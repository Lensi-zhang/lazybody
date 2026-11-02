package com.lazybody.bridge

import org.json.JSONObject

/**
 * 可伪造的 WiFi 接入点条目。
 *
 * 字段对应 android.net.wifi.ScanResult 的公共 API（不同 Android 版本字段略有差异，
 * 这里只覆盖主要几个。其余字段在反射构造时走默认值）。
 */
data class WifiEntry(
    val ssid: String,
    val bssid: String,
    val capabilities: String = "WPA2-PSK-CCMP",
    val level: Int = -50,          // dBm，典型范围 -100..-30
    val frequencyMHz: Int = 2412,  // 2.4GHz: 2412~2484，5GHz: 5180~5825
    val timestampUs: Long = 0L     // 扫描时间戳（微秒），0 表示当前时刻
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ssid", ssid)
        put("bssid", bssid)
        put("capabilities", capabilities)
        put("level", level)
        put("frequencyMHz", frequencyMHz)
        put("timestampUs", timestampUs)
    }

    companion object {
        fun fromJson(jo: JSONObject): WifiEntry = WifiEntry(
            ssid = jo.optString("ssid", "WiFi"),
            bssid = jo.optString("bssid", "00:11:22:33:44:55"),
            capabilities = jo.optString("capabilities", "WPA2-PSK-CCMP"),
            level = jo.optInt("level", -50),
            frequencyMHz = jo.optInt("frequencyMHz", 2412),
            timestampUs = jo.optLong("timestampUs", 0L)
        )
    }
}
