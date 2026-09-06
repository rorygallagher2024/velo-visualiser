package com.lowlatency.visualizer.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.appcompat.app.AppCompatActivity

/**
 * Owns the window's preferred display mode: the highest refresh rate offered at
 * the current display's native resolution.
 *
 * `preferredDisplayModeId` is scoped to ONE display — the mode ids on a
 * foldable's cover panel and its inner panel are unrelated numbering. The host
 * declares `screenLayout|screenSize|smallestScreenSize` in `configChanges`, so
 * folding does *not* recreate the activity, and the window was left asking for
 * a mode belonging to the panel it had just folded away from. The compositor
 * can't satisfy that, and every attempt blanks and re-syncs the panel: it was
 * reported from a Galaxy Z Flip as the whole screen flashing rapidly.
 *
 * So the mode is re-picked whenever the display changes underneath us, and it
 * falls back to 0 (system's choice) rather than leaving a foreign id pinned.
 *
 * Every write is guarded against being a no-op, because applying a preferred
 * mode itself triggers [onDisplayChanged] — re-applying unconditionally would
 * be the very flicker loop this class exists to prevent.
 */
class RefreshRateController(
    private val activity: AppCompatActivity,
) : DisplayManager.DisplayListener {

    private val displayManager =
        activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    fun bind() {
        displayManager.registerDisplayListener(this, null)
        apply()
    }

    /** Fold/unfold and rotation both reach here from the host. */
    fun onConfigurationChanged() = apply()

    fun onDestroy() = displayManager.unregisterDisplayListener(this)

    override fun onDisplayAdded(displayId: Int) = Unit

    override fun onDisplayRemoved(displayId: Int) = Unit

    /** Fires for our own mode change too — [applyModeId] absorbs that. */
    override fun onDisplayChanged(displayId: Int) {
        if (displayId == currentDisplay()?.displayId) apply()
    }

    private fun apply() {
        val display = currentDisplay() ?: return
        applyModeId(bestModeFor(display)?.modeId ?: 0, display.displayId)
    }

    /** Highest refresh rate among the modes at the display's native size. */
    private fun bestModeFor(display: Display): Display.Mode? {
        val current = display.mode ?: return null
        return display.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
            }
            .maxByOrNull { it.refreshRate }
    }

    private fun applyModeId(modeId: Int, displayId: Int) {
        val lp = activity.window.attributes
        if (lp.preferredDisplayModeId == modeId) return
        lp.preferredDisplayModeId = modeId
        activity.window.attributes = lp
        Log.i(TAG, "Preferred display mode -> $modeId on display $displayId")
    }

    @Suppress("DEPRECATION")   // defaultDisplay is the pre-R fallback only
    private fun currentDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display
        else activity.windowManager.defaultDisplay

    private companion object {
        const val TAG = "RefreshRate"
    }
}
