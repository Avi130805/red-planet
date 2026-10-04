# Flight simulations and profiles

`tools/flight/` simulates the Starship flights of *Red Planet* and writes the data-driven flight profiles the mod
plays: the timeline of phases on real mission time, the telemetry of the ship and Super Heavy, the camera shots and
the transfer interlude. The HUD shows the telemetry as it is; the world shows it through the altitude and downrange
mappings. Every number below is in `docs/SCIENCE.md` §19 (results) and §21 (compromises), with its source.

## Running

```sh
python3.11 tools/flight/simulate.py              # about 4 minutes: simulate, write the three profiles, validate
python3.11 tools/flight/simulate.py --cache run.pkl   # reuse a pickled run (created if missing) to iterate on the profiles
python3.11 tools/flight/validate.py              # validate the three profiles (or pass files)
```

Python 3.11 with numpy and scipy. The run is deterministic. It writes:

| File | Content |
|---|---|
| `src/main/resources/data/redplanet/redplanet/flight_profile/earth_to_mars.json` | The stack from the Earth pad to touchdown on Mars |
| `src/main/resources/data/redplanet/redplanet/flight_profile/mars_to_earth.json` | The ship alone from the Mars pad to touchdown on Earth |
| `src/gametest/resources/data/redplanet-gametest/redplanet/flight_profile/test_hop.json` | A ten-second stack hop with every segment, for automated tests (hand-written, crude) |
| `tools/flight/summary.json` | Every result quoted in SCIENCE.md, and the clock-rate table of each phase |

The profile JSON has one phase, keyframe or shot per line, so a rerun reads well in a diff.

| Module | Role |
|---|---|
| `models.py` | Planets, atmospheres, Raptor 3, vehicle masses and dimensions, aerodynamic models, heating |
| `flightsim.py` | 2D point-mass dynamics and the fixed-step RK4 integrator |
| `scenarios.py` | The four simulations and their guidance |
| `missions.py` | Final settings; closes the propellant budget of both trips |
| `profiles.py` | Phases, events, keyframe thinning, transfer coasts, camera shots, JSON |
| `simulate.py` | Entry point and report |
| `validate.py` | Codec and design checks of any profile |

## Physical model

- **Frame.** A point mass in the vertical plane of the flight heading, over a spherical planet with inverse-square
  gravity (curvature matters: orbit insertion, hyperbolic entries). Planets do not rotate (see compromises).
  Controls are held constant over each 0.05 s RK4 step.
- **Earth.** μ = 398,600.44 km³/s², R = 6378.137 km (FC appendix). Atmosphere: U.S. Standard Atmosphere 1976
  (NOAA-S/T 76-1562), the seven geopotential layers to 86 km and the tabulated density, pressure and temperature
  from 86 to 300 km, log-interpolated.
