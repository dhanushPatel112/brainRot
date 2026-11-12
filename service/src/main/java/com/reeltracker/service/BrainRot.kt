package com.reeltracker.service

/**
 * How rotten today's brain is. One place maps today's minutes (Instagram and YouTube added
 * together) to a sprite, so the
 * overlay, the home-screen widget and the app can never disagree.
 */
object BrainRot {

    /** Stage i is reached at [THRESHOLDS][i] minutes across all tracked apps today. */
    private val THRESHOLDS = intArrayOf(0, 5, 15, 30, 45, 60, 90)

    private val SPRITES = intArrayOf(
        R.drawable.brain_stage_0,
        R.drawable.brain_stage_1,
        R.drawable.brain_stage_2,
        R.drawable.brain_stage_3,
        R.drawable.brain_stage_4,
        R.drawable.brain_stage_5,
        R.drawable.brain_stage_6,
    )

    private val LABELS = arrayOf("Fresh", "Bruised", "Foggy", "Fried", "Mushy", "Rotting", "Brain rot")

    const val STAGES = 7

    fun stage(minutes: Int): Int = THRESHOLDS.indexOfLast { minutes >= it }.coerceAtLeast(0)

    fun sprite(stage: Int): Int = SPRITES[stage.coerceIn(0, STAGES - 1)]

    fun label(stage: Int): String = LABELS[stage.coerceIn(0, STAGES - 1)]

    /** Minutes left until the next stage, or null at the last one. */
    fun untilNext(minutes: Int): Int? {
        val next = stage(minutes) + 1
        return if (next < STAGES) THRESHOLDS[next] - minutes else null
    }

    /** From stage 4 the brain is green: drips and drops switch from sweat to slime. */
    fun isSlimy(stage: Int) = stage >= 4

    /** Smoke rises off the last two stages. */
    fun isSmoking(stage: Int) = stage >= 5
}
