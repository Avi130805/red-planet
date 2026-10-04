#!/usr/bin/env python3
"""
Vehicle textures for "Red Planet: Starship to Mars": the Starship (V3) and Super Heavy texture atlases sampled by the
procedural meshes in io.github.avi130805.redplanet.starship.geometry, their frost and cabin-light overlays, and the
two vehicle item icons.

Run from anywhere (paths are resolved from this file):

    python3 tools/textures/gen_vehicle_textures.py               # textures, item icons and previews
    python3 tools/textures/gen_vehicle_textures.py --no-preview  # textures and item icons only

Outputs
    src/client/resources/assets/redplanet/textures/entity/starship/
        ship.png (1024x1024)       upper stage: nose + barrel, flaps, engines, aft bulkhead, legs, crew cabin interior
        booster.png (1024x2048)    Super Heavy: barrel, hot-staging ring, aft bulkhead, grid fins, engines
        ship_frost.png, booster_frost.png   translucent frost over the propellant tanks (same layout as the base)
        ship_lights.png            emissive cabin windows and navigation lights (same layout as ship.png)
    src/client/resources/assets/redplanet/textures/item/starship.png, super_heavy.png   16x16 item icons
    tools/textures/preview/vehicle_atlases.png, vehicle_views.png, vehicle_closeups.png, vehicle_cabin.png,
        vehicle_items.png

UV contract (read from UvLayout.java and StarshipGeometry.java at run time; the built-in copies below are the
fallback, and differences are printed):
    16 px per metre. Lathe regions: s = angle / 2 pi from +Z (windward, the heat-shield side) toward +X, so
    x = r sin(2 pi s), z = r cos(2 pi s); column 0 is the neighbour of column W-1, so everything here is painted from
    the angle and wraps without a seam. Barrels: t = 0 at the top, 1 at the bottom. Nose: t = 0 at the tip, 1 at the
    base, by arc length along the tangent ogive (replicated finely from buildShip). Engine bells: t = 0 at the throat,
    1 at the exit; the powerhead reuses the outer region with t = 1 at the throat and 0 at its top. Discs: planar,
    u = (x/R + 1)/2, v = (z/R + 1)/2 with R the radius passed to disc() (the cabin floor and ceiling use the cabin
    wall's radius there, not 4.5 m). Plates: s along the span from the root, t along the height from the top; edge
    quads use u across the thickness and a quarter of v per outline edge.

Alpha: ship.png and booster.png are opaque everywhere except the cabin-wall window openings (alpha 0, exactly the
same angle and height ranges as the windows painted on the nose) and the grid-fin holes (alpha 0, an alpha-tested
lattice). The overlays are transparent except where painted. Unused atlas space is filled by extending the nearest
region, and transparent pixels carry the colour of their surroundings, so mipmaps don't bleed dark fringes.

Every random choice is seeded from a fixed string (CRC32), so reruns are byte-identical.

Numbers and approximations chosen here (they belong in docs/SCIENCE.md; the run prints them):
    steel rings ~1.8 m, the nearest whole count per section: ship barrel 19 x 1.81 m, nose 10 x 1.85 m of arc,
    booster 37 x 1.78 m up to the forward section (66 m) and 2 x 2.05 m above; 2-3 sheets per ring, seams staggered.
    Heat-shield tiles: pointy-top hexagons on a 6 px pitch with 5 px rows (0.375 m x 0.31 m; real tiles are ~0.3 m,
    enlarged 25 % so the honeycomb reads at 16 px/m), on a +-100 deg windward arc whose edge steps by whole tiles
    (and wanders by one tile now and then), widening over the nose from 4.9 m below the tip until the last 1.5 m are
    fully tiled, reaching round the fore-flap hinges (to 121 deg, 36.2-43.9 m), none on the bottom 0.5 m of the skirt.
    Crew deck: 9 windows 0.60 x 0.90 m centred at 39.10 m and 9 transoms 0.40 x 0.30 m centred at 39.95 m, one per
    cabin-wall panel column (12.41 deg pitch, centred on the leeward line, so they span 126-234 deg). Frost over the
    tank spans in SHIP_TANKS / BOOSTER_TANKS, thinner at the common dome and on tiles and chines.
"""
from __future__ import annotations

import argparse
import hashlib
import math
import re
import sys
import time
import zlib
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage

sys.dont_write_bytecode = True  # importing gen_textures must not leave a __pycache__ in the repository
HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
from gen_textures import oklab_to_rgb, rgb_to_oklab  # noqa: E402  (pure colour helpers; the import has no side effects)

ROOT = HERE.parents[1]
GEOM_DIR = ROOT / "src/main/java/io/github/avi130805/redplanet/starship/geometry"
TEX_DIR = ROOT / "src/client/resources/assets/redplanet/textures"
OUT_DIR = TEX_DIR / "entity/starship"
ITEM_DIR = TEX_DIR / "item"
PREVIEW_DIR = HERE / "preview"

TAU = 2.0 * math.pi
PX_PER_M = 16

# ===================================================================================================================
# Design numbers (see the module docstring; every one is reported for docs/SCIENCE.md)
# ===================================================================================================================

RING_NOMINAL_M = 1.8            # steel ring height (the coil width); each barrel uses the nearest whole number
TILE_PX = 6                     # heat-shield tile pitch along a row, px (0.375 m)
TILE_ROWS = 5                   # pixel rows per tile row (0.31 m)
TILE_HALF_ARC_DEG = 100.0       # windward heat-shield half-arc on barrel and nose
NOSE_FULL_TILE_M = 1.5          # the top of the nose is fully tiled
NOSE_WIDEN_FROM_M = 4.9         # ...and the tiled arc starts widening this far below the tip
NOSE_CAP_M = 0.30               # the very tip is one cap piece
FORE_FLAP_TILE_DEG = 121.0      # tiles reach around the fore-flap hinge line (hinges at +-115 deg)
AFT_STEEL_M = 0.5               # bottom of the skirt left bare
SHIP_TANKS = {"LOX": (4.0, 17.5), "CH4": (17.5, 27.5)}        # m above the skirt bottom
BOOSTER_TANKS = {"LOX": (5.0, 45.0), "CH4": (46.0, 66.0)}
BOOSTER_FORWARD_Y = 66.0        # forward section (above the CH4 tank), different panelling
WINDOW_COUNT = 9
WINDOW_PITCH_DEG = 360.0 / 29.0  # one window per cabin wall panel column (29 columns around)
WINDOW_MAIN = (39.10, 0.60, 0.90, 0.12)   # centre height, width, height, corner radius (m)
WINDOW_UPPER = (39.95, 0.40, 0.30, 0.08)
NAV_LIGHTS = [  # (angle deg, height m, colour) on the ship barrel: port red on +X, starboard green on -X, white strobes
    (118.0, 32.2, "red"), (242.0, 32.2, "green"), (180.0, 33.6, "white"), (180.0, 1.4, "white")]

# ===================================================================================================================
# Layout and geometry, read from the Java sources (built-in copies are the fallback)
# ===================================================================================================================

DEFAULT_TEXTURES = {"SHIP": (1024, 1024), "BOOSTER": (1024, 2048)}
DEFAULT_REGIONS = {
    "SHIP_NOSE": ("SHIP", 0, 0, 452, 312),
    "SHIP_BARREL": ("SHIP", 0, 312, 452, 864),
    "SHIP_AFT_FLAP_WINDWARD": ("SHIP", 456, 0, 528, 192),
    "SHIP_AFT_FLAP_LEEWARD": ("SHIP", 532, 0, 604, 192),
    "SHIP_AFT_FLAP_EDGE": ("SHIP", 608, 0, 616, 192),
    "SHIP_FORE_FLAP_WINDWARD": ("SHIP", 620, 0, 668, 128),
    "SHIP_FORE_FLAP_LEEWARD": ("SHIP", 672, 0, 720, 128),
    "SHIP_FORE_FLAP_EDGE": ("SHIP", 724, 0, 732, 128),
    "SHIP_AFT_DISC": ("SHIP", 456, 200, 600, 344),
    "SHIP_ENGINE_SL_OUTER": ("SHIP", 608, 200, 672, 232),
    "SHIP_ENGINE_SL_INNER": ("SHIP", 608, 236, 672, 268),
    "SHIP_ENGINE_VAC_OUTER": ("SHIP", 680, 200, 808, 264),
    "SHIP_ENGINE_VAC_INNER": ("SHIP", 680, 268, 808, 332),
    "SHIP_LEG": ("SHIP", 816, 300, 912, 332),
    "SHIP_LEG_FOOT": ("SHIP", 852, 200, 884, 232),
    "SHIP_CABIN_WALL": ("SHIP", 456, 400, 880, 476),
    "SHIP_CABIN_FLOOR": ("SHIP", 456, 480, 600, 624),
    "SHIP_CABIN_CEILING": ("SHIP", 608, 480, 752, 624),
    "SHIP_CABIN_SEAT": ("SHIP", 760, 480, 792, 512),
    "BOOSTER_BARREL": ("BOOSTER", 0, 0, 452, 1136),
    "BOOSTER_HOT_STAGE_RING": ("BOOSTER", 0, 1140, 452, 1172),
    "BOOSTER_HOT_STAGE_RING_TOP": ("BOOSTER", 456, 300, 600, 444),
    "BOOSTER_AFT_DISC": ("BOOSTER", 456, 0, 600, 144),
    "BOOSTER_GRID_FIN": ("BOOSTER", 456, 148, 536, 196),
    "BOOSTER_GRID_FIN_EDGE": ("BOOSTER", 540, 148, 548, 196),
    "BOOSTER_ENGINE_OUTER": ("BOOSTER", 608, 0, 672, 32),
    "BOOSTER_ENGINE_INNER": ("BOOSTER", 608, 36, 672, 68),
}

DEFAULT_GEOMETRY = dict(
    HULL_RADIUS=4.5, SHIP_BARREL_HEIGHT=34.4, SHIP_NOSE_HEIGHT=17.7,
    CABIN_FLOOR_Y=37.6, CABIN_CEILING_Y=42.4, CABIN_WALL_INSET=0.15, SEAT_COUNT=8.0, SEAT_RING_RADIUS=2.45,
    SEAT_HEIGHT=0.5,
    BOOSTER_BARREL_HEIGHT=70.1, BOOSTER_RING_HEIGHT=1.8,
    LEG_PIVOT_Y=6.8, LEG_LENGTH=6.5, LEG_EXTENSION=2.4, LEG_DEPLOY_DEG=15.0,
    LEG_ANGLES_DEG=[0.0, 50.0, 130.0, 180.0, 230.0, 310.0],
    SL_RING_RADIUS=1.35, SL_ANGLES_DEG=[0.0, 120.0, 240.0], SL_EXIT_RADIUS=0.65, SL_THROAT_Y=1.25, SL_EXIT_Y=-0.45,
    SL_GIMBAL_Y=2.6,
    VAC_RING_RADIUS=3.2, VAC_ANGLES_DEG=[60.0, 180.0, 300.0], VAC_EXIT_RADIUS=1.15, VAC_THROAT_Y=2.55,
    VAC_EXIT_Y=-0.35,
    BOOSTER_RINGS=[[0.85, 3.0, 0.65], [2.45, 10.0, 0.65], [3.88, 20.0, 0.60]],
    BOOSTER_ENGINE_THROAT_Y=0.9, BOOSTER_ENGINE_EXIT_Y=-1.0,
    GRID_FIN_ANGLES_DEG=[0.0, 120.0, 240.0], GRID_FIN_Y=63.4, GRID_FIN_HEIGHT=4.4, GRID_FIN_SPAN=6.0,
)

# Regions painted here; anything else found in UvLayout.java is reported as unpainted.
PAINTED = set(DEFAULT_REGIONS)


class Region:
    def __init__(self, name, tex, x0, y0, x1, y1):
        self.name, self.tex, self.x0, self.y0, self.x1, self.y1 = name, tex, x0, y0, x1, y1
        self.w, self.h = x1 - x0, y1 - y0

    def st(self):
        """(s, t) at pixel centres, each h x w."""
        t, s = np.mgrid[0:self.h, 0:self.w].astype(float)
        return (s + 0.5) / self.w, (t + 0.5) / self.h

    def u(self, s):
        return self.x0 + self.w * s

    def v(self, t):
        return self.y0 + self.h * t


class Layout:
    def __init__(self):
        self.textures = dict(DEFAULT_TEXTURES)
        raw = dict(DEFAULT_REGIONS)
        path = GEOM_DIR / "UvLayout.java"
        self.source = "built-in"
        if path.exists():
            src = path.read_text(encoding="utf-8")
            tex = {m.group(1): (int(m.group(2)), int(m.group(3)))
                   for m in re.finditer(r"^\s*(SHIP|BOOSTER)\((\d+),\s*(\d+)\)", src, re.M)}
            reg = {m.group(1): (m.group(2), *(int(v) for v in m.group(3, 4, 5, 6)))
                   for m in re.finditer(r"^\s*([A-Z0-9_]+)\(Texture\.(SHIP|BOOSTER),\s*(\d+),\s*(\d+),\s*(\d+),\s*(\d+)\)",
                                        src, re.M)}
            if reg:
                self.source = str(path.relative_to(ROOT))
                for k, v in tex.items():
                    if DEFAULT_TEXTURES.get(k) != v:
                        print(f"  layout: texture {k} is {v} in Java (built-in {DEFAULT_TEXTURES.get(k)})")
                    self.textures[k] = v
                for k, v in reg.items():
                    if DEFAULT_REGIONS.get(k) != v:
                        print(f"  layout: region {k} = {v} in Java (built-in {DEFAULT_REGIONS.get(k)})")
                for k in sorted(set(reg) - PAINTED):
                    print(f"  layout: !! region {k} exists in Java but is not painted by this script")
                missing = PAINTED - set(reg)
                if missing:
                    raise SystemExit(f"UvLayout.java lacks regions this script paints: {sorted(missing)}")
                raw = reg
        self.r = {k: Region(k, *v) for k, v in raw.items()}

    def __getitem__(self, name) -> Region:
        return self.r[name]

    def of(self, tex):
        return [r for r in self.r.values() if r.tex == tex]


class Geo:
    """StarshipGeometry constants plus the derived profiles the textures need."""

    def __init__(self):
        c = {k: (list(v) if isinstance(v, list) else v) for k, v in DEFAULT_GEOMETRY.items()}
        path = GEOM_DIR / "StarshipGeometry.java"
        if path.exists():
            src = path.read_text(encoding="utf-8")
            for m in re.finditer(r"public static final (?:double|int) ([A-Z0-9_]+) = (-?[\d.]+);", src):
                if m.group(1) in c:
                    c[m.group(1)] = float(m.group(2))
            for m in re.finditer(r"public static final double\[\] ([A-Z0-9_]+) = \{([^}]*)\};", src):
                if m.group(1) in c:
                    c[m.group(1)] = [float(v) for v in m.group(2).split(",")]
            m = re.search(r"double\[\]\[\] BOOSTER_RINGS = \{(.*?)\};", src, re.S)
            if m:
                c["BOOSTER_RINGS"] = [[float(v) for v in g.split(",")] for g in re.findall(r"\{([^{}]*)\}", m.group(1))]
        for k, v in c.items():
            if v != DEFAULT_GEOMETRY[k]:
                print(f"  geometry: {k} = {v} in Java (built-in {DEFAULT_GEOMETRY[k]})")
        self.c = c
        self.R = c["HULL_RADIUS"]
        self.ship_barrel = c["SHIP_BARREL_HEIGHT"]
        self.nose_h = c["SHIP_NOSE_HEIGHT"]
        self.ship_height = self.ship_barrel + self.nose_h
        self.booster_barrel = c["BOOSTER_BARREL_HEIGHT"]
        self.ring_h = c["BOOSTER_RING_HEIGHT"]
        self.booster_height = self.booster_barrel + self.ring_h
        self.rho = (self.R ** 2 + self.nose_h ** 2) / (2.0 * self.R)
        # Nose profile exactly as buildShip samples it, but with 200k rings; t is arc length from the tip.
        f = np.linspace(0.0, 1.0, 200001)
        x = self.nose_h * (1.0 - (1.0 - f) ** 1.6)
        r = np.sqrt(np.maximum(0.0, self.rho ** 2 - x ** 2)) + self.R - self.rho
        r = np.maximum(r, 0.35 * (1.0 - f))
        r[-1] = 0.0
        arc = np.concatenate([[0.0], np.cumsum(np.hypot(np.diff(r), np.diff(x)))])
        self._nx, self._nr, self._na = x, r, arc
        self.nose_len = float(arc[-1])
        self.fore_y0 = self.ship_barrel + 2.0      # fore-flap hinge, as in buildShip
        self.fore_y1 = self.ship_barrel + 9.2
        self.cabin_floor = c["CABIN_FLOOR_Y"]
        self.cabin_ceiling = c["CABIN_CEILING_Y"]
        self.cabin_inset = c["CABIN_WALL_INSET"]
        self.floor_r = float(self.hull_radius(self.cabin_floor)) - self.cabin_inset
        self.ceiling_r = float(self.hull_radius(self.cabin_ceiling)) - self.cabin_inset

    def nose_at_t(self, t):
        """Nose texture t (0 tip .. 1 base) -> (height above the nose base, radius, arc length from the base)."""
        a = (1.0 - np.asarray(t, float)) * self.nose_len
        return np.interp(a, self._na, self._nx), np.interp(a, self._na, self._nr), a

    def nose_t_at_height(self, y):
        return 1.0 - np.interp(np.asarray(y, float) - self.ship_barrel, self._nx, self._na) / self.nose_len

    def hull_radius(self, y):
        """StarshipGeometry.hullRadiusAt: barrel, then the tangent ogive (0 above the tip)."""
        y = np.asarray(y, float)
        x = y - self.ship_barrel
        r = np.sqrt(np.maximum(0.0, self.rho ** 2 - x ** 2)) + self.R - self.rho
        return np.where(y <= self.ship_barrel, self.R, np.where(x >= self.nose_h, 0.0, r))

    def seat_angles(self):
        n = int(self.c["SEAT_COUNT"])
        return [TAU * (k + 0.5) / n for k in range(n)]


# ===================================================================================================================
# Numerics: deterministic hashing, value noise on 3D positions, colour
# ===================================================================================================================

def seed_of(*parts) -> int:
    return zlib.crc32("|".join(str(p) for p in parts).encode()) & 0xFFFFFFFF


def _mix(h):
    h = h ^ (h >> np.uint32(16))
    h = h * np.uint32(0x7FEB352D)
    h = h ^ (h >> np.uint32(15))
    h = h * np.uint32(0x846CA68B)
    return h ^ (h >> np.uint32(16))


def hash01(seed, *coords):
    """Deterministic uniform [0, 1) per integer coordinate tuple."""
    cs = np.broadcast_arrays(*[np.asarray(c, np.int64) for c in coords])
    h = _mix(np.full(cs[0].shape, seed & 0xFFFFFFFF, np.uint32))
    for c in cs:
        h = _mix(h ^ (c & 0xFFFFFFFF).astype(np.uint32))
    return h.astype(np.float64) / 4294967296.0


def vnoise3(x, y, z, seed):
    """Smoothstep value noise on the integer lattice, in [0, 1]."""
    x, y, z = np.broadcast_arrays(np.asarray(x, float), np.asarray(y, float), np.asarray(z, float))
    xf, yf, zf = np.floor(x), np.floor(y), np.floor(z)
    tx, ty, tz = x - xf, y - yf, z - zf
    sx, sy, sz = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty), tz * tz * (3 - 2 * tz)
    ix, iy, iz = xf.astype(np.int64), yf.astype(np.int64), zf.astype(np.int64)

    def h(a, b, c):
        return hash01(seed, ix + a, iy + b, iz + c)

    x00 = h(0, 0, 0) + (h(1, 0, 0) - h(0, 0, 0)) * sx
    x10 = h(0, 1, 0) + (h(1, 1, 0) - h(0, 1, 0)) * sx
    x01 = h(0, 0, 1) + (h(1, 0, 1) - h(0, 0, 1)) * sx
    x11 = h(0, 1, 1) + (h(1, 1, 1) - h(0, 1, 1)) * sx
    y0 = x00 + (x10 - x00) * sy
    y1 = x01 + (x11 - x01) * sy
    return y0 + (y1 - y0) * sz


