package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import com.lowlatency.visualizer.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * "CRT Scope" — the Lissajous XY scope rendered like a real analog cathode-ray
 * tube, for oscilloscope music (Jerobeam Fenderson et al.).
 *
 * Left audio drives X, Right drives Y, traced as one continuous beam — same
 * true-stereo, phase-locked path as [LissajousScopeScene]. What makes it read
 * as a *tube* rather than a plotted line is beam physics:
 *
 *   - **Velocity-modulated brightness.** A constant-current electron beam
 *     deposits energy in proportion to dwell time, so where the trace moves
 *     slowly it glows hot and where it whips across it barely registers. Each
 *     vertex carries the local beam speed (segment length); brightness is its
 *     inverse. This is the single cue that makes vector art look "on a scope".
 *   - **P1 phosphor.** Green with a white-hot core at the brightest nodes and
 *     a persistence tail fading along the beam's age.
 *   - **Glass tube.** Gentle barrel curvature and an edge vignette so the trace
 *     sits behind a curved bulb, plus the usual slow burn-in orbit.
 *
 * Additive blending on black lets overlapping slow passes bloom naturally; the
 * renderer's HDR glow does the rest. Beam-brightness constants live in the
 * vertex shader (BEAM_GAIN / EPS / clamp) — tune there if a master reads hot.
 */
class CrtScopeScene : StereoScene {

    override val respondsToBeat get() = false

