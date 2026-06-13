package com.lazybody.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "location_history")
data class LocationHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "location")
    val location: String,

    @ColumnInfo(name = "longitude_wgs84")
    val longitudeWgs84: String,

    @ColumnInfo(name = "latitude_wgs84")
    val latitudeWgs84: String,

    @ColumnInfo(name = "longitude_custom")
    val longitudeCustom: String,

    @ColumnInfo(name = "latitude_custom")
    val latitudeCustom: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long
)
