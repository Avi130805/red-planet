#!/usr/bin/env python3
"""Build the Mars geography resources that the mod ships in its jar.

Sources (both NASA, public domain):
  * MOLA MEGDR topography, 16 pixels/degree (Smith et al., Mars Global Surveyor MOLA team), file
    MEGT90N000EB.IMG from the PDS Geosciences Node:
    https://pds-geosciences.wustl.edu/mgs/mgs-m-mola-5-megdr-l3-v1/mgsl_300x/meg016/
    Simple cylindrical, 0..360 degrees east, +90..-90 latitude, int16 big-endian metres relative to the
    MOLA areoid. Reference sphere radius 3396.0 km.
  * TES albedo mosaic, 7410 m/pixel (Christensen et al. 2001), from USGS Astrogeology:
    https://planetarymaps.usgs.gov/mosaic/Mars_MGS_TES_Albedo_mosaic_global_7410m.tif
    Simple cylindrical, -180..180 degrees east, float32 bolometric albedo (0.06..0.32).

Outputs (committed to the repo, so the Gradle build never needs the network):
  * src/main/resources/redplanet/mars/topography.rpgrid  (8 ppd, 10 m quantization, ~3 MB)
  * src/main/resources/redplanet/mars/albedo.rpgrid      (4 ppd, 8-bit, aligned to 0..360 east)
  * src/main/resources/redplanet/mars/roughness.rpgrid   (4 ppd, 8-bit, 4 m steps: sub-km RMS relief)
  * src/main/resources/redplanet/mars/craters.bin        (Robbins & Hynek 2012 craters >= 3 km)
  * src/client/resources/assets/redplanet/sky/stars.bin  (Yale Bright Star Catalogue, Mars equatorial frame)
  * src/client/resources/assets/redplanet/textures/environment/mars_globe.png  (globe texture for the
    interplanetary interlude, synthesized from albedo + MOLA hillshade)
  * docs/images/mars-topography.png, docs/images/mars-albedo.png  (previews for the docs)

Grid file format (".rpgrid", read by io.github.avi130805.redplanet.mars.geo.GeoGrid):
  magic   4 bytes  b"RPGR"
  version u8       1
  kind    u8       0 = int16 samples, 1 = uint8 samples
  width   u32 BE   samples per row (360 * ppd); column c covers longitude [c/ppd, (c+1)/ppd) east
  height  u32 BE   rows (180 * ppd); row r covers latitude [90 - r/ppd, 90 - (r+1)/ppd)
  scale   f32 BE   physical value = sample * scale + offset
  offset  f32 BE
  body    zlib (deflate) stream of rows; each row is horizontally delta-coded:
          s[0] stored as-is, s[i] stored as s[i] - s[i-1] (int16 BE, wrapping) for kind 0,
          or (s[i] - s[i-1]) & 0xFF for kind 1.

Run:  python3 tools/mars-data/build_mars_data.py [--cache DIR]
"""

from __future__ import annotations

import argparse
import os
import struct
import sys
import urllib.request
import zlib
from pathlib import Path

import numpy as np
from PIL import Image

MOLA_URL = ("https://pds-geosciences.wustl.edu/mgs/mgs-m-mola-5-megdr-l3-v1/mgsl_300x/meg016/"
            "megt90n000eb.img")
TES_URL = "https://planetarymaps.usgs.gov/mosaic/Mars_MGS_TES_Albedo_mosaic_global_7410m.tif"

REPO = Path(__file__).resolve().parents[2]
OUT_MAIN = REPO / "src/main/resources/redplanet/mars"
OUT_CLIENT_TEX = REPO / "src/client/resources/assets/redplanet/textures/environment"
OUT_DOCS = REPO / "docs/images"

PPD = 8                # output resolution, pixels per degree
TOPO_QUANT_M = 10.0    # elevation quantization step (metres); 1 block = 100 m vertically in-game


def fetch(url: str, dest: Path) -> Path:
    if dest.exists() and dest.stat().st_size > 0:
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"downloading {url}")
    tmp = dest.with_suffix(dest.suffix + ".part")
    with urllib.request.urlopen(url) as r, open(tmp, "wb") as f:
        while chunk := r.read(1 << 20):
            f.write(chunk)
    tmp.rename(dest)
    return dest


