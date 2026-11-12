package com.reeltracker.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reeltracker.limits.TrackedApp
import com.reeltracker.service.ReelWidgetProvider
import com.reeltracker.service.Setup
import com.reeltracker.service.UsageMonitor
import com.reeltracker.service.UsageService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val vm: TodayViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationsOnce()
        setContent {
            ReelTheme {
                val context = LocalContext.current
                var app by rememberSaveable { mutableStateOf(TrackedApp.INSTAGRAM) }
                val usage by vm.usage.getValue(app).collectAsStateWithLifecycle()

                var setup by remember { mutableStateOf(setupState(context)) }
                var is24h by remember { mutableStateOf(DateFormat.is24HourFormat(context)) }
                LifecycleResumeEffect(Unit) {
                    setup = setupState(context)
                    is24h = DateFormat.is24HourFormat(context)
                    // Coming back from Settings with both permissions: start watching straight away.
                    UsageService.start(context)
                    ReelWidgetProvider.refresh(context)
                    onPauseOrDispose { }
                }

                val now = rememberNow()
                // Re-read every second: the service's live state, or what it last saved.
                val snapshot = remember(now, app) { UsageMonitor.current(context, app) }
                val minutes = remember(now) { UsageMonitor.todayMinutes(context) }
                val status = when {
                    !setup.ready -> Status.SetupNeeded
                    UsageMonitor.running -> Status.Watching
                    else -> Status.Stopped
                }

                TodayScreen(
                    now = now,
                    app = app,
                    minutes = minutes,
                    usage = usage,
                    snapshot = snapshot,
                    status = status,
                    setup = setup,
                    is24h = is24h,
                    onUsageAccess = { open(Setup.usageAccessIntent(context)) },
                    onOverlay = { open(Setup.overlayIntent(context)) },
                    onBattery = { open(Setup.batteryIntent(context)) },
                    onStart = { UsageService.start(context) },
                    onSelectApp = { app = it },
                    onLimits = { budget, cap -> vm.requestLimits(app, budget, cap) },
                    onAddWidget = { requestWidget(context) },
                )
            }
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: RuntimeException) {
            Toast.makeText(this, "Couldn't open that settings screen on this phone.", Toast.LENGTH_LONG).show()
        }
    }

    /** The service's notification is hidden without this on Android 13+; the limits work either way. */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
    }
}

data class SetupState(val usageAccess: Boolean, val overlay: Boolean, val battery: Boolean) {
    val ready: Boolean get() = usageAccess && overlay
}

private fun setupState(context: Context) = SetupState(
    usageAccess = Setup.hasUsageAccess(context),
    overlay = Setup.canDrawOverlays(context),
    battery = Setup.ignoresBatteryOptimizations(context),
)

/** Wall clock, ticking on each second boundary. */
@Composable
private fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000 - value % 1_000)
        }
    }
    return now
}

/**
 * Asks the launcher to place the widget; it shows its own "Add to home screen?" prompt.
 * Launchers that cannot pin (some older or third-party ones) get the manual steps instead.
 */
private fun requestWidget(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    val provider = ComponentName(context, ReelWidgetProvider::class.java)
    val pinned = manager.isRequestPinAppWidgetSupported &&
        manager.requestPinAppWidget(provider, null, null)
    if (!pinned) {
        Toast.makeText(
            context,
            "Long-press your home screen, tap Widgets, and add Brainrot.",
            Toast.LENGTH_LONG,
        ).show()
    }
}
