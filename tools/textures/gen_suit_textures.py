#!/usr/bin/env python3
"""
Spacesuit equipment textures and the helmet visor overlay for "Red Planet: Starship to Mars" (mod id ``redplanet``).

Run from anywhere (paths are resolved from this file):

    python3 tools/textures/gen_suit_textures.py               # textures and the preview
    python3 tools/textures/gen_suit_textures.py --no-preview

Outputs
    src/client/resources/assets/redplanet/textures/entity/equipment/humanoid/spacesuit.png          64x32
        helmet (head box), torso (body and arms) and boots (legs), worn at the outer armour inflation
    src/client/resources/assets/redplanet/textures/entity/equipment/humanoid_leggings/spacesuit.png 64x32
        lower torso and legs (waist bearing ring, knee joints), worn at the inner inflation
    src/client/resources/assets/redplanet/textures/misc/spacesuit_visor.png                         512x256
        first-person visor overlay, stretched over the screen
    tools/textures/preview/suit_preview.png   the layers on a humanoid (front, right side, back, 3/4), the raw
        layers at 6x, and the visor over a mock first-person view

Everything is drawn by code. The armour UV layout was read from the 26.3 sources (HumanoidModel: head texOffs
(0, 0) 8x8x8 and its hat child (32, 0), which equipment helmets keep but leave transparent here; body (16, 16)
8x12x4; arms (40, 16) 4x12x4, the left one mirrored; legs (0, 16) 4x12x4, mirrored; createArmorMeshSet: head and
chest at the outer deformation, legs at the inner one with the legs 0.1 thinner, feet at the outer one with the
legs 0.1 thinner). Equipment renders with an alpha-tested cutout type, so both layers use binary alpha. Box UVs
unfold as [right][front][left][back] around each box, the top above the front and the bottom beside it, and
the side faces' columns run from back to front (right face) and front to back (left face).

Design: a white Mars EVA suit. Helmet: a hard white shell, a large gold visor across the face that wraps a
little round the sides, a light housing on each side, a grey neck seal ring underneath. Torso: a white hard
upper torso with a chest control module (a small dark panel with a green and an orange light), the
life-support backpack on the back face (its thickness hinted on the side faces' back columns), a grey hem;
orange bands on the upper arms, grey shoulder, elbow and wrist bearings, grey gloves. The torso's bottom two
rows are left open so the leggings' waist bearing ring shows below the hem. Leggings: white legs with knee joint
rings, the waist bearing ring on the body's bottom rows. Boots (main layer, legs' lower five rows and the
sole): grey treaded boots with a lit cuff, an orange strap buckle on the outer side and a rubber toe cap. Fabric
shading is soft: faces are lit from the top and front, the inner and back faces a step darker, with a few folds.
The suit lives outside, so Mars dust (gen_textures.mix toward ochre) tints the lower boots and the shins.

Visor: alpha exactly 0 across the middle (a superellipse 80% of the way to the edges), a soft dark blue-grey rim
fading in toward the edges and strongest in the corners (about 160), a faint gold tint along the top edge, and
one thin highlight arc following the rim round the upper-left corner. Deterministic: the only randomness is rng_for() from gen_textures.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.dont_write_bytecode = True  # importing gen_textures must not leave a __pycache__ in the repository
HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
from gen_textures import (Canvas, PREVIEW_BG, _face, font, hexc, mix, paste_rgba,  # noqa: E402
                          rng_for, upscale)

ROOT = HERE.parents[1]
TEX_DIR = ROOT / "src/client/resources/assets/redplanet/textures"
OUT_MAIN = TEX_DIR / "entity/equipment/humanoid/spacesuit.png"
OUT_LEGS = TEX_DIR / "entity/equipment/humanoid_leggings/spacesuit.png"
OUT_VISOR = TEX_DIR / "misc/spacesuit_visor.png"
PREVIEW_PATH = HERE / "preview/suit_preview.png"

TW, TH = 64, 32

SUIT = {
    # white ortho fabric and the hard shells (dark -> light)
    "white": ["#6f6d69", "#8b8984", "#a6a49e", "#bfbdb7", "#d4d2cc", "#e5e3de", "#f2f1ed", "#fcfcfa"],
    # bearings, gloves, boots, the backpack frame
    "grey": ["#1d1f22", "#2a2d31", "#3a3d42", "#4f5359", "#686c72", "#858990", "#a5a9ae"],
    "orange": ["#8c370c", "#c04f15", "#e66d1f", "#fb9140", "#ffb470"],
    # gold sun visor, from the ground reflection to the sky glint
    "visor": ["#2c1b05", "#4d320b", "#785313", "#a57a22", "#cda33d", "#ebcd6f", "#fbebb3"],
    "lamp": ["#ffe7a8", "#fffbef"],
    "led": ["#4fd35c", "#ff8f3a"],
}
# Mars dust works into the boots and shins: the grey and white ramps under a thin ochre film
DUST = "#b07a52"
SUIT["grey_dusty"] = [mix(c, DUST, 0.22) for c in SUIT["grey"]]
SUIT["white_dusty"] = [mix(c, DUST, 0.16) for c in SUIT["white"]]


def box_uv(u: int, v: int, w: int, h: int, d: int) -> dict[str, tuple[int, int, int, int]]:
    """Vanilla box UV unwrap: face -> (x, y, width, height) for a w x h x d box at texture offset (u, v)."""
    return {"top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d), "right": (u, v + d, d, h),
            "front": (u + d, v + d, w, h), "left": (u + d + w, v + d, d, h), "back": (u + 2 * d + w, v + d, w, h)}


HEAD = box_uv(0, 0, 8, 8, 8)
HAT = box_uv(32, 0, 8, 8, 8)
BODY = box_uv(16, 16, 8, 12, 4)
ARM = box_uv(40, 16, 4, 12, 4)
LEG = box_uv(0, 16, 4, 12, 4)
SIDES = ("right", "front", "left", "back")

_X, _Y = np.meshgrid(np.arange(TW), np.arange(TW))   # a 64 x 64 canvas, cropped to 64 x 32 at the end


def rect(x: int, y: int, w: int = 1, h: int = 1):
    return (_X >= x) & (_X < x + w) & (_Y >= y) & (_Y < y + h)


def face_rect(f):
    return rect(*f)


def row(f, r: int, x0: int = 0, x1: int | None = None):
    """Row r (face-local) of face f, optionally only columns x0..x1 (face-local, inclusive)."""
    x, y, w, h = f
    x1 = w - 1 if x1 is None else x1
    return rect(x + x0, y + r, x1 - x0 + 1, 1)


def new_layer() -> tuple[Canvas, dict[str, int]]:
    cv = Canvas(TW)
    cv.layer[:] = -1
    rid = {k: cv.add(v) for k, v in SUIT.items()}
    return cv, rid


def soft_white(cv: Canvas, w: int, f, base: int, rng, top_lit: bool = True, folds: int = 0):
    """Fill face f with white fabric: tone `base`, the top row a step lighter, the bottom row a step darker, and
    a few soft fold pixels (one tone darker) so large faces aren't flat."""
    x, y, fw, fh = f
    cv.put(face_rect(f), w, base)
    if top_lit:
        cv.put(row(f, 0), w, min(base + 1, 7))
    cv.put(row(f, fh - 1), w, max(base - 1, 0))
    for _ in range(folds):
        fx, fy = int(rng.integers(x + 1, x + fw - 1)), int(rng.integers(y + 1, y + fh - 1))
        cv.put(rect(fx, fy, 1, int(rng.integers(1, 3))), w, max(base - 1, 0))


