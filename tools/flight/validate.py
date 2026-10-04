#!/usr/bin/env python3.11
"""Validates flight-profile JSON files against the FlightProfile codec rules and the flight-design rules.

Usage:
    python3.11 tools/flight/validate.py [profile.json ...]

With no arguments it checks the two mod profiles and the gametest hop. Exit status 1 if any profile has an error.

Codec rules mirrored from src/main/java/io/github/avi130805/redplanet/starship/flight/ (field names, types, enums,
defaults, FlightProfile.validate, PhaseDef, TelemetryTrack, CameraShot, AltitudeMapping). Design rules from the
flight brief: event vocabulary, telemetry coverage, attitude continuity, ship/booster agreement before hot staging,
the touchdown and catch end states, shot coverage, in-world distance limits and pacing totals.
"""

from __future__ import annotations

import json
import math
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FILES = [
    ROOT / "src/main/resources/data/redplanet/redplanet/flight_profile/earth_to_mars.json",
    ROOT / "src/main/resources/data/redplanet/redplanet/flight_profile/mars_to_earth.json",
    ROOT / "src/gametest/resources/data/redplanet-gametest/redplanet/flight_profile/test_hop.json",
]

SEGMENTS = ["origin_pad", "ascent", "transfer", "descent", "landed"]
CLOCKS = ["linear", "ease_in", "ease_out", "ease_in_out"]
VEHICLES = ["stack", "ship"]
SHOT_TYPES = ["orbit", "fixed", "chase", "onboard", "cabin"]
TARGETS = ["ship", "booster", "stack"]
ANCHORS = ["pad", "landing_site", "tower"]
PACINGS = ["short", "standard", "long"]
EVENT_TYPES = {
    "propellant_load", "vent", "terminal_count", "deluge", "ignition", "liftoff", "max_q", "meco", "hot_staging",
    "boostback_start", "boostback_end", "booster_landing_burn", "booster_catch", "seco", "orbit", "refilling", "tmi",
    "coast", "approach", "entry_interface", "peak_heating", "plasma_end", "belly_flop", "flip", "landing_burn",
    "legs_deploy", "touchdown", "engine_cutoff", "safing",
}
TOP_KEYS = {"destination", "vehicle", "launch_azimuth_deg", "altitude_mapping", "downrange_mapping", "phases", "ship",
            "booster", "shots", "interlude"}
PHASE_KEYS = {"id", "segment", "mission_start", "mission_end", "duration", "clock", "events"}
POINT_KEYS = {"t", "alt_km", "speed_kmh", "lox", "ch4", "engines", "downrange_km", "pitch_deg"}
SHOT_KEYS = {"phase", "from", "to", "type", "target", "anchor", "offset", "look", "radius", "height", "speed", "angle",
             "fov", "shake", "cut"}
INTERLUDE_KEYS = {"from", "to", "transfer_days", "departure_dv_km_s", "arrival_speed_km_s", "tanker_flights"}
RESOURCE = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")

PITCH_STEP_MAX = 25.0  # deg between consecutive keyframes
DOWNRANGE_LIMIT = 1000.0  # in-world blocks at SECO and at the start of the descent ("about 1,000")
DOWNRANGE_HARD = 1100.0
STAGE_TOL = dict(alt_km=0.002, downrange_km=0.002, pitch_deg=0.05, speed_kmh=0.5)


def is_num(x) -> bool:
    return isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x)


def is_int(x) -> bool:
    return isinstance(x, int) and not isinstance(x, bool)


@dataclass
class Report:
    name: str
    errors: list = field(default_factory=list)
    warnings: list = field(default_factory=list)
    notes: list = field(default_factory=list)

    def err(self, msg):
        self.errors.append(msg)

    def warn(self, msg):
        self.warnings.append(msg)

    def note(self, msg):
        self.notes.append(msg)


# ----- codec-equivalent model ------------------------------------------------------------------------------------


def ease(kind, p):
    p = min(1.0, max(0.0, p))
    return {"linear": p, "ease_in": p * p, "ease_out": 1 - (1 - p) ** 2, "ease_in_out": p * p * (3 - 2 * p)}[kind]


