package com.lazybody.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lazybody.data.database.AppDatabase
import com.lazybody.data.database.entity.LocationHistoryEntity
import com.lazybody.data.repository.LocationRepository
import com.lazybody.data.repository.SearchRepository
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val locationRepository: LocationRepository
    private val searchRepository: SearchRepository

    private val _currentMarkerPosition = MutableLiveData<GeoPoint?>()
    val currentMarkerPosition: LiveData<GeoPoint?> = _currentMarkerPosition

    private val _currentMarkerName = MutableLiveData<String?>()
    val currentMarkerName: LiveData<String?> = _currentMarkerName

    private val _isMockServiceRunning = MutableLiveData(false)
    val isMockServiceRunning: LiveData<Boolean> = _isMockServiceRunning

    private val _mapZoomLevel = MutableLiveData(16.0)
    val mapZoomLevel: LiveData<Double> = _mapZoomLevel

    val locationHistory: LiveData<List<LocationHistoryEntity>>

    init {
        val database = AppDatabase.getDatabase(application)
        locationRepository = LocationRepository(database.locationHistoryDao())
        searchRepository = SearchRepository(database.searchHistoryDao())

        locationHistory = locationRepository.allLocations
    }

    fun setMarkerPosition(geoPoint: GeoPoint) {
        _currentMarkerPosition.value = geoPoint
    }

    fun setMarkerName(name: String?) {
        _currentMarkerName.value = name
    }

    fun setMockServiceRunning(running: Boolean) {
        _isMockServiceRunning.value = running
    }

    fun setMapZoomLevel(zoom: Double) {
        _mapZoomLevel.value = zoom
    }

    fun saveLocation(lng: Double, lat: Double, name: String = "Unknown Location") {
        viewModelScope.launch {
            val entity = LocationHistoryEntity(
                location = name,
                longitudeWgs84 = lng.toString(),
                latitudeWgs84 = lat.toString(),
                longitudeCustom = lng.toString(),
                latitudeCustom = lat.toString(),
                timestamp = System.currentTimeMillis() / 1000
            )
            locationRepository.insert(entity)
        }
    }

    suspend fun getLatestLocation(): LocationHistoryEntity? {
        return locationRepository.getLatestLocation()
    }
}
