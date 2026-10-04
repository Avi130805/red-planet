"""Title cards and text overlays for the trailer, drawn with Pillow and numpy.

Cards are animated (a starfield over the limb of a red planet, the title easing in); overlays are RGBA layers that
assemble.py composites over the shots. Fonts are open-licence Google Fonts (OFL), fetched into build/trailer/fonts on
first use; without network the script falls back to Liberation Sans.
"""
from __future__ import annotations

import urllib.request
from functools import lru_cache
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

from edit import HEIGHT, WIDTH

FONT_DIR = Path("build/trailer/fonts")
FONTS = {
    "logo": ("Orbitron[wght].ttf", "orbitron/Orbitron%5Bwght%5D.ttf", 900),
    "head": ("Rajdhani-Bold.ttf", "rajdhani/Rajdhani-Bold.ttf", None),
    "body": ("Rajdhani-SemiBold.ttf", "rajdhani/Rajdhani-SemiBold.ttf", None),
}
FALLBACK = "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"
ACCENT = (255, 112, 64)


@lru_cache(maxsize=None)
def font(kind: str, size: int) -> ImageFont.FreeTypeFont:
    name, remote, weight = FONTS[kind]
    path = FONT_DIR / name
    if not path.exists():
        try:
            FONT_DIR.mkdir(parents=True, exist_ok=True)
            urllib.request.urlretrieve("https://raw.githubusercontent.com/google/fonts/main/ofl/" + remote, path)
        except OSError:
            return ImageFont.truetype(FALLBACK, size)
    f = ImageFont.truetype(str(path), size)
    if weight is not None:
        try:
            f.set_variation_by_axes([weight])
        except (OSError, ValueError):
            pass
    return f


def tracked(draw: ImageDraw.ImageDraw, xy: tuple[float, float], text: str, f: ImageFont.FreeTypeFont, fill,
            tracking: float = 0.0, anchor: str = "mm") -> None:
    """Text with letter-spacing (tracking, in ems), centred on xy (anchor mm) or starting at it (anchor lm)."""
    widths = [draw.textlength(c, font=f) for c in text]
    space = tracking * f.size
    total = sum(widths) + space * (len(text) - 1)
    x = xy[0] - total / 2 if anchor == "mm" else xy[0]
    for c, w in zip(text, widths):
        draw.text((x, xy[1]), c, font=f, fill=fill, anchor="lm")
        x += w + space


def glow_text(size: tuple[int, int], lines: list[tuple], glow_color=(255, 80, 30), glow_radius: int = 22,
              glow_strength: float = 1.6) -> Image.Image:
    """An RGBA layer: each line (y, text, font, colour, tracking) drawn centred, with a coloured glow behind."""
    text = Image.new("L", size, 0)
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    dt = ImageDraw.Draw(text)
    dl = ImageDraw.Draw(layer)
    for y, s, f, colour, track in lines:
        tracked(dt, (size[0] / 2, y), s, f, 255, track)
        tracked(dl, (size[0] / 2, y), s, f, colour + (255,), track)
    glow = text.filter(ImageFilter.GaussianBlur(glow_radius))
    g = np.asarray(glow, dtype=np.float32) / 255.0 * glow_strength
    out = np.zeros((size[1], size[0], 4), dtype=np.float32)
    out[..., :3] = np.array(glow_color, dtype=np.float32) / 255.0
    out[..., 3] = np.clip(g, 0, 1)
    base = Image.fromarray((out * 255).astype(np.uint8), "RGBA")
    return Image.alpha_composite(base, layer)


# ------------------------------------------------------------------------------------------- backgrounds

def starfield(seed: int = 7) -> np.ndarray:
    rng = np.random.default_rng(seed)
    img = np.zeros((HEIGHT, WIDTH, 3), dtype=np.float32)
    n = 900
    xs = rng.integers(0, WIDTH, n)
    ys = rng.integers(0, HEIGHT, n)
    b = rng.random(n) ** 3
    tint = rng.random(n)
    for x, y, v, tn in zip(xs, ys, b, tint):
        colour = np.array([1.0, 0.92 + 0.08 * tn, 0.85 + 0.15 * tn]) * v
        img[y, x] = np.maximum(img[y, x], colour)
    blurred = np.asarray(Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.7)),
                         dtype=np.float32) / 255.0
    return np.clip(img * 0.8 + blurred * 1.6, 0, 1)


def planet_limb(rise: float) -> np.ndarray:
    """The limb of a dusty red planet filling the bottom of the frame, with a thin bright atmosphere."""
    yy, xx = np.mgrid[0:HEIGHT, 0:WIDTH].astype(np.float32)
    r = 2600.0
    cx, cy = WIDTH * 0.5, HEIGHT + r - 260.0 - rise
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    inside = np.clip((r - d) / 2.0, 0, 1)
    depth = np.clip((r - d) / 420.0, 0, 1)
    # lit from the upper left: brighter toward the limb's left
    light = 0.55 + 0.45 * np.clip(1.0 - (xx - cx) / (WIDTH * 0.9), 0, 1.3)
    surface = np.stack([0.62 - 0.30 * depth, 0.26 - 0.16 * depth, 0.12 - 0.08 * depth], -1) * light[..., None]
    rim = np.exp(-((d - r) / 7.0) ** 2) * 0.9
    halo = np.exp(-np.clip(d - r, 0, None) / 55.0) * (d > r) * 0.35
    atmosphere = (rim + halo)[..., None] * np.array([1.0, 0.55, 0.32])
    return np.clip(surface * inside[..., None] + atmosphere * light[..., None], 0, 1), inside


