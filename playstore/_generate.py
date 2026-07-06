#!/usr/bin/env python3
"""Velo brand assets — COLOURFUL variant.

A vivid full-spectrum gradient "V" chevron with a neon glow, on a deep near-black
ground, plus a rainbow oscilloscope trace on the feature graphic. Same precise V
geometry as the monochrome brand — just lit up, because it's a music visualiser.

  * Symbol : the V chevron stroked with a spectrum gradient + soft bloom.
  * Logo   : V over the "VELO VISUALISER" caption in slender wide-tracked caps.
"""
import math, os

OUT = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(OUT)

# Spectacle display font — Clash Display. weight 200 = ExtraLight.
THIN = "'Clash Display', 'Helvetica Neue', sans-serif"

# Deep grounds so the colour glows; caption stays legible.
DARK = dict(bg="#0A0A0F", ink="#F4F4F2", sub="#9A9AA8")
LIGHT = dict(bg="#F4F3EF", ink="#1A1A22", sub="#6E6E68")

# Vivid full-spectrum stops shared by the V and the scope trace.
SPECTRUM = [
    (0,   "#FF4D9D"),   # pink
    (20,  "#B14DFF"),   # violet
    (40,  "#4D7DFF"),   # blue
    (60,  "#2BD9D9"),   # cyan
    (80,  "#4DFF88"),   # green
    (100, "#FFE24D"),   # yellow
]


def _stops():
    return "".join(f'<stop offset="{o}%" stop-color="{c}"/>' for o, c in SPECTRUM)


def defs(glow_px):
    """Spectrum gradients (V diagonal, scope horizontal) + a soft glow filter."""
    return f'''<defs>
    <linearGradient id="vGrad" x1="0" y1="0" x2="1" y2="1">{_stops()}</linearGradient>
    <linearGradient id="scopeGrad" x1="0" y1="0" x2="1" y2="0">{_stops()}</linearGradient>
    <filter id="glow" x="-60%" y="-60%" width="220%" height="220%">
      <feGaussianBlur stdDeviation="{glow_px:.1f}"/>
    </filter>
  </defs>'''


# ----------------------------------------------------------------------------
def v_path(cx, top_y, width, extra=""):
    """A rounded, balanced V stroked with the spectrum gradient. Returns (svg, bottom_y)."""
    height = width * 0.96
    sw = width * 0.208
    x0, x1 = cx - width / 2, cx + width / 2
    by = top_y + height
    return (
        f'<path d="M {x0:.1f} {top_y:.1f} L {cx:.1f} {by:.1f} L {x1:.1f} {top_y:.1f}" '
        f'fill="none" stroke="url(#vGrad)" stroke-width="{sw:.1f}" '
        f'stroke-linecap="round" stroke-linejoin="round" {extra}/>',
        by,
    )


def v_group(cx, top_y, width):
    """Glowing V: a blurred copy for bloom + the crisp gradient stroke on top."""
    glow, _ = v_path(cx, top_y, width, 'filter="url(#glow)" opacity="0.9"')
    crisp, by = v_path(cx, top_y, width)
    return (glow + "\n    " + crisp, by)


def caption(cx, baseline, text, fs, color, weight=200, ls_em=0.34):
    return (f'<text x="{cx:.1f}" y="{baseline:.1f}" font-family="{THIN}" '
            f'font-size="{fs:.1f}" font-weight="{weight}" fill="{color}" '
            f'text-anchor="middle" letter-spacing="{ls_em}em" '
            f'style="text-transform:uppercase">{text}</text>')


def scope_points(x0, x1, cy, amp, step=2.5, phase=0.0, edge_ramp=70.0):
    pts, x = [], x0
    while x <= x1:
        t = x - x0
        ramp = min(1.0, t / edge_ramp); ramp = ramp * ramp * (3 - 2 * ramp)
        w = t / 90.0
        s = (0.62 * math.sin(w + phase) + 0.26 * math.sin(w * 2.3 + phase * 1.7 + 0.6)
             + 0.12 * math.sin(w * 4.1 + phase * 0.5 + 1.2))
        pts.append((x, cy - s * amp * ramp)); x += step
    return pts


def poly(pts):
    return " ".join(f"{x:.1f},{y:.1f}" for x, y in pts)