def write_grid(path: Path, samples: np.ndarray, kind: int, scale: float, offset: float) -> None:
    h, w = samples.shape
    if kind == 0:
        s = samples.astype(np.int32)
        d = np.empty_like(s)
        d[:, 0] = s[:, 0]
        d[:, 1:] = s[:, 1:] - s[:, :-1]
        body = d.astype(">i2").tobytes()
    else:
        s = samples.astype(np.int32)
        d = np.empty_like(s)
        d[:, 0] = s[:, 0]
        d[:, 1:] = (s[:, 1:] - s[:, :-1]) & 0xFF
        body = d.astype(np.uint8).tobytes()
    header = b"RPGR" + struct.pack(">BBIIff", 1, kind, w, h, scale, offset)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as f:
        f.write(header)
        f.write(zlib.compress(body, 9))
    print(f"wrote {path.relative_to(REPO)} ({path.stat().st_size / 1e6:.2f} MB, {w}x{h})")


def build_topography(cache: Path) -> np.ndarray:
    raw = np.fromfile(fetch(MOLA_URL, cache / "megt90n000eb.img"), dtype=">i2").reshape(2880, 5760)
    # 16 ppd -> 8 ppd by 2x2 block mean (both grids are cell-registered, so this is exact).
    m8 = raw.astype(np.float64).reshape(1440, 2, 2880, 2).mean(axis=(1, 3))
    q = np.round(m8 / TOPO_QUANT_M).astype(np.int16)
    write_grid(OUT_MAIN / "topography.rpgrid", q, 0, TOPO_QUANT_M, 0.0)
    return m8


def build_roughness(cache: Path) -> np.ndarray:
    """Sub-pixel roughness: RMS (metres) of the 16 ppd MOLA topography about a smoothed version of itself,
    gathered per 8 ppd cell over a 3x3-cell neighbourhood. The northern plains come out near 10-30 m, cratered
    highlands 100-300 m, Valles Marineris walls and volcano flanks higher. Drives the amplitude of procedural
    detail in the terrain generator.
    """
    from scipy.ndimage import gaussian_filter, uniform_filter
    raw = np.fromfile(fetch(MOLA_URL, cache / "megt90n000eb.img"), dtype=">i2").reshape(2880, 5760).astype(np.float64)
    smooth = gaussian_filter(raw, sigma=2.0, mode=("nearest", "wrap"))
    hp2 = (raw - smooth) ** 2
    ms = hp2.reshape(1440, 2, 2880, 2).mean(axis=(1, 3))
    ms = uniform_filter(ms, size=3, mode=("nearest", "wrap"))
    rough = np.sqrt(ms)
    # Regional statistic: 4 ppd is plenty and halves the resource size.
    rough4 = rough.reshape(720, 2, 1440, 2).mean(axis=(1, 3))
    q = np.clip(np.round(rough4 / 4.0), 0, 255).astype(np.uint8)
    write_grid(OUT_MAIN / "roughness.rpgrid", q, 1, 4.0, 0.0)
    print(f"roughness percentiles (m): 10%={np.percentile(rough,10):.0f} 50%={np.percentile(rough,50):.0f} "
          f"90%={np.percentile(rough,90):.0f} 99%={np.percentile(rough,99):.0f}")
    return rough


def build_albedo(cache: Path) -> np.ndarray:
    Image.MAX_IMAGE_PIXELS = None
    a = np.array(Image.open(fetch(TES_URL, cache / "tes_albedo_7410m.tif")), dtype=np.float64)
    assert a.shape == (1440, 2880), a.shape
    # The mosaic spans -180..180 east; roll by half a turn so column 0 starts at 0 degrees east like MOLA.
    a = np.roll(a, -1440, axis=1)
    # Polar rows carry striping/cross-hatch artifacts from sparse coverage; poleward of 82 degrees replace
    # each row by a blend toward its zonal median (the residual caps are bright, smooth ice anyway).
    lat = 90.0 - (np.arange(1440) + 0.5) / PPD
    for r in range(1440):
        if abs(lat[r]) > 82.0:
            t = min(1.0, (abs(lat[r]) - 82.0) / 4.0)
            a[r] = (1 - t) * a[r] + t * np.median(a[r])
    a = np.clip(a, 0.06, 0.32)
    lo, hi = 0.06, 0.32
    # Stored at 4 ppd (albedo varies over hundreds of km); the 8 ppd array is still used for the globe texture.
    a4 = a.reshape(720, 2, 1440, 2).mean(axis=(1, 3))
    q = np.round((a4 - lo) / (hi - lo) * 255.0).astype(np.uint8)
    write_grid(OUT_MAIN / "albedo.rpgrid", q, 1, (hi - lo) / 255.0, lo)
    return a


