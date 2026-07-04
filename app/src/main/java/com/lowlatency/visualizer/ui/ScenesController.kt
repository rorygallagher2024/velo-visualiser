package com.lowlatency.visualizer.ui

import android.content.SharedPreferences
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.lowlatency.visualizer.R
import com.lowlatency.visualizer.SecondaryVisualizerActivity
import com.lowlatency.visualizer.VisualizerSurfaceView

/**
 * Owns the Visuals tab: the scene [wheel], the pinned favourites strip, the ★
 * favourite toggle and the transient on-canvas scene label. Scene identity
 * (order, names, category) comes from [SceneCatalog] — the single source of
 * truth shared with the canvas swipe order and favourites — so this controller
 * no longer reads a grid of layout buttons.
 */
class ScenesController(
    private val activity: AppCompatActivity,
    private val glView: VisualizerSurfaceView,
    private val prefs: SharedPreferences,
    private val isMenuOpen: () -> Boolean,
    private val perfOverlayBottom: () -> Int,
    private val onManualSceneChange: () -> Unit,
) {
    private val entries = SceneCatalog.ENTRIES

    private lateinit var wheel: SceneWheelView
    private lateinit var counter: TextView
    private lateinit var favBtn: Button
    private lateinit var btnSceneLabel: Button
    private lateinit var sceneLabel: TextView
    private lateinit var favGroup: View
    private lateinit var favContainer: LinearLayout

    private val favourites = linkedSetOf<Int>()
    private val favPills = mutableListOf<Pair<Button, Int>>()

    private var sceneLabelRunnable: Runnable? = null
    private var sceneLabelEnabled = true
    private var activeSceneName = ""
    /** Scene currently centred in the wheel (leads glView.sceneIndex mid-scrub). */
    private var centredIndex = 0

    fun bind() {
        wheel = activity.findViewById(R.id.scene_wheel)
        counter = activity.findViewById(R.id.scene_counter)
        favBtn = activity.findViewById(R.id.btn_favourite)
        btnSceneLabel = activity.findViewById(R.id.btn_scene_label)
        sceneLabel = activity.findViewById(R.id.scene_label)
        favGroup = activity.findViewById(R.id.favourites_group)
        favContainer = activity.findViewById(R.id.favourites_container)

        glView.sceneOrder = entries.map { it.index }

        prefs.getStringSet(KEY_FAVOURITES, emptySet())?.forEach {
            it.toIntOrNull()?.let { idx -> favourites.add(idx) }
        }
        updateFavouritesOrder()

        centredIndex = glView.sceneIndex
        activeSceneName = nameOf(glView.sceneIndex)

        wheel.favourites = favourites.toSet()
        wheel.setScenes(entries.map { SceneWheelView.Item(it.index, nameOf(it.index)) }, glView.sceneIndex)
        wheel.onSelect = { glView.selectScene(it) }
        wheel.onCentre = { onCentred(it) }

        glView.onSceneChanged = { sceneIndex -> onSceneChanged(sceneIndex) }
        favBtn.setOnClickListener { toggleFavourite(centredIndex) }

        rebuildFavouritesGroup()
        updateCounter(glView.sceneIndex)
        refreshFavButton()

        setSceneLabelEnabled(prefs.getBoolean(KEY_SCENE_LABEL, true))
        btnSceneLabel.setOnClickListener { setSceneLabelEnabled(!sceneLabelEnabled) }
    }

    private fun onSceneChanged(sceneIndex: Int) {
        activeSceneName = nameOf(sceneIndex)
        centredIndex = sceneIndex
        wheel.centerOn(sceneIndex)          // follow external changes (swipe, shuffle)
        updateCounter(sceneIndex)
        refreshFavButton()
        updateFavPillSelection()
        if (!isMenuOpen()) showSceneLabel()
        onManualSceneChange()
        prefs.edit { putInt(SecondaryVisualizerActivity.KEY_ACTIVE_SCENE, sceneIndex) }
    }

    /** Fired live as the wheel scrolls past scenes (before it settles/selects). */
    private fun onCentred(sceneIndex: Int) {
        centredIndex = sceneIndex
        updateCounter(sceneIndex)
        refreshFavButton()
    }

    /** The active scene's display name (e.g. for the perf overlay's Scene row). */
    fun currentSceneName(): String = nameOf(glView.sceneIndex)

    /** Ordered (sceneIndex, displayName) pairs. */
    fun sceneList(): List<Pair<Int, String>> = entries.map { it.index to nameOf(it.index) }

    fun updateSelection() {
        centredIndex = glView.sceneIndex
        wheel.centerOn(glView.sceneIndex)
        updateCounter(glView.sceneIndex)
        refreshFavButton()
        updateFavPillSelection()
    }

    private fun nameOf(index: Int): String =
        entries.firstOrNull { it.index == index }?.let { activity.getString(it.nameRes) } ?: ""

    private fun updateCounter(index: Int) {
        val e = entries.firstOrNull { it.index == index } ?: return
        counter.text = "%s · %02d / %02d".format(e.category.label.uppercase(), entries.indexOf(e) + 1, entries.size)
    }

    private fun refreshFavButton() {
        val fav = favourites.contains(centredIndex)
        favBtn.isSelected = fav
        favBtn.setText(if (fav) R.string.fav_remove else R.string.fav_add)
    }

    private fun updateFavPillSelection() {
        for ((b, idx) in favPills) b.isSelected = idx == glView.sceneIndex
    }

    private fun toggleFavourite(index: Int) {
        if (!favourites.add(index)) favourites.remove(index)
        prefs.edit { putStringSet(KEY_FAVOURITES, favourites.map { it.toString() }.toSet()) }
        updateFavouritesOrder()
        wheel.favourites = favourites.toSet()
        rebuildFavouritesGroup()
        refreshFavButton()
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

    /** Rebuild the pinned Favourites pills (swipe order); the group hides when empty. */
    private fun rebuildFavouritesGroup() {
        favPills.clear()
        favContainer.removeAllViews()
        val ordered = entries.filter { favourites.contains(it.index) }
        favGroup.visibility = if (ordered.isEmpty()) View.GONE else View.VISIBLE
        for (e in ordered) {
            val pill = activity.layoutInflater
                .inflate(R.layout.item_favourite_pill, favContainer, false) as Button
            pill.text = nameOf(e.index)
            pill.isSelected = e.index == glView.sceneIndex
            pill.setOnClickListener { glView.selectScene(e.index); updateSelection() }
            pill.setOnLongClickListener { toggleFavourite(e.index); true }
            favContainer.addView(pill)
            favPills += pill to e.index
        }
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
    }
}
