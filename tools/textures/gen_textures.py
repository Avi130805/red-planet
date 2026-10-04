#!/usr/bin/env python3
"""
Procedural pixel-art textures for "Red Planet: Starship to Mars" (mod id ``redplanet``).

Run from anywhere (paths are resolved from this file):

    python3 tools/textures/gen_textures.py                    # every texture, the mod icon and the previews
    python3 tools/textures/gen_textures.py --only regolith,olivine
    python3 tools/textures/gen_textures.py --no-preview
    python3 tools/textures/gen_textures.py --list

Everything is drawn by code. No vanilla texture is copied or used as input for an output texture; vanilla
files are read only (and only if they exist) to build the side-by-side style-comparison preview. Every random
choice comes from ``rng_for(name)``, a NumPy PCG64 generator seeded from a CRC32 of the texture name, so a run
is byte-for-byte reproducible and adding a texture never changes the others.

Style targets (vanilla Java Edition look): 16x16, 6-12 colours per texture, clustered noise rather than
salt-and-pepper, soft top-left lighting on nuggets/cobble/bricks, block textures that tile seamlessly (all
noise is generated on a torus), alpha exactly 255 on solid blocks (26.3 picks a block's render layer from its
texture alpha; water_ice is the one translucent texture), binary alpha on items. Palette anchors follow the
art brief, which takes them from white-balanced Mastcam / Mastcam-Z true-colour images (see PAL below).

Outputs: textures/block/*.png and textures/item/*.png under src/client/resources/assets/redplanet/, the
128x128 mod icon (src/main/resources/assets/redplanet/icon.png, 64x64 art doubled), and previews in
tools/textures/preview/ (contact sheet with 3x3 tilings, vanilla comparison, distance view, isometric scene).
The run validates every texture (size, alpha rules, colour count, a seam heuristic) and prints warnings.

Toolkit (reusable when adding textures):
  colour     hexc(), rgb_to_oklab()/oklab_to_rgb(), ramp() - perceptual ramps through anchor colours,
             adjust() - lightness/chroma/hue tweaks in OKLCh (e.g. frost-greyed soil from regolith)
  noise      value_noise() periodic lattice noise, torus_simplex() 4D simplex sampled on a torus (seamless),
             noise_mix() weighted octaves + white grain, worley() toroidal cellular noise with Lloyd relaxation,
             periodic_curve() seamless 1D offsets, torus_path() closed curves on the torus (veins, cracks)
  quantize   quantize() quantile thresholds -> exact palette proportions, ordered_dither() Bayer 4x4,
             despeckle() removes most isolated pixels so noise reads as clusters
  canvas     Canvas: every pixel is a (ramp, tone) pair, so shading can step a pixel along its own ramp
  stamping   blob_cells()/nugget()/grow_cluster() cluster shapes, place_on_torus() (best-candidate spacing),
             stamp_minerals() ore nuggets / crystals with top-left light, dark rims, satellites and a drop
             shadow on the host rock
  patterns   brick_layout()/draw_bricks(), draw_polished_frame(), band_rows(), lamina_field() (seamless
             horizontal or inclined laminae), draw_path()/thin_mask(), bar_mask()
  items      frustum()/prism()/gem_mesh()/deform() meshes, view() camera, render_mesh() z-buffered flat
             shading + paint_mesh(), lump_field()/lump_item() metaball chunks, add_outline() vanilla two-tone
             outline
  previews   contact_sheet(), vanilla_comparison(), distance_view(), iso_scene()

To add a texture: write a function that returns a Canvas (or an RGBA uint8 array), decorate it with
@block("name") or @item("name"), seed it with rng_for("name"), and run the script; it appears in every preview.
"""
from __future__ import annotations

import argparse
import math
import sys
import zlib
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
BLOCK_DIR = ROOT / "src/client/resources/assets/redplanet/textures/block"
ITEM_DIR = ROOT / "src/client/resources/assets/redplanet/textures/item"
ICON_PATH = ROOT / "src/main/resources/assets/redplanet/icon.png"
PREVIEW_DIR = HERE / "preview"
# TES bolometric albedo (Christensen et al. 2001), rendered by tools/mars-data/build_mars_data.py. Used only
# to place the real dark/bright regions on the icon's globe; a procedural map is used if it is missing.
ALBEDO_MAP = ROOT / "docs/images/mars-albedo.png"
DEFAULT_VANILLA = Path("/home/user/mcsrc/jar-client/assets/minecraft/textures")

N = 16
GLOBAL_SEED = 0x6D617273  # "mars"

N4 = ((-1, 0), (1, 0), (0, -1), (0, 1))
N8 = N4 + ((-1, -1), (-1, 1), (1, -1), (1, 1))


# =====================================================================================================
# Colour
# =====================================================================================================

def hexc(h: str) -> tuple[int, int, int]:
    """'#rrggbb' -> (r, g, b)."""
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def _srgb_to_lin(c):
    c = np.asarray(c, float) / 255.0
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def _lin_to_srgb(c):
    c = np.clip(np.asarray(c, float), 0.0, 1.0)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1 / 2.4) - 0.055) * 255.0


_M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929],
                [0.2119034982, 0.6806995451, 0.1073969566],
                [0.0883024619, 0.2817188376, 0.6299787005]])
_M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468],
                [1.9779984951, -2.4285922050, 0.4505937099],
                [0.0259040371, 0.7827717662, -0.8086757660]])
_M2I = np.linalg.inv(_M2)
_M1I = np.linalg.inv(_M1)


def rgb_to_oklab(rgb):
    """sRGB 0..255 (any shape [..., 3]) -> OKLab."""
    return np.cbrt(_srgb_to_lin(rgb) @ _M1.T) @ _M2.T


def oklab_to_rgb(lab):
    """OKLab -> sRGB floats 0..255."""
    lms = np.asarray(lab, float) @ _M2I.T
    return _lin_to_srgb((lms ** 3) @ _M1I.T)


def _to8(rgb) -> tuple[int, int, int]:
    return tuple(int(round(float(v))) for v in np.clip(rgb, 0, 255))


def adjust(c, dl: float = 0.0, chroma: float = 1.0, dh: float = 0.0) -> tuple[int, int, int]:
    """Shift OKLCh lightness by dl (0..1 scale), scale chroma, rotate hue by dh degrees."""
    L, a, b = rgb_to_oklab(hexc(c) if isinstance(c, str) else c)
    C = math.hypot(a, b) * chroma
    h = math.atan2(b, a) + math.radians(dh)
    return _to8(oklab_to_rgb([L + dl, C * math.cos(h), C * math.sin(h)]))


def ramp(anchors, n: int | None = None) -> list[tuple[int, int, int]]:
    """A colour ramp (dark -> light) through hex anchors, resampled to n colours in OKLab."""
    cols = [hexc(a) if isinstance(a, str) else tuple(a) for a in anchors]
    if n is None or n == len(cols):
        return cols
    labs = np.array([rgb_to_oklab(c) for c in cols])
    out = []
    for p in np.linspace(0, len(cols) - 1, n):
        i = min(int(p), len(cols) - 2)
        t = p - i
        out.append(_to8(oklab_to_rgb(labs[i] * (1 - t) + labs[i + 1] * t)))
    return out


# =====================================================================================================
# Deterministic randomness and seamless noise
# =====================================================================================================

def rng_for(name: str, salt: str = "") -> np.random.Generator:
    """Independent, reproducible generator per texture (CRC32 is stable across Python versions)."""
    return np.random.default_rng((zlib.crc32(f"{name}|{salt}".encode()) ^ GLOBAL_SEED) & 0xFFFFFFFF)


def pixel_grid(n: int = N):
    """Pixel-centre coordinates (xs, ys), each n x n."""
    ys, xs = np.mgrid[0:n, 0:n].astype(float)
    return xs + 0.5, ys + 0.5


def normalize(f):
    f = np.asarray(f, float)
    f = f - f.mean()
    s = f.std()
    return f / s if s > 1e-9 else f


def value_noise(rng, cx: int, cy: int | None = None, n: int = N):
    """Periodic value noise: a cx x cy random lattice, smoothstep-interpolated, wrapping at the tile edge.
    cx == n gives one independent value per pixel column."""
    cy = cx if cy is None else cy
    lat = rng.random((cy, cx))
    xs = (np.arange(n) + 0.5) * cx / n - 0.5
    ys = (np.arange(n) + 0.5) * cy / n - 0.5
    x0 = np.floor(xs).astype(int)
    y0 = np.floor(ys).astype(int)
    tx, ty = xs - x0, ys - y0
    sx, sy = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
    xa, xb = x0 % cx, (x0 + 1) % cx
    ya, yb = y0 % cy, (y0 + 1) % cy
    a, b = lat[np.ix_(ya, xa)], lat[np.ix_(ya, xb)]
    c, d = lat[np.ix_(yb, xa)], lat[np.ix_(yb, xb)]
    top = a + (b - a) * sx[None, :]
    bot = c + (d - c) * sx[None, :]
    return top + (bot - top) * sy[:, None]


_GRAD4 = np.array([[0, 1, 1, 1], [0, 1, 1, -1], [0, 1, -1, 1], [0, 1, -1, -1],
                   [0, -1, 1, 1], [0, -1, 1, -1], [0, -1, -1, 1], [0, -1, -1, -1],
                   [1, 0, 1, 1], [1, 0, 1, -1], [1, 0, -1, 1], [1, 0, -1, -1],
                   [-1, 0, 1, 1], [-1, 0, 1, -1], [-1, 0, -1, 1], [-1, 0, -1, -1],
                   [1, 1, 0, 1], [1, 1, 0, -1], [1, -1, 0, 1], [1, -1, 0, -1],
                   [-1, 1, 0, 1], [-1, 1, 0, -1], [-1, -1, 0, 1], [-1, -1, 0, -1],
                   [1, 1, 1, 0], [1, 1, -1, 0], [1, -1, 1, 0], [1, -1, -1, 0],
                   [-1, 1, 1, 0], [-1, 1, -1, 0], [-1, -1, 1, 0], [-1, -1, -1, 0]], float)


def _simplex4(x, y, z, w, perm):
    """Vectorised 4D simplex noise (Gustavson's formulation), roughly in [-1, 1]."""
    F4 = (math.sqrt(5.0) - 1.0) / 4.0
    G4 = (5.0 - math.sqrt(5.0)) / 20.0
    s = (x + y + z + w) * F4
    i, j, k, l = np.floor(x + s), np.floor(y + s), np.floor(z + s), np.floor(w + s)
    t = (i + j + k + l) * G4
    x0, y0, z0, w0 = x - (i - t), y - (j - t), z - (k - t), w - (l - t)
    rx = (x0 > y0).astype(int) + (x0 > z0) + (x0 > w0)
    ry = (x0 <= y0).astype(int) + (y0 > z0) + (y0 > w0)
    rz = (x0 <= z0).astype(int) + (y0 <= z0) + (z0 > w0)
    rw = (x0 <= w0).astype(int) + (y0 <= w0) + (z0 <= w0)
    ii, jj, kk, ll = (i.astype(np.int64) & 255, j.astype(np.int64) & 255,
                      k.astype(np.int64) & 255, l.astype(np.int64) & 255)
    total = np.zeros_like(x, dtype=float)
    corners = [(0, 0, 0, 0, 0.0)]
    for thr, g in ((3, 1), (2, 2), (1, 3)):
        corners.append(((rx >= thr).astype(int), (ry >= thr).astype(int),
                        (rz >= thr).astype(int), (rw >= thr).astype(int), g * G4))
    corners.append((1, 1, 1, 1, 4 * G4))
    for oi, oj, ok, ol, g in corners:
        dx, dy, dz, dw = x0 - oi + g, y0 - oj + g, z0 - ok + g, w0 - ol + g
        gi = perm[ii + oi + perm[jj + oj + perm[kk + ok + perm[ll + ol]]]] % 32
        tt = 0.6 - dx * dx - dy * dy - dz * dz - dw * dw
        gr = _GRAD4[gi]
        dot = gr[..., 0] * dx + gr[..., 1] * dy + gr[..., 2] * dz + gr[..., 3] * dw
        total += np.where(tt > 0, tt ** 4 * dot, 0.0)
    return 27.0 * total


def torus_simplex(rng, fx: float, fy: float | None = None, n: int = N, octaves: int = 1,
                  persistence: float = 0.5, lacunarity: float = 2.0):
    """Seamless simplex noise: the tile's x and y are mapped onto two circles of a 4D torus, so the result
    wraps perfectly. fx / fy are the approximate number of noise features across the tile in x / y
    (fx < fy stretches features horizontally, like vanilla stone; fx > fy makes vertical streaks)."""
    fy = fx if fy is None else fy
    perm = rng.permutation(256)
    perm = np.concatenate([perm, perm, perm[:8]]).astype(np.int64)
    off = rng.uniform(-64, 64, 4)
    xs, ys = pixel_grid(n)
    u, v = 2 * np.pi * xs / n, 2 * np.pi * ys / n
    total, amp, norm = np.zeros((n, n)), 1.0, 0.0
    for o in range(octaves):
        rx = fx * lacunarity ** o / (2 * np.pi)
        ry = fy * lacunarity ** o / (2 * np.pi)
        total += amp * _simplex4(rx * np.cos(u) + off[0], rx * np.sin(u) + off[1],
                                 ry * np.cos(v) + off[2], ry * np.sin(v) + off[3], perm)
        norm += amp
        amp *= persistence
    return total / norm


def noise_mix(rng, comps, white: float = 0.0, n: int = N):
    """Normalised sum of torus-simplex components [(fx, fy, weight), ...] plus `white` per-pixel grain."""
    f = np.zeros((n, n))
    for fx, fy, w in comps:
        f += w * normalize(torus_simplex(rng, fx, fy, n))
    if white:
        f += white * normalize(rng.random((n, n)))
    return normalize(f)


def toroidal_delta(a, b, n: float = N):
    """Signed shortest difference a - b on a circle of circumference n."""
    return (np.asarray(a, float) - b + n / 2) % n - n / 2


def worley(rng, count: int, n: int = N, relax: int = 0, points=None):
    """Toroidal cellular (Worley) noise. Returns dict with F1, F2 (distances to nearest / second point),
    id (nearest point index), dx, dy (offset of the pixel from its point) and the points. `relax` Lloyd
    iterations even out the cell sizes (cobblestone, basalt columns)."""
    pts = rng.random((count, 2)) * n if points is None else np.array(points, float)
    xs, ys = pixel_grid(n)
    for _ in range(relax):
        sx, sy = np.meshgrid((np.arange(n * 4) + 0.5) / 4, (np.arange(n * 4) + 0.5) / 4)
        d = np.hypot(toroidal_delta(sx[..., None], pts[:, 0], n), toroidal_delta(sy[..., None], pts[:, 1], n))
        near = d.argmin(-1)
        for c in range(len(pts)):
            m = near == c
            if not m.any():
                continue
            for axis, coords in ((0, sx), (1, sy)):
                ang = coords[m] / n * 2 * np.pi
                pts[c, axis] = (math.atan2(np.sin(ang).mean(), np.cos(ang).mean()) / (2 * np.pi) * n) % n
    dx = toroidal_delta(xs[..., None], pts[:, 0], n)
    dy = toroidal_delta(ys[..., None], pts[:, 1], n)
    d = np.hypot(dx, dy)
    order = np.argsort(d, axis=-1)
    nid = order[..., 0]
    take = lambda a, k: np.take_along_axis(a, order[..., k:k + 1], -1)[..., 0]
    return {"F1": take(d, 0), "F2": take(d, 1), "id": nid, "id2": order[..., 1],
            "dx": take(dx, 0), "dy": take(dy, 0), "points": pts}


def periodic_curve(rng, n: int = N, amp: float = 1.0, harmonics=(1, 2)):
    """Seamless 1D offset curve (n samples) with integer harmonics, scaled so max |value| == amp."""
    x = np.arange(n) + 0.5
    c = np.zeros(n)
    for h in harmonics:
        c += rng.normal() * np.sin(2 * np.pi * h * x / n + rng.uniform(0, 2 * np.pi)) / h
    m = np.abs(c).max()
    return c / m * amp if m > 1e-9 else c


def torus_path(rng, winding=(1, 0), n: int = N, start=None, wobble: float = 1.0, harmonics=(1, 2, 3),
               samples: int = 400):
    """A closed curve on the torus winding (wx, wy) times around x / y, with periodic wobble perpendicular
    to its direction. Returns float points (t-ordered); draw with draw_path(). Seamless by construction."""
    wx, wy = winding
    sx, sy = (rng.uniform(0, n), rng.uniform(0, n)) if start is None else start
    t = np.linspace(0, 1, samples, endpoint=False)
    length = math.hypot(wx, wy) or 1.0
    px, py = -wy / length, wx / length  # unit normal
    off = np.zeros_like(t)
    for h in harmonics:
        off += rng.normal() * np.sin(2 * np.pi * h * t + rng.uniform(0, 2 * np.pi)) / h
    m = np.abs(off).max()
    off = off / m * wobble if m > 1e-9 else off
    return np.stack([sx + wx * n * t + px * off, sy + wy * n * t + py * off], -1)


# =====================================================================================================
# Quantisation and clean-up
# =====================================================================================================

def quantize(field, weights):
    """Map a scalar field to tones 0..len(weights)-1 so that tone k covers ~weights[k] of the pixels
    (quantile thresholds: exact control over how much of each palette colour appears)."""
    w = np.asarray(weights, float)
    w = w / w.sum()
    cuts = np.quantile(field, np.cumsum(w)[:-1])
    return np.searchsorted(cuts, field, side="right").astype(np.int16)


BAYER4 = (np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]) + 0.5) / 16.0 - 0.5


