package com.lowlatency.visualizer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * "Window into the music" clock for Ambient Mode.
 *
 * The screen washes to near-black EXCEPT the letterforms of the time, through which
 * the live, full-colour GL visualiser shows — the time appears made of the moving
 * visuals. A pure cutout, though, vanishes wherever the scene behind is dark or
 * sparse (an oscilloscope line, bars lit only at the bottom, a mostly-black scene),
 * because a 2D overlay can only reveal the GL — it can't give a glyph any light of
 * its own. So each glyph is guaranteed legibility on *any* scene by three layers:
 *
 *   1. the near-black MASK frames the type on bright scenes;
 *   2. a faint luminance FLOOR inside the letters (drawn SRC into the masked layer,
 *      so the glyph interior composites to a low alpha) keeps them readable on dark
 *      scenes while still letting the visuals show through and brighten them;
 *   3. a crisp white CONTOUR stroke fixes the digit shape even over pure black.
 *
 * The date/BPM subline is plain readable text over the mask (not a cutout — it only
 * needs to be legible). Burn-in drift is an internal draw offset (see [setShift]).
 */
class CutoutClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // Glyph interior: SRC replaces the opaque mask with a low-alpha white, so on
    // restore the interior is (floor white) over GL — a luminance floor + window.
    private val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        textAlign = Paint.Align.CENTER
        letterSpacing = TIME_TRACKING
        color = floorWhite(FLOOR_ALPHA)
    }
    // Crisp outline, drawn OVER the composited result so it reads on any background.
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
        letterSpacing = TIME_TRACKING
        color = floorWhite(STROKE_ALPHA)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        letterSpacing = SUB_TRACKING
        color = floorWhite(SUB_ALPHA)
    }

    private var timeText = "00:00"
    private var subText = ""
    private var shiftX = 0f
    private var shiftY = 0f
    private var pulse = 0f              // beat envelope: the contour breathes with it
    private var flipAt = 0L             // minute-flip flare clock (uptimeMillis)
    private var vignette: Paint? = null
    private var vigW = 0f
    private var vigH = 0f

    /** Near-opaque black so a faint ghost of the scene bleeds around the type. */
    var maskColor: Int = DEFAULT_MASK
        set(value) {
            field = value
            invalidate()
        }

    fun setTypefaces(timeTf: Typeface?, subTf: Typeface?) {
        floorPaint.typeface = timeTf
        strokePaint.typeface = timeTf
        subPaint.typeface = subTf
        invalidate()
    }

    fun setText(time: String, sub: String) {
        if (time == timeText && sub == subText) return
        // A quiet event every minute: the floor lifts so the visuals flare
        // through the fresh digits, then settles (see onDraw).
        if (time != timeText) flipAt = SystemClock.uptimeMillis()
        timeText = time
        subText = sub
        invalidate()
    }

    fun setShift(dx: Float, dy: Float) {
        if (dx == shiftX && dy == shiftY) return
        shiftX = dx
        shiftY = dy
        invalidate()
    }

    /** Beat envelope in — the contour and glyph floor breathe with the music. */
    fun setPulse(env: Float) {
        if (abs(env - pulse) < 0.01f) return
        pulse = env
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val size = resolveTimeSize(w, h)
        floorPaint.textSize = size
        strokePaint.textSize = size
        strokePaint.strokeWidth = size * STROKE_FRAC
        subPaint.textSize = size * SUB_RATIO

        // Living alphas: the contour + floor breathe with the beat, and each
        // minute flip briefly lifts the floor so the visuals flare through the
        // fresh digits before settling.
        val flip = ((SystemClock.uptimeMillis() - flipAt) / FLIP_MS).coerceIn(0f, 1f)
        val lift = (1f - flip) * (1f - flip)
        floorPaint.color =
            floorWhite((FLOOR_ALPHA + pulse * PULSE_FLOOR + lift * FLIP_LIFT).coerceAtMost(0.9f))
        strokePaint.color = floorWhite((STROKE_ALPHA + pulse * PULSE_STROKE).coerceAtMost(0.95f))

        val fm = floorPaint.fontMetrics
        val timeH = fm.descent - fm.ascent
        val subFm = subPaint.fontMetrics
        val subH = if (subText.isEmpty()) 0f else subFm.descent - subFm.ascent
        val gap = if (subText.isEmpty()) 0f else timeH * SUB_GAP
        val top = (h - (timeH + gap + subH)) / 2f + shiftY
        val cx = w / 2f + shiftX
        val timeBaseline = top - fm.ascent

        // Mask + window-with-floor for the time. The layer isolates the SRC carve so
        // the glyph interior ends up at the floor alpha (revealing the GL behind).
        // The mask itself is vignetted: corners fall to pure black, framing the type.
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        canvas.drawRect(0f, 0f, w, h, vignettePaint(w, h))
        canvas.drawText(timeText, cx, timeBaseline, floorPaint)
        canvas.restoreToCount(layer)

        // Crisp contour over the composited result — legible even on a black scene.
        canvas.drawText(timeText, cx, timeBaseline, strokePaint)

        // Subline: plain light text over the near-black field.
        if (subText.isNotEmpty()) {
            canvas.drawText(subText, cx, top + timeH + gap - subFm.ascent, subPaint)
        }
    }

    /**
     * Auto-size the time: a fixed fraction of the width, capped so the whole
     * stack (time + gap + subline) also fits the height — in landscape the width
     * is the long axis, and width-only sizing scales the glyphs past the screen
     * and pushes the subline off the bottom.
     */
    private fun resolveTimeSize(w: Float, h: Float): Float {
        floorPaint.textSize = REFERENCE_TEXT_PX
        subPaint.textSize = REFERENCE_TEXT_PX * SUB_RATIO
        val measured = floorPaint.measureText(timeText)
        val widthSize =
            if (measured > 0f) REFERENCE_TEXT_PX * (w * TIME_WIDTH_FRAC / measured) else REFERENCE_TEXT_PX
        // Stack height scales linearly with text size — measure it at the reference.
        val refFm = floorPaint.fontMetrics
        val refTimeH = refFm.descent - refFm.ascent
        val refSubFm = subPaint.fontMetrics
        val refStack = refTimeH +
            if (subText.isEmpty()) 0f else refTimeH * SUB_GAP + (refSubFm.descent - refSubFm.ascent)
        return minOf(widthSize, REFERENCE_TEXT_PX * (h * STACK_HEIGHT_FRAC / refStack))
    }

    /** Vignetted mask: [maskColor] at the centre easing to opaque black corners. */
    private fun vignettePaint(w: Float, h: Float): Paint {
        vignette?.let { if (w == vigW && h == vigH) return it }
        val p = Paint().apply {
            shader = RadialGradient(
                w / 2f, h / 2f, max(w, h) * 0.72f,
                intArrayOf(maskColor, maskColor, OPAQUE_BLACK),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        vignette = p; vigW = w; vigH = h
        return p
    }

    companion object {
        private const val DEFAULT_MASK = 0xCC0000000.toInt()   // ~88% black; ~12% scene bleed around the type
        private const val OPAQUE_BLACK = 0xFF000000.toInt()    // vignette corners: fully dark frame
        private const val FLIP_MS = 600f                       // minute-flip flare duration
        private const val PULSE_FLOOR = 0.10f                  // beat swell on the glyph floor
        private const val PULSE_STROKE = 0.30f                 // beat swell on the contour
        private const val FLIP_LIFT = 0.30f                    // floor lift as digits change
        private const val REFERENCE_TEXT_PX = 200f            // measure reference, then rescale
        private const val TIME_WIDTH_FRAC = 0.86f             // hero time fills this much width
        private const val STACK_HEIGHT_FRAC = 0.62f           // …but the stack never exceeds this height
        private const val TIME_TRACKING = 0.01f
        private const val FLOOR_ALPHA = 0.20f                 // glyph interior luminance floor
        private const val STROKE_ALPHA = 0.50f                // contour brightness
        private const val STROKE_FRAC = 0.018f                // contour width as a fraction of text size
        private const val SUB_ALPHA = 0.55f
        private const val SUB_RATIO = 0.13f                   // subline size relative to time
        private const val SUB_TRACKING = 0.22f
        private const val SUB_GAP = 0.12f                     // gap below time, × time height

        private fun floorWhite(alpha: Float): Int = ((alpha * 255f).toInt() shl 24) or 0x00FFFFFF
    }
}
