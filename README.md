# Red Planet: Starship to Mars

A Fabric mod for **Minecraft Java 26.3** that adds a scientifically grounded **Mars dimension**. You won't reach it
through a portal: you'll ride a full-scale **Starship** from launch to landing, and fly the same sequence home.
Under the true-to-the-data surface lies a fictional layer: the caves of a planet that hid its life underground.

**Status: playable preview (0.2.0).** Mars itself is in: terrain, sky, weather, dust devils, physics, blocks and
commands, plus the living caves beneath it. The Starship, spacesuits and habitats, creatures and structures are
still being built (see [the plan](#whats-next)). For now, travel between Earth and Mars by command.

## Install (macOS, official launcher)

You need the official Minecraft launcher with **26.3 played at least once**, so the game and its bundled Java 25
are downloaded. There's no need to install Java or Fabric yourself.

```bash
git clone --branch claude/busy-carson-9dpa6w https://github.com/Avi130805/Minecraft_Mods.git ~/RedPlanet
# Quit the Minecraft launcher, then:
bash ~/RedPlanet/scripts/install-mac.sh
```

The script installs Fabric Loader 0.19.5 using the launcher's own Java, puts Fabric API 0.161.0+26.3 and
`dist/redplanet-*.jar` into `~/Library/Application Support/minecraft/mods`, and moves older copies aside
without deleting them. Re-run it after every `git pull` to update. It never touches your worlds.
`--uninstall` removes the mod jar again.

Then, in the launcher, pick the **fabric-loader-26.3** installation and press Play. On an 8 GB Mac, give it 4 GB:
go to Installations, then Edit, then More options, and change `-Xmx2G` to `-Xmx4G`.

**Other systems:** install [Fabric Loader](https://fabricmc.net/use/installer/) 0.19.5 for 26.3. Then put
[Fabric API](https://modrinth.com/mod/fabric-api) 0.161.0+26.3 and the jar from `dist/` into your `mods` folder.

Setting up with a local AI assistant? Give it [docs/LOCAL_SETUP_PROMPT.md](docs/LOCAL_SETUP_PROMPT.md).

## What you can do in this build

Create a new world (Creative with cheats on is the easiest way to explore) and try:

| Command | What it does |
|---|---|
| `/redplanet tp mars` | Land at Curiosity's site in Gale crater, below Mount Sharp |
| `/redplanet tp mars <lat> <lon>` | Land anywhere: latitude in degrees north, longitude in degrees east |
| `/redplanet locate <landmark>` | Find one of 27 real places (Olympus Mons, Valles Marineris, Hellas, Jezero, the Face at Cydonia...); click the answer to fly there |
| `/redplanet info` | Where you are on Mars, the local time and season, ground temperature, air pressure, gravity |
| `/redplanet weather dust regional\|global\|clear` | Raise or settle a dust storm |
| `/redplanet weather devil` | Raise a dust devil nearby |
| `/redplanet tp earth` | Go home |

**The planet.**
- **Terrain:** it comes from real MOLA laser altimetry, at 1 block = 1 km across and 100 m up (a 10× vertical
  exaggeration, so the relief reads at Minecraft scale). The map wraps around the planet: walk east for 21,338 blocks
  and you're back where you started.
- **Biomes:** 14 surface biomes follow the real geography: the northern plains, the cratered southern highlands,
  Tharsis and its shield volcanoes, Valles Marineris, Hellas, the dune fields, both polar caps, the mid-latitude
  glaciers, Meridiani's hematite "blueberries", Gale's layered Mount Sharp, and Jezero's delta.
- **Features:** real catalogue craters, fresh small craters, boulder fields, iron-nickel meteorites, and drained lava
  tubes (with collapsed skylights) under the volcanoes.

**The sky.**
- **The Sun and sunsets:** the Sun is its true size (0.35°). The noon sky is butterscotch, and sunsets are blue,
  followed by a long dusty twilight.
- **Moons and stars:** Phobos and Deimos move on their real orbits (Phobos rises in the west, twice a sol). Earth
  and the Moon appear as an evening or morning star. 5,080 catalogue stars turn about Mars' own pole.
- **Dust storms:** they darken and brown the sky, close in the horizon, dim the Sun and roar past you.
- **Dust devils:** they cross the plains on afternoons in the dusty season and swirl loose items.

**The physics.**
- **Gravity:** 0.38 g, so you jump higher and fall slower.
- **Air:** thin CO₂. Unprotected, you black out and die in about two minutes (Creative and Spectator are exempt).
  Survival players can switch this off: set `"hypoxiaDamage": false` in `config/redplanet-server.json`, then restart.
  Beds don't work in Mars' air. Pressurized habitats arrive with the life-support update.
- **Time and seasons:** sols are 24 h 39 min long and the year lasts 668.6 sols, with seasons of different lengths.
- **Temperature and weather:** ground temperatures follow latitude, season, time of day and dust. Dry ice and CO₂
  frost sublimate once the ground warms past CO₂'s frost point, exposed water ice slowly sublimates, and it never
  rains.

**Materials.**
- **Rock and soil:** regolith, dust, basaltic sand, Mars stone, basalt, mudstone, layered and delta sediments, clay
  and carbonates, with building families (stairs, slabs, walls).
- **Ices:** polar water ice and CO₂ ice.
- **Ores:** hematite, olivine, jarosite, gypsum, sulfur and chromite, plus chromite back on Earth. They smelt to
  iron, chromium and nickel.
- **Native cave life (fiction):** areolichen, ember moss, the rustcap fungus and its whole wood set, rime bloom,
  salt spires and selenite.

**The caves (fiction).** Dig or follow a lava-tube skylight down. Under the volcanoes lie the **lichen hollows**:
rustcap groves with glowing gills, ember moss and walls of glowing lichen. In and under the ice caps lie the
**brine grottoes**, with rime-bloom meadows and salt spires. Under the sulfate lands of Valles Marineris, Gale and
Meridiani lie the **gypsum geodes**, full of selenite crystals. Near bedrock everywhere is the **Arean deep**.
Native life is the fiction layer's; the minerals are real. Try `/redplanet tp mars -5 250` and dig down 30–60
blocks.

All the numbers, their sources and the deliberate compromises are in [docs/SCIENCE.md](docs/SCIENCE.md); the game
design is in [docs/DESIGN.md](docs/DESIGN.md).

## What's next

1. **Brine:** perchlorate brine pools in the grottoes and a water extractor, plus the brine eels.
2. **The Starship:** build the stack, then launch, stage, coast, re-enter in a plasma glow, and do the belly-flop
   and flip to land on Mars. Then refuel from local ice and CO₂ (the Sabatier process) and fly home the same way.
3. **Living on Mars:** spacesuits and oxygen, pressurized habitats, power, water extraction, radiation.
4. **The fiction:**
   - **Creatures:** rimebacks, lumen moths, dust stalkers, dust wraiths.
   - **Places:** Arean ruins and vaults at real landmarks, and the camps of the lost Hesperia expedition.
   - **The finale:** the twin guardians Phobos and Deimos under the Face at Cydonia.

## Building from source

You need JDK 25. Run `./gradlew build` and the jar appears in `build/libs/`. Other tasks:
- `./gradlew runGameTest`: server gametests.
- `scripts/run-client-gametests.sh`: client gametests, which take screenshots under Xvfb.
- `./gradlew runDatagen`: regenerate models, loot, tags and recipes.

Contributor notes are in [CLAUDE.md](CLAUDE.md) and [docs/API-NOTES-26.3.md](docs/API-NOTES-26.3.md).

## License

MIT. Real data credits (MOLA, TES, the Yale Bright Star Catalogue, the crater catalogue) are listed in
[docs/SCIENCE.md](docs/SCIENCE.md).
