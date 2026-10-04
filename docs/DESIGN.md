# Red Planet: Starship to Mars: design

Status: approved for build (M1, 2026-10-04). Science and sources live in [SCIENCE.md](SCIENCE.md). 26.3 API facts
live in [API-NOTES-26.3.md](API-NOTES-26.3.md) and the detailed notes in [api-notes/](api-notes/).

## 1. Pillars

1. **The trip is the portal.** You board a true-scale Starship and stay in it for the whole flight: liftoff,
   staging, the transfer, entry, belly flop, flip and landing. The flight home is the same sequence mirrored.
2. **Mars is the real Mars.** The terrain is generated from NASA's MOLA topography and real crater catalogues, so
   Gale, Jezero, Olympus Mons, Valles Marineris and Hellas are where they are on Mars. The sky, the clock, the
   physics and the air follow measured numbers, and every deliberate compromise is written down.
3. **Rigour, but still Minecraft.** The numbers stay true (telemetry, pressure, temperature, dose); the
   presentation is scaled where it has to be (map scale, flight time compression, block textures).
4. **A dimension with its own story.** Like the Nether and the End, Mars has its own life, dangers, materials,
   structures, mystery and bosses. The planet is science; what you find on and under it is fiction (§8). The
   fiction never changes a measured number: its creatures don't breathe oxygen, don't burn, survive the cold and
   hide from the radiation.
5. **Full-featured.** Survival progression to build the rocket, a spacesuit, habitats, ISRU propellant for the
   return, historic lander sites, advancements, procedural textures and sounds, config, and tests at every level.

## 2. Platform

| Decision | Choice | Why |
|---|---|---|
| Loader | Fabric (Loader 0.19.5, Fabric API 0.161.0+26.3, Loom 1.18.2) | Stable on 26.3; NeoForge 26.3 is still beta. |
| Game | Minecraft Java 26.3, Java 25 | Latest release; unobfuscated (Mojang names). |
| Source sets | `main` (common), `client`, `gametest` (server + client gametests), `test` (JUnit) | Loom `splitEnvironmentSourceSets` + `fabricApi.configureTests`. |
| Mod id / package | `redplanet` / `io.github.avi130805.redplanet` | From the brief. |
| Hooks | Fabric API first; small, commented mixins where no hook exists | Per project rules. |
| Data | Datapack JSON for dimension, biomes, timelines, flight profiles, landing sites; datagen for models, recipes, loot, tags, lang | Fewer format mistakes, moddable. |

## 3. The Mars dimension

### 3.1 Geography from real data

- **Elevation:** MOLA MEGDR (Smith et al.) downsampled to 8 pixels per degree, 10 m steps (2.95 MB).
- **Craters:** Robbins & Hynek (2012), all 79,375 craters ≥ 3 km, at their true positions, with measured depth,
  rim height, degradation, central peaks/pits, terraces and layered ("rampart") ejecta.
- **Roughness:** sub-km RMS relief from 16 ppd MOLA, which scales procedural detail (smooth northern plains,
  rugged highlands).
- **Albedo:** TES (Christensen et al. 2001), which marks dusty vs dark basaltic terrain (biomes, dunes, globe texture).
- **Stars:** the Yale Bright Star Catalogue, rotated into Mars' equatorial frame (Mars' pole star is near Deneb).

Pipeline: `tools/mars-data/build_mars_data.py` downloads the public-domain sources and writes compact `.rpgrid`
and `.bin` resources (4.8 MB total). The build never needs the network.

### 3.2 Map projection and scales

- Horizontal: **Mercator** of the MOLA sphere (R = 3396 km) at **1 block = 1 km on the equator**. Mercator is
  conformal, so craters stay round at every latitude. +x is east and wraps every 21,337.7 blocks (walking east
  circles the planet); −z is north. Past ±85° latitude the map mirrors, so the polar caps have no wall.