def ordered_dither(levels_field, n_levels: int, strength=1.0):
    """Ordered (Bayer 4x4) dither of a field expressed in level units (0..n_levels-1). The matrix tiles
    every 4 px, so a dithered 16 px texture still wraps. `strength` may be a per-pixel array."""
    h, w = levels_field.shape
    b = np.tile(BAYER4, (h // 4 + 1, w // 4 + 1))[:h, :w]
    return np.clip(np.floor(levels_field + 0.5 + strength * b), 0, n_levels - 1).astype(np.int16)


def roll2(a, dy: int, dx: int):
    """Toroidal shift: result[y, x] = a[y - dy, x - dx]."""
    return np.roll(np.roll(a, dy, 0), dx, 1)


def shift0(a, dy: int, dx: int, fill=0):
    """Non-wrapping shift (items): result[y, x] = a[y - dy, x - dx]."""
    out = np.full_like(a, fill)
    h, w = a.shape[:2]
    ys, yd = (slice(0, h - dy), slice(dy, h)) if dy >= 0 else (slice(-dy, h), slice(0, h + dy))
    xs, xd = (slice(0, w - dx), slice(dx, w)) if dx >= 0 else (slice(-dx, w), slice(0, w + dx))
    out[yd, xd] = a[ys, xs]
    return out


def despeckle(t, rng, keep: float = 0.3, protect=None, wrap: bool = True):
    """Replace most isolated pixels (no equal 8-neighbour) with the dominant neighbouring tone; `keep` is the
    fraction kept for grain. Turns salt-and-pepper noise into the small clusters vanilla textures use."""
    t = t.copy()
    sh = roll2 if wrap else shift0
    same = sum((sh(t, dy, dx) == t).astype(int) for dy, dx in N8)
    iso = same == 0
    if protect is not None:
        iso &= ~protect
    drop = iso & (rng.random(t.shape) >= keep)
    nb = np.stack([sh(t, dy, dx) for dy, dx in N4])
    for y, x in zip(*np.nonzero(drop)):
        vals, cnt = np.unique(nb[:, y, x], return_counts=True)
        best = vals[cnt == cnt.max()]
        t[y, x] = best[np.argmin(np.abs(best - t[y, x]))]
    return t


def dilate(mask, steps: int = 1, diag: bool = False, wrap: bool = True):
    m = mask.copy()
    sh = roll2 if wrap else shift0
    for _ in range(steps):
        acc = m.copy()
        for dy, dx in (N8 if diag else N4):
            acc |= sh(m, dy, dx)
        m = acc
    return m


# =====================================================================================================
# Canvas
# =====================================================================================================

class Canvas:
    """A texture as per-pixel (ramp id, tone index) pairs. Ramps are dark -> light colour lists, so
    `shift()` lightens or darkens a pixel along its own material's ramp."""

    def __init__(self, n: int = N):
        self.n = n
        self.ramps: list[list[tuple[int, int, int]]] = []
        self.layer = np.full((n, n), -1, np.int16)  # -1 = transparent
        self.tone = np.zeros((n, n), np.int16)
        self.alpha = np.full((n, n), 255, np.uint8)

    def add(self, colors) -> int:
        self.ramps.append([hexc(c) if isinstance(c, str) else tuple(int(v) for v in c) for c in colors])
        return len(self.ramps) - 1

    def fill(self, rid: int, tone) -> "Canvas":
        self.layer[:] = rid
        self.tone[:] = tone
        return self

    def put(self, mask, rid: int, tone) -> None:
        mask = np.asarray(mask, bool)
        self.layer[mask] = rid
        self.tone[mask] = tone if np.isscalar(tone) else np.asarray(tone)[mask]

    def shift(self, mask, delta: int, rid: int | None = None) -> None:
        m = np.asarray(mask, bool).copy()
        if rid is not None:
            m &= self.layer == rid
        self.tone[m] += delta
        self.clamp()

    def clamp(self) -> None:
        for rid, cols in enumerate(self.ramps):
            m = self.layer == rid
            self.tone[m] = np.clip(self.tone[m], 0, len(cols) - 1)

    def rgba(self) -> np.ndarray:
        self.clamp()
        out = np.zeros((self.n, self.n, 4), np.uint8)
        for rid, cols in enumerate(self.ramps):
            m = self.layer == rid
            if m.any():
                out[m, :3] = np.array(cols, np.uint8)[self.tone[m]]
        out[..., 3] = np.where(self.layer >= 0, self.alpha, 0)
        return out


# =====================================================================================================
# Mineral clusters (ores, pebbles, spherules)
# =====================================================================================================

def grow_cluster(rng, size: int, compact: float = 2.0, elong=(1.0, 1.0), start=None):
    """A 4-connected blob of `size` pixels grown from (0, 0) (or from the cells in `start`). `compact`
    biases growth toward candidate cells with many filled neighbours (round nuggets); 0 gives stringy
    random walks. `elong` = (wy, wx) weights vertical / horizontal growth. Returns a list of (dy, dx)."""
    cells = set(start) if start else {(0, 0)}
    while len(cells) < size:
        cand = {}
        for (y, x) in cells:
            for dy, dx in N4:
                p = (y + dy, x + dx)
                if p in cells:
                    continue
                k = sum((p[0] + a, p[1] + b) in cells for a, b in N4)
                dirw = elong[0] if dx == 0 else elong[1]
                cand[p] = max(cand.get(p, 0), (k ** compact) * dirw)
        ps = sorted(cand)
        w = np.array([cand[p] for p in ps], float)
        cells.add(ps[rng.choice(len(ps), p=w / w.sum())])
    return sorted(cells)


def blob_cells(rng, size: int, elong=(0.75, 1.6), roughness: float = 0.18):
    """Exactly `size` pixels forming a rough ellipse (random elongation and angle): the `size` pixel
    centres with the smallest noisy elliptical radius. Chunky enough for top-left lighting to read."""
    e = rng.uniform(*elong)
    ang = rng.uniform(0, np.pi)
    a, b = math.sqrt(size / math.pi * e), math.sqrt(size / math.pi / e)
    R = int(math.ceil(max(a, b))) + 2
    ys, xs = np.mgrid[-R:R + 1, -R:R + 1]
    cx, cy = rng.uniform(-0.5, 0.5, 2)
    u = (xs - cx) * math.cos(ang) + (ys - cy) * math.sin(ang)
    v = -(xs - cx) * math.sin(ang) + (ys - cy) * math.cos(ang)
    r = (u / a) ** 2 + (v / b) ** 2 + rng.normal(0, roughness, xs.shape)
    idx = np.argsort(r.ravel(), kind="stable")[:size]
    return sorted(zip(ys.ravel()[idx].tolist(), xs.ravel()[idx].tolist()))


def nugget(rng, size: int, lobes: int = 1):
    """An ore nugget of ~size pixels: one rough ellipse, or 2-3 overlapping ones for larger clusters
    (vanilla ore clusters are usually two or three touching lumps)."""
    if lobes <= 1 or size < 7:
        return blob_cells(rng, size)
    parts = np.maximum(3, np.round(rng.dirichlet(np.ones(lobes) * 4) * size * 1.15)).astype(int)
    cells = set(blob_cells(rng, int(parts[0])))
    for sz in parts[1:]:
        ys = [c[0] for c in cells]
        xs = [c[1] for c in cells]
        ang = rng.uniform(0, 2 * np.pi)
        rad = 0.5 * (max(max(xs) - min(xs), max(ys) - min(ys)) + 1)
        oy = int(round(np.mean(ys) + math.sin(ang) * rad))
        ox = int(round(np.mean(xs) + math.cos(ang) * rad))
        cells |= {(oy + dy, ox + dx) for dy, dx in blob_cells(rng, int(sz))}
    return sorted(cells)


def shape_mask(cells, oy: int, ox: int, n: int = N):
    m = np.zeros((n, n), bool)
    for dy, dx in cells:
        m[(oy + dy) % n, (ox + dx) % n] = True
    return m


def place_on_torus(rng, shapes, n: int = N, gap: int = 1, occupied=None, avoid=None, candidates: int = 16,
                   tries: int = 400):
    """Place cluster shapes without overlap (at least `gap` px apart), spreading them evenly with
    best-candidate sampling (each placement maximises the distance to earlier ones). `avoid` is a mask of
    forbidden pixels. Returns a list of pixel masks."""
    occ = np.zeros((n, n), bool) if occupied is None else occupied.copy()
    forbid = np.zeros((n, n), bool) if avoid is None else avoid.copy()
    centres, placed = [], []
    for cells in shapes:
        best, best_score = None, -1.0
        found = 0
        for _ in range(tries):
            oy, ox = (int(v) for v in rng.integers(0, n, 2))
            m = shape_mask(cells, oy, ox, n)
            if (dilate(m, gap, diag=True) & occ).any() or (m & forbid).any():
                continue
            cy = oy + np.mean([c[0] for c in cells])
            cx = ox + np.mean([c[1] for c in cells])
            score = min((math.hypot(toroidal_delta(cx, px, n), toroidal_delta(cy, py, n))
                         for py, px in centres), default=1e9)
            score += rng.random() * 0.5
            if score > best_score:
                best, best_score, best_c = m, score, (cy, cx)
            found += 1
            if found >= candidates:
                break
        if best is not None:
            occ |= best
            centres.append(best_c)
            placed.append(best)
    return placed


def light_score(mask, wrap: bool = True):
    """Per-pixel top-left lighting score for a blob: +1 for each exposed top/left edge, -1 for each exposed
    bottom/right edge (range -2..2)."""
    sh = roll2 if wrap else shift0
    up, left = sh(mask, 1, 0), sh(mask, 0, 1)     # neighbour above / to the left is inside?
    down, right = sh(mask, -1, 0), sh(mask, 0, -1)
    return ((~up).astype(int) + (~left) - (~down) - (~right)) * mask


def erode(mask, wrap: bool = True):
    sh = roll2 if wrap else shift0
    m = mask.copy()
    for dy, dx in N4:
        m &= sh(mask, dy, dx)
    return m


def unwrap_coords(m, n: int = N):
    """Pixel coordinates of a (possibly edge-wrapping) cluster, unwrapped around its first pixel."""
    ys, xs = np.nonzero(m)
    ys = ys + np.where(ys - ys[0] > n / 2, -n, 0) + np.where(ys - ys[0] < -n / 2, n, 0)
    xs = xs + np.where(xs - xs[0] > n / 2, -n, 0) + np.where(xs - xs[0] < -n / 2, n, 0)
    return ys, xs


def stamp_minerals(cv: Canvas, masks, ore_rid: int, host_rid: int | None, mid: int, rng=None,
                   style: str = "nugget", shadow: int = 1, glint: int | None = None, glint_prob: float = 0.0,
                   rim: int | None = None, rim_min: int = 4, satellites: int = 0, wrap: bool = True):
    """Paint mineral clusters onto a host texture.

    style 'nugget'  : tone = mid + light_score, +1 inside (rounded nugget lit from the top left).
    style 'crystal' : tone falls off along the top-left -> bottom-right diagonal of each cluster, so larger
                      clusters read as a lit facet and a shaded facet.
    rim             : tone for exposed bottom/right edge pixels of clusters with >= rim_min pixels (the dark
                      outline vanilla ores have).
    shadow          : how many tones to darken host pixels directly below / right of a cluster.
    glint           : tone for an extra-bright pixel at the most lit spot (with probability glint_prob).
    satellites      : up to this many loose ore pixels (in the rim tone + 1) scattered next to each cluster,
                      like the stained fringe around vanilla iron nuggets."""
    rng = rng or np.random.default_rng(0)
    sh = roll2 if wrap else shift0
    allm = np.zeros_like(cv.layer, bool)
    for m in masks:
        allm |= m
    for m in list(masks):
        if satellites and m.sum() >= 5:
            ring = dilate(m, 1, diag=True, wrap=wrap) & ~m              # touching this cluster ...
            ring &= ~dilate(allm & ~m, 1, diag=True, wrap=wrap)       # ... but not any other one
            cand = np.argwhere(ring)
            if len(cand):
                k = int(rng.integers(1, satellites + 1))
                for y, x in cand[rng.permutation(len(cand))[:k]]:
                    cv.layer[y, x], cv.tone[y, x] = ore_rid, (rim if rim is not None else mid - 1) + 1
                    allm[y, x] = True
    for m in masks:
        sc = light_score(m, wrap)
        if style == "crystal":
            ys, xs = unwrap_coords(m, cv.n) if wrap else np.nonzero(m)
            d = (xs - xs.mean()) + (ys - ys.mean())
            span = max(1.0, (d.max() - d.min()) / 2)
            tone = np.zeros_like(cv.tone)
            tone[np.nonzero(m)] = np.round(mid - d / span * 1.25).astype(int)
            tone = np.where(sc >= 2, tone + 1, tone)
        else:
            inner = erode(m, wrap)
            tone = mid + np.clip(sc, -1, 1) + inner.astype(int)
            tone = np.where(inner & (light_score(inner, wrap) >= 1), tone + 1, tone)
        cv.put(m, ore_rid, tone)
        if rim is not None and m.sum() >= rim_min:
            exposed_br = m & ((~sh(m, -1, 0)) | (~sh(m, 0, -1))) & (sc < 0)
            cv.put(exposed_br, ore_rid, rim)
        if glint is not None and rng.random() < glint_prob:
            cand = np.argwhere(m & (sc == sc[m].max()))
            y, x = cand[rng.integers(len(cand))]
            cv.tone[y, x] = glint
    if host_rid is not None and shadow:
        below_right = (sh(allm, 1, 0) | sh(allm, 0, 1)) & ~allm
        cv.shift(below_right, -shadow, host_rid)
    cv.clamp()
    return allm


# =====================================================================================================
# Patterns: bricks, polished frames, bands, paths
# =====================================================================================================

def brick_layout(courses, n: int = N):
    """courses: [(height, [joint x, ...]), ...] from the top; heights sum to n. The last row of each course
    is the bed joint; joints are 1 px vertical mortar columns. Returns (brick_id, row, col, h, w) arrays:
    brick_id -1 = mortar; row/col = position inside the brick; h/w = brick size (wrapping is handled)."""
    bid = np.full((n, n), -1, int)
    row = np.zeros((n, n), int)
    col = np.zeros((n, n), int)
    bh = np.zeros((n, n), int)
    bw = np.zeros((n, n), int)
    y0, next_id = 0, 0
    for height, joints in courses:
        joints = sorted(j % n for j in joints)
        for k, j in enumerate(joints):
            start = j + 1
            end = joints[(k + 1) % len(joints)] + (n if k + 1 == len(joints) else 0)
            width = end - start
            for i in range(width):
                x = (start + i) % n
                for r in range(height - 1):
                    y = y0 + r
                    bid[y, x], row[y, x], col[y, x], bh[y, x], bw[y, x] = next_id, r, i, height - 1, width
            next_id += 1
        y0 += height
    assert y0 == n, "course heights must sum to the tile size"
    return bid, row, col, bh, bw


def draw_bricks(cv: Canvas, rid: int, courses, interior, mortar_tone, hi: int = 2, lo: int = -1,
                rng=None, tone_jitter: int = 1):
    """Bricks with a bevel: the top row and left column of each brick are lit (+hi on the top edge,
    left edge one less), the bottom row and right column shaded (lo). `interior` is a tone map for the
    brick faces; each brick also gets a random +-tone_jitter offset so neighbours differ slightly."""
    rng = rng or np.random.default_rng(1)
    bid, row, col, bh, bw = brick_layout(courses)
    tone = interior.copy()
    offs = rng.integers(-tone_jitter, tone_jitter + 1, bid.max() + 1) if tone_jitter else np.zeros(bid.max() + 1, int)
    b = bid >= 0
    tone[b] += offs[bid[b]]
    top = b & (row == 0)
    left = b & (col == 0) & ~top
    bottom = b & (row == bh - 1)
    right = b & (col == bw - 1) & ~top
    tone[top] = np.maximum(tone[top], interior.max() - 1) + hi - 1
    tone[left] = np.maximum(tone[left], interior.max() - 1) + hi - 2
    tone[bottom & ~left] += lo
    tone[right & ~bottom] += lo
    tone[bottom & right] += lo  # darkest corner
    tone[~b] = mortar_tone if np.isscalar(mortar_tone) else np.asarray(mortar_tone)[~b]
    cv.put(np.ones_like(b), rid, tone)
    cv.clamp()
    return bid


def draw_polished_frame(cv: Canvas, rid: int, light: int, dark: int, corner_dark: int | None = None,
                        inset: int = 0):
    """A one-pixel bevelled frame: top and left edges light, bottom and right edges dark."""
    n = cv.n
    a, b = inset, n - 1 - inset
    cv.tone[a, a:b + 1] = light
    cv.tone[a:b + 1, a] = light
    cv.tone[b, a:b + 1] = dark
    cv.tone[a:b + 1, b] = dark
    cv.tone[b, a] = (light + dark) // 2
    cv.tone[a, b] = (light + dark) // 2
    if corner_dark is not None:
        cv.tone[b, b] = corner_dark
    cv.layer[a, a:b + 1] = rid
    cv.layer[a:b + 1, a] = rid
    cv.layer[b, a:b + 1] = rid
    cv.layer[a:b + 1, b] = rid


def band_rows(rng, pattern, n: int = N):
    """Lay out horizontal bands. pattern: list of (min_h, max_h, kind) cycled from the top until n rows are
    filled exactly (the last band is trimmed / the first stretched so the stack tiles vertically).
    Returns per-row band index and the list of (kind, height)."""
    bands, total, i = [], 0, 0
    while total < n:
        lo, hi, kind = pattern[i % len(pattern)]
        h = int(rng.integers(lo, hi + 1))
        bands.append([kind, h])
        total += h
        i += 1
    bands[-1][1] -= total - n
    if bands[-1][1] <= 0:
        bands.pop()
        bands[0][1] += n - sum(b[1] for b in bands)
    rows = np.concatenate([[k] * h for k, (_, h) in enumerate(bands)])
    return rows, bands


def draw_path(points, n: int = N, width: float = 1.0):
    """Rasterise float torus points into a mask (wrapping), with a square brush of the given width."""
    m = np.zeros((n, n), bool)
    r = (width - 1) / 2
    for x, y in points:
        for oy in np.arange(-r, r + 0.01):
            for ox in np.arange(-r, r + 0.01):
                m[int(math.floor(y + oy)) % n, int(math.floor(x + ox)) % n] = True
    return m


def thin_mask(mask, wrap: bool = True):
    """Remove pixels that only touch the path diagonally-redundantly: keeps 4-connected 1 px lines tidy
    by dropping 'elbow' pixels whose removal keeps both neighbours 8-connected."""
    m = mask.copy()
    sh = roll2 if wrap else shift0
    for (a, b), (c, d) in (((-1, 0), (0, 1)), ((0, 1), (1, 0)), ((1, 0), (0, -1)), ((0, -1), (-1, 0))):
        na, nb = sh(m, -a, -b), sh(m, -c, -d)
        opp1, opp2 = sh(m, a, b), sh(m, c, d)
        elbow = m & na & nb & ~opp1 & ~opp2
        m &= ~elbow
    return m


# =====================================================================================================
# Palettes (dark -> light). Anchors from the art brief (white-balanced Mastcam / Mastcam-Z true colour);
# the minerals themselves are documented in docs/SCIENCE.md section 14, "Terrain and geology".
# =====================================================================================================

PAL = {
    # bright-to-dark rust soil: nanophase ferric oxide coatings over basaltic grains
    "regolith": ["#62392a", "#6e3f2b", "#804a32", "#8f5235", "#9e5c3b", "#a9633f", "#b9744a"],
    "regolith_clast": ["#3b302d", "#4f423c", "#665650"],
    # airfall dust: brighter butterscotch-orange, very fine
    "dust": ["#b4744a", "#bd7d50", "#c58555", "#cd8e5d", "#d49765", "#dca270"],
    # Bagnold-dune style basaltic sand: dark grey with a faint warm-brown cast
    "basaltic_sand": ["#262221", "#2e2a29", "#363130", "#3d3634", "#4a4240", "#564d4a", "#665b56"],
    "spherule": ["#262022", "#352d31", "#463d42", "#5c5257", "#7a6f75"],
    "ground_ice": ["#a8a29b", "#c6c3be", "#dddddb", "#efefee"],
    # reddish-brown basaltic rock and impact breccia
    "mars_stone": ["#4c3229", "#5a3c31", "#654437", "#6e4a3c", "#795145", "#86594b"],
    "mars_cobble": ["#3b271f", "#4b3229", "#583b30", "#654437", "#714d3e", "#7e5646", "#8c614f", "#9a6c58"],
    "mars_bricks": ["#3a261f", "#4c3229", "#5a3c31", "#654438", "#6e4a3c", "#7a5245", "#87604f", "#9a705f"],
    # dark basalt, slightly warm
    "mars_basalt": ["#25211f", "#2e2927", "#37312f", "#413a37", "#4b4340", "#564d49", "#635954", "#716660"],
    # lake-bed mudstone (Yellowknife Bay / Murray formation): pale grey-tan
    "mudstone": ["#857663", "#928370", "#9d8d79", "#a89884", "#b4a48f", "#bfb09b", "#cabca8"],
    # sulfate-bearing layered sediment (Mount Sharp): light and dark tan
    "sediment": ["#6c5440", "#7b6149", "#8a6f53", "#9a7d5e", "#a98c6a", "#b89b77", "#c6ab87", "#d3ba98"],
    # Jezero delta: pale grey-brown fine sandstone / mudstone
    "delta": ["#746a5e", "#82776b", "#8f8477", "#9c9183", "#a99e90", "#b6ac9e", "#c3baad"],
    # Fe/Mg smectite: greenish-grey to teal-brown
    "smectite": ["#4f5a55", "#5c6660", "#69726a", "#767d73", "#83877b", "#919283", "#9e9c8c"],
    # Mg-carbonate-bearing rock (Jezero margin): light grey-green
    "carbonate": ["#838a7a", "#909786", "#9da493", "#aab09f", "#b7bcac", "#c5c9ba", "#d3d6ca"],
    # polar ices
    "polar_ice": ["#c3cad0", "#d1d7dc", "#dee3e6", "#e9edef", "#f3f5f6", "#fbfbfb"],
    "polar_dust": ["#9f8a71", "#b39e85", "#c6b49c", "#d6c7b3"],
    "water_ice": ["#94b6dc", "#a5c3e4", "#b6d0eb", "#c8ddf2", "#dbe9f7", "#eef5fc"],
    "pld_ice": ["#cbc8c1", "#dad7d0", "#e7e5e0", "#f2f1ee", "#fafaf8"],
    "pld_dust": ["#8f775e", "#a48b70", "#b8a084", "#c9b398", "#d6c4ac"],
    "co2_ice": ["#adbfd4", "#bccde0", "#cbd9ea", "#d9e5f2", "#e6eff8", "#f2f7fc", "#fdfeff"],
    "dry_ice": ["#b9c6d3", "#c7d2dd", "#d4dee7", "#e1e8ef", "#ecf1f6", "#f6f9fb"],
    "co2_frost": ["#c9dbee", "#d7e5f3", "#e3eef8", "#eef5fb", "#f8fbfe", "#ffffff"],
    # ores
    "hematite": ["#45383f", "#665860", "#857a81", "#a59ba2", "#c3bbc0", "#e2dce0"],
    "olivine": ["#3c5220", "#56742a", "#7a9a3a", "#a4c14b", "#c3da6c", "#e2efa0"],
    "jarosite": ["#614a1c", "#86682a", "#9c7a2a", "#b38f33", "#c9a23a", "#dcb957"],
    "gypsum": ["#c9c1b2", "#ddd8cd", "#ece9e2", "#f8f7f3", "#ffffff"],
    "sulfur": ["#7e6a18", "#a48d22", "#c9b232", "#e8d44a", "#f3e57c", "#fbf3b8"],
    "chromite": ["#1a1311", "#2c221e", "#43352e", "#5e4b40"],
    "chromite_glint": ["#b3aea8", "#eeebe7"],
    "chromite_deep": ["#120d0c", "#2a211d", "#4a3c34", "#6e5c50"],
    "meteorite": ["#323236", "#3e3e43", "#4a4a4f", "#57575c", "#65656b", "#75757b", "#88888e"],
    "fusion_crust": ["#2d221c", "#3d2d24", "#4f3a2c", "#624834"],
    # Earth host rocks for chromite (generated here, vanilla-like greys)
    "earth_stone": ["#5f5f5f", "#6a6a6a", "#757575", "#808080", "#8b8b8b", "#969696"],
    "earth_deepslate": ["#2b2b2f", "#36363a", "#414145", "#4c4c50", "#57575c", "#636368"],
}


# ice-cemented soil: the regolith ramp with ~30% less chroma (frost and ice in the pores grey it)
PAL["frozen_soil"] = [adjust(c, dl=-0.01, chroma=0.72) for c in PAL["regolith"][:6]]


# =====================================================================================================
# Block textures
# =====================================================================================================

BLOCKS: dict[str, callable] = {}
ITEMS: dict[str, callable] = {}
SOLID_EXCEPTIONS = {"water_ice"}  # the only translucent block texture


def block(name):
    def reg(fn):
        BLOCKS[name] = fn
        return fn
    return reg


def item(name):
    def reg(fn):
        ITEMS[name] = fn
        return fn
    return reg


# ---------------------------------------------------------------------------------- surface materials

def regolith_canvas() -> Canvas:
    rng = rng_for("regolith")
    cv = Canvas()
    soil = cv.add(PAL["regolith"])
    f = noise_mix(rng, [(3, 3, 1.0), (6, 6, 0.8), (11, 11, 0.5)], white=0.75)
    t = quantize(f, [0.05, 0.12, 0.22, 0.27, 0.20, 0.10, 0.04])
    cv.fill(soil, despeckle(t, rng, keep=0.45))
    return cv


@block("regolith")
def tex_regolith():
    """Mars soil: rusty orange-brown fines (nanophase ferric oxide) with a few dark basaltic pebbles."""
    rng = rng_for("regolith", "clasts")
    cv = regolith_canvas()
    # a few small basaltic pebbles, lit from the top left
    clast = cv.add(PAL["regolith_clast"])
    shapes = [grow_cluster(rng, s, compact=3) for s in (3, 2, 2, 1)]
    stamp_minerals(cv, place_on_torus(rng, shapes, gap=3), clast, 0, mid=1, rng=rng, shadow=1)
    return cv


@block("mars_dust")
def tex_mars_dust():
    """Airfall dust: bright butterscotch-orange, very fine (vanilla sand density)."""
    rng = rng_for("mars_dust")
    cv = Canvas()
    dust = cv.add(PAL["dust"])
    f = noise_mix(rng, [(4, 4, 0.6), (8, 8, 0.5)], white=1.0)
    t = quantize(f, [0.06, 0.18, 0.30, 0.28, 0.14, 0.04])
    cv.fill(dust, despeckle(t, rng, keep=0.6))
    return cv


@block("basaltic_sand")
def tex_basaltic_sand():
    """Dark basaltic dune sand (Bagnold dunes): grey with a faint warm-brown cast."""
    rng = rng_for("basaltic_sand")
    cv = Canvas()
    sand = cv.add(PAL["basaltic_sand"])
    f = noise_mix(rng, [(4, 4, 0.6), (9, 9, 0.5)], white=1.0)
    t = quantize(f, [0.06, 0.14, 0.24, 0.26, 0.18, 0.09, 0.03])
    cv.fill(sand, despeckle(t, rng, keep=0.55))
    return cv


@block("hematite_spherule_regolith")
def tex_hematite_spherule_regolith():
    """Meridiani lag: hematite concretions ('blueberries', ~4 mm) weathered out of the soil."""
    rng = rng_for("hematite_spherule_regolith")
    cv = regolith_canvas()
    sph = cv.add(PAL["spherule"])
    round3 = [(0, 1), (1, 0), (1, 1), (1, 2), (2, 1)]
    sq2 = [(0, 0), (0, 1), (1, 0), (1, 1)]
    shapes = [round3, round3] + [sq2] * 4 + [[(0, 0)]] * 4 + [[(0, 0), (0, 1)]]
    masks = place_on_torus(rng, [shapes[i] for i in rng.permutation(len(shapes))], gap=2)
    for m in masks:
        sc = light_score(m)
        k = m.sum()
        tone = np.where(sc >= 1, 3, np.where(sc <= -1, 1, 2)) if k > 1 else np.full_like(sc, 1)
        cv.put(m, sph, tone)
        if k >= 4:  # specular glint on the top-left pixel
            ys, xs = unwrap_coords(m)
            i = np.argmin(xs + ys)
            cv.tone[ys[i] % N, xs[i] % N] = 4
    allm = np.any(masks, axis=0)
    cv.shift((roll2(allm, 1, 0) | roll2(allm, 0, 1)) & ~allm, -2, 0)
    return cv


@block("ice_rich_regolith")
def tex_ice_rich_regolith():
    """Ice-cemented soil (Phoenix 'Dodo-Goldilocks'): frosty, greyer soil with white ice in the pores."""
    rng = rng_for("ice_rich_regolith")
    cv = Canvas()
    soil = cv.add(PAL["frozen_soil"])
    ice = cv.add(PAL["ground_ice"])
    f = noise_mix(rng, [(3, 3, 1.0), (7, 7, 0.7)], white=0.7)
    cv.fill(soil, despeckle(quantize(f, [0.06, 0.16, 0.28, 0.28, 0.16, 0.06]), rng, keep=0.4))
    w = worley(rng, 22, relax=1)
    pore = (w["F2"] - w["F1"]) + 0.45 * noise_mix(rng, [(4, 4, 1.0)], white=0.8)
    icem = pore < np.quantile(pore, 0.10)
    icem = despeckle(icem.astype(np.int16), rng, keep=0.6).astype(bool)
    sc = light_score(icem)
    cv.put(icem, ice, np.where(sc >= 1, 2, np.where(sc <= -1, 0, 1)))
    cv.put(icem & (sc >= 2), ice, 3)
    cv.shift((roll2(icem, 1, 0) | roll2(icem, 0, 1)) & ~icem, -1, soil)
    return cv


# ------------------------------------------------------------------------------------------- rock

def mars_stone_canvas() -> Canvas:
    rng = rng_for("mars_stone")
    cv = Canvas()
    rock = cv.add(PAL["mars_stone"])
    f = noise_mix(rng, [(4, 9, 1.0), (8, 16, 0.55)], white=0.55)
    t = quantize(f, [0.03, 0.14, 0.28, 0.31, 0.18, 0.06])
    cv.fill(rock, despeckle(t, rng, keep=0.35))
    # vesicles: a dark pit with a lit lower rim
    shapes = [[(0, 0)]] * 4 + [[(0, 0), (0, 1)]]
    for m in place_on_torus(rng, shapes, gap=3):
        cv.put(m, rock, 0)
        cv.put(roll2(m, 1, 0) & ~m, rock, 5)
    return cv


@block("mars_stone")
def tex_mars_stone():
    """Mars stone: reddish-brown basaltic rock, horizontal blotches like vanilla stone, a few vesicles."""
    return mars_stone_canvas()


@block("mars_cobblestone")
def tex_mars_cobblestone():
    """Rounded Mars-stone cobbles lit from the top left, with thin dark crevices (vanilla density)."""
    rng = rng_for("mars_cobblestone")
    cv = Canvas()
    rid = cv.add(PAL["mars_cobble"])
    w = worley(rng, 11, relax=2)
    e = w["F2"] - w["F1"] + 0.25 * noise_mix(rng, [(6, 6, 1.0)], white=0.6)
    crevice = e < 0.7
    cid = w["id"]
    base = rng.integers(4, 7, len(w["points"]))
    r = np.maximum(w["F1"] + (w["F2"] - w["F1"]) / 2, 1.0)
    lit = -(w["dx"] + w["dy"]) / r                      # the top-left of each stone is lit
    tone = base[cid] + np.round(1.3 * lit).astype(int)
    grain = noise_mix(rng, [(8, 8, 1.0)], white=1.0)
    tone += (grain > 1.3).astype(int) - (grain < -1.3).astype(int)
    tl = (roll2(crevice, 1, 0) | roll2(crevice, 0, 1)) & ~crevice    # crevice above / left of the pixel
    br = (roll2(crevice, -1, 0) | roll2(crevice, 0, -1)) & ~crevice & ~tl
    tone[tl] += 1
    tone[br] -= 1
    tone[crevice] = np.where(rng.random(crevice.sum()) < 0.55, 0, 1)
    cv.fill(rid, tone)
    cv.clamp()
    return cv


@block("mars_stone_bricks")
def tex_mars_stone_bricks():
    """Mars stone cut into two offset courses of bevelled bricks."""
    rng = rng_for("mars_stone_bricks")
    cv = Canvas()
    rid = cv.add(PAL["mars_bricks"])
    f = noise_mix(rng, [(4, 10, 1.0), (8, 16, 0.5)], white=0.5)
    interior = 2 + quantize(f, [0.12, 0.38, 0.38, 0.12])
    draw_bricks(cv, rid, [(8, [15]), (8, [7])], interior, mortar_tone=0, hi=2, lo=-1, rng=rng)
    return cv


# basalt ---------------------------------------------------------------------------------------------

def basalt_rock_canvas(name: str) -> Canvas:
    """Isotropic dark basalt (used as the host of olivine basalt)."""
    rng = rng_for(name)
    cv = Canvas()
    rid = cv.add(PAL["mars_basalt"])
    f = noise_mix(rng, [(4, 4, 1.0), (8, 8, 0.6)], white=0.6)
    cv.fill(rid, despeckle(quantize(f, [0.05, 0.14, 0.27, 0.29, 0.18, 0.07]), rng, keep=0.35))
    return cv


@block("mars_basalt_side")
def tex_mars_basalt_side():
    """Columnar basalt seen from the side: long vertical streaks and cooling joints."""
    rng = rng_for("mars_basalt_side")
    cv = Canvas()
    rid = cv.add(PAL["mars_basalt"])
    cols = normalize(value_noise(rng, 16, 1))            # one value per pixel column
    f = 1.0 * cols + 0.7 * noise_mix(rng, [(12, 3, 1.0)]) + 0.25 * normalize(rng.random((N, N)))
    t = quantize(f, [0.04, 0.12, 0.24, 0.28, 0.20, 0.09, 0.03])
    t = despeckle(t, rng, keep=0.3)
    cv.fill(rid, t)
    # vertical cooling joints with a lit left lip
    for x, y0, length in ((int(rng.integers(0, 8)), int(rng.integers(0, N)), int(rng.integers(9, 15))),
                          (int(rng.integers(8, 16)), int(rng.integers(0, N)), int(rng.integers(5, 10)))):
        for k in range(length):
            y = (y0 + k) % N
            cv.tone[y, x] = 0
            cv.tone[y, (x - 1) % N] = max(int(cv.tone[y, (x - 1) % N]), 5)
            cv.tone[y, (x + 1) % N] = min(int(cv.tone[y, (x + 1) % N]), 2)
    return cv


@block("mars_basalt_top")
def tex_mars_basalt_top():
    """Cross-section of basalt columns: flat polygons separated by dark joints, bevelled edges."""
    rng = rng_for("mars_basalt_top")
    cv = Canvas()
    rid = cv.add(PAL["mars_basalt"])
    w = worley(rng, 6, relax=3)
    cid = w["id"]
    e = w["F2"] - w["F1"]
    r = np.maximum(w["F1"] + e / 2, 1.0)
    lit = -(w["dx"] + w["dy"]) / r
    base = rng.integers(3, 5, len(w["points"]))
    f = noise_mix(rng, [(8, 8, 1.0)], white=0.8)
    tone = base[cid] + np.round(0.8 * lit + 0.5 * f).astype(int)
    joint = e < 0.8
    tl = ((roll2(cid, 1, 0) != cid) | (roll2(cid, 0, 1) != cid) | roll2(joint, 1, 0) | roll2(joint, 0, 1)) & ~joint
    br = ((roll2(cid, -1, 0) != cid) | (roll2(cid, 0, -1) != cid) | roll2(joint, -1, 0) | roll2(joint, 0, -1)) & ~joint & ~tl
    tone[tl] += 1
    tone[br] -= 1
    tone[joint] = 0
    cv.fill(rid, tone)
    cv.clamp()
    return cv


@block("polished_mars_basalt")
def tex_polished_mars_basalt():
    """Polished basalt: a bevelled frame around a smooth face that still shows the vertical flow grain."""
    rng = rng_for("polished_mars_basalt")
    cv = Canvas()
    rid = cv.add(PAL["mars_basalt"])
    cols = normalize(value_noise(rng, 16, 1))
    f = 0.9 * cols + 0.6 * noise_mix(rng, [(10, 2, 1.0)]) + 0.2 * normalize(rng.random((N, N)))
    cv.fill(rid, 3 + quantize(f, [0.25, 0.45, 0.25, 0.05]))
    draw_polished_frame(cv, rid, light=6, dark=1, corner_dark=0)
    draw_polished_frame(cv, rid, light=5, dark=2, inset=1)
    return cv


@block("mars_basalt_bricks")
def tex_mars_basalt_bricks():
    """Small basalt bricks: four offset courses (deepslate-brick scale), so they differ from stone bricks."""
    rng = rng_for("mars_basalt_bricks")
    cv = Canvas()
    rid = cv.add(PAL["mars_basalt"])
    f = noise_mix(rng, [(6, 12, 1.0)], white=0.6)
    interior = 3 + quantize(f, [0.2, 0.5, 0.3])
    draw_bricks(cv, rid, [(4, [3, 11]), (4, [7, 15]), (4, [1, 9]), (4, [5, 13])], interior,
                mortar_tone=0, hi=2, lo=-1, rng=rng)
    return cv


# sediments ------------------------------------------------------------------------------------------

def lamina_field(rng, period: int = N, amp: float = 0.8, slope: float = 0.0, smooth: int = 0):
    """Fine horizontal (or inclined) laminae: a random per-row profile sampled at y + wobble(x) - slope*x.
    For a seamless tile, slope * 16 must be a multiple of `period` (the profile repeats every `period`)."""
    prof = rng.random(period)
    for _ in range(smooth):
        prof = (prof + np.roll(prof, 1) + np.roll(prof, -1)) / 3
    d = periodic_curve(rng, amp=amp, harmonics=(1, 2)) if amp else np.zeros(N)
    xs, ys = pixel_grid()
    rows = np.floor(ys + d[None, :] - slope * np.floor(xs)).astype(int) % period
    return normalize(prof[rows])


def mudstone_canvas() -> Canvas:
    rng = rng_for("mudstone")
    cv = Canvas()
    rid = cv.add(PAL["mudstone"])
    f = (1.0 * lamina_field(rng, amp=0.7) + 0.45 * noise_mix(rng, [(5, 5, 1.0)])
         + 0.3 * normalize(rng.random((N, N))))
    t = quantize(f, [0.03, 0.11, 0.23, 0.28, 0.21, 0.11, 0.03])
    cv.fill(rid, despeckle(t, rng, keep=0.45))
    for m in place_on_torus(rng, [[(0, 0)], [(0, 0), (0, 1)]], gap=4):  # small concretions
        cv.put(m, rid, 1)
        cv.put(roll2(m, 1, 0) & ~m, rid, 5)
    return cv


@block("mudstone")
def tex_mudstone():
    """Lake-bed mudstone (Gale): pale grey-tan with fine wavy laminae and a few small concretions."""
    return mudstone_canvas()


@block("polished_mudstone")
def tex_polished_mudstone():
    """Polished mudstone: faint laminae inside a bevelled frame."""
    rng = rng_for("polished_mudstone")
    cv = Canvas()
    rid = cv.add(PAL["mudstone"])
    lam = normalize(value_noise(rng, 1, N)[:, :1].repeat(N, 1))
    f = 0.6 * lam + 0.5 * noise_mix(rng, [(4, 8, 1.0)], white=0.4)
    cv.fill(rid, 3 + quantize(f, [0.2, 0.55, 0.25]))
    draw_polished_frame(cv, rid, light=6, dark=1, corner_dark=0)
    return cv


@block("layered_sediment_side")
def tex_layered_sediment_side():
    """Sulfate-bearing layered sediment (Mount Sharp): alternating light and dark tan beds."""
    rng = rng_for("layered_sediment_side")
    cv = Canvas()
    rid = cv.add(PAL["sediment"])
    rows, bands = band_rows(rng, [(2, 4, "light"), (1, 2, "dark"), (2, 3, "mid"), (1, 2, "dark")])
    base = {"light": 5, "mid": 4, "dark": 2}
    band_tone = np.array([base[k] + int(rng.integers(0, 2)) for k, _ in bands])
    d = periodic_curve(rng, amp=0.7, harmonics=(1, 2))
    _, ys = pixel_grid()
    r = np.floor(ys + d[None, :]).astype(int) % N
    tone = band_tone[rows[r]]
    streak = quantize(noise_mix(rng, [(6, 16, 1.0)], white=0.5), [0.18, 0.64, 0.18]) - 1
    tone = tone + streak
    cv.fill(rid, tone)
    cv.clamp()
    return cv


@block("layered_sediment_top")
def tex_layered_sediment_top():
    """A bedding-plane surface of the layered sediment: granular tan."""
    rng = rng_for("layered_sediment_top")
    cv = Canvas()
    rid = cv.add(PAL["sediment"])
    f = noise_mix(rng, [(3, 3, 1.0), (7, 7, 0.6)], white=0.7)
    cv.fill(rid, 2 + despeckle(quantize(f, [0.04, 0.16, 0.32, 0.28, 0.15, 0.05]), rng, keep=0.4))
    return cv


@block("delta_sediment")
def tex_delta_sediment():
    """Jezero delta front: pale grey-brown fine sandstone; inclined foreset laminae above a truncation
    (reactivation) surface, flat-lying laminae below."""
    rng = rng_for("delta_sediment")
    cv = Canvas()
    rid = cv.add(PAL["delta"])
    _, ys = pixel_grid()
    set_a = lamina_field(rng, period=8, amp=0.3, slope=0.5, smooth=1)
    set_b = lamina_field(rng, period=16, amp=0.4, slope=0.0, smooth=1)
    lam = np.where(ys < 9, set_a, set_b)
    f = 0.55 * lam + 0.65 * noise_mix(rng, [(5, 5, 1.0)], white=0.7)
    tone = 1 + quantize(f, [0.06, 0.2, 0.34, 0.28, 0.12])
    trunc = (np.floor(ys) == 9) | (np.floor(ys) == 0)
    tone[trunc & (noise_mix(rng, [(4, 1, 1.0)]) > -0.4)] -= 1
    cv.fill(rid, despeckle(tone, rng, keep=0.5))
    for m in place_on_torus(rng, [[(0, 0)]] * 3, gap=4):  # dark lithic grains
        cv.put(m, rid, 0)
    cv.clamp()
    return cv


@block("smectite_clay")
def tex_smectite_clay():
    """Fe/Mg smectite clay: smooth, greenish-grey to teal-brown, with faint desiccation cracks."""
    rng = rng_for("smectite_clay")
    cv = Canvas()
    rid = cv.add(PAL["smectite"])
    f = noise_mix(rng, [(2, 2, 1.0), (4, 4, 0.35)], white=0.35)
    cv.fill(rid, 1 + despeckle(quantize(f, [0.03, 0.17, 0.38, 0.28, 0.12, 0.02]), rng, keep=0.3))
    w = worley(rng, 4, relax=2)
    cracks = thin_mask(((w["F2"] - w["F1"]) < 0.6) & (noise_mix(rng, [(2, 2, 1.0)]) > 0.0))
    cv.shift(cracks, -1)
    return cv


@block("carbonate_rock")
def tex_carbonate_rock():
    """Mg-carbonate-bearing rock (Jezero margin): light grey-green, granular, with pale crystals."""
    rng = rng_for("carbonate_rock")
    cv = Canvas()
    rid = cv.add(PAL["carbonate"])
    f = noise_mix(rng, [(5, 5, 0.8), (10, 10, 0.6)], white=0.8)
    cv.fill(rid, despeckle(quantize(f, [0.03, 0.12, 0.25, 0.30, 0.20, 0.08, 0.02]), rng, keep=0.5))
    for m in place_on_torus(rng, [grow_cluster(rng, s, compact=2) for s in (3, 2, 2)], gap=3):
        cv.put(m, rid, 6)
    return cv


# ices -----------------------------------------------------------------------------------------------

@block("polar_water_ice")
def tex_polar_water_ice():
    """North polar residual cap water ice: opaque, white, with thin tan dust layers."""
    rng = rng_for("polar_water_ice")
    cv = Canvas()
    ice = cv.add(PAL["polar_ice"])
    dust = cv.add(PAL["polar_dust"])
    xs, ys = pixel_grid()
    diag = np.cos(2 * np.pi * 2 * (np.floor(xs) - np.floor(ys)) / N + rng.uniform(0, 6.28))
    f = 0.7 * noise_mix(rng, [(3, 3, 1.0), (6, 6, 0.5)], white=0.35) + 0.55 * diag
    cv.fill(ice, 1 + despeckle(quantize(f, [0.08, 0.27, 0.38, 0.22, 0.05]), rng, keep=0.4))
    for y0, amp, strong in ((3.5, 0.9, True), (11.0, 0.7, True), (7.2, 0.5, False)):
        d = periodic_curve(rng, amp=amp, harmonics=(1, 2))
        tone = quantize(noise_mix(rng, [(5, 1, 1.0)], white=0.4)[0], [0.2, 0.6, 0.2])
        for x in range(N):
            y = int(math.floor(y0 + d[x])) % N
            if strong:
                cv.put(_pix(y, x), dust, 1 + int(tone[x]))
            elif tone[x] > 0:
                cv.put(_pix(y, x), dust, 3)
    return cv


def _pix(y, x, n=N):
    m = np.zeros((n, n), bool)
    m[y % n, x % n] = True
    return m


@block("water_ice")
def tex_water_ice():
    """Clean water ice: translucent bluish, with diagonal streaks like vanilla ice (alpha 180-205)."""
    rng = rng_for("water_ice")
    cv = Canvas()
    ice = cv.add(PAL["water_ice"])
    xs, ys = pixel_grid()
    d = np.floor(xs) + np.floor(ys)                     # diagonal streaks, periodic in x and y
    streak = np.cos(2 * np.pi * 2 * d / N + 0.9) + 0.55 * np.cos(2 * np.pi * 5 * d / N + 2.1)
    strength = 0.55 + 0.45 * np.tanh(noise_mix(rng, [(2, 3, 1.0)]))    # streaks fade in and out
    f = 0.5 * noise_mix(rng, [(2, 2, 1.0), (4, 4, 0.4)], white=0.35) + strength * normalize(streak)
    t = despeckle(quantize(f, [0.12, 0.33, 0.3, 0.15, 0.07, 0.03]), rng, keep=0.0)
    cv.fill(ice, t)
    cv.alpha[:] = 190
    cv.alpha[cv.tone >= 4] = 205
    cv.alpha[cv.tone == 0] = 180
    return cv


@block("polar_layered_deposit_side")
def tex_polar_layered_deposit_side():
    """North polar layered deposits: white ice-rich and tan dust-rich bands."""
    rng = rng_for("polar_layered_deposit_side")
    cv = Canvas()
    ice = cv.add(PAL["pld_ice"])
    dust = cv.add(PAL["pld_dust"])
    rows, bands = band_rows(rng, [(2, 4, "ice"), (1, 2, "dust"), (2, 3, "ice"), (1, 3, "dust")])
    d = periodic_curve(rng, amp=0.6, harmonics=(1, 2))
    _, ys = pixel_grid()
    r = np.floor(ys + d[None, :]).astype(int) % N
    b = rows[r]
    kinds = np.array([k == "ice" for k, _ in bands])
    shade = np.array([int(rng.integers(0, 2)) for _ in bands])
    streak = quantize(noise_mix(rng, [(6, 16, 1.0)], white=0.5), [0.2, 0.6, 0.2]) - 1
    is_ice = kinds[b]
    cv.put(is_ice, ice, 2 + shade[b] + streak)
    cv.put(~is_ice, dust, 2 + shade[b] + streak)
    cv.clamp()
    return cv


@block("polar_layered_deposit_top")
def tex_polar_layered_deposit_top():
    """Top of the layered deposits: ice with wind-streaked dust."""
    rng = rng_for("polar_layered_deposit_top")
    cv = Canvas()
    ice = cv.add(PAL["pld_ice"])
    dust = cv.add(PAL["pld_dust"])
    f = noise_mix(rng, [(3, 3, 1.0), (7, 7, 0.5)], white=0.4)
    cv.fill(ice, 1 + despeckle(quantize(f, [0.1, 0.3, 0.4, 0.2]), rng, keep=0.4))
    g = noise_mix(rng, [(3, 9, 1.0), (6, 16, 0.6)], white=0.35)
    dm = g > np.quantile(g, 0.87)
    dm = despeckle(dm.astype(np.int16), rng, keep=0.35).astype(bool)
    cv.put(dm, dust, 3 - (rng.random((N, N)) < 0.3).astype(int))
    return cv


def facet_canvas(name, pal, points, relax, tone_lo, tone_hi, edge_hi, edge_lo, speckle=0.0):
    """Crystalline ice: toroidal Voronoi facets with random flat tones, lit top-left edges and shaded
    bottom-right edges (each facet looks bevelled)."""
    rng = rng_for(name)
    cv = Canvas()
    rid = cv.add(PAL[pal])
    w = worley(rng, points, relax=relax)
    base = rng.integers(tone_lo, tone_hi + 1, len(w["points"]))
    cid = w["id"]
    tone = base[cid].copy()
    tl_edge = (roll2(cid, 1, 0) != cid) | (roll2(cid, 0, 1) != cid)
    br_edge = (roll2(cid, -1, 0) != cid) | (roll2(cid, 0, -1) != cid)
    tone[tl_edge] += edge_hi
    tone[br_edge & ~tl_edge] += edge_lo
    if speckle:
        tone += (rng.random((N, N)) < speckle).astype(int) * rng.choice([-1, 1], (N, N))
    cv.fill(rid, tone)
    cv.clamp()
    return cv


@block("co2_ice")
def tex_co2_ice():
    """CO2 ice (south polar residual cap): opaque bright white with a faint blue cast, crystalline facets."""
    return facet_canvas("co2_ice", "co2_ice", 12, 1, 2, 5, edge_hi=1, edge_lo=-1, speckle=0.05)


@block("dry_ice")
def tex_dry_ice():
    """Dry ice moved off the cap: like CO2 ice but with fewer, softer facets."""
    return facet_canvas("dry_ice", "dry_ice", 7, 2, 1, 4, edge_hi=1, edge_lo=-1, speckle=0.12)


@block("co2_frost")
def tex_co2_frost():
    """Seasonal CO2 frost: snow-like bright bluish-white with a few sparkles."""
    rng = rng_for("co2_frost")
    cv = Canvas()
    rid = cv.add(PAL["co2_frost"])
    f = noise_mix(rng, [(4, 4, 0.8), (8, 8, 0.6)], white=0.8)
    cv.fill(rid, despeckle(quantize(f, [0.04, 0.12, 0.3, 0.4, 0.14]), rng, keep=0.5))
    for m in place_on_torus(rng, [[(0, 0)]] * 4, gap=3):
        cv.put(m, rid, 5)
    return cv


# ores -----------------------------------------------------------------------------------------------

def nugget_shapes(rng, sizes, lobes=1):
    """Nugget shapes for the given pixel sizes; clusters of 8+ pixels get `lobes` lobes."""
    return [nugget(rng, s, lobes=lobes if s >= 8 else 1) for s in sizes]


@block("hematite_ore")
def tex_hematite_ore():
    """Grey crystalline (specular) hematite: metallic grey with a red-purple cast."""
    rng = rng_for("hematite_ore")
    cv = mars_stone_canvas()
    ore = cv.add(PAL["hematite"])
    masks = place_on_torus(rng, nugget_shapes(rng, (17, 15, 12, 9, 6, 4), lobes=2), gap=1)
    stamp_minerals(cv, masks, ore, 0, mid=2, rng=rng, shadow=1, rim=0, glint=5, glint_prob=0.8, satellites=2)
    return cv


@block("olivine_basalt")
def tex_olivine_basalt():
    """Olivine-phyric basalt (Seitah, Nili Fossae): olive-green crystals in dark basalt."""
    rng = rng_for("olivine_basalt")
    cv = basalt_rock_canvas("olivine_basalt_host")
    ore = cv.add(PAL["olivine"])
    masks = place_on_torus(rng, nugget_shapes(rng, (8, 7, 6, 5, 5, 4, 3, 2, 2, 1)), gap=1)
    stamp_minerals(cv, masks, ore, 0, mid=3, rng=rng, style="crystal", shadow=1, rim=0, glint=5,
                   glint_prob=0.7)
    return cv


@block("jarosite_ore")
def tex_jarosite_ore():
    """Jarosite: earthy yellow-brown sulfate nodules and coatings."""
    rng = rng_for("jarosite_ore")
    cv = mars_stone_canvas()
    ore = cv.add(PAL["jarosite"])
    masks = place_on_torus(rng, nugget_shapes(rng, (15, 12, 10, 7, 5, 3), lobes=3), gap=1)
    stamp_minerals(cv, masks, ore, 0, mid=3, rng=rng, shadow=1, rim=1, glint=5, glint_prob=0.5, satellites=2)
    return cv


@block("gypsum_vein")
def tex_gypsum_vein():
    """Bright calcium-sulfate veins cutting Gale crater mudstone (raised, so they cast a shadow)."""
    rng = rng_for("gypsum_vein")
    cv = mudstone_canvas()
    vein = cv.add(PAL["gypsum"])
    main = draw_path(torus_path(rng, (1, 1), wobble=1.3, start=(2.0, 1.0)))
    branch = draw_path(torus_path(rng, (1, -1), wobble=1.6, start=(5.0, 13.0)))
    branch &= noise_mix(rng, [(2, 2, 1.0)]) > -0.35
    hair = draw_path(torus_path(rng, (1, 0), wobble=0.8, start=(0.0, 9.5)))
    hair &= noise_mix(rng, [(3, 1, 1.0)]) > 0.3
    veins = thin_mask(main | branch)
    cv.put(veins, vein, 3 - (rng.random((N, N)) < 0.25).astype(int))
    cv.put(veins & (light_score(veins) >= 1) & (rng.random((N, N)) < 0.5), vein, 4)
    cv.put(hair & ~veins, vein, 1)
    allv = veins | hair
    cv.shift((roll2(allv, 1, 0) | roll2(allv, 0, 1)) & ~allv, -2, 0)
    return cv


@block("sulfur_deposit")
def tex_sulfur_deposit():
    """Elemental sulfur crystals (Gediz Vallis, 2024) in reddish-brown rock."""
    rng = rng_for("sulfur_deposit")
    cv = mars_stone_canvas()
    ore = cv.add(PAL["sulfur"])
    masks = place_on_torus(rng, nugget_shapes(rng, (11, 9, 8, 6, 5, 3, 2)), gap=1)
    stamp_minerals(cv, masks, ore, 0, mid=3, rng=rng, style="crystal", shadow=1, rim=0, glint=5,
                   glint_prob=0.85)
    return cv


def chromite_grains(cv, rng, host_rid, sizes, glints=4, pal="chromite"):
    """Black-brown chromite pods with single bright metallic glints."""
    ore = cv.add(PAL[pal])
    gl = cv.add(PAL["chromite_glint"])
    masks = place_on_torus(rng, nugget_shapes(rng, sizes, lobes=2), gap=1)
    stamp_minerals(cv, masks, ore, host_rid, mid=1, rng=rng, shadow=1, rim=0, satellites=1)
    for m in sorted(masks, key=lambda m: -m.sum())[:glints]:
        sc = light_score(m)
        cand = np.argwhere(m & (sc == sc[m].max()))
        y, x = cand[rng.integers(len(cand))]
        cv.layer[y, x], cv.tone[y, x] = gl, 1
        ys, xs = unwrap_coords(m)
        if m.sum() >= 8:  # a second, dimmer glint on big pods
            i = int(rng.integers(len(ys)))
            if cv.layer[ys[i] % N, xs[i] % N] != gl:
                cv.layer[ys[i] % N, xs[i] % N], cv.tone[ys[i] % N, xs[i] % N] = gl, 0
    return masks


@block("mars_chromite_ore")
def tex_mars_chromite_ore():
    """Chromite (FeCr2O4) pods in Mars stone: black-brown with metallic glints."""
    rng = rng_for("mars_chromite_ore")
    cv = mars_stone_canvas()
    chromite_grains(cv, rng, 0, (13, 11, 9, 7, 5, 3))
    return cv


def bar_mask(start, direction, length: float, width: int, n: int = N):
    """A straight bar of `width` pixels drawn on the torus from `start` along `direction` (dx, dy) for
    `length` pixels. Steep bars are widened horizontally, shallow ones vertically (clean pixel edges)."""
    m = np.zeros((n, n), bool)
    dx, dy = direction
    L = math.hypot(dx, dy)
    ux, uy = dx / L, dy / L
    steep = abs(uy) > abs(ux)
    for t in np.linspace(0, length, int(length * 4) + 1):
        x, y = start[0] + ux * t, start[1] + uy * t
        for w in range(width):
            xx, yy = (x + w, y) if steep else (x, y + w)
            m[int(math.floor(yy)) % n, int(math.floor(xx)) % n] = True
    return m


@block("iron_nickel_meteorite")
def tex_iron_nickel_meteorite():
    """Iron-nickel meteorite (kamacite + taenite, like Opportunity's Heat Shield Rock). The metal shows an
    etched octahedrite's Widmanstatten pattern - kamacite plates crossing in three directions ~60 degrees
    apart, each set catching the light differently - over dark plessite, with patches of brown fusion crust."""
    rng = rng_for("iron_nickel_meteorite")
    cv = Canvas()
    metal = cv.add(PAL["meteorite"])
    crust = cv.add(PAL["fusion_crust"])
    tone = 1 + quantize(noise_mix(rng, [(6, 6, 1.0)], white=0.9), [0.4, 0.45, 0.15])   # plessite 1..3
    families = (((1, 0), 6), ((1, 2), 4), ((1, -2), 5))
    plates = np.zeros((N, N), bool)
    for k in range(9):
        direction, t = families[k % 3]
        m = bar_mask(rng.uniform(0, N, 2), direction, rng.uniform(9, 16), 2)
        tone[m] = t
        tone[m & (roll2(~m, -1, 0) | roll2(~m, 0, -1))] = t - 1     # shaded lower/right edge of the plate
        plates |= m
    tone[(roll2(plates, -1, 0) | roll2(plates, 0, -1)) & ~plates] = 0  # thin dark taenite borders
    cv.fill(metal, tone)
    g = noise_mix(rng, [(2, 2, 1.0), (4, 4, 0.6)], white=0.2)
    cm = (g > np.quantile(g, 0.84)) & (noise_mix(rng, [(8, 8, 1.0)], white=1.0) > -1.0)
    cm = despeckle(cm.astype(np.int16), rng, keep=0.0).astype(bool)
    cf = noise_mix(rng, [(7, 7, 1.0)], white=1.0)
    cv.put(cm, crust, quantize(cf, [0.3, 0.42, 0.2, 0.08]))
    cv.shift(cm & (roll2(~cm, 1, 0) | roll2(~cm, 0, 1)), 1, crust)      # lit upper/left crust lip
    return cv


def earth_stone_canvas(name, pal, fx, fy):
    """Host rock for the Earth ores, drawn here (not copied): horizontally streaked greys."""
    rng = rng_for(name)
    cv = Canvas()
    rid = cv.add(PAL[pal])
    f = noise_mix(rng, [(fx, fy, 1.0), (fx * 2, min(16, fy * 2), 0.5)], white=0.55)
    cv.fill(rid, despeckle(quantize(f, [0.06, 0.18, 0.3, 0.28, 0.14, 0.04]), rng, keep=0.35))
    return cv


@block("chromite_ore")
def tex_chromite_ore():
    """Earth chromite ore: black-brown grains with metallic glints in a vanilla-like grey stone."""
    rng = rng_for("chromite_ore")
    cv = earth_stone_canvas("chromite_ore_host", "earth_stone", 4, 10)
    chromite_grains(cv, rng, 0, (13, 11, 9, 7, 5, 3))
    return cv


@block("deepslate_chromite_ore")
def tex_deepslate_chromite_ore():
    """Earth chromite ore in a dark deepslate-like host, with a lighter brown sheen so the grains read."""
    rng = rng_for("deepslate_chromite_ore")
    cv = earth_stone_canvas("deepslate_chromite_ore_host", "earth_deepslate", 3, 12)
    chromite_grains(cv, rng, 0, (13, 11, 9, 7, 5, 3), glints=5, pal="chromite_deep")
    return cv



# =====================================================================================================
# Item toolkit: flat-shaded polyhedra, metaball lumps, vanilla-style outlines
# =====================================================================================================

LIGHT = np.array([-0.55, 0.72, 0.75])
LIGHT = LIGHT / np.linalg.norm(LIGHT)   # view space: x right, y up, z toward the viewer; light from top left


def rot(rx: float = 0.0, ry: float = 0.0, rz: float = 0.0) -> np.ndarray:
    """Rotation matrix applying rx (pitch, about x), then ry (yaw, about y), then rz (roll, about z);
    degrees, right-handed, y up."""
    a, b, c = (math.radians(v) for v in (rx, ry, rz))
    Rx = np.array([[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]])
    Ry = np.array([[math.cos(b), 0, math.sin(b)], [0, 1, 0], [-math.sin(b), 0, math.cos(b)]])
    Rz = np.array([[math.cos(c), -math.sin(c), 0], [math.sin(c), math.cos(c), 0], [0, 0, 1]])
    return Rz @ Ry @ Rx


def view(yaw: float, pitch: float, roll: float = 0.0) -> np.ndarray:
    """Camera-style orientation: turn the model by `yaw` about its vertical axis, tilt the camera down by
    `pitch` (positive shows the top), then roll in the screen plane. Degrees."""
    a, b, c = (math.radians(v) for v in (pitch, yaw, roll))
    Ry = np.array([[math.cos(b), 0, math.sin(b)], [0, 1, 0], [-math.sin(b), 0, math.cos(b)]])
    Rx = np.array([[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]])
    Rz = np.array([[math.cos(c), -math.sin(c), 0], [math.sin(c), math.cos(c), 0], [0, 0, 1]])
    return Rz @ Rx @ Ry


def frustum(w: float, h: float, d: float, top: float = 1.0, top_d: float | None = None):
    """Faces of a box w (x) x h (y) x d (z) centred on the origin whose top face is scaled by `top`
    (x) and `top_d` (z) - an ingot when top < 1. Faces are counter-clockwise seen from outside."""
    top_d = top if top_d is None else top_d
    x0, x1, z0, z1 = -w / 2, w / 2, -d / 2, d / 2
    tx0, tx1, tz0, tz1 = x0 * top, x1 * top, z0 * top_d, z1 * top_d
    y0, y1 = -h / 2, h / 2
    B = [(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)]
    T = [(tx0, y1, tz0), (tx1, y1, tz0), (tx1, y1, tz1), (tx0, y1, tz1)]
    return _outward([
        [T[0], T[3], T[2], T[1]],            # top
        [B[0], B[1], B[2], B[3]],            # bottom
        [B[3], B[2], T[2], T[3]],            # front (+z)
        [B[1], B[0], T[0], T[1]],            # back (-z)
        [B[2], B[1], T[1], T[2]],            # right (+x)
        [B[0], B[3], T[3], T[0]],            # left (-x)
    ])


def prism(sides: int, radius: float, length: float, tip: float = 0.0, squash: float = 1.0, tip2=None):
    """Faces of an n-sided prism along y (|y| <= length/2), optionally capped by pyramids of height
    `tip` (crystal terminations). `squash` scales the cross-section in z (tablets, blades)."""
    tip2 = tip if tip2 is None else tip2
    ang = [2 * math.pi * k / sides + math.pi / sides for k in range(sides)]
    ring = lambda y: [(radius * math.cos(a), y, radius * math.sin(a) * squash) for a in ang]
    lo, hi = ring(-length / 2), ring(length / 2)
    faces = []
    for k in range(sides):
        j = (k + 1) % sides
        faces.append([lo[k], hi[k], hi[j], lo[j]])
    if tip > 0:
        apex = (0.0, length / 2 + tip, 0.0)
        faces += [[hi[k], apex, hi[(k + 1) % sides]] for k in range(sides)]
    else:
        faces.append(list(reversed(hi)))
    if tip2 > 0:
        apex = (0.0, -length / 2 - tip2, 0.0)
        faces += [[lo[(k + 1) % sides], apex, lo[k]] for k in range(sides)]
    else:
        faces.append(list(lo))
    return _outward(faces)


def gem_mesh(sides: int = 8, table: float = 0.55, crown: float = 0.35, pavilion: float = 0.9,
             sx: float = 1.0, sz: float = 1.0):
    """A faceted gem (table, crown facets, pavilion) of girdle radius 1, girdle in the x-z plane."""
    ang = [2 * math.pi * k / sides + math.pi / sides for k in range(sides)]
    T = [(table * math.cos(a) * sx, crown, table * math.sin(a) * sz) for a in ang]
    G = [(math.cos(a) * sx, 0.0, math.sin(a) * sz) for a in ang]
    culet = (0.0, -pavilion, 0.0)
    faces = [list(reversed(T))]
    for k in range(sides):
        j = (k + 1) % sides
        faces += [[T[k], T[j], G[j], G[k]], [G[k], G[j], culet]]
    return _outward(faces)


def _outward(faces):
    """Orient every (convex, origin-surrounding) face counter-clockwise as seen from outside."""
    fixed = []
    for f in faces:
        f = np.array(f, float)
        nrm = np.cross(f[1] - f[0], f[2] - f[0])
        fixed.append(f[::-1] if np.dot(nrm, f.mean(0)) < 0 else f)
    return fixed


def deform(faces, sx: float = 1.0, sy: float = 1.0, sz: float = 1.0, shear_xy: float = 0.0):
    """Scale and shear a mesh (x += shear_xy * y): rhombohedra, elongated tablets."""
    return [np.array([((x + shear_xy * y) * sx, y * sy, z * sz) for x, y, z in f]) for f in faces]


def render_mesh(parts, n: int = N, light=LIGHT):
    """Rasterise flat-shaded convex faces at pixel centres with a z-buffer (orthographic, z toward the
    viewer). parts: [(faces, R, scale, (cx, cy) screen centre, z_offset), ...]. Returns (face_id map,
    per-face lambert shade list, depth map). face ids are global across parts."""
    fid = np.full((n, n), -1, int)
    zbuf = np.full((n, n), -1e9)
    shades = []
    xs, ys = pixel_grid(n)
    for faces, R, scale, (cx, cy), zoff in parts:
        for f in faces:
            v = (np.asarray(f, float) @ np.asarray(R).T) * scale
            nrm = np.cross(v[1] - v[0], v[2] - v[0])
            ln = np.linalg.norm(nrm)
            k = len(shades)
            if ln < 1e-9:
                shades.append(0.0)
                continue
            nrm = nrm / ln
            shades.append(float(np.dot(nrm, light)))
            if nrm[2] <= 1e-6:
                continue
            X = cx + v[:, 0]
            Y = cy - v[:, 1]
            inside = np.ones((n, n), bool)
            sign = 0.0
            for i in range(len(v)):
                j = (i + 1) % len(v)
                cr = (X[j] - X[i]) * (ys - Y[i]) - (Y[j] - Y[i]) * (xs - X[i])
                if sign == 0.0:
                    area = sum((X[(q + 1) % len(v)] - X[q]) * (Y[(q + 1) % len(v)] + Y[q]) for q in range(len(v)))
                    sign = -1.0 if area > 0 else 1.0
                inside &= cr * sign >= -1e-9
            # depth from the plane equation (model x = X - cx, model y = cy - Y)
            z = v[0, 2] - (nrm[0] * ((xs - cx) - v[0, 0]) + nrm[1] * ((cy - ys) - v[0, 1])) / nrm[2] + zoff
            upd = inside & (z > zbuf)
            fid[upd] = k
            zbuf[upd] = z[upd]
    return fid, shades, zbuf


def lump_field(balls, n: int = N, bumps=None, return_lobes: bool = False):
    """Height field of a union of hemispheres [(cx, cy, r, height), ...] in pixel units, plus optional
    bump noise. Returns (mask, height, lambert shade under LIGHT[, lobe id map])."""
    xs, ys = pixel_grid(n)
    H = np.zeros((n, n))
    lobe = np.full((n, n), -1)
    for k, (cx, cy, r, h) in enumerate(balls):
        d2 = ((xs - cx) ** 2 + (ys - cy) ** 2) / (r * r)
        hk = np.where(d2 < 1, h * np.sqrt(np.clip(1 - d2, 0, 1)), 0)
        lobe = np.where(hk > H, k, lobe)
        H = np.maximum(H, hk)
    mask = H > 0
    if bumps is not None:
        H = H + bumps * mask
    gy, gx = np.gradient(H)
    nx, ny, nz = -gx, gy, np.ones_like(H)   # screen y points down, view y points up
    ln = np.sqrt(nx * nx + ny * ny + nz * nz)
    shade = (nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2]) / ln
    if return_lobes:
        return mask, H, shade, lobe
    return mask, H, shade


def tones_from_shade(shade, mask, cuts):
    """Quantise lambert shade (within mask) with ascending thresholds -> tones 0..len(cuts)."""
    t = np.searchsorted(np.asarray(cuts), shade, side="right").astype(np.int16)
    return np.where(mask, t, 0)


def add_outline(cv: Canvas, rid: int, tone_tl: int, tone_br: int):
    """Vanilla item outline: a 1 px ring outside the shape (4-connected, so corners stay round). Pixels
    below / right of the shape get the darkest tone, the rest a slightly lighter one."""
    m = cv.layer >= 0
    ring = dilate(m, 1, wrap=False) & ~m
    br = ring & (shift0(m, 1, 0, False) | shift0(m, 0, 1, False))
    cv.put(ring & ~br, rid, tone_tl)
    cv.put(br, rid, tone_br)


def inner_edges(fid, shades):
    """Mask of pixels lying on a boundary between two faces where this face is the darker one (used to
    crisp up face edges inside an item)."""
    m = np.zeros(fid.shape, bool)
    for dy, dx in N4:
        nb = shift0(fid, dy, dx, -1)
        diff = (nb != fid) & (nb >= 0) & (fid >= 0)
        sh_self = np.where(fid >= 0, np.take(shades, np.maximum(fid, 0)), 0)
        sh_nb = np.where(nb >= 0, np.take(shades, np.maximum(nb, 0)), 0)
        m |= diff & (sh_self < sh_nb - 0.05)
    return m


def item_canvas() -> Canvas:
    return Canvas()  # fully transparent until painted


def paint_mesh(cv: Canvas, rid: int, fid, shades, cuts, base: int, gradient: float = 0.0,
               ridge: int = 0):
    """Paint rasterised faces: tone = base + quantised(lambert [+ top-left gradient]). `ridge` brightens
    pixels of a face that border a darker face (the bright edge line vanilla ingots have)."""
    xs, ys = pixel_grid(cv.n)
    sh = np.where(fid >= 0, np.take(shades, np.maximum(fid, 0)), -9)
    if gradient:
        sh = sh + gradient * ((cv.n / 2 - xs) + (cv.n / 2 - ys)) / cv.n
    mask = fid >= 0
    tone = base + tones_from_shade(sh, mask, cuts)
    if ridge:
        bright_edge = np.zeros_like(mask)
        for dy, dx in ((-1, 0), (1, 0), (0, 1), (0, -1)):
            nb = shift0(fid, dy, dx, -1)
            nbs = np.where(nb >= 0, np.take(shades, np.maximum(nb, 0)), 9)
            bright_edge |= mask & (nb >= 0) & (nb != fid) & (nbs < sh - 0.15)
        tone = np.where(bright_edge, tone + ridge, tone)
    cv.put(mask, rid, tone)
    cv.clamp()
    return mask


def ingot(colors) -> Canvas:
    """A cast ingot: a trapezoidal bar seen from above-front, long axis rising to the right."""
    cv = item_canvas()
    rid = cv.add(colors)
    faces = frustum(13.4, 3.8, 6.2, top=0.80, top_d=0.62)
    fid, shades, _ = render_mesh([(faces, view(35, 48), 1.0, (8.0, 8.3), 0.0)])
    paint_mesh(cv, rid, fid, shades, cuts=[-0.1, 0.25, 0.5, 0.72, 0.9], base=2, gradient=0.25, ridge=1)
    add_outline(cv, rid, 1, 0)
    return cv


# =====================================================================================================
# Items
# =====================================================================================================

IPAL = {
    "hematite": ["#2a2126", "#3d3237", "#4f434a", "#655860", "#7e7179", "#9a8f96", "#b9b0b6", "#ddd6db"],
    "spherule": ["#18131a", "#272026", "#352d34", "#463e45", "#5b5259", "#756c73", "#968e94", "#c2bcc0"],
    "olivine": ["#1e2b0a", "#33491a", "#4a6624", "#62832d", "#7a9a3a", "#93b343", "#a9c64e", "#c9e077", "#f0f8c8"],
    "jarosite": ["#3a2c0e", "#563f15", "#755a1f", "#987729", "#b18c31", "#c8a03a", "#dcb955", "#f1d98a"],
    "gypsum": ["#56514a", "#77716a", "#9d978d", "#bdb7ad", "#d6d1c8", "#e8e5de", "#f5f3ef", "#ffffff"],
    "sulfur": ["#4a3f0c", "#695910", "#8b7617", "#ad9522", "#c8b030", "#e0c943", "#f0e079", "#fbf4bb"],
    "chromite": ["#0b0908", "#181311", "#241d1a", "#312723", "#41342e", "#56463e", "#75645a"],
    "chromite_glint": ["#a8a29c", "#d9d5d1", "#ffffff"],
    "chromium": ["#232831", "#39414d", "#56606d", "#768191", "#97a3b4", "#b9c4d3", "#dae2ed", "#f6f9fd"],
    "meteor_metal": ["#1b1b1f", "#2a2a2f", "#3a3a40", "#4c4c53", "#606067", "#76767e", "#8f8f97", "#b0b0b8"],
    "meteor_crust": ["#2e2119", "#45322a", "#5c4433", "#74573f"],
    "nickel": ["#352e21", "#4f4532", "#71654b", "#928568", "#b1a586", "#cdc2a3", "#e4dbc0", "#f8f2e0"],
    "smectite": ["#252b27", "#363e38", "#4c5650", "#606a62", "#747d72", "#888f82", "#9da293", "#b6baab"],
    "perchlorate": ["#5a4347", "#775b60", "#987b80", "#b89da2", "#d2babe", "#e6d4d7", "#f4eaec", "#ffffff"],
    "ice_shard": ["#304a66", "#4b6b8e", "#6e93b8", "#93b7d8", "#b6d2ea", "#d4e7f5", "#ecf6fd", "#ffffff"],
    "dry_ice": ["#3f4955", "#5a6672", "#7a8794", "#9ba8b5", "#bac6d1", "#d4dde5", "#eaf0f5", "#ffffff"],
    "vapour": ["#c3d0db", "#dce5ec", "#eef3f7"],
}


def lump_item(name, colors, balls, cuts, base=2, bump_amp=0.0, bump_feat=4, glints=0, crevices=True,
              outline=(1, 0)) -> Canvas:
    """Raw-ore style chunk: a union of hemispherical lobes (+ bump noise), quantised top-left lighting,
    darkened crevices where a lobe tucks under its neighbour, a vanilla outline and optional glints."""
    rng = rng_for(name)
    cv = item_canvas()
    rid = cv.add(colors)
    bumps = bump_amp * noise_mix(rng, [(bump_feat, bump_feat, 1.0)], white=0.15) if bump_amp else None
    mask, H, shade, lobe = lump_field(balls, bumps=bumps, return_lobes=True)
    tone = base + tones_from_shade(shade, mask, cuts)
    if crevices:
        crev = np.zeros_like(mask)
        for dy, dx in N4:
            nl, nh = shift0(lobe, dy, dx, -1), shift0(H, dy, dx, 0.0)
            crev |= mask & (nl >= 0) & (nl != lobe) & (nh > H + 0.25)
        tone = np.where(crev, np.minimum(tone, base + 1) - 1, tone)
    cv.put(mask, rid, tone)
    cv.clamp()
    if glints:
        lit = np.argwhere(mask & (cv.tone >= len(colors) - 2))
        for y, x in lit[rng.permutation(len(lit))[:glints]]:
            cv.tone[y, x] = len(colors) - 1
    if outline:
        add_outline(cv, rid, *outline)
    return cv


def ore_lobes(rng, scale: float = 1.0, jitter: float = 0.5):
    """Four lobes arranged like a raw-ore chunk (big upper-left, right, bottom, small bottom-left)."""
    base = [(6.3, 6.4, 4.2, 4.0), (10.9, 7.9, 3.2, 3.1), (8.9, 11.3, 3.1, 2.9), (4.4, 11.3, 2.3, 2.2)]
    out = []
    for cx, cy, r, h in base:
        out.append((8 + (cx - 8) * scale + rng.uniform(-jitter, jitter),
                    8 + (cy - 8) * scale + rng.uniform(-jitter, jitter), r * scale, h * scale))
    return out


@item("raw_hematite")
def item_raw_hematite():
    """Lumpy grey-purple metallic chunk (specular hematite) with a couple of rusty-red streak spots."""
    rng = rng_for("raw_hematite", "shape")
    cv = lump_item("raw_hematite", IPAL["hematite"], ore_lobes(rng), cuts=[0.0, 0.35, 0.58, 0.76, 0.9],
                   base=1, bump_amp=0.6, bump_feat=3, glints=2)
    rust = cv.add(["#6b3b31", "#8a4c3c"])
    body = (cv.layer == 0) & (cv.tone >= 2) & (cv.tone <= 3)
    cand = np.argwhere(body)
    for y, x in cand[rng.permutation(len(cand))[:2]]:
        cv.layer[y, x], cv.tone[y, x] = rust, int(rng.integers(0, 2))
    return cv


@item("hematite_spherules")
def item_hematite_spherules():
    """A small heap of hematite concretions ('blueberries'): glossy dark-grey spheres."""
    cv = item_canvas()
    rid = cv.add(IPAL["spherule"])
    spheres = [(5.4, 6.2, 2.7), (10.6, 5.6, 2.5), (8.0, 9.6, 3.0), (4.2, 11.6, 2.3), (11.8, 11.0, 2.4)]
    xs, ys = pixel_grid()
    zbuf = np.full((N, N), -1e9)
    sid = np.full((N, N), -1)
    shade = np.zeros((N, N))
    for k, (cx, cy, r) in enumerate(spheres):
        d2 = (xs - cx) ** 2 + (ys - cy) ** 2
        inside = d2 < r * r
        z = np.sqrt(np.clip(r * r - d2, 0, None)) + cy * 0.5        # lower spheres sit in front
        upd = inside & (z > zbuf)
        nx, ny, nz = (xs - cx) / r, -(ys - cy) / r, np.sqrt(np.clip(1 - d2 / (r * r), 0, 1))
        sh = nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2]
        zbuf[upd], sid[upd], shade[upd] = z[upd], k, sh[upd]
    mask = sid >= 0
    tone = 2 + tones_from_shade(shade, mask, [0.05, 0.4, 0.62, 0.8, 0.93])
    # separation: a sphere's pixel next to the sphere in front of it (below/right) is darkened
    for dy, dx in ((1, 0), (0, 1), (1, 1)):
        nb = shift0(sid, -dy, -dx, -1)
        tone = np.where(mask & (nb >= 0) & (nb != sid) & (shift0(zbuf, -dy, -dx, -1e9) > zbuf), 2, tone)
    cv.put(mask, rid, tone)
    for k, (cx, cy, r) in enumerate(spheres):  # specular highlight
        y, x = int(cy - r * 0.45), int(cx - r * 0.45)
        if sid[y, x] == k:
            cv.tone[y, x] = 7
    cv.clamp()
    add_outline(cv, rid, 1, 0)
    return cv