# ----------------------------------------------------------------------------
# APP ICON — 512x512. The glowing V symbol alone.
# ----------------------------------------------------------------------------
def app_icon(pal):
    cx = 256
    v, _ = v_group(cx, 156, 212)
    return f'''<svg viewBox="0 0 512 512" xmlns="http://www.w3.org/2000/svg" width="512" height="512">
  <title>Velo App Icon</title>
  <desc>A spectrum-gradient V chevron with a soft glow.</desc>
  {defs(10.0)}
  <clipPath id="r"><rect width="512" height="512" rx="114" ry="114"/></clipPath>
  <g clip-path="url(#r)">
    <rect width="512" height="512" fill="{pal['bg']}"/>
    {v}
  </g>
</svg>
'''


# ----------------------------------------------------------------------------
# IN-APP / README LOGO — V + caption, transparent.
# ----------------------------------------------------------------------------
def velo_logo(pal=DARK):
    pad = 40
    cap_fs = 30
    cap_text = "VELO VISUALISER"
    cap_w = len(cap_text) * (0.62 * cap_fs) + len(cap_text) * (0.34 * cap_fs)
    cx = max(180, cap_w / 2 + pad)
    W = int(cx * 2)
    v, v_bot = v_group(cx, pad, 156)
    base = v_bot + 64
    cap = caption(cx, base, cap_text, cap_fs, pal["ink"], weight=200, ls_em=0.34)
    H = int(base + cap_fs * 0.4 + pad)
    return f'''<svg viewBox="0 0 {W} {H}" xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}">
  <title>Velo Logo</title>
  {defs(7.0)}
  {v}
  {cap}
</svg>
'''


# ----------------------------------------------------------------------------
# FEATURE GRAPHIC — 1024x500. Glowing logo lockup + rainbow scope trace.
# ----------------------------------------------------------------------------
def feature_graphic(pal=DARK):
    W, H, cy = 1024, 500, 250
    trace = scope_points(470, 1040, cy, 120, phase=0.0)
    ghost = scope_points(470, 1040, cy, 120, phase=2.1)
    lx = 250
    v, v_bot = v_group(lx, 124, 138)
    base = v_bot + 54
    cap = caption(lx, base, "VELO VISUALISER", 24, pal["ink"], weight=200, ls_em=0.34)
    tag = (f'<text x="{lx}" y="{base + 42:.1f}" font-family="{THIN}" font-size="17" '
           f'font-weight="300" fill="{pal["sub"]}" text-anchor="middle" '
           f'letter-spacing="0.04em">Sound becomes light.</text>')
    inset = 22
    return f'''<svg viewBox="0 0 {W} {H}" xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}">
  <title>Velo Feature Graphic</title>
  {defs(8.0)}
  <clipPath id="b"><rect width="{W}" height="{H}"/></clipPath>
  <linearGradient id="fade" x1="0" y1="0" x2="1" y2="0">
    <stop offset="0%" stop-color="{pal['bg']}" stop-opacity="1"/>
    <stop offset="22%" stop-color="{pal['bg']}" stop-opacity="0"/>
  </linearGradient>
  <g clip-path="url(#b)">
    <rect width="{W}" height="{H}" fill="{pal['bg']}"/>
    <line x1="470" y1="{cy}" x2="{W-inset}" y2="{cy}" stroke="#FFFFFF" stroke-opacity="0.10" stroke-width="1"/>
    <polyline points="{poly(ghost)}" fill="none" stroke="url(#scopeGrad)" stroke-opacity="0.30" stroke-width="2.4" filter="url(#glow)"/>
    <polyline points="{poly(trace)}" fill="none" stroke="url(#scopeGrad)" stroke-width="2.6"
              stroke-linecap="round" stroke-linejoin="round"/>
    <rect x="0" y="0" width="470" height="{H}" fill="url(#fade)"/>
    {v}
    {cap}
    {tag}
    <rect x="{inset}" y="{inset}" width="{W-2*inset}" height="{H-2*inset}"
          fill="none" stroke="{pal['ink']}" stroke-opacity="0.18" stroke-width="1"/>
  </g>
</svg>
'''


def main():
    assets = {
        "app_icon_512.svg":            app_icon(DARK),
        "app_icon_512_light.svg":      app_icon(LIGHT),
        "feature_graphic_1024x500.svg": feature_graphic(DARK),
        "feature_graphic_1024x500_light.svg": feature_graphic(LIGHT),
        "velo_logo.svg":               velo_logo(DARK),
    }
    for name, svg in assets.items():
        with open(os.path.join(OUT, name), "w") as f:
            f.write(svg)
    print("wrote:", ", ".join(assets))


if __name__ == "__main__":
    main()
