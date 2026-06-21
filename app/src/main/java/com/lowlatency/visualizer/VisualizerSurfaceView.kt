package com.lowlatency.visualizer

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
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

    /** Invoked on a single tap (no GL coupling — pure UI intent). */
    var onTap: (() -> Unit)? = null

    /** Invoked on an upward fling that starts near the bottom edge. */
    var onSwipeUp: (() -> Unit)? = null

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

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                val horizontal = abs(velocityX) > abs(velocityY)
                if (horizontal && abs(velocityX) > SWIPE_VELOCITY) {
                    // Left => next scene, right => previous (within favourites if set).
                    swipeScene(if (velocityX < 0) 1 else -1)
                    return true
                }
                if (!horizontal && velocityY < -SWIPE_VELOCITY) {
                    // Upward fling starting in the bottom third opens the menu.
                    val startedLow = e1 != null && e1.y > height * 0.66f
                    if (startedLow) {
                        onSwipeUp?.invoke()
                        return true
                    }
                }
                return false
            }
        }
    )

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

    /** Performance diagnostics (read on UI thread). */
    val rendererFps: Float get() = renderer.fps
    val rendererFrameTimeMs: Float get() = renderer.frameTimeMs

    /**
     * Favourite scene indices (sorted). When non-empty, a swipe cycles ONLY
     * these; empty = swipe cycles all scenes. The menu can still pick any scene.
     */
    var favourites: List<Int> = emptyList()

    /** Switch to an explicit scene (wraps), updating the menu source-of-truth. */
    fun selectScene(index: Int) {
        val count = renderer.sceneCount
        val next = ((index % count) + count) % count
        if (sceneIndex != next) {
            sceneIndex = next
            queueEvent { renderer.requestScene(next) }
            onSceneChanged?.invoke(next)
        }
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
        return gestureDetector.onTouchEvent(event)
    }

    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
        super.surfaceCreated(holder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.surface.setFrameRate(240f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
    }

    companion object {
        private const val SWIPE_VELOCITY = 800f   // px/s threshold
    }
}