@item("olivine")
def item_olivine():
    """Gem-quality olivine (peridot): an olive-green faceted oval, table up, lit from the top left."""
    cv = item_canvas()
    rid = cv.add(IPAL["olivine"])
    fid, shades, _ = render_mesh([(gem_mesh(8, 0.55, 0.35, 0.9, sx=0.72), view(0, 75), 6.4, (8.0, 8.0), 0.0)])
    paint_mesh(cv, rid, fid, shades, cuts=[0.0, 0.25, 0.45, 0.62, 0.78, 0.9], base=1, gradient=0.3, ridge=1)
    add_outline(cv, rid, 1, 0)
    table = np.argwhere(fid == 0)
    y, x = table[np.argmin(table[:, 0] + table[:, 1])]
    cv.tone[y, x] = len(IPAL["olivine"]) - 1
    return cv


@item("jarosite")
def item_jarosite():
    """A cluster of small pseudo-cubic (rhombohedral) yellow-brown jarosite crystals."""
    cv = item_canvas()
    rid = cv.add(IPAL["jarosite"])
    rhomb = deform(frustum(1.0, 1.0, 1.0), shear_xy=0.2)
    parts = [(rhomb, view(40, 30, 5), 5.0, (7.6, 9.0), 0.0),
             (rhomb, view(-30, 35), 3.8, (11.6, 11.4), 1.0),
             (rhomb, view(15, 45, -10), 3.4, (4.0, 11.6), 1.5),
             (rhomb, view(-10, 40, 20), 3.0, (10.6, 4.6), -2.0)]
    fid, shades, _ = render_mesh(parts)
    paint_mesh(cv, rid, fid, shades, cuts=[0.15, 0.45, 0.68, 0.84, 0.95], base=2, gradient=0.15, ridge=1)
    cv.shift(inner_edges(fid, shades) & (cv.tone > 2), -1)
    add_outline(cv, rid, 1, 0)
    return cv


