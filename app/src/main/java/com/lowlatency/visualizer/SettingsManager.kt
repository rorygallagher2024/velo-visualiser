package com.lowlatency.visualizer

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat

class SettingsManager(private val activity: MainActivity) {

    private val prefs: SharedPreferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    
    // UI elements managed by SettingsManager
    private lateinit var btnBurnin: Button
    private lateinit var btnPerfOverlay: Button
    private lateinit var btnPeakLuminance: Button
    private lateinit var btnGlowOff: Button
    private lateinit var btnGlowSubtle: Button
    private lateinit var btnGlowStandard: Button
    private lateinit var btnGlowIntense: Button
    private lateinit var btnHaptics: Button
    private lateinit var btnSensLow: Button
    private lateinit var btnSensStandard: Button
    private lateinit var btnSensHigh: Button
    private lateinit var btnThemeSpectrum: Button
    private lateinit var btnThemeNeon: Button
    private lateinit var btnThemeWarm: Button
    private lateinit var btnThemeCool: Button
    private lateinit var btnThemeMono: Button
    private lateinit var btnAdvanced: Button
    private lateinit var btnPrivacyPolicy: Button
    private lateinit var btnAbout: Button
    private lateinit var perfOverlay: TextView

    var perfOverlayEnabled = false
        private set

    fun bindViews(root: View) {
        btnBurnin = root.findViewById(R.id.btn_burnin)
        btnPerfOverlay = root.findViewById(R.id.btn_perf_overlay)
        btnPeakLuminance = root.findViewById(R.id.btn_peak_luminance)
        btnGlowOff = root.findViewById(R.id.btn_glow_off)
        btnGlowSubtle = root.findViewById(R.id.btn_glow_subtle)
        btnGlowStandard = root.findViewById(R.id.btn_glow_standard)
        btnGlowIntense = root.findViewById(R.id.btn_glow_intense)
        btnHaptics = root.findViewById(R.id.btn_haptics)
        btnSensLow = root.findViewById(R.id.btn_sens_low)
        btnSensStandard = root.findViewById(R.id.btn_sens_standard)
        btnSensHigh = root.findViewById(R.id.btn_sens_high)
        btnThemeSpectrum = root.findViewById(R.id.btn_theme_spectrum)
        btnThemeNeon = root.findViewById(R.id.btn_theme_neon)
        btnThemeWarm = root.findViewById(R.id.btn_theme_warm)
        btnThemeCool = root.findViewById(R.id.btn_theme_cool)
        btnThemeMono = root.findViewById(R.id.btn_theme_mono)
        btnAdvanced = root.findViewById(R.id.btn_advanced)
        btnPrivacyPolicy = root.findViewById(R.id.btn_privacy_policy)
        btnAbout = root.findViewById(R.id.btn_about)
        perfOverlay = root.findViewById(R.id.perf_overlay)
    }

