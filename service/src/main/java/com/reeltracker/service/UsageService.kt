package com.reeltracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.reeltracker.data.ReelGraph
import com.reeltracker.data.UsageRepository
import com.reeltracker.limits.Badge
import com.reeltracker.limits.LimitEngine
import com.reeltracker.limits.LimitState
import com.reeltracker.limits.Limits
import com.reeltracker.limits.Screen
import com.reeltracker.limits.TrackedApp
import com.reeltracker.limits.UsageDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service: asks which app is in front, runs each tracked app's rules, and draws the
 * result over whichever of them is open. Each [TrackedApp] has its own limits, state and history.
 * Ticks once a second while a tracked app is in front, every [IDLE_TICK_MS] otherwise, and
 * not at all while the screen is off. Uses usage access and "display over other apps" only. No accessibility
 * service, so payment apps have nothing to complain about.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageService : Service(), OverlayHost.Actions {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Database writes, one at a time and in order. Not cancelled with [scope], so the last flush lands. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private lateinit var watcher: ForegroundWatcher
    private lateinit var overlay: OverlayHost
    private lateinit var history: UsageRepository
    private lateinit var audio: AudioManager

    /** One app's rules, settings and history bookkeeping. */
    private inner class Tracker(val app: TrackedApp) {
        val store = LimitStore(this@UsageService, app)
        var state: LimitState = store.loadState(UsageDay.of(System.currentTimeMillis()))
        var limits = Limits()

        var sliceOpen = false
        var sliceId: Long? = null // only touched on [io]
        var lastFlushAt = 0L
        var savedState: LimitState? = null
        var lastSaveAt = 0L

        val minutes: Int get() = (state.usedMs / 60_000).toInt()
    }

    private lateinit var trackers: List<Tracker>

    /** The app whose overlays were drawn last; pause and popup callbacks belong to it. */
    private var shownApp: TrackedApp? = null
    private var shownMinutes: List<Int> = emptyList()
    private var focus: AudioFocusRequest? = null

    /** Cached permission checks, refreshed every [PERMISSION_CHECK_MS] and when the screen comes on. */
    private var canWatch = false
    private var canDraw = false
    private var permissionsCheckedAt = 0L

    /** False while the screen is off: nothing can be in front, so the loop waits for [wake]. */
    @Volatile private var screenOn = true
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    permissionsCheckedAt = 0L
                }
            }
            // Either way, tick now: off closes the slice and clears overlays, on catches up.
            wake.trySend(Unit)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground(notification("Starting…"))
        trackers = TrackedApp.entries.map { Tracker(it) }
        watcher = ForegroundWatcher(this)
        overlay = OverlayHost(this, this)
        history = ReelGraph.usage(this)
        audio = getSystemService(AudioManager::class.java)
        UsageMonitor.running = true
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
        scope.launch {
            while (isActive) {
                step()
                if (screenOn) {
                    // Woken early by a screen change; otherwise the next tick on the interval.
                    withTimeoutOrNull(nextTickDelay()) { wake.receive() }
                } else {
                    wake.receive()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        UsageMonitor.running = false
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        overlay.clear()
        releaseAudio()
        val now = System.currentTimeMillis()
        trackers.forEach {
            closeSlice(it, now)
            it.store.saveState(it.state)
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ the loop

    private fun step() {
        val now = System.currentTimeMillis()
        val day = UsageDay.of(now)
        refreshPermissions(now)
        // Without either permission nothing can be seen or drawn; keep the day ticking regardless.
        val front = if (screenOn && canWatch && canDraw) watcher.front(now) else null
        for (t in trackers) {
            t.limits = t.store.limits(day)
            t.state = LimitEngine.tick(t.state, t.limits, now, day, foreground = front == t.app.packageName)
        }
        apply(now)
    }

    private fun apply(now: Long) {
        // At most one app is in front; only its overlays are drawn.
        val shown = trackers.firstOrNull { it.state.foreground }
        val screen = shown?.let { LimitEngine.screen(it.state, it.limits) } ?: Screen.NONE
        val totalMinutes = (trackers.sumOf { it.state.usedMs } / 60_000).toInt()
        if (canDraw && shown != null) {
            shownApp = shown.app
            overlay.render(shown.app, screen, shown.limits, totalMinutes)
        } else {
            overlay.clear()
        }
        holdAudio(screen.block != null)
        for (t in trackers) {
            recordSlice(t, now)
            save(t, now)
        }

        val minutes = trackers.map { it.minutes }
        if (minutes != shownMinutes) {
            shownMinutes = minutes
            ReelWidgetProvider.update(this, trackers.associate { it.app to it.minutes })
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(statusText()))
        }
        UsageMonitor.publish(trackers.associate { it.app to UsageMonitor.Snapshot(it.state, it.limits, it.store.pending()) })
    }

    /** Every second while a tracked app is in front (aligned to the second), else every [IDLE_TICK_MS]. */
    private fun nextTickDelay(): Long {
        val tick = if (trackers.any { it.state.foreground }) TICK_MS else IDLE_TICK_MS
        return tick - System.currentTimeMillis() % tick
    }

    private fun refreshPermissions(now: Long) {
        if (now - permissionsCheckedAt < PERMISSION_CHECK_MS) return
        permissionsCheckedAt = now
        canWatch = Setup.hasUsageAccess(this)
        canDraw = Setup.canDrawOverlays(this)
    }

    // ------------------------------------------------------------------ overlay actions

    override fun pauseDone() {
        val t = shownTracker() ?: return
        t.state = LimitEngine.pauseCompleted(t.state)
        apply(System.currentTimeMillis())
    }

    override fun popupContinue() {
        val t = shownTracker() ?: return
        t.state = LimitEngine.popupDismissed(t.state)
        apply(System.currentTimeMillis())
    }

    private fun shownTracker() = trackers.firstOrNull { it.app == shownApp }

    /** "Never mind" / "Close Instagram" (or YouTube) / Back: to the home screen. Whatever was owed stays owed. */
    override fun leave() {
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(home) }
        // Don't wait up to a second for the next poll to notice the app left.
        overlay.clear()
    }

    // ------------------------------------------------------------------ side effects

    /** History rows: one per stretch of counted time, its end pushed forward every [FLUSH_MS]. */
    private fun recordSlice(t: Tracker, now: Long) {
        val counting = t.state.foreground && !t.state.blocked
        when {
            counting && !t.sliceOpen -> {
                t.sliceOpen = true
                t.lastFlushAt = now
                io.launch { t.sliceId = history.open(t.app, now) }
            }
            counting && now - t.lastFlushAt >= FLUSH_MS -> {
                t.lastFlushAt = now
                io.launch { t.sliceId?.let { history.extend(it, now) } }
            }
            !counting && t.sliceOpen -> closeSlice(t, now)
        }
    }

    private fun closeSlice(t: Tracker, now: Long) {
        if (!t.sliceOpen) return
        t.sliceOpen = false
        io.launch {
            t.sliceId?.let { history.extend(it, now) }
            t.sliceId = null
        }
    }

    /**
     * Saves at once when anything but the clocks changed, every few seconds while time is being
     * counted, and not at all when only [LimitState.lastTickAt] moved (idle ticks).
     */
    private fun save(t: Tracker, now: Long) {
        val state = t.state
        val saved = t.savedState
        val flagsChanged = saved?.let { it.withoutClocks() != state.withoutClocks() } ?: true
        val clocksMoved = saved?.let { it.copy(lastTickAt = null) != state.copy(lastTickAt = null) } ?: true
        if (flagsChanged || (clocksMoved && now - t.lastSaveAt >= SAVE_MS)) {
            t.store.saveState(state)
            t.savedState = state
            t.lastSaveAt = now
        }
    }

    private fun LimitState.withoutClocks() = copy(usedMs = 0, sessionUsedMs = 0, lastActiveAt = null, lastTickAt = null)

    /** While a pause or popup covers an app, take audio focus so the reel or video underneath goes quiet. */
    private fun holdAudio(hold: Boolean) {
        if (hold && focus == null) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            audio.requestAudioFocus(req)
            focus = req
        } else if (!hold) {
            releaseAudio()
        }
    }

    private fun releaseAudio() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    // ------------------------------------------------------------------ notification

    /** "Instagram 23m of 30m · YouTube 1h 05m of 1h, over", one part per app. */
    private fun statusText(): String = trackers.joinToString(" · ") {
        val used = Badge.short(it.state.usedMs)
        val budget = Badge.short(it.limits.dailyBudgetMs)
        if (it.state.usedMs >= it.limits.dailyBudgetMs) "${it.app.label} $used of $budget, over"
        else "${it.app.label} $used of $budget"
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        // Recreating an existing channel only updates its name and description (once "Instagram limits").
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "App limits", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while Brainrot is watching your Instagram and YouTube time"
                setShowBadge(false)
            }
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_brain)
            .setContentTitle("Brainrot")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    companion object {
        private const val TICK_MS = 1_000L
        private const val IDLE_TICK_MS = 5_000L
        private const val PERMISSION_CHECK_MS = 30_000L
        private const val FLUSH_MS = 15_000L
        private const val SAVE_MS = 5_000L
        private const val CHANNEL = "status"
        private const val NOTIFICATION_ID = 1

        /** Starts the service if both permissions are granted. Safe to call repeatedly. */
        fun start(context: Context) {
            if (!Setup.ready(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, UsageService::class.java))
            } catch (_: IllegalStateException) {
                // Not allowed from the background right now (Android 12+); the app starts it when opened.
            }
        }
    }
}
