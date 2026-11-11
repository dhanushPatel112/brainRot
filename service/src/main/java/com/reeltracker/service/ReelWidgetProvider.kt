package com.reeltracker.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.reeltracker.limits.Badge

/**
 * Home-screen widget: today's Instagram minutes and the brain at its current rot stage.
 *
 * The service redraws it every new minute; Android's periodic update and the app opening catch
 * it up (the 4 AM reset) when the service isn't drawing.
 */
class ReelWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids, todayMinutes(context))
    }

    companion object {

        /** Pushes [minutes] to every placed widget. Cheap when none are placed. */
        fun update(context: Context, minutes: Int) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ReelWidgetProvider::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids, minutes)
        }

        /** Reads today's minutes and redraws, for callers that do not have them to hand. */
        fun refresh(context: Context) = update(context.applicationContext, todayMinutes(context))

        private fun todayMinutes(context: Context) =
            (UsageMonitor.current(context.applicationContext).state.usedMs / 60_000).toInt()

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, minutes: Int) {
            val stage = BrainRot.stage(minutes)
            val views = RemoteViews(context.packageName, R.layout.widget_reels).apply {
                setImageViewResource(R.id.widget_brain, BrainRot.sprite(stage))
                setTextViewText(R.id.widget_count, Badge.short(minutes * 60_000L))
                setTextViewText(
                    R.id.widget_caption,
                    "${context.getString(R.string.widget_reels_today)} · ${BrainRot.label(stage)}",
                )
                setContentDescription(R.id.widget_root, "$minutes minutes on Instagram today, ${BrainRot.label(stage)}")
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