- Vertical: **100 m per block**, areoid (0 m) at **y = 192**. The dimension spans y = 0..447 (height 448). Hellas'
  floor (−8.1 km) is at y ≈ 111, Olympus Mons' summit (21.2 km) at y ≈ 404.
- Consequence: relief is exaggerated about 10× on the equator (less toward the poles). Big features read like
  Minecraft mountains and canyons. Local landforms that are not in the map data (dunes, small decorative craters,
  boulders, lava-tube skylights) are drawn at human scale.
- Time of day is global across the dimension (as in vanilla); the sky shows the sun's path for your latitude and the
  season.

### 3.3 Terrain generation (26.3 worldgen)

- `minecraft:noise` generator with Mars `noise_settings`: `final_density = surface_height − y` (plus the beardifier
  for structures), no aquifers, air as the default fluid, `sea_level = min_y` (no hardcoded lava), no ore veins.
- `redplanet:mars_height`: a 2D density function (evaluated once per column, ~0.65 µs) wrapping `MarsTerrain`:
  MOLA (bicubic) + catalogue craters + self-affine detail scaled by measured roughness + dunes + terraced polar
  layered deposits. Detail noise is seeded from `CompileContext.createRandom` and cross-faded at the longitude seam.
- `redplanet:mars`: a custom biome source that classifies each column from geography (§3.4).
- `redplanet:mars_surface`: a custom material rule that layers blocks per column from biome, slope, depth, crater
  rims, dunes, ground ice and latitude.
- Features: boulders, human-scale craters, ores, ground ice, lava tubes with skylights, dust-devil tracks,
  meteorites. Structures: historic lander sites at their real coordinates (custom `redplanet:geo_fixed` placement,
  wrapping every circumference). `/redplanet locate <site>` replaces `/locate` for them.

### 3.4 Biomes

All Mars biomes: `has_precipitation: false`, `water_evaporates: true`. Surface biomes spawn only the fiction's hostiles (at night and in dust storms, §8.5); the living cave biomes below the surface are in §8.3.

| Biome | Where (rule) | Surface |
|---|---|---|
| `northern_plains` | north of the dichotomy, regional elevation < −2.5 km | smooth regolith, patterned ground, ice table below 45° latitude |
| `cratered_highlands` | default in the south | regolith, rocky crater rims, boulders, many craters |
| `dusty_highlands` | highlands with albedo > 0.25 (Arabia Terra) | thick bright dust over regolith |
| `volcanic_plains` | Tharsis and Elysium rises | basalt, lava flows, few craters, lava tubes |
| `shield_volcano` | the big volcanoes (Olympus, Tharsis Montes, Elysium Mons, Alba) | basalt flanks, summit calderas |
| `canyon` | Valles Marineris, Noctis Labyrinthus (deep below the regional surface) | layered sediments, landslide rock |
| `impact_basin` | Hellas, Argyre, Isidis floors | dusty, frost-prone, highest pressure |
| `dune_field` | dark sand on crater floors, the north polar erg | basaltic sand dunes |
| `north_polar_cap` | Planum Boreum | water ice with layered dust bands |
| `south_polar_cap` | Planum Australe | CO₂ ice over water ice |
| `mid_latitude_glaciers` | Deuteronilus/Protonilus Mensae, east Hellas rim | debris-covered buried ice |
| `meridiani_planum` | Opportunity's plains | hematite "blueberry" lag, jarosite |
| `gale_mound` | Gale crater / Aeolis Mons | mudstone, sulfate layers, gypsum veins |
| `jezero_delta` | Jezero crater | delta sediments, clays, carbonates |

### 3.5 Blocks and materials (Mars)

