#!/usr/bin/env python3
"""Cut the trailer together: shots, title cards, text, grading, sound effects and the score, into one 1080p video.

    python3 tools/trailer/assemble.py [--out build/trailer/red-planet-trailer-1080p.mp4] [--preview]

Reads the edit (edit.py), the filmed shots (build/trailer/shots/<clip>.mp4 from TrailerClientGameTest), the score
(build/trailer/score.wav from score.py, rendered first if missing) and the mod's own sound effects. Every frame is
composited here in numpy (shake, flashes, fades, captions, letterbox, grade, grain) and piped into ffmpeg: H.264 High
at CRF 16, 1920x1080, 30 fps, yuv420p, AAC 320 kb/s, +faststart. --preview renders at 960x540 with fast settings.
A shot whose clip hasn't been filmed yet shows a labelled placeholder, so the edit can be reviewed early.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
from PIL import Image, ImageDraw
from scipy import signal

sys.path.insert(0, str(Path(__file__).resolve().parent))
import edit as edit_mod  # noqa: E402
import titles  # noqa: E402
from edit import FPS, HEIGHT, WIDTH  # noqa: E402

SHOTS = Path("build/trailer/shots")
SOUNDS = Path("src/client/resources/assets/redplanet/sounds")
SR = 48_000
LETTERBOX = 132  # 2.39:1 inside 1920x1080 (the cards stay full frame)


# ------------------------------------------------------------------------------------------- video in

class ClipReader:
    """Sequential frames from a filmed shot (decoded by ffmpeg), with seeking forward by reading."""

    def __init__(self, path: Path, start: float, speed: float):
        self.path = path
        self.speed = speed
        self.proc = subprocess.Popen(
            ["ffmpeg", "-loglevel", "error", "-ss", f"{start:.3f}", "-i", str(path), "-f", "rawvideo", "-pix_fmt", "rgb24",
             "-s", f"{WIDTH}x{HEIGHT}", "-"], stdout=subprocess.PIPE)
        self.index = -1
        self.frame = np.zeros((HEIGHT, WIDTH, 3), dtype=np.uint8)

    def at(self, k: float) -> np.ndarray:
        """Frame k (in clip frames from the start point, fractional speeds pick the nearest frame)."""
        want = int(round(k * self.speed))
        while self.index < want:
            raw = self.proc.stdout.read(WIDTH * HEIGHT * 3)
            if len(raw) < WIDTH * HEIGHT * 3:
                break  # ran out: hold the last frame
            self.frame = np.frombuffer(raw, dtype=np.uint8).reshape(HEIGHT, WIDTH, 3)
            self.index += 1
        return self.frame

    def close(self) -> None:
        self.proc.kill()


def placeholder(name: str) -> np.ndarray:
    img = Image.new("RGB", (WIDTH, HEIGHT), (24, 18, 16))
    d = ImageDraw.Draw(img)
    d.rectangle([60, 60, WIDTH - 60, HEIGHT - 60], outline=(120, 70, 50), width=4)
    d.text((WIDTH / 2, HEIGHT / 2), f"[ {name} ]", font=titles.font("head", 64), fill=(220, 160, 130), anchor="mm")
    return np.asarray(img)


# ------------------------------------------------------------------------------------------- grading and effects

def grade(img: np.ndarray, t: float) -> np.ndarray:
    """A light filmic grade: a gentle S-curve, a touch more saturation, warm highlights and cool shadows."""
    x = img
    x = x * x * (3 - 2 * x) * 0.35 + x * 0.65
    luma = (x * np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)).sum(-1, keepdims=True)
    x = luma + (x - luma) * 1.12
    shadows = np.clip(1 - luma * 2.0, 0, 1)
    highs = np.clip(luma * 2.0 - 1, 0, 1)
    x = x + shadows * np.array([-0.012, 0.0, 0.018], dtype=np.float32) + highs * np.array([0.02, 0.008, -0.015], dtype=np.float32)
    return np.clip(x, 0, 1)


_VIGNETTE = None


def vignette(img: np.ndarray) -> np.ndarray:
    global _VIGNETTE
    if _VIGNETTE is None:
        yy, xx = np.mgrid[0:HEIGHT, 0:WIDTH].astype(np.float32)
        d = np.sqrt(((xx - WIDTH / 2) / (WIDTH / 2)) ** 2 + ((yy - HEIGHT / 2) / (HEIGHT / 2)) ** 2)
        _VIGNETTE = (1 - 0.22 * np.clip(d - 0.55, 0, 1) ** 1.6)[..., None]
    return img * _VIGNETTE


def grain(img: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    n = rng.standard_normal((HEIGHT // 2, WIDTH // 2, 1)).astype(np.float32)
    n = np.repeat(np.repeat(n, 2, 0), 2, 1)
    return img + n * 0.012


def shake_offset(t: float, amplitude: float, seed: int) -> tuple[int, int]:
    """Smooth random shake (sum of sines at incommensurate rates), decaying through the shot."""
    if amplitude <= 0:
        return 0, 0
    rng = np.random.default_rng(seed)
    ph = rng.uniform(0, 2 * np.pi, 6)
    dx = np.sin(31.0 * t + ph[0]) * 0.6 + np.sin(17.3 * t + ph[1]) * 0.3 + np.sin(53.1 * t + ph[2]) * 0.1
    dy = np.sin(27.0 * t + ph[3]) * 0.6 + np.sin(13.7 * t + ph[4]) * 0.3 + np.sin(47.9 * t + ph[5]) * 0.1
    k = amplitude * np.exp(-t / 1.5)
    return int(round(dx * k)), int(round(dy * k))


def shift(img: np.ndarray, dx: int, dy: int) -> np.ndarray:
    """Shift with a slight zoom so no edge shows."""
    if dx == 0 and dy == 0:
        return img
    h, w = img.shape[:2]
    pad = max(abs(dx), abs(dy)) + 2
    zoom = max((w + 2 * pad) / w, (h + 2 * pad) / h)
    bw, bh = int(np.ceil(w * zoom)), int(np.ceil(h * zoom))
    big = np.asarray(Image.fromarray((img * 255).astype(np.uint8)).resize((bw, bh), Image.BICUBIC), dtype=np.float32) / 255
    ox = min(max(0, (bw - w) // 2 + dx), bw - w)
    oy = min(max(0, (bh - h) // 2 + dy), bh - h)
    return big[oy:oy + h, ox:ox + w]


def punch(layer: Image.Image, scale: float) -> Image.Image:
    """The overlay scaled about the frame's centre (cropped back to the frame)."""
    if abs(scale - 1.0) < 1e-3:
        return layer
    w, h = layer.size
    big = layer.resize((int(w * scale), int(h * scale)), Image.BICUBIC)
    ox, oy = (big.width - w) // 2, (big.height - h) // 2
    return big.crop((ox, oy, ox + w, oy + h))


