package com.lazybody.xposed

import android.content.ContentResolver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import com.lazybody.bridge.CellEntry
import com.lazybody.bridge.EnvironmentConfig
import com.lazybody.bridge.EnvironmentConfigStore
import com.lazybody.bridge.StateBridge
import com.lazybody.bridge.SimulationState
import com.lazybody.bridge.WifiEntry
import com.lazybody.sensor.MotionSession
import com.lazybody.sensor.SensorEngine
import com.lazybody.tencent.TencentSdkHook
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 唯一的 Xposed 入口。合并了 HideMockHook.java / LocationHook.kt / GnssStatusHook.kt 的全部钩子。
 *
 * 设计来源：
 *  - AnyWhere v2.11.1 的双进程模拟架构（SharedPreferences + 2s TTL）
 *  - lazybody 原 HideMockHook 的全面 mock 标记清理
 *
 * 模块职责：
 *  1) Location 反检测 —— 抹除 isMock/isFromMockProvider、setMock、IPC 字段、Listener Proxy 包装
 *  2) GnssStatus 完整伪造 —— 合成星座 + 1s 定时器回调注入
 *  3) WiFi / Cell 环境 —— 支持真实、隐藏和伪造三种模式
 *  4) Provider 列表清理 + 非标准 Provider 禁用
 *  5) Settings.Secure "mock_location" 清零
 *  6) 跨进程状态桥读取 —— 为环境、传感器和腾讯定位 Hook 提供实时状态
 */
