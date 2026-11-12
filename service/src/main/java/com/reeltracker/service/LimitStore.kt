package com.reeltracker.service

import android.content.Context
import android.content.SharedPreferences
import com.reeltracker.limits.LimitState
import com.reeltracker.limits.Limits
import com.reeltracker.limits.TrackedApp

/**
 * One app's limits, and its rules' state, in SharedPreferences.
 *
 * Changing a limit never applies today: the new value waits as "pending" and becomes active at
 * the next 4 AM, so the budget can't be raised in a weak moment.
 *
 * Instagram keeps the unprefixed keys from before YouTube was added, so an update keeps its
 * limits and today's count. Every other app prefixes its keys with its id.
 */
class LimitStore(context: Context, val app: TrackedApp) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val prefix = if (app == TrackedApp.INSTAGRAM) "" else "${app.id}_"

    private val defaultBudgetMin = app.defaultBudgetMin
    private val defaultCapMin = app.defaultCapMin

    // ------------------------------------------------------------------ limits

    /** The limits in force on [day], promoting a pending change once its day has come. */
    fun limits(day: Long): Limits {
        val from = prefs.getLong(k(PENDING_FROM), NONE)
        if (from != NONE && day >= from) {
            prefs.edit()
                .putInt(k(BUDGET_MIN), prefs.getInt(k(PENDING_BUDGET_MIN), defaultBudgetMin))
                .putInt(k(CAP_MIN), prefs.getInt(k(PENDING_CAP_MIN), defaultCapMin))
                .remove(k(PENDING_FROM)).remove(k(PENDING_BUDGET_MIN)).remove(k(PENDING_CAP_MIN))
                .apply()
        }
        return active()
    }

    private fun active() = Limits(
        dailyBudgetMs = prefs.getInt(k(BUDGET_MIN), defaultBudgetMin) * 60_000L,
        sessionCapMs = prefs.getInt(k(CAP_MIN), defaultCapMin) * 60_000L,
    )

    /** The change waiting for tomorrow, if any. */
    fun pending(): Limits? =
        if (prefs.getLong(k(PENDING_FROM), NONE) == NONE) null
        else Limits(
            dailyBudgetMs = prefs.getInt(k(PENDING_BUDGET_MIN), defaultBudgetMin) * 60_000L,
            sessionCapMs = prefs.getInt(k(PENDING_CAP_MIN), defaultCapMin) * 60_000L,
        )

    /** Schedules [limits] for the day after [today]. Asking for today's values cancels the change. */
    fun request(limits: Limits, today: Long) {
        limits(today) // promote anything already due, so "active" is current
        val e = prefs.edit()
        if (limits == active()) {
            e.remove(k(PENDING_FROM)).remove(k(PENDING_BUDGET_MIN)).remove(k(PENDING_CAP_MIN))
        } else {
            e.putLong(k(PENDING_FROM), today + 1)
                .putInt(k(PENDING_BUDGET_MIN), (limits.dailyBudgetMs / 60_000).toInt())
                .putInt(k(PENDING_CAP_MIN), (limits.sessionCapMs / 60_000).toInt())
        }
        e.apply()
    }

    // ------------------------------------------------------------------ state

    /** Saved state, or a fresh one when nothing is saved for [day] yet. */
    fun loadState(day: Long): LimitState {
        val saved = prefs.getLong(k(S_DAY), NONE)
        if (saved == NONE) return LimitState(day = day)
        return LimitState(
            day = saved,
            usedMs = prefs.getLong(k(S_USED), 0),
            pausesDone = prefs.getInt(k(S_PAUSES), 0),
            nextPauseAtUsedMs = prefs.long(k(S_NEXT_PAUSE)),
            pendingPauseS = prefs.getInt(k(S_PENDING_PAUSE), -1).takeIf { it >= 0 },
            sessionUsedMs = prefs.getLong(k(S_SESSION), 0),
            nextPopupAtSessionMs = prefs.long(k(S_NEXT_POPUP)),
            popupPending = prefs.getBoolean(k(S_POPUP), false),
            lastActiveAt = prefs.long(k(S_LAST_ACTIVE)),
            // Not restored: after a restart the service has to see the app again to count.
            lastTickAt = null,
            foreground = false,
        ).let { if (it.day != day) it.newDay(day) else it }
    }

    fun saveState(s: LimitState) {
        prefs.edit()
            .putLong(k(S_DAY), s.day)
            .putLong(k(S_USED), s.usedMs)
            .putInt(k(S_PAUSES), s.pausesDone)
            .putLong(k(S_NEXT_PAUSE), s.nextPauseAtUsedMs ?: NONE)
            .putInt(k(S_PENDING_PAUSE), s.pendingPauseS ?: -1)
            .putLong(k(S_SESSION), s.sessionUsedMs)
            .putLong(k(S_NEXT_POPUP), s.nextPopupAtSessionMs ?: NONE)
            .putBoolean(k(S_POPUP), s.popupPending)
            .putLong(k(S_LAST_ACTIVE), s.lastActiveAt ?: NONE)
            .apply()
    }

    private fun k(key: String) = prefix + key

    private fun SharedPreferences.long(key: String): Long? = getLong(key, NONE).takeIf { it != NONE }

    companion object {
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
