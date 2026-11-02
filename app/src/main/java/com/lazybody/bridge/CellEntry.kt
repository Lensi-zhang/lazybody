package com.lazybody.bridge

import org.json.JSONObject

/**
 * 可伪造的基站条目。
 *
 * 字段覆盖 CellInfoGsm / CellInfoWcdma / CellInfoLte / CellInfoNr 的公共部分，
 * 同时放入通用的"标识"（id 字符串），用于区分 primary（主小区）和 neighbors（邻区）。
 */
data class CellEntry(
    val id: String,                // 唯一标识，由调用方自由命名
    val primary: Boolean,          // 是否为服务主小区
    val mcc: Int = 460,            // 中国
    val mnc: Int = 1,              // 联通
    val tac: Int = 1,              // 跟踪区
    val cid: Int = 1,              // 小区 ID（LTE/NR）
    val pci: Int = 0,              // 物理小区 ID
    val frequency: Int = 1800,     // 频段（MHz）
    val signal: Int = -80,         // dBm
    val rat: String = "LTE"        // GSM / WCDMA / LTE / NR
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("primary", primary)
        put("mcc", mcc); put("mnc", mnc)
        put("tac", tac); put("cid", cid)
        put("pci", pci); put("frequency", frequency)
        put("signal", signal); put("rat", rat)
    }

    companion object {
        fun fromJson(jo: JSONObject): CellEntry = CellEntry(
            id = jo.optString("id", "cell"),
            primary = jo.optBoolean("primary", false),
            mcc = jo.optInt("mcc", 460),
            mnc = jo.optInt("mnc", 1),
            tac = jo.optInt("tac", 1),
            cid = jo.optInt("cid", 1),
            pci = jo.optInt("pci", 0),
            frequency = jo.optInt("frequency", 1800),
            signal = jo.optInt("signal", -80),
            rat = jo.optString("rat", "LTE")
        )
    }
}
