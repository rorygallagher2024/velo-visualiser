package com.lowlatency.visualizer.ui.lighting

import android.view.View
import android.widget.Button

/**
 * One lighting brand's self-contained panel: its selector chip, its container
 * view, its connect/scan/sync UI, and the routing of audio bands + Link beats to
 * its protocol client. The [com.lowlatency.visualizer.ui.LightingController]
 * coordinator owns the panel and drives it through this uniform contract —
 * binding, audio fan-out, the mic-only stop, advanced-button visibility, and the
 * activity lifecycle.
 *
 * This is the seam for *new* brands: WLED is the first to use it, and Govee will
 * follow the same template. The original three brands (Hue/LIFX/Nanoleaf) are
 * still inline in the coordinator and can migrate onto this interface later.
 */
interface LightBrandPanel {
    /** The brand's panel container, shown when its selector chip is active. */
    val container: View

    /** The brand's chip in the lighting brand selector. */
    val selectorButton: Button

    /** The brand's "Advanced…" button, shown only while it is syncing (null if none). */
    val advancedButton: Button?

    /** True while this brand is actively streaming to its lights. */
    val isSyncing: Boolean

    /** Resolve views and wire this panel's own listeners. */
    fun bind()

    /** Per-frame audio bands from the GL thread (allocation-free). */
    fun onBands(low: Float, mid: Float, high: Float)

    /** Raw (ungated) Ableton Link beat from the GL thread. */
    fun onLinkBeat()

    /** Switching to internal/system audio: light sync is mic-only, so stop. */
    fun stopForSystemAudio()

    fun onResume() {}
    fun onPause() {}
    fun onDestroy() {}
}
