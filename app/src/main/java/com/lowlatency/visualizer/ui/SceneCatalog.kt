package com.lowlatency.visualizer.ui

import com.lowlatency.visualizer.R

/** Display categories, in wheel order. [label] is shown on the scene counter. */
enum class SceneCategory(val label: String) {
    INSTRUMENTS("Instruments"),
    REACTIVE("Reactive"),
    IMMERSIVE("Immersive"),
}

/** One scene: its renderer [index], display-name resource, and category. */
data class SceneEntry(val index: Int, val nameRes: Int, val category: SceneCategory, val requiresStereoAudio: Boolean = false)

/**
 * The fixed catalogue of scenes — the single source of truth for the Visuals
 * wheel, the favourites strip and the canvas swipe order. List order is the
 * display / swipe order (grouped by category); [SceneEntry.index] matches
 * `VisualizerRenderer.createScene`. Adding a scene means adding one line here
 * (plus the renderer arm) — no layout button required anymore.
 */
object SceneCatalog {
    val ENTRIES: List<SceneEntry> = listOf(
        // ----- Instruments (clean, lightweight) -----
        SceneEntry(0, R.string.vis_oscilloscope, SceneCategory.INSTRUMENTS),
        SceneEntry(8, R.string.vis_rawscope, SceneCategory.INSTRUMENTS),
        SceneEntry(5, R.string.vis_bars, SceneCategory.INSTRUMENTS),
        SceneEntry(36, R.string.vis_obsidian, SceneCategory.INSTRUMENTS),
        SceneEntry(4, R.string.vis_circular, SceneCategory.INSTRUMENTS),
        SceneEntry(9, R.string.vis_spectrogram, SceneCategory.INSTRUMENTS),
        SceneEntry(44, R.string.vis_waveform, SceneCategory.INSTRUMENTS),
        SceneEntry(16, R.string.vis_led_matrix, SceneCategory.INSTRUMENTS),
        SceneEntry(28, R.string.vis_led_matrix_3d, SceneCategory.INSTRUMENTS),
        SceneEntry(17, R.string.vis_mechanical_meter, SceneCategory.INSTRUMENTS),
        SceneEntry(33, R.string.vis_phase_scope, SceneCategory.INSTRUMENTS),
        SceneEntry(41, R.string.vis_lissajous_scope, SceneCategory.INSTRUMENTS, requiresStereoAudio = true),
        SceneEntry(43, R.string.vis_crt_scope, SceneCategory.INSTRUMENTS, requiresStereoAudio = true),
        // ----- Reactive (beat / energy driven) -----
        SceneEntry(38, R.string.vis_veil, SceneCategory.REACTIVE),
        SceneEntry(40, R.string.vis_veil_topdown, SceneCategory.REACTIVE),
        SceneEntry(34, R.string.vis_nebula, SceneCategory.REACTIVE),
        SceneEntry(26, R.string.vis_logo_particle, SceneCategory.REACTIVE),
        SceneEntry(30, R.string.vis_spectral_canyon, SceneCategory.REACTIVE),
        SceneEntry(31, R.string.vis_spectral_canyon_classic, SceneCategory.REACTIVE),
        SceneEntry(7, R.string.vis_starscape, SceneCategory.REACTIVE),
        SceneEntry(12, R.string.vis_electric_iris, SceneCategory.REACTIVE),
        SceneEntry(1, R.string.vis_tunnel, SceneCategory.REACTIVE),
        SceneEntry(3, R.string.vis_laser, SceneCategory.REACTIVE),
        SceneEntry(11, R.string.vis_phyllotaxis, SceneCategory.REACTIVE),
        SceneEntry(14, R.string.vis_audio_web, SceneCategory.REACTIVE),
        SceneEntry(15, R.string.vis_topo_ridge, SceneCategory.REACTIVE),
        SceneEntry(22, R.string.vis_strange_attractor, SceneCategory.REACTIVE),
        SceneEntry(32, R.string.vis_waveform_waterfall, SceneCategory.REACTIVE),
        SceneEntry(21, R.string.vis_cymatics, SceneCategory.REACTIVE),
        SceneEntry(18, R.string.vis_beat_pulse, SceneCategory.REACTIVE),
        SceneEntry(10, R.string.vis_fireworks, SceneCategory.REACTIVE),
        SceneEntry(42, R.string.vis_chromatic_dots, SceneCategory.REACTIVE),
        // ----- Immersive (rich generative, heavier GPU) -----
        SceneEntry(6, R.string.vis_bloom, SceneCategory.IMMERSIVE),
        SceneEntry(13, R.string.vis_mandala, SceneCategory.IMMERSIVE),
        SceneEntry(39, R.string.vis_meridian, SceneCategory.IMMERSIVE),
        SceneEntry(37, R.string.vis_slipstream, SceneCategory.IMMERSIVE),
        SceneEntry(2, R.string.vis_fluid, SceneCategory.IMMERSIVE),
        SceneEntry(27, R.string.vis_crystal_swarm, SceneCategory.IMMERSIVE),
        SceneEntry(19, R.string.vis_mandelbox, SceneCategory.IMMERSIVE),
        SceneEntry(20, R.string.vis_reaction_diffusion, SceneCategory.IMMERSIVE),
        SceneEntry(23, R.string.vis_plasma_storm, SceneCategory.IMMERSIVE),
        SceneEntry(25, R.string.vis_odyssey, SceneCategory.IMMERSIVE),
        SceneEntry(29, R.string.vis_liquid_light, SceneCategory.IMMERSIVE),
        SceneEntry(24, R.string.vis_aurora_drift, SceneCategory.IMMERSIVE),
    )
}
