"""The trailer's edit: shots, titles, sound effects and music cues on one timeline (seconds, 30 fps).

Both the score (score.py) and the assembly (assemble.py) read this file, so the music lands on the cuts.

Each shot names a clip filmed by TrailerClientGameTest (build/trailer/shots/<clip>.mp4) and the part of it to use.
Cards are drawn by titles.py. Effects are the mod's own sounds (src/client/resources/assets/redplanet/sounds).
"""
from __future__ import annotations

from dataclasses import dataclass, field

FPS = 30
WIDTH = 1920
HEIGHT = 1080


@dataclass(frozen=True)
class Shot:
    start: float          # timeline position, s
    end: float
    clip: str             # build/trailer/shots/<clip>.mp4, or "card:<name>" for a title card
    clip_in: float = 0.0  # where in the clip this shot starts, s
    speed: float = 1.0    # playback speed (1 = real time)
    fade_in: float = 0.0  # from black, s
    fade_out: float = 0.0  # to black, s
    shake: float = 0.0    # extra camera shake added in the edit (pixels)
    flash: float = 0.0    # white flash at the start, s
    zoom: tuple[float, float] = (1.0, 1.0)    # digital push-in: scale at the start and the end of the shot
    anchor: tuple[float, float] = (0.5, 0.5)  # the point the zoom closes on (fractions of the frame)
    ease_zoom: bool = False  # ease the zoom in and out instead of a constant creep
    frame_y: int = 0      # move the picture down (or up, negative) inside the letterbox, pixels (at most the bar height)
    lift: float = 1.0     # brighten the shadows (a gamma lift) for a dark shot, before the grade


@dataclass(frozen=True)
class Text:
    start: float
    end: float
    lines: tuple[str, ...]
    style: str = "statement"  # statement | caption | band (low, on a dark band) | top (a caption high in the frame)
    fade: float = 0.25


@dataclass(frozen=True)
class Effect:
    at: float
    sound: str            # path under assets/redplanet/sounds without extension, e.g. "rocket/raptor_ignition"
    gain_db: float = 0.0
    length: float | None = None  # cut after this many seconds (with a short fade), None = whole file
    loop_until: float | None = None  # loop the file until this timeline time
    fade_out: float = 0.3
    pitch: float = 1.0    # resampling factor (2 = an octave up and half as long)


@dataclass(frozen=True)
class Cue:
    at: float
    kind: str             # boom | braam | hit | riser | drop | section
    length: float = 0.0
    param: str = ""       # for sections: the section name


@dataclass
class Edit:
    shots: list[Shot] = field(default_factory=list)
    texts: list[Text] = field(default_factory=list)
    effects: list[Effect] = field(default_factory=list)
    cues: list[Cue] = field(default_factory=list)

    @property
    def duration(self) -> float:
        return max(s.end for s in self.shots)