def hillshade(elev: np.ndarray, azimuth_deg=315.0, altitude_deg=35.0, z=12.0) -> np.ndarray:
    gy, gx = np.gradient(elev)
    # pixel spacing ~7.4 km; exaggerate vertical so relief reads at globe scale
    gx = gx * z / 7400.0
    gy = gy * z / 7400.0
    slope = np.pi / 2 - np.arctan(np.hypot(gx, gy))
    aspect = np.arctan2(-gx, gy)
    az = np.radians(azimuth_deg)
    alt = np.radians(altitude_deg)
    return np.clip(np.sin(alt) * np.sin(slope) + np.cos(alt) * np.cos(slope) * np.cos(az - aspect), 0, 1)


def build_globe(topo: np.ndarray, albedo: np.ndarray) -> None:
    """A colour globe texture: albedo mapped onto a Mars palette, modulated by MOLA hillshade.

    The palette endpoints come from true-colour imagery: dark basaltic terrains (e.g. Syrtis Major)
    are a dark grey-brown, bright dusty terrains (Tharsis, Arabia) a butterscotch orange.
    """
    t = np.clip((albedo - 0.08) / (0.29 - 0.08), 0, 1)[..., None]
    dark = np.array([74, 55, 44], dtype=np.float64)
    mid = np.array([150, 92, 58], dtype=np.float64)
    bright = np.array([214, 150, 102], dtype=np.float64)
    col = np.where(t < 0.5, dark + (mid - dark) * (t / 0.5), mid + (bright - mid) * ((t - 0.5) / 0.5))
    lat = 90.0 - (np.arange(1440) + 0.5) / PPD
    # residual polar caps: bright white where albedo is high near the poles
    cap = ((np.abs(lat) > 78.0)[:, None] & (albedo > 0.24)).astype(np.float64)[..., None]
    col = col * (1 - cap) + np.array([236, 232, 228]) * cap
    hs = hillshade(topo)[..., None]
    col = col * (0.55 + 0.6 * hs)
    img = Image.fromarray(np.clip(col, 0, 255).astype(np.uint8), "RGB").resize((1024, 512), Image.LANCZOS)
    OUT_CLIENT_TEX.mkdir(parents=True, exist_ok=True)
    img.save(OUT_CLIENT_TEX / "mars_globe.png", optimize=True)
    print(f"wrote {(OUT_CLIENT_TEX / 'mars_globe.png').relative_to(REPO)}")
    OUT_DOCS.mkdir(parents=True, exist_ok=True)
    img.resize((960, 480), Image.LANCZOS).save(OUT_DOCS / "mars-globe-texture.png", optimize=True)


def build_previews(topo: np.ndarray, albedo: np.ndarray) -> None:
    OUT_DOCS.mkdir(parents=True, exist_ok=True)
    n = np.clip((topo + 8200.0) / (21200.0 + 8200.0), 0, 1)
    # simple hypsometric tint
    stops = np.array([[0.0, 30, 40, 90], [0.18, 60, 110, 170], [0.28, 120, 170, 120], [0.4, 220, 200, 120],
                      [0.6, 190, 120, 70], [0.8, 150, 90, 80], [1.0, 255, 255, 255]])
    rgb = np.stack([np.interp(n, stops[:, 0], stops[:, k]) for k in (1, 2, 3)], axis=-1)
    rgb *= (0.6 + 0.5 * hillshade(topo))[..., None]
    Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8)).resize((1440, 720), Image.LANCZOS).save(
        OUT_DOCS / "mars-topography.png", optimize=True)
    g = np.clip((albedo - 0.06) / 0.26 * 255, 0, 255).astype(np.uint8)
    Image.fromarray(g).resize((1440, 720), Image.LANCZOS).save(OUT_DOCS / "mars-albedo.png", optimize=True)
    print("wrote docs previews")


