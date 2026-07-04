package com.lowlatency.visualizer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.lowlatency.visualizer.R
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Vertical "scroll wheel" scene picker — the Visuals-tab selector.
 *
 * Names ride a virtual cylinder: the centred name is the hero, rows above and
 * below curve away, foreshorten and fade like a physical wheel (iOS-picker
 * style). Because every scene is its own row there is no horizontal text
 * collision, and many more scenes are legible at once — which is why it scales
 * better to 40+ scenes than the tape strip. Same [onSelect]/[onCentre] contract.
 */
class SceneWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class Item(val index: Int, val name: String, val isHeader: Boolean = false)

    private var items: List<Item> = emptyList()
    var onSelect: ((Int) -> Unit)? = null
    var onCentre: ((Int) -> Unit)? = null

    /** Scene indices to mark with a ★ on their row. */
    var favourites: Set<Int> = emptySet()
        set(value) { field = value; invalidate() }

    /** Fired true when a drag/fling scrub begins, false when the wheel settles. */
    var onScrubbingChange: ((Boolean) -> Unit)? = null
    private var scrubbing = false

    private var scrubFrac = 0f
    private var scrubAnimator: ValueAnimator? = null

    /** Tap a row → select that scene, then dismiss the menu ([onSelect] + [onPick]). */
    var onPick: (() -> Unit)? = null
    /** Long-press a row → toggle it as a favourite. */
    var onFavourite: ((Int) -> Unit)? = null

    private var scrollPx = 0f
    private var rowH = 120f
    private var radius = 300f
    private var activePos = -1
    private var fittedBase = 0f     // one consistent hero text size (fits the longest name)
    private var shadowRadius = 0f

    private val scroller = OverScroller(context)
    private var flinging = false
    private var dragging = false

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.inter) ?: Typeface.DEFAULT
        letterSpacing = 0.06f
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_dim)
        strokeWidth = 1f * resources.displayMetrics.density
        alpha = 120
    }
    private val primary = ContextCompat.getColor(context, R.color.text_primary)

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true); flinging = false; dragging = false; return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                dragging = true
                setScrubbing(true)
                scrollPx = (scrollPx + dy).coerceIn(0f, maxScroll())
                reportCentre(); invalidate(); return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                dragging = false; flinging = true
                setScrubbing(true)
                scroller.fling(0, scrollPx.toInt(), 0, (-vy).toInt(), 0, 0, 0, maxScroll().toInt())
                invalidate(); return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                items.getOrNull(rowAt(e.y))?.takeIf { !it.isHeader }?.let { onSelect?.invoke(it.index) }
                onPick?.invoke()        // tap a visual → select it and dismiss the menu
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                items.getOrNull(rowAt(e.y))?.takeIf { !it.isHeader }?.let {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onFavourite?.invoke(it.index)
                }
            }
        },
    )

    /** The scene-row nearest a touch y, inverting the cylinder projection. */
    private fun rowAt(y: Float): Int {
        val s = ((y - height / 2f) / radius).coerceIn(-1f, 1f)
        return (scrollPx / rowH + asin(s) / ANGLE_STEP).roundToInt().coerceIn(0, lastIndex())
    }

    fun setScenes(list: List<Item>, activeIndex: Int) {
        items = list
        val pos = list.indexOfFirst { it.index == activeIndex }.coerceAtLeast(0)
        activePos = pos
        scrollPx = pos * rowH
        computeFittedBase()
        invalidate()
    }

    private fun lastIndex() = max(items.size - 1, 0)
    private fun maxScroll() = lastIndex() * rowH

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        radius = h * 0.5f
        // Row height set so a finger-drag near the centre tracks roughly 1:1 on screen.
        rowH = radius * ANGLE_STEP
        shadowRadius = SHADOW_DP * resources.displayMetrics.density
        scrollPx = max(activePos, 0) * rowH
        computeFittedBase()
    }

    /** One consistent hero text size that fits even the longest name (measured as
     *  if starred), so a row's size never varies with the name's length. */
    private fun computeFittedBase() {
        val ideal = height * BASE_FRAC
        if (items.isEmpty() || width == 0) { fittedBase = ideal; return }
        textPaint.textSize = ideal
        var widest = 1f
        for (it in items) widest = max(widest, textPaint.measureText("★  " + it.name.uppercase()))
        val maxW = width * FIT_FRAC
        fittedBase = if (widest > maxW) ideal * (maxW / widest) else ideal
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPx = scroller.currY.toFloat(); reportCentre(); invalidate()
        } else if (flinging) {
            flinging = false; snap()
        } else if (scrubbing) {
            setScrubbing(false)     // fully at rest after a scrub → restore chrome
        }
    }

    private fun setScrubbing(on: Boolean) {
        if (scrubbing == on) return
        scrubbing = on
        onScrubbingChange?.invoke(on)
        scrubAnimator?.cancel()
        scrubAnimator = ValueAnimator.ofFloat(scrubFrac, if (on) 1f else 0f).apply {
            duration = 200
            addUpdateListener { 
                scrubFrac = it.animatedValue as Float
                invalidate() 
            }
            start()
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Own vertical drags so the enclosing scroll sheet doesn't steal them.
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dragging && !flinging) { dragging = false; snap() }
        }
        return true
    }

    private fun snap() = animateTo((scrollPx / rowH).roundToInt().coerceIn(0, lastIndex()))

    private fun animateTo(pos: Int) {
        val target = (pos * rowH).toInt()
        scroller.startScroll(0, scrollPx.toInt(), 0, target - scrollPx.toInt(), 300)
        flinging = false
        activePos = pos
        items.getOrNull(pos)?.takeIf { !it.isHeader }?.let { onSelect?.invoke(it.index) }
        invalidate()
    }

    private fun reportCentre() {
        val p = (scrollPx / rowH).roundToInt().coerceIn(0, lastIndex())
        if (p != activePos) {
            val oldPos = activePos
            activePos = p
            
            val minPos = kotlin.math.min(oldPos, p)
            val maxPos = kotlin.math.max(oldPos, p)
            var crossedHeader = false
            for (i in minPos + 1..maxPos) {
                if (items.getOrNull(i)?.isHeader == true) {
                    crossedHeader = true
                    break
                }
            }
            if (crossedHeader && oldPos != -1) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            
            items.getOrNull(p)?.takeIf { !it.isHeader }?.let { onCentre?.invoke(it.index) }
        }
    }

    /** Animate to [sceneIndex] WITHOUT firing [onSelect] — for external scene
     *  changes (canvas swipe, shuffle) so the wheel follows without re-selecting. */
    fun centerOn(sceneIndex: Int) {
        val pos = items.indexOfFirst { it.index == sceneIndex }
        if (pos < 0 || pos == activePos) return
        scroller.forceFinished(true)
        flinging = false
        activePos = pos
        scroller.startScroll(0, scrollPx.toInt(), 0, (pos * rowH - scrollPx).toInt(), 300)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty() || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        // Selection band hairlines framing the centre (hero) row.
        val half = rowH * 0.5f
        val inset = width * 0.16f
        canvas.drawLine(inset, cy - half, width - inset, cy - half, tickPaint)
        canvas.drawLine(inset, cy + half, width - inset, cy + half, tickPaint)
        for (i in items.indices) {
            val angle = ((i * rowH - scrollPx) / rowH) * ANGLE_STEP
            if (abs(angle) >= EDGE_ANGLE) continue
            val fore = cos(angle)
            if (fore <= 0.02f) continue
            val y = cy + sin(angle) * radius
            val fav = !items[i].isHeader && favourites.contains(items[i].index)
            val name = (if (fav) "★  " else "") + items[i].name.uppercase()
            
            // Restrict visible angle when not scrubbing to prevent extending under buttons
            val restrictedEdge = 0.82f
            val currentEdge = restrictedEdge + scrubFrac * (EDGE_ANGLE - restrictedEdge)
            val distanceFrac = abs(angle) / currentEdge
            if (distanceFrac >= 1f) continue
            
            val alphaBase = (255f * fore * fore * fore).toInt()
            val fadeOut = (1f - distanceFrac).coerceIn(0f, 1f)
            val alpha = (alphaBase * fadeOut * fadeOut).toInt().coerceIn(6, 255)
            
            if (items[i].isHeader) {
                textPaint.textSize = fittedBase * (0.4f + 0.3f * fore)
                textPaint.color = ContextCompat.getColor(context, R.color.text_dim)
            } else {
                textPaint.textSize = fittedBase * (0.6f + 0.4f * fore)   // one consistent size
                textPaint.color = primary
            }
            
            textPaint.alpha = alpha
            // Alpha-matched dark halo keeps names legible when the sheet fades out
            // during a scrub and they float directly over the live visual.
            textPaint.setShadowLayer(shadowRadius, 0f, 0f, (alpha * 3 / 4) shl 24)
            val fm = textPaint.fontMetrics
            canvas.drawText(name, cx, y - (fm.ascent + fm.descent) / 2f, textPaint)
        }
    }

    companion object {
        private const val ANGLE_STEP = 0.18f     // radians between rows (wheel curvature / spacing)
        private const val EDGE_ANGLE = 1.45f    // ~83°, just past the visible rim
        private const val BASE_FRAC = 0.105f    // hero text size as a fraction of height
        private const val FIT_FRAC = 0.88f      // max name width as a fraction of view width
        private const val SHADOW_DP = 5f        // legibility halo behind names
    }
}
