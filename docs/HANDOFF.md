# Handoff: Starship-to-Mars mod — Minecraft Java 26.3 (Fabric)

You are taking over a new project in this repo (private: `Avi130805/Minecraft_Mods`). The previous session, on the
user's Mac, only did environment research. **No mod code exists yet.** Read this whole brief, then work
autonomously to completion.

## 1. The user's original request (verbatim)

> Create a minecraft mods which tried to another dimension : Mars .
>
> Scientific accuracy .
>
> Rather than a portal ,
> The transportation should look like this : the players sits on spacex starship rocket goes high in the sky , then transistion of landing into another dimension in the same rocket .
>
> Same back and froth animation .
>
> Build it properly , you can use subagents for this task .
>
> Latest minecraft version - mac has minecraft .
>
> Brainstorm .
>
> Rigirous .

Follow-up from the user: **"Do not keep things lean, please."** Don't economize on scope, tooling, testing, or polish.

How to read it:
- A new **Mars dimension**, as scientifically accurate as Minecraft allows. Document every deliberate compromise and why.
- **No portals.** The player sits inside a SpaceX **Starship**, which launches high into the sky. The flight then
  transitions into a **landing in the other dimension, in the same rocket**.
- **"Same back and forth animation":** the Mars → Earth trip mirrors Earth → Mars, using the same cinematic sequence
  in both directions.
- **"Brainstorm" and "rigorous":** design with real numbers and sources before building, then test everything.
- Subagents are explicitly allowed (Agent tool). Use them to parallelize research, art, worldgen, rendering and
  tests, and review everything they return.

## 2. Verified facts (checked 2026-10-04)

**Versions**
- **Minecraft Java 26.3** is the latest release (2026-09-15). 26.4 snapshots exist; don't target them.
- 26.3 requires **Java 25**.
- 26.x ships **unobfuscated**: Mojang's official names, no mappings.

**Toolchain (Fabric)**
- Fabric Loader **0.19.5**.
- Fabric API **0.161.0+26.3**, the newest build for 26.3.
- Loom plugin `net.fabricmc.fabric-loom` **1.18.2**. The template uses `1.18-SNAPSHOT`.
- Gradle wrapper **9.7.1**.
- Dependencies use `implementation`, not `modImplementation`, and there is no `mappings` line.
- `loom { splitEnvironmentSourceSets() }`, with `options.release = 25`.
- Start from the official template: https://github.com/FabricMC/fabric-example-mod, branch `26.3`.

Rejected alternative: NeoForge for 26.3 is still beta (`26.3.0.48-beta`), so it was not chosen.

**The user's machine, where the final jar will be played**
- Apple M2, **8 GB RAM**, macOS 27.
- The official Minecraft launcher, with 26.3 installed and a "latest-release" profile.
- Fabric is **not** installed in the launcher yet.
- There is **no system JDK**. The launcher's bundled Java 25 (it includes `javac`) is at
  `~/Library/Application Support/minecraft/runtime/java-runtime-epsilon/mac-os-arm64/java-runtime-epsilon/jre.bundle/Contents/Home`.
- Performance has to be good on that machine.

## 3. Ground rules for 26.x code

- **Don't write 26.x code from memory.** Rendering, data formats and names changed a lot between 1.21.x and 26.x.
  These are expected but **unverified**; check each one:
  - the GPU abstraction (`RenderPipeline` / `GpuDevice`);
  - entity rendering through render states plus `submit(...)` / `SubmitNodeCollector`;
  - GUI deferred render state;
  - data-driven **environment attributes** on dimension types and biomes (sky, fog, and gameplay flags such as
    `water_evaporates`), and possibly **timelines / world clocks**;
  - `ResourceLocation` possibly renamed to `Identifier`;
  - possibly a Vulkan backend.
- Before designing each system, run `./gradlew genSources` and read the real code, the vanilla data inside the client
  jar, and the vanilla data reports. Record the findings in `docs/API-NOTES-26.3.md`.
- Prefer Fabric API hooks. Use mixins only where no hook exists, and keep them small, targeted and commented.
- Repo layout: the Gradle project goes at the repo root. Mod id `redplanet`, display name
  "Red Planet: Starship to Mars", package `io.github.avi130805.redplanet`, unless you find a strong reason otherwise.
- Don't reproduce SpaceX logo artwork. A faithful Starship shape is fine.

## 4. Design brief

Brainstorm further and decide. **First** write two documents and commit them, then build:
- `docs/DESIGN.md`: decisions and a phased plan.
- `docs/SCIENCE.md`: every number with its source, and how it maps to gameplay.

