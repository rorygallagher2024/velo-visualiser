package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * "Spectrum Analyser" — the third instrument in the meter family, alongside the
 * Mechanical Meter and the Level Meter. Where they answer *how loud*, this answers
 * *where the energy is*, in the same voice: white on black, one moving element per
 * reading, no decoration.
 *
 * Thirty-one bands, because that is the third-octave standard a real RTA uses
 * rather than a number picked to fill the screen. Each band carries a slender
 * column and a hairline cap that holds its recent maximum and then falls away
 * under gravity, exactly like the Level Meter's peak marker. The caps are the
 * whole point of the instrument: they turn a wall of moving bars into something
 * you can actually read, because the shape of a mix stays legible for a moment
 * after the transient that made it has gone.
 *
 * Ballistics are peak-programme, matching the Level Meter: snap up, glide down. A
 * bar has no mass to overshoot with, so nothing here springs.
 *
 * The columns and caps are monochrome, and stay that way: there is no per-band
 * equivalent of clipping, so a loud band is just a loud band. Overload is a
 * whole-signal, time-domain event on the summed waveform, which is why it gets its
 * own lamp above the scale — shared with the other two instruments via
 * [MeterCalibration.overLit] — rather than recolouring thirty-one markers that
 * measure something else.
 *
 * It takes the **stereo** ring purely to see that raw signal: the mono analysis
 * ring is scaled and AGC'd for digital sources, so full scale there is not full
 * scale. The samples are otherwise unused; bands come from the shared FFT.
 */
class SpectrumAnalyserScene : StereoScene {

    // Instrument readout — honest representation of the signal, no beat punch.
    override val respondsToBeat get() = false

    private var program = 0
    private var aPos = 0
    private var uRes = 0
    private var uTime = 0
    private var uDim = 0
    private var uLevel = 0
    private var uPeak = 0
    private var uOver = 0

    private var width = 1f
    private var height = 1f

    private val level = FloatArray(BANDS)
    private val peak = FloatArray(BANDS)
    private val peakVel = FloatArray(BANDS)
    private val peakHold = FloatArray(BANDS)

