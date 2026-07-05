package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import com.lowlatency.visualizer.BeatBus
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max

/**
 * Visual 15 — "Mandala Pulse".
 *
 * A layered, kaleidoscopic mandala that breathes with the music. Rather than one
 * flat fold, it stacks three counter-rotating octaves of petal rings (12 / 24 /
 * 36-fold) over a pulsing core, with anti-aliased filigree, a high-frequency
 * shimmer ring, and an evolving warm-core → cool-rim palette. Highlights are
 * emitted above 1.0 so the renderer's bloom makes the linework glow, and beats
 * punch it via the post pipeline.
 *
 * A small noise floor is shaved off each band so the mic's noise floor can't
 * twitch it when quiet — below the floor it settles to a calm, slowly breathing
 * idle — while the full dynamics above it survive, so it reacts across the whole
 * range (not only when loud). The de-noised bands are then smoothed with
 * asymmetric attack/release envelopes (fast in, slow out) so it swells and
 * settles musically instead of strobing:
 *   - Lows  → breathing zoom + core bloom (a peak-held bass *pulse*).
 *   - Mids  → rotation speed + palette drift.
 *   - Highs → outer-ring detail + the shimmer sparkle.
 *   - Beats → a shockwave ring pings outward from the core ([BeatBus.beatCount]).
 */
class MandalaPulseScene : GlScene {

    companion object {
        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform vec2  u_resolution;
            uniform float u_time;
            uniform float u_dim;
            uniform float u_low;     // smoothed band envelopes
            uniform float u_mid;
            uniform float u_high;
            uniform float u_energy;  // overall weighted energy
            uniform float u_pulse;   // peak-held bass pulse 0..1
            uniform float u_shock;   // seconds since last gated beat (ring age)
            out vec4 fragColor;

            const float TAU = 6.28318530718;

            mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }

            // Iñigo-Quílez cosine palette — warm gold core drifting to cool blue rim.
            vec3 pal(float t) {
                return 0.5 + 0.5 * cos(TAU * (vec3(1.0, 0.9, 0.75) * t
                       + vec3(0.0, 0.18, 0.45)));
            }

            // Kaleidoscopic fold to n mirrored sectors; returns radius, rewrites p.
            float kaleido(inout vec2 p, float n) {
                float r = length(p);
                float a = atan(p.y, p.x);
                float sector = TAU / n;
                a = mod(a, sector);
                a = abs(a - sector * 0.5);          // mirror within the sector
                p = vec2(cos(a), sin(a)) * r;
                return r;
            }

            // Anti-aliased glowing line where field d crosses 0 (width w).
            float aaLine(float d, float w) {
                float aa = fwidth(d) * 1.5 + 1e-4;
                return smoothstep(w + aa, w - aa, abs(d));
            }

            const vec3 LIGHT_DIR = vec3(-0.448, 0.647, 0.617);  // key light (pre-normalized)
            const float RELIEF = 34.0;                          // petal emboss strength

            void main() {
                vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution)
                          / min(u_resolution.x, u_resolution.y);
                uv *= 2.05;
                // Soft-knee the pulse for everything that *moves* geometry (zoom +
                // parallax) so low-level bass jitter can't twitch it — only real
                // hits displace the mandala; petal detail still reacts below this.
                float punch = smoothstep(0.12, 0.5, u_pulse);
                // Breathing zoom on the bass, plus a tiny always-on idle breath so
                // a silent mandala is calm but never frozen.
                uv /= (1.0 + punch * 0.22 + u_low * 0.10 + 0.015 * sin(u_time * 0.6));

                float r0 = length(uv);
                vec3 col = vec3(0.0);

                float spin = u_time * (0.05 + u_mid * 0.18);

                // Three nested octaves of petal rings, alternating spin direction.
                for (int i = 0; i < 3; i++) {
                    float fi = float(i);
                    float dir = (mod(fi, 2.0) < 0.5) ? 1.0 : -1.0;
                    // Per-octave depth parallax: inner rings pop toward you on the
                    // bass more than the outer ones, so the layers separate in depth.
                    float depth = 1.0 - punch * (0.22 - fi * 0.09);
                    vec2 p = uv * rot(spin * dir + fi * 0.7) * depth;
                    float n = 12.0 + fi * 12.0;                 // 12, 24, 36 petals
                    float r = kaleido(p, n);

                    float lobe = p.y / max(r, 1e-3);            // angular pos in sector
                    float ring  = sin(r * (8.0 + fi * 6.0) - u_time * (0.8 + fi * 0.3));
                    float petal = sin(lobe * (3.0 + fi) + r * 4.0);
                    float field = ring * 0.6 + petal * 0.4;

                    // Crisp filigree edge + a soft filled lobe.
                    float line = aaLine(field, 0.04 + u_high * 0.05);
                    float body = smoothstep(0.0, 0.7, field);
                    body *= body;

                    // Fake relief: treat the lobe as a height field and light it, so
                    // petals look raised and glassy — dimension without geometry.
                    vec3 nrm = normalize(vec3(-dFdx(body) * RELIEF, -dFdy(body) * RELIEF, 1.0));
                    float diff = clamp(dot(nrm, LIGHT_DIR) * 0.5 + 0.5, 0.0, 1.0);
                    float spec = pow(diff, 28.0) * body;

                    // Radial window; outer octaves sit back a touch (atmospheric depth).
                    float band = smoothstep(0.02, 0.28, r) * smoothstep(1.25, 0.5, r);
                    float amp  = (i == 2) ? u_high : (i == 0 ? u_low : u_mid);
                    float w = band * (0.45 + amp * 1.1) * (1.0 - fi * 0.12);

                    vec3 pc = pal(r * 0.42 + fi * 0.16 + u_time * 0.05 + u_mid * 0.3);
                    col += pc * (line + body * 0.4 * diff) * w;    // shaded lobe fill
                    col += vec3(0.9, 0.95, 1.0) * spec * w * 0.5;  // glassy sheen
                }

