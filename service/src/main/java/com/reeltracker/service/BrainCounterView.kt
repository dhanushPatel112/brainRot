package com.reeltracker.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.random.Random

/**
 * The floating timer badge: a brain that rots as today's Instagram minutes climb, over a pill
 * that reads "today · this session".
 *
 *  - idle      the brain breathes
 *  - +1 minute it squishes and sheds a drop (sweat, then slime once it turns green)
 *  - new stage lightning, a shockwave ring, a shake, and the brain swaps to the next sprite
 *  - stages 5+ smoke curls off the top, continuously
 *
 * Plain Views, not Compose: it lives in an overlay window. Every child is drawn
 * inside this view's bounds, because an overlay window clips anything that spills outside it.
 */
@SuppressLint("ViewConstructor")
internal class BrainCounterView(context: Context) : FrameLayout(context) {

    private val brain = ImageView(context)
    private val badge = TextView(context)
    private val fx = FrameLayout(context) // one-shot effects are added and removed here
    private val smoke = List(3) { ImageView(context) }

    private var minutes: Int? = null
    private var stage = 0
    private val breathing: AnimatorSet
    private var smokeLoop: ValueAnimator? = null

    init {
        clipChildren = false
        layoutParams = LayoutParams(dp(W), dp(H))

        // Smoke sits behind the brain, rising from its top edge.
        val smokeSprites = intArrayOf(R.drawable.fx_smoke_1, R.drawable.fx_smoke_2, R.drawable.fx_smoke_3)
        smoke.forEachIndexed { i, v ->
            v.setImageResource(smokeSprites[i])
            v.alpha = 0f
            addView(v, LayoutParams(dp(18), dp(26)).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = dp((W - BRAIN_W) / 2 + 5 + i * 18)
                topMargin = dp(BRAIN_TOP - 16)
            })
        }

        brain.setImageResource(BrainRot.sprite(0))
        brain.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(brain, LayoutParams(dp(BRAIN_W), dp(BRAIN_H)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(BRAIN_TOP)
        })
        // Squish from the bottom, like it is sitting on something.
        brain.post { brain.pivotX = brain.width / 2f; brain.pivotY = brain.height * 0.85f }

