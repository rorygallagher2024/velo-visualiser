package com.lowlatency.visualizer.gl

/**
 * Meridian's GLSL — the journey-structure pass set. One moon lights every pass;
 * one camera roll banks every pass; and the world's *shape* is driven by slow
 * music-energy uniforms (valley width, ridge amplitude/sharpness, cloud cover,
 * aurora gain, water chop) so the arrangement of the track becomes geography:
 * breakdowns open into wide calm basins, builds close the canyon in, drops
 * blast the walls apart into a vista.
 *
 * Each shader is fully self-contained (helpers duplicated verbatim — the
 * headless validator lints each string in isolation). The meander(), rollRot()
 * and height() functions MUST stay identical wherever they appear.
 */
internal object MeridianShaders {

    const val QUAD_VS = """#version 300 es
        layout(location = 0) in vec2 aPos;
        out vec2 v_uv;
        void main() {
            v_uv = aPos * 0.5 + 0.5;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """

    /** Night sky: moon + halo, energy-thickened clouds, stars (treble), aurora
     *  (mids × energy), beat-warmed horizon. Banks with the camera roll. */
    const val SKY_FS = """#version 300 es
        precision highp float;
        uniform float u_time;
        uniform float u_treble;
        uniform float u_mid;
        uniform float u_env;
        uniform float u_aspect;
        uniform float u_cloud;                 // coverage bias (energy-driven)
        uniform float u_auroraG;               // aurora gain (energy-driven)
        uniform float u_roll;                  // camera bank
        uniform float u_dim;
        in vec2 v_uv;
        out vec4 fragColor;

        const vec3 MOON = vec3(0.404, 0.288, 0.868);
        const vec3 MOONCOL = vec3(0.86, 0.90, 1.05);

        float hash(vec2 q) { return fract(sin(dot(q, vec2(127.1, 311.7))) * 43758.5453); }
        float vnoise(vec2 q) {
            vec2 i = floor(q);
            vec2 f = fract(q);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
                       mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
        }
        float fbm(vec2 q) { return vnoise(q) * 0.6 + vnoise(q * 2.17 + 5.2) * 0.4; }
        vec2 rollRot(vec2 p, float r) {
            float c = cos(r); float s = sin(r);
            return vec2(p.x * c - p.y * s, p.x * s + p.y * c);
        }

        void main() {
            // INK & LIGHT: the sky is black paper — a flat graphic moon, sparse
            // crisp stars, one thin aurora ribbon. No clouds, no gradients.
            vec2 sc = vec2((v_uv.x * 2.0 - 1.0) * u_aspect / 1.15, (v_uv.y * 2.0 - 1.0) / 1.15);
            sc = rollRot(sc, -u_roll);                  // sky banks with the flight
            vec3 rd = normalize(vec3(sc, 1.0));
            float up = max(rd.y, 0.0);

            vec3 col = vec3(0.0);

            // Flat pale disc + a thin detached ring — printed, not photographed.
            float md = max(dot(rd, MOON), 0.0);
            col += vec3(0.92, 0.94, 1.02) * smoothstep(0.99950, 0.99966, md) * 1.7;
            float ring = smoothstep(0.99855, 0.99885, md) * (1.0 - smoothstep(0.99905, 0.99935, md));
            col += vec3(0.92, 0.94, 1.02) * ring * 0.24;

            // Sparse ink stars, barely twinkling with the treble.
            if (rd.y > 0.02) {
                vec2 q = rd.xz / (rd.y + 0.35) * 18.0;
                vec2 cell = floor(q);
                float h = hash(cell);
                float star = smoothstep(0.9975, 1.0, h)
                    * smoothstep(0.30, 0.0, length(fract(q) - 0.5))
                    * (0.6 + 0.4 * sin(u_time * 2.0 + h * 40.0))
                    * (0.40 + u_treble * 0.8);
                col += vec3(0.85, 0.92, 1.1) * star;
            }

            // One thin luminous ribbon on the horizon — the aurora as a signal line.
            float ay = (rd.y - 0.145) * 26.0;
            float band = exp(-ay * ay);
            float wob = sin(rd.x * 7.0 + u_time * 0.20) * 0.5 + sin(rd.x * 15.0 - u_time * 0.12) * 0.3;
            col += mix(vec3(0.10, 0.55, 0.60), vec3(0.45, 0.20, 0.85), max(wob, 0.0))
                * band * (0.30 + 0.70 * max(wob, 0.0)) * (0.25 + u_mid * 0.9) * u_auroraG;

            // Beats warm the horizon line, faintly.
            col += vec3(0.30, 0.18, 0.55) * exp(-up * 7.0) * u_env * 0.20;

            fragColor = vec4(col * u_dim, 1.0);
        }
    """