def letterbox(img: np.ndarray) -> np.ndarray:
    img = img.copy()
    img[:LETTERBOX] = 0
    img[HEIGHT - LETTERBOX:] = 0
    return img


# ------------------------------------------------------------------------------------------- audio

def load_sound(name: str) -> np.ndarray:
    """A mod sound (mono ogg, any variant numbering) resampled to 48 kHz."""
    for candidate in (SOUNDS / f"{name}.ogg", SOUNDS / f"{name}1.ogg"):
        if candidate.exists():
            data, sr = sf.read(candidate, always_2d=False)
            if data.ndim > 1:
                data = data.mean(axis=1)
            if sr != SR:
                data = signal.resample_poly(data, SR, sr)
            return data.astype(np.float64)
    print(f"  (missing sound {name}; skipped)")
    return np.zeros(1)


def effects_track(e: edit_mod.Edit, seconds: float) -> np.ndarray:
    out = np.zeros(int(seconds * SR) + SR)
    for fx in e.effects:
        x = load_sound(fx.sound)
        if fx.pitch != 1.0:
            x = signal.resample(x, int(len(x) / fx.pitch))
        if fx.loop_until is not None:
            need = int((fx.loop_until - fx.at) * SR)
            reps = int(np.ceil(need / max(1, len(x)))) + 1
            x = np.tile(x, reps)[:need]
        if fx.length is not None:
            x = x[: int(fx.length * SR)]
        nf = min(len(x), int(fx.fade_out * SR))
        if nf > 0:
            x[-nf:] *= np.linspace(1, 0, nf)
        i = int(fx.at * SR)
        x = x[: max(0, len(out) - i)] * 10 ** (fx.gain_db / 20)
        out[i:i + len(x)] += x
    return out


def mix_audio(e: edit_mod.Edit, seconds: float) -> np.ndarray:
    score_path = Path("build/trailer/score.wav")
    if not score_path.exists():
        subprocess.run([sys.executable, str(Path(__file__).with_name("score.py")), "--out", str(score_path)], check=True)
    music, sr = sf.read(score_path, always_2d=True)
    if sr != SR:
        music = signal.resample_poly(music, SR, sr, axis=0)
    n = int(seconds * SR)
    music = np.pad(music, ((0, max(0, n - len(music))), (0, 0)))[:n]
    fx = effects_track(e, seconds)[:n]
    # duck the music under the loud effects (rockets): a smoothed envelope of the effects
    env = np.abs(signal.hilbert(fx)) if len(fx) < 2 ** 23 else np.abs(fx)
    env = signal.sosfiltfilt(signal.butter(1, 3.0 / (SR / 2), output="sos"), env)
    duck = 1.0 / (1.0 + 1.6 * np.clip(env, 0, 1))
    mix = music * duck[:, None] * 0.9 + np.stack([fx, fx], axis=1) * 0.8
    peak = np.max(np.abs(mix))
    mix = np.tanh(mix / max(peak, 1e-6) * 1.4) / np.tanh(1.4) * 0.93
    return mix.astype(np.float32)


