#!/usr/bin/env python3
"""Synthesize every sound effect of "Red Planet: Starship to Mars" and write the resource-pack files.

Nothing in this script is sampled, recorded or downloaded. Every sound is built from first principles
(noise shaped in the frequency domain, band-limited oscillators, IIR filters, modal resonators, envelopes,
Poisson event trains and synthetic reverb impulse responses), each from its own fixed random seed. Running it
again reproduces the same audio and, for the same numpy/scipy/libvorbis versions, byte-identical files
(the random Ogg stream serial number that libsndfile picks is replaced by a fixed one, and the page CRCs are
recomputed).

Run from the repository root:
    python3 tools/sounds/generate_sounds.py                      # render everything
    python3 tools/sounds/generate_sounds.py --previews DIR       # also write spectrogram + waveform PNGs
    python3 tools/sounds/generate_sounds.py --only rocket.vent   # re-render a subset (prefix match)
Requires Python 3.10+, numpy, scipy, soundfile (libsndfile with Ogg/Vorbis) and, for --previews, Pillow.

Outputs
    src/client/resources/assets/redplanet/sounds/<category>/<name>[N].ogg  mono, 44.1 kHz, Ogg Vorbis (VBR)
    src/client/resources/assets/redplanet/sounds.json                       event -> files, subtitle keys
    tools/sounds/subtitles_en_us.json                                       subtitle key -> English text

Format notes (checked against the decompiled 26.3 client: SoundEventRegistrationSerializer, SoundEngine,
JOrbisAudioStream, com.mojang.blaze3d.audio.Channel/Library):
  * Files are MONO: OpenAL only spatializes mono buffers.
  * "attenuation_distance" is the OpenAL AL_LINEAR_DISTANCE max distance: gain falls linearly from 1 at the
    source to 0 at that many blocks, multiplied by max(volume, 1) of the sound instance.
  * "stream": true uses the streaming pool, which has at most 8 channels (shared with music), so only the
    long ambient beds and the deluge stream. Every other loop is a static buffer looped by OpenAL
    (AL_LOOPING), which is sample-exact.
  * The game decodes with JOrbis and honours the end-of-stream granule position, so a loop decodes to exactly
    the number of samples written here.
  * "preload": true decodes the buffer at resource reload, so cinematic cues start without a first-play hitch.

Level policy
  The game can lower a sound (instance volume, category sliders) but never raise it above 1.0, so each file is
  stored at the loudest level it will ever need. Rocket sounds are peak-normalized to about -1 dBFS; the roar
  loops and the one-shots that hand over to them (ignition, hot staging, cutoff) share the same saturation
  stage, so their levels match. Everything else has a K-weighted loudness target (ITU-R BS.1770: integrated
  LUFS for loops, maximum momentary LUFS for one-shots) with peak_db as a ceiling: alert and UI tones -19 to
  -26, machines -26, Martian ambience -24 to -30 (wind and breathing peak at -12 dBFS), so a 1 kHz beep is not
  louder than the booster. Variants share one target, so they match each other. After encoding, the decoded
  peak is checked and the gain pulled down only if Vorbis overshoot would pass -0.3 dBFS (the game clamps to
  16 bits). The report prints peak, RMS and loudness for every file.

LOOP STRATEGY
  Loops are synthesized circularly so they repeat seamlessly by construction, with no crossfade:
    - noise is generated as an inverse FFT of a shaped random spectrum, which is periodic over the loop;
    - filters are applied in the frequency domain (exact periodic steady-state response);
    - modulators (gusts, turbulence, wobble) contain only whole numbers of cycles per loop;
    - tones are quantized to whole numbers of cycles per loop;
    - events (crackle shocks, bubbles, sand grains) wrap around the loop end;
    - reverb is a circular convolution, so the tail of the end flows into the start;
    - time-varying filters use a circular STFT with periodic control curves.
  Vorbis codes the first and last block of a stream slightly less accurately, which can leave a faint click
  where the decoded loop wraps, so each loop is encoded at several circular rotations (all equally valid loops)
  and the cleanest decoded seam is kept. The report prints seam diagnostics measured on the decoded audio.
  Loop lengths are rounded to a multiple of 1024 samples (about 23 ms), so they differ slightly from the
  nominal durations below.

=============================================================================================================
LOOPS (play these with SoundInstance#isLooping() == true; all others are one-shots):
    rocket.booster_roar   rocket.ship_roar      rocket.engine_distant  rocket.vacuum_hum   rocket.vent
    rocket.entry_plasma   mars.wind             mars.dust_storm        mars.dust_devil     mars.ingenuity
    machine.moxie         machine.sabatier      machine.electrolyzer   machine.habitat_leak
    suit.breathing
=============================================================================================================

SOUND CATALOGUE (what each sound models, and how it is made)

ROCKET
  rocket.raptor_ignition  3.5 s. Super Heavy start-up: three staggered ignition "whoomps" (centre 3, inner
      ring 10, outer ring 20 engines), each a decaying sine gliding 80 -> 30 Hz plus a low-passed noise burst
      and a combustion pop. Under them a roar crossfades from a thin, ship-like spectrum to the full booster
      spectrum as engines light, with combustion-settling flutter, Raptor crackle that rises with the
      level, torch-igniter hiss before first light, and a flame-trench reverb. Tails off so the booster loop
      can take over.
  rocket.booster_roar     LOOP 8 s. 33 Raptors. Turbulent-mixing noise with a spectrum peaking at 30-120 Hz
      and falling about 5-7 dB/octave to 6 kHz, split into low/mid/high bands with independent log-normal
      turbulence AM (0.3-6 Hz). The Raptor "crackle" is a Poisson train (about 40-120 shocks/s, denser when
      the turbulence is louder) of steepened, zero-area N-shaped shocks 1-3 ms long, with log-normal
      amplitudes and a steeper front than back (positive skew of dp/dt, as measured for rocket crackle),
      high-passed so it adds the ripping top while the bed carries the body. Soft saturation glues it.
  rocket.ship_roar        LOOP 6 s. Six engines: the same model with less bass (peak 100-250 Hz), a separate
      2-8 kHz fine-scale-turbulence hiss, and shorter (0.6-2 ms), denser crackle.
  rocket.engine_distant   LOOP 8 s. The roar from several km: atmospheric absorption leaves only <400 Hz,
      the crackle survives as irregular low "thumps" (8-22 ms smoothed shocks, low-passed at 260 Hz),
      turbulence-driven fading is deeper, and a long low-passed reverb adds terrain echoes.
  rocket.vacuum_hum       LOOP 6 s. What passengers feel in space under thrust: structure-borne rumble
      (30-120 Hz noise plus narrow structural modes at 33-108 Hz), panel rattle gated by the vibration,
      a faint turbopump whine near 1.19 kHz with wobble, and a faint cabin-fan hiss.
  rocket.hot_staging      3 s. Flash ignition of the ship's engines against the booster dome: a broadband
      burst with a reflected second shock, a sub thump, a ringing steel interstage (modal bank, 185-3600 Hz),
      a down-sweeping plume whoosh, then the ship roar and crackle building up.
  rocket.stage_separation 1.5 s, 2 variants. A latch-release click, then a heavy clank: a dense modal bank
      (~70 inharmonic steel modes at 150-2600 Hz plus short-lived modes to 6.5 kHz; higher modes decay faster,
      split mode pairs beat) struck by a short noise burst, an impact crash, a low mass thud, a softer
      bounce, and a short metallic-interior reverb. Dense, quickly damped modes make it clank, not chime.
  rocket.sonic_boom       2 s, 2 variants. The classic double boom: two N-waves 0.17-0.20 s apart (nose and
      aft shock systems), each 16-26 ms long so its own front and rear shocks fuse into one boom, with
      1.5-1.8 ms rise times and a low-pass (absorption, turbulence), a ground reflection, a rolling
      thunder-like rumble tail, and an outdoor reverb with terrain reflections.
  rocket.vent             LOOP 4 s. Cryogenic propellant venting: strong 1-8 kHz hiss peaking near 3 kHz,
      a 200-800 Hz body, gusting AM with brighter gusts, flutter, and random "spits" of two-phase flow.
  rocket.deluge           6 s (streamed). The water deluge: a valve thump and surge, then a sustained
      rushing roar (pink-ish noise with fast "churn" modulation for a watery texture), dense splash grains
      (2-8 ms band-limited noise grains at 1-6 kHz), spray hiss and low rumble, decaying and darkening.
  rocket.flap_actuator    0.8 s, 3 variants. Electric flap actuator: band-limited sawtooth motor tone
      gliding through 120-300 Hz, an inharmonic gear-mesh whine, gear rattle pulsing with the motor
      rotation, housing resonances, and relay click / end-stop clunk.
  rocket.landing_legs     1.2 s, 2 variants. Hydraulic hiss with a pump whine and a mechanical rumble while
      the legs extend, then a lock "thunk" (low thud, a short metallic click, a little rattle).
  rocket.touchdown        2 s. The landing burn collapsing at contact, a very low impact thud (64 -> 30 Hz)
      with a regolith crunch and low structural ring, a final engine chuff, then stick-slip metal creaks
      (irregular pulse train through narrow steel resonances) and settling debris.
  rocket.engine_cutoff    1.5 s, 2 variants (booster / ship spectrum). The roar holds 60 ms, then collapses
      within ~0.3 s while it sputters and its spectrum darkens (8 kHz -> 400 Hz); then a small low "chuff"
      (the last propellant), a quiet purge hiss, and thermal "tink"s.
  rocket.entry_plasma     LOOP 6 s. Atmospheric entry heard from inside: deep buffeting rumble, two
      resonant wind bands sweeping 250-2200 Hz, a crackling plasma hiss (noise gated by a sparse spike
      train), and occasional buffet thumps.
  rocket.countdown_beep   0.25 s. Clean 1 kHz sine with soft raised-cosine attack and release.
  rocket.go_tone          0.6 s. Two ascending chime notes (A5 -> E6, a fifth), lightly inharmonic
      partials, small-room reverb. No voice.
  rocket.alarm            1.0 s. Master alarm: phase-continuous alternation between 1000 and 800 Hz every
      125 ms, rounded-square timbre (odd harmonics).
  rocket.chopsticks       2.5 s. Tower arms closing: geared electric-motor drone spinning up, hydraulic pump
      whine and flow hiss, carriage rumble with rail-joint knocks, then a huge metallic clamp (modal bank
      70-1500 Hz, heavy thud) and an outdoor reverb for scale.
MARS (sound on Mars is quiet and muffled: thin CO2 absorbs high frequencies, see docs/SCIENCE.md)
  mars.wind               LOOP 12 s (streamed). Perseverance-microphone-like wind: deep noise low-passed at
      ~500 Hz, a brighter 150-550 Hz layer that only comes up in gusts, a slow log-normal gust envelope
      (0.04-0.3 Hz) and gentle buffeting. Quiet.
  mars.dust_storm         LOOP 10 s (streamed). Stronger, faster gusts and rumble, a moaning resonance, and
      a sandy patter (dense Poisson micro-grains band-passed at 0.7-3.5 kHz, then muffled), still muffled.
  mars.dust_devil         LOOP 5 s. A passing vortex: band-passed noise whose centre wobbles with a ~3 Hz
      rotation (Doppler of the debris; the rotation phase wanders randomly so it never ticks like a
      metronome), rotation AM, a slowly sweeping flanger (phasing), a low core rumble and swirling grit.
  mars.sublimation        1.5 s, 3 variants. CO2 ice sublimating: a decaying Poisson crackle of tiny clicks
      through small resonances, occasional gas-pocket pops (short damped sines), and a soft fizz.
  mars.ingenuity          LOOP 3 s. The Ingenuity helicopter as Perseverance heard it in 2021: an 84 Hz
      blade-pass hum (2 blades x ~2537 rpm) with harmonics, two coaxial rotors one beat per loop apart,
      slight flutter, muffled by distance and the Martian atmosphere.
MACHINES
  machine.moxie           LOOP 4 s. Scroll compressor: pulsating 58.25 Hz orbit harmonics with a housing
      resonance, magnetic hum, a faint motor-drive whine (1.75 kHz) and PWM whistle, bearing noise pulsing
      with the orbit, gas-flow hiss.
  machine.sabatier        LOOP 4 s. Reactor/heater-controller hum (50 Hz and harmonics), a recirculation
      pump pulsing at 1.25 Hz in a low rumble, a gentle gas-flow hiss.
  machine.electrolyzer    LOOP 4 s. Bubbling: Minnaert bubbles (f0 = 3.26/r, r = 0.7-6 mm) as damped sines
      with the rising chirp and damping of van den Doel's liquid-sound model, a fine fizz, 100 Hz rectifier
      hum with harmonics.
  machine.solar_deploy    1.5 s, 2 variants. Small geared motor, hinge ratchet clicks speeding up and
      slowing down, panel flex rustle, end latch click-clunk.
  machine.habitat_pressurize 3 s. Valve opens, then an airflow hiss that rises and gets fuller (lower
      frequencies carry better as the pressure rises), a flow whistle, valve close thunk.
  machine.habitat_leak    LOOP 3 s. Air escaping through a crack: high hiss with a wavering 3.15 kHz whistle
      (tone plus narrow-band noise) and flow flutter.
  machine.airlock         2.5 s, 2 variants. Airlock cycle: depressurization hiss, servo whirr, heavy bolt
      clunk (thud + modal clank), repressurization hiss and valve click, metal-chamber reverb.
SUIT
  suit.breathing          LOOP 4.5 s. Calm breathing in a helmet: inhale (1.6 s, brighter, formants
      gliding up, friction hiss) and exhale (2 s, darker, formants gliding down), made by shaping noise with a
      time-varying vocal-tract-like spectrum (STFT); short pauses (the loop starts in one), helmet early
      reflections 0.8-7 ms apart (boxy comb) and a cavity resonance, a very faint suit fan.
  suit.low_oxygen         0.8 s. Urgent double beep at 2.09 kHz through a small helmet speaker.
  suit.helmet_seal        0.7 s, 2 variants. Latch click-clack with a low knock, then a short decaying
      pressurization hiss inside the helmet.
  suit.dosimeter_click    0.05 s, 4 variants. Geiger-counter tick: a 1-3 sample pulse through a small
      speaker resonance (2.4-4.6 kHz, ~2 ms).
UI
  ui.telemetry_event      0.4 s. Soft sine blip at 1.57 kHz with a slight pitch drop and a short tail.
  ui.interlude_whoosh     3 s. Cinematic riser: noise band sweeping 180 Hz -> 5 kHz with an accelerating
      swell, a 38 -> 55 Hz sub swell, a high shimmer, hall reverb, released after the peak.
"""

from __future__ import annotations

import argparse
import io
import json
import sys
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import numpy as np
import soundfile as sf
from scipy import signal

SR = 44100
TAU = 2.0 * np.pi
MOD_ID = "redplanet"
REPO = Path(__file__).resolve().parents[2]
ASSETS = REPO / "src" / "client" / "resources" / "assets" / MOD_ID
SOUND_ROOT = ASSETS / "sounds"
SOUNDS_JSON = ASSETS / "sounds.json"
SUBTITLES_OUT = Path(__file__).resolve().parent / "subtitles_en_us.json"
SIZE_BUDGET = 6 * 1024 * 1024
LOOP_QUANTUM = 1024


# =============================================================================================================
# Registry
# =============================================================================================================

@dataclass
class SoundSpec:
    event: str
    fn: Callable
    seconds: float
    loop: bool = False
    stream: bool = False
    attenuation: int = 16
    variants: int = 1
    peak_db: float = -1.0
    subtitle: str = ""
    quality: float = 0.5
    preload: bool = False
    fade_in: float = 0.002
    fade_out: float = 0.04
    lufs: float | None = None      # loudness target (integrated for loops, max momentary for one-shots);
                                   # peak_db then acts as a ceiling
    max_rotate: float = 0.4        # loops: how far (s) the seam-optimizing rotation may move the start

    @property
    def category(self) -> str:
        return self.event.split(".", 1)[0]

    @property
    def stem(self) -> str:
        return self.event.split(".", 1)[1]

    def file_names(self) -> list[str]:
        if self.variants == 1:
            return [f"{self.category}/{self.stem}"]
        return [f"{self.category}/{self.stem}{i + 1}" for i in range(self.variants)]


REGISTRY: list[SoundSpec] = []


def sound(event: str, seconds: float, **kw):
    def deco(fn):
        REGISTRY.append(SoundSpec(event=event, fn=fn, seconds=seconds, **kw))
        return fn
    return deco


def _crc(s: str) -> int:
    return zlib.crc32(s.encode("utf-8")) & 0xFFFFFFFF


