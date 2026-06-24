package com.lowlatency.visualizer.ui

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lowlatency.visualizer.BeatSettings
import com.lowlatency.visualizer.HueStrobeSettings
import com.lowlatency.visualizer.R
import com.lowlatency.visualizer.hue.HueLightController
import com.lowlatency.visualizer.LinkSync

class AudioSettingsController(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val hueController: HueLightController,
    private val systemAudioProvider: () -> Boolean,
    private val requestSystemAudioCapture: () -> Unit,
    private val selectMicrophone: () -> Unit
) {
    private val btnAdvanced: Button = activity.findViewById(R.id.btn_advanced)
    private val segMic: Button = activity.findViewById(R.id.seg_mic)
    private val segInternal: Button = activity.findViewById(R.id.seg_internal)
    private val internalAudioWarning: TextView = activity.findViewById(R.id.internal_audio_warning)
    private val btnHaptics: Button = activity.findViewById(R.id.btn_haptics)

    private val dialogHandler = Handler(Looper.getMainLooper())

    init {
        // Load persisted advanced settings
        HueStrobeSettings.linkBeatFlashEnabled = prefs.getBoolean(KEY_ADV_LINK_BEAT_FLASH, HueStrobeSettings.linkBeatFlashEnabled)
        HueStrobeSettings.colourSplit = prefs.getFloat(KEY_ADV_COLOUR, HueStrobeSettings.colourSplit)
        HueStrobeSettings.restingGlow = prefs.getFloat(KEY_ADV_GLOW, HueStrobeSettings.restingGlow)
        HueStrobeSettings.audioBrightness = prefs.getFloat(KEY_ADV_AUDIO_BRIGHT, HueStrobeSettings.audioBrightness)
        HueStrobeSettings.audioFlash = prefs.getFloat(KEY_ADV_AUDIO_FLASH, HueStrobeSettings.audioFlash)
        HueStrobeSettings.hueLookaheadMs = prefs.getFloat(KEY_ADV_HUE_LOOKAHEAD, HueStrobeSettings.hueLookaheadMs)

        btnAdvanced.setOnClickListener { showAdvancedDialog() }
        
        segMic.setOnClickListener { selectMicrophone() }
        segInternal.setOnClickListener { requestSystemAudioCapture() }
    }

    fun updateSourceSelection(systemAudioMode: Boolean) {
        segMic.isSelected = !systemAudioMode
        segInternal.isSelected = systemAudioMode
        internalAudioWarning.visibility = if (systemAudioMode) View.VISIBLE else View.GONE
    }

    fun updateAdvancedVisibility() {
        val relevant = hueController.isEnabled
        btnAdvanced.visibility = if (relevant) View.VISIBLE else View.GONE
    }

    private fun showAdvancedDialog() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_advanced, null)
        val linkMode = LinkSync.enabled

        view.findViewById<TextView>(R.id.adv_title_text)
            .setText(if (linkMode) R.string.adv_title_link else R.string.adv_title_audio)
        view.findViewById<TextView>(R.id.adv_hint_text)
            .setText(if (linkMode) R.string.adv_hint_link else R.string.adv_hint_audio)
        view.findViewById<View>(R.id.group_link).visibility = if (linkMode) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.group_audio).visibility = if (linkMode) View.GONE else View.VISIBLE

        val btnLinkBeatFlash = view.findViewById<Button>(R.id.btn_link_beat_flash)
        val seekColour = view.findViewById<SeekBar>(R.id.seek_colour)
        val seekGlow = view.findViewById<SeekBar>(R.id.seek_glow)
        val seekAudioBright = view.findViewById<SeekBar>(R.id.seek_audio_brightness)
        val seekAudioFlash = view.findViewById<SeekBar>(R.id.seek_audio_flash)

        val seekLookahead = view.findViewById<SeekBar>(R.id.seek_lookahead)
        val labelLookahead = view.findViewById<TextView>(R.id.label_lookahead_value)

        fun applyLinkBeatFlashLabel() {
            btnLinkBeatFlash.setText(
                if (HueStrobeSettings.linkBeatFlashEnabled) R.string.adv_link_beat_on
                else R.string.adv_link_beat_off
            )
        }

        fun applyPositions() {
            applyLinkBeatFlashLabel()
            seekColour.progress = (HueStrobeSettings.colourSplit * 100f).toInt()
            seekGlow.progress = (HueStrobeSettings.restingGlow * 100f).toInt()
            seekAudioBright.progress = (HueStrobeSettings.audioBrightness * 100f).toInt()
            seekAudioFlash.progress = (HueStrobeSettings.audioFlash * 100f).toInt()
            seekLookahead.progress = HueStrobeSettings.hueLookaheadMs.toInt()
            labelLookahead.text = if (seekLookahead.progress == 0) "Off" else "-${seekLookahead.progress} ms"
        }
        applyPositions()

        btnLinkBeatFlash.setOnClickListener {
            val enabled = !HueStrobeSettings.linkBeatFlashEnabled
            HueStrobeSettings.linkBeatFlashEnabled = enabled
            prefs.edit().putBoolean(KEY_ADV_LINK_BEAT_FLASH, enabled).apply()
            applyLinkBeatFlashLabel()
        }
        seekColour.onProgress { v -> HueStrobeSettings.colourSplit = v; prefs.edit().putFloat(KEY_ADV_COLOUR, v).apply() }
        seekGlow.onProgress { v -> HueStrobeSettings.restingGlow = v; prefs.edit().putFloat(KEY_ADV_GLOW, v).apply() }
        seekAudioBright.onProgress { v -> HueStrobeSettings.audioBrightness = v; prefs.edit().putFloat(KEY_ADV_AUDIO_BRIGHT, v).apply() }
        seekAudioFlash.onProgress { v -> HueStrobeSettings.audioFlash = v; prefs.edit().putFloat(KEY_ADV_AUDIO_FLASH, v).apply() }
        seekLookahead.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    HueStrobeSettings.hueLookaheadMs = progress.toFloat()
                    labelLookahead.text = if (progress == 0) "Off" else "-${progress} ms"
                    prefs.edit().putFloat(KEY_ADV_HUE_LOOKAHEAD, progress.toFloat()).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        val dialog = AlertDialog.Builder(activity).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<Button>(R.id.btn_adv_done).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btn_adv_reset).setOnClickListener {
            HueStrobeSettings.resetToDefaults()
            persistAdvanced()
            applyPositions()
        }

        val lightsDot = view.findViewById<View>(R.id.lights_dot)
        val meterLevel = view.findViewById<ProgressBar>(R.id.meter_level)
        val meterBass = view.findViewById<ProgressBar>(R.id.meter_bass)
        val markerLevel = view.findViewById<View>(R.id.marker_level)
        val markerBass = view.findViewById<View>(R.id.marker_bass)
        markerBass.visibility = if (linkMode) View.VISIBLE else View.INVISIBLE
        var lastLightBeat = hueController.lightBeatCount
        
        val poll = object : Runnable {
            override fun run() {
                val lc = hueController.lightBeatCount
                if (lc != lastLightBeat) { 
                    lastLightBeat = lc
                    lightsDot.animate().cancel()
                    lightsDot.alpha = 1f
                    lightsDot.scaleX = 1.35f
                    lightsDot.scaleY = 1.35f
                    lightsDot.animate().alpha(0.18f).scaleX(1f).scaleY(1f).setDuration(170L).start()
                }
                
                val full = BeatSettings.levelFull.coerceAtLeast(1e-4f)
                meterLevel.progress = (hueController.currentMicLevel / full * 100f).toInt().coerceIn(0, 100)
                meterBass.progress = (hueController.currentBassRatio * 100f).toInt().coerceIn(0, 100)
                
                val triggerFrac = (BeatSettings.levelBase / full).coerceIn(0f, 1f)
                markerLevel.translationX = triggerFrac * (meterLevel.width - markerLevel.width)
                if (linkMode) {
                    val splitMid = ((HueStrobeSettings.bassLo + HueStrobeSettings.bassHi) * 0.5f).coerceIn(0f, 1f)
                    markerBass.translationX = splitMid * (meterBass.width - markerBass.width)
                }
                dialogHandler.postDelayed(this, 50L)
            }
        }
        dialog.setOnDismissListener { dialogHandler.removeCallbacks(poll) }
        dialog.show()
        dialogHandler.post(poll)
    }

    private fun persistAdvanced() {
        prefs.edit()
            .putBoolean(KEY_ADV_LINK_BEAT_FLASH, HueStrobeSettings.linkBeatFlashEnabled)
            .putFloat(KEY_ADV_COLOUR, HueStrobeSettings.colourSplit)
            .putFloat(KEY_ADV_GLOW, HueStrobeSettings.restingGlow)
            .putFloat(KEY_ADV_AUDIO_BRIGHT, HueStrobeSettings.audioBrightness)
            .putFloat(KEY_ADV_AUDIO_FLASH, HueStrobeSettings.audioFlash)
            .putFloat(KEY_ADV_HUE_LOOKAHEAD, HueStrobeSettings.hueLookaheadMs)
            .apply()
    }

    private inline fun SeekBar.onProgress(crossinline action: (Float) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) action(p / 100f)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    fun onDestroy() {
        dialogHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val KEY_ADV_LINK_BEAT_FLASH = "adv_link_beat_flash"
        const val KEY_ADV_COLOUR = "adv_colour_split"
        const val KEY_ADV_GLOW = "adv_resting_glow"
        const val KEY_ADV_AUDIO_BRIGHT = "adv_audio_brightness"
        const val KEY_ADV_AUDIO_FLASH = "adv_audio_flash"
        const val KEY_ADV_HUE_LOOKAHEAD = "adv_hue_lookahead"
    }
}