### 4a. Mars, the real numbers to honor

| Quantity | Real value | Gameplay mapping |
|---|---|---|
| Gravity | 3.721 m/s² (0.379 g) | Scale gravity for **all** entities (players, mobs, items, arrows, falling blocks), not only via the living-entity gravity attribute. Jumps ≈ 2.6× higher. Safe fall ≈ 3 / 0.379 ≈ 7.9 blocks. Fall damage ∝ g·h. |
| Atmosphere density | ~0.020 kg/m³ (≈ 1.6% of Earth's) | Near-zero air drag, so projectiles fly far and elytra barely glide. |
| Atmosphere pressure and composition | ~610 Pa mean (0.6% of Earth's); ~95% CO₂, ~2.6% N₂, ~1.9% Ar, ~0.16% O₂ | Unbreathable. Below the Armstrong limit (6.3 kPa), so ebullism. Useful consciousness ≈ 10–15 s, which matches vanilla max air (300 ticks = 15 s): reuse the air/bubble mechanic. Needs a pressure suit and an O₂ supply. |
| Pressure by elevation | ~1.2 kPa on the Hellas floor, ~70 Pa on the Olympus Mons summit | Show the varying value in the suit HUD. |
| Combustion | No free O₂ | No fire, torches, campfires or fuel-burning furnaces outside pressurized or oxygenated contexts. Provide non-combustion alternatives. |
| Water | Triple point 611.657 Pa | Liquid water is unstable, and ice sublimates instead of melting. Check whether a vanilla `water_evaporates` attribute exists. Ice is stable at the poles and underground. |
| Polar caps | Water ice, plus seasonal CO₂ frost (frost point ≈ −125 °C / 148 K at Mars pressure) | Polar-cap terrain with both ice types. |
| Temperature | Mean ≈ −63 °C; extremes ≈ −140 °C (polar winter night) to ≈ +20 °C (equatorial summer afternoon) | Shown in the HUD; depends on time of sol, latitude/biome and elevation. |
| Sol | 24 h 39 m 35 s = 1.0275 Earth days | ≈ 24,660 ticks, if 26.3 allows a per-dimension day length (investigate timelines/clocks). |
| Year and axial tilt | 687 Earth days (668.6 sols); tilt 25.19° | Seasons. |
| Sun | Apparent size ≈ 0.35° (≈ 2/3 of the view from Earth); irradiance ≈ 43% (≈ 586 W/m²) | Smaller, slightly dimmer sun. |
| Sky color | Butterscotch/tan by day (≈ 1.5 µm iron-oxide dust); **blue sunsets** from forward scattering | Custom sky and sunset colors. |
| Phobos | ≈ 0.2° apparent size; period 7 h 39 m; rises in the **west**, sets in the east; ≈ 4 h 15 min above the horizon; twice per sol; not visible above ~70° latitude | Rendered moon. |
| Deimos | Star-like ≈ 0.03°; period 30.3 h; rises in the east; up ≈ 2.7 sols | Rendered moon. |
| Earth | Bright bluish "evening/morning star", never more than ~46° from the Sun | Rendered star. |
| Geology | Basaltic crust; nanophase iron-oxide dust; hemispheric dichotomy (smooth northern lowlands vs cratered southern highlands); craters with rims, ejecta and central peaks; shield volcanoes (Olympus Mons 21.9 km high, ~600 km wide); canyon systems (Valles Marineris up to 7 km deep); dark basaltic dune fields; layered sediments (Gale / Mount Sharp); dried deltas (Jezero) | Worldgen. Terrain must be vertically compressed to fit Minecraft: pick a scale, consider a taller Mars dimension (e.g. height 512), and benchmark on 8 GB. |
| Minerals and materials | Hematite "blueberries" (Meridiani), jarosite, gypsum veins, perchlorate-laced soil (toxic), olivine, clays/carbonates, elemental sulfur (Curiosity, 2024), iron-nickel meteorites (Opportunity's "Heat Shield Rock") | Blocks and ores. No lava lakes or aquifers: volcanism is essentially extinct. |
| Weather | Dust devils; regional/global dust storms (less sunlight, less visibility); water-ice and CO₂ clouds; CO₂ snow at the poles; no rain | Weather replacement. |
| Life | None | No native mobs or plants. Earth plants and animals need life support. |
| Radiation | ≈ 0.64 mSv/day on the surface, ≈ 1.8 mSv/day in cruise (Curiosity RAD); no global magnetic field | Optional dosimeter mechanic. |
| Sound | Quiet and muffled; speed ≈ 240 m/s, with treble faster (~250 m/s), as measured by Perseverance | Lower volume and a low-pass filter on Mars. |
| Earth–Mars light-time | 3–22 minutes | Flavor; optional comm-delay mode. |
| ISRU | MOXIE made O₂ from CO₂ on Mars (2021–23); Sabatier: CO₂ + 4H₂ → CH₄ + 2H₂O | Oxygen generator, and methalox propellant for the return trip. |

### 4b. Starship, the real vehicle and flight profile

**Vehicle**
- **Ship:** ~52 m tall, 9 m diameter, 6 Raptors (3 sea-level + 3 vacuum), 4 flaps, stainless steel, with a black
  hexagonal-tile heat shield on the windward side.
- **Super Heavy:** ~71 m, 33 Raptors.
- **Full stack:** ~121–124 m. At 1 block = 1 m a true-scale stack fits under the build limit. Prefer true scale.

**Earth → Mars**
1. Propellant load, with venting and frost.
2. Countdown and Raptor ignition sequence.
3. Liftoff.
4. Max-Q (~T+1:00).
5. MECO and **hot staging** (~T+2:40, ~65–70 km). The booster boosts back to a tower "chopsticks" catch.
6. SECO / orbit.
7. Orbital refilling (tanker flights).
8. Trans-Mars injection, possible only in launch windows every ~26 months.
9. ~6–9 month coast (Hohmann ≈ 259 days).
10. Direct Mars entry at ~7.5 km/s, belly-first, with plasma.
11. Belly-flop skydive on the flaps.
12. **Flip maneuver** and Raptor landing burn.
13. Touchdown with a dust plume.

**Mars → Earth**
- The **ship alone** launches: Mars ascent Δv ≈ 4.1 km/s, refilled via ISRU.
- Then trans-Earth injection, coast, and Earth entry (faster than Mars entry, so brighter plasma).
- Then belly flop, flip and landing, back at the launch site or near the player's spawn.

### 4c. Required experience

The player boards a Starship and sits inside it as a passenger. They stay in the **same** rocket the whole way.

Recommended approach (verify feasibility in 26.3):
- **Ship entity:** a true-scale custom entity with a high-quality model (smooth cylinders and ogive via custom
  geometry, or a well-built part model), with animated flaps, engine gimbal and landing legs. Give it a large culling
  box and tracking range.
- **Ascent:** the entity really climbs in-world. It passes through the cloud layer, the sky darkens toward black with
  altitude, and stars appear. At hot staging the booster visibly separates and falls away.
- **Transfer:** a space interlude, drawn as an overlay over the world *and* over the vanilla dimension-loading screen,
  hides the dimension change. It shows Earth shrinking, a refilling/TMI beat, a mission clock jumping months ahead,
  and Mars growing.
- **Arrival:** the same ship (same identity, crew, cargo and propellant) appears high above a pre-generated,
  pre-validated landing site. It flies entry plasma, belly flop, flip, landing burn and touchdown.
- **Return:** an exact mirror of the outbound trip.
- **Camera:** a cinematic camera controller with orbitable or keyframed shots, a launch-pad view, and a skip key.
  Durations are configurable (short/standard/long). Include reduce-shake and photosensitivity options.
- **Telemetry HUD:** a SpaceX-webcast-style display showing the T± clock, speed (km/h), altitude (km), engine status
  and LOX/CH₄ levels. Drive it from a data file of the **real** profile, time-compressed for gameplay, so the numbers
  stay true while the in-world motion is scaled. Make the whole flight timeline data-driven: phases, durations,
  telemetry keyframes and camera keyframes.
- **Server and robustness:**
  - The flight state machine is server-authoritative.
  - It survives logout or a crash mid-flight, by finishing or rolling back cleanly.
  - Multiplayer works: several passengers per ship, and spectators can watch launches.
  - Chunks at the landing site are loaded before arrival.

### 4d. Progression and content

The user wants it built properly, so go full-featured, but finish and polish the core trip first.

- **Rocket crafting:** Raptor engine (copper-alloy combustion chamber, accurate to its regenerative cooling),
  stainless steel, ship and booster items, and a launch mount/pad block.
- **Spacesuit:** methalox propellant, plus a spacesuit (helmet, suit with life support, O₂ tanks) with a suit HUD
  showing O₂, suit pressure, outside pressure and temperature, and dose.
- **ISRU:** MOXIE O₂ generator, Sabatier reactor and solar power. Dust reduces panel output, as it did for InSight
  and Opportunity.
- **Mars blocks:** regolith, dust, basalt, the ores above, water ice, CO₂ ice (sublimates), layered sediment and
  meteorite iron.
- **Biomes:** lowlands, highlands, Tharsis volcanic, canyon, polar caps, dune fields and crater fields.
- **Structures:** crater features, a shield volcano, and rare historic lander/rover sites with plaques (Viking,
  Pathfinder/Sojourner, Spirit, Opportunity, Curiosity, Perseverance + Ingenuity).
- **Advancements**, for example "One Small Step" and "Blue Sunset".
- **Sounds:** Raptors, sonic booms, thin-air muffling and Mars wind.
- **Integration:** creative tab, recipes, loot tables, tags, en_us lang and a config screen. Use datagen where it
  reduces format mistakes.
- **Textures:** pixel-art in vanilla style, generated procedurally (Python/Pillow) and reviewed visually.

### 4e. Suggested phasing

| Milestone | Scope |
|---|---|
| M0 | Scaffold from the template, JDK 25, green build, genSources, and `docs/API-NOTES-26.3.md`. Fan out subagents per area: dimension types / environment attributes / timelines, worldgen, entity rendering, camera, HUD and screens, the loading screen, networking, gametests. |
| M1 | `docs/DESIGN.md` and `docs/SCIENCE.md`. |
| M2 | Mars dimension and physics rules (gravity, drag, atmosphere, fire, water), with tests. A debug command for teleporting. |
| M3 | Terrain, biomes, blocks, textures, sky (sun size, Phobos, Deimos, blue sunset, Earth star) and weather. |
| M4 | The Starship entity, model, flight state machine, camera, HUD, interlude, dimension transfer, landing, and the mirrored return. |
| M5 | Suit and O₂, ISRU, crafting, advancements and sounds. |
| M6 | Polish, client-gametest screenshots, performance, docs, install script and release. |

## 5. Verification

- `./gradlew build` must be green.
- **Server gametests** for:
  - gravity and drag scaling;
  - suffocation timing;
  - fire and water rules;
  - flight state-machine transitions;
  - dimension transfer with passengers;
  - logout mid-flight.
- **Client gametests** (`fabric-client-gametest-api-v1`):
  - Run headless under **Xvfb + Mesa** (llvmpipe; lavapipe if 26.3 uses Vulkan). Loom's production-run tasks
    support `useXVFB` on Linux.
  - Fly a full Earth → Mars → Earth round trip.
  - Capture screenshots at every phase: countdown, liftoff, cloud pass, staging, interlude, entry plasma, belly flop,
    flip, landing burn, touchdown, and the Mars sky at noon, sunset and night.
  - **Look at them** and iterate on the visuals.
  - Commit a curated set to `docs/screenshots/`.
- Benchmark chunk generation and frame cost. Target smooth play on an 8 GB M2.

## 6. Deliverables

- Source in this repo, committed in logical steps and pushed.
- A release jar, attached to a GitHub Release in this private repo, or in `dist/` if releases aren't possible.
- `scripts/install-mac.sh`, which the user runs themselves. It:
  - installs Fabric Loader 0.19.5 for 26.3 into the official launcher, using the launcher's bundled Java (path above);
  - copies Fabric API 0.161.0+26.3 and the mod jar into `~/Library/Application Support/minecraft/mods`;
  - backs up anything it touches.
- Docs: `README.md` (features, controls, install), `docs/DESIGN.md`, `docs/SCIENCE.md`,
  `docs/API-NOTES-26.3.md` and `CHANGELOG.md`.

## 7. Cloud environment

- **JDK 25:** install Temurin 25 if `java -version` reports less.
- **Network access** is needed to these domains: `maven.fabricmc.net`, `meta.fabricmc.net`, `piston-meta.mojang.com`,
  `piston-data.mojang.com`, `launchermeta.mojang.com`, `libraries.minecraft.net`, `resources.download.minecraft.net`,
  `services.gradle.org`, `downloads.gradle.org`, `plugins.gradle.org`, `repo.maven.apache.org`, `github.com`,
  `objects.githubusercontent.com` and `api.adoptium.net`. If downloads are blocked, stop and tell the user to switch
  this environment's network access to **Full**, or to allowlist those domains.
- **Headless client tests:** install Xvfb and the Mesa GL/Vulkan drivers.
- `ds-task`, the user's local DeepSeek delegation tool, does **not** exist in the cloud. Use Agent subagents.
