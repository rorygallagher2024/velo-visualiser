package com.lowlatency.visualizer.ui

import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lowlatency.visualizer.R
import com.lowlatency.visualizer.audio.ToneGenerator
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Test-tone source: owns the [ToneGenerator] and a floating controls overlay
 * (frequency / level / X-Y), shown on a canvas tap exactly like the local-file
 * media bar. The controls live only here — the settings menu just selects the
 * Tone source; there is no duplicate panel.
 *
 * X-Y off: Left and Right share one frequency (centred mono tone). X-Y on: the
 * right channel gets its own frequency slider, so integer ratios trace stable
 * Lissajous figures on the scope scenes.
 *
 * The level slider is the signal amplitude (a safety control, defaulting low),
 * separate from device volume: because the visuals mirror the tone *before*
 * the OS applies volume, muting the phone still leaves the visuals reacting.
 *
 * @param isToneMode whether Tone is the live source (owned by AudioSourceController).
 * @param onEnterTone flips the app to the TONE source (AudioSourceController.enterTone).
 * @param isMenuOpen whether the settings sheet is open (tap is ignored then).
 * @param closeMenu dismiss the settings sheet after selecting Tone.
 */
class ToneController(
    private val activity: AppCompatActivity,
    private val isToneMode: () -> Boolean,
    private val onEnterTone: () -> Unit,
    private val isMenuOpen: () -> Boolean,
    private val closeMenu: () -> Unit,
    private val onOverlayLayoutChanged: () -> Unit = {},
) {
    private val generator = ToneGenerator()

    private lateinit var overlay: View
    private lateinit var freqLabel: TextView
    private lateinit var freqSeek: SeekBar
    private lateinit var freqRightLabel: TextView
    private lateinit var freqRightSeek: SeekBar
    private lateinit var levelLabel: TextView
    private lateinit var levelSeek: SeekBar
    private lateinit var xyToggle: Button

    private var xyMode = false
    private var scrubbing = false
    private val hideRunnable = Runnable { hideOverlay() }

    fun bind() {
        overlay = activity.findViewById(R.id.tone_controls_overlay)
        freqLabel = activity.findViewById(R.id.tone_freq_label)
        freqSeek = activity.findViewById(R.id.tone_freq_seek)
        freqRightLabel = activity.findViewById(R.id.tone_freq_right_label)
        freqRightSeek = activity.findViewById(R.id.tone_freq_right_seek)
        levelLabel = activity.findViewById(R.id.tone_level_label)
        levelSeek = activity.findViewById(R.id.tone_level_seek)
        xyToggle = activity.findViewById(R.id.tone_xy_toggle)

        freqSeek.progress = DEFAULT_FREQ_POS
        freqRightSeek.progress = DEFAULT_FREQ_POS
        levelSeek.progress = DEFAULT_LEVEL_POS
        applyLeftFreq()
        applyRightFreq()
        applyLevel()

        freqSeek.setOnSeekBarChangeListener(seekListener { applyLeftFreq() })
        freqRightSeek.setOnSeekBarChangeListener(seekListener { applyRightFreq() })
        levelSeek.setOnSeekBarChangeListener(seekListener { applyLevel() })
        xyToggle.setOnClickListener {
            setXyMode(!xyMode)
            scheduleHide()   // keep the overlay up a moment after the toggle
        }
    }

    /** Tapped the Tone segment: flip the source, start generating, reveal controls. */
    fun enter() {
        onEnterTone()
        generator.start()
        closeMenu()
        showOverlayTemporarily()
    }

    /** Called when the source leaves TONE (AudioSourceController.stopTone). */
    fun stop() {
        generator.stop()
        hideOverlay()
    }

    /** Canvas tap toggles the overlay (never over the open menu). */
    fun onCanvasTap() {
        if (!isToneMode() || isMenuOpen()) return
        if (overlay.visibility == View.VISIBLE) hideOverlay() else showOverlayTemporarily()
    }

    /** Hide the overlay if the source is no longer Tone (menu source switch). */
    fun refresh() {
        if (!::overlay.isInitialized) return
        if (!isToneMode()) hideOverlay()
    }

    /** Bottom edge of the visible overlay, so transient labels stack below it. */
    fun overlayBottom(): Int =
        if (::overlay.isInitialized && overlay.visibility == View.VISIBLE && overlay.height > 0) {
            overlay.bottom
        } else {
            0
        }

    fun onPause() {
        if (isToneMode()) generator.stop()
    }

    fun onResume() {
        if (isToneMode()) generator.start()
    }

    fun onDestroy() {
        generator.stop()
    }

    private fun showOverlayTemporarily() {
        overlay.removeCallbacks(hideRunnable)
        if (overlay.visibility != View.VISIBLE) {
            overlay.visibility = View.VISIBLE
            overlay.translationY = offscreenY()
        }
        overlay.animate().translationY(0f).setDuration(ANIM_MS).start()
        overlay.post { onOverlayLayoutChanged() }   // let labels stack below it
        scheduleHide()
    }

    private fun hideOverlay() {
        if (!::overlay.isInitialized || overlay.visibility != View.VISIBLE) return
        overlay.removeCallbacks(hideRunnable)
        overlay.animate().translationY(offscreenY()).setDuration(ANIM_MS)
            .withEndAction {
                overlay.visibility = View.GONE
                onOverlayLayoutChanged()
            }
            .start()
    }

    private fun scheduleHide() {
        overlay.removeCallbacks(hideRunnable)
        // Never auto-hide from under a scrubbing finger.
        if (!scrubbing) overlay.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    /** Y offset that parks the overlay just above the top edge. */
    private fun offscreenY(): Float {
        val bottom = overlay.bottom
        return if (bottom > 0) -bottom.toFloat()
        else -FALLBACK_OFFSET_DP * activity.resources.displayMetrics.density
    }

    private fun setXyMode(on: Boolean) {
        xyMode = on
        xyToggle.isSelected = on
        freqRightLabel.visibility = if (on) View.VISIBLE else View.GONE
        freqRightSeek.visibility = if (on) View.VISIBLE else View.GONE
        // Leaving X-Y recentres to a mono tone; entering it seeds the right
        // channel from its slider.
        if (on) applyRightFreq() else applyLeftFreq()
    }

    private fun applyLeftFreq() {
        val hz = posToHz(freqSeek.progress)
        generator.leftHz = hz
        if (!xyMode) generator.rightHz = hz
        freqLabel.text = activity.getString(R.string.tone_freq_label, hz.roundToInt())
    }

    private fun applyRightFreq() {
        val hz = posToHz(freqRightSeek.progress)
        generator.rightHz = hz
        freqRightLabel.text = activity.getString(R.string.tone_freq_right_label, hz.roundToInt())
    }

    private fun applyLevel() {
        val pos = levelSeek.progress
        generator.level = pos / MAX_POS.toFloat()
        levelLabel.text = activity.getString(R.string.tone_level_label, (pos / 10f).roundToInt())
    }

    private fun seekListener(onChange: () -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) = onChange()
            override fun onStartTrackingTouch(sb: SeekBar) {
                scrubbing = true
                overlay.removeCallbacks(hideRunnable)   // stay up while adjusting
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                scrubbing = false
                scheduleHide()
            }
        }

    companion object {
        private const val MAX_POS = 1000
        private const val MIN_HZ = 20.0
        private const val MAX_HZ = 20_000.0
        private const val DEFAULT_LEVEL_POS = 100         // 10% amplitude — gentle first sound
        private const val HIDE_DELAY_MS = 4_000L
        private const val ANIM_MS = 220L
        private const val FALLBACK_OFFSET_DP = 280f

        // Log-mapped frequency slider: pos/MAX in [0,1] -> [MIN_HZ, MAX_HZ].
        private fun posToHz(pos: Int): Float =
            (MIN_HZ * (MAX_HZ / MIN_HZ).pow(pos / MAX_POS.toDouble()))
                .toFloat().coerceIn(MIN_HZ.toFloat(), MAX_HZ.toFloat())

        // Slider position for a 440 Hz default (inverse of posToHz).
        private val DEFAULT_FREQ_POS =
            (MAX_POS * ln(440.0 / MIN_HZ) / ln(MAX_HZ / MIN_HZ)).roundToInt()
    }
}
