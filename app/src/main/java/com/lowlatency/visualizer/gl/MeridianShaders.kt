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
            vec2 sc = vec2((v_uv.x * 2.0 - 1.0) * u_aspect / 1.15, (v_uv.y * 2.0 - 1.0) / 1.15);
            sc = rollRot(sc, -u_roll);                  // sky banks with the flight
            vec3 rd = normalize(vec3(sc, 1.0));
            float up = max(rd.y, 0.0);

            vec3 col = mix(vec3(0.030, 0.032, 0.082), vec3(0.0), smoothstep(0.0, 0.55, up));

            float md = max(dot(rd, MOON), 0.0);
            col += MOONCOL * smoothstep(0.99955, 0.99985, md) * 2.6;
            col += MOONCOL * pow(md, 700.0) * 0.9;
            col += MOONCOL * pow(md, 60.0) * (0.10 + u_env * 0.06);

            if (rd.y > 0.02) {
                vec2 cp = rd.xz / rd.y;
                float c1 = fbm(cp * 0.55 + vec2(u_time * 0.010, u_time * 0.004));
                float c2 = fbm(cp * 1.10 - vec2(u_time * 0.016, -u_time * 0.006) + 31.0);
                float cov = smoothstep(0.52 - u_cloud, 0.78 - u_cloud, c1 * 0.65 + c2 * 0.35);
                float horizonFade = smoothstep(0.02, 0.18, rd.y) * (1.0 - smoothstep(0.5, 0.9, rd.y));
                vec3 cloudCol = mix(vec3(0.045, 0.05, 0.09), MOONCOL * 0.30, pow(md, 6.0) + 0.18);
                col = mix(col, cloudCol, cov * horizonFade * 0.85);

                vec2 q = rd.xz / (rd.y + 0.35) * 26.0;
                vec2 cell = floor(q);
                float h = hash(cell);
                float star = smoothstep(0.995, 1.0, h)
                    * smoothstep(0.45, 0.0, length(fract(q) - 0.5))
                    * (0.55 + 0.45 * sin(u_time * 2.5 + h * 40.0))
                    * (0.45 + u_treble * 0.9) * (1.0 - cov);
                col += vec3(0.85, 0.92, 1.1) * star;
            }

            float ay = (rd.y - 0.16) * 5.5;
            float band = exp(-ay * ay);
            float wob = sin(rd.x * 9.0 + u_time * 0.21) * 0.5 + sin(rd.x * 21.0 - u_time * 0.13) * 0.3;
            col += (vec3(0.05, 0.55, 0.45) + vec3(0.25, 0.05, 0.45) * max(wob, 0.0))
                * band * (0.30 + 0.70 * max(wob, 0.0)) * u_mid * 0.55 * u_auroraG;

            col += vec3(0.40, 0.22, 0.65) * exp(-up * 6.0) * u_env * 0.28;

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

    const val TERRAIN_FS = """#version 300 es
        precision highp float;
        uniform float u_riverB;
        uniform float u_env;
        uniform float u_dim;
        uniform float u_valley;
        uniform float u_ridgeAmp;
        uniform float u_ridged;
        in vec2  v_world;
        in vec3  v_view;
        in float v_h;
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
        float fbm(vec2 q) {
            return vnoise(q) * 0.55 + vnoise(q * 2.13 + 5.2) * 0.28 + vnoise(q * 4.31 + 9.7) * 0.17;
        }
        float ridged(vec2 q) { return 1.0 - abs(2.0 * vnoise(q) - 1.0); }
        float meander(float z) { return (sin(z * 0.11) * 0.8 + sin(z * 0.043 + 1.7) * 1.3) * 0.55; }
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
            float e = 0.09;
            float hL = height(v_world + vec2(0.0, -e));
            float hR = height(v_world + vec2(0.0, e));
            float hD = height(v_world + vec2(-e, 0.0));
            float hU = height(v_world + vec2(e, 0.0));
            vec3 n = normalize(vec3(hL - hR, 2.0 * e, hD - hU));

            vec3 V = normalize(-v_view);
            float lam = max(dot(n, MOON), 0.0);
            float spec = pow(max(dot(reflect(-MOON, n), V), 0.0), 24.0);

            float flat01 = smoothstep(0.55, 0.9, n.y);
            vec3 albedo = mix(vec3(0.085, 0.085, 0.125), vec3(0.15, 0.125, 0.21), flat01)
                + vec3(0.03, 0.02, 0.05) * clamp(v_h, 0.0, 1.2);

            vec3 col = albedo * (0.10 + 0.95 * lam * MOONCOL) + MOONCOL * spec * 0.22;

            float dc = abs(v_world.y - meander(v_world.x));
            col += vec3(0.10, 0.42, 0.52) * exp(-dc * 2.2) * u_riverB * 0.8;
            col += vec3(0.30, 0.16, 0.50) * exp(-dc * 0.8) * u_env * 0.08;

            float f = exp(-v_view.z * 0.16);
            col = mix(FOGCOL, col, f);
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

        vec3 skyRef(vec3 rd) {
            float up = max(rd.y, 0.0);
            vec3 col = mix(vec3(0.030, 0.032, 0.082), vec3(0.0), smoothstep(0.0, 0.55, up));
            float md = max(dot(rd, MOON), 0.0);
            col += MOONCOL * smoothstep(0.9992, 0.9998, md) * 2.2;
            col += MOONCOL * pow(md, 90.0) * 0.35;
            if (rd.y > 0.03) {
                float c = vnoise(rd.xz / rd.y * 0.8 + u_time * 0.012);
                col = mix(col, MOONCOL * 0.16, smoothstep(0.55, 0.8, c) * 0.5);
            }
            return col;
        }

        void main() {
            float chop = vnoise(v_water * 3.1 + vec2(u_time * 0.5, u_time * 0.3)) - 0.5;
            vec3 n = normalize(vec3(
                (sin(v_water.x * 6.0 + u_time * 2.4) * 0.05 + chop * 0.16) * u_chop,
                1.0,
                sin(v_water.y * 7.0 - u_time * 1.9) * 0.06 * u_chop
            ));

            vec3 V = normalize(v_view);
            vec3 rd = reflect(V, n);
            rd.y = abs(rd.y);
            float fres = 0.06 + 0.94 * pow(1.0 - max(dot(-V, n), 0.0), 5.0);
            vec3 col = vec3(0.010, 0.016, 0.040) + skyRef(rd) * fres * 0.95;

            float off = texture(u_pcm, vec2(v_t01, 0.5)).r * 0.60;
            float d = v_water.y - off;
            float bright = (0.35 + 0.65 * u_loud) * (1.0 + u_env * 0.45);
            col += mix(vec3(0.0, 0.55, 0.75), vec3(1.05, 1.15, 1.20), exp(-d * d * 240.0))
                * exp(-d * d * 70.0) * bright * 1.5;
            col += vec3(0.05, 0.30, 0.40) * exp(-abs(v_water.y) * 1.4) * bright * 0.25;

            float f = exp(-v_view.z * 0.16);
            col = mix(FOGCOL, col, f);
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
            vec3 n = normalize(v_n);
            vec3 V = normalize(-v_view);
            float lam = max(dot(n, MOON), 0.0);
            float spec = pow(max(dot(reflect(-MOON, n), V), 0.0), 32.0);
            float rim = pow(1.0 - abs(dot(n, V)), 2.0);

            vec3 col = vec3(0.09, 0.11, 0.20) * (0.18 + 0.9 * lam * MOONCOL)
                + MOONCOL * spec * 0.7
                + vec3(0.25, 0.70, 0.95) * u_glow * (0.30 + rim * 0.9);

            float f = exp(-v_view.z * 0.16);
            col = mix(FOGCOL, col, f);
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
