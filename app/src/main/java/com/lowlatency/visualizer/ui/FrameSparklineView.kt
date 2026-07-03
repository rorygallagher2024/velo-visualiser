package com.lowlatency.visualizer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.lowlatency.visualizer.R
import kotlin.math.max
import kotlin.math.min

/**
 * Frame-time sparkline for the performance overlay: the last ~3 seconds of
 * frame times as a thin brand-styled trace over a dim frame-budget hairline
 * (1000 / refresh rate). A stutter reads as a spike, jank as a sawtooth,
 * thermal throttling as a slow climb — the difference between a number and an
 * instrument. Segments that blow past the budget turn amber.
 *
 * Fed by [PerfOverlayController]'s 20 Hz ticker via [push]; the vertical scale
 * adapts to the window's peak (never below ~1.8× budget) so spikes stay in
 * frame without the trace jittering.
 */
class FrameSparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val samples = FloatArray(CAP)
    private var head = 0
    private var count = 0

    /** Frame budget in ms (1000 / display refresh rate); set by the controller. */
    var budgetMs = 8.33f

    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.text_primary)
        alpha = 200
    }
    private val overPaint = Paint(tracePaint).apply { color = AMBER }
    private val budgetPaint = Paint().apply {
        strokeWidth = 1f
        color = context.getColor(R.color.text_dim)
        alpha = 90
    }

    /** Append one frame-time sample (ms) and redraw. */
    fun push(ms: Float) {
        samples[head] = ms.coerceIn(0f, 60f)
        head = (head + 1) % CAP
        count = min(count + 1, CAP)
        invalidate()
    }

    fun reset() {
        count = 0
        head = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || count < 2) return

        // Adaptive vertical scale: budget always visible, spikes always in frame.
        var peak = 0f
        for (i in 0 until count) peak = max(peak, samples[(head - 1 - i + CAP * 2) % CAP])
        val maxV = max(budgetMs * 1.8f, peak * 1.1f)
        fun y(v: Float) = h - (v / maxV) * (h - PAD_TOP) - 1f

        // The frame-budget hairline — the line the trace should live under.
        val by = y(budgetMs)
        canvas.drawLine(0f, by, w, by, budgetPaint)

        // Trace, oldest -> newest; over-budget segments switch to amber.
        val n = count
        val step = w / (CAP - 1)
        var px = w - (n - 1) * step
        var pv = samples[(head - n + CAP) % CAP]
        for (i in 1 until n) {
            val v = samples[(head - n + i + CAP) % CAP]
            val x = px + step
            canvas.drawLine(px, y(pv), x, y(v), if (v > budgetMs * OVER_FACTOR) overPaint else tracePaint)
            px = x
            pv = v
        }
    }

    companion object {
        private const val CAP = 64            // ~3.2 s at the 20 Hz ticker
        private const val OVER_FACTOR = 1.25f // budget overshoot that turns amber
        private const val PAD_TOP = 3f
        private const val AMBER = 0xFFFFBB33.toInt()
    }
}
