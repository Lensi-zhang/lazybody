package com.lazybody.xposed

import android.content.ContentResolver
import android.content.Context
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
 *  2) GnssStatus 完整伪造 —— 7 颗 GPS 卫星 + 1s 定时器回调注入
 *  3) WiFi / Cell 屏蔽 —— scanResults / getAllCellInfo 返回空
 *  4) Provider 列表清理 + 非标准 Provider 禁用
 *  5) Settings.Secure "mock_location" 清零
 *  6) 跨进程状态桥读取（readSimulationState）——当前仅调试用途，后续让 hook 感知状态做动态决策
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

        /**
         * 尝试读取 App 侧写入的 SimulationState 并检查 TTL 新鲜度。
         * 当前版本仅调试用途；后续让 hook 根据状态做动态决策（例如 fake_wifi 开关）。
         */
        fun readSimulationState(context: Context?): SimulationState {
            context ?: return SimulationState.INACTIVE
            return try {
                val sp = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
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
            hookSensor(lpparam.classLoader)

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
                        val cfg = readEnvironmentConfig(getAppContext())
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
                        val cfg = readEnvironmentConfig(getAppContext())
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
                        val cfg = readEnvironmentConfig(getAppContext())
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
                        val cfg = readEnvironmentConfig(getAppContext())
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

    /** 懒加载的 context 缓存。Xposed hook 时 context 可能还没完全初始化， */
    private var cachedAppContext: Context? = null

    private fun getAppContext(): Context? {
        cachedAppContext?.let { return it }
        try {
            val appClass = Class.forName("android.app.ActivityThread")
            val currentApp = XposedHelpers.callStaticMethod(appClass, "currentApplication") as? Context
            currentApp?.let { cachedAppContext = it }
            return currentApp
        } catch (_: Throwable) { return null }
    }

    private fun readEnvironmentConfig(context: Context?): EnvironmentConfig {
        context ?: return EnvironmentConfig.DEFAULT
        return try {
            val sp = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
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
                    val cb = param.args[0]
                    if (cb != null && cb !in callbacks) callbacks.add(cb)
                    return true
                }
            }
            try { XposedHelpers.findAndHookMethod(LocationManager::class.java, "registerGnssStatusCallback", callbackClass, registerHook) } catch (_: Throwable) {}
            try { XposedHelpers.findAndHookMethod(LocationManager::class.java, "registerGnssStatusCallback", callbackClass, Handler::class.java, registerHook) } catch (_: Throwable) {}

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

    /**
     * 用 GnssStatus.Builder.addSatellite 构造真实的星座对象。
     *
     * 策略：优先用反射调用 addSatellite，参数类型匹配让 Android 自己选
     * 重载；从 6 参数（API 24）逐步升级到 API 37+ 的完整参数集，直到成功。
     *
     * 签名（Android 11, API 30）:
     *   addSatellite(int constellationType, int svid, float cn0DbHz,
     *       float elevationDegrees, float azimuthDegrees,
     *       boolean usedInFix)
     *
     * 签名（Android 10+, API 29）: 同上 + signalStrength/snr/carrier/baseband 4 个
     * 签名（Android 15+）: 再追加 2 个参数。
     */
    private fun buildFakeGnssStatus(): android.location.GnssStatus? {
        val satellites = try { gpsEngine.snapshot() } catch (_: Throwable) { return null }
        return try {
            val builder = android.location.GnssStatus.Builder()
            var anySuccess = true
            for (s in satellites) {
                if (!tryAddSatellite(builder, s)) anySuccess = false
            }
            if (!anySuccess && satellites.isNotEmpty()) {
                // 至少要添加一颗，否则 build() 可能抛异常
                tryAddSatellite(builder, satellites.first())
            }
            builder.build()
        } catch (t: Throwable) {
            XposedBridge.log(t)
            null
        }
    }

    /**
     * 反射调 GnssStatus.Builder.addSatellite。
     * 依次尝试参数数量从少到多的重载，直到成功。
     *
     * Android 参数顺序坑：官方 API 是 (constellationType, svid, cn0, elev, azim, usedInFix, ...)
     * 我之前在 Kotlin 里把 constellation 和 svid 的顺序搞反了——Android 把 constellationType 放第一位。
     */
    private fun tryAddSatellite(builder: Any, s: com.lazybody.gnss.GnssSatellite): Boolean {
        // 参数列表按 Android 官方顺序：constellationType 首位
        val base = arrayOf(
            s.constellationType,  // int   constellationType
            s.svid,               // int   svid
            s.cn0DbHz,            // float cn0DbHz
            s.elevationDegrees,   // float elevationDegrees
            s.azimuthDegrees,     // float azimuthDegrees
            s.usedInFix           // boolean usedInFix
        )
        val extraA = arrayOf(
            s.signalStrengthDbm,        // float signalStrengthDbm
            0f,                         // float basebandCn0DbHz
            s.signalToNoiseRatioDbHz,   // float snrDbHz / signalStrength() 各版本不同
            s.carrierFrequencyHz        // float carrierFrequencyHz
        )

        // 先试 10 参数（API 29+）
        val tenArgs = base + extraA
        try {
            XposedHelpers.callMethod(builder, "addSatellite", *tenArgs)
            return true
        } catch (_: Throwable) {}

        // 再试 6 参数（API 24+）
        try {
            XposedHelpers.callMethod(builder, "addSatellite", *base)
            return true
        } catch (_: Throwable) {}

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
        /** 真实 Sensor（反射恢复原始调用时用）——仅对 step 类型有意义，其他类型传 null。 */
        val realSensor: Sensor?
    )

    @Suppress("PrivateApi")
    private fun hookSensor(cl: ClassLoader) {
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

        // 5.2 registerListener：拦截 → 换假 Sensor + 恢复原始 + 登记
        try {
            XposedBridge.hookAllMethods(sensorManagerClass, "registerListener", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val listener = param.args.firstOrNull() as? SensorEventListener ?: return
                    val sensor = param.args.getOrNull(1) as? Sensor ?: return
                    val type = runCatching { sensor.type }.getOrNull() ?: return
                    if (type !in FAKE_SENSOR_TYPES) return

                    val fake = getOrBuildFakeSensor(type) ?: return
                    param.args[1] = fake

                    // 反射找真 Sensor（用于停仿真时恢复）
                    val realSensor = runCatching {
                        SensorEngineReflection.findRealSensor(param.thisObject as SensorManager, type)
                    }.getOrNull()

                    // 同时偷偷用原始调用器注册真 Sensor —— 这样我们既能控制假回调，
                    // 又不会让真实物理事件被"屏蔽"掉（避免触发传感器计数/功耗异常）
                    runCatching {
                        SensorEngineReflection.invokeOriginalRegisterListener(
                            param.thisObject, listener, realSensor ?: sensor, param.args.getOrNull(3) as? Handler
                        )
                    }

                    registrations.add(SensorReg(listener, fake, type, realSensor))
                    ensureSensorTickRunning()
                }
            })
        } catch (_: Throwable) {}

        // 5.3 unregisterListener：注销 + 反射恢复原始 unregister
        try {
            XposedBridge.hookAllMethods(sensorManagerClass, "unregisterListener", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val listener = param.args.firstOrNull() as? SensorEventListener ?: return
                    val targets = registrations.filter { it.listener === listener }
                    val iter = registrations.iterator()
                    while (iter.hasNext()) {
                        if (iter.next().listener === listener) iter.remove()
                    }
                    // 恢复原始 unregister
                    for (reg in targets) {
                        runCatching {
                            SensorEngineReflection.invokeOriginalUnregisterListener(
                                param.thisObject, reg.listener, reg.realSensor ?: reg.fakeSensor
                            )
                        }
                    }
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

                val speed = currentFakeSpeed()
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
                                // Android ROTATION_VECTOR values[0..3] = quaternion (x,y,z,w)
                                // 我们简化：输出单位四元数（identity 旋转）
                                SensorEngine.buildSensorEvent(floatArrayOf(0f, 0f, 0f, 1f), reg.fakeSensor, nowNs)
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

    /** 读 App 侧 SimulationState.speed，决定步频/加速度强度。 */
    private fun currentFakeSpeed(): Double {
        val ctx = getAppContext() ?: return 0.0
        return try {
            val sp = android.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
            val json = sp.getString(StateBridge.KEY_STATE, null)
            val state = SimulationState.fromJson(json)
            if (state.isFresh(SystemClock.elapsedRealtimeNanos(), StateBridge.TTL_NANOS))
                state.speed.toDouble() else 0.0
        } catch (_: Throwable) { 0.0 }
    }

    // =========================================================================
    // 反射工具：用 XposedHelpers.callMethod 调原始 SensorManager（恢复物理传感器）
    // =========================================================================

    private object SensorEngineReflection {

        fun findRealSensor(mgr: SensorManager, type: Int): Sensor? {
            return runCatching {
                for (method in mgr.javaClass.declaredMethods) {
                    if (method.name == "getDefaultSensor"
                        && method.parameterTypes.size == 1
                        && method.parameterTypes[0] == Int::class.javaPrimitiveType
                    ) {
                        method.isAccessible = true
                        method.invoke(mgr, type) as? Sensor
                    }
                }
                null
            }.getOrNull()
        }

        /** 用 XposedHelpers.callMethod 调原始 registerListener —— 自动选对重载，比手写反射稳。 */
        fun invokeOriginalRegisterListener(
            mgr: Any?, listener: SensorEventListener, sensor: Sensor, handler: Handler?
        ) {
            val target = mgr as? SensorManager ?: return
            runCatching {
                // 先试 4 参（带 samplingPeriod + handler），再试 3 参（samplingPeriod）
                try {
                    XposedHelpers.callMethod(target, "registerListener",
                        listener, sensor, 20_000, handler)
                    return
                } catch (_: Throwable) {}
                try {
                    XposedHelpers.callMethod(target, "registerListener",
                        listener, sensor, 20_000)
                    return
                } catch (_: Throwable) {}
                try {
                    XposedHelpers.callMethod(target, "registerListener", listener, sensor)
                } catch (_: Throwable) {}
            }
        }

        fun invokeOriginalUnregisterListener(mgr: Any?, listener: SensorEventListener, sensor: Sensor?) {
            val target = mgr as? SensorManager ?: return
            runCatching {
                if (sensor != null) {
                    XposedHelpers.callMethod(target, "unregisterListener", listener, sensor)
                } else {
                    XposedHelpers.callMethod(target, "unregisterListener", listener)
                }
            }
        }
    }
}
