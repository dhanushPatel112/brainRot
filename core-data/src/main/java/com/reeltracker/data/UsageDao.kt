package com.reeltracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageDao {

    @Insert
    suspend fun insert(slice: UsageSlice): Long

    @Query("UPDATE usage_slices SET endedAt = :end WHERE id = :id")
    suspend fun setEnd(id: Long, end: Long)

    /** Slices that overlap [from, ∞), oldest first. */
    @Query("SELECT * FROM usage_slices WHERE endedAt > :from ORDER BY startedAt")
    fun since(from: Long): Flow<List<UsageSlice>>
}
