package com.lowlatency.visualizer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

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

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Auto-size the time to a fixed fraction of the width — a hero on any device.
        floorPaint.textSize = REFERENCE_TEXT_PX
        val measured = floorPaint.measureText(timeText)
        val size = if (measured > 0f) REFERENCE_TEXT_PX * (w * TIME_WIDTH_FRAC / measured) else REFERENCE_TEXT_PX
        floorPaint.textSize = size
        strokePaint.textSize = size
        strokePaint.strokeWidth = size * STROKE_FRAC
        subPaint.textSize = size * SUB_RATIO

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
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        canvas.drawColor(maskColor)
        canvas.drawText(timeText, cx, timeBaseline, floorPaint)
        canvas.restoreToCount(layer)

        // Crisp contour over the composited result — legible even on a black scene.
        canvas.drawText(timeText, cx, timeBaseline, strokePaint)

        // Subline: plain light text over the near-black field.
        if (subText.isNotEmpty()) {
            canvas.drawText(subText, cx, top + timeH + gap - subFm.ascent, subPaint)
        }
    }

    companion object {
        private const val DEFAULT_MASK = 0xCC0000000.toInt()   // ~88% black; ~12% scene bleed around the type
        private const val REFERENCE_TEXT_PX = 200f            // measure reference, then rescale
        private const val TIME_WIDTH_FRAC = 0.86f             // hero time fills this much width
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