Regolith, Mars dust (falling) and dust layers (accumulate during storms), basaltic sand, Mars stone and cobblestone,
Mars basalt (plus polished and brick sets), mudstone, layered sediment, delta sediment, smectite clay, carbonate
rock, ice-rich regolith (permafrost), polar water ice, polar layered deposit, CO₂ ice and CO₂ frost (sublimate),
and ores: hematite (+ surface spherule lag), olivine basalt, jarosite, gypsum veins, elemental sulfur, chromite,
iron-nickel meteorite. Earth gains chromite ore (for stainless steel). Decorative stairs, slabs and walls for the
main stones. Textures are procedural pixel art (§9). The native cave life (areolichen, rustcap, ember moss, rime
bloom, salt spires, brine) and the Arean materials are in §8.

### 3.6 Time, seasons and sky

- **Clock:** `world_clock/mars.json` + timeline `redplanet:mars_day` with `period_ticks = 24660` (one sol). The
  dimension's `default_clock` makes `/time`, sleeping and daylight detectors follow Mars time. The timeline drives
  `sun_angle`, `star_angle`, `sky_light_level`, `sky_color`, `fog_color`, `sunrise_sunset_color` (blue) and stars.
- **Seasons:** areocentric solar longitude Ls from Mars' Keplerian orbit (real year, 668.6 sols; server config can
  compress it). Season drives sun declination, temperature, polar frost and the dust-storm season.
- **Sky renderer (client):** butterscotch day sky, blue aureole around the sun that grows into the blue sunset,
  0.35° sun at 43% irradiance, Phobos (0.2°, rises in the west, phases, eclipses) and Deimos (star-like), Earth as
  an evening/morning star with the Moon, the real star field tilted by latitude, long twilight. All positions come
  from `MarsAstronomy` (unit-tested).
- **Weather:** Mars never rains. Small mixins stop the shared overworld weather from reaching Mars
  (`canHaveWeather`, client rain level). Dust storms come from a dedicated world clock and timeline the server
  starts and stops (fog, sky colour, light, solar output), regional or global, more likely in the southern
  spring/summer. Dust devils are moving vortex entities that lift dust and clean solar panels.

### 3.7 Environment rules

Planet parameters are custom environment attributes (`redplanet:gameplay/*`) set in the Mars dimension type, so
they are data-driven and any dimension can opt in. A positional layer (one mixin on the attribute system builder)
adds habitat interiors and altitude effects.

