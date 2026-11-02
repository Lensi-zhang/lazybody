package com.lazybody.sensor

import android.os.SystemClock
import kotlin.math.max

/**
 * 步态模拟会话——维护累计步数、当前步频、速度，按 tick 递增步数。
 *
 * 与 SensorEngine 配套：Engine 负责反射构造 Sensor/SensorEvent，
 * MotionSession 负责"该发多少步"的决策。
 *
 * 简化自 AnyWhere v2.11.1 xposed/motion/c.java——它用高斯核 + 256 点
 * 波形表精确注入每个步态的三轴加速度，我们先只做"步数事件"
 * （step_counter 累计值 + step_detector 脉冲）。三轴加速度波形注入
 * 后续可加，但复杂度过高，投入产出比差。
 */
class MotionSession {

    /** 当前速度 m/s。0 表示停下。 */
    var speedMps: Double = 0.0

    /** 累计步数（发给 STEP_COUNTER 的绝对值）。 */
    var totalSteps: Long = 0L
        private set

    /** tick 间隔（ms）。默认 100ms，对应 AnyWhere d0.java。 */
    var tickIntervalMs: Long = 100L

    private var lastTickNanos: Long = SystemClock.elapsedRealtimeNanos()

    /**
     * 推进一次 tick，返回这次 tick 应该发的步数增量（通常 0 或 1，偶尔 2）。
     * 返回 0 表示当前 tick 还不该发步。
     */
    fun advanceTick(): Long {
        val now = SystemClock.elapsedRealtimeNanos()
        val dtSec = (now - lastTickNanos).coerceAtLeast(0L) / 1_000_000_000.0
        lastTickNanos = now

        // 停下了就不发步
        if (speedMps <= 0.05) return 0L

        val cadence = SensorEngine.cadenceFromSpeed(speedMps)   // spm
        // cadence 是每分钟步数，转每秒，× dtSec 得到本 tick 该前进多少步
        val stepAdvance = cadence / 60.0 * dtSec

        // 累计到整数就发一步（防 float 漂移 + 每 tick 只发一个 step_detector 脉冲）
        val floor = stepAdvance.toLong()
        if (floor <= 0) return 0L

        totalSteps += floor
        return floor
    }

    /** 重置会话（Service 启动或停止模拟时调用）。 */
    fun reset() {
        speedMps = 0.0
        totalSteps = 0L
        lastTickNanos = SystemClock.elapsedRealtimeNanos()
    }
}
