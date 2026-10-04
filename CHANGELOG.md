# Changelog

## 0.3.0: the Starship (2026-10-04)

### The rocket
- **Starship and Super Heavy** at true scale, in the V3 configuration: a 52.1 m ship on a 71.9 m booster, a 124 m
  stack, 9 m wide. The procedural meshes include:
  - the ship's tangent-ogive nose, four flaps, three sea-level and three vacuum Raptor 3s, and telescoping legs;
  - the booster's 33 Raptors, its vented hot-staging ring and three grid fins.
- **Visible from afar:** three levels of detail, plus a far impostor that keeps the rocket in view from far beyond
  the render distance.
- **Textures** at 16 px per metre: stainless-steel rings and sheets, hexagonal heat-shield tiles on the windward side
  and crew windows. Frost covers the full propellant tanks, and the windows and navigation lights glow at night. No
  SpaceX logos.
- **Animated:** flaps, engine gimbals, legs (they slide down, then splay), grid fins, venting on the pad, and frost
  that shakes off during the climb.
- **Crew cabin:** eight couches in a ring on the crew deck, under the windows. The cabin is pressurized, so you can
  breathe aboard on Mars. You stay strapped in during flight and sneak to unbuckle on the ground.
- **Items:** Starship and Super Heavy, in the Red Planet tab. Their interim recipes use iron blocks, chromium ingots,
  glass and copper blocks. Use a Super Heavy on the ground, then a Starship on the booster. Hit a landed ship a few
  times to pick it up again.

### The flight
- **Real mission profiles:** the ascent follows the timeline of the V3 flights (Flights 12 and 13). The rest is
  simulated, because it hasn't flown yet:
  - **Earth:** liftoff, max-Q, MECO, hot staging, Super Heavy's boostback and return, ship ascent and orbit.
  - **Transfer:** refilling in orbit, then the trans-Mars injection.
  - **Mars:** entry, peak heating, the belly flop, the flip, the landing burn and touchdown.
  - **Home:** the return mirrors the outbound flight from Mars.

  Profiles are data (`data/<namespace>/redplanet/flight_profile/`), so datapacks can add flights.
- **Three pacings:** short, standard and long. The trip to Mars takes about 3, 6½ or 14 minutes, and the trip home a
  little less.
- **Scaled to fit:** the first 300 m of altitude are true scale, and above that the scale is logarithmic. Staging at
  68 km happens about 1,400 blocks up, and the rocket flies downrange along its launch azimuth.
- **Super Heavy** separates at hot staging, flies its own boostback and comes back down onto the pad, ready for the
  next ship. The tower catch comes with the launch site.
- **The transfer:** between worlds, a full-screen interlude plays:
  - Earth falling away, and refilling in orbit from tanker ships;
  - the injection burn;
  - a Hohmann transfer diagram, with the mission clock running months ahead;
  - Mars growing until entry.

  Meanwhile the server loads the landing site.
- **Landing sites:** any of the 27 landmarks, or any latitude and longitude. The ship looks for the flattest spot
  within 24 blocks. Coming home, it lands beside the pad it left from.
- **Multiplayer:** up to eight crew, and everyone sees the same flight. Skipping ahead takes the whole crew's vote.
  Players on the ground watch the real ascent and the booster's return.

### On screen
- **Cinematic camera:** shots for each phase (pad cameras, chase, onboard, orbit), with shake you can reduce in the
  config. **V** switches to a free orbit around the ship, or to the cabin, where the view turns with the ship as it
  pitches over and falls belly-first.
- **Telemetry HUD** in webcast style:
  - the mission clock and the current milestone;
  - booster and ship panels with speed, altitude, lit engines and LOX and CH₄ bars;
  - the event timeline.
- **Mission control (M):** a shaded-relief map of Mars, drawn from the mod's own MOLA and TES data, with the landing
  sites. Click to choose.
- **The sky darkens** with real altitude as you climb, until the stars come out, and the ground below fades into haze.
- **Plumes:** additive Raptor plumes, with Mach diamonds in thick air, that balloon into a wide glow in thin air and
  on Mars. Also entry plasma along the windward side, and the hot-staging flash. Near the ground, steam (or Martian
  dust) rolls out from under the engines.
- **Sound:** the countdown, ignition, the deluge, staging, engine cutoffs, the booster's sonic boom, entry plasma,
  the flaps, the legs and touchdown. The engine roar fades as the air thins, and a quiet hum fills the cabin.

### Commands
- `/redplanet starship launch [short|standard|long] [<landmark> | at <lat> <lon>]`, `/redplanet starship skip` and
  `/redplanet starship status`.
- For operators: `/redplanet starship spawn ship|stack` and `/redplanet starship launch profile <id>`.

### Tests
- Server gametests fly an uncrewed and a crewed stack to Mars and land them, with the crew aboard and unhurt. Client
  gametests photograph the stack, mission control, and every phase of the flight there and back.

## 0.2.0: the caves (2026-10-04)

