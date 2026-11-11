package com.reeltracker.service

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager

/**
 * Which app is in front, from the usage-access event log. No accessibility service: payment
 * apps don't object to usage access.
 *
 * Each poll reads only the events since the last one. The log can only say which activity
 * resumed or paused, never what is on screen, which is why everything counts all of Instagram
 * rather than only Reels.
 */
internal class ForegroundWatcher(context: Context) {

    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    private var queriedTo = 0L
    private var lastEventAt = 0L
    private var resumedPkg: String? = null
    private var resumedClass: String? = null

    /** True when [pkg] is the resumed app, the screen is on and the phone is unlocked. */
    fun isInFront(pkg: String, now: Long): Boolean {
        poll(now)
        return resumedPkg == pkg && power.isInteractive && !keyguard.isKeyguardLocked
    }

    private fun poll(now: Long) {
        // First poll looks back far enough to find what is open right now; after that, a small
        // overlap catches events that were logged a moment after their timestamp.
        val from = if (queriedTo == 0L) now - FIRST_LOOKBACK_MS else queriedTo - OVERLAP_MS
        val events = usm.queryEvents(from, now) ?: return
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.timeStamp < lastEventAt) continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    resumedPkg = e.packageName
                    resumedClass = e.className
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    // A pause for an activity that is no longer the resumed one is stale.
                    if (e.packageName == resumedPkg && e.className == resumedClass) {
                        resumedPkg = null
                        resumedClass = null
                    }
                }
            }
            lastEventAt = e.timeStamp
        }
        queriedTo = now
    }

    private companion object {
        const val FIRST_LOOKBACK_MS = 6 * 60 * 60_000L
        const val OVERLAP_MS = 2_000L
    }
}
