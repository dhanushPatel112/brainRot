package com.reeltracker.limits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitEngineTest {

    private val limits = Limits(dailyBudgetMs = 30 * MIN, sessionCapMs = 10 * MIN)

    /** Drives the engine one tick per second, like the service does. */
    private inner class Sim(
        var s: LimitState = LimitState(day = 0),
        val limits: Limits = this@LimitEngineTest.limits,
        var now: Long = 1_000_000L,
        var day: Long = 0,
    ) {
        /** Opens Instagram (if it isn't open) at [now], then uses it for [ms]. */
        fun use(ms: Long) {
            if (!s.foreground) s = LimitEngine.tick(s, limits, now, day, foreground = true)
            run(ms, foreground = true)
        }
        fun away(ms: Long) = run(ms, foreground = false)
        private fun run(ms: Long, foreground: Boolean) {
            val end = now + ms
            while (now < end) {
                now += 1_000
                s = LimitEngine.tick(s, limits, now, day, foreground)
            }
        }
        fun screen() = LimitEngine.screen(s, limits)
        fun finishPause() { s = LimitEngine.pauseCompleted(s) }
        fun dismissPopup() { s = LimitEngine.popupDismissed(s) }
    }

    @Test
    fun `counts only foreground time`() {
        val sim = Sim()
        sim.use(5 * MIN)
        sim.away(30 * MIN)
        sim.use(2 * MIN)
        assertEquals(7 * MIN, sim.s.usedMs)
    }

    @Test
    fun `badge shows today and session, no grey or block under the limits`() {
        val sim = Sim()
        sim.use(4 * MIN)
        val screen = sim.screen()
        assertFalse(screen.grey)
        assertNull(screen.block)
        assertEquals("4m · 4m", screen.badge!!.text)
        sim.away(1_000)
        assertEquals(Screen.NONE, sim.screen())
    }

    @Test
    fun `session cap greys the screen and asks, then asks again every 5 minutes`() {
        val sim = Sim()
        sim.use(10 * MIN)
        assertTrue(sim.screen().grey)
        assertEquals(Block.SessionPopup, sim.screen().block)

        // Time behind the popup doesn't count.
        val used = sim.s.usedMs
        sim.use(MIN)
        assertEquals(used, sim.s.usedMs)

        sim.dismissPopup()
        assertNull(sim.screen().block)
        assertTrue("grey stays for the rest of the session", sim.screen().grey)
        sim.use(5 * MIN - 2_000)
        assertNull(sim.screen().block)
        sim.use(3_000)
        assertEquals(Block.SessionPopup, sim.screen().block)
    }

    @Test
    fun `two minutes away ends the session and lifts the session grey`() {
        val sim = Sim()
        sim.use(10 * MIN)
        sim.dismissPopup()
        sim.away(LimitEngine.SESSION_GAP_MS)
        sim.use(1_000)
        assertFalse(sim.screen().grey)
        assertEquals(1_000L, sim.s.sessionUsedMs)
    }

    @Test
    fun `a short trip out keeps the session going`() {
        val sim = Sim()
        sim.use(9 * MIN)
        sim.away(60_000)
        sim.use(MIN)
        assertEquals(Block.SessionPopup, sim.screen().block)
    }

    @Test
    fun `leaving does not get rid of an unanswered popup`() {
        val sim = Sim()
        sim.use(10 * MIN)
        sim.away(30_000)
        sim.use(1_000)
        assertEquals(Block.SessionPopup, sim.screen().block)
    }

    @Test
    fun `over budget, Instagram is grey with no way to turn it off`() {
        val sim = Sim()
        repeat(4) {
            sim.use(8 * MIN)
            sim.away(LimitEngine.SESSION_GAP_MS)
        }
        assertTrue(sim.s.usedMs >= 30 * MIN)
        sim.use(1_000)
        assertTrue(sim.screen().grey)
    }

    @Test
    fun `crossing the budget costs a 10s pause at once, then longer ones every 5 minutes`() {
        // Long session cap, so the session popup doesn't step in front of the third pause.
        val sim = Sim(LimitState(day = 0, usedMs = 30 * MIN - 5_000), limits.copy(sessionCapMs = 60 * MIN))
        sim.use(5_000)
        assertEquals(Block.Pause(10), sim.screen().block)

        sim.finishPause()
        sim.use(5 * MIN - 2_000)
        assertNull(sim.screen().block)
        sim.use(3_000)
        assertEquals(Block.Pause(20), sim.screen().block)

        sim.finishPause()
        sim.use(5 * MIN + 1_000)
        assertEquals(Block.Pause(30), sim.screen().block)
    }

    @Test
    fun `opening Instagram over budget costs a pause, and pauses keep growing across opens`() {
        val sim = Sim(LimitState(day = 0, usedMs = 40 * MIN, pausesDone = 2, nextPauseAtUsedMs = 45 * MIN))
        sim.use(1_000)
        assertEquals(Block.Pause(30), sim.screen().block)
        sim.finishPause()
        sim.away(LimitEngine.LAUNCH_GRACE_MS)
        sim.use(1_000)
        assertEquals(Block.Pause(40), sim.screen().block)
    }

    @Test
    fun `a quick trip out and back does not cost a launch pause`() {
        val sim = Sim(LimitState(day = 0, usedMs = 40 * MIN, nextPauseAtUsedMs = 44 * MIN))
        sim.use(1_000)
        sim.finishPause()
        sim.use(1_000)
        sim.away(10_000)
        sim.use(1_000)
        assertNull(sim.screen().block)
    }

    @Test
    fun `walking out of a pause leaves it owed, at the same length`() {
        val sim = Sim(LimitState(day = 0, usedMs = 40 * MIN, pausesDone = 1))
        sim.use(1_000)
        assertEquals(Block.Pause(20), sim.screen().block)
        sim.away(5_000) // "Never mind", then straight back
        sim.use(1_000)
        assertEquals(Block.Pause(20), sim.screen().block)
    }

    @Test
    fun `pauses top out at two minutes`() {
        assertEquals(10, LimitEngine.pauseSeconds(0))
        assertEquals(20, LimitEngine.pauseSeconds(1))
        assertEquals(120, LimitEngine.pauseSeconds(11))
        assertEquals(120, LimitEngine.pauseSeconds(50))
    }

    @Test
    fun `a pause comes before the session popup when both are due`() {
        val sim = Sim(LimitState(day = 0, usedMs = 20 * MIN))
        sim.use(10 * MIN)
        assertEquals(Block.Pause(10), sim.screen().block)
        sim.finishPause()
        sim.use(1_000)
        assertEquals(Block.SessionPopup, sim.screen().block)
    }

    @Test
    fun `a new day resets the budget and pauses`() {
        val sim = Sim(LimitState(day = 0, usedMs = 50 * MIN, pausesDone = 5, pendingPauseS = 60))
        sim.day = 1
        sim.away(1_000)
        assertEquals(0L, sim.s.usedMs)
        assertEquals(0, sim.s.pausesDone)
        assertNull(sim.s.pendingPauseS)
        sim.use(MIN)
        assertFalse(sim.screen().grey)
    }

    @Test
    fun `a sleeping service does not count the gap as use`() {
        val sim = Sim()
        sim.use(1_000)
        sim.now += 10 * MIN
        sim.s = LimitEngine.tick(sim.s, limits, sim.now, 0, foreground = true)
        assertTrue(sim.s.usedMs <= 1_000 + LimitEngine.MAX_TICK_MS)
    }

    @Test
    fun `badge text formats hours`() {
        assertEquals("0m · 0m", Badge(0, 0).text)
        assertEquals("1h 05m · 10m", Badge(65 * MIN, 10 * MIN).text)
    }

    private companion object {
        const val MIN = 60_000L
    }
}
