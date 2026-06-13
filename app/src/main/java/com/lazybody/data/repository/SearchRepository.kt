package com.lazybody.data.repository

import androidx.lifecycle.LiveData
import com.lazybody.data.database.dao.SearchHistoryDao
import com.lazybody.data.database.entity.SearchHistoryEntity

class SearchRepository(private val searchHistoryDao: SearchHistoryDao) {

    val allSearches: LiveData<List<SearchHistoryEntity>> = searchHistoryDao.getAllSearches()

    suspend fun getSearchByKey(key: String): SearchHistoryEntity? {
        return searchHistoryDao.getSearchByKey(key)
    }

    suspend fun insert(search: SearchHistoryEntity): Long {
        return searchHistoryDao.insertSearch(search)
    }

    suspend fun delete(search: SearchHistoryEntity) {
        searchHistoryDao.deleteSearch(search)
    }

    suspend fun deleteByKey(key: String) {
        searchHistoryDao.deleteSearchByKey(key)
    }

    suspend fun deleteAll() {
        searchHistoryDao.deleteAllSearches()
    }
}