    const val TERRAIN_VS = """#version 300 es
        layout(location = 0) in vec2 aGrid;   // x01 across, z01 (0 = far, 1 = near)
        uniform float u_travel;
        uniform float u_camX;
        uniform float u_camY;
        uniform float u_roll;
        uniform vec2  u_f;
        uniform float u_valley;               // flat-floor half width (energy-driven)
        uniform float u_ridgeAmp;             // ridge amplitude (energy + bass)
        uniform float u_ridged;               // 0 soft hills .. 1 knife crests
        out vec2  v_world;
        out vec3  v_view;
        out float v_h;

        float hash(vec2 q) { return fract(sin(dot(q, vec2(127.1, 311.7))) * 43758.5453); }
        float vnoise(vec2 q) {
            vec2 i = floor(q);
            vec2 f = fract(q);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
                       mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
        }
        float fbm(vec2 q) {
            return vnoise(q) * 0.55 + vnoise(q * 2.13 + 5.2) * 0.28 + vnoise(q * 4.31 + 9.7) * 0.17;
        }
        float ridged(vec2 q) { return 1.0 - abs(2.0 * vnoise(q) - 1.0); }
        float meander(float z) { return (sin(z * 0.11) * 0.8 + sin(z * 0.043 + 1.7) * 1.3) * 0.55; }
        vec2 rollRot(vec2 p, float r) {
            float c = cos(r); float s = sin(r);
            return vec2(p.x * c - p.y * s, p.x * s + p.y * c);
        }
        float height(vec2 w) {
            float d = w.y - meander(w.x);
            float e = smoothstep(u_valley, u_valley + 2.5, abs(d));
            vec2 wp = w + vec2(vnoise(w * 0.13) * 1.7, vnoise(w * 0.11 + 3.7) * 1.7);
            float soft = fbm(wp * 0.34);
            float sharp = ridged(wp * 0.33) * 0.68 + ridged(wp * 0.71 + 11.0) * 0.32;
            float m = mix(soft, sharp * sharp, u_ridged);
            return fbm(w * 0.42) * 0.13 + e * u_ridgeAmp * (0.25 + 0.85 * m);
        }

        void main() {
            float zv = 0.32 + (1.0 - aGrid.y) * 14.18;
            float zw = u_travel + zv;
            float lat = (aGrid.x * 2.0 - 1.0) * 4.6;
            float h = height(vec2(zw, lat));
            float xv = lat - u_camX;
            float yv = h - u_camY;
            vec2 pr = rollRot(vec2(xv * u_f.x, yv * u_f.y), u_roll);
            gl_Position = vec4(pr, 0.0, zv);
            v_world = vec2(zw, lat);
            v_view = vec3(xv, yv, zv);
            v_h = h;
        }
    """

