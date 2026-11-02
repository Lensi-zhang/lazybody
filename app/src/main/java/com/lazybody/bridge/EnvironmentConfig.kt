package com.lazybody.bridge

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * WiFi / 基站环境伪造配置。
 *
 * 三个模式：
 *  - passthrough（默认）: 不 hook，让 App 读到真实扫描结果
 *  - hide: 伪造为空，让 App 看不到任何 WiFi/基站（反检测常用）
 *  - forge: 返回我们预设的 fake 列表
 *
 * 设计来源：AnyWhere v2.11.1 xposed/config/m.java 中 `wifi_mode` / `cell_mode` /
 * `wifi_networks` / `cell_towers` 等 SP 键。
 */
data class EnvironmentConfig(
    val wifiMode: Mode = Mode.PASSTHROUGH,
    val wifiNetworks: List<WifiEntry> = emptyList(),
    val wifiConnectedBssid: String = "",
    val cellMode: Mode = Mode.PASSTHROUGH,
    val cellTowers: List<CellEntry> = emptyList(),
    val cellPrimaryId: String = "",
    val cellNeighborIds: List<String> = emptyList()
) {
    enum class Mode { PASSTHROUGH, HIDE, FORGE }

    fun toJson(): JSONObject = JSONObject().apply {
        put("wifiMode", wifiMode.name)
        put("wifiNetworks", JSONArray().apply {
            wifiNetworks.forEach { put(it.toJson()) }
        })
        put("wifiConnectedBssid", wifiConnectedBssid)
        put("cellMode", cellMode.name)
        put("cellTowers", JSONArray().apply {
            cellTowers.forEach { put(it.toJson()) }
        })
        put("cellPrimaryId", cellPrimaryId)
        put("cellNeighborIds", JSONArray().apply {
            cellNeighborIds.forEach { put(it) }
        })
    }

    companion object {
        val DEFAULT = EnvironmentConfig()

        private fun parseMode(s: String): Mode = try { Mode.valueOf(s) } catch (_: Throwable) { Mode.PASSTHROUGH }

        fun fromJson(raw: String?): EnvironmentConfig {
            if (raw.isNullOrBlank()) return DEFAULT
            return try {
                val jo = org.json.JSONObject(raw)
                EnvironmentConfig(
                    wifiMode = parseMode(jo.optString("wifiMode", "PASSTHROUGH")),
                    wifiNetworks = run {
                        val arr = jo.optJSONArray("wifiNetworks") ?: JSONArray()
                        (0 until arr.length()).map { WifiEntry.fromJson(arr.getJSONObject(it)) }
                    },
                    wifiConnectedBssid = jo.optString("wifiConnectedBssid", ""),
                    cellMode = parseMode(jo.optString("cellMode", "PASSTHROUGH")),
                    cellTowers = run {
                        val arr = jo.optJSONArray("cellTowers") ?: JSONArray()
                        (0 until arr.length()).map { CellEntry.fromJson(arr.getJSONObject(it)) }
                    },
                    cellPrimaryId = jo.optString("cellPrimaryId", ""),
                    cellNeighborIds = run {
                        val arr = jo.optJSONArray("cellNeighborIds") ?: JSONArray()
                        (0 until arr.length()).map { arr.getString(it) }
                    }
                )
            } catch (_: Throwable) { DEFAULT }
        }
    }
}

/**
 * App 进程侧写入、Xposed 模块侧读取环境伪造配置的帮助类。
 *
 * 同 [StateBridge]，走 SharedPreferences + 节流写入。
 */
class EnvironmentConfigStore(context: Context) {

    private val sp: SharedPreferences = context.applicationContext
        .getSharedPreferences(StateBridge.PREFS_NAME, Context.MODE_PRIVATE)

    /** App 侧：把整个 EnvironmentConfig 落盘。 */
    fun save(config: EnvironmentConfig) {
        sp.edit()
            .putString(KEY_CONFIG, config.toJson().toString())
            .putString(KEY_WIFI_MODE, config.wifiMode.name)
            .putString(KEY_CELL_MODE, config.cellMode.name)
            .commit()
    }

    /** App 侧：单独切 WiFi 模式（便捷 API）。 */
    fun setWifiMode(mode: EnvironmentConfig.Mode) {
        val cur = load()
        save(cur.copy(wifiMode = mode))
    }

    /** App 侧：单独切 Cell 模式。 */
    fun setCellMode(mode: EnvironmentConfig.Mode) {
        val cur = load()
        save(cur.copy(cellMode = mode))
    }

    /** 读当前环境配置。 */
    fun load(): EnvironmentConfig =
        EnvironmentConfig.fromJson(sp.getString(KEY_CONFIG, null))

    companion object {
        const val KEY_CONFIG = "xposed_env_config"
        const val KEY_WIFI_MODE = "xposed_wifi_mode"
        const val KEY_CELL_MODE = "xposed_cell_mode"
    }
}