def to_blocks(metres, true_scale, log_scale):
    a = abs(metres)
    b = a if a <= true_scale else true_scale + log_scale * math.log1p((a - true_scale) / log_scale)
    return math.copysign(b, metres)


class Track:
    """TelemetryTrack.sample: linear interpolation, engines from the lower keyframe, clamped at the ends."""

    def __init__(self, points):
        self.p = points

    def sample(self, t):
        p = self.p
        if not p:
            return None
        if t <= p[0]["t"]:
            return dict(p[0])
        if t >= p[-1]["t"]:
            return dict(p[-1])
        lo, hi = 0, len(p) - 1
        while hi - lo > 1:
            mid = (lo + hi) // 2
            if p[mid]["t"] <= t:
                lo = mid
            else:
                hi = mid
        a, b = p[lo], p[hi]
        f = (t - a["t"]) / (b["t"] - a["t"])
        out = {k: a[k] + (b[k] - a[k]) * f for k in ("alt_km", "speed_kmh", "lox", "ch4", "downrange_km", "pitch_deg")}
        out["engines"] = a["engines"]
        out["t"] = t
        return out


def with_defaults(point):
    return {"t": point.get("t"), "alt_km": point.get("alt_km"), "speed_kmh": point.get("speed_kmh"),
            "lox": point.get("lox", 1.0), "ch4": point.get("ch4", 1.0), "engines": point.get("engines", 0),
            "downrange_km": point.get("downrange_km", 0.0), "pitch_deg": point.get("pitch_deg", 90.0)}


def mission_time(phase, at):
    return phase["mission_start"] + (phase["mission_end"] - phase["mission_start"]) * ease(phase["clock"], at)


def event_time(phases, kind):
    for ph in phases:
        for e in ph["events"]:
            if e["type"] == kind:
                return mission_time(ph, e["at"])
    return None


def phase_ticks(ph, pacing):
    return max(1, round(ph["duration"][pacing] * 20.0))


# ----- checks ----------------------------------------------------------------------------------------------------


def check_mapping(rep, prof, key, default):
    m = prof.get(key, default)
    if not isinstance(m, dict):
        rep.err(f"{key} must be an object")
        return default
    for k in m:
        if k not in ("true_scale_m", "log_scale_m"):
            rep.warn(f"{key}: unknown field {k} (ignored by the codec)")
    t = m.get("true_scale_m", default["true_scale_m"])
    s = m.get("log_scale_m", default["log_scale_m"])
    if not (is_num(t) and is_num(s)) or t < 0 or s <= 0:
        rep.err(f"{key}: true_scale_m >= 0 and log_scale_m > 0 required (got {t}, {s})")
        return default
    return {"true_scale_m": t, "log_scale_m": s}


def check_points(rep, name, raw, max_engines):
    pts = []
    if not isinstance(raw, list):
        rep.err(f"{name} must be a list of points")
        return pts
    prev = -math.inf
    for i, q in enumerate(raw):
        if not isinstance(q, dict):
            rep.err(f"{name}[{i}] is not an object")
            continue
        for k in q:
            if k not in POINT_KEYS:
                rep.warn(f"{name}[{i}]: unknown field {k}")
        for k in ("t", "alt_km", "speed_kmh"):
            if k not in q:
                rep.err(f"{name}[{i}]: missing required field {k}")
        p = with_defaults(q)
        bad = [k for k in ("t", "alt_km", "speed_kmh", "lox", "ch4", "downrange_km", "pitch_deg") if not is_num(p[k])]
        if bad:
            rep.err(f"{name}[{i}]: non-numeric {bad}")
            continue
        if not is_int(p["engines"]) or not 0 <= p["engines"] <= max_engines:
            rep.err(f"{name}[{i}]: engines must be an integer in 0..{max_engines} (got {p['engines']})")
        for k in ("lox", "ch4"):
            if not 0.0 <= p[k] <= 1.0:
                rep.err(f"{name}[{i}]: {k} = {p[k]} outside 0..1")
        if p["alt_km"] < -1e-9:
            rep.err(f"{name}[{i}]: negative altitude {p['alt_km']}")
        if p["speed_kmh"] < 0:
            rep.err(f"{name}[{i}]: negative speed")
        if p["t"] < prev:
            rep.err(f"{name}[{i}]: keyframes not sorted by t ({p['t']} after {prev})")
        prev = p["t"]
        pts.append(p)
    for a, b in zip(pts, pts[1:]):
        if abs(b["pitch_deg"] - a["pitch_deg"]) > PITCH_STEP_MAX:
            rep.err(f"{name}: pitch jumps {a['pitch_deg']:.1f} -> {b['pitch_deg']:.1f} deg between t={a['t']} and "
                    f"t={b['t']} (max step {PITCH_STEP_MAX})")
    return pts