        addView(fx, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        badge.apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = dp(30)
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), dp(1), dp(8), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xE6141416.toInt())
                setStroke(dp(1), ACCENT)
            }
            text = "–"
        }
        addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(4)
        })

        breathing = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(brain, View.SCALE_X, 1f, 1.035f).breathe(),
                ObjectAnimator.ofFloat(brain, View.SCALE_Y, 1f, 1.05f).breathe(),
            )
        }

        contentDescription = context.getString(R.string.overlay_description, "–")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        breathing.start()
        if (BrainRot.isSmoking(stage)) startSmoke()
    }

    override fun onDetachedFromWindow() {
        breathing.cancel()
        stopSmoke()
        fx.removeAllViews()
        super.onDetachedFromWindow()
    }

    /**
     * Shows today's [totalMinutes] as the brain's stage and [label] in the pill. The first value
     * after attaching is shown without fuss; after that each new minute plays the drip, and
     * crossing a threshold plays the stage-up.
     */
    fun setUsage(totalMinutes: Int, label: String) {
        badge.text = label
        contentDescription = context.getString(R.string.overlay_description, label)

        val previous = minutes
        minutes = totalMinutes
        val newStage = BrainRot.stage(totalMinutes)
        if (previous == null || totalMinutes < previous) {
            // First value, or a new day: no celebration, just the right state.
            applyStage(newStage)
            return
        }
        if (totalMinutes == previous) return

        if (newStage > stage) {
            stageUp(newStage)
        } else {
            minutePassed()
        }
    }

    /** A tap with no drag: a quick wobble, so the widget feels alive under the finger. */
    fun poke() {
        ObjectAnimator.ofFloat(brain, View.ROTATION, 0f, -10f, 8f, -5f, 3f, 0f).apply {
            duration = 420
            start()
        }
    }

    // ------------------------------------------------------------------ animations

    private fun minutePassed() {
        squish()
        popBadge()
        dropFrom(if (BrainRot.isSlimy(stage)) R.drawable.fx_drop_green else R.drawable.fx_drop_blue)
    }

    private fun stageUp(newStage: Int) {
        popBadge()
        shake()
        ring()
        bolts()
        // Swap the sprite at the peak of the flash.
        brain.animate().alpha(0.35f).setDuration(120).withEndAction {
            applyStage(newStage)
            brain.animate().alpha(1f).setDuration(220).start()
        }.start()
        dropFrom(if (BrainRot.isSlimy(newStage)) R.drawable.fx_drip_green else R.drawable.fx_drip_blue, big = true)
    }

    private fun applyStage(newStage: Int) {
        stage = newStage
        brain.setImageResource(BrainRot.sprite(newStage))
        (badge.background as GradientDrawable).setStroke(dp(1), if (BrainRot.isSlimy(newStage)) SLIME else ACCENT)
        if (BrainRot.isSmoking(newStage)) startSmoke() else stopSmoke()
    }

    private fun squish() {
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(brain, View.SCALE_Y, 1f, 0.84f, 1.08f, 1f),
                ObjectAnimator.ofFloat(brain, View.SCALE_X, 1f, 1.12f, 0.96f, 1f),
            )
            duration = 360
            start()
        }
    }

    private fun shake() {
        ObjectAnimator.ofFloat(brain, View.TRANSLATION_X, 0f, -dp(5f), dp(5f), -dp(4f), dp(3f), 0f).apply {
            duration = 380
            start()
        }
    }

    private fun popBadge() {
        badge.animate().cancel()
        badge.scaleX = 1.3f
        badge.scaleY = 1.3f
        badge.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start()
    }

    /** A drop falls from under the brain and fades; slime falls slower and stretches. */
    private fun dropFrom(sprite: Int, big: Boolean = false) {
        val w = dp(if (big) 30 else 10)
        val h = dp(if (big) 28 else 16)
        val drop = ImageView(context).apply { setImageResource(sprite) }
        val left = dp(W / 2) - w / 2 + if (big) 0 else Random.nextInt(-dp(16), dp(16))
        fx.addView(drop, LayoutParams(w, h).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = left
            topMargin = dp(BRAIN_TOP + BRAIN_H - 16)
        })
        drop.alpha = 0f
        drop.pivotY = 0f
        drop.animate()
            .alpha(1f)
            .translationY(dp(if (big) 8f else 18f))
            .scaleY(if (BrainRot.isSlimy(stage)) 1.35f else 1f)
            .setDuration(if (big) 700 else 520)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                drop.animate().alpha(0f).setDuration(220).withEndAction { fx.removeView(drop) }.start()
            }
            .start()
    }

    /** A shockwave ring bursting from the brain's centre. */
    private fun ring() {
        val size = dp(BRAIN_W)
        val ring = ImageView(context).apply { setImageResource(R.drawable.fx_ring) }
        fx.addView(ring, LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(BRAIN_TOP + BRAIN_H / 2) - size / 2
        })
        ring.scaleX = 0.3f
        ring.scaleY = 0.3f
        ring.alpha = 0.9f
        ring.animate().scaleX(1.25f).scaleY(1.25f).alpha(0f).setDuration(520)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { fx.removeView(ring) }
            .start()
    }

    /** Lightning flickers over the brain, twice. */
    private fun bolts() {
        val bolt = ImageView(context).apply { setImageResource(R.drawable.fx_bolt) }
        fx.addView(bolt, LayoutParams(dp(44), dp(40)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 0
        })
        ObjectAnimator.ofFloat(bolt, View.ALPHA, 0f, 1f, 0.1f, 1f, 0f).apply {
            duration = 560
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fx.removeView(bolt)
                }
            })
            start()
        }
    }

    private fun startSmoke() {
        if (smokeLoop != null || !isAttachedToWindow) return
        // One driver for all three wisps, each offset in phase, so they never pulse together.
        smokeLoop = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                smoke.forEachIndexed { i, v ->
                    val p = (t + i / 3f) % 1f
                    v.translationY = -dp(14f) * p
                    v.alpha = (if (p < 0.3f) p / 0.3f else (1f - p) / 0.7f) * 0.75f
                }
            }
            start()
        }
    }

    private fun stopSmoke() {
        smokeLoop?.cancel()
        smokeLoop = null
        smoke.forEach { it.alpha = 0f }
    }

    // ------------------------------------------------------------------ helpers

    private fun ObjectAnimator.breathe() = apply {
        duration = 1700
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object {
        /** Window size in dp: room above the brain for smoke and bolts, below for drops and the badge. */
        const val W = 128
        const val H = 104
        private const val BRAIN_W = 62
        private const val BRAIN_H = 52
        private const val BRAIN_TOP = 18

        private const val ACCENT = 0xFFFF5A4E.toInt()
        private const val SLIME = 0xFF8BC34A.toInt()
    }
}
