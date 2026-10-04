"""Builds the flight-profile JSON files from the simulations.

The profile schema is the Mojang-codec schema in src/main/java/io/github/avi130805/redplanet/starship/flight/:
phases (contiguous mission time, ordered segments), telemetry keyframes (linear interpolation, engines switch at
keyframes), camera shots and the transfer interlude. Keyframes are the dense simulation samples thinned with an
error bound expressed in the units that matter: in-world blocks (through the altitude and downrange mappings) for
position, km/h for speed, degrees for attitude.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np
from scipy.integrate import solve_ivp

from models import BOOSTER_PROPELLANT, EARTH, MARS, RAPTOR_VAC, SHIP_PROPELLANT, Planet

DAY = 86400.0
DEG = math.pi / 180.0


# =================================================================================================================
# Mappings (AltitudeMapping.java) and easing (PhaseDef.Easing)
# =================================================================================================================


@dataclass(frozen=True)
class Mapping:
    true_scale_m: float
    log_scale_m: float

    def blocks(self, metres: float) -> float:
        a = abs(metres)
        b = a if a <= self.true_scale_m else self.true_scale_m + self.log_scale_m * math.log1p(
            (a - self.true_scale_m) / self.log_scale_m)
        return math.copysign(b, metres)


ALTITUDE_MAPPING = Mapping(300.0, 190.0)  # FlightProfile default
DOWNRANGE_MAPPING = Mapping(300.0, 60.0)  # FlightProfile.DEFAULT_DOWNRANGE


def ease(kind: str, p: float) -> float:
    p = min(1.0, max(0.0, p))
    if kind == "linear":
        return p
    if kind == "ease_in":
        return p * p
    if kind == "ease_out":
        return 1.0 - (1.0 - p) * (1.0 - p)
    if kind == "ease_in_out":
        return p * p * (3.0 - 2.0 * p)
    raise ValueError(kind)


def ease_inverse(kind: str, f: float) -> float:
    """Phase progress at which an eased clock reaches the mission-time fraction f."""
    f = min(1.0, max(0.0, f))
    if kind == "linear":
        return f
    if kind == "ease_in":
        return math.sqrt(f)
    if kind == "ease_out":
        return 1.0 - math.sqrt(1.0 - f)
    if kind == "ease_in_out":
        return 0.5 - math.sin(math.asin(1.0 - 2.0 * f) / 3.0)
    raise ValueError(kind)


def ease_rate(kind: str, p: float) -> float:
    """d(mission fraction)/d(progress)."""
    if kind == "linear":
        return 1.0
    if kind == "ease_in":
        return 2.0 * p
    if kind == "ease_out":
        return 2.0 * (1.0 - p)
    return 6.0 * p * (1.0 - p)


# =================================================================================================================
# Keyframes
# =================================================================================================================


@dataclass
class Key:
    t: float
    alt_km: float
    speed_kmh: float
    lox: float
    ch4: float
    engines: int
    downrange_km: float
    pitch_deg: float

    def as_json(self) -> dict:
        def r(x, n):
            return round(x, n) + 0.0  # + 0.0 turns -0.0 into 0.0

        return {
            "t": r(self.t, 3),
            "alt_km": r(max(0.0, self.alt_km), 5),
            "speed_kmh": r(self.speed_kmh, 2),
            "lox": r(min(1.0, max(0.0, self.lox)), 5),
            "ch4": r(min(1.0, max(0.0, self.ch4)), 5),
            "engines": int(self.engines),
            "downrange_km": r(self.downrange_km, 5),
            "pitch_deg": r(self.pitch_deg, 2),
        }


@dataclass
class Tolerance:
    blocks: float = 0.25  # in-world position error (altitude and downrange, through the mappings)
    alt_km: float = 0.02  # HUD altitude
    speed_kmh: float = 3.0
    speed_rel: float = 0.003
    pitch_deg: float = 0.5
    fill: float = 0.002
    max_gap: float = 60.0  # s between keyframes, at most
    max_pitch_step: float = 15.0  # deg between consecutive keyframes (rotations stay sweeps, not jumps)


def simplify(keys: list[Key], tol: Tolerance, forced: set[int] | None = None,
             alt_map: Mapping = ALTITUDE_MAPPING, dr_map: Mapping = DOWNRANGE_MAPPING) -> list[Key]:
    """Keep the fewest keyframes whose linear interpolation stays within tolerance of every dense sample.

    Ramer-Douglas-Peucker on a normalised multi-channel error; engine-count changes, segment ends and any forced
    indices are always kept (engines switch at keyframes, so a change needs a keyframe at its first sample).
    """
    n = len(keys)
    if n <= 2:
        return list(keys)
    keep = {0, n - 1} | set(forced or ())
    for i in range(1, n):
        if keys[i].engines != keys[i - 1].engines:
            keep.add(i)
            keep.add(i - 1)
    t = np.array([k.t for k in keys])
    alt = np.array([k.alt_km for k in keys])
    altb = np.array([alt_map.blocks(k.alt_km * 1000.0) for k in keys])
    spd = np.array([k.speed_kmh for k in keys])
    lox = np.array([k.lox for k in keys])
    ch4 = np.array([k.ch4 for k in keys])
    dr = np.array([k.downrange_km for k in keys])
    drb = np.array([dr_map.blocks(k.downrange_km * 1000.0) for k in keys])
    pit = np.array([k.pitch_deg for k in keys])

    def worst(i, j):
        if j - i < 2:
            return -1, 0.0
        sl = slice(i + 1, j)
        dt = t[j] - t[i]
        f = (t[sl] - t[i]) / dt if dt > 0 else np.zeros(j - i - 1)

        def lin(a):
            return a[i] + (a[j] - a[i]) * f

        alt_i = lin(alt)
        e = np.abs(np.array([alt_map.blocks(x * 1000.0) for x in alt_i]) - altb[sl]) / tol.blocks
        e = np.maximum(e, np.abs(alt_i - alt[sl]) / tol.alt_km)
        dr_i = lin(dr)
        e = np.maximum(e, np.abs(np.array([dr_map.blocks(x * 1000.0) for x in dr_i]) - drb[sl]) / tol.blocks)
        e = np.maximum(e, np.abs(lin(spd) - spd[sl]) / np.maximum(tol.speed_kmh, tol.speed_rel * np.abs(spd[sl])))
        e = np.maximum(e, np.abs(lin(pit) - pit[sl]) / tol.pitch_deg)
        e = np.maximum(e, np.abs(lin(lox) - lox[sl]) / tol.fill)
        e = np.maximum(e, np.abs(lin(ch4) - ch4[sl]) / tol.fill)
        k = int(np.argmax(e))
        err = float(e[k])
        if err <= 1.0 and abs(pit[j] - pit[i]) > tol.max_pitch_step:
            # Split a fast rotation where the attitude is halfway, so no step exceeds the limit.
            k = int(np.argmin(np.abs(pit[sl] - 0.5 * (pit[i] + pit[j]))))
            err = 2.0
        if dt > tol.max_gap and err <= 1.0:
            k = int(np.argmin(np.abs(t[sl] - 0.5 * (t[i] + t[j]))))
            err = 2.0
        return i + 1 + k, err

    order = sorted(keep)
    stack = [(order[a], order[a + 1]) for a in range(len(order) - 1)]
    while stack:
        i, j = stack.pop()
        k, err = worst(i, j)
        if err > 1.0:
            keep.add(k)
            stack.append((i, k))
            stack.append((k, j))
    return [keys[i] for i in sorted(keep)]


def keys_from_samples(samples, *, t_offset: float = 0.0, s_offset: float = 0.0, fill=None,
                      engines=None) -> list[Key]:
    """Dense keyframes from simulation samples. `fill(sample) -> (lox, ch4)`; `engines(sample) -> int`."""
    out = []
    for s in samples:
        lx, c4 = fill(s) if fill else (1.0, 1.0)
        out.append(Key(s.t + t_offset, s.h / 1000.0, s.v * 3.6, lx, c4,
                       engines(s) if engines else s.engines, (s.s - s_offset) / 1000.0, math.degrees(s.pitch)))
    return out


def ship_fill(prop: float) -> tuple[float, float]:
    f = prop / SHIP_PROPELLANT
    return f, f  # consumed at the loading ratio (O/F 3.5): both tanks drain together


def booster_fill(prop: float) -> tuple[float, float]:
    f = prop / BOOSTER_PROPELLANT
    return f, f


# =================================================================================================================
# Two-body coasts for the interlude telemetry
# =================================================================================================================


def two_body(planet: Planet, r0: np.ndarray, v0: np.ndarray, times: np.ndarray):
    """Positions and velocities at the given times (s, may be negative) from a 2D Kepler orbit."""
    mu = planet.mu

    def f(t, y):
        r = math.hypot(y[0], y[1])
        a = -mu / r ** 3
        return [y[2], y[3], a * y[0], a * y[1]]

    out = {}
    for sign in (1.0, -1.0):
        ts = sorted(t for t in times if t * sign > 0)
        if not ts:
            continue
        if sign < 0:
            ts = ts[::-1]
        sol = solve_ivp(f, (0.0, ts[-1]), [r0[0], r0[1], v0[0], v0[1]], t_eval=ts, rtol=1e-10, atol=1e-3,
                        method="DOP853")
        for k, t in enumerate(sol.t):
            out[t] = sol.y[:, k]
    if 0.0 in times or any(abs(t) < 1e-9 for t in times):
        out[0.0] = np.array([r0[0], r0[1], v0[0], v0[1]])
    return out


def state_from_entry(planet: Planet, h: float, v: float, gamma: float):
    r = planet.radius + h
    return np.array([0.0, r]), np.array([v * math.cos(gamma), v * math.sin(gamma)])


# =================================================================================================================
# Phases, events, shots
# =================================================================================================================


@dataclass
class Phase:
    id: str
    segment: str
    start: float
    end: float
    durations: tuple  # short, standard, long (gameplay seconds)
    clock: str = "linear"
    events: list = field(default_factory=list)  # (mission time, type)
    shots: list = field(default_factory=list)  # dicts; from/to may be fractions or ("t", mission time)

    def frac(self, t: float) -> float:
        span = self.end - self.start
        return 0.0 if span <= 0 else ease_inverse(self.clock, (t - self.start) / span)

    def as_json(self) -> dict:
        evs = []
        for t, kind in sorted(self.events, key=lambda e: e[0]):
            evs.append({"at": round(self.frac(t), 9), "type": kind})
        return {
            "id": self.id,
            "segment": self.segment,
            "mission_start": round(self.start, 3),
            "mission_end": round(self.end, 3),
            "duration": {"short": self.durations[0], "standard": self.durations[1], "long": self.durations[2]},
            "clock": self.clock,
            "events": evs,
        }


def shot(kind: str, frm, to, **kw) -> dict:
    d = {"from": frm, "to": to, "type": kind}
    d.update(kw)
    return d


def resolve_shots(phases: list[Phase]) -> list[dict]:
    out = []
    for ph in phases:
        for s in ph.shots:
            d = {"phase": ph.id}
            for key in ("from", "to"):
                v = s[key]
                if isinstance(v, tuple):
                    v = ph.frac(v[1])
                d[key] = round(float(v), 6)
            for key, v in s.items():
                if key in ("from", "to"):
                    continue
                d[key] = [round(float(x), 3) for x in v] if isinstance(v, (list, tuple)) else v
            out.append(d)
    return out


def rate_table(phases: list[Phase]) -> list[str]:
    """Mission seconds per gameplay second at each phase's start and end, per pacing (for tuning the clocks)."""
    lines = []
    for ph in phases:
        span = ph.end - ph.start
        row = []
        for dur in ph.durations:
            r = span / dur
            row.append(f"{r * ease_rate(ph.clock, 0.0):9.2f}->{r * ease_rate(ph.clock, 1.0):9.2f}")
        lines.append(f"{ph.id:18s} {ph.clock:12s} " + " | ".join(row))
    return lines


