package com.lowlatency.visualizer.ui

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.lowlatency.visualizer.R

/**
 * "Shuffle" mode — when on, auto-advances to a random scene every [INTERVAL_MS]
 * via [advanceScene] (the surface view picks within favourites if set). The
 * renderer's existing fade transition crossfades between scenes, so there's no
 * extra animation work here.
 *
 * Any scene change resets the countdown ([onSceneChanged]) — the shuffle's own
 * advance and a manual swipe alike — so a manual pick always gets a full interval
 * before the next auto-advance. Persisted across launches like the other toggles.
 */
class ShuffleController(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val advanceScene: () -> Unit,
) {
    private lateinit var btn: Button
    private val handler = Handler(Looper.getMainLooper())
    private val advance = Runnable { advanceScene(); schedule() }

    var enabled = false
        private set

    fun bind() {
        btn = activity.findViewById(R.id.btn_shuffle)
        btn.setOnClickListener { setEnabled(!enabled) }
        setEnabled(prefs.getBoolean(KEY_SHUFFLE, false))
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        prefs.edit { putBoolean(KEY_SHUFFLE, on) }
        btn.isSelected = on
        btn.setText(if (on) R.string.shuffle_on else R.string.shuffle_off)
        if (on) schedule() else handler.removeCallbacks(advance)
    }

    /** Reset the countdown on any scene change (auto or manual). */
    fun onSceneChanged() { if (enabled) schedule() }

    fun onResume() { if (enabled) schedule() }
    fun onPause() = handler.removeCallbacks(advance)
    fun onDestroy() = handler.removeCallbacks(advance)

    private fun schedule() {
        handler.removeCallbacks(advance)
        handler.postDelayed(advance, INTERVAL_MS)
    }

    companion object {
        private const val KEY_SHUFFLE = "shuffle_enabled"
        private const val INTERVAL_MS = 20_000L   // auto-advance cadence
    }
}
