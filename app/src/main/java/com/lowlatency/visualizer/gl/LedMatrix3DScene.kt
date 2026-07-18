package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * "3D LED" — Premium 3D Overhaul.
 *
 * A pseudo-isometric 3D raymarched LED matrix. Renders a physical 24x14 grid
 * of glowing glass cubes floating in a void.
 */
class LedMatrix3DScene : GlScene {

    override val respondsToBeat get() = false

    companion object {
        private const val COLS = 24
        private const val ROWS = 14
        private const val RELEASE_TAU = 0.09f   // LED glow-down time constant
        private const val ROW_HYST = 0.15f      // Schmitt band around each LED (rows)

        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform vec2  u_resolution;
            uniform float u_time;
            uniform float u_dim;
            uniform sampler2D u_spectrum;
            out vec4 fragColor;

            const float COLS = float(${COLS});
            const float ROWS = float(${ROWS});

            // 2D Rotation
            mat2 rot(float a) {
                float s = sin(a), c = cos(a);
                return mat2(c, -s, s, c);
            }

            // Box SDF with rounded edges
            float sdRoundBox(vec3 p, vec3 b, float r) {
                vec3 q = abs(p) - b;
                return length(max(q,0.0)) + min(max(q.x,max(q.y,q.z)),0.0) - r;
            }

            // Get the integer ID of the cube we are inside/near
            vec2 getCubeID(vec3 p) {
                return vec2(clamp(floor(p.x + 0.5), 0.0, COLS - 1.0),
                            clamp(floor(p.y + 0.5), 0.0, ROWS - 1.0));
            }

            // The device body: a rounded slab sitting just behind the LEDs,
            // sized with a bezel margin — the grid reads as a built object,
            // not cubes floating in a void. LEDs protrude through its face.
            float sdChassis(vec3 p) {
                vec3 c = vec3((COLS - 1.0) * 0.5, (ROWS - 1.0) * 0.5, 1.05);
                return sdRoundBox(p - c, vec3(COLS * 0.5 + 0.9, ROWS * 0.5 + 0.9, 0.35), 0.30);
            }

            // Map the world
            float map(vec3 p) {
                vec3 q = p;
                q.xy -= getCubeID(p);
                // Box size 0.35, roundness 0.05 => total size 0.4 => gap is 0.2 between centers
                float leds = sdRoundBox(q, vec3(0.35), 0.08);
                return min(leds, sdChassis(p));
            }

            // Calculate Normal
            vec3 calcNormal(vec3 p) {
                vec2 e = vec2(1.0, -1.0) * 0.5773 * 0.005;
                return normalize(e.xyy*map(p + e.xyy) + 
                                 e.yyx*map(p + e.yyx) + 
                                 e.yxy*map(p + e.yxy) + 
                                 e.xxx*map(p + e.xxx));
            }

            void main() {
                vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution) / min(u_resolution.x, u_resolution.y);
                
                // ZOOM OUT: Scale UVs so the matrix fits nicely within the screen
                uv *= 1.25;

                // Grid Center (Anchored around the middle of the board)
                vec3 center = vec3((COLS - 1.0)*0.5, (ROWS - 1.0)*0.5, 0.0);

                // Isometric Orbiting Camera
                // Slowly drifts around the LED matrix
                vec3 ro = center + vec3(18.0 * sin(u_time * 0.15), 12.0 + 4.0 * cos(u_time * 0.2), -26.0);
                vec3 ta = center;
                
                // Build Camera Ray. Right-handed basis (right = up x fwd,
                // up = fwd x right): with the camera on the -Z side this puts
                // +X on screen-right, so column 0 (bass) sits on the LEFT and
                // treble on the right — cross(ww, up) mirrored the spectrum.
                vec3 ww = normalize(ta - ro);
                vec3 uu = normalize(cross(vec3(0.0, 1.0, 0.0), ww));
                vec3 vv = normalize(cross(ww, uu));
                vec3 rd = normalize(uv.x*uu + uv.y*vv + 1.6*ww); // 1.6 FOV

                // Raymarch Loop
                float t = 0.0;
                float d = 0.0;
                vec3 p;
                
                // Dither starting point to hide banding/steps
                float dither = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
                t += dither * 0.1;

                // 60 steps is plenty for a simple finite cube grid
                for(int i=0; i<60; i++) {
                    p = ro + rd * t;
                    d = map(p);
                    if(d < 0.005 || t > 100.0) break;
                    t += d;
                }

                vec3 col = vec3(0.0); // Deep void background

                if(t < 100.0) {
                    vec2 id = getCubeID(p);
                    vec3 n = calcNormal(p);
                    // Which object did we hit? Compare the two SDFs here.
                    vec3 qh = p; qh.xy -= id;
                    bool onChassis = sdChassis(p) < sdRoundBox(qh, vec3(0.35), 0.08);
                    
                    // Main directional light
                    vec3 l = normalize(vec3(0.5, 1.0, -0.8));
                    
                    // Fetch Audio Spectrum Data
                    float u = (id.x + 0.5) / COLS;
                    float mag = texture(u_spectrum, vec2(u, 0.25)).r;
                    float peak = texture(u_spectrum, vec2(u, 0.75)).r;
                    
                    float litRows = mag * ROWS;
                    float peakRow = floor(peak * ROWS - 0.001);
                    
                    bool isLit = id.y < litRows;
                    bool isPeak = abs(id.y - peakRow) < 0.1 && peak > 0.02;

                    // Material Definitions
                    vec3 matColor = vec3(0.045); // Unlit dark lens — visible hardware
                    float emission = 0.0;

                    if(onChassis) {
                        matColor = vec3(0.05);   // matte housing
                    } else if(isPeak) {
                        // Stark white hot peaks
                        matColor = vec3(1.0, 0.95, 0.9);
                        emission = 2.0; // Subtle bloom triggering HDR
                    } else if(isLit) {
                        // Deep Orange to Cyan gradient across the board
                        vec3 orange = vec3(1.0, 0.35, 0.05);
                        vec3 cyan = vec3(0.0, 0.85, 1.0);
                        matColor = mix(orange, cyan, id.x / COLS);
                        
                        // Emission blooms harder at the top of the bar
                        emission = 0.8 + (id.y / ROWS) * 0.7; 
                    }

                    // Lighting Calculation
                    float diff = max(dot(n, l), 0.0);
                    float amb = 0.15;
                    
                    // Sharp specular highlights for "glass/plastic" feel
                    // (dialed down on the matte housing so it doesn't glare)
                    vec3 h = normalize(l - rd);
                    float spec = pow(max(dot(n, h), 0.0), 64.0) * (onChassis ? 0.25 : 1.0);

                    // Combine Diffuse, Specular, and Ambient
                    col = matColor * (diff + amb) + spec * 0.6;
                    
                    // Add glowing emission
                    col += matColor * emission;

                    // Fresnel edge glow (simulates thick glass internal reflection)
                    float fresnel = pow(1.0 - max(dot(n, -rd), 0.0), 4.0);
                    col += fresnel * matColor * 0.5;
                }

                // Global Dimming
                col *= u_dim;

                // Output linear HDR color (PostProcessor.kt handles the bloom and tonemapping)
                fragColor = vec4(col, 1.0);
            }
        """
    }

    private val upload: FloatBuffer = ByteBuffer
        .allocateDirect(COLS * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private var program = 0
    private var aPos = 0
    private var uResolution = 0
    private var uTime = 0
    private var uDim = 0
    private var uSpectrum = 0

    private var specTex = 0
    private var width = 1f
    private var height = 1f

    // LED physics: analog level per column (instant attack, RC-style release)
    // and a Schmitt-latched lit-row count so LEDs never strobe at a threshold.
    private val colLevel = FloatArray(COLS)
    private val litRows = FloatArray(COLS)
    private var lastT = -1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uSpectrum = GLES20.glGetUniformLocation(program, "u_spectrum")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        specTex = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, specTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R32F,
            COLS, 2, 0, GLES30.GL_RED, GLES20.GL_FLOAT, null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        if (program == 0) return

        val magnitudes = SpectrumData.magnitudes
        val peaks = SpectrumData.peaks
        val dt = if (lastT < 0f) 0.016f else (timeSec - lastT).coerceIn(0f, 0.1f)
        lastT = timeSec
        val release = kotlin.math.exp(-dt / RELEASE_TAU)
        upload.clear()

        // Group raw 128-bin spectrum into 24 distinct columns, then run each
        // through the LED physics: instant attack, ~90 ms glow-down, and a
        // Schmitt latch per row so an LED clicks on/off instead of strobing
        // while the level dances at its threshold.
        for (i in 0 until COLS) {
            val lo = i * 128 / COLS
            val hi = (i + 1) * 128 / COLS
            val n = (hi - lo).coerceAtLeast(1)
            var sum = 0f
            for (j in lo until lo + n) sum += magnitudes[j.coerceAtMost(127)]
            val target = sum / n
            colLevel[i] = if (target > colLevel[i]) target else colLevel[i] * release
            val level = colLevel[i] * ROWS
            var lit = litRows[i]
            while (lit < ROWS && level >= lit + 0.5f + ROW_HYST) lit += 1f
            while (lit > 0f && level <= lit - 0.5f - ROW_HYST) lit -= 1f
            litRows[i] = lit
            upload.put(lit / ROWS)
        }
        for (i in 0 until COLS) {
            val lo = i * 128 / COLS
            val hi = (i + 1) * 128 / COLS
            val n = (hi - lo).coerceAtLeast(1)
            var sum = 0f
            for (j in lo until lo + n) sum += peaks[j.coerceAtMost(127)]
            upload.put(sum / n)
        }
        upload.position(0)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, specTex)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, COLS, 2,
            GLES30.GL_RED, GLES20.GL_FLOAT, upload
        )
        GLES20.glUniform1i(uSpectrum, 0)
        
        GLES20.glUniform2f(uResolution, width, height)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
