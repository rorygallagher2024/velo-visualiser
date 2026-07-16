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
 * Calibration is per source, because the two source families have opposite
 * problems. Digital sources (system / local / tone) arrive at a consistent,
 * loudness-compressed level, so a fixed -20…+3 VU window fits and only needs
 * to self-raise a touch to tame a hot master. The mic / line input has a
 * wide, low, room- and distance-dependent dynamic range that no fixed window
 * fits — too sensitive and a loud voice pins it, too insensitive and a quiet
 * one floors it — so it auto-ranges: the dial centre follows the recent level
 * (fast up, slower down) and the dial floor rides a tracked noise floor, so
 * quiet still sweeps the needle, loud rides near (never past) the red tick,
 * and whatever this room and mic idle at reads as rest.
 */
class MechanicalMeterScene : GlScene {

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

    private var width = 1f
    private var height = 1f

    private var needleLevel = 0f
    private var refDb = Float.NaN      // digital-source 0 VU reference
    private var micRefDb = Float.NaN   // mic auto-range centre
    private var micNoiseDb = Float.NaN // mic noise-floor estimate (min follower)
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

        val db = 20f * log10(max(rms, 1e-5f))
        val target = if (BeatSettings.systemAudio) {
            // Digital sources (system / local / tone) arrive at a consistent,
            // loudness-compressed level, so a fixed -20…+3 VU window fits — it
            // just self-raises to tame a hot master. (Unchanged: these work.)
            val vu = db - adaptDigitalReference(db, dt)
            ((vu + VU_FLOOR_ABS) / (VU_FLOOR_ABS + VU_CEIL)).coerceIn(0f, 1f)
        } else {
            // Mic / line input has a wide, low, room-dependent dynamic range no
            // fixed window fits — hence auto-ranging.
            micTarget(db, dt)
        }

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

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    /**
     * Digital-source 0 VU: fixed calibration that chases sustained loud level
     * up (so a hot master settles back onto the red tick within a couple of
     * seconds) and relaxes down slowly. Clamped to [base, base + range] so it
     * only ever gets *less* sensitive — digital sources never run quiet.
     */
    private fun adaptDigitalReference(db: Float, dt: Float): Float {
        if (refDb.isNaN()) refDb = DIGITAL_REF_DB
        val tau = if (db > refDb) REF_ATTACK_SEC else REF_RELEASE_SEC
        refDb += (db - refDb) * min(dt / tau, 1f)
        refDb = refDb.coerceIn(DIGITAL_REF_DB, DIGITAL_REF_DB + REF_ADAPT_RANGE_DB)
        return refDb
    }

    /**
     * Mic / line-input dial position by auto-ranging. The reference follows the
     * recent level — up fast to catch loud, down slower to re-centre — so a
     * quiet room becomes sensitive and a loud one gains headroom; neither the
     * old fixed window's floor (quiet pinned to minimum) nor its ceiling (loud
     * pinned to max) can trap the needle. A relative span sets most of the
     * scale, but the dial floor never rises above an absolute silence level, so
     * genuine quiet still rests the needle instead of floating mid-dial.
     */
    private fun micTarget(db: Float, dt: Float): Float {
        if (micRefDb.isNaN()) micRefDb = db.coerceIn(MIC_REF_MIN, MIC_REF_MAX)
        if (micNoiseDb.isNaN()) micNoiseDb = db
        val tau = if (db > micRefDb) MIC_ATTACK_SEC else MIC_RELEASE_SEC
        micRefDb += (db - micRefDb) * min(dt / tau, 1f)
        micRefDb = micRefDb.coerceIn(MIC_REF_MIN, MIC_REF_MAX)

        // Noise-floor minimum follower: latches onto quiet gaps quickly, creeps
        // up only under sustained level. The dial floor rides just above it, so
        // ambient rests the needle (and the idle dim engages) on ANY device —
        // a fixed dB floor either hovers the needle on a hot mic's room tone or
        // deadens a quiet one. Capped at the reference so floor < ceiling even
        // when the reference is pinned at its clamp under prolonged loud input.
        val nTau = if (db < micNoiseDb) NOISE_FALL_SEC else NOISE_RISE_SEC
        micNoiseDb += (db - micNoiseDb) * min(dt / nTau, 1f)
        micNoiseDb = micNoiseDb.coerceAtMost(micRefDb)

        val floorDb = max(micRefDb - MIC_SPAN_BELOW, micNoiseDb + NOISE_MARGIN_DB)
        val ceilDb = micRefDb + MIC_SPAN_ABOVE
        return ((db - floorDb) / (ceilDb - floorDb)).coerceIn(0f, 1f)
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
        private const val VU_FLOOR_ABS = 20f          // digital scale spans -20…+3 VU
        private const val VU_CEIL = 3f
        private const val DIGITAL_REF_DB = -19f       // 0.30-scaled mastered music
        private const val REF_ATTACK_SEC = 2f         // chase loud level up (~2 s)
        private const val REF_RELEASE_SEC = 20f       // relax back down (slow)
        private const val REF_ADAPT_RANGE_DB = 30f    // headroom above the base ref

        // Mic / line-input auto-range. The reference (dial centre) tracks the
        // recent level between these bounds; a quiet room drives it toward
        // MIC_REF_MIN (sensitive), a loud one toward MIC_REF_MAX (headroom).
        // Tune MIC_REF_MIN / NOISE_MARGIN_DB if a given mic reads hot or floors.
        private const val MIC_REF_MIN = -50f          // most sensitive (quiet room)
        private const val MIC_REF_MAX = -18f          // least sensitive (loud room)
        private const val MIC_ATTACK_SEC = 0.8f       // rise toward loud
        private const val MIC_RELEASE_SEC = 4f        // fall back / re-centre
        private const val MIC_SPAN_BELOW = 22f        // dB below ref → dial bottom
        private const val MIC_SPAN_ABOVE = 12f        // dB above ref → dial top
        private const val NOISE_FALL_SEC = 0.6f       // noise floor: latch onto quiet gaps
        private const val NOISE_RISE_SEC = 25f        // noise floor: creep up when loud persists
        private const val NOISE_MARGIN_DB = 3f        // dial floor sits this far above it

        private const val MAX_STEP_SEC = 0.016f       // spring integrator sub-step

        private const val VS = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FS = """#version 300 es
            precision highp float;
            uniform vec2  u_res;
            uniform float u_time, u_dim, u_needle, u_peak, u_wakeLo, u_wakeHi;
            uniform float u_wakeEnergy, u_sheen;
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
                    // Lit by sweep speed: a violent swing burns a bright fan,
                    // slow drift barely marks it.
                    col += vec3(0.03 + 0.10 * u_wakeEnergy) * t * t * radial;
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
