package com.reeltracker.service

import android.content.Context
import android.content.SharedPreferences
import com.reeltracker.limits.LimitState
import com.reeltracker.limits.Limits

/**
 * The limits you set, and the rules' state, in SharedPreferences.
 *
 * Changing a limit never applies today: the new value waits as "pending" and becomes active at
 * the next 4 AM, so the budget can't be raised in a weak moment.
 */
class LimitStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ limits

    /** The limits in force on [day], promoting a pending change once its day has come. */
    fun limits(day: Long): Limits {
        val from = prefs.getLong(PENDING_FROM, NONE)
        if (from != NONE && day >= from) {
            prefs.edit()
                .putInt(BUDGET_MIN, prefs.getInt(PENDING_BUDGET_MIN, DEFAULT_BUDGET_MIN))
                .putInt(CAP_MIN, prefs.getInt(PENDING_CAP_MIN, DEFAULT_CAP_MIN))
                .remove(PENDING_FROM).remove(PENDING_BUDGET_MIN).remove(PENDING_CAP_MIN)
                .apply()
        }
        return active()
    }

    private fun active() = Limits(
        dailyBudgetMs = prefs.getInt(BUDGET_MIN, DEFAULT_BUDGET_MIN) * 60_000L,
        sessionCapMs = prefs.getInt(CAP_MIN, DEFAULT_CAP_MIN) * 60_000L,
    )

    /** The change waiting for tomorrow, if any. */
    fun pending(): Limits? =
        if (prefs.getLong(PENDING_FROM, NONE) == NONE) null
        else Limits(
            dailyBudgetMs = prefs.getInt(PENDING_BUDGET_MIN, DEFAULT_BUDGET_MIN) * 60_000L,
            sessionCapMs = prefs.getInt(PENDING_CAP_MIN, DEFAULT_CAP_MIN) * 60_000L,
        )

    /** Schedules [limits] for the day after [today]. Asking for today's values cancels the change. */
    fun request(limits: Limits, today: Long) {
        limits(today) // promote anything already due, so "active" is current
        val e = prefs.edit()
        if (limits == active()) {
            e.remove(PENDING_FROM).remove(PENDING_BUDGET_MIN).remove(PENDING_CAP_MIN)
        } else {
            e.putLong(PENDING_FROM, today + 1)
                .putInt(PENDING_BUDGET_MIN, (limits.dailyBudgetMs / 60_000).toInt())
                .putInt(PENDING_CAP_MIN, (limits.sessionCapMs / 60_000).toInt())
        }
        e.apply()
    }

    // ------------------------------------------------------------------ state

    /** Saved state, or a fresh one when nothing is saved for [day] yet. */
    fun loadState(day: Long): LimitState {
        val saved = prefs.getLong(S_DAY, NONE)
        if (saved == NONE) return LimitState(day = day)
        return LimitState(
            day = saved,
            usedMs = prefs.getLong(S_USED, 0),
            pausesDone = prefs.getInt(S_PAUSES, 0),
            nextPauseAtUsedMs = prefs.long(S_NEXT_PAUSE),
            pendingPauseS = prefs.getInt(S_PENDING_PAUSE, -1).takeIf { it >= 0 },
            sessionUsedMs = prefs.getLong(S_SESSION, 0),
            nextPopupAtSessionMs = prefs.long(S_NEXT_POPUP),
            popupPending = prefs.getBoolean(S_POPUP, false),
            lastActiveAt = prefs.long(S_LAST_ACTIVE),
            // Not restored: after a restart the service has to see Instagram again to count.
            lastTickAt = null,
            foreground = false,
        ).let { if (it.day != day) it.newDay(day) else it }
    }

    fun saveState(s: LimitState) {
        prefs.edit()
            .putLong(S_DAY, s.day)
            .putLong(S_USED, s.usedMs)
            .putInt(S_PAUSES, s.pausesDone)
            .putLong(S_NEXT_PAUSE, s.nextPauseAtUsedMs ?: NONE)
            .putInt(S_PENDING_PAUSE, s.pendingPauseS ?: -1)
            .putLong(S_SESSION, s.sessionUsedMs)
            .putLong(S_NEXT_POPUP, s.nextPopupAtSessionMs ?: NONE)
            .putBoolean(S_POPUP, s.popupPending)
            .putLong(S_LAST_ACTIVE, s.lastActiveAt ?: NONE)
            .apply()
    }

    private fun SharedPreferences.long(key: String): Long? = getLong(key, NONE).takeIf { it != NONE }

    companion object {
        const val DEFAULT_BUDGET_MIN = 30
        const val DEFAULT_CAP_MIN = 10

        private const val PREFS = "limits"
        private const val NONE = Long.MIN_VALUE

        private const val BUDGET_MIN = "budget_min"
        private const val CAP_MIN = "cap_min"
        private const val PENDING_FROM = "pending_from_day"
        private const val PENDING_BUDGET_MIN = "pending_budget_min"
        private const val PENDING_CAP_MIN = "pending_cap_min"

        private const val S_DAY = "s_day"
        private const val S_USED = "s_used"
        private const val S_PAUSES = "s_pauses"
        private const val S_NEXT_PAUSE = "s_next_pause"
        private const val S_PENDING_PAUSE = "s_pending_pause"
        private const val S_SESSION = "s_session"
        private const val S_NEXT_POPUP = "s_next_popup"
        private const val S_POPUP = "s_popup"
        private const val S_LAST_ACTIVE = "s_last_active"
    }
}