@dataclass
class Ctx:
    """Everything a sound function needs: its length, loop flag and seeded random streams."""
    event: str
    variant: int
    n: int
    loop: bool

    @property
    def t(self) -> np.ndarray:
        return np.arange(self.n) / SR

    @property
    def dur(self) -> float:
        return self.n / SR

    def rng(self, tag: str = "") -> np.random.Generator:
        """An independent, reproducible random stream per (event, variant, component)."""
        return np.random.default_rng([_crc(self.event), self.variant, _crc(tag)])

    def qf(self, f: float) -> float:
        """Quantize a frequency to a whole number of cycles per loop (identity for one-shots)."""
        return qf(f, self.n) if self.loop else float(f)


# =============================================================================================================
# DSP helpers
# =============================================================================================================

def nsamp(seconds: float) -> int:
    return int(round(seconds * SR))


def loop_samples(seconds: float) -> int:
    return max(LOOP_QUANTUM, int(round(seconds * SR / LOOP_QUANTUM)) * LOOP_QUANTUM)


def qf(f: float, n: int) -> float:
    return max(1, round(f * n / SR)) * SR / n


def db(d) -> float | np.ndarray:
    """Decibels to linear gain."""
    return 10.0 ** (np.asarray(d, dtype=float) / 20.0)


def to_db(x) -> float | np.ndarray:
    return 20.0 * np.log10(np.maximum(np.abs(x), 1e-12))


def rms(x: np.ndarray) -> float:
    return float(np.sqrt(np.mean(np.square(x)))) if len(x) else 0.0


def peak(x: np.ndarray) -> float:
    return float(np.max(np.abs(x))) if len(x) else 0.0


def unit(x: np.ndarray) -> np.ndarray:
    """Scale to unit RMS."""
    return x / max(rms(x), 1e-20)


def unit_peak(x: np.ndarray) -> np.ndarray:
    return x / max(peak(x), 1e-20)


def smooth01(u) -> np.ndarray:
    u = np.clip(u, 0.0, 1.0)
    return 0.5 - 0.5 * np.cos(np.pi * u)


def ramp(t: np.ndarray, t0: float, dur: float) -> np.ndarray:
    """0 before t0, raised-cosine rise to 1 over dur seconds."""
    return smooth01((t - t0) / max(dur, 1e-9))


def env_pts(t: np.ndarray, pts, curve: str = "smooth") -> np.ndarray:
    """Breakpoint envelope [(time, value), ...] with raised-cosine (or linear) segments."""
    ts = np.array([p[0] for p in pts], dtype=float)
    vs = np.array([p[1] for p in pts], dtype=float)
    i = np.clip(np.searchsorted(ts, t, side="right") - 1, 0, len(ts) - 2)
    u = np.clip((t - ts[i]) / np.maximum(ts[i + 1] - ts[i], 1e-9), 0.0, 1.0)
    if curve == "smooth":
        u = smooth01(u)
    return vs[i] + (vs[i + 1] - vs[i]) * u


def decay_env(t: np.ndarray, t0: float, tau: float, attack: float = 0.002, hold: float = 0.0) -> np.ndarray:
    """Attack (raised cosine) then exponential decay with time constant tau."""
    tt = t - t0
    return np.where(tt >= 0, smooth01(tt / max(attack, 1e-6)) * np.exp(-np.maximum(tt - attack - hold, 0.0) / tau),
                    0.0)


def curve_db(freqs: np.ndarray, pts) -> np.ndarray:
    """Piecewise-linear dB curve over log frequency, from breakpoints [(Hz, dB), ...]."""
    f = np.log10(np.maximum(freqs, 1.0))
    xs = np.log10([p[0] for p in pts])
    ys = np.array([p[1] for p in pts], dtype=float)
    return np.interp(f, xs, ys)


def gauss_spectrum(rng: np.random.Generator, nbins: int) -> np.ndarray:
    return (rng.standard_normal(nbins) + 1j * rng.standard_normal(nbins)) / np.sqrt(2.0)


def shaped_noise(n: int, pts, rng: np.random.Generator) -> np.ndarray:
    """Gaussian noise with the given amplitude spectrum (dB breakpoints), unit RMS, periodic over n samples."""
    freqs = np.fft.rfftfreq(n, 1.0 / SR)
    mag = db(curve_db(freqs, pts))
    mag[0] = 0.0
    return unit(np.fft.irfft(gauss_spectrum(rng, len(freqs)) * mag, n))


def smooth_mod(n: int, f_lo: float, f_hi: float, rng: np.random.Generator) -> np.ndarray:
    """Zero-mean, unit-RMS random modulator band-limited to [f_lo, f_hi] Hz (1/f-ish), periodic over n."""
    freqs = np.fft.rfftfreq(n, 1.0 / SR)
    sel = (freqs >= f_lo) & (freqs <= f_hi)
    sel[0] = False
    if not sel.any():
        k = max(1, int(round(0.5 * (f_lo + f_hi) * n / SR)))
        sel[min(k, len(sel) - 1)] = True
    mag = np.zeros(len(freqs))
    mag[sel] = (freqs[sel] / max(freqs[sel][0], 1e-6)) ** -0.5
    return unit(np.fft.irfft(gauss_spectrum(rng, len(freqs)) * mag, n))


def lognorm_env(m: np.ndarray, sigma: float) -> np.ndarray:
    """Positive, unit-mean modulation envelope from a unit-RMS modulator."""
    return np.exp(sigma * m - 0.5 * sigma * sigma)


def butter(kind: str, f, order: int = 2) -> np.ndarray:
    return signal.butter(order, f, btype=kind, fs=SR, output="sos")


def peq(fc: float, q: float, gain_db: float) -> np.ndarray:
    """RBJ peaking EQ biquad as an sos row."""
    a_ = 10.0 ** (gain_db / 40.0)
    w0 = TAU * fc / SR
    al = np.sin(w0) / (2.0 * q)
    cw = np.cos(w0)
    b = np.array([1 + al * a_, -2 * cw, 1 - al * a_])
    a = np.array([1 + al / a_, -2 * cw, 1 - al / a_])
    return np.concatenate([b / a[0], a / a[0]])[None, :]


def reson(fc: float, q: float) -> np.ndarray:
    """RBJ constant 0 dB peak band-pass (a resonator) as an sos row."""
    w0 = TAU * fc / SR
    al = np.sin(w0) / (2.0 * q)
    cw = np.cos(w0)
    b = np.array([al, 0.0, -al])
    a = np.array([1 + al, -2 * cw, 1 - al])
    return np.concatenate([b / a[0], a / a[0]])[None, :]


def circ_apply(x: np.ndarray, sos: np.ndarray) -> np.ndarray:
    """Apply an IIR filter circularly: the exact periodic steady-state response (for loops)."""
    n = len(x)
    freqs = np.fft.rfftfreq(n, 1.0 / SR)
    _, h = signal.sosfreqz(sos, worN=freqs, fs=SR)
    return np.fft.irfft(np.fft.rfft(x) * h, n)


def F(c: Ctx, x: np.ndarray, sos: np.ndarray) -> np.ndarray:
    """Filter: circular for loops, causal for one-shots."""
    return circ_apply(x, sos) if c.loop else signal.sosfilt(sos, x)


def lp(c, x, f, order=2):
    return F(c, x, butter("lowpass", f, order))


def hp(c, x, f, order=2):
    return F(c, x, butter("highpass", f, order))


def bp(c, x, lo, hi, order=2):
    return F(c, x, butter("bandpass", [lo, hi], order))


def convolve(c: Ctx, x: np.ndarray, h: np.ndarray) -> np.ndarray:
    """Convolution: circular (tail wraps into the start) for loops, truncated linear for one-shots."""
    n = len(x)
    if c.loop:
        hh = np.zeros(n)
        for s in range(0, len(h), n):
            seg = h[s:s + n]
            hh[:len(seg)] += seg
        return np.fft.irfft(np.fft.rfft(x) * np.fft.rfft(hh), n)
    return signal.fftconvolve(x, h)[:n]


def band_rms(x: np.ndarray, lo: float, hi: float) -> float:
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1.0 / SR)
    sel = (f >= lo) & (f < hi)
    return float(np.sqrt(2.0 * np.sum(np.abs(spec[sel]) ** 2)) / len(x))


def match_band(sig: np.ndarray, ref: np.ndarray, lo: float, hi: float, offset_db: float,
               sel: np.ndarray | None = None) -> np.ndarray:
    """Scale sig so its energy in [lo, hi) Hz is offset_db relative to ref's (measured over mask sel)."""
    a, b = (sig, ref) if sel is None else (sig[sel], ref[sel])
    return sig * (band_rms(b, lo, hi) / max(band_rms(a, lo, hi), 1e-20)) * db(offset_db)


def soft_limit(x: np.ndarray, ceiling: float = 1.0, knee: float = 0.5) -> np.ndarray:
    """Linear below knee*ceiling, tanh-compressed above (continuous slope): a gentle saturator."""
    a = knee * ceiling
    y = x.copy()
    m = np.abs(x) > a
    y[m] = np.sign(x[m]) * (a + (ceiling - a) * np.tanh((np.abs(x[m]) - a) / (ceiling - a)))
    return y


def glue(x: np.ndarray, rms_db: float = -12.0, knee: float = 0.5) -> np.ndarray:
    """Set the RMS level, then soft-limit the peaks (density of a close-miked recording)."""
    return soft_limit(x * (db(rms_db) / max(rms(x), 1e-20)), 1.0, knee)


def glue_ref(x: np.ndarray, sel: np.ndarray, rms_db: float = -14.0, knee: float = 0.5) -> np.ndarray:
    """glue() for one-shots: the gain comes from the RMS over a reference stretch (mask sel), not the silences."""
    return soft_limit(x * (db(rms_db) / max(rms(x[sel]), 1e-20)), 1.0, knee)


def punch(x: np.ndarray, drive_db: float = 3.0, knee: float = 0.55) -> np.ndarray:
    """Drive the peak drive_db over full scale into the soft limiter (for one-shots with silence)."""
    return soft_limit(unit_peak(x) * db(drive_db), 1.0, knee)


def gauss_band(freqs: np.ndarray, fc, bw_oct) -> np.ndarray:
    """Gaussian band on a log-frequency axis (bw_oct = full width at half maximum, octaves)."""
    s = np.asarray(bw_oct) / 2.3548
    return np.exp(-0.5 * (np.log2(np.maximum(freqs, 1.0) / fc) / s) ** 2)