# ------------------------------------------------------------------------------------------- main layer

def paint_main() -> np.ndarray:
    """Helmet, torso and boots (the 'humanoid' equipment layer)."""
    rng = rng_for("spacesuit_main")
    cv, c = new_layer()
    w, g, o, v, lamp, led = c["white"], c["grey"], c["orange"], c["visor"], c["lamp"], c["led"]

    # --- helmet -----------------------------------------------------------------------------------
    hx, hy, _, _ = HEAD["top"]                                   # shell crown: brighter toward the front left
    cv.put(face_rect(HEAD["top"]), w, 6)
    cv.put(rect(hx, hy, 8, 1) | rect(hx, hy, 1, 8) | rect(hx + 7, hy, 1, 8), w, 5)
    cv.put(rect(hx + 1, hy + 4, 3, 3), w, 7)
    cv.put(rect(hx + 3, hy + 1, 2, 1), g, 3)                      # rear vent
    fx, fy, _, _ = HEAD["front"]                                 # face: shell frame round a big gold visor
    cv.put(face_rect(HEAD["front"]), w, 6)
    cv.put(rect(fx, fy, 8, 1), w, 7)
    cv.put(rect(fx + 7, fy, 1, 8), w, 5)
    cv.put(rect(fx, fy + 7, 8, 1), w, 5)
    visor = rect(fx + 1, fy + 1, 6, 6) & ~(rect(fx + 1, fy + 1) | rect(fx + 6, fy + 1) | rect(fx + 1, fy + 6)
                                           | rect(fx + 6, fy + 6))
    vt = np.array([[5, 5, 5, 4, 4, 4],                          # bright sky reflection at the top left,
                   [5, 5, 4, 4, 4, 3],                          # falling off to the ground reflection
                   [4, 4, 4, 3, 3, 3],
                   [4, 3, 3, 3, 3, 2],
                   [3, 3, 2, 2, 2, 2],
                   [3, 2, 2, 2, 1, 1]])
    for j in range(6):
        for i in range(6):
            if visor[fy + 1 + j, fx + 1 + i]:
                cv.put(rect(fx + 1 + i, fy + 1 + j), v, int(vt[j, i]))
    cv.put(rect(fx + 2, fy + 2) | rect(fx + 3, fy + 2) | rect(fx + 2, fy + 3), v, 6)   # glint
    cv.put(rect(fx + 5, fy + 5), v, 4)                                                  # secondary glint
    for side, (wrap_cols, house_x, lens_x) in (("right", ((6, 7), 3, 5)), ("left", ((0, 1), 2, 2))):
        sx, sy, _, _ = HEAD[side]
        cv.put(face_rect(HEAD[side]), w, 5)
        cv.put(rect(sx, sy, 8, 1), w, 6)
        cv.put(rect(sx, sy + 7, 8, 1), w, 4)
        for cx in wrap_cols:                                     # the visor wraps round the front edge
            cv.put(rect(sx + cx, sy + 2, 1, 4), v, 3)
            cv.put(rect(sx + cx, sy + 1, 1, 1), v, 4)
            cv.put(rect(sx + cx, sy + 4, 1, 1), v, 1)
        cv.put(rect(sx + house_x, sy + 2, 3, 3), g, 3)           # helmet light housing, lens facing forward
        cv.put(rect(sx + house_x, sy + 2, 3, 1), g, 5)
        cv.put(rect(sx + house_x, sy + 4, 3, 1), g, 2)
        cv.put(rect(sx + lens_x, sy + 3), lamp, 1)
        cv.put(rect(sx + lens_x, sy + 2), lamp, 0)
    bx, by, _, _ = HEAD["back"]
    cv.put(face_rect(HEAD["back"]), w, 5)
    cv.put(rect(bx, by, 8, 1), w, 6)
    cv.put(rect(bx, by + 7, 8, 1), w, 4)
    cv.put(rect(bx + 3, by + 5, 2, 2), g, 3)                      # purge valve
    cv.put(rect(bx + 3, by + 5), g, 5)
    nx, ny, _, _ = HEAD["bottom"]                                # neck seal ring; the opening stays clear
    ring = face_rect(HEAD["bottom"]) & ~rect(nx + 1, ny + 1, 6, 6)
    cv.put(ring, g, 3)

    # --- torso ------------------------------------------------------------------------------------
    tx, ty, _, _ = BODY["top"]
    cv.put(face_rect(BODY["top"]), w, 6)
    cv.put(rect(tx + 2, ty + 1, 4, 2), g, 3)                      # neck bearing (under the helmet)
    fx, fy, _, _ = BODY["front"]
    hut = rect(fx, fy, 8, 9)
    cv.put(hut, w, 6)
    cv.put(rect(fx, fy, 2, 9) | rect(fx + 2, fy, 1, 3), w, 7)     # the shell's lit left flank
    cv.put(rect(fx, fy, 8, 1), w, 7)
    cv.put(rect(fx + 7, fy, 1, 9), w, 5)
    cv.put(rect(fx + 1, fy + 7, 6, 1), w, 5)                      # lower fold
    dcm = rect(fx + 2, fy + 3, 4, 4)                              # chest control module
    cv.put(dcm, g, 2)
    cv.put(rect(fx + 2, fy + 3, 4, 1), g, 4)
    cv.put(rect(fx + 5, fy + 3, 1, 4), g, 1)
    cv.put(rect(fx + 3, fy + 4), led, 0)
    cv.put(rect(fx + 4, fy + 4), led, 1)
    cv.put(rect(fx + 3, fy + 5, 2, 1), g, 4)                      # knobs
    cv.put(rect(fx + 2, fy + 7, 4, 1), w, 4)                      # module's shadow on the fabric
    cv.put(rect(fx, fy + 9, 8, 1), g, 4)                          # hem ring (rows 30-31 left open)
    for side, back_col in (("right", 0), ("left", 3)):
        sx, sy, _, _ = BODY[side]
        cv.put(rect(sx, sy, 4, 9), w, 5)
        cv.put(rect(sx, sy, 4, 1), w, 6)
        cv.put(rect(sx + back_col, sy, 1, 9), g, 5)               # the backpack's edge behind the arm
        cv.put(rect(sx, sy + 9, 4, 1), g, 4)
    bx, by, _, _ = BODY["back"]                                  # life-support backpack
    cv.put(rect(bx, by, 8, 9), g, 3)                              # frame
    cv.put(rect(bx, by, 8, 1), g, 5)
    cv.put(rect(bx, by + 8, 8, 1), g, 2)
    cv.put(rect(bx + 1, by + 1, 6, 7), w, 6)                      # white cover, lit at the top
    cv.put(rect(bx + 1, by + 1, 6, 1), w, 7)
    cv.put(rect(bx + 6, by + 1, 1, 7), w, 5)
    cv.put(rect(bx + 1, by + 5, 6, 1), w, 4)                      # access seam
    cv.put(rect(bx + 2, by + 6, 4, 1), g, 2)                      # radiator louvre slot
    cv.put(rect(bx + 2, by + 7, 4, 1), w, 5)
    cv.put(rect(bx + 2, by + 2, 4, 2), g, 4)                      # control box with its status light
    cv.put(rect(bx + 2, by + 2, 4, 1), g, 5)
    cv.put(rect(bx + 4, by + 3), led, 0)
    cv.put(rect(bx + 2, by + 1, 4, 1), o, 3)                      # orange grab handle
    cv.put(rect(bx, by + 9, 8, 1), g, 4)

    # --- arms -------------------------------------------------------------------------------------
    ax, ay, _, _ = ARM["top"]
    cv.put(face_rect(ARM["top"]), g, 4)                           # shoulder bearing seen from above
    cv.put(rect(ax + 1, ay + 1, 2, 2), w, 6)
    gx, gy, _, _ = ARM["bottom"]
    cv.put(face_rect(ARM["bottom"]), g, 3)                        # glove palm
    cv.put(rect(gx, gy, 4, 1), g, 4)
    for side in SIDES:
        f = ARM[side]
        base = {"right": 6, "front": 6, "left": 5, "back": 5}[side]
        soft_white(cv, w, f, base, rng)
        cv.put(row(f, 0), g, 5 if side in ("right", "front") else 4)      # shoulder bearing
        cv.put(row(f, 3), o, 3 if side in ("right", "front") else 2)      # orange upper-arm band
        cv.put(row(f, 4), o, 2 if side in ("right", "front") else 1)
        cv.put(row(f, 6), g, 4 if side in ("right", "front") else 3)      # elbow joint
        cv.put(row(f, 7), w, base - 1)
        cv.put(row(f, 10), g, 4 if side in ("right", "front") else 3)     # wrist bearing
        cv.put(row(f, 11), g, 2)                                          # glove cuff

    # --- boots (the legs' lower five rows and the sole) --------------------------------------------
    sx, sy, _, _ = LEG["bottom"]
    cv.put(face_rect(LEG["bottom"]), g, 1)                        # treaded sole
    cv.put(face_rect(LEG["bottom"]) & ((_X + _Y) % 2 == 0), g, 0)
    cv.put(rect(sx, sy, 4, 1), g, 2)
    gd = c["grey_dusty"]
    for side in SIDES:
        f = LEG[side]
        lit = side in ("right", "front")
        cv.put(row(f, 7), g, 6 if lit else 5)                     # cuff
        cv.put(row(f, 8), g, 4 if lit else 3)
        cv.put(row(f, 9), g, 4 if lit else 3)
        cv.put(row(f, 10), gd, 3 if lit else 2)                   # dust worked into the lower boot
        cv.put(row(f, 11), gd, 1)                                 # sole edge with tread notches
        x, y, fw, fh = f
        cv.put(rect(x, y + 11, fw, 1) & (_X % 2 == 0), g, 0)
    fx, fy, _, _ = LEG["front"]
    cv.put(rect(fx, fy + 10, 4, 1), gd, 5)                        # rubber toe cap
    ox, oy, _, _ = LEG["right"]
    cv.put(rect(ox, oy + 8, 4, 1), g, 2)                          # strap with an orange buckle (outer side)
    cv.put(rect(ox + 1, oy + 8, 2, 1), o, 3)
    return cv.rgba()[:TH]


