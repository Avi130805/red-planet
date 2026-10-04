#!/usr/bin/env python3
"""
Earth texture for the transfer interlude's globe ("Red Planet: Starship to Mars").

    python3 tools/textures/gen_globe_textures.py

Coastlines, lakes and ice sheets come from Natural Earth 1:50m (public domain, https://www.naturalearthdata.com/,
GeoJSON mirror https://github.com/nvkelso/natural-earth-vector). The files are downloaded once into
tools/textures/.cache/ (git-ignored). Everything else is painted by code: ocean depth tint near coasts, land colour from
a climate sketch (deserts, tropical forest, temperate, boreal, tundra), and a cloud layer from wrapped fractal noise
with more cloud in the intertropical convergence zone and the storm tracks, less over the subtropical highs.

Output: src/client/resources/assets/redplanet/textures/environment/earth.png, 1024 x 512, equirectangular, longitude
-180..180 left to right, north up. Mars' globe needs no file: the game builds it from the MOLA and TES data it already
ships (MarsMapTexture).
"""
from __future__ import annotations

import json
import pathlib
import urllib.request

import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from scipy.ndimage import map_coordinates

ROOT = pathlib.Path(__file__).resolve().parents[2]
CACHE = pathlib.Path(__file__).resolve().parent / ".cache"
OUT = ROOT / "src/client/resources/assets/redplanet/textures/environment/earth.png"
BASE = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/"
W, H = 1024, 512
SS = 2  # supersampling for the polygon fill

# Climate sketch: rectangles (lat0, lat1, lon0, lon1) of the great hot and cold deserts.
DESERTS = [
    (15, 32, -17, 35),    # Sahara
    (13, 32, 35, 60),     # Arabian
    (24, 30, 68, 76),     # Thar
    (36, 47, 74, 120),    # Taklamakan and Gobi
    (35, 40, 50, 62),     # Iranian plateau (Dasht-e Kavir, Dasht-e Lut)
    (-32, -19, 116, 145), # Australian interior
    (-28, -17, 12, 25),   # Namib and Kalahari
    (-27, -17, -71, -68), # Atacama
    (28, 37, -117, -104), # North American south-west
    (-50, -40, -71, -65), # Patagonia
]


def fetch(name: str) -> dict:
    CACHE.mkdir(exist_ok=True)
    path = CACHE / name
    if not path.exists():
        print("downloading", name)
        urllib.request.urlretrieve(BASE + name, path)
    return json.loads(path.read_text())


def rasterize(geo: dict) -> np.ndarray:
    img = Image.new("L", (W * SS, H * SS), 0)
    draw = ImageDraw.Draw(img)

    def ring(coords):
        return [((lon + 180.0) / 360.0 * W * SS, (90.0 - lat) / 180.0 * H * SS) for lon, lat in coords]

    for feature in geo["features"]:
        geom = feature["geometry"]
        polys = [geom["coordinates"]] if geom["type"] == "Polygon" else geom["coordinates"]
        for poly in polys:
            draw.polygon(ring(poly[0]), fill=255)
            for hole in poly[1:]:
                draw.polygon(ring(hole), fill=0)
    img = img.resize((W, H), Image.LANCZOS)
    return np.asarray(img, dtype=np.float64) / 255.0