BSC5_URL = "https://cdsarc.cds.unistra.fr/ftp/V/50/catalog.gz"
OUT_CLIENT_SKY = REPO / "src/client/resources/assets/redplanet/sky"

# IAU WGCCRE 2009 (Archinal et al. 2011) Mars north pole, ICRF J2000.
MARS_POLE_RA = 317.68143
MARS_POLE_DEC = 52.88650
# Mars orbit, J2000 ecliptic (Standish, JPL "Keplerian Elements for Approximate Positions of the Major Planets").
MARS_INCLINATION = 1.84969142
MARS_ASC_NODE = 49.55953891
EARTH_OBLIQUITY_J2000 = 23.43928


def mars_equatorial_basis() -> np.ndarray:
    """Rows: Mars equatorial frame axes (x = Mars vernal equinox, z = Mars north pole) in ICRF."""
    a, d = np.radians(MARS_POLE_RA), np.radians(MARS_POLE_DEC)
    pole = np.array([np.cos(d) * np.cos(a), np.cos(d) * np.sin(a), np.sin(d)])
    i, node, eps = np.radians(MARS_INCLINATION), np.radians(MARS_ASC_NODE), np.radians(EARTH_OBLIQUITY_J2000)
    n_ecl = np.array([np.sin(i) * np.sin(node), -np.sin(i) * np.cos(node), np.cos(i)])
    rot = np.array([[1, 0, 0], [0, np.cos(eps), -np.sin(eps)], [0, np.sin(eps), np.cos(eps)]])
    n_orb = rot @ n_ecl
    # Vernal equinox: the Sun crosses the Mars equator northward, i.e. direction pole x orbit-normal.
    x = np.cross(pole, n_orb)
    x /= np.linalg.norm(x)
    y = np.cross(pole, x)
    obliquity = np.degrees(np.arccos(np.dot(pole, n_orb)))
    assert abs(obliquity - 25.19) < 0.05, obliquity
    return np.stack([x, y, pole])


def build_star_catalog(cache: Path) -> None:
    """Yale Bright Star Catalogue, 5th revised ed. (Hoffleit & Warren 1991), CDS V/50, stars to V = 6.0,
    rotated into the Mars equatorial frame used by MarsAstronomy.

    stars.bin: magic "RPST", u32 count, then per star: f32 x, y, z (unit vector), f32 Vmag, f32 B-V.
    """
    import gzip
    basis = mars_equatorial_basis()
    rows = []
    with gzip.open(fetch(BSC5_URL, cache / "bsc5_catalog.gz"), "rt", encoding="latin-1") as f:
        for line in f:
            line = line.rstrip("\n").ljust(197)
            if not line[75:77].strip() or not line[102:107].strip():
                continue  # no J2000 position (novae, non-stellar entries) or no V
            ra = (int(line[75:77]) + int(line[77:79]) / 60.0 + float(line[79:83]) / 3600.0) * 15.0
            dec = int(line[84:86]) + int(line[86:88]) / 60.0 + int(line[88:90]) / 3600.0
            if line[83] == "-":
                dec = -dec
            vmag = float(line[102:107])
            bv = float(line[109:114]) if line[109:114].strip() else 0.6
            if vmag > 6.0:
                continue
            a, d = np.radians(ra), np.radians(dec)
            v = np.array([np.cos(d) * np.cos(a), np.cos(d) * np.sin(a), np.sin(d)])
            rows.append((*(basis @ v), vmag, bv))
    OUT_CLIENT_SKY.mkdir(parents=True, exist_ok=True)
    with open(OUT_CLIENT_SKY / "stars.bin", "wb") as out:
        out.write(b"RPST" + struct.pack(">I", len(rows)))
        for r in rows:
            out.write(struct.pack(">5f", *r))
    # Report the star nearest the Mars north celestial pole (expect Deneb's neighbourhood in Cygnus).
    best = max(rows, key=lambda r: r[2])
    print(f"wrote {(OUT_CLIENT_SKY / 'stars.bin').relative_to(REPO)}: {len(rows)} stars; "
          f"brightest-near-pole check: star at {np.degrees(np.arcsin(best[2])):.2f} deg dec, V={best[3]}")


