package com.reeltracker.service

import android.content.Context
import com.reeltracker.limits.LimitState
import com.reeltracker.limits.Limits
import com.reeltracker.limits.TrackedApp
import com.reeltracker.limits.UsageDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live view of the rules for the UI and widget, which run in the same process as the service. */
object UsageMonitor {

    data class Snapshot(val state: LimitState, val limits: Limits, val pending: Limits?)

    private val _snapshots = MutableStateFlow<Map<TrackedApp, Snapshot>>(emptyMap())

    /** Pushed by the service every tick, one per app; empty until it has run in this process. */
    val snapshots: StateFlow<Map<TrackedApp, Snapshot>> = _snapshots.asStateFlow()

    @Volatile
    var running: Boolean = false
        internal set

    internal fun publish(s: Map<TrackedApp, Snapshot>) {
        _snapshots.value = s
    }

    /** [app] as the service sees it when it is running, else what was last saved. */
    fun current(context: Context, app: TrackedApp): Snapshot {
        if (running) _snapshots.value[app]?.let { return it }
        val store = LimitStore(context, app)
        val day = UsageDay.of(System.currentTimeMillis())
        return Snapshot(store.loadState(day), store.limits(day), store.pending())
    }

    /** Every app's minutes today, for the brain (which adds them up) and the widget. */
    fun todayMinutes(context: Context): Map<TrackedApp, Int> =
        TrackedApp.entries.associateWith { (current(context, it).state.usedMs / 60_000).toInt() }
}