def validate(path: Path, kind: str | None = None) -> Report:
    rep = Report(path.name)
    try:
        prof = json.loads(path.read_text())
    except Exception as e:  # noqa: BLE001
        rep.err(f"cannot read JSON: {e}")
        return rep
    if not isinstance(prof, dict):
        rep.err("top level must be an object")
        return rep
    kind = kind or ("test" if "test" in path.stem else "main")
    for k in prof:
        if k not in TOP_KEYS:
            rep.warn(f"unknown top-level field {k}")

    # --- top level ---
    dest = prof.get("destination")
    if not isinstance(dest, str) or not RESOURCE.match(dest):
        rep.err(f"destination must be a resource location (got {dest!r})")
    vehicle = prof.get("vehicle", "stack")
    if vehicle not in VEHICLES:
        rep.err(f"vehicle must be one of {VEHICLES}")
    az = prof.get("launch_azimuth_deg", 90.0)
    if not is_num(az):
        rep.err("launch_azimuth_deg must be a number")
    alt_map = check_mapping(rep, prof, "altitude_mapping", {"true_scale_m": 300.0, "log_scale_m": 190.0})
    dr_map = check_mapping(rep, prof, "downrange_mapping", {"true_scale_m": 300.0, "log_scale_m": 60.0})

    # --- phases ---
    phases = prof.get("phases")
    if not isinstance(phases, list) or not phases:
        rep.err("phases must be a non-empty list")
        return rep
    ids = set()
    ok_phases = []
    for i, ph in enumerate(phases):
        if not isinstance(ph, dict):
            rep.err(f"phases[{i}] is not an object")
            continue
        for k in ph:
            if k not in PHASE_KEYS:
                rep.warn(f"phase {ph.get('id', i)}: unknown field {k}")
        for k in ("id", "segment", "mission_start", "mission_end", "duration"):
            if k not in ph:
                rep.err(f"phases[{i}]: missing required field {k}")
        pid = ph.get("id")
        if not isinstance(pid, str) or not pid:
            rep.err(f"phases[{i}]: id must be a non-empty string")
        elif pid in ids:
            rep.err(f"duplicate phase id {pid}")
        ids.add(pid)
        if ph.get("segment") not in SEGMENTS:
            rep.err(f"phase {pid}: segment must be one of {SEGMENTS}")
        clock = ph.get("clock", "linear")
        if clock not in CLOCKS:
            rep.err(f"phase {pid}: clock must be one of {CLOCKS}")
        s, e = ph.get("mission_start"), ph.get("mission_end")
        if not (is_num(s) and is_num(e)):
            rep.err(f"phase {pid}: mission_start/mission_end must be numbers")
            continue
        if e < s:
            rep.err(f"phase {pid} ends before it starts")
        d = ph.get("duration", {})
        if not isinstance(d, dict):
            rep.err(f"phase {pid}: duration must be an object")
            continue
        for k in d:
            if k not in PACINGS:
                rep.warn(f"phase {pid}: unknown duration field {k}")
        for pc in PACINGS:
            if not is_num(d.get(pc)) or not d.get(pc) > 0:
                rep.err(f"Phase {pid} has no {pc} duration")
        events = ph.get("events", [])
        if not isinstance(events, list):
            rep.err(f"phase {pid}: events must be a list")
            events = []
        evs = []
        for j, ev in enumerate(events):
            if not isinstance(ev, dict) or "at" not in ev or "type" not in ev:
                rep.err(f"phase {pid}: event {j} needs 'at' and 'type'")
                continue
            if not is_num(ev["at"]) or not 0.0 <= ev["at"] <= 1.0:
                rep.err(f"phase {pid}: event {ev.get('type')} at={ev.get('at')} outside [0, 1]")
            if ev["type"] not in EVENT_TYPES:
                rep.err(f"phase {pid}: unknown event type {ev['type']!r}")
            if "label" in ev:
                rep.warn(f"phase {pid}: event {ev['type']} has a label (the game localises by type)")
            evs.append(ev)
        ok_phases.append({"id": pid, "segment": ph.get("segment"), "mission_start": s, "mission_end": e,
                          "duration": {pc: d.get(pc, 0.0) for pc in PACINGS}, "clock": clock, "events": evs})
    phases = ok_phases
    for a, b in zip(phases, phases[1:]):
        if abs(a["mission_end"] - b["mission_start"]) > 1e-6:
            rep.err(f"Phase {b['id']} starts at T{b['mission_start']} but {a['id']} ends at T{a['mission_end']}")
        if a["segment"] in SEGMENTS and b["segment"] in SEGMENTS and \
                SEGMENTS.index(b["segment"]) < SEGMENTS.index(a["segment"]):
            rep.err(f"Phase {b['id']} ({b['segment']}) comes after a later segment")
    present = {p["segment"] for p in phases}
    for seg in SEGMENTS:
        if seg not in present:
            (rep.err if kind == "test" else rep.warn)(f"no {seg} phase")

    # --- telemetry ---
    ship = check_points(rep, "ship", prof.get("ship"), 6)
    if "ship" not in prof:
        rep.err("missing required field ship")
    booster = check_points(rep, "booster", prof.get("booster", []), 33) if "booster" in prof else []
    if vehicle == "stack" and not booster:
        rep.err("A stack flight needs booster telemetry")
    if vehicle == "ship" and booster:
        rep.warn("ship-only flight carries booster telemetry")
    t0, t1 = phases[0]["mission_start"], phases[-1]["mission_end"]
    if ship:
        if ship[0]["t"] > t0 + 1e-6 or ship[-1]["t"] < t1 - 1e-6:
            rep.err(f"ship telemetry covers T{ship[0]['t']}..T{ship[-1]['t']}, timeline is T{t0}..T{t1}")
    st, sb = Track(ship), Track(booster)

    # --- events in flight order ---
    times = {}
    for ph in phases:
        for ev in ph["events"]:
            times.setdefault(ev["type"], mission_time(ph, ev["at"]))
    t_liftoff = times.get("liftoff")
    if t_liftoff is None:
        rep.err("no liftoff event")
    if vehicle == "stack":
        t_stage = times.get("hot_staging")
        t_catch = times.get("booster_catch")
        if t_stage is None:
            rep.err("stack flight without a hot_staging event (the ship would never leave the booster)")
        if t_catch is None:
            rep.err("stack flight without a booster_catch event")
        if booster and t_liftoff is not None and booster[0]["t"] > t_liftoff + 1e-6:
            rep.err("booster telemetry starts after liftoff")
        if booster and t_catch is not None:
            if booster[-1]["t"] < t_catch - 1e-6:
                rep.err(f"booster telemetry ends at T{booster[-1]['t']} before the catch at T{t_catch:.3f}")
            end = booster[-1]
            if abs(end["downrange_km"]) > 1e-3 or end["alt_km"] > 0.15 or end["speed_kmh"] > 5.0:
                rep.err(f"booster does not end caught at the tower: downrange {end['downrange_km']} km, "
                        f"altitude {end['alt_km']} km, speed {end['speed_kmh']} km/h")
            if abs(end["pitch_deg"] - 90.0) > 1.0:
                rep.err(f"booster ends at pitch {end['pitch_deg']} (expected upright, 90)")
        if t_stage is not None and booster and ship:
            # Before hot staging the stack moves as one: both tracks must agree.
            checks = sorted({q["t"] for q in ship + booster if t_liftoff is not None and t_liftoff <= q["t"] <= t_stage}
                            | {t_stage})
            worst = {k: 0.0 for k in STAGE_TOL}
            for t in checks:
                a, b = st.sample(t), sb.sample(t)
                for k in STAGE_TOL:
                    worst[k] = max(worst[k], abs(a[k] - b[k]))
            for k, tol in STAGE_TOL.items():
                if worst[k] > tol:
                    rep.err(f"ship and booster disagree before hot staging: max |d {k}| = {worst[k]:.4f} (> {tol})")

    # --- touchdown ---
    descents = [p for p in phases if p["segment"] == "descent"]
    if descents:
        last = descents[-1]
        t_td = last["mission_end"]
        if ship:
            q = st.sample(t_td)
            if abs(q["alt_km"]) > 1e-9 or abs(q["downrange_km"]) > 1e-9 or abs(q["pitch_deg"] - 90.0) > 1e-9:
                rep.err(f"touchdown state at T{t_td}: altitude {q['alt_km']}, downrange {q['downrange_km']}, pitch "
                        f"{q['pitch_deg']} (must be exactly 0, 0, 90)")
            if not any(abs(p["t"] - t_td) < 1e-6 for p in ship):
                rep.err("no ship keyframe exactly at touchdown")
            if q["speed_kmh"] > 2.0 * 3.6:
                rep.err(f"touchdown at {q['speed_kmh'] / 3.6:.2f} m/s (> 2 m/s)")
        if times.get("touchdown") is None:
            rep.err("no touchdown event")
        elif abs(times["touchdown"] - t_td) > 1e-6:
            rep.err(f"touchdown event at T{times['touchdown']} is not the end of the last descent phase T{t_td}")
        # During the descent the ship approaches the site: downrange <= 0.
        pos = [p for p in ship if descents[0]["mission_start"] <= p["t"] <= t_td and p["downrange_km"] > 0.05]
        if pos:
            rep.warn(f"{len(pos)} descent keyframes past the landing site (max {max(p['downrange_km'] for p in pos):.3f} km)")

    # --- in-world distances ---
    def blocks(km):
        return to_blocks(km * 1000.0, dr_map["true_scale_m"], dr_map["log_scale_m"])

    if ship:
        asc_end = max((p["mission_end"] for p in phases if p["segment"] == "ascent"), default=None)
        t_seco = times.get("seco")
        if t_seco is not None and asc_end is not None and t_seco <= asc_end:
            b = blocks(st.sample(t_seco)["downrange_km"])
            rep.note(f"ship in-world downrange at SECO: {b:.0f} blocks")
            lim_check(rep, "SECO", b)
        if asc_end is not None:
            b = blocks(st.sample(asc_end)["downrange_km"])
            rep.note(f"ship in-world downrange at the end of the ascent: {b:.0f} blocks")
            lim_check(rep, "end of ascent", b)
        if descents:
            b = blocks(st.sample(descents[0]["mission_start"])["downrange_km"])
            rep.note(f"ship in-world downrange at the start of the descent: {b:.0f} blocks")
            lim_check(rep, "start of descent", b)
        inworld = [p for p in ship if any(ph["mission_start"] <= p["t"] <= ph["mission_end"] and ph["segment"] !=
                                          "transfer" for ph in phases)]
        if inworld:
            mx = max(abs(blocks(p["downrange_km"])) for p in inworld)
            ma = max(to_blocks(p["alt_km"] * 1000.0, alt_map["true_scale_m"], alt_map["log_scale_m"]) for p in inworld)
            rep.note(f"ship max in-world distance: {mx:.0f} blocks downrange, {ma:.0f} blocks up")
    if booster:
        mx = max(abs(blocks(p["downrange_km"])) for p in booster)
        rep.note(f"booster max in-world downrange: {mx:.0f} blocks")

    # --- shots ---
    shots = prof.get("shots", [])
    if not isinstance(shots, list):
        rep.err("shots must be a list")
        shots = []
    cover = {p["id"]: [] for p in phases}
    for i, sh in enumerate(shots):
        if not isinstance(sh, dict):
            rep.err(f"shots[{i}] is not an object")
            continue
        for k in sh:
            if k not in SHOT_KEYS:
                rep.warn(f"shots[{i}]: unknown field {k}")
        pid = sh.get("phase")
        if pid not in cover:
            rep.err(f"Camera shot for unknown phase {pid}")
            continue
        fr, to = sh.get("from", 0.0), sh.get("to", 1.0)
        if not (is_num(fr) and is_num(to)) or not 0.0 <= fr < to <= 1.0:
            rep.err(f"shot {i} ({pid}): need 0 <= from < to <= 1 (got {fr}, {to})")
            continue
        if sh.get("type") not in SHOT_TYPES:
            rep.err(f"shot {i} ({pid}): type must be one of {SHOT_TYPES}")
        tgt = sh.get("target", "ship")
        if tgt not in TARGETS:
            rep.err(f"shot {i} ({pid}): target must be one of {TARGETS}")
        if sh.get("anchor", "pad") not in ANCHORS:
            rep.err(f"shot {i} ({pid}): anchor must be one of {ANCHORS}")
        for k in ("offset", "look"):
            if k in sh and (not isinstance(sh[k], list) or len(sh[k]) != 3 or not all(is_num(x) for x in sh[k])):
                rep.err(f"shot {i} ({pid}): {k} must be a list of 3 numbers")
        for k in ("radius", "height", "speed", "angle", "fov", "shake"):
            if k in sh and not is_num(sh[k]):
                rep.err(f"shot {i} ({pid}): {k} must be a number")
        if "cut" in sh and not isinstance(sh["cut"], bool):
            rep.err(f"shot {i} ({pid}): cut must be a boolean")
        if not 1.0 <= sh.get("fov", 70.0) <= 170.0:
            rep.err(f"shot {i} ({pid}): fov {sh.get('fov')} out of range")
        if not 0.0 <= sh.get("shake", 0.0) <= 1.0:
            rep.err(f"shot {i} ({pid}): shake must be in 0..1")
        ph = next(p for p in phases if p["id"] == pid)
        if tgt in ("booster", "stack") and vehicle != "stack":
            rep.err(f"shot {i} ({pid}): target {tgt} in a ship-only flight")
        if vehicle == "stack" and tgt != "ship":
            ta, tb = mission_time(ph, fr), mission_time(ph, to)
            if tgt == "booster" and booster and tb > booster[-1]["t"] + 1e-6:
                rep.warn(f"shot {i} ({pid}): booster shot outlives the booster telemetry")
            if tgt == "stack" and times.get("hot_staging") is not None and ta >= times["hot_staging"]:
                rep.warn(f"shot {i} ({pid}): stack shot after hot staging")
        if sh.get("anchor") == "tower" and ph["segment"] in ("descent", "landed") and dest != "minecraft:overworld":
            rep.warn(f"shot {i} ({pid}): tower anchor at the destination")
        cover[pid].append((fr, to))
    for pid, iv in cover.items():
        iv.sort()
        x = 0.0
        for a, b in iv:
            if a > x + 1e-9:
                break
            x = max(x, b)
        if x < 1.0 - 1e-9:
            rep.err(f"phase {pid}: shots cover only [0, {x:.3f}]")

    # --- interlude ---
    inter = prof.get("interlude")
    if inter is not None:
        if not isinstance(inter, dict):
            rep.err("interlude must be an object")
        else:
            for k in inter:
                if k not in INTERLUDE_KEYS:
                    rep.warn(f"interlude: unknown field {k}")
            for k in ("from", "to"):
                if not isinstance(inter.get(k), str):
                    rep.err(f"interlude.{k} must be a string")
            for k in ("transfer_days", "departure_dv_km_s", "arrival_speed_km_s"):
                if not is_num(inter.get(k)) or inter.get(k) <= 0:
                    rep.err(f"interlude.{k} must be a positive number")
            if "tanker_flights" in inter and (not is_int(inter["tanker_flights"]) or inter["tanker_flights"] < 0):
                rep.err("interlude.tanker_flights must be a non-negative integer")

    # --- pacing ---
    for pc in PACINGS:
        ticks = sum(phase_ticks(p, pc) for p in phases)
        rep.note(f"{pc}: {ticks / 20.0:.1f} s = {ticks / 1200.0:.2f} min "
                 f"(transfer {sum(phase_ticks(p, pc) for p in phases if p['segment'] == 'transfer') / 20.0:.1f} s)")
    if kind == "test":
        for p in phases:
            for pc in PACINGS:
                if not 0.5 <= p["duration"][pc] <= 2.0:
                    rep.err(f"test phase {p['id']}: {pc} duration {p['duration'][pc]} s outside 0.5..2 s")
        total = sum(p["duration"]["standard"] for p in phases)
        if not 7.0 <= total <= 13.0:
            rep.err(f"test hop lasts {total} s in standard (about 10 s wanted)")
    else:
        targets = {"short": (2.5, 3.7), "standard": (6.0, 8.0), "long": (12.0, 15.0)}
        for pc, (lo, hi) in targets.items():
            mins = sum(phase_ticks(p, pc) for p in phases) / 1200.0
            if not lo <= mins <= hi:
                rep.warn(f"{pc} pacing lasts {mins:.2f} min (target {lo}-{hi})")
        tr = sum(p["duration"]["standard"] for p in phases if p["segment"] == "transfer")
        if not 45.0 <= tr <= 75.0:
            rep.warn(f"standard transfer interlude lasts {tr} s (about 60 s wanted)")
        if t_liftoff is not None:
            t_sep = times.get("hot_staging", times.get("seco"))
            g = gameplay_between(phases, t_liftoff, t_sep, "standard")
            rep.note(f"standard gameplay from liftoff to {'hot staging' if 'hot_staging' in times else 'SECO'}: "
                     f"{g:.1f} s")
            if vehicle == "stack" and g < 25.0:
                rep.warn(f"liftoff to staging lasts only {g:.1f} s in standard")
        if "landing_burn" in times and descents:
            g = gameplay_between(phases, times["landing_burn"], descents[-1]["mission_end"], "standard")
            rep.note(f"standard gameplay for the flip and landing burn: {g:.1f} s")
            if g < 15.0:
                rep.warn(f"flip and landing burn last only {g:.1f} s in standard")
    return rep


