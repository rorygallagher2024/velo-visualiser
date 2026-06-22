package com.lowlatency.visualizer

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import kotlin.math.abs

class MenuManager(private val activity: MainActivity) {

    private lateinit var glView: VisualizerSurfaceView
    private lateinit var scrim: View
    private lateinit var optionsSheet: View
    private lateinit var tabBtnVisuals: Button
    private lateinit var tabBtnLighting: Button
    private lateinit var tabBtnSettings: Button
    private lateinit var tabVisualizers: LinearLayout
    private lateinit var tabLighting: LinearLayout
    private lateinit var tabSettings: LinearLayout
    
    private val favourites = linkedSetOf<Int>()
    private lateinit var visButtons: List<Triple<Button, Int, String>>
    
    var menuOpen = false
        private set

    fun bindViews(root: View) {
        glView = root.findViewById(R.id.gl_view)
        scrim = root.findViewById(R.id.scrim)
        optionsSheet = root.findViewById(R.id.options_sheet)
        tabBtnVisuals = root.findViewById(R.id.tab_btn_visuals)
        tabBtnLighting = root.findViewById(R.id.tab_btn_lighting)
        tabBtnSettings = root.findViewById(R.id.tab_btn_settings)
        tabVisualizers = root.findViewById(R.id.tab_visualizers)
        tabLighting = root.findViewById(R.id.tab_lighting)
        tabSettings = root.findViewById(R.id.tab_settings)
        
        optionsSheet.visibility = View.GONE
    }

