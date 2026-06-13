package com.lazybody.data.database.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.lazybody.data.database.entity.LocationHistoryEntity

@Dao
interface LocationHistoryDao {

    @Query("SELECT * FROM location_history ORDER BY timestamp DESC")
    fun getAllLocations(): LiveData<List<LocationHistoryEntity>>

    @Query("SELECT * FROM location_history ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestLocation(): LocationHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(location: LocationHistoryEntity): Long

    @Update
    suspend fun updateLocation(location: LocationHistoryEntity)

    @Delete
    suspend fun deleteLocation(location: LocationHistoryEntity)

    @Query("DELETE FROM location_history")
    suspend fun deleteAllLocations()
}