    companion object {
        private const val MAX_POINTS = 8192           // full trace at up to 192 kHz
        private const val TRACE_SEC = 0.040f          // 40 ms window (see LissajousScopeScene)
        private const val TRIGGER_SEARCH_SEC = 0.021f // edge hunt over ~one 48 Hz cycle
        private const val FLOATS_PER_VERT = 3         // x, y, beam speed
        private const val CURVATURE = 0.06f           // barrel bulge (glass tube)

        private const val SCOPE_VS = """#version 300 es
            layout(location = 0) in vec2 aPos;    // XY from Left/Right audio
            layout(location = 1) in float aSpeed; // local beam speed (segment length)
            uniform float u_time;
            uniform float u_aspect;               // width / height
            uniform float u_count;
            uniform float u_curvature;
            out float v_t;                        // 0..1 along the beam (age)
            out float v_intensity;                // velocity-modulated brightness
            out vec2  v_ndc;                      // curved position, for vignette

            void main() {
                // Burn-in protection orbit (~+/- 3 px, pixels to NDC).
                vec2 orbit = vec2(
                    sin(u_time * 0.31) + 0.5 * sin(u_time * 0.13 + 1.7),
                    cos(u_time * 0.27) + 0.5 * cos(u_time * 0.19 + 0.9)
                ) * 0.005;

                // Aspect-correct so circles stay circular in any orientation.
                vec2 p = aPos;
                if (u_aspect > 1.0) {
                    p.x /= u_aspect;
                } else {
                    p.y *= u_aspect;
                }
                p *= 0.86;   // margin for the curved glass

                // Barrel curvature: push outward with radius, like a tube face.
                float r2 = dot(p, p);
                p *= 1.0 + u_curvature * r2;

                gl_Position = vec4(p + orbit, 0.0, 1.0);
                v_t = float(gl_VertexID) / u_count;
                v_ndc = p;

                // Constant-current beam: energy per unit length ~ 1 / speed.
                // BEAM_GAIN / (speed + BEAM_EPS), clamped [BEAM_MIN, BEAM_MAX].
                float b = 0.0016 / (aSpeed + 0.0009);
                v_intensity = clamp(b, 0.03, 2.2);
            }
        """

        private const val SCOPE_FS = """#version 300 es
            precision highp float;
            uniform float u_dim;
            in float v_t;
            in float v_intensity;
            in vec2  v_ndc;
            out vec4 fragColor;

            void main() {
                // P1 phosphor green; the hottest nodes bleach toward white.
                vec3 phosphor = vec3(0.30, 1.0, 0.45);

                // Persistence: the trailing (older) beam fades out like a real
                // tube so the trace doesn't clutter into a solid mass.
                float age = 1.0 - v_t;
                float persistence = exp(-age * 3.0);

                float energy = v_intensity * persistence;
                // White-hot core where the beam dwells the longest.
                vec3 col = mix(phosphor, vec3(1.0), clamp(energy - 1.0, 0.0, 1.0));
                col *= energy * 1.4;

                // Glass vignette: darken toward the curved edges of the tube.
                float vig = 1.0 - smoothstep(0.7, 1.15, length(v_ndc));
                col *= vig;

                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    private val verts = FloatArray(MAX_POINTS * FLOATS_PER_VERT)
    private val vbuf: FloatBuffer = ByteBuffer
        .allocateDirect(MAX_POINTS * FLOATS_PER_VERT * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var program = 0
    private var aPos = 0
    private var aSpeed = 0
    private var uTime = 0
    private var uAspect = 0
    private var uCount = 0
    private var uCurvature = 0
    private var uDim = 0
    private var vbo = 0
    private var aspect = 1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(SCOPE_VS, SCOPE_FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aSpeed = GLES20.glGetAttribLocation(program, "aSpeed")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uAspect = GLES20.glGetUniformLocation(program, "u_aspect")
        uCount = GLES20.glGetUniformLocation(program, "u_count")
        uCurvature = GLES20.glGetUniformLocation(program, "u_curvature")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER, MAX_POINTS * FLOATS_PER_VERT * 4, null, GLES20.GL_DYNAMIC_DRAW
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.aspect = if (height > 0) width.toFloat() / height else 1f
    }

    // Stereo scene: the stereo entrypoint below carries the signal.
    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) = Unit

    override fun drawStereo(pcmStereo: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val totalPairs = pcmStereo.size / 2
        // Draw a fixed TIME window (never a point budget) so 96/192 kHz
        // oscilloscope masters keep every sample — mirrors LissajousScopeScene.
        val sampleRate = NativeBridge.nativeGetSampleRate().coerceAtLeast(8000)
        val drawPoints = (sampleRate * TRACE_SEC).toInt().coerceAtMost(MAX_POINTS)

        // Positive-edge trigger anchored to the newest samples, so the beam
        // always ends at "now" and stays phase-stable on lossless material.
        val searchSpan = minOf(
            (sampleRate * TRIGGER_SEARCH_SEC).toInt(),
            totalPairs - drawPoints - 2,
        ).coerceAtLeast(0)
        val base = (totalPairs - drawPoints - searchSpan).coerceAtLeast(0)
        var startIndex = base
        for (i in base until base + searchSpan) {
            val left1 = pcmStereo[i * 2]
            val left2 = pcmStereo[(i + 1) * 2]
            if (left1 <= 0f && left2 > 0f && pcmStereo[(i + 2) * 2] > 0.01f) {
                startIndex = i
                break
            }
        }

        val limit = minOf(drawPoints, totalPairs - startIndex).coerceAtMost(MAX_POINTS)
        if (limit <= 1) return

        // Interleave (x, y, beamSpeed). Speed is the segment length from the
        // previous sample; point 0 borrows point 1's so the head isn't a false
        // hot spot.
        var vi = 0
        var prevX = pcmStereo[startIndex * 2]
        var prevY = pcmStereo[startIndex * 2 + 1]
        for (i in 0 until limit) {
            val x = pcmStereo[(startIndex + i) * 2]           // Left -> X
            val y = pcmStereo[(startIndex + i) * 2 + 1]       // Right -> Y
            val dx = x - prevX
            val dy = y - prevY
            verts[vi++] = x
            verts[vi++] = y
            verts[vi++] = sqrt(dx * dx + dy * dy)
            prevX = x
            prevY = y
        }
        // Patch point 0's speed to match point 1 (index 2 <- index 5).
        if (limit > 1) verts[2] = verts[FLOATS_PER_VERT + 2]

        vbuf.clear(); vbuf.put(verts, 0, limit * FLOATS_PER_VERT); vbuf.position(0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)

        GLES20.glUseProgram(program)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, limit * FLOATS_PER_VERT * 4, vbuf)

        val stride = FLOATS_PER_VERT * 4
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(aSpeed)
        GLES20.glVertexAttribPointer(aSpeed, 1, GLES20.GL_FLOAT, false, stride, 2 * 4)

        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform1f(uCount, (limit - 1).toFloat())
        GLES20.glUniform1f(uCurvature, CURVATURE)
        GLES20.glUniform1f(uDim, dim)

        GLES20.glLineWidth(1f)
        GLES30.glDrawArrays(GLES20.GL_LINE_STRIP, 0, limit)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aSpeed)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun onDeactivate() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}