@item("gypsum")
def item_gypsum():
    """A white tabular gypsum (selenite) crystal: an elongated hexagonal plate seen from above, with a
    growth step on its face and a pearly glint."""
    cv = item_canvas()
    rid = cv.add(IPAL["gypsum"])
    plate = deform(prism(6, 4.2, 2.0), sx=1.5)
    fid, shades, _ = render_mesh([(plate, view(20, 60, -40), 1.0, (8.0, 8.0), 0.0)])
    paint_mesh(cv, rid, fid, shades, cuts=[-0.2, 0.2, 0.45, 0.65, 0.82], base=2, gradient=0.2, ridge=1)
    cv.shift(inner_edges(fid, shades) & (cv.tone > 2), -1)
    top = fid == int(np.argmax(shades))
    inner = erode(erode(top, False), False)
    xs, ys = pixel_grid()
    step = inner & ~erode(inner, False) & ((xs + ys) > np.mean((xs + ys)[top]))   # lower-right growth step
    cv.shift(step, -1)
    add_outline(cv, rid, 1, 0)
    lit = np.argwhere(top)
    y, x = lit[np.argmin(lit[:, 0] * 1.0 + lit[:, 1] * 0.6)]
    cv.tone[y, x] = len(IPAL["gypsum"]) - 1
    return cv


