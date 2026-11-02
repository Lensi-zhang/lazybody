package com.lazybody.tencent

import android.location.Location
import android.location.LocationManager
import com.lazybody.bridge.SimulationState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 腾讯位置 SDK 动态 hook。
 *
 * 设计来源：AnyWhere v2.11.1 xposed/hooks/i0.java——维护两个 profile
 * （open/sapp），反射扫描 SDK 主类的方法签名，动态 hook 所有返回 Location
 * 的 accessor。
 *
 * 简化策略：
 *   1) 尝试加载腾讯 SDK 常见的 6 个主类（open / sapp / plus / MiniLocation...）
 *   2) 对每个主类，扫描所有实例方法（非 static），找返回 Location 且参数空的
 *   3) hook：让它返回 SimulationState 对应的假位置
 *
 * 不做 profile 匹配 + 不写 Invoker.ORIGIN 恢复原始调用链——简化版够用。
 */
object TencentSdkHook {

    // 腾讯位置 SDK 不同版本的主类名（open / sapp / plus / mini 等 profile）
    private val MAIN_CLASS_CANDIDATES = listOf(
        "com.tencent.map.geolocation.TencentLocationManager",
        "com.tencent.map.geolocation.sapp.TencentLocationManager",
        "com.tencent.map.geolocation.sapp.proxy.TencentLocationManagerProxy",
        "com.tencent.map.geolocation.proxy.TencentLocationManagerProxy",
        "com.tencent.map.geolocation.MiniLocationManager",
        "com.tencent.map.geolocation.MiniLocation"
    )

    /**
     * 尝试 hook 所有腾讯 SDK 主类。返回成功 hook 到的方法数。
     */
    fun tryHook(lpparam: XC_LoadPackage.LoadPackageParam): Int {
        var hookedCount = 0
        for (className in MAIN_CLASS_CANDIDATES) {
            runCatching {
                val cls = Class.forName(className, false, lpparam.classLoader)
                hookedCount += hookClass(cls, lpparam)
            }
        }
        return hookedCount
    }

    private fun hookClass(cls: Class<*>, lpparam: XC_LoadPackage.LoadPackageParam): Int {
        val candidates = scanLocationAccessors(cls)
        if (candidates.isEmpty()) return 0

        for (method in candidates) {
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val fake = readFakeLocation()
                        if (fake != null) param.setResult(fake)
                    }
                })
            }
        }
        return candidates.size
    }

    /**
     * 扫描一个类中所有可能是 Location accessor 的实例方法。
     *
     * 筛选条件：
     *   1) 返回类型是 android.location.Location 或其子类
     *   2) 非 static（实例方法才有状态意义）
     *   3) 参数为空（getter 风格）
     *   4) 非 abstract（abstract 通常是接口声明）
     */
    private fun scanLocationAccessors(cls: Class<*>): List<Method> {
        val result = CopyOnWriteArrayList<Method>()
        val visited = HashSet<String>()
        val targetName = "android.location.Location"

        runCatching {
            for (method in cls.declaredMethods) {
                val modifiers = method.modifiers
                if (Modifier.isStatic(modifiers)) continue
                if (Modifier.isAbstract(modifiers)) continue
                if (method.parameterTypes.isNotEmpty()) continue
                val ret = method.returnType ?: continue
                if (ret.name == targetName || Location::class.java.isAssignableFrom(ret)) {
                    val key = "${cls.name}.${method.name}"
                    if (visited.add(key)) {
                        method.isAccessible = true
                        result.add(method)
                    }
                }
            }
            // 父类也扫描（腾讯 SDK 内部常会有 abstract 基类 + 子类实现）
            var parent = cls.superclass
            while (parent != null && parent != Any::class.java) {
                for (method in parent.declaredMethods) {
                    val modifiers = method.modifiers
                    if (Modifier.isStatic(modifiers)) continue
                    if (method.parameterTypes.isNotEmpty()) continue
                    val ret = method.returnType ?: continue
                    if (ret.name == targetName || Location::class.java.isAssignableFrom(ret)) {
                        val key = "${parent.name}.${method.name}"
                        if (visited.add(key)) {
                            method.isAccessible = true
                            result.add(method)
                        }
                    }
                }
                parent = parent.superclass
            }
        }
        return result
    }

    /**
     * 读 App 侧写入的 SimulationState，构造一个假的 Location 对象。
     * 由 Xposed 模块调用时传进来的 context 已经缓存好了（XposedMain.cachedAppContext）。
     */
    private fun readFakeLocation(): Location? {
        val context = runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            XposedHelpers.callStaticMethod(atClass, "currentApplication") as? android.content.Context
        }.getOrNull() ?: return null

        val sp = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val json = sp.getString("xposed_location_state", null)
        val state = SimulationState.fromJson(json)
        if (!state.active) return null

        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = state.lat
            longitude = state.lng
            altitude = state.alt
            bearing = state.bearing
            speed = state.speed
            accuracy = state.accuracy
            time = state.timeMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        }
        return loc
    }
}
