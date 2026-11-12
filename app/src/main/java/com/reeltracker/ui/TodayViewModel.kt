package com.reeltracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reeltracker.data.ReelGraph
import com.reeltracker.limits.Limits
import com.reeltracker.limits.TrackedApp
import com.reeltracker.limits.UsageDay
import com.reeltracker.limits.UsageStats
import com.reeltracker.service.LimitStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class TodayViewModel(app: Application) : AndroidViewModel(app) {

    private val stores = TrackedApp.entries.associateWith { LimitStore(app, it) }

    /** Each app's history for today. */
    val usage: Map<TrackedApp, StateFlow<UsageStats>> = TrackedApp.entries.associateWith {
        ReelGraph.usage(app).today(it).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageStats.EMPTY)
    }

    /** New limits apply from the next 4 AM, never today. */
    fun requestLimits(app: TrackedApp, budgetMin: Int, capMin: Int) {
        stores.getValue(app).request(
            Limits(dailyBudgetMs = budgetMin * 60_000L, sessionCapMs = capMin * 60_000L),
            today = UsageDay.of(System.currentTimeMillis()),
        )
    }
}
