package com.reeltracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * Minutes in [appLabel] per hour of the day, one series. Tap a column to read it; the busiest hour is labelled
 * by default. Specs per the dataviz guide: <= 24dp columns, 2dp gap, 4dp rounded cap square at
 * the baseline, hairline axis, text in text tokens, never the series colour.
 */
@Composable
fun HourlyChart(hourly: List<Int>, appLabel: String, currentHour: Int, is24h: Boolean, modifier: Modifier = Modifier) {
    val empty = "No $appLabel yet today"
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer()
    var selected by remember(hourly) { mutableStateOf<Int?>(null) }

    val max = hourly.maxOrNull() ?: 0
    val peak = hourly.indexOf(max)
    val focus = selected ?: peak.takeIf { max > 0 }

    Column(modifier) {
        Row {
            Text(
                text = when {
                    focus == null -> empty
                    selected == null -> "Busiest  ${hourRange(focus, is24h)}"
                    else -> hourRange(focus, is24h)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = p.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (focus != null) {
                Text(
                    text = "${hourly[focus]} min",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = p.textPrimary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        val axisStyle = TextStyle(fontSize = 11.sp, color = p.textMuted, fontFeatureSettings = "tnum")
        val nowStyle = axisStyle.copy(color = p.textPrimary, fontWeight = FontWeight.SemiBold)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(148.dp)
                .semantics { contentDescription = describe(hourly, appLabel, is24h, empty) }
                .pointerInput(hourly) {
                    detectTapGestures { o ->
                        val h = (o.x / (size.width / 24f)).toInt().coerceIn(0, 23)
                        selected = if (selected == h) null else h
                    }
                },
        ) {
            val labelArea = 22.dp.toPx()
            val baseline = size.height - labelArea
            val plotTop = 8.dp.toPx()
            val slot = size.width / 24f
            val gap = 2.dp.toPx()
            val barW = min(slot - gap, 24.dp.toPx())
            val radius = 4.dp.toPx()

            // Hairline at the max, labelled at the right: the one y reference the chart needs.
            if (max > 0) {
                drawLine(p.hairline, Offset(0f, plotTop), Offset(size.width, plotTop), strokeWidth = 1f)
                val m = measurer.measure("$max", axisStyle)
                drawText(m, topLeft = Offset(size.width - m.size.width, plotTop - m.size.height - 2.dp.toPx()))
            }
            drawLine(p.hairline, Offset(0f, baseline), Offset(size.width, baseline), strokeWidth = 1f)

            for (h in 0 until 24) {
                val v = hourly[h]
                if (v == 0) continue
                val barH = (baseline - plotTop) * v / max
                val left = h * slot + (slot - barW) / 2f
                val r = min(radius, barH)
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = left, top = baseline - barH, right = left + barW, bottom = baseline,
                            topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                            bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
                        )
                    )
                }
                val dim = selected != null && selected != h
                drawPath(path, if (dim) p.accentSoft else p.accent)
            }

            // Current hour marker: a small dot under the axis.
            val cx = currentHour * slot + slot / 2f
            drawCircle(p.textPrimary, radius = 2.dp.toPx(), center = Offset(cx, baseline + 5.dp.toPx()))

            for (h in listOf(0, 6, 12, 18)) {
                val m = measurer.measure(axisLabel(h, is24h), if (h == currentHour) nowStyle else axisStyle)
                val x = (h * slot + slot / 2f - m.size.width / 2f).coerceAtLeast(0f)
                drawText(m, topLeft = Offset(x, baseline + 9.dp.toPx()))
            }
        }
    }
}

private fun axisLabel(h: Int, is24h: Boolean): String =
    if (is24h) "%02d".format(h) else when (h) {
        0 -> "12a"; 12 -> "12p"; else -> if (h < 12) "${h}a" else "${h - 12}p"
    }

private fun hourRange(h: Int, is24h: Boolean): String =
    "${hourLabel(h, is24h)} – ${hourLabel((h + 1) % 24, is24h)}"

private fun describe(hourly: List<Int>, appLabel: String, is24h: Boolean, empty: String): String =
    hourly.withIndex().filter { it.value > 0 }
        .joinToString(prefix = "$appLabel minutes by hour. ", separator = ", ") { "${hourLabel(it.index, is24h)}: ${it.value} min" }
        .ifEmpty { empty }
