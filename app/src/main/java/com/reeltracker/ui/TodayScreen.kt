package com.reeltracker.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reeltracker.limits.Badge
import com.reeltracker.limits.LimitEngine
import com.reeltracker.limits.TrackedApp
import com.reeltracker.limits.UsageSession
import com.reeltracker.limits.UsageStats
import com.reeltracker.service.BrainRot
import com.reeltracker.service.UsageMonitor

enum class Status { Watching, Stopped, SetupNeeded }

private val tnum = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun TodayScreen(
    now: Long,
    /** The app whose numbers and limits the screen shows. */
    app: TrackedApp,
    /** Every app's minutes today; the brain follows their total. */
    minutes: Map<TrackedApp, Int>,
    usage: UsageStats,
    snapshot: UsageMonitor.Snapshot,
    status: Status,
    setup: SetupState,
    is24h: Boolean,
    onUsageAccess: () -> Unit,
    onOverlay: () -> Unit,
    onBattery: () -> Unit,
    onStart: () -> Unit,
    onSelectApp: (TrackedApp) -> Unit,
    onLimits: (budgetMin: Int, capMin: Int) -> Unit,
    onAddWidget: () -> Unit,
) {
    val p = LocalPalette.current
    val clock = Clock(is24h)
    val bars = WindowInsets.systemBars.asPaddingValues()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background),
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = bars.calculateTopPadding() + 16.dp,
            bottom = bars.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header(now, clock, status, onStart) }
        if (!setup.ready || !setup.battery) item { SetupCard(setup, onUsageAccess, onOverlay, onBattery) }
        if (setup.ready && status == Status.Stopped) item { StoppedCard(onStart) }
        item { AppSwitch(app, minutes, onSelectApp) }
        item { Hero(app, snapshot) }
        item { BrainCard(minutes.values.sum(), onAddWidget) }
        item { StatRow(usage, snapshot.state.pausesDone) }
        item {
            Panel("By hour") {
                HourlyChart(usage.hourlyMinutes, app.label, currentHour = java.time.LocalTime.now().hour, is24h = is24h)
            }
        }
        item { Sessions(app, usage, now, clock) }
        item { LimitsCard(app, snapshot, onLimits) }
        item { Footer() }
    }
}

@Composable
private fun Header(now: Long, clock: Clock, status: Status, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(clock.date(now), style = MaterialTheme.typography.titleMedium, color = p.textSecondary)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    clock.clock(now),
                    style = tnum.copy(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
                    color = p.textPrimary,
                )
                clock.amPm(now)?.let {
                    Text(
                        " $it",
                        style = MaterialTheme.typography.titleMedium,
                        color = p.textSecondary,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
            }
        }
        StatusPill(status, onClick, Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun StatusPill(status: Status, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val (dot, label) = when (status) {
        Status.Watching -> p.good to "Watching"
        Status.Stopped -> p.accent to "Not running"
        Status.SetupNeeded -> p.accent to "Setup needed"
    }
    Row(
        modifier
            .clip(CircleShape)
            .background(p.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status == Status.Watching) PulsingDot(dot) else Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = p.textPrimary)
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val a = rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "alpha",
    )
    Box(Modifier.size(8.dp).alpha(a.value).clip(CircleShape).background(color))
}

// ------------------------------------------------------------------ setup

@Composable
private fun SetupCard(setup: SetupState, onUsageAccess: () -> Unit, onOverlay: () -> Unit, onBattery: () -> Unit) {
    val p = LocalPalette.current
    Panel(
        if (setup.ready) "One more step" else "Set up Brainrot",
        subtitle = "No accessibility permission, so GPay, Paytm and bank apps keep working.",
    ) {
        SetupRow(
            done = setup.usageAccess,
            title = "Usage access",
            body = "Lets Brainrot see when Instagram or YouTube is open. Find Brainrot in the list and allow it.",
            onClick = onUsageAccess,
        )
        Spacer(Modifier.height(14.dp))
        SetupRow(
            done = setup.overlay,
            title = "Display over other apps",
            body = "For the timer, the grey screen, pauses and the session popup.",
            onClick = onOverlay,
        )
        Spacer(Modifier.height(14.dp))
        SetupRow(
            done = setup.battery,
            title = "Unrestricted battery (recommended)",
            body = "Stops Nothing OS from closing Brainrot in the background.",
            onClick = onBattery,
        )
        if (!setup.usageAccess || !setup.overlay) {
            Spacer(Modifier.height(12.dp))
            Text(
                "If a switch is greyed out: Settings → Apps → Brainrot → ⋮ → Allow restricted settings, then try again.",
                style = MaterialTheme.typography.bodySmall,
                color = p.textMuted,
            )
        }
    }
}

@Composable
private fun SetupRow(done: Boolean, title: String, body: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (done) p.good else p.cardRaised),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Text("✓", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = p.textPrimary)
            Text(body, style = MaterialTheme.typography.bodySmall, color = p.textMuted)
        }
        if (!done) {
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) { Text("Allow") }
        }
    }
}