@item("sulfur_crystals")
def item_sulfur_crystals():
    """Lumps of bright yellow elemental sulfur crystals (orthorhombic dipyramids). Registered as
    redplanet:sulfur_crystals because vanilla 26.x has its own sulfur block."""
    cv = item_canvas()
    rid = cv.add(IPAL["sulfur"])
    dip = prism(4, 2.6, 1.2, tip=3.4)
    parts = [(dip, rot(10, 20, -28), 1.0, (6.8, 8.0), 0.0),
             (dip, rot(-8, 45, 34), 0.82, (10.8, 10.2), 1.5),
             (dip, rot(15, -15, 62), 0.7, (5.0, 12.0), 2.0)]
    fid, shades, _ = render_mesh(parts)
    paint_mesh(cv, rid, fid, shades, cuts=[-0.1, 0.2, 0.45, 0.65, 0.82], base=2, gradient=0.15, ridge=1)
    cv.shift(inner_edges(fid, shades), -1)
    add_outline(cv, rid, 1, 0)
    return cv


@item("raw_chromite")
def item_raw_chromite():
    """Black-brown chromite lump with metallic glints."""
    rng = rng_for("raw_chromite", "shape")
    cv = lump_item("raw_chromite", IPAL["chromite"], ore_lobes(rng, 0.97), cuts=[0.0, 0.35, 0.58, 0.78, 0.9],
                   base=1, bump_amp=0.8, bump_feat=4)
    gl = cv.add(IPAL["chromite_glint"])
    lit = np.argwhere((cv.layer == 0) & (cv.tone >= 4))
    for y, x in lit[rng.permutation(len(lit))[:5]]:
        cv.layer[y, x], cv.tone[y, x] = gl, int(rng.integers(0, 3))
    return cv