    private val calibration = MeterCalibration()
    private var idleGlow = 1f
    private var silentSec = 0f
    private var lastTime = -1f

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uRes = GLES20.glGetUniformLocation(program, "u_res")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uLevel = GLES20.glGetUniformLocation(program, "u_level")
        uPeak = GLES20.glGetUniformLocation(program, "u_peak")
        uOver = GLES20.glGetUniformLocation(program, "u_over")
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    /** Never called: the renderer dispatches [drawStereo] for a [StereoScene]. */
    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) = Unit

    override fun onAudioSourceChanged() {
        calibration.reset()
    }

    override fun drawStereo(pcmStereo: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.1f)
        lastTime = timeSec

        val loudest = advanceBands(dt)
        updateIdle(loudest, dt)
        calibration.updateOverload(pcmStereo, 2, dt)

        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uRes, width, height)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim * idleGlow)
        GLES20.glUniform1fv(uLevel, BANDS, level, 0)
        GLES20.glUniform1fv(uPeak, BANDS, peak, 0)
        GLES20.glUniform1f(uOver, calibration.overLit)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    /**
     * Fold the shared 128-bin spectrum down into the 31 bands and advance each.
     * The source bins are already log-spaced, so a proportional grouping lands
     * close to third-octave centres without a second frequency map. Bands take the
     * loudest bin they cover rather than the mean: an analyser should show the peak
     * in a band, and averaging buries a narrow tone under its quiet neighbours.
     *
     * @return the loudest band, for the idle-standby check.
     */
    private fun advanceBands(dt: Float): Float {
        val mags = SpectrumData.magnitudes
        var loudest = 0f
        for (b in 0 until BANDS) {
            val lo = b * mags.size / BANDS
            val hi = max((b + 1) * mags.size / BANDS, lo + 1)
            var m = 0f
            for (i in lo until hi) {
                if (mags[i] > m) m = mags[i]
            }
            if (m > loudest) loudest = m
            advanceBand(b, m, dt)
        }
        return loudest
    }

    /** Peak-programme ballistics, then the cap: hold its high water mark, then fall. */
    private fun advanceBand(b: Int, target: Float, dt: Float) {
        val rate = if (target >= level[b]) ATTACK_RATE else RELEASE_RATE
        level[b] += (target - level[b]) * min(rate * dt, 1f)

        val shown = level[b]
        if (shown >= peak[b]) {
            peak[b] = shown
            peakVel[b] = 0f
            peakHold[b] = PEAK_HOLD_SEC
        } else if (peakHold[b] > 0f) {
            peakHold[b] -= dt
        } else {
            peakVel[b] += PEAK_GRAVITY * dt
            peak[b] = max(shown, peak[b] - peakVel[b] * dt)
        }
    }

    /** A few seconds of silence dims the instrument to a resting glow. */
    private fun updateIdle(loudest: Float, dt: Float) {
        silentSec = if (loudest < IDLE_SILENCE_LEVEL) silentSec + dt else 0f
        val glowTarget = if (silentSec > IDLE_AFTER_SEC) IDLE_GLOW else 1f
        val glowRate = if (glowTarget > idleGlow) IDLE_WAKE_RATE else IDLE_FALL_RATE
        idleGlow += (glowTarget - idleGlow) * min(glowRate * dt, 1f)
    }

    companion object {
        private const val BANDS = 31                  // third-octave, 20 Hz … 20 kHz

        private const val ATTACK_RATE = 60f           // ~17 ms: effectively instant
        private const val RELEASE_RATE = 4f           // ~250 ms glide back down
        private const val PEAK_HOLD_SEC = 0.6f
        private const val PEAK_GRAVITY = 1.6f         // scale-units per second²

        private const val IDLE_SILENCE_LEVEL = 0.02f
        private const val IDLE_AFTER_SEC = 3f
        private const val IDLE_GLOW = 0.4f
        private const val IDLE_FALL_RATE = 1.2f
        private const val IDLE_WAKE_RATE = 9f

        private const val VS = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FS = """#version 300 es
            precision highp float;
            uniform vec2  u_res;
            uniform float u_time, u_dim, u_over;
            uniform float u_level[$BANDS];
            uniform float u_peak[$BANDS];
            out vec4 fragColor;

            const vec3 RED = vec3(1.0, 0.27, 0.16);

            const int   BANDS     = $BANDS;
            const float BASE_FRAC = 0.10;    // baseline height, fraction of screen
            const float TOP_FRAC  = 0.90;    // full-scale height
            const float MARGIN    = 0.055;   // side margin, fraction of width
            const float BAR_FILL  = 0.60;    // column width within its cell
            const float AA        = 1.2;     // edge softness, in pixels

            float sdRoundBox(vec2 p, vec2 b, float r) {
                vec2 d = abs(p) - b + r;
                return min(max(d.x, d.y), 0.0) + length(max(d, 0.0)) - r;
            }

            void main() {
                // Everything is measured in pixels so the anti-alias width can be a
                // constant. fwidth() is unusable here: the band index comes from a
                // floor(), which jumps a whole cell at every boundary and would
                // scatter stray lines along the column edges.
                vec2 p = gl_FragCoord.xy;
                // OLED burn-in protection: the instrument drifts on a slow orbit.
                p -= vec2(
                    sin(u_time * 0.31) + 0.5 * sin(u_time * 0.13 + 1.7),
                    cos(u_time * 0.27) + 0.5 * cos(u_time * 0.19 + 0.9)
                ) * 1.5;

                float baseY  = u_res.y * BASE_FRAC;
                float span   = u_res.y * TOP_FRAC - baseY;
                float x0     = u_res.x * MARGIN;
                float fieldW = u_res.x * (1.0 - 2.0 * MARGIN);
                float cellW  = fieldW / float(BANDS);
                float halfW  = cellW * BAR_FILL * 0.5;

                vec3 col = vec3(0.0);

                // Whisper baseline: grounds the columns without drawing a frame.
                float dBase = sdRoundBox(p - vec2(x0 + fieldW * 0.5, baseY),
                                         vec2(fieldW * 0.5, 0.6), 0.5);
                col += vec3(0.055) * smoothstep(AA, -AA, dBase);

                int bi = int(floor((p.x - x0) / cellW));
                if (bi >= 0 && bi < BANDS) {
                    float cx = x0 + (float(bi) + 0.5) * cellW;

                    // The column, rounded at the crest, collapsing to a sliver at rest.
                    float hh = max(u_level[bi] * span * 0.5, 0.0);
                    float dBar = sdRoundBox(p - vec2(cx, baseY + hh),
                                            vec2(halfW, hh), min(halfW * 0.6, hh));
                    col += vec3(1.0) * smoothstep(AA, -AA, dBar) * 1.2;

                    // The cap: dimmer than the column so it reads as memory, not
                    // level. Stays white whatever happens — no band is ever the
                    // thing that clipped.
                    float dPk = sdRoundBox(p - vec2(cx, baseY + u_peak[bi] * span),
                                           vec2(halfW, 1.1), 0.9);
                    col += vec3(0.34) * smoothstep(AA, -AA, dPk);
                }

                // The one red element: overload on the input as a whole, in the same
                // lamp the meter and the level meter use.
                float dLamp = sdRoundBox(p - vec2(u_res.x * 0.5, u_res.y * 0.945),
                                         u_res.y * vec2(0.020, 0.0055), u_res.y * 0.0045);
                col += RED * smoothstep(AA, -AA, dLamp) * (0.06 + 1.25 * u_over);

                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }
}
