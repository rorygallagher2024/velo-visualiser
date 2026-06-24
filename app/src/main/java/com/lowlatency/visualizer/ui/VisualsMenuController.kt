package com.lowlatency.visualizer.ui

import android.content.SharedPreferences
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lowlatency.visualizer.BeatSettings
import com.lowlatency.visualizer.GlowSettings
import com.lowlatency.visualizer.R
import com.lowlatency.visualizer.ThemeSettings
import com.lowlatency.visualizer.VisualizerSurfaceView
import com.lowlatency.visualizer.HapticController

class VisualsMenuController(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val glView: VisualizerSurfaceView,
    private val hapticController: HapticController,
    private val getPerfOverlayEnabled: () -> Boolean,
    private val getPerfOverlayHeight: () -> Int,
    private val isMenuOpen: () -> Boolean
) {
    private val heroVisName: TextView = activity.findViewById(R.id.hero_vis_name)
    private val sceneLabel: TextView = activity.findViewById(R.id.scene_label)

    private val btnBurnin: Button = activity.findViewById(R.id.btn_burnin)
    private val btnSceneLabel: Button = activity.findViewById(R.id.btn_scene_label)
    private val btnPeakLuminance: Button = activity.findViewById(R.id.btn_peak_luminance)

    private val btnGlowOff: Button = activity.findViewById(R.id.btn_glow_off)
    private val btnGlowSubtle: Button = activity.findViewById(R.id.btn_glow_subtle)
    private val btnGlowStandard: Button = activity.findViewById(R.id.btn_glow_standard)
    private val btnGlowIntense: Button = activity.findViewById(R.id.btn_glow_intense)

    private val btnThemeSpectrum: Button = activity.findViewById(R.id.btn_theme_spectrum)
    private val btnThemeNeon: Button = activity.findViewById(R.id.btn_theme_neon)
    private val btnThemeWarm: Button = activity.findViewById(R.id.btn_theme_warm)
    private val btnThemeCool: Button = activity.findViewById(R.id.btn_theme_cool)
    private val btnThemeMono: Button = activity.findViewById(R.id.btn_theme_mono)

    private val btnSensLow: Button = activity.findViewById(R.id.btn_sens_low)
    private val btnSensStandard: Button = activity.findViewById(R.id.btn_sens_standard)
    private val btnSensHigh: Button = activity.findViewById(R.id.btn_sens_high)

    private val btnHaptics: Button = activity.findViewById(R.id.btn_haptics)

    private val favourites = mutableSetOf<Int>()
    private var visButtons = emptyList<Triple<Button, Int, String>>()
    
    private var sceneLabelEnabled = true
    private var sceneLabelRunnable: Runnable? = null

    init {
        setupVisualizerButtons()

        // Burn-in
        val burnIn = prefs.getBoolean(KEY_BURNIN, true)
        glView.burnInEnabled = burnIn
        updateBurnInButton(burnIn)
        btnBurnin.setOnClickListener {
            val enabled = !glView.burnInEnabled
            glView.burnInEnabled = enabled
            prefs.edit().putBoolean(KEY_BURNIN, enabled).apply()
            updateBurnInButton(enabled)
        }

        // Scene Label
        setSceneLabelEnabled(prefs.getBoolean(KEY_SCENE_LABEL, true))
        btnSceneLabel.setOnClickListener { setSceneLabelEnabled(!sceneLabelEnabled) }

        // Peak Luminance
        val peak = prefs.getBoolean(KEY_PEAK_LUMINANCE, false)
        updatePeakLuminance(peak)
        btnPeakLuminance.setOnClickListener {
            val enabled = !prefs.getBoolean(KEY_PEAK_LUMINANCE, false)
            prefs.edit().putBoolean(KEY_PEAK_LUMINANCE, enabled).apply()
            updatePeakLuminance(enabled)
        }

        // Glow
        GlowSettings.strength = GlowSettings.Strength.fromKey(prefs.getString(KEY_GLOW, null))
        updateGlowSelection()
        btnGlowOff.setOnClickListener { setGlow(GlowSettings.Strength.OFF) }
        btnGlowSubtle.setOnClickListener { setGlow(GlowSettings.Strength.SUBTLE) }
        btnGlowStandard.setOnClickListener { setGlow(GlowSettings.Strength.STANDARD) }
        btnGlowIntense.setOnClickListener { setGlow(GlowSettings.Strength.INTENSE) }

        // Theme
        ThemeSettings.preset = ThemeSettings.Theme.fromKey(prefs.getString(KEY_THEME, null))
        updateThemeSelection()
        btnThemeSpectrum.setOnClickListener { setTheme(ThemeSettings.Theme.SPECTRUM) }
        btnThemeNeon.setOnClickListener { setTheme(ThemeSettings.Theme.NEON) }
        btnThemeWarm.setOnClickListener { setTheme(ThemeSettings.Theme.WARM) }
        btnThemeCool.setOnClickListener { setTheme(ThemeSettings.Theme.COOL) }
        btnThemeMono.setOnClickListener { setTheme(ThemeSettings.Theme.MONO) }

        // Beat Sensitivity
        BeatSettings.preset = BeatSettings.Sensitivity.fromKey(prefs.getString(KEY_BEAT_SENS, null))
        updateBeatSensSelection()
        btnSensLow.setOnClickListener { setBeatSensitivity(BeatSettings.Sensitivity.LOW) }
        btnSensStandard.setOnClickListener { setBeatSensitivity(BeatSettings.Sensitivity.STANDARD) }
        btnSensHigh.setOnClickListener { setBeatSensitivity(BeatSettings.Sensitivity.HIGH) }

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
    }

    private fun setupVisualizerButtons() {
        visButtons = listOf(
            Triple(activity.findViewById(R.id.btn_oscilloscope), 0, "Oscilloscope"),
            Triple(activity.findViewById(R.id.btn_rawscope), 8, "Raw Scope"),
            Triple(activity.findViewById(R.id.btn_bars), 5, "Bars"),
            Triple(activity.findViewById(R.id.btn_circular), 4, "Circular"),
            Triple(activity.findViewById(R.id.btn_spectrogram), 9, "Spectrogram"),
            Triple(activity.findViewById(R.id.btn_led_matrix), 16, "LED Matrix"),
            Triple(activity.findViewById(R.id.btn_led_matrix_3d), 28, "LED Matrix 3D"),
            Triple(activity.findViewById(R.id.btn_mechanical_meter), 17, "Mechanical Meter"),

            Triple(activity.findViewById(R.id.btn_cymatics), 21, "Cymatics"),
            Triple(activity.findViewById(R.id.btn_beat_pulse), 18, "Beat Pulse"),
            Triple(activity.findViewById(R.id.btn_fireworks), 10, "Fireworks"),
            Triple(activity.findViewById(R.id.btn_starscape), 7, "Starscape"),
            Triple(activity.findViewById(R.id.btn_bloom), 6, "Bloom"),
            Triple(activity.findViewById(R.id.btn_electric_iris), 12, "Electric Iris"),
            Triple(activity.findViewById(R.id.btn_aurora_drift), 24, "Aurora Drift"),
            Triple(activity.findViewById(R.id.btn_tunnel), 1, "Tunnel"),
            Triple(activity.findViewById(R.id.btn_laser), 3, "Laser"),
            Triple(activity.findViewById(R.id.btn_phyllotaxis), 11, "Phyllotaxis"),
            Triple(activity.findViewById(R.id.btn_mandala), 13, "Mandala"),
            Triple(activity.findViewById(R.id.btn_audio_web), 14, "Audio Web"),
            Triple(activity.findViewById(R.id.btn_topo_ridge), 15, "Topo Ridge"),
            Triple(activity.findViewById(R.id.btn_strange_attractor), 22, "Strange Attractor"),
            Triple(activity.findViewById(R.id.btn_logo_particle), 26, "Logo Particle"),

            Triple(activity.findViewById(R.id.btn_fluid), 2, "Fluid"),
            Triple(activity.findViewById(R.id.btn_crystal_swarm), 27, "Crystal Swarm"),
            Triple(activity.findViewById(R.id.btn_mandelbox), 19, "Mandelbox"),
            Triple(activity.findViewById(R.id.btn_reaction_diffusion), 20, "Reaction Diffusion"),
            Triple(activity.findViewById(R.id.btn_plasma_storm), 23, "Plasma Storm"),
            Triple(activity.findViewById(R.id.btn_odyssey), 25, "Odyssey")
        )

        glView.sceneOrder = visButtons.map { it.second }
        glView.onSceneChanged = {
            updateVisualizerSelection()
            if (!isMenuOpen()) showSceneLabel()
        }

        prefs.getStringSet(KEY_FAVOURITES, emptySet())?.forEach {
            it.toIntOrNull()?.let { idx -> favourites.add(idx) }
        }
        updateFavouritesOrder()

        visButtons.forEach { (b, idx, _) ->
            b.setOnClickListener { glView.selectScene(idx); updateVisualizerSelection() }
            b.setOnLongClickListener { toggleFavourite(idx); true }
        }
    }

    fun updateVisualizerSelection() {
        val current = glView.sceneIndex
        for ((b, idx, base) in visButtons) {
            b.isSelected = idx == current
            b.text = if (favourites.contains(idx)) "★ $base" else base
            if (idx == current) heroVisName.text = base
        }
    }

    private fun toggleFavourite(index: Int) {
        if (!favourites.add(index)) favourites.remove(index)
        prefs.edit().putStringSet(KEY_FAVOURITES, favourites.map { it.toString() }.toSet()).apply()
        updateFavouritesOrder()
        updateVisualizerSelection()
        val fav = favourites.contains(index)
        Toast.makeText(
            activity,
            if (fav) "Added to swipe favourites" else "Removed from favourites",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun updateFavouritesOrder() {
        val order = glView.sceneOrder
        glView.favourites = favourites.toList().sortedBy { order.indexOf(it) }
    }

    private fun setSceneLabelEnabled(enabled: Boolean) {
        sceneLabelEnabled = enabled
        prefs.edit().putBoolean(KEY_SCENE_LABEL, enabled).apply()
        btnSceneLabel.isSelected = enabled
        btnSceneLabel.setText(if (enabled) R.string.scene_label_on else R.string.scene_label_off)
        if (!enabled) {
            sceneLabelRunnable?.let { sceneLabel.removeCallbacks(it) }
            sceneLabel.animate().cancel()
            sceneLabel.visibility = View.GONE
        }
    }

    private fun showSceneLabel() {
        if (!sceneLabelEnabled) return
        val name = heroVisName.text
        if (name.isNullOrBlank()) return
        sceneLabel.text = name
        
        val d = activity.resources.displayMetrics.density
        val lp = sceneLabel.layoutParams as android.view.ViewGroup.MarginLayoutParams
        lp.topMargin = if (getPerfOverlayEnabled() && getPerfOverlayHeight() > 0)
            getPerfOverlayHeight() + (d * 12).toInt()
        else
            (d * 72).toInt()
        sceneLabel.layoutParams = lp
        
        sceneLabelRunnable?.let { sceneLabel.removeCallbacks(it) }
        sceneLabel.animate().cancel()
        sceneLabel.visibility = View.VISIBLE
        sceneLabel.alpha = 0f
        sceneLabel.animate().alpha(1f).setDuration(180L).start()
        val hide = Runnable {
            sceneLabel.animate().alpha(0f).setDuration(450L)
                .withEndAction { sceneLabel.visibility = View.GONE }.start()
        }
        sceneLabelRunnable = hide
        sceneLabel.postDelayed(hide, 1100L)
    }

    private fun updateBurnInButton(enabled: Boolean) {
        btnBurnin.isSelected = enabled
        btnBurnin.setText(if (enabled) R.string.burnin_on else R.string.burnin_off)
    }

    private fun updatePeakLuminance(enabled: Boolean) {
        val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
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

    private fun updateHapticsButton(enabled: Boolean) {
        btnHaptics.isSelected = enabled
        btnHaptics.setText(if (enabled) R.string.haptics_on else R.string.haptics_off)
    }

    fun onDestroy() {
        sceneLabelRunnable?.let { sceneLabel.removeCallbacks(it) }
    }

    companion object {
        const val KEY_BURNIN = "burn_in_enabled"
        const val KEY_SCENE_LABEL = "scene_label_enabled"
        const val KEY_PEAK_LUMINANCE = "peak_luminance_enabled"
        const val KEY_GLOW = "glow_strength"
        const val KEY_THEME = "color_theme"
        const val KEY_BEAT_SENS = "beat_sensitivity"
        const val KEY_HAPTICS = "haptics_enabled"
        const val KEY_FAVOURITES = "favourite_scenes"
    }
}