ROBBINS_URL = "http://craters.sjrdesign.net/RobbinsCraterDatabase_20121016.tsv.zip"
CRATER_MIN_DIAMETER_KM = 3.0


def build_crater_catalog(cache: Path) -> None:
    """Robbins & Hynek (2012) global Mars crater database, craters >= 3 km.

    craters.bin: magic "RPCR", u32 count, then per crater (sorted by latitude):
      f32 lat (deg N), f32 lon (deg E, 0..360), u16 diameter (10 m units), u16 rim-to-floor depth (m, 0 = unknown),
      u16 rim height (m, 0 = unknown), u8 flags, u8 degradation state (0 unknown, 1 most degraded .. 4 fresh).
    flags: bit0 central peak, bit1 central pit, bit2 layered ("rampart") ejecta, bit3 double/multiple layered ejecta,
           bit4 terraced walls, bit5 flat floor (complex).
    """
    import csv
    import io
    import zipfile
    zpath = fetch(ROBBINS_URL, cache / "RobbinsCraterDatabase_20121016.tsv.zip")
    with zipfile.ZipFile(zpath) as z:
        name = next(n for n in z.namelist() if n.endswith(".tsv") and not n.startswith("__MACOSX"))
        text = io.TextIOWrapper(z.open(name), encoding="latin-1")
        reader = csv.reader(text, delimiter="\t")
        hdr = next(reader)
        ix = {h: i for i, h in enumerate(hdr)}

        def num(row, key):
            try:
                return float(row[ix[key]])
            except (ValueError, IndexError):
                return float("nan")

        out = []
        for row in reader:
            d = num(row, "DIAM_CIRCLE_IMAGE")
            if not d >= CRATER_MIN_DIAMETER_KM or row[ix["CONFIDENCE_IMPACT_CRATER"]] not in ("3", "4"):
                continue
            lat = num(row, "LATITUDE_CIRCLE_IMAGE")
            lon = num(row, "LONGITUDE_CIRCLE_IMAGE") % 360.0
            depth = num(row, "DEPTH_RIMFLOOR_TOPOG")
            rim = num(row, "DEPTH_RIMHEIGHT_TOPOG")
            m1 = row[ix["MORPHOLOGY_CRATER_1"]]
            m2 = row[ix["MORPHOLOGY_CRATER_2"]]
            ej = row[ix["MORPHOLOGY_EJECTA_1"]]
            flags = 0
            if "CPk" in m1 or "PkRg" in m1:
                flags |= 1
            if "Pt" in m1:
                flags |= 2
            if "LE" in ej:
                flags |= 4
            if ej.startswith("DLE") or ej.startswith("MLE") or "/DLE" in ej:
                flags |= 8
            if "Terraced" in m2:
                flags |= 16
            if "FF" in m1:
                flags |= 32
            deg = row[ix["DEGRADATION_STATE"]].strip()
            out.append((lat, lon, d, depth, rim, flags, int(deg) if deg.isdigit() else 0))
    out.sort(key=lambda c: c[0])
    path = OUT_MAIN / "craters.bin"
    body = bytearray()
    for lat, lon, d, depth, rim, flags, deg in out:
        body += struct.pack(">ffHHHBB", lat, lon, min(65535, int(round(d * 100))),
                            0 if not depth > 0 else min(65535, int(round(depth * 1000))),
                            0 if not rim > 0 else min(65535, int(round(rim * 1000))), flags, deg)
    with open(path, "wb") as f:
        f.write(b"RPCR" + struct.pack(">I", len(out)))
        f.write(zlib.compress(bytes(body), 9))
    print(f"wrote {path.relative_to(REPO)}: {len(out)} craters >= {CRATER_MIN_DIAMETER_KM} km "
          f"({path.stat().st_size / 1e6:.2f} MB)")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=os.environ.get("MARS_DATA_CACHE", str(Path.home() / ".cache/redplanet-mars-data")))
    args = ap.parse_args()
    cache = Path(args.cache)
    topo = build_topography(cache)
    albedo = build_albedo(cache)
    build_roughness(cache)
    build_globe(topo, albedo)
    build_previews(topo, albedo)
    build_star_catalog(cache)
    build_crater_catalog(cache)
    print(f"topography range {topo.min():.0f} .. {topo.max():.0f} m")
    return 0


if __name__ == "__main__":
    sys.exit(main())
