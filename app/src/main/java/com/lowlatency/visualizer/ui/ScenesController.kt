package com.lowlatency.visualizer.ui

import android.content.SharedPreferences
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.lowlatency.visualizer.R
import com.lowlatency.visualizer.SecondaryVisualizerActivity
import com.lowlatency.visualizer.VisualizerSurfaceView

/**
 * Owns the Visuals tab: the scene [wheel] (which fills the tab) and the transient
 * on-canvas scene label. Scene identity (order, names, category) comes from
 * [SceneCatalog].
 *
 * The wheel is the whole interaction: **scrub** to browse, **tap** a visual to
 * select it and dismiss the menu, **hold** a visual to toggle it as a favourite.
 * Favourites aren't a pinned strip — they're the *swipe set* the canvas
 * left/right swipe cycles through, marked with a ★ on their wheel row (swipe
 * falls back to all scenes when none are starred).
 */
class ScenesController(
    private val activity: AppCompatActivity,
    private val glView: VisualizerSurfaceView,
    private val prefs: SharedPreferences,
    private val isMenuOpen: () -> Boolean,
    private val perfOverlayBottom: () -> Int,
    private val onManualSceneChange: () -> Unit,
    private val onScrubPreview: (Boolean) -> Unit = {},
    private val onCloseMenu: () -> Unit = {},
) {
    private val entries = SceneCatalog.ENTRIES

    private lateinit var wheel: SceneWheelView
    private lateinit var counter: TextView
    private lateinit var btnSceneLabel: Button
    private lateinit var sceneLabel: TextView

    private val favourites = linkedSetOf<Int>()

    private var sceneLabelRunnable: Runnable? = null
    private var sceneLabelEnabled = true
    private var activeSceneName = ""

    fun bind() {
        wheel = activity.findViewById(R.id.scene_wheel)
        counter = activity.findViewById(R.id.scene_counter)
        btnSceneLabel = activity.findViewById(R.id.btn_scene_label)
        sceneLabel = activity.findViewById(R.id.scene_label)

        glView.sceneOrder = entries.map { it.index }

        prefs.getStringSet(KEY_FAVOURITES, emptySet())?.forEach {
            it.toIntOrNull()?.let { idx -> favourites.add(idx) }
        }
        updateFavouritesOrder()
        activeSceneName = nameOf(glView.sceneIndex)

        wheel.favourites = favourites.toSet()
        wheel.setScenes(entries.map { SceneWheelView.Item(it.index, nameOf(it.index)) }, glView.sceneIndex)
        wheel.onSelect = { glView.selectScene(it) }
        wheel.onCentre = { updateCounter(it) }
        wheel.onScrubbingChange = { setScrubPreview(it) }
        wheel.onPick = { onCloseMenu() }
        wheel.onFavourite = { toggleFavourite(it) }

        glView.onSceneChanged = { sceneIndex -> onSceneChanged(sceneIndex) }

        updateCounter(glView.sceneIndex)

        setSceneLabelEnabled(prefs.getBoolean(KEY_SCENE_LABEL, true))
        btnSceneLabel.setOnClickListener { setSceneLabelEnabled(!sceneLabelEnabled) }
    }

    private fun onSceneChanged(sceneIndex: Int) {
        activeSceneName = nameOf(sceneIndex)
        wheel.centerOn(sceneIndex)          // follow external changes (swipe, shuffle)
        updateCounter(sceneIndex)
        if (!isMenuOpen()) showSceneLabel()
        onManualSceneChange()
        prefs.edit { putInt(SecondaryVisualizerActivity.KEY_ACTIVE_SCENE, sceneIndex) }
    }

    /** The active scene's display name (e.g. for the perf overlay's Scene row). */
    fun currentSceneName(): String = nameOf(glView.sceneIndex)

    /** Ordered (sceneIndex, displayName) pairs. */
    fun sceneList(): List<Pair<Int, String>> = entries.map { it.index to nameOf(it.index) }

    fun updateSelection() {
        wheel.centerOn(glView.sceneIndex)
        updateCounter(glView.sceneIndex)
    }

    /** Fade the counter (and the sheet chrome via [onScrubPreview]) away while
     *  scrubbing so the live visual previews clearly behind the wheel. */
    private fun setScrubPreview(active: Boolean) {
        counter.animate().alpha(if (active) 0f else 1f).setDuration(PREVIEW_FADE_MS).start()
        onScrubPreview(active)
    }

    private fun nameOf(index: Int): String =
        entries.firstOrNull { it.index == index }?.let { activity.getString(it.nameRes) } ?: ""

    private fun updateCounter(index: Int) {
        val e = entries.firstOrNull { it.index == index } ?: return
        counter.text = "%s · %02d / %02d".format(e.category.label.uppercase(), entries.indexOf(e) + 1, entries.size)
    }

    private fun toggleFavourite(index: Int) {
        if (!favourites.add(index)) favourites.remove(index)
        prefs.edit { putStringSet(KEY_FAVOURITES, favourites.map { it.toString() }.toSet()) }
        updateFavouritesOrder()
        wheel.favourites = favourites.toSet()
        Toast.makeText(
            activity,
            if (favourites.contains(index)) R.string.fav_added_toast else R.string.fav_removed_toast,
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
        val name = activeSceneName
        if (name.isBlank()) return
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

    companion object {
        private const val KEY_FAVOURITES = "favourite_scenes"
        private const val KEY_SCENE_LABEL = "scene_label_enabled"
        private const val PREVIEW_FADE_MS = 200L
    }
}
