package com.lowlatency.visualizer.ui

import android.content.SharedPreferences
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lowlatency.visualizer.R
import com.lowlatency.visualizer.SecondaryVisualizerActivity
import com.lowlatency.visualizer.VisualizerSurfaceView
import androidx.core.content.edit

class ScenesController(
    private val activity: AppCompatActivity,
    private val glView: VisualizerSurfaceView,
    private val prefs: SharedPreferences,
    private val isMenuOpen: () -> Boolean,
    private val perfOverlayBottom: () -> Int,
    private val onManualSceneChange: () -> Unit,
) {
    private lateinit var heroVisName: TextView
    private lateinit var btnSceneLabel: Button
    private lateinit var sceneLabel: TextView
    private var sceneLabelRunnable: Runnable? = null
    private var sceneLabelEnabled = true

    private lateinit var visButtons: List<Triple<Button, Int, String>>
    private val favourites = linkedSetOf<Int>()

    fun bind() {
        heroVisName = activity.findViewById(R.id.hero_vis_name)
        btnSceneLabel = activity.findViewById(R.id.btn_scene_label)
        sceneLabel = activity.findViewById(R.id.scene_label)

        visButtons = listOf(
            // Instruments
            btn(R.id.btn_oscilloscope, 0),
            btn(R.id.btn_rawscope, 8),
            btn(R.id.btn_bars, 5),
            btn(R.id.btn_circular, 4),
            btn(R.id.btn_spectrogram, 9),
            btn(R.id.btn_led_matrix, 16),
            btn(R.id.btn_led_matrix_3d, 28),
            btn(R.id.btn_mechanical_meter, 17),
            btn(R.id.btn_phase_scope, 33),
            // Reactive
            btn(R.id.btn_logo_particle, 26),
            btn(R.id.btn_spectral_canyon, 30),
            btn(R.id.btn_spectral_canyon_classic, 31),
            btn(R.id.btn_starscape, 7),
            btn(R.id.btn_bloom, 6),
            btn(R.id.btn_electric_iris, 12),
            btn(R.id.btn_tunnel, 1),
            btn(R.id.btn_laser, 3),
            btn(R.id.btn_phyllotaxis, 11),
            btn(R.id.btn_mandala, 13),
            btn(R.id.btn_audio_web, 14),
            btn(R.id.btn_topo_ridge, 15),
            btn(R.id.btn_strange_attractor, 22),
            btn(R.id.btn_waveform_waterfall, 32),
            btn(R.id.btn_cymatics, 21),
            btn(R.id.btn_beat_pulse, 18),
            btn(R.id.btn_fireworks, 10),
            // Immersive
            btn(R.id.btn_fluid, 2),
            btn(R.id.btn_crystal_swarm, 27),
            btn(R.id.btn_mandelbox, 19),
            btn(R.id.btn_reaction_diffusion, 20),
            btn(R.id.btn_plasma_storm, 23),
            btn(R.id.btn_odyssey, 25),
            btn(R.id.btn_liquid_light, 29),
            btn(R.id.btn_aurora_drift, 24),
        )
        glView.sceneOrder = visButtons.map { it.second }

        glView.onSceneChanged = { sceneIndex ->
            updateSelection()
            if (!isMenuOpen()) showSceneLabel()
            onManualSceneChange()
            prefs.edit { putInt(SecondaryVisualizerActivity.KEY_ACTIVE_SCENE, sceneIndex) }
        }

        prefs.getStringSet(KEY_FAVOURITES, emptySet())?.forEach {
            it.toIntOrNull()?.let { idx -> favourites.add(idx) }
        }
        updateFavouritesOrder()

        visButtons.forEach { (b, idx, _) ->
            b.setOnClickListener { glView.selectScene(idx); updateSelection() }
            b.setOnLongClickListener { toggleFavourite(idx); true }
        }
        updateSelection()

        setSceneLabelEnabled(prefs.getBoolean(KEY_SCENE_LABEL, true))
        btnSceneLabel.setOnClickListener { setSceneLabelEnabled(!sceneLabelEnabled) }
    }

    fun updateSelection() {
        val current = glView.sceneIndex
        for ((b, idx, base) in visButtons) {
            b.isSelected = idx == current
            b.text = if (favourites.contains(idx)) "★ $base" else base
            if (idx == current) heroVisName.text = base
        }
    }

    private fun toggleFavourite(index: Int) {
        if (!favourites.add(index)) favourites.remove(index)
        prefs.edit { putStringSet(KEY_FAVOURITES, favourites.map { it.toString() }.toSet()) }
        updateFavouritesOrder()
        updateSelection()
        Toast.makeText(
            activity,
            if (favourites.contains(index)) "Added to swipe favourites" else "Removed from favourites",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun updateFavouritesOrder() {
        val order = glView.sceneOrder
        glView.favourites = favourites.toList().sortedBy { order.indexOf(it) }
    }

    private fun setSceneLabelEnabled(enabled: Boolean) {
        sceneLabelEnabled = enabled
        prefs.edit { putBoolean(KEY_SCENE_LABEL, enabled) }
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
        val overlayBottom = perfOverlayBottom()
        lp.topMargin = if (overlayBottom > 0)
            overlayBottom + (d * 12).toInt()
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

    private fun btn(id: Int, sceneIndex: Int): Triple<Button, Int, String> {
        val b = activity.findViewById<Button>(id)
        return Triple(b, sceneIndex, b.text.toString())
    }

    companion object {
        private const val KEY_FAVOURITES = "favourite_scenes"
        private const val KEY_SCENE_LABEL = "scene_label_enabled"
    }
}