- **Mars.** μ = 42,828.37 km³/s² (FC appendix), R = 3396.0 km (the MOLA areoid used as the game's datum, SCIENCE §1):
  g = 3.714 m/s². Atmosphere: SCIENCE §3, the game's own: p = 560 Pa · e^(−h/11 km), ρ₀ = 0.0139 kg/m³, isothermal
  210 K; speed of sound 240 m/s (SCIENCE §13).
- **Raptor 3** (SpaceX V3 update, FC §21). Sea-level engine: 250 tf at sea level, Isp 330 s at sea level and 350 s in
  vacuum (FC §21 "reasonable game values"), so 757.6 kg/s, 265.2 tf in vacuum and a 1.47 m² exit. Vacuum engine:
  275 tf, Isp 380 s, 2.3 m exit (FC §21). Thrust = throttle × F_vac − p_ambient × A_exit; throttle 40–100 %
  (assumption, Raptor-class deep throttling).
- **Vehicles.** Propellant 3,650 t (booster) and 1,600 t (ship), O/F 3.5 (FC §21, §23). Heights 71.9 / 52.1 / 124 m,
  9 m diameter (SCIENCE §18). Assumed dry masses (no official V3 figure): booster 275 t, ship 130 t. Payload 100 t to
  Mars (SpaceX "100+ t", FC §21) and 50 t back (crew and samples; cargo stays on Mars).
- **Aerodynamics** (models, documented as such).
  - Stack and ship on ascent, nose first, 63.6 m²: C_D 0.30 subsonic, 0.55 at Mach 1.2, 0.30 hypersonic.
  - Super Heavy falling engines first: blunt cylinder, C_D 0.85 subsonic to 1.6 supersonic on 63.6 m². Its three
    grid fins (6.0 × 4.4 m each, as in `StarshipGeometry`) add C_D 0.9 on 79 m² once deployed after the boostback.
  - The ship broadside uses crossflow theory: normal force C_N = c_dc(M sin α) sin² α on the 469 m² planform
    (52.1 × 9 m). The crossflow drag c_dc of a circular cylinder is 1.2 subsonic, rises transonic, and reaches the
    modified-Newtonian 1.23 hypersonic (shape after Jorgensen 1977, NASA TR R-474). The axial force acts on the nose
    (C_A 0.03) or on the blunt base when tail first (C_D 1.05 on 63.6 m²). At α = 70° this gives L/D = 0.36; at 90°
    (the belly flop) L/D = 0 and the subsonic terminal speed near sea level is 81 m/s (FC §24 estimate: 75–90 m/s).
  - Angle of attack (assumed): 70° above Mach 6, rising to 90° by Mach 4.
- **Heating.** Stagnation-point convective flux, Sutton & Graves 1971 (NASA TR R-376): q = k √(ρ/r_n) v³ with
  k = 1.7415·10⁻⁴ (air) or 1.9027·10⁻⁴ (CO₂) SI and r_n = 4.5 m (the hull radius). It times peak heating and the end
  of the plasma (flux below 10 W/cm²); the absolute values are indicative only.

## The four simulations

### (a) Earth ascent: `EarthAscent`

1. **Stack.** 33 engines; a vertical rise for 10 s (the 124 m stack clears the tower), a 10 s pitch kick, then a
   zero-angle-of-attack gravity turn. The throttle drops to 70 % for 16 s through max-Q (a Falcon 9-style bucket)
   and then holds a lower setting. Three unknowns (kick angle, bucket start, throttle after the bucket) are solved
   for three targets (SCIENCE §19, FC §22): max-Q at T+52 s, then 68 km and 5,300 km/h at hot staging. MECO at
   T+2:20 leaves the three centre engines running through hot staging at T+2:23.
2. **Ship.** 3 sea-level + 3 vacuum Raptors from T+2:23, with linear-tangent steering (tan pitch = a + b·t), blended
   from the stack's attitude over 8 s. A crew limit of 4 g throttles the end of the burn. Cutoff comes exactly at the
   energy of a 200 km circular orbit. Three unknowns (a, b, throttle) are solved for 200 km, a zero flight-path angle
   and SECO at T+8:30. Result: 28,023 km/h, the inertial orbital speed (SCIENCE §19).
3. **Booster.**
   - **Flip and boostback:** a 4 s flip on 3 engines, then the boostback on 13 engines from T+2:27 to T+3:15 at a
     fixed attitude and throttle.
   - **Coast:** it turns engines-down on cold gas and lets the airflow align it engines first once the dynamic
     pressure rises; the grid fins are deployed.
   - **Landing burn:** from T+6:30, 6 s on 13 engines to a gate, then 3 engines to the tower arms 100 m up at
     T+6:55. Both stages use fixed-final-time minimum-energy guidance, a = 6(r_f − r − v t)/t² − 2(v_f − v)/t, with
     gravity and measured drag compensated.
   - **Solving:** the boostback attitude and throttle are solved so the burn starts at T+6:30 at the altitude the
     two-stage profile needs, and downrange of the pad by the drift of the first stage.

### (b) Mars arrival and (d) Earth return: `EntryLanding`

1. **Entry** at 125 km: Mars at 7.5 km/s (FC §23) with γ = −11.5°; Earth at 12 km/s (FC §23) with γ = −6.0°. Both
   speeds exceed escape speed, so the ship must keep its lift pointed down to stay in. The planar bank guidance holds
   the drag deceleration at 3.0 g (Mars) or 3.5 g (Earth): the sink rate that drives the drag toward the reference
   (D = const gives ḣ = −2HD/v, plus feedback) is held by feedback linearisation of the radial acceleration, with
   cos(bank) as the control. Below a knee speed (3 km/s Mars, 6 km/s Earth) the ship rolls to full lift up, to stay
   high while it slows.
2. **Belly flop.** The angle of attack goes to 90° by Mach 4. On Earth the ship then falls belly first to its
   subsonic terminal speed. In Mars' thin air even a gliding Starship cannot stay up below ~1.5 km/s (full lift
   holds it at 39 km at 3 km/s, 21 km at 2 km/s and 12 km at 1.5 km/s), so it arrives low and supersonic.
