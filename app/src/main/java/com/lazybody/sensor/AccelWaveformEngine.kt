package com.lazybody.sensor

import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos

/**
 * 三轴加速度波形引擎。
 *
 * 简化自 AnyWhere v2.11.1 xposed/motion/c.java 的 256 点波形表 + 高斯核插值：
 *   xyz = staticGravity(pitch/roll) + verticalImpulse(phase, amplitude) + gaussNoise(σ=0.8) + splitmix64Drift(tickNanos)
 *
 * 速度直接映射到冲击频率（cadenceFromSpeed）和幅度，停下时只输出静止重力。
 */
class AccelWaveformEngine {

    private val rng = java.util.Random()

    /** 当前步态相位 0..2π。由 cadence 和 tick 时间推进。 */
    private var phase = 0.0

    /** 目标俯仰/横滚角（弧度）—— 模拟手机握持姿态，默认横持。 */
    var pitchRad: Double = Math.toRadians(-5.0)
    var rollRad: Double = Math.toRadians(-30.0)

    private val GRAVITY = 9.80665

    /**
     * 生成一帧三轴加速度（m/s²）。
     *
     * @param cadenceSpm  当前步频（spm），决定冲击频率
     * @param dtSec       距上一帧的时间（秒）
     * @param speedMps    当前速度（m/s），决定冲击幅度
     */
    fun nextFrame(cadenceSpm: Double, dtSec: Double, speedMps: Double, nowNanos: Long): FloatArray {
        // 相位推进：cadence → 频率 → Δφ
        val freqHz = cadenceSpm / 60.0
        phase = (phase + 2.0 * PI * freqHz * dtSec).mod(2.0 * PI)

        // 冲击幅度：速度越快冲击越强
        val impactAmp: Double = when {
            speedMps <= 0.05 -> 0.0
            speedMps <= 2.2 -> 1.2 + speedMps * 0.3
            speedMps <= 4.5 -> 2.0 + (speedMps - 2.2) * 0.8
            else -> 3.8 + (speedMps - 4.5) * 0.5
        }

        // 静态重力三轴（含 pitch/roll 姿态）
        val gx: Float = (sin(pitchRad) * GRAVITY).toFloat()
        val gy: Float = (sin(rollRad) * cos(pitchRad) * GRAVITY).toFloat()
        val gz: Float = (cos(rollRad) * cos(pitchRad) * GRAVITY).toFloat()

        // 垂直冲击脉冲（sin² 形状）
        val verticalImpulse: Float = (-sin(phase)).coerceAtLeast(0.0).toFloat() * impactAmp.toFloat()

        // 小幅周期抖动
        val stepJitter: Float = (cos(phase * 2.0) * 0.15 * impactAmp).toFloat()

        // 三轴高斯噪声
        val nx: Float = rng.nextGaussian().toFloat() * 0.2f
        val ny: Float = rng.nextGaussian().toFloat() * 0.2f
        val nz: Float = rng.nextGaussian().toFloat() * 0.3f

        // splitmix64 时间漂移
        val drifts = splitmix64Vector(nowNanos)

        return floatArrayOf(
            gx + nx + drifts[0] + stepJitter,
            gy + ny + drifts[1] + stepJitter * 0.7f,
            gz + nz + drifts[2] + verticalImpulse
        )
    }

    private fun splitmix64Vector(seed: Long): FloatArray {
        val a = splitmix64(seed)
        val b = splitmix64(a xor SPLITMIX_GOLDEN)
        val c = splitmix64(b xor SPLITMIX_GOLDEN)
        val mask = (-1L ushr 32)  // 0xFFFFFFFFL，Kotlin Long 的 32 位 mask
        return floatArrayOf(
            ((a and mask).toDouble() / mask.toDouble()).toFloat() * 0.15f - 0.075f,
            ((b and mask).toDouble() / mask.toDouble()).toFloat() * 0.15f - 0.075f,
            ((c and mask).toDouble() / mask.toDouble()).toFloat() * 0.10f - 0.05f
        )
    }

    private fun splitmix64(z: Long): Long {
        var x = z + SPLITMIX_GOLDEN
        x = (x xor (x ushr 30)) * SPLITMIX_C1
        x = (x xor (x ushr 27)) * SPLITMIX_C2
        return x xor (x ushr 31)
    }

    companion object {
        // splitmix64 标准常量 (Java Long signed 表示)
        private const val SPLITMIX_GOLDEN = -7046029254386353127L  // 0x9E3779B97F4A7C15
        private const val SPLITMIX_C1 = -5435695900002550887L      // 0xBF58476D1CE4E5B9
        private const val SPLITMIX_C2 = -7740664369290057621L      // 0x94D049BB133111EB
    }

    /** 重置相位（停止运动/切换姿态时）。 */
    fun reset() { phase = 0.0 }
}
