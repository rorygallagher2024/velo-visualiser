package com.lowlatency.visualizer.nanoleaf

import android.graphics.Color
import com.lowlatency.visualizer.BeatBus
import com.lowlatency.visualizer.LightingSettings
import com.lowlatency.visualizer.LinkSync
import com.lowlatency.visualizer.NativeBridge

/**
 * The one colour every paired device paints, derived from the audio bands or the
 * Ableton Link phase. Owned by the sender thread; band levels arrive from the GL
 * thread, hence the volatiles.
 */
internal class NanoleafColorEngine {

    data class RGB(val r: Int, val g: Int, val b: Int)

    @Volatile private var low = 0f
    @Volatile private var mid = 0f
    @Volatile private var high = 0f
    @Volatile private var linkBeatCount = 0

    private var flash = 0f
    private var lastBeat = -1L
    private var currentHue = AUDIO_HUE_BASS
    private var currentSat = SAT_AUDIO

    fun setBands(low: Float, mid: Float, high: Float) {
        this.low = low
        this.mid = mid
        this.high = high
    }

    fun onLinkBeat() {
        linkBeatCount++
    }

    /** The colour for this frame. */
    fun nextColour(): RGB =
        if (LinkSync.enabled) linkColour() else audioColour(low, mid, high)

    private fun audioColour(l: Float, m: Float, h: Float): RGB {
        val cfg = LightingSettings
        val bc = BeatBus.beatCount.toLong()
        if (bc != lastBeat) {
            flash = BeatBus.loudness
            lastBeat = bc
        }

        if (flash > 0.01f) {
            val trebleWeight = (h + m * 0.5f) / (l + m + h + 0.001f)
            val targetHue = AUDIO_HUE_BASS + (AUDIO_HUE_TREBLE - AUDIO_HUE_BASS) * trebleWeight
            currentHue += (targetHue - currentHue) * 0.3f
            currentSat += (SAT_AUDIO - currentSat) * 0.3f
        }

        flash *= FLASH_DECAY
        // Shared curve: a dim base tracks the mic energy, the beat punches on
        // top — identical across all brands.
        return hsvToRgb(currentHue, currentSat, cfg.audioBrightnessValue(l, m, h, flash))
    }

    private fun linkColour(): RGB {
        val cfg = LightingSettings
        val phase = NativeBridge.nativeLinkBeatPhase().toFloat()
        val isLinkOnBeat = phase < 0.1f
        val currentBeat = linkBeatCount

        if (cfg.linkBeatFlashEnabled && BeatBus.gateOpen) {
            if (isLinkOnBeat && currentBeat.toLong() != lastBeat) {
                flash = cfg.beatFlashAmp(BeatBus.loudness)
                lastBeat = currentBeat.toLong()
            }
        }

        val activeFlash = if (cfg.linkBeatFlashEnabled) flash else 0.5f
        val bri = cfg.linkBrightnessValue(activeFlash)
        flash *= FLASH_DECAY

        return hsvToRgb(PURPLE_HUE, 0.9f, bri)
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float): RGB {
        val color = Color.HSVToColor(floatArrayOf(h, s, v))
        return RGB((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
    }

    companion object {
        private const val AUDIO_HUE_BASS = 280f
        private const val AUDIO_HUE_TREBLE = 190f
        private const val PURPLE_HUE = 280f
        private const val SAT_AUDIO = 0.9f
        private const val FLASH_DECAY = 0.85f
    }
}
