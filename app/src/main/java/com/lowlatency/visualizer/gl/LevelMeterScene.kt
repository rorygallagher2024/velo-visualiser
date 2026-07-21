package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * "Level Meter" — the Mechanical Meter's sibling: loudness, not frequency.
 *
 * Three readings:
 *  - The **column** is RMS with peak-programme ballistics: it snaps up and glides
 *    back down. A bar is a PPM, not a VU — the needle next door is the instrument
 *    that is *supposed* to take 300 ms to arrive, because that is what a moving
 *    coil does. Copying its spring here just made the bar feel late.
 *  - The **hairline** is that column's high water mark, held then dropped under
 *    gravity, so the column reaches up and touches it on every peak.
 *  - The **lamp** is overload, and nothing else lights it.
 *
 * Scale and overload both come from [MeterCalibration], so this and the Mechanical
 * Meter can never disagree about what "hot" means. The scale is fixed at -60…0 dBFS
 * with no auto-gain, so a given height always means the same signal level, and a
 * quiet mic is *shown* to be quiet rather than normalised up to look loud. Expect
 * the column to stop short of the top: 0 dBFS RMS is a full-scale square wave, so
 * the last stretch is unreachable by design and stays reserved for peaks.
 *
 * **Mono shows one bar, stereo shows two.** Rather than switching modes (which
 * would pop), it always draws two channels and slides them apart from the centre
 * as real stereo is detected from the data — upmixed rings are bit-identical, so
 * a mono source keeps both columns exactly coincident and reads as a single bar.
 * The channels combine with max(), so the coincident case isn't double-bright.
 *
 * Both channels share one scale, so left and right stay comparable to each other.
 */
class LevelMeterScene : StereoScene {

    // Instrument readout — honest representation of the signal, no beat punch.
    override val respondsToBeat get() = false

    private var program = 0
    private var aPos = 0
    private var uRes = 0
    private var uTime = 0
    private var uDim = 0
    private var uLevelL = 0
    private var uLevelR = 0
    private var uPeakL = 0
    private var uPeakR = 0
    private var uSep = 0
    private var uClip = 0

    private var width = 1f
    private var height = 1f

    // Per-channel mechanics: 0 = left, 1 = right.
    private val level = FloatArray(2)
    private val peak = FloatArray(2)
    private val peakVel = FloatArray(2)
    private val peakHold = FloatArray(2)

    private var stereoMix = 0f          // 0 = coincident (mono), 1 = split apart
    private val calibration = MeterCalibration()
    private var idleGlow = 1f
    private var silentSec = 0f
    private var lastTime = -1f