def stft_filter(c: Ctx, x: np.ndarray, mag_fn, nfft: int = 2048, hop: int = 512) -> np.ndarray:
    """Time-varying filter: multiply the STFT of x by mag_fn(frame_times, freqs) -> (frames, bins).

    Circular (periodic control curves give a seamless loop) for loops; zero-padded for one-shots.
    sqrt-Hann analysis and synthesis windows at 75% overlap reconstruct exactly when mag_fn == 1.
    """
    n0 = len(x)
    if c.loop:
        if n0 % hop:
            raise ValueError("loop length must be a multiple of the STFT hop")
        n, off, xx = n0, 0, x
    else:
        off = nfft
        n = int(np.ceil((n0 + 2 * nfft) / hop)) * hop
        xx = np.zeros(n)
        xx[off:off + n0] = x
    win = np.sqrt(0.5 - 0.5 * np.cos(TAU * np.arange(nfft) / nfft))
    centers = np.arange(0, n, hop)
    idx = (centers[:, None] - nfft // 2 + np.arange(nfft)[None, :]) % n
    spec = np.fft.rfft(xx[idx] * win, axis=1)
    tf = (centers - off) / SR
    freqs = np.fft.rfftfreq(nfft, 1.0 / SR)
    m = np.broadcast_to(mag_fn(tf, freqs), spec.shape)
    y = np.fft.irfft(spec * m, nfft, axis=1) * win
    out = np.bincount(idx.ravel(), weights=y.ravel(), minlength=n) / (nfft / (2.0 * hop))
    return out if c.loop else out[off:off + n0]


def white(c: Ctx, tag: str) -> np.ndarray:
    return c.rng(tag).standard_normal(c.n)


def phase_of(freq: np.ndarray) -> np.ndarray:
    """Running phase (radians) of an instantaneous-frequency curve."""
    return TAU * np.cumsum(freq) / SR


def bl_saw(ph: np.ndarray, f_inst: np.ndarray, tilt: float = 1.0) -> np.ndarray:
    """Band-limited sawtooth (additive), harmonics faded out below Nyquist so glides never alias."""
    fmin = max(float(np.min(f_inst)), 30.0)
    kmax = int(0.46 * SR / fmin)
    y = np.zeros_like(ph)
    for k in range(1, kmax + 1):
        w = np.clip((0.44 * SR - k * f_inst) / (0.04 * SR), 0.0, 1.0)
        if not np.any(w):
            break
        y += w * np.sin(k * ph) / k ** tilt
    return y


def poisson_events(n: int, rate, rng: np.random.Generator) -> np.ndarray:
    """Sample positions of an (in)homogeneous Poisson process; rate in events/s (scalar or per sample)."""
    rate = np.broadcast_to(np.asarray(rate, dtype=float), (n,))
    rmax = float(rate.max())
    if rmax <= 0:
        return np.zeros(0, dtype=int)
    k = rng.poisson(rmax * n / SR)
    pos = np.sort(rng.integers(0, n, k))
    keep = rng.random(k) < rate[pos] / rmax
    return pos[keep]


def place(c: Ctx, n: int, pos: np.ndarray, amps: np.ndarray, kernel: np.ndarray) -> np.ndarray:
    """Sum of scaled copies of kernel starting at pos (wrapping around for loops)."""
    imp = np.zeros(n)
    np.add.at(imp, np.asarray(pos) % n, amps)
    if c.loop:
        kk = np.zeros(n)
        kk[:len(kernel)] = kernel
        return np.fft.irfft(np.fft.rfft(imp) * np.fft.rfft(kk), n)
    return signal.fftconvolve(imp, kernel)[:n]


def add_into(c: Ctx, buf: np.ndarray, start: int, sig: np.ndarray) -> None:
    """buf[start:] += sig, wrapping around for loops."""
    n = len(buf)
    if c.loop:
        np.add.at(buf, (start + np.arange(len(sig))) % n, sig)
        return
    if start >= n:
        return
    if start < 0:
        sig = sig[-start:]
        start = 0
    e = min(n, start + len(sig))
    buf[start:e] += sig[:e - start]


def hann_kernel(length: int) -> np.ndarray:
    k = np.hanning(length + 2)[1:-1]
    return k / k.sum()


def crackle(c: Ctx, n: int, rate: float, env: np.ndarray | None = None, gamma: float = 1.5,
            dur_ms=(1.0, 3.0), kappa=(0.6, 0.92), sigma: float = 0.8, n_kernels: int = 24, smooth: int = 3,
            hp_hz: float | None = 700.0, tag: str = "crackle") -> np.ndarray:
    """Rocket crackle: Poisson train of steepened, zero-area N-shaped shocks.

    Each shock jumps to +1 almost instantly (the shock front), falls linearly to -kappa over D ms, and returns
    to zero over D*(1-kappa)/kappa, so the net area is zero and the back is gentler than the front (positive
    skewness of dp/dt). Event rate follows env**gamma (normalized to the mean rate), amplitudes are
    log-normal and scale with sqrt(env). The optional high-pass keeps the crackle from swamping the low mids
    (the 1-3 ms shocks carry most of their energy at 200-1000 Hz); the turbulent bed supplies the body.
    """
    r = c.rng(tag)
    if env is None:
        env = np.ones(n)
    w = env ** gamma
    w = w / w.mean()
    pos = poisson_events(n, rate * w, r)
    amps = r.lognormal(0.0, sigma, len(pos)) * np.sqrt(env[pos])
    kid = r.integers(0, n_kernels, len(pos))
    out = np.zeros(n)
    win = hann_kernel(smooth) if smooth > 1 else None
    for k in range(n_kernels):
        d = r.uniform(*dur_ms) / 1000.0
        kap = r.uniform(*kappa)
        ld = max(3, int(round(d * SR)))
        lr = max(1, int(round(ld * (1.0 - kap) / kap)))
        ker = np.concatenate([[0.0], np.linspace(1.0, -kap, ld), np.linspace(-kap, 0.0, lr + 1)[1:]])
        if win is not None:
            ker = np.convolve(ker, win)
        sel = kid == k
        if sel.any():
            out += place(c, n, pos[sel], amps[sel], ker)
    return hp(c, out, hp_hz, 2) if hp_hz else out


def metal_modes(r: np.random.Generator, f1: float, fmax: float, count: int, tau0: float, tau_exp: float = 0.6,
                amp_tilt: float = 0.3, pair_prob: float = 0.5, gap=(0.10, 0.32)) -> list[tuple]:
    """Inharmonic mode set of a large steel structure: (freq, decay tau, amplitude).

    Frequencies climb by random log steps from f1; higher modes decay faster (tau ~ f^-tau_exp); about half
    the modes get a split twin 0.15-0.6% away (imperfect symmetry), which makes the ring beat.
    """
    fs = [f1]
    while len(fs) < count:
        nxt = fs[-1] * np.exp(r.uniform(*gap))
        if nxt > fmax:
            break
        fs.append(nxt)
    modes = []
    for f in fs:
        tau = tau0 * (f1 / f) ** tau_exp * r.uniform(0.7, 1.3)
        a = r.uniform(0.35, 1.0) * (f1 / f) ** amp_tilt
        modes.append((f, tau, a))
        if r.random() < pair_prob:
            modes.append((f * (1.0 + r.choice([-1.0, 1.0]) * r.uniform(0.0015, 0.006)), tau * r.uniform(0.8, 1.1),
                          a * r.uniform(0.5, 0.9)))
    return modes


def modal_ir(modes, length_s: float) -> np.ndarray:
    """Impulse response of a modal resonator bank: sum of exponentially decaying sines."""
    length = nsamp(length_s)
    t = np.arange(length) / SR
    y = np.zeros(length)
    for f, tau, a in modes:
        if f < 0.45 * SR:
            y += a * np.exp(-t / tau) * np.sin(TAU * f * t)
    return y


def strike(r: np.random.Generator, ms: float = 3.0, bright: float = 6000.0) -> np.ndarray:
    """Excitation of an impact: a short Hann-windowed noise burst; shorter and brighter = harder."""
    length = max(4, nsamp(ms / 1000.0))
    e = r.standard_normal(length) * np.hanning(length)
    e = signal.sosfilt(butter("lowpass", min(bright, 0.45 * SR), 2), np.concatenate([e, np.zeros(64)]))
    e += np.concatenate([np.hanning(length), np.zeros(64)]) * 0.5  # net push, gives the strike some weight
    return e / np.sum(np.abs(e))


def hit(c: Ctx, n: int, t0: float, modes, exc: np.ndarray, length: float) -> np.ndarray:
    """A modal bank struck at t0 by excitation exc."""
    y = signal.fftconvolve(exc, modal_ir(modes, length))
    out = np.zeros(n)
    add_into(c, out, nsamp(t0), y)
    return out


def thud(n: int, t0: float, f_start: float, f_end: float, tau: float, glide: float = 0.08,
         attack: float = 0.004) -> np.ndarray:
    """Low impact / ignition 'whoomp': decaying sine whose pitch falls from f_start to f_end."""
    t = np.arange(n) / SR - t0
    m = t >= 0
    tt = t[m]
    f = f_end + (f_start - f_end) * np.exp(-tt / glide)
    y = np.zeros(n)
    y[m] = np.sin(TAU * np.cumsum(f) / SR) * np.exp(-tt / tau) * smooth01(tt / attack)
    return y


def nwave(n: int, t0: float, dur: float, amp: float, rise: float) -> np.ndarray:
    """Sonic-boom N-wave: jump to +amp, linear fall to -amp over dur, jump back; shocks rounded to `rise` s."""
    t = np.arange(n) / SR - t0
    y = np.where((t >= 0) & (t <= dur), amp * (1.0 - 2.0 * t / dur), 0.0)
    return np.convolve(y, hann_kernel(max(3, nsamp(rise))), mode="same")


def creak(c: Ctx, n: int, rate_pts, amp_pts, res, rng: np.random.Generator) -> np.ndarray:
    """Stick-slip creak: an irregular pulse train (rate from rate_pts) ringing narrow metal resonances."""
    t = np.arange(n) / SR
    rate = env_pts(t, rate_pts) * np.exp(0.25 * smooth_mod(n, 4.0, 30.0, rng))
    ph = np.cumsum(rate) / SR
    idx = np.nonzero(np.diff(np.floor(ph)) > 0)[0] + 1
    a = env_pts(t, amp_pts)[idx] * rng.lognormal(0.0, 0.35, len(idx))
    imp = np.zeros(n)
    np.add.at(imp, idx, a)
    out = np.zeros(n)
    for f, q, g in res:
        out += g * F(c, imp, reson(f, q))
    return out


def bubble(f0: float, amp: float, xi: float = 0.1) -> np.ndarray:
    """Minnaert bubble (van den Doel 2005): damped sine, damping d(f0), frequency rising as f0(1 + xi d t)."""
    d = 0.043 * f0 + 0.0014 * f0 ** 1.5
    length = nsamp(min(0.12, 7.0 / d))
    tt = np.arange(length) / SR
    f = f0 * (1.0 + xi * d * tt)
    return amp * np.exp(-d * tt) * np.sin(TAU * np.cumsum(f) / SR) * smooth01(tt / 0.0005)


def circ_delay(x: np.ndarray, d: np.ndarray) -> np.ndarray:
    """Circular fractional delay (linear interpolation) by d samples (array)."""
    n = len(x)
    i = np.arange(n) - d
    i0 = np.floor(i).astype(int)
    fr = i - i0
    return x[i0 % n] * (1.0 - fr) + x[(i0 + 1) % n] * fr


def reverb_ir(r: np.random.Generator, rt60: float, length: float | None = None, predelay: float = 0.0,
              early=(), hf_damp: float = 0.5, lf_ratio: float = 1.15, build: float = 0.008,
              tone_lp: float | None = None) -> np.ndarray:
    """Synthetic reverb impulse response with unit energy.

    Exponentially decaying noise in three bands (lows decay lf_ratio*rt60, highs hf_damp*rt60), a short
    build-up, optional early reflections [(delay s, gain)], predelay and an overall low-pass.
    """
    ln = nsamp(length if length else min(rt60 * 1.1, 4.0))
    t = np.arange(ln) / SR
    w = r.standard_normal(ln)
    lo = signal.sosfilt(butter("lowpass", 400, 2), w)
    hi = signal.sosfilt(butter("highpass", 3500, 2), w)
    mid = w - lo - hi
    k = np.log(1000.0)
    ir = (lo * np.exp(-k * t / (rt60 * lf_ratio)) + mid * np.exp(-k * t / rt60)
          + hi * np.exp(-k * t / (rt60 * hf_damp)))
    if build > 0:
        ir *= np.clip(t / build, 0.0, 1.0) ** 2
    for dt, g in early:
        i = nsamp(dt)
        if i < ln:
            ir[i] += g * 2.5
    if tone_lp:
        ir = signal.sosfilt(butter("lowpass", tone_lp, 2), ir)
    ir = np.concatenate([np.zeros(nsamp(predelay)), ir])
    return ir / np.sqrt(np.sum(ir ** 2))


def reverb(c: Ctx, x: np.ndarray, ir: np.ndarray, wet_db: float) -> np.ndarray:
    """Add reverb; wet_db is the reverberant energy relative to the dry energy over the whole sound."""
    wet = convolve(c, x, ir)
    return x + wet * (db(wet_db) * rms(x) / max(rms(wet), 1e-20))


def smooth_curve(x: np.ndarray, seconds: float) -> np.ndarray:
    """Smooth a control curve with a Hann kernel, holding the end values (no droop at the edges)."""
    k = max(3, nsamp(seconds))
    return np.convolve(np.pad(x, k, mode="edge"), hann_kernel(k), mode="same")[k:-k]


def helmet_ir(r: np.random.Generator) -> np.ndarray:
    """A helmet bubble ~30 cm across: dense first reflections 0.8-7 ms apart (boxy comb) and a 15 ms tail."""
    ln = nsamp(0.05)
    t = np.arange(ln) / SR
    ir = np.zeros(ln)
    ir[0] = 1.0
    for d, g in [(0.00085, 0.4), (0.0016, -0.3), (0.0023, 0.26), (0.0031, -0.2), (0.0042, 0.16),
                 (0.0056, -0.12), (0.0071, 0.09)]:
        ir[nsamp(d)] += g
    tail = r.standard_normal(ln) * np.exp(-t / 0.006) * 0.06
    ir += signal.sosfilt(butter("lowpass", 5000, 2), tail)
    return ir


def fade_edges(x: np.ndarray, fin: float, fout: float) -> np.ndarray:
    """Raised-cosine fades; the first and last samples are exactly zero."""
    x = x.copy()
    n = len(x)
    a = min(n // 2, max(1, nsamp(fin)))
    b = min(n // 2, max(1, nsamp(fout)))
    x[:a] *= smooth01(np.arange(a) / a)
    x[n - b:] *= smooth01(np.arange(b)[::-1] / b)
    return x


# -------------------------------------------------------------------------------------------------------------
# LOOPS (seamless, circularly synthesized; see the module docstring). A sound is a loop when its @sound(...)
# says loop=True; every other event is a one-shot:
#   rocket.booster_roar  rocket.ship_roar  rocket.engine_distant  rocket.vacuum_hum  rocket.vent
#   rocket.entry_plasma  mars.wind  mars.dust_storm  mars.dust_devil  mars.ingenuity  machine.moxie
#   machine.sabatier  machine.electrolyzer  machine.habitat_leak  suit.breathing
# -------------------------------------------------------------------------------------------------------------

# =============================================================================================================
# ROCKET
# =============================================================================================================

# Turbulent-mixing noise spectra (relative dB). Booster: peak 30-120 Hz, about -5 dB/oct to 2 kHz, -7 dB/oct
# to 6 kHz, then air absorption. Ship: peak 100-250 Hz, flatter mids (smaller nozzle cluster, closer source).
ROAR_BOOSTER = [(5, -80), (12, -34), (20, -8), (30, 0), (60, 1), (120, 0), (250, -4), (500, -9), (1000, -15),
                (2000, -21), (4000, -28), (6000, -33), (9000, -46), (14000, -66), (22050, -90)]
ROAR_SHIP = [(5, -80), (15, -42), (25, -20), (40, -8), (70, -2), (120, 0), (250, -1), (500, -4), (1000, -8),
             (2000, -12), (4000, -17), (7000, -24), (10000, -36), (16000, -60), (22050, -85)]


def roar_bed(c: Ctx, pts, crossovers=(200.0, 1500.0), am=((0.3, 2.0, 0.22), (0.5, 3.0, 0.28), (1.0, 6.0, 0.32)),
             flutter=(4.0, 12.0, 0.10), tag: str = "roar"):
    """Turbulent jet noise: one spectral envelope split into low/mid/high bands (power-complementary), each
    with independent log-normal turbulence AM. Returns (unit-RMS bed, [band envelopes])."""
    n = c.n
    freqs = np.fft.rfftfreq(n, 1.0 / SR)
    shape = db(curve_db(freqs, pts))
    shape[0] = 0.0
    f1, f2 = crossovers
    fs = np.maximum(freqs, 1e-6)
    p_lo = 1.0 / (1.0 + (fs / f1) ** 4)
    p_hi = 1.0 / (1.0 + (f2 / fs) ** 4)
    p_mid = np.clip(1.0 - p_lo - p_hi, 0.0, 1.0)
    out = np.zeros(n)
    envs = []
    for i, (p, (lo, hi, s)) in enumerate(zip((p_lo, p_mid, p_hi), am)):
        band = np.fft.irfft(gauss_spectrum(c.rng(f"{tag}/n{i}"), len(freqs)) * shape * np.sqrt(p), n)
        env = lognorm_env(smooth_mod(n, lo, hi, c.rng(f"{tag}/am{i}")), s)
        if i == 1 and flutter:
            env = env * lognorm_env(smooth_mod(n, flutter[0], flutter[1], c.rng(f"{tag}/fl")), flutter[2])
        out += band * env
        envs.append(env)
    return unit(out), envs


@sound("rocket.raptor_ignition", 3.5, attenuation=384, peak_db=-1.0, quality=0.6, preload=True, fade_out=0.3,
       subtitle="Raptor engines ignite")
def raptor_ignition(c: Ctx):
    n, t = c.n, c.t
    ign = [(0.32, 0.6), (0.70, 0.8), (1.06, 1.0)]       # (time, strength): centre 3, inner ring 10, outer ring 20
    # Torch igniters and chill-down before first light.
    ig = shaped_noise(n, [(5, -90), (1500, -30), (3500, -4), (6000, 0), (9000, -6), (14000, -30), (22050, -70)],
                      c.rng("ig"))
    igniter = ig * env_pts(t, [(0.0, 0.0), (0.06, 0.3), (0.30, 0.5), (0.45, 0.25), (1.3, 0.0)])
    sparks = crackle(c, n, 70.0, env=env_pts(t, [(0, 0.001), (0.05, 1), (0.36, 1), (0.6, 0.001)]),
                     dur_ms=(0.15, 0.4), sigma=0.6, hp_hz=2500.0, tag="sparks")
    # The roar steps up with each bank (crackle denser with it), then tails off for the booster_roar loop.
    lvl = np.zeros(n)
    for (t0, _), w, rise in zip(ign, (0.22, 0.33, 0.45), (0.35, 0.45, 0.65)):
        lvl += w * ramp(t, t0 + 0.02, rise)
    lvl *= 1.0 - 0.85 * ramp(t, 2.6, 0.9)
    thin, _ = roar_bed(c, ROAR_SHIP, crossovers=(300, 2000), tag="thin")
    full, envs = roar_bed(c, ROAR_BOOSTER, tag="full")
    wmix = ramp(t, 0.45, 1.6)
    roar = thin * np.sqrt(1.0 - wmix) + full * np.sqrt(wmix)
    fm = smooth_mod(n, 7.0, 18.0, c.rng("flutter"))
    flut = np.ones(n)
    for t0, s in ign:                                   # combustion settling after each light-up
        flut += 0.5 * s * np.exp(-np.maximum(t - t0, 0.0) / 0.35) * (t >= t0) * fm
    roar = roar * lvl * np.maximum(flut, 0.15)
    ref = (t > 1.9) & (t < 2.6)                         # full power: same glue as the booster loop
    cr = crackle(c, n, 90.0, env=np.maximum(lvl, 1e-3) * envs[1], gamma=2.0, dur_ms=(1.0, 3.0), sigma=0.75)
    cr = match_band(lp(c, cr, 9000), roar, 1000, 8000, 5.0, sel=ref)
    body = glue_ref(roar + cr, ref, -14.0, 0.5)
    wh = np.zeros(n)                                    # the three whoomps
    for k, (t0, s) in enumerate(ign):
        wh += s * thud(n, t0, 82.0 - 3 * k, 30.0 + 2 * k, tau=0.24 + 0.04 * k, glide=0.10, attack=0.006)
        bn = lp(c, shaped_noise(n, [(5, -60), (30, -6), (60, 0), (150, -2), (300, -10), (600, -24), (22050, -100)],
                                c.rng(f"wb{k}")), 450, 4)
        wh += 0.8 * s * unit_peak(bn) * decay_env(t, t0 - 0.004, 0.22, attack=0.010)
        wh += 0.35 * s * unit_peak(hp(c, white(c, f"pop{k}"), 700)) * decay_env(t, t0, 0.007, attack=0.0005)
    mix = body + 0.95 * unit_peak(wh) + 0.02 * igniter + 0.08 * unit_peak(sparks)
    ir = reverb_ir(c.rng("ir"), rt60=1.2, early=[(0.035, 0.6), (0.082, 0.4), (0.15, 0.25)], hf_damp=0.5,
                   build=0.01)
    return soft_limit(reverb(c, mix, ir, -11.0), 1.0, 0.6)


@sound("rocket.booster_roar", 8.0, loop=True, attenuation=512, peak_db=-1.0, quality=0.6, preload=True,
       subtitle="Super Heavy roars")
def booster_roar(c: Ctx):
    bed, envs = roar_bed(c, ROAR_BOOSTER)
    cr = crackle(c, c.n, 80.0, env=envs[1], dur_ms=(1.0, 3.0), sigma=0.75, hp_hz=700.0)
    cr = lp(c, cr, 9000)                                # ~100 m of air absorption
    mix = bed + match_band(cr, bed, 1000, 8000, 5.0)    # crackle dominates above 1 kHz
    return glue(mix, -14.0, 0.5)


@sound("rocket.ship_roar", 6.0, loop=True, attenuation=384, peak_db=-1.5, quality=0.6, preload=True,
       subtitle="Starship engines roar")
def ship_roar(c: Ctx):
    bed, envs = roar_bed(c, ROAR_SHIP, crossovers=(300, 2000),
                         am=((0.4, 2.5, 0.2), (0.6, 4.0, 0.26), (1.5, 8.0, 0.3)))
    hiss = shaped_noise(c.n, [(5, -90), (800, -30), (2000, -8), (4000, 0), (7000, -3), (11000, -15), (16000, -40),
                              (22050, -70)], c.rng("hiss")) * envs[2]
    cr = crackle(c, c.n, 95.0, env=envs[1], dur_ms=(0.6, 2.0), sigma=0.7, hp_hz=900.0)
    cr = lp(c, cr, 11000)
    mix = bed + match_band(hiss, bed, 2000, 8000, 2.0) + match_band(cr, bed, 1000, 8000, 5.0)
    return glue(mix, -14.0, 0.5)


@sound("rocket.engine_distant", 8.0, loop=True, attenuation=1024, peak_db=-3.0,
       subtitle="Rocket rumbles in the distance")
def engine_distant(c: Ctx):
    pts = [(5, -80), (10, -32), (18, -5), (30, 0), (60, 0), (120, -4), (200, -10), (300, -18), (400, -26),
           (600, -42), (1000, -62), (2000, -90), (22050, -120)]
    bed, envs = roar_bed(c, pts, crossovers=(60, 200), am=((0.15, 1.0, 0.35), (0.2, 1.5, 0.4), (0.3, 2.0, 0.4)),
                         flutter=(2.0, 6.0, 0.12))
    th = crackle(c, c.n, 28.0, env=envs[1], gamma=2.0, dur_ms=(8.0, 22.0), kappa=(0.6, 0.85), sigma=0.9,
                 smooth=9, hp_hz=None, tag="thumps")
    th = lp(c, th, 260, 4)                              # only the low part of each shock survives the distance
    th *= 1.1 * peak(bed) / max(np.percentile(np.abs(th), 99.97), 1e-12)
    ir = reverb_ir(c.rng("ir"), rt60=2.8, hf_damp=0.4, build=0.03, tone_lp=900)
    mix = reverb(c, bed + th, ir, -5.0)
    return lp(c, glue(lp(c, mix, 420, 4), -15.0, 0.6), 450, 4)   # filter again after the limiter


@sound("rocket.vacuum_hum", 6.0, loop=True, attenuation=48, peak_db=-4.0, lufs=-24.0, subtitle="Hull vibrates")
def vacuum_hum(c: Ctx):
    n = c.n
    r = c.rng("modes")
    base = shaped_noise(n, [(5, -80), (18, -30), (30, -3), (50, 0), (90, -2), (130, -8), (200, -20), (350, -40),
                            (22050, -120)], c.rng("base"))
    res = np.zeros(n)
    for i, f in enumerate([33.0, 41.0, 47.5, 58.0, 66.0, 79.0, 93.0, 108.0]):
        x = shaped_noise(n, [(5, -60), (20, 0), (300, 0), (22050, -20)], c.rng(f"m{i}"))
        res += unit(F(c, x, reson(f, r.uniform(8, 16)))) * (0.9 - 0.06 * i) * r.uniform(0.7, 1.0)
    thrust = lognorm_env(smooth_mod(n, 0.3, 1.2, c.rng("thrust")), 0.18)
    rumble = unit(base + 0.8 * unit(res)) * thrust
    vib = F(c, shaped_noise(n, [(5, -60), (20, 0), (300, 0), (22050, -20)], c.rng("vib")), reson(47.5, 10))
    gate = np.maximum(unit_peak(vib), 0.0) ** 2         # panels buzz on the vibration peaks
    rattle = unit(shaped_noise(n, [(5, -90), (150, -20), (300, 0), (700, -3), (1500, -20), (22050, -90)],
                               c.rng("rattle")) * gate)
    fw = c.qf(1187.0)                                   # turbopump whine, structure-borne
    ph = phase_of(fw * (1.0 + 0.0025 * smooth_mod(n, 0.2, 1.5, c.rng("wob"))))
    whine = (np.sin(ph) + 0.3 * np.sin(2 * ph + 0.4) + 0.12 * np.sin(3 * ph + 1.1))
    whine = unit(whine * lognorm_env(smooth_mod(n, 0.5, 3.0, c.rng("wamp")), 0.25))
    fan = shaped_noise(n, [(5, -90), (200, -20), (600, 0), (1500, -4), (4000, -20), (22050, -80)], c.rng("fan"))
    mix = rumble + db(-21) * rattle + db(-31) * whine + db(-38) * fan
    return glue(lp(c, mix, 2500, 2), -14.0, 0.6)


@sound("rocket.hot_staging", 3.0, attenuation=512, peak_db=-1.0, quality=0.6, preload=True, fade_out=0.35,
       subtitle="Hot staging")
def hot_staging(c: Ctx):
    n, t = c.n, c.t
    t0 = 0.06
    w = white(c, "bang")
    bang = bp(c, w, 60, 14000) * decay_env(t, t0, 0.045, attack=0.0004)
    bang2 = 0.5 * hp(c, w, 200) * decay_env(t, t0 + 0.013, 0.025, attack=0.0004)    # reflection off the dome
    sub = thud(n, t0, 70.0, 36.0, tau=0.16, glide=0.06, attack=0.002)
    modes = metal_modes(c.rng("modes"), 185.0, 3600.0, 40, tau0=0.7, tau_exp=0.7, amp_tilt=0.2, gap=(0.04, 0.14))
    ring = hit(c, n, t0, modes, strike(c.rng("exc"), 2.0, 7000.0), 1.8)
    bed, envs = roar_bed(c, ROAR_SHIP, crossovers=(300, 2000), am=((0.4, 2.5, 0.2), (0.6, 4.0, 0.26), (1.5, 8, 0.3)))
    lvl = ramp(t, t0 + 0.015, 0.35) * (1.0 - 0.75 * ramp(t, 1.8, 1.2))
    cr = crackle(c, n, 95.0, env=np.maximum(lvl, 1e-3) * envs[1], gamma=1.5, dur_ms=(0.6, 2.0), sigma=0.7,
                 hp_hz=900.0)
    roar = bed * lvl
    ref = (t > 0.6) & (t < 1.6)
    body = glue_ref(roar + match_band(lp(c, cr, 11000), roar, 1000, 8000, 5.0, sel=ref), ref, -14.0, 0.5)

    def sweep(tf, freqs):                               # plume venting through the hot-staging ring
        u = np.clip((tf - t0) / 0.6, 0.0, 1.0)[:, None]
        fc = 3200.0 * (650.0 / 3200.0) ** u
        return gauss_band(freqs[None, :], fc, 1.2)
    whoosh = stft_filter(c, white(c, "whoosh"), sweep) * decay_env(t, t0, 0.35, attack=0.01)
    impulse = unit_peak(unit_peak(bang + bang2) + 0.9 * unit_peak(sub))
    mix = body + 1.6 * impulse + 0.45 * unit_peak(ring) + 0.5 * unit_peak(whoosh)
    ir = reverb_ir(c.rng("ir"), rt60=1.4, early=[(0.05, 0.4), (0.12, 0.25)], hf_damp=0.45, build=0.01)
    return soft_limit(reverb(c, mix, ir, -12.0), 1.0, 0.55)


@sound("rocket.stage_separation", 1.5, variants=2, attenuation=64, peak_db=-1.5, preload=True, fade_out=0.2,
       subtitle="Stages separate")
def stage_separation(c: Ctx):
    n, t = c.n, c.t
    r = c.rng("modes")
    f1 = [152.0, 171.0][c.variant]
    # A big welded steel structure has a very dense, quickly damped mode spectrum: it clanks, it does not chime.
    modes = metal_modes(r, f1, 2600.0, 70, tau0=0.6, tau_exp=0.6, amp_tilt=0.15, gap=(0.03, 0.11))
    hi_modes = metal_modes(c.rng("hi"), 2600.0, 6500.0, 24, tau0=0.06, tau_exp=0.5, amp_tilt=0.0, gap=(0.03, 0.09))
    t_main = 0.045
    clank = hit(c, n, t_main, modes + hi_modes, strike(c.rng("exc"), 3.0, 6000.0), 1.5)
    crash = bp(c, white(c, "crash"), 250, 7000) * decay_env(t, t_main, 0.05, attack=0.0008)
    pre = hit(c, n, 0.012, metal_modes(c.rng("pre"), 900.0, 3800.0, 6, tau0=0.03), strike(c.rng("pexc"), 0.8, 9000.0), 0.3)
    t_b = t_main + r.uniform(0.09, 0.14)
    bounce = hit(c, n, t_b, modes, strike(c.rng("bexc"), 2.5, 3500.0), 1.4)
    th = thud(n, t_main, 78.0, 52.0, tau=0.1, glide=0.03, attack=0.002)
    th += 0.5 * unit_peak(lp(c, white(c, "thudn"), 300, 4)) * decay_env(t, t_main, 0.05, attack=0.002)
    mix = (unit_peak(clank) + 0.5 * unit_peak(crash) + 0.25 * unit_peak(pre) + 0.3 * unit_peak(bounce)
           + 0.9 * unit_peak(th))
    ir = reverb_ir(c.rng("ir"), rt60=0.7, hf_damp=0.8, build=0.004, early=[(0.006, 0.5), (0.011, 0.4), (0.019, 0.3)])
    return punch(reverb(c, mix, ir, -12.0), 2.0)


@sound("rocket.sonic_boom", 2.0, variants=2, attenuation=1024, peak_db=-1.0, preload=True, fade_out=0.35,
       subtitle="Sonic boom")
def sonic_boom(c: Ctx):
    n, t = c.n, c.t
    r = c.rng("boom")
    sep = [0.20, 0.17][c.variant]
    t1 = 0.03
    # Two N-waves (nose and aft shock systems). Each is short enough that its own front and rear shocks fuse
    # into one "boom"; rise times of 1.5-1.8 ms and a low-pass stand in for absorption and turbulence.
    d1, d2 = r.uniform(0.018, 0.026), r.uniform(0.016, 0.022)
    booms = lp(c, nwave(n, t1, d1, 1.0, rise=0.0015), 3000, 2) + lp(c, nwave(n, t1 + sep, d2, 0.85, rise=0.0018), 2400, 2)
    g = nsamp(0.0065)                                   # ground reflection a few ms later
    booms = booms + 0.7 * np.concatenate([np.zeros(g), booms[:-g]])
    rum = lp(c, shaped_noise(n, [(5, -80), (20, -6), (40, 0), (90, -2), (180, -10), (400, -28), (22050, -120)],
                             c.rng("rum")), 400, 4)
    roll = lognorm_env(smooth_mod(n, 1.5, 7.0, c.rng("roll")), 0.5)
    rum *= ramp(t, t1 + 0.01, 0.06) * np.exp(-np.maximum(t - t1 - 0.07, 0.0) / 0.55) * roll
    dry = booms + 0.35 * unit_peak(rum) * peak(booms)
    ir = reverb_ir(c.rng("ir"), rt60=1.6, early=[(0.055, 0.5), (0.13, 0.35), (0.24, 0.3), (0.41, 0.2), (0.62, 0.12)],
                   hf_damp=0.35, tone_lp=2500, build=0.02)
    return punch(reverb(c, dry, ir, -4.0), 5.0, 0.5)


@sound("rocket.vent", 4.0, loop=True, attenuation=96, peak_db=-5.0, lufs=-21.0, subtitle="Propellant vents")
def vent(c: Ctx):
    n = c.n
    hiss = shaped_noise(n, [(5, -100), (300, -34), (800, -14), (1500, -4), (3000, 0), (6000, -2), (8000, -7),
                            (10000, -14), (13000, -26), (18000, -50), (22050, -70)], c.rng("hiss"))
    body = shaped_noise(n, [(5, -100), (80, -30), (200, -6), (400, 0), (800, -4), (1600, -20), (4000, -50),
                            (22050, -100)], c.rng("body"))
    bright = shaped_noise(n, [(5, -100), (2500, -20), (5000, 0), (8000, -4), (11000, -16), (15000, -36),
                              (22050, -70)], c.rng("bright"))
    gust = lognorm_env(smooth_mod(n, 0.15, 1.2, c.rng("gust")), 0.32)
    flutter = 1.0 + 0.08 * smooth_mod(n, 4.0, 10.0, c.rng("flutter"))
    r = c.rng("spit")
    spits = np.zeros(n)
    sp_noise = bp(c, white(c, "spitn"), 1800, 6500)
    for p in poisson_events(n, 6.0, r):                 # two-phase flow 'spits'
        ln = nsamp(r.uniform(0.005, 0.02))
        seg = np.take(sp_noise, np.arange(p, p + ln), mode="wrap") * np.hanning(ln) * r.uniform(0.8, 2.2)
        add_into(c, spits, p, seg)
    mix = hiss * gust * flutter + db(-12) * body * gust ** 0.7 + db(-8) * bright * gust ** 2 + spits
    return glue(mix, -13.0, 0.6)


@sound("rocket.deluge", 6.0, stream=True, attenuation=256, peak_db=-1.5, fade_out=0.7, subtitle="Water deluge roars")
def deluge(c: Ctx):
    n, t = c.n, c.t
    t0 = 0.12
    level = env_pts(t, [(0.0, 0.0), (t0, 0.0), (t0 + 0.35, 1.25), (1.2, 1.0), (2.5, 0.85), (5.4, 0.32), (6.0, 0.25)])
    rush = shaped_noise(n, [(5, -90), (40, -14), (80, -6), (200, -5), (500, -2), (1000, 0), (2000, -1), (4000, -5),
                            (8000, -14), (14000, -30), (22050, -60)], c.rng("rush"))
    churn = lognorm_env(smooth_mod(n, 4.0, 30.0, c.rng("churn")), 0.38)
    low = lp(c, rush, 300, 2)
    high = hp(c, rush, 300, 2) * churn
    r = c.rng("splash")
    splash = np.zeros(n)
    centres = [1200, 1800, 2600, 3600, 5000, 6500]
    for k, fcen in enumerate(centres):                  # droplet / splash grains
        pos = poisson_events(n, 70.0 * np.clip(level, 0, None) ** 1.5, r)
        grain = bp(c, white(c, f"g{k}"), fcen / 1.4, min(fcen * 1.4, 20000), 2)
        for p in pos:
            ln = nsamp(r.uniform(0.002, 0.008))
            seg = np.take(grain, np.arange(p, p + ln), mode="wrap") * np.hanning(ln) * r.lognormal(0, 0.5)
            add_into(c, splash, p, seg)
    spray = shaped_noise(n, [(5, -100), (2500, -24), (5000, -4), (8000, 0), (11000, -8), (16000, -30), (22050, -60)],
                         c.rng("spray"))
    thump = thud(n, t0, 52.0, 34.0, tau=0.12, glide=0.05)
    thump += 0.6 * unit_peak(lp(c, white(c, "tn"), 300, 4)) * decay_env(t, t0, 0.09)
    mid = shaped_noise(n, [(5, -90), (150, -20), (400, -4), (700, 0), (1200, -4), (2500, -18), (22050, -80)],
                       c.rng("mid")) * churn ** 0.6
    body = (unit(low) * level + unit(high) * level ** 1.4 + db(-4) * mid * level ** 1.3
            + 0.7 * unit(splash) * level ** 1.2 + db(-10) * spray * level ** 2.0)
    mix = body + 1.2 * unit_peak(thump) * peak(body) / 3.0
    return punch(mix, 4.0)


@sound("rocket.flap_actuator", 0.8, variants=3, attenuation=32, peak_db=-5.0, lufs=-19.0, fade_out=0.03,
       subtitle="Flap actuator whirs")
def flap_actuator(c: Ctx):
    n, t = c.n, c.t
    cfg = [dict(t_on=0.03, t_up=0.11, t_off=0.62, f_lo=120.0, f_hi=262.0),
           dict(t_on=0.02, t_up=0.09, t_off=0.70, f_lo=135.0, f_hi=228.0),
           dict(t_on=0.03, t_up=0.07, t_off=0.42, f_lo=150.0, f_hi=298.0)][c.variant]
    t_on, t_off, f_lo, f_hi = cfg["t_on"], cfg["t_off"], cfg["f_lo"], cfg["f_hi"]
    f = f_lo + (f_hi - f_lo) * ramp(t, t_on, cfg["t_up"]) - 0.55 * (f_hi - f_lo) * ramp(t, t_off - 0.05, 0.12)
    f = f * (1.0 + 0.025 * smooth_mod(n, 3.0, 9.0, c.rng("load")) + 0.006 * smooth_mod(n, 20.0, 60.0, c.rng("jit")))
    f = np.maximum(f, 40.0)
    ph = phase_of(f)
    saw = bl_saw(ph, f)
    gear_whine = np.sin(4.3 * ph) * 0.25
    rattle = bp(c, white(c, "rattle"), 1000, 5000) * (0.5 + 0.5 * np.sin(ph)) ** 4
    mesh = bp(c, white(c, "mesh"), 800, 6000) * (0.55 + 0.45 * np.sin(7.0 * ph)) ** 2   # gear-tooth meshing
    amp = ramp(t, t_on, 0.03) * (1.0 - ramp(t, t_off, 0.09))
    motor = (unit_peak(saw) + gear_whine + 0.35 * unit_peak(rattle) + 0.3 * unit_peak(mesh)) * amp
    motor = F(c, motor, peq(900, 1.2, 6.0))
    motor = F(c, motor, peq(2400, 2.0, 4.0))
    motor = lp(c, motor, 6000, 2)
    r = c.rng("clicks")
    click = hit(c, n, t_on - 0.006, metal_modes(r, 2600.0, 6000.0, 4, tau0=0.006), strike(r, 0.4, 9000), 0.05)
    clunk = hit(c, n, t_off + 0.07, metal_modes(r, 380.0, 2500.0, 8, tau0=0.05), strike(r, 1.5, 3000), 0.3)
    clunk += 0.6 * thud(n, t_off + 0.07, 140.0, 110.0, tau=0.03, glide=0.01)
    return unit_peak(motor) + 0.25 * unit_peak(click) + 0.45 * unit_peak(clunk)


@sound("rocket.landing_legs", 1.2, variants=2, attenuation=64, peak_db=-2.0, lufs=-16.0, fade_out=0.12,
       subtitle="Landing legs lock")
def landing_legs(c: Ctx):
    n, t = c.n, c.t
    t_lock = [0.86, 0.80][c.variant]
    hiss = shaped_noise(n, [(5, -100), (500, -30), (1000, -10), (2000, 0), (4000, -1), (7000, -8), (11000, -24),
                            (22050, -70)], c.rng("hiss"))
    hiss_env = ramp(t, 0.02, 0.03) * env_pts(t, [(0.0, 1.0), (0.3, 0.85), (t_lock, 0.6)]) * (1 - ramp(t, t_lock - 0.02, 0.04))
    fwh = 640.0 + 260.0 * ramp(t, 0.05, t_lock - 0.1)
    whine = np.sin(phase_of(fwh)) * ramp(t, 0.04, 0.08) * (1 - ramp(t, t_lock - 0.03, 0.05))
    rumble = unit(lp(c, bp(c, white(c, "rum"), 50, 240), 300)) * lognorm_env(smooth_mod(n, 4, 18, c.rng("ram")), 0.4)
    rumble *= ramp(t, 0.08, 0.1) * (1 - ramp(t, t_lock - 0.05, 0.06))
    r = c.rng("lock")
    thunk = thud(n, t_lock, 98.0, 70.0, tau=0.07, glide=0.02, attack=0.0015)
    thunk += 0.6 * lp(c, white(c, "tk"), 420, 4) * decay_env(t, t_lock, 0.035, attack=0.001)
    click = hit(c, n, t_lock + 0.002, metal_modes(r, 1700.0, 5500.0, 8, tau0=0.05, tau_exp=0.4), strike(r, 0.6, 8000), 0.3)
    rattle = np.zeros(n)
    for k, dt in enumerate((0.032, 0.056, 0.073)):
        rattle += 0.5 ** (k + 1) * hit(c, n, t_lock + dt, metal_modes(r, 2200.0, 5000.0, 4, tau0=0.015),
                                       strike(r, 0.5, 8000), 0.1)
    mix = (unit(hiss) * hiss_env * 0.5 + db(-20) * whine * 2 + 0.25 * rumble
           + 2.2 * unit_peak(thunk) + 0.8 * unit_peak(click) + 0.5 * rattle / max(peak(click), 1e-9))
    ir = reverb_ir(c.rng("ir"), rt60=0.5, hf_damp=0.6, build=0.004)
    mix = reverb(c, mix, ir, -16.0)
    return punch(mix, 1.5)


@sound("rocket.touchdown", 2.0, attenuation=256, peak_db=-1.0, preload=True, fade_in=0.02, fade_out=0.25,
       subtitle="Starship touches down")
def touchdown(c: Ctx):
    n, t = c.n, c.t
    t_imp = 0.10
    bed, envs = roar_bed(c, ROAR_SHIP, crossovers=(300, 2000), tag="burn")
    roar_env = np.where(t < 0.12, 1.0, np.exp(-np.maximum(t - 0.12, 0.0) / 0.12))
    roar = bed * roar_env
    roar = roar + match_band(lp(c, crackle(c, n, 90.0, env=np.maximum(roar_env, 1e-3) * envs[1], dur_ms=(0.6, 2.0),
                                           hp_hz=900.0), 11000), roar, 1000, 8000, 5.0, sel=t < 0.12)
    roar = lp(c, roar, 7000)
    chuff = unit_peak(lp(c, white(c, "chuff"), 220, 4)) * decay_env(t, 0.42, 0.1, attack=0.02)
    chuff += 0.5 * thud(n, 0.42, 58.0, 40.0, tau=0.07, glide=0.03, attack=0.015)
    impact = thud(n, t_imp, 64.0, 30.0, tau=0.15, glide=0.05, attack=0.003)
    impact += 0.7 * unit_peak(lp(c, white(c, "imp"), 300, 4)) * decay_env(t, t_imp, 0.09, attack=0.002)
    r = c.rng("crunch")
    crunch = np.zeros(n)
    cn = bp(c, white(c, "crn"), 300, 3000)
    for p in poisson_events(n, 400.0 * decay_env(t, t_imp, 0.12, attack=0.003), r):
        ln = nsamp(r.uniform(0.001, 0.005))
        add_into(c, crunch, p, np.take(cn, np.arange(p, p + ln), mode="wrap") * np.hanning(ln) * r.lognormal(0, 0.5))
    ring = hit(c, n, t_imp, metal_modes(c.rng("ring"), 92.0, 900.0, 40, tau0=0.35, tau_exp=0.5, gap=(0.03, 0.10)),
               strike(r, 6.0, 1500), 1.8)
    ck = creak(c, n, [(0.0, 25.0), (0.55, 25.0), (0.9, 70.0), (1.3, 45.0), (1.7, 30.0)],
               [(0.0, 0.0), (0.55, 0.0), (0.75, 1.0), (1.2, 0.7), (1.62, 0.0)],
               [(420, 30, 1.0), (780, 40, 0.8), (1150, 35, 0.6), (1730, 50, 0.45), (2400, 60, 0.3)], c.rng("creak"))
    ck2 = creak(c, n, [(0.0, 50.0), (1.35, 50.0), (1.6, 90.0)], [(0.0, 0.0), (1.35, 0.0), (1.45, 0.6), (1.7, 0.0)],
                [(610, 30, 1.0), (1320, 45, 0.6), (2050, 55, 0.4)], c.rng("creak2"))
    debris = np.zeros(n)
    dn = bp(c, white(c, "deb"), 500, 4000)
    for p in poisson_events(n, 60.0 * decay_env(t, t_imp + 0.1, 0.35, attack=0.05), r):
        ln = nsamp(r.uniform(0.002, 0.006))
        add_into(c, debris, p, np.take(dn, np.arange(p, p + ln), mode="wrap") * np.hanning(ln) * r.lognormal(0, 0.6))
    mix = (1.2 * unit_peak(roar) + 0.9 * unit_peak(chuff) + 2.6 * unit_peak(impact) + 0.35 * unit_peak(crunch)
           + 0.5 * unit_peak(ring) + 0.3 * unit_peak(ck + ck2) + 0.12 * unit_peak(debris))
    ir = reverb_ir(c.rng("ir"), rt60=1.1, hf_damp=0.5, build=0.01, early=[(0.04, 0.3), (0.09, 0.2)])
    mix = reverb(c, mix, ir, -14.0)
    return punch(mix, 2.5)


@sound("rocket.engine_cutoff", 1.5, variants=2, attenuation=384, peak_db=-1.0, preload=True, fade_in=0.015,
       fade_out=0.15, subtitle="Engines shut down")
def engine_cutoff(c: Ctx):
    n, t = c.n, c.t
    v = c.variant
    bed, envs = roar_bed(c, ROAR_BOOSTER if v == 0 else ROAR_SHIP, tag="roar")
    cr = crackle(c, n, 85.0, env=envs[1], gamma=1.5, dur_ms=(0.8, 2.5), hp_hz=800.0)
    full = glue(bed + match_band(lp(c, cr, 10000), bed, 1000, 8000, 5.0), -14.0, 0.5)   # same as the loops
    t_hold = 0.06
    lvl = np.where(t < t_hold, 1.0, np.exp(-np.maximum(t - t_hold, 0.0) / 0.12))
    lvl = np.maximum(lvl * (1.0 + 0.35 * ramp(t, 0.06, 0.1) * smooth_mod(n, 8.0, 25.0, c.rng("sputter"))), 0.0)

    def darken(tf, freqs):                              # chamber pressure falls -> spectrum collapses downward
        u = np.clip((tf - t_hold) / 0.4, 0.0, 1.0)[:, None]
        fc = 8000.0 * (400.0 / 8000.0) ** u
        return 1.0 / np.sqrt(1.0 + (freqs[None, :] / fc) ** 4)
    roar = stft_filter(c, full * lvl, darken)
    t_ch = [0.34, 0.37][v]                             # the last propellant leaves as a low puff
    chuff = unit_peak(lp(c, white(c, "chuff"), 220, 4)) * decay_env(t, t_ch, 0.09, attack=0.02)
    chuff += 0.6 * thud(n, t_ch, 60.0, 40.0, tau=0.07, glide=0.03, attack=0.015)
    purge = bp(c, white(c, "purge"), 1000, 5000) * decay_env(t, 0.36, 0.4, attack=0.08)
    r = c.rng("ping")
    pings = np.zeros(n)
    for _ in range(3):                                 # thermal contraction 'tinks'
        pings += hit(c, n, r.uniform(0.6, 1.35), metal_modes(r, r.uniform(2200, 4200), 9000.0, 3, tau0=0.04),
                     strike(r, 0.3, 10000), 0.2) * r.uniform(0.4, 1.0)
    return roar + 0.3 * unit_peak(chuff) + 0.025 * unit_peak(purge) + 0.03 * unit_peak(pings)


@sound("rocket.entry_plasma", 6.0, loop=True, attenuation=256, peak_db=-2.0, quality=0.6, subtitle="Plasma roars")
def entry_plasma(c: Ctx):
    n = c.n
    deep, envs = roar_bed(c, [(5, -80), (15, -30), (25, -6), (45, 0), (90, 0), (180, -5), (350, -14), (700, -24),
                              (1500, -36), (3000, -50), (22050, -100)],
                          crossovers=(80, 250), am=((0.3, 1.5, 0.35), (0.5, 3.0, 0.35), (1.0, 6.0, 0.3)), tag="deep")
    T = n / SR
    m1 = smooth_mod(n, 1.0 / T, 0.6, c.rng("sw1"))
    m2 = smooth_mod(n, 1.0 / T, 0.9, c.rng("sw2"))
    hop = 512
    m1f, m2f = m1[::hop], m2[::hop]

    def sweeps(tf, freqs):
        fc1 = 260.0 * (1300.0 / 260.0) ** np.clip(0.5 + 0.22 * m1f, 0.0, 1.0)
        fc2 = 500.0 * (2200.0 / 500.0) ** np.clip(0.5 + 0.22 * m2f, 0.0, 1.0)
        f = freqs[None, :]
        tilt = (np.maximum(f, 20.0) / 500.0) ** -0.5
        return tilt * (gauss_band(f, fc1[:, None], 0.9) + 0.6 * gauss_band(f, fc2[:, None], 0.7))
    wind = stft_filter(c, white(c, "wind"), sweeps, hop=hop)
    hiss = shaped_noise(n, [(5, -90), (1500, -30), (3000, -6), (6000, 0), (9000, -6), (14000, -24), (22050, -60)],
                        c.rng("hiss"))
    r = c.rng("gate")
    pos = poisson_events(n, 300.0, r)
    gate = place(c, n, pos, r.lognormal(0.0, 0.8, len(pos)), hann_kernel(nsamp(0.0015)) * nsamp(0.0015))
    crackle_hiss = hiss * (0.35 + gate / max(np.percentile(gate, 99.5), 1e-9))
    th = np.zeros(n)
    for p in poisson_events(n, 1.5, c.rng("buffet")):  # buffet thumps
        ln = nsamp(0.35)
        tt = np.arange(ln) / SR
        k = np.sin(TAU * np.cumsum(32 + 28 * np.exp(-tt / 0.05)) / SR) * np.exp(-tt / 0.09) * smooth01(tt / 0.006)
        add_into(c, th, p, k * c.rng(f"b{p}").uniform(0.5, 1.0))
    mix = deep + db(-14) * unit(wind) * envs[1] + db(-15) * unit(crackle_hiss) * envs[2] + 0.5 * th
    return glue(mix, -14.0, 0.55)


@sound("rocket.countdown_beep", 0.25, attenuation=32, peak_db=-6.0, lufs=-20.0, preload=True, fade_out=0.005,
       subtitle="Countdown beeps")
def countdown_beep(c: Ctx):
    t = c.t
    return np.sin(TAU * 1000.0 * t) * ramp(t, 0.0, 0.008) * (1.0 - ramp(t, 0.17, 0.06))


def chime(t: np.ndarray, t0: float, f: float, decay: float = 0.35) -> np.ndarray:
    """A soft electronic chime note: a few slightly inharmonic partials with faster-decaying overtones."""
    tt = t - t0
    m = tt >= 0
    y = np.zeros(len(t))
    for ratio, a, d in [(1.0, 1.0, decay), (2.0, 0.28, decay * 0.5), (3.01, 0.1, decay * 0.3), (4.2, 0.04, decay * 0.18)]:
        y[m] += a * np.sin(TAU * f * ratio * tt[m]) * np.exp(-tt[m] / d)
    return y * smooth01(np.maximum(tt, 0.0) / 0.004)


@sound("rocket.go_tone", 0.6, attenuation=32, peak_db=-6.0, lufs=-20.0, preload=True, fade_out=0.06,
       subtitle="Go tone chimes")
def go_tone(c: Ctx):
    t = c.t
    y = chime(t, 0.0, 880.0, 0.3) + 0.95 * chime(t, 0.16, 1318.5, 0.38)
    ir = reverb_ir(c.rng("ir"), rt60=0.35, hf_damp=0.6, build=0.003)
    return reverb(c, y, ir, -12.0)


@sound("rocket.alarm", 1.0, attenuation=32, peak_db=-6.0, lufs=-19.0, fade_out=0.015, subtitle="Master alarm blares")
def alarm(c: Ctx):
    t = c.t
    hi = (np.floor(t / 0.125) % 2) == 0
    f = np.where(hi, 1000.0, 800.0)
    ph = phase_of(smooth_curve(f, 0.003))               # 3 ms glides, no splatter
    wave = np.sin(ph) + 0.33 * np.sin(3 * ph) + 0.15 * np.sin(5 * ph) + 0.07 * np.sin(7 * ph)
    return wave * ramp(t, 0.0, 0.005)


@sound("rocket.chopsticks", 2.5, attenuation=256, peak_db=-2.0, preload=True, fade_out=0.25,
       subtitle="Chopsticks close")
def chopsticks(c: Ctx):
    n, t = c.n, c.t
    t_cl = 2.05
    run = ramp(t, 0.0, 0.25) * (1.0 - ramp(t, t_cl - 0.02, 0.1))
    speed = 0.8 + 0.2 * ramp(t, 0.05, 0.6) - 0.08 * ramp(t, t_cl - 0.35, 0.3)
    ph = phase_of(24.0 * speed)
    drone = sum(a * np.sin(k * ph + 0.4 * k) for k, a in [(1, 1.0), (2, 0.8), (3, 0.5), (4, 0.45), (6, 0.25),
                                                          (8, 0.18), (12, 0.1), (24, 0.12), (25, 0.08)])
    drone = lp(c, drone + 0.3 * lp(c, white(c, "gear"), 400, 2) * (0.5 + 0.5 * np.sin(12 * ph)) ** 2, 1200, 2)
    pump = np.sin(phase_of(950.0 + 300.0 * ramp(t, 0.05, 0.9)))
    pump += 0.3 * np.sin(2 * phase_of(950.0 + 300.0 * ramp(t, 0.05, 0.9)) + 0.5)
    flow = bp(c, white(c, "flow"), 1000, 4000)
    rum = lp(c, bp(c, white(c, "rum"), 40, 250), 300) * lognorm_env(smooth_mod(n, 2.0, 9.0, c.rng("rm")), 0.35)
    r = c.rng("knock")
    knocks = np.zeros(n)
    for tk in np.arange(0.35, t_cl - 0.1, 0.36):
        knocks += hit(c, n, tk + r.uniform(-0.03, 0.03), metal_modes(r, 260.0, 1800.0, 6, tau0=0.08),
                      strike(r, 2.0, 2500), 0.4) * r.uniform(0.4, 0.8)
    clamp = hit(c, n, t_cl, metal_modes(c.rng("clamp"), 70.0, 1500.0, 24, tau0=1.3, tau_exp=0.55, amp_tilt=0.15),
                strike(r, 4.0, 4000), 2.0)
    crack = hit(c, n, t_cl, metal_modes(r, 1800.0, 6000.0, 6, tau0=0.03), strike(r, 0.6, 9000), 0.2)
    crack = unit_peak(crack) + 0.8 * unit_peak(bp(c, white(c, "crash"), 200, 6000) * decay_env(t, t_cl, 0.08, attack=0.001))
    th = thud(n, t_cl, 58.0, 38.0, tau=0.18, glide=0.04, attack=0.002)
    machine = (unit(drone) + db(-16) * unit(pump) + db(-22) * unit(flow) + 0.6 * unit(rum)) * run \
        + 0.6 * knocks / max(peak(knocks), 1e-9) * run
    mix = 0.45 * unit_peak(machine) + 1.0 * unit_peak(clamp) + 0.3 * unit_peak(crack) + 0.9 * unit_peak(th)
    ir = reverb_ir(c.rng("ir"), rt60=1.6, early=[(0.07, 0.4), (0.16, 0.3), (0.29, 0.2)], hf_damp=0.45, build=0.015)
    mix = reverb(c, mix, ir, -9.0)
    return punch(mix, 2.0)


# =============================================================================================================
# MARS ENVIRONMENT
# =============================================================================================================

@sound("mars.wind", 12.0, loop=True, stream=True, attenuation=16, peak_db=-12.0, subtitle="Martian wind blows")
def mars_wind(c: Ctx):
    n = c.n
    gust = lognorm_env(smooth_mod(n, 0.04, 0.3, c.rng("gust")), 0.7)
    deep = shaped_noise(n, [(5, -60), (12, -18), (20, -3), (40, 0), (80, 0), (140, -4), (220, -12), (350, -26),
                            (500, -40), (800, -60), (22050, -140)], c.rng("deep"))
    bright = shaped_noise(n, [(5, -90), (60, -24), (150, -4), (260, 0), (400, -6), (550, -18), (800, -36),
                              (1300, -60), (22050, -140)], c.rng("bright"))
    buffet = lognorm_env(smooth_mod(n, 0.6, 3.5, c.rng("buffet")), 0.22)
    mix = deep * gust ** 0.7 * buffet + db(-6) * bright * gust ** 1.6 * buffet
    return lp(c, mix, 520, 4)


@sound("mars.dust_storm", 10.0, loop=True, stream=True, attenuation=16, peak_db=-7.0, lufs=-25.0,
       subtitle="Dust storm howls")
def dust_storm(c: Ctx):
    n = c.n
    gust = lognorm_env(smooth_mod(n, 0.1, 0.6, c.rng("gust")), 0.42)
    rumble = shaped_noise(n, [(5, -60), (12, -16), (20, -3), (40, 0), (100, 0), (200, -4), (350, -12), (550, -24),
                              (800, -40), (1500, -70), (22050, -140)], c.rng("rumble"))
    buffet = lognorm_env(smooth_mod(n, 1.0, 5.0, c.rng("buffet")), 0.25)
    T = n / SR
    mm = smooth_mod(n, 1.0 / T, 0.5, c.rng("moan"))[::512]

    def moan(tf, freqs):
        fc = 180.0 * (420.0 / 180.0) ** smooth01(0.5 + 0.35 * mm)
        return gauss_band(freqs[None, :], fc[:, None], 0.5)
    howl = stft_filter(c, white(c, "howl"), moan)
    r = c.rng("grains")
    rate = 900.0 * gust ** 1.5
    pos = poisson_events(n, rate, r)
    imp = np.zeros(n)
    np.add.at(imp, pos, r.lognormal(0.0, 0.6, len(pos)) * r.choice([-1.0, 1.0], len(pos)))
    patter = bp(c, imp, 700, 3500, 2)
    patter = lp(c, patter, 2200, 4)                    # still muffled
    hiss = lp(c, bp(c, white(c, "hiss"), 900, 2800), 2200, 4) * gust ** 2
    mix = (rumble * gust * buffet + db(-10) * unit(howl) * gust ** 1.3 + db(-15) * unit(patter)
           + db(-26) * unit(hiss))
    return lp(c, mix, 2600, 4)


@sound("mars.dust_devil", 5.0, loop=True, attenuation=64, peak_db=-8.0, lufs=-24.0, subtitle="Dust devil whirls")
def dust_devil(c: Ctx):
    n, t = c.n, c.t
    T = n / SR
    rot = c.qf(3.0)
    hop = 256
    # rotation phase with a periodic random wander, so the swirl is not a metronome
    rph = TAU * rot * t + 1.3 * smooth_mod(n, 1.0 / T, 1.2, c.rng("wander"))
    rphf = rph[::hop]

    def swirl_mag(tf, freqs):
        fc = 330.0 * 2.0 ** (0.5 * np.sin(rphf) + 0.25 * np.sin(TAU * 2.0 * tf / T + 1.0))
        f = freqs[None, :]
        return gauss_band(f, fc[:, None], 1.0) * (np.maximum(f, 20.0) / 300.0) ** -0.3
    swirl = stft_filter(c, white(c, "swirl"), swirl_mag, hop=hop)
    am = (0.4 + 0.6 * (0.5 + 0.5 * np.cos(rph))) ** 1.5
    d = nsamp(0.0018) + nsamp(0.0012) * np.sin(TAU * 2.0 * t / T)
    phased = swirl * am
    phased = phased + 0.75 * circ_delay(phased, d)      # slowly sweeping flanger = phasing
    core = shaped_noise(n, [(5, -60), (15, -12), (30, 0), (80, 0), (150, -8), (300, -24), (22050, -120)], c.rng("core"))
    core *= lognorm_env(smooth_mod(n, 0.2, 1.0, c.rng("coream")), 0.3)
    r = c.rng("grit")
    pos = poisson_events(n, 500.0 * (0.4 + 0.6 * (0.5 + 0.5 * np.cos(rph - 0.8))), r)
    imp = np.zeros(n)
    np.add.at(imp, pos, r.lognormal(0.0, 0.6, len(pos)) * r.choice([-1.0, 1.0], len(pos)))
    grit = lp(c, bp(c, imp, 600, 3000), 1600)
    mix = unit(phased) + 0.7 * core + db(-16) * unit(grit)
    return lp(c, mix, 1100, 4)


@sound("mars.sublimation", 1.5, variants=3, attenuation=12, peak_db=-3.0, lufs=-25.0, fade_out=0.25,
       subtitle="CO₂ ice fizzes")
def sublimation(c: Ctx):
    n, t = c.n, c.t
    r = c.rng("crackle")
    onset = 0.01 + 0.02 * c.variant
    rate = (160.0 * np.exp(-np.maximum(t - onset, 0) / (0.4 + 0.12 * c.variant)) + 25.0) * ramp(t, onset, 0.04)
    pos = poisson_events(n, rate, r)
    imp = np.zeros(n)
    np.add.at(imp, pos, r.lognormal(0.0, 0.5, len(pos)) * r.choice([-1.0, 1.0], len(pos)))
    clicks = 0.4 * hp(c, imp, 1200)
    for fcen, q, g in [(1600 + 200 * c.variant, 5, 0.6), (2700, 7, 0.5), (4100 - 300 * c.variant, 6, 0.4)]:
        clicks += g * F(c, imp, reson(fcen, q))         # tiny cracking grains ring small resonances
    pops = np.zeros(n)
    for p in poisson_events(n, 5.0 * ramp(t, onset, 0.05) * np.exp(-t / 0.8), r):
        f0 = r.uniform(500, 1400)
        ln = nsamp(0.02)
        tt = np.arange(ln) / SR
        add_into(c, pops, p, np.sin(TAU * f0 * tt) * np.exp(-tt / r.uniform(0.003, 0.008)) * r.uniform(0.5, 1.0))
    fizz = bp(c, white(c, "fizz"), 1500, 6000) * decay_env(t, onset, 0.5, attack=0.04)
    mix = unit_peak(clicks) + 0.35 * unit_peak(pops) + 0.12 * unit_peak(fizz)
    return lp(c, mix, 5000, 4)                          # thin CO2 takes the top off


@sound("mars.ingenuity", 3.0, loop=True, attenuation=32, peak_db=-10.0, lufs=-25.0, subtitle="Helicopter hums")
def ingenuity(c: Ctx):
    n, t = c.n, c.t
    T = n / SR
    fa = c.qf(84.0)                                     # blade-pass: 2 blades x ~2537 rpm
    fb = fa + 1.0 / T                                   # second rotor, one beat per loop

    def rotor(f, tag):
        r = c.rng(tag)
        y = np.zeros(n)
        for k in range(1, 13):
            y += k ** -1.1 * np.sin(TAU * k * f * t + r.uniform(0, 0.6))   # near-aligned phases: blade 'thwop'
        return y
    hum = rotor(fa, "upper") + 0.85 * rotor(fb, "lower")
    hum *= 1.0 + 0.06 * smooth_mod(n, 0.5, 4.0, c.rng("flutter"))
    wind = shaped_noise(n, [(5, -60), (20, -6), (50, 0), (120, -6), (300, -30), (22050, -140)], c.rng("wind"))
    mix = unit(hum) + db(-20) * wind
    return lp(c, mix, 480, 4)                           # ~80 m of thin CO2 plus the microphone


# =============================================================================================================
# MACHINES
# =============================================================================================================

@sound("machine.moxie", 4.0, loop=True, attenuation=16, peak_db=-8.0, lufs=-26.0, subtitle="MOXIE hums")
def moxie(c: Ctx):
    n, t = c.n, c.t
    f_rot = c.qf(58.25)                                 # scroll orbit, ~3500 rpm
    ph = phase_of(f_rot * (1.0 + 0.0015 * smooth_mod(n, 0.25, 1.0, c.rng("wob"))))
    r = c.rng("harm")
    hum = np.zeros(n)
    body = [(50, -6), (150, 0), (350, 2), (600, -2), (1200, -12), (3000, -30), (22050, -60)]
    for k in range(1, 26):
        a = k ** -0.9 * db(curve_db(np.array([k * f_rot]), body)[0])
        if k == 4:
            a *= 2.0                                    # magnetic hum of a 4-pole motor
        hum += a * np.sin(k * ph + r.uniform(0, TAU))
    whine = np.sin(phase_of(c.qf(1747.5) * (1.0 + 0.002 * smooth_mod(n, 0.25, 0.75, c.rng("ww")))))
    pwm = np.sin(TAU * c.qf(7500.0) * t)
    mech = shaped_noise(n, [(5, -90), (600, -24), (1500, -6), (3000, 0), (5000, -6), (9000, -24), (22050, -70)],
                        c.rng("mech"))
    pulse = 0.6 + 0.4 * (0.5 + 0.5 * np.cos(ph)) ** 3
    flow = shaped_noise(n, [(5, -90), (200, -20), (500, -6), (1200, 0), (3000, -8), (7000, -30), (22050, -80)],
                        c.rng("flow"))
    return unit(hum) + db(-24) * whine + db(-40) * pwm + db(-22) * mech * pulse + db(-28) * flow


@sound("machine.sabatier", 4.0, loop=True, attenuation=16, peak_db=-8.0, lufs=-26.0, subtitle="Sabatier reactor hums")
def sabatier(c: Ctx):
    n, t = c.n, c.t
    f0 = c.qf(50.0)
    hum = sum(a * np.sin(TAU * k * f0 * t + p) for k, a, p in [(1, 1.0, 0), (2, 0.7, 0.5), (3, 0.35, 1.0),
                                                               (4, 0.25, 1.7), (6, 0.1, 2.2), (8, 0.05, 0.3)])
    rumble = shaped_noise(n, [(5, -80), (20, -12), (40, 0), (90, -2), (160, -10), (300, -26), (22050, -120)],
                          c.rng("rumble"))
    pump = 1.0 + 0.15 * np.cos(TAU * c.qf(1.25) * t)
    flow = shaped_noise(n, [(5, -100), (500, -30), (1200, -10), (2500, 0), (4500, -4), (7000, -16), (12000, -40),
                            (22050, -80)], c.rng("flow"))
    flow *= lognorm_env(smooth_mod(n, 0.25, 0.75, c.rng("flowam")), 0.12)
    return 0.6 * unit(hum) + 0.8 * rumble * pump + db(-17) * flow


@sound("machine.electrolyzer", 4.0, loop=True, attenuation=12, peak_db=-8.0, lufs=-26.0, subtitle="Electrolyzer bubbles")
def electrolyzer(c: Ctx):
    n, t = c.n, c.t
    r = c.rng("bubbles")
    rate = 34.0 * np.clip(1.0 + 0.4 * smooth_mod(n, 0.3, 1.5, c.rng("rate")), 0.1, None)
    out = np.zeros(n)
    for p in poisson_events(n, rate, r):
        radius = float(np.clip(r.lognormal(np.log(2.4e-3), 0.45), 0.7e-3, 6e-3))
        add_into(c, out, int(p), bubble(3.26 / radius, (radius / 2.4e-3) ** 0.6 * r.lognormal(0.0, 0.35)))
    fizz = shaped_noise(n, [(5, -100), (2000, -30), (4500, -6), (7000, 0), (10000, -6), (15000, -30), (22050, -70)],
                        c.rng("fizz")) * lognorm_env(smooth_mod(n, 8.0, 40.0, c.rng("fizzam")), 0.4)
    f0 = c.qf(100.0)
    hum = (np.sin(TAU * f0 * t) + 0.5 * np.sin(TAU * 2 * f0 * t + 0.7) + 0.25 * np.sin(TAU * 3 * f0 * t + 1.3)
           + 0.12 * np.sin(TAU * 0.5 * f0 * t))
    return unit_peak(out) + db(-32) * fizz + db(-24) * hum


@sound("machine.solar_deploy", 1.5, variants=2, attenuation=24, peak_db=-5.0, lufs=-20.0, fade_out=0.08,
       subtitle="Solar panel unfolds")
def solar_deploy(c: Ctx):
    n, t = c.n, c.t
    t_on, t_off = 0.04, [1.18, 1.10][c.variant]
    f = 215.0 + 45.0 * ramp(t, t_on, 0.15) - 40.0 * ramp(t, t_off - 0.15, 0.15)
    f = f * (1.0 + 0.02 * smooth_mod(n, 3.0, 10.0, c.rng("load")))
    ph = phase_of(f)
    amp = ramp(t, t_on, 0.04) * (1.0 - ramp(t, t_off, 0.06))
    motor = (unit_peak(bl_saw(ph, f)) + 0.2 * np.sin(5.2 * ph)) * amp
    motor = lp(c, F(c, motor, peq(1300, 1.5, 6.0)), 5000)
    r = c.rng("ratchet")
    k = 14
    times = 0.15 + (t_off - 0.25) * smooth01(np.linspace(0.0, 1.0, k)) + r.uniform(-0.01, 0.01, k)
    clicks = np.zeros(n)
    for tk in times:
        clicks += hit(c, n, tk, metal_modes(r, r.uniform(2000, 3000), 6000.0, 4, tau0=0.008), strike(r, 0.4, 9000), 0.06)
        clicks += 0.3 * thud(n, tk, 320.0, 280.0, tau=0.01, glide=0.005, attack=0.0005)
    rustle = bp(c, white(c, "rustle"), 1500, 6000) * lognorm_env(smooth_mod(n, 2.0, 8.0, c.rng("rs")), 0.5) * amp
    latch = hit(c, n, t_off + 0.06, metal_modes(r, 800.0, 2500.0, 8, tau0=0.06), strike(r, 1.0, 5000), 0.3)
    latch += 0.7 * thud(n, t_off + 0.06, 160.0, 130.0, tau=0.04, glide=0.01, attack=0.001)
    return (0.6 * unit_peak(motor) + 0.45 * unit_peak(clicks) + 0.12 * unit_peak(rustle) * 2.0
            + 0.9 * unit_peak(latch))


@sound("machine.habitat_pressurize", 3.0, attenuation=24, peak_db=-4.0, lufs=-18.0, fade_out=0.15,
       subtitle="Habitat pressurizes")
def habitat_pressurize(c: Ctx):
    n, t = c.n, c.t
    t_open, t_close = 0.06, 2.55

    def mag(tf, freqs):
        u = np.clip((tf - t_open) / (t_close - t_open), 0.0, 1.0)[:, None]
        lo = 2600.0 * (1.0 - u) + 380.0 * u            # denser air carries the lows -> fuller hiss
        hi = 6500.0 + 4000.0 * u
        f = np.maximum(freqs[None, :], 1.0)
        shape = 1.0 / np.sqrt(1.0 + (lo / f) ** 4) / np.sqrt(1.0 + (f / hi) ** 4)
        return shape + 0.5 * u * gauss_band(f, 1900.0, 0.06)
    hiss = stft_filter(c, white(c, "hiss"), mag)
    u = np.clip((t - t_open) / (t_close - t_open), 0.0, 1.0)
    level = (0.06 + 0.94 * smooth01(u) ** 1.3) * ramp(t, t_open, 0.02) * (1.0 - ramp(t, t_close, 0.06))
    level *= lognorm_env(smooth_mod(n, 2.0, 7.0, c.rng("flut")), 0.08)
    r = c.rng("valve")
    v_open = hit(c, n, t_open - 0.01, metal_modes(r, 1200.0, 4000.0, 5, tau0=0.02), strike(r, 0.5, 8000), 0.1)
    v_close = hit(c, n, t_close + 0.02, metal_modes(r, 450.0, 2500.0, 7, tau0=0.06), strike(r, 1.2, 4000), 0.3)
    v_close += 0.8 * thud(n, t_close + 0.02, 120.0, 90.0, tau=0.04, glide=0.01, attack=0.001)
    return unit(hiss) * level * 0.3 + 0.1 * unit_peak(v_open) + 0.5 * unit_peak(v_close)


@sound("machine.habitat_leak", 3.0, loop=True, attenuation=24, peak_db=-7.0, lufs=-24.0, subtitle="Air hisses out")
def habitat_leak(c: Ctx):
    n = c.n
    base = shaped_noise(n, [(5, -100), (1000, -30), (2500, -8), (5000, 0), (8000, -3), (12000, -14), (18000, -40),
                            (22050, -60)], c.rng("base"))
    wav = 1.0 + 0.012 * smooth_mod(n, 0.33, 2.0, c.rng("waver"))
    fw = c.qf(3150.0)
    ph = phase_of(fw * wav)
    amp = lognorm_env(smooth_mod(n, 0.5, 6.0, c.rng("amp")), 0.3)
    whistle = (np.sin(ph) + 0.25 * np.sin(2 * ph + 0.5)) * amp
    hop = 512
    wavf = wav[::hop]

    def narrow(tf, freqs):
        f = freqs[None, :]
        return gauss_band(f, fw * wavf[:, None], 0.03) + 0.5 * gauss_band(f, 2 * fw * wavf[:, None], 0.025)
    nb = stft_filter(c, white(c, "nb"), narrow, nfft=4096, hop=hop)
    flutter = 1.0 + 0.06 * smooth_mod(n, 6.0, 15.0, c.rng("flutter"))
    return base * flutter + db(-9) * unit(whistle) + db(-7) * unit(nb) * amp


@sound("machine.airlock", 2.5, variants=2, attenuation=24, peak_db=-3.0, lufs=-17.0, fade_out=0.1,
       subtitle="Airlock cycles")
def airlock(c: Ctx):
    n, t = c.n, c.t
    v = c.variant
    hiss_pts = [(5, -100), (500, -26), (1000, -8), (2500, 0), (5000, -2), (8000, -8), (12000, -24), (22050, -70)]
    h1 = shaped_noise(n, hiss_pts, c.rng("h1"))
    h1_env = ramp(t, 0.02, 0.012) * np.exp(-np.maximum(t - 0.05, 0) / (0.42 + 0.06 * v)) * (1.0 - ramp(t, 0.82, 0.06))

    def drop(tf, freqs):                                # pressure falls -> hiss darkens a little
        u = np.clip(tf / 0.85, 0.0, 1.0)[:, None]
        return 1.0 / np.sqrt(1.0 + (freqs[None, :] / (12000.0 - 7000.0 * u)) ** 4)
    h1 = stft_filter(c, h1, drop)
    fs = 150.0 + 30.0 * ramp(t, 0.86, 0.05)
    servo = lp(c, bl_saw(phase_of(fs), fs), 3000) * ramp(t, 0.86, 0.02) * (1.0 - ramp(t, 1.0, 0.03))
    t_cl = 1.05
    r = c.rng("clunk")
    clunk = hit(c, n, t_cl, metal_modes(r, 190.0 + 15 * v, 2600.0, 40, tau0=0.25, gap=(0.04, 0.12)),
                strike(r, 2.5, 3500), 1.0)
    clunk = (unit_peak(clunk) + 0.9 * unit_peak(thud(n, t_cl, 75.0, 58.0, tau=0.08, glide=0.02, attack=0.0015))
             + 0.4 * unit_peak(bp(c, white(c, "crash"), 300, 6000) * decay_env(t, t_cl, 0.03, attack=0.0008)))
    h2 = shaped_noise(n, hiss_pts, c.rng("h2"))
    h2_env = ramp(t, 1.25, 0.15) * env_pts(t, [(1.25, 0.3), (2.0, 1.0), (2.3, 1.0)]) * (1.0 - ramp(t, 2.3, 0.04))
    click = hit(c, n, 2.33, metal_modes(r, 1500.0, 4500.0, 5, tau0=0.015), strike(r, 0.5, 8000), 0.08)
    mix = (0.32 * unit(h1) * h1_env + 0.08 * unit_peak(servo) + unit_peak(clunk) + 0.22 * unit(h2) * h2_env
           + 0.2 * unit_peak(click))
    ir = reverb_ir(c.rng("ir"), rt60=0.4, hf_damp=0.8, build=0.003, early=[(0.004, 0.5), (0.009, 0.35)])
    mix = reverb(c, mix, ir, -12.0)
    return punch(mix, 1.0, 0.6)


# =============================================================================================================
# SUIT
# =============================================================================================================

@sound("suit.breathing", 4.5, loop=True, attenuation=8, peak_db=-12.0, max_rotate=0.2, subtitle="Breathing")
def breathing(c: Ctx):
    n, t = c.n, c.t
    T = n / SR
    t_in, d_in = 0.25, 1.6                              # loop starts in the pause before an inhale
    t_ex, d_ex = 2.15, 2.0

    def mag(tf, freqs):
        f = np.maximum(freqs[None, :], 1.0)
        u_in = np.clip((tf - t_in) / d_in, 0.0, 1.0)[:, None]
        u_ex = np.clip((tf - t_ex) / d_ex, 0.0, 1.0)[:, None]
        is_in = (tf < (t_in + d_in + t_ex) / 2.0)[:, None]
        # inhale: formants glide up, more friction hiss; exhale: warmer, darker, formants glide down
        f1 = np.where(is_in, 520.0 + 140.0 * u_in, 650.0 - 170.0 * u_ex)
        f2 = np.where(is_in, 1350.0 + 350.0 * u_in, 1500.0 - 350.0 * u_ex)
        f3 = np.where(is_in, 2600.0, 2450.0)
        top = np.where(is_in, 5200.0, 2800.0)
        base = 1.0 / np.sqrt(1.0 + (180.0 / f) ** 4) / np.sqrt(1.0 + (f / top) ** 8)
        form = 0.3 + gauss_band(f, f1, 0.55) + 0.8 * gauss_band(f, f2, 0.45) + 0.45 * gauss_band(f, f3, 0.4)
        fric = np.where(is_in, 0.45, 0.08) * gauss_band(f, 3600.0, 1.0)
        return base * form + fric / np.sqrt(1.0 + (f / 7000.0) ** 8)
    noise = stft_filter(c, white(c, "breath"), mag)
    u1 = np.clip((t - t_in) / d_in, 0.0, 1.0)
    inhale = np.where(u1 < 0.6, smooth01(u1 / 0.6) ** 1.3, smooth01((1.0 - u1) / 0.4))
    inhale = inhale * ((t >= t_in) & (t < t_in + d_in))
    u2 = np.clip((t - t_ex) / d_ex, 0.0, 1.0)
    exhale = np.where(u2 < 0.14, smooth01(u2 / 0.14), np.exp(-(u2 - 0.14) / 0.3) * smooth01((1.0 - u2) / 0.25))
    exhale = exhale * ((t >= t_ex) & (t < t_ex + d_ex))
    flow = lognorm_env(smooth_mod(n, 3.0 / T, 9.0, c.rng("flow")), 0.12)
    breath = noise * (0.8 * inhale + 1.0 * exhale) * flow
    boxy = convolve(c, breath, helmet_ir(c.rng("helmet")))
    boxy = F(c, boxy, peq(380.0, 1.0, 3.0))
    fan = shaped_noise(n, [(5, -90), (200, -20), (500, 0), (1500, -4), (4000, -20), (22050, -80)], c.rng("fan"))
    return unit_peak(boxy) + db(-50) * fan


@sound("suit.low_oxygen", 0.8, attenuation=8, peak_db=-6.0, lufs=-19.0, fade_out=0.02, subtitle="Low oxygen warning")
def low_oxygen(c: Ctx):
    t = c.t
    ph = TAU * 2093.0 * t
    tone = np.sin(ph) + 0.2 * np.sin(3 * ph) + 0.06 * np.sin(5 * ph)
    gate = sum(ramp(t, t0, 0.003) * (1.0 - ramp(t, t0 + 0.13, 0.01)) for t0 in (0.0, 0.22))
    y = bp(c, tone * gate, 450, 6000, 2)               # small helmet speaker
    return convolve(c, y, helmet_ir(c.rng("helmet")))


@sound("suit.helmet_seal", 0.7, variants=2, attenuation=8, peak_db=-2.0, lufs=-21.0, fade_out=0.06,
       subtitle="Helmet seals")
def helmet_seal(c: Ctx):
    n, t = c.n, c.t
    r = c.rng("latch")
    t1, t2 = 0.02, 0.045 + 0.008 * c.variant
    clicks = np.zeros(n)
    for tk, g in ((t1, 0.8), (t2, 1.0)):
        clicks += g * hit(c, n, tk, metal_modes(r, r.uniform(2800, 3600), 7000.0, 5, tau0=0.015), strike(r, 0.4, 9000), 0.08)
        clicks += 0.5 * g * thud(n, tk, r.uniform(150, 190), 120.0, tau=0.015, glide=0.006, attack=0.0008)
    hiss = shaped_noise(n, [(5, -100), (800, -24), (1500, -6), (3000, 0), (6000, -2), (9000, -8), (14000, -30),
                            (22050, -70)], c.rng("hiss"))

    def settle(tf, freqs):
        u = np.clip((tf - 0.06) / 0.5, 0.0, 1.0)[:, None]
        return 1.0 / np.sqrt(1.0 + (freqs[None, :] / (8000.0 - 4000.0 * u)) ** 6)
    hiss = stft_filter(c, hiss, settle) * decay_env(t, 0.06, 0.18 + 0.04 * c.variant, attack=0.015)
    y = unit_peak(clicks) + 0.35 * unit_peak(hiss)
    return convolve(c, y, helmet_ir(c.rng("helmet")))


@sound("suit.dosimeter_click", 0.05, variants=4, attenuation=8, peak_db=-6.0, lufs=-24.0, fade_in=0.0003,
       fade_out=0.004,
       subtitle="Dosimeter clicks")
def dosimeter_click(c: Ctx):
    n = c.n
    r = c.rng("click")
    imp = np.zeros(n)
    p0 = nsamp(0.006)                                  # lead-in: keeps Vorbis pre-echo off the first sample
    w = int(r.integers(1, 4))
    pol = float(r.choice([-1.0, 1.0]))
    imp[p0:p0 + w] = pol
    imp[p0 + w + nsamp(0.0004)] = -0.4 * pol           # speaker cone rebound
    fr = r.uniform(2400, 4600)
    tau = r.uniform(0.0012, 0.0028)
    ln = nsamp(0.012)
    tt = np.arange(ln) / SR
    ring = signal.fftconvolve(imp, np.sin(TAU * fr * tt) * np.exp(-tt / tau))[:n]
    broad = signal.sosfilt(butter("bandpass", [600, 12000], 2), imp)
    return 0.7 * unit_peak(broad) + unit_peak(ring)


# =============================================================================================================
# UI
# =============================================================================================================

@sound("ui.telemetry_event", 0.4, attenuation=16, peak_db=-8.0, lufs=-26.0, fade_out=0.05, subtitle="Telemetry blips")
def telemetry_event(c: Ctx):
    t = c.t - 0.006                                     # lead-in: keeps Vorbis pre-echo off the first sample
    f = 1568.0 * (1.0 - 0.03 * (1.0 - np.exp(-np.maximum(t, 0.0) / 0.03)))
    ph = phase_of(f)
    y = (np.sin(ph) + db(-18) * np.sin(2 * ph)) * smooth01(t / 0.004) * np.exp(-np.maximum(t, 0.0) / 0.075)
    ir = reverb_ir(c.rng("ir"), rt60=0.25, hf_damp=0.6, build=0.002)
    return reverb(c, y, ir, -14.0)


@sound("ui.interlude_whoosh", 3.0, attenuation=16, peak_db=-3.0, lufs=-16.0, fade_out=0.25, subtitle="Whoosh")
def interlude_whoosh(c: Ctx):
    n, t = c.n, c.t
    tp = 2.25

    def mag(tf, freqs):
        u = np.clip(tf / tp, 0.0, 1.0)
        fc = 180.0 * (5200.0 / 180.0) ** (u ** 1.6)
        fc = np.where(tf > tp, 5200.0 * (1.0 + 0.15 * (tf - tp)), fc)
        bw = 0.8 + 0.6 * u
        return gauss_band(freqs[None, :], fc[:, None], bw[:, None])
    sweep = stft_filter(c, white(c, "sweep"), mag)
    env = smooth01(t / tp) ** 2.2 * (1.0 - ramp(t, tp, 0.65))
    fsub = 38.0 + 17.0 * smooth01(t / tp)
    ph = phase_of(fsub)
    sub = (np.sin(ph) + 0.3 * np.sin(2 * ph)) * smooth01(t / tp) ** 2.5 * (1.0 - ramp(t, tp, 0.6))
    shimmer = hp(c, white(c, "shimmer"), 6000) * env ** 3
    dry = unit(sweep) * env + 0.9 * sub + db(-20) * shimmer
    ir = reverb_ir(c.rng("ir"), rt60=2.0, hf_damp=0.5, build=0.02, predelay=0.01)
    return reverb(c, dry, ir, -10.0)


# =============================================================================================================
# Encoding, analysis, outputs
# =============================================================================================================

_BITREV = bytes(int(f"{i:08b}"[::-1], 2) for i in range(256))


def ogg_crc(data: bytes) -> int:
    """Ogg page CRC (poly 0x04C11DB7, MSB-first, init 0) via zlib's reflected CRC-32 on bit-reversed bytes."""
    raw = zlib.crc32(data.translate(_BITREV), 0xFFFFFFFF) ^ 0xFFFFFFFF
    return int(f"{raw:032b}"[::-1], 2)


def set_ogg_serial(blob: bytes, serial: int) -> bytes:
    """Replace the (random) logical-stream serial number in every Ogg page and recompute the page CRCs."""
    out = bytearray(blob)
    pos = 0
    while pos < len(out):
        if out[pos:pos + 4] != b"OggS":
            raise ValueError(f"bad Ogg page at byte {pos}")
        nseg = out[pos + 26]
        plen = 27 + nseg + sum(out[pos + 27:pos + 27 + nseg])
        out[pos + 14:pos + 18] = serial.to_bytes(4, "little")
        out[pos + 22:pos + 26] = b"\0\0\0\0"
        out[pos + 22:pos + 26] = ogg_crc(bytes(out[pos:pos + plen])).to_bytes(4, "little")
        pos += plen
    return bytes(out)


def encode_ogg(x: np.ndarray, quality: float, serial: int, title: str) -> bytes:
    buf = io.BytesIO()
    with sf.SoundFile(buf, "w", SR, 1, format="OGG", subtype="VORBIS", compression_level=1.0 - quality) as f:
        f.title = title
        f.comment = "Synthesized by tools/sounds/generate_sounds.py (Red Planet: Starship to Mars)"
        f.write(x.astype(np.float32))
    return set_ogg_serial(buf.getvalue(), serial)


def decode_ogg(data: bytes) -> np.ndarray:
    y, sr = sf.read(io.BytesIO(data), dtype="float64", always_2d=False)
    assert sr == SR
    return y


def k_weighting() -> np.ndarray:
    """ITU-R BS.1770 K-weighting (shelf + RLB high-pass), coefficients derived for SR."""
    f0, g, q = 1681.974450955533, 3.999843853973347, 0.7071752369554196
    k = np.tan(np.pi * f0 / SR)
    vh = 10.0 ** (g / 20.0)
    vb = vh ** 0.4996667741545416
    a0 = 1.0 + k / q + k * k
    s1 = [(vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
          1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0]
    f0, q = 38.13547087602444, 0.5003270373238773
    k = np.tan(np.pi * f0 / SR)
    a0 = 1.0 + k / q + k * k
    s2 = [1.0, -2.0, 1.0, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0]
    return np.array([s1, s2])


def loudness(x: np.ndarray, loop: bool) -> tuple[float, float]:
    """(integrated LUFS with BS.1770 gating, maximum momentary LUFS over 400 ms)."""
    if loop:
        y = signal.sosfilt(k_weighting(), np.concatenate([x, x, x]))[2 * len(x):]
        y = np.concatenate([y, y[:nsamp(0.4)]])
    else:
        y = signal.sosfilt(k_weighting(), x)
    blk, step = nsamp(0.4), nsamp(0.1)
    if len(y) < blk:
        v = -0.691 + 10.0 * np.log10(np.mean(y ** 2) + 1e-20)
        return v, v
    c = np.concatenate([[0.0], np.cumsum(y ** 2)])
    starts = np.arange(0, len(y) - blk + 1, step)
    ms = (c[starts + blk] - c[starts]) / blk
    lk = -0.691 + 10.0 * np.log10(ms + 1e-20)
    g1 = ms[lk > -70.0]
    if not len(g1):
        return -np.inf, float(lk.max())
    rel = -0.691 + 10.0 * np.log10(g1.mean()) - 10.0
    g2 = ms[(lk > -70.0) & (lk > rel)]
    return -0.691 + 10.0 * np.log10(g2.mean()), float(lk.max())


def seam_metrics(y: np.ndarray) -> dict:
    """Loop-seam diagnostics on the decoded audio (what OpenAL actually loops).

    jump:  |y[0] - y[-1]| over the 99th percentile of |first difference| (<= ~1: no visible step);
    click: absolute level (dBFS) of the >2 kHz energy that the seam adds in a 2 ms window, above the median
           of the surrounding 2 ms windows; floor: that median (dBFS);
    step:  level change (dB) between the last and first 50 ms, and its percentile among the same measurement
           at every 50 ms boundary of the (circular) loop; the seam is one of them, so ~p50 is typical and a
           real discontinuity would sit alone at p100.
    """
    n = len(y)
    d = np.abs(np.diff(y))
    jump = abs(y[0] - y[-1]) / max(np.percentile(d, 99), 1e-12)
    m = min(8192, n // 4)
    h = signal.sosfiltfilt(butter("highpass", 2000, 4), np.concatenate([y[-3 * m:], y[:3 * m]]))[2 * m:4 * m]
    w = nsamp(0.002)
    e = np.array([rms(h[i:i + w]) for i in range(0, len(h) - w, w // 2)])
    centre = rms(h[m - w // 2:m + w // 2])
    med = float(np.median(e))
    click = 20.0 * np.log10(np.sqrt(max(centre ** 2 - med ** 2, 0.0)) + 1e-12)
    q = nsamp(0.05)
    z = np.concatenate([y, y])
    bounds = np.arange(n, 2 * n, q)                      # every 50 ms boundary of the loop, the seam first
    steps = np.array([abs(to_db(rms(z[b:b + q])) - to_db(rms(z[b - q:b]))) for b in bounds])
    step = float(steps[0])
    pct = float(100.0 * np.mean(steps <= step + 1e-9))
    return dict(jump=float(jump), click=float(click), floor=float(to_db(med)), step=step, pct=pct)


def encode_checked(x: np.ndarray, quality: float, serial: int, title: str):
    """Encode and decode; if Vorbis overshoot would push the decoded peak above -0.3 dBFS (the game clamps
    samples to 16 bits), pull the gain down and encode again. Overshoot below that is harmless and kept."""
    limit = float(db(-0.3))
    for _ in range(8):
        data = encode_ogg(x, quality, serial, title)
        y = decode_ogg(data)
        if peak(y) <= limit:
            return x, data, y
        x = x * (0.99 * limit / peak(y))
    return x, data, y


def render_variant(spec: SoundSpec, variant: int):
    n = loop_samples(spec.seconds) if spec.loop else nsamp(spec.seconds)
    c = Ctx(spec.event, variant, n, spec.loop)
    x = np.asarray(spec.fn(c), dtype=float)
    if x.shape != (n,):
        raise ValueError(f"{spec.event}: expected {n} samples, got {x.shape}")
    if not np.all(np.isfinite(x)) or peak(x) <= 0:
        raise ValueError(f"{spec.event}: produced invalid audio")
    if spec.loop:
        x = circ_apply(x, butter("highpass", 12.0, 2))            # removes DC exactly, keeps periodicity
    else:
        x = signal.sosfilt(butter("highpass", 12.0, 2), x)
        x = fade_edges(x, spec.fade_in, spec.fade_out)
    x *= float(db(spec.peak_db)) / peak(x)
    if spec.lufs is not None:                                     # loudness target, peak_db is the ceiling
        li, lm = loudness(x, spec.loop)
        x *= min(float(db(spec.lufs - (li if spec.loop else lm))), 1.0)
    name = spec.file_names()[variant]
    serial = _crc(f"{MOD_ID}:{name}")
    title = f"{MOD_ID}:{name}"
    x, data, y = encode_checked(x, spec.quality, serial, title)
    if spec.loop:
        # Vorbis codes the first and last block of a stream a little less accurately (it sees an onset from and
        # a cut to silence), which can leave a faint click where the loop wraps. Any circular rotation of a
        # periodic signal is an equally valid loop, so try a few and keep the cleanest decoded seam: first by
        # click level (anything under -90 dBFS counts as silent), then by the smallest level step, which also
        # avoids starting the loop on a loud moment.
        def score(yy):
            m = seam_metrics(yy)
            return (round(max(m["click"], -90.0) / 3.0), m["pct"])
        best = (score(y), x, data, y)
        step = max(1, int(round(nsamp(spec.max_rotate) / 15)))
        for k in range(1, 16):
            xr, dr, yr = encode_checked(np.roll(x, -step * k), spec.quality, serial, title)
            sc = score(yr)
            if sc < best[0]:
                best = (sc, xr, dr, yr)
        _, x, data, y = best
    if len(y) != n:
        raise ValueError(f"{name}: decoded length {len(y)} != {n}")
    return name, x, y, data


def preview(path: Path, y: np.ndarray, spec: SoundSpec, name: str, stats: dict) -> None:
    """Spectrogram (log frequency, dual resolution) + waveform + long-term spectrum (+ seam zoom for loops)."""
    from PIL import Image, ImageDraw, ImageFont
    try:
        font = ImageFont.load_default(size=13)
        small = ImageFont.load_default(size=11)
    except TypeError:
        font = small = ImageFont.load_default()
    z = np.concatenate([y, y]) if spec.loop else y
    W, L, R = 1500, 56, 190
    PW = W - L - R
    top, wave_h, spec_h = 40, 130, 380
    inset_h = 130 if spec.loop else 0
    H = top + wave_h + 12 + spec_h + 34 + inset_h
    img = Image.new("RGB", (W, H), (14, 14, 20))
    d = ImageDraw.Draw(img)
    loop_txt = "LOOP x2 (seam at centre)" if spec.loop else "one-shot"
    d.text((L, 8), f"{spec.event}  ->  {MOD_ID}:{name}.ogg   [{loop_txt}{', stream' if spec.stream else ''}]",
           fill=(235, 235, 235), font=font)
    d.text((L, 23), "dur {dur:.3f}s   peak {peak:.1f} dBFS   rms {rms:.1f} dBFS   LUFS-I {lufs:.1f}   M-max {mmax:.1f}"
           "   {kb:.1f} KB{seam}".format(**stats), fill=(170, 200, 255), font=small)
    # waveform (min/max per column)
    n = len(z)
    edges = np.linspace(0, n, PW + 1).astype(int)
    mid = top + wave_h // 2
    d.line([(L, mid), (L + PW, mid)], fill=(60, 60, 70))
    for i in range(PW):
        seg = z[edges[i]:max(edges[i + 1], edges[i] + 1)]
        y0, y1 = float(seg.max()), float(seg.min())
        r_ = rms(seg)
        d.line([(L + i, mid - y0 * wave_h / 2), (L + i, mid - y1 * wave_h / 2)], fill=(90, 170, 255))
        d.line([(L + i, mid - r_ * wave_h / 2), (L + i, mid + r_ * wave_h / 2)], fill=(170, 220, 255))
    for v in (1.0, -1.0):
        d.line([(L, mid - v * wave_h / 2), (L + PW, mid - v * wave_h / 2)], fill=(90, 50, 50))
    # spectrogram
    st = top + wave_h + 12
    fmin, fmax = 20.0, 20000.0
    rows_f = fmin * (fmax / fmin) ** (1.0 - (np.arange(spec_h) + 0.5) / spec_h)
    centers = ((edges[:-1] + edges[1:]) // 2)

    def stft_mag(nfft):
        win = np.hanning(nfft)
        idx = centers[:, None] - nfft // 2 + np.arange(nfft)[None, :]
        valid = (idx >= 0) & (idx < n)
        frames = np.where(valid, z[np.clip(idx, 0, n - 1)], 0.0) * win
        return np.abs(np.fft.rfft(frames, axis=1)) / (win.sum() / 2), np.fft.rfftfreq(nfft, 1.0 / SR)
    m_lo, f_lo = stft_mag(8192)
    m_hi, f_hi = stft_mag(1024)
    img_db = np.empty((spec_h, PW))
    for j, fr in enumerate(rows_f):
        if fr < 500.0:
            img_db[j] = to_db(m_lo[:, int(round(fr * 8192 / SR))])
        else:
            img_db[j] = to_db(m_hi[:, int(round(fr * 1024 / SR))])
    ref = np.percentile(img_db, 99.9)
    u = np.clip((img_db - (ref - 90.0)) / 90.0, 0.0, 1.0)
    anchors = np.array([[0, 0, 4], [28, 16, 68], [79, 18, 123], [129, 37, 129], [181, 54, 122], [229, 80, 100],
                        [251, 135, 97], [254, 194, 135], [252, 253, 191]], dtype=float)
    pos = u * (len(anchors) - 1)
    i0 = np.clip(np.floor(pos).astype(int), 0, len(anchors) - 2)
    fr_ = (pos - i0)[..., None]
    rgb = anchors[i0] * (1 - fr_) + anchors[i0 + 1] * fr_
    img.paste(Image.fromarray(rgb.astype(np.uint8), "RGB"), (L, st))
    for fl in (20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000):
        yy = st + spec_h * (1.0 - np.log(fl / fmin) / np.log(fmax / fmin))
        d.line([(L - 4, yy), (L, yy)], fill=(200, 200, 200))
        d.text((4, yy - 7), f"{fl // 1000}k" if fl >= 1000 else str(fl), fill=(200, 200, 200), font=small)
        d.line([(L + PW + 6, yy), (L + PW + 10, yy)], fill=(120, 120, 120))
    # time axis
    dur = n / SR
    step = 0.25 if dur <= 2.5 else (0.5 if dur <= 6 else 1.0)
    if dur < 0.3:
        step = 0.01
    tt = 0.0
    while tt <= dur + 1e-9:
        x_ = L + PW * tt / dur
        d.line([(x_, st + spec_h), (x_, st + spec_h + 5)], fill=(200, 200, 200))
        d.text((x_ - 8, st + spec_h + 7), f"{tt:g}", fill=(200, 200, 200), font=small)
        tt += step
    if spec.loop:
        xs = L + PW // 2
        for yy in range(top, st + spec_h, 8):
            d.line([(xs, yy), (xs, yy + 4)], fill=(120, 255, 120))
    # long-term average spectrum on the same log axis (dB, 70 dB range)
    nf = 8192
    if len(z) >= nf:
        _, pxx = signal.welch(z, SR, nperseg=nf)
        fw = np.fft.rfftfreq(nf, 1.0 / SR)
        ltas = 10 * np.log10(np.interp(rows_f, fw, pxx) + 1e-30)
        ltas -= ltas.max()
        xs0 = L + PW + 14
        pts = [(xs0 + (R - 24) * max(0.0, 1.0 + v / 70.0), st + j) for j, v in enumerate(ltas)]
        d.line(pts, fill=(255, 210, 120))
        d.text((xs0, st - 12), "LTAS (70 dB)", fill=(200, 200, 200), font=small)
    if spec.loop:
        iy = st + spec_h + 30
        k = nsamp(0.005)
        seg = np.concatenate([y[-k:], y[:k]])
        sc = max(peak(seg), 1e-6)
        pts = [(L + PW * i / (2 * k - 1), iy + inset_h / 2 - seg[i] / sc * (inset_h / 2 - 6)) for i in range(2 * k)]
        d.line([(L, iy + inset_h / 2), (L + PW, iy + inset_h / 2)], fill=(60, 60, 70))
        d.line(pts, fill=(120, 255, 160))
        d.line([(L + PW / 2, iy), (L + PW / 2, iy + inset_h)], fill=(255, 120, 120))
        d.text((L + 4, iy + 2), "seam zoom: last 5 ms | first 5 ms (decoded Vorbis)", fill=(200, 200, 200), font=small)
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)


def write_json_files() -> None:
    sounds = {}
    subs = {}
    for spec in REGISTRY:
        entries = []
        for name in spec.file_names():
            e = {"name": f"{MOD_ID}:{name}", "stream": spec.stream, "attenuation_distance": spec.attenuation}
            if spec.preload:
                e["preload"] = True
            entries.append(e)
        key = f"subtitles.{MOD_ID}.{spec.event}"
        sounds[spec.event] = {"sounds": entries, "subtitle": key}
        subs[key] = spec.subtitle
    SOUNDS_JSON.parent.mkdir(parents=True, exist_ok=True)
    SOUNDS_JSON.write_text(json.dumps(sounds, indent=2) + "\n", encoding="utf-8")
    SUBTITLES_OUT.write_text(json.dumps(subs, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--only", action="append", default=[], help="render only events starting with this prefix")
    ap.add_argument("--previews", type=Path, help="write spectrogram/waveform PNGs into this directory")
    args = ap.parse_args(argv)

    names = [s.event for s in REGISTRY]
    if len(set(names)) != len(names):
        raise SystemExit("duplicate event names in REGISTRY")
    write_json_files()
    todo = [s for s in REGISTRY if not args.only or any(s.event.startswith(p) for p in args.only)]
    rows = []
    produced = set()
    for spec in todo:
        for v in range(spec.variants):
            name, x, y, data = render_variant(spec, v)
            out = SOUND_ROOT / f"{name}.ogg"
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_bytes(data)
            produced.add(out)
            li, lm = loudness(y, spec.loop)
            stats = dict(dur=len(y) / SR, peak=to_db(peak(y)), rms=to_db(rms(y)), lufs=li, mmax=lm,
                         kb=len(data) / 1024.0, seam="")
            seam = None
            if spec.loop:
                seam = seam_metrics(y)
                stats["seam"] = ("   seam: jump {jump:.2f}x  click {click:.0f} dBFS (floor {floor:.0f})  "
                                 "step {step:.1f} dB (p{pct:.0f})").format(**seam)
            if args.previews:
                preview(args.previews / f"{name.replace('/', '__')}.png", y, spec, name, stats)
            rows.append((spec, name, stats, seam, abs(float(np.mean(y)))))
            print(f"  rendered {name:34s} {stats['dur']:6.2f}s", file=sys.stderr)
    if not args.only:                                   # remove stale files from earlier layouts
        for f in SOUND_ROOT.rglob("*.ogg"):
            if f not in produced:
                f.unlink()
                print(f"  removed stale {f.relative_to(REPO)}", file=sys.stderr)

    print()
    hdr = f"{'event':28s} {'file':32s} {'dur s':>6s} {'peak':>6s} {'rms':>6s} {'LUFS':>6s} {'Mmax':>6s} {'KB':>6s}  loop seam"
    print(hdr)
    print("-" * len(hdr))
    for spec, name, s, seam, dc in rows:
        seam_txt = "" if seam is None else "jump {jump:.2f}x click {click:5.0f} dBFS floor {floor:5.0f} step {step:.1f} dB p{pct:.0f}".format(**seam)
        print(f"{spec.event:28s} {name + '.ogg':32s} {s['dur']:6.2f} {s['peak']:6.1f} {s['rms']:6.1f} "
              f"{s['lufs']:6.1f} {s['mmax']:6.1f} {s['kb']:6.1f}  {seam_txt}")
        if dc > 1e-3:
            print(f"    WARNING: DC offset {dc:.2e}")
    total = sum(f.stat().st_size for f in SOUND_ROOT.rglob("*.ogg"))
    nfiles = len(list(SOUND_ROOT.rglob("*.ogg")))
    print(f"\n{nfiles} files, {len(REGISTRY)} events, total {total / 1024 / 1024:.2f} MB "
          f"(budget {SIZE_BUDGET / 1024 / 1024:.0f} MB)")
    print("loops:", ", ".join(s.event for s in REGISTRY if s.loop))
    if total > SIZE_BUDGET:
        print("WARNING: over the size budget", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