@Composable
private fun StoppedCard(onStart: () -> Unit) {
    val p = LocalPalette.current
    Panel("Brainrot isn't running") {
        Text(
            "The system stopped it, probably to save battery. Start it again, and set battery use for Brainrot to Unrestricted.",
            style = MaterialTheme.typography.bodyMedium,
            color = p.textSecondary,
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onStart,
            colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White),
        ) { Text("Start watching") }
    }
}

// ------------------------------------------------------------------ today

/** Instagram | YouTube, each with today's minutes. Picks whose numbers and limits show below. */
@Composable
private fun AppSwitch(selected: TrackedApp, minutes: Map<TrackedApp, Int>, onSelect: (TrackedApp) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(CircleShape)
            .background(p.card)
            .padding(4.dp),
    ) {
        TrackedApp.entries.forEach { app ->
            val on = app == selected
            Row(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (on) p.accent else Color.Transparent)
                    .clickable { onSelect(app) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (on) Color.White else p.textPrimary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    Badge.short((minutes[app] ?: 0) * 60_000L),
                    style = tnum.merge(MaterialTheme.typography.labelLarge),
                    color = if (on) Color.White.copy(alpha = 0.8f) else p.textMuted,
                )
            }
        }
    }
}

@Composable
private fun Hero(app: TrackedApp, snapshot: UsageMonitor.Snapshot) {
    val p = LocalPalette.current
    val used = snapshot.state.usedMs
    val budget = snapshot.limits.dailyBudgetMs
    val over = used >= budget
    Column(Modifier.padding(top = 8.dp, bottom = 6.dp)) {
        Text("${app.label} today", style = MaterialTheme.typography.titleMedium, color = p.textSecondary)
        AnimatedContent(
            targetState = Badge.short(used),
            transitionSpec = {
                (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
            },
            label = "used",
        ) { text ->
            Text(
                text,
                style = TextStyle(fontSize = 96.sp, lineHeight = 100.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-3).sp),
                color = if (over) p.accent else p.textPrimary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(p.accentSoft.copy(alpha = 0.45f))) {
            Box(
                Modifier
                    .fillMaxWidth((used.toFloat() / budget).coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(if (over) p.accent else p.good)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (over) {
                "Over your ${Badge.short(budget)} budget. ${app.label} is grey for the rest of the day, " +
                    "and the next pause is ${LimitEngine.pauseSeconds(snapshot.state.pausesDone)}s."
            } else {
                "${Badge.short(budget - used)} left of your ${Badge.short(budget)} budget"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = p.textMuted,
        )
    }
}

/**
 * Today's brain, the same sprite the badge and the widget show, and the way to add that widget.
 * [minutes] is every app's time together, whichever app is selected.
 */
@Composable
private fun BrainCard(minutes: Int, onAddWidget: () -> Unit) {
    val p = LocalPalette.current
    val stage = BrainRot.stage(minutes)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(p.card)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crossfade(targetState = stage, label = "brain") { s ->
            Image(
                painter = painterResource(BrainRot.sprite(s)),
                contentDescription = "Brain: ${BrainRot.label(s)}",
                modifier = Modifier.size(width = 76.dp, height = 64.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                BrainRot.label(stage),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = p.textPrimary,
            )
            Text(
                (BrainRot.untilNext(minutes)?.let { "$it more minutes until it gets worse" } ?: "It can't get any worse") +
                    ". Instagram and YouTube together.",
                style = MaterialTheme.typography.bodySmall,
                color = p.textMuted,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onAddWidget,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            ) {
                Text("Add to home screen", color = p.accent, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun StatRow(usage: UsageStats, pauses: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Sessions", "${usage.sessions.size}", Modifier.weight(1f))
        StatTile("Longest", usage.sessions.maxOfOrNull { it.activeMs }?.let { Badge.short(it) } ?: "–", Modifier.weight(1f))
        StatTile("Pauses", "$pauses", Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(p.card)
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = p.textSecondary, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Text(value, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold), color = p.textPrimary, maxLines = 1)
    }
}

@Composable
private fun Sessions(app: TrackedApp, usage: UsageStats, now: Long, clock: Clock) {
    val p = LocalPalette.current
    Panel("Sessions", subtitle = "2 minutes away from ${app.label} starts a new session") {
        if (usage.sessions.isEmpty()) {
            Text("No ${app.label} yet today.", style = MaterialTheme.typography.bodyMedium, color = p.textMuted)
            return@Panel
        }
        val longest = usage.sessions.maxOf { it.activeMs }.coerceAtLeast(1)
        val newestFirst = usage.sessions.asReversed()
        newestFirst.forEachIndexed { i, s ->
            val live = i == 0 && now - s.end < LimitEngine.SESSION_GAP_MS
            SessionRow(s, longest, live, clock)
            if (i != newestFirst.lastIndex) {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(p.hairline))
            }
        }
    }
}

@Composable
private fun SessionRow(s: UsageSession, longest: Long, live: Boolean, clock: Clock) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                clock.range(s.start, s.end),
                style = tnum.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                color = p.textPrimary,
            )
            if (live) {
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier.clip(CircleShape).background(p.cardRaised).padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PulsingDot(p.accent)
                    Spacer(Modifier.width(5.dp))
                    Text("Now", style = MaterialTheme.typography.labelSmall, color = p.textPrimary)
                }
            }
        }
        Text(duration(s.activeMs), style = tnum.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = p.textPrimary)
    }
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(p.accentSoft.copy(alpha = 0.45f))) {
        Box(Modifier.fillMaxWidth(s.activeMs.toFloat() / longest).height(4.dp).clip(CircleShape).background(p.accent))
    }
}

// ------------------------------------------------------------------ limits

/**
 * The two limits. Today's values never change here: a new value is shown as "from tomorrow"
 * and takes over at 4 AM.
 */
@Composable
private fun LimitsCard(app: TrackedApp, snapshot: UsageMonitor.Snapshot, onLimits: (Int, Int) -> Unit) {
    val p = LocalPalette.current
    val active = snapshot.limits
    val wanted = snapshot.pending ?: active
    val budgetMin = (wanted.dailyBudgetMs / 60_000).toInt()
    val capMin = (wanted.sessionCapMs / 60_000).toInt()
    Panel("${app.label} limits", subtitle = "Changes start tomorrow at 4 AM, so you can't raise them in a weak moment.") {
        Stepper(
            label = "Daily budget",
            value = budgetMin,
            today = (active.dailyBudgetMs / 60_000).toInt(),
            range = 5..240,
            step = 5,
            onChange = { onLimits(it, capMin) },
        )
        Spacer(Modifier.height(14.dp))
        Stepper(
            label = "Session limit",
            value = capMin,
            today = (active.sessionCapMs / 60_000).toInt(),
            range = 5..60,
            step = 5,
            onChange = { onLimits(budgetMin, it) },
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "After the budget: ${app.label} turns grey, and a pause (10s, then 10s longer each time, up to 2 min) " +
                "comes when you open it and every 5 minutes. After the session limit: grey until you've been away " +
                "2 minutes, and a check-in every 5 minutes.",
            style = MaterialTheme.typography.bodySmall,
            color = p.textMuted,
        )
    }
}

@Composable
private fun Stepper(label: String, value: Int, today: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = p.textPrimary)
            Text(
                if (value == today) "Today: ${Badge.short(today * 60_000L)}"
                else "Today: ${Badge.short(today * 60_000L)} · from tomorrow: ${Badge.short(value * 60_000L)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (value == today) p.textMuted else p.accent,
            )
        }
        RoundButton("−", enabled = value - step >= range.first) { onChange(value - step) }
        Text(
            Badge.short(value * 60_000L),
            style = tnum.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
            color = p.textPrimary,
            modifier = Modifier.width(72.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        RoundButton("+", enabled = value + step <= range.last) { onChange(value + step) }
    }
}

@Composable
private fun RoundButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .border(1.dp, p.hairline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
    }
}

@Composable
private fun Footer() {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Measured on this phone. Nothing leaves it.", style = MaterialTheme.typography.bodySmall, color = p.textMuted)
    }
}

@Composable
private fun Panel(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(p.card)
            .padding(18.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = p.textPrimary)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = p.textMuted)
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}
