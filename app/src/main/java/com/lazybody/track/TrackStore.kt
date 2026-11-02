package com.lazybody.track

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val alt: Double,
    val bearing: Float,
    val speed: Float,
    val time: Long
)

data class TrackRecord(
    val id: String,
    val name: String,
    val startTime: Long,
    val endTime: Long,
    val distance: Double,
    val points: List<TrackPoint>
)

object TrackStore {
    private fun directory(context: Context): File = File(context.filesDir, "tracks").apply { mkdirs() }

    @JvmStatic
    fun list(context: Context): List<TrackRecord> = directory(context).listFiles { file ->
        file.name.endsWith(".json")
    }?.mapNotNull { read(it) }?.sortedByDescending { it.startTime } ?: emptyList()

    @JvmStatic
    fun load(context: Context, id: String): TrackRecord? {
        if (!id.matches(Regex("[a-zA-Z0-9_-]+"))) return null
        return read(File(directory(context), "$id.json"))
    }

    @JvmStatic
    fun delete(context: Context, id: String): Boolean {
        if (!id.matches(Regex("[a-zA-Z0-9_-]+"))) return false
        return File(directory(context), "$id.json").delete()
    }

    @JvmStatic
    fun deleteAll(context: Context) {
        directory(context).listFiles { file -> file.name.endsWith(".json") }?.forEach { it.delete() }
    }

    internal fun save(context: Context, record: TrackRecord) {
        val json = JSONObject()
            .put("version", 1)
            .put("id", record.id)
            .put("name", record.name)
            .put("startTime", record.startTime)
            .put("endTime", record.endTime)
            .put("distance", record.distance)
        val points = JSONArray()
        record.points.forEach { point ->
            points.put(JSONObject()
                .put("lat", point.lat)
                .put("lng", point.lng)
                .put("alt", point.alt)
                .put("bearing", point.bearing.toDouble())
                .put("speed", point.speed.toDouble())
                .put("t", point.time))
        }
        json.put("points", points)
        val target = File(directory(context), "${record.id}.json")
        val temp = File(directory(context), "${record.id}.tmp")
        temp.writeText(json.toString())
        if (target.exists() && !target.delete()) return
        temp.renameTo(target)
    }

    private fun read(file: File): TrackRecord? = try {
        val json = JSONObject(file.readText())
        val array = json.getJSONArray("points")
        val points = ArrayList<TrackPoint>(array.length())
        for (i in 0 until array.length()) {
            val point = array.getJSONObject(i)
            points.add(TrackPoint(
                point.getDouble("lat"), point.getDouble("lng"), point.optDouble("alt"),
                point.optDouble("bearing").toFloat(), point.optDouble("speed").toFloat(),
                point.getLong("t")
            ))
        }
        TrackRecord(
            json.getString("id"), json.optString("name", "模拟轨迹"),
            json.getLong("startTime"), json.getLong("endTime"),
            json.optDouble("distance"), points
        )
    } catch (_: Exception) {
        null
    }
}

internal class TrackRecorder(private val context: Context) {
    private val points = ArrayList<TrackPoint>()
    private var id = ""
    private var name = ""
    private var startTime = 0L
    private var distance = 0.0

    @Synchronized
    fun begin(lat: Double, lng: Double, alt: Double) {
        if (id.isNotEmpty()) return
        startTime = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(startTime))
        id = "track_$stamp"
        name = "模拟轨迹 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(startTime))
        points.add(TrackPoint(lat, lng, alt, 0f, 0f, startTime))
    }

    @Synchronized
    fun sample(lat: Double, lng: Double, alt: Double, bearing: Float, speed: Float, force: Boolean = false) {
        if (id.isEmpty()) return
        val now = System.currentTimeMillis()
        val last = points.last()
        if (!force && now - last.time < 1000L) return
        val segment = distance(last.lat, last.lng, lat, lng)
        if (!force && segment < 2.0 && now - last.time < 10_000L) return
        if (force && segment < 0.05 && now - last.time < 1000L) return
        distance += segment
        points.add(TrackPoint(lat, lng, alt, bearing, speed, now))
    }

    @Synchronized
    fun finish(lat: Double, lng: Double, alt: Double, bearing: Float, speed: Float) {
        if (id.isEmpty()) return
        sample(lat, lng, alt, bearing, speed, true)
        TrackStore.save(context, TrackRecord(id, name, startTime, System.currentTimeMillis(), distance, points.toList()))
        id = ""
        points.clear()
    }

    private fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, result)
        return result[0].toDouble()
    }
}