class XposedMain : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "LazyBodyHook"

        /** 白名单：自己和系统核心进程，避免误伤。 */
        private val WHITELIST = arrayListOf(
            "com.lazybody",
            "android",
            "com.android.systemui",
            "com.android.phone"
        )

        /** TTL 窗口，与 [StateBridge.TTL_NANOS] 一致。 */
        private const val TTL_NANOS = 2_000_000_000L

        // ====== Location 反射字段缓存（lazy 初始化）======
        @Volatile private var mockField: Field? = null
        @Volatile private var fieldResolved: Boolean = false

        private fun resolveMockField(): Field? {
            if (fieldResolved) return mockField
            synchronized(XposedMain::class.java) {
                if (fieldResolved) return mockField
                try {
                    // API 31+
                    mockField = Location::class.java.getDeclaredField("mMock")
                } catch (_: NoSuchFieldException) {
                    try {
                        // API < 31
                        mockField = Location::class.java.getDeclaredField("mIsFromMockProvider")
                    } catch (_: NoSuchFieldException) {}
                }
                mockField?.isAccessible = true
                fieldResolved = true
                return mockField
            }
        }

        /** 反射把 Location 中的 mock 字段置为 false。 */
        private fun clearMockField(location: Location) {
            val f = resolveMockField() ?: return
            try {
                f.set(location, false)
            } catch (_: Throwable) {}
        }

        /** 读取 App 侧写入的 SimulationState，并丢弃非激活或超过 TTL 的快照。 */
        fun readSimulationState(): SimulationState {
            return try {
                val sp = XSharedPreferences("com.lazybody", StateBridge.PREFS_NAME)
                sp.reload()
                val json = sp.getString(StateBridge.KEY_STATE, null)
                val state = SimulationState.fromJson(json)
                if (!state.isFresh(SystemClock.elapsedRealtimeNanos(), TTL_NANOS)) {
                    SimulationState.INACTIVE
                } else {
                    state
                }
            } catch (_: Throwable) {
                SimulationState.INACTIVE
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == null || lpparam.packageName in WHITELIST) return

        try {
            hookLocationMockClearing()
            hookLocationManager()
            hookSettingsSecure()
            hookProviderLists()
            hookWifi()
            hookTelephony()
            hookGnss()

            // ——— 新增：反检测增强 ———
            hookAppOps(lpparam.classLoader)
            hookSettingsGpsOn()
            hookHasSystemFeature(lpparam.classLoader)
            hookPackageManagerGetPermission(lpparam.classLoader)

            // ——— 新增：传感器/计步伪造 ———
            hookSensor()

            // ——— 新增：腾讯/高德 SDK 动态扫描 hook ———
            TencentSdkHook.tryHook(lpparam)
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    // =========================================================================
    // 1) Location 反检测：抹除一切 mock 痕迹
    // =========================================================================

    private fun hookLocationMockClearing() {
        XposedHelpers.findAndHookMethod(
            Location::class.java, "isFromMockProvider",
            XC_MethodReplacement.returnConstant(false)
        )

        if (Build.VERSION.SDK_INT >= 31) {
            try {
                XposedHelpers.findAndHookMethod(
                    Location::class.java, "isMock",
                    XC_MethodReplacement.returnConstant(false)
                )
            } catch (_: Throwable) {}
            try {
                XposedHelpers.findAndHookMethod(
                    Location::class.java, "setMock", Boolean::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) { param.args[0] = false }
                    }
                )
            } catch (_: Throwable) {}
        }

        try {
            XposedHelpers.findAndHookMethod(
                Location::class.java, "setIsFromMockProvider", Boolean::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { param.args[0] = false }
                }
            )
        } catch (_: Throwable) {}

        XposedHelpers.findAndHookMethod(
            Location::class.java, "getExtras",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.result as? Bundle)?.apply {
                        remove("mockLocation")
                        remove("isFromMockProvider")
                        remove("mockProvider")
                    }
                }
            }
        )

        try {
            XposedHelpers.findAndHookMethod(
                Location::class.java, "readFromParcel", android.os.Parcel::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        clearMockField(param.thisObject as Location)
                    }
                }
            )
        } catch (t: Throwable) { XposedBridge.log(t) }
    }

    private fun hookLocationManager() {
        XposedHelpers.findAndHookMethod(
            LocationManager::class.java, "getLastKnownLocation", String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.result as? Location)?.let { clearMockField(it) }
                }
            }
        )

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                XposedBridge.hookAllMethods(
                    LocationManager::class.java, "getCurrentLocation",
                    object : XC_MethodHook() {
                        @Suppress("UNCHECKED_CAST")
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            for (i in param.args.indices) {
                                if (param.args[i] is java.util.function.Consumer<*>) {
                                    val original = param.args[i] as java.util.function.Consumer<Location>
                                    param.args[i] = java.util.function.Consumer<Location> { loc ->
                                        loc?.let { clearMockField(it) }
                                        original.accept(loc)
                                    }
                                    break
                                }
                            }
                        }
                    }
                )
            } catch (t: Throwable) { XposedBridge.log(t) }
        }

        try {
            val listenerClass = Class.forName("android.location.LocationListener")
            val wrap = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    for (i in param.args.indices) {
                        val arg = param.args[i] ?: continue
                        if (listenerClass.isInstance(arg)) {
                            val original = arg
                            param.args[i] = java.lang.reflect.Proxy.newProxyInstance(
                                listenerClass.classLoader, arrayOf(listenerClass)
                            ) { _, method, args ->
                                if (args != null && args.isNotEmpty()
                                    && "onLocationChanged" == method.name
                                    && args[0] is Location
                                ) {
                                    clearMockField(args[0] as Location)
                                }
                                method.invoke(original, args)
                            }
                            break
                        }
                    }
                }
            }
            XposedBridge.hookAllMethods(LocationManager::class.java, "requestLocationUpdates", wrap)
            XposedBridge.hookAllMethods(LocationManager::class.java, "requestSingleUpdate", wrap)
        } catch (t: Throwable) { XposedBridge.log(t) }
    }

    private fun hookSettingsSecure() {
        XposedHelpers.findAndHookMethod(
            Settings.Secure::class.java, "getString", ContentResolver::class.java, String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if ("mock_location" == param.args[1]) param.setResult("0")
                }
            }
        )
    }

    private fun hookProviderLists() {
        val standard = arrayListOf("gps", "network", "passive", "fused")

        val cleaner = object : XC_MethodHook() {
            @Suppress("UNCHECKED_CAST")
            override fun afterHookedMethod(param: MethodHookParam) {
                val list = param.result as? List<String> ?: return
                val filtered = list.filter { it in standard }
                if (filtered.size != list.size) param.setResult(filtered)
            }
        }
        try { XposedHelpers.findAndHookMethod(LocationManager::class.java, "getProviders", Boolean::class.java, cleaner) } catch (_: Throwable) {}
        try { XposedHelpers.findAndHookMethod(LocationManager::class.java, "getAllProviders", cleaner) } catch (_: Throwable) {}

        XposedHelpers.findAndHookMethod(
            LocationManager::class.java, "isProviderEnabled", String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable()) return
                    val provider = param.args[0] as? String ?: return
                    if (provider !in standard) param.setResult(false)
                }
            }
        )
    }

    // =========================================================================
    // 2) WiFi 伪造：三模式 passthrough / hide / forge
    // =========================================================================

    private fun hookWifi() {
        hookWifiGetScanResults()
        hookWifiGetConnectionInfo()
    }

    private fun hookWifiGetScanResults() {
        try {
            XposedHelpers.findAndHookMethod(
                WifiManager::class.java, "getScanResults",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cfg = readEnvironmentConfig()
                        when (cfg.wifiMode) {
                            EnvironmentConfig.Mode.PASSTHROUGH -> return
                            EnvironmentConfig.Mode.HIDE -> param.setResult(ArrayList<Any>())
                            EnvironmentConfig.Mode.FORGE -> {
                                val list = ArrayList<Any>()
                                for (entry in cfg.wifiNetworks) {
                                    buildFakeScanResult(entry)?.let { list.add(it) }
                                }
                                param.setResult(list)
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun hookWifiGetConnectionInfo() {
        try {
            XposedHelpers.findAndHookMethod(
                WifiManager::class.java, "getConnectionInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cfg = readEnvironmentConfig()
                        when (cfg.wifiMode) {
                            EnvironmentConfig.Mode.HIDE -> {
                                param.setResult(null)
                            }
                            EnvironmentConfig.Mode.FORGE -> {
                                val bssid = cfg.wifiConnectedBssid.ifBlank {
                                    cfg.wifiNetworks.firstOrNull()?.bssid
                                }
                                if (bssid != null) {
                                    val info = buildFakeWifiInfo(
                                        bssid = bssid,
                                        ssid = cfg.wifiNetworks.firstOrNull()?.ssid ?: "",
                                        level = cfg.wifiNetworks.firstOrNull()?.level ?: -50,
                                        frequency = cfg.wifiNetworks.firstOrNull()?.frequencyMHz ?: 2412
                                    )
                                    if (info != null) param.setResult(info)
                                }
                            }
                            EnvironmentConfig.Mode.PASSTHROUGH -> return
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // 2.1) Telephony 伪造：三模式 passthrough / hide / forge
    // =========================================================================

    private fun hookTelephony() {
        hookGetCellLocation()
        hookGetAllCellInfo()
    }

    private fun hookGetCellLocation() {
        try {
            XposedHelpers.findAndHookMethod(
                TelephonyManager::class.java, "getCellLocation",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cfg = readEnvironmentConfig()
                        when (cfg.cellMode) {
                            EnvironmentConfig.Mode.HIDE -> param.setResult(null)
                            EnvironmentConfig.Mode.FORGE -> {
                                val primary = cfg.cellTowers.firstOrNull { it.primary }
                                    ?: cfg.cellTowers.firstOrNull()
                                if (primary != null) {
                                    val loc = buildFakeCellLocation(primary)
                                    if (loc != null) param.setResult(loc)
                                }
                            }
                            EnvironmentConfig.Mode.PASSTHROUGH -> return
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    private fun hookGetAllCellInfo() {
        try {
            XposedHelpers.findAndHookMethod(
                TelephonyManager::class.java, "getAllCellInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cfg = readEnvironmentConfig()
                        when (cfg.cellMode) {
                            EnvironmentConfig.Mode.HIDE -> param.setResult(ArrayList<Any>())
                            EnvironmentConfig.Mode.FORGE -> {
                                val list = ArrayList<CellInfo>()
                                for (entry in cfg.cellTowers) {
                                    buildFakeCellInfo(entry)?.let { list.add(it) }
                                }
                                param.setResult(list)
                            }
                            EnvironmentConfig.Mode.PASSTHROUGH -> return
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // 2.2) 反射构造 ScanResult / WifiInfo / CellLocation / CellInfo
    // =========================================================================

    /**
     * 反射构造 android.net.wifi.ScanResult。
     * ScanResult 无公开构造器，用 Class.forName 找全参构造或 Builder。
     */
    private fun buildFakeScanResult(entry: WifiEntry): Any? {
        return try {
            val cls = Class.forName("android.net.wifi.ScanResult")
            // 1) 优先 Builder 模式（Android 13+）
            try {
                val builder = XposedHelpers.newInstance(
                    Class.forName("android.net.wifi.ScanResult\$Builder")
                )
                XposedHelpers.callMethod(builder, "setBssid", entry.bssid)
                XposedHelpers.callMethod(builder, "setSsid", entry.ssid)
                XposedHelpers.callMethod(builder, "setCapabilities", entry.capabilities)
                XposedHelpers.callMethod(builder, "setLevel", entry.level)
                XposedHelpers.callMethod(builder, "setFrequency", entry.frequencyMHz)
                XposedHelpers.callMethod(builder, "setTimestamp",
                    if (entry.timestampUs > 0) entry.timestampUs else SystemClock.elapsedRealtimeNanos() / 1000L)
                return XposedHelpers.callMethod(builder, "build")
            } catch (_: Throwable) { /* 尝试老版本构造 */ }

            // 2) 反射私有构造 / 字段赋值
            val result = XposedHelpers.newInstance(cls)
            setFieldSafe(result, "BSSID", entry.bssid)
            setFieldSafe(result, "SSID", entry.ssid)
            setFieldSafe(result, "capabilities", entry.capabilities)
            setFieldSafe(result, "level", entry.level)
            setFieldSafe(result, "frequency", entry.frequencyMHz)
            setFieldSafe(result, "timestamp",
                if (entry.timestampUs > 0) entry.timestampUs else SystemClock.elapsedRealtimeNanos() / 1000L)
            result
        } catch (_: Throwable) { null }
    }

    /**
     * 反射构造 android.net.wifi.WifiInfo（表示当前连接的 SSID）。
     */
    private fun buildFakeWifiInfo(
        bssid: String,
        ssid: String,
        level: Int,
        frequency: Int
    ): Any? {
        return try {
            val builderClass = Class.forName("android.net.wifi.WifiInfo\$Builder")
            val builder = XposedHelpers.newInstance(builderClass)
            try {
                // Android 11+ 有 setSsid / setBssid
                XposedHelpers.callMethod(builder, "setSsid", ssid)
            } catch (_: Throwable) {}
            try {
                XposedHelpers.callMethod(builder, "setBssid", bssid)
            } catch (_: Throwable) {}
            try {
                XposedHelpers.callMethod(builder, "setRssi", level)
            } catch (_: Throwable) {}
            try {
                XposedHelpers.callMethod(builder, "setFrequency", frequency)
            } catch (_: Throwable) {}
            try {
                XposedHelpers.callMethod(builder, "setMacAddress", bssid)
            } catch (_: Throwable) {}
            try {
                XposedHelpers.callMethod(builder, "setNetworkId", 0)
            } catch (_: Throwable) {}
            XposedHelpers.callMethod(builder, "build")
        } catch (_: Throwable) { null }
    }

    /**
     * 反射构造 CellLocation（android.telephony.gsm）。已废弃但旧 App 还在调用。
     */
    private fun buildFakeCellLocation(entry: CellEntry): Any? {
        return try {
            val cls = Class.forName("android.telephony.gsm.GsmCellLocation")
            val loc = XposedHelpers.newInstance(cls)
            setFieldSafe(loc, "mLac", entry.tac)
            setFieldSafe(loc, "mCid", entry.cid)
            setFieldSafe(loc, "mPsc", entry.pci)
            loc
        } catch (_: Throwable) { null }
    }

    /**
     * 反射构造 CellInfo（抽象类，构造 CellInfoLte / CellInfoWcdma 等子类）。
     * 这里按 rat 字符串选对应子类，找不到就用 CellInfoLte 兜底。
     */
    private fun buildFakeCellInfo(entry: CellEntry): CellInfo? {
        val targetName = when (entry.rat.uppercase()) {
            "LTE" -> "android.telephony.CellInfoLte"
            "NR", "5G" -> "android.telephony.CellInfoNr"
            "WCDMA", "UMTS", "3G" -> "android.telephony.CellInfoWcdma"
            "GSM", "2G" -> "android.telephony.CellInfoGsm"
            "TDSCDMA", "TD-SCDMA" -> "android.telephony.CellInfoTdscdma"
            else -> "android.telephony.CellInfoLte"
        }
        return try {
            val cls = Class.forName(targetName)
            val info = XposedHelpers.newInstance(cls)
            // 公共字段 CellIdentity / CellSignalStrength 由父类持有
            setFieldSafe(info, "mRegistered", true)
            // CellIdentity 子类字段
            setFieldSafe(info, "mMcc", entry.mcc)
            setFieldSafe(info, "mMnc", entry.mnc)
            setFieldSafe(info, "mTac", entry.tac)
            setFieldSafe(info, "mPci", entry.pci)
            // CellInfoLte.CellIdentityLte 特有
            setFieldSafe(info, "mCi", entry.cid)
            // CellInfoGsm / Wcdma / Tdscdma 用 mCid / mLac 等，兜底也填一下
            setFieldSafe(info, "mCid", entry.cid)
            setFieldSafe(info, "mLac", entry.tac)
            // CellSignalStrength 子类（各版本字段名不同，失败就忽略）
            setFieldSafe(info, "mDbm", entry.signal)
            setFieldSafe(info, "mRssi", entry.signal)
            setFieldSafe(info, "mRsrp", entry.signal)
            setFieldSafe(info, "mRsrq", entry.signal - 10)
            info as? CellInfo
        } catch (_: Throwable) { null }
    }

    // =========================================================================
    // 2.3) 反射工具：带 try/catch 的字段赋值
    // =========================================================================

    /** 反射给 obj 的 name 字段赋值，找不到就静默忽略。 */
    private fun setFieldSafe(obj: Any, name: String, value: Any?) {
        try {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            // 类型兜底：value 是 Int 但字段是 Int 就直接设；如果是 Long 字段则转
            f.set(obj, value)
        } catch (_: Throwable) {}
        // 父类再试一次
        try {
            val sup = obj.javaClass.superclass ?: return
            val f = sup.getDeclaredField(name)
            f.isAccessible = true
            f.set(obj, value)
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // 2.4) 环境配置读取（从 App 的 SharedPreferences）
    // =========================================================================

    private fun readEnvironmentConfig(): EnvironmentConfig {
        return try {
            val sp = XSharedPreferences("com.lazybody", StateBridge.PREFS_NAME)
            sp.reload()
            val json = sp.getString(EnvironmentConfigStore.KEY_CONFIG, null)
            if (json.isNullOrBlank()) {
                // 兼容：老版本可能只写了 wifi_mode / cell_mode 独立键
                val wifiModeName = sp.getString(EnvironmentConfigStore.KEY_WIFI_MODE, "PASSTHROUGH")
                val cellModeName = sp.getString(EnvironmentConfigStore.KEY_CELL_MODE, "PASSTHROUGH")
                EnvironmentConfig(
                    wifiMode = try { EnvironmentConfig.Mode.valueOf(wifiModeName!!) } catch (_: Throwable) { EnvironmentConfig.Mode.PASSTHROUGH },
                    cellMode = try { EnvironmentConfig.Mode.valueOf(cellModeName!!) } catch (_: Throwable) { EnvironmentConfig.Mode.PASSTHROUGH }
                )
            } else {
                EnvironmentConfig.fromJson(json)
            }
        } catch (_: Throwable) { EnvironmentConfig.DEFAULT }
    }

    // =========================================================================
    // 3) GnssStatus 完整伪造：GpsEngine + GnssStatus.Builder.addSatellite
    // =========================================================================

    private val gpsEngine by lazy { com.lazybody.gnss.GpsEngine() }

    private fun hookGnss() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val callbacks = CopyOnWriteArrayList<Any>()

        try {
            val callbackClass = Class.forName("android.location.GnssStatus\$Callback")
            val registerHook = object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? {
                    val cb = param.args.firstOrNull { it != null && callbackClass.isInstance(it) }
                    if (cb != null && cb !in callbacks) callbacks.add(cb)
                    return true
                }
            }
            // 同时覆盖 Callback、Handler 和 Android 11+ 的 Executor 重载。
            XposedBridge.hookAllMethods(
                LocationManager::class.java, "registerGnssStatusCallback", registerHook
            )

            try {
                XposedHelpers.findAndHookMethod(LocationManager::class.java, "unregisterGnssStatusCallback", callbackClass,
                    object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any? {
                            callbacks.remove(param.args[0])
                            return null
                        }
                    })
            } catch (_: Throwable) {}

            val handler = Handler(Looper.getMainLooper())
            val tick = object : Runnable {
                override fun run() {
                    try {
                        if (callbacks.isNotEmpty()) {
                            val status = buildFakeGnssStatus()
                            if (status != null) {
                                for (cb in callbacks) {
                                    XposedHelpers.callMethod(cb, "onSatelliteStatusChanged", status)
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                    handler.postDelayed(this, 1000)
                }
            }
            handler.post(tick)

        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    /** 按系统版本构造 GnssStatus，并注入 GpsEngine 生成的星座。 */
    private fun buildFakeGnssStatus(): android.location.GnssStatus? {
        val satellites = try { gpsEngine.snapshot() } catch (_: Throwable) { return null }
        if (satellites.isEmpty()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            buildModernGnssStatus(satellites)
        } else {
            buildLegacyGnssStatus(satellites)
        }
    }

    /** Android 12+ 使用公开 Builder，避免依赖已变化的隐藏字段。 */
    private fun buildModernGnssStatus(
        satellites: List<com.lazybody.gnss.GnssSatellite>
    ): android.location.GnssStatus? {
        return try {
            val builder = android.location.GnssStatus.Builder()
            for (s in satellites) {
                XposedHelpers.callMethod(
                    builder, "addSatellite",
                    s.constellationType, s.svid, s.cn0DbHz,
                    s.elevationDegrees, s.azimuthDegrees, s.usedInFix
                )
            }
            builder.build()
        } catch (_: Throwable) {
            null
        }
    }

    /** Android 7—11 通过隐藏构造器和 setSatellite 填充状态。 */
    private fun buildLegacyGnssStatus(
        satellites: List<com.lazybody.gnss.GnssSatellite>
    ): android.location.GnssStatus? {
        return try {
            val constructor = android.location.GnssStatus::class.java.getDeclaredConstructor()
            constructor.isAccessible = true
            val status = constructor.newInstance()
            var count = 0
            for (satellite in satellites) {
                if (setLegacySatellite(status, count, satellite)) count++
            }
            if (count == 0) return null
            XposedHelpers.callMethod(status, "setSatelliteCount", count)
            status
        } catch (_: Throwable) {
            null
        }
    }

    private fun setLegacySatellite(
        status: android.location.GnssStatus,
        index: Int,
        satellite: com.lazybody.gnss.GnssSatellite
    ): Boolean {
        val methods = android.location.GnssStatus::class.java.declaredMethods
            .filter { it.name == "setSatellite" }
            .sortedBy { it.parameterTypes.size }
        for (method in methods) {
            val size = method.parameterTypes.size
            if (size != 9 && size != 11 && size != 12) continue
            val args = arrayListOf<Any?>(
                index, satellite.svid, satellite.constellationType, satellite.cn0DbHz,
                satellite.elevationDegrees, satellite.azimuthDegrees,
                satellite.hasEphemeris, satellite.hasAlmanac, satellite.usedInFix
            )
            when {
                size == 11 -> {
                    args.add(true) // hasCarrierFrequency
                    args.add(satellite.carrierFrequencyHz)
                }
                size == 12 && method.parameterTypes[9] == Float::class.javaPrimitiveType -> {
                    args.add(satellite.cn0DbHz) // basebandCn0DbHz
                    args.add(true)
                    args.add(satellite.carrierFrequencyHz)
                }
                size == 12 -> {
                    args.add(true)
                    args.add(satellite.carrierFrequencyHz)
                    args.add(satellite.cn0DbHz)
                }
            }
            try {
                method.isAccessible = true
                method.invoke(status, *args.toArray())
                return true
            } catch (_: Throwable) {}
        }
        return false
    }

    // =========================================================================
    // 4) 反检测增强 —— AppOps / Settings.Secure.GPS_ON / hasSystemFeature / permission
    // =========================================================================

    private fun hookAppOps(cl: ClassLoader) {
        val appOpsClass = try { Class.forName("android.app.AppOpsManager", true, cl) } catch (_: Throwable) { return }

        val allowOps = setOf(
            "android:gps", "android:fine_location", "android:coarse_location",
            "android:any_location", "android:background_location",
            "android:activity_recognition"
        )

        fun shouldAllow(op: String?): Boolean {
            if (op == null) return false
            return op in allowOps || op.contains("location") || op.contains("gps") || op.contains("agnss")
        }

        listOf("checkOpNoThrow", "checkOp", "noteOpNoThrow").forEach { methodName ->
            try {
                XposedBridge.hookAllMethods(appOpsClass, methodName, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val op = param.args.firstOrNull() as? String ?: return
                        if (shouldAllow(op)) param.setResult(0)  // MODE_ALLOWED
                    }
                })
            } catch (_: Throwable) {}
        }
    }

    /** GPS_ON / LOCATION_MODE / ASSISTED_GPS_ENABLED 等 key 强制返回 1 表示已开启。 */
    private fun hookSettingsGpsOn() {
        val gpsKeys = setOf("gps_on", "location_providers_allowed", "assisted_gps_enabled")
        val locKeys = setOf("access_fine_location", "access_coarse_location", "location_mode", "enable_agnss")
        val allKeys = gpsKeys + locKeys

        try {
            XposedHelpers.findAndHookMethod(
                Settings.Secure::class.java, "getString",
                ContentResolver::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        when {
                            key == "mock_location" -> param.setResult("0")
                            key in allKeys && param.result == null -> param.setResult("1")
                        }
                    }
                }
            )
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod(
                Settings.Secure::class.java, "getInt",
                ContentResolver::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        when {
                            key == "mock_location" -> param.setResult(0)
                            key in allKeys -> param.setResult(1)
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    /** 让 FEATURE_LOCATION / SENSOR_STEP_COUNTER / SENSOR_STEP_DETECTOR / ACCELERATOR 等返回 true。 */
    private fun hookHasSystemFeature(cl: ClassLoader) {
        val pmClass = try {
            Class.forName("android.app.ApplicationPackageManager", true, cl)
        } catch (_: Throwable) {
            try { Class.forName("android.content.pm.ApplicationPackageManager", true, cl) }
            catch (_: Throwable) { return }
        }
        val features = setOf(
            "android.hardware.location", "android.hardware.location.gps",
            "android.hardware.location.network",
            "android.hardware.sensor.stepcounter", "android.hardware.sensor.stepdetector",
            "android.hardware.sensor.accelerometer", "android.hardware.sensor.compass"
        )
        runCatching {
            for (method in pmClass.declaredMethods) {
                if (method.parameterTypes.size != 1
                    || method.parameterTypes[0] != String::class.java
                    || method.returnType != Boolean::class.javaPrimitiveType
                    || method.name != "hasSystemFeature"
                ) continue
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val feature = param.args[0] as? String ?: return
                        if (feature in features) param.setResult(true)
                    }
                })
                return
            }
        }
    }

    /** 让 ACCESS_FINE/COARSE/LOCATION_BACKGROUND/ACTIVITY_RECOGNITION 权限返回 PERMISSION_GRANTED(0)。 */
    private fun hookPackageManagerGetPermission(cl: ClassLoader) {
        val pmClass = try {
            Class.forName("android.app.ApplicationPackageManager", true, cl)
        } catch (_: Throwable) { return }
        val perms = setOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.ACTIVITY_RECOGNITION"
        )
        runCatching {
            for (method in pmClass.declaredMethods) {
                if (method.name != "checkPermission") continue
                val params = method.parameterTypes
                if (params.size == 2 && params.all { it == String::class.java }) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val perm = param.args[1] as? String ?: return
                            if (perm in perms) param.setResult(0)
                        }
                    })
                    return
                }
            }
        }
    }

    // =========================================================================
    // 5) 传感器伪造 —— 反射构造假 Sensor + tick 注入 SensorEvent + 恢复原始
    // =========================================================================

    /** 我们伪造的所有 Sensor 类型集合。 */
    private val FAKE_SENSOR_TYPES = setOf(
        Sensor.TYPE_STEP_COUNTER,
        Sensor.TYPE_STEP_DETECTOR,
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_GRAVITY,
        Sensor.TYPE_ROTATION_VECTOR
    )

    private val fakeSensorsCache = HashMap<Int, Sensor>()
    private val registrations = CopyOnWriteArrayList<SensorReg>()
    private val motionSession = MotionSession()
    private val accelEngine = com.lazybody.sensor.AccelWaveformEngine()
    private var sensorHandler: Handler? = null
    private var sensorTickPosted = false

    private data class SensorReg(
        val listener: SensorEventListener,
        val fakeSensor: Sensor,
        val targetType: Int,
        /** 原始 registerListener 实际使用的 Sensor，注销时必须保持一致。 */
        val registeredSensor: Sensor
    )

    @Suppress("PrivateApi")
    private fun hookSensor() {
        val sensorManagerClass = SensorManager::class.java

        // 5.1 getDefaultSensor / getSensorList：让 App 看见我们的假 Sensor（插在最前）
        try {
            XposedBridge.hookAllMethods(sensorManagerClass, "getDefaultSensor", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val type = param.args.firstOrNull() as? Int ?: return
                    if (type in FAKE_SENSOR_TYPES) param.setResult(getOrBuildFakeSensor(type))
                }
            })
        } catch (_: Throwable) {}

        try {
            XposedBridge.hookAllMethods(sensorManagerClass, "getSensorList", object : XC_MethodHook() {
                @Suppress("UNCHECKED_CAST")
                override fun afterHookedMethod(param: MethodHookParam) {
                    val type = param.args.firstOrNull() as? Int ?: return
                    if (type !in FAKE_SENSOR_TYPES) return
                    val list = (param.result as? MutableList<Sensor>)
                        ?: (param.result as? List<Sensor>)?.toMutableList()
                        ?: ArrayList()
                    val fake = getOrBuildFakeSensor(type) ?: return
                    if (fake !in list) list.add(0, fake)
                    param.setResult(list)
                }
            })
        } catch (_: Throwable) {}

        // 5.2 registerListener：用原始方法注册物理 Sensor，合成事件单独分发。
        try {
            XposedBridge.hookAllMethods(sensorManagerClass, "registerListener", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val listener = param.args.firstOrNull() as? SensorEventListener ?: return
                    val sensor = param.args.getOrNull(1) as? Sensor ?: return
                    val type = runCatching { sensor.type }.getOrNull() ?: return
                    if (type !in FAKE_SENSOR_TYPES) return
                    val originalMethod = param.method as? Method ?: return
                    val fake = getOrBuildFakeSensor(type) ?: return
                    val manager = param.thisObject as SensorManager
                    val realSensor = runCatching {
                        SensorEngineReflection.findRealSensor(manager, type)
                    }.getOrNull()
                    val registeredSensor = realSensor ?: sensor
                    val originalArgs = param.args.copyOf()
                    originalArgs[1] = registeredSensor

                    runCatching {
                        SensorEngineReflection.invokeOriginal(originalMethod, manager, originalArgs)
                    }
                    registrations.add(SensorReg(listener, fake, type, registeredSensor))
                    param.result = true
                    ensureSensorTickRunning()
                }
            })
        } catch (_: Throwable) {}

        // 5.3 unregisterListener：只注销此前实际注册的 Sensor，阻止 Hook 方法再次执行。
        try {
            XposedBridge.hookAllMethods(sensorManagerClass, "unregisterListener", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val listener = param.args.firstOrNull() as? SensorEventListener ?: return
                    val sensor = param.args.getOrNull(1) as? Sensor
                    val sensorType = runCatching { sensor?.type }.getOrNull()
                    val targets = registrations.filter {
                        it.listener === listener && (sensor == null || it.targetType == sensorType)
                    }
                    if (targets.isEmpty()) return

                    for (reg in targets) {
                        runCatching {
                            SensorEngineReflection.invokeOriginalUnregisterListener(
                                param.thisObject, reg.listener, reg.registeredSensor
                            )
                        }
                    }
                    registrations.removeAll(targets)
                    param.result = null
                    if (registrations.isEmpty()) {
                        motionSession.reset()
                        accelEngine.reset()
                    }
                }
            })
        } catch (_: Throwable) {}
    }

    /** 反射构造指定类型的假 Sensor。 */
    private fun getOrBuildFakeSensor(type: Int): Sensor? {
        fakeSensorsCache[type]?.let { return it }
        return SensorEngine.buildFakeSensor(type)
            .also { if (it != null) fakeSensorsCache[type] = it }
    }

    private fun ensureSensorTickRunning() {
        if (sensorTickPosted) return
        val looper = runCatching { Looper.getMainLooper() }.getOrNull() ?: return
        sensorHandler = sensorHandler ?: Handler(looper)
        sensorTickPosted = true

        val tick = object : Runnable {
            private var lastNanos = SystemClock.elapsedRealtimeNanos()

            override fun run() {
                sensorTickPosted = false
                val nowNs = SystemClock.elapsedRealtimeNanos()
                val dtSec = ((nowNs - lastNanos).coerceAtLeast(0L)).toDouble() / 1_000_000_000.0
                lastNanos = nowNs

                val state = readSimulationState()
                val speed = state.speed.toDouble()
                motionSession.speedMps = speed
                val cadence = SensorEngine.cadenceFromSpeed(speed)

                // 1. 步数 tick
                val steps = motionSession.advanceTick()

                // 2. 三轴加速度波形（仅 accel/gravity/rotation 类型有，step 跳过）
                val xyz = accelEngine.nextFrame(cadence, dtSec, speed, nowNs)

                for (reg in registrations) {
                    try {
                        when (reg.targetType) {
                            Sensor.TYPE_STEP_COUNTER -> {
                                val e = SensorEngine.buildSensorEvent(
                                    floatArrayOf(motionSession.totalSteps.toFloat()), reg.fakeSensor, nowNs
                                )
                                e?.let { reg.listener.onSensorChanged(it) }
                            }
                            Sensor.TYPE_STEP_DETECTOR -> {
                                repeat(steps.toInt()) {
                                    SensorEngine.buildSensorEvent(floatArrayOf(1.0f), reg.fakeSensor, nowNs)
                                        ?.let { reg.listener.onSensorChanged(it) }
                                }
                            }
                            Sensor.TYPE_ACCELEROMETER -> {
                                // 标准 Android ACCELEROMETER 的 values[0..2] = xyz (m/s²)
                                SensorEngine.buildSensorEvent(xyz.copyOf(3), reg.fakeSensor, nowNs)
                                    ?.let { reg.listener.onSensorChanged(it) }
                            }
                            Sensor.TYPE_GRAVITY -> {
                                // Android GRAVITY values[0..2] = g 向量
                                SensorEngine.buildSensorEvent(xyz.copyOf(3), reg.fakeSensor, nowNs)
                                    ?.let { reg.listener.onSensorChanged(it) }
                            }
                            Sensor.TYPE_ROTATION_VECTOR -> {
                                // Android 方位角绕 Z 轴旋转，负号用于匹配传感器坐标系。
                                val halfAngle = -Math.toRadians(state.bearing.toDouble()) / 2.0
                                val rotation = floatArrayOf(
                                    0f, 0f, Math.sin(halfAngle).toFloat(), Math.cos(halfAngle).toFloat()
                                )
                                SensorEngine.buildSensorEvent(rotation, reg.fakeSensor, nowNs)
                                    ?.let { reg.listener.onSensorChanged(it) }
                            }
                        }
                    } catch (_: Throwable) {}
                }

                if (registrations.isNotEmpty()) {
                    sensorTickPosted = true
                    sensorHandler?.postDelayed(this, motionSession.tickIntervalMs)
                }
            }
        }
        sensorHandler?.post(tick)
    }

    // =========================================================================
    // 原始 SensorManager 方法调用，避免同名 Hook 重入。
    // =========================================================================

    private object SensorEngineReflection {

        fun findRealSensor(mgr: SensorManager, type: Int): Sensor? {
            val method = SensorManager::class.java.getDeclaredMethod(
                "getDefaultSensor", Int::class.javaPrimitiveType
            )
            return XposedBridge.invokeOriginalMethod(method, mgr, arrayOf(type)) as? Sensor
        }

        fun invokeOriginal(method: Method, receiver: Any, args: Array<Any?>): Any? =
            XposedBridge.invokeOriginalMethod(method, receiver, args)

        fun invokeOriginalUnregisterListener(
            mgr: Any,
            listener: SensorEventListener,
            sensor: Sensor
        ) {
            val method = SensorManager::class.java.getDeclaredMethod(
                "unregisterListener", SensorEventListener::class.java, Sensor::class.java
            )
            XposedBridge.invokeOriginalMethod(method, mgr, arrayOf(listener, sensor))
        }
    }
}