# ------------------------------------------------------------------------------------------- frames

def render(e: edit_mod.Edit, out: Path, preview: bool) -> None:
    seconds = e.duration
    total = int(round(seconds * FPS))
    audio = mix_audio(e, seconds)
    wav = out.with_suffix(".wav")
    sf.write(wav, audio, SR, subtype="PCM_24")
    w, h = (960, 540) if preview else (WIDTH, HEIGHT)
    enc = subprocess.Popen(
        ["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{WIDTH}x{HEIGHT}", "-r", str(FPS),
         "-i", "-", "-i", str(wav), "-vf", f"scale={w}:{h}:flags=lanczos", "-c:v", "libx264",
         "-preset", "veryfast" if preview else "slow", "-crf", "23" if preview else "16", "-profile:v", "high",
         "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k" if preview else "320k", "-movflags", "+faststart",
         "-shortest", str(out)], stdin=subprocess.PIPE)
    rng = np.random.default_rng(3)
    cards: dict[int, tuple] = {}  # shot index -> (frame generator, last frame): cards are streamed, not stored
    readers: dict[int, ClipReader] = {}
    for f in range(total):
        t = f / FPS
        idx, shot = next(((i, s) for i, s in enumerate(e.shots) if s.start <= t < s.end), (len(e.shots) - 1, e.shots[-1]))
        local = t - shot.start
        is_card = shot.clip.startswith("card:")
        if is_card:
            if idx not in cards:
                cards.clear()
                cards[idx] = (titles.card_frames(shot.clip[5:], shot.end - shot.start + 0.1, FPS), None)
            gen, last = cards[idx]
            img = next(gen, last)
            if img is None:
                img = np.zeros((HEIGHT, WIDTH, 3), dtype=np.float32)
            cards[idx] = (gen, img)
        else:
            path = SHOTS / f"{shot.clip}.mp4"
            if path.exists():
                if idx not in readers:
                    for k in [k for k in readers if k != idx]:
                        readers.pop(k).close()
                    readers[idx] = ClipReader(path, shot.clip_in, shot.speed)
                img = readers[idx].at(local * FPS).astype(np.float32) / 255
            else:
                img = placeholder(shot.clip).astype(np.float32) / 255
            img = grade(img, t)
            dx, dy = shake_offset(local, shot.shake, idx)
            img = shift(img, dx, dy)
            img = vignette(img)
        if shot.flash > 0 and local < shot.flash * 3:
            img = img + (1 - img) * np.exp(-local / shot.flash) * 0.85
        for text in e.texts:
            if text.start <= t < text.end:
                k = min(1.0, (t - text.start) / text.fade, (text.end - t) / text.fade)
                layer = titles.overlay(text.lines, text.style)
                if text.style == "statement":
                    # a punch-in: the words land from 12 % larger in the first 0.2 s
                    layer = punch(layer, 1.0 + 0.12 * (1.0 - titles.ease((t - text.start) / 0.2)))
                img = titles.composite(img, layer, k)
        if shot.fade_in > 0 and local < shot.fade_in:
            img = img * (local / shot.fade_in)
        if shot.fade_out > 0 and shot.end - t < shot.fade_out:
            img = img * ((shot.end - t) / shot.fade_out)
        if not is_card:
            img = letterbox(grain(img, rng))
        enc.stdin.write((np.clip(img, 0, 1) * 255).astype(np.uint8).tobytes())
        if f % 90 == 0:
            print(f"  frame {f}/{total} ({t:.1f} s, {shot.clip})", flush=True)
    for r in readers.values():
        r.close()
    enc.stdin.close()
    enc.wait()
    print(f"wrote {out}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", default="build/trailer/red-planet-trailer-1080p.mp4")
    ap.add_argument("--preview", action="store_true")
    args = ap.parse_args()
    out = Path(args.out)
    if args.preview and args.out == ap.get_default("out"):
        out = out.with_name("red-planet-trailer-preview.mp4")
    out.parent.mkdir(parents=True, exist_ok=True)
    render(edit_mod.trailer(), out, args.preview)


if __name__ == "__main__":
    main()
