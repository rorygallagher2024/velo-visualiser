package com.lowlatency.visualizer.gl

import android.opengl.GLES20
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
 * One red element exists on the face, and it is the overload lamp sitting just past
 * full deflection. The needle itself stays white at every angle. Red reports an
 * *event* here and in the Level Meter both, never a position: earlier revisions put
 * a red tick at 0 VU and blushed the needle as it passed, but 0 VU is where music
 * lives, so a raised voice or a full-scale tone reddened a meter that was nowhere
 * near clipping. Both instruments now read [MeterCalibration.overLit], so they
 * cannot disagree about what warrants red.
 *
 * The dial is absolute for the same reason, via [MeterCalibration]: the floor
 * auto-ranges to the room (sensitivity, so a quiet mic still sweeps the needle)
 * while the top stays pinned at 0 dBFS (headroom). An earlier revision moved the
 * top with the signal too, which made full deflection mean nothing more than
 * "louder than lately".
 *
 * It reads the **stereo** ring rather than the mono analysis ring, because dBFS
 * has to mean dBFS: digital sources enter the mono ring scaled by
 * `kDigitalMonoGain` and a time-varying AGC, so the needle would drift with the
 * gain rather than the music. The stereo ring is kept full scale, and mono sources
 * upmix into it, so it is the honest feed on every source.
 */
class MechanicalMeterScene : StereoScene {

    private var program = 0
    private var aPos = 0
    private var uRes = 0
    private var uTime = 0
    private var uDim = 0
    private var uNeedle = 0
    private var uPeak = 0
    private var uWakeLo = 0
    private var uWakeHi = 0
    private var uWakeEnergy = 0
    private var uSheen = 0
    private var uOver = 0

    private var width = 1f
    private var height = 1f

    private var needleLevel = 0f
    private val calibration = MeterCalibration()
    private var velocity = 0f
    private var peakLevel = 0f
    private var peakVelocity = 0f
    private var peakHold = 0f
    private var wakeLo = 0f
    private var wakeHi = 0f
    private var wakeEnergy = 0f
    private var silentSec = 0f
    private var idleGlow = 1f
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
        uNeedle = GLES20.glGetUniformLocation(program, "u_needle")
        uPeak = GLES20.glGetUniformLocation(program, "u_peak")
        uWakeLo = GLES20.glGetUniformLocation(program, "u_wakeLo")
        uWakeHi = GLES20.glGetUniformLocation(program, "u_wakeHi")
        uWakeEnergy = GLES20.glGetUniformLocation(program, "u_wakeEnergy")
        uSheen = GLES20.glGetUniformLocation(program, "u_sheen")
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

        // Mono sum of the full-scale stereo ring, matching the engine's convention.
        var sumSq = 0f
        var frames = 0
        var i = 0
        while (i + 1 < pcmStereo.size) {
            val m = (pcmStereo[i] + pcmStereo[i + 1]) * 0.5f
            sumSq += m * m
            frames++
            i += 2
        }
        val rms = sqrt(sumSq / frames.coerceAtLeast(1))

        val db = 20f * log10(max(rms, 1e-5f))
        calibration.update(db, dt)
        calibration.updateOverload(pcmStereo, 2, dt)
        val target = calibration.position(db)