    fun setup() {
        wireTabs()
        wireGestures()
        wireVisualizerButtons()
        
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_FAVOURITES, emptySet())?.forEach {
            it.toIntOrNull()?.let { idx -> favourites.add(idx) }
        }
        updateFavouritesOrder()
        updateVisualizerSelection()
    }

    private fun wireTabs() {
        tabBtnVisuals.setOnClickListener { selectTab(TAB_VISUALS) }
        tabBtnLighting.setOnClickListener { selectTab(TAB_LIGHTING) }
        tabBtnSettings.setOnClickListener { selectTab(TAB_SETTINGS) }
        selectTab(TAB_VISUALS)
    }

    fun selectTab(tab: Int) {
        tabBtnVisuals.isSelected = tab == TAB_VISUALS
        tabBtnLighting.isSelected = tab == TAB_LIGHTING
        tabBtnSettings.isSelected = tab == TAB_SETTINGS
        for ((view, id) in listOf(
            tabVisualizers to TAB_VISUALS,
            tabLighting to TAB_LIGHTING,
            tabSettings to TAB_SETTINGS,
        )) {
            val active = tab == id
            view.visibility = if (active) View.VISIBLE else View.GONE
            (view.layoutParams as LinearLayout.LayoutParams).apply {
                weight = if (active) 1f else 0f
                height = if (active) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun wireGestures() {
        glView.onSwipeUp = { showMenu() }
        scrim.setOnClickListener { hideMenu() }

        val sheetGestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (vy > SWIPE_DOWN_VELOCITY && abs(vy) > abs(vx)) {
                    if (optionsSheet.scrollY == 0) {
                        hideMenu()
                        return true
                    }
                }
                return false
            }
        })
        optionsSheet.setOnTouchListener { _, ev -> sheetGestures.onTouchEvent(ev); false }

        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    activity.findViewById<View>(R.id.first_boot_overlay).visibility == View.VISIBLE -> { /* must acknowledge */ }
                    menuOpen -> hideMenu()
                    else -> { isEnabled = false; activity.onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
    }

    private fun wireVisualizerButtons() {
        visButtons = listOf(
            Triple(activity.findViewById(R.id.btn_oscilloscope), 0, activity.getString(R.string.vis_oscilloscope)),
            Triple(activity.findViewById(R.id.btn_rawscope), 8, activity.getString(R.string.vis_rawscope)),
            Triple(activity.findViewById(R.id.btn_bars), 5, activity.getString(R.string.vis_bars)),
            Triple(activity.findViewById(R.id.btn_circular), 4, activity.getString(R.string.vis_circular)),
            Triple(activity.findViewById(R.id.btn_spectrogram), 9, activity.getString(R.string.vis_spectrogram)),
            Triple(activity.findViewById(R.id.btn_led_matrix), 16, activity.getString(R.string.vis_led_matrix)),
            Triple(activity.findViewById(R.id.btn_mechanical_meter), 17, activity.getString(R.string.vis_mechanical_meter)),
            Triple(activity.findViewById(R.id.btn_cymatics), 21, activity.getString(R.string.vis_cymatics)),
            Triple(activity.findViewById(R.id.btn_beat_pulse), 18, activity.getString(R.string.vis_beat_pulse)),
            Triple(activity.findViewById(R.id.btn_fireworks), 10, activity.getString(R.string.vis_fireworks)),
            Triple(activity.findViewById(R.id.btn_starscape), 7, activity.getString(R.string.vis_starscape)),
            Triple(activity.findViewById(R.id.btn_bloom), 6, activity.getString(R.string.vis_bloom)),
            Triple(activity.findViewById(R.id.btn_electric_iris), 12, activity.getString(R.string.vis_electric_iris)),
            Triple(activity.findViewById(R.id.btn_aurora_drift), 24, activity.getString(R.string.vis_aurora_drift)),
            Triple(activity.findViewById(R.id.btn_tunnel), 1, activity.getString(R.string.vis_tunnel)),
            Triple(activity.findViewById(R.id.btn_laser), 3, activity.getString(R.string.vis_laser)),
            Triple(activity.findViewById(R.id.btn_phyllotaxis), 11, activity.getString(R.string.vis_phyllotaxis)),
            Triple(activity.findViewById(R.id.btn_mandala), 13, activity.getString(R.string.vis_mandala)),
            Triple(activity.findViewById(R.id.btn_audio_web), 14, activity.getString(R.string.vis_audio_web)),
            Triple(activity.findViewById(R.id.btn_topo_ridge), 15, activity.getString(R.string.vis_topo_ridge)),
            Triple(activity.findViewById(R.id.btn_strange_attractor), 22, activity.getString(R.string.vis_strange_attractor)),
            Triple(activity.findViewById(R.id.btn_fluid), 2, activity.getString(R.string.vis_fluid)),
            Triple(activity.findViewById(R.id.btn_mandelbox), 19, activity.getString(R.string.vis_mandelbox)),
            Triple(activity.findViewById(R.id.btn_reaction_diffusion), 20, activity.getString(R.string.vis_reaction_diffusion)),
            Triple(activity.findViewById(R.id.btn_plasma_storm), 23, activity.getString(R.string.vis_plasma_storm)),
            Triple(activity.findViewById(R.id.btn_odyssey), 25, activity.getString(R.string.vis_odyssey)),
        )
        glView.sceneOrder = visButtons.map { it.second }
        glView.onSceneChanged = { updateVisualizerSelection() }

        visButtons.forEach { (b, idx, _) ->
            b.setOnClickListener { glView.selectScene(idx); updateVisualizerSelection() }
            b.setOnLongClickListener { toggleFavourite(idx); true }
        }
    }

    fun showMenu() {
        if (menuOpen) return
        menuOpen = true
        activity.syncMenuState()

        scrim.alpha = 0f
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(180).start()

        val off = activity.resources.displayMetrics.heightPixels.toFloat()
        optionsSheet.translationY = off
        optionsSheet.visibility = View.VISIBLE
        optionsSheet.animate().translationY(0f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    fun hideMenu() {
        if (!menuOpen) return
        menuOpen = false

        scrim.animate().alpha(0f).setDuration(180)
            .withEndAction { scrim.visibility = View.GONE }.start()

        val off = activity.resources.displayMetrics.heightPixels.toFloat()
        optionsSheet.animate().translationY(off).setDuration(200)
            .withEndAction { optionsSheet.visibility = View.GONE }.start()
    }

    private fun toggleFavourite(index: Int) {
        if (!favourites.add(index)) favourites.remove(index)
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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

    fun updateVisualizerSelection() {
        val current = glView.sceneIndex
        for ((b, idx, base) in visButtons) {
            b.isSelected = idx == current
            b.text = if (favourites.contains(idx)) "★ $base" else base
        }
    }

    companion object {
        const val PREFS = "visualizer_prefs"
        const val KEY_FAVOURITES = "favourite_scenes"
        const val SWIPE_DOWN_VELOCITY = 1200f
        const val TAB_VISUALS = 0
        const val TAB_LIGHTING = 1
        const val TAB_SETTINGS = 2
    }
}
