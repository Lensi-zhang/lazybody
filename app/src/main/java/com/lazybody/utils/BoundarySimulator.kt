package com.lazybody.utils

import com.elvishew.xlog.XLog
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 活动边界模拟器
 *
 * 当模拟位置到达设定的活动边界时，自动随机角度返回
 * 用于模拟真实的运动轨迹，避免超出设定范围
 */
class BoundarySimulator {

    companion object {
        private const val TAG = "BoundarySimulator"
        private const val DEFAULT_RADIUS_METERS = 100.0 // 默认活动半径 100 米
        private const val METERS_PER_DEGREE_LAT = 111319.9 // 每度纬度对应的米数
    }

    // 中心点坐标
    private var centerLat = 0.0
    private var centerLng = 0.0

    // 活动半径（米）
    private var radiusMeters = DEFAULT_RADIUS_METERS

    // 是否启用
    private var isEnabled = false

    // 当前移动角度（度）
    private var currentAngle = 0.0

    // 上一次位置
    private var lastLat = 0.0
    private var lastLng = 0.0

    /**
     * 设置中心点（第一次启动模拟定位时的位置）
     */
    fun setCenter(lat: Double, lng: Double) {
        centerLat = lat
        centerLng = lng
        lastLat = lat
        lastLng = lng
        XLog.d("$TAG: 中心点设置为 ($lat, $lng)")
    }

    /**
     * 设置活动半径
     */
    fun setRadius(radius: Double) {
        radiusMeters = radius
        XLog.d("$TAG: 活动半径设置为 ${radius}米")
    }

    /**
     * 启用/禁用边界模拟
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        XLog.d("$TAG: 边界模拟${if (enabled) "启用" else "禁用"}")
    }

    /**
     * 检查是否启用
     */
    fun isEnabled(): Boolean = isEnabled

    /**
     * 获取当前活动半径
     */
    fun getRadius(): Double = radiusMeters

    /**
     * 计算两点之间的距离（米）
     */
    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val latDiff = (lat2 - lat1) * METERS_PER_DEGREE_LAT
        val lngDiff = (lng2 - lng1) * METERS_PER_DEGREE_LAT * cos(Math.toRadians(lat1))
        return Math.sqrt(latDiff * latDiff + lngDiff * lngDiff)
    }

    /**
     * 检查是否到达边界并计算新的位置
     *
     * @param currentLat 当前纬度
     * @param currentLng 当前经度
     * @param speed 速度（米/秒）
     * @return 新的位置坐标，如果未到达边界则返回原坐标
     */
    fun checkBoundaryAndUpdate(currentLat: Double, currentLng: Double, speed: Double): Pair<Double, Double> {
        if (!isEnabled) {
            return Pair(currentLat, currentLng)
        }

        // 如果中心点未设置，使用当前位置作为中心点
        if (centerLat == 0.0 && centerLng == 0.0) {
            setCenter(currentLat, currentLng)
        }

        // 计算当前位置到中心点的距离
        val distance = calculateDistance(centerLat, centerLng, currentLat, currentLng)

        android.util.Log.d(TAG, "距离=${String.format("%.1f", distance)}米, 半径=${String.format("%.1f", radiusMeters)}米, 中心=($centerLat, $centerLng), 当前=($currentLat, $currentLng)")

        // 检查是否到达边界
        if (distance >= radiusMeters) {
            android.util.Log.d(TAG, "到达边界！距离=${String.format("%.1f", distance)}米，半径=${String.format("%.1f", radiusMeters)}米")

            // 计算从中心点到当前位置的角度（以屏幕为准）
            // 角度定义：0=右(东), 90=上(北), 180=左(西), 270=下(南)
            val angleFromCenter = Math.toDegrees(
                Math.atan2(currentLat - centerLat, currentLng - centerLng)
            )

            // 生成随机返回角度（在当前位置角度的反方向附近随机偏移）
            val randomOffset = Random.nextDouble(-60.0, 60.0)
            currentAngle = (angleFromCenter + 180.0 + randomOffset + 360) % 360.0

            android.util.Log.d(TAG, "返回角度=${String.format("%.1f", currentAngle)}度")

            // 计算返回方向上的新位置（向返回方向移动一小步）
            val stepDistance = speed * 0.1 // 0.1秒的移动距离
            val stepLat = stepDistance * Math.sin(Math.toRadians(currentAngle)) / METERS_PER_DEGREE_LAT
            val stepLng = stepDistance * Math.cos(Math.toRadians(currentAngle)) / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(centerLat)))

            val newLat = currentLat + stepLat
            val newLng = currentLng + stepLng

            lastLat = newLat
            lastLng = newLng

            XLog.d("$TAG: 返回方向移动: ($newLat, $newLng)")

            return Pair(newLat, newLng)
        }

        // 未到达边界，返回原位置
        lastLat = currentLat
        lastLng = currentLng
        return Pair(currentLat, currentLng)
    }

    /**
     * 获取当前状态信息
     */
    fun getStatusInfo(): String {
        if (!isEnabled) return "边界模拟: 关闭"

        val distance = if (centerLat != 0.0) {
            calculateDistance(centerLat, centerLng, lastLat, lastLng)
        } else {
            0.0
        }

        return "边界模拟: 开启 | 半径: ${String.format("%.0f", radiusMeters)}米 | 距离: ${String.format("%.0f", distance)}米"
    }

    /**
     * 获取返回角度（用于同步到摇杆）
     */
    fun getReturnAngle(): Double {
        return currentAngle
    }
}
