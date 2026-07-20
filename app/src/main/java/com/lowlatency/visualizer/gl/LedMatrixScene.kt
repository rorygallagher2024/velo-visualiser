package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * "Pocket LED" — a dot-matrix spectrum panel with the Mechanical Meter's polish.
 *
 * Borrows the meter's *craft* rather than its palette: honest instrument physics,
 * an idle standby and the shared OLED burn-in orbit, expressed as a grid of LED
 * segments. The colour is the iconic hardware-meter ladder — green low, amber
 * mid, red hot up each column — and the app's global theme grade (Neon / Warm /
 * Cool / Mono) re-tints the whole panel in the composite.
 *
 * Each column is a bottom-anchored bar with **VU-style ballistics** (fast attack,
 * smooth release) and a single peak-hold cell that hovers, holds, then falls back
 * under gravity like a hi-fi peak pointer. Unlit cells glow faintly so the whole
 * matrix reads as a physical panel; the crest and the peak cap run HDR > 1 so
 * they bloom. After a few seconds of silence the panel dims to a resting glow and
 * snaps awake on the first signal.
 *
 * One full-screen fragment pass in normalized [0,1] space; the ballistics ride a
 * COLS x 2 float texture (row0 = bar level, row1 = gravity peak).
 */
class LedMatrixScene : GlScene {

    // Instrument readout — honest representation of the signal, no beat punch.
    override val respondsToBeat get() = false