_FBM_STD = {1: 0.163, 2: 0.120, 3: 0.106, 4: 0.100}   # measured; fbm3 returns roughly unit variance


def fbm3(x, y, z, seed, octaves=3, lac=2.0, gain=0.5):
    total, amp, norm = 0.0, 1.0, 0.0
    for o in range(octaves):
        total = total + amp * (vnoise3(x, y, z, seed + 7919 * o) - 0.5)
        norm += amp
        amp *= gain
        x, y, z = x * lac + 31.7, y * lac + 17.3, z * lac + 11.1
    return total / norm / _FBM_STD.get(octaves, 0.1)


def noise2(shape, seed, sx, sy, octaves=2, z=0.37):
    """Planar fBm in pixel units (feature sizes sx, sy px), unit variance."""
    yy, xx = np.mgrid[0:shape[0], 0:shape[1]].astype(float)
    return fbm3((xx + 0.5) / sx, (yy + 0.5) / sy, np.full(shape, z), seed, octaves)


def noise_ring(shape, seed, cells_s, sy, octaves=2):
    """fBm that wraps around in x (s): x is mapped onto a circle with `cells_s` features around; sy px along y."""
    h, w = shape
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    a = (xx + 0.5) / w * TAU
    rad = cells_s / TAU
    return fbm3(rad * np.sin(a), (yy + 0.5) / sy, rad * np.cos(a), seed, octaves)