def trailer() -> Edit:
    e = Edit()
    S, T, X, C = e.shots.append, e.texts.append, e.effects.append, e.cues.append

    # ---------------------------------------------------------------- the hook (0-3 s): sunset, liftoff, space, Mars
    S(Shot(0.00, 0.80, "pad_dusk", 0.0, frame_y=70, zoom=(1.0, 1.03), anchor=(0.5, 0.3)))
    S(Shot(0.80, 1.60, "liftoff_low", 5.85, flash=0.08, shake=9, frame_y=-110))
    S(Shot(1.60, 2.35, "staging_side", 0.5, shake=3, frame_y=100))
    S(Shot(2.35, 3.10, "mars_landing", 4.4, shake=4))
    T(Text(1.70, 3.05, ("THIS IS MINECRAFT.",), "statement", 0.12))
    X(Effect(0.00, "rocket/booster_roar", -14.0, length=0.8, fade_out=0.1))
    X(Effect(0.80, "rocket/raptor_ignition", 0.0))
    X(Effect(0.90, "rocket/booster_roar", -2.0, loop_until=1.60, fade_out=0.10))
    X(Effect(1.60, "rocket/hot_staging", -2.0, length=0.75))
    X(Effect(2.35, "rocket/touchdown", 0.0))
    C(Cue(0.00, "boom"))
    C(Cue(0.80, "hit"))
    C(Cue(1.60, "braam", 0.75))
    C(Cue(2.35, "hit"))

    # ---------------------------------------------------------------- title
    S(Shot(3.10, 6.00, "card:title", fade_out=0.4))
    X(Effect(3.10, "ui/interlude_whoosh", -8.0))
    C(Cue(3.10, "section", 2.9, "title"))
    C(Cue(5.40, "riser", 0.6))

    # ---------------------------------------------------------------- act 1: Earth
    S(Shot(6.00, 9.00, "pad_dusk", 1.4, fade_in=0.3, frame_y=90, zoom=(1.0, 1.04), anchor=(0.5, 0.2)))
    T(Text(6.4, 8.8, ("A TRUE-SCALE STARSHIP",), "caption"))
    S(Shot(9.00, 11.40, "mission_control", 0.3, zoom=(1.0, 1.05), anchor=(0.5, 0.35)))
    T(Text(9.3, 11.2, ("PICK ANY LANDING SITE ON MARS",), "band"))
    X(Effect(10.6, "ui/telemetry_event", -6.0))
    S(Shot(11.40, 13.00, "ignition_wide", 2.3, shake=2))
    X(Effect(11.40, "rocket/deluge", -8.0, length=1.6))
    X(Effect(11.70, "rocket/raptor_ignition", -2.0))
    S(Shot(13.00, 14.20, "hook_ignition", 3.0, shake=6))
    S(Shot(14.20, 16.60, "liftoff_sunset", 4.6, shake=3))
    X(Effect(13.00, "rocket/booster_roar", -3.0, loop_until=20.2))
    S(Shot(16.60, 18.00, "hook_liftoff", 6.4, shake=3))
    S(Shot(18.00, 19.00, "ascent_chase", 1.0, shake=3))
    S(Shot(19.00, 20.20, "ascent_track", 2.0, frame_y=-132))
    S(Shot(20.20, 21.40, "hot_staging", 0.6, flash=0.08, shake=3, frame_y=-132))
    X(Effect(20.20, "rocket/hot_staging", 0.0))
    S(Shot(21.40, 23.00, "ship_chase", 0.1))
    X(Effect(21.40, "rocket/vacuum_hum", -6.0, length=1.6))
    C(Cue(6.00, "section", 17.0, "act1"))
    C(Cue(14.20, "hit"))
    C(Cue(20.20, "braam", 1.4))
    C(Cue(22.40, "riser", 0.6))

    # ---------------------------------------------------------------- act 2: to Mars
    S(Shot(23.00, 25.20, "interlude_earth", 0.5, zoom=(1.0, 1.05), anchor=(0.5, 0.7)))
    T(Text(23.3, 25.0, ("REFUEL IN ORBIT. BURN FOR MARS.",), "top"))
    X(Effect(23.00, "ui/interlude_whoosh", -6.0))
    S(Shot(25.20, 27.40, "interlude_mars", 1.6, zoom=(1.0, 1.04)))
    T(Text(25.4, 27.2, ("SIX MONTHS LATER",), "top"))
    S(Shot(27.40, 29.40, "entry_plasma", 1.0, shake=4))
    X(Effect(27.40, "rocket/entry_plasma", -2.0, length=3.2))
    S(Shot(29.40, 30.60, "entry_side", 1.5, zoom=(1.0, 1.06)))
    S(Shot(30.60, 32.40, "mars_landing", 3.5, shake=2))
    X(Effect(30.60, "rocket/ship_roar", -3.0, loop_until=34.6))
    X(Effect(31.20, "rocket/landing_legs", -6.0))
    S(Shot(32.40, 35.00, "mars_landing_low", 3.35, shake=5, zoom=(1.18, 1.26), anchor=(0.64, 0.55)))
    X(Effect(34.60, "rocket/touchdown", 0.0))
    C(Cue(23.00, "section", 11.6, "space"))
    C(Cue(30.60, "riser", 4.0))
    C(Cue(34.60, "hit"))

    # ---------------------------------------------------------------- act 3: Mars
    S(Shot(35.00, 38.00, "mars_gale", 0.4, fade_in=0.2))
    T(Text(35.4, 37.8, ("THE REAL MARS,", "FROM NASA ELEVATION DATA"), "caption"))
    X(Effect(35.00, "mars/wind", -8.0, loop_until=47.5))
    S(Shot(38.00, 40.50, "olympus_mons", 0.6))
    T(Text(38.3, 40.3, ("OLYMPUS MONS",), "caption"))
    S(Shot(40.50, 43.00, "valles_marineris", 0.8))
    T(Text(40.8, 42.8, ("VALLES MARINERIS",), "caption"))
    S(Shot(43.00, 45.00, "dust_devil", 0.5))
    X(Effect(43.00, "mars/dust_devil", -6.0, length=2.0))
    S(Shot(45.00, 47.50, "mars_storm", 0.4))
    X(Effect(45.00, "mars/dust_storm", -6.0, length=2.5))
    S(Shot(47.50, 50.00, "suit_visor", 0.8, frame_y=110))
    T(Text(47.7, 49.8, ("EVERY BREATH COUNTS",), "caption"))
    X(Effect(47.50, "suit/helmet_seal", -6.0))
    X(Effect(47.80, "suit/breathing", -6.0, loop_until=50.0))
    S(Shot(50.00, 52.50, "habitat", 0.5))
    T(Text(50.3, 52.3, ("SEAL IT. PRESSURIZE IT. BREATHE.",), "band"))
    X(Effect(50.00, "machine/airlock", -6.0))
    X(Effect(50.40, "machine/habitat_pressurize", -4.0))
    S(Shot(52.50, 56.50, "caves", 0.3, lift=1.35))
    T(Text(52.9, 56.2, ("SOMETHING LIVES IN THE LAVA TUBES",), "caption"))
    S(Shot(56.50, 59.00, "mars_sunset", 0.3))
    C(Cue(35.00, "section", 12.5, "mars"))
    C(Cue(47.50, "section", 11.5, "build"))

    # ---------------------------------------------------------------- act 4: home
    S(Shot(59.00, 60.40, "mars_liftoff_low", 0.3, shake=4))
    T(Text(59.3, 61.2, ("THEN FLY HOME",), "caption"))
    X(Effect(59.00, "rocket/raptor_ignition", -2.0))
    S(Shot(60.40, 62.60, "mars_liftoff", 6.4, shake=5))
    X(Effect(60.40, "rocket/ship_roar", -3.0, loop_until=62.6))
    S(Shot(62.60, 64.20, "earth_entry", 1.0, shake=4))
    X(Effect(62.60, "rocket/sonic_boom", -2.0))
    X(Effect(62.80, "rocket/entry_plasma", -8.0, length=1.4))
    S(Shot(64.20, 66.00, "home_landing", 2.2, shake=2))
    S(Shot(66.00, 67.50, "home_landing_low", 4.7, shake=3))
    X(Effect(64.20, "rocket/ship_roar", -4.0, loop_until=66.9))
    X(Effect(65.80, "rocket/landing_legs", -6.0))
    X(Effect(66.90, "rocket/touchdown", 0.0))
    C(Cue(59.00, "section", 8.5, "climax"))
    C(Cue(59.00, "braam", 2.0))
    C(Cue(66.90, "hit"))

    # ---------------------------------------------------------------- end
    S(Shot(67.50, 71.50, "card:logo", fade_in=0.0))
    S(Shot(71.50, 78.00, "card:end", fade_out=1.2))
    C(Cue(67.50, "boom"))
    C(Cue(67.50, "section", 10.5, "end"))
    return e