    companion object {
        private const val COLS = 24
        private const val ROWS = 14

        private const val RELEASE_TAU = 0.14f      // bar snaps up instantly, eases down this fast
        private const val PEAK_HOLD_SEC = 0.7f     // peak cell hovers, then falls
        private const val PEAK_GRAVITY = 1.1f      // dial-fractions per second²
        private const val IDLE_SILENCE = 0.02f     // level that counts as silence
        private const val IDLE_AFTER_SEC = 3f
        private const val IDLE_GLOW = 0.4f          // resting brightness while idle
        private const val IDLE_FALL_RATE = 1.2f     // ease into idle (~1.5 s)
        private const val IDLE_WAKE_RATE = 9f       // snap awake (~0.15 s)

        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform vec2  u_resolution;
            uniform float u_time;
            uniform float u_dim;
            uniform sampler2D u_spectrum;     // COLS x 2 : row0 bar, row1 peak
            out vec4 fragColor;

            const float COLS_F = float(${COLS});
            const float ROWS_F = float(${ROWS});
            const float MARGIN = 0.16;        // gap between LED segments
            const float CORNER = 0.12;        // segment corner radius (cell units)

            // Classic LED VU colours by height: green low, amber mid, red hot. The
            // global theme grade (Neon/Warm/Cool/Mono) re-tints the lot downstream in
            // the composite, so this is just the default, ungraded ladder.
            const vec3 GREEN = vec3(0.15, 1.0, 0.30);
            const vec3 AMBER = vec3(1.0, 0.72, 0.10);
            const vec3 RED   = vec3(1.0, 0.18, 0.13);
            const vec3 PEAK_TINT = vec3(1.0, 0.95, 0.85);   // warm near-white the peak cap leans toward

            float sdRoundBox(vec2 p, vec2 b, float r) {
                vec2 d = abs(p) - b + r;
                return min(max(d.x, d.y), 0.0) + length(max(d, 0.0)) - r;
            }

            // Fixed colour zones up the column, with a hair of blend so the
            // boundaries anti-alias instead of banding hard.
            vec3 ledColor(float rowFrac) {
                vec3 c = mix(GREEN, AMBER, smoothstep(0.56, 0.60, rowFrac));
                return mix(c, RED, smoothstep(0.80, 0.84, rowFrac));
            }

            void main() {
                vec2 uv = gl_FragCoord.xy / u_resolution;   // y=0 at bottom
                // OLED burn-in protection: the whole panel drifts a few pixels on a
                // slow orbit, matching the meter and the scopes.
                uv += vec2(sin(u_time * 0.31) + 0.5 * sin(u_time * 0.13 + 1.7),
                           cos(u_time * 0.27) + 0.5 * cos(u_time * 0.19 + 0.9)) * 0.0012;

                vec2 grid = vec2(COLS_F, ROWS_F);
                vec2 f = uv * grid;
                vec2 ci = floor(f);
                if (any(lessThan(ci, vec2(0.0))) || any(greaterThanEqual(ci, grid))) {
                    fragColor = vec4(0.0, 0.0, 0.0, 1.0); return;
                }

                // Rounded, anti-aliased LED segment inside the cell.
                vec2 cell = fract(f) - 0.5;
                float d = sdRoundBox(cell, vec2(0.5 - MARGIN), CORNER);
                float aa = fwidth(d) + 1e-4;
                float shape = smoothstep(aa, -aa, d);
                if (shape < 0.001) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }

                float u    = (ci.x + 0.5) / COLS_F;
                float bar  = texture(u_spectrum, vec2(u, 0.25)).r;
                float peak = texture(u_spectrum, vec2(u, 0.75)).r;

                float rowFrac = (ci.y + 0.5) / ROWS_F;
                // Fractional fill: whole cells below the crest are full, the tip cell
                // fades by the remainder, so the bar top is smooth, not steppy.
                float fill = clamp(bar * ROWS_F - ci.y, 0.0, 1.0);
                float peakRow = floor(peak * ROWS_F - 0.001);
                float isPeak  = step(abs(ci.y - peakRow), 0.001) * step(0.02, peak);

                // Green/amber/red ladder, brightening toward the crest (HDR > 1 blooms).
                vec3 zone = ledColor(rowFrac);
                vec3 litCol = zone * (0.85 + rowFrac * 1.4);

                vec3 col = zone * 0.035;                      // faint resting glow, keeps the zone tint
                col = mix(col, litCol, fill);
                // Peak cap: gently distinct — a paler, slightly hotter shade of the
                // bar's own colour. Kept modest so the channels don't all clamp past
                // 1.0 and wash the cap out to flat white.
                col = mix(col, mix(zone, PEAK_TINT, 0.25) * 1.7, isPeak);

                fragColor = vec4(col * shape * u_dim, 1.0);
            }
        """
    }

    private val upload: FloatBuffer = ByteBuffer
        .allocateDirect(COLS * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    // Per-column ballistics (Kotlin side, honest instrument motion).
    private val barLevel = FloatArray(COLS)
    private val peakLevel = FloatArray(COLS)
    private val peakHold = FloatArray(COLS)
    private val peakVel = FloatArray(COLS)
    private var idleGlow = 1f
    private var silentSec = 0f
    private var lastTime = -1f

    private var program = 0
    private var aPos = 0
    private var uResolution = 0
    private var uTime = 0
    private var uDim = 0
    private var uSpectrum = 0

    private var specTex = 0
    private var width = 1f
    private var height = 1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uSpectrum = GLES20.glGetUniformLocation(program, "u_spectrum")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        specTex = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, specTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R32F,
            COLS, 2, 0, GLES30.GL_RED, GLES20.GL_FLOAT, null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.1f)
        lastTime = timeSec

        val loudest = updateBallistics(dt)
        updateIdle(loudest, dt)
        uploadLevels()

        GLES20.glDisable(GLES20.GL_BLEND)    // opaque full-screen pass
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, specTex)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, COLS, 2,
            GLES30.GL_RED, GLES20.GL_FLOAT, upload
        )
        GLES20.glUniform1i(uSpectrum, 0)
        GLES20.glUniform2f(uResolution, width, height)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim * idleGlow)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    /** Advance each column's bar (fast attack, smooth release) and its gravity peak;
     *  returns the loudest raw column, for the idle detector. */
    private fun updateBallistics(dt: Float): Float {
        val mags = SpectrumData.magnitudes
        val fallRate = 1f - exp(-dt / RELEASE_TAU)
        var loudest = 0f
        for (i in 0 until COLS) {
            val lo = i * 128 / COLS
            val n = ((i + 1) * 128 / COLS - lo).coerceAtLeast(1)
            var sum = 0f
            for (j in lo until lo + n) sum += mags[j.coerceAtMost(127)]
            val raw = sum / n
            loudest = max(loudest, raw)
            // Instant attack (LEDs snap straight up to the signal), smooth release.
            barLevel[i] = if (raw >= barLevel[i]) raw else barLevel[i] + (raw - barLevel[i]) * fallRate
            advancePeak(i, dt)
        }
        return loudest
    }

    /** Peak cell: kicked up instantly, holds, then falls under gravity. */
    private fun advancePeak(i: Int, dt: Float) {
        if (barLevel[i] >= peakLevel[i]) {
            peakLevel[i] = barLevel[i]; peakVel[i] = 0f; peakHold[i] = PEAK_HOLD_SEC
        } else if (peakHold[i] > 0f) {
            peakHold[i] -= dt
        } else {
            peakVel[i] += PEAK_GRAVITY * dt
            peakLevel[i] = max(barLevel[i], peakLevel[i] - peakVel[i] * dt)
        }
    }

    /** Dim to a resting glow after a few seconds of silence, snap awake on signal. */
    private fun updateIdle(loudest: Float, dt: Float) {
        silentSec = if (loudest < IDLE_SILENCE) silentSec + dt else 0f
        val glowTarget = if (silentSec > IDLE_AFTER_SEC) IDLE_GLOW else 1f
        val glowRate = if (glowTarget > idleGlow) IDLE_WAKE_RATE else IDLE_FALL_RATE
        idleGlow += (glowTarget - idleGlow) * min(glowRate * dt, 1f)
    }

    private fun uploadLevels() {
        upload.clear()
        for (i in 0 until COLS) upload.put(barLevel[i])
        for (i in 0 until COLS) upload.put(peakLevel[i])
        upload.position(0)
    }
}
