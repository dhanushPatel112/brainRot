package com.reeltracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Store events, derive counters, never store a counter. */
@Entity(
    tableName = "reel_events",
    indices = [Index(value = ["sessionId", "key"], unique = true), Index("wallClock")],
)
data class ReelEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val key: Int,
    val wallClock: Long,        // System.currentTimeMillis()
    val strategyId: String,
)

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long?,         // null == open
)

/**
 * One stretch of counted Instagram time (in front, not covered by a pause or popup).
 * Written when it starts and its end pushed forward while it runs, so a killed process loses
 * at most one flush interval.
 */
@Entity(tableName = "usage_slices", indices = [Index("startedAt")])
data class UsageSlice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
)
