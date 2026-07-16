package com.lowlatency.visualizer.ui

import android.content.res.Resources

/**
 * Shared sizing for the floating canvas overlays (media bar, tone controls).
 *
 * One policy on every screen: fill the window's width minus comfortable edge
 * margins, capped so tablets and opened foldables get a generous instrument
 * rather than a wall-to-wall banner. Computed from the LIVE window (not
 * resource buckets) because the app survives fold/unfold without recreating.
 *
 * [scale] is 0 at phone width and 1 at the cap — overlays use it to grow
 * type and touch targets along the same curve as their width.
 */
object OverlayMetrics {

    private const val EDGE_MARGIN_DP = 16f    // breathing room each side on phones
    private const val MAX_WIDTH_DP = 640f     // cap for tablets / opened foldables
    private const val SCALE_FLOOR_DP = 340f   // typical phone overlay width → scale 0

    fun widthDp(resources: Resources): Float {
        val window = resources.configuration.screenWidthDp.toFloat()
        return (window - 2f * EDGE_MARGIN_DP).coerceAtMost(MAX_WIDTH_DP)
    }

    fun widthPx(resources: Resources): Int =
        (widthDp(resources) * resources.displayMetrics.density).toInt()

    fun scale(resources: Resources): Float =
        ((widthDp(resources) - SCALE_FLOOR_DP) / (MAX_WIDTH_DP - SCALE_FLOOR_DP))
            .coerceIn(0f, 1f)
}