# ------------------------------------------------------------------------------------------ leggings

def paint_leggings() -> np.ndarray:
    """Lower torso and legs (the 'humanoid_leggings' equipment layer)."""
    rng = rng_for("spacesuit_leggings")
    cv, c = new_layer()
    w, g, o = c["white"], c["grey"], c["orange"]
    for side in SIDES:                                           # body: brief (rows 27-29), waist ring (30-31)
        f = BODY[side]
        x, y, fw, fh = f
        lit = side in ("right", "front")
        cv.put(rect(x, y + 7, fw, 3), w, 6 if lit else 5)
        cv.put(rect(x, y + 7, fw, 1), w, 7 if lit else 6)
        cv.put(rect(x, y + 10, fw, 1), g, 5 if lit else 4)
        cv.put(rect(x, y + 11, fw, 1), g, 3 if lit else 2)
    fx, fy, _, _ = BODY["front"]
    cv.put(rect(fx + 3, fy + 10, 2, 1), o, 3)                     # bearing index mark
    tx, ty, _, _ = LEG["top"]
    cv.put(face_rect(LEG["top"]), w, 6)
    for side in SIDES:
        f = LEG[side]
        base = {"right": 6, "front": 6, "left": 5, "back": 5}[side]
        soft_white(cv, w, f, base, rng, folds=1)
        cv.put(row(f, 4), g, 5 if side in ("right", "front") else 4)      # knee joint ring
        cv.put(row(f, 5), g, 3 if side in ("right", "front") else 2)
        cv.put(row(f, 6), w, base - 1)                                    # fabric bunched under the joint
    wd = c["white_dusty"]
    for side in SIDES:                                           # dust on the shins (mostly under the boots)
        x, y, fw, fh = LEG[side]
        cv.put(rect(x, y + 9, fw, 3), wd, 5 if side in ("right", "front") else 4)
        cv.put(rect(x, y + 8, fw, 1) & (_X % 2 == 0), wd, 5 if side in ("right", "front") else 4)
    fx, fy, _, _ = LEG["front"]
    cv.put(rect(fx + 1, fy + 2, 1, 2), w, 5)                       # thigh fold
    return cv.rgba()[:TH]


