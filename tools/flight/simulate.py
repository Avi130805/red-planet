#!/usr/bin/env python3.11
"""Red Planet flight simulations: writes the data-driven flight profiles of the Starship flights.

    python3.11 tools/flight/simulate.py            # simulate (about 5 minutes), write the profiles, validate
    python3.11 tools/flight/simulate.py --cache c.pkl   # reuse (or create) a pickled simulation run

Outputs:
    src/main/resources/data/redplanet/redplanet/flight_profile/earth_to_mars.json
    src/main/resources/data/redplanet/redplanet/flight_profile/mars_to_earth.json
    src/gametest/resources/data/redplanet-gametest/redplanet/flight_profile/test_hop.json
    tools/flight/summary.json   (every number quoted in docs/SCIENCE.md section 19)

Models, inputs and assumptions: tools/flight/README.md.
"""

from __future__ import annotations

import argparse
import json
import math
import pickle
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import profiles  # noqa: E402
import validate  # noqa: E402
from missions import Missions  # noqa: E402
from models import BOOSTER_PROPELLANT, SHIP_PROPELLANT  # noqa: E402

ROOT = HERE.parents[1]
OUT = {
    "earth_to_mars": ROOT / "src/main/resources/data/redplanet/redplanet/flight_profile/earth_to_mars.json",
    "mars_to_earth": ROOT / "src/main/resources/data/redplanet/redplanet/flight_profile/mars_to_earth.json",
    "test_hop": ROOT / "src/gametest/resources/data/redplanet-gametest/redplanet/flight_profile/test_hop.json",
}


def dump(profile: dict) -> str:
    """JSON with one phase, keyframe or shot per line: compact, and readable in a diff."""
    lines = ["{"]
    keys = list(profile)
    for n, key in enumerate(keys):
        val = profile[key]
        comma = "," if n < len(keys) - 1 else ""
        if isinstance(val, list):
            lines.append(f'  "{key}": [')
            for i, item in enumerate(val):
                lines.append("    " + json.dumps(item, separators=(", ", ": ")) + ("," if i < len(val) - 1 else ""))
            lines.append("  ]" + comma)
        else:
            lines.append(f'  "{key}": ' + json.dumps(val, separators=(", ", ": ")) + comma)
    lines.append("}")
    return "\n".join(lines) + "\n"


def first(samples, pred):
    return next((q for q in samples if pred(q)), None)


def descent_summary(d, planet_name: str) -> dict:
    tr = d.track
    s = tr.samples
    ev = tr.events
    info = tr.info
    at = lambda t: min(s, key=lambda q: abs(q.t - t))  # noqa: E731
    peak = at(info["peak_heat_t"])
    pl = at(ev["plasma_end"])
    bf = at(ev["belly_flop"])
    ign = at(ev["landing_burn"])
    flip = at(ev["flip"])
    maxg = at(info["max_accel_t"])
    two = first(s, lambda q: q.t > ev["landing_burn"] and q.engines == 2)
    one = first(s, lambda q: q.t > ev["landing_burn"] and q.engines == 1)
    return {
        "planet": planet_name,
        "entry_speed_m_s": round(s[0].v, 1),
        "entry_altitude_km": round(s[0].h / 1000.0, 1),
        "entry_flight_path_deg": round(math.degrees(s[0].gamma), 2),
        "entry_mass_t": round(s[0].m / 1000.0, 1),
        "peak_deceleration_g": round(info["max_accel"] / 9.80665, 2),
        "peak_deceleration_t_s": round(info["max_accel_t"], 1),
        "peak_deceleration_altitude_km": round(maxg.h / 1000.0, 1),
        "peak_heating_W_cm2": round(info["peak_heat"] / 1.0e4, 1),
        "peak_heating_t_s": round(info["peak_heat_t"], 1),
        "peak_heating_altitude_km": round(peak.h / 1000.0, 1),
        "peak_heating_speed_m_s": round(peak.v, 0),
        "plasma_end_t_s": round(ev["plasma_end"], 1),
        "plasma_end_altitude_km": round(pl.h / 1000.0, 1),
        "plasma_end_speed_m_s": round(pl.v, 0),
        "belly_flop_t_s": round(ev["belly_flop"], 1),
        "belly_flop_altitude_km": round(bf.h / 1000.0, 2),
        "belly_flop_speed_m_s": round(bf.v, 0),
        "landing_burn_t_s": round(ev["landing_burn"], 2),
        "landing_burn_altitude_m": round(ign.h, 0),
        "landing_burn_speed_m_s": round(ign.v, 1),
        "landing_burn_mach": round(ign.mach, 2),
        "flip_t_s": round(ev["flip"], 2),
        "flip_altitude_m": round(flip.h, 0),
        "three_to_two_altitude_m": round(two.h, 0) if two else None,
        "two_to_one_altitude_m": round(one.h, 0) if one else None,
        "touchdown_t_s": round(ev["touchdown"], 2),
        "landing_burn_duration_s": round(ev["touchdown"] - ev["landing_burn"], 1),
        "touchdown_speed_m_s": round(abs(info["touchdown_vz"]), 2),
        "touchdown_drift_m_s": round(abs(info["touchdown_vx"]), 2),
        "landing_propellant_t": round((d.prop_ei - info["prop_left"]) / 1000.0, 1),
        "propellant_at_entry_t": round(d.prop_ei / 1000.0, 1),
        "propellant_at_touchdown_t": round(info["prop_left"] / 1000.0, 1),
        "range_km": round((s[-1].s - s[0].s) / 1000.0, 0),
    }


