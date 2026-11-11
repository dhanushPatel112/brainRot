package com.reeltracker.limits

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A usage day runs from 4 AM to 4 AM, so scrolling at 1 AM still counts against yesterday's
 * budget instead of getting a fresh one at midnight.
 */
object UsageDay {
    const val START_HOUR = 4

    /** The day [ms] falls in, as an epoch day. */
    fun of(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(ms).atZone(zone).minusHours(START_HOUR.toLong()).toLocalDate().toEpochDay()

    /** Wall-clock start (4 AM) of [day]. */
    fun startOf(day: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.ofEpochDay(day).atTime(START_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
}