# --------------------------------------------------------------------------------------------- visor

def smoothstep(a: float, b: float, x):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def paint_visor(w: int = 512, h: int = 256) -> np.ndarray:
    """First-person helmet visor: transparent middle, soft dark rim strongest in the corners, a faint gold tint
    along the top, one thin highlight arc in the upper left. Straight alpha (not premultiplied)."""
    ys, xs = np.mgrid[0:h, 0:w].astype(float)
    nx, ny = (xs + 0.5 - w / 2) / (w / 2), (ys + 0.5 - h / 2) / (h / 2)
    d = (np.abs(nx) ** 4 + np.abs(ny) ** 4) ** 0.25               # 1 at the edge midpoints, 1.19 in the corners
    rim = smoothstep(0.80, 1.19, d) ** 1.5
    a_rim = 160.0 * rim
    gold = smoothstep(0.72, 1.0, -ny) * (1 - 0.6 * smoothstep(0.85, 1.19, d))
    a_gold = 34.0 * gold
    # highlight arc: a reflection on the glass rim, following the d = 0.9 contour round the upper-left corner
    # (just outside the clear middle) and fading out at both ends
    gy, gx = np.gradient(d)
    dist = np.abs(d - 0.9) / np.maximum(np.hypot(gx, gy), 1e-9)          # distance to the contour, in px
    phi = np.degrees(np.arctan2(-ny, -nx))                                 # 0 = left edge, 90 = top edge
    env = np.sin(np.pi * np.clip((phi - 12) / 68, 0, 1)) ** 1.2
    a_hi = 62.0 * np.exp(-(dist / 0.9) ** 2) * env
    rim_rgb = np.array(hexc("#0a0d13"), float)
    gold_rgb = np.array(hexc("#b08a3c"), float)
    hi_rgb = np.array(hexc("#f2f5ff"), float)
    # composite the three contributions (over-operator, rim first) into straight-alpha RGBA
    out_a = np.zeros((h, w))
    out_c = np.zeros((h, w, 3))
    for a, col in ((a_rim, rim_rgb), (a_gold, gold_rgb), (a_hi, hi_rgb)):
        a = a / 255.0
        new_a = a + out_a * (1 - a)
        safe = np.where(new_a > 1e-6, new_a, 1.0)
        out_c = (col[None, None, :] * a[..., None] + out_c * (out_a * (1 - a))[..., None]) / safe[..., None]
        out_a = new_a
    rgba = np.zeros((h, w, 4), np.uint8)
    rgba[..., :3] = np.clip(np.round(out_c), 0, 255).astype(np.uint8)
    rgba[..., 3] = np.clip(np.round(out_a * 255), 0, 255).astype(np.uint8)
    rgba[d < 0.80, 3] = 0
    rgba[rgba[..., 3] == 0, :3] = rim_rgb.astype(np.uint8)      # transparent pixels keep the rim colour
    return rgba


