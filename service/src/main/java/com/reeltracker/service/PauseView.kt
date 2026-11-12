package com.reeltracker.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen pause over a tracked app. Counts down [seconds], then calls [onDone] once and the
 * service lifts it. "Never mind" and Back call [onLeave], which goes to the home screen; the
 * pause stays owed and starts over the next time [appLabel] opens.
 *
 * The end time is fixed when the view is made, so taking the window down and putting it back
 * (to restack it under a new badge) doesn't restart the countdown.
 */
@SuppressLint("ViewConstructor")
internal class PauseView(
    context: Context,
    private val seconds: Int,
    appLabel: String,
    subtitle: String,
    private val onDone: () -> Unit,
    private val onLeave: () -> Unit,
) : FrameLayout(context) {

    private val endAt = SystemClock.elapsedRealtime() + seconds * 1000L
    private val count = TextView(context)
    private var finished = false

    private val ticker = object : Runnable {
        override fun run() {
            val left = endAt - SystemClock.elapsedRealtime()
            if (left <= 0) {
                count.text = "0"
                if (!finished) {
                    finished = true
                    onDone()
                }
                return
            }
            count.text = ((left + 999) / 1000).toString()
            postDelayed(this, 200)
        }
    }

    init {
        setBackgroundColor(0xF20F0F10.toInt())
        isFocusableInTouchMode = true
        isClickable = true // swallow every touch: nothing reaches the app underneath

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), 0, dp(32), 0)
        }
        column.addView(ImageView(context).apply {
            setImageResource(R.drawable.brain_stage_2)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(96), dp(80)))
        column.addView(text("Breathe.", 28f, Color.WHITE, bold = true).apply { setPadding(0, dp(16), 0, 0) })
        column.addView(count.apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 96f)
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER
            text = seconds.toString()
        })
        column.addView(text("$appLabel opens when this reaches zero.", 15f, 0xCCFFFFFF.toInt()))
        column.addView(text(subtitle, 14f, 0x99FFFFFF.toInt()).apply { setPadding(0, dp(8), 0, dp(36)) })
        column.addView(Button(context).apply {
            text = "Never mind, take me out"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), 0x66FFFFFF)
            }
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setOnClickListener { onLeave() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        contentDescription = "Pause. $appLabel opens in $seconds seconds."
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        removeCallbacks(ticker)
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onLeave()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
