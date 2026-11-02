package com.lazybody.tencent

import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.lazybody.bridge.SimulationState
import com.lazybody.xposed.XposedMain
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap

/** 腾讯定位 SDK 的 Location getter 与 TencentLocationListener 回调 Hook。 */
object TencentSdkHook {

    private const val LISTENER_CLASS = "com.tencent.map.geolocation.TencentLocationListener"
    private const val LOCATION_CLASS = "com.tencent.map.geolocation.TencentLocation"

    private val MAIN_CLASS_CANDIDATES = listOf(
        "com.tencent.map.geolocation.TencentLocationManager",
        "com.tencent.map.geolocation.sapp.TencentLocationManager",
        "com.tencent.map.geolocation.sapp.proxy.TencentLocationManagerProxy",
        "com.tencent.map.geolocation.proxy.TencentLocationManagerProxy",
        "com.tencent.map.geolocation.MiniLocationManager",
        "com.tencent.map.geolocation.MiniLocation"
    )

    /** removeUpdates 必须换回注册时使用的同一个代理对象。 */
    private val listenerProxies = Collections.synchronizedMap(IdentityHashMap<Any, Any>())

    fun tryHook(lpparam: XC_LoadPackage.LoadPackageParam): Int {
        var hookedCount = 0
        for (className in MAIN_CLASS_CANDIDATES) {
            runCatching {
                val cls = Class.forName(className, false, lpparam.classLoader)
                hookedCount += hookClass(cls)
            }
        }
        return hookedCount
    }

    private fun hookClass(cls: Class<*>): Int {
        var hookedCount = 0
        val listenerClass = runCatching {
            Class.forName(LISTENER_CLASS, false, cls.classLoader)
        }.getOrNull()
        val locationClass = runCatching {
            Class.forName(LOCATION_CLASS, false, cls.classLoader)
        }.getOrNull()

        if (listenerClass != null) {
            for (method in allMethods(cls)) {
                if (Modifier.isStatic(method.modifiers)) continue
                if (method.parameterTypes.none { it.name == LISTENER_CLASS }) continue
                try {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            for (index in param.args.indices) {
                                val listener = param.args[index] ?: continue
                                if (!listenerClass.isInstance(listener)) continue
                                val proxy = if (method.name.startsWith("remove")) {
                                    listenerProxies[listener]
                                } else {
                                    proxyListener(listener, listenerClass, locationClass)
                                }
                                if (proxy != null) param.args[index] = proxy
                            }
                        }
                    })
                    hookedCount++
                } catch (_: Throwable) {}
            }
        }

        for (method in scanLocationAccessors(cls)) {
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        readFakeLocation()?.let { param.result = it }
                    }
                })
                hookedCount++
            } catch (_: Throwable) {}
        }
        return hookedCount
    }

    private fun proxyListener(
        listener: Any,
        listenerClass: Class<*>,
        locationClass: Class<*>?
    ): Any {
        listenerProxies[listener]?.let { return it }
        val proxy = Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass),
            InvocationHandler { proxyInstance, method, args ->
                when (method.name) {
                    "toString" -> "TencentLocationListenerProxy($listener)"
                    "hashCode" -> System.identityHashCode(proxyInstance)
                    "equals" -> proxyInstance === args?.firstOrNull()
                    else -> {
                        val callArgs = args?.copyOf() ?: arrayOfNulls<Any?>(0)
                        if (method.name == "onLocationChanged" && callArgs.isNotEmpty()) {
                            val state = XposedMain.readSimulationState()
                            if (state.active) {
                                callArgs[0] = proxyTencentLocation(locationClass, state, callArgs[0])
                                if (callArgs.size > 1 && callArgs[1] is Int) callArgs[1] = 0
                            }
                        }
                        method.invoke(listener, *callArgs)
                    }
                }
            }
        )
        listenerProxies[listener] = proxy
        return proxy
    }

    private fun proxyTencentLocation(
        locationClass: Class<*>?,
        state: SimulationState,
        original: Any?
    ): Any? {
        if (locationClass == null || !locationClass.isInterface) return original
        return Proxy.newProxyInstance(
            locationClass.classLoader,
            arrayOf(locationClass),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "getLatitude" -> state.lat
                    "getLongitude" -> state.lng
                    "getAltitude" -> state.alt
                    "getBearing" -> state.bearing
                    "getSpeed" -> state.speed
                    "getAccuracy" -> state.accuracy
                    "getTime" -> state.timeMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
                    "getProvider" -> LocationManager.GPS_PROVIDER
                    "toString" -> "TencentLocation(${state.lng},${state.lat})"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> {
                        if (original != null) {
                            runCatching {
                                method.invoke(original, *(args ?: arrayOfNulls<Any?>(0)))
                            }.getOrNull() ?: defaultValue(method.returnType)
                        } else {
                            defaultValue(method.returnType)
                        }
                    }
                }
            }
        )
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        Character.TYPE -> 0.toChar()
        else -> null
    }

    private fun scanLocationAccessors(cls: Class<*>): List<Method> = allMethods(cls).filter { method ->
        !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            method.parameterTypes.isEmpty() &&
            Location::class.java.isAssignableFrom(method.returnType)
    }.distinctBy { it.toGenericString() }

    private fun allMethods(cls: Class<*>): List<Method> {
        val result = ArrayList<Method>()
        var current: Class<*>? = cls
        while (current != null && current != Any::class.java) {
            result.addAll(current.declaredMethods)
            current = current.superclass
        }
        return result
    }

    private fun readFakeLocation(): Location? {
        val state = XposedMain.readSimulationState()
        if (!state.active) return null
        return Location(LocationManager.GPS_PROVIDER).apply {
            latitude = state.lat
            longitude = state.lng
            altitude = state.alt
            bearing = state.bearing
            speed = state.speed
            accuracy = state.accuracy
            time = state.timeMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && state.elapsedRealtimeNanos > 0L) {
                elapsedRealtimeNanos = state.elapsedRealtimeNanos
            }
        }
    }
}