# ------------------------------------------------------------------------------------------ validation

def validate_layer(name: str, arr: np.ndarray) -> list[str]:
    msgs = []
    if arr.shape[:2] != (TH, TW):
        msgs.append(f"{name}: size {arr.shape[1]}x{arr.shape[0]}, expected {TW}x{TH}")
    if not set(np.unique(arr[..., 3])) <= {0, 255}:
        msgs.append(f"{name}: partial alpha (equipment renders cutout)")
    if (arr[0:16, 32:64, 3] > 0).any():
        msgs.append(f"{name}: the hat region is painted (it renders 0.5 px outside the helmet)")
    return msgs


def validate_visor(arr: np.ndarray) -> list[str]:
    msgs = []
    h, w = arr.shape[:2]
    if (h, w) != (256, 512):
        msgs.append(f"visor: size {w}x{h}, expected 512x256")
    mid = arr[int(h * 0.2):int(h * 0.8), int(w * 0.2):int(w * 0.8), 3]
    if mid.max() != 0:
        msgs.append(f"visor: the middle is not fully transparent (max alpha {mid.max()})")
    if arr[..., 3].max() > 200:
        msgs.append(f"visor: alpha reaches {arr[..., 3].max()} (keep it subtle)")
    return msgs


# --------------------------------------------------------------------------------------------- preview

