package com.reeltracker.limits

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class UsageStatsTest {

    private val india = ZoneId.of("Asia/Kolkata")
    private fun at(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 11, 11, h, m, s).atZone(india).toInstant().toEpochMilli()
    private val hourOf: (Long) -> Int = { Instant.ofEpochMilli(it).atZone(india).hour }

    @Test
    fun `slices closer than the session gap join one session`() {
        val stats = UsageStats.from(
            listOf(
                Slice(at(9, 0), at(9, 5)),
                Slice(at(9, 6), at(9, 8)),   // 1 min away: same session
                Slice(at(9, 20), at(9, 21)), // 12 min away: new session
            ),
            hourOf,
        )
        assertEquals(8 * MIN, stats.totalMs)
        assertEquals(2, stats.sessions.size)
        assertEquals(7 * MIN, stats.sessions[0].activeMs)
        assertEquals(at(9, 8), stats.sessions[0].end)
    }

    @Test
    fun `a slice across an hour is split by local hour, at a half-hour offset`() {
        val stats = UsageStats.from(listOf(Slice(at(9, 50), at(10, 20))), hourOf)
        assertEquals(10, stats.hourlyMinutes[9])
        assertEquals(20, stats.hourlyMinutes[10])
    }

    @Test
    fun `usage day starts at 4 AM`() {
        val oneAm = at(1, 0)
        val fiveAm = at(5, 0)
        assertEquals(UsageDay.of(oneAm, india) + 1, UsageDay.of(fiveAm, india))
        assertEquals(at(4, 0), UsageDay.startOf(UsageDay.of(fiveAm, india), india))
    }

    private companion object {
        const val MIN = 60_000L
    }
}
