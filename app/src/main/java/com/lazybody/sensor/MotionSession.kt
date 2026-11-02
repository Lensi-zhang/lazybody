package com.lazybody.sensor

import android.os.SystemClock

/**
 * 步态模拟会话——维护累计步数、当前步频、速度，按 tick 递增步数。
 *
 * 与 SensorEngine 配套：Engine 负责反射构造 Sensor/SensorEvent，
 * MotionSession 负责"该发多少步"的决策。
 *
 * 本类负责 step_counter 累计值与 step_detector 脉冲；
 * 三轴波形由 AccelWaveformEngine 生成。
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
    private var stepRemainder = 0.0

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
        // 保留每次 tick 的小数步，避免 100ms 周期下步数长期不增长。
        stepRemainder += cadence / 60.0 * dtSec
        val wholeSteps = stepRemainder.toLong()
        if (wholeSteps <= 0L) return 0L

        stepRemainder -= wholeSteps
        totalSteps += wholeSteps
        return wholeSteps
    }

    /** 重置会话（Service 启动或停止模拟时调用）。 */
    fun reset() {
        speedMps = 0.0
        totalSteps = 0L
        stepRemainder = 0.0
        lastTickNanos = SystemClock.elapsedRealtimeNanos()
    }
}