def smoothstep(e0, e1, x):
    t = np.clip((np.asarray(x, float) - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def wrap_pi(a):
    return (np.asarray(a, float) + math.pi) % TAU - math.pi


def quant(x, step):
    return step * np.round(np.asarray(x, float) / step)


def lab(c) -> np.ndarray:
    return rgb_to_oklab(np.asarray(c, float))


def to_rgb8(labimg) -> np.ndarray:
    return np.clip(np.round(oklab_to_rgb(labimg)), 0, 255).astype(np.uint8)


def ramp_lab(anchors, x):
    """Piecewise-linear OKLab ramp through [(position, rgb), ...]; x of any shape -> (..., 3)."""
    pos = np.array([p for p, _ in anchors], float)
    cols = np.array([lab(c) for _, c in anchors])
    x = np.clip(np.asarray(x, float), pos[0], pos[-1])
    return np.stack([np.interp(x, pos, cols[:, k]) for k in range(3)], -1)


def mix_lab(a, b, w):
    w = np.asarray(w, float)[..., None]
    return a * (1 - w) + np.asarray(b) * w


def shift(m, dy, dx, wrap_x=False, fill=False):
    """Shift a 2D array by (dy, dx); x optionally wraps (lathe regions)."""
    out = np.roll(m, dx, axis=1) if wrap_x else np.full_like(m, fill)
    if not wrap_x:
        if dx > 0:
            out[:, dx:] = m[:, :-dx]
        elif dx < 0:
            out[:, :dx] = m[:, -dx:]
        else:
            out[:] = m
    res = np.full_like(m, fill)
    if dy > 0:
        res[dy:] = out[:-dy]
    elif dy < 0:
        res[:dy] = out[-dy:]
    else:
        res[:] = out
    return res


def dilate8(m, wrap_x=False):
    out = m.copy()
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dy or dx:
                out |= shift(m, dy, dx, wrap_x)
    return out


def dilate4(m, wrap_x=False):
    out = m.copy()
    for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        out |= shift(m, dy, dx, wrap_x)
    return out


def rect_mask(shape, x0, y0, x1, y1):
    m = np.zeros(shape, bool)
    m[max(0, y0):max(0, y1), max(0, x0):max(0, x1)] = True
    return m


def outline_of(m, wrap_x=False):
    """Pixels of m with a 4-neighbour outside m."""
    inner = m.copy()
    for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        inner &= shift(m, dy, dx, wrap_x, fill=False)
    return m & ~inner


def fill_transparent_rgb(rgb, alpha):
    """Give alpha-0 pixels the colour of the nearest opaque pixel (no dark fringes under filtering)."""
    if alpha.min() == 255 or alpha.max() == 0:
        return rgb
    idx = ndimage.distance_transform_edt(alpha == 0, return_distances=False, return_indices=True)
    return rgb[idx[0], idx[1]]


# ===================================================================================================================
# Palette (sRGB anchors; shading happens in OKLab)
# ===================================================================================================================

STEEL = (184, 187, 191)
STEEL_TINTS = np.array([  # weight, dL, da, db: sheets from different coils differ slightly in warmth and brightness
    (0.30, 0.000, 0.0000, 0.0000),
    (0.20, 0.005, 0.0008, 0.0040),     # warm
    (0.20, -0.003, -0.0010, -0.0048),  # cool
    (0.11, 0.014, 0.0000, -0.0012),    # bright
    (0.12, -0.013, 0.0004, 0.0008),    # dull
    (0.07, 0.003, 0.0020, 0.0072),     # straw (faint heat tint)
])
TILE = {-1: (33, 34, 37), 0: (40, 41, 44), 1: (48, 49, 53), 2: (58, 59, 63)}
GROUT = (19, 19, 21)
TILE_CAP = (36, 37, 41)
SOOT = (46, 42, 40)
GLASS = (14, 19, 32)
GLASS_TOP = (24, 33, 52)
GLASS_GLINT = (44, 60, 88)
WINDOW_FRAME = (205, 207, 211)


# ===================================================================================================================
# Lathe canvases: per-pixel angle, height, meridian distance, radius and global tile row
# ===================================================================================================================

class HullFrame:
    def __init__(self, w, y, d, r, G, arc_from_tip=None):
        self.W, self.H = w, len(y)
        self.theta = (np.arange(w) + 0.5) / w * TAU
        self.y, self.d, self.r = np.asarray(y, float), np.asarray(d, float), np.asarray(r, float)
        self.G = np.asarray(G, np.int64)
        self.arc_from_tip = arc_from_tip
        self._xyz = None

    def xyz(self):
        if self._xyz is None:
            X = self.r[:, None] * np.sin(self.theta)[None, :]
            Z = self.r[:, None] * np.cos(self.theta)[None, :]
            Y = np.broadcast_to(self.d[:, None], X.shape)
            self._xyz = (X, Y, Z)
        return self._xyz

    def noise(self, name, sx, sy, octaves=3):
        """fBm on the hull surface, feature sizes sx around and sy along the hull (metres); unit variance."""
        X, Y, Z = self.xyz()
        return fbm3(X / sx, Y / sy, Z / sx, seed_of(name), octaves)

    def cols_of_angle(self, ang):
        return (np.floor(np.asarray(ang, float) % TAU / TAU * self.W).astype(int)) % self.W

    def row_of_height(self, yv):
        return int(np.argmin(np.abs(self.y - yv)))


def ship_hull_frame(geo, L):
    nose, barrel = L["SHIP_NOSE"], L["SHIP_BARREL"]
    if not (nose.w == barrel.w and nose.x0 == barrel.x0 and nose.y1 == barrel.y0):
        raise SystemExit("this script paints SHIP_NOSE and SHIP_BARREL as one canvas; they must be stacked")
    tn = (np.arange(nose.h) + 0.5) / nose.h
    xn, rn, an = geo.nose_at_t(tn)
    tb = (np.arange(barrel.h) + 0.5) / barrel.h
    yb = geo.ship_barrel * (1 - tb)
    y = np.concatenate([geo.ship_barrel + xn, yb])
    d = np.concatenate([geo.ship_barrel + an, yb])
    r = np.concatenate([rn, np.full(barrel.h, geo.R)])
    G = np.arange(nose.h + barrel.h) - nose.h
    arc_tip = np.concatenate([tn * geo.nose_len, np.full(barrel.h, 1e9)])
    return HullFrame(nose.w, y, d, r, G, arc_tip)


def booster_hull_frame(geo, L):
    reg = L["BOOSTER_BARREL"]
    t = (np.arange(reg.h) + 0.5) / reg.h
    y = geo.booster_barrel * (1 - t)
    return HullFrame(reg.w, y, y, np.full(reg.h, geo.R), np.arange(reg.h))


def ring_edges_ship(geo):
    nb = max(1, round(geo.ship_barrel / RING_NOMINAL_M))
    nn = max(1, round(geo.nose_len / RING_NOMINAL_M))
    return np.concatenate([np.linspace(0, geo.ship_barrel, nb + 1),
                           np.linspace(geo.ship_barrel, geo.ship_barrel + geo.nose_len, nn + 1)[1:]])


def ring_edges_booster(geo):
    n = max(1, round(BOOSTER_FORWARD_Y / RING_NOMINAL_M))
    fwd = geo.booster_barrel - BOOSTER_FORWARD_Y
    nf = max(1, round(fwd / 2.0))
    return np.concatenate([np.linspace(0, BOOSTER_FORWARD_Y, n + 1),
                           np.linspace(BOOSTER_FORWARD_Y, geo.booster_barrel, nf + 1)[1:]])


# ===================================================================================================================
# Stainless steel
# ===================================================================================================================

def paint_steel(fr, edges, name, sheets_fn, aligned_fn=None, base=STEEL, step=0.016, min_sep_deg=35.0):
    """Ring-and-sheet stainless steel on a lathe canvas. Rings run along the meridian distance between `edges`;
    each ring is rolled from sheets_fn(ring, r_mean, rng) sheets whose vertical seams are staggered against the ring
    below (or on a regular grid where aligned_fn(ring) is true). Sheets get slightly different tints; inside a sheet
    a soft bulge (lighter upper half), long vertical streaks and small clusters are quantised into steps, so the
    shading reads as clustered pixel art instead of noise. Ring welds are a dark warm line with a highlight below,
    sheet seams a lighter dark line."""
    rng = np.random.default_rng(seed_of(name, "sheets"))
    H, W = fr.H, fr.W
    nring = len(edges) - 1
    ring = np.clip(np.searchsorted(edges, fr.d, side="right") - 1, 0, nring - 1)
    out = np.empty((H, W, 3))
    out[:] = lab(base)
    weights = STEEL_TINTS[:, 0] / STEEL_TINTS[:, 0].sum()
    seams, prev = [], np.array([])
    for k in range(nring):
        rows = ring == k
        if not rows.any():
            seams.append(np.array([]))
            continue
        n = int(sheets_fn(k, float(fr.r[rows].mean()), rng))
        if n <= 0:
            ang = np.array([])
        elif aligned_fn is not None and aligned_fn(k):
            ang = np.sort((math.radians(7.5) + TAU * np.arange(n) / n) % TAU)
        else:
            best, best_sep = None, -1.0
            for _ in range(60):
                cand = np.sort((rng.uniform(0, TAU) + TAU * np.arange(n) / n + rng.uniform(-0.2, 0.2, n)) % TAU)
                sep = 9.0 if len(prev) == 0 else float(np.min(np.abs(wrap_pi(cand[:, None] - prev[None, :]))))
                if sep > best_sep:
                    best, best_sep = cand, sep
                if sep >= math.radians(min_sep_deg):
                    break
            ang = best
        seams.append(ang)
        prev = ang if len(ang) else prev
        sheet = (np.searchsorted(ang, fr.theta, side="right") % max(1, len(ang))) if len(ang) else np.zeros(W, int)
        pick = rng.choice(len(STEEL_TINTS), size=max(1, len(ang)), p=weights)
        tint = STEEL_TINTS[pick][:, 1:][sheet]          # (W, 3)
        out[rows] += tint[None, :, :]
    lo, hi = edges[ring], edges[ring + 1]
    v = (hi - fr.d) / np.maximum(hi - lo, 1e-6)        # 0 at the top of each ring, 1 at the bottom
    bulge = 0.0085 * np.cos(np.pi * v)
    off = (bulge[:, None]
           + 0.0080 * fr.noise(name + ":streak", 0.45, 6.0, 3)
           + 0.0060 * fr.noise(name + ":cloud", 2.6, 7.5, 2)
           + 0.0085 * fr.noise(name + ":grain", 0.20, 0.24, 2))
    out[..., 0] += quant(off, step)                    # mostly the sheet's own tone, clusters one step either side
    weld = np.zeros(H, bool)
    weld[1:] = ring[1:] != ring[:-1]                   # first row of the lower ring at every ring boundary
    below = np.zeros(H, bool)
    below[1:] = weld[:-1]
    below &= ~weld
    out[weld, :, 0] -= 0.105
    out[weld, :, 2] += 0.007
    out[below, :, 0] += 0.022
    seam = np.zeros((H, W), bool)
    for k, ang in enumerate(seams):
        if not len(ang):
            continue
        rows = np.nonzero((ring == k) & ~weld)[0]
        seam[np.ix_(rows, fr.cols_of_angle(ang))] = True
    out[seam, 0] -= 0.055
    return out, dict(ring=ring, weld=weld, seam=seam, seams=seams, edges=edges)


def flat_steel(shape, name, base=STEEL, step=0.016, scale=1.0):
    """Unwrapped steel for plates (no rings): soft clusters and streaks in pixel space."""
    out = np.empty(shape + (3,))
    out[:] = lab(base)
    off = (0.0078 * noise2(shape, seed_of(name, "streak"), 6 * scale, 60 * scale, 3)
           + 0.0085 * noise2(shape, seed_of(name, "grain"), 3.2 * scale, 3.6 * scale, 2)
           + 0.0055 * noise2(shape, seed_of(name, "cloud"), 30 * scale, 40 * scale, 2))
    out[..., 0] += quant(off, step)
    return out


# ===================================================================================================================
# Heat-shield tiles
# ===================================================================================================================

def hex_classify(xi, rib, par):
    """Pixel-art honeycomb of pointy-top hexagons on a 6 px pitch with rows of 5 px, alternate rows offset 3 px.
    A tile is 5 x 4 px with a 1 px nub above and below, grout lines are 1 px:

        ...G.....G..      rib 0 of an odd row continues the vertical grout of the row above
        .GG.GG.GG.GG      rib 0 of an even row: the zigzag between rows
        G.....G.....      ribs 1-4: tile bodies, vertical grout every 6 px

    xi: integer pattern column, rib: row within the tile row (0 = zigzag row), par: tile-row parity.
    Returns (grout, k, nub_up): k = tile index in this row; nub_up marks the zigzag-row pixels that belong to the
    tile of the row above (its bottom nub)."""
    u = np.mod(xi - 3 * par, TILE_PX)
    body = rib > 0
    grout = np.where(body, u == 0, (u != 0) & (u != 3))
    k = np.floor_divide(xi - 3 * par, TILE_PX)
    return grout, k, (~body) & (u == 0)


def tile_layer(fr, coverage, ref_r, cap_rows=None):
    """Tiles on a lathe canvas. Tile rows follow the canvas rows (5 px each, counted from G = 0 so the nose and the
    barrel share one honeycomb); along a row, tiles keep their physical size, so on the nose (smaller radius) a tile
    spans more columns. Rows anchored at the windward line; rows that close all the way round use a whole number
    of tiles so the honeycomb wraps. coverage(band, y, lam) -> (phi_plus, phi_minus) half-arcs in radians (or None).
    Returns dict(tile, grout, band, k) arrays."""
    H, W = fr.H, fr.W
    band = np.floor_divide(fr.G, TILE_ROWS)
    rib = fr.G - band * TILE_ROWS
    par = band & 1
    ub = np.unique(band)
    lam_of, phi_of, nfull_of = {}, {}, {}
    for b in ub:
        rows = band == b
        lam = float(fr.r[rows].mean()) / ref_r
        cov = coverage(int(b), float(fr.y[rows].mean()), lam)
        n = 0
        if cov is None:
            phi = (-1.0, -1.0)
        elif min(cov) >= math.radians(170.0):
            n = max(3, int(round(W * lam / TILE_PX)))
            lam = TILE_PX * n / W
            phi = (10.0, 10.0)
        else:
            phi = cov
        lam_of[b], phi_of[b], nfull_of[b] = lam, phi, n
    bmin = int(ub.min()) - 1
    span = int(ub.max()) - bmin + 1
    php, phn, nfu = np.full(span, -1.0), np.full(span, -1.0), np.zeros(span, np.int64)
    lam_a = np.ones(span)
    for b in ub:
        php[b - bmin], phn[b - bmin] = phi_of[b]
        nfu[b - bmin] = nfull_of[b]
        lam_a[b - bmin] = lam_of[b]
    lam_a[0] = lam_a[1]                               # the row above the canvas: same scale, never tiled

    def included(tb_, tk_):
        # Is tile (band, index) inside the heat shield? Judged at the tile's centre angle.
        i = tb_ - bmin
        thc = wrap_pi((TILE_PX * tk_ + 3 * (tb_ & 1) + 0.5) / lam_a[i] * (TAU / W))
        return np.where(thc >= 0, thc <= php[i], -thc <= phn[i])

    lam_r = lam_a[band - bmin]
    lam_u = lam_a[band - 1 - bmin]
    cols = np.arange(W)
    thp = cols + 0.5 - W * (cols >= W / 2)            # signed angle in barrel pixels, 0 on the windward line
    xi = np.floor(thp[None, :] * lam_r[:, None]).astype(np.int64) + 3
    grout, k, nub_up = hex_classify(xi, rib[:, None], par[:, None])
    xi_u = np.floor(thp[None, :] * lam_u[:, None]).astype(np.int64) + 3
    k_u = np.floor_divide(xi_u - 3 * ((band - 1) & 1)[:, None], TILE_PX)   # tile of the row above at this column
    B = np.broadcast_to(band[:, None], xi.shape)
    tb = np.where(nub_up, B - 1, B)
    tk = np.where(nub_up, k_u, k)
    inc_own = included(tb, tk)
    # Grout is drawn where any tile it borders (in the honeycomb, not in texture space, where a grout line near the
    # tip spans many columns) is included: body grout between tiles k-1 and k of its row, zigzag grout between this
    # row's tile and the one above.
    body = np.broadcast_to((rib > 0)[:, None], xi.shape)
    gr = grout & np.where(body, included(B, k - 1) | inc_own, inc_own | included(B - 1, k_u))
    tile = ~grout & inc_own
    nn = nfu[tb - bmin]
    tk = np.where(nn > 0, np.mod(tk, np.maximum(nn, 1)), tk)
    if cap_rows is not None and cap_rows.any():
        tile[cap_rows] = True
        gr[cap_rows] = False
        tb = np.array(tb)
        tb[cap_rows] = -999999
        tk[cap_rows] = 0
    if cap_rows is not None and cap_rows.any():
        last = np.nonzero(cap_rows)[0].max()
        if last + 1 < H:
            gr[last + 1] |= tile[last + 1] | gr[last + 1]
            tile[last + 1] = False
    return dict(tile=tile, grout=gr, band=tb, k=tk)


def tile_colours(band, k, name):
    """Per-tile shade class: most tiles alike, a few a shade darker or lighter (replaced or re-coated tiles),
    and faint patches of slightly different batches."""
    h = hash01(seed_of(name, "tile"), band, k)
    cls = np.zeros(band.shape, np.int64)
    cls[h < 0.06] = -1
    cls[h > 0.90] = 1
    cls[h > 0.977] = 2
    patch = hash01(seed_of(name, "patch"), np.floor_divide(band, 5), np.floor_divide(k, 4))
    cls = np.clip(cls + (patch > 0.88) - (patch < 0.06), -1, 2)
    out = np.empty(band.shape + (3,))
    for c, rgb in TILE.items():
        out[cls == c] = lab(rgb)
    out[band == -999999] = lab(TILE_CAP)
    return out


def ship_coverage(geo, W):
    """Half-arc of the heat shield per tile row: +-100 deg with a stepped edge (whole tiles; the edge wanders by a
    tile now and then, independently on each side), widening over the top of the nose until the last 1.5 m are
    fully tiled, reaching round the fore-flap hinges, and leaving the bottom 0.5 m of the skirt bare."""
    walks = {}
    for side in (1, -1):
        b = np.arange(-120, 140)
        n = vnoise3(b / 11.0, side * 3.1, 0.5, seed_of("ship_tile_edge", side))
        w = np.where(n > 0.80, 1, np.where(n < 0.20, -1, 0))
        walks[side] = dict(zip(b.tolist(), w.tolist()))
    top = geo.ship_height

    def cov(b, yc, lam):
        if yc < AFT_STEEL_M:
            return None
        widen = smoothstep(top - NOSE_WIDEN_FROM_M, top - NOSE_FULL_TILE_M, yc)
        base = TILE_HALF_ARC_DEG + (180.0 - TILE_HALF_ARC_DEG) * float(widen)
        if yc >= top - NOSE_FULL_TILE_M:
            base = 180.0
        pitch = TILE_PX / max(lam, 1e-3) / W * 360.0         # one tile, in degrees, on this row
        out = []
        for side in (1, -1):
            ph = base + walks[side].get(b, 0) * pitch * (1.0 - float(widen))
            if geo.fore_y0 - 0.25 <= yc <= geo.fore_y1 + 0.25:
                ph = max(ph, FORE_FLAP_TILE_DEG)
            out.append(math.radians(ph))
        return tuple(out)
    return cov


def paint_hex_plate(shape, name, offset=3):
    """A plate face fully covered with tiles (flap windward faces)."""
    h, w = shape
    ys, xs = np.mgrid[0:h, 0:w]
    band = ys // TILE_ROWS
    rib = ys - band * TILE_ROWS
    par = band & 1
    xi = xs + offset
    grout, k, nub_up = hex_classify(xi, rib, par)
    tb = np.where(nub_up, band - 1, band)
    tk = np.where(nub_up, np.floor_divide(xi - 3 * ((band - 1) & 1), TILE_PX), k)
    out = tile_colours(tb, tk, name)
    out[grout] = lab(GROUT)
    return out


# ===================================================================================================================
# Windows (one physical definition for the nose and the cabin wall)
# ===================================================================================================================

def window_list():
    out = []
    for i in range(WINDOW_COUNT):
        th = math.radians(180.0 + (i - (WINDOW_COUNT - 1) / 2.0) * WINDOW_PITCH_DEG)
        out.append((th,) + WINDOW_MAIN)
        out.append((th,) + WINDOW_UPPER)
    return out


def window_masks(theta, y, geo, wins):
    """Glass of every window on a lathe canvas with columns at `theta` and rows at heights `y`: a rounded rectangle
    in (arc length at the window's centre height, height). Returns (glass, local u, local v, window index)."""
    H, W = len(y), len(theta)
    glass = np.zeros((H, W), bool)
    lu, lv = np.zeros((H, W)), np.zeros((H, W))
    wid = np.full((H, W), -1)
    for i, (th, yc, w, h, rc) in enumerate(wins):
        rr = float(geo.hull_radius(yc))
        du = np.broadcast_to(wrap_pi(theta - th)[None, :] * rr, (H, W))
        dv = np.broadcast_to((y - yc)[:, None], (H, W))
        qx, qy = np.abs(du) - (w / 2 - rc), np.abs(dv) - (h / 2 - rc)
        sdf = np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0) - rc
        m = sdf <= 0
        glass |= m
        lu[m], lv[m], wid[m] = (du / (w / 2))[m], (dv / (h / 2))[m], i
    return glass, lu, lv, wid


def glass_shading(lu, lv):
    """Dark blue-black glass: the sky reflected in the top third and a diagonal glint."""
    out = ramp_lab([(-1.0, GLASS), (0.30, GLASS), (1.0, GLASS_TOP)], lv)
    glint = np.abs(lu * 0.55 + lv * 0.85 - 0.25) < 0.16
    out[glint] = lab(GLASS_GLINT)
    return out


# ===================================================================================================================
# Ship
# ===================================================================================================================

def ship_sheets(geo):
    def fn(k, r_mean, rng):
        if r_mean < 0.6:
            return 0                                   # the tip: one spun piece
        if r_mean < 2.2:
            return 2
        return int(rng.choice([2, 3], p=[0.45, 0.55]))
    return fn


def paint_ship_hull(geo, L):
    fr = ship_hull_frame(geo, L)
    lab_img, st = paint_steel(fr, ring_edges_ship(geo), "ship_hull", ship_sheets(geo))
    # Aft skirt: a little soot from the hot-staging blast and engine plumes.
    soot = (1 - smoothstep(0.0, 2.8, fr.y))[:, None] * (0.75 + 0.25 * fr.noise("ship_hull:soot", 0.35, 1.6, 2))
    lab_img = mix_lab(lab_img, lab(SOOT), quant(np.clip(soot, 0, 1) * 0.32, 1 / 24))
    # Heat shield.
    tl = tile_layer(fr, ship_coverage(geo, fr.W), geo.R, cap_rows=fr.arc_from_tip < NOSE_CAP_M)
    tcol = tile_colours(tl["band"], tl["k"], "ship_tiles")
    lab_img[tl["tile"]] = tcol[tl["tile"]]
    lab_img[tl["grout"]] = lab(GROUT)
    tiled = tl["tile"] | tl["grout"]
    # Crew windows.
    wins = window_list()
    glass, lu, lv, _ = window_masks(fr.theta, fr.y, geo, wins)
    frame = dilate8(glass, wrap_x=True) & ~glass
    lab_img[frame] = lab(WINDOW_FRAME)
    lab_img[frame & shift(glass, 1, 0, True)] = lab(adjust_rgb(WINDOW_FRAME, -0.07))   # sill (glass above)
    lab_img[frame & shift(glass, -1, 0, True)] = lab(adjust_rgb(WINDOW_FRAME, 0.03))   # head (glass below)
    gl = glass_shading(lu, lv)
    lab_img[glass] = gl[glass]
    # Navigation lights: a small dark housing with a coloured lens.
    lens = {}
    for ang, yv, colour in NAV_LIGHTS:
        c = int(fr.cols_of_angle(math.radians(ang)))
        rrow = fr.row_of_height(yv)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                lab_img[rrow + dy, (c + dx) % fr.W] = lab((62, 64, 68))
        lab_img[rrow, c] = lab({"red": (150, 46, 42), "green": (46, 132, 70), "white": (214, 216, 220)}[colour])
        lens[(rrow, c)] = colour
    return fr, to_rgb8(lab_img), dict(tiled=tiled, glass=glass, lu=lu, lv=lv, lens=lens, steel=st)


def adjust_rgb(rgb, dl=0.0, db=0.0, da=0.0):
    v = lab(rgb)
    v = v + np.array([dl, da, db])
    return tuple(int(c) for c in to_rgb8(v[None, None, :])[0, 0])


def paint_flap_leeward(reg, name, panels, root_px, ribs):
    shape = (reg.h, reg.w)
    out = flat_steel(shape, name)
    for yr in ribs:                                     # internal ribs show as faint lines on the skin
        out[yr, :, 0] -= 0.022
        out[yr + 1, :, 0] += 0.012
    root = rect_mask(shape, 0, 0, root_px, reg.h)      # actuator / hinge fairing along the root
    out[root, 0] -= 0.045
    out[:, root_px - 1, 0] -= 0.05
    out[:, root_px, 0] += 0.025
    for yb in range(3, reg.h - 2, 5):
        out[yb, root_px - 3, 0] -= 0.07
    for x0, y0, x1, y1 in panels:                       # access panels: dark outline, light inner bevel, fasteners
        m = rect_mask(shape, x0, y0, x1, y1)
        ol = outline_of(m)
        out[m, 0] += 0.008
        out[ol, 0] -= 0.075
        inner = rect_mask(shape, x0 + 1, y0 + 1, x1 - 1, y1 - 1)
        bevel = outline_of(inner) & (rect_mask(shape, x0 + 1, y0 + 1, x1 - 1, y0 + 2) | rect_mask(shape, x0 + 1, y0 + 1, x0 + 2, y1 - 1))
        out[bevel, 0] += 0.03
        for cx, cy in ((x0 + 2, y0 + 2), (x1 - 3, y0 + 2), (x0 + 2, y1 - 3), (x1 - 3, y1 - 3)):
            out[cy, cx, 0] -= 0.06
    return to_rgb8(out)


def paint_flap_edge(reg, name):
    shape = (reg.h, reg.w)
    out = paint_hex_plate(shape, name, offset=1)
    out[..., 0] -= 0.01
    out[:, 0, 0] += 0.035
    out[:, -1, 0] += 0.035
    return to_rgb8(out)


def engine_striations(w, h, period=2):
    xs = np.arange(w)
    return np.broadcast_to((xs % period == 1)[None, :], (h, w))


def paint_engine_bell_outer(reg, name, anchors, soot=0.0, rings=(), lip=True, stri_until=0.9, bands=8):
    """Regeneratively cooled nozzle exterior (the powerhead reuses this region upside down): temper-colour bands
    along t (quantised, with slightly wavy edges, from blue-grey steel at the throat to bronze at the exit), crisp
    2 px cooling-channel stripes (channel dark, land light), a flange near the throat and the exit manifold lip."""
    s, t = reg.st()
    wave = 0.045 * noise_ring((1, reg.w), seed_of(name, "wave"), 5.0, 1.0, 2)
    tq = (np.floor((t + wave) * bands) + 0.5) / bands
    out = ramp_lab(anchors, np.clip(tq, 0, 1))
    out[..., 0] += quant(0.007 * noise_ring((reg.h, reg.w), seed_of(name, "grain"), 12, 4, 2), 0.01)
    stri = engine_striations(reg.w, reg.h) & (t < stri_until)
    land = ~engine_striations(reg.w, reg.h) & (t < stri_until)
    out[stri, 0] -= 0.040
    out[land, 0] += 0.010
    if soot:
        sn = noise_ring((reg.h, reg.w), seed_of(name, "soot"), 10, reg.h * 0.45, 2)
        amt = np.clip(soot * (0.45 + 0.55 * t) + 0.14 * sn, 0, 0.85)
        out = mix_lab(out, lab(SOOT), quant(amt, 1 / 8))
    for tr, dl in rings:
        row = int(min(reg.h - 1, max(0, round(tr * reg.h - 0.5))))
        out[row, :, 0] += dl
        out[min(reg.h - 1, row + 1), :, 0] -= dl * 0.8
    if lip:
        out[-3:-1, :, 0] += 0.06
        out[-1, :, 0] -= 0.07
        out[0, :, 0] += 0.035
    return to_rgb8(out)


def paint_engine_bell_inner(reg, name, anchors, stri_until=0.5, soot=0.25, bands=10):
    """Nozzle interior: the copper chamber liner glowing warm at the throat, darkening to soot toward the exit, with
    the cooling channels showing in the upper part and streaks of soot below."""
    s, t = reg.st()
    tq = (np.floor(t * bands) + 0.5) / bands
    out = ramp_lab(anchors, tq)
    stri = engine_striations(reg.w, reg.h) & (t < stri_until)
    out[stri, 0] -= 0.035
    sn = noise_ring((reg.h, reg.w), seed_of(name, "soot"), 12, reg.h * 0.6, 2)
    out[..., 0] += quant(-soot * 0.06 * np.clip(sn, 0, 3) * t, 0.015)
    out[..., 0] += quant(0.008 * noise_ring((reg.h, reg.w), seed_of(name, "grain"), 20, 2.5, 1), 0.01)
    return to_rgb8(out)


def paint_vac_outer(reg, name):
    """Raptor Vacuum: a short regeneratively cooled section, then the large, smooth, radiatively cooled extension
    (darker, faint heat tint toward the exit, a few stiffener rings)."""
    s, t = reg.st()
    joint = 0.22
    out = ramp_lab([(0.0, (102, 110, 124)), (joint, (110, 100, 88)), (joint + 0.001, (70, 72, 79)),
                    (0.7, (64, 64, 70)), (1.0, (82, 70, 62))], t)
    tint = noise_ring((reg.h, reg.w), seed_of(name, "heat"), 2.5, reg.h * 0.5, 2)
    out[..., 2] += 0.006 * tint * (t > joint)
    out[..., 1] += 0.004 * tint * (t > joint)
    out[..., 0] += quant(0.007 * noise_ring((reg.h, reg.w), seed_of(name, "grain"), 24, 6, 2), 0.008)
    stri = engine_striations(reg.w, reg.h) & (t < joint)
    out[stri, 0] -= 0.026
    jr = int(round(joint * reg.h))
    out[jr - 1, :, 0] += 0.06
    out[jr, :, 0] -= 0.05
    for row in range(jr + 8, reg.h - 4, 9):
        out[row, :, 0] += 0.022
        out[row + 1, :, 0] -= 0.016
    out[-3:-1, :, 0] += 0.06
    out[-1, :, 0] -= 0.06
    out[0, :, 0] += 0.035
    return to_rgb8(out)


def paint_vac_inner(reg, name):
    s, t = reg.st()
    out = ramp_lab([(0.0, (172, 102, 62)), (0.10, (110, 66, 42)), (0.22, (48, 40, 36)), (0.6, (38, 37, 38)),
                    (1.0, (44, 43, 45))], t)
    stri = engine_striations(reg.w, reg.h) & (t < 0.2)
    out[stri, 0] -= 0.03
    for row in range(int(0.22 * reg.h) + 8, reg.h - 3, 9):
        out[row, :, 0] += 0.012
    out[..., 0] += quant(0.009 * noise_ring((reg.h, reg.w), seed_of(name, "grain"), 24, 5, 2), 0.008)
    return to_rgb8(out)


def disc_coords(reg, radius):
    s, t = reg.st()
    return (2 * s - 1) * radius, (2 * t - 1) * radius     # x right, z down (v follows +Z)


def paint_aft_disc(reg, name, radius, engines, soot_level, cell=0.5):
    """Engine-bay heat-shield blanket seen from below: quilted panels, soot, a darker socket with a flange where
    each engine passes through, and the skirt's steel rim."""
    x, z = disc_coords(reg, radius)
    r = np.hypot(x, z)
    shape = x.shape
    out = np.empty(shape + (3,))
    out[:] = lab((88, 88, 90))
    u, v = (x / cell) % 1.0, (z / cell) % 1.0
    pillow = (1 - (2 * u - 1) ** 4) * (1 - (2 * v - 1) ** 4)
    out[..., 0] += quant(0.05 * pillow - 0.025 + 0.012 * noise2(shape, seed_of(name, "grain"), 3, 3), 0.012)
    px_m = reg.w / (2 * radius)
    stitch = (np.minimum(u, 1 - u) * cell * px_m < 0.5) | (np.minimum(v, 1 - v) * cell * px_m < 0.5)
    out[stitch, 0] -= 0.035
    sn = noise2(shape, seed_of(name, "soot"), 10, 10, 3)
    soot = np.clip(soot_level * (0.55 + 0.25 * (1 - r / radius)) + 0.16 * sn, 0, 0.92)
    for ex, ez, er in engines:
        de = np.hypot(x - ex, z - ez)
        soot = np.maximum(soot, soot_level * 1.2 * (1 - smoothstep(er + 0.10, er + 0.75, de)))
    out = mix_lab(out, lab(SOOT), quant(soot, 1 / 12))
    for ex, ez, er in engines:
        de = np.hypot(x - ex, z - ez)
        flange = (de > er + 0.03) & (de <= er + 0.13)
        lit = flange & ((x - ex) * -0.6 + (z - ez) * -0.8 > 0)
        out[flange] = lab((96, 96, 100))
        out[lit] = lab((124, 124, 128))
        hole = de <= er + 0.03
        out[hole] = lab((24, 23, 24))
        out[hole & (de > er - 0.06)] = lab((38, 36, 36))      # the boot's lip
        out[hole & (de < er * 0.55)] = lab((16, 16, 17))     # deep in the socket
    rim = r > radius - 0.14
    out[rim] = lab((152, 154, 158))
    out[(r > radius - 0.20) & ~rim] = lab((70, 70, 73))
    return to_rgb8(out)


def paint_leg(reg, name, length, half_root=0.35, half_tip=0.30):
    """Telescoping landing-leg strut, painted in metres: s (x) runs along the strut from the hinge (a = s * length,
    ~15 px/m) and t (y) across it (b = +0.35 m on row 0 to -0.35 m on the last row, ~46 px/m); the plate tapers to
    +-0.30 m at the foot, so the painted edges follow the taper. Hinge clevis and pin, outer sleeve (rounded by
    baked cylinder shading, light from the top), hydraulic line with clips, gland collar, a narrower polished piston
    between dark rails, foot clevis and pin, landing grime toward the foot."""
    shape = (reg.h, reg.w)
    s, t = reg.st()
    a = s * length
    b = (0.5 - t) * 2.0 * half_root
    hw = half_root + (half_tip - half_root) * a / length
    px_b = reg.h / (2.0 * half_root)
    px_a = reg.w / length

    def cylinder(radius, lo, hi):
        """Lambert across a cylinder of this radius, lit from the top front; quantised to 4 tones in lo..hi (dL)."""
        nb = np.clip(b / radius, -1.0, 1.0)
        lam = np.clip(nb * 0.55 + np.sqrt(1.0 - nb * nb) * 0.84, 0.0, 1.0)
        return lo + (hi - lo) * np.floor(lam * 3.999) / 3.0

    out = np.empty(shape + (3,))
    out[:] = lab((84, 87, 93))
    out[..., 0] += cylinder(hw, -0.07, 0.05)
    out[..., 0] += quant(0.006 * noise2(shape, seed_of(name, "grain"), 4, 3, 2), 0.01)
    edge = (hw - np.abs(b)) * px_b                     # px inside the visible (tapered) edge; < 0 is off the plate
    rim = edge < 1.0                                   # the last pixel row inside the edge, and everything outside
    base_l = lab((84, 87, 93))[0]
    out[rim & (b > 0), 0] = base_l + 0.07                # lit bevel along the upper edge, one even line
    out[rim & (b < 0), 0] = base_l - 0.10
    # hydraulic line along the sleeve, with clips
    line = (a > 0.45) & (a < 3.62)
    out[line & (np.abs(b + 0.215) * px_b < 0.5), 0] -= 0.10
    out[line & (np.abs(b + 0.195) * px_b < 0.5), 0] += 0.05
    clip = line & (np.abs(((a - 0.45) % 0.75) - 0.375) * px_a > 0.375 * px_a - 0.5) & (np.abs(b + 0.205) < 0.045)
    out[clip, 0] += 0.07
    # gland collar where the piston leaves the sleeve
    collar = (a >= 3.70) & (a < 3.98)
    out[collar] = lab((112, 116, 123))
    out[collar, 0] += cylinder(hw, -0.07, 0.06)[collar]
    out[(np.abs(a - 3.70) * px_a < 0.5) | (np.abs(a - 3.98) * px_a < 0.5)] = lab((44, 46, 51))
    # polished piston, narrower than the sleeve, between dark rails
    pz = (a >= 3.98) & (a < 6.05)
    rod_r = 0.19
    rod = pz & (np.abs(b) <= rod_r)
    rails = pz & ~rod
    out[rails] = lab((52, 54, 59))
    out[rails & rim & (b > 0), 0] += 0.05
    out[rails & (np.abs(np.abs(b) - rod_r) * px_b < 1.0), 0] -= 0.035      # the rod's shadow on the rails
    out[rod] = lab((146, 151, 158))
    out[rod, 0] += cylinder(rod_r, -0.12, 0.05)[rod]
    out[rod & (np.abs(b - 0.085) * px_b < 0.5), 0] += 0.08                 # specular line
    # clevises with pins at both ends
    for a0, a1, pin in ((0.0, 0.30, 0.15), (6.05, length + 1.0, 6.30)):
        m = (a >= a0) & (a < a1)
        out[m] = lab((60, 62, 68))
        out[m, 0] += cylinder(hw, -0.05, 0.04)[m]
        out[m & (np.abs(a - a0) * px_a < 0.5)] = lab((40, 42, 47))
        d = np.hypot((a - pin) / 0.085, b / 0.085)
        out[d <= 1.0] = lab((128, 132, 138))
        out[(d <= 1.0) & (b < -0.02)] = lab((96, 99, 105))
        out[(d > 1.0) & (d <= 1.6) & m] = lab((44, 46, 51))
    # dust and soot toward the foot (the gland wipes the polished rod, so it stays cleaner)
    grime = smoothstep(5.7, 6.5, a) * (0.85 + 0.15 * noise2(shape, seed_of(name, "grime"), 3, 3, 2))
    grime = np.where(rod, grime * 0.35, grime)
    out = mix_lab(out, lab((58, 54, 50)), quant(np.clip(grime, 0, 1) * 0.4, 1 / 8))
    return to_rgb8(out)


def paint_leg_foot(reg, name):
    shape = (reg.h, reg.w)
    s, t = reg.st()
    x, z = 2 * s - 1, 2 * t - 1
    r = np.hypot(x, z)
    out = np.empty(shape + (3,))
    out[:] = lab((50, 52, 56))
    out[..., 0] += quant(0.014 * noise2(shape, seed_of(name, "grain"), 4, 4, 2), 0.012)
    out[(r > 0.62) & (r < 0.74), 0] += 0.06
    out[(r > 0.74) & (r < 0.80), 0] -= 0.03
    out[r < 0.16, 0] += 0.05
    out[(np.abs(x) > 0.92) | (np.abs(z) > 0.92), 0] -= 0.04
    return to_rgb8(out)


# ------------------------------------------------------------------------------------------------- crew cabin

CABIN_PANEL = (212, 215, 219)
CABIN_COLUMNS = 29


def paint_cabin_wall(geo, reg, wins):
    """The crew-deck wall seen from inside: padded panels on the window pitch, a handrail, handholds between the
    windows, displays in front of the couches that face the windward (windowless) wall, lockers above. Window
    openings are alpha 0 at exactly the windows' angle and height ranges, framed by a dark bezel."""
    W, H = reg.w, reg.h
    theta = (np.arange(W) + 0.5) / W * TAU
    y = geo.cabin_ceiling - (geo.cabin_ceiling - geo.cabin_floor) * (np.arange(H) + 0.5) / H
    deg = np.degrees(theta)
    pitch = WINDOW_PITCH_DEG
    colf = (deg - (180.0 - pitch / 2)) / pitch
    col = np.floor(colf).astype(int)
    cu = colf - col                                      # 0..1 across a panel column
    zones = [(geo.cabin_floor, 37.80, "base"), (37.80, 38.40, "low"), (38.40, 38.50, "rail"),
             (38.50, 40.30, "mid"), (40.30, 41.95, "high"), (41.95, geo.cabin_ceiling + 1, "cove")]
    shape = (H, W)
    out = np.empty(shape + (3,))
    out[:] = lab(CABIN_PANEL)
    zone_of = np.empty(H, object)
    for z0, z1, nm in zones:
        rows = (y >= z0) & (y < z1)
        zone_of[rows] = nm
        if nm in ("low", "mid", "high"):
            zv = (z1 - y[rows]) / (z1 - z0)
            pil = (1 - (2 * cu[None, :] - 1) ** 4) * (1 - (2 * zv[:, None] - 1) ** 4)
            out[rows, :, 0] += quant(0.020 * pil - 0.010, 0.0075)
    rows_of = {nm: (y >= z0) & (y < z1) for z0, z1, nm in zones}
    out[rows_of["base"]] = lab((96, 100, 107))
    out[rows_of["cove"]] = lab((192, 196, 201))
    rail = rows_of["rail"]
    out[rail] = lab((76, 80, 88))
    rr = np.nonzero(rail)[0]
    if len(rr):
        out[rr.min() - 1] = lab((150, 156, 164))
    # horizontal seams at zone boundaries, vertical seams at panel columns
    hb = np.zeros(H, bool)
    zn = list(zone_of)
    for i in range(1, H):
        if zn[i] != zn[i - 1]:
            hb[i] = True
    out[hb & ~rail, :, 0] -= 0.045
    vseam = np.zeros(W, bool)
    vseam[1:] = col[1:] != col[:-1]
    vseam[0] = col[0] != col[-1]
    for nm in ("low", "mid", "high", "cove"):
        m = rows_of[nm][:, None] & vseam[None, :]
        out[m, 0] -= 0.04
    for c in np.nonzero(vseam)[0]:                       # handrail brackets
        out[rr, c] = lab((54, 58, 64))
    # windows
    glass, lu, lv, wid = window_masks(theta, y, geo, wins)
    ring1 = dilate8(glass, True) & ~glass
    ring2 = dilate8(glass | ring1, True) & ~(glass | ring1)
    out[ring2] = lab((128, 132, 138))
    out[ring1] = lab((44, 46, 51))
    # handholds between neighbouring windows
    win_cols = {int(np.floor((math.degrees(th) - (180.0 - pitch / 2)) / pitch)) for th, *_ in wins}
    hold_rows = (y >= 38.72) & (y <= 39.48)
    for c in np.nonzero(vseam)[0]:
        left, right = col[c - 1], col[c]
        if left in win_cols and right in win_cols:
            out[hold_rows, c] = lab((66, 70, 78))
            out[hold_rows, (c - 1) % W] = lab((150, 156, 164))
            ends = np.nonzero(hold_rows)[0]
            out[[ends.min(), ends.max()], c] = lab((48, 50, 56))
    # displays for the couches that face the windowless wall
    r_wall = float(geo.hull_radius(39.1)) - geo.cabin_inset
    screen_rows = (y >= 38.80) & (y <= 39.42)
    for k, a in enumerate(geo.seat_angles()):
        if min(abs(math.degrees(wrap_pi(a - th))) for th, *_ in wins) < 10.0:
            continue
        hw = 0.46 / r_wall
        cols = np.abs(wrap_pi(theta - a)) <= hw
        m = screen_rows[:, None] & cols[None, :]
        out[m] = lab((40, 42, 47))
        inner = m & ~outline_of(m, True)
        out[inner] = lab((10, 21, 29))
        paint_display(out, inner, k)
    # lockers in the upper panels on the windward side: a latch at each panel's lower middle
    high = np.nonzero(rows_of["high"])[0]
    if len(high):
        latch_row = high.max() - 2
        for c in range(W):
            if abs(math.degrees(wrap_pi(theta[c]))) < 105 and abs(cu[c] - 0.5) < 0.5 / (WINDOW_PITCH_DEG / 360 * W):
                out[latch_row, c] = lab((90, 94, 100))
                out[latch_row, (c + 1) % W] = lab((90, 94, 100))
    # air vents in the cove
    cove = np.nonzero(rows_of["cove"])[0]
    if len(cove):
        vr = cove.min() + 2
        for c in range(W):
            if col[c] % 2 == 0 and abs(cu[c] - 0.5) < 0.18:
                out[vr, c] = lab((120, 124, 130))
    alpha = np.where(glass, 0, 255).astype(np.uint8)
    rgb = to_rgb8(out)
    rgb = fill_transparent_rgb(rgb, alpha)
    return rgb, alpha, glass


def paint_display(out, inner, k):
    """Abstract instrument display (no text): telemetry bars, a horizon line and a ring gauge."""
    ys, xs = np.nonzero(inner)
    y0, y1, x0, x1 = ys.min(), ys.max(), xs.min(), xs.max()
    if x1 - x0 > out.shape[1] / 2:                     # straddles the wrap: skip decoration (none do)
        return
    rng = np.random.default_rng(seed_of("cabin_display", k))
    cyan, green, amber, dim = lab((70, 190, 210)), lab((110, 205, 120)), lab((232, 172, 72)), lab((30, 62, 74))
    w = x1 - x0 + 1
    for i, row in enumerate(range(y0 + 1, min(y1, y0 + 7), 2)):
        length = int(rng.integers(max(2, w // 4), max(3, w // 2)))
        out[row, x0 + 1:x0 + 1 + length] = [cyan, green, amber][i % 3]
        out[row, x0 + 1 + length:x0 + w // 2 + 1] = dim
    hz = y0 + (y1 - y0) * 2 // 3
    out[hz, x0 + w // 2 + 1:x1] = dim
    cx, cy = x0 + w * 3 // 4, y0 + (y1 - y0) // 3 + 1
    for dx, dy in ((0, -2), (1, -2), (2, -1), (2, 0), (1, 1), (0, 1), (-1, 0), (-1, -1)):
        yy, xx = cy + dy, cx + dx
        if y0 <= yy <= y1 and x0 <= xx <= x1:
            out[yy, xx] = cyan if dy < 0 else green


def paint_cabin_floor(geo, reg):
    """Grey non-slip deck with a 0.6 m panel grid, couch rails and the round hatch down to the cargo bay."""
    R = geo.floor_r
    x, z = disc_coords(reg, R)
    r = np.hypot(x, z)
    shape = x.shape
    out = np.empty(shape + (3,))
    out[:] = lab((112, 115, 121))
    ys, xs = np.mgrid[0:shape[0], 0:shape[1]]
    tread = ((xs + ys) % 4 == 0) & (xs % 2 == 0)
    out[tread, 0] += 0.02
    out[..., 0] += quant(0.008 * noise2(shape, seed_of("cabin_floor", "grain"), 5, 5, 2), 0.008)
    px = reg.w / (2 * R)
    grid = (np.abs(((x + 0.3) % 0.6) - 0.3) * px > 0.3 * px - 0.5) | (np.abs(((z + 0.3) % 0.6) - 0.3) * px > 0.3 * px - 0.5)
    out[grid, 0] -= 0.035
    for a in geo.seat_angles():                          # couch mounting rails
        rad = np.array([math.sin(a), math.cos(a)])
        tan = np.array([math.cos(a), -math.sin(a)])
        along = x * rad[0] + z * rad[1]
        across = x * tan[0] + z * tan[1]
        for off in (-0.24, 0.24):
            m = (np.abs(across - off) * px < 0.6) & (along > 1.95) & (along < 2.95)
            out[m] = lab((72, 75, 81))
    out[r > R - 0.12] = lab((84, 88, 94))
    # hatch
    ring = (r <= 0.60) & (r > 0.50)
    out[ring] = lab((160, 163, 168))
    out[(r <= 0.60) & (r > 0.565)] = lab((176, 179, 184))
    door = r <= 0.50
    out[door] = lab((96, 99, 105))
    out[door & (np.abs(r - 0.36) * px < 0.5)] = lab((82, 85, 91))
    handle = (np.abs(x) < 0.16) & (np.abs(z - 0.22) < 0.035)
    out[handle] = lab((150, 153, 158))
    hinge = (np.abs(x) < 0.22) & (z < -0.42) & (z > -0.52)
    out[hinge] = lab((70, 73, 79))
    return to_rgb8(out)


def paint_cabin_ceiling(geo, reg):
    """White ceiling panels (concentric and radial seams), a ring of warm light strips and a central vent hatch."""
    R = geo.ceiling_r
    x, z = disc_coords(reg, R)
    r = np.hypot(x, z)
    ang = np.arctan2(x, z)
    shape = x.shape
    px = reg.w / (2 * R)
    out = np.empty(shape + (3,))
    out[:] = lab((226, 228, 231))
    out[..., 0] += quant(0.006 * noise2(shape, seed_of("cabin_ceiling", "grain"), 6, 6, 2), 0.006)
    for rs in (0.80, 1.70, 2.72):
        out[np.abs(r - rs) * px < 0.55] = lab((198, 201, 206))
    sector = TAU / 12
    radial = (np.abs(wrap_pi(ang + sector / 2) % sector - sector / 2) * r * px < 0.55) & (r > 0.80)
    out[radial] = lab((198, 201, 206))
    strip = (r > 1.98) & (r < 2.36) & (np.abs((ang % sector) - sector / 2) < sector / 2 - 0.10)
    core = strip & (np.abs(r - 2.17) * px < 1.0)
    out[dilate8(strip) & ~strip] = lab((176, 172, 166))
    out[strip] = lab((246, 220, 176))
    out[core] = lab((255, 242, 214))
    hatch = r <= 0.45
    out[hatch] = lab((150, 153, 158))
    grille = (r <= 0.36) & ((np.floor((x + 1) * px) % 2) == 0)
    out[r <= 0.36] = lab((112, 115, 120))
    out[grille] = lab((84, 87, 92))
    out[r > R - 0.15] = lab((206, 209, 213))
    return to_rgb8(out)


def paint_cabin_seat(reg):
    """Couch upholstery, tileable (period 8 px): dark blue-grey fabric quilted in 8 px squares with stitching."""
    h, w = reg.h, reg.w
    ys, xs = np.mgrid[0:h, 0:w]
    u, v = (xs % 8 + 0.5) / 8, (ys % 8 + 0.5) / 8
    out = np.empty((h, w, 3))
    out[:] = lab((52, 60, 76))
    pil = (1 - (2 * u - 1) ** 2) * (1 - (2 * v - 1) ** 2)
    out[..., 0] += quant(0.06 * pil - 0.03, 0.015)
    twill = ((xs + ys) // 2) % 2 == 0
    out[twill, 0] += 0.006
    seam = (xs % 8 == 0) | (ys % 8 == 0)
    out[seam] = lab((38, 44, 57))
    stitch = ((xs % 8 == 0) & (ys % 2 == 1)) | ((ys % 8 == 0) & (xs % 2 == 1))
    out[stitch] = lab((96, 106, 126))
    return to_rgb8(out)


# ===================================================================================================================
# Super Heavy
# ===================================================================================================================

def booster_sheets(geo, edges):
    nfwd = int(np.searchsorted(edges, BOOSTER_FORWARD_Y - 1e-6))

    def fn(k, r_mean, rng):
        if k >= nfwd:
            return 12
        return int(rng.choice([2, 3], p=[0.5, 0.5]))
    return fn, (lambda k: k >= nfwd)


def paint_booster_hull(geo, L):
    fr = booster_hull_frame(geo, L)
    edges = ring_edges_booster(geo)
    fn, aligned = booster_sheets(geo, edges)
    lab_img, st = paint_steel(fr, edges, "booster_hull", fn, aligned_fn=aligned)
    y = fr.y
    H, W = fr.H, fr.W
    # forward section: a separate assembly, slightly cooler, with a stringer line in each panel
    fwd = y >= BOOSTER_FORWARD_Y
    lab_img[fwd, :, 0] -= 0.012
    lab_img[fwd, :, 2] -= 0.004
    mid = fr.cols_of_angle(math.radians(7.5) + TAU * (np.arange(12) + 0.5) / 12)
    rows = np.nonzero(fwd & ~st["weld"])[0]
    lab_img[np.ix_(rows, mid)] += np.array([-0.025, 0, 0])
    # chines: raised fairings over the plumbing at +-90 deg, a shade darker with a lit edge and a shadowed edge
    chine = np.zeros((H, W), bool)
    for ang in (math.pi / 2, 3 * math.pi / 2):
        hw = 0.5 * np.clip(np.minimum((y - 3.0) / 1.2, (67.2 - y) / 1.2), 0, 1)       # tapered ends
        dist = np.abs(wrap_pi(fr.theta - ang))[None, :] * geo.R
        chine |= dist <= np.maximum(hw[:, None], 0) - 1e-6
    # A triangular ridge: the half facing smaller s catches the light, the other half is in shade, with a bright
    # apex line, a dark foot on the shaded side and the fairing's shadow on the tank wall beside it.
    side = np.zeros((H, W), int)
    for ang in (math.pi / 2, 3 * math.pi / 2):
        side += np.where(wrap_pi(fr.theta - ang)[None, :] < 0, -1, 1) * (np.abs(wrap_pi(fr.theta - ang)) < 0.2)[None, :]
    lab_img[chine & (side < 0), 0] += 0.012
    lab_img[chine & (side > 0), 0] -= 0.075
    lit = chine & ~shift(chine, 0, 1, True)
    dark = chine & ~shift(chine, 0, -1, True)
    lab_img[lit, 0] += 0.07
    lab_img[dark, 0] -= 0.06
    shadow = ~chine & shift(dark, 0, 1, True)
    lab_img[shadow, 0] -= 0.05
    for ang in (math.pi / 2, 3 * math.pi / 2):
        c = int(fr.cols_of_angle(ang))
        apex = chine[:, c] & chine[:, (c - 1) % W]
        lab_img[apex, (c - 1) % W, 0] += 0.06
    seg = chine & st["weld"][:, None]
    lab_img[seg, 0] -= 0.035
    fast = chine & dark & ((np.arange(H) % 6) == 3)[:, None]
    lab_img[fast, 0] -= 0.05
    # grid fin mounts (doubler plates with an actuator housing) at the fin roots
    fin_y0 = geo.c["GRID_FIN_Y"] - 0.2
    fin_y1 = geo.c["GRID_FIN_Y"] + geo.c["GRID_FIN_HEIGHT"] + 0.2
    for a in geo.c["GRID_FIN_ANGLES_DEG"]:
        d = np.abs(wrap_pi(fr.theta - math.radians(a)))[None, :] * geo.R
        plate = (d <= 0.58) & ((y >= fin_y0) & (y <= fin_y1))[:, None]
        lab_img[plate, 0] -= 0.03
        ol = outline_of(plate, True)
        lab_img[ol, 0] -= 0.09
        riv = plate & ~ol & dilate4(ol, True) & (((np.arange(H)[:, None] + np.arange(W)[None, :]) % 3) == 0)
        lab_img[riv, 0] -= 0.05
        yc = geo.c["GRID_FIN_Y"] + geo.c["GRID_FIN_HEIGHT"] / 2
        hous = (d <= 0.22) & (np.abs(y - yc) <= 1.15)[:, None]
        lab_img[hous, 0] -= 0.05
        lab_img[outline_of(hous, True), 0] -= 0.06
    # engine-section soot from exhaust recirculation, ragged upper edge, streaking upward
    streak = fr.noise("booster_hull:soot_streak", 0.38, 2.6, 3)
    edge = 5.0 + 1.1 * streak
    amt = (1 - smoothstep(0.0, 1.0, y[:, None] / edge)) ** 1.3
    amt = np.clip(amt * (0.86 + 0.14 * fr.noise("booster_hull:soot_cloud", 1.2, 1.2, 2)), 0, 1)
    lab_img = mix_lab(lab_img, lab(SOOT), quant(amt * 0.9, 1 / 14))
    # quick-disconnect plate and vents
    qd = (np.abs(wrap_pi(fr.theta - math.pi))[None, :] * geo.R <= 0.7) & ((y >= 0.9) & (y <= 2.5))[:, None]
    lab_img[qd, 0] -= 0.05
    lab_img[outline_of(qd, True), 0] -= 0.07
    for ang_d, yv in ((200.0, 44.6), (160.0, 65.4), (20.0, 69.3), (340.0, 69.3)):
        c = int(fr.cols_of_angle(math.radians(ang_d)))
        rrow = fr.row_of_height(yv)
        lab_img[rrow - 1:rrow + 2, [(c - 1) % W, c, (c + 1) % W]] += np.array([0.03, 0, 0])
        lab_img[rrow, c] = lab((30, 30, 32))
    return fr, to_rgb8(lab_img), dict(chine=chine, steel=st)


def paint_hot_stage_ring(geo, reg):
    """The vented hot-staging interstage: steel flanges top and bottom, 24 vents ~0.72 m x 1.2 m, scorched."""
    W, H = reg.w, reg.h
    theta = (np.arange(W) + 0.5) / W * TAU
    hgt = geo.ring_h * (1 - (np.arange(H) + 0.5) / H)       # 0 at the bottom of the ring
    fr = HullFrame(W, geo.booster_barrel + hgt, geo.booster_barrel + hgt, np.full(H, geo.R), np.arange(H))
    out, _ = paint_steel(fr, np.array([0, geo.booster_barrel + geo.ring_h + 1]), "hot_stage_ring",
                         lambda k, r, rng: 0, step=0.0105)
    nv = 24
    vent_hw = 0.36 / geo.R
    dth = np.abs(wrap_pi(theta[None, :] - (np.floor(theta / TAU * nv) + 0.5)[None, :] * TAU / nv))
    vent_rows = (hgt >= 0.30) & (hgt <= 1.50)
    vent = (dth <= vent_hw) & vent_rows[:, None]
    # Scorch: the ship's exhaust leaves through the vents at staging, so soot fans out above and below each vent
    # (streaky) and browns the ribs between them; the whole ring is heat-tinted.
    across = np.clip(1 - (dth - vent_hw) * geo.R / 0.40, 0, 1)
    gap = np.where(hgt[:, None] > 1.50, hgt[:, None] - 1.50, np.where(hgt[:, None] < 0.30, 0.30 - hgt[:, None], 0.0))
    streak = fr.noise("hot_stage_ring:streak", 0.07, 0.6, 2)
    fan = np.clip(1 - gap / (0.32 + 0.10 * streak), 0, 1) * (np.abs(dth) <= vent_hw * 1.25)
    scorch = 0.16 + 0.34 * across * vent_rows[:, None] + 0.45 * fan + 0.10 * fr.noise("hot_stage_ring:scorch", 0.3, 0.6, 2)
    out = mix_lab(out, lab((66, 52, 43)), quant(np.clip(scorch, 0, 0.88), 1 / 10))
    top = hgt > 1.62
    bot = hgt < 0.15
    out[top, :, 0] += 0.035
    out[bot, :, 0] += 0.02
    out[np.argmin(np.abs(hgt - 1.62)), :, 0] -= 0.08
    out[np.argmin(np.abs(hgt - 0.15)), :, 0] -= 0.08
    # Vents: dark openings with the lit underside of the lintel, the sill, and a faint glow low down where the
    # scorched dome inside catches some light.
    out[vent] = lab((15, 14, 14))
    out[vent & (hgt < 0.62)[:, None]] = lab((25, 22, 21))
    lip = vent & ~shift(vent, 1, 0, True)
    out[lip] = lab((60, 53, 48))
    sill = vent & ~shift(vent, -1, 0, True)
    out[sill] = lab((34, 31, 30))
    lit = ~vent & shift(vent, 0, -1, True)
    shade = ~vent & shift(vent, 0, 1, True)
    out[lit, 0] += 0.05
    out[shade, 0] -= 0.06
    return to_rgb8(out)


def paint_ring_top(geo, reg):
    """Top of the hot-staging ring from above: the scorched heat-shield dome over the booster's forward dome, gores
    and rings, darker halos where the ship's engines fire at staging, and the ring's steel lip."""
    R = geo.R
    x, z = disc_coords(reg, R)
    r = np.hypot(x, z)
    ang = np.arctan2(x, z)
    shape = x.shape
    px = reg.w / (2 * R)
    out = ramp_lab([(0.0, (34, 31, 30)), (2.0, (52, 48, 45)), (4.2, (86, 80, 75))], r)
    out[..., 0] += quant(0.03 * noise2(shape, seed_of("ring_top", "mottle"), 8, 8, 3), 0.015)
    for rs in (1.2, 2.3, 3.3):
        out[np.abs(r - rs) * px < 0.55, 0] -= 0.035
    sector = TAU / 16
    gore = (np.abs(wrap_pi(ang + sector / 2) % sector - sector / 2) * r * px < 0.55) & (r > 1.2)
    out[gore, 0] -= 0.03
    halo = np.zeros(shape)
    for rr_, angs, er in ((geo.c["SL_RING_RADIUS"], geo.c["SL_ANGLES_DEG"], 0.9),
                          (geo.c["VAC_RING_RADIUS"], geo.c["VAC_ANGLES_DEG"], 1.3)):
        for a in angs:
            ex, ez = rr_ * math.sin(math.radians(a)), rr_ * math.cos(math.radians(a))
            halo = np.maximum(halo, 1 - smoothstep(er * 0.5, er, np.hypot(x - ex, z - ez)))
    out = mix_lab(out, lab((22, 20, 20)), quant(halo * 0.55, 1 / 8))
    lipm = r > R - 0.25
    out[lipm] = lab((176, 178, 182))
    out[(r > R - 0.31) & ~lipm] = lab((60, 56, 54))
    out[r > R - 0.06] = lab((150, 152, 156))
    return to_rgb8(out)


def paint_grid_fin(reg):
    """Alpha-tested waffle: 2 px steel bars on an 8 x 7 px pitch (0.60 x 0.64 m cells, 9 x 6 of them) inside a solid
    frame that follows the trapezoid (the root is 85 % of the tip height). Holes are alpha 0, metal 255."""
    H, W = reg.h, reg.w
    ys, xs = np.mgrid[0:H, 0:W]
    yc, xc = ys + 0.5, xs + 0.5
    top = H * (1 - 0.85) / 2 * (1 - xc / W)
    bot = H - top
    dist = np.minimum.reduce([yc - top, bot - yc, xc, W - xc])
    frame = dist < 3.0
    holes = (((xs - 5) % 8) < 6) & (xs >= 5) & (xs < W - 5) & (((ys - 4) % 7) < 5) & (ys >= 4) & (ys < H - 4)
    hole = holes & ~frame
    out = np.empty((H, W, 3))
    out[:] = lab((76, 79, 86))
    out[frame] = lab((94, 97, 104))
    edge_in = frame & dilate4(~frame) & ~(dist < 1.0)
    out[edge_in & (dist > 2.0), 0] -= 0.05
    out[dist < 1.0, 0] -= 0.04
    out[~hole & shift(hole, -1, 0), 0] += 0.06      # bar above a hole: its lower face catches the light
    out[~hole & shift(hole, 1, 0), 0] -= 0.06
    out[~hole & shift(hole, 0, -1), 0] += 0.03
    out[~hole & shift(hole, 0, 1), 0] -= 0.03
    out[..., 0] += quant(0.012 * noise2((H, W), seed_of("grid_fin", "soot"), 6, 6, 2), 0.012)
    alpha = np.where(hole, 0, 255).astype(np.uint8)
    rgb = fill_transparent_rgb(to_rgb8(out), alpha)
    return rgb, alpha


def paint_grid_fin_edge(reg):
    H, W = reg.h, reg.w
    out = np.empty((H, W, 3))
    out[:] = lab((94, 97, 104))
    out[:, [0, W - 1], 0] -= 0.05
    out[:, 3:5, 0] += 0.03
    out[..., 0] += quant(0.012 * noise2((H, W), seed_of("grid_fin_edge", "grain"), 3, 6, 2), 0.012)
    for row in range(3, H, 7):
        out[row, 1:W - 1, 0] -= 0.035
    return to_rgb8(out)


# ===================================================================================================================
# Overlays: frost and lights
# ===================================================================================================================

FROST_FILL = (226, 236, 248)


def frost_layer(fr, name, y0, y1, dip_y, dip_floor, dip_hw, weaker=None, weaker_k=0.55):
    """Frost over a tank span: white with a faint blue tint, vertical streaks and feathery clusters; alpha
    200-255 in the middle, ragged fades over ~1.3 m at the outer domes, thinner along the common dome."""
    y = fr.y[:, None]
    n1 = np.clip(fr.noise(name + ":edge_lo", 0.45, 0.6, 3), -1.4, 1.4)
    n2 = np.clip(fr.noise(name + ":edge_hi", 0.45, 0.6, 3), -1.4, 1.4)
    n3 = np.clip(fr.noise(name + ":dip", 0.6, 0.6, 2), -2, 2)
    env = smoothstep(y0 - 0.15 + 0.32 * n1, y0 + 1.25 + 0.32 * n1, y) * (
        1 - smoothstep(y1 - 1.45 + 0.32 * n2, y1 + 0.15 + 0.32 * n2, y))
    env = env * (1 - (1 - dip_floor) * np.exp(-(((y - dip_y) + 0.22 * n3) / dip_hw) ** 2))
    env = env * ((y >= y0 - 0.6) & (y <= y1 + 0.6))      # never beyond the domes
    streak = fr.noise(name + ":streak", 0.22, 2.4, 3)
    feather = fr.noise(name + ":feather", 0.09, 0.32, 2)
    blotch = fr.noise(name + ":blotch", 1.5, 2.2, 2)
    dens = env * np.clip(0.86 + 0.085 * streak + 0.06 * feather + 0.06 * blotch, 0, 1)
    if weaker is not None:
        dens = np.where(weaker, dens * weaker_k, dens)
    dens = np.clip(dens, 0, 1)
    a = quant(dens * 255, 255 / 14)
    a = np.where(a < 18, 0, a)
    col = ramp_lab([(0.0, (198, 216, 238)), (0.55, (220, 233, 248)), (1.0, (243, 247, 253))], dens + 0.06 * streak)
    col[..., 0] = quant(col[..., 0], 0.012)
    rgb = to_rgb8(col)
    rgb[a == 0] = FROST_FILL
    return rgb, np.clip(a, 0, 255).astype(np.uint8)


def lights_layer(fr, aux):
    """Emissive layer for the hull canvas: warm cabin light in every window (alpha 255 on the glass), and the
    navigation-light lenses."""
    H, W = fr.H, fr.W
    rgb = np.zeros((H, W, 3), np.uint8)
    rgb[:] = (255, 214, 150)
    alpha = np.zeros((H, W), np.uint8)
    g = aux["glass"]
    lv = aux["lv"]
    col = ramp_lab([(-1.0, (196, 128, 64)), (-0.55, (232, 168, 96)), (0.2, (250, 208, 140)), (1.0, (255, 236, 196))], lv)
    col[..., 0] = quant(col[..., 0], 0.02)
    c8 = to_rgb8(col)
    rgb[g] = c8[g]
    alpha[g] = 255
    for (rrow, c), colour in aux["lens"].items():
        rgb[rrow, c] = {"red": (255, 64, 52), "green": (80, 255, 120), "white": (255, 255, 255)}[colour]
        alpha[rrow, c] = 255
    return rgb, alpha


# ===================================================================================================================
# Atlases
# ===================================================================================================================

class Atlas:
    def __init__(self, w, h, fill=(0, 0, 0), alpha=255):
        self.rgb = np.zeros((h, w, 3), np.uint8)
        self.rgb[:] = fill
        self.alpha = np.full((h, w), alpha, np.uint8)
        self.painted = np.zeros((h, w), bool)

    def put(self, reg, rgb, alpha=None):
        if rgb.shape[:2] != (reg.h, reg.w):
            raise ValueError(f"{reg.name}: painted {rgb.shape[:2]}, region is {(reg.h, reg.w)}")
        self.rgb[reg.y0:reg.y1, reg.x0:reg.x1] = rgb
        if alpha is not None:
            self.alpha[reg.y0:reg.y1, reg.x0:reg.x1] = alpha
        self.painted[reg.y0:reg.y1, reg.x0:reg.x1] = True

    def pad(self, reach=16, flat=(128, 130, 134)):
        """Fill unused space opaquely: within `reach` px of a region (what 4 mip levels can sample) with the nearest
        painted colour, so mipmaps never mix in foreign colours; further out with one flat colour."""
        free = ~self.painted
        dist, idx = ndimage.distance_transform_edt(free, return_indices=True)
        near = free & (dist <= reach)
        self.rgb[near] = self.rgb[idx[0][near], idx[1][near]]
        self.rgb[free & ~near] = flat
        self.alpha[free] = 255

    def rgba(self):
        return np.dstack([self.rgb, self.alpha])


def build_all(geo, L):
    t0 = time.time()
    sw, sh = L.textures["SHIP"]
    bw, bh = L.textures["BOOSTER"]
    ship = Atlas(sw, sh)
    ship_frost = Atlas(sw, sh, FROST_FILL, 0)
    ship_lights = Atlas(sw, sh, (255, 214, 150), 0)
    booster = Atlas(bw, bh)
    booster_frost = Atlas(bw, bh, FROST_FILL, 0)
    info = {}

    # --- ship hull (nose + barrel as one canvas)
    fr, rgb, aux = paint_ship_hull(geo, L)
    hull_reg = Region("SHIP_HULL", "SHIP", L["SHIP_NOSE"].x0, L["SHIP_NOSE"].y0, L["SHIP_BARREL"].x1, L["SHIP_BARREL"].y1)
    ship.put(hull_reg, rgb)
    info["ship_frame"], info["ship_aux"] = fr, aux
    frgb, fa = frost_layer(fr, "ship_frost", SHIP_TANKS["LOX"][0], SHIP_TANKS["CH4"][1], SHIP_TANKS["LOX"][1], 0.45,
                           0.55, weaker=aux["tiled"])
    nb = L["SHIP_NOSE"].h
    fa[:nb] = 0                                           # barrel region only
    frgb[:nb] = FROST_FILL
    ship_frost.put(hull_reg, frgb, fa)
    lrgb, la = lights_layer(fr, aux)
    ship_lights.put(hull_reg, lrgb, la)

    # --- flaps
    ship.put(L["SHIP_AFT_FLAP_WINDWARD"], to_rgb8(paint_hex_plate((L["SHIP_AFT_FLAP_WINDWARD"].h, L["SHIP_AFT_FLAP_WINDWARD"].w), "aft_flap_tiles")))
    ship.put(L["SHIP_FORE_FLAP_WINDWARD"], to_rgb8(paint_hex_plate((L["SHIP_FORE_FLAP_WINDWARD"].h, L["SHIP_FORE_FLAP_WINDWARD"].w), "fore_flap_tiles")))
    ship.put(L["SHIP_AFT_FLAP_LEEWARD"], paint_flap_leeward(L["SHIP_AFT_FLAP_LEEWARD"], "aft_flap_leeward",
                                                            panels=[(14, 46, 40, 74), (24, 104, 54, 140), (10, 152, 30, 168)],
                                                            root_px=6, ribs=[32, 64, 96, 128, 160]))
    ship.put(L["SHIP_FORE_FLAP_LEEWARD"], paint_flap_leeward(L["SHIP_FORE_FLAP_LEEWARD"], "fore_flap_leeward",
                                                             panels=[(12, 40, 32, 62), (16, 78, 36, 98)],
                                                             root_px=5, ribs=[36, 72]))
    ship.put(L["SHIP_AFT_FLAP_EDGE"], paint_flap_edge(L["SHIP_AFT_FLAP_EDGE"], "aft_flap_edge"))
    ship.put(L["SHIP_FORE_FLAP_EDGE"], paint_flap_edge(L["SHIP_FORE_FLAP_EDGE"], "fore_flap_edge"))

    # --- engines
    sl_out = [(0.0, (102, 111, 126)), (0.3, (94, 98, 108)), (0.5, (118, 108, 84)), (0.7, (110, 92, 80)),
              (0.85, (128, 100, 72)), (1.0, (146, 112, 76))]                  # steel, straw, bronze-violet, bronze
    sl_in = [(0.0, (178, 106, 64)), (0.18, (138, 82, 50)), (0.45, (70, 48, 36)), (0.75, (38, 32, 29)), (1.0, (24, 22, 21))]
    ship.put(L["SHIP_ENGINE_SL_OUTER"], paint_engine_bell_outer(L["SHIP_ENGINE_SL_OUTER"], "ship_sl_outer", sl_out,
                                                                rings=[(0.08, 0.035)]))
    ship.put(L["SHIP_ENGINE_SL_INNER"], paint_engine_bell_inner(L["SHIP_ENGINE_SL_INNER"], "ship_sl_inner", sl_in))
    ship.put(L["SHIP_ENGINE_VAC_OUTER"], paint_vac_outer(L["SHIP_ENGINE_VAC_OUTER"], "ship_vac_outer"))
    ship.put(L["SHIP_ENGINE_VAC_INNER"], paint_vac_inner(L["SHIP_ENGINE_VAC_INNER"], "ship_vac_inner"))

    # --- aft bulkhead
    c = geo.c
    eng = [(c["SL_RING_RADIUS"] * math.sin(math.radians(a)), c["SL_RING_RADIUS"] * math.cos(math.radians(a)),
            c["SL_EXIT_RADIUS"]) for a in c["SL_ANGLES_DEG"]]
    eng += [(c["VAC_RING_RADIUS"] * math.sin(math.radians(a)), c["VAC_RING_RADIUS"] * math.cos(math.radians(a)),
             c["VAC_EXIT_RADIUS"]) for a in c["VAC_ANGLES_DEG"]]
    ship.put(L["SHIP_AFT_DISC"], paint_aft_disc(L["SHIP_AFT_DISC"], "ship_aft_disc", geo.R, eng, 0.30))

    # --- legs
    ship.put(L["SHIP_LEG"], paint_leg(L["SHIP_LEG"], "ship_leg", c["LEG_LENGTH"]))
    ship.put(L["SHIP_LEG_FOOT"], paint_leg_foot(L["SHIP_LEG_FOOT"], "ship_leg_foot"))

    # --- crew cabin
    wall_rgb, wall_a, wall_glass = paint_cabin_wall(geo, L["SHIP_CABIN_WALL"], window_list())
    ship.put(L["SHIP_CABIN_WALL"], wall_rgb, wall_a)
    info["wall_glass"] = wall_glass
    ship.put(L["SHIP_CABIN_FLOOR"], paint_cabin_floor(geo, L["SHIP_CABIN_FLOOR"]))
    ship.put(L["SHIP_CABIN_CEILING"], paint_cabin_ceiling(geo, L["SHIP_CABIN_CEILING"]))
    ship.put(L["SHIP_CABIN_SEAT"], paint_cabin_seat(L["SHIP_CABIN_SEAT"]))
    print(f"  ship painted ({time.time() - t0:.1f} s)")

    # --- booster
    t1 = time.time()
    bfr, brgb, baux = paint_booster_hull(geo, L)
    booster.put(L["BOOSTER_BARREL"], brgb)
    info["booster_frame"], info["booster_aux"] = bfr, baux
    lo, hi = BOOSTER_TANKS["LOX"], BOOSTER_TANKS["CH4"]
    frgb, fa = frost_layer(bfr, "booster_frost", lo[0], hi[1], (lo[1] + hi[0]) / 2, 0.22, 0.9,
                           weaker=baux["chine"], weaker_k=0.72)
    booster_frost.put(L["BOOSTER_BARREL"], frgb, fa)
    booster.put(L["BOOSTER_HOT_STAGE_RING"], paint_hot_stage_ring(geo, L["BOOSTER_HOT_STAGE_RING"]))
    booster.put(L["BOOSTER_HOT_STAGE_RING_TOP"], paint_ring_top(geo, L["BOOSTER_HOT_STAGE_RING_TOP"]))
    beng = []
    for ring_index, (rr_, cnt, er) in enumerate(c["BOOSTER_RINGS"]):
        cnt = int(cnt)
        for k in range(cnt):                              # as boosterEnginePositions(): middle ring offset half a step
            a = TAU * (k + (0.5 if ring_index == 1 else 0.0)) / cnt
            beng.append((rr_ * math.sin(a), rr_ * math.cos(a), er))
    booster.put(L["BOOSTER_AFT_DISC"], paint_aft_disc(L["BOOSTER_AFT_DISC"], "booster_aft_disc", geo.R, beng, 0.55))
    gf_rgb, gf_a = paint_grid_fin(L["BOOSTER_GRID_FIN"])
    booster.put(L["BOOSTER_GRID_FIN"], gf_rgb, gf_a)
    booster.put(L["BOOSTER_GRID_FIN_EDGE"], paint_grid_fin_edge(L["BOOSTER_GRID_FIN_EDGE"]))
    b_out = [(0.0, (92, 98, 108)), (0.35, (82, 83, 88)), (0.6, (100, 90, 72)), (0.85, (96, 80, 66)),
             (1.0, (114, 90, 64))]
    b_in = [(0.0, (160, 94, 58)), (0.18, (118, 72, 46)), (0.45, (58, 42, 34)), (0.75, (32, 28, 26)), (1.0, (21, 20, 19))]
    booster.put(L["BOOSTER_ENGINE_OUTER"], paint_engine_bell_outer(L["BOOSTER_ENGINE_OUTER"], "booster_engine_outer",
                                                                   b_out, soot=0.28, rings=[(0.08, 0.03)]))
    booster.put(L["BOOSTER_ENGINE_INNER"], paint_engine_bell_inner(L["BOOSTER_ENGINE_INNER"], "booster_engine_inner",
                                                                   b_in, soot=0.4))
    print(f"  booster painted ({time.time() - t1:.1f} s)")
    info["booster_engines"] = beng
    info["ship_engines"] = eng

    ship.pad()
    booster.pad()
    out = dict(ship=ship.rgba(), booster=booster.rgba(), ship_frost=ship_frost.rgba(),
               booster_frost=booster_frost.rgba(), ship_lights=ship_lights.rgba())
    return out, info


# ===================================================================================================================
# Item icons (16 x 16, vanilla style: binary alpha, 6-12 colours, two-tone outline, light from the top left)
# ===================================================================================================================

ITEM_RAMPS = {
    "steel": ["#25272c", "#3b3f46", "#5d636c", "#838a93", "#a9b0b8", "#cdd2d8", "#eceef1"],   # '0'..'6'
    "tile": ["#0c0c0e", "#16171a", "#212226", "#2e3035", "#3d3f45"],                        # 'a'..'e'
    "soot": ["#151210", "#2a221d", "#43362c", "#5e4a3a"],                                    # 'p'..'s'
}
ITEM_CODES = {**{str(i): ("steel", i) for i in range(7)}, **{c: ("tile", i) for i, c in enumerate("abcde")},
              **{c: ("soot", i) for i, c in enumerate("pqrs")}}

# Hand-placed pixels ('.' = transparent); the outline is added by item_from_map. Light comes from the top left.
# Starship upright, seen from the windward quarter: black heat shield down the left side and over the tip, steel on
# the right, fore flaps as nubs below the nose, aft flaps (steel faces, dark roots), legs and engine bells below.
STARSHIP_MAP = [
    "................",
    "........c.......",
    "........d.......",
    ".......dc5......",
    ".......c56......",
    "......dc654.....",
    ".....3dc6542....",
    "......dc654.....",
    "......dc654.....",
    "......dc654.....",
    ".....bdc6543....",
    "....43dc65443...",
    "....43dc65433...",
    ".....bcc4332....",
    "....2..bcb..2...",
    "................",
]
# Super Heavy: the hot-staging ring (light lip, dark vented band), two grid fins with a lattice right below it, a
# plain steel barrel with one ring weld, and the sooty engine section with the bells.
SUPER_HEAVY_MAP = [
    "................",
    "......4554......",
    "......qrrq......",
    "....135664313...",
    "....315664131...",
    "......5664......",
    "......5664......",
    "......4554......",
    "......5664......",
    "......5664......",
    "......5664......",
    "......5663......",
    "......4553......",
    "......srrq......",
    "......pqqp......",
    "................",
]


def _hex(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def item_from_map(rows):
    """Paint a pixel map and add the mod's vanilla-style outline: a 1 px, 4-connected ring around the shape in the
    darkest tone of the material it touches where it lies below or right of the shape, and the next tone elsewhere."""
    H, W = len(rows), len(rows[0])
    mat = np.full((H, W), "", object)
    tone = np.zeros((H, W), int)
    for y, row in enumerate(rows):
        if len(row) != W:
            raise ValueError(f"item map row {y} has {len(row)} pixels")
        for x, ch in enumerate(row):
            if ch != ".":
                mat[y, x], tone[y, x] = ITEM_CODES[ch]
    shape = mat != ""
    for y in range(H):
        for x in range(W):
            if shape[y, x]:
                continue
            nb = [(y + dy, x + dx) for dy, dx in ((0, -1), (0, 1), (-1, 0), (1, 0))
                  if 0 <= y + dy < H and 0 <= x + dx < W and shape[y + dy, x + dx]]
            if not nb:
                continue
            above_left = [q for q in nb if q in ((y - 1, x), (y, x - 1))]
            src = (above_left or nb)[0]
            mat[y, x] = mat[src]
            tone[y, x] = 0 if above_left else 1
    out = np.zeros((H, W, 4), np.uint8)
    for y in range(H):
        for x in range(W):
            if mat[y, x]:
                out[y, x] = _hex(ITEM_RAMPS[mat[y, x]][tone[y, x]]) + (255,)
    return out


def make_items():
    return {"starship": item_from_map(STARSHIP_MAP), "super_heavy": item_from_map(SUPER_HEAVY_MAP)}


# ===================================================================================================================
# Validation
# ===================================================================================================================

LATHE_REGIONS = ["SHIP_NOSE", "SHIP_BARREL", "SHIP_ENGINE_SL_OUTER", "SHIP_ENGINE_SL_INNER", "SHIP_ENGINE_VAC_OUTER",
                 "SHIP_ENGINE_VAC_INNER", "SHIP_CABIN_WALL", "BOOSTER_BARREL", "BOOSTER_HOT_STAGE_RING",
                 "BOOSTER_ENGINE_OUTER", "BOOSTER_ENGINE_INNER"]


def seam_ratio(img, reg, rows=None):
    # Colour step across the s = 0/1 wrap divided by the largest step among the three column pairs on either side.
    # <= ~1 means the wrap looks like its neighbourhood (a grout or panel line that happens to sit on the wrap has an
    # equal step on its other side); a real discontinuity scores well above 1.5. Rows where the honeycomb is stretched
    # (near the nose tip one pattern column spans several texture columns, so a pattern boundary always falls on the
    # wrap with no twin nearby) can be excluded with `rows`.
    a = img[reg.y0:reg.y1, reg.x0:reg.x1, :3].astype(float)
    if rows is not None:
        a = a[rows]
    wrap = np.abs(a[:, 0] - a[:, -1]).mean()
    pairs = [(a[:, j + 1], a[:, j]) for j in range(0, 3)] + [(a[:, -j - 1], a[:, -j - 2]) for j in range(0, 3)]
    near = max(np.abs(p - q).mean() for p, q in pairs)
    return wrap / max(near, 0.5)


def validate(tex, items, info, geo, L):
    problems = []
    sw, sh = L.textures["SHIP"]
    bw, bh = L.textures["BOOSTER"]
    for name, (w, h) in (("ship", (sw, sh)), ("ship_frost", (sw, sh)), ("ship_lights", (sw, sh)),
                         ("booster", (bw, bh)), ("booster_frost", (bw, bh))):
        if tex[name].shape != (h, w, 4):
            problems.append(f"{name}: size {tex[name].shape}")
    # base textures: opaque except the cabin-wall openings and the grid-fin holes
    for name, holes_reg in (("ship", "SHIP_CABIN_WALL"), ("booster", "BOOSTER_GRID_FIN")):
        a = tex[name][..., 3]
        r = L[holes_reg]
        outside = np.ones(a.shape, bool)
        outside[r.y0:r.y1, r.x0:r.x1] = False
        if (a[outside] != 255).any():
            problems.append(f"{name}: non-opaque pixels outside {holes_reg}")
        inside = a[r.y0:r.y1, r.x0:r.x1]
        if not set(np.unique(inside).tolist()) <= {0, 255}:
            problems.append(f"{name}: partial alpha in {holes_reg}")
        if (inside == 0).sum() == 0:
            problems.append(f"{name}: no transparent pixels in {holes_reg}")
    wall = L["SHIP_CABIN_WALL"]
    if not np.array_equal(tex["ship"][wall.y0:wall.y1, wall.x0:wall.x1, 3] == 0, info["wall_glass"]):
        problems.append("ship: cabin openings differ from the window glass mask")
    # window alignment: every window's angle and height ranges on the nose vs the wall
    nose_glass = info["ship_aux"]["glass"]
    fr = info["ship_frame"]
    wtheta = (np.arange(wall.w) + 0.5) / wall.w * TAU
    wy = geo.cabin_ceiling - (geo.cabin_ceiling - geo.cabin_floor) * (np.arange(wall.h) + 0.5) / wall.h
    worst_deg, worst_m = 0.0, 0.0
    _, _, _, nid = window_masks(fr.theta, fr.y, geo, window_list())
    _, _, _, wid = window_masks(wtheta, wy, geo, window_list())
    for i in range(len(window_list())):
        ny, nx = np.nonzero(nid == i)
        wyy, wxx = np.nonzero(wid == i)
        if not len(ny) or not len(wyy):
            problems.append(f"window {i} missing on the {'nose' if not len(ny) else 'wall'}")
            continue
        a_n = np.degrees(wrap_pi(fr.theta[nx] - math.pi))
        a_w = np.degrees(wrap_pi(wtheta[wxx] - math.pi))
        worst_deg = max(worst_deg, abs(a_n.min() - a_w.min()), abs(a_n.max() - a_w.max()))
        worst_m = max(worst_m, abs(fr.y[ny].min() - wy[wyy].min()), abs(fr.y[ny].max() - wy[wyy].max()))
    info["window_alignment"] = (worst_deg, worst_m)
    nose_px_deg = 360 / fr.W
    if worst_deg > 360 / wall.w + nose_px_deg or worst_m > 0.12:
        problems.append(f"windows misaligned: {worst_deg:.2f} deg, {worst_m:.3f} m")
    del nose_glass
    # overlays
    fa = tex["ship_frost"][..., 3]
    hull = np.zeros(fa.shape, bool)
    b = L["SHIP_BARREL"]
    hull[b.y0:b.y1, b.x0:b.x1] = True
    if (fa[~hull] != 0).any():
        problems.append("ship_frost: alpha outside the barrel")
    bf = tex["booster_frost"][..., 3]
    bb = L["BOOSTER_BARREL"]
    hull = np.zeros(bf.shape, bool)
    hull[bb.y0:bb.y1, bb.x0:bb.x1] = True
    if (bf[~hull] != 0).any():
        problems.append("booster_frost: alpha outside the barrel")
    for nm, fr_, tanks, reg in (("ship_frost", info["ship_frame"], SHIP_TANKS, L["SHIP_BARREL"]),
                                ("booster_frost", info["booster_frame"], BOOSTER_TANKS, L["BOOSTER_BARREL"])):
        a = tex[nm][..., 3]
        lo = min(v[0] for v in tanks.values())
        hi = max(v[1] for v in tanks.values())
        ys = fr_.y if nm == "booster_frost" else fr_.y[L["SHIP_NOSE"].h:]
        sub = a[reg.y0:reg.y1, reg.x0:reg.x1]
        outside = (ys < lo - 0.8) | (ys > hi + 0.8)
        if (sub[outside] != 0).any():
            problems.append(f"{nm}: frost outside the tank spans")
        mid = (ys > lo + 2.5) & (ys < hi - 2.5)
        special = info["ship_aux"]["tiled"][L["SHIP_NOSE"].h:] if nm == "ship_frost" else info["booster_aux"]["chine"]
        rows = sub[mid]
        info[nm + "_mid_alpha"] = (float(np.median(rows[~special[mid]])), float(np.median(rows[special[mid]])))
    la = tex["ship_lights"][..., 3]
    if not set(np.unique(la).tolist()) <= {0, 255}:
        problems.append("ship_lights: partial alpha")
    for nm in ("SHIP_CABIN_WALL", "SHIP_CABIN_FLOOR", "SHIP_CABIN_CEILING", "SHIP_CABIN_SEAT"):
        r = L[nm]
        if (la[r.y0:r.y1, r.x0:r.x1] != 0).any():
            problems.append(f"ship_lights: lit pixels in {nm}")
    # wrap seams on lathe regions
    seams = {}
    lam_ok = info["ship_frame"].r / geo.R >= 0.85
    for nm in LATHE_REGIONS:
        img = tex["ship"] if nm.startswith("SHIP") else tex["booster"]
        rows = lam_ok[:L["SHIP_NOSE"].h] if nm == "SHIP_NOSE" else None
        seams[nm] = seam_ratio(img, L[nm], rows)
        if seams[nm] > 1.5:
            problems.append(f"{nm}: seam ratio {seams[nm]:.2f} at s = 0/1")
    info["seams"] = seams
    for nm, arr in items.items():
        if arr.shape != (16, 16, 4):
            problems.append(f"item {nm}: size")
        if not set(np.unique(arr[..., 3]).tolist()) <= {0, 255}:
            problems.append(f"item {nm}: partial alpha")
        ncol = len({tuple(c) for c in arr.reshape(-1, 4) if c[3] > 0})
        info[f"item_{nm}_colours"] = ncol
        if not 6 <= ncol <= 12:
            problems.append(f"item {nm}: {ncol} colours")
    return problems


def print_design_numbers(geo, L):
    """The numbers chosen here, for docs/SCIENCE.md."""
    se, be = ring_edges_ship(geo), ring_edges_booster(geo)
    nb = int(np.searchsorted(se, geo.ship_barrel + 1e-6)) - 1
    nf = int(np.searchsorted(be, BOOSTER_FORWARD_Y - 1e-6))
    bar, nose = L["SHIP_BARREL"], L["SHIP_NOSE"]
    print("  design numbers:")
    print(f"    steel rings: ship barrel {nb} x {se[1] - se[0]:.3f} m, nose {len(se) - 1 - nb} x {se[-1] - se[-2]:.3f} m "
          f"of arc; booster {nf} x {be[1] - be[0]:.3f} m to {BOOSTER_FORWARD_Y} m, {len(be) - 1 - nf} x "
          f"{be[-1] - be[-2]:.3f} m above")
    print(f"    tiles: {TILE_PX} px pitch = {TILE_PX * TAU * geo.R / bar.w:.3f} m, rows {TILE_ROWS} px = "
          f"{TILE_ROWS * geo.ship_barrel / bar.h:.3f} m (barrel) / {TILE_ROWS * geo.nose_len / nose.h:.3f} m (nose); "
          f"half-arc {TILE_HALF_ARC_DEG} deg (s < {TILE_HALF_ARC_DEG / 360:.3f} or > {1 - TILE_HALF_ARC_DEG / 360:.3f}); "
          f"fore-flap hinges to {FORE_FLAP_TILE_DEG} deg over {geo.fore_y0 - 0.25:.2f}-{geo.fore_y1 + 0.25:.2f} m; "
          f"nose fully tiled above {geo.ship_height - NOSE_FULL_TILE_M:.1f} m; bare below {AFT_STEEL_M} m")
    wins = window_list()
    angles = [math.degrees(w[0]) for w in wins[::2]]
    yc, w, h, _ = WINDOW_MAIN
    yu, wu, hu, _ = WINDOW_UPPER
    t0, t1 = geo.nose_t_at_height(yc + h / 2), geo.nose_t_at_height(yc - h / 2)
    u0, u1 = geo.nose_t_at_height(yu + hu / 2), geo.nose_t_at_height(yu - hu / 2)
    half = math.degrees(w / 2 / float(geo.hull_radius(yc)))
    print(f"    windows: {WINDOW_COUNT} x {w} x {h} m at {yc - h / 2:.2f}-{yc + h / 2:.2f} m (nose t {t0:.4f}-{t1:.4f}) and "
          f"{WINDOW_COUNT} x {wu} x {hu} m at {yu - hu / 2:.2f}-{yu + hu / 2:.2f} m (nose t {u0:.4f}-{u1:.4f}); centres "
          f"{angles[0]:.1f}-{angles[-1]:.1f} deg every {WINDOW_PITCH_DEG:.3f} deg; glass spans "
          f"{angles[0] - half:.1f}-{angles[-1] + half:.1f} deg")
    print(f"    frost: ship {SHIP_TANKS}, booster {BOOSTER_TANKS}; cabin floor disc R {geo.floor_r:.3f} m, ceiling "
          f"disc R {geo.ceiling_r:.3f} m")


def save_png(arr, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(arr, "RGBA").save(path, optimize=True)


# ===================================================================================================================
# Preview: a port of MeshBuilder / StarshipGeometry and a small z-buffered rasteriser
# ===================================================================================================================

LODS = {"HIGH": (64, 18, 16, 6, True), "MEDIUM": (32, 10, 10, 4, True), "LOW": (16, 6, 6, 2, False)}


class Mesh:
    """Quads (4 vertices of x, y, z, nx, ny, nz, U, V in atlas pixels) wound like MeshBuilder."""

    def __init__(self, tex):
        self.tex = tex
        self.q = []

    def quad(self, a, b, c, d, o):
        u, v = b[:3] - a[:3], d[:3] - a[:3]
        cr = np.cross(u, v)
        if cr @ cr < 1e-14:
            u = c[:3] - a[:3]
            cr = np.cross(u, v)
            if cr @ cr < 1e-14:
                v, u = c[:3] - b[:3], b[:3] - a[:3]
                cr = np.cross(u, v)
        self.q.append(np.stack([a, b, c, d]) if cr @ np.asarray(o, float) >= 0 else np.stack([a, d, c, b]))

    @staticmethod
    def V(p, n, reg, s, t):
        return np.array([p[0], p[1], p[2], n[0], n[1], n[2], reg.u(s), reg.v(t)], float)

    def lathe(self, r, y, t, seg, reg, inward, cx, cz, th_off=0.0):
        n = len(r)
        nr, ny = np.zeros(n), np.zeros(n)
        for i in range(n):
            sr = sy = 0.0
            if i > 0:
                dr, dy = r[i] - r[i - 1], y[i] - y[i - 1]
                ln = math.hypot(dr, dy)
                sr, sy = sr + dy / ln, sy - dr / ln
            if i < n - 1:
                dr, dy = r[i + 1] - r[i], y[i + 1] - y[i]
                ln = math.hypot(dr, dy)
                sr, sy = sr + dy / ln, sy - dr / ln
            ln = math.hypot(sr, sy)
            nr[i], ny[i] = sr / ln, sy / ln
            if inward:
                nr[i], ny[i] = -nr[i], -ny[i]
        for j in range(seg):
            s0, s1 = j / seg, (j + 1) / seg
            th0, th1 = th_off + TAU * s0, th_off + TAU * s1
            sn0, cs0, sn1, cs1 = math.sin(th0), math.cos(th0), math.sin(th1), math.cos(th1)
            for i in range(n - 1):
                a = self.V((cx + r[i] * sn0, y[i], cz + r[i] * cs0), (nr[i] * sn0, ny[i], nr[i] * cs0), reg, s0, t[i])
                b = self.V((cx + r[i] * sn1, y[i], cz + r[i] * cs1), (nr[i] * sn1, ny[i], nr[i] * cs1), reg, s1, t[i])
                c = self.V((cx + r[i + 1] * sn1, y[i + 1], cz + r[i + 1] * cs1),
                           (nr[i + 1] * sn1, ny[i + 1], nr[i + 1] * cs1), reg, s1, t[i + 1])
                d = self.V((cx + r[i + 1] * sn0, y[i + 1], cz + r[i + 1] * cs0),
                           (nr[i + 1] * sn0, ny[i + 1], nr[i + 1] * cs0), reg, s0, t[i + 1])
                mid = (th0 + th1) / 2
                mnr, mny = (nr[i] + nr[i + 1]) / 2, (ny[i] + ny[i + 1]) / 2
                self.quad(a, b, c, d, (mnr * math.sin(mid), mny, mnr * math.cos(mid)))

    def disc(self, outer, inner, y, seg, reg, up, cx, cz):
        ny = 1.0 if up else -1.0
        for j in range(seg):
            th0, th1 = TAU * j / seg, TAU * (j + 1) / seg
            xs = [outer * math.sin(th0), outer * math.sin(th1), inner * math.sin(th1), inner * math.sin(th0)]
            zs = [outer * math.cos(th0), outer * math.cos(th1), inner * math.cos(th1), inner * math.cos(th0)]
            vs = [self.V((cx + xs[k], y, cz + zs[k]), (0, ny, 0), reg, (xs[k] / outer + 1) / 2, (zs[k] / outer + 1) / 2)
                  for k in range(4)]
            self.quad(*vs, (0, ny, 0))

    def plate(self, outline, th_root, th_tip, span, hr, origin, sdir, hdir, ndir, preg, mreg, ereg):
        O, S, Hd, Nd = (np.asarray(v, float) for v in (origin, sdir, hdir, ndir))
        m = len(outline)
        plus, minus = [], []
        for a, b in outline:
            half = 0.5 * (th_root + (th_tip - th_root) * (a / span))
            p = O + S * a + Hd * b
            s, t = a / span, 1.0 - (b - hr[0]) / (hr[1] - hr[0])
            plus.append(self.V(p + Nd * half, Nd, preg, s, t))
            minus.append(self.V(p - Nd * half, -Nd, mreg, s, t))
        self.quad(*plus, Nd)
        self.quad(*minus, -Nd)
        ca = sum(p[0] for p in outline) / m
        cb = sum(p[1] for p in outline) / m
        for k in range(m):
            k2 = (k + 1) % m
            ea = (outline[k][0] + outline[k2][0]) / 2 - ca
            eb = (outline[k][1] + outline[k2][1]) / 2 - cb
            o = S * ea + Hd * eb
            o = o / np.linalg.norm(o)

            def e(v, s, t):
                return np.array([v[0], v[1], v[2], o[0], o[1], o[2], ereg.u(s), ereg.v(t)])
            self.quad(e(plus[k], 0, k / m), e(plus[k2], 0, (k + 1) / m), e(minus[k2], 1, (k + 1) / m),
                      e(minus[k], 1, k / m), o)

    def arrays(self):
        return np.array(self.q) if self.q else np.zeros((0, 4, 8))


def engine_bell(mb, lod, cx, cz, throat_y, exit_y, throat_r, exit_r, ph_r, ph_top, seg, outer, inner):
    rings = lod[3]
    r, y, t = [], [], []
    for i in range(rings + 1):
        s = i / rings
        flare = 1.0 - s ** 0.55
        r.append(throat_r + (exit_r - throat_r) * flare)
        y.append(exit_y + (throat_y - exit_y) * s)
        t.append(1.0 - s)
    mb.lathe(r, y, t, seg, outer, False, cx, cz)
    if not lod[4]:
        return
    mb.lathe(r, y, t, seg, inner, True, cx, cz)
    mb.lathe([throat_r, ph_r, ph_r, ph_r * 0.6], [throat_y, throat_y + 0.25, ph_top - 0.2, ph_top], [1.0, 0.8, 0.2, 0.0],
             max(6, seg // 2), outer, False, cx, cz)


def build_ship_mesh(geo, L, lod_name="HIGH", legs_deployed=False):
    lod = LODS[lod_name]
    c = geo.c
    R, B, Hn = geo.R, geo.ship_barrel, geo.nose_h
    parts = {}
    hull = Mesh("ship")
    by = [B * i / 4 for i in range(5)]
    hull.lathe([R] * 5, by, [1 - v / B for v in by], lod[0], L["SHIP_BARREL"], False, 0, 0)
    nr_, ny_, arc = [], [], [0.0]
    n = lod[1]
    for i in range(n + 1):
        f = i / n
        x = Hn * (1 - (1 - f) ** 1.6)
        r = math.sqrt(max(0.0, geo.rho ** 2 - x * x)) + R - geo.rho
        r = 0.0 if i == n else max(r, 0.35 * (1 - f))
        nr_.append(r)
        ny_.append(B + x)
        if i > 0:
            arc.append(arc[-1] + math.hypot(nr_[i] - nr_[i - 1], ny_[i] - ny_[i - 1]))
    hull.lathe(nr_, ny_, [1 - a / arc[-1] for a in arc], lod[0], L["SHIP_NOSE"], False, 0, 0)
    hull.disc(R, 0.0, 0.0, lod[0], L["SHIP_AFT_DISC"], False, 0, 0)
    parts["hull"] = hull
    cab = Mesh("ship")
    wr_n = max(2, n // 4)
    wy = [geo.cabin_floor + (geo.cabin_ceiling - geo.cabin_floor) * i / wr_n for i in range(wr_n + 1)]
    wr = [float(geo.hull_radius(v)) - geo.cabin_inset for v in wy]
    wt = [(geo.cabin_ceiling - v) / (geo.cabin_ceiling - geo.cabin_floor) for v in wy]
    cab.lathe(wr, wy, wt, lod[0], L["SHIP_CABIN_WALL"], True, 0, 0)
    cab.disc(wr[0], 0.0, geo.cabin_floor, lod[0], L["SHIP_CABIN_FLOOR"], True, 0, 0)
    cab.disc(wr[-1], 0.0, geo.cabin_ceiling, lod[0], L["SHIP_CABIN_CEILING"], False, 0, 0)
    for a in geo.seat_angles():
        sn, cs = math.sin(a), math.cos(a)
        radial, tangent, up = (sn, 0, cs), (cs, 0, -sn), (0, 1, 0)
        sr, sh = c["SEAT_RING_RADIUS"], c["SEAT_HEIGHT"]
        so = (sr * sn - 0.35 * sn, geo.cabin_floor, sr * cs - 0.35 * cs)
        cab.plate([(0, 0), (0, sh), (0.7, sh), (0.7, 0)], 0.7, 0.7, 0.7, (0, sh), so, radial, up, tangent,
                  L["SHIP_CABIN_SEAT"], L["SHIP_CABIN_SEAT"], L["SHIP_CABIN_SEAT"])
        bh = sh + 1.0
        bo = (so[0] - 0.15 * sn, geo.cabin_floor, so[2] - 0.15 * cs)
        cab.plate([(0, 0), (0, bh), (0.15, bh), (0.15, 0)], 0.7, 0.7, 0.15, (0, bh), bo, radial, up, tangent,
                  L["SHIP_CABIN_SEAT"], L["SHIP_CABIN_SEAT"], L["SHIP_CABIN_SEAT"])
    parts["cabin"] = cab

    def flap(name, outline, hinge_deg, yb, yt, rb, rt, th_r, th_t, wreg, lreg, ereg):
        a = math.radians(hinge_deg)
        sn, cs = math.sin(a), math.cos(a)
        radial = (sn, 0, cs)
        if yt > yb:
            origin = (rb * sn, yb, rb * cs)
            dy, dr = yt - yb, rt - rb
            ln = math.hypot(dy, dr)
            hdir = (dr / ln * sn, dy / ln, dr / ln * cs)
        else:
            origin, hdir = (rb * sn, 0, rb * cs), (0, 1, 0)
        normal = (cs, 0, -sn)
        plus_w = normal[2] > 0
        span = max(p[0] for p in outline)
        hmin, hmax = min(p[1] for p in outline), max(p[1] for p in outline)
        m = Mesh("ship")
        m.plate(outline, th_r, th_t, span, (hmin, hmax), origin, radial, hdir, normal,
                wreg if plus_w else lreg, lreg if plus_w else wreg, ereg)
        parts[name] = m

    aft = [(0.0, 1.2), (0.0, 13.5), (4.3, 12.1), (4.3, 2.5)]
    for nm, dg in (("aft_r", 90.0), ("aft_l", -90.0)):
        flap(nm, aft, dg, 0, 0, R, R, 0.6, 0.28, L["SHIP_AFT_FLAP_WINDWARD"], L["SHIP_AFT_FLAP_LEEWARD"],
             L["SHIP_AFT_FLAP_EDGE"])
    fb, ft = B + 2.0, B + 9.2
    rb = math.sqrt(geo.rho ** 2 - 4.0) + R - geo.rho
    rt = math.sqrt(geo.rho ** 2 - 9.2 ** 2) + R - geo.rho
    hl = math.hypot(ft - fb, rb - rt)
    fore = [(0.0, 0.0), (0.0, hl), (2.6, hl - 1.6), (2.6, 1.0)]
    for nm, dg in (("fore_r", 115.0), ("fore_l", -115.0)):
        flap(nm, fore, dg, fb, ft, rb, rt, 0.45, 0.22, L["SHIP_FORE_FLAP_WINDWARD"], L["SHIP_FORE_FLAP_LEEWARD"],
             L["SHIP_FORE_FLAP_EDGE"])
    eng = Mesh("ship")
    for a in c["SL_ANGLES_DEG"]:
        ar = math.radians(a)
        engine_bell(eng, lod, c["SL_RING_RADIUS"] * math.sin(ar), c["SL_RING_RADIUS"] * math.cos(ar), c["SL_THROAT_Y"],
                    c["SL_EXIT_Y"], 0.24, c["SL_EXIT_RADIUS"], 0.62, c["SL_GIMBAL_Y"] + 0.5, lod[2],
                    L["SHIP_ENGINE_SL_OUTER"], L["SHIP_ENGINE_SL_INNER"])
    for a in c["VAC_ANGLES_DEG"]:
        ar = math.radians(a)
        engine_bell(eng, lod, c["VAC_RING_RADIUS"] * math.sin(ar), c["VAC_RING_RADIUS"] * math.cos(ar),
                    c["VAC_THROAT_Y"], c["VAC_EXIT_Y"], 0.26, c["VAC_EXIT_RADIUS"], 0.62, c["VAC_THROAT_Y"] + 1.4,
                    lod[2] + 4, L["SHIP_ENGINE_VAC_OUTER"], L["SHIP_ENGINE_VAC_INNER"])
    parts["engines"] = eng
    legs = Mesh("ship")
    for a in c["LEG_ANGLES_DEG"]:
        ar = math.radians(a)
        sn, cs = math.sin(ar), math.cos(ar)
        pr = R + 0.12
        origin = (pr * sn, c["LEG_PIVOT_Y"], pr * cs)
        radial, down, tangent = (sn, 0, cs), (0, -1, 0), (cs, 0, -sn)
        m = Mesh("ship")
        ll = c["LEG_LENGTH"]
        m.plate([(0.0, -0.35), (0.0, 0.35), (ll, 0.3), (ll, -0.3)], 0.32, 0.24, ll, (-0.35, 0.35), origin, down,
                tangent, radial, L["SHIP_LEG"], L["SHIP_LEG"], L["SHIP_LEG"])
        foot = (origin[0], origin[1] - ll, origin[2])
        m.plate([(-0.55, -0.55), (-0.55, 0.55), (0.55, 0.55), (0.55, -0.55)], 0.18, 0.18, 1.1, (-0.55, 0.55), foot,
                radial, tangent, (0, 1, 0), L["SHIP_LEG_FOOT"], L["SHIP_LEG_FOOT"], L["SHIP_LEG_FOOT"])
        q = m.arrays()
        if legs_deployed and len(q):
            q = q.copy()
            q[..., 1] -= c["LEG_EXTENSION"]
            axis = np.array([-cs, 0.0, sn])
            piv = np.array([origin[0], origin[1] - c["LEG_EXTENSION"], origin[2]])
            Rm = rotation_matrix(axis, math.radians(c["LEG_DEPLOY_DEG"]))
            q[..., :3] = (q[..., :3] - piv) @ Rm.T + piv
            q[..., 3:6] = q[..., 3:6] @ Rm.T
        legs.q.extend(list(q))
    parts["legs"] = legs
    return parts


def build_booster_mesh(geo, L, lod_name="HIGH"):
    lod = LODS[lod_name]
    c = geo.c
    R, B = geo.R, geo.booster_barrel
    parts = {}
    hull = Mesh("booster")
    ys = [B * i / 6 for i in range(7)]
    hull.lathe([R] * 7, ys, [1 - v / B for v in ys], lod[0], L["BOOSTER_BARREL"], False, 0, 0)
    hull.disc(R, 0.0, 0.0, lod[0], L["BOOSTER_AFT_DISC"], False, 0, 0)
    parts["hull"] = hull
    ring = Mesh("booster")
    ring.lathe([R, R], [B, B + geo.ring_h], [1, 0], lod[0], L["BOOSTER_HOT_STAGE_RING"], False, 0, 0)
    ring.disc(R, 0.0, B + geo.ring_h, lod[0], L["BOOSTER_HOT_STAGE_RING_TOP"], True, 0, 0)
    parts["ring"] = ring
    fins = Mesh("booster")
    h = c["GRID_FIN_HEIGHT"] / 2
    for a in c["GRID_FIN_ANGLES_DEG"]:
        ar = math.radians(a)
        sn, cs = math.sin(ar), math.cos(ar)
        origin = (R * sn, c["GRID_FIN_Y"] + h, R * cs)
        fins.plate([(0.0, -h * 0.85), (0.0, h * 0.85), (c["GRID_FIN_SPAN"], h), (c["GRID_FIN_SPAN"], -h)], 0.7, 0.7,
                   c["GRID_FIN_SPAN"], (-h, h), origin, (sn, 0, cs), (cs, 0, -sn), (0, 1, 0), L["BOOSTER_GRID_FIN"],
                   L["BOOSTER_GRID_FIN"], L["BOOSTER_GRID_FIN_EDGE"])
    parts["fins"] = fins
    eng = Mesh("booster")
    for ri, (rr_, cnt, er) in enumerate(c["BOOSTER_RINGS"]):
        cnt = int(cnt)
        for k in range(cnt):
            a = TAU * (k + (0.5 if ri == 1 else 0.0)) / cnt
            engine_bell(eng, lod, rr_ * math.sin(a), rr_ * math.cos(a), c["BOOSTER_ENGINE_THROAT_Y"],
                        c["BOOSTER_ENGINE_EXIT_Y"], 0.22, er, 0.5, c["BOOSTER_ENGINE_THROAT_Y"] + 0.6,
                        max(6, lod[2] - 4), L["BOOSTER_ENGINE_OUTER"], L["BOOSTER_ENGINE_INNER"])
    parts["engines"] = eng
    return parts


def rotation_matrix(axis, ang):
    k = np.asarray(axis, float)
    k = k / np.linalg.norm(k)
    K = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
    return np.eye(3) + math.sin(ang) * K + (1 - math.cos(ang)) * (K @ K)


class Camera:
    def __init__(self, eye, target, ppm=None, fov_deg=None, size=(512, 512), center=None):
        self.eye = np.asarray(eye, float)
        f = np.asarray(target, float) - self.eye
        f = f / np.linalg.norm(f)
        right = np.cross(f, [0.0, 1.0, 0.0])
        if np.linalg.norm(right) < 1e-6:
            right = np.array([1.0, 0.0, 0.0])
        right = right / np.linalg.norm(right)
        up = np.cross(right, f)
        self.Rm = np.stack([right, up, f])
        self.ortho = fov_deg is None
        self.W, self.H = size
        self.cx, self.cy = (self.W / 2, self.H / 2) if center is None else center
        self.scale = ppm if self.ortho else (self.W / 2) / math.tan(math.radians(fov_deg) / 2)
        self.right, self.up, self.fwd = right, up, f


def ortho_view(azimuth, elevation, target, ppm, size, center=None):
    az, el = math.radians(azimuth), math.radians(elevation)
    d = np.array([math.sin(az) * math.cos(el), math.sin(el), math.cos(az) * math.cos(el)])
    return Camera(np.asarray(target, float) + d * 500.0, target, ppm=ppm, size=size, center=center)


def gather(parts, offset=(0, 0, 0), fullbright=()):
    out = []
    for name, m in parts.items():
        q = m.arrays()
        if not len(q):
            continue
        q = q.copy()
        q[..., :3] += np.asarray(offset, float)
        out.append((q, m.tex, name in fullbright))
    return out


def render(batches, cam, textures, light=None, bg=(36, 40, 48), cull=True, frost=None, emissive=None, night=False,
           sky=None, ambient=0.38):
    """Z-buffered rasteriser with nearest texel sampling, alpha test (< 128 discarded), back-face culling like the
    game, smooth Lambert shading (or full-bright for the cabin) and optional frost / emissive overlays."""
    W, H = cam.W, cam.H
    col = np.zeros((H, W, 3))
    if sky is not None:
        tt = np.linspace(0, 1, H)[:, None]
        col[:] = srgb_to_lin(np.asarray(sky[0], float) * (1 - tt) + np.asarray(sky[1], float) * tt)[:, None, :]
    else:
        col[:] = srgb_to_lin(np.asarray(bg, float))
    depth = np.full((H, W), np.inf)
    if light is None:
        light = -cam.fwd * 0.62 - cam.right * 0.45 + cam.up * 0.64
    light = np.asarray(light, float)
    light = light / np.linalg.norm(light)
    tex_lin = {k: (srgb_to_lin(v[..., :3]), v[..., 3]) for k, v in textures.items()}
    frost_lin = {k: (srgb_to_lin(v[..., :3]), v[..., 3] / 255.0) for k, v in (frost or {}).items()}
    emis_lin = {k: (srgb_to_lin(v[..., :3]), v[..., 3]) for k, v in (emissive or {}).items()}
    for quads, tex, fullbright in batches:
        P = quads[..., :3]
        Nn = quads[..., 3:6]
        UV = quads[..., 6:8]
        tris = [(0, 1, 2), (0, 2, 3)]
        pc = (P - cam.eye) @ cam.Rm.T                     # camera space: x right, y up, z forward
        for tri in tris:
            idx = list(tri)
            Pc = pc[:, idx]
            Nt = Nn[:, idx]
            Ut = UV[:, idx]
            if cam.ortho:
                polys = [(Pc, Nt, Ut)]
            else:
                polys = clip_near(Pc, Nt, Ut, 0.05)
            for Pc_, Nt_, Ut_ in polys:
                _raster(Pc_, Nt_, Ut_, cam, col, depth, tex_lin[tex], frost_lin.get(tex), emis_lin.get(tex), light,
                        cull, fullbright, night, ambient)
    return lin_to_srgb8(col)


def clip_near(Pc, Nt, Ut, near):
    """Clip triangles (arrays of shape (N, 3, k)) against z > near; returns a list of (P, N, UV) triangle arrays."""
    z = Pc[..., 2]
    inside = z > near
    alln = inside.all(1)
    out = [(Pc[alln], Nt[alln], Ut[alln])]
    part = inside.any(1) & ~alln
    if part.any():
        newP, newN, newU = [], [], []
        for i in np.nonzero(part)[0]:
            poly = []
            for k in range(3):
                a, b = k, (k + 1) % 3
                pa, pb = Pc[i, a], Pc[i, b]
                ia, ib = pa[2] > near, pb[2] > near
                if ia:
                    poly.append((pa, Nt[i, a], Ut[i, a]))
                if ia != ib:
                    t = (near - pa[2]) / (pb[2] - pa[2])
                    poly.append((pa + (pb - pa) * t, Nt[i, a] + (Nt[i, b] - Nt[i, a]) * t, Ut[i, a] + (Ut[i, b] - Ut[i, a]) * t))
            for k in range(1, len(poly) - 1):
                tri = [poly[0], poly[k], poly[k + 1]]
                newP.append(np.stack([v[0] for v in tri]))
                newN.append(np.stack([v[1] for v in tri]))
                newU.append(np.stack([v[2] for v in tri]))
        if newP:
            out.append((np.array(newP), np.array(newN), np.array(newU)))
    return out


def srgb_to_lin(c):
    c = np.asarray(c, float) / 255.0
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def lin_to_srgb8(c):
    c = np.clip(c, 0, 1)
    s = np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1 / 2.4) - 0.055)
    return np.clip(np.round(s * 255), 0, 255).astype(np.uint8)


def _raster(Pc, Nt, Ut, cam, col, depth, tex, frost, emis, light, cull, fullbright, night, ambient):
    if not len(Pc):
        return
    W, H = cam.W, cam.H
    if cam.ortho:
        sx = cam.cx + cam.scale * Pc[..., 0]
        sy = cam.cy - cam.scale * Pc[..., 1]
        iz = np.ones(Pc.shape[:2])
    else:
        sx = cam.cx + cam.scale * Pc[..., 0] / Pc[..., 2]
        sy = cam.cy - cam.scale * Pc[..., 1] / Pc[..., 2]
        iz = 1.0 / Pc[..., 2]
    area = (sx[:, 1] - sx[:, 0]) * (sy[:, 2] - sy[:, 0]) - (sx[:, 2] - sx[:, 0]) * (sy[:, 1] - sy[:, 0])
    front = area < 0                                      # counter-clockwise in a y-up frame
    keep = (front if cull else np.ones_like(front)) & (np.abs(area) > 1e-9)
    texrgb, texa = tex
    th, tw = texa.shape
    for i in np.nonzero(keep)[0]:
        x0 = max(int(np.floor(sx[i].min())), 0)
        x1 = min(int(np.ceil(sx[i].max())), W - 1)
        y0 = max(int(np.floor(sy[i].min())), 0)
        y1 = min(int(np.ceil(sy[i].max())), H - 1)
        if x1 < x0 or y1 < y0:
            continue
        gy, gx = np.mgrid[y0:y1 + 1, x0:x1 + 1].astype(float)
        gx += 0.5
        gy += 0.5
        X, Y = sx[i], sy[i]
        d = area[i]
        w0 = ((X[1] - gx) * (Y[2] - gy) - (X[2] - gx) * (Y[1] - gy)) / d
        w1 = ((X[2] - gx) * (Y[0] - gy) - (X[0] - gx) * (Y[2] - gy)) / d
        w2 = 1.0 - w0 - w1
        m = (w0 >= -1e-9) & (w1 >= -1e-9) & (w2 >= -1e-9)
        if not m.any():
            continue
        zi = iz[i]
        q = w0 * zi[0] + w1 * zi[1] + w2 * zi[2]
        z = (w0 * Pc[i, 0, 2] + w1 * Pc[i, 1, 2] + w2 * Pc[i, 2, 2]) if cam.ortho else 1.0 / q
        sub = depth[y0:y1 + 1, x0:x1 + 1]
        m &= z < sub
        if not m.any():
            continue
        b0, b1, b2 = (w0 * zi[0] / q)[m], (w1 * zi[1] / q)[m], (w2 * zi[2] / q)[m]
        U = b0 * Ut[i, 0, 0] + b1 * Ut[i, 1, 0] + b2 * Ut[i, 2, 0]
        V = b0 * Ut[i, 0, 1] + b1 * Ut[i, 1, 1] + b2 * Ut[i, 2, 1]
        iu = np.clip(np.floor(U).astype(int), 0, tw - 1)
        iv = np.clip(np.floor(V).astype(int), 0, th - 1)
        a = texa[iv, iu]
        ok = a >= 128
        if not ok.any():
            continue
        rgb = texrgb[iv, iu]
        if frost is not None:
            frgb, fa = frost
            fa_ = fa[iv, iu][:, None]
            rgb = rgb * (1 - fa_) + frgb[iv, iu] * fa_
        if fullbright:
            shade = np.ones(len(U))
        else:
            n = b0[:, None] * Nt[i, 0] + b1[:, None] * Nt[i, 1] + b2[:, None] * Nt[i, 2]
            n = n / np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-9)
            nd = n @ cam.Rm.T
            n = np.where((nd[:, 2:3] > 0), -n, n) if not cull else n
            shade = ambient + (1 - ambient) * np.clip(n @ light, 0, 1)
            if night:
                shade = shade * 0.16
        rgb = rgb * shade[:, None]
        if emis is not None:
            lit = emis[1][iv, iu] >= 128
            rgb = np.where(lit[:, None], emis[0][iv, iu], rgb)
        mm = np.zeros_like(m)
        mm[m] = ok
        sub_c = col[y0:y1 + 1, x0:x1 + 1]
        sub_c[mm] = rgb[ok]
        sub[mm] = z[mm] if np.ndim(z) else z
    return


# ----------------------------------------------------------------------------------------------- preview sheets

def font(size):
    try:
        return ImageFont.load_default(size=size)
    except TypeError:
        return ImageFont.load_default()


def label(img, xy, text, size=14, fill=(230, 230, 230)):
    ImageDraw.Draw(img).text(xy, text, font=font(size), fill=fill)


def box_down(arr, k):
    """Box-filter downsample by k in linear light (what a mip chain approximates)."""
    h, w = arr.shape[0] // k * k, arr.shape[1] // k * k
    lin = srgb_to_lin(arr[:h, :w, :3])
    lin = lin.reshape(h // k, k, w // k, k, 3).mean(axis=(1, 3))
    return lin_to_srgb8(lin)


def checker_bg(w, h, cell=8):
    yy, xx = np.mgrid[0:h, 0:w]
    m = ((yy // cell + xx // cell) % 2).astype(bool)
    out = np.zeros((h, w, 3), np.uint8)
    out[:] = (52, 54, 60)
    out[m] = (66, 68, 74)
    return out


def comp_over(rgba, bg):
    a = rgba[..., 3:4] / 255.0
    return (rgba[..., :3] * a + bg * (1 - a)).astype(np.uint8)


def preview_atlases(tex, L, path):
    k = 2
    pad = 24
    panels = [("ship.png", tex["ship"], "SHIP", "base"), ("booster.png", tex["booster"], "BOOSTER", "base"),
              ("ship_frost.png", tex["ship_frost"], "SHIP", "overlay"),
              ("booster_frost.png", tex["booster_frost"], "BOOSTER", "overlay"),
              ("ship_lights.png", tex["ship_lights"], "SHIP", "lights")]
    imgs = []
    for title, arr, texname, kind in panels:
        h, w = arr.shape[:2]
        if kind == "base":
            bg = checker_bg(w, h, 16)
            rgb = comp_over(arr, bg)
        elif kind == "overlay":
            rgb = comp_over(arr, np.full((h, w, 3), (40, 44, 52), np.uint8))
        else:
            rgb = comp_over(arr, np.full((h, w, 3), (16, 16, 20), np.uint8))
        im = Image.fromarray(rgb).resize((w // k, h // k), Image.BOX)
        dr = ImageDraw.Draw(im)
        for r in L.of(texname):
            dr.rectangle([r.x0 // k, r.y0 // k, (r.x1 - 1) // k, (r.y1 - 1) // k], outline=(255, 64, 200))
            if kind == "base" and r.w >= 40 and r.h >= 24:
                dr.text((r.x0 // k + 2, r.y0 // k + 1), r.name.replace("SHIP_", "").replace("BOOSTER_", "").lower(),
                        font=font(9), fill=(255, 240, 120))
        imgs.append((title, im))
    W = sum(im.width for _, im in imgs) + pad * (len(imgs) + 1)
    H = max(im.height for _, im in imgs) + pad * 2 + 10
    sheet = Image.new("RGB", (W, H), (28, 30, 34))
    x = pad
    for title, im in imgs:
        sheet.paste(im, (x, pad + 10))
        label(sheet, (x, 6), f"{title} (1/{k})", 13)
        x += im.width + pad
    sheet.save(path, optimize=True)


def stack_batches(geo, L, lod="HIGH", legs=False, cabin=True):
    ship = build_ship_mesh(geo, L, lod, legs_deployed=legs)
    if not cabin:
        ship.pop("cabin")
    boost = build_booster_mesh(geo, L, lod)
    return gather(boost) + gather(ship, (0, geo.booster_height, 0), fullbright=("cabin",))


def preview_views(tex, geo, L, path):
    textures = {"ship": tex["ship"], "booster": tex["booster"]}
    batches = stack_batches(geo, L, "HIGH", cabin=False)
    ppm = 16
    top = geo.booster_height + geo.ship_height
    Hpx = int((top + 4) * ppm)
    Wpx = int(24 * ppm)
    views = [("windward (az 0)", 0, 0), ("side +X (az 90)", 90, 0), ("leeward (az 180)", 180, 0),
             ("3/4 (az 35, el 10)", 35, 10)]
    near, far = [], []
    for title, az, el in views:
        cam = ortho_view(az, el, (0, top / 2, 0), ppm, (Wpx, Hpx), center=(Wpx / 2, Hpx / 2 + 0.5 * ppm))
        img = render(batches, cam, textures)
        near.append((title, box_down(img, 2)))
        far.append((title, box_down(img, 8)))
        print(f"    view {title} rendered")
    pad = 16
    nw, nh = near[0][1].shape[1], near[0][1].shape[0]
    up = 4
    W = pad + len(near) * (nw + pad) + 30 + len(far) * (far[0][1].shape[1] * up + pad)
    H = nh + 60
    sheet = Image.new("RGB", (W, H), (28, 30, 34))
    x = pad
    label(sheet, (pad, 4), "near: 8 px/m (1/2 texel density, box-filtered like mip 1)", 14)
    for title, im in near:
        sheet.paste(Image.fromarray(im), (x, 40))
        label(sheet, (x, 22), title, 12)
        x += nw + pad
    x += 30
    label(sheet, (x, 4), "far: 2 px/m (1/8, ~130 blocks away at 70 deg fov), shown x4", 14)
    for title, im in far:
        big = Image.fromarray(im).resize((im.shape[1] * up, im.shape[0] * up), Image.NEAREST)
        sheet.paste(big, (x, 40))
        label(sheet, (x, 22), title, 12)
        x += big.width + pad
    sheet.save(path, optimize=True)


def preview_closeups(tex, geo, L, path):
    textures = {"ship": tex["ship"], "booster": tex["booster"]}
    frost = {"ship": tex["ship_frost"], "booster": tex["booster_frost"]}
    ship = build_ship_mesh(geo, L, "HIGH")
    ship.pop("cabin")
    ship_b = gather(ship)
    ship_legs = build_ship_mesh(geo, L, "HIGH", legs_deployed=True)
    ship_legs.pop("cabin")
    boost_b = gather(build_booster_mesh(geo, L, "HIGH"))
    tiles = []
    S = 2
    # 1. nose, leeward: windows
    cam = ortho_view(180, 0, (0, 42.5, 0), 16 * S, (16 * S * 15, 16 * S * 21))
    tiles.append(("ship nose, leeward (windows), 32 px/m", render(ship_b, cam, textures)))
    # 2. nose, windward / side
    cam = ortho_view(70, 6, (0, 43.5, 0), 16 * S, (16 * S * 15, 16 * S * 21))
    tiles.append(("ship nose, az 70 (tile edge, fore flap), 32 px/m", render(ship_b, cam, textures)))
    # 3. aft, windward, from below with legs deployed
    cam = ortho_view(30, -18, (0, 4.0, 0), 16 * S, (16 * S * 18, 16 * S * 16))
    tiles.append(("ship aft, az 30 el -18, legs deployed", render(gather(ship_legs), cam, textures)))
    # 4. ship engines from below
    cam = ortho_view(20, -70, (0, 0.0, 0), 16 * S, (16 * S * 13, 16 * S * 13))
    tiles.append(("ship aft bulkhead and engines from below", render(gather(ship_legs), cam, textures)))
    # 5. booster top: hot-staging ring and grid fins
    cam = ortho_view(25, 22, (0, 67.5, 0), 16 * S, (16 * S * 18, 16 * S * 16))
    tiles.append(("booster top: hot-stage ring, grid fins", render(boost_b, cam, textures)))
    # 6. booster aft from below
    cam = ortho_view(30, -55, (0, 0.5, 0), 16 * S, (16 * S * 13, 16 * S * 13))
    tiles.append(("booster engines from below", render(boost_b, cam, textures)))
    # 7. booster section with frost and the chine
    cam = ortho_view(70, 0, (0, 45.5, 0), 16, (16 * 16, 16 * 22))
    tiles.append(("booster, az 70, frost on (tanks loaded)", render(boost_b, cam, textures, frost=frost)))
    # 8. ship barrel with frost
    cam = ortho_view(140, 0, (0, 16.0, 0), 16, (16 * 16, 16 * 22))
    tiles.append(("ship, az 140, frost on", render(ship_b, cam, textures, frost=frost)))
    # 9. night: cabin lights
    cam = ortho_view(160, 0, (0, 40.0, 0), 16 * S, (16 * S * 13, 16 * S * 10))
    tiles.append(("night: cabin windows (ship_lights)", render(ship_b, cam, textures, night=True, bg=(6, 8, 14),
                                                               emissive={"ship": tex["ship_lights"]})))
    pad = 16
    cols = 3
    rows = [tiles[i:i + cols] for i in range(0, len(tiles), cols)]
    W = pad + cols * (max(t[1].shape[1] for t in tiles) + pad)
    Hs = [max(t[1].shape[0] for t in r) + 30 for r in rows]
    sheet = Image.new("RGB", (W, sum(Hs) + pad), (28, 30, 34))
    y = pad // 2
    cw = max(t[1].shape[1] for t in tiles) + pad
    for r, hrow in zip(rows, Hs):
        x = pad
        for title, im in r:
            label(sheet, (x, y), title, 12)
            sheet.paste(Image.fromarray(im), (x, y + 18))
            x += cw
        y += hrow
    sheet.save(path, optimize=True)


def preview_cabin(tex, geo, L, path):
    textures = {"ship": tex["ship"]}
    ship = build_ship_mesh(geo, L, "HIGH")
    batches = gather(ship, fullbright=("cabin",))
    shots = []
    seat = geo.seat_angles()
    sky = ((40, 70, 120), (150, 190, 230))
    for k, look_off in ((4, 0.0), (3, 8.0), (0, 0.0)):
        a = seat[k]
        # A riding player's eye sits about 1 m above the seat point (1.62 m eye height, 0.6 m vehicle attachment).
        eye = np.array([geo.c["SEAT_RING_RADIUS"] * math.sin(a), geo.cabin_floor + geo.c["SEAT_HEIGHT"] + 1.0,
                        geo.c["SEAT_RING_RADIUS"] * math.cos(a)])
        la = a + math.radians(look_off)
        target = eye + np.array([math.sin(la), -0.05, math.cos(la)])
        cam = Camera(eye, target, fov_deg=90, size=(640, 400))
        shots.append((f"from couch {k} (angle {math.degrees(a):.1f} deg), looking outward", render(batches, cam, textures, sky=sky)))
    # a cutaway from above: the deck
    cam = Camera((0.01, geo.cabin_floor + 2.2, 0.0), (0.0, geo.cabin_floor, 0.0), fov_deg=110, size=(640, 400))
    shots.append(("cabin deck from the ceiling (hatch, rails, couches)", render(batches, cam, textures, sky=sky)))
    pad = 16
    W = pad + 2 * (640 + pad)
    H = pad + 2 * (400 + 30)
    sheet = Image.new("RGB", (W, H), (28, 30, 34))
    for i, (title, im) in enumerate(shots):
        x = pad + (i % 2) * (640 + pad)
        y = pad // 2 + (i // 2) * (400 + 30)
        label(sheet, (x, y), title, 12)
        sheet.paste(Image.fromarray(im), (x, y + 18))
    sheet.save(path, optimize=True)


def preview_items(items, path):
    k = 16
    pad = 24
    names = list(items)
    others = []
    for nm in ("chromium_ingot", "nickel_ingot", "iron_nickel_chunk", "rustcap_door"):
        fp = ITEM_DIR / f"{nm}.png"
        if fp.exists():
            others.append(np.array(Image.open(fp).convert("RGBA")))
    W = pad + len(names) * (16 * k + pad) + 2 * (16 * 4 + pad) + 40 + len(others) * (16 * 4 + 8)
    H = 16 * k + 2 * pad + 20
    sheet = Image.new("RGB", (W, H), (28, 30, 34))
    x = pad
    for nm in names:
        arr = items[nm]
        bg = np.full((16, 16, 3), (139, 139, 139), np.uint8)
        rgb = comp_over(arr, bg)
        sheet.paste(Image.fromarray(rgb).resize((16 * k, 16 * k), Image.NEAREST), (x, pad + 16))
        label(sheet, (x, 4), f"{nm}.png (x{k}, on the inventory slot grey)", 13)
        x += 16 * k + pad
    for nm in names:
        arr = items[nm]
        rgb = comp_over(arr, np.full((16, 16, 3), (139, 139, 139), np.uint8))
        sheet.paste(Image.fromarray(rgb).resize((64, 64), Image.NEAREST), (x, pad + 16))
        sheet.paste(Image.fromarray(rgb), (x + 24, pad + 100))
        x += 64 + pad
    x += 40
    if others:
        label(sheet, (x, 4), "existing mod items (style reference)", 13)
    for arr in others:
        rgb = comp_over(arr, np.full((16, 16, 3), (139, 139, 139), np.uint8))
        sheet.paste(Image.fromarray(rgb).resize((64, 64), Image.NEAREST), (x, pad + 16))
        sheet.paste(Image.fromarray(rgb), (x + 24, pad + 100))
        x += 64 + 8
    sheet.save(path, optimize=True)


# ===================================================================================================================

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--no-preview", action="store_true", help="skip the preview renders")
    args = ap.parse_args(argv)
    t0 = time.time()
    L = Layout()
    geo = Geo()
    print(f"layout from {L.source}; nose arc length {geo.nose_len:.3f} m; cabin floor r {geo.floor_r:.3f} m, "
          f"ceiling r {geo.ceiling_r:.3f} m")
    tex, info = build_all(geo, L)
    items = make_items()
    problems = validate(tex, items, info, geo, L)
    names = {"ship": "ship.png", "booster": "booster.png", "ship_frost": "ship_frost.png",
             "booster_frost": "booster_frost.png", "ship_lights": "ship_lights.png"}
    for key, fn in names.items():
        save_png(tex[key], OUT_DIR / fn)
    for key, arr in items.items():
        save_png(arr, ITEM_DIR / f"{key}.png")
    for key, fn in names.items():
        print(f"  {fn:18s} sha256 {hashlib.sha256((OUT_DIR / fn).read_bytes()).hexdigest()[:16]}")
    for key in items:
        p = ITEM_DIR / f"{key}.png"
        print(f"  item {key:13s} sha256 {hashlib.sha256(p.read_bytes()).hexdigest()[:16]} ({info[f'item_{key}_colours']} colours)")
    wa = info["window_alignment"]
    print(f"  windows: nose vs cabin wall differ by at most {wa[0]:.2f} deg and {wa[1]:.3f} m (pixel quantisation)")
    sf, bf = info["ship_frost_mid_alpha"], info["booster_frost_mid_alpha"]
    print(f"  frost median alpha mid-tank: ship steel {sf[0]:.0f} / tiles {sf[1]:.0f}; booster steel {bf[0]:.0f} / "
          f"chines {bf[1]:.0f}")
    print("  wrap seam ratios (<= 1: the wrap looks like its neighbours): " +
          ", ".join(f"{k.lower()} {v:.2f}" for k, v in info["seams"].items()))
    print_design_numbers(geo, L)
    for p in problems:
        print(f"  !! {p}")
    print(f"textures written to {OUT_DIR.relative_to(ROOT)} ({time.time() - t0:.1f} s), {len(problems)} problems")
    if not args.no_preview:
        PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
        t1 = time.time()
        preview_atlases(tex, L, PREVIEW_DIR / "vehicle_atlases.png")
        preview_items(items, PREVIEW_DIR / "vehicle_items.png")
        preview_cabin(tex, geo, L, PREVIEW_DIR / "vehicle_cabin.png")
        preview_closeups(tex, geo, L, PREVIEW_DIR / "vehicle_closeups.png")
        preview_views(tex, geo, L, PREVIEW_DIR / "vehicle_views.png")
        print(f"previews in {PREVIEW_DIR.relative_to(ROOT)} ({time.time() - t1:.1f} s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