    /** INK & LIGHT terrain: black-paper silhouettes with contour-etched
     *  ridgelines (screen-space height derivative — essentially free), lit only
     *  by the river below. No per-pixel height recompute, no moonlight model. */
    const val TERRAIN_FS = """#version 300 es
        precision highp float;
        uniform float u_riverB;
        uniform float u_env;
        uniform float u_dim;
        in vec2  v_world;
        in vec3  v_view;
        in float v_h;
        out vec4 fragColor;

        float meander(float z) { return (sin(z * 0.11) * 0.8 + sin(z * 0.043 + 1.7) * 1.3) * 0.55; }

        void main() {
            // Ink body — barely above black, so silhouettes read against the sky.
            vec3 col = vec3(0.010, 0.011, 0.020);

            // Contour etching: where height changes fast across the screen, a cool
            // graphite line appears — ridgelines and crests draw themselves.
            float crest = clamp(fwidth(v_h) * 26.0, 0.0, 1.0) * smoothstep(0.05, 0.5, v_h);
            col += vec3(0.30, 0.42, 0.60) * crest * 0.55;

            // The river is the world's light: its glow climbs the near banks.
            float dc = abs(v_world.y - meander(v_world.x));
            col += vec3(0.08, 0.45, 0.60) * exp(-dc * 2.0) * u_riverB;
            col += vec3(0.25, 0.12, 0.45) * exp(-dc * 0.7) * u_env * 0.10;

            // Fog to pure black — the ink swallows the distance.
            col *= exp(-v_view.z * 0.20);
            fragColor = vec4(col * u_dim, 1.0);
        }
    """

    const val WATER_VS = """#version 300 es
        layout(location = 0) in vec2 aGrid;
        uniform float u_travel;
        uniform float u_camX;
        uniform float u_camY;
        uniform float u_roll;
        uniform vec2  u_f;
        out vec3  v_view;
        out float v_t01;
        out vec2  v_water;

        float meander(float z) { return (sin(z * 0.11) * 0.8 + sin(z * 0.043 + 1.7) * 1.3) * 0.55; }
        vec2 rollRot(vec2 p, float r) {
            float c = cos(r); float s = sin(r);
            return vec2(p.x * c - p.y * s, p.x * s + p.y * c);
        }

        void main() {
            float zv = 0.32 + (1.0 - aGrid.y) * 13.73;
            float zw = u_travel + zv;
            float local = (aGrid.x * 2.0 - 1.0) * 1.15;
            float lat = meander(zw) + local;
            float xv = lat - u_camX;
            float yv = 0.02 - u_camY;
            vec2 pr = rollRot(vec2(xv * u_f.x, yv * u_f.y), u_roll);
            gl_Position = vec4(pr, 0.0, zv);
            v_view = vec3(xv, yv, zv);
            v_t01 = (zv - 0.32) / 13.73;
            v_water = vec2(zw, local);
        }
    """

