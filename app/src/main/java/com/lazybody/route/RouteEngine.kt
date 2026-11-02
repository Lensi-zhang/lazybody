package com.lazybody.route

import kotlin.math.*

/**
 * 球面上的一个路径点（WGS84 经纬度）。
 */
data class Waypoint(val lat: Double, val lng: Double) {
    companion object {
        val EMPTY = Waypoint(0.0, 0.0)
    }
}

/**
 * 路线模式。
 * - ONCE: 从头到尾走一遍，走完停下
 * - LOOP: 走到终点后原路返回（返程 bearing +180°），循环往复
 */
enum class RouteMode { ONCE, LOOP }

/**
 * 速度档位（m/s）。数值来自 AnyWhere v2.11.1 分析。
 */
object SpeedTier {
    const val WALK = 1.2
    const val RUN = 3.6
    const val DRIVE = 10.0

    /** 根据数值自动推断档位，用于从已存配置恢复。 */
    fun classify(mps: Double): String = when {
        mps <= 2.2 -> "walk"
        mps <= 4.5 -> "run"
        else -> "drive"
    }

    fun mpsOf(label: String): Double = when (label.lowercase()) {
        "run" -> RUN
        "drive" -> DRIVE
        else -> WALK
    }
}

/**
 * 大圆距离（米）——Haversine 公式。
 */
private fun haversine(a: Waypoint, b: Waypoint): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val la1 = Math.toRadians(a.lat)
    val la2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2).pow(2) + cos(la1) * cos(la2) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/**
 * 初始方位角（度）——大圆 bearing。0 = 正北，顺时针增加。
 */
private fun initialBearing(a: Waypoint, b: Waypoint): Double {
    val la1 = Math.toRadians(a.lat)
    val la2 = Math.toRadians(b.lat)
    val dl = Math.toRadians(b.lng - a.lng)
    val y = sin(dl) * cos(la2)
    val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dl)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/**
 * Slerp 球面线性插值，返回点在 [0,1] 进度时的位置。
 */
private fun slerp(a: Waypoint, b: Waypoint, t: Double): Waypoint {
    val φ1 = Math.toRadians(a.lat)
    val φ2 = Math.toRadians(b.lat)
    val Δλ = Math.toRadians(b.lng - a.lng)
    val cosΔ = sin(φ1) * sin(φ2) + cos(φ1) * cos(φ2) * cos(Δλ)
    val Ω = acos(cosΔ.coerceIn(-1.0, 1.0))
    if (Ω == 0.0 || t == 0.0) return a
    if (t == 1.0) return b
    val sinΩ = sin(Ω)
    val A = sin((1 - t) * Ω) / sinΩ
    val B = sin(t * Ω) / sinΩ
    val lat = asin(A * sin(φ1) + B * sin(φ2))
    val lng = atan2(
        A * cos(φ1) * sin(0.0) + B * cos(φ2) * sin(Δλ),
        A * cos(φ1) * cos(0.0) + B * cos(φ2) * cos(Δλ)
    ) + Math.toRadians(a.lng)
    return Waypoint(Math.toDegrees(lat), Math.toDegrees(lng))
}

/**
 * 路线引擎：按给定速度推进位置，返回当前坐标与方位。
 *
 * @param waypoints 路径点列表（≥2 个）
 * @param mode ONCE 或 LOOP
 * @param speedMps 当前速度（m/s），可在运行时调整以实现变速
 */