def ease(u: float) -> float:
    u = min(1.0, max(0.0, u))
    return u * u * (3 - 2 * u)


# ------------------------------------------------------------------------------------------- cards

def card_frames(name: str, seconds: float, fps: int):
    """Yields the frames (H x W x 3 float, 0..1) of a title card."""
    stars = starfield()
    n = int(round(seconds * fps))
    if name in ("title", "logo"):
        for i in range(n):
            t = i / fps
            limb, mask = planet_limb(rise=28.0 * t)
            twinkle = 0.85 + 0.15 * np.sin(t * 3.0)
            frame = stars * twinkle * (1 - mask[..., None]) + limb
            k = ease(t / 0.9)
            s = 1.0 + 0.05 * (1 - k)
            title = glow_text((WIDTH, HEIGHT), [
                (HEIGHT * 0.40, "RED PLANET", font("logo", int(158 * s)), (255, 255, 255), 0.16 + 0.10 * (1 - k)),
            ])
            sub_k = ease((t - 0.45) / 0.8)
            sub = glow_text((WIDTH, HEIGHT), [
                (HEIGHT * 0.40 + 118, "STARSHIP  TO  MARS", font("body", 50), (236, 214, 200), 0.62),
            ], glow_radius=10, glow_strength=0.6)
            frame = composite(frame, title, k)
            frame = composite(frame, sub, sub_k)
            if name == "logo":
                tag = glow_text((WIDTH, HEIGHT), [
                    (HEIGHT * 0.40 - 120, "A FREE MOD FOR MINECRAFT JAVA EDITION", font("body", 34), (220, 200, 190), 0.35),
                ], glow_radius=6, glow_strength=0.4)
                frame = composite(frame, tag, ease((t - 0.9) / 0.8))
            yield frame.astype(np.float32)
    elif name == "end":
        limb, mask = planet_limb(rise=40.0)
        base = stars * 0.7 * (1 - mask[..., None]) + limb * 0.85
        layer = glow_text((WIDTH, HEIGHT), [
            (HEIGHT * 0.30, "RED PLANET", font("logo", 92), (255, 255, 255), 0.16),
            (HEIGHT * 0.30 + 74, "STARSHIP  TO  MARS", font("body", 36), (236, 214, 200), 0.6),
        ], glow_radius=14, glow_strength=1.0)
        info = glow_text((WIDTH, HEIGHT), [
            (HEIGHT * 0.52, "FREE  ·  FABRIC  ·  MINECRAFT JAVA 26.3", font("head", 52), (255, 255, 255), 0.12),
            (HEIGHT * 0.52 + 70, "github.com/avi130805/minecraft_mods", font("body", 44), ACCENT, 0.04),
        ], glow_radius=8, glow_strength=0.5, glow_color=(0, 0, 0))
        legal = glow_text((WIDTH, HEIGHT), [
            (HEIGHT * 0.88, "NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.",
             font("body", 24), (190, 180, 175), 0.06),
            (HEIGHT * 0.88 + 34, "NOT AFFILIATED WITH OR ENDORSED BY SPACEX.", font("body", 24), (190, 180, 175), 0.06),
        ], glow_radius=1, glow_strength=0.0, glow_color=(0, 0, 0))
        for i in range(n):
            t = i / fps
            frame = composite(base, layer, ease(t / 0.6))
            frame = composite(frame, info, ease((t - 0.5) / 0.6))
            frame = composite(frame, legal, ease((t - 1.0) / 0.6))
            yield frame.astype(np.float32)
    else:
        raise ValueError("unknown card " + name)


def composite(frame: np.ndarray, layer: Image.Image, opacity: float) -> np.ndarray:
    if opacity <= 0:
        return frame
    a = np.asarray(layer, dtype=np.float32) / 255.0
    alpha = a[..., 3:4] * min(1.0, opacity)
    return frame * (1 - alpha) + a[..., :3] * alpha


# ------------------------------------------------------------------------------------------- overlays

@lru_cache(maxsize=None)
def overlay(lines: tuple[str, ...], style: str) -> Image.Image:
    """An RGBA overlay for on-screen text."""
    if style == "statement":
        return glow_text((WIDTH, HEIGHT), [
            (HEIGHT * 0.5 + i * 120 - (len(lines) - 1) * 60, s, font("logo", 104), (255, 255, 255), 0.10)
            for i, s in enumerate(lines)
        ], glow_color=(0, 0, 0), glow_radius=26, glow_strength=1.3)
    if style == "caption":
        img = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
        shadow = Image.new("L", (WIDTH, HEIGHT), 0)
        d = ImageDraw.Draw(img)
        ds = ImageDraw.Draw(shadow)
        y0 = HEIGHT - 205 - (len(lines) - 1) * 52
        for i, s in enumerate(lines):
            y = y0 + i * 52
            tracked(ds, (WIDTH / 2, y), s, font("head", 46), 255, 0.18)
            tracked(d, (WIDTH / 2, y), s, font("head", 46), (255, 255, 255, 255), 0.18)
        bar_y = y0 + (len(lines) - 1) * 52 + 40
        d.rectangle([WIDTH / 2 - 40, bar_y, WIDTH / 2 + 40, bar_y + 4], fill=ACCENT + (255,))
        soft = shadow.filter(ImageFilter.GaussianBlur(12))
        base = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
        base.putalpha(soft.point(lambda v: int(v * 0.75)))
        return Image.alpha_composite(base, img)
    raise ValueError("unknown style " + style)
