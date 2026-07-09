package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import com.lowlatency.visualizer.BeatSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class MechanicalMeterScene : GlScene {

    // Instrument readout — honest representation of the signal, no beat punch.
    override val respondsToBeat get() = false

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
            const vec2  WIN_C = vec2(0.0, -0.165);   // bezel window centre
            const vec2  WIN_B = vec2(1.10, 0.76);    // window half-extent
            const float BEZEL = 0.045;               // bezel band width

            float sdSeg(vec2 p, vec2 a, vec2 b) {
                vec2 pa = p - a, ba = b - a;
                float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
                return length(pa - ba * h);
            }

            float sdRoundBox(vec2 p, vec2 b, float r) {
                vec2 q = abs(p) - b;
                return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            float hash(vec2 q) { return fract(sin(dot(q, vec2(127.1, 311.7))) * 43758.5453); }

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
                // Unlit LEDs sit as dark recessed dots; only lit hot ones bloom.
                col = mix(col, mix(BG * 0.72, onCol, lit), smoothstep(0.022 + px, 0.022 - px, ld));
                col += ACCENT * smoothstep(0.06, 0.025, ld) * lit * (hot ? 0.10 : 0.0);
                return col;
            }

            void main() {
                vec2 uv = (gl_FragCoord.xy * 2.0 - u_resolution) / min(u_resolution.y, u_resolution.x);
                // Fit the bezel to the screen: the window spans x = ±1.20, so on
                // narrow displays (foldable cover screens) shrink until it fits
                // with a margin; wide screens keep the full 0.88 scale.
                float fit = min(0.88, max(1.0, u_resolution.x / u_resolution.y) / 1.26);
                uv /= fit;
                float px = 2.0 / (min(u_resolution.y, u_resolution.x) * fit);

                float dWin = sdRoundBox(uv - WIN_C, WIN_B, 0.10);

                // ---- meter face: lit, grained, vignetted (a material, not a fill) ----
                vec3 col = BG;
                col *= 1.0 + 0.16 * (1.0 - smoothstep(0.0, 1.5, length(uv - vec2(0.0, 0.10))));
                col *= 1.0 + (hash(floor(uv * 420.0)) - 0.5) * 0.05;      // paper grain
                col *= 1.0 - 0.22 * smoothstep(-0.35, -BEZEL, dWin);      // edge vignette

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

                // Needle geometry
                float na = (u_needle * 2.0 - 1.0) * SWEEP;
                vec2 ndir = vec2(sin(na), cos(na));
                vec2 tip = HUB + ndir * (R_ARC + 0.07);
                vec2 tail = HUB - ndir * 0.14;

                // Drop shadow first — the needle floats above the printed face.
                float nds = sdSeg(uv - vec2(0.016, -0.022), tail, tip);
                col = mix(col, col * 0.55, smoothstep(0.02, 0.0, nds) * 0.55);

                float nd = sdSeg(uv, tail, tip);
                float along = dot(uv - tail, tip - tail) / dot(tip - tail, tip - tail);
                float taper = mix(0.010, 0.0018, clamp(along, 0.0, 1.0));
                col += ACCENT * smoothstep(0.06, 0.0, nd) * 0.12;
                col = mix(col, ACCENT * 1.6, smoothstep(taper + px, taper - px, nd));

                // Counterweight
                float cwd = length(uv - (HUB - ndir * 0.10));
                col = mix(col, INK * 0.2, smoothstep(0.020 + px, 0.020 - px, cwd));

                // Machined pivot bearing: concentric rings catching a fixed light.
                float hd = length(uv - HUB);
                float hAng = atan(uv.x - HUB.x, uv.y - HUB.y);
                float lc = 0.55 + 0.45 * cos(hAng - 0.7);
                col = mix(col, DDIM * 1.3 * lc, smoothstep(0.075 + px, 0.075 - px, hd));
                col = mix(col, INK * (0.30 + 0.30 * lc), smoothstep(px, -px, abs(hd - 0.062) - 0.0022));
                col = mix(col, INK * (0.20 + 0.25 * lc), smoothstep(px, -px, abs(hd - 0.043) - 0.0018));
                col = mix(col, ACCENT * 2.0, smoothstep(0.016 + px, 0.016 - px, hd));
                col = mix(col, ACCENT * 2.6, smoothstep(0.007 + px, 0.007 - px, hd));

                // Peak dot
                float peakAng = (u_peak * 2.0 - 1.0) * SWEEP;
                vec2 pp = HUB + vec2(sin(peakAng), cos(peakAng)) * (R_ARC + 0.035);
                float pd = length(uv - pp);
                col = mix(col, ACCENT * 2.2, smoothstep(0.014 + px, 0.014 - px, pd));

                // ---- assemble the physical unit on the black canvas ----
                vec3 bezel = mix(vec3(0.020), vec3(0.165, 0.158, 0.142), smoothstep(0.0, -BEZEL, dWin));
                bezel *= 0.72 + 0.30 * smoothstep(-0.9, 0.7, uv.y);       // lit from above
                vec3 outCol = vec3(0.0);
                outCol = mix(outCol, bezel, smoothstep(px, -px, dWin));
                // Hairline where the face meets the bezel.
                outCol = mix(outCol, INK * 0.22, smoothstep(px * 1.5, 0.0, abs(dWin + BEZEL)));
                outCol = mix(outCol, col, smoothstep(px, -px, dWin + BEZEL));

                // Glass: a soft diagonal glare band + a thinner echo, over the pane.
                float gd = dot(uv - vec2(-0.25, 0.30), normalize(vec2(0.55, 0.84)));
                float glare = smoothstep(0.34, 0.0, abs(gd)) * 0.5 + smoothstep(0.10, 0.0, abs(gd - 0.42));
                outCol += INK * glare * 0.045 * smoothstep(px, -px, dWin);

                fragColor = vec4(outCol * u_dim, 1.0);
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

        // The Unprocessed mic averages far quieter than the 0.30-scaled digital
        // ring (system audio / local files), whose mastered material would pin
        // the needle at this mic calibration — digital gets VU-style headroom:
        // sustained club-loud sections ride ~80-90% with peaks kissing the red,
        // and breakdowns visibly fall away.
        val gain = if (BeatSettings.systemAudio) 9f else 175f
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

        // Mechanical micro-detail: a real needle trembles faintly under signal —
        // two incommensurate flutters, scaled by level, on top of the spring.
        val tremble = (sin(timeSec * 123f) + 0.6f * sin(timeSec * 287f)) * 0.0025f * normalizedTarget

        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uResolution, width, height)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uNeedle, (normalizedNeedle + tremble).coerceIn(0f, 1f))
        GLES20.glUniform1f(uPeak, peakLevel)
        GLES20.glUniform1f(uBar, barLevel)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