# =================================================================================================================
# Shared pieces
# =================================================================================================================

# The game fires an event when its mission time falls in (previous tick, this tick], and it skips the first tick after a
# launch, a reload or a change of dimension (VehicleEntity.fireEvents). An event exactly at the start of the descent
# (where the ship arrives in the new dimension) would never fire, so entry_interface comes half a second later (about
# 2 s of gameplay on the eased entry clock). The pad events are well inside their phases.
ARRIVAL_EVENT_DELAY = 0.5  # s
LEGS_LEAD = 12.0  # s: the landing legs deploy this long before touchdown (no effect on the point-mass physics)

# SpaceX's pre-launch timeline for Starship V3 (Flight 12 and 13 pages; mission time, s). Propellant loading is shown
# from T-40 min: the GO poll at T-50 min falls before the profile starts.
PRELAUNCH = {
    "ship_lox": -2250.0,  # T-37:30 ship LOX load underway
    "booster_lox": -2220.0,  # T-37:00 booster LOX load underway
    "booster_ch4": -2125.0,  # T-35:25 booster fuel (liquid methane) load underway
    "ship_ch4": -2088.0,  # T-34:48 ship fuel load underway
    "engine_chill": -1290.0,  # T-21:30 Raptor engine chill on booster and ship (venting)
    "booster_done": -170.0,  # T-02:50 booster propellant load complete
    "ship_done": -130.0,  # T-02:10 ship propellant load complete
    "deluge": -17.0,  # T-00:17 flame diverter activation
    "engine_start": -3.0,  # T-00:03 booster engine startup command
}


def load_schedule(t_start: float, t_done: float, f0: float, f1: float):
    """Fill knots: from f0 when loading starts, 97 % of the way after the main flow, topped off when complete."""
    return [(t_start, f0), (t_done - 240.0, f0 + 0.97 * (f1 - f0)), (t_done, f1)]


def schedule(t: float, knots) -> float:
    """Piecewise-linear fill schedule through (time, fraction) knots."""
    ts = [k[0] for k in knots]
    fs = [k[1] for k in knots]
    return float(np.interp(t, ts, fs))


def pad_keys(t0: float, t1: float, lox_knots, ch4_knots, engine_times=(), extra_times=()) -> list[Key]:
    """Keyframes on the pad: the vehicle does not move; the tanks follow the loading schedule.

    engine_times: [(time, engines)] steps (engines switch at keyframes)."""
    times = sorted({t0, t1, *[k[0] for k in lox_knots], *[k[0] for k in ch4_knots], *extra_times,
                    *[e[0] for e in engine_times]})
    times = [t for t in times if t0 <= t <= t1]
    out = []
    for t in times:
        n = 0
        for te, ne in engine_times:
            if t >= te:
                n = ne
        out.append(Key(t, 0.0, 0.0, schedule(t, lox_knots), schedule(t, ch4_knots), n, 0.0, 90.0))
    return out


