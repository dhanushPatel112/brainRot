package com.reeltracker.limits

/**
 * The apps Brainrot limits. Each has its own budget, session limit, counter and history; only the
 * brain's rot stage adds them together.
 *
 * Usage access only says which app is in front, so each one counts as a whole: all of Instagram,
 * not just Reels; all of YouTube, Shorts and long videos alike.
 *
 * @param id stable key for storage (history rows, settings); never change it
 */
enum class TrackedApp(
    val id: String,
    val label: String,
    /** For tight spots like the widget. */
    val shortLabel: String,
    val packageName: String,
    val defaultBudgetMin: Int,
    val defaultCapMin: Int,
) {
    INSTAGRAM("instagram", "Instagram", "IG", "com.instagram.android", defaultBudgetMin = 30, defaultCapMin = 10),

    /** A longer budget and session limit than Instagram, so most long videos can finish. */
    YOUTUBE("youtube", "YouTube", "YT", "com.google.android.youtube", defaultBudgetMin = 60, defaultCapMin = 20),
}
