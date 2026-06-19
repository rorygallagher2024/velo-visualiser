package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Teenage-Engineering-inspired visual — "Mechanical Meter".
 * 
 * A minimalist analog-style needle meter. High-precision line art and 
 * physically-modeled needle ballistics. 
 *
 * Aesthetics:
 * - Off-white "ink" lines on a warm charcoal background.
 * - Sharp, antialiased needle with smooth damping.
 * - Minimalist scale with numeric indicators.
 */
class MechanicalMeterScene : GlScene {

    companion object {
        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform vec2  u_resolution;
            uniform float u_dim;
            uniform float u_needle_pos; // 0..1 (left to right)
            uniform float u_peak_pos;   // 0..1
            out vec4 fragColor;

            // Teenage Engineering signature colors
            const vec3 COLOR_BACKGROUND = vec3(0.106, 0.102, 0.09); // #1B1A17
            const vec3 COLOR_INK        = vec3(0.945, 0.933, 0.902); // #F1EEE6
            const vec3 COLOR_ACCENT     = vec3(0.941, 0.325, 0.11);  // #F0531C

            float sdLine(vec2 p, vec2 a, vec2 b) {
                vec2 pa = p - a, ba = b - a;
                float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
                return length(pa - ba * h);
            }

            void main() {
                vec2 uv = (gl_FragCoord.xy * 2.0 - u_resolution.xy) / min(u_resolution.y, u_resolution.x);
                
                // Centering and scaling the meter arc - lowered for more presence
                vec2 center = vec2(0.0, -1.0);
                float radius = 1.6;
                
                // Arc / Scale
                float distToCenter = length(uv - center);
                float circle = abs(distToCenter - radius);
                
                // Angle of current pixel relative to bottom center
                float angle = atan(uv.x - center.x, uv.y - center.y);
                
                // Limit to the meter's active range (-35 to 35 deg for tighter look)
                float range = 0.6;
                bool inRange = abs(angle) < range + 0.05;
                
                vec3 col = COLOR_BACKGROUND;

                // Subtle grid background (TE engineering style)
                vec2 grid = fract(uv * 10.0);
                float gridLine = (smoothstep(0.02, 0.0, grid.x) + smoothstep(0.02, 0.0, grid.y)) * 0.1;
                col += COLOR_INK * gridLine * 0.2;

                // Main Scale Line
                float scaleLine = smoothstep(0.012, 0.0, circle) * float(inRange);
                col = mix(col, COLOR_INK * 0.3, scaleLine);

                // Ticks - more frequent and varied lengths
                for(float i = -1.0; i <= 1.05; i += 0.1) {
                    float tickAngle = i * range;
                    bool isMajor = abs(fract(i * 5.0 + 0.5) - 0.5) < 0.1;
                    float tLen = isMajor ? 0.08 : 0.04;
                    vec2 p1 = center + vec2(sin(tickAngle), cos(tickAngle)) * (radius - tLen);
                    vec2 p2 = center + vec2(sin(tickAngle), cos(tickAngle)) * (radius + tLen);
                    float d = sdLine(uv, p1, p2);
                    col = mix(col, COLOR_INK, smoothstep(0.006, 0.002, d));
                }

                // The Needle - double needle for a technical look
                float needleAngle = (u_needle_pos * 2.0 - 1.0) * range;
                vec2 needleTip = center + vec2(sin(needleAngle), cos(needleAngle)) * (radius + 0.15);
                float dNeedle = sdLine(uv, center, needleTip);
                
                // Needle base glow
                col += COLOR_ACCENT * smoothstep(0.08, 0.0, dNeedle) * 0.15;
                
                // Sharp Needle
                float needleMask = smoothstep(0.01, 0.004, dNeedle);
                col = mix(col, COLOR_ACCENT, needleMask);

                // Peak Indicator (small orange dot on the arc)
                float peakAngle = (u_peak_pos * 2.0 - 1.0) * range;
                vec2 pPeak = center + vec2(sin(peakAngle), cos(peakAngle)) * radius;
                float dPeak = length(uv - pPeak);
                float peakMask = smoothstep(0.025, 0.015, dPeak);
                col = mix(col, COLOR_ACCENT * 1.5, peakMask);

                // Hub (the pivot point)
                float hub = length(uv - center);
                col = mix(col, COLOR_INK, smoothstep(0.12, 0.11, hub));
                col = mix(col, COLOR_BACKGROUND, smoothstep(0.10, 0.09, hub));
                col = mix(col, COLOR_ACCENT, smoothstep(0.04, 0.03, hub));

                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    private var program = 0
    private var aPos = 0
    private var uResolution = 0
    private var uDim = 0
    private var uNeedlePos = 0
    private var uPeakPos = 0

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private var width = 1f
    private var height = 1f
    
    // Ballistics
    private var currentLevel = 0f
    private var velocity = 0f
    private var peakLevel = 0f
    private var lastTime = -1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uNeedlePos = GLES20.glGetUniformLocation(program, "u_needle_pos")
        uPeakPos = GLES20.glGetUniformLocation(program, "u_peak_pos")
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.1f)
        lastTime = timeSec

        // Calculate RMS level from PCM
        var sumSq = 0f
        for (s in pcm) sumSq += s * s
        val rms = sqrt(sumSq / pcm.size)
        
        // --- GAIN & SENSITIVITY TWEAKS ---
        // RMS is typically very low (0.001 to 0.05). 
        // We need much more gain and a nonlinear curve to make it "dance".
        // Use a logarithmic-style boost to make quiet sounds move the needle.
        val gain = 175f
        val targetLevel = (rms * gain).let { if (it < 0.01f) 0f else sqrt(it) }.coerceIn(0f, 1.2f)
        
        // --- BALLISTICS TWEAKS ---
        // Increased stiffness and damping for a faster, more "snappy" response
        // while maintaining the mechanical bounce.
        val stiffness = 350f
        val damping = 18f
        val force = (targetLevel - currentLevel) * stiffness
        val accel = force - velocity * damping
        velocity += accel * dt
        currentLevel += velocity * dt
        currentLevel = currentLevel.coerceIn(0f, 1.2f)

        // Peak hold
        if (currentLevel > peakLevel) peakLevel = currentLevel
        else peakLevel = max(0f, peakLevel - dt * 0.4f)

        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uResolution, width, height)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uNeedlePos, currentLevel / 1.2f)
        GLES20.glUniform1f(uPeakPos, peakLevel / 1.2f)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