def lim_check(rep, what, b):
    if abs(b) > DOWNRANGE_HARD:
        rep.err(f"in-world downrange at {what} is {b:.0f} blocks (limit about {DOWNRANGE_LIMIT:.0f})")
    elif abs(b) > DOWNRANGE_LIMIT:
        rep.warn(f"in-world downrange at {what} is {b:.0f} blocks (limit about {DOWNRANGE_LIMIT:.0f})")


def gameplay_between(phases, ta, tb, pacing):
    """Gameplay seconds between two mission times (inverting each phase's clock numerically)."""
    total = 0.0
    for p in phases:
        s, e = p["mission_start"], p["mission_end"]
        if e <= ta or s >= tb or e <= s:
            continue
        def prog(t):
            lo, hi = 0.0, 1.0
            for _ in range(60):
                mid = 0.5 * (lo + hi)
                if mission_time(p, mid) < t:
                    lo = mid
                else:
                    hi = mid
            return 0.5 * (lo + hi)
        pa = prog(max(ta, s))
        pb = prog(min(tb, e))
        total += (pb - pa) * p["duration"][pacing]
    return total


def main(argv):
    files = [Path(a) for a in argv] or DEFAULT_FILES
    failed = False
    for f in files:
        rep = validate(f)
        status = "FAIL" if rep.errors else "ok"
        print(f"== {f.relative_to(ROOT) if f.is_absolute() and ROOT in f.parents else f}: {status} "
              f"({len(rep.errors)} errors, {len(rep.warnings)} warnings)")
        for m in rep.errors:
            print(f"   ERROR   {m}")
        for m in rep.warnings:
            print(f"   warning {m}")
        for m in rep.notes:
            print(f"   - {m}")
        failed |= bool(rep.errors)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
