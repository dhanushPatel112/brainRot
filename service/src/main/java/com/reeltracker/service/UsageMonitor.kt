package com.reeltracker.service

import android.content.Context
import com.reeltracker.limits.LimitState
import com.reeltracker.limits.Limits
import com.reeltracker.limits.UsageDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live view of the rules for the UI and widget, which run in the same process as the service. */
object UsageMonitor {

    data class Snapshot(val state: LimitState, val limits: Limits, val pending: Limits?)

    private val _snapshot = MutableStateFlow<Snapshot?>(null)

    /** Pushed by the service every tick; null until it has run in this process. */
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    @Volatile
    var running: Boolean = false
        internal set

    internal fun publish(s: Snapshot) {
        _snapshot.value = s
    }

    /** The service's view when it is running, else what was last saved. */
    fun current(context: Context): Snapshot {
        if (running) _snapshot.value?.let { return it }
        val store = LimitStore(context)
        val day = UsageDay.of(System.currentTimeMillis())
        return Snapshot(store.loadState(day), store.limits(day), store.pending())
    }
}
