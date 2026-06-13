package com.lazybody.data.database.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.lazybody.data.database.entity.SearchHistoryEntity

@Dao
interface SearchHistoryDao {

    @Query("SELECT * FROM search_history ORDER BY timestamp DESC")
    fun getAllSearches(): LiveData<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history WHERE `key` = :key LIMIT 1")
    suspend fun getSearchByKey(key: String): SearchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearch(search: SearchHistoryEntity): Long

    @Delete
    suspend fun deleteSearch(search: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE `key` = :key")
    suspend fun deleteSearchByKey(key: String)

    @Query("DELETE FROM search_history")
    suspend fun deleteAllSearches()
}
