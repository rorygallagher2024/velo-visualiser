package com.lowlatency.visualizer.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.log10
import androidx.core.content.res.ResourcesCompat
import com.lowlatency.visualizer.R

class StudioAnalyzerScene(private val context: Context) : GlScene {

    private var quadProgram = 0
    private var lineProgram = 0
    private var quadVbo = 0
    private var lineVbo = 0
    private var textTextureId = 0

    // Quad Uniforms
    private var uDimQuad = 0
    private var uFft = 0
    private var uTextTexture = 0
    private var aPositionQuad = 0

    // Line Uniforms
    private var uDimLine = 0
    private var aPositionLine = 0

    // Native text rendering
    private lateinit var textBitmap: Bitmap
    private lateinit var textCanvas: Canvas
    private lateinit var textPaint: Paint
    private lateinit var valuePaint: Paint
    private lateinit var panelPaint: Paint
    private lateinit var linePaint: Paint
    private var lastTextUpdate = -1f
    private var texW = 1080
    private var texH = 2400

    private val vertexShaderQuad = """
        #version 300 es
        in vec4 a_Position;
        out vec2 v_uv;
        void main() {
            gl_Position = a_Position;
            v_uv = a_Position.xy * 0.5 + 0.5;
        }
    """.trimIndent()

    private val fragmentShaderQuad = """
        #version 300 es
        precision highp float;

        uniform sampler2D u_textTexture;
        uniform float u_fft[64];
        uniform float u_dim;

        in vec2 v_uv;
        out vec4 fragColor;

        void main() {
            vec2 uv = v_uv;
            
            // Pure Black Studio Background
            vec3 bgTop = vec3(0.0, 0.0, 0.0);
            vec3 bgBot = vec3(0.0, 0.0, 0.0);
            vec3 col = vec3(0.0);
            
            // Sleek Spectrum Graph (bottom 70% of screen)
            float graphY = uv.y / 0.7;
            if(graphY <= 1.0) {
                float bin = uv.x * 63.0;
                int idx = int(bin);
                float t = fract(bin);
                
                // Perfect Catmull-Rom spline interpolation for buttery smooth curves
                float p0 = u_fft[max(idx-1, 0)];
                float p1 = u_fft[idx];
                float p2 = u_fft[min(idx+1, 63)];
                float p3 = u_fft[min(idx+2, 63)];
                
                float t2 = t * t;
                float t3 = t2 * t;
                float val = 0.5 * (
                    (2.0 * p1) +
                    (-p0 + p2) * t +
                    (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
                    (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
                );
                
                // Curve logarithmic scale and safety bound
                val = pow(max(0.0, val), 0.7) * 0.85; 
                
                float dist = abs(graphY - val);
                float line = smoothstep(0.008, 0.001, dist);
                float fill = smoothstep(val, val - 0.02, graphY) * 0.2;
                
                // Premium Cyan/Teal Studio color
                vec3 specColor = mix(vec3(0.0, 0.85, 1.0), vec3(0.0, 1.0, 0.6), uv.x);
                col += specColor * (line + fill);
                
                // Very subtle log-scale grid
                float gridX = step(fract(uv.x * 20.0), 0.005) * 0.03;
                col += vec3(1.0) * gridX * smoothstep(val, val - 0.1, graphY);
            }
            
            // Horizontal reference lines
            float hGrid = step(fract(uv.y * 10.0), 0.002) * 0.04;
            col += vec3(1.0) * hGrid;

            // Text Overlay Overlay (Top Layer)
            // Flip Y for Android Bitmap coordinates
            vec4 texColor = texture(u_textTexture, vec2(uv.x, 1.0 - uv.y));
            col = mix(col, texColor.rgb, texColor.a);
            
            fragColor = vec4(col * u_dim, 1.0);
        }
    """.trimIndent()

    private val vertexShaderLine = """
        #version 300 es
        in vec2 a_Position; // x is -1..1, y is raw amplitude
        void main() {
            vec2 pos = a_Position;
            // Center waveform horizontally in the dashboard space
            pos.y = pos.y * 0.12 + 0.75; 
            gl_Position = vec4(pos, 0.0, 1.0);
        }
    """.trimIndent()

    private val fragmentShaderLine = """
        #version 300 es
        precision highp float;
        uniform float u_dim;
        out vec4 fragColor;
        void main() {
            // Warm amber thin line for the raw waveform
            fragColor = vec4(1.0, 0.75, 0.15, 0.95) * u_dim; 
        }
    """.trimIndent()