MODEL = {   # part: (box uv, size (w, h, d) in px, position of its min corner (x, y, z), y down, z toward the viewer)
    "head": (HEAD, (8, 8, 8), (-4, 0, -4)),
    "body": (BODY, (8, 12, 4), (-4, 8, -2)),
    "right_arm": (ARM, (4, 12, 4), (-8, 8, -2)),
    "left_arm": (ARM, (4, 12, 4), (4, 8, -2)),
    "right_leg": (LEG, (4, 12, 4), (-4, 20, -2)),
    "left_leg": (LEG, (4, 12, 4), (0, 20, -2)),
}
SKIN = np.array(hexc("#7d7f84"), float)


def face_img(tex: np.ndarray, f, mirror: bool = False) -> np.ndarray:
    x, y, w, h = f
    a = tex[y:y + h, x:x + w].copy()
    return a[:, ::-1] if mirror else a


def mannequin_tex() -> np.ndarray:
    """A plain grey stand-in for the player's skin, lit per face in the projections."""
    t = np.zeros((TH * 2, TW, 4), np.uint8)
    t[..., :3] = SKIN.astype(np.uint8)
    t[..., 3] = 255
    return t


def layers_for(part: str, main: np.ndarray, legs: np.ndarray):
    """(texture, inflation) pairs drawn over the mannequin, inner first."""
    if part == "head":
        return [(main, 1.0)]
    if part == "body":
        return [(legs, 0.5), (main, 1.0)]
    if part.endswith("arm"):
        return [(main, 1.0)]
    return [(legs, 0.4), (main, 0.9)]