3. **Landing burn**, on three sea-level Raptors.
   - **Flip:** a 5 s rotation onto the braking direction while the engines spool up; the flip is called 2 s after
     ignition (FC §24, Flights 11–13).
   - **Braking:** fixed-final-time guidance to a gate at 400 m (Mars) or 120 m (Earth), with horizontal speed zero
     and horizontal position free. The landing site is defined as where the ship touches down.
   - **Handover:** when the remaining horizontal speed is what the rotation to vertical will remove.
   - **Final descent:** constant-deceleration arcs on 2 and then 1 engine to a 1 m/s touchdown. Engines step
     3 → 2 → 1 as on Flights 12–13 (FC §24). The attitude is rate-limited to 15°/s; legs deploy 12 s before
     touchdown.
   - **Ignition:** on Mars, the latest burn whose braking needs 85 % throttle when the flip ends. On Earth, the burn
     that puts the flip call at 520 m (FC §24: 500–550 m).
4. **Propellant.** The propellant carried to entry is iterated until the ship lands with a 10 t reserve.

### (c) Mars ascent: `MarsAscent`

The ship alone flies straight onto the Earth-return hyperbola (direct return, SCIENCE §19), on 3 + 3 Raptors (the
vacuum engines work in 560 Pa). After an 8 s vertical rise it eases over 25 s onto linear-tangent steering, under the
same 4 g limit, and burns out horizontally at 100 km when the specific energy reaches v∞²/2 for v∞ = 3.5 km/s
(FC appendix: 5.88 km/s ideal from the surface). The ISRU load is solved so the ship reaches the hyperbola with
exactly the Earth landing propellant. With a 50 t return payload that is 72 % of the tanks: a fuller ship would land
on Earth too heavy for the one-engine final descent.

### Transfer telemetry

- **Low orbit:** a 200 km circular parking orbit.
- **Refilling:** six tankers dock one after another over three days, two hours each. Their count is the budget
  divided by 100 t per tanker (SpaceX "100+ t" to LEO, FC §21; within the defensible 4–10+, FC §23).
- **Trans-Mars injection:** a finite prograde burn on the three vacuum Raptors, integrated, of 3.6 km/s (FC §23).
- **Coast:** two-body hyperbolas from each end. Distance and speed are relative to the departure planet until the
  ship is as far from it as from the destination, then relative to the destination; the altitude is continuous and
  the speed changes reference there.
- **Approach:** in the last day the ship turns to its entry attitude.
- **Mission time:** continuous throughout, so entry comes 182 days after the injection burn (T+185.0 days outbound,
  T+182.0 days on the return).

## Profiles

- **Phases.** Phase boundaries sit on the moments to dwell on. A phase that ends on one eases out, and the next eases
  in, so the clock slows smoothly to near-standstill at liftoff, hot staging, the flip and touchdown, and the rate
  (mission seconds per gameplay second, in `summary.json`) is continuous there. Event fractions invert the phase's
  easing, so `FlightProfile.eventTime` returns the simulated mission time.
- **The pad** follows SpaceX's V3 pre-launch timeline (Flight 12 and 13 pages):
  - LOX loading from T−37:30 (ship) and T−37:00 (booster), methane from T−35:25 (booster) and T−34:48 (ship);
  - the engine chill (venting) at T−21:30;
  - loading complete at T−2:50 (booster) and T−2:10 (ship);
  - the flame diverter at T−0:17 and engine start-up at T−0:03.
  The profile shows loading from T−40:00.
- **Event timing in the game.** The game skips events on the first tick after a launch, a reload or a dimension
  change, so `entry_interface` comes 0.5 s after the ship arrives above the destination rather than at the exact
  start of the descent.
- **Pacing.** STANDARD runs 6 min 23 s (Earth → Mars) and 6 min 15 s (Mars → Earth), each with a 60 s interlude:
  - liftoff to hot staging takes 52 s of play;
  - the flip and landing burn take 40 s (Mars) and 28 s (Earth);
  - SHORT runs 2:51 and 2:41; LONG runs 14:23 and 12:25, with the Earth ascent close to real time (470 s of play
    for 520 s of flight).
