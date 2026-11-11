package com.reeltracker.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * "10 minutes of active use" popup. Everything about it is shuffled each time it appears, so the
 * way out never lands under the same thumb twice:
 *
 *  - where the card sits (top, middle or bottom, nudged sideways)
 *  - its colours, and which of the two buttons is the filled one
 *  - the wording of both buttons
 *  - the buttons' order, and whether they sit side by side or stacked
 *  - the "keep going" button only wakes up after 2–4 s, and one time in three it must be held
 *
 * [onContinue] is the only way to Instagram. [onLeave] (the other button, or Back) goes home.
 */
@SuppressLint("ViewConstructor")
internal class SessionPopupView(
    context: Context,
    capMinutes: Int,
    sessionLabel: String,
    private val onContinue: () -> Unit,
    private val onLeave: () -> Unit,
    private val random: Random = Random.Default,
) : FrameLayout(context) {

    private val look = LOOKS.random(random)
    private val hold = random.nextInt(3) == 0
    private val armAt = SystemClock.elapsedRealtime() + random.nextLong(2_000, 4_001)
    private val waste: Button
    private val wasteLabel = WASTE_LABELS.random(random)
    private val posted = ArrayList<Runnable>()
    private val wake = Runnable {
        waste.isEnabled = true
        waste.alpha = 1f
    }

    init {
        setBackgroundColor(0xB3000000.toInt())
        isFocusableInTouchMode = true
        isClickable = true

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(random.nextInt(8, 29)).toFloat()
                setColor(look.card)
            }
        }
        card.addView(text("$capMinutes minutes of active use", 22f, look.text, bold = true))
        card.addView(text(BODIES.random(random), 15f, look.muted).apply { setPadding(0, dp(8), 0, 0) })
        card.addView(text("This session: $sessionLabel", 13f, look.muted).apply { setPadding(0, dp(6), 0, dp(18)) })

        val wasteFilled = random.nextBoolean()
        waste = button(if (hold) "Hold: $wasteLabel" else wasteLabel, filled = wasteFilled)
        val leave = button(LEAVE_LABELS.random(random), filled = !wasteFilled).apply { setOnClickListener { onLeave() } }
        armWaste()

        val sideBySide = random.nextBoolean()
        val row = LinearLayout(context).apply {
            orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val buttons = if (random.nextBoolean()) listOf(waste, leave) else listOf(leave, waste)
        buttons.forEachIndexed { i, b ->
            val lp = if (sideBySide) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) leftMargin = dp(10)
                }
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) topMargin = dp(10)
                }
            }
            row.addView(b, lp)
        }
        card.addView(row)

        val vertical = listOf(Gravity.TOP, Gravity.CENTER_VERTICAL, Gravity.BOTTOM).random(random)
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, vertical or Gravity.CENTER_HORIZONTAL).apply {
            val side = dp(random.nextInt(12, 41))
            val nudge = dp(random.nextInt(-16, 17))
            leftMargin = side + nudge
            rightMargin = side - nudge
            topMargin = dp(random.nextInt(48, 121))
            bottomMargin = dp(random.nextInt(48, 121))
        })
        contentDescription = "$capMinutes minutes of active use on Instagram."
    }

    /** Greyed out until [armAt], then either a tap or a 2-second hold goes through. */
    @SuppressLint("ClickableViewAccessibility")
    private fun armWaste() {
        waste.isEnabled = false
        waste.alpha = 0.4f
        posted += wake

        if (!hold) {
            waste.setOnClickListener { onContinue() }
            return
        }
        var downAt = 0L
        val progress = object : Runnable {
            override fun run() {
                val held = SystemClock.elapsedRealtime() - downAt
                if (held >= HOLD_MS) {
                    onContinue()
                    return
                }
                waste.text = "Keep holding… %.1fs".format((HOLD_MS - held) / 1000f)
                postDelayed(this, 100)
            }
        }
        posted += progress
        waste.setOnTouchListener { _, e ->
            if (!waste.isEnabled) return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.elapsedRealtime()
                    post(progress)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(progress)
                    waste.text = "Hold: $wasteLabel"
                }
            }
            true
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        // Scheduled here, not once in init: restacking detaches the view and drops its callbacks.
        if (!waste.isEnabled) postDelayed(wake, (armAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }

    override fun onDetachedFromWindow() {
        posted.forEach { removeCallbacks(it) }
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onLeave()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun button(label: String, filled: Boolean) = Button(context).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(if (filled) look.onAccent else look.text)
        background = GradientDrawable().apply {
            cornerRadius = dp(random.nextInt(6, 25)).toFloat()
            if (filled) setColor(look.accent) else setStroke(dp(1), look.muted)
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        stateListAnimator = null
        textAlignment = View.TEXT_ALIGNMENT_CENTER
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private class Look(val card: Int, val text: Int, val muted: Int, val accent: Int, val onAccent: Int)

    private companion object {
        const val HOLD_MS = 2_000L

        val LOOKS = listOf(
            Look(0xFF1A1A19.toInt(), 0xFFFFFFFF.toInt(), 0xFFC3C2B7.toInt(), 0xFFF4533F.toInt(), 0xFFFFFFFF.toInt()),
            Look(0xFFFFFFFF.toInt(), 0xFF0B0B0B.toInt(), 0xFF52514E.toInt(), 0xFF1F8A4C.toInt(), 0xFFFFFFFF.toInt()),
            Look(0xFF10233F.toInt(), 0xFFFFFFFF.toInt(), 0xFFB9C7DB.toInt(), 0xFFFFC94D.toInt(), 0xFF10233F.toInt()),
            Look(0xFFFFF4D6.toInt(), 0xFF2B2100.toInt(), 0xFF6B5A2A.toInt(), 0xFF7A3EF0.toInt(), 0xFFFFFFFF.toInt()),
            Look(0xFF2B1036.toInt(), 0xFFFFFFFF.toInt(), 0xFFD9C3E3.toInt(), 0xFF3CC8C8.toInt(), 0xFF0B0B0B.toInt()),
        )

        val WASTE_LABELS = listOf(
            "I still want to waste my time",
            "Keep rotting my brain",
            "Yes, more scrolling",
            "I choose the scroll",
            "5 more minutes of nothing",
        )

        val LEAVE_LABELS = listOf(
            "Close Instagram",
            "I'm done",
            "Get me out",
            "Take a break",
            "Enough for now",
        )

        val BODIES = listOf(
            "Was this what you opened Instagram for?",
            "Everything from here on is in grey.",
            "The next check-in is 5 minutes away.",
            "Your brain called. It wants a break.",
        )
    }
}