    fun setup(glView: VisualizerSurfaceView, hapticController: HapticController) {
        // Burn-in protection toggle (persisted, default on).
        val burnIn = prefs.getBoolean(KEY_BURNIN, true)
        glView.burnInEnabled = burnIn
        updateBurnInButton(burnIn)
        btnBurnin.setOnClickListener {
            val enabled = !glView.burnInEnabled
            glView.burnInEnabled = enabled
            prefs.edit().putBoolean(KEY_BURNIN, enabled).apply()
            updateBurnInButton(enabled)
        }

        // Performance overlay toggle (persisted, default off).
        setPerfOverlay(prefs.getBoolean(KEY_PERF_OVERLAY, false))
        btnPerfOverlay.setOnClickListener { setPerfOverlay(!perfOverlayEnabled) }

        // Peak luminance (HDR+) toggle (persisted, default off).
        val peak = prefs.getBoolean(KEY_PEAK_LUMINANCE, false)
        updatePeakLuminance(peak)
        btnPeakLuminance.setOnClickListener {
            val enabled = !prefs.getBoolean(KEY_PEAK_LUMINANCE, false)
            prefs.edit().putBoolean(KEY_PEAK_LUMINANCE, enabled).apply()
            updatePeakLuminance(enabled)
        }

        // HDR bloom / glow strength (persisted).
        GlowSettings.strength = GlowSettings.Strength.fromKey(prefs.getString(KEY_GLOW, null))
        updateGlowSelection()
        btnGlowOff.setOnClickListener { setGlow(GlowSettings.Strength.OFF) }
        btnGlowSubtle.setOnClickListener { setGlow(GlowSettings.Strength.SUBTLE) }
        btnGlowStandard.setOnClickListener { setGlow(GlowSettings.Strength.STANDARD) }
        btnGlowIntense.setOnClickListener { setGlow(GlowSettings.Strength.INTENSE) }

        // Colour theme (persisted, applied as a global grade in the composite).
        ThemeSettings.preset = ThemeSettings.Theme.fromKey(prefs.getString(KEY_THEME, null))
        updateThemeSelection()
        btnThemeSpectrum.setOnClickListener { setTheme(ThemeSettings.Theme.SPECTRUM) }
        btnThemeNeon.setOnClickListener { setTheme(ThemeSettings.Theme.NEON) }
        btnThemeWarm.setOnClickListener { setTheme(ThemeSettings.Theme.WARM) }
        btnThemeCool.setOnClickListener { setTheme(ThemeSettings.Theme.COOL) }
        btnThemeMono.setOnClickListener { setTheme(ThemeSettings.Theme.MONO) }

        // Haptics
        if (hapticController.isSupported) {
            val haptics = prefs.getBoolean(KEY_HAPTICS, false)
            hapticController.enabled = haptics
            updateHapticsButton(haptics)
            btnHaptics.setOnClickListener {
                val enabled = !hapticController.enabled
                hapticController.enabled = enabled
                prefs.edit().putBoolean(KEY_HAPTICS, enabled).apply()
                updateHapticsButton(enabled)
                if (enabled) hapticController.previewPulse()
            }
        } else {
            btnHaptics.isEnabled = false
            btnHaptics.alpha = 0.4f
            updateHapticsButton(false)
        }

        // Beat sensitivity presets (persisted).
        BeatSettings.preset = BeatSettings.Sensitivity.fromKey(prefs.getString(KEY_BEAT_SENS, null))
        updateBeatSensSelection()
        btnSensLow.setOnClickListener { setBeatSensitivity(BeatSettings.Sensitivity.LOW) }
        btnSensStandard.setOnClickListener { setBeatSensitivity(BeatSettings.Sensitivity.STANDARD) }
        btnSensHigh.setOnClickListener { setBeatSensitivity(BeatSettings.Sensitivity.HIGH) }

        // Advanced light-sync tuning (persisted).
        HueStrobeSettings.linkBeatFlashEnabled = prefs.getBoolean(KEY_ADV_LINK_BEAT_FLASH, HueStrobeSettings.linkBeatFlashEnabled)
        HueStrobeSettings.colourSplit = prefs.getFloat(KEY_ADV_COLOUR, HueStrobeSettings.colourSplit)
        HueStrobeSettings.restingGlow = prefs.getFloat(KEY_ADV_GLOW, HueStrobeSettings.restingGlow)
        HueStrobeSettings.audioBrightness = prefs.getFloat(KEY_ADV_AUDIO_BRIGHT, HueStrobeSettings.audioBrightness)
        HueStrobeSettings.audioFlash = prefs.getFloat(KEY_ADV_AUDIO_FLASH, HueStrobeSettings.audioFlash)
        HueStrobeSettings.hueLookaheadMs = prefs.getFloat(KEY_ADV_HUE_LOOKAHEAD, HueStrobeSettings.hueLookaheadMs)
        btnAdvanced.setOnClickListener { showAdvancedDialog() }

        btnPrivacyPolicy.setOnClickListener { showPrivacyPolicy() }
        btnAbout.setOnClickListener { showAboutDialog() }
    }

    private fun updateBurnInButton(enabled: Boolean) {
        btnBurnin.isSelected = enabled
        btnBurnin.setText(if (enabled) R.string.burnin_on else R.string.burnin_off)
    }

    fun setPerfOverlay(enabled: Boolean) {
        perfOverlayEnabled = enabled
        prefs.edit().putBoolean(KEY_PERF_OVERLAY, enabled).apply()
        btnPerfOverlay.isSelected = enabled
        btnPerfOverlay.setText(if (enabled) R.string.perf_overlay_on else R.string.perf_overlay_off)
        if (enabled) {
            perfOverlay.visibility = View.VISIBLE
            activity.startPerfPoller()
        } else {
            perfOverlay.visibility = View.GONE
            activity.stopPerfPoller()
        }
    }

