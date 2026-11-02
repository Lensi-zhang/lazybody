package com.lazybody.gnss

import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 单颗卫星的模拟星座条目。对应 GnssStatus.Builder.addSatellite 参数集。
 *
 * Android 公开 API（API 24+ N）：
 *   addSatellite(int svid, int constellationType, float cn0DbHz,
 *                float elevationDegrees, float azimuthDegrees, boolean usedInFix)
 *
 * Android 10+ (API 29) 额外参数可按需填。
 */
data class GnssSatellite(
    val svid: Int,                  // 卫星编号（GPS 1-37, GLONASS 1-24, Galileo 1-30, BDS 1-63）
    val constellationType: Int,     // GnssStatus.CONSTELLATION_GPS=1 / GLONASS=3 / GALILEO=4 / QZSS=4 / BDS=5
    val cn0DbHz: Float,             // 载噪比 dB-Hz（典型 25~50）
    val elevationDegrees: Float,    // 仰角 0~90（地平线以上）
    val azimuthDegrees: Float,      // 方位 0~359（正北为 0）
    val usedInFix: Boolean,         // 是否用于位置解算
    val signalStrengthDbm: Float = -110f,
    val signalToNoiseRatioDbHz: Float = 35f,
    val carrierFrequencyHz: Float = 1_575_420_000f,  // L1（GPS）
    val hasEphemeris: Boolean = true,
    val hasAlmanac: Boolean = true
)

/**
 * 星座引擎：合成一个"有真实感"的 GNSS 可见卫星列表，并让方位缓慢随时间漂移。
 *
 * 不依赖真实星历（简化版），生成方式：
 *  1) 按指定可见卫星数（默认 10）均匀分布在天球上
 *  2) elevations 用 beta 分布集中在 30°~70°（最常见）
 *  3) 方位随真实时间缓慢旋转（约 1°/s，模拟地球自转相对卫星轨道的变化）
 *  4) cn0 加小幅高斯抖动（±3 dB-Hz），usedInFix 随机掉 1~2 颗模拟遮挡
 *
 * 设计来源：AnyWhere v2.11.1 的 fake/i.java + config.e——它加载预录 JSON 场景
 * 回放；我们先用合成版，后续可加 JSON 场景加载。
 */
class GpsEngine(
    private val satelliteCount: Int = DEFAULT_COUNT,
    private val visibilitySpinRateDegPerSec: Double = 0.8,
    private val noiseCn0Sigma: Float = 3f
) {
    private data class Template(
        val svid: Int,
        val constellation: Int,
        val elevBase: Float,   // 基础仰角
        val azimBase: Float,   // 基础方位
        val carrierHz: Float
    )

    private val templates: List<Template> = buildTemplates(satelliteCount)
    private val initEpochNanos: Long = SystemClock.elapsedRealtimeNanos()

    /**
     * 返回当前模拟时刻的卫星列表。
     *
     * @param nowNanos 当前 elapsedRealtimeNanos，若为 0 则用 SystemClock 自身
     */
    fun snapshot(nowNanos: Long = 0L): List<GnssSatellite> {
        val now = if (nowNanos > 0) nowNanos else SystemClock.elapsedRealtimeNanos()
        val elapsedSec = (now - initEpochNanos) / 1_000_000_000.0
        val driftDeg = (elapsedSec * visibilitySpinRateDegPerSec).toFloat() % 360f

        return templates.map { t ->
            val az = (t.azimBase + driftDeg + randomSmallDrift()).coerceIn(0f, 359.9f)
            val elev = (t.elevBase + randomSmallDrift() * 0.3f).coerceIn(5f, 85f)
            val cn0 = (38f + gauss() * noiseCn0Sigma).coerceIn(20f, 55f)
            // 约 15% 概率把 usedInFix 置 false（模拟遮挡）
            val usedInFix = cn0 > 25f && Math.random() > 0.08
            GnssSatellite(
                svid = t.svid,
                constellationType = t.constellation,
                cn0DbHz = cn0,
                elevationDegrees = elev,
                azimuthDegrees = az,
                usedInFix = usedInFix,
                carrierFrequencyHz = t.carrierHz
            )
        }
    }

    // -------------------------------------------------------------------------
    // 内部：均匀分布的默认星座模板
    // -------------------------------------------------------------------------

    companion object {
        const val DEFAULT_COUNT = 10
        const val CONST_GPS = 1
        const val CONST_GLONASS = 3
        const val CONST_GALILEO = 4
        const val CONST_BDS = 5
        const val CONST_QZSS = 4

        /**
         * 按可见卫星数合成均匀分布的星座模板。
         * 默认分布：~70% GPS + 15% GLONASS + 15% BDS/Galileo。
         * 方位用 Fibonacci 球面点均匀分布，仰角用 beta-like 分布。
         */
        private fun buildTemplates(count: Int): List<Template> {
            val result = ArrayList<Template>(count)
            // Fibonacci lattice on sphere
            val goldenAngle = PI * (3 - sqrt(5.0))
            val startSvidGps = 1; val endSvidGps = (count * 0.7).toInt().coerceAtLeast(4)
            val endSvidGlo = endSvidGps + (count * 0.15).toInt().coerceAtLeast(1)
            val endSvidBds = count

            for (i in 0 until count) {
                val (constel, svid) = when {
                    i < endSvidGps -> CONST_GPS to (startSvidGps + i)
                    i < endSvidGlo -> CONST_GLONASS to (i - endSvidGps + 1)
                    else -> CONST_BDS to (i - endSvidGlo + 1)
                }
                val carrierHz = when (constel) {
                    CONST_GLONASS -> 1_602_000_000f
                    CONST_BDS -> 1_561_098_000f
                    CONST_GALILEO -> 1_575_420_000f
                    else -> 1_575_420_000f
                }
                // 仰角：集中在 30~70° 范围
                val elev = (30 + (Math.random() * 40)).toFloat()
                // 方位：均匀 0~360
                val azim = (i * (360.0 / count) + Math.random() * 10 - 5).toFloat()
                result.add(Template(svid, constel, elev, azim, carrierHz))
            }
            return result
        }

        private fun gauss(): Float {
            // Box-Muller
            var u1 = Math.random(); var u2 = Math.random()
            while (u1 == 0.0) { u1 = Math.random() }
            return (sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * PI * u2)).toFloat()
        }

        private fun randomSmallDrift(): Float = (Math.random() - 0.5).toFloat() * 5f
    }
}
