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
import com.lowlatency.visualizer.gl.VisualizerRenderer

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
    private val isStereoAudio: () -> Boolean,
    private val onScrubPreview: (Boolean) -> Unit = {},
    private val onCloseMenu: () -> Unit = {},
) {
    private val allEntries = SceneCatalog.ENTRIES
    private val entries get() = allEntries.filter { !it.requiresStereoAudio || isStereoAudio() }

    private lateinit var wheel: SceneWheelView
    private lateinit var counter: TextView
    private lateinit var hint: TextView
    private lateinit var favFilter: TextView
    private lateinit var btnSceneLabel: Button
    private lateinit var sceneLabel: TextView

    private val favourites = linkedSetOf<Int>()
    private var showingFavourites = false

    private var hintRunnable: Runnable? = null
    private var sceneLabelRunnable: Runnable? = null
    private var sceneLabelEnabled = true
    private var activeSceneName = ""

    fun bind() {
        wheel = activity.findViewById(R.id.scene_wheel)
        counter = activity.findViewById(R.id.scene_counter)
        hint = activity.findViewById(R.id.scene_hint)
        favFilter = activity.findViewById(R.id.btn_fav_filter)
        btnSceneLabel = activity.findViewById(R.id.btn_scene_label)
        sceneLabel = activity.findViewById(R.id.scene_label)

        glView.sceneOrder = entries.map { it.index }

        prefs.getStringSet(KEY_FAVOURITES, emptySet())?.forEach {
            it.toIntOrNull()?.let { idx -> favourites.add(idx) }
        }
        updateFavouritesOrder()
        activeSceneName = nameOf(glView.sceneIndex)

        wheel.favourites = favourites.toSet()
        rebuildWheel()
        wheel.onSelect = { glView.selectScene(it) }
        wheel.onCentre = { updateCounter(it) }
        wheel.onScrubbingChange = { setScrubPreview(it) }
        wheel.onPick = { onCloseMenu() }
        wheel.onFavourite = { toggleFavourite(it) }
        wheel.onOverscrollDown = { onCloseMenu() }   // flick down at the top → dismiss

        favFilter.setOnClickListener { toggleFavFilter() }
        updateFavFilterIcon()

        glView.onSceneChanged = { sceneIndex -> onSceneChanged(sceneIndex) }

        updateCounter(glView.sceneIndex)

        setSceneLabelEnabled(prefs.getBoolean(KEY_SCENE_LABEL, true))
        btnSceneLabel.setOnClickListener { setSceneLabelEnabled(!sceneLabelEnabled) }
    }

    /** Build the wheel item list — either the full categorized catalog or just favourites. */
    private fun buildWheelItems(): List<SceneWheelView.Item> {
        if (showingFavourites) {
            // Flat list of favourited scenes in catalog order (no headers).
            return entries
                .filter { favourites.contains(it.index) }
                .map { SceneWheelView.Item(it.index, nameOf(it.index), isHeader = false) }
        }
        val items = mutableListOf<SceneWheelView.Item>()
        var currentCategory: SceneCategory? = null
        for (e in entries) {
            if (e.category != currentCategory) {
                currentCategory = e.category
                items.add(SceneWheelView.Item(-1, e.category.label, isHeader = true))
            }
            items.add(SceneWheelView.Item(e.index, nameOf(e.index), isHeader = false))
        }
        return items
    }

    fun onAudioSourceChanged() {
        glView.sceneOrder = entries.map { it.index }
        updateFavouritesOrder()
        rebuildWheel()
        
        // If the current scene is now unavailable (e.g. requires system audio but we switched to mic),
        // fallback to the default scene.
        if (entries.none { it.index == glView.sceneIndex }) {
            glView.selectScene(VisualizerRenderer.DEFAULT_SCENE)
        }
    }

    private fun rebuildWheel() {
        wheel.setScenes(buildWheelItems(), glView.sceneIndex)
    }

    private fun toggleFavFilter() {
        if (!showingFavourites && favourites.isEmpty()) {
            Toast.makeText(activity, R.string.fav_hint_toast, Toast.LENGTH_SHORT).show()
            return
        }
        showingFavourites = !showingFavourites
        updateFavFilterIcon()
        rebuildWheel()
        updateCounter(glView.sceneIndex)
    }

    private fun updateFavFilterIcon() {
        favFilter.text = if (showingFavourites) "★" else "☆"
        favFilter.contentDescription = activity.getString(
            if (showingFavourites) R.string.cd_fav_filter_showing else R.string.cd_fav_filter_show
        )
        @Suppress("DEPRECATION")
        favFilter.setTextColor(
            if (showingFavourites) activity.resources.getColor(R.color.accent)
            else activity.resources.getColor(R.color.text_dim)
        )
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
        ensureActiveSceneInWheel()
        wheel.centerOn(glView.sceneIndex)
        updateCounter(glView.sceneIndex)
    }

    /** The wheel must open centred on the live scene. Canvas swipes and shuffle
     *  roam the full catalog, so with the favourites filter on the active scene
     *  may not be in the wheel at all — centerOn would bail and the menu would
     *  open pointing at a stale row. Drop the filter rather than lie. */
    private fun ensureActiveSceneInWheel() {
        if (!showingFavourites || favourites.contains(glView.sceneIndex)) return
        showingFavourites = false
        updateFavFilterIcon()
        rebuildWheel()
    }

    /** Fade the counter (and the sheet chrome via [onScrubPreview]) away while
     *  scrubbing so the live visual previews clearly behind the wheel. */
    private fun setScrubPreview(active: Boolean) {
        val a = if (active) 0f else 1f
        counter.animate().alpha(a).setDuration(PREVIEW_FADE_MS).start()
        favFilter.animate().alpha(a).setDuration(PREVIEW_FADE_MS).start()
        if (active) dismissHint()
        onScrubPreview(active)
    }

    /** Show the tap/hold hint on menu open, then fade it after a couple of seconds. */
    fun onMenuOpened() {
        hintRunnable?.let { hint.removeCallbacks(it) }
        hint.animate().cancel()
        hint.alpha = 1f
        val r = Runnable { hint.animate().alpha(0f).setDuration(HINT_FADE_MS).start() }
        hintRunnable = r
        hint.postDelayed(r, HINT_HOLD_MS)
    }

    private fun dismissHint() {
        hintRunnable?.let { hint.removeCallbacks(it) }
        hint.animate().alpha(0f).setDuration(PREVIEW_FADE_MS).start()
    }

    private fun nameOf(index: Int): String =
        allEntries.firstOrNull { it.index == index }?.let { activity.getString(it.nameRes) } ?: ""

    private fun updateCounter(index: Int) {
        val e = entries.firstOrNull { it.index == index } ?: return
        if (showingFavourites) {
            val favEntries = entries.filter { favourites.contains(it.index) }
            val pos = favEntries.indexOf(e) + 1
            counter.text = "FAVOURITES · %02d / %02d".format(pos.coerceAtLeast(1), favEntries.size)
        } else {
            counter.text = "%s · %02d / %02d".format(e.category.label.uppercase(), entries.indexOf(e) + 1, entries.size)
        }
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
        // If we're viewing favourites and just removed the last one, flip back.
        if (showingFavourites) {
            if (favourites.isEmpty()) {
                showingFavourites = false
                updateFavFilterIcon()
            }
            rebuildWheel()
            updateCounter(glView.sceneIndex)
        }
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
        repositionSceneLabel()
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

    fun repositionSceneLabel() {
        val d = activity.resources.displayMetrics.density
        val lp = sceneLabel.layoutParams as android.view.ViewGroup.MarginLayoutParams
        val overlayBottom = perfOverlayBottom()
        lp.topMargin = if (overlayBottom > 0)
            overlayBottom + (d * 12).toInt()
        else
            (d * 72).toInt()
        sceneLabel.layoutParams = lp
    }

    fun onConfigurationChanged() {
        if (::wheel.isInitialized) {
            val lp = wheel.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.topMargin = activity.resources.getDimensionPixelSize(R.dimen.scene_wheel_margin_top)
            lp.bottomMargin = activity.resources.getDimensionPixelSize(R.dimen.scene_wheel_margin_bottom)
            wheel.layoutParams = lp
        }
        val footer = activity.findViewById<View>(R.id.scene_footer)
        if (footer != null) {
            val lp = footer.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.bottomMargin = activity.resources.getDimensionPixelSize(R.dimen.scene_footer_margin_bottom)
            footer.layoutParams = lp
        }
    }

    companion object {
        private const val KEY_FAVOURITES = "favourite_scenes"
        private const val KEY_SCENE_LABEL = "scene_label_enabled"
        private const val PREVIEW_FADE_MS = 200L
        private const val HINT_HOLD_MS = 2000L
        private const val HINT_FADE_MS = 500L
    }
}