    fun updatePerfOverlay(text: CharSequence) {
        perfOverlay.text = text
    }

    fun updatePeakLuminance(enabled: Boolean) {
        val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else activity.windowManager.defaultDisplay
        val isHdr = d?.isHdr == true

        if (isHdr) {
            btnPeakLuminance.setText(if (enabled) R.string.peak_luminance_on else R.string.peak_luminance_off)
            activity.findViewById<TextView>(R.id.peak_luminance_hint_text).setText(R.string.peak_luminance_hint)
        } else {
            btnPeakLuminance.setText(if (enabled) R.string.max_brightness_on else R.string.max_brightness_off)
            activity.findViewById<TextView>(R.id.peak_luminance_hint_text).setText(R.string.max_brightness_hint)
        }
        btnPeakLuminance.isSelected = enabled

        val lp = activity.window.attributes
        lp.screenBrightness = if (enabled) 1.0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = lp

        if (Build.VERSION.SDK_INT >= 35) {
            activity.window.setDesiredHdrHeadroom(if (enabled) 10.0f else 1.0f)
        }
    }

    private fun setGlow(s: GlowSettings.Strength) {
        GlowSettings.strength = s
        prefs.edit().putString(KEY_GLOW, s.key).apply()
        updateGlowSelection()
    }

    private fun updateGlowSelection() {
        val s = GlowSettings.strength
        btnGlowOff.isSelected = s == GlowSettings.Strength.OFF
        btnGlowSubtle.isSelected = s == GlowSettings.Strength.SUBTLE
        btnGlowStandard.isSelected = s == GlowSettings.Strength.STANDARD
        btnGlowIntense.isSelected = s == GlowSettings.Strength.INTENSE
    }

    private fun setTheme(t: ThemeSettings.Theme) {
        ThemeSettings.preset = t
        prefs.edit().putString(KEY_THEME, t.key).apply()
        updateThemeSelection()
    }

    private fun updateThemeSelection() {
        val t = ThemeSettings.preset
        btnThemeSpectrum.isSelected = t == ThemeSettings.Theme.SPECTRUM
        btnThemeNeon.isSelected = t == ThemeSettings.Theme.NEON
        btnThemeWarm.isSelected = t == ThemeSettings.Theme.WARM
        btnThemeCool.isSelected = t == ThemeSettings.Theme.COOL
        btnThemeMono.isSelected = t == ThemeSettings.Theme.MONO
    }

    private fun updateHapticsButton(enabled: Boolean) {
        btnHaptics.isSelected = enabled
        btnHaptics.setText(if (enabled) R.string.haptics_on else R.string.haptics_off)
    }

    private fun setBeatSensitivity(s: BeatSettings.Sensitivity) {
        BeatSettings.preset = s
        prefs.edit().putString(KEY_BEAT_SENS, s.key).apply()
        updateBeatSensSelection()
    }

    private fun updateBeatSensSelection() {
        btnSensLow.isSelected = BeatSettings.preset == BeatSettings.Sensitivity.LOW
        btnSensStandard.isSelected = BeatSettings.preset == BeatSettings.Sensitivity.STANDARD
        btnSensHigh.isSelected = BeatSettings.preset == BeatSettings.Sensitivity.HIGH
    }

    private fun showAdvancedDialog() {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_advanced, null)
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

        // Live diagnostics poll.
        val hueController = activity.hueManager.hueController
        val lightsDot = view.findViewById<View>(R.id.lights_dot)
        val meterLevel = view.findViewById<ProgressBar>(R.id.meter_level)
        val meterBass = view.findViewById<ProgressBar>(R.id.meter_bass)
        val markerLevel = view.findViewById<View>(R.id.marker_level)
        val markerBass = view.findViewById<View>(R.id.marker_bass)
        var lastLightBeat = hueController.lightBeatCount
        val poll = object : Runnable {
            override fun run() {
                val lc = hueController.lightBeatCount
                if (lc != lastLightBeat) { lastLightBeat = lc; flashDot(lightsDot) }
                if (linkMode) {
                    val full = BeatSettings.levelFull.coerceAtLeast(1e-4f)
                    meterLevel.progress =
                        (hueController.currentMicLevel / full * 100f).toInt().coerceIn(0, 100)
                    meterBass.progress = (hueController.currentBassRatio * 100f).toInt().coerceIn(0, 100)
                    val triggerFrac = (BeatSettings.levelBase / full).coerceIn(0f, 1f)
                    markerLevel.translationX = triggerFrac * (meterLevel.width - markerLevel.width)
                    val splitMid = ((HueStrobeSettings.bassLo + HueStrobeSettings.bassHi) * 0.5f).coerceIn(0f, 1f)
                    markerBass.translationX = splitMid * (meterBass.width - markerBass.width)
                }
                activity.linkHandler.postDelayed(this, 50L)
            }
        }
        dialog.setOnDismissListener { activity.linkHandler.removeCallbacks(poll) }
        dialog.show()
        activity.linkHandler.post(poll)
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