def flat_view(main: np.ndarray, legs: np.ndarray, face: str, k: int = 10) -> Image.Image:
    """Orthographic front / back / right view: each part's face for that side, every layer inflated about the
    part's centre as in game."""
    W, H = 22 * k, 36 * k
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    skin = mannequin_tex()
    light = {"front": 1.0, "back": 0.8, "right": 0.88}[face]
    order = ["body", "right_leg", "left_leg", "right_arm", "left_arm", "head"]
    if face == "right":
        order = ["left_leg", "left_arm", "body", "right_leg", "head", "right_arm"]
    for part in order:
        uv, (w, h, d), (x0, y0, z0) = MODEL[part]
        mirror = part.startswith("left")
        src = face
        if face == "right" and mirror:
            src = "left"                                          # the far-side limbs mostly hide; fine
        for tex, g in [(skin, 0.0)] + layers_for(part, main, legs):
            img_face = face_img(tex, uv[src], mirror and src in ("front", "back"))
            if face == "front":
                fx, fw = x0 - g, w + 2 * g
            elif face == "back":
                fx, fw = -(x0 + w) - g, w + 2 * g
            else:
                fx, fw = (z0 - g), d + 2 * g
            fy, fh = y0 - g, h + 2 * g
            px = int(round((fx + 11) * k)), int(round((fy + 2) * k))
            size = max(1, int(round(fw * k))), max(1, int(round(fh * k)))
            a = img_face.astype(float)
            a[..., :3] *= light
            im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGBA").resize(size, Image.NEAREST)
            img.alpha_composite(im, px)
    return img


def iso_view(main: np.ndarray, legs: np.ndarray, s: float = 9.0) -> Image.Image:
    """3/4 view from the player's front left: the top, front and left (+x) side of every box, drawn back to
    front with Minecraft-style face shading."""
    W, H = int(40 * s), int(48 * s)
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    E = np.array([0.87, 0.45]) * s          # +x (the player's left)
    S = np.array([-0.87, 0.45]) * s         # +z (toward the viewer, the player's front)
    U = np.array([0.0, -1.0]) * s
    origin = np.array([W / 2, H * 0.83])
    skin = mannequin_tex()

    def P(x, y, z):                          # model px (y measured down from the head top) -> screen
        return origin + E * x + S * z + U * (32 - y)

    for part in ("right_arm", "right_leg", "left_leg", "body", "head", "left_arm"):   # far to near
        uv, (w, h, d), (x0, y0, z0) = MODEL[part]
        mirror = part.startswith("left")
        for tex, g in [(skin, 0.0)] + layers_for(part, main, legs):
            X0, Y0, Z0 = x0 - g, y0 - g, z0 - g
            fw, fh, fd = w + 2 * g, h + 2 * g, d + 2 * g
            sw, sh, sd = fw / w, fh / h, fd / d
            top = face_img(tex, uv["top"], mirror)
            front = face_img(tex, uv["front"], mirror)
            # the +x face: a 'left' face runs front -> back; a mirrored limb shows its 'right' face flipped
            side = face_img(tex, uv["right"] if mirror else uv["left"], mirror)
            _face(img, top, P(X0, Y0, Z0), E * sw, S * sd, 1.0)
            _face(img, front, P(X0, Y0, Z0 + fd), E * sw, -U * sh, 0.82)
            _face(img, side, P(X0 + fw, Y0, Z0 + fd), -S * sd, -U * sh, 0.62)
    return img.crop(img.getbbox())


def mock_screen(w: int = 960, h: int = 540) -> Image.Image:
    """A stand-in first-person frame (sky, dunes, a block, a crosshair and a hotbar) to judge the visor on."""
    ys = np.linspace(0, 1, h)[:, None, None]
    sky = np.array(hexc("#c79a74"), float) * (1 - ys) + np.array(hexc("#e2b48a"), float) * ys
    img = np.repeat(sky, w, axis=1)
    horizon = int(h * 0.58)
    xs = np.arange(w)
    ridge = horizon + (12 * np.sin(xs / 90.0) + 7 * np.sin(xs / 37.0)).astype(int)
    for x in range(w):
        img[ridge[x]:, x] = np.array(hexc("#9e5c3b"), float) * (0.85 + 0.15 * np.sin(x / 13.0))
    im = Image.fromarray(img.astype(np.uint8), "RGB").convert("RGBA")
    d = ImageDraw.Draw(im)
    d.rectangle([w * 0.62, h * 0.48, w * 0.74, h * 0.68], fill=(214, 211, 204, 255), outline=(60, 62, 66, 255), width=3)
    d.line([w / 2 - 9, h / 2, w / 2 + 9, h / 2], fill=(255, 255, 255, 255), width=2)
    d.line([w / 2, h / 2 - 9, w / 2, h / 2 + 9], fill=(255, 255, 255, 255), width=2)
    hb_w = 364
    d.rectangle([w / 2 - hb_w / 2, h - 46, w / 2 + hb_w / 2, h - 6], fill=(40, 40, 40, 200), outline=(20, 20, 20, 255))
    for i in range(9):
        x = w / 2 - hb_w / 2 + 2 + i * 40
        d.rectangle([x + 2, h - 44, x + 38, h - 8], outline=(150, 150, 150, 255))
    return im