@item("chromium_ingot")
def item_chromium_ingot():
    """Chromium: a bright, slightly blue-white silver ingot (distinct from iron's neutral grey)."""
    return ingot(IPAL["chromium"])


@item("nickel_ingot")
def item_nickel_ingot():
    """Nickel: a warm, faintly golden silver ingot."""
    return ingot(IPAL["nickel"])


@item("nickel_nugget")
def item_nickel_nugget():
    """A small, irregular nickel nugget (vanilla nugget scale)."""
    return lump_item("nickel_nugget", IPAL["nickel"],
                     [(7.4, 7.8, 2.3, 2.1), (9.4, 8.7, 2.0, 1.8), (7.7, 9.7, 1.7, 1.4), (6.0, 9.3, 1.2, 1.0)],
                     cuts=[0.05, 0.4, 0.65, 0.84, 0.94], base=2, glints=1)


@item("iron_nickel_chunk")
def item_iron_nickel_chunk():
    """A dark iron-nickel meteorite fragment: metal with a crosshatched (Widmanstatten) sheen on its lit
    faces and brown fusion crust on the shaded side."""
    rng = rng_for("iron_nickel_chunk", "shape")
    cv = lump_item("iron_nickel_chunk", IPAL["meteor_metal"], ore_lobes(rng, 0.98, 0.7),
                   cuts=[-0.05, 0.3, 0.55, 0.74, 0.88], base=1, bump_amp=0.5, bump_feat=3, outline=None)
    metal = 0
    xs, ys = pixel_grid()
    X, Y = np.floor(xs).astype(int), np.floor(ys).astype(int)
    m = cv.layer == metal
    hatch = (((X + 2 * Y) % 5 == 0) | ((X - 2 * Y) % 5 == 0)) & m & (cv.tone >= 3) & (cv.tone <= 5)
    cv.shift(hatch, 2, metal)
    crust = cv.add(IPAL["meteor_crust"])
    cm = m & (cv.tone <= 2) & (noise_mix(rng, [(4, 4, 1.0)], white=0.5) > -0.2)
    cv.put(cm, crust, np.clip(cv.tone, 0, 3))
    add_outline(cv, metal, 1, 0)
    return cv


@item("smectite_clay_ball")
def item_smectite_clay_ball():
    """A ball of greenish-grey smectite clay."""
    return lump_item("smectite_clay_ball", IPAL["smectite"],
                     [(7.6, 8.0, 5.1, 4.4), (9.4, 9.4, 3.9, 3.4), (5.6, 10.2, 2.8, 2.2)],
                     cuts=[-0.05, 0.38, 0.6, 0.78, 0.92], base=2, bump_amp=0.25, bump_feat=3, crevices=False)


@item("perchlorate_salt")
def item_perchlorate_salt():
    """A small pile of white-pink perchlorate salt crystals."""
    rng = rng_for("perchlorate_salt")
    grains = []
    for _ in range(26):
        x = rng.uniform(2.8, 13.2)
        top = 13.2 - 8.5 * (1 - ((x - 8.0) / 5.6) ** 2)       # mound profile
        y = rng.uniform(top + 1.0, 13.2)
        grains.append((x, y, rng.uniform(1.3, 2.1), rng.uniform(1.0, 1.6)))
    grains.append((8.0, 7.2, 2.2, 1.8))
    cv = lump_item("perchlorate_salt", IPAL["perchlorate"], grains, cuts=[-0.2, 0.25, 0.5, 0.7, 0.86],
                   base=2, glints=4)
    return cv