class RouteEngine(
    waypoints: List<Waypoint>,
    val mode: RouteMode,
    var speedMps: Double = SpeedTier.WALK
) {
    private val nodes: List<Waypoint>
    private val segments: DoubleArray        // 每段长度（米）
    private val bearings: DoubleArray        // 每段初始方位（度）
    private val segmentTotal: Double          // 全程总长（米）
    private val loopTotal: Double             // LOOP 模式 = 单程 × 2（含返程）

    /** 累计已走过的距离（米），用于二分当前段。 */
    var traveled: Double = 0.0
        private set

    /** 当前段索引。 */
    var currentSegment: Int = 0
        private set

    /** 是否走在返程段上（LOOP 模式后半程）。 */
    var returning: Boolean = false
        private set

    /** 是否完成（ONCE 走完 或 LOOP 循环到指定次数）。 */
    var finished: Boolean = false
        private set

    /** LOOP 已完成的循环次数。 */
    var completedLoops: Int = 0
        private set

    init {
        require(waypoints.size >= 2) { "路线至少需要 2 个航点" }
        val wps = waypoints.toList()
        nodes = if (mode == RouteMode.LOOP) wps + wps.reversed().drop(1) else wps
        segments = DoubleArray(nodes.size - 1)
        bearings = DoubleArray(nodes.size - 1)
        var acc = 0.0
        for (i in 0 until nodes.size - 1) {
            val len = haversine(nodes[i], nodes[i + 1])
            segments[i] = len
            bearings[i] = initialBearing(nodes[i], nodes[i + 1])
            acc += len
        }
        segmentTotal = segments.sum()
        loopTotal = segmentTotal
    }

    /**
     * 按时间增量前进一步。
     *
     * @param dtSec 上一 tick 到现在过去的秒数
     * @return 当前位置（WGS84）+ bearing（度，0=北），或 null 如果已完成
     */
    fun advance(dtSec: Double): Pair<Waypoint, Double>? {
        if (finished) return null
        val step = speedMps * dtSec
        traveled += step

        val target = when (mode) {
            RouteMode.ONCE -> segmentTotal.coerceAtLeast(segments[segments.size - 1])
            RouteMode.LOOP -> loopTotal
        }

        if (traveled >= target) {
            if (mode == RouteMode.ONCE) {
                finished = true
                val last = nodes.last()
                return last to bearings.last()
            } else {
                // LOOP：保留跨周期余量，下一 tick 从起点连续前进。
                completedLoops += floor(traveled / target).toInt()
                traveled %= target
                currentSegment = 0
                returning = false
            }
        }

        // 二分查找当前段
        var acc = 0.0
        var idx = 0
        for (i in segments.indices) {
            val segEnd = acc + segments[i]
            if (traveled <= segEnd) {
                idx = i
                break
            }
            acc = segEnd
            idx = i
            if (i == segments.size - 1) {
                idx = segments.size - 1
            }
        }
        currentSegment = idx

        val segLen = segments[idx]
        val localT = if (segLen < 1e-6) 0.0 else ((traveled - acc) / segLen).coerceIn(0.0, 1.0)
        val from = nodes[idx]
        val to = nodes[idx + 1]
        val pos = slerp(from, to, localT)

        // LOOP 模式中，当 idx 超过去程最后段索引时标记返程（用于调试展示，不影响计算）
        val halfSegments = nodes.size / 2
        returning = mode == RouteMode.LOOP && idx >= halfSegments

        var bearing = bearings[idx]
        // 零长段继承前一段方位角
        if (segLen < 1e-6 && idx > 0) bearing = bearings[idx - 1]

        return pos to bearing
    }

    /** 外部可强制跳到指定进度（0..1），便于 UI 拖拽调试。 */
    fun jumpTo(progress: Double) {
        val clamped = progress.coerceIn(0.0, 1.0)
        val target = when (mode) { RouteMode.ONCE -> segmentTotal; RouteMode.LOOP -> loopTotal }
        traveled = clamped * target
        finished = false
    }

    /** 重置到起点。 */
    fun reset() {
        traveled = 0.0
        currentSegment = 0
        returning = false
        finished = false
        completedLoops = 0
    }

    /** 当前路线总长度（米）。 */
    fun totalLengthMeters(): Double = loopTotal

    /** 已走百分比（0..1）。 */
    fun progress(): Double = when (mode) {
        RouteMode.ONCE -> traveled / segmentTotal
        RouteMode.LOOP -> traveled / loopTotal
    }

    /** 从点 1 到点 2 的大圆距离（静态工具）。 */
    fun distance(a: Waypoint, b: Waypoint) = haversine(a, b)

    /** 两点初始方位角（静态工具）。 */
    fun bearing(a: Waypoint, b: Waypoint) = initialBearing(a, b)
}
