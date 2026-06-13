package com.lazybody.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "description")
    val description: String,

    @ColumnInfo(name = "is_location")
    val isLocation: Int,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "longitude_custom")
    val longitudeCustom: String?,

    @ColumnInfo(name = "latitude_custom")
    val latitudeCustom: String?,

    @ColumnInfo(name = "longitude_wgs84")
    val longitudeWgs84: String?,

    @ColumnInfo(name = "latitude_wgs84")
    val latitudeWgs84: String?
)