def coast_keys(planet: Planet, r0, v0, t_ref: float, times, fill: float, downrange_km: float,
               pitch_deg: float = 0.0) -> list[Key]:
    """Keys along a two-body coast; `times` are mission times, t_ref the mission time of (r0, v0)."""
    rel = np.array([t - t_ref for t in times])
    states = two_body(planet, np.asarray(r0), np.asarray(v0), rel)
    out = []
    for t, dt in zip(times, rel):
        y = states[min(states.keys(), key=lambda q: abs(q - dt))]
        r = math.hypot(y[0], y[1])
        v = math.hypot(y[2], y[3])
        out.append(Key(t, (r - planet.radius) / 1000.0, v * 3.6, fill, fill, 0, downrange_km, pitch_deg))
    return out


def transfer_coast(dep_planet: Planet, dep_r, dep_v, t_dep: float, arr_planet: Planet, arr_r, arr_v, t_arr: float,
                   t_coast0: float, t_coast1: float, fill: float, dr_dep: float, dr_arr: float):
    """Keys for the coast: distance and speed relative to the departure planet until the ship is as far from it as
    from the arrival planet (two-body hyperbolas from each end), then relative to the arrival planet."""
    grid = np.unique(np.concatenate([
        t_coast0 + np.geomspace(60.0, t_coast1 - t_coast0, 300),
        [t_coast0, t_coast1]]))
    dep = two_body(dep_planet, np.asarray(dep_r), np.asarray(dep_v), grid - t_dep)
    arr = two_body(arr_planet, np.asarray(arr_r), np.asarray(arr_v), grid - t_arr)

    def dist(states, dt):
        y = states[min(states.keys(), key=lambda q: abs(q - dt))]
        return math.hypot(y[0], y[1])

    d_dep = np.array([dist(dep, t - t_dep) for t in grid])
    d_arr = np.array([dist(arr, t - t_arr) for t in grid])
    cross = int(np.argmax(d_dep >= d_arr))
    t_sw = float(grid[cross])
    # Keys: geometric spacing from each end toward the switch.
    n = 14
    dep_times = sorted({t_coast0, *(t_coast0 + np.geomspace(3600.0, t_sw - t_coast0, n)), t_sw})
    arr_times = sorted({t_sw + 1.0, *(t_coast1 - np.geomspace(3600.0, t_coast1 - t_sw - 1.0, n)), t_coast1})
    keys = coast_keys(dep_planet, dep_r, dep_v, t_dep, dep_times, fill, dr_dep)
    keys += coast_keys(arr_planet, arr_r, arr_v, t_arr, arr_times, fill, dr_arr)
    keys.sort(key=lambda k: k.t)
    return keys, t_sw


def burn_keys(planet: Planet, h0: float, v0: float, t0: float, m0: float, m1: float, n_vac: int, dt: float = 1.0):
    """A prograde burn on the vacuum Raptors from a circular orbit (finite-burn TMI), integrated in 2D."""
    from flightsim import Control, Flight
    f = Flight(planet, h0=h0, v_fwd=v0, mass=m0, t0=t0)
    thrust = n_vac * RAPTOR_VAC.thrust(1.0, 0.0)
    mdot = n_vac * RAPTOR_VAC.mdot
    keys = []
    while True:
        h, s, vx, vz = f.local()
        gam = math.atan2(vz, vx)
        c = Control(thrust=thrust, mdot=mdot, pitch=gam)
        keys.append((f.t, h, math.hypot(vx, vz), f.y[4], math.degrees(gam)))
        step = min(dt, (f.y[4] - m1) / mdot)
        if step <= 1e-9:
            break
        f.step(c, step)
    r = f.y[:2].copy()
    v = f.y[2:4].copy()
    return keys, r, v, f


# =================================================================================================================
# Earth -> Mars
# =================================================================================================================


def entry_keys(track, t_ei: float, m_dry: float, s_td: float) -> list[Key]:
    keys = keys_from_samples(track.samples, t_offset=t_ei, s_offset=s_td,
                             fill=lambda q: ship_fill(q.m - m_dry))
    last = keys[-1]
    # Touchdown: exactly on the site, upright, engines off at contact.
    keys[-1] = Key(last.t, 0.0, last.speed_kmh, last.lox, last.ch4, 0, 0.0, 90.0)
    return keys


def descent_forced(keys: list[Key], times) -> set[int]:
    idx = set()
    ts = np.array([k.t for k in keys])
    for t in times:
        idx.add(int(np.argmin(np.abs(ts - t))))
    return idx