| Rule | Mechanic |
|---|---|
| Gravity 0.379 g | `Entity.getGravity()` scaled for every entity (+ fishing hook and boat buoyancy call sites). A jump rises 3.1 blocks instead of 1.25. |
| Thin air (1.1% of Earth's density at the datum) | Every per-tick drag loss is scaled by √(ρ_rel·g_rel) = 0.066, which reproduces the real terminal-speed ratio (≈ 23 blocks/tick for a falling player) and is exactly vanilla on Earth; horizontal air control unchanged; elytra lift ∝ ρv² (useless unless very fast). |
| Fall damage ∝ g·h | Fall distance accumulates Earth-equivalent: safe fall ≈ 7.9 blocks. |
| Unbreathable | No air refill; the 15 s air bar matches the real time of useful consciousness, then blackout effects and slow `redplanet:hypoxia` damage (death at ≈ 90 s). Protected by a pressurized suit with O₂, a habitat, or a crewed vehicle. |
| No combustion | Fire can't exist; torches, lanterns, campfires, candles won't light; furnaces won't burn fuel; burning entities go out. Black powder (TNT, fireworks) still works (carries its own oxidizer). Habitats with O₂ allow combustion. |
| Water | `water_evaporates`: placed water boils off, ice sublimates; liquid water only in habitats. Exposed water ice slowly sublimates outside the poles; CO₂ ice sublimates fast when warm. |
| Pressure & temperature | Pressure 560 Pa at the datum, scaled by elevation (H = 11 km) and season; temperature from latitude, Ls, time of sol, albedo, pressure and dust. Shown on the suit HUD. |
| Radiation | Dose rate 0.64 mSv/sol on the surface, 1.8 mSv/day in cruise, shielded by habitats/ship; tracked per player, shown by the dosimeter. Gameplay effect only above high thresholds (configurable). |
| Sound | Quieter and low-passed on Mars outside habitats (OpenAL EFX where available, volume fallback). |
| Respawn | Beds work only inside habitats on Mars. Landing on Mars sets a forced respawn by your ship; returning home restores your Earth spawn. |

## 4. Starship

### 4.1 Vehicle

- True scale, V3 configuration (flown since Flight 12): ship 52.1 m (9 m diameter), Super Heavy 71.9 m, stack
  124.0 m. Procedural meshes (`StarshipGeometry`): smooth hulls, tangent-ogive nose, four flaps, 3 sea-level + 3
  vacuum Raptor 3s, telescoping landing legs, booster with 33 engines, integrated hot-staging ring and three grid fins. Three levels of detail. Textures at
  16 px/m (stainless steel panels, black hexagonal heat-shield tiles on the windward side, windows on the crew deck).
  No SpaceX logos.
- Animated: flaps, engine gimbal, legs, grid fins, frost on the tanks, venting, engine plumes (additive, Mach
  diamonds at sea level), entry plasma, hot-staging flash.
- Entities: `redplanet:starship` (passengers ride inside; up to 8 seats on the crew deck) and
  `redplanet:super_heavy` (separates at staging and flies back). Large culling box, long tracking range.

### 4.2 Launch site

- **Orbital Launch Mount** (a controller block; the kit builds the mount table, hold-down clamps and flame
  deflector) and an optional **Launch Tower** with chopsticks. With a tower, the booster is caught and reused (and a
  returning ship can be caught too); without one, the booster is expended.
- **Tank farm** next to the mount fills the stack with methalox on Earth. On Mars, propellant comes from ISRU (§6).
- **Mission control** UI (from the ship or a console block): destination and landing site (map of Mars with
  historic sites and SpaceX's candidate regions, or "return to base"), pacing, crew list, go/no-go checks
  (propellant, crew suits, window).

### 4.3 Flight profiles (data-driven)

`data/redplanet/flight_profile/earth_to_mars.json` and `mars_to_earth.json`:

- **Phases:** id, segment (`origin_pad`, `ascent`, `transfer`, `descent`, `landed`), real mission-time span
  (T± seconds), gameplay duration per pacing (short / standard / long), clock easing, events (max_q, meco,
  hot_staging, booster_catch, seco, refilling, tmi, entry_interface, peak_heating, flip, legs_deploy, touchdown…).
- **Telemetry:** real keyframes per vehicle: altitude (km), speed (km/h), LOX and CH₄ fill, engines burning,
  downrange and pitch. The HUD shows these at the current mission time, so numbers stay true while the in-world
  motion is compressed.
- **Altitude mapping:** true scale for the first 300 m, then logarithmic (`t + s·ln(1 + (h − t)/s)`), so 65 km
  staging is ~1,400 blocks up.
- **Camera shots:** per phase, from/to fractions, types `orbit`, `fixed` (pad, tower, landing site), `chase`,
  `onboard`, `cabin`, with fov and shake.

Outbound phases: propellant load → terminal count → ignition (deluge, engine start) → liftoff → ascent
(max-Q) → MECO and hot staging (booster boostback and catch in parallel) → ship ascent → SECO → [transfer:
orbit, refilling, TMI, coast, approach] → entry → belly flop → flip → landing burn → touchdown. Return: Mars
propellant load (ISRU) → count → ignition → liftoff → Mars ascent (ship only) → [transfer: TEI, coast, approach]
→ Earth entry (faster, brighter plasma) → belly flop → flip → landing burn (or tower catch) → touchdown.

### 4.4 Server flight controller

- Server-authoritative state machine on the ship entity: profile id, phase index, phase tick, pacing, mission
  start, origin pad, destination site, booster UUID, propellant, crew. All of it is in the entity's save data
  (cross-dimension teleport rebuilds the entity from NBT).
- In-world positions are a pure function of (profile, phase, tick), so a reload resumes exactly.
- **Transfer:** when the ship reaches the transfer segment it fades out. The server reserves and pre-generates the
  landing site with an async chunk ticket (`addTicketAndLoadWithRadius`), validates flatness, then teleports the
  ship with its passengers to the entry point high above the site (above the build limit, so the vanilla loading
  screen closes almost at once). The interlude plays over the world and the loading screen meanwhile.
- **Robustness:** logout mid-flight keeps the flight going; a per-player mission attachment puts the player back
  aboard (or next to the landed ship) on login. A crash resumes from saved state; if the destination is invalid,
  the flight rolls back to the origin pad. A `KEEP_DIMENSION_ACTIVE` ticket keeps an uncrewed Mars ship ticking.
  Touchdown resets fall distance; passengers can't dismount until landed (one mixin); passengers breathe inside.
- **Multiplayer:** any number of passengers up to the seat count; all see the same timeline; a skip needs every
  passenger's vote (single-player: immediate). Spectators on the ground see the real ascent and the booster return.

### 4.5 Client presentation

- **Cinematic camera:** overrides the camera while riding during flight; interpolated keyframed shots with cuts;
  reduce-shake and photosensitivity options; skip key; a free-look toggle to the cabin view.
- **Telemetry HUD:** webcast style: T± clock, SUPER HEAVY and STARSHIP panels (speed km/h, altitude km,
  engine diagrams with lit Raptors, LOX/CH₄ bars), event timeline.
- **Interlude:** a full-screen space scene drawn over the world and over the loading screen: Earth (textured globe)
  shrinking, refilling with tanker ships, the TMI burn, a Hohmann transfer diagram with the mission clock jumping
  months ahead (dose and light-time readouts), Mars (globe from MOLA/TES) growing, entry interface.
- **Ascent sky:** an altitude layer darkens the overworld sky to black and brings out the stars as the rocket
  climbs; clouds are passed at true height.

## 5. Survival progression

1. Find chromite (Earth) → chromium; alloy stainless steel; GRCop copper alloy for Raptor chambers.
2. Craft Raptor and Raptor Vacuum engines, heat-shield tiles, flaps, grid fins, avionics, tank rings.
3. Build the launch site kit (mount, optional tower, tank farm) and assemble Super Heavy and Starship on it.
4. Craft a spacesuit (helmet, life-support torso, legs, boots) and oxygen tanks.
5. Fly to Mars. Survive: oxygen, habitats, power, water ice. Find a Hesperia camp; harvest light (areolichen,
   lumen moths), food (rimebacks) and wood (rustcap) in the lava tubes.
6. ISRU: MOXIE (O₂ from CO₂), water extractor (ice, permafrost, gypsum, brine), electrolyzer (H₂ + O₂), Sabatier
   reactor (CH₄), cryogenic propellant depot → refuel the ship and fly home whenever you like.
7. The Arean thread (optional, open-ended): relic fragments → Ares compass → seven vaults → Cydonia → Phobos and
   Deimos → Heart of Ares → terraforming. Arean alloy and the thruster pack come from it.

Creative mode has everything; server config offers propellant economy presets (realistic tonnes shown in all
modes; production rates scaled).

## 6. ISRU, power and habitats

- **Power:** solar panels (output from the sun's elevation for your latitude and season, dust storm opacity, and
  dust on the panels, cleaned by dust devils, wind events or a brush), batteries, cables (a small network), and a
  Kilopower fission unit (10 kWe) for the late game.
- **Habitats:** a Habitat Regulator flood-fills a sealed volume (doors and trapdoors count when closed), pressurizes
  it with O₂/N₂ and keeps it supplied. Inside: breathing, fire, liquid water, beds, plants. A breach depressurizes
  with a hiss (and puts out flames).
- **Farming:** wash perchlorates out of regolith to make soil; grow potatoes in a habitat.
- **Lighting:** LED lamps (non-combustion light), moth lanterns and areolichen; glowstone and froglights work; torches
  don't (outside habitats).

## 7. Historic sites and exploration

Structures with commemorative plaques at the real landing coordinates: Viking 1, Viking 2, Mars Pathfinder +
Sojourner, Spirit, Opportunity, Phoenix, Curiosity, InSight, Perseverance + Ingenuity, Zhurong. A Mars atlas item
shows the MOLA map, your position and the sites. Advancements reward visiting them and reaching landmarks
(Olympus Mons summit, Hellas floor, Valles Marineris floor, the polar caps).

## 8. The fiction layer: life and the Areans

The planet is science; what lives on it and what is buried in it is fiction. SCIENCE.md marks fiction as fiction.
The aim is that Mars plays like a dimension of its own, as the Nether and the End do.

### 8.1 Premise

- About four billion years ago, while Mars was still warm and wet and had a magnetic field (the real dynamo died
  ~4 Ga), a civilization arose there: the **Areans** (fiction; "Arean" is the adjective for Mars, as in areology).
- As the field died and the air was stripped away, the Areans withdrew underground into lava tubes and sealed
  vaults, re-engineered their biosphere to live in the cold and the dark without air, and then went silent. Their
  machines still keep watch.
- Two war-constructs, **Phobos** and **Deimos** (Fear and Dread, the sons of Ares), sleep beneath the Cydonia
  mesas, under the "Face on Mars".
- A previous human mission, the **Hesperia Expedition** (fiction), landed years before the player and vanished.
  Its camps and logbooks are the first clues.

### 8.2 Where the fiction lives

- **The surface stays barren and true**: no plants, no liquid water, a sky that follows the measurements. What
  threatens you there is the environment, plus Dust Stalkers at night and in storms, Dust Wraiths in global
  storms, and Arean ruins half-buried in dust.
- **Underground is alive**: lava tubes in the volcanic provinces, ice caves under the polar caps and the glacier
  belt, brine grottoes, crystal geodes in canyon walls, and the Arean deep near bedrock. Life hid there from the
  radiation, which is what an astrobiologist would expect.

### 8.3 Cave biomes (3D biomes below the surface)

| Biome | Where | Character |
|---|---|---|
| `lichen_hollows` | lava tubes under Tharsis, Elysium and the volcanic plains, opened to the surface by skylights | glowing areolichen walls, rustcap fungus groves, grazers, moths |
| `brine_grottoes` | under the polar caps and the mid-latitude glacier belt | blue ice caves, perchlorate brine pools that never freeze, rime blooms, brine eels |
| `gypsum_geodes` | canyon walls (Valles Marineris), Gale | giant selenite crystals that let light through, salt spires |
| `arean_deep` | near bedrock everywhere | dark ferrous rock, ruined Arean machinery, custodians |

### 8.4 Native life: blocks

- **Areolichen**: a glowing crust (light 10) on cave surfaces. Torches don't burn on Mars, so outside a habitat
  your light is what you brought from Earth or what you harvest here.
- **Rustcap**: a giant fungus (stem, cap and gill blocks), Mars' wood. Stems make planks, doors, crafting tables;
  like Nether woods it doesn't burn.
- **Ember moss**: warm-glowing moss carpets. Moved to the surface it slowly dies under the radiation unless it is
  inside a habitat.
- **Rime bloom**: crystalline frost flowers in the ice caves; brewed into a cryo tonic that halves oxygen use for a
  while (a native torpor compound).
- **Salt spires**: perchlorate crystal stalagmites (pointed, like dripstone); a perchlorate source for oxygen
  candles.
- **Brine**: perchlorate brine, a liquid that neither evaporates nor freezes (real chemistry: these salts keep
  water liquid far below 0 °C). Undrinkable; a water extractor turns it into water and perchlorate.

### 8.5 Creatures

None of them breathe, none burn, and all fall at Mars gravity.

| Creature | Kind | Where and when | Behaviour | Drops and uses |
|---|---|---|---|---|
| Rimeback | passive, breedable | lichen hollows, brine grottoes | slow six-legged grazer; bred with areolichen | hide (suit padding), meat |
| Lumen moth | ambient | caves | glowing flyer; can be caught in a bottle | moth lantern (light without fire) |
| Brine eel | aquatic, hostile | brine pools | lunges from the brine | brine gland (cryo tonic) |
| Dust stalker | hostile | surface at night and in dust storms, dune fields, caves | waits buried as a dust mound, ambushes with long low-gravity leaps | chitin plates (suit armour), fangs |
| Dust wraith | hostile flyer | only during dust storms | a vortex of statically charged dust; phases through the haze, shocks | charged dust (batteries, thruster fuel) |
| Arean custodian | hostile construct | ruins, sanctums, vaults, the deep | patrols; arc-beam attack | custodian core (power, electronics), Arean alloy scrap |
| Arean sentry | hostile, stationary | vaults | turret that fires magnetic pulses, which throw you far in low gravity | ion lens |
| Phobos | boss | Cydonia | fast melee construct that circles the arena (as Phobos races around Mars) | Heart of Ares (shared) |
| Deimos | boss | Cydonia | slow artillery construct that hovers high (as Deimos crawls across the sky) | |

### 8.6 Structures

| Structure | Like | Where | Contents |
|---|---|---|---|
| Arean ruins | ruined portals | surface, highlands, half-buried in dust | walls of Arean stone and ferroglass; a cache of relic fragments and alloy scrap |
| Hesperia Expedition camps | shipwrecks | a few surface sites | crashed lander, habitat modules, logbooks that tell the story, O₂ tanks, food, tools |
| Lava-tube sanctum | Nether fortress | volcanic provinces, entered by skylights | shrines, custodians, relic fragments, cores, upgrade templates |
| Arean vault | End city and stronghold | one under each great landmark, at fixed coordinates: Candor Chasma, Hellas, Olympus Mons' caldera, Elysium Mons, Chasma Boreale, Planum Australe, Argyre | multi-level complex with sentries and custodians, a Vault Key, the best loot (thruster pack, Arean upgrade templates) |
| Cydonia (the Face) | End portal and dragon arena | the real "Face on Mars" mesa at 40.75° N, 350.54° E | a gate with seven key sockets; below it, the guardians' arena |
| Fresh impact craters | — | rare event anywhere (real: new craters keep appearing) | meteoritic iron-nickel and impact glass |

### 8.7 Materials and gear

- **Relic fragments** → **Ares compass**: points to the nearest unopened vault (like an eye of ender).
- **Arean alloy** (the netherite of Mars): alloy scrap + chromium. An Arean upgrade template upgrades the suit and
  tools: better oxygen efficiency, radiation shielding, durability.
- **Custodian cores**: compact power sources for electronics, an alternative to early solar power.
- **Thruster pack** (the elytra of Mars, where wings don't work): a cold-gas thruster that flies in thin air,
  refuelled with charged dust or propellant.
- **Impact glass** (real: found on Mars from orbit): clear, radiation-blocking glass for habitats.
- **Vault keys** (seven) open Cydonia.
- **Heart of Ares** (boss drop) → **Terraforming engine**: an endgame machine that thickens the air in a slowly
  growing radius: pressure and temperature rise, water stays liquid, then the air becomes breathable and plants
  grow (a positional environment layer, like habitats).

### 8.8 Story and progression on Mars

1. Land and survive. A Hesperia camp's logbook mentions lights deep in the lava tubes.
2. Lava tubes: native life gives light, food and rustcap wood.
3. Ruins and sanctums give relic fragments → the Ares compass.
4. The vaults send you across real Mars (Valles Marineris, Hellas, Olympus Mons, the poles) for seven keys.
5. Cydonia: Phobos and Deimos. The Heart of Ares lets you terraform your base.

The "Red Planet" advancement tree has a science branch (landmarks, landing sites, dose, the flight) and a fiction
branch (life, ruins, vaults, guardians).

### 8.9 Mood

Procedural ambient music for the Mars surface, caves, vaults and the boss fight; cave ambience (dripping brine,
moth wings, distant machinery); dust-storm wind with static crackle when wraiths are near.

## 9. Art and audio

- Textures: procedural pixel art generated by Python/Pillow scripts (blocks, items, GUI, vehicle atlases, sky
  sprites), reviewed visually. Globe textures from real data.
- Sounds: procedural synthesis (Raptor roar and crackle, sonic booms, venting, deluge, Mars wind, machines,
  suit breathing), Ogg Vorbis, mono.
- Particles: exhaust, steam and deluge clouds, venting, dust plumes, entry plasma, Mars dust, CO₂ sublimation.

## 10. Performance budget (8 GB M2)

- Mars data ~30 MB heap, loaded once. Terrain column cost ~0.65 µs; no 3D noise in `final_density`.
- Vehicle meshes: three LODs (≤ 14k quads full stack up close, ≤ 4k far); particle counts capped.
- Interlude and sky: CPU-built meshes, reused buffers.
- Benchmark chunk generation and frame cost in client gametests; target smooth play on the user's machine.

## 11. Testing

- **JUnit:** projection, data grids, landmark checks, astronomy, geometry, terrain cost, flight timeline math,
  telemetry interpolation, altitude mapping, physics formulas.
- **Server gametests:** gravity and drag scaling, fall damage, suffocation timing, fire and water rules, run in a
  real `redplanet:mars` level (the gametest datapack overrides the `flat_all_dimensions` world preset to add a flat
  Mars, since the gametest server otherwise has no datapack dimensions), flight state machine transitions,
  dimension transfer with passengers, logout mid-flight.
- **Client gametests (Xvfb + Mesa):** a full Earth → Mars → Earth round trip with screenshots at every phase and of
  the Mars sky at noon, sunset and night; a curated set goes to `docs/screenshots/`.

## 12. Milestones

| Milestone | Scope | Status |
|---|---|---|
| M0 | Scaffold, toolchain, genSources, API research, data pipeline, pure cores (geo, astro, geometry, terrain) | done |
| M1 | DESIGN.md, SCIENCE.md, API-NOTES-26.3.md | done |
| M2 | Dimension, clocks/timelines, environment attributes, physics rules, weather mixins, debug commands, gametests | next |
| M3 | Worldgen (height function, biome source, material rule, features), cave biomes and native flora, blocks, textures, sky, dust storms | |
| M4 | Starship + booster entities and rendering, flight profiles, controller, camera, HUD, interlude, transfer, landing, return | |
| M5 | Suit and O₂, habitats, ISRU and power, crafting chain, launch site, advancements, sounds | |
| M6 | Fiction: creatures, Arean ruins, sanctums and vaults, Hesperia camps, Cydonia and the twin guardians, gear, terraforming, lore, music | |
| M7 | Polish, client gametest screenshots, performance, docs, install script, release | |

## 13. Risks

| Risk | Mitigation |
|---|---|
| Mixins on moving 26.x internals | Keep them few and small; gametests cover each rule. |
| Rendering API (renderpearl, submit) is new | Build on vanilla render types and the entity submit path first; custom pipelines only where needed. |
| OpenAL EFX may be missing on macOS | Feature-detect; fall back to volume scaling. |
| Server gametests lack the Mars level | The gametest datapack's world preset adds a flat Mars; full trips in client gametests. |
| Memory on 8 GB | Data loaded once; LODs; particle caps; benchmarks. |
| Scope (science + fiction + rocket) | Built in milestones that each leave a playable mod; tests per milestone. |
