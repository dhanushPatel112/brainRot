package com.reeltracker.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.reeltracker.limits.Badge
import com.reeltracker.limits.Block
import com.reeltracker.limits.Screen
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Draws a [Screen] over Instagram as up to three overlay windows, bottom to top:
 *
 *  1. grey tint: full screen, never touchable, so chats and taps go straight through
 *  2. timer badge: the brain and "today · session", draggable
 *  3. block: the pause or the session popup, full screen, takes every touch and Back
 *
 * Windows of one type stack in the order they were added, so adding a lower layer while a higher
 * one is up means taking the higher ones down and putting them back on top.
 */
internal class OverlayHost(
    private val context: Context,
    private val actions: Actions,
) {
    interface Actions {
        fun pauseDone()
        fun popupContinue()
        fun leave()
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private class Layer(val view: View, val lp: WindowManager.LayoutParams)

    /** Index = z-order. */
    private val layers = arrayOfNulls<Layer>(3)
    private var shownBlock: Block? = null

    fun render(screen: Screen, limits: com.reeltracker.limits.Limits) {
        setLayer(TINT, screen.grey) { tint() }

        val badge = screen.badge
        setLayer(BADGE, badge != null) { badge() }
        if (badge != null) (layers[BADGE]?.view as? BrainCounterView)?.setUsage((badge.todayMs / 60_000).toInt(), badge.text)

        val block = screen.block
        if (block != shownBlock) {
            remove(BLOCK)
            shownBlock = block
            if (block != null) setLayer(BLOCK, true) { block(block, badge, limits) }
        }
    }

    fun clear() {
        for (i in layers.indices.reversed()) remove(i)
        shownBlock = null
    }

    private fun setLayer(index: Int, on: Boolean, make: () -> Layer) {
        val present = layers[index] != null
        if (on == present) return
        if (!on) {
            remove(index)
            return
        }
        val above = (index + 1 until layers.size).filter { layers[it] != null }
        above.forEach { detach(it) }
        val layer = make()
        if (!attach(layer)) return
        layers[index] = layer
        above.forEach { i -> layers[i]?.let { if (!attach(it)) layers[i] = null } }
    }

    private fun remove(index: Int) {
        detach(index)
        layers[index] = null
    }

    private fun detach(index: Int) {
        layers[index]?.let { runCatching { wm.removeView(it.view) } }
    }

    private fun attach(layer: Layer): Boolean =
        try {
            wm.addView(layer.view, layer.lp)
            true
        } catch (_: RuntimeException) {
            false // overlay permission revoked; the service notices and stops drawing
        }

    // ------------------------------------------------------------------ layers

    private fun params(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    /**
     * Android 12+ only lets touches pass through another app's overlay if the window's alpha is
     * at most 0.8, so the strength of the grey lives in the window alpha, never in the colour.
     */
    private fun tint(): Layer {
        val v = View(context).apply { setBackgroundColor(TINT_COLOR) }
        val lp = params(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply { alpha = TINT_ALPHA }
        return Layer(v, lp)
    }

    private fun badge(): Layer {
        val v = BrainCounterView(context)
        val lp = params(
            dp(BrainCounterView.W),
            dp(BrainCounterView.H),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (x0, y0) = savedPosition()
            x = x0
            y = y0
        }
        v.setOnTouchListener(DragListener(v, lp))
        return Layer(v, lp)
    }

    private fun block(block: Block, badge: Badge?, limits: com.reeltracker.limits.Limits): Layer {
        val today = badge?.let { Badge.short(it.todayMs) } ?: "0m"
        val v: View = when (block) {
            is Block.Pause -> PauseView(
                context,
                seconds = block.seconds,
                subtitle = "$today on Instagram today. Your budget is ${Badge.short(limits.dailyBudgetMs)}.",
                onDone = actions::pauseDone,
                onLeave = actions::leave,
            )
            Block.SessionPopup -> SessionPopupView(
                context,
                capMinutes = (limits.sessionCapMs / 60_000).toInt(),
                sessionLabel = badge?.let { Badge.short(it.sessionMs) } ?: "",
                onContinue = actions::popupContinue,
                onLeave = actions::leave,
            )
        }
        // Focusable, so Back comes to us instead of Instagram.
        val lp = params(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        return Layer(v, lp)
    }

    // ------------------------------------------------------------------ badge position

    /** Last dropped position, clamped to this screen; top-right by default. */
    private fun savedPosition(): Pair<Int, Int> {
        val (screenW, screenH) = screen()
        val w = dp(BrainCounterView.W)
        val h = dp(BrainCounterView.H)
        val x = prefs.getInt(KEY_X, screenW - w - dp(8))
        val y = prefs.getInt(KEY_Y, dp(96))
        return x.coerceIn(0, (screenW - w).coerceAtLeast(0)) to y.coerceIn(0, (screenH - h).coerceAtLeast(0))
    }

    private fun screen(): Pair<Int, Int> {
        val m = context.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    /**
     * Drag once the finger has moved past the touch slop; a touch that never moves is a tap.
     * On release the badge eases to the nearer side edge, and that position is saved.
     */
    private inner class DragListener(
        private val v: BrainCounterView,
        private val lp: WindowManager.LayoutParams,
    ) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val (screenW, screenH) = screen()
                        lp.x = (startX + dx.roundToInt()).coerceIn(0, screenW - lp.width)
                        lp.y = (startY + dy.roundToInt()).coerceIn(0, screenH - lp.height)
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                    if (dragging) snapToEdge() else if (e.actionMasked == MotionEvent.ACTION_UP) v.poke()
                    dragging = false
                }
            }
            return true
        }

        private fun snapToEdge() {
            val (screenW, _) = screen()
            val margin = dp(4)
            val target = if (lp.x + lp.width / 2 < screenW / 2) margin else screenW - lp.width - margin
            android.animation.ValueAnimator.ofInt(lp.x, target).apply {
                duration = 220
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener {
                    lp.x = it.animatedValue as Int
                    runCatching { wm.updateViewLayout(v, lp) }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                    }
                })
                start()
            }
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TINT = 0
        const val BADGE = 1
        const val BLOCK = 2

        /** Neutral grey at 55%: washes the colour out of Reels, keeps chat text readable. */
        const val TINT_COLOR = 0xFF808080.toInt()
        const val TINT_ALPHA = 0.55f

        const val PREFS = "overlay" // same file as before, so the badge keeps its old spot
        const val KEY_X = "x"
        const val KEY_Y = "y"
    }
}