def build_earth_to_mars(ms) -> tuple[dict, dict]:
    a = ms.ascent
    k = a.k
    m = ms.masses
    m_ship_full = m.ship_dry + m.payload_out + SHIP_PROPELLANT
    stack = ms.stack_track.samples
    ship_up = ms.ship_track.samples
    boost = ms.booster_track.samples
    mars = ms.mars.track
    mev = mars.events

    # ----- mission timeline (s) ------------------------------------------------------------------------------
    T_LOAD, T_COUNT, T_IGN_PHASE = -2400.0, -120.0, -20.0
    t_seco = ms.ascent.ship_result["t"]
    T_ASC_END = math.ceil(t_seco - 1e-6) + 10.0
    T_ORBIT_END = 3600.0
    T_TMI_PHASE = 3.0 * DAY
    t_tmi0 = T_TMI_PHASE + 600.0
    t_tmi1 = t_tmi0 + ms.tmi_burn
    T_TMI_END = T_TMI_PHASE + 1800.0
    T_EI = round(t_tmi1 + ms_transfer_days(ms) * DAY, 3)
    T_APPROACH = T_EI - DAY
    T_PLASMA = T_EI + mev["plasma_end"]
    T_BURN = T_EI + mev["landing_burn"]
    T_TD = T_EI + mev["touchdown"]
    T_END = T_TD + 120.0

    # ----- pad: loading schedules (fractions of each tank), SpaceX's V3 pre-launch timeline ------------------
    ship_lox = load_schedule(PRELAUNCH["ship_lox"], PRELAUNCH["ship_done"], 0.0, 1.0)
    ship_ch4 = load_schedule(PRELAUNCH["ship_ch4"], PRELAUNCH["ship_done"], 0.0, 1.0)
    boost_lox = load_schedule(PRELAUNCH["booster_lox"], PRELAUNCH["booster_done"], 0.0, 1.0)
    boost_ch4 = load_schedule(PRELAUNCH["booster_ch4"], PRELAUNCH["booster_done"], 0.0, 1.0)
    T_IGNITION = PRELAUNCH["engine_start"]
    ship_pad = pad_keys(T_LOAD, 0.0, ship_lox, ship_ch4, extra_times=(T_COUNT, T_IGN_PHASE, T_IGNITION))
    boost_pad = pad_keys(T_LOAD, 0.0, boost_lox, boost_ch4, engine_times=[(T_IGNITION, 33)],
                         extra_times=(T_COUNT, T_IGN_PHASE, T_IGNITION - 0.05))

    # ----- ascent: the stack (shared keyframes until hot staging) ---------------------------------------------
    stack_keys = keys_from_samples(stack, fill=lambda q: booster_fill(q.m - m_ship_full - m.booster_dry))
    stack_keys = simplify(stack_keys, Tolerance(), descent_forced(stack_keys, (k.max_q, k.meco)))
    boost_asc = list(stack_keys)
    ship_asc = [Key(q.t, q.alt_km, q.speed_kmh, 1.0, 1.0, 0, q.downrange_km, q.pitch_deg) for q in stack_keys]
    # Liftoff keyframe on the pad at T0 belongs to the ascent tracks; drop the duplicate pad T0.
    ship_pad = [q for q in ship_pad if q.t < 0.0]
    boost_pad = [q for q in boost_pad if q.t < 0.0]

    # Ship after staging, to SECO, then coasting in orbit.
    m_dry_out = m.ship_dry + m.payload_out
    up = keys_from_samples(ship_up, fill=lambda q: ship_fill(q.m - m_dry_out))
    up = simplify(up, Tolerance())
    seco_fill = ship_fill(ms.seco_prop)[0]
    last = up[-1]
    orbit_v = last.speed_kmh
    up.append(Key(T_ASC_END, last.alt_km, orbit_v, seco_fill, seco_fill, 0, last.downrange_km + orbit_v / 3.6 *
                  (T_ASC_END - last.t) / 1000.0 * EARTH.radius / (EARTH.radius + last.alt_km * 1000.0), 0.0))

    # Booster after staging: flip, boostback, coast, landing burn, catch.
    bk = keys_from_samples(boost, fill=lambda q: booster_fill(q.m - m.booster_dry))
    bk[-1] = Key(k.booster_catch, k.catch_alt / 1000.0, 0.0, bk[-1].lox, bk[-1].ch4, 0, 0.0, 90.0)
    bk = simplify(bk, Tolerance(), descent_forced(bk, (k.boostback_start, k.boostback_end, k.booster_landing_burn)))
    caught = bk[-1]
    bk.append(Key(420.0, caught.alt_km, 0.0, caught.lox, caught.ch4, 0, 0.0, 90.0))  # held by the tower arms

    # ----- transfer ------------------------------------------------------------------------------------------
    dr_seco = up[-1].downrange_km
    s_td = mars.samples[-1].s
    dr_entry = (mars.samples[0].s - s_td) / 1000.0
    transfer = [Key(T_ORBIT_END, 200.0, orbit_v, seco_fill, seco_fill, 0, dr_seco, 0.0)]
    # Refilling: tankers dock one after another; each transfer takes two hours.
    t_ref0, t_ref1 = T_ORBIT_END, T_TMI_PHASE
    spacing = (t_ref1 - t_ref0 - 2.0 * 3600.0) / ms.tankers
    prop = ms.seco_prop
    for n in range(ms.tankers):
        ta = t_ref0 + 3600.0 + n * spacing
        tb = ta + 2.0 * 3600.0
        f0 = ship_fill(prop)[0]
        prop += ms.per_tanker
        f1 = ship_fill(prop)[0]
        transfer.append(Key(ta, 200.0, orbit_v, f0, f0, 0, dr_seco, 0.0))
        transfer.append(Key(tb, 200.0, orbit_v, f1, f1, 0, dr_seco, 0.0))
    # Trans-Mars injection: finite prograde burn on the three vacuum Raptors (its first keyframe lights them).
    m_before = m_dry_out + ms.tmi_prop_before
    m_after = m_dry_out + ms.mars.prop_ei
    raw, r_tmi, v_tmi, f_tmi = burn_keys(EARTH, 200.0e3, orbit_v / 3.6, t_tmi0, m_before, m_after, 3)
    burn = [Key(t, h / 1000.0, v * 3.6, *ship_fill(mm - m_dry_out), 3, dr_seco, pg) for t, h, v, mm, pg in raw]
    burn[-1].engines = 0
    transfer += simplify(burn, Tolerance(pitch_deg=1.0, max_gap=30.0))
    arr_fill = ship_fill(ms.mars.prop_ei)[0]
    r_ei, v_ei = state_from_entry(MARS, 125.0e3, mars.samples[0].v, mars.samples[0].gamma)
    coast, t_switch = transfer_coast(EARTH, r_tmi, v_tmi, t_tmi1, MARS, r_ei, v_ei, T_EI, t_tmi1, T_APPROACH,
                                     arr_fill, dr_seco, dr_entry)
    transfer += coast[1:]
    # Approach: the last day, Mars-relative, turning to the entry attitude.
    pitch_ei = mars.samples[0].pitch / DEG
    app_times = [T_APPROACH + DAY * f for f in (0.25, 0.5, 0.75, 0.9, 0.97, 0.99, 0.997, 0.999)]
    app = coast_keys(MARS, r_ei, v_ei, T_EI, app_times, arr_fill, dr_entry)
    ramp = [0.0, 0.0, 0.2, 0.45, 0.7, 0.9, 1.0, 1.0]
    for q, w in zip(app, ramp):
        q.pitch_deg = pitch_ei * w
    transfer += app

    # ----- descent and landed --------------------------------------------------------------------------------
    dk = entry_keys(mars, T_EI, m_dry_out, s_td)
    dk = simplify(dk, Tolerance(), descent_forced(dk, (T_PLASMA, T_BURN, T_BURN + 2.0, T_EI + mev["peak_heat_t"]
                                                         if "peak_heat_t" in mev else T_EI)))
    td = dk[-1]
    landed = [Key(T_TD + 0.5, 0.0, 0.0, td.lox, td.ch4, 0, 0.0, 90.0), Key(T_END, 0.0, 0.0, td.lox, td.ch4, 0, 0.0, 90.0)]

    ship_keys = ship_pad + ship_asc + up + transfer + dk + landed
    boost_keys = boost_pad + boost_asc + bk

    # ----- phases --------------------------------------------------------------------------------------------
    t_legs = T_TD - LEGS_LEAD
    ph = [
        Phase("propellant_load", "origin_pad", T_LOAD, T_COUNT, (8, 30, 45), "linear",
              [(PRELAUNCH["ship_lox"], "propellant_load"), (PRELAUNCH["engine_chill"], "vent")]),
        Phase("terminal_count", "origin_pad", T_COUNT, T_IGN_PHASE, (7, 20, 30), "linear",
              [(T_COUNT, "terminal_count")]),
        Phase("ignition", "origin_pad", T_IGN_PHASE, 0.0, (5, 10, 15), "ease_out",
              [(PRELAUNCH["deluge"], "deluge"), (T_IGNITION, "ignition")]),
        Phase("liftoff", "ascent", 0.0, 20.0, (7, 15, 20), "ease_in", [(0.0, "liftoff")]),
        Phase("max_q", "ascent", 20.0, 100.0, (8, 22, 80), "linear", [(k.max_q, "max_q")]),
        Phase("meco", "ascent", 100.0, k.hot_staging, (6, 15, 43), "ease_out", [(k.meco, "meco")]),
        Phase("hot_staging", "ascent", k.hot_staging, 200.0, (8, 20, 57), "ease_in",
              [(k.hot_staging, "hot_staging"), (k.boostback_start, "boostback_start"),
               (k.boostback_end, "boostback_end")]),
        Phase("ship_ascent", "ascent", 200.0, k.booster_landing_burn, (12, 28, 160), "linear", []),
        Phase("booster_catch", "ascent", k.booster_landing_burn, 420.0, (6, 14, 30), "ease_out",
              [(k.booster_landing_burn, "booster_landing_burn"), (k.booster_catch, "booster_catch")]),
        Phase("seco", "ascent", 420.0, T_ASC_END, (7, 16, 80), "ease_in_out", [(t_seco, "seco")]),
        Phase("orbit", "transfer", T_ASC_END, T_ORBIT_END, (3, 6, 8), "linear", [(T_ASC_END, "orbit")]),
        Phase("refilling", "transfer", T_ORBIT_END, T_TMI_PHASE, (8, 14, 20), "linear",
              [(T_ORBIT_END, "refilling")]),
        Phase("tmi", "transfer", T_TMI_PHASE, T_TMI_END, (6, 10, 15), "ease_in_out", [(t_tmi0, "tmi")]),
        Phase("coast", "transfer", T_TMI_END, T_APPROACH, (12, 20, 30), "linear", [(T_TMI_END, "coast")]),
        Phase("approach", "transfer", T_APPROACH, T_EI, (6, 10, 15), "ease_out", [(T_APPROACH, "approach")]),
        Phase("entry", "descent", T_EI, T_PLASMA, (20, 45, 75), "ease_in",
              [(T_EI + ARRIVAL_EVENT_DELAY, "entry_interface"), (T_EI + mars.info["peak_heat_t"], "peak_heating")]),
        Phase("belly_flop", "descent", T_PLASMA, T_BURN, (14, 30, 50), "ease_out",
              [(T_PLASMA, "plasma_end"), (T_EI + mev["belly_flop"], "belly_flop")]),
        Phase("landing", "descent", T_BURN, T_TD, (20, 40, 60), "ease_in_out",
              [(T_BURN, "landing_burn"), (T_BURN + 2.0, "flip"), (t_legs, "legs_deploy"), (T_TD, "touchdown")]),
        Phase("landed", "landed", T_TD, T_END, (8, 18, 30), "ease_in",
              [(T_TD, "engine_cutoff"), (T_TD + 45.0, "safing")]),
    ]
    add_earth_to_mars_shots(ph, k, T_BURN, T_TD, t_legs)
    profile = {
        "destination": "redplanet:mars",
        "vehicle": "stack",
        "launch_azimuth_deg": 90.0,
        "altitude_mapping": {"true_scale_m": ALTITUDE_MAPPING.true_scale_m, "log_scale_m": ALTITUDE_MAPPING.log_scale_m},
        "downrange_mapping": {"true_scale_m": DOWNRANGE_MAPPING.true_scale_m, "log_scale_m": DOWNRANGE_MAPPING.log_scale_m},
        "phases": [p.as_json() for p in ph],
        "ship": [q.as_json() for q in ship_keys],
        "booster": [q.as_json() for q in boost_keys],
        "shots": resolve_shots(ph),
        "interlude": {"from": "earth", "to": "mars", "transfer_days": ms_transfer_days(ms),
                      "departure_dv_km_s": 3.6, "arrival_speed_km_s": round(mars.samples[0].v / 1000.0, 2),
                      "tanker_flights": ms.tankers},
    }
    info = dict(T_EI=T_EI, T_TD=T_TD, t_switch=t_switch, phases=ph, tmi_end_speed=burn[-1].speed_kmh,
                tmi_end_alt=burn[-1].alt_km)
    return profile, info


