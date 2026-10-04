"""2D point-mass flight dynamics over a spherical, non-rotating planet (polar plane of the flight heading).

State: inertial position (x, y) with the planet centre at the origin, inertial velocity (vx, vy) and mass m. The
vehicle starts at (0, R + h): local "up" is +y there and the heading ("forward", downrange) is +x. Forces: inverse-
square gravity, thrust along the body axis, aerodynamic drag along -v and lift normal to v in the plane. Controls are
held constant over each fixed RK4 step (zero-order hold), as a flight computer would.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np

from models import Planet


@dataclass
class Control:
    thrust: float = 0.0  # N, along the body axis (tail to nose)
    mdot: float = 0.0  # kg/s
    pitch: float = math.pi / 2  # rad: body axis above the local horizon (90 deg = nose up, 180 = nose back)
    cd_area: float = 0.0  # C_D * A (m^2)
    cl_area: float = 0.0  # C_L * A (m^2), lift toward the local-up side of the velocity
    lift_up: float = 1.0  # cos(bank): fraction of the lift kept in the vertical plane (negative = lift down)


@dataclass
class Sample:
    t: float
    h: float  # m above the reference surface
    s: float  # m of downrange along the surface (R * theta)
    v: float  # m/s inertial speed
    gamma: float  # rad flight-path angle
    vx: float  # m/s horizontal (forward) velocity
    vz: float  # m/s vertical velocity
    m: float  # kg
    pitch: float  # rad
    engines: int
    thrust: float
    q: float  # Pa dynamic pressure
    mach: float
    heat: float  # W/m^2 stagnation convective heat flux (entries only)
    accel: float  # m/s^2 non-gravitational acceleration ("g-load" * g0)
    extra: dict = field(default_factory=dict)


class Flight:
    """Fixed-step integrator with helpers for local-frame quantities."""

    def __init__(self, planet: Planet, h0: float = 0.0, v_fwd: float = 0.0, v_up: float = 0.0, mass: float = 0.0,
                 t0: float = 0.0, s0: float = 0.0):
        self.planet = planet
        r = planet.radius + h0
        th = s0 / planet.radius
        up = np.array([math.sin(th), math.cos(th)])
        fw = np.array([math.cos(th), -math.sin(th)])
        pos = up * r
        vel = fw * v_fwd + up * v_up
        self.y = np.array([pos[0], pos[1], vel[0], vel[1], mass], dtype=float)
        self.t = t0

    # ----- state helpers -------------------------------------------------------------------------------------
    @staticmethod
    def frame(y):
        r = math.hypot(y[0], y[1])
        upx, upy = y[0] / r, y[1] / r
        return r, upx, upy, upy, -upx  # r, up, forward

    def local(self, y=None):
        y = self.y if y is None else y
        r, upx, upy, fwx, fwy = self.frame(y)
        vx = y[2] * fwx + y[3] * fwy
        vz = y[2] * upx + y[3] * upy
        h = r - self.planet.radius
        s = self.planet.radius * math.atan2(y[0], y[1])
        return h, s, vx, vz

    def atmosphere(self, h):
        return self.planet.atmosphere(h)

    # ----- dynamics ------------------------------------------------------------------------------------------
    def deriv(self, y, c: Control):
        x, yy, vx, vy, m = y
        r = math.hypot(x, yy)
        upx, upy = x / r, yy / r
        fwx, fwy = upy, -upx
        g = self.planet.mu / (r * r)
        ax = -g * upx
        ay = -g * upy
        if c.thrust > 0.0:
            cp, sp = math.cos(c.pitch), math.sin(c.pitch)
            bx = cp * fwx + sp * upx
            by = cp * fwy + sp * upy
            a = c.thrust / m
            ax += a * bx
            ay += a * by
        v = math.hypot(vx, vy)
        if v > 1e-9 and (c.cd_area > 0.0 or c.cl_area != 0.0):
            rho = self.planet.atmosphere(r - self.planet.radius)[0]
            qa = 0.5 * rho * v / m  # times v gives dynamic pressure / m
            d = qa * c.cd_area
            ax -= d * vx
            ay -= d * vy
            lift = qa * c.cl_area * c.lift_up
            ax += lift * -vy
            ay += lift * vx
        return np.array([vx, vy, ax, ay, -c.mdot])

    def step(self, c: Control, dt: float):
        y = self.y
        k1 = self.deriv(y, c)
        k2 = self.deriv(y + 0.5 * dt * k1, c)
        k3 = self.deriv(y + 0.5 * dt * k2, c)
        k4 = self.deriv(y + dt * k3, c)
        self.y = y + dt / 6.0 * (k1 + 2.0 * k2 + 2.0 * k3 + k4)
        self.t += dt

    def sample(self, c: Control, engines: int, heat: bool = False, **extra) -> Sample:
        h, s, vx, vz = self.local()
        v = math.hypot(vx, vz)
        rho, p, temp, a_snd = self.atmosphere(h)
        q = 0.5 * rho * v * v
        drag = q * c.cd_area
        lift = q * c.cl_area * c.lift_up
        # Non-gravitational acceleration magnitude (what the crew feels).
        gam = math.atan2(vz, vx)
        fx = c.thrust * math.cos(c.pitch) - drag * math.cos(gam) - lift * math.sin(gam)
        fz = c.thrust * math.sin(c.pitch) - drag * math.sin(gam) + lift * math.cos(gam)
        acc = math.hypot(fx, fz) / self.y[4]
        return Sample(self.t, h, s, v, gam, vx, vz, self.y[4], c.pitch, engines, c.thrust, q, v / a_snd,
                      self.planet.sutton_graves_k * math.sqrt(rho / 4.5) * v ** 3 if heat else 0.0, acc, dict(extra))


def smoothstep(x: float) -> float:
    x = min(1.0, max(0.0, x))
    return x * x * (3.0 - 2.0 * x)


def lerp(a: float, b: float, f: float) -> float:
    return a + (b - a) * f
