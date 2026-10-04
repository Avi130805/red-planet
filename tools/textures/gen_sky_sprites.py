#!/usr/bin/env python3
"""Sky sprites for the Mars sky renderer, written into the vanilla celestials atlas folder
(assets/redplanet/textures/environment/celestial/, sprites redplanet:<name>).

The sky bodies are drawn additively (vanilla's CELESTIAL pipeline: colour x alpha added), so brightness is encoded
in alpha over white or tinted RGB. Phobos is also drawn as a dark silhouette when it crosses the Sun.

Geometry contract with client/sky/MarsCelestials.java: in each sprite the body's disc radius is a fixed fraction
of the sprite's half-width (DISC_FRACTION below), so the renderer can scale a quad to a true angular size.

Run: python3 tools/textures/gen_sky_sprites.py
"""

from __future__ import annotations

import math
from pathlib import Path

import numpy as np
from PIL import Image

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "src/client/resources/assets/redplanet/textures/environment/celestial"

# Disc radius as a fraction of the sprite half-width (keep in sync with MarsCelestials).
DISC_FRACTION = {"sun_mars": 0.25, "phobos": 0.80}


def grid(n: int):
    c = (n - 1) / 2.0
    y, x = np.mgrid[0:n, 0:n].astype(np.float64)
    return (x - c) / (n / 2.0), (y - c) / (n / 2.0)  # -1..1 across the sprite


def save(name: str, rgba: np.ndarray, dither: bool = False) -> None:
    """Writes 8-bit RGBA. With dither, alpha is rounded stochastically (seeded): faint, smooth glows otherwise show
    as concentric bands, because only a handful of 8-bit levels cover their outer half."""
    scaled = rgba * 255.0
    if dither:
        rng = np.random.default_rng(sum(map(ord, name)))
        scaled[..., 3] += rng.random(scaled.shape[:2]) - 0.5
    img = Image.fromarray(np.clip(scaled + 0.5, 0, 255).astype(np.uint8), "RGBA")
    img.save(OUT / f"{name}.png")
    print("wrote", name, img.size)


def sun_mars() -> None:
    """A limb-darkened disc (radius 0.25 of the half-width) with a tight inner glow."""
    n = 64
    x, y = grid(n)
    r = np.hypot(x, y)
    rd = DISC_FRACTION["sun_mars"]
    mu = np.sqrt(np.clip(1.0 - (r / rd) ** 2, 0.0, 1.0))
    limb = 1.0 - 0.55 * (1.0 - mu)  # linear limb darkening, visible-band coefficient ~0.55
    disc = (r <= rd).astype(np.float64)
    # Anti-aliased edge over one sprite pixel.
    edge = np.clip((rd - r) * (n / 2.0) + 0.5, 0.0, 1.0)
    # The glow starts at the limb's brightness (0.45) so disc and glow join without a dark ring.
    glow = 0.45 * np.exp(-((r - rd).clip(0) / 0.12) ** 1.1)
    alpha = edge * limb + (1.0 - edge) * glow
    rgb = np.stack([np.ones_like(r), 0.97 * np.ones_like(r), 0.91 * np.ones_like(r)], -1)
    save("sun_mars", np.concatenate([rgb, alpha[..., None]], -1))


def radial(name: str, n: int, profile) -> None:
    """A white radial glow. The window (1 - r^2)^2 takes it smoothly to zero at the sprite's edge, so the quad's
    outline never shows against a dark sky."""
    x, y = grid(n)
    r = np.hypot(x, y)
    a = profile(r) * np.clip(1.0 - r * r, 0.0, 1.0) ** 2
    rgb = np.ones((n, n, 3))
    save(name, np.concatenate([rgb, a[..., None]], -1), dither=True)


