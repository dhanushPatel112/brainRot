package com.reeltracker.limits

/** One stretch of counted time in one app: in front, not covered by a pause or popup. */
data class Slice(val start: Long, val end: Long) {
    val durationMs: Long get() = end - start
}

/** Slices that are less than [LimitEngine.SESSION_GAP_MS] apart, joined into one session. */
data class UsageSession(val start: Long, val end: Long, val activeMs: Long)

/** A day's history for the Today screen, derived from slices when read; nothing is cached. */
data class UsageStats(
    val totalMs: Long,
    /** Minutes per hour of the day, 24 buckets, local time. */
    val hourlyMinutes: List<Int>,
    val sessions: List<UsageSession>,
) {
    companion object {
        val EMPTY = UsageStats(0, List(24) { 0 }, emptyList())

        /**
         * @param slices chronological, already clipped to the day
         * @param hourOf maps a wall-clock time to its local hour; injected to keep this pure
         */
        fun from(slices: List<Slice>, hourOf: (Long) -> Int): UsageStats {
            if (slices.isEmpty()) return EMPTY
            val hourlyMs = LongArray(24)
            for (s in slices) {
                // Cut at every half hour, so each piece sits inside one local hour (see STEP_MS).
                var t = s.start
                while (t < s.end) {
                    val end = minOf(s.end, (t / STEP_MS + 1) * STEP_MS)
                    hourlyMs[hourOf(t)] += end - t
                    t = end
                }
            }
            val sessions = ArrayList<UsageSession>()
            var start = slices[0].start
            var end = slices[0].end
            var active = 0L
            for (s in slices) {
                if (s.start - end >= LimitEngine.SESSION_GAP_MS) {
                    sessions += UsageSession(start, end, active)
                    start = s.start
                    active = 0
                }
                active += s.durationMs
                end = maxOf(end, s.end)
            }
            sessions += UsageSession(start, end, active)
            return UsageStats(
                totalMs = slices.sumOf { it.durationMs },
                hourlyMinutes = hourlyMs.map { ((it + 30_000) / 60_000).toInt() },
                sessions = sessions,
            )
        }

        /**
         * Local hours start on a UTC half hour in India (+5:30) and on a UTC hour in most other
         * zones, so cutting slices at every UTC half hour keeps each piece inside one local hour.
         */
        private const val STEP_MS = 30 * 60_000L
    }
}
