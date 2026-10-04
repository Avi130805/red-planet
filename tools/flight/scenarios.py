"""The four flight simulations: Earth ascent (stack, ship, booster return), entry and landing (Mars, Earth), Mars ascent.

Each scenario returns dense samples (every integration step) plus the mission times of its events. Calibration and
guidance targets are solved numerically (scipy.optimize) so that the published event times are met exactly.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np
from scipy.optimize import brentq, least_squares

from flightsim import Control, Flight, Sample, lerp, smoothstep
from models import (BOOSTER_PROPELLANT, EARTH, G0, HULL_AREA, MARS, RAPTOR_SL, RAPTOR_VAC, SHIP_PLANFORM,
                    SHIP_PROPELLANT, Planet, VehicleMasses, booster_descent_cd_area, cd_ascent, cd_booster_descent,
                    entry_alpha, ship_aero)

DEG = math.pi / 180.0


@dataclass
class Track:
    """Dense samples of one vehicle plus metadata."""

    samples: list = field(default_factory=list)
    events: dict = field(default_factory=dict)  # name -> mission time (s)
    info: dict = field(default_factory=dict)  # scalar results for the report

    def add(self, s: Sample):
        self.samples.append(s)


def ship_engines(n_sl: int, n_vac: int, throttle: float, p_amb: float) -> tuple[float, float]:
    f = n_sl * RAPTOR_SL.thrust(throttle, p_amb) + n_vac * RAPTOR_VAC.thrust(throttle, p_amb)
    md = (n_sl * RAPTOR_SL.mdot + n_vac * RAPTOR_VAC.mdot) * throttle
    return f, md


def peak_time(ts, ys) -> tuple[float, float]:
    """Time and value of the maximum of a sampled curve, refined by a parabola through the top three samples."""
    k = int(np.argmax(ys))
    if 0 < k < len(ys) - 1:
        y0, y1, y2 = ys[k - 1], ys[k], ys[k + 1]
        den = y0 - 2.0 * y1 + y2
        if den < 0.0:
            d = 0.5 * (y0 - y2) / den
            dt = ts[k + 1] - ts[k]
            return ts[k] + d * dt, y1 - 0.25 * (y0 - y2) * d
    return ts[k], ys[k]


# =================================================================================================================
# (a) Earth ascent: Super Heavy + Starship, then the ship to orbit and the booster back to the tower
# =================================================================================================================


@dataclass
class EarthAscentTargets:
    """SCIENCE.md section 19 (FC section 22): V3 event times and webcast values."""

    max_q: float = 52.0
    meco: float = 140.0
    hot_staging: float = 143.0
    staging_alt: float = 68.0e3
    staging_speed: float = 5300.0 / 3.6
    boostback_start: float = 147.0
    boostback_end: float = 195.0
    booster_landing_burn: float = 390.0
    booster_catch: float = 415.0
    catch_alt: float = 100.0  # the tower arms catch the booster about 100 m up
    seco: float = 510.0
    orbit_alt: float = 200.0e3
    t_vertical: float = 10.0  # vertical rise until the stack has cleared the launch tower
    t_kick: float = 10.0
    bucket_throttle: float = 0.70  # throttle bucket through max-Q (as Falcon 9 flies)
    bucket_length: float = 16.0
    throttle_ramp: float = 3.0
    ship_g_limit: float = 4.0  # crew acceleration limit on the ship's ascent burn (g)
    booster_flip_throttle: float = 0.6
    landing_stage_a: float = 6.0  # s on 13 engines before stepping down to 3
    landing_vb: float = 95.0  # m/s at the 13 -> 3 engine step


class EarthAscent:
    def __init__(self, masses: VehicleMasses, targets: EarthAscentTargets | None = None, dt: float = 0.05):
        self.m = masses
        self.k = targets or EarthAscentTargets()
        self.dt = dt
        self.m_ship = masses.ship_dry + masses.payload_out + SHIP_PROPELLANT

    # ----- stack (liftoff to hot staging) ---------------------------------------------------------------------
    def throttle(self, t: float, t_bucket: float, throttle_up: float) -> float:
        k = self.k
        r = k.throttle_ramp
        if t < t_bucket:
            return 1.0
        if t < t_bucket + r:
            return lerp(1.0, k.bucket_throttle, (t - t_bucket) / r)
        if t < t_bucket + k.bucket_length:
            return k.bucket_throttle
        return lerp(k.bucket_throttle, throttle_up, min(1.0, (t - t_bucket - k.bucket_length) / r))

    def stack(self, kick_deg: float, t_bucket: float, throttle_up: float, record: bool = False):
        k, m = self.k, self.m
        f = Flight(EARTH, mass=m.booster_dry + BOOSTER_PROPELLANT + self.m_ship)
        track = Track()
        qs, ts = [], []
        pitch = math.pi / 2
        n_eng, tau = 33, 1.0
        while f.t < k.hot_staging - 1e-9:
            t = f.t
            dt = min(self.dt, k.hot_staging - t)
            h, s, vx, vz = f.local()
            v = math.hypot(vx, vz)
            gam = math.atan2(vz, vx) if v > 1.0 else math.pi / 2
            if t < k.t_vertical - 1e-6:
                pitch = math.pi / 2
            elif t < k.t_vertical + k.t_kick - 1e-6:
                pitch = (90.0 - kick_deg * smoothstep((t - k.t_vertical) / k.t_kick)) * DEG
            else:
                pitch = min((90.0 - kick_deg) * DEG, gam)  # then a zero-angle-of-attack gravity turn
            rho, p, temp, a = EARTH.atmosphere(h)
            if t < k.meco - 1e-6:
                n_eng = 33
                tau = self.throttle(t, t_bucket, throttle_up)
            else:
                n_eng, tau = 3, 1.0  # the three centre engines keep the stack under thrust through hot staging
            c = Control(thrust=n_eng * RAPTOR_SL.thrust(tau, p), mdot=n_eng * RAPTOR_SL.mdot * tau, pitch=pitch,
                        cd_area=cd_ascent(v / a) * HULL_AREA)
            if record:
                track.add(f.sample(c, n_eng, throttle=tau))
            qs.append(0.5 * rho * v * v)
            ts.append(t)
            f.step(c, dt)
        h, s, vx, vz = f.local()
        tq, q = peak_time(ts, qs)
        prop = f.y[4] - self.m_ship - m.booster_dry
        return f, pitch, dict(h=h, s=s, v=math.hypot(vx, vz), gamma=math.atan2(vz, vx), t_maxq=tq, maxq=q,
                              booster_prop=prop), track

    def calibrate_stack(self):
        k = self.k

        def res(x):
            _, _, r, _ = self.stack(*x)
            return [(r["h"] - k.staging_alt) / 500.0, (r["v"] - k.staging_speed) / 10.0, (r["t_maxq"] - k.max_q) / 0.5]

        sol = least_squares(res, [2.46, 50.4, 0.905], bounds=([0.5, 30.0, 0.45], [15.0, 80.0, 1.0]),
                            diff_step=[1e-3, 1e-3, 1e-3], xtol=1e-10, ftol=1e-10)
        self.stack_params = sol.x
        return sol

    # ----- ship (hot staging to SECO) -------------------------------------------------------------------------
    def ship(self, f0: Flight, pitch0: float, a: float, b: float, tau: float, record: bool = False, blend: float = 8.0):
        k, m = self.k, self.m
        f = Flight(EARTH)
        f.y = f0.y.copy()
        f.t = f0.t
        f.y[4] = self.m_ship
        m_dry = m.ship_dry + m.payload_out
        r_t = EARTH.radius + k.orbit_alt
        e_t = -EARTH.mu / (2.0 * r_t)
        t0 = f.t
        track = Track()
        pitch = pitch0
        c = Control()

        def energy(y):
            h, s, vx, vz = f.local(y)
            return 0.5 * (vx * vx + vz * vz) - EARTH.mu / (EARTH.radius + h)

        while True:
            t = f.t
            h, s, vx, vz = f.local()
            v = math.hypot(vx, vz)
            pg = math.atan(a + b * (t - t0))  # linear-tangent steering
            pitch = pitch0 + (pg - pitch0) * smoothstep((t - t0) / blend)
            rho, p, temp, snd = EARTH.atmosphere(h)
            thrust, md = ship_engines(3, 3, tau, p)
            if thrust > k.ship_g_limit * G0 * f.y[4]:
                scale = k.ship_g_limit * G0 * f.y[4] / thrust
                thrust *= scale
                md *= scale
            c = Control(thrust=thrust, mdot=md, pitch=pitch, cd_area=cd_ascent(v / snd) * HULL_AREA)
            if record:
                track.add(f.sample(c, 6, throttle=md / ((3 * RAPTOR_SL.mdot + 3 * RAPTOR_VAC.mdot))))
            y_prev, t_prev = f.y.copy(), f.t
            f.step(c, self.dt)
            if energy(f.y) >= e_t:
                # Cut off exactly at the target energy: bisect the last step.
                lo, hi = 0.0, self.dt
                for _ in range(40):
                    mid = 0.5 * (lo + hi)
                    f.y, f.t = y_prev.copy(), t_prev
                    f.step(c, mid)
                    if energy(f.y) >= e_t:
                        hi = mid
                    else:
                        lo = mid
                f.y, f.t = y_prev.copy(), t_prev
                f.step(c, hi)
                break
            if f.y[4] <= m_dry or f.t > 1200.0:
                break
        h, s, vx, vz = f.local()
        res = dict(t=f.t, h=h, s=s, v=math.hypot(vx, vz), gamma=math.atan2(vz, vx), prop=f.y[4] - m_dry,
                   reached=energy(f.y) >= e_t - 1.0)
        if record:
            track.add(f.sample(Control(pitch=pitch), 0))
        return f, res, track

    def solve_ship(self, f0, pitch0):
        k = self.k

        def res(x):
            _, r, _ = self.ship(f0, pitch0, *x)
            pen = 0.0 if r["reached"] else 100.0
            return [(r["h"] - k.orbit_alt) / 200.0 + pen, r["gamma"] / (0.02 * DEG), (r["t"] - k.seco) / 0.2]

        sol = least_squares(res, [0.63, -0.0017, 0.99], bounds=([-3.0, -0.05, 0.4], [5.0, 0.05, 1.0]),
                            diff_step=[1e-4, 1e-3, 1e-4], xtol=1e-12, ftol=1e-12)
        self.ship_params = sol.x
        return sol

    # ----- booster return ------------------------------------------------------------------------------------
    def booster(self, f0: Flight, pitch0: float, prop0: float, pitch_bb_deg: float, tau_bb: float,
                record: bool = False, stop_at: float | None = None):
        k, m = self.k, self.m
        f = Flight(EARTH)
        f.y = f0.y.copy()
        f.t = f0.t
        f.y[4] = m.booster_dry + prop0
        track = Track()
        pitch_bb = pitch_bb_deg * DEG
        pitch = pitch0
        pitch_coast_start = pitch_bb
        landing = None
        end = k.booster_catch if stop_at is None else stop_at
        while f.t < end - 1e-9:
            t = f.t
            dt = min(self.dt, end - t)
            h, s, vx, vz = f.local()
            v = math.hypot(vx, vz)
            rho, p, temp, snd = EARTH.atmosphere(max(h, 0.0))
            q = 0.5 * rho * v * v
            # Grid fins deploy after the boostback.
            cd_area = (booster_descent_cd_area(v / snd) if t >= k.boostback_end else
                       cd_booster_descent(v / snd) * HULL_AREA)
            n, tau = 0, 0.0
            if t < k.boostback_start - 1e-6:  # flip on the three centre engines
                w = smoothstep((t - k.hot_staging) / (k.boostback_start - k.hot_staging))
                pitch = lerp(pitch0, pitch_bb, w)
                n, tau = 3, k.booster_flip_throttle
            elif t < k.boostback_end - 1e-6:
                pitch = pitch_bb
                n, tau = 13, tau_bb
            elif t < k.booster_landing_burn - 1e-6:
                # Reorient engines-down on cold-gas thrusters, then let the airflow align it engines-first.
                w = smoothstep((t - k.boostback_end) / 20.0)
                hold = lerp(pitch_coast_start, 90.0 * DEG, w)
                aero = math.atan2(-vz, -vx) if v > 1.0 else 90.0 * DEG
                wq = smoothstep((q - 100.0) / 1900.0)
                pitch = lerp(hold, aero, wq)
            else:
                if landing is None:
                    landing = LandingGuidance(self, f, pitch)
                n, tau, pitch = landing.command(f)
            c = Control(thrust=n * RAPTOR_SL.thrust(tau, p), mdot=n * RAPTOR_SL.mdot * tau, pitch=pitch,
                        cd_area=cd_area)
            if record:
                track.add(f.sample(c, n, throttle=tau))
            f.step(c, dt)
        h, s, vx, vz = f.local()
        if record:
            track.add(f.sample(Control(pitch=90.0 * DEG), 0))
        return f, dict(h=h, s=s, vx=vx, vz=vz, prop=f.y[4] - m.booster_dry), track

    def landing_ignition_altitude(self, v0: float) -> float:
        """Altitude at which the 25 s two-stage landing burn must start from a fall at v0 (constant-deceleration arcs)."""
        k = self.k
        t_a = k.landing_stage_a
        t_b = (k.booster_catch - k.booster_landing_burn) - t_a
        return k.catch_alt + 0.5 * k.landing_vb * t_b + 0.5 * (v0 + k.landing_vb) * t_a

    def solve_booster(self, f0, pitch0, prop0):
        k = self.k

        def res(x):
            f, r, _ = self.booster(f0, pitch0, prop0, *x, stop_at=k.booster_landing_burn)
            v0 = math.hypot(r["vx"], r["vz"])
            h_req = self.landing_ignition_altitude(v0)
            s_req = -r["vx"] * 0.5 * k.landing_stage_a  # leave room for the drift while the horizontal speed is nulled
            return [(r["h"] - h_req) / 20.0, (r["s"] - s_req) / 20.0]

        sol = least_squares(res, [193.0, 0.48], bounds=([120.0, 0.4], [260.0, 1.0]), diff_step=[1e-5, 1e-4],
                            xtol=1e-12, ftol=1e-12)
        self.booster_params = sol.x
        return sol

    # ----- everything -----------------------------------------------------------------------------------------
    def run(self):
        self.calibrate_stack()
        f_stage, pitch_stage, stack_res, stack_track = self.stack(*self.stack_params, record=True)
        self.stack_result = stack_res
        self.solve_ship(f_stage, pitch_stage)
        _, ship_res, ship_track = self.ship(f_stage, pitch_stage, *self.ship_params, record=True)
        self.ship_result = ship_res
        prop0 = stack_res["booster_prop"]
        self.solve_booster(f_stage, pitch_stage, prop0)
        _, boost_res, boost_track = self.booster(f_stage, pitch_stage, prop0, *self.booster_params, record=True)
        self.booster_result = boost_res
        return stack_track, ship_track, boost_track


class LandingGuidance:
    """Super Heavy's landing burn: 13 engines to a gate, then 3 engines to the tower arms at a fixed time.

    Both stages use fixed-final-time minimum-energy (linear acceleration) guidance on a double integrator:
    a = 6 (r_f - r - v t_go) / t_go^2 - 2 (v_f - v) / t_go, plus gravity compensation; drag is measured and added.
    """

    def __init__(self, ascent: EarthAscent, f: Flight, pitch: float):
        self.a = ascent
        k = ascent.k
        self.t0 = f.t
        self.t_gate = f.t + k.landing_stage_a
        self.t_end = k.booster_catch
        t_b = self.t_end - self.t_gate
        self.h_gate = k.catch_alt + 0.5 * k.landing_vb * t_b
        self.pitch0 = pitch

    def command(self, f: Flight):
        k = self.a.k
        h, s, vx, vz = f.local()
        r = f.planet.radius + h
        g = f.planet.mu / (r * r)
        m = f.y[4]
        v = math.hypot(vx, vz)
        rho, p, temp, snd = f.planet.atmosphere(max(h, 0.0))
        drag = 0.5 * rho * v * v * booster_descent_cd_area(v / snd) / m
        dxd = -drag * vx / v if v > 0.1 else 0.0
        dzd = -drag * vz / v if v > 0.1 else 0.0
        if f.t < self.t_gate - 1e-6:
            n = 13
            tgo = max(self.t_gate - f.t, 0.05)
            az = 6.0 * (self.h_gate - h - vz * tgo) / tgo ** 2 - 2.0 * (-k.landing_vb - vz) / tgo
            ax = -vx / max(tgo, 1.0)
        else:
            n = 3
            tgo = max(self.t_end - f.t, 0.05)
            if tgo > 0.6:
                az = 6.0 * (k.catch_alt - h - vz * tgo) / tgo ** 2 - 2.0 * (0.0 - vz) / tgo
                ax = 6.0 * (0.0 - s - vx * tgo) / tgo ** 2 - 2.0 * (0.0 - vx) / tgo
            else:
                az = -vz / tgo
                ax = -vx / tgo
        tz = (az + g - dzd) * m
        tx = (ax - dxd) * m
        rho, p, temp, snd = f.planet.atmosphere(max(h, 0.0))
        thrust = math.hypot(tx, tz)
        tau = min(1.0, max(RAPTOR_SL.min_throttle, (thrust + n * p * RAPTOR_SL.exit_area) / (n * RAPTOR_SL.thrust_vac)))
        pitch = math.atan2(tz, tx)
        w = smoothstep((f.t - self.t0) / 1.5)
        return n, tau, lerp(self.pitch0, pitch, w)


# =================================================================================================================
# (b, d) Entry, belly flop, flip and landing burn (Mars arrival, Earth return)
# =================================================================================================================


@dataclass
class EntryConfig:
    planet: Planet
    v_ei: float  # m/s at the entry interface
    gamma_ei_deg: float  # flight-path angle at the entry interface
    m_dry: float  # ship dry mass + payload (kg)
    prop: float  # propellant at the entry interface (kg)
    drag_ref_g: float  # drag deceleration the bank guidance holds during the hypersonic glide (Earth g)
    density_scale_height: float  # m, used by the drag-tracking guidance
    drag_knee: float = 4000.0  # m/s: below this speed the ship rolls to full lift up to stay high
    alpha_mach: tuple = (8.0, 4.0)  # angle of attack 70 deg above the first Mach number, 90 deg (belly flop) below the second
    h_ei: float = 125.0e3
    k_h: float = 0.10  # 1/s, sink-rate loop gain
    k_drag: float = 0.05  # 1/s, drag loop gain
    plasma_flux: float = 1.0e5  # W/m^2 (10 W/cm^2): the shock layer glows above this stagnation heat flux
    v_touchdown: float = 1.0  # m/s
    # Powered descent: throttle of each engine-count arc and the arc tops (m above the site).
    arc_throttle: tuple = (0.80, 0.75, 0.55)  # 3, 2, 1 engines
    arc_tops: tuple = (60.0, 400.0)  # top of the 1-engine arc, top of the 2-engine arc
    lead: float = 2.0  # s from ignition to the flip call (Flights 11-13: burn starts ~2 s before the flip)
    rotation: float = 5.0  # s for the whole rotation from the belly-flop attitude onto the braking direction
    lead_throttle: float = 0.5
    brake_throttle: float = 0.85  # throttle the braking guidance needs when the flip ends (sets the ignition)
    flip_altitude: float | None = None  # if set, ignite so the flip call (lead seconds later) comes at this altitude
    tilt_limit_deg: float = 20.0
    max_rate_deg: float = 15.0  # attitude rate limit in the final descent (deg/s)
    dt: float = 0.05


class EntryLanding:
    """Entry interface to touchdown: lifting entry, belly flop, flip, braking burn and the 3 -> 2 -> 1 engine descent.

    Phases of `run`: "entry" (bank guidance), then from ignition "flip" (rotation onto the braking direction), "brake"
    (fixed-final-time guidance to the gate at the top of the 2-engine arc) and "arcs" (constant-deceleration arcs on
    2 and then 1 engine to touchdown). The landing site is wherever the ship touches down.
    """

    def __init__(self, cfg: EntryConfig):
        self.c = cfg
        self.p = cfg.planet

    # ----- powered-descent reference profile -----------------------------------------------------------------
    def arcs(self, m: float):
        """Deceleration of each arc (m/s^2) at mass m, ignoring drag, and the speeds at the arc tops."""
        c, p = self.c, self.p
        g = p.g(p.radius)
        p_amb = p.atmosphere(0.0)[1]
        acc = []
        for n, tau in zip((3, 2, 1), c.arc_throttle):
            acc.append(n * RAPTOR_SL.thrust(tau, p_amb) / m - g)
        a3, a2, a1 = acc
        h1, h2 = c.arc_tops
        v1 = math.sqrt(c.v_touchdown ** 2 + 2.0 * a1 * h1)
        v2 = math.sqrt(v1 * v1 + 2.0 * a2 * (h2 - h1))
        return (a3, a2, a1), (v1, v2)

    def v_ref(self, h: float, m: float) -> tuple[float, float, int]:
        """Reference descent speed (m/s, downward positive), its deceleration and the engine count at altitude h."""
        c = self.c
        (a3, a2, a1), (v1, v2) = self.arcs(m)
        h1, h2 = c.arc_tops
        h = max(h, 0.0)
        if h <= h1:
            return math.sqrt(c.v_touchdown ** 2 + 2.0 * a1 * h), a1, 1
        if h <= h2:
            return math.sqrt(v1 * v1 + 2.0 * a2 * (h - h1)), a2, 2
        return math.sqrt(v2 * v2 + 2.0 * a3 * (h - h2)), a3, 3

    # ----- simulation -----------------------------------------------------------------------------------------
    def run(self, h_ignition: float | None, record: bool = True, stop_at: str | None = None):
        c, pl = self.c, self.p
        g_e = G0
        v0 = c.v_ei
        gam0 = c.gamma_ei_deg * DEG
        f = Flight(pl, h0=c.h_ei, v_fwd=v0 * math.cos(gam0), v_up=v0 * math.sin(gam0), mass=c.m_dry + c.prop)
        track = Track()
        ev = track.events
        info = track.info
        d_ref = c.drag_ref_g * g_e
        phase = "entry"
        t_ign = None
        pitch_bf = 0.0
        peak = (0.0, 0.0)
        maxg = (0.0, 0.0)
        u = -1.0
        pitch = 0.0
        t_knee, u_knee = None, -1.0
        t_gate = None
        t_hand, tilt_hand = None, 0.0
        h_hand, v_hand, n_eng_arcs = 0.0, 0.0, 3
        while True:
            t = f.t
            h, s, vx, vz = f.local()
            v = math.hypot(vx, vz)
            gam = math.atan2(vz, vx)
            r = pl.radius + h
            g = pl.mu / (r * r)
            rho, p_amb, temp, snd = pl.atmosphere(max(h, 0.0))
            mach = v / snd
            q = 0.5 * rho * v * v
            m = f.y[4]
            n, tau = 0, 0.0
            # The ignition is armed once the belly flop has begun (the altitude dips and recovers during the glide).
            if phase == "entry" and h_ignition is not None and "belly_flop" in ev and h <= h_ignition:
                phase = "flip"
                t_ign = t
                pitch_bf = pitch
                ev["landing_burn"] = t
                ev["flip"] = t + c.lead
            if phase == "entry":
                alpha = entry_alpha(mach, *c.alpha_mach)
                cd, cl = ship_aero(mach, alpha)
                drag = q * cd * SHIP_PLANFORM / m
                lift = q * cl * SHIP_PLANFORM / m
                # Planar bank guidance, cos(bank) = u in [-1, 1]. While fast it holds the drag at d_ref: the sink rate
                # that drives the drag toward d_ref (D = const gives hdot = -2 H D / v, plus feedback) is held by
                # feedback linearisation of the radial acceleration. Below the knee speed the ship rolls to full lift
                # up to stay high while it slows.
                if drag > 1e-6:
                    hdot_ref = c.density_scale_height * (c.k_drag * (drag - d_ref) / drag - 2.0 * drag / max(v, 1.0))
                else:
                    hdot_ref = -1.0e4
                hdot_ref = min(100.0, max(-2000.0, hdot_ref))
                rdd = c.k_h * (hdot_ref - vz)
                need = rdd - vx * vx / r + g + drag * math.sin(gam)
                if lift * math.cos(gam) > 1e-6:
                    u_track = min(1.0, max(-1.0, need / (lift * math.cos(gam))))
                else:
                    u_track = -1.0 if need < 0 else 1.0
                if v > c.drag_knee:
                    u = u_track
                else:
                    if t_knee is None:
                        t_knee, u_knee = t, u
                    u = lerp(u_knee, 1.0, smoothstep((t - t_knee) / 20.0))
                pitch = gam + alpha  # the 2D attitude shows the trim angle of attack; the bank is not drawn
                ctrl = Control(pitch=pitch, cd_area=cd * SHIP_PLANFORM, cl_area=cl * SHIP_PLANFORM, lift_up=u)
            else:
                dt_burn = t - t_ign
                alpha_now = pitch - gam
                cd, cl = ship_aero(mach, min(math.pi, abs(alpha_now)))
                drag_acc = q * cd * SHIP_PLANFORM / m
                dz = -drag_acc * vz / v if v > 0.1 else 0.0
                dx = -drag_acc * vx / v if v > 0.1 else 0.0
                vref, aref, n_arc = self.v_ref(h, m)
                if phase in ("flip", "brake"):
                    if phase == "flip":
                        tgo = self.brake_tgo(h, vz, m)
                    else:
                        tgo = t_gate - t
                    n_cmd, tau_cmd, p_cmd, ok = self.brake_command(h, vx, vz, m, g, p_amb, dx, dz, tgo)
                    if phase == "flip":
                        # Rotate from the belly-flop attitude onto the braking direction as the engines spool up.
                        w = smoothstep(dt_burn / c.rotation)
                        pitch = lerp(pitch_bf, p_cmd, w)
                        n = 3
                        tau = c.lead_throttle if dt_burn < c.lead else lerp(
                            c.lead_throttle, tau_cmd, smoothstep((dt_burn - c.lead) / (c.rotation - c.lead)))
                        if dt_burn >= c.rotation:
                            phase = "brake"
                            ev["flip_end"] = t
                            t_gate = t + tgo
                            info["brake_throttle0"] = tau_cmd_raw(self, h, vx, vz, m, g, p_amb, dx, dz, tgo)
                            if stop_at == "flip":
                                break
                    else:
                        n, tau = n_cmd, tau_cmd
                        pitch = lerp(pitch, p_cmd, f_dt_blend(c.dt, 0.3))
                        # Hand over when the remaining horizontal speed is about what the rotation to vertical (at the
                        # attitude rate limit) will remove, so the ship arrives upright with no horizontal speed.
                        tilt = abs(pitch - 90.0 * DEG)
                        a_h = n * RAPTOR_SL.thrust(tau, p_amb) / m * abs(math.cos(pitch))
                        v_rot = 0.5 * a_h * tilt / (c.max_rate_deg * DEG)
                        if abs(vx) <= v_rot or tgo < 1.0 or h < c.arc_tops[1]:
                            phase = "arcs"
                            ev["brake_end"] = t
                            info["brake_end"] = (h, vx, vz)
                            t_hand, tilt_hand = t, tilt
                            h_hand, v_hand = h, -vz
                            n_eng_arcs = n
                            if stop_at == "brake":
                                break
                if phase == "arcs":
                    if h > c.arc_tops[1] and t_hand is not None:
                        # Above the gate: a straight line in (h, v) from the handover state to the gate.
                        v2 = self.arcs(m)[1][1]
                        slope = max(0.0, (v_hand - v2) / max(h_hand - c.arc_tops[1], 1.0))
                        vref = v2 + slope * (h - c.arc_tops[1])
                        aref = vref * slope
                    az = aref + 1.0 * (-vz - vref)  # deceleration (upward) needed to follow the reference
                    tz = max(0.05 * m * g, m * (az + g - dz))  # never thrust downward
                    tx = m * (-vx / 2.5 - dx)
                    # The tilt allowance shrinks from the braking attitude to the final-descent limit at the rate limit.
                    tilt_max = max(c.tilt_limit_deg * DEG, tilt_hand - c.max_rate_deg * DEG * (t - t_hand)) \
                        if t_hand is not None else c.tilt_limit_deg * DEG
                    lim = math.tan(min(tilt_max, 85.0 * DEG)) * tz
                    tx = max(-lim, min(lim, tx))
                    thrust_need = math.hypot(tx, tz)

                    def throttle_for(k):
                        return (thrust_need + k * p_amb * RAPTOR_SL.exit_area) / (k * RAPTOR_SL.thrust_vac)

                    # Engines step down 3 -> 2 -> 1 (never back up unless the thrust is short): below each arc top,
                    # or earlier once the rotation is done if the minimum throttle would be too much.
                    n = min(n_eng_arcs, n_arc)
                    upright = abs(pitch - 90.0 * DEG) < 25.0 * DEG
                    while upright and n > 1 and throttle_for(n) < RAPTOR_SL.min_throttle:
                        n -= 1
                    while throttle_for(n) > 1.0 and n < 3:
                        n += 1
                    n_eng_arcs = n
                    tau = min(1.0, max(RAPTOR_SL.min_throttle, throttle_for(n)))
                    p_cmd = math.atan2(tz, tx)
                    step = c.max_rate_deg * DEG * c.dt
                    pitch += max(-step, min(step, p_cmd - pitch))
                alpha_now = pitch - gam
                cd, cl = ship_aero(mach, min(math.pi, abs(alpha_now)))
                ctrl = Control(thrust=n * RAPTOR_SL.thrust(tau, p_amb), mdot=n * RAPTOR_SL.mdot * tau, pitch=pitch,
                               cd_area=cd * SHIP_PLANFORM, cl_area=cl * SHIP_PLANFORM * (1.0 if alpha_now >= 0 else -1.0))
            smp = f.sample(ctrl, n, heat=True, throttle=tau, bank_cos=u if phase == "entry" else 1.0, phase=phase)
            if smp.heat > peak[0]:
                peak = (smp.heat, t)
            if smp.accel > maxg[0]:
                maxg = (smp.accel, t)
            if record:
                track.add(smp)
            # Events from the state.
            if "plasma_end" not in ev and peak[0] > c.plasma_flux and smp.heat < c.plasma_flux and t > peak[1]:
                ev["plasma_end"] = t
            if "belly_flop" not in ev and phase == "entry" and mach < c.alpha_mach[1]:
                ev["belly_flop"] = t
            if "subsonic" not in ev and mach < 1.0:
                ev["subsonic"] = t
            y_prev, t_prev = f.y.copy(), f.t
            f.step(ctrl, c.dt)
            if f.local()[0] <= 0.0:
                # Touchdown: bisect the last step to h = 0.
                lo, hi = 0.0, c.dt
                for _ in range(50):
                    mid = 0.5 * (lo + hi)
                    f.y, f.t = y_prev.copy(), t_prev
                    f.step(ctrl, mid)
                    if f.local()[0] <= 0.0:
                        hi = mid
                    else:
                        lo = mid
                f.y, f.t = y_prev.copy(), t_prev
                f.step(ctrl, lo)
                break
            if f.t > 4000.0:
                break
        h, s, vx, vz = f.local()
        ev["touchdown"] = f.t
        info.update(peak_heat=peak[0], peak_heat_t=peak[1], max_accel=maxg[0], max_accel_t=maxg[1],
                    touchdown_vz=vz, touchdown_vx=vx, prop_left=f.y[4] - c.m_dry, t_ignition=t_ign,
                    h_ignition=h_ignition)
        if record:
            track.add(f.sample(Control(pitch=pitch), 0, heat=True, throttle=0.0, bank_cos=1.0, phase="landed"))
        return f, track

    def brake_tgo(self, h: float, vz: float, m: float) -> float:
        """Time to the 2-engine gate for a constant vertical deceleration (the braking phase's time-to-go)."""
        hg = self.c.arc_tops[1]
        vg = self.arcs(m)[1][1]
        return max(1.0, 2.0 * (h - hg) / (max(-vz, 0.0) + vg))

    def brake_command(self, h, vx, vz, m, g, p_amb, dx, dz, tgo):
        """Braking-phase guidance on three engines: fixed-final-time minimum-energy guidance to the gate at the top of
        the 2-engine arc (position and vertical speed fixed, horizontal speed zero, horizontal position free)."""
        c = self.c
        hg = c.arc_tops[1]
        vg = self.arcs(m)[1][1]
        tgo = max(tgo, 0.05)
        az = 6.0 * (hg - h - vz * tgo) / tgo ** 2 - 2.0 * (-vg - vz) / tgo
        ax = -vx / tgo  # constant horizontal deceleration to the gate (horizontal position free)
        tz = max(0.0, m * (az + g - dz))  # never thrust downward
        tx = m * (ax - dx)
        thrust = math.hypot(tx, tz)
        n = 3
        tau = (thrust + n * p_amb * RAPTOR_SL.exit_area) / (n * RAPTOR_SL.thrust_vac)
        if tau < RAPTOR_SL.min_throttle:  # three engines at minimum would be too much: drop to two
            n = 2
            tau = (thrust + n * p_amb * RAPTOR_SL.exit_area) / (n * RAPTOR_SL.thrust_vac)
        return n, min(1.0, max(RAPTOR_SL.min_throttle, tau)), math.atan2(tz, tx), tau <= 1.0

    def solve_ignition(self, h_lo: float, h_hi: float) -> float:
        """Ignition altitude: the latest burn whose braking guidance needs `brake_throttle` when the flip ends, or
        (Earth) the burn that puts the flip call at the published flip altitude."""
        c = self.c
        if c.flip_altitude is not None:
            def err(h_ign):
                f, tr = self.run(h_ign, record=True, stop_at="flip")
                t_flip = tr.events["flip"]
                smp = min(tr.samples, key=lambda q: abs(q.t - t_flip))
                return smp.h - c.flip_altitude
        else:
            def err(h_ign):
                f, tr = self.run(h_ign, record=False, stop_at="flip")
                return tr.info.get("brake_throttle0", 2.0) - c.brake_throttle

        return brentq(err, h_lo, h_hi, xtol=0.01)


def tau_cmd_raw(el, h, vx, vz, m, g, p_amb, dx, dz, tgo):
    """Unclamped throttle the braking guidance asks for (for the ignition search)."""
    c = el.c
    hg = c.arc_tops[1]
    vg = el.arcs(m)[1][1]
    tgo = max(tgo, 0.05)
    az = 6.0 * (hg - h - vz * tgo) / tgo ** 2 - 2.0 * (-vg - vz) / tgo
    tz = m * (az + g - dz)
    tx = m * (-vx / tgo - dx)
    return (math.hypot(tx, tz) + 3 * p_amb * RAPTOR_SL.exit_area) / (3 * RAPTOR_SL.thrust_vac)


def f_dt_blend(dt: float, tau: float = 0.5) -> float:
    """First-order attitude response per step (time constant tau)."""
    return 1.0 - math.exp(-dt / tau)


# =================================================================================================================
# (c) Mars ascent: the ship alone, from the surface straight onto the Earth-return hyperbola
# =================================================================================================================


@dataclass
class MarsAscentConfig:
    m_dry: float  # ship dry mass + return payload
    prop: float = SHIP_PROPELLANT
    v_inf: float = 3500.0  # m/s hyperbolic excess speed of the Earth-return trajectory
    h_cutoff: float = 150.0e3  # injection altitude (horizontal burnout)
    t_vertical: float = 8.0
    pitch_blend: float = 25.0  # s over which the attitude eases from vertical onto the steering law (gentle pitch-over)
    g_limit: float = 4.0
    dt: float = 0.05


class MarsAscent:
    def __init__(self, cfg: MarsAscentConfig):
        self.c = cfg
        self.p = MARS

    def run(self, a: float, b: float, record: bool = False):
        c, pl = self.c, self.p
        blend = c.pitch_blend
        f = Flight(pl, mass=c.m_dry + c.prop)
        track = Track()
        e_t = 0.5 * c.v_inf ** 2  # specific orbital energy of the escape hyperbola
        pitch = 90.0 * DEG
        n_sl, n_vac = 3, 3
        qs, ts = [], []

        def energy(y):
            h, s, vx, vz = f.local(y)
            return 0.5 * (vx * vx + vz * vz) - pl.mu / (pl.radius + h)

        while True:
            t = f.t
            h, s, vx, vz = f.local()
            v = math.hypot(vx, vz)
            rho, p_amb, temp, snd = pl.atmosphere(max(h, 0.0))
            if t < c.t_vertical - 1e-6:
                pitch = 90.0 * DEG
            else:
                pg = math.atan(a + b * (t - c.t_vertical))  # linear-tangent steering
                pitch = lerp(90.0 * DEG, pg, smoothstep((t - c.t_vertical) / blend))
            thrust, md = ship_engines(n_sl, n_vac, 1.0, p_amb)
            if thrust > c.g_limit * G0 * f.y[4]:
                # Crew acceleration limit: throttle down, and shut the sea-level engines once even the minimum
                # throttle on six engines is too much.
                tau = c.g_limit * G0 * f.y[4] / thrust
                if tau < RAPTOR_SL.min_throttle and n_sl > 0:
                    n_sl = 0
                    thrust, md = ship_engines(0, n_vac, 1.0, p_amb)
                    tau = min(1.0, c.g_limit * G0 * f.y[4] / thrust)
                thrust, md = ship_engines(n_sl, n_vac, max(RAPTOR_SL.min_throttle, tau), p_amb)
            ctrl = Control(thrust=thrust, mdot=md, pitch=pitch, cd_area=cd_ascent(v / snd) * HULL_AREA)
            qs.append(0.5 * rho * v * v)
            ts.append(t)
            if record:
                track.add(f.sample(ctrl, n_sl + n_vac, throttle=md / (3 * RAPTOR_SL.mdot + 3 * RAPTOR_VAC.mdot)))
            y_prev, t_prev = f.y.copy(), f.t
            f.step(ctrl, c.dt)
            if energy(f.y) >= e_t:
                lo, hi = 0.0, c.dt
                for _ in range(40):
                    mid = 0.5 * (lo + hi)
                    f.y, f.t = y_prev.copy(), t_prev
                    f.step(ctrl, mid)
                    if energy(f.y) >= e_t:
                        hi = mid
                    else:
                        lo = mid
                f.y, f.t = y_prev.copy(), t_prev
                f.step(ctrl, hi)
                break
            if f.y[4] <= c.m_dry or f.t > 1500.0:
                break
        h, s, vx, vz = f.local()
        tq, q = peak_time(ts, qs)
        track.events.update(liftoff=0.0, max_q=tq, seco=f.t)
        res = dict(t=f.t, h=h, s=s, v=math.hypot(vx, vz), gamma=math.atan2(vz, vx), prop=f.y[4] - c.m_dry,
                   reached=energy(f.y) >= e_t - 1.0, max_q=q, t_max_q=tq)
        track.info.update(res)
        if record:
            track.add(f.sample(Control(pitch=pitch), 0, throttle=0.0))
        return f, res, track

    def solve(self):
        c = self.c

        def res(x):
            _, r, _ = self.run(*x)
            pen = 0.0 if r["reached"] else 100.0
            return [(r["h"] - c.h_cutoff) / 200.0 + pen, r["gamma"] / (0.02 * DEG)]

        sol = least_squares(res, [1.0, -0.004], diff_step=[1e-4, 1e-3], xtol=1e-12, ftol=1e-12)
        self.params = sol.x
        return sol
