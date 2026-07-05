package com.lowlatency.visualizer

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.VelocityTracker
import android.view.ViewConfiguration
import com.lowlatency.visualizer.gl.HdrEGLConfigChooser
import com.lowlatency.visualizer.gl.VisualizerRenderer
import kotlin.math.abs

/**
 * High-refresh GLSurfaceView hosting the visualizer, with a clean native
 * gesture layer (no on-screen controls — pure output):
 *
 *   - Horizontal swipe (left/right) -> smoothly swap the active GL program
 *     between the Hardware Oscilloscope and the Deep Tunnel.
 *   - Single tap -> [onTap] callback (used by the Activity to toggle the audio
 *     source between mic and system audio).
 */
class VisualizerSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    /**
     * Toggles Dynamic Resolution Scaling. When true, halves the EGL surface resolution
     * to save GPU fill-rate, relying on the OS hardware composer to stretch it back.
     */
    var isMenuOpen = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    holder.setFixedSize((width / 2).coerceAtLeast(1), (height / 2).coerceAtLeast(1))
                } else {
                    // Revert to the full layout size so it auto-resizes on fold/rotate
                    holder.setSizeFromLayout()
                }
            }
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isMenuOpen) {
            holder.setFixedSize((w / 2).coerceAtLeast(1), (h / 2).coerceAtLeast(1))
        }
    }

    /** Invoked on a single tap (no GL coupling — pure UI intent). */
    var onTap: (() -> Unit)? = null

    /**
     * Interactive swipe-up-to-open-menu, driven by raw touch so the sheet can
     * follow the finger (instead of a fixed canned fling animation):
     *  - [onMenuDragStart]   the user began an upward drag from the bottom region.
     *  - [onMenuDrag]        called each move with px dragged upward from the start (>=0).
     *  - [onMenuDragRelease] finger lifted; arg is the upward velocity in px/s
     *                        (positive = flicking up). The Activity decides settle.
     */
    var onMenuDragStart: (() -> Unit)? = null
    var onMenuDrag: ((Float) -> Unit)? = null
    var onMenuDragRelease: ((Float) -> Unit)? = null

    /** Long-press on the canvas (menu closed) → open the menu — an edge-independent
     *  alternative to the swipe-up, which can clash with the system nav gesture. */
    var onLongHold: (() -> Unit)? = null

    /** Invoked on the UI thread when the scene is changed (via swipe or select). */
    var onSceneChanged: ((Int) -> Unit)? = null

    /** The currently displayed scene index (source of truth for the menu). */
    var sceneIndex = VisualizerRenderer.DEFAULT_SCENE
        private set

    /** The ordered list of scene indices matching the menu UI. */
    var sceneOrder: List<Int> = emptyList()

    private val renderer = VisualizerRenderer(context)

    /** Enable/disable the first-run VELO intro (off => boot straight to visuals). */
    var introEnabled: Boolean
        get() = renderer.introEnabled
        set(value) { renderer.introEnabled = value }

    /** True while the intro animation is still playing. */
    val introActive: Boolean get() = renderer.introActive

    /** True if the intro will run on the next surface creation (cold start). */
    val willPlayIntro: Boolean get() = renderer.willPlayIntro()

    /**
     * Invoked when the intro finishes (or is skipped/disabled). Delivered on the
     * main thread — the renderer fires it on the GL thread and we hop here.
     */
    var onIntroFinished: (() -> Unit)? = null
        set(value) {
            field = value
            renderer.onIntroFinished = { post { value?.invoke() } }
        }

    /** Fast-forward the intro to its dissolve. Safe to call any time. */
    fun skipIntro() = queueEvent { renderer.skipIntro() }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true   // required to receive flings

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (renderer.introActive) { skipIntro(); return true }
                onTap?.invoke()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (renderer.introActive || menuDragging || isMenuOpen) return
                this@VisualizerSurfaceView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongHold?.invoke()
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                if (isMenuOpen) return false

                // Horizontal fling => change scene. The vertical (menu) gesture is
                // handled interactively in onTouchEvent, not here.
                if (abs(velocityX) > abs(velocityY) && abs(velocityX) > SWIPE_VELOCITY) {
                    swipeScene(if (velocityX < 0) 1 else -1)
                    return true
                }
                return false
            }
        }
    )

    // ----- Interactive swipe-up drag (menu) -----
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var startedLow = false
    private var gestureDecided = false
    private var menuDragging = false

    /** Enable/disable OLED burn-in protection (idle dimming + orbit dimming). */
    var burnInEnabled: Boolean
        get() = renderer.burnInEnabled
        set(value) { renderer.burnInEnabled = value }

    /** Per-frame audio tap [low, mid, high] on the GL thread (Hue light sync). */
    var bandsSink: ((Float, Float, Float) -> Unit)?
        get() = renderer.bandsSink
        set(value) { renderer.bandsSink = value }

    /** Per-frame raw-PCM tap on the GL thread (beat/onset detection for haptics). */
    var pcmBeatSink: ((FloatArray) -> Unit)?
        get() = renderer.pcmBeatSink
        set(value) { renderer.pcmBeatSink = value }

    /** Fired on the GL thread on each Ableton Link beat (haptics when Link sync is on). */
    var onLinkBeat: (() -> Unit)?
        get() = renderer.onLinkBeat
        set(value) { renderer.onLinkBeat = value }

    /** Fired on the GL thread when a drop/build surge is detected (any mode). */
    var onDrop: (() -> Unit)?
        get() = renderer.onDrop
        set(value) { renderer.onDrop = value }

    /** Performance diagnostics (read on UI thread). */
    val rendererFps: Float get() = renderer.fps
    val rendererFrameTimeMs: Float get() = renderer.frameTimeMs

    /** Whether the active scene opts out of the settings-sheet canvas blur. */
    val activeSceneSuppressesBlur: Boolean get() = renderer.activeSceneSuppressesBlur

    /**
     * Favourite scene indices (sorted). When non-empty, a swipe cycles ONLY
     * these; empty = swipe cycles all scenes. The menu can still pick any scene.
     */
    var favourites: List<Int> = emptyList()

    /** Switch to an explicit scene (wraps), updating the menu source-of-truth.
     *  [transitionSec] < 0 uses the renderer's default fade; pass a larger value
     *  for a slower, ambient crossfade (e.g. Shuffle). */
    fun selectScene(index: Int, transitionSec: Float = -1f) {
        val count = renderer.sceneCount
        val next = ((index % count) + count) % count
        if (sceneIndex != next) {
            sceneIndex = next
            queueEvent {
                if (transitionSec > 0f) renderer.requestScene(next, transitionSec)
                else renderer.requestScene(next)
            }
            onSceneChanged?.invoke(next)
        }
    }

    /** Public next/prev scene step (e.g. swipes from Display Mode). +1 = next. */
    fun cycleScene(dir: Int) = swipeScene(dir)

    /** Jump to a random scene (Shuffle mode) — within favourites if set, never an
     *  immediate repeat. The renderer's transition crossfades to it. */
    fun shuffleScene() {
        val favs = favourites
        val order = if (favs.isNotEmpty()) favs else sceneOrder
        val pool = order.filter { it != sceneIndex }
        if (pool.isEmpty()) { swipeScene(1); return }   // <=1 option: just step on
        selectScene(pool.random(), SHUFFLE_TRANSITION_SEC)   // slower, ambient fade
    }

    /** Step to the next/previous scene for a swipe — within favourites if set. */
    private fun swipeScene(dir: Int) {
        val favs = favourites
        val activeOrder = if (favs.isNotEmpty()) favs else sceneOrder
        
        if (activeOrder.isEmpty()) {
            // Fallback to raw numeric order if no sequence provided
            selectScene(sceneIndex + dir)
            return
        }

        val pos = activeOrder.indexOf(sceneIndex)
        val nextIndex = if (pos < 0) {
            // Current isn't in the list — jump to the first (or last) item.
            if (dir > 0) activeOrder.first() else activeOrder.last()
        } else {
            val nextPos = ((pos + dir) % activeOrder.size + activeOrder.size) % activeOrder.size
            activeOrder[nextPos]
        }
        selectScene(nextIndex)
    }

    init {
        // Keep the screen awake whenever this view is attached/visible — scoped
        // to the visualizer surface itself (complements the window-level
        // FLAG_KEEP_SCREEN_ON set in MainActivity.onCreate).
        keepScreenOn = true

        // ES 3.x context: required for the Fluid scene's compute shaders /
        // SSBOs. Backward-compatible — the GLES20-based scenes keep working.
        setEGLContextClientVersion(3)
        // HDR: pick an FP16 (or 10-bit) framebuffer so >1.0 fragment output is
        // preserved. Must be applied BEFORE setRenderer().
        setEGLConfigChooser(HdrEGLConfigChooser())
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                startedLow = event.y > height * MENU_GRAB_ZONE
                gestureDecided = false
                menuDragging = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                gestureDetector.onTouchEvent(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!gestureDecided) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        gestureDecided = true
                        // Upward drag from the bottom region (and not during the
                        // intro) becomes an interactive menu pull.
                        if (!isMenuOpen && startedLow && dy < 0 && abs(dy) > abs(dx) &&
                            onMenuDragStart != null && !renderer.introActive
                        ) {
                            menuDragging = true
                            onMenuDragStart?.invoke()
                        }
                    }
                }
                if (menuDragging) {
                    onMenuDrag?.invoke((downY - event.y).coerceAtLeast(0f))
                    return true
                }
                gestureDetector.onTouchEvent(event)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (menuDragging) {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val upwardVel = -(velocityTracker?.yVelocity ?: 0f)
                    onMenuDragRelease?.invoke(upwardVel)
                    menuDragging = false
                } else {
                    gestureDetector.onTouchEvent(event)
                }
                velocityTracker?.recycle()
                velocityTracker = null
                return true
            }
            else -> {
                gestureDetector.onTouchEvent(event)
                return true
            }
        }
    }

    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
        super.surfaceCreated(holder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.surface.setFrameRate(240f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
    }

    companion object {
        private const val SWIPE_VELOCITY = 800f   // px/s threshold
        private const val SHUFFLE_TRANSITION_SEC = 0.9f   // slower, ambient fade for Shuffle (vs ~0.45s manual)
        private const val MENU_GRAB_ZONE = 0.5f   // upward drag below this fraction opens the menu
    }
}
