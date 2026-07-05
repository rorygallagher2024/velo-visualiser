package com.lowlatency.visualizer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.lowlatency.visualizer.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The menu's section selector — the tabs (Visuals · Lighting · Settings) as a
 * horizontal sliding label carousel that echoes the vertical scene wheel. Drag
 * or fling to page between sections, tap a peeking neighbour to jump to it; the
 * active label is centred, bright and large, neighbours shrink and dim.
 *
 * Disabled sections (e.g. Lighting under system audio) draw greyed and can't be
 * landed on — a snap or tap onto one lands on the nearest enabled section.
 */
class SectionWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var items: List<String> = emptyList()
    var onSelect: ((Int) -> Unit)? = null
    var disabled: Set<Int> = emptySet()
        set(value) { field = value; invalidate() }

    private var scrollPx = 0f
    private var spacing = 300f
    private var activePos = 0

    private val scroller = OverScroller(context)
    private var flinging = false
    private var dragging = false

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.inter) ?: Typeface.DEFAULT
        letterSpacing = 0.14f
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
                parent?.requestDisallowInterceptTouchEvent(true)
                scrollPx = (scrollPx + dx).coerceIn(0f, maxScroll())
                invalidate(); return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                dragging = false; flinging = true
                scroller.fling(scrollPx.toInt(), 0, (-vx).toInt(), 0, 0, maxScroll().toInt(), 0, 0)
                invalidate(); return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val tapPos = ((scrollPx + (e.x - width / 2f)) / spacing).roundToInt().coerceIn(0, lastIndex())
                animateTo(nearestEnabled(tapPos), notify = true); return true
            }
        },
    )

    fun setItems(labels: List<String>, active: Int) {
        items = labels
        activePos = active.coerceIn(0, max(labels.size - 1, 0))
        scrollPx = activePos * spacing
        invalidate()
    }

    /** Move to [index] without firing [onSelect] (external sync, e.g. a redirect). */
    fun setActive(index: Int) {
        val p = index.coerceIn(0, lastIndex())
        if (p == activePos) return
        animateTo(p, notify = false)
    }

    private fun lastIndex() = max(items.size - 1, 0)
    private fun maxScroll() = lastIndex() * spacing

    private fun nearestEnabled(pos: Int): Int {
        if (pos !in disabled) return pos
        for (d in 1..items.size) {
            if (pos - d >= 0 && (pos - d) !in disabled) return pos - d
            if (pos + d <= lastIndex() && (pos + d) !in disabled) return pos + d
        }
        return pos
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        spacing = w * 0.42f
        scrollPx = activePos * spacing
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPx = scroller.currX.toFloat(); invalidate()
        } else if (flinging) {
            flinging = false; snap()
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dragging && !flinging) { dragging = false; snap() }
        }
        return true
    }

    private fun snap() =
        animateTo(nearestEnabled((scrollPx / spacing).roundToInt().coerceIn(0, lastIndex())), notify = true)

    private fun animateTo(pos: Int, notify: Boolean) {
        val target = (pos * spacing).toInt()
        scroller.startScroll(scrollPx.toInt(), 0, target - scrollPx.toInt(), 0, 300)
        flinging = false
        activePos = pos
        if (notify) onSelect?.invoke(pos)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty() || width == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val base = height * 0.44f
        for (i in items.indices) {
            val dx = i * spacing - scrollPx
            val d = abs(dx) / spacing
            if (d > 1.6f) continue
            val t = min(d, 1.6f) / 1.6f
            val dim = if (i in disabled) 0.35f else 1f
            textPaint.textSize = base * (1f - 0.34f * t)
            textPaint.color = primary
            textPaint.alpha = (255f * (1f - 0.72f * t) * dim).toInt().coerceIn(20, 255)
            val fm = textPaint.fontMetrics
            canvas.drawText(items[i].uppercase(), cx + dx, cy - (fm.ascent + fm.descent) / 2f, textPaint)
        }
    }
}