    private val quadCoords = floatArrayOf(
        -1f, -1f,
         1f, -1f,
        -1f,  1f,
         1f,  1f
    )
    
    private val lineBuffer: FloatBuffer

    init {
        val bb = ByteBuffer.allocateDirect(4096 * 2 * 4)
        bb.order(ByteOrder.nativeOrder())
        lineBuffer = bb.asFloatBuffer()
    }

    override fun onCreated() {
        quadProgram = ShaderUtil.buildProgram(vertexShaderQuad, fragmentShaderQuad)
        uDimQuad = GLES30.glGetUniformLocation(quadProgram, "u_dim")
        uFft = GLES30.glGetUniformLocation(quadProgram, "u_fft")
        uTextTexture = GLES30.glGetUniformLocation(quadProgram, "u_textTexture")
        aPositionQuad = GLES30.glGetAttribLocation(quadProgram, "a_Position")

        lineProgram = ShaderUtil.buildProgram(vertexShaderLine, fragmentShaderLine)
        uDimLine = GLES30.glGetUniformLocation(lineProgram, "u_dim")
        aPositionLine = GLES30.glGetAttribLocation(lineProgram, "a_Position")

        val buffers = IntArray(2)
        GLES30.glGenBuffers(2, buffers, 0)
        quadVbo = buffers[0]
        lineVbo = buffers[1]

        val qb = ByteBuffer.allocateDirect(quadCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        qb.put(quadCoords).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, qb.capacity() * 4, qb, GLES30.GL_STATIC_DRAW)

        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        textTextureId = texIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textTextureId)
        // Ensure high-quality filtering
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        
        textBitmap = Bitmap.createBitmap(texW, texH, Bitmap.Config.ARGB_8888)
        textCanvas = Canvas(textBitmap)
        
        val satoshi = ResourcesCompat.getFont(context, R.font.satoshi_regular)
        val clash = ResourcesCompat.getFont(context, R.font.clash_display_medium)
        
        textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#99AAB5") // Sleek silver
            typeface = satoshi ?: Typeface.DEFAULT
        }
        valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = clash ?: Typeface.DEFAULT_BOLD
        }
        panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0C1014") // Very dark, barely transparent
            alpha = 240
        }
        linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            strokeWidth = 3f
        }
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        lastTextUpdate = -1f
        if (width > 0 && height > 0) {
            // Allocate bitmap exactly to screen resolution for 1:1 pixel perfection
            // Caps at 2400 to prevent OOM on absurdly high res tablets, but mostly 1:1
            val maxRes = 2400f
            val scale = minOf(1f, maxRes / maxOf(width, height))
            texW = (width * scale).toInt()
            texH = (height * scale).toInt()
            
            textBitmap = Bitmap.createBitmap(texW, texH, Bitmap.Config.ARGB_8888)
            textCanvas = Canvas(textBitmap)
            
            // Dynamic massive text sizing
            textPaint.textSize = texH * 0.025f
            valuePaint.textSize = texH * 0.08f
        }
    }

    private var smoothedRms = -100f
    private var smoothedPeak = -100f
    private var smoothedBass = 0f
    private var smoothedMid = 0f
    private var smoothedHigh = 0f

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        // --- 1. Audio Analysis ---
        var sumSquares = 0f
        var peak = 0f
        
        val samples = minOf(1024, pcm.size)
        for (i in 0 until samples) {
            val s = pcm[i]
            val absS = abs(s)
            sumSquares += s * s
            if (absS > peak) peak = absS
        }
        
        val rms = kotlin.math.sqrt(sumSquares / samples.toDouble()).toFloat()
        val rmsDb = 20f * log10(maxOf(rms, 0.00001f))
        val peakDb = 20f * log10(maxOf(peak, 0.00001f))
        
        val isSilent = rmsDb < -50f

        // Calculate custom band averages from the 128-bin spectrum for more dynamic UI (avoids native clamping)
        var bassSum = 0f
        for (i in 0 until 4) bassSum += SpectrumData.magnitudes[i]
        val rawBass = if (isSilent) 0f else minOf(1f, bassSum / 4f)

        var midSum = 0f
        for (i in 4 until 32) midSum += SpectrumData.magnitudes[i]
        // Mids are typically denser, apply a slight scale for visual balance
        val rawMid = if (isSilent) 0f else minOf(1f, (midSum / 28f) * 1.5f)

        var highSum = 0f
        for (i in 32 until 128) highSum += SpectrumData.magnitudes[i]
        // Highs require a boost to read well dynamically
        val rawHigh = if (isSilent) 0f else minOf(1f, (highSum / 96f) * 2.5f)

        // Professional Metering Ballistics
        // RMS uses very slow EMA for readable, stable loudness (like short-term LUFS)
        smoothedRms += (rmsDb - smoothedRms) * 0.02f
        
        // Bands use moderate EMA
        smoothedBass += (rawBass - smoothedBass) * 0.1f
        smoothedMid += (rawMid - smoothedMid) * 0.1f
        smoothedHigh += (rawHigh - smoothedHigh) * 0.1f

        // Peak uses Instant Attack, Extremely Slow Fall (True Peak Hold)
        if (peakDb > smoothedPeak) {
            smoothedPeak = peakDb
        } else {
            smoothedPeak -= 0.05f // Very slow falloff (~3dB per second at 60fps)
        }
        
        // --- 2. Update Text Overlay (Throttled to ~10 FPS for rock-solid readability) ---
        if (timeSec - lastTextUpdate > 0.1f) {
            lastTextUpdate = timeSec
            
            textCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            
            // Typography as a Spectacle
            val pX = texW * 0.06f
            val pY = texH * 0.12f
            val labelSpacing = texH * 0.09f
            
            // Limit values for clean display
            val dispRms = maxOf(-99.9f, smoothedRms)
            val dispPeak = maxOf(-99.9f, smoothedPeak)
            
            // RMS
            textCanvas.drawText("RMS LOUDNESS", pX, pY, textPaint)
            textCanvas.drawText(String.format("%.1f dB", dispRms), pX, pY + labelSpacing, valuePaint)
            
            // PEAK
            val peakX = texW * 0.55f
            textCanvas.drawText("TRUE PEAK", peakX, pY, textPaint)
            textCanvas.drawText(String.format("%.1f dB", dispPeak), peakX, pY + labelSpacing, valuePaint)
            
            // BANDS (Prominent row below)
            val bY = texH * 0.32f
            val bSpacing = texW * 0.3f
            
            textCanvas.drawText("BASS", pX, bY, textPaint)
            textCanvas.drawText(String.format("%02d", (smoothedBass*100).toInt()), pX, bY + labelSpacing, valuePaint)
            
            textCanvas.drawText("MID", pX + bSpacing, bY, textPaint)
            textCanvas.drawText(String.format("%02d", (smoothedMid*100).toInt()), pX + bSpacing, bY + labelSpacing, valuePaint)
            
            textCanvas.drawText("HIGH", pX + bSpacing * 2, bY, textPaint)
            textCanvas.drawText(String.format("%02d", (smoothedHigh*100).toInt()), pX + bSpacing * 2, bY + labelSpacing, valuePaint)

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textTextureId)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, textBitmap, 0)
        }

        // --- 3. Draw Background Quad (Spectrum + Text) ---
        GLES30.glUseProgram(quadProgram)
        GLES30.glUniform1f(uDimQuad, dim)

        val fft64 = FloatArray(64)
        for (i in 0 until 64) {
            fft64[i] = SpectrumData.magnitudes[i * 2]
        }
        GLES30.glUniform1fv(uFft, 64, fft64, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textTextureId)
        GLES30.glUniform1i(uTextTexture, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(aPositionQuad)
        GLES30.glVertexAttribPointer(aPositionQuad, 2, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPositionQuad)

        // --- 4. Draw Waveform Line ---
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniform1f(uDimLine, dim)

        lineBuffer.clear()
        val step = 2.0f / (samples - 1)
        for (i in 0 until samples) {
            lineBuffer.put(-1f + i * step) // X
            lineBuffer.put(pcm[i])         // Y
        }
        lineBuffer.position(0)
        
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, samples * 2 * 4, lineBuffer, GLES30.GL_DYNAMIC_DRAW)
        
        GLES30.glEnableVertexAttribArray(aPositionLine)
        GLES30.glVertexAttribPointer(aPositionLine, 2, GLES30.GL_FLOAT, false, 0, 0)
        
        GLES30.glLineWidth(3.0f)
        GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, samples)
        GLES30.glDisableVertexAttribArray(aPositionLine)
    }
}
