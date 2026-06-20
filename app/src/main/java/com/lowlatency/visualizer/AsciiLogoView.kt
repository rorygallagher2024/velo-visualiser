package com.lowlatency.visualizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat

/**
 * Geometric ASCII renderer that bypasses standard TextView layout.
 *
 * It draws every character to an explicit grid coordinate (36x6), which
 * guarantees 100% alignment regardless of system font scaling, kerning,
 * or sub-pixel "helpful" text adjustments.
 */
class AsciiLogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var asciiText: String = ""
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        
        // Base character size calculation. Space Mono is approx 1.6-1.8x as 
        // tall as it is wide. We calculate height based on the width to 
        // maintain the intended ASCII aspect ratio.
        val charWidth = width.toFloat() / 36f
        val charHeight = charWidth * 1.7f
        
        // Final height: 6 lines of text + a generous 24dp "safety buffer" top/bottom
        // to prevent any external layout from clipping the actual art.
        val buffer = (resources.displayMetrics.density * 24).toInt()
        val height = (charHeight * 6.0f).toInt() + (buffer * 2)
        
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (asciiText.isEmpty()) return

        // Resolve colors and fonts on each draw to respect any theme changes,
        // though these are usually static in this app.
        paint.color = ResourcesCompat.getColor(resources, R.color.accent, null)
        paint.typeface = ResourcesCompat.getFont(context, R.font.space_mono_bold)

        val lines = asciiText.split("\n")
        val cols = 36
        val charWidth = width.toFloat() / cols
        
        // Find the maximum font size that stays within one grid cell width.
        // We measure a wide character ('W') to ensure no overlap.
        var fontSize = charWidth / 0.58f 
        paint.textSize = fontSize
        while (paint.measureText("W") > charWidth && fontSize > 1f) {
            fontSize -= 0.2f
            paint.textSize = fontSize
        }
        
        val metrics = paint.fontMetrics
        val charHeight = metrics.descent - metrics.ascent
        
        // Vertically center the 6-line block within the view height. 
        // Because of our large measurement buffer, the art will be safely 
        // away from the view edges.
        val blockHeight = lines.size * charHeight
        var y = (height - blockHeight) / 2f - metrics.ascent

        for (line in lines) {
            val length = line.length.coerceAtMost(cols)
            for (i in 0 until length) {
                // Draw each character exactly in the center of its grid cell.
                val x = i * charWidth + charWidth / 2f
                val char = line[i].toString()
                if (char != " " && char != "\r") {
                    canvas.drawText(char, x, y, paint)
                }
            }
            y += charHeight
        }
    }
}
