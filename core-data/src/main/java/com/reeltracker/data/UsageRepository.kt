package com.reeltracker.data

import com.reeltracker.limits.Slice
import com.reeltracker.limits.TrackedApp
import com.reeltracker.limits.UsageDay
import com.reeltracker.limits.UsageStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId

/** History of time per app, for the Today screen. The live limits do not read from here. */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageRepository internal constructor(private val dao: UsageDao) {

    /** [app]'s usage today (4 AM to 4 AM), recomputed whenever a slice is written or the day turns. */
    fun today(app: TrackedApp): Flow<UsageStats> = dayStarts().flatMapLatest { start ->
        val zone = ZoneId.systemDefault()
        dao.since(app.id, start).map { rows ->
            val slices = rows.map { Slice(maxOf(it.startedAt, start), it.endedAt) }.filter { it.durationMs > 0 }
            UsageStats.from(slices) { Instant.ofEpochMilli(it).atZone(zone).hour }
        }
    }

    suspend fun open(app: TrackedApp, at: Long): Long = dao.insert(UsageSlice(startedAt = at, endedAt = at, app = app.id))

    suspend fun extend(id: Long, end: Long) = dao.setEnd(id, end)

    /** Emits the start of the current usage day, then again at each 4 AM. */
    private fun dayStarts(): Flow<Long> = flow {
        while (true) {
            val day = UsageDay.of(System.currentTimeMillis())
            emit(UsageDay.startOf(day))
            delay((UsageDay.startOf(day + 1) - System.currentTimeMillis()).coerceAtLeast(0) + 1_000)
        }
    }.distinctUntilChanged()
}