    private fun flashDot(dot: View) {
        dot.animate().cancel()
        dot.alpha = 1f
        dot.scaleX = 1.35f
        dot.scaleY = 1.35f
        dot.animate().alpha(0.18f).scaleX(1f).scaleY(1f).setDuration(170L).start()
    }

    fun updateAdvancedVisibility(relevant: Boolean) {
        btnAdvanced.visibility = if (relevant) View.VISIBLE else View.GONE
    }

    private fun appVersionName(): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun showPrivacyPolicy() {
        val dialog = Dialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_privacy, null)
        val message = HtmlCompat.fromHtml(activity.getString(R.string.privacy_policy_text), HtmlCompat.FROM_HTML_MODE_LEGACY)
        view.findViewById<TextView>(R.id.privacy_text).text = message

        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialog.show()
        configureDialogWindow(dialog)

        view.findViewById<Button>(R.id.btn_privacy_ok).setOnClickListener { dialog.dismiss() }
    }

    private fun showAboutDialog() {
        val dialog = Dialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_about, null)

        view.findViewById<TextView>(R.id.about_version).text = activity.getString(R.string.version_fmt, appVersionName())

        val rate = NativeBridge.nativeGetSampleRate()
        val engine = if (activity.audioManager.systemAudioMode) {
            "AudioPlaybackCapture • $rate Hz"
        } else {
            "Oboe Engine: AAudio Active • $rate Hz"
        }
        view.findViewById<TextView>(R.id.about_engine_status).text = engine

        val licenses = HtmlCompat.fromHtml(activity.getString(R.string.about_licenses_text), HtmlCompat.FROM_HTML_MODE_LEGACY)
        view.findViewById<TextView>(R.id.about_licenses).text = licenses

        val trademarks = HtmlCompat.fromHtml(activity.getString(R.string.about_trademarks_text), HtmlCompat.FROM_HTML_MODE_LEGACY)
        view.findViewById<TextView>(R.id.about_trademarks).text = trademarks

        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialog.show()
        configureDialogWindow(dialog)

        view.findViewById<Button>(R.id.btn_about_ok).setOnClickListener { dialog.dismiss() }
    }

    private fun configureDialogWindow(dialog: Dialog) {
        val metrics = activity.resources.displayMetrics
        val w = (metrics.widthPixels * 0.9).toInt()
        val h = (metrics.heightPixels * 0.9).toInt()

        dialog.window?.let { window ->
            val params = window.attributes
            params.width = w
            window.attributes = params

            window.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)

            window.decorView.post {
                if (window.decorView.height > h) {
                    window.setLayout(w, h)
                }
            }
        }
    }

    companion object {
        const val PREFS = "visualizer_prefs"
        const val KEY_BURNIN = "burn_in_enabled"
        const val KEY_GLOW = "glow_strength"
        const val KEY_HAPTICS = "haptics_enabled"
        const val KEY_BEAT_SENS = "beat_sensitivity"
        const val KEY_THEME = "color_theme"
        const val KEY_ADV_LINK_BEAT_FLASH = "adv_link_beat_flash"
        const val KEY_ADV_COLOUR = "adv_colour_split"
        const val KEY_ADV_GLOW = "adv_resting_glow"
        const val KEY_ADV_AUDIO_BRIGHT = "adv_audio_brightness"
        const val KEY_ADV_AUDIO_FLASH = "adv_audio_flash"
        const val KEY_ADV_HUE_LOOKAHEAD = "adv_hue_lookahead"
        const val KEY_PERF_OVERLAY = "perf_overlay_enabled"
        const val KEY_PEAK_LUMINANCE = "peak_luminance_enabled"
    }
}
