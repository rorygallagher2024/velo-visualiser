package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import com.lowlatency.visualizer.BeatPulse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sin

/**
 * "Cymatics" — a Chladni-plate standing-wave visual. A square plate is driven at
 * the music's *dominant frequency*; sand collects along the nodal lines of the
 * standing wave, tracing the classic symmetric Chladni figures. As the dominant
 * pitch rises the mode numbers climb and the pattern grows finer; louder tones
 * make the sand settle tighter and brighter.
 *
 * Unlike the band-driven scenes, this is tied to the actual peak frequency: a
 * CPU FFT ([SpectrumAnalyzer]) finds the loudest bin each frame, which maps to
 * the plate's two vibration modes. The figure morphs continuously with pitch and
 * drifts slowly over time so it stays alive on sustained notes.
 */
class ChladniPlateScene : GlScene {

    companion object {
        private const val BINS = 128

        private const val VERT = """#version 300 es
layout(location = 0) in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

        private const val FRAG = """#version 300 es
precision highp float;
uniform vec2  u_resolution;
uniform float u_time;
uniform float u_dim;
uniform float u_n;        // plate mode number 1
uniform float u_m;        // plate mode number 2
uniform float u_amp;      // dominant-tone loudness 0..1
uniform float u_hue;      // slow palette drift
uniform float u_env;      // beat envelope
out vec4 fragColor;

const float PI  = 3.14159265;
const float TAU = 6.2831853;

vec3 palette(float t) {
    return 0.5 + 0.5 * cos(TAU * (vec3(1.0) * t + vec3(0.05, 0.14, 0.24) + u_hue));
}

// Standing-wave displacement on a free-edge square plate. Nodal lines (where
// this is zero) are where the sand collects.
float chladni(vec2 p, float n, float m) {
    return cos(n * PI * p.x) * cos(m * PI * p.y)
         - cos(m * PI * p.x) * cos(n * PI * p.y);
}

void main() {
    vec2 frag = gl_FragCoord.xy;
    float side = min(u_resolution.x, u_resolution.y) * 0.92;
    vec2 plate = (frag - u_resolution * 0.5) / side + 0.5;   // 0..1 inside plate

    vec3 col;
    if (plate.x >= 0.0 && plate.x <= 1.0 && plate.y >= 0.0 && plate.y <= 1.0) {
        float s = chladni(plate, u_n, u_m);

        // Anti-aliased nodal lines: crisp at any mode density via screen-space
        // derivative of the field.
        float aa = fwidth(s) * 1.6 + 1e-4;
        float sand = 1.0 - smoothstep(0.0, aa, abs(s));

        float antinode = abs(s);                 // 0 at node, ->1 at antinode
        float vib = 0.5 + 0.5 * sin(u_time * 6.0);

        vec3 sandCol = palette(0.12 + antinode * 0.10);
        // Louder dominant tone -> brighter, crisper sand; beats flare it (HDR).
        col = sandCol * sand * (0.25 + u_amp * 1.6) * (1.6 + u_env * 2.0);
        // Faint antinode shimmer so the plate isn't dead between lines.
        col += palette(0.55) * (antinode * antinode) * 0.05 * (0.5 + u_amp) * (0.7 + 0.3 * vib);
        // Plate substrate.
        col += vec3(0.012, 0.013, 0.018);
        // Settle + dim toward silence.
        col *= 0.4 + 0.6 * u_amp;
        // Darken right at the rim for a physical plate edge.
        vec2 d = min(plate, 1.0 - plate);
        col *= 0.3 + 0.7 * smoothstep(0.0, 0.005, min(d.x, d.y));
    } else {
        col = vec3(0.010, 0.010, 0.014);         // bezel around the plate
    }

    vec2 uv = frag / u_resolution;
    col *= 1.0 - 0.28 * dot(uv - 0.5, uv - 0.5) * 2.0;   // vignette

    fragColor = vec4(max(col, 0.0) * u_dim, 1.0);
}
"""
    }

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private val analyzer = SpectrumAnalyzer(bins = BINS)

    private var program = 0
    private var aPos = 0
    private var uResolution = 0
    private var uTime = 0
    private var uDim = 0
    private var uN = 0
    private var uM = 0
    private var uAmp = 0
    private var uHue = 0
    private var uEnv = 0

    private var w = 1f
    private var h = 1f

    private var lastTime = -1f
    private var pitch = 0.3f       // smoothed dominant pitch 0..1
    private var amp = 0f           // smoothed dominant-tone loudness
    private var hue = 0f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERT, FRAG)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uN = GLES20.glGetUniformLocation(program, "u_n")
        uM = GLES20.glGetUniformLocation(program, "u_m")
        uAmp = GLES20.glGetUniformLocation(program, "u_amp")
        uHue = GLES20.glGetUniformLocation(program, "u_hue")
        uEnv = GLES20.glGetUniformLocation(program, "u_env")
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        w = width.toFloat(); h = height.toFloat()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.05f)
        lastTime = timeSec
        analyzer.update(pcm, dt)

        // Dominant bin = loudest spectrum bin (skip the lowest couple to ignore
        // DC / rumble). Its position is the "pitch" that drives the plate modes.
        val mags = analyzer.magnitudes
        var peakBin = 0
        var peakVal = 0f
        for (i in 2 until mags.size) {
            if (mags[i] > peakVal) { peakVal = mags[i]; peakBin = i }
        }
        if (peakVal > 0.08f) {          // only track when there's real signal
            val target = peakBin.toFloat() / (mags.size - 1)
            pitch += (target - pitch) * 0.04f
        }
        // Loudness follower: fast attack, slow decay → smooth settle in silence.
        amp = if (peakVal > amp) peakVal else amp * 0.92f
        hue += 0.0008f

        // Pitch -> two distinct plate modes (kept apart so the figure never
        // collapses to n==m), with a slow time drift so sustained notes evolve.
        val base = 2f + pitch * 8f
        val n = base
        val m = base * 0.62f + 1.7f + 0.8f * sin(timeSec * 0.05f)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uResolution, w, h)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uN, n)
        GLES20.glUniform1f(uM, m)
        GLES20.glUniform1f(uAmp, amp.coerceIn(0f, 1f))
        GLES20.glUniform1f(uHue, hue)
        GLES20.glUniform1f(uEnv, BeatPulse.envelope)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