    const val WATER_FS = """#version 300 es
        precision highp float;
        uniform sampler2D u_pcm;
        uniform float u_time;
        uniform float u_loud;
        uniform float u_env;
        uniform float u_chop;                  // energy-driven surface agitation
        uniform vec4  u_gate0;                 // view-space gate: x, y, zv, glow
        uniform vec4  u_gate1;
        uniform float u_dim;
        in vec3  v_view;
        in float v_t01;
        in vec2  v_water;
        out vec4 fragColor;

        const vec3 MOON = vec3(0.404, 0.288, 0.868);
        const vec3 MOONCOL = vec3(0.86, 0.90, 1.05);
        const vec3 FOGCOL = vec3(0.030, 0.032, 0.082);

        float hash(vec2 q) { return fract(sin(dot(q, vec2(127.1, 311.7))) * 43758.5453); }
        float vnoise(vec2 q) {
            vec2 i = floor(q);
            vec2 f = fract(q);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
                       mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
        }

        // The ink sky, for mirrors: flat moon (smeared into a glitter path by the
        // ripples), thin ring, faint stars, the aurora ribbon. Graphic, not cloudy.
        vec3 inkSky(vec3 rd) {
            vec3 col = vec3(0.0);
            float md = max(dot(rd, MOON), 0.0);
            col += vec3(0.92, 0.94, 1.02) * smoothstep(0.99950, 0.99966, md) * 1.7;
            float ring = smoothstep(0.99855, 0.99885, md) * (1.0 - smoothstep(0.99905, 0.99935, md));
            col += vec3(0.92, 0.94, 1.02) * ring * 0.24;
            col += vec3(0.92, 0.94, 1.02) * (pow(md, 300.0) * 0.55 + pow(md, 40.0) * 0.06);
            if (rd.y > 0.02) {
                vec2 q = rd.xz / (rd.y + 0.35) * 18.0;
                float h = hash(floor(q));
                col += vec3(0.85, 0.92, 1.1) * smoothstep(0.9975, 1.0, h)
                    * smoothstep(0.30, 0.0, length(fract(q) - 0.5)) * 0.5;
            }
            float ay = (rd.y - 0.145) * 26.0;
            col += vec3(0.30, 0.35, 0.75) * exp(-ay * ay) * 0.35;
            return col;
        }

        // Mirror image of a gate: intersect the reflected ray with the gate's
        // plane and evaluate the same ring, softened — it wobbles in the chop.
        vec3 gateGlint(vec3 ro, vec3 rd, vec4 g) {
            if (g.w <= 0.0 || rd.z <= 0.001) return vec3(0.0);
            float t = (g.z - ro.z) / rd.z;
            if (t <= 0.0) return vec3(0.0);
            vec2 p = ro.xy + rd.xy * t;
            float rr = length(p - g.xy) / 2.4;
            float d1 = abs(rr - 0.72) * 10.0;
            float band = exp(-d1 * d1);
            return mix(vec3(0.25, 0.70, 0.95), vec3(0.86, 0.90, 1.05), band * 0.5) * band * g.w;
        }

        void main() {
            // INK & LIGHT, mirrored: a real reflective surface — but everything it
            // reflects is ink, so the aesthetic holds.
            float chop = vnoise(v_water * 3.1 + vec2(u_time * 0.5, u_time * 0.3)) - 0.5;
            vec3 n = normalize(vec3(
                (sin(v_water.x * 6.0 + u_time * 2.4) * 0.05 + chop * 0.16) * u_chop,
                1.0,
                sin(v_water.y * 7.0 - u_time * 1.9) * 0.06 * u_chop
            ));
            vec3 V = normalize(v_view);
            vec3 rd = reflect(V, n);
            rd.y = abs(rd.y);
            float fres = 0.08 + 0.92 * pow(1.0 - max(dot(-V, n), 0.0), 5.0);
            vec3 col = vec3(0.004, 0.006, 0.012) + inkSky(rd) * fres;
            col += gateGlint(v_view, rd, u_gate0) * fres;
            col += gateGlint(v_view, rd, u_gate1) * fres;

            // The current: still the heart — the waveform's own light on the mirror.
            float off = texture(u_pcm, vec2(v_t01, 0.5)).r * 0.60;
            float d = v_water.y - off;
            float bright = (0.40 + 0.60 * u_loud) * (1.0 + u_env * 0.50);
            col += mix(vec3(0.0, 0.55, 0.75), vec3(1.10, 1.20, 1.25), exp(-d * d * 240.0))
                * exp(-d * d * 70.0) * bright * 1.7;
            float g = vnoise(v_water * 6.0 + vec2(u_time * 1.4, -u_time * 0.9));
            col += vec3(0.0, 0.50, 0.70) * smoothstep(0.78, 0.95, g)
                * exp(-d * d * 8.0) * bright * 0.25 * u_chop;

            col *= exp(-v_view.z * 0.20);
            fragColor = vec4(col * u_dim, 1.0);
        }
    """

