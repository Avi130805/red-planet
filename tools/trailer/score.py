#!/usr/bin/env python3
"""Synthesize the trailer's score from the edit's cues (edit.py): nothing sampled, everything built from oscillators,
noise, filters and a synthetic reverb, so the music lands exactly on the cuts and can be re-rendered when the edit
changes.

    python3 tools/trailer/score.py [--out build/trailer/score.wav]

The piece is in D minor. Sections (cue kind "section", param = name):
  title   low drone and a distant choir-like pad under the title card
  act1    a pulsing 16th-note ostinato at 100 BPM over i-VI-III-VII, toms join at the first liftoff
  space   weightless: soft pads, a slow bell motif, no drums
  mars    wonder: wide Dorian pads and a slow lead melody, light pulse
  build   the pulse returns with a ticking hi-hat: building the base
  climax  full drums, braams on the downbeats, the ostinato an octave up
  end     the final chord rings out under the end card
One-shot cues: boom (a sub drop), braam (a distorted brass-like blast), hit (an orchestral-style impact), riser
(noise and pitch sweep ending on the next downbeat).
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy import signal

sys.path.insert(0, str(Path(__file__).resolve().parent))
import edit as edit_mod  # noqa: E402

SR = 48_000
BPM = 100.0
BEAT = 60.0 / BPM
RNG = np.random.default_rng(20261004)

# D minor, i-VI-III-VII: Dm, Bb, F, C (MIDI note numbers of the chord tones, root first)
PROGRESSION = [(50, 53, 57), (46, 50, 53), (41, 45, 48), (48, 52, 55)]
# Mars: D Dorian colour: Dm9, G/D, Fmaj7, Em7 (the B natural is the wonder)
MARS_CHORDS = [(50, 53, 57, 64), (50, 55, 59, 62), (53, 57, 60, 64), (52, 55, 59, 62)]


def hz(midi: float) -> float:
    return 440.0 * 2.0 ** ((midi - 69.0) / 12.0)


def t_axis(seconds: float) -> np.ndarray:
    return np.arange(int(seconds * SR)) / SR


# ------------------------------------------------------------------------------------------- oscillators and shaping

def saw(freq: float | np.ndarray, seconds: float, phase: float = 0.0) -> np.ndarray:
    """Band-limited sawtooth (additive, harmonics up to 18 kHz). freq may vary over time (array)."""
    n = int(seconds * SR)
    f = np.broadcast_to(np.asarray(freq, dtype=np.float64), (n,)) if np.ndim(freq) else np.full(n, float(freq))
    ph = 2 * np.pi * np.cumsum(f) / SR + phase
    top = int(max(1, min(60, 18_000 / max(20.0, float(np.max(f))))))
    out = np.zeros(n)
    for k in range(1, top + 1):
        out += ((-1) ** (k + 1)) * np.sin(k * ph) / k
    return out * (2 / np.pi)


def tri(freq: float, seconds: float) -> np.ndarray:
    n = int(seconds * SR)
    ph = 2 * np.pi * freq * np.arange(n) / SR
    out = np.zeros(n)
    for k in range(1, 16, 2):
        out += ((-1) ** ((k - 1) // 2)) * np.sin(k * ph) / (k * k)
    return out * (8 / np.pi ** 2)


def adsr(n: int, a: float, d: float, s: float, r: float, hold: float | None = None) -> np.ndarray:
    """Attack, decay, sustain level, release (seconds). Hold = time until release starts (default: fills n)."""
    env = np.zeros(n)
    na, nd, nr = int(a * SR), int(d * SR), int(r * SR)
    nh = n - nr if hold is None else min(n, int(hold * SR))
    i = 0
    seg = min(na, nh)
    env[:seg] = np.linspace(0, 1, seg, endpoint=False) if seg else env[:seg]
    i = seg
    seg = min(nd, max(0, nh - i))
    env[i:i + seg] = np.linspace(1, s, seg, endpoint=False) if seg else env[i:i + seg]
    i += seg
    env[i:nh] = s
    level = env[nh - 1] if nh > 0 else 0.0
    seg = max(0, min(nr, n - nh))
    env[nh:nh + seg] = np.linspace(level, 0, seg) if seg else env[nh:nh + seg]
    return env


def lowpass(x: np.ndarray, fc: float, q: float = 0.707) -> np.ndarray:
    fc = min(fc, SR * 0.45)
    b, a = signal.iirfilter(2, fc / (SR / 2), btype="low", ftype="butter")
    return signal.lfilter(b, a, x)


def highpass(x: np.ndarray, fc: float) -> np.ndarray:
    sos = signal.butter(2, fc / (SR / 2), btype="high", output="sos")
    return signal.sosfilt(sos, x)


def bandpass(x: np.ndarray, lo: float, hi: float, order: int = 2) -> np.ndarray:
    sos = signal.butter(order, [lo / (SR / 2), min(hi, SR * 0.45) / (SR / 2)], btype="band", output="sos")
    return signal.sosfilt(sos, x)


def sweep_lowpass(x: np.ndarray, cutoff: np.ndarray, block: int = 256) -> np.ndarray:
    """A time-varying 2-pole low-pass: coefficients updated every block, filter state carried across."""
    out = np.empty_like(x)
    zi = np.zeros(2)
    for i in range(0, len(x), block):
        fc = float(np.clip(cutoff[min(i, len(cutoff) - 1)], 30.0, SR * 0.45))
        b, a = signal.iirfilter(2, fc / (SR / 2), btype="low", ftype="butter")
        out[i:i + block], zi = signal.lfilter(b, a, x[i:i + block], zi=zi)
    return out


def sweep_bandpass(x: np.ndarray, centre: np.ndarray, q: float = 4.0, block: int = 256) -> np.ndarray:
    out = np.empty_like(x)
    zi = np.zeros(2)
    for i in range(0, len(x), block):
        fc = float(np.clip(centre[min(i, len(centre) - 1)], 40.0, SR * 0.4))
        b, a = signal.iirpeak(fc / (SR / 2), q)
        out[i:i + block], zi = signal.lfilter(b, a, x[i:i + block], zi=zi)
    return out


def noise(seconds: float) -> np.ndarray:
    return RNG.standard_normal(int(seconds * SR))


def norm(x: np.ndarray) -> np.ndarray:
    p = np.max(np.abs(x))
    return x / p if p > 0 else x


# ------------------------------------------------------------------------------------------- instruments

def boom(seconds: float = 3.5) -> np.ndarray:
    """A sub drop: a sine sweeping 95 -> 28 Hz, a noise thud, long decay."""
    t = t_axis(seconds)
    f = 28 + 67 * np.exp(-t / 0.35)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 1.4)
    thud = lowpass(noise(seconds), 180) * np.exp(-t / 0.08) * 2.5
    return np.tanh(1.6 * (body + thud)) * 0.95


def braam(seconds: float, root: int = 38) -> np.ndarray:
    """The brass-like blast: detuned saws on D1/D2/A2/D3, distorted, with a filter that opens and closes."""
    t = t_axis(seconds)
    voices = np.zeros(len(t))
    for note, gain in ((root, 1.0), (root + 12, 0.9), (root + 19, 0.6), (root + 24, 0.35)):
        for det in (-0.12, 0.0, 0.11):
            voices += gain * saw(hz(note + det), seconds, RNG.uniform(0, 6.28))
    cutoff = 180 + 2600 * np.exp(-((t - 0.25) / 0.45) ** 2) + 500 * np.exp(-t / 1.5)
    x = sweep_lowpass(voices, cutoff)
    x = np.tanh(2.4 * norm(x)) * adsr(len(t), 0.04, 0.6, 0.55, min(1.2, seconds * 0.5))
    return x


def hit(seconds: float = 4.0) -> np.ndarray:
    """An impact: a low drum (pitch-dropping sine), a noise crack, a metallic ring."""
    t = t_axis(seconds)
    f = 45 + 120 * np.exp(-t / 0.04)
    drum = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.9)
    crack = bandpass(noise(seconds), 400, 6000) * np.exp(-t / 0.05) * 0.6
    ring = sum(np.sin(2 * np.pi * fr * t + RNG.uniform(0, 6.28)) * np.exp(-t / dec) * g
               for fr, dec, g in ((220.0, 1.6, 0.08), (331.0, 1.1, 0.06), (523.0, 0.8, 0.04), (787.0, 0.5, 0.03)))
    return np.tanh(1.3 * (drum + crack + ring))


def riser(seconds: float) -> np.ndarray:
    """White noise through a band-pass sweeping 300 Hz -> 9 kHz, plus a saw cluster rising a fifth; ends abruptly."""
    t = t_axis(seconds)
    u = t / seconds
    centre = 300 * (30 ** (u ** 1.6))
    whoosh = sweep_bandpass(noise(seconds), centre, q=2.5)
    pitch = hz(50) * (2 ** (7 / 12 * u ** 2))
    cluster = sum(saw(pitch * (1 + d), seconds) for d in (-0.004, 0.0, 0.005)) / 3
    cluster = sweep_lowpass(cluster, 300 + 5000 * u ** 2)
    env = u ** 2.2
    return (0.7 * norm(whoosh) + 0.35 * norm(cluster)) * env


def pad(chord: tuple[int, ...], seconds: float, bright: float = 1400.0, attack: float = 0.8, release: float = 1.2) -> np.ndarray:
    n = int(seconds * SR)
    x = np.zeros(n)
    for note in chord:
        for det in (-0.08, -0.03, 0.03, 0.08):
            x += saw(hz(note + det), seconds, RNG.uniform(0, 6.28))
    x = lowpass(lowpass(x, bright), bright * 1.3)
    return norm(x) * adsr(n, attack, 0.5, 0.85, release)


def pluck(note: int, seconds: float, bright: float = 3200.0) -> np.ndarray:
    n = int(seconds * SR)
    t = t_axis(seconds)
    x = saw(hz(note), seconds) + 0.5 * saw(hz(note + 0.07), seconds)
    cutoff = 220 + bright * np.exp(-t / 0.09)
    x = sweep_lowpass(x, cutoff, block=128)
    return x * np.exp(-t / 0.22) * adsr(n, 0.003, 0.05, 1.0, 0.02)


def bell(note: int, seconds: float) -> np.ndarray:
    """An FM bell: modulator at 3.5x, index decaying."""
    t = t_axis(seconds)
    f = hz(note)
    index = 3.0 * np.exp(-t / 0.6)
    x = np.sin(2 * np.pi * f * t + index * np.sin(2 * np.pi * 3.5 * f * t))
    return x * np.exp(-t / 2.2) * adsr(len(t), 0.002, 0.1, 1.0, 0.05)


def lead(note: int, seconds: float) -> np.ndarray:
    """A soft lead: triangle plus a sine an octave down, with delayed vibrato."""
    t = t_axis(seconds)
    vib = 1 + 0.004 * np.sin(2 * np.pi * 5.2 * t) * np.clip((t - 0.35) / 0.4, 0, 1)
    f = hz(note) * vib
    ph = 2 * np.pi * np.cumsum(f) / SR
    x = 0.8 * (2 / np.pi) * np.arcsin(np.sin(ph)) + 0.35 * np.sin(ph / 2)
    return lowpass(x, 2600) * adsr(len(t), 0.18, 0.4, 0.8, 0.5)


def taiko(seconds: float = 1.2, pitch: float = 1.0) -> np.ndarray:
    t = t_axis(seconds)
    f = (52 + 110 * np.exp(-t / 0.03)) * pitch
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.35)
    skin = bandpass(noise(seconds), 150, 1800) * np.exp(-t / 0.03) * 0.5
    return np.tanh(1.8 * (body + skin))


def tom(seconds: float = 0.6, pitch: float = 1.0) -> np.ndarray:
    t = t_axis(seconds)
    f = (95 + 80 * np.exp(-t / 0.05)) * pitch
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.18) + bandpass(noise(seconds), 300, 3000) * np.exp(-t / 0.02) * 0.3


def hat(seconds: float = 0.08) -> np.ndarray:
    t = t_axis(seconds)
    return highpass(noise(seconds), 7000) * np.exp(-t / 0.018)


def cymbal_swell(seconds: float) -> np.ndarray:
    t = t_axis(seconds)
    u = t / seconds
    return highpass(noise(seconds), 4000) * (u ** 3) * 0.6


# ------------------------------------------------------------------------------------------- mixing

class Mix:
    def __init__(self, seconds: float):
        self.n = int(seconds * SR) + SR * 6
        self.dry = np.zeros((self.n, 2))
        self.wet = np.zeros((self.n, 2))

    def add(self, at: float, x: np.ndarray, gain_db: float = 0.0, pan: float = 0.0, send: float = 0.3) -> None:
        i = int(at * SR)
        if i >= self.n or len(x) == 0:
            return
        x = x[: self.n - i] * (10 ** (gain_db / 20))
        left = np.sqrt(0.5 * (1 - pan))
        right = np.sqrt(0.5 * (1 + pan))
        self.dry[i:i + len(x), 0] += x * left
        self.dry[i:i + len(x), 1] += x * right
        self.wet[i:i + len(x), 0] += x * left * send
        self.wet[i:i + len(x), 1] += x * right * send

    def render(self) -> np.ndarray:
        ir = reverb_ir(3.2)
        wet = np.stack([signal.fftconvolve(self.wet[:, c], ir[:, c])[: self.n] for c in range(2)], axis=1)
        out = self.dry + 0.5 * wet
        out = highpass_stereo(out, 28.0)
        # gentle bus compression by a soft clipper, then normalise to -1 dBFS
        out = np.tanh(1.2 * out / max(1e-9, np.percentile(np.abs(out), 99.9))) * 0.89
        return out


def highpass_stereo(x: np.ndarray, fc: float) -> np.ndarray:
    sos = signal.butter(2, fc / (SR / 2), btype="high", output="sos")
    return signal.sosfilt(sos, x, axis=0)


def reverb_ir(seconds: float) -> np.ndarray:
    """A hall: decorrelated noise per channel, exponential decay (RT60 = seconds), darker as it decays."""
    t = t_axis(seconds)
    decay = np.exp(-6.9 * t / seconds)
    chans = []
    for _ in range(2):
        nz = RNG.standard_normal(len(t)) * decay
        early = sweep_lowpass(nz, 9000 * np.exp(-t / 0.9) + 1200)
        chans.append(early)
    ir = np.stack(chans, axis=1)
    ir[: int(0.012 * SR)] *= np.linspace(0, 1, int(0.012 * SR))[:, None]  # pre-delay softening
    return ir / np.sqrt(np.sum(ir ** 2, axis=0, keepdims=True))


# ------------------------------------------------------------------------------------------- sections

def section_title(m: Mix, at: float, length: float) -> None:
    m.add(at, pad((26, 38, 45), length + 1.5, bright=500, attack=1.0, release=1.5), -10, send=0.6)
    m.add(at + 0.4, pad((62, 65, 69), length + 1.0, bright=2200, attack=1.6, release=1.2), -22, send=0.9)


def ostinato(m: Mix, at: float, length: float, octave: int = 0, gain: float = -14.0, chords=PROGRESSION) -> None:
    step = BEAT / 4
    bar = BEAT * 4
    count = int(length / step)
    pattern = [0, 2, 1, 2, 0, 2, 1, 2, 0, 2, 1, 2, 0, 1, 2, 1]
    for i in range(count):
        t = at + i * step
        chord = chords[int((t - at) / bar) % len(chords)]
        note = chord[pattern[i % 16] % len(chord)] + 12 + octave
        accent = 0.0 if i % 4 == 0 else -4.0
        m.add(t, pluck(note, 0.35), gain + accent, pan=0.25 if i % 2 else -0.25, send=0.25)


def bass_line(m: Mix, at: float, length: float, gain: float = -10.0, chords=PROGRESSION) -> None:
    bar = BEAT * 4
    for b in range(int(np.ceil(length / bar))):
        t = at + b * bar
        root = chords[b % len(chords)][0] - 12
        dur = min(bar, at + length - t)
        if dur <= 0.05:
            break
        x = saw(hz(root), dur) + 0.6 * np.sin(2 * np.pi * hz(root - 12) * t_axis(dur))
        x = lowpass(x, 420) * adsr(len(x), 0.01, 0.3, 0.8, 0.15)
        m.add(t, x, gain, send=0.1)


def pads_over(m: Mix, at: float, length: float, chords, gain: float, bright: float) -> None:
    bar = BEAT * 4
    for b in range(int(np.ceil(length / bar))):
        t = at + b * bar
        dur = min(bar, at + length - t)
        if dur <= 0.1:
            break
        m.add(t, pad(chords[b % len(chords)], dur + 0.8, bright=bright, attack=0.5, release=0.8), gain, send=0.5)


def drums(m: Mix, at: float, length: float, density: int, gain: float = -6.0) -> None:
    """density 1: taiko on beat 1; 2: + beat 3 and toms; 3: + 8th-note toms and hats."""
    step = BEAT / 2
    for i in range(int(length / step)):
        t = at + i * step
        beat = i % 8
        if beat == 0:
            m.add(t, taiko(1.2), gain, send=0.35)
        if density >= 2 and beat == 4:
            m.add(t, taiko(1.0, 1.15), gain - 2, send=0.35)
        if density >= 2 and beat in (6, 7):
            m.add(t, tom(0.5, 1.3 if beat == 6 else 1.1), gain - 5, pan=0.3 if beat == 6 else -0.3, send=0.3)
        if density >= 3 and beat in (1, 3, 5):
            m.add(t, tom(0.5, 0.9), gain - 8, pan=-0.2, send=0.25)
        if density >= 2:
            m.add(t, hat(), gain - 16, pan=0.4, send=0.1)


def section_act1(m: Mix, at: float, length: float, cues: list) -> None:
    liftoff = next((c.at for c in cues if c.kind == "hit" and at < c.at < at + length), at + length / 2)
    ostinato(m, at, length)
    bass_line(m, at, length)
    pads_over(m, at, length, PROGRESSION, -20, 900)
    drums(m, at, liftoff - at, 1, -10)
    drums(m, liftoff, at + length - liftoff, 3, -6)


def section_space(m: Mix, at: float, length: float) -> None:
    pads_over(m, at, length, [(50, 57, 62, 65), (46, 53, 58, 62), (48, 55, 60, 64), (45, 52, 57, 61)], -16, 1100)
    motif = [74, 72, 69, 67, 69, 72, 74, 77]
    for i, note in enumerate(motif * 3):
        t = at + 0.5 + i * BEAT
        if t > at + length - 0.5:
            break
        m.add(t, bell(note, 2.5), -22, pan=0.3 * np.sin(i), send=0.7)


def section_mars(m: Mix, at: float, length: float) -> None:
    pads_over(m, at, length, MARS_CHORDS, -13, 1600)
    bass_line(m, at, length, -14, MARS_CHORDS)
    melody = [(69, 2), (71, 1), (72, 1), (74, 3), (72, 1), (71, 2), (67, 2), (69, 4)]
    t = at + BEAT * 2
    for note, beats in melody * 2:
        if t + beats * BEAT > at + length:
            break
        m.add(t, lead(note, beats * BEAT + 0.3), -15, send=0.55)
        t += beats * BEAT


def section_build(m: Mix, at: float, length: float) -> None:
    ostinato(m, at, length, 0, -16, MARS_CHORDS)
    pads_over(m, at, length, MARS_CHORDS, -18, 1400)
    bass_line(m, at, length, -12, MARS_CHORDS)
    drums(m, at, length, 2, -10)


def section_climax(m: Mix, at: float, length: float) -> None:
    ostinato(m, at, length, 12, -14)
    bass_line(m, at, length, -8)
    pads_over(m, at, length, PROGRESSION, -14, 1800)
    drums(m, at, length, 3, -4)
    bar = BEAT * 4
    for b in range(1, int(length / bar)):
        m.add(at + b * bar, braam(1.6, PROGRESSION[b % 4][0] - 12), -10, send=0.4)


def section_end(m: Mix, at: float, length: float) -> None:
    m.add(at, pad((38, 50, 57, 62, 65), length + 2.0, bright=1600, attack=0.05, release=4.0), -8, send=0.8)
    m.add(at, pad((74, 77, 81), length, bright=3000, attack=1.5, release=3.0), -20, send=0.9)


def render(e: edit_mod.Edit) -> np.ndarray:
    m = Mix(e.duration)
    for c in e.cues:
        if c.kind == "boom":
            m.add(c.at, boom(), -2, send=0.25)
        elif c.kind == "braam":
            m.add(c.at, braam(max(1.0, c.length)), -4, send=0.35)
        elif c.kind == "hit":
            m.add(c.at, hit(), -3, send=0.4)
        elif c.kind == "riser":
            x = riser(c.length)
            m.add(c.at, x, -6, send=0.4)
            m.add(c.at, cymbal_swell(c.length), -14, send=0.5)
        elif c.kind == "section":
            {
                "title": lambda: section_title(m, c.at, c.length),
                "act1": lambda: section_act1(m, c.at, c.length, e.cues),
                "space": lambda: section_space(m, c.at, c.length),
                "mars": lambda: section_mars(m, c.at, c.length),
                "build": lambda: section_build(m, c.at, c.length),
                "climax": lambda: section_climax(m, c.at, c.length),
                "end": lambda: section_end(m, c.at, c.length),
            }[c.param]()
    out = m.render()
    return out[: int((e.duration + 0.5) * SR)]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", default="build/trailer/score.wav")
    args = ap.parse_args()
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    audio = render(edit_mod.trailer())
    sf.write(out, audio, SR, subtype="PCM_24")
    print(f"wrote {out} ({len(audio) / SR:.1f} s)")


if __name__ == "__main__":
    main()
