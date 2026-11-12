package com.reeltracker.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.reeltracker.limits.Badge
import com.reeltracker.limits.TrackedApp

/**
 * Home-screen widget: today's minutes across Instagram and YouTube, each app's own minutes, and
 * the brain at its current rot stage (which follows the total).
 *
 * The service redraws it every new minute; Android's periodic update and the app opening catch
 * it up (the 4 AM reset) when the service isn't drawing.
 */
class ReelWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids, UsageMonitor.todayMinutes(context.applicationContext))
    }

    companion object {

        /** Pushes each app's [minutes] to every placed widget. Cheap when none are placed. */
        fun update(context: Context, minutes: Map<TrackedApp, Int>) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ReelWidgetProvider::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids, minutes)
        }

        /** Reads today's minutes and redraws, for callers that do not have them to hand. */
        fun refresh(context: Context) =
            update(context.applicationContext, UsageMonitor.todayMinutes(context.applicationContext))

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, minutes: Map<TrackedApp, Int>) {
            val total = minutes.values.sum()
            val stage = BrainRot.stage(total)
            val split = TrackedApp.entries.joinToString(" · ") { "${it.shortLabel} ${Badge.short((minutes[it] ?: 0) * 60_000L)}" }
            val spoken = TrackedApp.entries.joinToString(", ") { "${minutes[it] ?: 0} minutes on ${it.label}" }
            val views = RemoteViews(context.packageName, R.layout.widget_reels).apply {
                setImageViewResource(R.id.widget_brain, BrainRot.sprite(stage))
                setTextViewText(R.id.widget_count, Badge.short(total * 60_000L))
                setTextViewText(
                    R.id.widget_caption,
                    "${context.getString(R.string.widget_reels_today)} · ${BrainRot.label(stage)}",
                )
                setTextViewText(R.id.widget_split, split)
                setContentDescription(R.id.widget_root, "$spoken today, ${BrainRot.label(stage)}")
                openAppOnTap(context)?.let { setOnClickPendingIntent(R.id.widget_root, it) }
            }
            manager.updateAppWidget(ids, views)
        }

        private fun openAppOnTap(context: Context): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            return PendingIntent.getActivity(
                context, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