def phobos() -> None:
    """Irregular, cratered, dark grey body (13.0 x 11.4 x 9.1 km; albedo ~0.07), seen roughly end-on."""
    n = 32
    rng = np.random.default_rng(7)
    x, y = grid(n)
    rd = DISC_FRACTION["phobos"]
    ang = np.arctan2(y, x)
    # Lumpy outline: an ellipse (13.0 x 11.4) with low-order harmonics.
    radius = rd * (1.0 / np.sqrt((np.cos(ang) / 1.0) ** 2 + (np.sin(ang) / (11.4 / 13.0)) ** 2))
    radius *= 1.0 + 0.05 * np.cos(3 * ang + 0.7) + 0.035 * np.cos(5 * ang + 2.1)
    r = np.hypot(x, y)
    inside = np.clip((radius - r) * (n / 2.0) + 0.5, 0.0, 1.0)
    shade = 0.62 + 0.38 * np.clip(1.0 - (r / radius) ** 2, 0, 1) ** 0.5  # rounded body
    tone = np.ones_like(r)
    craters = [(-0.25, 0.10, 0.30)]  # Stickney, ~9 km across on an 11 km body
    for _ in range(9):
        craters.append((rng.uniform(-0.6, 0.6), rng.uniform(-0.5, 0.5), rng.uniform(0.06, 0.14)))
    for cx, cy, cr in craters:
        d = np.hypot(x - cx, y - cy) / cr
        tone -= 0.28 * np.exp(-(d ** 2) * 2.5) - 0.12 * np.exp(-((d - 1.0) ** 2) * 18.0)
    v = np.clip(shade * tone, 0.2, 1.1)
    # Slightly reddish grey. Phobos' albedo is only ~0.07, but it is sunlit against a black sky, so the eye sees it as a
    # bright grey disc (the renderer scales its brightness with phase).
    base = np.array([0.78, 0.72, 0.66])
    rgb = np.clip(v[..., None] * base, 0, 1)
    save("phobos", np.concatenate([rgb, inside[..., None]], -1))


def point(name: str, color, core: float, halo: float) -> None:
    n = 16
    x, y = grid(n)
    r = np.hypot(x, y)
    a = np.clip(np.exp(-(r / core) ** 2) + halo * np.exp(-(r / 0.55) ** 2), 0.0, 1.0) * np.clip((1.0 - r) / 0.15, 0, 1)
    rgb = np.ones((n, n, 3)) * np.array(color)
    save(name, np.concatenate([rgb, a[..., None]], -1))


PARTICLES = REPO / "src/client/resources/assets/redplanet/textures/particle"


def dust_motes() -> None:
    """Four 8x8 dust motes for redplanet:dust_mote: soft, slightly irregular specks in white (the particle tints them
    ochre and varies only brightness). Binary-ish alpha keeps them crisp at Minecraft's pixel scale."""
    PARTICLES.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(1977)
    n = 8
    for i, (radius, lumps) in enumerate([(0.32, 2), (0.45, 3), (0.58, 3), (0.72, 4)]):
        x, y = grid(n)
        r = np.hypot(x, y)
        ang = np.arctan2(y, x)
        edge = radius * (1.0 + 0.18 * np.cos(lumps * ang + rng.uniform(0, 6.28)))
        core = np.clip((edge - r) * n * 0.5 + 0.5, 0.0, 1.0)
        shade = 0.82 + 0.18 * np.clip(1.0 - r / max(radius, 1e-3), 0.0, 1.0)
        rgb = np.stack([shade, shade, shade], -1)
        img = Image.fromarray(np.clip(np.concatenate([rgb, core[..., None]], -1) * 255.0 + 0.5, 0, 255).astype(np.uint8), "RGBA")
        img.save(PARTICLES / f"dust_mote_{i}.png")
        print("wrote particle dust_mote_%d" % i)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    dust_motes()
    sun_mars()
    radial("glare", 128, lambda r: 0.9 * np.exp(-(r / 0.22) ** 1.5) + 0.25 * np.exp(-(r / 0.6) ** 2))
    # The aureole spans 50 degrees: 256 texels keep a texel near 0.2 degrees (the atlas samples nearest), and the
    # forward-scattering power law is softened at the centre, where the glare takes over.
    radial("aureole", 256, lambda r: (0.10 / (r + 0.10)) ** 1.2)
    phobos()
    point("deimos", (1.0, 0.97, 0.93), 0.16, 0.10)
    point("earth", (0.78, 0.88, 1.0), 0.20, 0.22)
    point("moon_point", (0.92, 0.92, 0.90), 0.13, 0.06)


if __name__ == "__main__":
    main()
