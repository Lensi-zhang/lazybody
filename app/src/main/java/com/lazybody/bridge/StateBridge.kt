package com.lazybody.bridge

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.preference.PreferenceManager

/**
 * App 进程侧的写入端：把模拟状态序列化写入 SharedPreferences，
 * 由 Xposed 模块在目标进程中读取。
 *
 * 设计来源：AnyWhere v2.11.1 config/m.java，200ms 节流 + 2 秒 TTL。
 *
 * 使用：构造时传入 Context，之后调用 [write] 即可；内部做了
 * [MIN_WRITE_INTERVAL_MS] 的节流，过于频繁的调用会被丢弃。
 */
class StateBridge(context: Context) {

    private val sp: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /** 两次实际写入之间的最小间隔，避免频繁跨进程 commit 开销。 */
    private val MIN_WRITE_INTERVAL_MS = 200L

    private var lastWriteMs: Long = 0L

    /**
     * 写入一次状态快照。
     * 太频繁调用会被节流。
     */
    @Synchronized
    fun write(state: SimulationState) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWriteMs < MIN_WRITE_INTERVAL_MS) return

        // 强制把真实时间戳一并填充，确保读取方知道状态新鲜度
        val stamped = if (state.active) {
            state.copy(
                timeMillis = System.currentTimeMillis(),
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            )
        } else {
            state
        }

        sp.edit()
            .putString(KEY_STATE, stamped.toJson().toString())
            .putBoolean(KEY_ACTIVE, stamped.active)
            .apply()

        lastWriteMs = now
    }

    /** 标记模拟已停止——写一条 active=false 让 Xposed 立即退出。 */
    fun deactivate() {
        sp.edit()
            .putBoolean(KEY_ACTIVE, false)
            .putString(KEY_STATE, SimulationState.INACTIVE.toJson().toString())
            .apply()
        lastWriteMs = 0L
    }

    companion object {
        const val KEY_STATE = "xposed_location_state"
        const val KEY_ACTIVE = "xposed_simulation_active"

        /** 状态在 Xposed 端仍被视为有效的最大时间（ns），过期视为"停止模拟"。 */
        const val TTL_NANOS = 2_000_000_000L
    }
}
