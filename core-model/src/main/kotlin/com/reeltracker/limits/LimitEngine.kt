package com.reeltracker.limits

/**
 * The rules, and nothing else: no Android, no clock, no storage. The service feeds it one
 * [tick] per second and draws whatever [screen] says. Everything here is a pure function of
 * (state, limits, time), so the whole policy runs in plain JVM tests.
 *
 * Time only counts while Instagram is in front AND no pause or popup is covering it, so sitting
 * on a pause screen never eats into the budget.
 */
object LimitEngine {

    /** Away from Instagram for at least this long ends the session (resets the 10-min cap). */
    const val SESSION_GAP_MS = 2 * 60_000L

    /** After the budget, a pause comes back after every this-much counted use. */
    const val PAUSE_EVERY_MS = 5 * 60_000L

    /** After the session cap is dismissed, the popup comes back after this much more use. */
    const val POPUP_EVERY_MS = 5 * 60_000L

    /** Coming back within this long does not cost a launch pause (copying a link, a quick reply). */
    const val LAUNCH_GRACE_MS = 30_000L

    const val PAUSE_FIRST_S = 10
    const val PAUSE_STEP_S = 10
    const val PAUSE_MAX_S = 120

    /** A gap between ticks longer than this is the service sleeping, not use. */
    const val MAX_TICK_MS = 5_000L

    /** Length of the pause owed after [pausesDone] completed pauses today: 10, 20, 30 … 120 s. */
    fun pauseSeconds(pausesDone: Int): Int =
        (PAUSE_FIRST_S + PAUSE_STEP_S * pausesDone).coerceAtMost(PAUSE_MAX_S)

    /**
     * Advances the state to [now].
     *
     * @param day the usage day [now] falls in (see [UsageDay]); a change resets the daily budget
     * @param foreground Instagram is the app on screen and the screen is on
     */
    fun tick(s: LimitState, limits: Limits, now: Long, day: Long, foreground: Boolean): LimitState {
        var st = if (day != s.day) s.newDay(day) else s

        if (!foreground) {
            return st.copy(foreground = false, lastTickAt = now)
        }

        if (!st.foreground) st = enter(st, limits, now)

        if (st.blocked) {
            // Covered by a pause or popup: the session stays alive, nothing is counted.
            return st.copy(lastActiveAt = now, lastTickAt = now)
        }

        val dt = st.lastTickAt?.let { (now - it).coerceIn(0, MAX_TICK_MS) } ?: 0L
        st = st.copy(
            usedMs = st.usedMs + dt,
            sessionUsedMs = st.sessionUsedMs + dt,
            lastActiveAt = now,
            lastTickAt = now,
        )

        if (st.usedMs >= limits.dailyBudgetMs) {
            // Crossing the budget mid-session costs the first pause right away.
            val due = st.nextPauseAtUsedMs ?: st.usedMs
            st = st.copy(nextPauseAtUsedMs = due)
            if (st.usedMs >= due && st.pendingPauseS == null) {
                st = st.copy(pendingPauseS = pauseSeconds(st.pausesDone))
            }
        }
        val popupAt = st.nextPopupAtSessionMs ?: limits.sessionCapMs
        if (st.sessionUsedMs >= popupAt) st = st.copy(popupPending = true, nextPopupAtSessionMs = popupAt)
        return st
    }

    /** Instagram just came to the front. */
    private fun enter(s: LimitState, limits: Limits, now: Long): LimitState {
        val away = s.lastActiveAt?.let { now - it }
        var st = s.copy(foreground = true)
        if (away == null || away >= SESSION_GAP_MS) {
            st = st.copy(sessionUsedMs = 0, nextPopupAtSessionMs = null, popupPending = false)
        }
        val overBudget = st.usedMs >= limits.dailyBudgetMs
        val freshLaunch = away == null || away >= LAUNCH_GRACE_MS
        if (overBudget && freshLaunch && st.pendingPauseS == null) {
            st = st.copy(pendingPauseS = pauseSeconds(st.pausesDone))
        }
        // Time spent away is not use: the next tick measures from here.
        return st.copy(lastTickAt = now)
    }

    /** The countdown ran out. The next pause is due after [PAUSE_EVERY_MS] more use. */
    fun pauseCompleted(s: LimitState): LimitState =
        if (s.pendingPauseS == null) s
        else s.copy(
            pendingPauseS = null,
            pausesDone = s.pausesDone + 1,
            nextPauseAtUsedMs = s.usedMs + PAUSE_EVERY_MS,
        )

    /** "I still want to waste my time": the popup returns after [POPUP_EVERY_MS] more use. */
    fun popupDismissed(s: LimitState): LimitState =
        if (!s.popupPending) s
        else s.copy(popupPending = false, nextPopupAtSessionMs = s.sessionUsedMs + POPUP_EVERY_MS)

    /** What to draw over Instagram right now. */
    fun screen(s: LimitState, limits: Limits): Screen {
        if (!s.foreground) return Screen.NONE
        val block = when {
            s.pendingPauseS != null -> Block.Pause(s.pendingPauseS)
            s.popupPending -> Block.SessionPopup
            else -> null
        }
        val overBudget = s.usedMs >= limits.dailyBudgetMs
        val overCap = s.sessionUsedMs >= limits.sessionCapMs
        return Screen(
            badge = Badge(s.usedMs, s.sessionUsedMs),
            grey = overBudget || overCap,
            block = block,
        )
    }
}

data class Limits(
    val dailyBudgetMs: Long = 30 * 60_000L,
    val sessionCapMs: Long = 10 * 60_000L,
)

/**
 * Everything the rules remember. Small enough to persist on every change, so killing the app
 * does not wipe a pause that is owed.
 */
data class LimitState(
    val day: Long,
    /** Counted Instagram time on [day]. */
    val usedMs: Long = 0,
    /** Pauses sat through today; sets the length of the next one. */
    val pausesDone: Int = 0,
    /** [usedMs] at which the next periodic pause is due; null until the budget runs out. */
    val nextPauseAtUsedMs: Long? = null,
    /** A pause of this many seconds is owed and shows until it completes. */
    val pendingPauseS: Int? = null,
    val sessionUsedMs: Long = 0,
    /** [sessionUsedMs] at which the next session popup is due; null means the cap itself. */
    val nextPopupAtSessionMs: Long? = null,
    val popupPending: Boolean = false,
    /** Last time Instagram was seen in front; the session gap is measured from here. */
    val lastActiveAt: Long? = null,
    val lastTickAt: Long? = null,
    val foreground: Boolean = false,
) {
    val blocked: Boolean get() = pendingPauseS != null || popupPending

    /** The budget and pauses reset; a session running across the reset carries on. */
    fun newDay(day: Long) = copy(
        day = day,
        usedMs = 0,
        pausesDone = 0,
        nextPauseAtUsedMs = null,
        pendingPauseS = null,
    )
}

data class Screen(val badge: Badge?, val grey: Boolean, val block: Block?) {
    companion object {
        val NONE = Screen(null, false, null)
    }
}

data class Badge(val todayMs: Long, val sessionMs: Long) {
    /** "23m · 4m", "1h 05m · 10m". */
    val text: String get() = "${short(todayMs)} · ${short(sessionMs)}"

    companion object {
        fun short(ms: Long): String {
            val m = ms / 60_000
            return if (m < 60) "${m}m" else "${m / 60}h %02dm".format(m % 60)
        }
    }
}

sealed interface Block {
    data class Pause(val seconds: Int) : Block
    data object SessionPopup : Block
}
