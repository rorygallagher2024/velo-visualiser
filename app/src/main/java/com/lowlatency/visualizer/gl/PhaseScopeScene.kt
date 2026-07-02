package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import com.lowlatency.visualizer.BeatPulse
import kotlin.math.abs

/**
 * "Phase Scope" — a 3D oscilloscope.
 *
 * Where a normal scope plots the waveform as a flat line, this one plots each
 * sample as a point in 3D **phase space**: `(s[i], s[i+τ], s[i+2τ])`, a time-delay
 * embedding of the raw PCM. The result is a single glowing beam that traces a
 * living space-curve — sustained notes draw clean rotating loops, transients flare
 * it open, silence collapses it to a point. The whole figure auto-rotates so its
 * depth always reads, with a hue gradient running down the beam, a beat flare, and
 * the post-processor's HDR bloom doing the glow.
 *
 * Raw-PCM sibling of [WaveformWaterfallScene] (which stacks flat traces); this one
 * folds the same signal into a rotating 3D curve instead.
 */
class PhaseScopeScene : GlScene {

    companion object {
        private const val POINTS = 1024      // PCM window length
        private const val TAU = 48           // phase-space delay in samples (loop size)
        private const val VCOUNT = POINTS - 2 * TAU   // beam vertices

        // Auto-gain: normalise each frame to the window's peak so the curve fills the
        // cube at any input level (a quiet mic would otherwise collapse it to a dot).
        private const val AGC_TARGET = 5.5f  // post-gain peak feeding the soft-clip
        private const val AGC_FLOOR = 0.03f  // treat anything quieter as silence
        private const val AGC_SMOOTH = 0.08f // per-frame gain easing (avoids pumping)
        private const val AGC_MIN = 3f
        private const val AGC_MAX = 150f

        private const val SCOPE_VS = """#version 300 es
            layout(location = 0) in vec3 aPos;   // phase-space point, each axis ~[-1,1]
            uniform float u_time;
            uniform float u_aspect;              // width / height
            uniform float u_count;               // (vertices - 1), for the 0..1 ramp
            out float v_t;                       // 0..1 along the beam (hue)
            out float v_amp;                      // excursion from origin (brightness)
            out float v_depth;                    // 0..1 depth cue (near = 1)

            void main() {
                vec3 p = aPos;

                // Slow auto-rotation reveals the 3D structure (yaw + a gentle pitch drift).
                float ya = u_time * 0.25;
                float pa = 0.45 + 0.30 * sin(u_time * 0.13);
                float cy = cos(ya), sy = sin(ya);
                p = vec3(cy * p.x + sy * p.z, p.y, -sy * p.x + cy * p.z);
                float cp = cos(pa), sp = sin(pa);
                p = vec3(p.x, cp * p.y - sp * p.z, sp * p.y + cp * p.z);

                float d = 3.0;
                float w = d / (d - p.z);          // simple perspective (near = larger)
                vec2 proj = vec2(p.x / u_aspect, p.y) * w * 0.78;
                gl_Position = vec4(proj, 0.0, 1.0);

                v_t = float(gl_VertexID) / u_count;
                v_amp = length(aPos);
                v_depth = clamp((p.z + 1.0) * 0.5, 0.0, 1.0);
            }
        """

        private const val SCOPE_FS = """#version 300 es
            precision highp float;
            uniform float u_dim;
            uniform float u_env;                  // global beat envelope
            uniform float u_time;
            in float v_t;
            in float v_amp;
            in float v_depth;
            out vec4 fragColor;

            void main() {
                // Hue travels down the beam and drifts over time for a living colour.
                float h = v_t + u_time * 0.04;
                vec3 pal = 0.5 + 0.5 * cos(6.28318 * (h + vec3(0.0, 0.33, 0.67)));

                // Brightness rides the local excursion (loud = brighter) and flares
                // on the beat; nearer arcs read a little hotter for depth.
                float bright = (0.35 + v_amp * 1.3) * (0.8 + u_env * 1.6);
                float depthCue = mix(0.55, 1.25, v_depth);

                vec3 col = pal * bright * depthCue;
                col *= 1.4 + v_amp * 1.6;          // HDR lift so the beam blooms
                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    private val verts = FloatArray(VCOUNT * 3)
    private val vbuf: FloatBuffer = ByteBuffer
        .allocateDirect(VCOUNT * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var program = 0
    private var aPos = 0
    private var uTime = 0
    private var uAspect = 0
    private var uCount = 0
    private var uDim = 0
    private var uEnv = 0
    private var vbo = 0
    private var aspect = 1f
    private var agc = 8f      // smoothed auto-gain (see AGC_* constants)

    override fun onCreated() {
        program = ShaderUtil.buildProgram(SCOPE_VS, SCOPE_FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uAspect = GLES20.glGetUniformLocation(program, "u_aspect")
        uCount = GLES20.glGetUniformLocation(program, "u_count")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uEnv = GLES20.glGetUniformLocation(program, "u_env")

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, VCOUNT * 3 * 4, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.aspect = if (height > 0) width.toFloat() / height else 1f
    }

    /** Auto-gained soft-clip: spreads the curve to fill the cube, loud peaks saturate. */
    private fun softClip(s: Float): Float {
        val g = s * agc
        return g / (1f + abs(g))
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val limit = minOf(VCOUNT, pcm.size - 2 * TAU)
        if (limit <= 1) return

        // Track the window peak and ease the auto-gain toward a fill-the-cube target.
        var peak = 0f
        for (i in 0 until limit + 2 * TAU) {
            val a = abs(pcm[i]); if (a > peak) peak = a
        }
        val desired = (AGC_TARGET / maxOf(peak, AGC_FLOOR)).coerceIn(AGC_MIN, AGC_MAX)
        agc += (desired - agc) * AGC_SMOOTH

        var vi = 0
        for (i in 0 until limit) {
            verts[vi++] = softClip(pcm[i])
            verts[vi++] = softClip(pcm[i + TAU])
            verts[vi++] = softClip(pcm[i + 2 * TAU])
        }
        vbuf.clear(); vbuf.put(verts, 0, limit * 3); vbuf.position(0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // additive on black

        GLES20.glUseProgram(program)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, limit * 3 * 4, vbuf)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, 0)

        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform1f(uCount, (limit - 1).toFloat())
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uEnv, BeatPulse.envelope)

        GLES20.glLineWidth(1f)
        GLES30.glDrawArrays(GLES20.GL_LINE_STRIP, 0, limit)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun onDeactivate() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}
