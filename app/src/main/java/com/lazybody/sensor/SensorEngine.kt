package com.lazybody.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs

/**
 * 传感器引擎：反射构造虚拟计步传感器（STEP_COUNTER / STEP_DETECTOR）。
 *
 * 设计来源：AnyWhere v2.11.1 xposed/hooks/d0.java
 *   - 反射调 Sensor(String) 私有构造器，然后反射设置 mType/mName/mVendor/
 *     mVersion/mHandle/mMaxRange/mResolution/mPower/mStringType 等私有字段
 *   - 反射调 SensorEvent(Integer) 私有构造器，然后反射填 values / sensor / timestamp
 *
 * 核心用途：让微信运动/Keep/QQ 等拿到假步数，同时配合 Location 模拟形成
 * "速度-步数"自洽闭环——走得快计步也快，走得慢计步也慢。
 */
object SensorEngine {

    const val STEP_COUNTER_TYPE = Sensor.TYPE_STEP_COUNTER        // 19
    const val STEP_DETECTOR_TYPE = Sensor.TYPE_STEP_DETECTOR      // 18

    // ====== 反射构造 Sensor ======

    /**
     * 反射构造一个假 Sensor，填好所有内部字段，让 SensorManager 把它当真实传感器。
     */
    @Suppress("PrivateApi", "SameParameterValue")
    fun buildFakeSensor(type: Int): Sensor? {
        val name = when (type) {
            STEP_COUNTER_TYPE -> "LazyBody Step Counter"
            STEP_DETECTOR_TYPE -> "LazyBody Step Detector"
            else -> "LazyBody Sensor"
        }
        val stringType = when (type) {
            STEP_COUNTER_TYPE -> "android.sensor.step_counter"
            STEP_DETECTOR_TYPE -> "android.sensor.step_detector"
            else -> "android.sensor.unknown"
        }
        val handle = when (type) {
            STEP_COUNTER_TYPE -> 0x416_000_001.toInt()
            STEP_DETECTOR_TYPE -> 0x416_000_002.toInt()
            else -> 0x416_000_000
        }
        return try {
            val ctor = Sensor::class.java.getDeclaredConstructor(String::class.java)
            ctor.isAccessible = true
            val sensor = ctor.newInstance(name)
            setField(sensor, "mType", type)
            setField(sensor, "mName", name)
            setField(sensor, "mVendor", "LazyBody")
            setField(sensor, "mVersion", 1)
            setField(sensor, "mHandle", handle)
            setField(sensor, "mMaxRange", 65535f)
            setField(sensor, "mResolution", 1f)
            setField(sensor, "mPower", 0.5f)
            setField(sensor, "mMinDelay", 0)
            setField(sensor, "mMaxDelay", 0)
            setField(sensor, "mStringType", stringType)
            setField(sensor, "mFlags", 0)
            sensor
        } catch (t: Throwable) {
            XLog.e("SensorEngine.buildFakeSensor($type) failed: ${t.message}")
            null
        }
    }

    // ====== 反射构造 SensorEvent ======

    /**
     * 反射构造 SensorEvent，填 values / sensor / timestamp。
     */
    @Suppress("PrivateApi")
    fun buildSensorEvent(values: FloatArray, sensor: Sensor, timestampNs: Long): SensorEvent? {
        return try {
            val ctor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            ctor.isAccessible = true
            val event = ctor.newInstance(values.size)
            // 填 values 数组——先检查反射拿到的数组长度够不够
            val valuesField = SensorEvent::class.java.getDeclaredField("values")
            valuesField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val existing = valuesField.get(event) as FloatArray
            if (existing.size >= values.size) {
                System.arraycopy(values, 0, existing, 0, values.size)
            } else {
                // 数组不够长，直接整个替换
                valuesField.set(event, values.copyOf())
            }
            SensorEvent::class.java.getDeclaredField("sensor").let {
                it.isAccessible = true
                it.set(event, sensor)
            }
            SensorEvent::class.java.getDeclaredField("timestamp").let {
                it.isAccessible = true
                it.setLong(event, timestampNs)
            }
            SensorEvent::class.java.getDeclaredField("accuracy").let {
                it.isAccessible = true
                it.setInt(event, SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
            }
            event
        } catch (t: Throwable) {
            XLog.e("SensorEngine.buildSensorEvent failed: ${t.message}")
            null
        }
    }

    // ====== 步态 tick：速度 → 步频 → 步数 ======

    /**
     * 根据当前速度（m/s）返回当前步频（steps per minute）。
     * 分段映射：≤0.8 站着不动、~1.2 走路、~3.6 慢跑、>4.5 快跑。
     *
     * 数据来源：AnyWhere motion/c.java 的分段表。
     */
    fun cadenceFromSpeed(mps: Double): Double = when {
        mps <= 0.2 -> 0.0
        mps <= 0.8 -> 95.0
        mps <= 1.5 -> 110.0
        mps <= 2.2 -> 130.0
        mps <= 3.0 -> 155.0
        mps <= 4.5 -> 180.0
        mps <= 6.0 -> 195.0
        else -> 210.0
    }

    private fun setField(obj: Any, name: String, value: Any?) {
        try {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.set(obj, value)
        } catch (_: Throwable) {}
        try {
            val sup = obj.javaClass.superclass ?: return
            val f = sup.getDeclaredField(name)
            f.isAccessible = true
            f.set(obj, value)
        } catch (_: Throwable) {}
    }
}

/**
 * 极简日志（Xposed 环境不想依赖任何 Android App 里的 Logger，直接 android.util.Log）。
 */
private object XLog {
    fun e(msg: String) { try { android.util.Log.e("LazyBodySensor", msg) } catch (_: Throwable) {} }
}
