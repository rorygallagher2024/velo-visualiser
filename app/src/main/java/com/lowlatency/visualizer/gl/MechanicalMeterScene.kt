package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import com.lowlatency.visualizer.BeatSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Mechanical Meter" — beauty in simplicity: the needle IS the instrument.
 *
 * A single white hairline pivoted below the screen, swaying with true VU
 * ballistics (~300 ms rise, gentle mechanical overshoot, faint tremble under
 * signal). Its motion paints a phosphor wake that relaxes away — the only
 * "art" is the history of the movement. A ghost peak needle is kicked to the
 * maximum, holds, then falls back under gravity like a hi-fi peak pointer.
 * One fixed mark exists on the face: a short signal-red tick at 0 VU; the
 * needle blushes red only when it crosses.
 *
 * Calibration is per source (Unprocessed mic vs the 0.30-scaled digital ring)
 * so loud material rides around the red tick and breakdowns fall away —
 * never pinned. The scale spans -20…+3 VU.
 */
class MechanicalMeterScene : GlScene {

    private var program = 0
    private var aPos = 0
    private var uRes = 0
    private var uDim = 0
    private var uNeedle = 0
    private var uPeak = 0
    private var uWakeLo = 0
    private var uWakeHi = 0
    private var uSheen = 0

    private var width = 1f
    private var height = 1f