def preview(main: np.ndarray, legs: np.ndarray, visor: np.ndarray, path: Path) -> None:
    views = [("front", flat_view(main, legs, "front")), ("right side", flat_view(main, legs, "right")),
             ("back", flat_view(main, legs, "back")), ("3/4", iso_view(main, legs))]
    views = [(t, im.crop(im.getbbox())) for t, im in views]
    pad, head = 24, 30
    top_h = max(im.size[1] for _, im in views)
    raw_k = 6
    raw_w = TW * raw_k
    screen = mock_screen()
    over = screen.copy()
    over.alpha_composite(Image.fromarray(visor, "RGBA").resize(screen.size, Image.BILINEAR))
    sw, sh = 480, 270
    W = max(sum(im.size[0] for _, im in views) + pad * (len(views) + 1), 2 * raw_w + 3 * pad, 2 * sw + 3 * pad)
    H = head + top_h + pad + head + TH * raw_k + pad + head + sh + pad
    sheet = Image.new("RGBA", (W, H), PREVIEW_BG)
    d = ImageDraw.Draw(sheet)
    d.text((pad, 6), "Spacesuit on a humanoid (main layer at the outer inflation over leggings at the inner one)",
           fill=(240, 230, 220, 255), font=font(16))
    x = pad
    for title, im in views:
        d.text((x, head - 4), title, fill=(230, 180, 140, 255), font=font(13))
        sheet.alpha_composite(im, (x, head + 14))
        x += im.size[0] + pad
    y = head + top_h + pad + 10
    for i, (title, arr) in enumerate((("humanoid/spacesuit.png (64x32)", main),
                                      ("humanoid_leggings/spacesuit.png (64x32)", legs))):
        xx = pad + i * (raw_w + pad)
        d.text((xx, y), title, fill=(230, 180, 140, 255), font=font(13))
        paste_rgba(sheet, upscale(arr, raw_k), (xx, y + 18), "checker")
    y += head + TH * raw_k + pad
    d.text((pad, y), "Visor overlay over a mock first-person frame (left: without, right: with)",
           fill=(230, 180, 140, 255), font=font(13))
    sheet.alpha_composite(screen.resize((sw, sh), Image.BILINEAR), (pad, y + 18))
    sheet.alpha_composite(over.resize((sw, sh), Image.BILINEAR), (2 * pad + sw, y + 18))
    path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(path, optimize=True)


def save_png(arr: np.ndarray, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(arr, "RGBA").save(path, optimize=True)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--no-preview", action="store_true")
    args = ap.parse_args(argv)
    main_layer, legs_layer, visor = paint_main(), paint_leggings(), paint_visor()
    msgs = validate_layer("humanoid", main_layer) + validate_layer("humanoid_leggings", legs_layer) + validate_visor(visor)
    for arr, path in ((main_layer, OUT_MAIN), (legs_layer, OUT_LEGS), (visor, OUT_VISOR)):
        save_png(arr, path)
        a = arr[..., 3]
        print(f"  {path.relative_to(ROOT)}  {arr.shape[1]}x{arr.shape[0]}  alpha {a.min()}-{a.max()}, "
              f"{(a > 0).mean() * 100:.0f}% painted")
    for m in msgs:
        print(f"  !! {m}")
    if not args.no_preview:
        preview(main_layer, legs_layer, visor, PREVIEW_PATH)
        print(f"preview: {PREVIEW_PATH.relative_to(ROOT)}")
    return 1 if msgs else 0


if __name__ == "__main__":
    sys.exit(main())
