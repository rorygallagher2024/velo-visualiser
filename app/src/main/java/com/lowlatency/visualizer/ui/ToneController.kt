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
 * Test-tone source UI: the frequency / level sliders and the X/Y toggle, plus
 * ownership of the [ToneGenerator]. The panel shows only while Tone is the live
 * source (visibility driven from [refresh], called on every source change).
 *
 * X/Y off: Left and Right share one frequency (centred mono tone). X/Y on: the
 * right channel gets its own frequency slider, so integer ratios trace stable
 * Lissajous figures on the scope scenes.
 *
 * @param isToneMode whether Tone is the live source (owned by AudioSourceController).
 * @param onEnterTone flips the app to the TONE source (AudioSourceController.enterTone).
 */
class ToneController(
    private val activity: AppCompatActivity,
    private val isToneMode: () -> Boolean,
    private val onEnterTone: () -> Unit,
) {
    private val generator = ToneGenerator()

    private lateinit var panel: View
    private lateinit var freqLabel: TextView
    private lateinit var freqSeek: SeekBar
    private lateinit var freqRightLabel: TextView
    private lateinit var freqRightSeek: SeekBar
    private lateinit var levelLabel: TextView
    private lateinit var levelSeek: SeekBar
    private lateinit var xyToggle: Button

    private var xyMode = false

    fun bind() {
        panel = activity.findViewById(R.id.tone_panel)
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
        xyToggle.setOnClickListener { setXyMode(!xyMode) }
    }

    /** Tapped the Tone segment: flip the source and start generating. */
    fun enter() {
        onEnterTone()
        generator.start()
    }

    /** Called when the source leaves TONE (AudioSourceController.stopTone). */
    fun stop() {
        generator.stop()
    }

    /** Show/hide the panel to match the live source. */
    fun refresh() {
        if (!::panel.isInitialized) return
        panel.visibility = if (isToneMode()) View.VISIBLE else View.GONE
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

    private fun setXyMode(on: Boolean) {
        xyMode = on
        xyToggle.isSelected = on
        freqRightLabel.visibility = if (on) View.VISIBLE else View.GONE
        freqRightSeek.visibility = if (on) View.VISIBLE else View.GONE
        // Leaving X/Y recentres to a mono tone; entering it seeds the right
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
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        }

    companion object {
        private const val MAX_POS = 1000
        private const val MIN_HZ = 20.0
        private const val MAX_HZ = 20_000.0
        private const val DEFAULT_LEVEL_POS = 120         // 12% amplitude (~-18 dBFS)

        // Log-mapped frequency slider: pos/MAX in [0,1] -> [MIN_HZ, MAX_HZ].
        private fun posToHz(pos: Int): Float =
            (MIN_HZ * (MAX_HZ / MIN_HZ).pow(pos / MAX_POS.toDouble()))
                .toFloat().coerceIn(MIN_HZ.toFloat(), MAX_HZ.toFloat())

        // Slider position for a 440 Hz default (inverse of posToHz).
        private val DEFAULT_FREQ_POS =
            (MAX_POS * ln(440.0 / MIN_HZ) / ln(MAX_HZ / MIN_HZ)).roundToInt()
    }
}