### Underground Mars (fiction layer, DESIGN.md section 8.3)
- **Cave biomes:** four 3D cave biomes now generate beneath matching terrain:
  - **lichen hollows** under Tharsis and Elysium;
  - **brine grottoes** in and under the polar caps and the glacier belt;
  - **gypsum geodes** under Valles Marineris, Gale and Meridiani;
  - **the Arean deep** above bedrock everywhere.
- **More caves:** meltwater ice tubes under the caps and glaciers, older and deeper lava tubes under the volcanoes,
  and ordinary caves under the caps.
- **Lichen hollows:** rustcap groves. The giant fungi measure the cave and grow to fit it, so they never break
  through the roof, and they hang glowing gills under their caps. Ember-moss floors and ceilings, walls coated in
  glowing areolichen, and drifting spores.
- **Brine grottoes:** ice-lined channels, glowing rime-bloom meadows, perchlorate crusts and clusters of salt spires.
- **Gypsum geodes:** selenite-lined geodes in sulfate mudstone, and giant selenite beams crossing the caves, like
  the crystals of Naica.
- **The Arean deep:** dark basalt masses and rich ore.
- **Rustcap sprouts:** bonemeal grows a sprout into a giant rustcap, but only on ember moss.
- **Brighter glows:** rime blooms and selenite clusters give more light (7 and 5).

### Weather
- **Dust devils** form in the dusty season (Ls 173-340, peak 250) between 09:30 and 16:30 local time. They wander
  the plains as spiralling columns of dust and swirl loose items, but they can't knock you over (the air is too
  thin). `/redplanet weather devil` raises one. Their frequency is configurable (`dustDevilScale`).
- **Storm wind:** a roaring wind loop rises with the local dust and is muffled underground.
- **Dust particles:** Mars dust now drifts with the wind as soft ochre motes, replacing vanilla's dust particles,
  which sometimes turned olive.

### Sky
- **Phobos** is as bright as its real magnitude, so even a thin crescent outshines the stars.

### Tests
- 28 server gametests, including cave-biome placement under real terrain, rustcap growth, bonemeal, selenite beams
  and dust devils. Client gametests now also photograph each cave biome in generated terrain.

## 0.1.0: playable preview (2026-10-04)

The first build you can install and play: Mars as a place, reached by command until the Starship lands in a later
update.

### Mars
- **Terrain:** the Mars dimension is built from real MOLA topography (1 block = 1 km across, 100 m up), with
  catalogue craters, roughness-matched detail, dunes, and stepped polar layered deposits. The map wraps seamlessly
  around the planet.
- **Biomes:** 14 surface biomes are placed by real geography. Real catalogue craters and fresh small craters,
  boulder fields, iron-nickel meteorites, and drained lava tubes with skylights under the volcanoes.
- **Landmarks:** 27 real landmarks and landing sites. `/redplanet locate` finds them.

### Sky and weather
- **Sun and sunsets:** the Sun is its true size, with its glare and the blue forward-scattering aureole. The noon
  sky is butterscotch, sunsets are blue, and the twilight is long.
- **Moons, Earth and stars:** Phobos and Deimos follow their real orbits, with phases and eclipses. Earth and the
  Moon appear as an evening or morning star. 5,080 catalogue stars turn about Mars' pole.
- **Dust storms:** regional and global storms darken and brown the sky, shorten the view, dim the Sun and fill the
  air with dust.
- **Seasons:** the year is 668.6 sols long, with the real, unequal season lengths.

### Physics and environment
- 0.38 g gravity; a thin CO₂ atmosphere with hypoxia (configurable); a temperature model; dry ice, CO₂ frost and
  water-ice sublimation; no rain; no sleeping in Mars' air.

### Blocks and items
- **Mars materials:**
  - **Rock and soil:** regolith, dust, sands, stones, basalt, sediments, clay and carbonates, with stairs, slabs and
    walls.
  - **Ices:** polar water ice and CO₂ ice.
  - **Ores:** hematite, olivine, jarosite, gypsum, sulfur and chromite, plus chromite on Earth.
- **Native cave life (fiction, creative tab for now):**
  - areolichen, ember moss and carpet;
  - the rustcap fungus with its stems, hyphae, cap and glowing gills, and a full wood set: planks, stairs, slab,
    fence, gate, door, trapdoor, pressure plate and button. Axes strip the stems and hyphae, and like the Nether
    woods it doesn't burn;
  - rime bloom, perchlorate crust, salt spires (speleothems that grow and fall) and selenite;
  - potted rustcap and rime bloom.

### Commands
- `/redplanet tp mars [lat lon]`, `/redplanet tp earth`, `/redplanet info`, `/redplanet locate <landmark>`,
  `/redplanet weather dust regional|global|clear`.

### Install
- `scripts/install-mac.sh` sets up Fabric Loader 0.19.5 and Fabric API with the launcher's own Java, and installs
  `dist/redplanet-0.1.0.jar`. For setting up with a local AI assistant, see `docs/LOCAL_SETUP_PROMPT.md`.