- **Keyframes.** Dense samples are thinned (Ramer–Douglas–Peucker) to within 0.25 block of in-world position (through
  both mappings), 0.02 km of altitude, 3 km/h or 0.3 % of speed, 0.5° of pitch and 0.2 % of fill. Steps between
  keyframes stay within 15° of pitch and 60 s. Engine changes always get a keyframe (engines switch at keyframes).
  Before hot staging the ship's keyframes are the stack's, so the two tracks agree exactly.
- **End states.** The booster ends caught 100 m up at downrange 0, held there until its phase ends (the game then
  sets it on the pad). The ship ends at exactly altitude 0, downrange 0 and pitch 90 at the end of the last descent
  phase.
- **In-world distance.** The default mappings keep the ship within 895 blocks of the pad at SECO, 916 blocks
  (Mars) or 954 blocks (Earth) of the site at entry, and the booster within 715 blocks, so no override is needed.
- **Shots.** Every phase is covered on [0, 1]:
  - pad orbit and tower views, and a deluge close-up at ignition;
  - chase from below, a cabin view through max-Q, and an onboard camera looking aft as the booster falls away;
  - the booster's return from the tower;
  - an onboard flap camera in the plasma;
  - a fixed camera at the landing site looking up through the flip;
  - an orbit around the landed ship.
  Transfer phases carry a cabin shot (the interlude covers the screen).

## Validation (`validate.py`)

- **Codec:** field names and types, enums, defaults, `FlightProfile.validate` (contiguity, segment order, positive
  durations, booster telemetry for a stack, shots on known phases), phase ids unique, `at` in [0, 1], shot
  `from < to`, 3-vectors, fill in [0, 1], engine bounds.
- **Telemetry:**
  - sorted keyframes;
  - ship telemetry covering the whole timeline, booster telemetry from liftoff to the catch;
  - no pitch step over 25° between keyframes;
  - ship and booster agreeing before hot staging;
  - the booster ending at downrange 0 under 0.15 km;
  - the exact touchdown state with a keyframe at it, under 2 m/s, and the `touchdown` event at the end of the last
    descent phase.
- **Design:** the event vocabulary, shot coverage of every phase, the ~1,000-block downrange limit at SECO and at
  the start of the descent, the pacing targets, and the test hop's 0.5–2 s phases totalling about 10 s.

## Assumptions and compromises

- **No planetary rotation, so the speeds shown are inertial.** On Earth this is ≈0.42 km/s at Starbase. Webcasts
  show Earth-relative speeds: Flight 5's suborbital 26,200 km/h is ≈27,700 km/h inertial. To reproduce the webcast
  hot-staging numbers without the rotation, the simulated booster throttles to 70 % (bucket) and then 90.5 %. The
  ship then needs ≈0.4 km/s more, which the budget covers. On Mars the direct return costs ≈0.24 km/s more.
- **Assumed values:** the masses (booster 275 t dry, ship 130 t dry, payloads 100 / 50 t), the angle-of-attack
  schedule, the drag coefficients, and the 4 g crew limit.
- **The interlude's numbers come from different transfers.** The 3.6 km/s injection is for a minimum-energy transfer
  (FC §23), while SpaceX's 7.5 km/s entry implies a faster one, and 182 days lies between. The interlude shows all
  three as SCIENCE §19 states them; the coast telemetry is two-body from each end.
- **Mars atmosphere:** isothermal to 125 km, so the cold middle atmosphere is too dense in the model and the ship
  slows a little higher than it would.
- **2D attitude:** pitch shows the trim angle of attack. The bank angle that modulates the lift is not drawn, and the
  crossrange it would cause is ignored.
- **Landing sites** are at the datum (MOLA 0, sea level); the descent is the same profile at every site.
- **Not modelled:** trajectory-correction burns, boil-off, the propellant used during the 3 s engine start before
  liftoff, and the ship's final flight-path tweaks to a pinpoint site. On Earth, a tower catch of the ship instead of
  touchdown is the game's choice.
- **The return injection uses the `tmi` event type.** The schema has no `tei`, and the interlude keys its injection
  stage on `tmi`.