        updateMechanics(target, dt, timeSec)

        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uRes, width, height)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim * idleGlow)
        GLES20.glUniform1f(uNeedle, needleLevel)
        GLES20.glUniform1f(uPeak, peakLevel)
        GLES20.glUniform1f(uWakeLo, wakeLo)
        GLES20.glUniform1f(uWakeHi, wakeHi)
        GLES20.glUniform1f(uWakeEnergy, wakeEnergy)
        GLES20.glUniform1f(uSheen, min(abs(velocity) * 0.35f, 0.5f))
        GLES20.glUniform1f(uOver, calibration.overLit)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    /** All the physics: VU-ballistic spring, tremble, phosphor wake, peak fall. */
    private fun updateMechanics(target: Float, dt: Float, timeSec: Float) {
        // True VU ballistics: ~300 ms rise with a whisper of overshoot.
        // Integrated in sub-steps: explicit Euler at this stiffness is only
        // stable below ~50 ms steps, so a dropped frame (dt clamps at 100 ms)
        // would otherwise kick the needle into a divergent oscillation.
        var remaining = dt
        while (remaining > 0f) {
            val h = min(remaining, MAX_STEP_SEC)
            val force = (target - needleLevel) * STIFFNESS
            velocity += (force - velocity * DAMPING) * h
            val unclamped = needleLevel + velocity * h
            needleLevel = unclamped.coerceIn(0f, 1f)
            // End stop: a real needle halts dead against the pin rather than
            // storing phantom velocity that would delay its release.
            if (needleLevel != unclamped) velocity = 0f
            remaining -= h
        }

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

        // The wake remembers how HARD the needle swept, not just where: fast
        // swings burn bright, slow drift barely marks the fan.
        wakeEnergy = max(wakeEnergy - dt * WAKE_ENERGY_DECAY, min(abs(velocity) * 0.5f, 1f))

        // Idle: a few seconds of true silence and the instrument dims to a
        // resting glow, snapping awake on the first signal.
        silentSec = if (target < IDLE_SILENCE_LEVEL) silentSec + dt else 0f
        val glowTarget = if (silentSec > IDLE_AFTER_SEC) IDLE_GLOW else 1f
        val glowRate = if (glowTarget > idleGlow) IDLE_WAKE_RATE else IDLE_FALL_RATE
        idleGlow += (glowTarget - idleGlow) * min(glowRate * dt, 1f)

        needleLevel = shown
    }

    companion object {
        private const val STIFFNESS = 550f            // VU ballistics: ~300 ms rise
        private const val DAMPING = 30f               // zeta ≈ 0.64 — slight overshoot
        private const val WAKE_RELAX_PER_SEC = 3.2f
        private const val WAKE_ENERGY_DECAY = 1.2f    // sweep-brightness fade, per second
        private const val PEAK_HOLD_SEC = 0.6f
        private const val PEAK_GRAVITY = 1.6f         // dial-units per second²
        private const val IDLE_SILENCE_LEVEL = 0.02f  // dial fraction that counts as silence
        private const val IDLE_AFTER_SEC = 3f
        private const val IDLE_GLOW = 0.4f            // resting brightness while idle
        private const val IDLE_FALL_RATE = 1.2f       // ease into idle (~1.5 s)
        private const val IDLE_WAKE_RATE = 9f         // snap awake (~0.15 s)
        // Dial scale and overload live in MeterCalibration, shared with the Level
        // Meter so the two instruments read the same signal the same way.

        private const val MAX_STEP_SEC = 0.016f       // spring integrator sub-step

        private const val VS = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FS = """#version 300 es
            precision highp float;
            uniform vec2  u_res;
            uniform float u_time, u_dim, u_needle, u_peak, u_wakeLo, u_wakeHi, u_over;
            uniform float u_wakeEnergy, u_sheen;
            out vec4 fragColor;

            const float PIVOT_DEPTH = 0.45;   // pivot this far below the bottom edge
            const float TIP_Y       = 0.86;   // tip height at centre, in screen heights
            const float LAMP_AT     = 1.04;   // just past full deflection, never covered
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
                // OLED burn-in protection: the whole instrument (pivot, tick,
                // blade) drifts a few pixels on a slow orbit, like the scopes.
                q += vec2(
                    sin(u_time * 0.31) + 0.5 * sin(u_time * 0.13 + 1.7),
                    cos(u_time * 0.27) + 0.5 * cos(u_time * 0.19 + 0.9)
                ) * 0.0015;
                float halfW = 0.5 * u_res.x / u_res.y;

                float L = PIVOT_DEPTH + TIP_Y;              // needle length
                // Sweep adapts so the tip's travel spans ~88% of any screen:
                // wide displays earn the full swing, portrait sways a deep blade.
                float sweep = asin(clamp(0.88 * halfW / L, 0.10, 0.66));

                vec2 pc = q - vec2(0.0, -PIVOT_DEPTH);      // pivot-relative
                float r = length(pc);
                float th = atan(pc.x, pc.y);                // 0 = straight up
                float lvl = (th / sweep + 1.0) * 0.5;       // dial fraction at pixel

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
                    // Lit by sweep speed: a violent swing burns a bright fan,
                    // slow drift barely marks it.
                    col += vec3(0.03 + 0.10 * u_wakeEnergy) * t * t * radial;
                }

                // ----- the one red element: the overload lamp -------------------
                // Sited past the end of travel rather than at some fraction of it,
                // because it reports an event, not a position. Dormant it is barely
                // an ember; on a genuine overload it latches bright.
                vec2 d0 = dialDir(LAMP_AT, sweep);
                float dTick = sdSeg(pc, d0 * (L * 0.94), d0 * L);
                col += RED * aaFill(0.0015, dTick) * (0.06 + 1.25 * u_over);

                // ----- peak pointer: ghost hairline, gravity-dropped ------------
                float dPeak = sdSeg(pc, dialDir(u_peak, sweep) * 0.02,
                                        dialDir(u_peak, sweep) * L);
                col += vec3(0.30) * aaFill(0.0009, dPeak);

                // ----- the needle ------------------------------------------------
                vec2 nd = dialDir(u_needle, sweep);
                float dNeedle = sdSeg(pc, nd * 0.02, nd * L);
                float wTaper = mix(0.0021, 0.0011, clamp(r / L, 0.0, 1.0));
                float blade = aaFill(wTaper, dNeedle);
                // White at every deflection, like the Level Meter's columns: the
                // needle reports level, and level is never by itself a fault.
                vec3 bladeCol = vec3(1.0);
                col += bladeCol * blade * (1.35 + u_sheen);

                // a tiny luminous tip, so the reading has a focal point
                float dTip = length(pc - nd * L);
                col += bladeCol * aaFill(0.006, dTip - 0.002) * 0.9;

                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }
}