def ms_transfer_days(ms) -> float:
    from missions import TRANSFER_DAYS
    return TRANSFER_DAYS


def add_earth_to_mars_shots(ph, k, t_burn, t_td, t_legs):
    P = {p.id: p for p in ph}
    aft_cam = dict(target="ship", offset=[4.9, 2.0, 0.0], look=[0.0, -1.0, 0.12])
    P["propellant_load"].shots = [
        shot("orbit", 0.0, 0.35, target="stack", radius=260.0, height=40.0, speed=4.0, angle=30.0, fov=60.0),
        shot("fixed", 0.35, 0.7, target="stack", anchor="tower", offset=[-30.0, 95.0, 25.0], fov=55.0, cut=True),
        shot("fixed", 0.7, 1.0, target="stack", anchor="pad", offset=[-220.0, 6.0, -320.0], fov=35.0)]
    P["terminal_count"].shots = [
        shot("cabin", 0.0, 0.5, fov=75.0),
        shot("fixed", 0.5, 1.0, target="stack", anchor="pad", offset=[160.0, 4.0, -240.0], fov=40.0)]
    P["ignition"].shots = [
        shot("fixed", 0.0, 0.5, target="stack", anchor="pad", offset=[28.0, 3.0, -42.0], fov=50.0, shake=0.2),
        shot("fixed", 0.5, 1.0, target="stack", anchor="pad", offset=[-260.0, 8.0, -380.0], fov=40.0, shake=0.45)]
    P["liftoff"].shots = [
        shot("fixed", 0.0, 0.55, target="stack", anchor="tower", offset=[14.0, 30.0, 4.0], fov=70.0, shake=0.7),
        shot("fixed", 0.55, 1.0, target="stack", anchor="pad", offset=[-300.0, 5.0, -450.0], fov=35.0, shake=0.35)]
    P["max_q"].shots = [
        shot("chase", 0.0, 0.3, target="stack", offset=[0.0, -80.0, -110.0], fov=55.0, shake=0.35),
        shot("cabin", 0.3, ("t", k.max_q + 12.0), fov=75.0, shake=0.5),
        shot("chase", ("t", k.max_q + 12.0), 1.0, target="stack", offset=[-90.0, -30.0, -60.0], fov=50.0, shake=0.25)]
    P["meco"].shots = [
        shot("chase", 0.0, ("t", k.meco - 8.0), target="stack", offset=[-120.0, -60.0, -40.0], fov=55.0, shake=0.2),
        shot("onboard", ("t", k.meco - 8.0), 1.0, fov=80.0, shake=0.3, **aft_cam)]
    P["hot_staging"].shots = [
        shot("onboard", 0.0, ("t", k.boostback_start + 2.0), fov=80.0, shake=0.6, cut=False, **aft_cam),
        shot("chase", ("t", k.boostback_start + 2.0), ("t", 175.0), target="booster", offset=[70.0, 20.0, -90.0],
             fov=60.0, shake=0.2),
        shot("chase", ("t", 175.0), 1.0, target="ship", offset=[0.0, -40.0, -100.0], fov=55.0, shake=0.15)]
    P["ship_ascent"].shots = [
        shot("cabin", 0.0, 0.35, fov=75.0, shake=0.15),
        shot("onboard", 0.35, 0.65, target="ship", offset=[4.9, 8.0, 0.5], look=[0.0, 1.0, 0.2], fov=70.0,
             shake=0.1),
        shot("fixed", 0.65, 1.0, target="booster", anchor="tower", offset=[-60.0, 4.0, -90.0], fov=40.0)]
    P["booster_catch"].shots = [
        shot("fixed", 0.0, ("t", k.booster_catch - 8.0), target="booster", anchor="tower",
             offset=[-45.0, 150.0, -30.0], fov=60.0, shake=0.3),
        shot("fixed", ("t", k.booster_catch - 8.0), 1.0, target="booster", anchor="pad",
             offset=[-180.0, 3.0, -260.0], fov=40.0, shake=0.25)]
    P["seco"].shots = [
        shot("chase", 0.0, 0.55, target="ship", offset=[30.0, 25.0, -120.0], fov=55.0),
        shot("onboard", 0.55, 1.0, target="ship", offset=[4.9, 1.0, 0.0], look=[0.0, -1.0, 0.05], fov=75.0)]
    for pid in ("orbit", "refilling", "tmi", "coast", "approach"):
        P[pid].shots = [shot("cabin", 0.0, 1.0, fov=70.0)]
    P["entry"].shots = [
        shot("chase", 0.0, 0.12, target="ship", offset=[0.0, 60.0, -200.0], fov=60.0),
        shot("onboard", 0.12, 0.6, target="ship", offset=[5.8, 6.0, 1.5], look=[0.0, 1.0, 0.35], fov=85.0,
             shake=0.5),
        shot("cabin", 0.6, 1.0, fov=75.0, shake=0.4)]
    P["belly_flop"].shots = [
        shot("chase", 0.0, 0.55, target="ship", offset=[-80.0, 20.0, -60.0], fov=55.0, shake=0.2),
        shot("fixed", 0.55, 1.0, target="ship", anchor="landing_site", offset=[-600.0, 3.0, -900.0], fov=30.0)]
    P["landing"].shots = [
        shot("fixed", 0.0, ("t", t_burn + 9.0), target="ship", anchor="landing_site", offset=[-90.0, 2.0, -140.0],
             fov=45.0),
        shot("chase", ("t", t_burn + 9.0), ("t", t_legs), target="ship", offset=[40.0, -20.0, -70.0], fov=60.0,
             shake=0.4),
        shot("fixed", ("t", t_legs), 1.0, target="ship", anchor="landing_site", offset=[-45.0, 1.8, -70.0],
             fov=55.0, shake=0.5)]
    P["landed"].shots = [
        shot("orbit", 0.0, 1.0, target="ship", radius=90.0, height=12.0, speed=6.0, angle=200.0, fov=60.0)]