                // HDR lift on the linework so it blooms — applied BEFORE the core
                // and highlights so they can't compound into a white blowout.
                col *= 1.0 + u_energy * 0.9;

                // Central core — a warm glow that pulses on bass. Kept moderate and
                // added post-lift so the centre reads as gold, never a white disc.
                float core = exp(-r0 * r0 * 5.0);
                vec3 coreCol = mix(vec3(1.0, 0.74, 0.42), vec3(0.68, 0.86, 1.0), u_high * 0.5);
                col += coreCol * core * (0.3 + u_pulse * 1.0);

                // Outer shimmer ring — fine sparkle driven by treble.
                vec2 sp = uv;
                float rs = kaleido(sp, 48.0);
                float spark = aaLine(sin(rs * 38.0 - u_time * 3.0), 0.05);
                spark *= smoothstep(0.55, 0.9, rs) * smoothstep(1.45, 1.0, rs);
                col += vec3(0.72, 0.86, 1.0) * spark * u_high * 1.6;

                // Beat shockwave — a soft ring pings out from the core on each
                // gated beat, then fades as it travels, so the beat reads clearly.
                float sr = u_shock * 1.5;
                float sw = exp(-30.0 * (r0 - sr) * (r0 - sr)) * exp(-u_shock * 2.6);
                col += pal(0.15 + u_high * 0.4) * sw * 1.7;

                // Gentle vignette for depth.
                col *= smoothstep(1.85, 0.15, r0);

                fragColor = vec4(col * u_dim, 1.0);
            }
        """

        // Band level treated as mic noise and shaved off (raise if it twitches
        // when quiet, lower if it under-reacts).
        private const val NOISE_FLOOR = 0.07f
    }

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private var program = 0
    private var aPos = 0
    private var uResolution = 0
    private var uTime = 0
    private var uDim = 0
    private var uLow = 0
    private var uMid = 0
    private var uHigh = 0
    private var uEnergy = 0
    private var uPulse = 0
    private var uShock = 0

    private var width = 1f
    private var height = 1f

    // Smoothed audio envelopes (fast attack, slow release) + peak-held bass pulse.
    private var sLow = 0f
    private var sMid = 0f
    private var sHigh = 0f
    private var pulse = 0f
    private var shockAge = 9f      // seconds since last beat (large ⇒ no ring)
    private var lastBeat = 0
    private var lastT = -1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uLow = GLES20.glGetUniformLocation(program, "u_low")
        uMid = GLES20.glGetUniformLocation(program, "u_mid")
        uHigh = GLES20.glGetUniformLocation(program, "u_high")
        uEnergy = GLES20.glGetUniformLocation(program, "u_energy")
        uPulse = GLES20.glGetUniformLocation(program, "u_pulse")
        uShock = GLES20.glGetUniformLocation(program, "u_shock")
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    /** Frame-rate-independent one-pole envelope: fast toward rising input, slow on release. */
    private fun env(current: Float, target: Float, attackPerSec: Float, releasePerSec: Float, dt: Float): Float {
        val rate = if (target > current) attackPerSec else releasePerSec
        return current + (target - current) * (1f - exp(-rate * dt))
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val first = lastT < 0f
        val dt = if (first) 0.016f else (timeSec - lastT).coerceIn(0f, 0.1f)
        lastT = timeSec
        if (first) lastBeat = BeatBus.beatCount

        // Shave a small noise floor off each band: near-silence can't twitch the
        // mandala, but the full dynamics above the floor survive, so it reacts
        // across the whole range — not only when it's loud.
        val low = max(bands[0] - NOISE_FLOOR, 0f)
        val mid = max(bands[1] - NOISE_FLOOR, 0f)
        val high = max(bands[2] - NOISE_FLOOR, 0f)

        sLow = env(sLow, low, 16f, 4f, dt)
        sMid = env(sMid, mid, 13f, 3.2f, dt)
        sHigh = env(sHigh, high, 22f, 6f, dt)
        // Peak-held bass pulse on the de-noised bass, so the floor can't fire it.
        pulse = max(pulse - dt * 2.4f, 0f)
        if (low > pulse) pulse = low
        val energy = sLow * 0.5f + sMid * 0.3f + sHigh * 0.2f

        // Beat shockwave age: reset on each gated beat, then expands + fades.
        val beat = BeatBus.beatCount
        if (beat != lastBeat) { lastBeat = beat; shockAge = 0f }
        shockAge = (shockAge + dt).coerceAtMost(2f)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uResolution, width, height)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uLow, sLow)
        GLES20.glUniform1f(uMid, sMid)
        GLES20.glUniform1f(uHigh, sHigh)
        GLES20.glUniform1f(uEnergy, energy)
        GLES20.glUniform1f(uPulse, pulse)
        GLES20.glUniform1f(uShock, shockAge)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
