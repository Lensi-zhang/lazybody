package com.lazybody.ui.main

import android.content.Context
import com.lazybody.data.database.entity.SearchHistoryEntity
import com.lazybody.data.repository.SearchRepository
import com.lazybody.utils.GoUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.location.Address
import org.osmdroid.bonuspack.location.GeocoderNominatim
import java.util.Locale

class SearchHelper(
    private val context: Context,
    private val searchRepository: SearchRepository,
    private val onLocationFound: (String, String, Double, Double) -> Unit
) {

    private val geocoder = GeocoderNominatim(Locale.getDefault(), context.packageName)

    fun performSearch(query: String, callback: (List<Address>) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val addresses = geocoder.getFromLocationName(query, 10)
                withContext(Dispatchers.Main) {
                    if (!addresses.isNullOrEmpty()) {
                        callback(addresses)
                        saveSearchHistory(query, "搜索关键字", 0)
                    } else {
                        GoUtils.DisplayToast(context, "未找到结果")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    GoUtils.DisplayToast(context, "搜索失败: ${e.message}")
                }
            }
        }
    }

    fun reverseGeocode(lat: Double, lng: Double, callback: (Address?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                withContext(Dispatchers.Main) {
                    callback(addresses?.firstOrNull())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(null)
                }
            }
        }
    }

    private suspend fun saveSearchHistory(key: String, description: String, isLocation: Int) {
        searchRepository.insert(
            SearchHistoryEntity(
                key = key,
                description = description,
                isLocation = isLocation,
                timestamp = System.currentTimeMillis() / 1000,
                longitudeCustom = null,
                latitudeCustom = null,
                longitudeWgs84 = null,
                latitudeWgs84 = null
            )
        )
    }
}
