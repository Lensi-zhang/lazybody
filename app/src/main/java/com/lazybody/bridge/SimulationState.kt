package com.lazybody.bridge

import org.json.JSONObject

/**
 * App 进程 ↔ Xposed 模块 间通过 SharedPreferences 共享的状态快照。
 *
 * 设计来源：AnyWhere v2.11.1 `xposed_location_state` 机制（config/n.java）。
 */
data class SimulationState(
    val active: Boolean,
    val lat: Double,
    val lng: Double,
    val alt: Double,
    val bearing: Float,
    val speed: Float,
    val accuracy: Float,
    val timeMillis: Long,
    val elapsedRealtimeNanos: Long,
    val steps: Double,
    val cadenceSpm: Double
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("active", active)
        if (active) {
            put("latitude", lat)
            put("longitude", lng)
            put("altitude", alt)
            put("bearing", bearing.toDouble())
            put("speed", speed.toDouble())
            put("accuracy", accuracy.toDouble())
            put("timeMillis", timeMillis)
            put("elapsedRealtimeNanos", elapsedRealtimeNanos)
            put("steps", steps)
            put("cadenceSpm", cadenceSpm)
        }
    }

    /** 状态是否在 TTL 窗口内仍然新鲜（相对传入的 elapsedRealtimeNanos）。 */
    fun isFresh(nowNanos: Long, ttlNanos: Long): Boolean =
        active && elapsedRealtimeNanos > 0L && (nowNanos - elapsedRealtimeNanos) in 0..ttlNanos

    companion object {
        /** 未激活 / 空状态。 */
        val INACTIVE = SimulationState(
            active = false,
            lat = 0.0, lng = 0.0, alt = 0.0,
            bearing = 0f, speed = 0f, accuracy = 0f,
            timeMillis = 0L, elapsedRealtimeNanos = 0L,
            steps = 0.0, cadenceSpm = 0.0
        )

        /** 从 SP JSON 字符串解析。非法值返回 INACTIVE。 */
        fun fromJson(raw: String?): SimulationState {
            if (raw.isNullOrBlank()) return INACTIVE
            return try {
                val jo = JSONObject(raw)
                if (!jo.optBoolean("active", false)) return INACTIVE
                SimulationState(
                    active = true,
                    lat = jo.optDouble("latitude", 0.0),
                    lng = jo.optDouble("longitude", 0.0),
                    alt = jo.optDouble("altitude", 0.0),
                    bearing = jo.optDouble("bearing", 0.0).toFloat(),
                    speed = jo.optDouble("speed", 0.0).toFloat(),
                    accuracy = jo.optDouble("accuracy", 0.0).toFloat(),
                    timeMillis = jo.optLong("timeMillis", 0L),
                    elapsedRealtimeNanos = jo.optLong("elapsedRealtimeNanos", 0L),
                    steps = jo.optDouble("steps", 0.0),
                    cadenceSpm = jo.optDouble("cadenceSpm", 0.0)
                )
            } catch (_: Throwable) {
                INACTIVE
            }
        }
    }
}
