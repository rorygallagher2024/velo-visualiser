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
        val availableW = MeasureSpec.getSize(widthMeasureSpec)
        val availableH = MeasureSpec.getSize(heightMeasureSpec)
        
        // Target 320dp max width for high-end look on tablets/foldables
        val maxW = (resources.displayMetrics.density * 320).toInt()
        val buffer = (resources.displayMetrics.density * 24).toInt()
        
        // Calculate dimensions based on width first
        var targetW = availableW.coerceAtMost(maxW)
        var targetH = ((targetW.toFloat() / 36f) * 1.7f * 6.0f).toInt() + (buffer * 2)
        
        // If height is too tall for screen (landscape/foldable), scale down to fit height
        if (targetH > availableH && availableH > 0) {
            targetH = availableH
            // Back-calculate width to maintain ASCII aspect ratio (36 cols, 6 rows, ~1.7 ratio)
            val usableH = targetH - (buffer * 2)
            targetW = ((usableH.toFloat() / 6.0f / 1.7f) * 36f).toInt()
        }
        
        setMeasuredDimension(targetW, targetH)
    }

    override fun onDraw(canvas: Canvas) {
        if (asciiText.isEmpty()) return

        paint.color = ResourcesCompat.getColor(resources, R.color.accent, null)
        paint.typeface = ResourcesCompat.getFont(context, R.font.space_mono_bold)

        val lines = asciiText.split("\n")
        val cols = 36
        val charWidth = width.toFloat() / cols
        
        // Fit font to grid cell width precisely
        var fontSize = charWidth / 0.58f 
        paint.textSize = fontSize
        while (paint.measureText("W") > charWidth && fontSize > 1f) {
            fontSize -= 0.1f
            paint.textSize = fontSize
        }
        
        val metrics = paint.fontMetrics
        val charHeight = metrics.descent - metrics.ascent
        
        val blockHeight = lines.size * charHeight
        var y = (height - blockHeight) / 2f - metrics.ascent

        for (line in lines) {
            val length = line.length.coerceAtMost(cols)
            for (i in 0 until length) {
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
