# Changelog

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