# =================================================================================================================
# Mars -> Earth
# =================================================================================================================


def burn_delta_v(samples) -> float:
    """Rocket-equation delta-v actually flown: the integral of thrust / mass."""
    return sum(a.thrust / a.m * (b.t - a.t) for a, b in zip(samples[:-1], samples[1:]))


def build_mars_to_earth(ms) -> tuple[dict, dict]:
    from missions import LANDING_RESERVE
    m = ms.masses
    m_dry_back = m.ship_dry + m.payload_back
    asc = ms.mars_ascent_track
    res = ms.mars_ascent_result
    earth = ms.earth.track
    eev = earth.events

    T_LOAD, T_COUNT, T_IGN_PHASE = -2400.0, -120.0, -20.0
    T_IGNITION = PRELAUNCH["engine_start"]
    t_seco = res["t"]
    T_ASC_END = math.ceil(t_seco - 1e-6) + 8.0
    T_INJ_END = T_ASC_END + 2.0 * 3600.0
    T_EI = round(t_seco + ms_transfer_days(ms) * DAY, 3)
    T_APPROACH = T_EI - DAY
    T_PLASMA = T_EI + eev["plasma_end"]
    T_BURN = T_EI + eev["landing_burn"]
    T_TD = T_EI + eev["touchdown"]
    T_END = T_TD + 120.0
    t_maxq = res["t_max_q"]

    # Pad: ISRU fills the ship from the landing reserve it arrived with to what the return needs.
    f0 = LANDING_RESERVE / SHIP_PROPELLANT
    f1 = ms.mars_load / SHIP_PROPELLANT
    lox = load_schedule(PRELAUNCH["ship_lox"], PRELAUNCH["ship_done"], f0, f1)
    ch4 = load_schedule(PRELAUNCH["ship_ch4"], PRELAUNCH["ship_done"], f0, f1)
    pad = pad_keys(T_LOAD, 0.0, lox, ch4, engine_times=[(T_IGNITION, 6)],
                   extra_times=(T_COUNT, T_IGN_PHASE, T_IGNITION - 0.05))
    pad = [q for q in pad if q.t < 0.0]

    up = keys_from_samples(asc.samples, fill=lambda q: ship_fill(q.m - m_dry_back))
    up = simplify(up, Tolerance(), descent_forced(up, (t_maxq,)))
    last = up[-1]
    arr_fill = ship_fill(ms.earth.prop_ei)[0]
    # Coasting away after burnout while the ship levels its nose (steps of at most 12 degrees).
    r_bo = np.array([0.0, MARS.radius + res["h"]])
    v_bo = np.array([res["v"] * math.cos(res["gamma"]), res["v"] * math.sin(res["gamma"])])
    n_steps = max(1, math.ceil(abs(last.pitch_deg) / 12.0))
    times = [t_seco + (T_ASC_END - t_seco) * (i + 1) / n_steps for i in range(n_steps)]
    states = two_body(MARS, r_bo, v_bo, np.array([t - t_seco for t in times]))
    for i, t in enumerate(times):
        y = states[min(states.keys(), key=lambda q: abs(q - (t - t_seco)))]
        r = math.hypot(y[0], y[1])
        theta = math.atan2(y[0], y[1])
        up.append(Key(t, (r - MARS.radius) / 1000.0, math.hypot(y[2], y[3]) * 3.6, arr_fill, arr_fill, 0,
                      last.downrange_km + MARS.radius * theta / 1000.0, last.pitch_deg * (1.0 - (i + 1) / n_steps)))

    # Transfer: Mars-relative escape hyperbola from the burnout state, then Earth-relative approach hyperbola.
    r_seco = np.array([0.0, MARS.radius + res["h"]])
    v_seco = np.array([res["v"] * math.cos(res["gamma"]), res["v"] * math.sin(res["gamma"])])
    s_td = earth.samples[-1].s
    dr_entry = (earth.samples[0].s - s_td) / 1000.0
    r_ei, v_ei = state_from_entry(EARTH, 125.0e3, earth.samples[0].v, earth.samples[0].gamma)
    dr_seco = up[-1].downrange_km
    inj = coast_keys(MARS, r_seco, v_seco, t_seco, [T_ASC_END + 600.0, T_ASC_END + 1800.0, T_INJ_END], arr_fill,
                     dr_seco)
    coast, t_switch = transfer_coast(MARS, r_seco, v_seco, t_seco, EARTH, r_ei, v_ei, T_EI, T_INJ_END, T_APPROACH,
                                     arr_fill, dr_seco, dr_entry)
    pitch_ei = earth.samples[0].pitch / DEG
    app_times = [T_APPROACH + DAY * f for f in (0.25, 0.5, 0.75, 0.9, 0.97, 0.99, 0.997, 0.999)]
    app = coast_keys(EARTH, r_ei, v_ei, T_EI, app_times, arr_fill, dr_entry)
    ramp = [0.0, 0.0, 0.2, 0.45, 0.7, 0.9, 1.0, 1.0]
    for q, w in zip(app, ramp):
        q.pitch_deg = pitch_ei * w
    transfer = inj + coast[1:] + app

    dk = entry_keys(earth, T_EI, m_dry_back, s_td)
    dk = simplify(dk, Tolerance(), descent_forced(dk, (T_PLASMA, T_BURN, T_BURN + 2.0,
                                                         T_EI + earth.info["peak_heat_t"])))
    td = dk[-1]
    landed = [Key(T_TD + 0.5, 0.0, 0.0, td.lox, td.ch4, 0, 0.0, 90.0), Key(T_END, 0.0, 0.0, td.lox, td.ch4, 0, 0.0, 90.0)]
    ship_keys = pad + up + transfer + dk + landed

    t_legs = T_TD - LEGS_LEAD
    ph = [
        Phase("propellant_load", "origin_pad", T_LOAD, T_COUNT, (10, 32, 60), "linear",
              [(PRELAUNCH["ship_lox"], "propellant_load"), (PRELAUNCH["engine_chill"], "vent")]),
        Phase("terminal_count", "origin_pad", T_COUNT, T_IGN_PHASE, (8, 20, 35), "linear",
              [(T_COUNT, "terminal_count")]),
        Phase("ignition", "origin_pad", T_IGN_PHASE, 0.0, (5, 10, 15), "ease_out", [(T_IGNITION, "ignition")]),
        Phase("liftoff", "ascent", 0.0, 20.0, (8, 18, 20), "ease_in", [(0.0, "liftoff")]),
        Phase("climb", "ascent", 20.0, 120.0, (12, 30, 100), "linear", [(t_maxq, "max_q")]),
        Phase("mars_ascent", "ascent", 120.0, T_ASC_END, (18, 52, 160), "ease_out", [(t_seco, "seco")]),
        Phase("injection", "transfer", T_ASC_END, T_INJ_END, (7, 12, 18), "linear", [(T_ASC_END, "tmi")]),
        Phase("coast", "transfer", T_INJ_END, T_APPROACH, (16, 32, 45), "linear", [(T_INJ_END, "coast")]),
        Phase("approach", "transfer", T_APPROACH, T_EI, (8, 16, 22), "ease_out", [(T_APPROACH, "approach")]),
        Phase("entry", "descent", T_EI, T_PLASMA, (25, 60, 110), "ease_in",
              [(T_EI + ARRIVAL_EVENT_DELAY, "entry_interface"), (T_EI + earth.info["peak_heat_t"], "peak_heating")]),
        Phase("belly_flop", "descent", T_PLASMA, T_BURN, (20, 45, 90), "ease_out",
              [(T_PLASMA, "plasma_end"), (T_EI + eev["belly_flop"], "belly_flop")]),
        Phase("landing", "descent", T_BURN, T_TD, (16, 28, 40), "ease_in_out",
              [(T_BURN, "landing_burn"), (T_BURN + 2.0, "flip"), (t_legs, "legs_deploy"), (T_TD, "touchdown")]),
        Phase("landed", "landed", T_TD, T_END, (8, 20, 30), "ease_in",
              [(T_TD, "engine_cutoff"), (T_TD + 45.0, "safing")]),
    ]
    add_mars_to_earth_shots(ph, t_maxq, T_BURN, T_TD, t_legs)
    dv = burn_delta_v(asc.samples)
    profile = {
        "destination": "minecraft:overworld",
        "vehicle": "ship",
        "launch_azimuth_deg": 90.0,
        "altitude_mapping": {"true_scale_m": ALTITUDE_MAPPING.true_scale_m, "log_scale_m": ALTITUDE_MAPPING.log_scale_m},
        "downrange_mapping": {"true_scale_m": DOWNRANGE_MAPPING.true_scale_m, "log_scale_m": DOWNRANGE_MAPPING.log_scale_m},
        "phases": [p.as_json() for p in ph],
        "ship": [q.as_json() for q in ship_keys],
        "shots": resolve_shots(ph),
        "interlude": {"from": "mars", "to": "earth", "transfer_days": ms_transfer_days(ms),
                      "departure_dv_km_s": round(dv / 1000.0, 2),
                      "arrival_speed_km_s": round(earth.samples[0].v / 1000.0, 2), "tanker_flights": 0},
    }
    return profile, dict(T_EI=T_EI, T_TD=T_TD, t_switch=t_switch, phases=ph, departure_dv=dv)


