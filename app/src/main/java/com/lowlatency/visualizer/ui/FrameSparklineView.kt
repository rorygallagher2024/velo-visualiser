package com.lowlatency.visualizer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.lowlatency.visualizer.R
import kotlin.math.max
import kotlin.math.min

/**
 * Frame-time sparkline for the performance overlay: the last ~3 seconds of
 * frame times as a smooth brand-styled trace over a dim frame-budget hairline
 * (1000 / refresh rate). A stutter reads as a bump, thermal throttling as a
 * slow climb — the difference between a number and an instrument. Over-budget
 * frames get a small amber tick.
 *
 * "Smooth as butter" is deliberate, on three fronts: pushed values are lightly
 * EMA-smoothed (the 20 Hz sample of a 120 fps stream is otherwise aliased
 * noise); the trace is drawn as a midpoint-quadratic curve with a soft gradient
 * fill, not a jagged polyline; and the vertical scale *eases* toward its target
 * so the whole trace never jumps when a spike scrolls off the edge.
 */
class FrameSparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val samples = FloatArray(CAP)
    private var head = 0
    private var count = 0
    private var smoothed = 0f
    private var scaleMs = 16f

    /** Frame budget in ms (1000 / display refresh rate); set by the controller. */
    var budgetMs = 8.33f

    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = context.getColor(R.color.text_primary)
        alpha = 225
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val budgetPaint = Paint().apply {
        strokeWidth = 1f
        color = context.getColor(R.color.text_dim)
        alpha = 80
    }
    private val overPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AMBER }

    private val path = Path()
    private val fillPath = Path()

    /** Append one frame-time sample (ms), lightly smoothed to tame sampling noise. */
    fun push(ms: Float) {
        val v = ms.coerceIn(0f, 60f)
        smoothed = if (count == 0) v else smoothed + (v - smoothed) * SMOOTH
        samples[head] = smoothed
        head = (head + 1) % CAP
        count = min(count + 1, CAP)
        invalidate()
    }

    fun reset() {
        count = 0
        head = 0
        smoothed = 0f
        scaleMs = budgetMs * 1.8f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            (context.getColor(R.color.text_primary) and 0x00FFFFFF) or 0x33000000, // ~20% at top
            context.getColor(R.color.text_primary) and 0x00FFFFFF,                 // transparent at base
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || count < 2) return

        // Ease the vertical scale toward its target (budget always visible, spikes
        // always in frame) so the trace never jumps when peaks enter/leave.
        var peak = 0f
        for (i in 0 until count) peak = max(peak, samples[(head - 1 - i + CAP * 2) % CAP])
        val target = max(budgetMs * 1.8f, peak * 1.12f)
        scaleMs += (target - scaleMs) * SCALE_EASE
        fun y(v: Float) = h - (v / scaleMs) * (h - PAD_TOP) - 1f

        // The frame-budget hairline.
        val by = y(budgetMs)
        canvas.drawLine(0f, by, w, by, budgetPaint)

        // Build a smooth midpoint-quadratic curve through the samples.
        val n = count
        val step = w / (CAP - 1)
        val x0 = w - (n - 1) * step
        path.rewind()
        var prevX = x0
        var prevY = y(samples[(head - n + CAP) % CAP])
        path.moveTo(prevX, prevY)
        for (i in 1 until n) {
            val cx = x0 + i * step
            val cy = y(samples[(head - n + i + CAP) % CAP])
            val mx = (prevX + cx) * 0.5f
            val my = (prevY + cy) * 0.5f
            path.quadTo(prevX, prevY, mx, my)
            prevX = cx
            prevY = cy
        }
        path.lineTo(prevX, prevY)

        // Soft gradient fill under the curve, then the stroke on top.
        fillPath.set(path)
        fillPath.lineTo(prevX, h)
        fillPath.lineTo(x0, h)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, tracePaint)

        // A small amber tick on any over-budget frame (usually none → clean line).
        val r = 1.6f * resources.displayMetrics.density
        for (i in 0 until n) {
            val v = samples[(head - n + i + CAP) % CAP]
            if (v > budgetMs * OVER_FACTOR) canvas.drawCircle(x0 + i * step, y(v), r, overPaint)
        }
    }

    companion object {
        private const val CAP = 64            // ~3.2 s at the 20 Hz ticker
        private const val SMOOTH = 0.5f       // EMA on pushed values (tames aliasing)
        private const val SCALE_EASE = 0.12f  // vertical-scale easing (~0.4 s)
        private const val OVER_FACTOR = 1.3f  // budget overshoot that earns a tick
        private const val PAD_TOP = 4f
        private const val AMBER = 0xFFFFBB33.toInt()
    }
}
