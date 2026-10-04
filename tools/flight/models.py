"""Physical models for the Red Planet flight simulations: planets, atmospheres, engines, vehicles, aerodynamics.

Every constant here is documented in docs/SCIENCE.md section 19 (sources) and tools/flight/README.md. Short source
tags in the comments: FC = docs/api-notes/science-sources.md, SCI = docs/SCIENCE.md.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

G0 = 9.80665  # standard gravity (m/s^2), defines Isp

# ---------------------------------------------------------------------------------------------------------------
# Atmospheres
# ---------------------------------------------------------------------------------------------------------------

# U.S. Standard Atmosphere 1976 (NOAA-S/T 76-1562), 0-86 km: geopotential layers.
_R_STAR = 8.31432  # J/(mol K), USSA76 gas constant
_M0 = 0.0289644  # kg/mol, sea-level mean molar mass of air
_R0_GEOPOT = 6356766.0  # m, USSA76 effective Earth radius for geopotential altitude
_GMR = G0 * _M0 / _R_STAR  # K/m (34.163195 K/km)
_LAYERS = (  # (base geopotential altitude m', base temperature K, lapse rate K/m', base pressure Pa)
    (0.0, 288.15, -0.0065, 101325.0),
    (11000.0, 216.65, 0.0, 22632.06),
    (20000.0, 216.65, 0.001, 5474.889),
    (32000.0, 228.65, 0.0028, 868.0187),
    (47000.0, 270.65, 0.0, 110.9063),
    (51000.0, 270.65, -0.0028, 66.93887),
    (71000.0, 214.65, -0.002, 3.956420),
    (84852.0, 186.946, 0.0, 0.3733836),
)
# USSA76 tabulated values above 86 km (geometric km): density kg/m^3, pressure Pa, kinetic temperature K.
_UPPER = np.array([
    # km      rho          p            T
    [86.0, 6.958e-6, 3.7338e-1, 186.87],
    [90.0, 3.416e-6, 1.8359e-1, 186.87],
    [95.0, 1.393e-6, 7.5966e-2, 188.42],
    [100.0, 5.604e-7, 3.2011e-2, 195.08],
    [110.0, 9.708e-8, 7.1042e-3, 240.00],
    [120.0, 2.222e-8, 2.5382e-3, 360.00],
    [130.0, 8.152e-9, 1.2505e-3, 469.27],
    [140.0, 3.831e-9, 7.2028e-4, 559.63],
    [150.0, 2.076e-9, 4.5422e-4, 634.39],
    [160.0, 1.233e-9, 3.0395e-4, 696.29],
    [180.0, 5.194e-10, 1.5134e-4, 787.55],
    [200.0, 2.541e-10, 8.4736e-5, 854.56],
    [250.0, 6.073e-11, 2.4767e-5, 941.33],
    [300.0, 1.916e-11, 8.7704e-6, 976.01],
])
_UPPER_LOG_RHO = np.log(_UPPER[:, 1])
_UPPER_LOG_P = np.log(_UPPER[:, 2])


def ussa76(h: float) -> tuple[float, float, float, float]:
    """U.S. Standard Atmosphere 1976 at geometric altitude h (m): (density, pressure, temperature, speed of sound)."""
    if h < 86000.0:
        hg = _R0_GEOPOT * max(h, -1000.0) / (_R0_GEOPOT + max(h, -1000.0))
        k = 0
        while k + 1 < len(_LAYERS) and hg >= _LAYERS[k + 1][0]:
            k += 1
        hb, tb, lb, pb = _LAYERS[k]
        t = tb + lb * (hg - hb)
        if lb == 0.0:
            p = pb * math.exp(-_GMR * (hg - hb) / tb)
        else:
            p = pb * (tb / t) ** (_GMR / lb)
        rho = p * _M0 / (_R_STAR * t)
        return rho, p, t, math.sqrt(1.4 * _R_STAR / _M0 * t)
    km = h / 1000.0
    if km >= _UPPER[-1, 0]:
        # Exponential continuation of the last interval (only reached in orbit, where drag is negligible).
        k = len(_UPPER) - 2
    else:
        k = int(np.searchsorted(_UPPER[:, 0], km, side="right")) - 1
    f = (km - _UPPER[k, 0]) / (_UPPER[k + 1, 0] - _UPPER[k, 0])
    rho = math.exp(_UPPER_LOG_RHO[k] + f * (_UPPER_LOG_RHO[k + 1] - _UPPER_LOG_RHO[k]))
    p = math.exp(_UPPER_LOG_P[k] + f * (_UPPER_LOG_P[k + 1] - _UPPER_LOG_P[k]))
    t = float(_UPPER[min(k + 1, len(_UPPER) - 1), 3]) if km >= _UPPER[-1, 0] else float(
        _UPPER[k, 3] + f * (_UPPER[k + 1, 3] - _UPPER[k, 3]))
    return rho, p, t, math.sqrt(1.4 * _R_STAR / _M0 * min(t, 300.0))


# Mars: SCIENCE.md section 3 (the game's atmosphere): p0 = 560 Pa at the MOLA datum, scale height 11.0 km,
# rho0 = p0 M / (R T) = 0.0139 kg/m^3 (M = 43.34 g/mol, T = 210 K). Isothermal exponential, annual mean.
MARS_P0 = 560.0
MARS_RHO0 = 560.0 * 0.04334 / (8.314 * 210.0)  # 0.01390 kg/m^3
MARS_H = 11000.0
MARS_SOUND = 240.0  # m/s, Perseverance SuperCam (Maurice et al. 2022), SCI section 13


def mars_atmosphere(h: float) -> tuple[float, float, float, float]:
    f = math.exp(-h / MARS_H)
    return MARS_RHO0 * f, MARS_P0 * f, 210.0, MARS_SOUND


# ---------------------------------------------------------------------------------------------------------------
# Planets
# ---------------------------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Planet:
    name: str
    mu: float  # m^3/s^2
    radius: float  # m
    atmosphere: object
    sutton_graves_k: float  # stagnation-point convective heating constant (SI), Sutton & Graves 1971

    def g(self, r: float) -> float:
        return self.mu / (r * r)

    def circular_speed(self, h: float) -> float:
        return math.sqrt(self.mu / (self.radius + h))

    def escape_speed(self, h: float) -> float:
        return math.sqrt(2.0 * self.mu / (self.radius + h))


# Earth: mu = 398,600.44 km^3/s^2, R = 6378.137 km (FC appendix). Mars: mu = 42,828.37 km^3/s^2 (FC appendix),
# R = 3396.0 km, the MOLA areoid mean equatorial radius used as the game's datum (SCI section 1): g = 3.714 m/s^2.
EARTH = Planet("earth", 3.9860044e14, 6378137.0, ussa76, 1.7415e-4)
MARS = Planet("mars", 4.282837e13, 3396000.0, mars_atmosphere, 1.9027e-4)


# ---------------------------------------------------------------------------------------------------------------
# Engines (Raptor 3, SpaceX "Introducing Starship V3", 12 May 2026 = FC section 21)
# ---------------------------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Engine:
    name: str
    thrust_vac: float  # N at full throttle in vacuum
    mdot: float  # kg/s at full throttle
    exit_area: float  # m^2, for the back-pressure loss p_a * A_e
    min_throttle: float = 0.40

    def thrust(self, throttle: float, p_amb: float) -> float:
        """Thrust (N): throttle scales the mass flow; the ambient back-pressure term does not."""
        return max(0.0, throttle * self.thrust_vac - p_amb * self.exit_area)

    def isp(self, p_amb: float) -> float:
        return self.thrust(1.0, p_amb) / (self.mdot * G0)


def _sea_level_raptor() -> Engine:
    # 250 tf at sea level; Isp 330 s at sea level and 350 s in vacuum (FC section 21, "reasonable game values").
    f_sl = 250.0e3 * G0
    mdot = f_sl / (330.0 * G0)
    f_vac = mdot * 350.0 * G0
    return Engine("raptor3", f_vac, mdot, (f_vac - f_sl) / 101325.0)


def _vacuum_raptor() -> Engine:
    # 275 tf in vacuum, Isp 380 s (FC section 21); exit 2.3 m across (SpaceX: RVac 2.3 m x 4.4 m).
    f_vac = 275.0e3 * G0
    return Engine("raptor3_vac", f_vac, f_vac / (380.0 * G0), math.pi * 1.15 ** 2)


RAPTOR_SL = _sea_level_raptor()
RAPTOR_VAC = _vacuum_raptor()


# ---------------------------------------------------------------------------------------------------------------
# Vehicles
# ---------------------------------------------------------------------------------------------------------------

HULL_DIAMETER = 9.0  # m (FC section 21)
HULL_AREA = math.pi * (HULL_DIAMETER / 2.0) ** 2  # 63.6 m^2, nose-on reference area
SHIP_LENGTH = 52.1  # m (SCI section 18)
BOOSTER_LENGTH = 71.9  # m
STACK_LENGTH = 124.0  # m
SHIP_PLANFORM = SHIP_LENGTH * HULL_DIAMETER  # 469 m^2, broadside reference area

BOOSTER_PROPELLANT = 3650.0e3  # kg (FC section 21)
SHIP_PROPELLANT = 1600.0e3  # kg (FC section 21)
OF_RATIO = 3.5  # oxidiser/fuel mass ratio (Heldmann et al. 2022, FC section 23; SCI section 16)
LOX_FRACTION = OF_RATIO / (1.0 + OF_RATIO)


@dataclass
class VehicleMasses:
    """Assumed masses (no official V3 dry masses exist; see README 'Assumptions')."""

    booster_dry: float = 275.0e3
    ship_dry: float = 130.0e3
    payload_out: float = 100.0e3  # Earth to Mars (SpaceX "100+ t", FC section 21)
    payload_back: float = 50.0e3  # Mars to Earth: crew and samples; cargo stays on Mars


# ---------------------------------------------------------------------------------------------------------------
# Aerodynamics
# ---------------------------------------------------------------------------------------------------------------


def _interp(x: float, xs, ys) -> float:
    return float(np.interp(x, xs, ys))


# Nose-first stack and ship on ascent (reference: 63.6 m^2). Model: generic slender launch-vehicle curve with a
# transonic peak (subsonic 0.30, peak 0.55 at Mach 1.2, 0.30 hypersonic).
_ASCENT_M = (0.0, 0.6, 0.9, 1.2, 1.6, 2.0, 3.0, 5.0, 25.0)
_ASCENT_CD = (0.30, 0.30, 0.42, 0.55, 0.50, 0.45, 0.36, 0.30, 0.30)


def cd_ascent(mach: float) -> float:
    return _interp(mach, _ASCENT_M, _ASCENT_CD)


# Super Heavy falling engines-first with grid fins (reference: 63.6 m^2). Model: blunt flat-faced cylinder
# (subsonic ~0.85, supersonic ~1.6 as the stagnation pressure behind the normal shock acts on the base).
_BOOSTER_M = (0.0, 0.6, 1.0, 1.5, 3.0, 25.0)
_BOOSTER_CD = (0.85, 0.85, 1.15, 1.45, 1.60, 1.60)


def cd_booster_descent(mach: float) -> float:
    return _interp(mach, _BOOSTER_M, _BOOSTER_CD)


# Super Heavy V3's three grid fins, deployed for the descent: 6.0 m x 4.4 m each (StarshipGeometry), C_D = 0.9 on
# their planform (model; lattice fins have high drag, no published value).
GRID_FIN_AREA = 3 * 6.0 * 4.4
GRID_FIN_CD = 0.9


def booster_descent_cd_area(mach: float) -> float:
    return cd_booster_descent(mach) * HULL_AREA + GRID_FIN_CD * GRID_FIN_AREA


# Ship broadside (belly first): crossflow drag of a circular cylinder vs crossflow Mach number, based on the
# 469 m^2 planform. 1.2 subsonic (subcritical cylinder), a transonic rise, and the modified-Newtonian limit
# (2/3) Cp_max = 1.23 hypersonic (Cp_max = 1.84). Shape after Jorgensen 1977 (NASA TR R-474).
_CROSS_M = (0.0, 0.4, 0.8, 1.0, 1.3, 2.0, 3.0, 5.0, 10.0, 40.0)
_CROSS_CD = (1.20, 1.20, 1.35, 1.55, 1.60, 1.45, 1.36, 1.30, 1.23, 1.23)
SHIP_AXIAL_CA_NOSE = 0.03  # nose first: nose pressure and skin friction (planform-based)
SHIP_AXIAL_CA_BASE = 1.05 * HULL_AREA / SHIP_PLANFORM  # tail first: blunt base with the engines, C_D 1.05 on 63.6 m^2


def ship_aero(mach: float, alpha: float) -> tuple[float, float]:
    """(C_D, C_L) on the 469 m^2 planform at angle of attack alpha in [0, pi] (rad) between the velocity and the nose.

    Crossflow theory: the normal force follows the crossflow drag of a circular cylinder, C_N = c_dc(M sin a) sin^2 a;
    the axial force acts along the axial component of the relative flow (on the nose when nose first, on the base
    when tail first). C_D = C_N sin a + C_A |cos a|; positive C_L points to the nose side of the velocity.
    """
    s, c = math.sin(alpha), math.cos(alpha)
    s = abs(s)
    cn = _interp(mach * s, _CROSS_M, _CROSS_CD) * s * s
    # The axial force scales with the axial dynamic pressure q cos^2(alpha), so it vanishes in pure crossflow.
    ca = (SHIP_AXIAL_CA_NOSE if c >= 0.0 else SHIP_AXIAL_CA_BASE) * c * c
    sign = 1.0 if c >= 0.0 else -1.0
    return cn * s + ca * abs(c), cn * c - ca * s * sign


# Angle-of-attack schedule (assumed; no published Starship value): 70 deg in hypersonic flight, rising to 90 deg
# (the belly flop) as the ship slows from Mach `high` to Mach `low`.
def entry_alpha(mach: float, high: float = 8.0, low: float = 4.0, alpha_hyp: float = 70.0) -> float:
    return math.radians(_interp(mach, (0.0, low, high, 50.0), (90.0, 90.0, alpha_hyp, alpha_hyp)))


def sutton_graves(planet: Planet, rho: float, v: float, nose_radius: float = 4.5) -> float:
    """Stagnation-point convective heat flux (W/m^2), Sutton & Graves 1971 (NASA TR R-376)."""
    return planet.sutton_graves_k * math.sqrt(max(rho, 0.0) / nose_radius) * v ** 3
