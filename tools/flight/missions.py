"""Runs the four simulations with their final settings and closes the propellant budget of both trips.

Budget logic (all masses in kg):
  Earth -> Mars: the ship reaches LEO with what the ascent leaves; tankers then load exactly what the trans-Mars
  injection (3.6 km/s on the three vacuum Raptors) needs to arrive at Mars with the landing propellant; the landing
  propellant is iterated until the ship touches down with LANDING_RESERVE left.
  Mars -> Earth: ISRU loads exactly what the direct-return ascent needs to arrive at Earth with the Earth landing
  propellant (iterated the same way). Trajectory-correction burns are not modelled.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

from scipy.optimize import brentq

from models import EARTH, G0, MARS, RAPTOR_VAC, SHIP_PROPELLANT, VehicleMasses
from scenarios import (EarthAscent, EarthAscentTargets, EntryConfig, EntryLanding, MarsAscent, MarsAscentConfig)

LANDING_RESERVE = 10.0e3  # kg left in the tanks at touchdown (both planets)
TANKER_DELIVERY = 100.0e3  # kg of propellant per tanker flight (SpaceX: "100+ t" payload to LEO, FC section 21)
TMI_DV = 3600.0  # m/s (Kingdon 2025, FC section 23; SCIENCE section 19)
MARS_ENTRY_SPEED = 7500.0  # m/s (SpaceX Mars page, FC section 23)
EARTH_ENTRY_SPEED = 12000.0  # m/s (fast return, FC section 23; SCIENCE section 19)
TRANSFER_DAYS = 182.0  # SCIENCE section 19 (game value)
RETURN_V_INF = 3500.0  # m/s hyperbolic excess leaving Mars (FC appendix: 5.88 km/s ideal from the surface)


def mars_entry_config(m: VehicleMasses, prop: float, dt: float) -> EntryConfig:
    return EntryConfig(
        planet=MARS, v_ei=MARS_ENTRY_SPEED, gamma_ei_deg=-11.5, m_dry=m.ship_dry + m.payload_out, prop=prop,
        drag_ref_g=3.0, density_scale_height=11000.0, drag_knee=3000.0, alpha_mach=(6.0, 4.0),
        arc_throttle=(0.80, 0.75, 0.55), arc_tops=(60.0, 400.0), brake_throttle=0.85, dt=dt)


def earth_entry_config(m: VehicleMasses, prop: float, dt: float) -> EntryConfig:
    return EntryConfig(
        planet=EARTH, v_ei=EARTH_ENTRY_SPEED, gamma_ei_deg=-6.0, m_dry=m.ship_dry + m.payload_back, prop=prop,
        drag_ref_g=3.5, density_scale_height=7000.0, drag_knee=6000.0, alpha_mach=(6.0, 4.0),
        arc_throttle=(0.80, 0.75, 0.90), arc_tops=(25.0, 120.0), flip_altitude=520.0, dt=dt)


@dataclass
class Descent:
    sim: EntryLanding
    track: object
    prop_ei: float
    h_ignition: float


def solve_descent(make_cfg, m: VehicleMasses, prop_guess: float, h_lo: float, h_hi: float, dt: float) -> Descent:
    """Iterate the propellant carried to the entry interface until touchdown leaves LANDING_RESERVE."""
    prop = prop_guess
    for _ in range(8):
        sim = EntryLanding(make_cfg(m, prop, dt))
        h_ign = sim.solve_ignition(h_lo, h_hi)
        f, tr = sim.run(h_ign, record=True)
        used = prop - tr.info["prop_left"]
        new = used + LANDING_RESERVE
        if abs(new - prop) < 20.0:
            prop = new
            break
        prop = new
    sim = EntryLanding(make_cfg(m, prop, dt))
    h_ign = sim.solve_ignition(h_lo, h_hi)
    f, tr = sim.run(h_ign, record=True)
    return Descent(sim, tr, prop, h_ign)


@dataclass
class Missions:
    masses: VehicleMasses = field(default_factory=VehicleMasses)
    dt: float = 0.05

    def run(self, log=print):
        m = self.masses
        # (a) Earth ascent.
        log("Earth ascent: calibrating the stack, solving the ship and booster guidance ...")
        self.ascent = EarthAscent(m, EarthAscentTargets(), dt=self.dt)
        self.stack_track, self.ship_track, self.booster_track = self.ascent.run()
        self.seco_prop = self.ascent.ship_result["prop"]

        # (b) Mars arrival: entry, belly flop, flip and landing burn.
        log("Mars descent: solving the ignition and the landing propellant ...")
        self.mars = solve_descent(mars_entry_config, m, 90.0e3, 1500.0, 11000.0, self.dt)

        # (d) Earth return: entry and landing.
        log("Earth descent: solving the ignition and the landing propellant ...")
        self.earth = solve_descent(earth_entry_config, m, 30.0e3, 550.0, 2000.0, self.dt)

        # (c) Mars ascent: load exactly what reaches the return hyperbola with the Earth landing propellant.
        log("Mars ascent: solving the guidance and the ISRU load ...")
        target = self.earth.prop_ei

        def residual(load):
            sim = MarsAscent(MarsAscentConfig(m.ship_dry + m.payload_back, prop=load, v_inf=RETURN_V_INF,
                                              h_cutoff=100.0e3, dt=self.dt))
            sim.solve()
            f, r, tr = sim.run(*sim.params, record=False)
            return r["prop"] - target

        self.mars_load = brentq(residual, 700.0e3, SHIP_PROPELLANT, xtol=50.0)
        self.mars_ascent = MarsAscent(MarsAscentConfig(m.ship_dry + m.payload_back, prop=self.mars_load,
                                                       v_inf=RETURN_V_INF, h_cutoff=100.0e3, dt=self.dt))
        self.mars_ascent.solve()
        _, self.mars_ascent_result, self.mars_ascent_track = self.mars_ascent.run(*self.mars_ascent.params,
                                                                                  record=True)

        # Refilling in low Earth orbit and the trans-Mars injection on the three vacuum Raptors.
        c_vac = RAPTOR_VAC.isp(0.0) * G0
        m_arrival = m.ship_dry + m.payload_out + self.mars.prop_ei
        m_before = m_arrival * math.exp(TMI_DV / c_vac)
        self.tmi_prop_before = m_before - m.ship_dry - m.payload_out
        self.tmi_burn = (m_before - m_arrival) / (3 * RAPTOR_VAC.mdot)
        delivered = self.tmi_prop_before - self.seco_prop
        self.tankers = math.ceil(delivered / TANKER_DELIVERY - 1e-9)
        self.per_tanker = delivered / self.tankers
        log("done.")
        return self
