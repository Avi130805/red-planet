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


@dataclass(frozen=True)
class Text:
    start: float
    end: float
    lines: tuple[str, ...]
    style: str = "statement"  # statement | title | caption | end
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

    # ---------------------------------------------------------------- the hook (0-3 s)
    S(Shot(0.00, 0.80, "hook_ignition", 0.0, flash=0.10, shake=6))
    S(Shot(0.80, 1.95, "hook_liftoff", 0.0, shake=10))
    S(Shot(1.95, 3.10, "hook_catch", 0.0, shake=3))
    T(Text(2.05, 3.05, ("THIS IS MINECRAFT.",), "statement", 0.12))
    X(Effect(0.00, "rocket/raptor_ignition", 0.0))
    X(Effect(0.70, "rocket/booster_roar", -2.0, loop_until=2.05, fade_out=0.25))
    X(Effect(1.95, "rocket/chopsticks", 0.0))
    C(Cue(0.00, "boom"))
    C(Cue(0.80, "braam", 1.2))
    C(Cue(1.95, "hit"))

    # ---------------------------------------------------------------- title
    S(Shot(3.10, 6.00, "card:title", fade_out=0.4))
    C(Cue(3.10, "section", 2.9, "title"))
    C(Cue(5.40, "riser", 0.6))

    # ---------------------------------------------------------------- act 1: Earth
    S(Shot(6.00, 9.00, "pad_dawn", 0.0, fade_in=0.3))
    T(Text(6.4, 8.8, ("A TRUE-SCALE STARSHIP",), "caption"))
    S(Shot(9.00, 11.50, "mission_control", 0.0))
    S(Shot(11.50, 14.50, "liftoff_day", 0.0, shake=4))
    X(Effect(11.50, "rocket/raptor_ignition", -4.0))
    X(Effect(12.10, "rocket/booster_roar", -3.0, loop_until=17.0))
    X(Effect(11.60, "rocket/deluge", -6.0, length=3.0))
    S(Shot(14.50, 16.80, "ascent_track", 0.0))
    S(Shot(16.80, 18.60, "hot_staging", 0.0, flash=0.08))
    X(Effect(16.80, "rocket/hot_staging", 0.0))
    S(Shot(18.60, 21.00, "boostback", 0.0))
    C(Cue(6.00, "section", 15.0, "act1"))
    C(Cue(11.50, "hit"))
    C(Cue(16.80, "braam", 1.6))
    C(Cue(20.40, "riser", 0.6))

    # ---------------------------------------------------------------- act 2: to Mars
    S(Shot(21.00, 24.00, "orbit_earth", 0.0))
    T(Text(21.4, 23.8, ("REFUEL IN ORBIT. BURN FOR MARS.",), "caption"))
    S(Shot(24.00, 27.00, "interlude", 0.0))
    S(Shot(27.00, 30.00, "entry_plasma", 0.0, shake=3))
    X(Effect(27.00, "rocket/entry_plasma", -2.0, length=3.0))
    S(Shot(30.00, 33.00, "belly_flop", 0.0))
    S(Shot(33.00, 36.50, "mars_landing", 0.0, shake=3))
    X(Effect(33.40, "rocket/ship_roar", -3.0, loop_until=35.9))
    X(Effect(35.90, "rocket/touchdown", 0.0))
    C(Cue(21.00, "section", 12.0, "space"))
    C(Cue(33.00, "riser", 2.9))
    C(Cue(35.90, "hit"))

    # ---------------------------------------------------------------- act 3: Mars
    S(Shot(36.50, 39.50, "mars_gale", 0.4, fade_in=0.2))
    T(Text(36.9, 39.3, ("THE REAL MARS,", "FROM NASA ELEVATION DATA"), "caption"))
    X(Effect(36.50, "mars/wind", -8.0, loop_until=48.5))
    S(Shot(39.50, 42.00, "olympus_mons", 0.6))
    S(Shot(42.00, 44.00, "valles_marineris", 0.8))
    S(Shot(44.00, 46.00, "dust_devil", 0.5))
    X(Effect(44.00, "mars/dust_devil", -6.0, length=2.0))
    S(Shot(46.00, 47.50, "phobos_night", 0.8))
    T(Text(46.1, 47.4, ("PHOBOS, ON ITS REAL ORBIT",), "caption", 0.15))
    S(Shot(47.50, 49.00, "mars_storm", 1.0))
    X(Effect(47.50, "mars/dust_storm", -6.0, length=1.5))
    S(Shot(49.00, 51.50, "suit_visor", 0.0))
    X(Effect(49.00, "suit/breathing", -6.0, loop_until=51.5))
    S(Shot(51.50, 54.00, "habitat", 0.0))
    T(Text(51.8, 53.8, ("SEAL IT. PRESSURIZE IT. BREATHE.",), "caption"))
    X(Effect(51.70, "machine/habitat_pressurize", -4.0))
    S(Shot(54.00, 56.50, "isru", 0.0))
    T(Text(54.3, 56.3, ("MAKE PROPELLANT FROM THIN AIR",), "caption"))
    X(Effect(54.00, "machine/moxie", -10.0, loop_until=56.5))
    S(Shot(56.50, 58.50, "caves", 0.0))
    S(Shot(58.50, 61.00, "mars_sunset", 0.3))
    C(Cue(36.50, "section", 12.5, "mars"))
    C(Cue(49.00, "section", 12.0, "build"))

    # ---------------------------------------------------------------- act 4: home
    S(Shot(61.00, 64.00, "mars_liftoff", 0.0, shake=5))
    X(Effect(61.00, "rocket/raptor_ignition", -4.0))
    X(Effect(61.50, "rocket/ship_roar", -3.0, loop_until=64.0))
    S(Shot(64.00, 66.50, "earth_entry", 0.0))
    S(Shot(66.50, 69.50, "home_catch", 0.0, shake=2))
    X(Effect(68.20, "rocket/chopsticks", 0.0))
    C(Cue(61.00, "section", 8.5, "climax"))
    C(Cue(61.00, "braam", 2.0))
    C(Cue(68.20, "hit"))

    # ---------------------------------------------------------------- end
    S(Shot(69.50, 73.50, "card:logo", fade_in=0.0))
    S(Shot(73.50, 80.00, "card:end", fade_out=1.2))
    C(Cue(69.50, "boom"))
    C(Cue(69.50, "section", 10.5, "end"))
    return e