def wrapped_noise(rng: np.random.Generator, cells: int, octaves: int) -> np.ndarray:
    """Fractal value noise that wraps around in longitude."""
    out = np.zeros((H, W))
    amp, total = 1.0, 0.0
    for o in range(octaves):
        cx = cells * 2 ** o
        cy = max(2, cx // 2)
        grid = rng.random((cy + 1, cx))
        grid = np.concatenate([grid, grid[:, :1]], axis=1)  # wrap
        img = Image.fromarray((grid * 255).astype(np.uint8)).resize((W, H), Image.BICUBIC)
        out += amp * (np.asarray(img, dtype=np.float64) / 255.0)
        total += amp
        amp *= 0.5
    return out / total


def smooth(x, a, b):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def main() -> None:
    land = rasterize(fetch("ne_50m_land.geojson"))
    ice = rasterize(fetch("ne_50m_glaciated_areas.geojson"))
    lakes = rasterize(fetch("ne_50m_lakes.geojson"))
    land = np.clip(land - lakes, 0.0, 1.0)

    lat = 90.0 - (np.arange(H) + 0.5) / H * 180.0
    lon = -180.0 + (np.arange(W) + 0.5) / W * 360.0
    LON, LAT = np.meshgrid(lon, lat)
    alat = np.abs(LAT)
    rng = np.random.default_rng(20261004)
    n1 = wrapped_noise(rng, 8, 5)
    n2 = wrapped_noise(rng, 16, 4)

    # Ocean: deep blue, lighter over the continental shelves (a blur of the coastline).
    shelf = np.asarray(Image.fromarray((land * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(4)), dtype=np.float64) / 255.0
    deep = np.array([10, 30, 72], dtype=np.float64)
    shallow = np.array([28, 82, 128], dtype=np.float64)
    ocean = deep + (shallow - deep) * np.clip(shelf * 1.6, 0, 1)[..., None]
    ocean *= (0.92 + 0.12 * n2)[..., None]

    # Land: a climate sketch by latitude, plus the desert boxes.
    tropical = np.array([34, 78, 34], dtype=np.float64)
    savanna = np.array([112, 116, 58], dtype=np.float64)
    temperate = np.array([66, 96, 46], dtype=np.float64)
    boreal = np.array([38, 62, 40], dtype=np.float64)
    tundra = np.array([112, 108, 92], dtype=np.float64)
    desert = np.array([196, 164, 112], dtype=np.float64)
    col = np.zeros((H, W, 3))
    w_trop = 1.0 - smooth(alat, 8, 16)
    w_sav = smooth(alat, 8, 16) * (1.0 - smooth(alat, 22, 32))
    w_temp = smooth(alat, 22, 32) * (1.0 - smooth(alat, 48, 56))
    w_bor = smooth(alat, 48, 56) * (1.0 - smooth(alat, 64, 70))
    w_tun = smooth(alat, 64, 70)
    for w, c in ((w_trop, tropical), (w_sav, savanna), (w_temp, temperate), (w_bor, boreal), (w_tun, tundra)):
        col += w[..., None] * c
    dry = np.zeros((H, W))
    for la0, la1, lo0, lo1 in DESERTS:
        box = smooth(LAT, la0 - 6, la0 + 4) * (1 - smooth(LAT, la1 - 4, la1 + 6)) \
            * smooth(LON, lo0 - 7, lo0 + 5) * (1 - smooth(LON, lo1 - 5, lo1 + 7))
        dry = np.maximum(dry, box)
    # Ragged desert margins: the boxes only set where dryness is possible; noise decides where it shows.
    n3 = wrapped_noise(rng, 40, 4)
    dry = smooth(dry * (0.5 + 0.7 * n1 + 0.6 * (n3 - 0.5)), 0.3, 0.85)
    col = col * (1 - dry[..., None]) + desert * dry[..., None]
    col *= (0.85 + 0.3 * n2)[..., None]
    icecol = np.array([232, 238, 244], dtype=np.float64)
    ice_w = np.clip(ice + smooth(-LAT, 62, 66) * land, 0, 1)
    col = col * (1 - ice_w[..., None]) + icecol * ice_w[..., None]

    img = ocean * (1 - land[..., None]) + col * land[..., None]

    # Clouds: more along the ITCZ (~5 N) and the storm tracks (45-60), fewer over the subtropical highs (~25).
    cover = 0.36 + 0.22 * np.exp(-((LAT - 5) / 8) ** 2) + 0.25 * np.exp(-((alat - 52) / 10) ** 2) \
        - 0.18 * np.exp(-((alat - 25) / 7) ** 2)
    # Domain-warped noise gives swirls and fronts rather than blobs; stretched east-west like real cloud bands.
    c = wrapped_noise(rng, 18, 7)
    wx = wrapped_noise(rng, 6, 3) - 0.5
    wy = wrapped_noise(rng, 6, 3) - 0.5
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float64)
    warped = map_coordinates(c, [np.clip(yy + wy * 60, 0, H - 1), (xx + wx * 200) % W], order=1, mode="wrap")
    fine = wrapped_noise(rng, 48, 3)
    cloud = np.clip((warped * 0.7 + fine * 0.3 - (1 - cover)) * 4.0, 0, 1) * 0.9
    img = img * (1 - cloud[..., None]) + np.array([245, 247, 250]) * cloud[..., None]

    OUT.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").convert("RGBA").save(OUT)
    print("wrote", OUT.relative_to(ROOT))


if __name__ == "__main__":
    main()