    private var needleLevel = 0f
    private var velocity = 0f
    private var peakLevel = 0f
    private var peakVelocity = 0f
    private var peakHold = 0f
    private var wakeLo = 0f
    private var wakeHi = 0f
    private var lastTime = -1f

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uRes = GLES20.glGetUniformLocation(program, "u_res")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uNeedle = GLES20.glGetUniformLocation(program, "u_needle")
        uPeak = GLES20.glGetUniformLocation(program, "u_peak")
        uWakeLo = GLES20.glGetUniformLocation(program, "u_wakeLo")
        uWakeHi = GLES20.glGetUniformLocation(program, "u_wakeHi")
        uSheen = GLES20.glGetUniformLocation(program, "u_sheen")
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.1f)
        lastTime = timeSec

        var sumSq = 0f
        for (s in pcm) sumSq += s * s
        val rms = sqrt(sumSq / pcm.size)

        // dB against a per-source 0 VU reference; the dial spans -20…+3 VU.
        val db = 20f * log10(max(rms, 1e-5f))
        val vu = db - if (BeatSettings.systemAudio) DIGITAL_REF_DB else MIC_REF_DB
        val target = ((vu + VU_FLOOR_ABS) / (VU_FLOOR_ABS + VU_CEIL)).coerceIn(0f, 1f)

        updateMechanics(target, dt, timeSec)

        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uRes, width, height)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uNeedle, needleLevel)
        GLES20.glUniform1f(uPeak, peakLevel)
        GLES20.glUniform1f(uWakeLo, wakeLo)
        GLES20.glUniform1f(uWakeHi, wakeHi)
        GLES20.glUniform1f(uSheen, min(abs(velocity) * 0.35f, 0.5f))

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    /** All the physics: VU-ballistic spring, tremble, phosphor wake, peak fall. */
    private fun updateMechanics(target: Float, dt: Float, timeSec: Float) {
        // True VU ballistics: ~300 ms rise with a whisper of overshoot.
        val force = (target - needleLevel) * STIFFNESS
        velocity += (force - velocity * DAMPING) * dt
        needleLevel = (needleLevel + velocity * dt).coerceIn(0f, 1f)

        // A real movement trembles faintly under signal, on top of the spring.
        val tremble = (sin(timeSec * 123f) + 0.6f * sin(timeSec * 287f)) * 0.002f * target
        val shown = (needleLevel + tremble).coerceIn(0f, 1f)

        // Phosphor wake: the swept band relaxes exponentially toward the needle.
        val relax = 1f - exp(-WAKE_RELAX_PER_SEC * dt)
        wakeLo = min(shown, wakeLo + (shown - wakeLo) * relax)
        wakeHi = max(shown, wakeHi + (shown - wakeHi) * relax)

        // Peak pointer: kicked instantly, holds, then falls under gravity.
        if (shown >= peakLevel) {
            peakLevel = shown
            peakVelocity = 0f
            peakHold = PEAK_HOLD_SEC
        } else if (peakHold > 0f) {
            peakHold -= dt
        } else {
            peakVelocity += PEAK_GRAVITY * dt
            peakLevel = max(shown, peakLevel - peakVelocity * dt)
        }

        needleLevel = shown
    }

    companion object {
        private const val STIFFNESS = 550f            // VU ballistics: ~300 ms rise
        private const val DAMPING = 30f               // zeta ≈ 0.64 — slight overshoot
        private const val WAKE_RELAX_PER_SEC = 3.2f
        private const val PEAK_HOLD_SEC = 0.6f
        private const val PEAK_GRAVITY = 1.6f         // dial-units per second²
        private const val VU_FLOOR_ABS = 20f          // scale spans -20…+3 VU
        private const val VU_CEIL = 3f
        private const val MIC_REF_DB = -45f           // Unprocessed mic, loud room
        private const val DIGITAL_REF_DB = -19f       // 0.30-scaled mastered music

        private const val VS = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FS = """#version 300 es
            precision highp float;
            uniform vec2  u_res;
            uniform float u_dim, u_needle, u_peak, u_wakeLo, u_wakeHi, u_sheen;
            out vec4 fragColor;

            const float PIVOT_DEPTH = 0.45;   // pivot this far below the bottom edge
            const float TIP_Y       = 0.86;   // tip height at centre, in screen heights
            const float RED_START   = 0.8696; // 0 VU as a 0..1 dial fraction
            const vec3  RED         = vec3(1.0, 0.27, 0.16);

            float aaFill(float w, float d) {
                float aa = fwidth(d) + 1e-4;
                return smoothstep(w + aa, w - aa, d);
            }

            float sdSeg(vec2 p, vec2 a, vec2 b) {
                vec2 pa = p - a, ba = b - a;
                float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
                return length(pa - ba * h);
            }

            vec2 dialDir(float level, float sweep) {
                float a = (level * 2.0 - 1.0) * sweep;
                return vec2(sin(a), cos(a));
            }

            void main() {
                // Units of screen height; origin at bottom-centre, y up.
                vec2 q = vec2((gl_FragCoord.x - 0.5 * u_res.x) / u_res.y,
                              gl_FragCoord.y / u_res.y);
                float halfW = 0.5 * u_res.x / u_res.y;

                float L = PIVOT_DEPTH + TIP_Y;              // needle length
                // Sweep adapts so the tip's travel spans ~88% of any screen:
                // wide displays earn the full swing, portrait sways a deep blade.
                float sweep = asin(clamp(0.88 * halfW / L, 0.10, 0.66));

                vec2 pc = q - vec2(0.0, -PIVOT_DEPTH);      // pivot-relative
                float r = length(pc);
                float th = atan(pc.x, pc.y);                // 0 = straight up
                float lvl = (th / sweep + 1.0) * 0.5;       // dial fraction at pixel

                float redMix = smoothstep(RED_START - 0.012, RED_START + 0.012, u_needle);
                vec3 col = vec3(0.0);

                // ----- phosphor wake: the motion paints its own fading fan ------
                float inside = step(u_wakeLo, lvl) * step(lvl, u_wakeHi)
                             * step(r, L) * step(0.0, q.y);
                if (inside > 0.5) {
                    float t = lvl < u_needle
                        ? (lvl - u_wakeLo) / max(u_needle - u_wakeLo, 1e-4)
                        : (u_wakeHi - lvl) / max(u_wakeHi - u_needle, 1e-4);
                    t = clamp(t, 0.0, 1.0);
                    float radial = smoothstep(0.02, 0.45, r) * (1.0 - smoothstep(L * 0.97, L, r));
                    col += vec3(0.085) * t * t * radial;
                }

                // ----- the one fixed mark: a signal-red tick at 0 VU ------------
                vec2 d0 = dialDir(RED_START, sweep);
                float dTick = sdSeg(pc, d0 * (L * 0.94), d0 * L);
                col += RED * aaFill(0.0015, dTick) * (0.55 + 0.65 * redMix);

                // ----- peak pointer: ghost hairline, gravity-dropped ------------
                float dPeak = sdSeg(pc, dialDir(u_peak, sweep) * 0.02,
                                        dialDir(u_peak, sweep) * L);
                col += vec3(0.30) * aaFill(0.0009, dPeak);

                // ----- the needle ------------------------------------------------
                vec2 nd = dialDir(u_needle, sweep);
                float dNeedle = sdSeg(pc, nd * 0.02, nd * L);
                float wTaper = mix(0.0021, 0.0011, clamp(r / L, 0.0, 1.0));
                float blade = aaFill(wTaper, dNeedle);
                vec3 bladeCol = mix(vec3(1.0), RED, redMix);
                col += bladeCol * blade * (1.35 + u_sheen);

                // a tiny luminous tip, so the reading has a focal point
                float dTip = length(pc - nd * L);
                col += bladeCol * aaFill(0.006, dTip - 0.002) * 0.9;

                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }
}