@item("ice_shard")
def item_ice_shard():
    """A bluish-white shard of water ice: an elongated six-sided crystal with pointed ends, plus a chip."""
    cv = item_canvas()
    rid = cv.add(IPAL["ice_shard"])
    shard = prism(6, 2.2, 6.0, tip=4.2, tip2=2.6, squash=0.7)
    chip = prism(6, 1.3, 3.0, tip=2.2, tip2=1.0, squash=0.7)
    fid, shades, _ = render_mesh([(shard, view(40, 30, -45), 1.0, (8.0, 8.0), 0.0),
                                  (chip, view(0, 30, -20), 1.0, (4.6, 11.6), 2.0)])
    paint_mesh(cv, rid, fid, shades, cuts=[-0.2, 0.15, 0.4, 0.62, 0.8], base=2, gradient=0.25, ridge=1)
    cv.shift(inner_edges(fid, shades) & (cv.tone > 2), -1)
    add_outline(cv, rid, 1, 0)
    return cv


@item("dry_ice_chunk")
def item_dry_ice_chunk():
    """A chunk of CO2 ice: a frosty white block with wisps of sublimating vapour curling up."""
    rng = rng_for("dry_ice_chunk")
    cv = item_canvas()
    rid = cv.add(IPAL["dry_ice"])
    cube = deform(frustum(1.0, 1.0, 1.0), sy=0.92)
    fid, shades, _ = render_mesh([(cube, view(35, 35), 7.0, (8.0, 10.0), 0.0)])
    paint_mesh(cv, rid, fid, shades, cuts=[-0.4, -0.05, 0.25, 0.6, 0.8], base=2, gradient=0.2, ridge=1)
    frost = (fid >= 0) & (rng.random((N, N)) < 0.1)
    cv.shift(frost, -1)
    add_outline(cv, rid, 1, 0)
    vap = cv.add(IPAL["vapour"])
    for wisp in ([(5, 3), (4, 2), (4, 1), (5, 0)], [(9, 3), (10, 2), (10, 1), (9, 0)],
                 [(12, 4), (13, 3), (13, 2)]):
        for i, (x, y) in enumerate(wisp):
            if cv.layer[y, x] < 0:
                cv.layer[y, x], cv.tone[y, x] = vap, 2 if i == 0 else (1 if i < len(wisp) - 1 else 0)
    return cv


# =====================================================================================================
# Mod icon: pixel-art Mars with a Starship rising in front of it (64x64 art, shown at 128x128)
# =====================================================================================================

ICON_ART = 64
ICON_PAL = {
    "space": ["#04050a", "#070911", "#0b0e19", "#101523"],
    "stars": ["#4f5670", "#8f97b3", "#d6dcf0", "#ffffff", "#ffe2b0"],
    # globe: night side -> sunlit dusty highlands; endpoints from true-colour Mars imagery
    "globe": ramp(["#1a0d09", "#4a2416", "#7f3f25", "#b2633b", "#d98f5b", "#e7a872"], 10),
    "cap": ["#7d8590", "#b4bcc5", "#e2e6ea", "#f8f9fa"],
    "limb": ["#3a2218", "#6a3e2a"],
    "steel": ["#3b4048", "#5c636d", "#808892", "#a6aeb8", "#c9cfd6", "#e7ebef", "#ffffff"],
    "tiles": ["#121418", "#1d2025", "#2a2e35"],
    "plume": ramp(["#7a2a14", "#e06a28", "#ffc965", "#fffdf4"], 7),
    "outline": ["#06070c"],
}


def _albedo_lookup():
    """TES albedo (0..1) sampler from docs/images/mars-albedo.png, or a procedural stand-in."""
    if ALBEDO_MAP.exists():
        img = Image.open(ALBEDO_MAP).convert("L").resize((360, 180), Image.BOX)  # 1 px per degree, smoothed
        a = np.asarray(img, float) / 255.0   # 0..360 E, 90N at row 0
        h, w = a.shape

        def look(lat, lon):
            r = np.clip(((90.0 - lat) / 180.0 * h).astype(int), 0, h - 1)
            c = ((lon % 360.0) / 360.0 * w).astype(int) % w
            return a[r, c]
        return look
    rng = rng_for("icon", "albedo")
    field = normalize(value_noise(rng, 8, 4, n=180)[:90] + 0.5 * value_noise(rng, 24, 12, n=180)[:90])

    def look(lat, lon):
        r = np.clip(((90.0 - lat) / 180.0 * 90).astype(int), 0, 89)
        c = ((lon % 360.0) / 360.0 * 180).astype(int) % 180
        return np.clip(0.5 + 0.18 * field[r, c], 0, 1)
    return look


def make_icon() -> np.ndarray:
    """The 128x128 mod icon as an RGBA array (opaque): 64x64 pixel art doubled with nearest neighbour."""
    S = ICON_ART
    rng = rng_for("icon")
    img = np.zeros((S, S, 3), np.uint8)
    pal = {k: np.array([hexc(c) if isinstance(c, str) else c for c in v], np.uint8) for k, v in ICON_PAL.items()}
    xs, ys = pixel_grid(S)

    # deep space: a faint diagonal glow, ordered-dithered into 4 levels
    glow = np.clip(1.0 - np.hypot(xs - 10, ys - 58) / 70.0, 0, 1) * 2.6
    img[:] = pal["space"][ordered_dither(glow, 4, 1.0)]

    # Mars: orthographic globe centred on Arabia Terra / Syrtis Major, north pole tilted toward us
    cx, cy, R = 39.0, 30.5, 23.5
    u, v = (xs - cx) / R, -(ys - cy) / R
    rho = np.hypot(u, v)
    disc = rho <= 1.0
    lat0, lon0 = math.radians(22.0), 58.0          # Syrtis Major just right of centre
    c = np.arcsin(np.clip(rho, 0, 1))
    with np.errstate(invalid="ignore", divide="ignore"):
        lat = np.degrees(np.arcsin(np.clip(np.cos(c) * math.sin(lat0) + np.where(rho > 0, v * np.sin(c) * math.cos(lat0) / rho, 0), -1, 1)))
        lon = lon0 + np.degrees(np.arctan2(u * np.sin(c), rho * np.cos(c) * math.cos(lat0) - v * np.sin(c) * math.sin(lat0)))
    alb = _albedo_lookup()(lat, lon)
    nz = np.sqrt(np.clip(1 - rho ** 2, 0, 1))
    sun = np.array([-0.62, 0.42, 0.66])
    sun /= np.linalg.norm(sun)
    lam = np.clip(u * sun[0] + v * sun[1] + nz * sun[2], 0, 1)
    light = 0.06 + 0.94 * lam ** 0.8
    # albedo contrast: dark basaltic terrains vs bright dust, then lighting -> palette levels with dither
    a_n = np.clip((alb - 0.22) / 0.55, 0, 1) ** 0.9
    level = light * (3.6 + 5.8 * a_n)
    gl = ordered_dither(level, len(ICON_PAL["globe"]), np.where(lam < 0.35, 0.9, 0.45))
    img[disc] = pal["globe"][gl[disc]]
    # north polar residual cap (drawn a little larger than the real ~1000 km so it reads at icon size)
    cap = disc & (lat > 72.0 + 2.5 * normalize(value_noise(rng, 16, 8, n=S)))
    capl = ordered_dither(light * 3.6 + 0.3, 4, 0.6)
    img[cap] = pal["cap"][capl[cap]]
    # thin dusty limb on the sunlit side
    ring = (rho > 1.0) & (rho <= 1.0 + 1.3 / R)
    lit_ring = ring & ((u * sun[0] + v * sun[1]) > 0.15)
    img[lit_ring] = pal["limb"][((u * sun[0] + v * sun[1]) > 0.55)[lit_ring].astype(int)]

    # stars (away from Mars and the ship)
    ship_cx = 17.5
    occupied = (rho <= 1.12) | ((np.abs(xs - ship_cx) < 9) & (ys > 4))
    stars = [(5, 6, 3), (12, 3, 2), (28, 4, 1), (3, 20, 1), (9, 15, 2), (58, 3, 3), (63, 12, 1),
             (61, 58, 2), (50, 61, 1), (35, 60, 2), (4, 39, 2), (26, 52, 1), (2, 54, 1), (44, 2, 1),
             (22, 6, 1), (31, 57, 1), (57, 56, 1)]
    for x, y, b in stars:
        if 0 <= x < S and 0 <= y < S and not occupied[y, x]:
            img[y, x] = pal["stars"][b]
    for x, y in ((7, 26), (54, 59)):  # two twinkles
        if not occupied[y, x]:
            img[y, x] = pal["stars"][3]
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if not occupied[y + dy, x + dx]:
                    img[y + dy, x + dx] = pal["stars"][1]
    # Phobos: a tiny lumpy grey moon just off the limb
    for x, y, t in ((58, 50, 2), (59, 50, 1), (58, 51, 1), (59, 51, 0), (60, 51, 0)):
        img[y, x] = np.array(hexc(["#3d3836", "#5f5853", "#857c75"][t]), np.uint8)

    # Starship: stainless barrel, tangent-ogive nose, forward and aft flaps, black heat shield on the
    # windward (right) side, three engine bells and a methalox plume. ~1 px per 1.3 m.
    ship = np.zeros((S, S), bool)
    shade = np.zeros((S, S))
    tiles = np.zeros((S, S), bool)
    Rb, tip, nose_base, base_y = 3.5, 7, 18, 47
    Ln = nose_base - tip
    rho_o = (Rb * Rb + Ln * Ln) / (2 * Rb)
    for y in range(tip, base_y + 1):
        sdist = max(0.0, nose_base - (y + 0.5))
        r = Rb if y + 0.5 >= nose_base else max(0.0, math.sqrt(max(rho_o ** 2 - sdist ** 2, 0)) + Rb - rho_o)
        for x in range(S):
            dx = x + 0.5 - (ship_cx + 1.0)
            if r > 0.35 and abs(dx) < max(r, 0.6):
                ship[y, x] = True
                nx = np.clip(dx / max(r, 0.6), -1, 1)
                shade[y, x] = -0.72 * nx + 0.69 * math.sqrt(1 - nx * nx)
                tiles[y, x] = nx > 0.3
    # flaps: a small forward pair below the nose and a large aft pair (left = lit steel, right = tiles)
    flap_px = []
    for y in range(19, 25):
        k = 1 if y < 21 else 2
        flap_px += [(y, int(ship_cx + 1 - Rb) - i - 1) for i in range(k)]
        flap_px += [(y, int(ship_cx + 1 + Rb) + i) for i in range(k)]
    for y in range(37, base_y + 1):
        k = 1 + (y - 37) * 3 // (base_y - 37)
        flap_px += [(y, int(ship_cx + 1 - Rb) - i - 1) for i in range(k)]
        flap_px += [(y, int(ship_cx + 1 + Rb) + i) for i in range(k)]
    flaps = np.zeros((S, S), bool)
    for y, x in flap_px:
        flaps[y, x] = True
    steel = np.clip(np.round(shade * 5.4 - 0.4), 0, 6).astype(int)
    body = ship & ~tiles
    img[body] = pal["steel"][steel[body]]
    tile_t = ((xs.astype(int) + ys.astype(int)) % 2 == 0).astype(int) + (shade > 0.3)
    img[tiles] = pal["tiles"][np.clip(tile_t, 0, 2)[tiles]]
    left_flaps = flaps & (xs < ship_cx + 1)
    img[left_flaps] = pal["steel"][2]
    img[flaps & ~left_flaps] = pal["tiles"][1]
    # two welded ring seams on the barrel
    for y in (28, 36):
        for x in range(S):
            if body[y, x]:
                img[y, x] = pal["steel"][max(0, steel[y, x] - 1)]
    # engine skirt and bells
    for x in range(int(ship_cx + 1 - Rb), int(ship_cx + 1 + Rb) + 1):
        if ship[base_y, x]:
            img[base_y, x] = pal["steel"][1]
    bells = [(base_y + 1, x) for x in (int(ship_cx) - 1, int(ship_cx) + 1, int(ship_cx) + 3)]
    for y, x in bells:
        img[y, x] = pal["steel"][0]
    # plume: hot core to cool fringe, dithered into space
    py0 = base_y + 2
    for y in range(py0, S):
        t = (y - py0) / (S - py0)
        half = 1.2 + 3.2 * t
        for x in range(S):
            dx = abs(x + 0.5 - (ship_cx + 1.0))
            if dx > half + 0.6:
                continue
            heat = (1 - t) * 6.4 - dx / (half + 0.6) * 3.4 + rng.uniform(-0.5, 0.5)
            if heat < 0.2 and BAYER4[y % 4, x % 4] + 0.5 > heat + 0.35:
                continue
            img[y, x] = pal["plume"][int(np.clip(heat, 0, 6))]
    # 1 px dark outline around the ship (not the plume) so it reads against Mars and space
    solid = ship | flaps
    for y, x in bells:
        solid[y, x] = True
    ring = dilate(solid, 1, wrap=False) & ~solid
    plume_rows = ys >= py0
    img[ring & ~plume_rows] = pal["outline"][0]

    big = np.repeat(np.repeat(img, 2, axis=0), 2, axis=1)
    out = np.dstack([big, np.full(big.shape[:2], 255, np.uint8)])
    return out


# =====================================================================================================
# Output, validation and previews
# =====================================================================================================

def to_rgba(result) -> np.ndarray:
    return result.rgba() if isinstance(result, Canvas) else np.asarray(result, np.uint8)


