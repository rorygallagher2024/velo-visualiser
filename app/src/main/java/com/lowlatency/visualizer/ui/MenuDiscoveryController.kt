package com.lowlatency.visualizer.ui

import android.animation.ObjectAnimator
import android.content.SharedPreferences
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.lowlatency.visualizer.R

/**
 * Menu-discovery affordance — the single deliberate exception to the app's
 * "no on-screen chrome" rule, and a self-destructing one.
 *
 * The settings sheet (swipe up) is the hub for everything the immersive canvas
 * hides: lighting, Ableton Link, ambient mode, themes, the full scene list. A
 * first-time user who never finds it sees one visual and bounces. So until the
 * menu is opened just once — *ever* — a faint chevron breathes at the bottom edge.
 * The instant the sheet opens ([onMenuOpened]) the flag is set and the cue fades
 * for good; it never shows again. Not permanent chrome — a first-run affordance
 * that deletes itself on success.
 *
 * The host drives timing: it calls [reveal] only once the intro / "feel the speed"
 * sequence is done (so the cue never fights the opening moments), [onMenuOpened]
 * when the sheet opens, and forwards [onPause]/[onDestroy].
 */
class MenuDiscoveryController(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val isMenuOpen: () -> Boolean,
) {
    private lateinit var cue: View
    private var alphaAnim: ObjectAnimator? = null
    private var bobAnim: ObjectAnimator? = null

    fun bind() {
        cue = activity.findViewById(R.id.swipe_up_cue)
    }

    private fun discovered(): Boolean = prefs.getBoolean(KEY_DISCOVERED, false)

    /** Show the breathing cue (no-op once discovered, while the menu is open, or unbound). */
    fun reveal() {
        if (discovered() || isMenuOpen() || !::cue.isInitialized) return
        if (cue.visibility != View.VISIBLE) {
            cue.visibility = View.VISIBLE
            cue.alpha = 0f
            cue.animate().alpha(BREATHE_MAX).setDuration(600L)
                .withEndAction { startBreathing() }.start()
        } else {
            startBreathing()
        }
    }

    /** The sheet was opened — retire the cue permanently. */
    fun onMenuOpened() {
        if (discovered() || !::cue.isInitialized) return
        prefs.edit { putBoolean(KEY_DISCOVERED, true) }
        stopBreathing()
        cue.animate().cancel()
        cue.animate().alpha(0f).translationY(0f).setDuration(300L)
            .withEndAction { cue.visibility = View.GONE }.start()
    }

    fun onPause() = stopBreathing()

    fun onDestroy() = stopBreathing()

    private fun startBreathing() {
        stopBreathing()
        val bob = activity.resources.displayMetrics.density * BOB_DP
        alphaAnim = ObjectAnimator.ofFloat(cue, View.ALPHA, BREATHE_MIN, BREATHE_MAX).apply {
            duration = BREATHE_MS
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        bobAnim = ObjectAnimator.ofFloat(cue, View.TRANSLATION_Y, 0f, -bob).apply {
            duration = BREATHE_MS
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopBreathing() {
        alphaAnim?.cancel(); alphaAnim = null
        bobAnim?.cancel(); bobAnim = null
    }

    companion object {
        private const val KEY_DISCOVERED = "menu_discovered"
        private const val BREATHE_MIN = 0.28f
        private const val BREATHE_MAX = 0.62f
        private const val BREATHE_MS = 1300L
        private const val BOB_DP = 5f
    }
}