def collect(ms, i1, i2) -> dict:
    a = ms.ascent
    k = a.k
    st = a.stack_result
    sh = a.ship_result
    b = ms.booster_track.samples
    apogee = max(b, key=lambda q: q.h)
    ign = min(b, key=lambda q: abs(q.t - k.booster_landing_burn))
    desc = [q for q in b if q.t > k.boostback_end]
    qmax = max(desc, key=lambda q: q.q)
    asc = ms.mars_ascent_track.samples
    mres = ms.mars_ascent_result
    three = first(asc, lambda q: q.engines == 3)
    return {
        "earth_ascent": {
            "liftoff_mass_t": round(ms.stack_track.samples[0].m / 1000.0, 1),
            "liftoff_thrust_to_weight": round(ms.stack_track.samples[0].thrust / (ms.stack_track.samples[0].m * 9.798), 3),
            "pitch_kick_deg": round(a.stack_params[0], 3),
            "throttle_bucket_start_s": round(a.stack_params[1], 2),
            "throttle_bucket": k.bucket_throttle,
            "throttle_after_bucket": round(a.stack_params[2], 4),
            "max_q_t_s": round(st["t_maxq"], 2),
            "max_q_kPa": round(st["maxq"] / 1000.0, 1),
            "meco_t_s": k.meco,
            "hot_staging_t_s": k.hot_staging,
            "staging_altitude_km": round(st["h"] / 1000.0, 2),
            "staging_speed_kmh": round(st["v"] * 3.6, 0),
            "staging_flight_path_deg": round(math.degrees(st["gamma"]), 1),
            "staging_downrange_km": round(st["s"] / 1000.0, 1),
            "booster_propellant_at_staging_t": round(st["booster_prop"] / 1000.0, 0),
            "ship_steering_tan_pitch": [round(a.ship_params[0], 5), round(a.ship_params[1], 7)],
            "ship_throttle": round(a.ship_params[2], 4),
            "ship_g_limit": k.ship_g_limit,
            "seco_t_s": round(sh["t"], 2),
            "seco_altitude_km": round(sh["h"] / 1000.0, 2),
            "seco_speed_kmh": round(sh["v"] * 3.6, 0),
            "seco_downrange_km": round(sh["s"] / 1000.0, 0),
            "seco_propellant_t": round(sh["prop"] / 1000.0, 1),
        },
        "booster_return": {
            "boostback_attitude_deg": round(a.booster_params[0], 2),
            "boostback_throttle": round(a.booster_params[1], 4),
            "boostback_start_t_s": k.boostback_start,
            "boostback_end_t_s": k.boostback_end,
            "apogee_km": round(apogee.h / 1000.0, 1),
            "apogee_t_s": round(apogee.t, 0),
            "max_downrange_km": round(max(q.s for q in b) / 1000.0, 1),
            "descent_max_q_kPa": round(qmax.q / 1000.0, 0),
            "landing_burn_t_s": k.booster_landing_burn,
            "landing_burn_altitude_m": round(ign.h, 0),
            "landing_burn_speed_m_s": round(ign.v, 0),
            "thirteen_to_three_t_s": k.booster_landing_burn + k.landing_stage_a,
            "catch_t_s": k.booster_catch,
            "catch_altitude_m": k.catch_alt,
            "propellant_at_catch_t": round(a.booster_result["prop"] / 1000.0, 0),
            "propellant_at_catch_fraction": round(a.booster_result["prop"] / BOOSTER_PROPELLANT, 4),
        },
        "earth_orbit_and_tmi": {
            "tankers": ms.tankers,
            "per_tanker_t": round(ms.per_tanker / 1000.0, 1),
            "propellant_before_tmi_t": round(ms.tmi_prop_before / 1000.0, 1),
            "tmi_burn_s": round(ms.tmi_burn, 1),
            "tmi_end_speed_kmh": round(i1["tmi_end_speed"], 0),
            "entry_interface_mission_days": round(i1["T_EI"] / 86400.0, 3),
            "frame_switch_days": round(i1["t_switch"] / 86400.0, 1),
        },
        "mars_descent": descent_summary(ms.mars, "mars"),
        "mars_ascent": {
            "propellant_loaded_t": round(ms.mars_load / 1000.0, 1),
            "propellant_loaded_fraction": round(ms.mars_load / SHIP_PROPELLANT, 4),
            "liftoff_mass_t": round(asc[0].m / 1000.0, 1),
            "liftoff_thrust_to_weight_mars": round(asc[0].thrust / (asc[0].m * 3.7136), 3),
            "steering_tan_pitch": [round(ms.mars_ascent.params[0], 5), round(ms.mars_ascent.params[1], 7)],
            "max_q_t_s": round(mres["t_max_q"], 1),
            "max_q_kPa": round(mres["max_q"] / 1000.0, 2),
            "sea_level_engines_off_t_s": round(three.t, 1) if three else None,
            "burnout_t_s": round(mres["t"], 2),
            "burnout_altitude_km": round(mres["h"] / 1000.0, 1),
            "burnout_speed_kmh": round(mres["v"] * 3.6, 0),
            "burnout_downrange_km": round(mres["s"] / 1000.0, 0),
            "v_infinity_km_s": 3.5,
            "burn_delta_v_km_s": round(i2["departure_dv"] / 1000.0, 3),
            "propellant_at_burnout_t": round(mres["prop"] / 1000.0, 1),
            "entry_interface_mission_days": round(i2["T_EI"] / 86400.0, 3),
        },
        "earth_descent": descent_summary(ms.earth, "earth"),
    }


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cache", type=Path, help="pickle of a simulation run to reuse (created if missing)")
    ap.add_argument("--dt", type=float, default=0.05, help="integration step (s)")
    ap.add_argument("--no-write", action="store_true", help="simulate and validate without writing the profiles")
    args = ap.parse_args(argv)

    if args.cache and args.cache.exists():
        ms = pickle.loads(args.cache.read_bytes())
        print(f"loaded simulation run from {args.cache}")
    else:
        ms = Missions(dt=args.dt).run()
        if args.cache:
            args.cache.write_bytes(pickle.dumps(ms))

    p1, i1 = profiles.build_earth_to_mars(ms)
    p2, i2 = profiles.build_mars_to_earth(ms)
    p3 = profiles.build_test_hop()
    summary = collect(ms, i1, i2)
    summary["clock_rates_earth_to_mars"] = profiles.rate_table(i1["phases"])
    summary["clock_rates_mars_to_earth"] = profiles.rate_table(i2["phases"])

    print(json.dumps({k: v for k, v in summary.items() if not k.startswith("clock")}, indent=2))
    print("\nmission seconds per gameplay second at each phase's start -> end (short | standard | long):")
    for line in summary["clock_rates_earth_to_mars"]:
        print("  E>M " + line)
    for line in summary["clock_rates_mars_to_earth"]:
        print("  M>E " + line)

    if args.no_write:
        return 0
    for name, prof in (("earth_to_mars", p1), ("mars_to_earth", p2), ("test_hop", p3)):
        OUT[name].parent.mkdir(parents=True, exist_ok=True)
        OUT[name].write_text(dump(prof))
        print(f"wrote {OUT[name].relative_to(ROOT)} ({OUT[name].stat().st_size // 1024} KiB)")
    (HERE / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(f"wrote {(HERE / 'summary.json').relative_to(ROOT)}\n")
    return validate.main([str(p) for p in OUT.values()])


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
