package com.lowlatency.visualizer

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Beat-driven haptics: a short vibration pulse on each detected bass transient.
 *
 * [onBands] is called from the GL thread every frame (alongside the Hue tap).
 * Beat detection is the same cheap low-band threshold/rising-edge test used
 * elsewhere, with a minimum gap so it doesn't buzz continuously. Pulse strength
 * and length scale with the beat energy. Vibrator.vibrate() is a fast, async
 * binder call and bursts are throttled to ~a few per second, so it's safe to
 * fire directly from the render thread.
 */
class HapticController(context: Context) {

    private val vibrator: Vibrator? = run {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (v?.hasVibrator() == true) v else null
    }
    private val hasAmplitudeControl = vibrator?.hasAmplitudeControl() == true

    /** Whether this device can vibrate at all (used to enable/hide the toggle). */
    val isSupported: Boolean get() = vibrator != null

    @Volatile var enabled = false
        set(value) {
            field = value
            if (!value) vibrator?.cancel()
        }

    private var lastLow = 0f
    private var lastBeatNs = 0L

    /** Called on the GL thread with the latest [low, mid, high] bands. */
    fun onBands(low: Float, mid: Float, high: Float) {
        if (!enabled) return
        val v = vibrator ?: return

        val now = System.nanoTime()
        val isBeat = low > BEAT_THRESHOLD &&
            (low - lastLow) > BEAT_DELTA &&
            (now - lastBeatNs) > MIN_GAP_NS
        lastLow = low
        if (!isBeat) return
        lastBeatNs = now

        val durationMs = (MIN_MS + low * (MAX_MS - MIN_MS)).toLong().coerceIn(MIN_MS, MAX_MS)
        val effect = if (hasAmplitudeControl) {
            val amp = (low * 255f).toInt().coerceIn(60, 255)
            VibrationEffect.createOneShot(durationMs, amp)
        } else {
            VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        try { v.vibrate(effect) } catch (_: Throwable) { /* ignore transient vibrator errors */ }
    }

    fun release() {
        vibrator?.cancel()
    }

    companion object {
        private const val BEAT_THRESHOLD = 0.42f
        private const val BEAT_DELTA = 0.10f
        private const val MIN_GAP_NS = 120_000_000L   // 120 ms between pulses
        private const val MIN_MS = 12L
        private const val MAX_MS = 45L
    }
}