def add_mars_to_earth_shots(ph, t_maxq, t_burn, t_td, t_legs):
    P = {p.id: p for p in ph}
    P["propellant_load"].shots = [
        shot("orbit", 0.0, 0.5, target="ship", radius=150.0, height=25.0, speed=4.0, angle=60.0, fov=60.0),
        shot("fixed", 0.5, 1.0, target="ship", anchor="pad", offset=[-140.0, 3.0, -200.0], fov=40.0)]
    P["terminal_count"].shots = [
        shot("cabin", 0.0, 0.5, fov=75.0),
        shot("fixed", 0.5, 1.0, target="ship", anchor="pad", offset=[110.0, 2.5, -160.0], fov=42.0)]
    P["ignition"].shots = [
        shot("fixed", 0.0, 0.5, target="ship", anchor="pad", offset=[20.0, 2.0, -30.0], fov=50.0, shake=0.2),
        shot("fixed", 0.5, 1.0, target="ship", anchor="pad", offset=[-180.0, 4.0, -260.0], fov=38.0, shake=0.35)]
    P["liftoff"].shots = [
        shot("fixed", 0.0, 0.55, target="ship", anchor="pad", offset=[12.0, 1.8, -36.0], fov=70.0, shake=0.6),
        shot("fixed", 0.55, 1.0, target="ship", anchor="pad", offset=[-220.0, 3.0, -330.0], fov=35.0, shake=0.3)]
    P["climb"].shots = [
        shot("chase", 0.0, 0.4, target="ship", offset=[0.0, -60.0, -90.0], fov=55.0, shake=0.25),
        shot("cabin", 0.4, 0.75, fov=75.0, shake=0.3),
        shot("chase", 0.75, 1.0, target="ship", offset=[-70.0, -20.0, -50.0], fov=50.0, shake=0.15)]
    P["mars_ascent"].shots = [
        shot("onboard", 0.0, 0.4, target="ship", offset=[4.9, 2.0, 0.0], look=[0.0, -1.0, 0.25], fov=75.0,
             shake=0.15),
        shot("chase", 0.4, 0.7, target="ship", offset=[30.0, 25.0, -120.0], fov=55.0),
        shot("cabin", 0.7, 1.0, fov=75.0)]
    for pid in ("injection", "coast", "approach"):
        P[pid].shots = [shot("cabin", 0.0, 1.0, fov=70.0)]
    P["entry"].shots = [
        shot("chase", 0.0, 0.1, target="ship", offset=[0.0, 60.0, -200.0], fov=60.0),
        shot("onboard", 0.1, 0.65, target="ship", offset=[5.8, 6.0, 1.5], look=[0.0, 1.0, 0.35], fov=85.0,
             shake=0.6),
        shot("cabin", 0.65, 1.0, fov=75.0, shake=0.5)]
    P["belly_flop"].shots = [
        shot("chase", 0.0, 0.6, target="ship", offset=[-80.0, 20.0, -60.0], fov=55.0, shake=0.25),
        shot("fixed", 0.6, 1.0, target="ship", anchor="landing_site", offset=[-300.0, 2.0, -450.0], fov=32.0)]
    P["landing"].shots = [
        shot("fixed", 0.0, ("t", t_burn + 6.0), target="ship", anchor="landing_site", offset=[-90.0, 2.0, -140.0],
             fov=45.0),
        shot("chase", ("t", t_burn + 6.0), ("t", t_legs), target="ship", offset=[40.0, -20.0, -70.0], fov=60.0,
             shake=0.4),
        shot("fixed", ("t", t_legs), 1.0, target="ship", anchor="landing_site", offset=[-45.0, 1.8, -70.0],
             fov=55.0, shake=0.5)]
    P["landed"].shots = [
        shot("orbit", 0.0, 1.0, target="ship", radius=90.0, height=12.0, speed=6.0, angle=200.0, fov=60.0)]