    const val SHARD_VS = """#version 300 es
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNorm;
        uniform float u_travel;
        uniform float u_camX;
        uniform vec3  u_pos;
        uniform float u_rot;
        uniform float u_scale;
        uniform float u_camY;
        uniform float u_roll;
        uniform vec2  u_f;
        out vec3 v_n;
        out vec3 v_view;

        float meander(float z) { return (sin(z * 0.11) * 0.8 + sin(z * 0.043 + 1.7) * 1.3) * 0.55; }
        vec2 rollRot(vec2 p, float r) {
            float c = cos(r); float s = sin(r);
            return vec2(p.x * c - p.y * s, p.x * s + p.y * c);
        }

        void main() {
            float c = cos(u_rot);
            float s = sin(u_rot);
            vec3 p = vec3(aPos.x * c - aPos.z * s, aPos.y, aPos.x * s + aPos.z * c) * u_scale;
            vec3 nl = vec3(aNorm.x * c - aNorm.z * s, aNorm.y, aNorm.x * s + aNorm.z * c);
            float zv = u_pos.z + p.z;
            float zw = u_travel + zv;
            float xv = meander(zw) + u_pos.x + p.x - u_camX;
            float yv = u_pos.y + p.y - u_camY;
            vec2 pr = rollRot(vec2(xv * u_f.x, yv * u_f.y), u_roll);
            gl_Position = vec4(pr, 0.0, zv);
            v_n = nl;
            v_view = vec3(xv, yv, zv);
        }
    """

    const val SHARD_FS = """#version 300 es
        precision highp float;
        uniform float u_glow;
        uniform float u_dim;
        in vec3 v_n;
        in vec3 v_view;
        out vec4 fragColor;

        const vec3 MOON = vec3(0.404, 0.288, 0.868);
        const vec3 MOONCOL = vec3(0.86, 0.90, 1.05);
        const vec3 FOGCOL = vec3(0.030, 0.032, 0.082);

        void main() {
            // INK & LIGHT: dark crystal silhouettes with a luminous rim — their
            // band's energy burns along the edges, the body stays ink.
            vec3 n = normalize(v_n);
            vec3 V = normalize(-v_view);
            float rim = pow(1.0 - abs(dot(n, V)), 2.0);

            vec3 col = vec3(0.006, 0.008, 0.014);
            col += vec3(0.25, 0.70, 0.95) * u_glow * rim * 1.4;
            col += vec3(0.90, 0.95, 1.05) * pow(rim, 6.0) * (0.20 + u_glow * 0.25);

            col *= exp(-v_view.z * 0.20);
            fragColor = vec4(col * u_dim, 1.0);
        }
    """

    /** The Gates: colossal luminous rings spanning the canyon — billboarded
     *  quads with an SDF ring, drawn additive (occlusion-safe scale event). */
    const val GATE_VS = """#version 300 es
        layout(location = 0) in vec2 aPos;
        uniform vec3  u_center;               // (lat, alt, zv)
        uniform float u_scale;
        uniform float u_camX;
        uniform float u_camY;
        uniform float u_roll;
        uniform vec2  u_f;
        out vec2 v_q;

        vec2 rollRot(vec2 p, float r) {
            float c = cos(r); float s = sin(r);
            return vec2(p.x * c - p.y * s, p.x * s + p.y * c);
        }

        void main() {
            float zv = u_center.z;
            float xv = u_center.x + aPos.x * u_scale - u_camX;
            float yv = u_center.y + aPos.y * u_scale - u_camY;
            vec2 pr = rollRot(vec2(xv * u_f.x, yv * u_f.y), u_roll);
            gl_Position = vec4(pr, 0.0, zv);
            v_q = aPos;
        }
    """

    const val GATE_FS = """#version 300 es
        precision highp float;
        uniform float u_glow;                 // energy/beat/approach, fog folded in (CPU)
        uniform float u_dim;
        in vec2 v_q;
        out vec4 fragColor;

        const vec3 MOONCOL = vec3(0.86, 0.90, 1.05);

        void main() {
            float r = length(v_q);
            float d1 = abs(r - 0.72) * 16.0;
            float ring = exp(-d1 * d1);
            float d2 = abs(r - 0.52) * 34.0;
            float inner = exp(-d2 * d2) * 0.35;
            vec3 col = mix(vec3(0.25, 0.70, 0.95), MOONCOL, ring * 0.5)
                * (ring + inner) * u_glow;
            col += vec3(0.10, 0.30, 0.45) * smoothstep(0.72, 0.1, r) * u_glow * 0.06;
            fragColor = vec4(col * u_dim, 1.0);
        }
    """
}