def save_png(arr: np.ndarray, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(arr, "RGBA").save(path, optimize=True)


def seam_score(arr: np.ndarray) -> float:
    """Heuristic seam check: the mean colour jump across the wrap-around boundary divided by the largest
    jump across any of the 15 interior row (or column) boundaries, worst axis. Everything here is
    generated on a torus, so this only catches code that is accidentally non-periodic; mortar lines and
    band edges raise the reference too, so patterns are judged against themselves. <= ~1.5 is fine; the
    3x3 tilings in the contact sheet are the real check."""
    a = arr[..., :3].astype(float)
    out = []
    for axis in (0, 1):
        jumps = np.abs(a - np.roll(a, -1, axis=axis)).mean(axis=(1 - axis, 2))   # boundary k -> k+1
        out.append(jumps[-1] / max(jumps[:-1].max(), 1e-6))
    return float(max(out))


FRAMED = {"polished_mars_basalt", "polished_mudstone"}  # deliberate border bevel: seam expected


def validate(name: str, arr: np.ndarray, kind: str) -> list[str]:
    msgs = []
    cols = {tuple(c) for c in arr.reshape(-1, 4) if c[3] > 0}
    if kind == "block":
        if name in SOLID_EXCEPTIONS:
            al = arr[..., 3]
            if al.min() < 160 or al.max() > 215:
                msgs.append(f"translucent alpha out of range {al.min()}-{al.max()}")
        elif (arr[..., 3] != 255).any():
            msgs.append("solid block has non-opaque pixels")
        if not 6 <= len(cols) <= 12:
            msgs.append(f"{len(cols)} colours")
        z = seam_score(arr)
        if z > 1.5 and name not in FRAMED:
            msgs.append(f"seam score {z:.2f}")
    else:
        if not set(np.unique(arr[..., 3])) <= {0, 255}:
            msgs.append("item has partial alpha")
        if not 6 <= len(cols) <= 12:
            msgs.append(f"{len(cols)} colours")
    return msgs


# ----------------------------------------------------------------------------------------- previews

PREVIEW_BG = (38, 36, 40, 255)
SLOT_BG = (139, 139, 139, 255)  # inventory slot grey, for items


def font(size: int):
    try:
        return ImageFont.load_default(size=size)
    except TypeError:  # Pillow without FreeType
        return ImageFont.load_default()


def upscale(arr: np.ndarray, k: int) -> Image.Image:
    return Image.fromarray(arr, "RGBA").resize((arr.shape[1] * k, arr.shape[0] * k), Image.NEAREST)


def tiled(arr: np.ndarray, reps: int = 3) -> np.ndarray:
    return np.tile(arr, (reps, reps, 1))


def checker(w: int, h: int, cell: int = 8, a=(58, 56, 62, 255), b=(70, 68, 74, 255)) -> Image.Image:
    yy, xx = np.mgrid[0:h, 0:w]
    m = ((yy // cell + xx // cell) % 2).astype(bool)
    out = np.where(m[..., None], np.array(b, np.uint8), np.array(a, np.uint8))
    return Image.fromarray(out.astype(np.uint8), "RGBA")


def paste_rgba(dst: Image.Image, src: Image.Image, xy, bg=None) -> None:
    if bg is not None:
        if bg == "checker":
            dst.paste(checker(*src.size), xy)
        else:
            dst.paste(Image.new("RGBA", src.size, bg), xy)
    tmp = dst.crop((xy[0], xy[1], xy[0] + src.size[0], xy[1] + src.size[1]))
    tmp.alpha_composite(src)
    dst.paste(tmp, xy)


def contact_sheet(results: dict, icon: np.ndarray | None, path: Path, cols_blocks: int = 4, cols_items: int = 6):
    """Every texture at 8x, labelled; blocks also as a 3x3 tiling at 4x to check seams."""
    f_title, f_label = font(22), font(14)
    pad, label_h = 14, 20
    bcell_w, bcell_h = 128 + 8 + 192, 192 + label_h
    icell_w, icell_h = 128 * 2 + 8, 128 + label_h
    blocks, items = results["block"], results["item"]
    brows = math.ceil(len(blocks) / cols_blocks)
    irows = math.ceil(len(items) / cols_items)
    W = max(cols_blocks * (bcell_w + pad), cols_items * (icell_w + pad)) + pad
    H = 50 + 30 + brows * (bcell_h + pad) + 30 + irows * (icell_h + pad) + (30 + 256 + pad if icon is not None else 0) + pad
    sheet = Image.new("RGBA", (W, H), PREVIEW_BG)
    d = ImageDraw.Draw(sheet)
    d.text((pad, 12), "Red Planet: Starship to Mars - procedural textures (gen_textures.py)", fill=(240, 230, 220, 255), font=f_title)
    y = 50
    d.text((pad, y), f"Blocks ({len(blocks)}): 8x and 3x3 tiling at 4x", fill=(230, 180, 140, 255), font=f_label)
    y += 30
    for i, (name, arr) in enumerate(blocks.items()):
        x0 = pad + (i % cols_blocks) * (bcell_w + pad)
        y0 = y + (i // cols_blocks) * (bcell_h + pad)
        d.text((x0, y0), name, fill=(235, 235, 235, 255), font=f_label)
        bg = "checker" if arr[..., 3].min() < 255 else None
        paste_rgba(sheet, upscale(arr, 8), (x0, y0 + label_h), bg)
        paste_rgba(sheet, upscale(tiled(arr), 4), (x0 + 136, y0 + label_h), bg)
    y += brows * (bcell_h + pad) + 4
    d.text((pad, y), f"Items ({len(items)}): 8x on slot grey and on dark", fill=(230, 180, 140, 255), font=f_label)
    y += 30
    for i, (name, arr) in enumerate(items.items()):
        x0 = pad + (i % cols_items) * (icell_w + pad)
        y0 = y + (i // cols_items) * (icell_h + pad)
        d.text((x0, y0), name, fill=(235, 235, 235, 255), font=f_label)
        paste_rgba(sheet, upscale(arr, 8), (x0, y0 + label_h), SLOT_BG)
        paste_rgba(sheet, upscale(arr, 8), (x0 + 136, y0 + label_h), (30, 30, 34, 255))
    y += irows * (icell_h + pad) + 4
    if icon is not None:
        d.text((pad, y), "Mod icon (128x128, shown 1x and 2x)", fill=(230, 180, 140, 255), font=f_label)
        y += 30
        ic = Image.fromarray(icon, "RGBA")
        sheet.paste(ic, (pad, y))
        sheet.paste(ic.resize((256, 256), Image.NEAREST), (pad + 128 + pad, y))
    path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(path, optimize=True)



COMPARE = [
    ("regolith", ["block/red_sand", "block/coarse_dirt", "block/terracotta"]),
    ("mars_dust", ["block/sand", "block/red_sand"]),
    ("basaltic_sand", ["block/gravel", "block/black_concrete_powder"]),
    ("hematite_spherule_regolith", ["block/red_sand", "block/gravel"]),
    ("ice_rich_regolith", ["block/coarse_dirt", "block/packed_mud"]),
    ("mars_stone", ["block/stone", "block/granite", "block/red_sandstone"]),
    ("mars_cobblestone", ["block/cobblestone", "block/cobbled_deepslate"]),
    ("mars_stone_bricks", ["block/stone_bricks", "block/mud_bricks"]),
    ("mars_basalt_side", ["block/basalt_side", "block/smooth_basalt"]),
    ("mars_basalt_top", ["block/basalt_top"]),
    ("polished_mars_basalt", ["block/polished_basalt_side", "block/polished_deepslate"]),
    ("mars_basalt_bricks", ["block/deepslate_bricks", "block/polished_blackstone_bricks"]),
    ("mudstone", ["block/calcite", "block/packed_mud", "block/white_terracotta"]),
    ("polished_mudstone", ["block/polished_andesite", "block/polished_diorite"]),
    ("layered_sediment_side", ["block/sandstone", "block/dripstone_block"]),
    ("layered_sediment_top", ["block/sandstone_top"]),
    ("delta_sediment", ["block/tuff", "block/light_gray_terracotta"]),
    ("smectite_clay", ["block/clay", "block/green_terracotta"]),
    ("carbonate_rock", ["block/calcite", "block/diorite"]),
    ("polar_water_ice", ["block/packed_ice", "block/snow"]),
    ("water_ice", ["block/ice"]),
    ("polar_layered_deposit_side", ["block/packed_ice", "block/sandstone"]),
    ("polar_layered_deposit_top", ["block/snow"]),
    ("co2_ice", ["block/packed_ice", "block/blue_ice"]),
    ("dry_ice", ["block/packed_ice", "block/snow"]),
    ("co2_frost", ["block/snow", "block/powder_snow"]),
    ("hematite_ore", ["block/iron_ore", "block/deepslate_iron_ore"]),
    ("olivine_basalt", ["block/emerald_ore", "block/basalt_side"]),
    ("jarosite_ore", ["block/gold_ore", "block/copper_ore"]),
    ("gypsum_vein", ["block/diorite", "block/calcite"]),
    ("sulfur_deposit", ["block/gold_ore", "block/nether_gold_ore"]),
    ("mars_chromite_ore", ["block/coal_ore", "block/iron_ore"]),
    ("iron_nickel_meteorite", ["block/iron_block", "block/raw_iron_block"]),
    ("chromite_ore", ["block/coal_ore", "block/iron_ore"]),
    ("deepslate_chromite_ore", ["block/deepslate_coal_ore", "block/deepslate_iron_ore"]),
    ("raw_hematite", ["item/raw_iron", "item/netherite_scrap"]),
    ("hematite_spherules", ["item/flint", "item/coal"]),
    ("olivine", ["item/emerald", "item/diamond"]),
    ("jarosite", ["item/raw_gold", "item/glowstone_dust"]),
    ("gypsum", ["item/quartz", "item/sugar"]),
    ("sulfur_crystals", ["item/glowstone_dust", "item/raw_gold"]),
    ("raw_chromite", ["item/raw_iron", "item/coal"]),
    ("chromium_ingot", ["item/iron_ingot", "item/netherite_ingot"]),
    ("iron_nickel_chunk", ["item/raw_iron", "item/netherite_scrap"]),
    ("nickel_ingot", ["item/iron_ingot", "item/gold_ingot"]),
    ("nickel_nugget", ["item/iron_nugget", "item/gold_nugget"]),
    ("smectite_clay_ball", ["item/clay_ball", "item/snowball"]),
    ("perchlorate_salt", ["item/sugar", "item/redstone"]),
    ("ice_shard", ["item/amethyst_shard", "item/prismarine_shard"]),
    ("dry_ice_chunk", ["item/snowball", "item/prismarine_crystals"]),
]


def load_vanilla(vdir: Path, rel: str):
    p = vdir / f"{rel}.png"
    if not p.exists():
        return None
    a = np.asarray(Image.open(p).convert("RGBA"))
    return a[:16, :16].copy()   # first frame of animated strips


def vanilla_comparison(results: dict, vdir: Path, path: Path, k: int = 6) -> bool:
    """Each texture next to the vanilla textures it should sit beside (style check). Needs the vanilla
    client assets; returns False (and writes nothing) if they are not available."""
    if not vdir.exists():
        return False
    f_label = font(12)
    cell, gap, label_h = 16 * k, 6, 16
    group_w = 3 * cell + 2 * gap + 18
    cols = 3
    rows = math.ceil(len(COMPARE) / cols)
    W = cols * group_w + 20
    H = 44 + rows * (cell + label_h + 14) + 10
    sheet = Image.new("RGBA", (W, H), PREVIEW_BG)
    d = ImageDraw.Draw(sheet)
    d.text((10, 10), "Style check: ours (left, outlined) next to vanilla 26.3 references", fill=(240, 230, 220, 255), font=font(18))
    for i, (name, refs) in enumerate(COMPARE):
        kind = "block" if name in results["block"] else "item"
        ours = results[kind].get(name)
        if ours is None:
            continue
        x0 = 10 + (i % cols) * group_w
        y0 = 44 + (i // cols) * (cell + label_h + 14)
        d.text((x0, y0), name, fill=(240, 200, 160, 255), font=f_label)
        bg = SLOT_BG if kind == "item" else ("checker" if ours[..., 3].min() < 255 else None)
        paste_rgba(sheet, upscale(ours, k), (x0, y0 + label_h), bg)
        d.rectangle([x0 - 2, y0 + label_h - 2, x0 + cell + 1, y0 + label_h + cell + 1], outline=(230, 160, 110, 255))
        for j, rel in enumerate(refs[:2]):
            v = load_vanilla(vdir, rel)
            if v is None:
                continue
            xx = x0 + (j + 1) * (cell + gap) + 4
            vbg = SLOT_BG if rel.startswith("item/") else ("checker" if v[..., 3].min() < 255 else None)
            paste_rgba(sheet, upscale(v, k), (xx, y0 + label_h), vbg)
            d.text((xx, y0 + label_h + cell - 13), rel.split("/")[1][:18], fill=(255, 255, 255, 200), font=font(10))
    path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(path, optimize=True)
    return True


def distance_view(results: dict, path: Path, reps: int = 8):
    """Each block tiled 8x8 at 1:1 and 2:1 - how it reads from a distance and whether repetition shows."""
    blocks = results["block"]
    f_label = font(12)
    cw = 16 * reps * 3 + 16
    ch = 16 * reps * 2 + 22
    cols = 4
    rows = math.ceil(len(blocks) / cols)
    sheet = Image.new("RGBA", (cols * (cw + 10) + 10, rows * (ch + 8) + 40), PREVIEW_BG)
    d = ImageDraw.Draw(sheet)
    d.text((10, 10), "Distance view: 8x8 tiling at 1:1 (left) and 2:1 (right)", fill=(240, 230, 220, 255), font=font(18))
    for i, (name, arr) in enumerate(blocks.items()):
        x0 = 10 + (i % cols) * (cw + 10)
        y0 = 40 + (i // cols) * (ch + 8)
        d.text((x0, y0), name, fill=(235, 235, 235, 255), font=f_label)
        t = tiled(arr, reps)
        bg = "checker" if arr[..., 3].min() < 255 else None
        paste_rgba(sheet, Image.fromarray(t, "RGBA"), (x0, y0 + 18), bg)
        paste_rgba(sheet, upscale(t, 2), (x0 + 16 * reps + 12, y0 + 18), bg)
    sheet.save(path, optimize=True)


def _face(dst: Image.Image, tex: np.ndarray, origin, du, dv, light: float):
    """Draw a texture as the parallelogram origin + u*du + v*dv (u, v in texels), nearest sampling."""
    a = np.array([[du[0], dv[0]], [du[1], dv[1]]], float)
    inv = np.linalg.inv(a)
    t = tex.astype(float).copy()
    t[..., :3] *= light
    src = Image.fromarray(np.clip(t, 0, 255).astype(np.uint8), "RGBA")
    ox, oy = origin
    coeffs = (inv[0, 0], inv[0, 1], -(inv[0, 0] * ox + inv[0, 1] * oy),
              inv[1, 0], inv[1, 1], -(inv[1, 0] * ox + inv[1, 1] * oy))
    layer = src.transform(dst.size, Image.AFFINE, coeffs, resample=Image.NEAREST)
    dst.alpha_composite(layer)


def iso_scene(results: dict, path: Path, s: float = 2.0):
    """A small isometric Mars diorama with Minecraft-style face shading (top 1.0, south 0.8, east 0.6):
    checks how the textures sit together in a world."""
    B = results["block"]

    def tex(name, face):
        for cand in ((f"{name}_{face}", f"{name}_side", name) if face == "side" else (f"{name}_top", name)):
            if cand in B:
                return B[cand]
        raise KeyError(name)

    # (x, z): column of blocks bottom -> top
    W_, D_ = 10, 10
    cols = {}
    for x in range(W_):
        for z in range(D_):
            cols[(x, z)] = ["mars_stone", "regolith"]
    for x, z in ((3, 4), (4, 4), (4, 5), (5, 5), (3, 5), (5, 6)):
        cols[(x, z)][-1] = "mars_dust"
    for x, z in ((6, 7), (7, 7), (7, 8), (8, 8), (6, 8), (8, 9), (9, 9), (7, 9)):
        cols[(x, z)][-1] = "basaltic_sand"
    for x, z in ((2, 7), (3, 7), (2, 8)):
        cols[(x, z)][-1] = "hematite_spherule_regolith"
    for x, z in ((8, 3), (9, 3), (9, 4)):
        cols[(x, z)][-1] = "ice_rich_regolith"
    cols[(1, 1)] += ["layered_sediment", "layered_sediment", "layered_sediment"]
    cols[(2, 1)] += ["mudstone", "gypsum_vein", "layered_sediment"]
    cols[(3, 1)] += ["layered_sediment", "layered_sediment"]
    cols[(1, 2)] += ["delta_sediment", "smectite_clay"]
    cols[(1, 3)] += ["carbonate_rock"]
    cols[(0, 0)] += ["mars_stone", "hematite_ore", "mars_stone", "sulfur_deposit"]
    cols[(0, 1)] += ["jarosite_ore", "mars_chromite_ore", "mars_stone"]
    cols[(0, 2)] += ["mars_stone", "olivine_basalt"]
    cols[(1, 0)] += ["mars_cobblestone", "mars_stone", "mars_stone"]
    cols[(2, 0)] += ["mars_stone", "mars_stone"]
    cols[(6, 1)] += ["mars_basalt", "mars_basalt", "mars_basalt", "mars_basalt"]
    cols[(7, 1)] += ["mars_basalt", "mars_basalt"]
    cols[(6, 2)] += ["mars_basalt"]
    cols[(8, 0)] += ["polar_layered_deposit", "polar_layered_deposit", "polar_water_ice"]
    cols[(9, 0)] += ["polar_layered_deposit", "polar_water_ice", "co2_ice", "co2_frost"]
    cols[(9, 1)] += ["polar_water_ice", "dry_ice"]
    cols[(8, 1)] += ["water_ice"]
    cols[(4, 8)] += ["mars_stone_bricks", "mars_stone_bricks"]
    cols[(5, 8)] += ["mars_basalt_bricks", "polished_mars_basalt"]
    cols[(4, 9)] += ["polished_mudstone"]
    cols[(5, 2)] += ["iron_nickel_meteorite"]
    cols[(6, 4)] += ["chromite_ore"]
    cols[(7, 4)] += ["deepslate_chromite_ore"]

    E = np.array([1.0, 0.5]) * s
    S = np.array([-1.0, 0.5]) * s
    U = np.array([0.0, -1.0]) * s
    width = int((W_ + D_) * 16 * s) + 40
    height = int((W_ + D_) * 8 * s + 9 * 16 * s) + 40
    # butterscotch sky gradient
    sky = np.zeros((height, width, 4), np.uint8)
    t = np.linspace(0, 1, height)[:, None]
    sky[..., 0] = (196 - 70 * t).astype(np.uint8)
    sky[..., 1] = (150 - 70 * t).astype(np.uint8)
    sky[..., 2] = (110 - 60 * t).astype(np.uint8)
    sky[..., 3] = 255
    img = Image.fromarray(sky, "RGBA")
    origin0 = np.array([D_ * 16 * s + 20, 9 * 16 * s + 10])
    order = sorted(((x, z, y) for (x, z), col in cols.items() for y in range(len(col))), key=lambda p: (p[0] + p[1], p[2]))
    for x, z, y in order:
        name = cols[(x, z)][y]
        is_top = y == len(cols[(x, z)]) - 1
        base = origin0 + E * 16 * x + S * 16 * z + U * 16 * y
        top_o = base + U * 16
        if is_top:  # lower top faces are hidden by the block above
            _face(img, tex(name, "top"), top_o, E, S, 1.0)
        _face(img, tex(name, "side"), top_o + S * 16, E, -U, 0.8)
        _face(img, tex(name, "side"), top_o + E * 16 + S * 16, -S, -U, 0.6)
    img = img.crop(img.getbbox())
    img.save(path, optimize=True)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--only", help="comma-separated texture names")
    ap.add_argument("--no-preview", action="store_true")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--vanilla", type=Path, default=DEFAULT_VANILLA,
                    help="vanilla textures dir, for the comparison preview only")
    args = ap.parse_args(argv)
    if args.list:
        print("blocks:", " ".join(BLOCKS))
        print("items:", " ".join(ITEMS))
        return 0
    only = set(args.only.split(",")) if args.only else None
    if only:
        unknown = only - set(BLOCKS) - set(ITEMS) - {"icon"}
        if unknown:
            print(f"unknown texture names: {', '.join(sorted(unknown))} (see --list)")
            return 2
    results = {"block": {}, "item": {}}
    problems = written_count = 0
    for kind, table, outdir in (("block", BLOCKS, BLOCK_DIR), ("item", ITEMS, ITEM_DIR)):
        for name, fn in table.items():
            arr = to_rgba(fn())
            results[kind][name] = arr
            if only and name not in only:
                continue
            save_png(arr, outdir / f"{name}.png")
            msgs = validate(name, arr, kind)
            ncol = len({tuple(c) for c in arr.reshape(-1, 4) if c[3] > 0})
            extra = f" seam={seam_score(arr):.2f}" if kind == "block" else ""
            print(f"  {kind:5s} {name:30s} {ncol:2d} colours{extra}" + (f"  !! {'; '.join(msgs)}" if msgs else ""))
            problems += bool(msgs)
            written_count += 1
    print(f"{written_count} textures written, {problems} with warnings")
    icon = make_icon()
    if not only or "icon" in only:
        save_png(icon, ICON_PATH)
        print(f"  icon  {ICON_PATH.relative_to(ROOT)} {icon.shape[1]}x{icon.shape[0]}")
    if not args.no_preview:
        contact_sheet(results, icon, PREVIEW_DIR / "contact_sheet.png")
        written = ["contact_sheet.png"]
        if vanilla_comparison(results, args.vanilla, PREVIEW_DIR / "vanilla_comparison.png"):
            written.append("vanilla_comparison.png")
        else:
            print(f"  (vanilla textures not found at {args.vanilla}; skipped the comparison sheet)")
        distance_view(results, PREVIEW_DIR / "distance_view.png")
        iso_scene(results, PREVIEW_DIR / "iso_scene.png")
        written += ["distance_view.png", "iso_scene.png"]
        print(f"previews in {PREVIEW_DIR.relative_to(ROOT)}: {', '.join(written)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
