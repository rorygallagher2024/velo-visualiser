package com.lowlatency.visualizer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.lowlatency.visualizer.R
import kotlin.math.max
import kotlin.math.min

/**
 * The menu's section selector — the three tabs (Visuals · Lighting · Settings)
 * shown *statically* so every section is always visible, with a rounded pill
 * that glides smoothly beneath the active one. Tap a tab to select it; the pill
 * also follows when the section changes by a content swipe.
 *
 * Disabled sections (e.g. Lighting under system audio) draw greyed and ignore taps.
 */
class SectionTabsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var items: List<String> = emptyList()
    var onSelect: ((Int) -> Unit)? = null
    var disabled: Set<Int> = emptySet()
        set(value) { field = value; invalidate() }

    private var active = 0
    private var pillPos = 0f            // animated 0..(n-1)
    private var pillAnim: ValueAnimator? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.inter) ?: Typeface.DEFAULT
        letterSpacing = 0.09f
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        alpha = 26
    }
    private val primary = ContextCompat.getColor(context, R.color.text_primary)
    private val pillRect = RectF()

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (items.isEmpty()) return false
                val idx = (e.x / (width.toFloat() / items.size)).toInt().coerceIn(0, items.size - 1)
                if (idx !in disabled) select(idx)
                return true
            }
        },
    )

    fun setItems(labels: List<String>, activeIndex: Int) {
        items = labels
        active = activeIndex.coerceIn(0, max(labels.size - 1, 0))
        pillPos = active.toFloat()
        invalidate()
    }

    /** Move to [index] without firing [onSelect] (external sync). */
    fun setActive(index: Int) {
        val p = index.coerceIn(0, max(items.size - 1, 0))
        if (p == active) return
        active = p
        animatePillTo(p)
    }

    private fun select(index: Int) {
        active = index
        animatePillTo(index)
        onSelect?.invoke(index)
    }

    private fun animatePillTo(index: Int) {
        pillAnim?.cancel()
        pillAnim = ValueAnimator.ofFloat(pillPos, index.toFloat()).apply {
            duration = 260L
            addUpdateListener { pillPos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty() || width == 0) return
        val slot = width.toFloat() / items.size
        val cy = height / 2f

        // Sliding pill under the active tab.
        val pad = slot * 0.06f
        val cxPill = (pillPos + 0.5f) * slot
        val ph = height * 0.82f
        pillRect.set(cxPill - slot / 2f + pad, cy - ph / 2f, cxPill + slot / 2f - pad, cy + ph / 2f)
        val r = ph / 2f
        canvas.drawRoundRect(pillRect, r, r, pillPaint)

        // Labels.
        textPaint.textSize = height * 0.30f
        val fm = textPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        for (i in items.indices) {
            val cx = (i + 0.5f) * slot
            // Bright when active/near the pill; dimmer otherwise; greyed if disabled.
            val near = 1f - min(kotlin.math.abs(pillPos - i), 1f)
            val lit = 0.55f + 0.45f * near
            val dim = if (i in disabled) 0.35f else 1f
            textPaint.color = primary
            textPaint.alpha = (255f * lit * dim).toInt().coerceIn(30, 255)
            canvas.drawText(items[i].uppercase(), cx, baseline, textPaint)
        }
    }
}
