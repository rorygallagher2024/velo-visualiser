package com.lowlatency.visualizer

import android.os.Build

/**
 * Whether the float/10-bit *window* surface (and the HDR window colour mode
 * that goes with it) is used, read by [com.lowlatency.visualizer.gl.HdrEGLConfigChooser]
 * and the activity's window setup.
 *
 * Exynos phones (e.g. Galaxy S24 SM-S921B, Xclipse GPU) were reported flashing
 * the whole screen violently from launch — a photosensitivity hazard — while
 * Snapdragon Galaxies render fine. The surface is created without an explicit
 * colour space, which leaves its interpretation to the driver/compositor. Not
 * reproduced in-house, so on Exynos it is off unless the user opts in from the
 * (Exynos-only) settings switch. Only the screen surface drops to 8-bit (losing
 * >1.0 headroom); the offscreen FP16 bloom pipeline is unaffected.
 *
 * Read once at surface/window creation, so a change applies on the next launch.
 */
object HdrOutputSettings {

    val isExynos: Boolean by lazy { isSamsungSoc() }

    /** User opt-in, only consulted on Exynos. Set from prefs before the surface exists. */
    @Volatile var exynosOptIn: Boolean = false

    val wideSurfaceEnabled: Boolean get() = !isExynos || exynosOptIn

    private fun isSamsungSoc(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            Build.SOC_MANUFACTURER.equals("Samsung", ignoreCase = true)
        ) return true
        // Pre-S there is no SOC_* field; Exynos boards name themselves
        // (e.g. "samsungexynos8895", "exynos990", "s5e9925").
        val hw = Build.HARDWARE.lowercase()
        return hw.contains("exynos") || hw.startsWith("s5e")
    }
}
