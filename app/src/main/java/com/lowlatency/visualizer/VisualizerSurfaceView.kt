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

    /** The currently displayed scene index (source of truth for the menu). */
    var sceneIndex = 0
        private set

    private val renderer = VisualizerRenderer()

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true   // required to receive flings

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTap?.invoke()
                return true
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                val horizontal = abs(velocityX) > abs(velocityY)
                if (horizontal && abs(velocityX) > SWIPE_VELOCITY) {
                    // Left => next scene, right => previous.
                    val dir = if (velocityX < 0) 1 else -1
                    selectScene(sceneIndex + dir)
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

    /** Enable/disable the HDR bloom (glow) post-processing pipeline. */
    var bloomEnabled: Boolean
        get() = renderer.bloomEnabled
        set(value) { renderer.bloomEnabled = value }

    /** Switch to an explicit scene (wraps), updating the menu source-of-truth. */
    fun selectScene(index: Int) {
        val count = renderer.sceneCount
        val next = ((index % count) + count) % count
        sceneIndex = next
        queueEvent { renderer.requestScene(next) }
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