# =================================================================================================================
# Gametest hop: every segment in about ten seconds, physically crude but schema-complete
# =================================================================================================================


def build_test_hop() -> dict:
    K = Key
    ph = [
        Phase("pad", "origin_pad", -4.0, 0.0, (0.5, 1.5, 2.0), "linear",
              [(-3.6, "propellant_load"), (-3.0, "deluge"), (-2.0, "ignition")]),
        Phase("liftoff", "ascent", 0.0, 6.0, (0.5, 2.0, 2.0), "linear", [(0.0, "liftoff"), (5.0, "hot_staging")]),
        Phase("booster_return", "ascent", 6.0, 14.0, (0.5, 1.5, 2.0), "linear",
              [(7.0, "boostback_start"), (9.0, "boostback_end"), (11.0, "booster_landing_burn"),
               (13.0, "booster_catch"), (13.5, "seco")]),
        Phase("transfer", "transfer", 14.0, 20.0, (0.5, 1.5, 2.0), "linear", [(14.0, "coast")]),
        Phase("descent", "descent", 20.0, 30.0, (0.5, 2.0, 2.0), "linear",
              [(20.5, "entry_interface"), (23.0, "belly_flop"), (25.0, "landing_burn"), (25.5, "flip"),
               (27.0, "legs_deploy"), (30.0, "touchdown")]),
        Phase("landed", "landed", 30.0, 33.0, (0.5, 1.5, 2.0), "linear", [(30.0, "engine_cutoff"), (31.5, "safing")]),
    ]
    stack = [  # t, alt km, speed km/h, downrange km, pitch
        (0.0, 0.0, 0.0, 0.0, 90.0), (1.0, 0.004, 29.0, 0.0, 90.0), (2.0, 0.016, 58.0, 0.0, 90.0),
        (3.0, 0.045, 115.0, 0.002, 89.0), (4.0, 0.09, 180.0, 0.006, 88.0), (5.0, 0.15, 250.0, 0.012, 87.0)]
    booster = [K(-4.0, 0, 0, 1.0, 1.0, 0, 0, 90.0), K(-2.0, 0, 0, 1.0, 1.0, 33, 0, 90.0)]
    ship = [K(-4.0, 0, 0, 1.0, 1.0, 0, 0, 90.0)]
    for t, alt, v, dr, p in stack:
        booster.append(K(t, alt, v, 1.0 - 0.01 * t, 1.0 - 0.01 * t, 33, dr, p))
        ship.append(K(t, alt, v, 1.0, 1.0, 0, dr, p))
    booster[-1].engines = 3
    booster += [K(6.0, 0.17, 150.0, 0.94, 0.94, 3, 0.014, 105.0), K(7.0, 0.18, 80.0, 0.93, 0.93, 13, 0.015, 125.0),
                K(8.0, 0.185, 40.0, 0.92, 0.92, 13, 0.014, 145.0), K(9.0, 0.18, 60.0, 0.91, 0.91, 0, 0.012, 125.0),
                K(10.0, 0.165, 90.0, 0.91, 0.91, 0, 0.008, 105.0), K(11.0, 0.14, 100.0, 0.91, 0.91, 13, 0.004, 92.0),
                K(12.0, 0.115, 40.0, 0.90, 0.90, 3, 0.001, 90.0), K(13.0, 0.1, 0.0, 0.90, 0.90, 0, 0.0, 90.0)]
    ship += [K(6.0, 0.2, 320.0, 0.98, 0.98, 6, 0.018, 84.0), K(9.0, 0.32, 420.0, 0.93, 0.93, 6, 0.05, 76.0),
             K(13.5, 0.55, 560.0, 0.86, 0.86, 0, 0.12, 68.0), K(14.0, 0.56, 560.0, 0.86, 0.86, 0, 0.13, 66.0),
             K(19.9, 0.62, 520.0, 0.30, 0.30, 0, -0.15, 50.0), K(20.0, 0.6, 520.0, 0.30, 0.30, 0, -0.15, 50.0),
             K(21.0, 0.52, 400.0, 0.30, 0.30, 0, -0.12, 35.0), K(22.0, 0.45, 300.0, 0.30, 0.30, 0, -0.09, 18.0),
             K(23.0, 0.38, 250.0, 0.30, 0.30, 0, -0.06, 2.0), K(24.0, 0.31, 250.0, 0.30, 0.30, 0, -0.04, 0.0),
             K(25.0, 0.24, 240.0, 0.30, 0.30, 3, -0.025, 0.0), K(25.5, 0.21, 220.0, 0.295, 0.295, 3, -0.02, 22.0),
             K(26.0, 0.18, 190.0, 0.29, 0.29, 3, -0.015, 44.0), K(26.5, 0.15, 160.0, 0.285, 0.285, 3, -0.01, 66.0),
             K(27.0, 0.12, 130.0, 0.28, 0.28, 3, -0.006, 88.0), K(28.0, 0.06, 70.0, 0.27, 0.27, 2, -0.002, 90.0),
             K(29.0, 0.015, 20.0, 0.265, 0.265, 1, 0.0, 90.0), K(30.0, 0.0, 3.6, 0.26, 0.26, 0, 0.0, 90.0),
             K(30.5, 0.0, 0.0, 0.26, 0.26, 0, 0.0, 90.0), K(33.0, 0.0, 0.0, 0.26, 0.26, 0, 0.0, 90.0)]
    P = {p.id: p for p in ph}
    P["pad"].shots = [shot("fixed", 0.0, 1.0, target="stack", anchor="pad", offset=[-60.0, 4.0, -90.0], fov=60.0)]
    P["liftoff"].shots = [shot("chase", 0.0, 1.0, target="stack", offset=[0.0, -40.0, -80.0], fov=60.0, shake=0.3)]
    P["booster_return"].shots = [
        shot("fixed", 0.0, 0.5, target="booster", anchor="pad", offset=[-80.0, 4.0, -120.0], fov=55.0),
        shot("onboard", 0.5, 1.0, target="ship", offset=[4.9, 2.0, 0.0], look=[0.0, -1.0, 0.1], fov=75.0)]
    P["transfer"].shots = [shot("cabin", 0.0, 1.0, fov=70.0)]
    P["descent"].shots = [shot("fixed", 0.0, 1.0, target="ship", anchor="landing_site", offset=[-60.0, 2.0, -90.0],
                               fov=55.0)]
    P["landed"].shots = [shot("orbit", 0.0, 1.0, target="ship", radius=60.0, height=8.0, speed=20.0, fov=60.0)]
    return {
        "destination": "redplanet:mars",
        "vehicle": "stack",
        "launch_azimuth_deg": 90.0,
        "phases": [p.as_json() for p in ph],
        "ship": [q.as_json() for q in ship],
        "booster": [q.as_json() for q in booster],
        "shots": resolve_shots(ph),
        "interlude": {"from": "earth", "to": "mars", "transfer_days": 182.0, "departure_dv_km_s": 3.6,
                      "arrival_speed_km_s": 7.5, "tanker_flights": 0},
    }
