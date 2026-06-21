package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.sqrt

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
            uniform float u_needle;
            uniform float u_peak;
            uniform float u_bar;
            out vec4 fragColor;

            const vec3  BG     = vec3(0.106, 0.102, 0.090);
            const vec3  INK    = vec3(0.945, 0.933, 0.902);
            const vec3  ACCENT = vec3(0.941, 0.325, 0.110);
            const vec3  DDIM   = vec3(0.18, 0.17, 0.15);

            const vec2  HUB   = vec2(0.0, -0.72);
            const float R_ARC = 1.22;
            const float R_LED = 0.94;
            const float SWEEP = 0.90;
            const float HOT   = 0.75;

            float sdSeg(vec2 p, vec2 a, vec2 b) {
                vec2 pa = p - a, ba = b - a;
                float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
                return length(pa - ba * h);
            }

            vec3 evalTick(vec2 uv, float px, int idx, vec3 col) {
                float t = float(idx) / 20.0;
                float ta = (t * 2.0 - 1.0) * SWEEP;
                bool major = (idx % 5 == 0);
                float il = major ? 0.10 : 0.045;
                float ol = major ? 0.035 : 0.012;
                float tw = major ? 0.0035 : 0.0018;
                vec2 dir = vec2(sin(ta), cos(ta));
                float sd = sdSeg(uv, HUB + dir * (R_ARC - il), HUB + dir * (R_ARC + ol));
                vec3 tc = t >= HOT ? ACCENT : INK;
                float talpha = major ? 0.85 : 0.3;
                return mix(col, tc * talpha, smoothstep(tw + px, tw - px, sd));
            }

            vec3 evalLed(vec2 uv, float px, int idx, float bar, vec3 col) {
                float t = (float(idx) + 0.5) / 16.0;
                float la = (t * 2.0 - 1.0) * SWEEP;
                vec2 lp = HUB + vec2(sin(la), cos(la)) * R_LED;
                float ld = length(uv - lp);
                float lit = smoothstep(t - 0.02, t + 0.02, bar);
                bool hot = (t >= HOT);
                vec3 onCol = hot ? ACCENT * 1.8 : INK * 0.7;
                col = mix(col, mix(DDIM * 0.3, onCol, lit), smoothstep(0.022 + px, 0.022 - px, ld));
                col += (hot ? ACCENT : INK * 0.4) * smoothstep(0.06, 0.025, ld) * lit * 0.05;
                return col;
            }

            void main() {
                vec2 uv = (gl_FragCoord.xy * 2.0 - u_resolution) / min(u_resolution.y, u_resolution.x);
                float px = 2.0 / min(u_resolution.y, u_resolution.x);
                vec3 col = BG;

                vec2 dv = uv - HUB;
                float dist = length(dv);
                float ang = atan(dv.x, dv.y);
                float inArc = smoothstep(SWEEP + 0.03, SWEEP - 0.01, abs(ang));

                // Outer arc
                col = mix(col, INK * 0.25, smoothstep(px * 1.5, 0.0, abs(dist - R_ARC)) * inArc);

                // Inner reference arc
                col = mix(col, DDIM * 0.3, smoothstep(px * 1.5, 0.0, abs(dist - R_LED)) * inArc);

                // Hot zone overlay on outer arc
                float hotAng = (HOT * 2.0 - 1.0) * SWEEP;
                float inHot = step(hotAng, ang) * step(ang, SWEEP + 0.01);
                col = mix(col, ACCENT * 0.45, smoothstep(px * 2.0, 0.0, abs(dist - R_ARC) - 0.006) * inHot);

                // Tick marks — nearest 2 only (was 21)
                float tickNorm = (ang / SWEEP + 1.0) * 0.5;
                int ti = clamp(int(floor(tickNorm * 20.0)), 0, 20);
                col = evalTick(uv, px, ti, col);
                if (ti < 20) col = evalTick(uv, px, ti + 1, col);

                // LED dots — nearest 2 only (was 16)
                int li = clamp(int(floor(tickNorm * 16.0 - 0.5)), 0, 15);
                col = evalLed(uv, px, li, u_bar, col);
                if (li < 15) col = evalLed(uv, px, li + 1, u_bar, col);

                // Needle
                float na = (u_needle * 2.0 - 1.0) * SWEEP;
                vec2 ndir = vec2(sin(na), cos(na));
                vec2 tip = HUB + ndir * (R_ARC + 0.07);
                vec2 tail = HUB - ndir * 0.14;
                float nd = sdSeg(uv, tail, tip);
                float along = dot(uv - tail, tip - tail) / dot(tip - tail, tip - tail);
                float taper = mix(0.010, 0.0018, clamp(along, 0.0, 1.0));
                col += ACCENT * smoothstep(0.06, 0.0, nd) * 0.12;
                col = mix(col, ACCENT * 1.6, smoothstep(taper + px, taper - px, nd));

                // Counterweight
                float cwd = length(uv - (HUB - ndir * 0.10));
                col = mix(col, INK * 0.2, smoothstep(0.020 + px, 0.020 - px, cwd));

                // Hub
                float hd = length(uv - HUB);
                col = mix(col, INK * 0.5, smoothstep(px, -px, abs(hd - 0.06) - 0.005));
                col = mix(col, BG * 1.1, smoothstep(0.05 + px, 0.05 - px, hd));
                col = mix(col, ACCENT * 2.2, smoothstep(0.018 + px, 0.018 - px, hd));

                // Peak dot
                float peakAng = (u_peak * 2.0 - 1.0) * SWEEP;
                vec2 pp = HUB + vec2(sin(peakAng), cos(peakAng)) * (R_ARC + 0.035);
                float pd = length(uv - pp);
                col = mix(col, ACCENT * 2.2, smoothstep(0.014 + px, 0.014 - px, pd));

                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    private var program = 0
    private var aPos = 0
    private var uResolution = 0
    private var uDim = 0
    private var uNeedle = 0
    private var uPeak = 0
    private var uBar = 0

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private var width = 1f
    private var height = 1f

    private var needleLevel = 0f
    private var velocity = 0f
    private var barLevel = 0f
    private var peakLevel = 0f
    private var lastTime = -1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uNeedle = GLES20.glGetUniformLocation(program, "u_needle")
        uPeak = GLES20.glGetUniformLocation(program, "u_peak")
        uBar = GLES20.glGetUniformLocation(program, "u_bar")
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

        val gain = 175f
        val targetLevel = (rms * gain).let { if (it < 0.01f) 0f else sqrt(it) }.coerceIn(0f, 1.2f)
        val normalizedTarget = targetLevel / 1.2f

        // Spring-damped needle
        val stiffness = 350f
        val damping = 18f
        val force = (targetLevel - needleLevel) * stiffness
        velocity += (force - velocity * damping) * dt
        needleLevel += velocity * dt
        needleLevel = needleLevel.coerceIn(0f, 1.2f)
        val normalizedNeedle = needleLevel / 1.2f

        // Fast LED bar (instant attack, smooth decay)
        barLevel = if (normalizedTarget > barLevel) normalizedTarget
                   else barLevel * (1f - 4f * dt).coerceIn(0f, 1f)

        // Peak hold
        if (normalizedNeedle > peakLevel) peakLevel = normalizedNeedle
        else peakLevel = max(0f, peakLevel - dt * 0.4f)

        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uResolution, width, height)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uNeedle, normalizedNeedle)
        GLES20.glUniform1f(uPeak, peakLevel)
        GLES20.glUniform1f(uBar, barLevel)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
