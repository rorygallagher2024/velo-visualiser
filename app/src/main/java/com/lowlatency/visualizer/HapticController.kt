package com.lowlatency.visualizer

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

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

    // Route as MEDIA so the OS is less likely to filter it as "touch feedback"
    // (which is suppressed when that system setting / ring-vibration is off).
    private val mediaAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    init {
        Log.i(TAG, "Haptics init: vibrator=${vibrator != null}, amplitudeControl=$hasAmplitudeControl")
    }

    /** Whether this device can vibrate at all (used to enable/hide the toggle). */
    val isSupported: Boolean get() = vibrator != null

    /** Fire one strong, long pulse immediately to confirm vibration works at all. */
    fun previewPulse() {
        val v = vibrator ?: run { Log.w(TAG, "previewPulse: no vibrator"); return }
        emit(v, 220L, 255)
        Log.i(TAG, "previewPulse fired (220ms, max amplitude)")
    }

    @Volatile var enabled = false
        set(value) {
            field = value
            if (!value) vibrator?.cancel()
        }

    private val beat = BeatDetector(debugName = "haptics")

    /**
     * Internal/system audio arrives much hotter than the unprocessed mic, so the
     * same detection over-fires. Raise the threshold for internal audio only.
     */
    fun setSystemAudio(internal: Boolean) {
        beat.thresholdScale = if (internal) INTERNAL_THRESHOLD_SCALE else 1f
    }

    /** Called on the GL thread with the latest raw PCM window. */
    fun onPcm(pcm: FloatArray) {
        // Keep the detector warm even when disabled so re-enabling doesn't fire
        // a spurious pulse from a cold baseline.
        val isBeat = beat.update(pcm)
        if (!enabled) return
        val v = vibrator ?: return
        if (isBeat) pulse(v, 0.9f)
    }

    private fun pulse(v: Vibrator, energy: Float) {
        val durationMs = (MIN_MS + energy * (MAX_MS - MIN_MS)).toLong().coerceIn(MIN_MS, MAX_MS)
        val amp = (energy * 255f).toInt().coerceIn(110, 255)
        emit(v, durationMs, amp)
    }

    private fun emit(v: Vibrator, durationMs: Long, amplitude: Int) {
        val effect = if (hasAmplitudeControl) {
            VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255))
        } else {
            VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        try {
            @Suppress("DEPRECATION")
            v.vibrate(effect, mediaAttrs)
        } catch (t: Throwable) {
            Log.w(TAG, "vibrate failed: ${t.message}")
        }
    }

    fun release() {
        vibrator?.cancel()
    }

    companion object {
        private const val TAG = "HapticController"
        private const val MIN_MS = 20L
        private const val MAX_MS = 70L
        private const val INTERNAL_THRESHOLD_SCALE = 3.0f   // internal audio is much hotter
    }
}
