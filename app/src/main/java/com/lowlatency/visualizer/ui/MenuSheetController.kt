package com.lowlatency.visualizer.ui

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.lowlatency.visualizer.R
import com.lowlatency.visualizer.VisualizerSurfaceView
import kotlin.math.abs

/**
 * Owns the settings sheet's presentation: the interactive swipe-up drag (the
 * sheet follows the finger), velocity-based open/close settling, the dim scrim,
 * and the synchronized canvas blur. Extracted from MainActivity so the activity
 * stays a thin coordinator — it just supplies the GL surface and a hook to
 * refresh the menu contents before the sheet shows.
 *
 * The canvas stays chrome-free: the gesture is discovered via the transient
 * first-boot hint, never a persistent on-screen affordance.
 */
class MenuSheetController(
    private val activity: AppCompatActivity,
    private val glView: VisualizerSurfaceView,
    private val onBeforeOpen: () -> Unit,
) {
    private lateinit var scrim: View
    private lateinit var optionsSheet: View
    private lateinit var sheetContent: View
    private lateinit var tabBar: View
    private lateinit var handle: View
    private lateinit var navDivider: View
    private lateinit var closeBtn: View
    private lateinit var veloLogo: View
    private lateinit var optionsSheetScroll: View
    
    // Dynamically positioned elements
    private lateinit var sceneWheel: View
    private lateinit var bottomNavContainer: View
    private lateinit var sceneFooter: View
    
    private var scrubPreview = false

    private var blurAnimator: ValueAnimator? = null
    private var currentBlurRadius = 0f
    private var sheetDragActive = false
    private var sheetTravel = 0f
    private var lastClosedMs = 0L

    var isOpen = false
        private set


    @Suppress("ClickableViewAccessibility")
    fun bind() {
        scrim = activity.findViewById(R.id.scrim)
        optionsSheet = activity.findViewById(R.id.options_sheet)
        sheetContent = activity.findViewById(R.id.options_sheet_content)
        tabBar = activity.findViewById(R.id.section_tabs)
        handle = activity.findViewById(R.id.sheet_handle)
        navDivider = activity.findViewById(R.id.sheet_nav_divider)
        closeBtn = activity.findViewById(R.id.btn_close_menu)
        veloLogo = activity.findViewById(R.id.velo_logo)
        optionsSheetScroll = activity.findViewById(R.id.options_sheet_scroll)
        sceneWheel = activity.findViewById(R.id.scene_wheel)
        bottomNavContainer = activity.findViewById(R.id.bottom_nav_container)
        sceneFooter = activity.findViewById(R.id.scene_footer)
        
        closeBtn.setOnClickListener { close() }
        optionsSheet.visibility = View.GONE
        applyWidthCap()

        // Interactive swipe-up: the sheet follows the finger, then settles open or
        // closed on release based on position + velocity.
        glView.onMenuDragStart = { beginDrag() }
        glView.onMenuDrag = { dyUp -> updateDrag(dyUp) }
        glView.onMenuDragRelease = { vUp -> endDrag(vUp) }
        glView.onLongHold = { open() }   // long-press the canvas → open the menu

        scrim.setOnClickListener { close() }

        // Fast swipe-down on the sheet dismisses it. The sheet is a ScrollView, so
        // this listener must NOT consume touches (return false) — otherwise the
        // ScrollView can't scroll its content. The detector still observes events.
        val sheetGestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = false
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (vy > SWIPE_DOWN_VELOCITY && abs(vy) > abs(vx) && optionsSheetScroll.scrollY == 0) {
                    close()
                    return true
                }
                return false
            }
        })
        optionsSheet.setOnTouchListener { _, ev -> sheetGestures.onTouchEvent(ev); false }
        optionsSheetScroll.setOnTouchListener { _, ev -> sheetGestures.onTouchEvent(ev); false }
    }

    /**
     * Cap the sheet's content to a centered column on wide displays (tablets,
     * unfolded foldables, landscape phones) so controls don't stretch
     * edge-to-edge; below the trigger width the column fills as before. The
     * sheet's translucent background still spans the full width — only the
     * content centers.
     */
    private fun applyWidthCapToView(view: View, widthPx: Int, baseGravity: Int) {
        val lp = view.layoutParams as FrameLayout.LayoutParams
        lp.width = widthPx
        lp.gravity = if (widthPx == ViewGroup.LayoutParams.MATCH_PARENT) baseGravity else baseGravity or Gravity.CENTER_HORIZONTAL
        view.layoutParams = lp
    }

    private fun applyWidthCap() {
        val widthDp = activity.resources.configuration.screenWidthDp
        val widthPx = if (widthDp > WIDTH_CAP_TRIGGER_DP) (WIDTH_CAP_DP * activity.resources.displayMetrics.density).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
        
        applyWidthCapToView(sheetContent, widthPx, Gravity.NO_GRAVITY)
        
        val tabVis = activity.findViewById<View>(R.id.tab_visualizers)
        if (tabVis != null) {
            applyWidthCapToView(tabVis, widthPx, Gravity.NO_GRAVITY)
        }
        
        applyWidthCapToView(bottomNavContainer, widthPx, Gravity.BOTTOM)
        
        // Tab margins must be updated dynamically since we handle config changes without recreation.
        val lpTabs = tabBar.layoutParams as LinearLayout.LayoutParams
        val isLandscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        lpTabs.bottomMargin = if (isLandscape) (4f * activity.resources.displayMetrics.density).toInt() else (29f * activity.resources.displayMetrics.density).toInt()
        tabBar.layoutParams = lpTabs
    }

    /** Re-fit the content column after a rotation / fold change. */
    fun onConfigurationChanged() {
        if (::sheetContent.isInitialized) applyWidthCap()
        if (::optionsSheet.isInitialized) {
            val lp = optionsSheet.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = activity.resources.getDimensionPixelSize(R.dimen.sheet_margin_top)
            optionsSheet.layoutParams = lp
            
            optionsSheet.post {
                if (!isOpen && !sheetDragActive) {
                    sheetTravel = sheetTravelPx()
                    optionsSheet.translationY = sheetTravel
                } else if (isOpen && !sheetDragActive) {
                    sheetTravel = sheetTravelPx()
                    optionsSheet.translationY = 0f
                }
            }
        }
    }

    /**
     * Open the sheet programmatically (e.g. a swipe-up from Ambient Mode, where the
     * GL view never sees the gesture). Lifts the scrim + sheet above any full-screen
     * overlay so they show on top; the overlay below keeps running, so closing the
     * sheet returns to it.
     */
    fun openOverlay() {
        if (isOpen) return
        scrim.bringToFront()
        optionsSheet.bringToFront()
        open()
    }

    /** Open the sheet with the standard slide-up (e.g. a long-press on the canvas). */
    fun open() {
        if (isOpen) return
        if (SystemClock.elapsedRealtime() - lastClosedMs < REOPEN_COOLDOWN_MS) return
        beginDrag()                 // primes the sheet off-screen + refreshes contents
        sheetDragActive = false     // not a finger drag — settle handles the animation
        settle(open = true, speedPxPerS = 0f)
    }

    /** Animate the sheet shut (scrim tap / back press / swipe-down). */
    fun close() {
        if (!isOpen) return
        sheetDragActive = false
        settle(open = false, speedPxPerS = 0f)
    }

    /** Travel distance from fully-closed (sheet off the bottom) to open. */
    private fun sheetTravelPx(): Float =
        (if (optionsSheet.height > 0) optionsSheet.height else activity.resources.displayMetrics.heightPixels).toFloat()

    /** Finger-down upward drag began: prime the sheet at the closed position so it
     *  can track the finger from off-screen. */
    private fun beginDrag() {
        if (sheetDragActive) return
        if (SystemClock.elapsedRealtime() - lastClosedMs < REOPEN_COOLDOWN_MS) return
        sheetDragActive = true
        isOpen = true
        glView.isMenuOpen = true
        resetScrubPreview()
        onBeforeOpen()
        optionsSheet.animate().cancel()
        scrim.animate().cancel()
        sheetTravel = sheetTravelPx()
        optionsSheet.translationY = sheetTravel
        optionsSheet.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            optionsSheet.background.mutate().alpha = 210 // ~82% translucent glass
        }
    }

    /** The sheet tracks the finger 1:1; scrim + blur follow drag progress. */
    private fun updateDrag(dyUp: Float) {
        if (!sheetDragActive) return
        val ty = (sheetTravel - dyUp).coerceIn(0f, sheetTravel)
        optionsSheet.translationY = ty
        val progress = if (sheetTravel > 0f) 1f - ty / sheetTravel else 0f
        scrim.alpha = progress
        setBlurRadius(progress * MENU_BLUR_MAX)
    }

    /** Finger lifted: settle open or closed from position + velocity. */
    private fun endDrag(velUpPxPerS: Float) {
        if (!sheetDragActive) return
        sheetDragActive = false
        val progress = if (sheetTravel > 0f) 1f - optionsSheet.translationY / sheetTravel else 0f
        val open = when {
            velUpPxPerS > SHEET_SETTLE_VELOCITY -> true
            velUpPxPerS < -SHEET_SETTLE_VELOCITY -> false
            else -> progress > 0.4f
        }
        settle(open, abs(velUpPxPerS))
    }

    /** Animate to the open/closed rest state, easing at a speed handed off from
     *  the gesture's velocity. */
    private fun settle(open: Boolean, speedPxPerS: Float) {
        isOpen = open
        glView.isMenuOpen = open
        if (!open) lastClosedMs = SystemClock.elapsedRealtime()
        if (open) {
            optionsSheet.visibility = View.VISIBLE
            scrim.visibility = View.VISIBLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                optionsSheet.background.mutate().alpha = SHEET_ALPHA
            }
        }
        val target = if (open) 0f else sheetTravelPx()
        val distance = abs(optionsSheet.translationY - target)
        val dur = if (speedPxPerS > 1f) (distance / speedPxPerS * 1000f).toLong().coerceIn(140L, 360L) else 300L
        val interp = DecelerateInterpolator(1.4f)

        optionsSheet.animate().translationY(target).setDuration(dur).setInterpolator(interp)
            .withEndAction { if (!open) optionsSheet.visibility = View.GONE }
            .start()
        scrim.animate().alpha(if (open) 1f else 0f).setDuration(dur).setInterpolator(interp)
            .withEndAction { if (!open) scrim.visibility = View.GONE }
            .start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            animateBlur(if (open) MENU_BLUR_MAX else 0f, dur, interp)
        }
    }

    /** Immediately set the canvas blur radius (during the drag, bypassing the
     *  animator so it tracks the finger without lag). */
    private fun setBlurRadius(r: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        blurAnimator?.cancel()
        currentBlurRadius = r
        val wantBlur = r > 0.5f && !glView.activeSceneSuppressesBlur
        glView.setRenderEffect(if (wantBlur) RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null)
    }

    private fun animateBlur(targetRadius: Float, animDuration: Long, animInterpolator: TimeInterpolator) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        blurAnimator?.cancel()
        // Resume from the live radius (the drag may have left it mid-way) so the
        // settle doesn't snap the blur and flicker.
        blurAnimator = ValueAnimator.ofFloat(currentBlurRadius, targetRadius).apply {
            duration = animDuration
            interpolator = animInterpolator
            addUpdateListener { anim ->
                val r = anim.animatedValue as Float
                currentBlurRadius = r
                val wantBlur = r > 0.5f && !glView.activeSceneSuppressesBlur
                glView.setRenderEffect(if (wantBlur) RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null)
            }
            start()
        }
    }

    /**
     * Scrub-preview: while the scene wheel is being dragged, melt the sheet's chrome
     * out of the way — fade the tab bar / handle / divider — so the list can stretch
     * to the edges clearly. The dim scrim is also faded so the visual pops, but the
     * glass sheet background itself remains. All restored when the wheel settles.
     */
    fun setScrubPreview(active: Boolean) {
        if (!isOpen || scrubPreview == active) return
        scrubPreview = active
        val a = if (active) 0f else 1f
        // Fade the chrome AND the dim scrim, so the canvas is fully revealed.
        // Set visibility to INVISIBLE when active to prevent ghost touches.
        listOfNotNull(bottomNavContainer, handle, closeBtn, veloLogo, scrim, sceneFooter).forEach { view ->
            view.animate().cancel()
            if (!active) view.visibility = View.VISIBLE
            view.animate().alpha(a).setDuration(PREVIEW_FADE_MS)
                .withEndAction {
                    if (active) view.visibility = View.INVISIBLE
                }
                .start()
        }
    }

    /** Clear any lingering scrub-preview fade (called when the sheet (re)opens). */
    private fun resetScrubPreview() {
        scrubPreview = false
        if (::tabBar.isInitialized) {
            listOfNotNull(bottomNavContainer, handle, closeBtn, veloLogo, scrim, sceneFooter).forEach { view ->
                view.animate().cancel()
                view.alpha = 1f
                view.visibility = View.VISIBLE
            }
        }
    }

    companion object {
        private const val SWIPE_DOWN_VELOCITY = 1200f
        private const val TAB_SWIPE_VELOCITY = 700f   // horizontal flick to change section
        private const val MENU_BLUR_MAX = 32f
        private const val SHEET_SETTLE_VELOCITY = 600f  // px/s flick that decisively opens/closes
        private const val WIDTH_CAP_TRIGGER_DP = 600    // apply the cap above this screen width
        private const val WIDTH_CAP_DP = 560f           // centered content column width
        private const val SHEET_ALPHA = 210             // ~82% translucent glass (open)
        private const val PREVIEW_FADE_MS = 200L
        private const val REOPEN_COOLDOWN_MS = 300L     // block re-open right after a close
    }
}
