package com.reeltracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reeltracker.data.ReelGraph
import com.reeltracker.limits.Limits
import com.reeltracker.limits.UsageDay
import com.reeltracker.limits.UsageStats
import com.reeltracker.service.LimitStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class TodayViewModel(app: Application) : AndroidViewModel(app) {

    private val store = LimitStore(app)

    val usage: StateFlow<UsageStats> =
        ReelGraph.usage(app).today.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageStats.EMPTY)

    /** New limits apply from the next 4 AM, never today. */
    fun requestLimits(budgetMin: Int, capMin: Int) {
        store.request(
            Limits(dailyBudgetMs = budgetMin * 60_000L, sessionCapMs = capMin * 60_000L),
            today = UsageDay.of(System.currentTimeMillis()),
        )
    }
}