    // Scratch from the last measure() pass (avoids per-frame allocation).
    private var rmsL = 0f
    private var rmsR = 0f
    private var chDiff = 0f

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uRes = GLES20.glGetUniformLocation(program, "u_res")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uLevelL = GLES20.glGetUniformLocation(program, "u_levelL")
        uLevelR = GLES20.glGetUniformLocation(program, "u_levelR")
        uPeakL = GLES20.glGetUniformLocation(program, "u_peakL")
        uPeakR = GLES20.glGetUniformLocation(program, "u_peakR")
        uSep = GLES20.glGetUniformLocation(program, "u_sep")
        uClip = GLES20.glGetUniformLocation(program, "u_clip")
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    /** Never called: the renderer dispatches [drawStereo] for a [StereoScene]. */
    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) = Unit

    override fun drawStereo(pcmStereo: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.1f)
        lastTime = timeSec

        measure(pcmStereo)

        // Real stereo shows a genuine L/R difference; upmixed rings are
        // bit-identical, so mono sources keep the two columns coincident.
        val stereoTarget = if (chDiff > 1e-4f) 1f else 0f
        stereoMix += (stereoTarget - stereoMix) * STEREO_EASE

        val dbL = 20f * log10(max(rmsL, 1e-5f))
        val dbR = 20f * log10(max(rmsR, 1e-5f))
        // One shared scale, so left and right stay comparable to each other.
        calibration.update(max(dbL, dbR), dt)

        val targetL = calibration.position(dbL)
        val targetR = calibration.position(dbR)
        updateChannel(0, targetL, dt)
        updateChannel(1, targetR, dt)
        updateIdle(calibration.atRest(max(dbL, dbR)), dt)
        calibration.updateOverload(pcmStereo, 2, dt)

        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uRes, width, height)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim * idleGlow)
        GLES20.glUniform1f(uLevelL, level[0])
        GLES20.glUniform1f(uLevelR, level[1])
        GLES20.glUniform1f(uPeakL, peak[0])
        GLES20.glUniform1f(uPeakR, peak[1])
        GLES20.glUniform1f(uSep, STEREO_SEP * stereoMix)
        GLES20.glUniform1f(uClip, calibration.overLit)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    /**
     * Per-channel RMS plus the largest L/R difference (the stereo tell), measured
     * over the most recent [MeterCalibration.RECENT_FRAMES] only.
     *
     * The ring carries ~170 ms at 48 kHz, and averaging all of it costs more
     * responsiveness than any ballistic choice can win back: a transient has to
     * fill the whole window before the RMS reflects it. `readLatest` copies
     * chronologically, so the newest audio is the tail.
     */
    private fun measure(pcmStereo: FloatArray) {
        val total = pcmStereo.size / 2
        val take = min(total, MeterCalibration.RECENT_FRAMES)
        var sumL = 0f
        var sumR = 0f
        var diff = 0f
        var i = (total - take) * 2
        while (i + 1 < pcmStereo.size) {
            val l = pcmStereo[i]
            val r = pcmStereo[i + 1]
            sumL += l * l
            sumR += r * r
            val d = if (l - r < 0f) r - l else l - r
            if (d > diff) diff = d
            i += 2
        }
        val n = take.coerceAtLeast(1)
        rmsL = sqrt(sumL / n)
        rmsR = sqrt(sumR / n)
        chDiff = diff
    }

    /**
     * Peak-programme ballistics for the column, plus the peak hairline: the
     * column's own high water mark, held then dropped under gravity, exactly like
     * the meter's ghost pointer. It must measure the *same thing the column does* —
     * a revision that put true peak up there instead left a line parked permanently
     * above a bar that could never reach it, because the gap was crest factor and
     * every window of music re-armed the hold.
     */
    private fun updateChannel(ch: Int, target: Float, dt: Float) {
        // Snap up, glide down. Asymmetric easing rather than a spring: a bar has no
        // mass to overshoot with, and the attack is the whole point of a peak meter.
        val rate = if (target >= level[ch]) ATTACK_RATE else RELEASE_RATE
        level[ch] += (target - level[ch]) * min(rate * dt, 1f)

        val shown = level[ch]
        if (shown >= peak[ch]) {
            peak[ch] = shown
            peakVel[ch] = 0f
            peakHold[ch] = PEAK_HOLD_SEC
        } else if (peakHold[ch] > 0f) {
            peakHold[ch] -= dt
        } else {
            peakVel[ch] += PEAK_GRAVITY * dt
            peak[ch] = max(shown, peak[ch] - peakVel[ch] * dt)
        }
    }

    override fun onAudioSourceChanged() {
        calibration.reset()
    }

    /** A few seconds of silence dims the instrument to a resting glow. */
    private fun updateIdle(atRest: Boolean, dt: Float) {
        silentSec = if (atRest) silentSec + dt else 0f
        val glowTarget = if (silentSec > IDLE_AFTER_SEC) IDLE_GLOW else 1f
        val glowRate = if (glowTarget > idleGlow) IDLE_WAKE_RATE else IDLE_FALL_RATE
        idleGlow += (glowTarget - idleGlow) * min(glowRate * dt, 1f)
    }

    companion object {
        private const val ATTACK_RATE = 60f           // ~17 ms to arrive: effectively instant
        private const val RELEASE_RATE = 4f           // ~250 ms glide back down
        private const val PEAK_HOLD_SEC = 0.6f
        private const val PEAK_GRAVITY = 1.6f         // scale-units per second²
        private const val STEREO_EASE = 0.02f         // mono↔stereo slide (~1 s)
        private const val STEREO_SEP = 0.082f         // half-separation, in screen heights

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
            uniform float u_time, u_dim;
            uniform float u_levelL, u_levelR, u_peakL, u_peakR, u_sep, u_clip;
            out vec4 fragColor;

            const float BOT   = 0.12;     // scale bottom, in screen heights
            const float TOP   = 0.88;     // scale top
            const float BAR_W = 0.055;    // half-width of a column
            const vec3  RED   = vec3(1.0, 0.27, 0.16);

            float aaFill(float w, float d) {
                float aa = fwidth(d) + 1e-4;
                return smoothstep(w + aa, w - aa, d);
            }

            float sdRoundBox(vec2 p, vec2 b, float r) {
                vec2 d = abs(p) - b + r;
                return min(max(d.x, d.y), 0.0) + length(max(d, 0.0)) - r;
            }

            // One channel: the whisper track it travels, the column, and its peak.
            vec3 channel(vec2 q, float cx, float level, float pk) {
                float span = TOP - BOT;
                vec3 c = vec3(0.0);

                // Whisper track: the scale's extent, feathered away at both ends so
                // it suggests the travel without drawing a frame.
                float dT = sdRoundBox(q - vec2(cx, (BOT + TOP) * 0.5),
                                      vec2(0.0011, span * 0.5), 0.0009);
                float feather = smoothstep(BOT - 0.01, BOT + 0.07, q.y) *
                                (1.0 - smoothstep(TOP - 0.07, TOP + 0.01, q.y));
                c += vec3(0.05) * aaFill(0.0, dT) * feather;

                // The column: white at every height. Level is the whole message here,
                // and the clip lamp is the only thing allowed to say "too much".
                float hh = max((level * span) * 0.5, 0.0);
                float dBar = sdRoundBox(q - vec2(cx, BOT + hh),
                                        vec2(BAR_W, hh), min(BAR_W * 0.55, hh));
                c += vec3(1.0) * aaFill(0.0, dBar) * 1.25;

                // Peak hairline: held, then gravity-dropped.
                float dPk = sdRoundBox(q - vec2(cx, BOT + pk * span),
                                       vec2(BAR_W, 0.0011), 0.0009);
                c += vec3(0.34) * aaFill(0.0, dPk);
                return c;
            }

            void main() {
                // Units of screen height; origin at bottom-centre, y up.
                vec2 q = vec2((gl_FragCoord.x - 0.5 * u_res.x) / u_res.y,
                              gl_FragCoord.y / u_res.y);
                // OLED burn-in protection: the instrument drifts a few pixels on a
                // slow orbit, like the meter and the scopes.
                q += vec2(
                    sin(u_time * 0.31) + 0.5 * sin(u_time * 0.13 + 1.7),
                    cos(u_time * 0.27) + 0.5 * cos(u_time * 0.19 + 0.9)
                ) * 0.0015;

                // max(), not +: when mono the two columns are exactly coincident and
                // must read as one bar, not one at double brightness.
                vec3 col = max(channel(q, -u_sep, u_levelL, u_peakL),
                               channel(q,  u_sep, u_levelR, u_peakR));

                // The clip lamp, centred above the travel so it reads for one column
                // or two. Dormant it is barely an ember; on an overload it latches
                // bright. Saturated red past 1.0 clamps only its own channel, so it
                // stays red rather than blowing out to white with bloom off.
                float dLamp = sdRoundBox(q - vec2(0.0, TOP + 0.032),
                                         vec2(0.020, 0.0055), 0.0045);
                col += RED * aaFill(0.0, dLamp) * (0.06 + 1.25 * u_clip);

                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }
}
