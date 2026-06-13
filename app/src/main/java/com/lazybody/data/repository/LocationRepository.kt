package com.lazybody.data.repository

import androidx.lifecycle.LiveData
import com.lazybody.data.database.dao.LocationHistoryDao
import com.lazybody.data.database.entity.LocationHistoryEntity

class LocationRepository(private val locationHistoryDao: LocationHistoryDao) {

    val allLocations: LiveData<List<LocationHistoryEntity>> = locationHistoryDao.getAllLocations()

    suspend fun getLatestLocation(): LocationHistoryEntity? {
        return locationHistoryDao.getLatestLocation()
    }

    suspend fun insert(location: LocationHistoryEntity): Long {
        return locationHistoryDao.insertLocation(location)
    }

    suspend fun update(location: LocationHistoryEntity) {
        locationHistoryDao.updateLocation(location)
    }

    suspend fun delete(location: LocationHistoryEntity) {
        locationHistoryDao.deleteLocation(location)
    }

    suspend fun deleteAll() {
        locationHistoryDao.deleteAllLocations()
    }
}
