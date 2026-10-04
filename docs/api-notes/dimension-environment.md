# Dimensions, environment attributes, timelines, clocks, weather, teleports and chunk tickets (Minecraft Java 26.3)

Research notes for **Red Planet: Starship to Mars** (`redplanet`). Written 2026-10-04.

Everything here comes from reading the real 26.3 code. Nothing is from memory.

| Source | Path |
|---|---|
| Decompiled common/server code | `/home/user/mcsrc/common` (cited below as `common/...`) |
| Decompiled client-only code | `/home/user/mcsrc/client` (cited as `client/...`) |
| Vanilla data from the client jar | `/home/user/mcsrc/jar-client/data/minecraft` (cited as `data/...`) |
| Fabric API 0.161.0+26.3 sources | `/home/user/mcsrc/fabric-api/src` (cited as `fabric/...`) |

`file:NN` points to a line number in the decompiled source. The numbers are approximate. Anything not proven by
reading code is marked **UNVERIFIED**.

---

## 0. Key findings

1. **You can register your own environment attributes.** Put them in `BuiltInRegistries.ENVIRONMENT_ATTRIBUTE` from
   `ModInitializer.onInitialize()`, then use them in your `dimension_type`, biome (positional attributes only) and
   timeline JSON. The attribute codec works **by name**, so no raw-ID registry sync is needed.
   - Mark an attribute `.syncable()` if the client needs it. Otherwise it is stripped from the copy sent to clients.
2. **A custom world clock with a 24 660-tick sol works with plain data:**
   - `data/redplanet/world_clock/mars.json` containing `{}`;
   - a timeline with `"clock": "redplanet:mars", "period_ticks": 24660`;
   - in the dimension type, `"default_clock": "redplanet:mars"` and `"timelines": "#redplanet:in_mars"`.

   Sleeping, `/time` and daylight detectors then follow the Mars clock.
3. **Clocks and weather are server-global, not per dimension.**
   - One `ServerClockManager` ticks every clock every tick, wherever the players are.
   - One `WeatherData` holds the rain and thunder state for every dimension that can have weather.
   - Any dimension with skylight, no ceiling and a key other than `the_end` *shares* the overworld rain. It also
     *advances the shared weather timers a second time each tick*.
   - The only way to opt out is a tiny mixin on `Level.canHaveWeather()`. No JSON field or attribute does it.
4. **The overworld's rain state also reaches players in other dimensions.** `START_RAINING`/`STOP_RAINING` and one
   `RAIN_LEVEL_CHANGE` are broadcast to **all** players, whatever dimension they are in. The client sky renderer uses
   that raw rain level to fade the sun, moon and stars.
5. **`skybox: "overworld"` always draws a sun, a moon and stars at the angles the attributes give.**
   - With no timeline, the attribute defaults put the sun *and* a full moon at the zenith permanently.
   - The Mars timeline therefore has to drive `sun_angle`/`star_angle` and park `moon_angle`.
6. **Cross-dimension `Entity.teleport` rebuilds non-player entities.**
   - The new instance and new network id come from an NBT round trip; the UUID is preserved.
   - Passengers are teleported first and then re-mounted on the new vehicle.
   - Players are moved, not recreated, and stay seated.
   - The client always gets `ClientboundRespawnPacket`, which opens a `LevelLoadingScreen`.
7. **Use `ServerChunkCache.addTicketAndLoadWithRadius(TicketType, ChunkPos, radius)` to pre-load chunks without
   blocking.**
   - It returns a `CompletableFuture`. Pair it with `whenCompleteAsync(..., server)`; vanilla does the same for player
     spawn.
   - Custom `TicketType(timeout, flags)` records register in `BuiltInRegistries.TICKET_TYPE`.
   - A dimension with no ticket carrying `FLAG_KEEP_DIMENSION_ACTIVE` stops ticking entities 300 ticks after the last
     player leaves.
8. **Mod datapack dimensions are added to existing worlds too.** `WorldDimensions.bake` merges the datapack
   `dimension` registry with the dimensions saved in the world, and the datapack wins. Fabric mod data packs are always
   enabled. Fabric's `fabric-dimensions-v1` adds:
   - `DimensionEvents.MODIFY_ATTRIBUTES`;
   - fail-soft loading after a mod is uninstalled;
   - suppression of the "experimental" warning.

---

## 1. Dimension type JSON (`data/<ns>/dimension_type/<name>.json`)

### 1.1 Record and codec

`common/net/minecraft/world/level/dimension/DimensionType.java:28-104`

```java
public record DimensionType(
    boolean hasFixedTime, boolean hasSkyLight, boolean hasCeiling, boolean hasEnderDragonFight,
    double coordinateScale, int minY, int height, int logicalHeight, HolderSet<Block> infiniburn,
    float ambientLight, DimensionType.MonsterSettings monsterSettings, DimensionType.Skybox skybox,
    CardinalLighting.Type cardinalLightType, EnvironmentAttributeMap attributes,
    HolderSet<Timeline> timelines, Optional<Holder<WorldClock>> defaultClock)
// codec (createDirectCodec, :81-104):
Codec.BOOL.optionalFieldOf("has_fixed_time", false)
Codec.BOOL.fieldOf("has_skylight")
Codec.BOOL.fieldOf("has_ceiling")
Codec.BOOL.fieldOf("has_ender_dragon_fight")
Codec.doubleRange(1.0E-5F, 3.0E7).fieldOf("coordinate_scale")
Codec.intRange(MIN_Y, MAX_Y).fieldOf("min_y")
Codec.intRange(16, Y_SIZE).fieldOf("height")
Codec.intRange(0, Y_SIZE).fieldOf("logical_height")
RegistryCodecs.holderSet(Registries.BLOCK).fieldOf("infiniburn")
Codec.FLOAT.fieldOf("ambient_light")
MonsterSettings.CODEC  // inline: "monster_spawn_light_level" (IntProvider 0..15), "monster_spawn_block_light_limit" (int 0..15)
Skybox.CODEC.optionalFieldOf("skybox", Skybox.OVERWORLD)
CardinalLighting.Type.CODEC.optionalFieldOf("cardinal_light", CardinalLighting.Type.DEFAULT)
attributeMapCodec.optionalFieldOf("attributes", EnvironmentAttributeMap.EMPTY)
RegistryCodecs.holderSet(Registries.TIMELINE).optionalFieldOf("timelines", HolderSet.empty())
WorldClock.CODEC.optionalFieldOf("default_clock")
```

`DIRECT_CODEC` uses `EnvironmentAttributeMap.CODEC`. The `NETWORK_CODEC` sent to clients uses
`EnvironmentAttributeMap.NETWORK_CODEC`, which drops attributes that are not syncable (`:53-54`).

### 1.2 Fields

| JSON field | Req.? | What it does (consumer) |
|---|---|---|
| `has_skylight` | yes | Light engine has a sky-light layer (`ChunkMap.java:194`, `ClientChunkCache.java:38`, `SerializableChunkData.java:216`). Part of `canHaveWeather()` (`Level.java:937-939`). Daylight detectors tick only if true (`DaylightDetectorBlock.java:98`). Phantom-spawner checks it. |
| `has_ceiling` | yes | Part of `canHaveWeather()`. Used by spawn search (`PlayerSpawnFinder.java:146`), natural spawning (`NaturalSpawner.java:422`) and map rendering (`MapItem.java:96,122`). |
| `has_ender_dragon_fight` | yes | `ServerLevel` creates an `EnderDragonFight` saved data if true (`ServerLevel.java` ctor). |
| `has_fixed_time` | no (false) | If true, `isBrightOutside()` and `isDarkOutside()` both return **false** (`Level.java:374-380`). Beds with `when_dark` then never work, and AI "is it day" checks are off. It does **not** stop clocks. |
| `coordinate_scale` | yes | Only for nether-portal position scaling (`NetherPortalBlock.java:134`) and `/execute in` coordinate scaling (`CommandSourceStack.withLevel`, `:324`). Use `1.0`. |
| `min_y` / `height` / `logical_height` | yes | See 1.3. `logical_height` is used only for portal placement (`PortalForcer.java:59`) and the chorus-fruit teleport range (`TeleportRandomlyConsumeEffect.java:56`), via `ServerLevel.getLogicalHeight()`. |
| `infiniburn` | yes | Block tag or list. Fire never burns out on these blocks (`FireBlock.java:135`). Vanilla: `#minecraft:infiniburn_overworld` (netherrack, magma), `_nether`, `_end` (+ bedrock). |
| `ambient_light` | yes | Floor of the brightness curve, `Mth.lerp(ambientLight, curvedV, 1.0F)`. Used for mob light logic and pathfinding cost (`LevelReader.java:116-120`) and in the lightmap (`client/.../Lightmap.java:92-95`). Overworld 0, nether 0.1, end 0.25. |
| `monster_spawn_light_level` | yes | IntProvider 0..15, e.g. `7` or `{"type":"minecraft:uniform","min_inclusive":0,"max_inclusive":7}`. |
| `monster_spawn_block_light_limit` | yes | Int 0..15. |
| `skybox` | no (`overworld`) | **Allowed values: `none`, `overworld`, `end`** (`DimensionType.Skybox`, `:139-155`). `overworld` draws the sky disc, sunrise fan, sun, moon and stars, driven by attributes (`client/.../SkyRenderer.java:104-127`). `end` draws the static end sky; `ClientLevel.java:266` creates `EndFlashState` (`hasEndFlashes()`). `none` draws nothing. |
| `cardinal_light` | no (`default`) | **Allowed values: `default`, `nether`** (`common/net/minecraft/world/level/CardinalLighting.java`). Per-face block shading: DEFAULT is down 0.5, up 1.0, N/S 0.8, E/W 0.6; NETHER is down 0.9, up 0.9. Used by `GameRenderer.java:839` and `ClientLevel.java:995`. |
| `attributes` | no (empty) | Environment attribute map. **Any** attribute is allowed here, including non-positional ones such as `sky_light_level`. It is the bottom layer; see §2. |
| `timelines` | no (empty) | `HolderSet<Timeline>`: `"#tag"`, a single id or a list (`HolderSetCodec` uses `compactListCodec`). Each timeline adds one time-based layer per tracked attribute (`EnvironmentAttributeSystem.java:68-74`). |
| `default_clock` | no | The clock `/time` acts on in this dimension (`TimeCommand.java:246-249`). Also the clock sleeping advances (`ServerLevel.java:376-380`), what `Level.getDefaultClockTime()` returns (`Level.java:884-890`), and what `GameTestHelper.setTime` uses. Without it, `/time ...` fails with `commands.time.no_default_clock` and sleeping skips no time. The nether has none. |

### 1.3 Height constraints (`DimensionType.java:44-79`)

- `BlockPos.PACKED_HORIZONTAL_LENGTH = 1 + log2(2^25) = 26`, so `PACKED_Y_LENGTH = 64 - 52 = 12` bits.
- `Y_SIZE = (1 << 12) - 32 = 4064`, `MAX_Y = (4064 >> 1) - 1 = 2031`, `MIN_Y = 2031 - 4064 + 1 = -2032`.
- The compact constructor throws (wrapped by `catchDecoderException`, so it becomes a load error) when any of these
  holds:
  - `height < 16`;
  - `min_y + height > 2032`;
  - `logical_height > height`;
  - `height % 16 != 0`;
  - `min_y % 16 != 0`.
- So `min_y` must be in [-2032, 2031], `height` in [16, 4064], and the top build Y is `min_y + height - 1`, at most
  2031.

Check with the worldgen research whether the noise-settings `noise.min_y/height` must sit inside the dimension's range.
That is **UNVERIFIED** here and outside this area.

### 1.4 Vanilla example, copied from `data/dimension_type/overworld.json`

```json
{
  "ambient_light": 0.0,
  "attributes": {
    "minecraft:audio/ambient_sounds": { "mood": { "block_search_extent": 8, "offset": 2.0, "sound": "minecraft:ambient.cave", "tick_delay": 6000 } },
    "minecraft:audio/background_music": {
      "creative": { "max_delay": 24000, "min_delay": 12000, "sound": "minecraft:music.creative" },
      "default":  { "max_delay": 24000, "min_delay": 12000, "sound": "minecraft:music.game" }
    },
    "minecraft:gameplay/bed_rule": { "can_set_spawn": "always", "can_sleep": "when_dark", "error_message": { "translate": "block.minecraft.bed.no_sleep" } },
    "minecraft:gameplay/nether_portal_spawns_piglin": true,
    "minecraft:gameplay/respawn_anchor_works": false,
    "minecraft:gameplay/straw_bed_rule": { "can_set_spawn": "never", "can_sleep": "when_dark", "destroy_on_leave": true, "error_message": { "translate": "block.minecraft.bed.no_sleep" } },
    "minecraft:visual/ambient_light_color": "#0a0a0a",
    "minecraft:visual/cloud_color": "#ccffffff",
    "minecraft:visual/cloud_height": 192.33,
    "minecraft:visual/fog_color": "#c0d8ff",
    "minecraft:visual/sky_color": "#78a7ff"
  },
  "coordinate_scale": 1.0,
  "default_clock": "minecraft:overworld",
  "has_ceiling": false,
  "has_ender_dragon_fight": false,
  "has_skylight": true,
  "height": 384,
  "infiniburn": "#minecraft:infiniburn_overworld",
  "logical_height": 384,
  "min_y": -64,
  "monster_spawn_block_light_limit": 0,
  "monster_spawn_light_level": { "type": "minecraft:uniform", "max_inclusive": 7, "min_inclusive": 0 },
  "timelines": "#minecraft:in_overworld"
}
```

How the other vanilla dimension types differ:

- **`the_end.json`:** `"skybox": "end"`, `"has_fixed_time": true`, `"default_clock": "minecraft:the_end"`,
  `"timelines": "#minecraft:in_end"`, `"monster_spawn_light_level": 15`, and `"minecraft:visual/sky_light_factor": 0.0`.
- **`the_nether.json`:** `"skybox": "none"` and `"cardinal_light": "nether"`. `"has_fixed_time": true`, **no**
  `default_clock`, `"coordinate_scale": 8.0`, `"logical_height": 128`. It also sets these attributes:
  - `"minecraft:gameplay/sky_light_level": 4.0`;
  - `"minecraft:gameplay/water_evaporates": true`;
  - `"minecraft:gameplay/fast_lava": true`;
  - `"minecraft:visual/fog_start_distance": 10.0`, `"fog_end_distance": 96.0`.

There is **no** `data/minecraft/dimension/` folder in the jar. The vanilla level stems live in
`data/minecraft/worldgen/world_preset/normal.json`.

### 1.5 Level stem (`data/<ns>/dimension/<name>.json`)

`common/net/minecraft/world/level/dimension/LevelStem.java`:

```java
public record LevelStem(Holder<DimensionType> type, ChunkGenerator generator)
// CODEC: "type" (DimensionType holder), "generator" (ChunkGenerator, dispatched by "type": minecraft:noise | flat | debug)
```

Example taken from `world_preset/single_biome_surface.json`:

```json
{ "type": "minecraft:overworld",
  "generator": { "type": "minecraft:noise",
                 "biome_source": { "type": "minecraft:fixed", "biome": "minecraft:plains" },
                 "settings": "minecraft:overworld" } }
```

The registry key is `Registries.LEVEL_STEM = minecraft:dimension`, and its directory is `dimension`
(`Registries.java:318`, `elementsDirPath` returns the path).

---

## 2. Environment attributes (`net.minecraft.world.attribute`)

### 2.1 Classes

**`EnvironmentAttribute<Value>`** (`EnvironmentAttribute.java`)

- The constructor is private. Build one with `EnvironmentAttribute.builder(AttributeType<V>)`.
- Builder methods:
  - `.defaultValue(V)` (required; `build()` NPEs without it);
  - `.valueRange(AttributeRange<V>)`;
  - `.syncable()` (default **false**);
  - `.notPositional()` (default **positional**);
  - `.spatiallyInterpolated()`;
  - `.fullResolutionBiomes()` (`@Deprecated`).
- `valueCodec()` is the type codec validated by the range. `sanitizeValue()` clamps.

**`AttributeType<Value>`** (record, `AttributeType.java`). Fields:

- `valueCodec`;
- `modifierLibrary` (`Map<OperationId, AttributeModifier>`);
- `modifierCodec`;
- the lerps `keyframeLerp`, `stateChangeLerp`, `spatialLerp`, `partialTickLerp`;
- optional `toFloat` and `toInt`.

`ofInterpolated(...)` and `ofNotInterpolated(...)` are the factories. A not-interpolated type steps: keyframe step at
1.0, state change at 0.0, spatial at 0.5, partial tick at 0.0.

**`AttributeTypes`** (registered in `BuiltInRegistries.ATTRIBUTE_TYPE`):

- `boolean`, `tri_state`, `float`, `angle_degrees`, `rgb_color`, `argb_color`, `integer`;
- `moon_phase`, `activity`, `bed_rule`, `particle`, `ambient_particles`, `background_music`, `ambient_sounds`,
  `mob_spawn_settings`.

**`AttributeRange`:**

- `UNIT_FLOAT` [0,1], `UNIT_FLOAT_EPSILON` [0,0.9999999], `NON_NEGATIVE_FLOAT`;
- `ofFloat(min,max)`, `any()`.

**`EnvironmentAttributeMap`:**

- An immutable `Map<EnvironmentAttribute, Entry(argument, modifier)>`.
- `CODEC` is `Codec.dispatchedMap(EnvironmentAttributes.CODEC, Entry::createCodec)`.
- `NETWORK_CODEC` keeps only syncable attributes.
- `CODEC_ONLY_POSITIONAL` rejects non-positional attributes; biomes use it.
- `Builder`: `set(attr, value)` (override), `modify(attr, modifier, arg)`, `putAll(map)`.
- `applyModifier(attr, base)` is the core operation.

**`EnvironmentAttributeLayer<V>`** is sealed, with three non-sealed functional sub-interfaces:

- `Constant.applyConstant(base)`;
- `Positional.applyPositional(base, Vec3 pos, @Nullable SpatialAttributeInterpolator)`;
- `TimeBased.applyTimeBased(base, int cacheTickId)`.

**`EnvironmentAttributeSystem implements EnvironmentAttributeReader`.** Each `Level` owns one. Its `Builder` methods
are **all public**:

- `addDefaultLayers(Level)`, `addStaticLayers(LevelAccessor)`, `addDynamicLayers(Level)`;
- `addConstantLayer(map)`, `addConstantLayer(attr, layer)`, `addTimeBasedLayer(attr, layer)`,
  `addPositionalLayer(attr, layer)`;
- `addTimelineLayer(Holder<Timeline>, ClockManager)`;
- `build()`.

**`EnvironmentAttributeProbe`** (client camera): `tick(level, pos)`, then `getValue(attr, partialTicks)`. It blends
biomes with a Gaussian 6×6×6 kernel and interpolates between ticks (`EnvironmentAttributeProbe.java`,
`GaussianSampler.java`).

**`SpatialAttributeInterpolator`:** a weighted set of biome attribute maps. `applyAttributeLayer` lerps the per-biome
results with `type.spatialLerp()`.

### 2.2 The 51 vanilla attributes (from `EnvironmentAttributes.java`)

All are in the `minecraft:` namespace. "positional = no" means `notPositional()`: read it with `getDimensionValue`, and
biomes cannot set it.

| id | type | default | range | positional | syncable | spatially interpolated |
|---|---|---|---|---|---|---|
| `visual/fog_color` | rgb_color | `#000000` |  | yes | yes | yes |
| `visual/fog_start_distance` | float | `0.0` |  | yes | yes | yes |
| `visual/fog_end_distance` | float | `1024.0` | ≥0 | yes | yes | yes |
| `visual/sky_fog_end_distance` | float | `512.0` | ≥0 | yes | yes | yes |
| `visual/cloud_fog_end_distance` | float | `2048.0` | ≥0 | yes | yes | yes |
| `visual/water_fog_color` | rgb_color | `#050533` |  | yes | yes | yes |
| `visual/water_fog_start_distance` | float | `-8.0` |  | yes | yes | yes |
| `visual/water_fog_end_distance` | float | `96.0` | ≥0 | yes | yes | yes |
| `visual/sky_color` | rgb_color | `#000000` |  | yes | yes | yes |
| `visual/sunrise_sunset_color` | argb_color | `#00000000` |  | yes | yes | yes |
| `visual/cloud_color` | argb_color | `#00000000` (no clouds: clouds render only if alpha > 0, `LevelRenderer.java:402`) |  | yes | yes | yes |
| `visual/cloud_height` | float | `192.33` |  | yes | yes | yes |
| `visual/sun_angle` | angle_degrees | `0.0` |  | yes | yes | yes |
| `visual/moon_angle` | angle_degrees | `0.0` |  | yes | yes | yes |
| `visual/star_angle` | angle_degrees | `0.0` |  | yes | yes | yes |
| `visual/moon_phase` | moon_phase | `full_moon` |  | yes | yes | no |
| `visual/star_brightness` | float | `0.0` | [0,1] | yes | yes | yes |
| `visual/block_light_tint` | rgb_color | `#ffd88c` |  | yes | yes | yes |
| `visual/sky_light_color` | rgb_color | `#ffffff` |  | yes | yes | yes |
| `visual/sky_light_factor` | float | `1.0` | [0,1] | yes | yes | yes |
| `visual/night_vision_color` | rgb_color | `#999999` |  | yes | yes | yes |
| `visual/ambient_light_color` | rgb_color | `#000000` |  | yes | yes | yes |
| `visual/default_dripstone_particle` | particle | `dripping_dripstone_water` |  | yes | yes | no |
| `visual/ambient_particles` | ambient_particles | `[]` |  | yes | yes | no (list cross-fade) |
| `audio/background_music` | background_music | empty |  | yes | yes | no |
| `audio/music_volume` | float | `1.0` | [0,1] | yes | yes | no |
| `audio/ambient_sounds` | ambient_sounds | empty |  | yes | yes | no |
| `audio/firefly_bush_sounds` | boolean | `false` |  | yes | yes | no |
| `gameplay/sky_light_level` | float | `15.0` | [0,15] | **no** | yes | no |
| `gameplay/can_start_raid` | boolean | `true` |  | yes | no | no |
| `gameplay/water_evaporates` | boolean | `false` |  | yes | yes | no |
| `gameplay/bed_rule` | bed_rule | can_sleep `when_dark`, can_set_spawn `always` |  | yes | no | no |
| `gameplay/straw_bed_rule` | bed_rule | `when_dark` / `never` / destroy_on_leave |  | yes | no | no |
| `gameplay/respawn_anchor_works` | boolean | `false` |  | yes | no | no |
| `gameplay/nether_portal_spawns_piglin` | boolean | `false` |  | yes | no | no |
| `gameplay/fast_lava` | boolean | `false` |  | **no** | yes | no |
| `gameplay/increased_fire_burnout` | boolean | `false` |  | yes | no | no |
| `gameplay/eyeblossom_open` | tri_state | `default` |  | yes | no | no |
| `gameplay/turtle_egg_hatch_chance` | float | `0.002` | [0,1] | yes | no | no |
| `gameplay/piglins_zombify` | boolean | `true` |  | yes | yes | no |
| `gameplay/snow_golem_melts` | boolean | `false` |  | yes | no | no |
| `gameplay/creaking_active` | boolean | `false` |  | yes | yes | no |
| `gameplay/surface_slime_spawn_chance` | float | `0.0` | [0,1] | yes | no | no |
| `gameplay/cat_waking_up_gift_chance` | float | `0.0` | [0,1] | yes | no | no |
| `gameplay/bees_stay_in_hive` | boolean | `false` |  | yes | no | no |
| `gameplay/monsters_burn` | boolean | `false` |  | yes | no | no |
| `gameplay/can_pillager_patrol_spawn` | boolean | `true` |  | yes | no | no |
| `gameplay/natural_mob_spawns` | mob_spawn_settings | empty |  | yes (full-resolution biome lookup) | no | no |
| `gameplay/creature_world_gen_spawn_probability` | float | `0.1` | [0,0.9999999] | yes | no | no |
| `gameplay/villager_activity` | activity | `idle` |  | yes | no | no |
| `gameplay/baby_villager_activity` | activity | `idle` |  | yes | no | no |

Gameplay notes relevant to Mars:

- **`water_evaporates`** has several effects:
  - a water bucket will not place and plays a fizz with smoke instead (`BucketItem.java:121`);
  - ice **vanishes** instead of turning to water, both when broken and when it melts (`IceBlock.java:40,60`);
  - wet sponges dry (`WetSpongeBlock.java:27`).

  That is a ready-made "sublimation" rule.
- **`sky_light_level`** drives `Level.skyDarken = (int)(15 - value)` (`Level.java:735-736`). That in turn drives
  `isBrightOutside`/`isDarkOutside`, beds, AI and mob light checks.
- **`monsters_burn`** decides whether undead burn in daylight (`Mob.java:499`).

### 2.3 How a value is resolved (layer order)

From `EnvironmentAttributeSystem.java:61-103` and `:141-220`, `WeatherAttributes.java:39-66`, and
`ClientLevel.java:269-281`:

```
attribute.defaultValue()
 → [1] dimension_type "attributes"           Constant layer  (addDimensionLayer)
 → [2] biome "attributes"                    Positional layer (only for attributes that ANY biome in the registry sets)
 → [3] each timeline of dimension_type.timelines, in HolderSet order   TimeBased layer per tracked attribute
 → [4] weather (rain, then thunder)          TimeBased layer, only if level.canHaveWeather()
 → [5] client only: lightning sky flash      TimeBased layers on SKY_COLOR and SKY_LIGHT_FACTOR (ClientLevel)
 → attribute.sanitizeValue(result)           clamp to valueRange
```

- **Folding of leading constant layers.** `bakeLayerSampler` (`:38-55`) folds constant layers at the head of the list
  into a precomputed `baseValue`, so the dimension layer costs nothing at query time.
- **Positional vs dimension values.**
  - `getDimensionValue(attr)` skips positional layers and caches the result per tick (`cachedTickValue`).
  - `getValue(attr, pos)` returns that same cached value when no positional layer exists for the attribute.
  - Otherwise it walks all layers for that position (`:244-286`).
- **Cache invalidation.** Caches are cleared every tick by `ServerLevel.tick` (`ServerLevel.java:364`) and
  `ClientLevel`. They are also cleared whenever a clock is modified (`ServerClockManager.modifyClock`,
  `ClientPacketListener.handleSetTime`).
- **Biome layer, server side.** With no interpolator it uses `biomeManager.getNoiseBiomeAtPosition(pos)`. Attributes
  marked `fullResolutionBiomes` (`natural_mob_spawns`) use `biomeManager.getBiome(blockPos)` instead.
- **Biome layer, client probe.** It uses the Gaussian `SpatialAttributeInterpolator`, but only for attributes marked
  `spatiallyInterpolated` (`:90-101`).
- **Weather layer.** For each attribute in `RAIN ∪ THUNDER`, rain is applied first, then thunder, each with
  `stateChangeLerp`:

  ```java
  rain = rainLevel - thunderLevel; if (rain > 0) result = lerp(rain, result, RAIN.apply(result));
  if (thunder > 0) result = lerp(thunder, result, THUNDER.apply(result));
  ```

- **There is no numeric priority.** Order is structural, and each `EnvironmentAttributeMap` holds one entry per
  attribute:
  - An `override` in a later layer replaces everything below it.
  - Any other modifier combines with the running value from the layers below.
  - So a biome `sky_color` (plain value, i.e. override) replaces the dimension value, and the vanilla day timeline then
    `multiply`s it by white→black.

### 2.4 Modifiers (`AttributeModifier.java`, `modifier/*.java`)

In JSON, an entry is either a plain value (`override`) or `{"modifier": "<op>", "argument": <arg>}`
(`EnvironmentAttributeMap.Entry.createCodec`). In timelines the modifier is a field of the track, and each keyframe
`value` is the *argument*.

| type | operations (`override` is always allowed) | argument |
|---|---|---|
| boolean | `and`, `nand`, `or`, `nor`, `xor`, `xnor` | boolean |
| float, angle_degrees | `add`, `subtract`, `multiply`, `minimum`, `maximum`, `alpha_blend` | float. `alpha_blend` takes `{"value": f, "alpha": 0..1}` or a bare float (alpha 1) and gives `lerp(alpha, subject, value)`. |
| rgb_color | `add`, `subtract`, `multiply`, `alpha_blend`, `blend_to_gray` | `"#rrggbb"` (add/sub/mul); `"#aarrggbb"` (alpha_blend); `{"brightness": 0..1, "factor": 0..1}` (blend_to_gray: lerp toward greyscale×brightness) |
| argb_color | `add`, `subtract`, `multiply` (alpha is multiplied too), `alpha_blend`, `blend_to_gray` | `add`/`subtract`: `"#rrggbb"` (they are `RgbModifier<Vector4fc>`, RGB only); `multiply`/`alpha_blend`: `"#aarrggbb"`; `blend_to_gray`: `{brightness, factor}` |
| integer | `add`, `subtract`, `multiply`, `minimum`, `maximum` | int |
| ambient_particles | `append` | list |
| mob_spawn_settings | `overlay` | MobSpawnSettings (categories in the argument replace the base's) |
| tri_state, moon_phase, activity, bed_rule, particle, background_music, ambient_sounds | override only | value |

Colors may be written as `"#rrggbb"`/`"#aarrggbb"`, a float array or an int (`ExtraCodecs.STRING_RGB_VEC3_COLOR`,
`STRING_ARGB_VEC4_COLOR`). Hex strings are the safest choice.

Interpolation inside a timeline:

- The override modifier uses `type.keyframeLerp()`. Other modifiers use `modifier.argumentKeyframeLerp()`: float lerp,
  sRGB colour lerp, or constant for booleans.
- Non-interpolated types hold the previous keyframe's value until the next keyframe.
- For `angle_degrees`, the keyframe lerp is a plain float lerp, so 0→360 sweeps a full turn. Only the partial-tick lerp
  wraps (`ofDegrees(90)`).

### 2.5 Where attributes are declared

- **Dimension type:** `"attributes"`, any attribute.
- **Biome:** top-level `"attributes"` in `worldgen/biome/*.json`. Only positional attributes are allowed
  (`Biome.DIRECT_CODEC` uses `CODEC_ONLY_POSITIONAL`, `Biome.java:40-47`).
  - Sky, fog, water fog, particles, music, ambient sounds **and natural mob spawns** all moved there.
  - The biome `"effects"` block now holds only `water_color`, `foliage_color`, `dry_foliage_color`, `grass_color` and
    `grass_color_modifier` (`BiomeSpecialEffects.java:20-25`).
  - Vanilla desert example:

    ```json
    "attributes": {
      "minecraft:audio/background_music": { "default": { "max_delay": 24000, "min_delay": 12000, "sound": "minecraft:music.overworld.desert" } },
      "minecraft:gameplay/natural_mob_spawns": { "modifier": "overlay", "argument": { "spawn_costs": {}, "spawns_by_category": { "creature": [ { "type": "minecraft:rabbit", "count": { "type": "minecraft:uniform", "min_inclusive": 2, "max_inclusive": 3 }, "weight": 12 } ] } } },
      "minecraft:gameplay/snow_golem_melts": true,
      "minecraft:visual/sky_color": "#6eb1ff"
    }
    ```

  - Basalt deltas uses `"minecraft:visual/ambient_particles": {"modifier": "append", "argument": [{"particle": {"type": "minecraft:white_ash"}, "probability": 0.118}]}`.
- **Timeline:** `"tracks"`, any attribute (§3).
- **Fabric biome API:** `BiomeModificationContext.AttributesContext`, with `set(attr, value)`,
  `setModifier(attr, modifier, arg)` and `addAll(map)` (`fabric/.../api/biome/v1/BiomeModificationContext.java:94-116`).
- **Fabric dimension API:** `DimensionEvents.MODIFY_ATTRIBUTES` (§5.4).
- **Loot and predicate data:**
  - condition `minecraft:environment_attribute_check` with `{"attribute": id, "value": v}`;
  - number providers `minecraft:environment_attribute` (float and int) (`loot/predicates/EnvironmentAttributeCheck.java`,
    `LootItemConditionTypes.java:27`).

  These work with modded attributes too, for example in advancements.

### 2.6 Network sync of attribute data

- **Registry sync.** Dimension types, biomes, timelines and world clocks are in
  `RegistryDataLoader.SYNCHRONIZED_REGISTRIES` (`:164-197`). Dimension types and biomes go through their
  `NETWORK_CODEC`, which **drops non-syncable attributes**. `Timeline.NETWORK_CODEC` drops non-syncable *tracks*.
  So on the client, a non-syncable attribute always reads as its default.
- **Known packs.**
  - `RegistrySynchronization.packRegistry` skips sending an entry when its `knownPackInfo` is a pack the client also
    has. The client then loads it from its own copy with the same network codec: `RegistryDataCollector` calls
    `RegistryDataLoader.load(entries, knownDataSource, ..., RegistryDataLoader.SYNCHRONIZED_REGISTRIES, ...)`
    (`client/.../RegistryDataCollector.java:93-94`).
  - Fabric registers mod data packs as known packs (`fabric/.../impl/resource/pack/ModNioPackResources.java:111`).
  - Fabric's `DimensionModificationImpl` clears `knownPackInfo` on modified dimension types, so the modified version is
    sent in full.

### 2.7 Registering custom attributes from the mod (crucial question)

**Yes, this works.** The evidence:

1. **The registry is writable during mod init.**
   - `BuiltInRegistries.ENVIRONMENT_ATTRIBUTE` and `ATTRIBUTE_TYPE` are ordinary `registerSimple` registries
     (`BuiltInRegistries.java:354-357`).
   - Fabric's `BootstrapMixin` redirects `BuiltInRegistries.bootStrap()` so that it only calls `createContents()`, with
     no freeze (`fabric/.../mixin/registry/sync/BootstrapMixin.java`).
   - The freeze (`BuiltInRegistries.bootStrap()`, which runs `freeze()` and `validate()`) happens after mod
     initialization. On the dedicated server it is in `MainMixin` (injected at `Util.startTimerHackThread`). On the
     client it is in `MinecraftMixin` (injected in the `Minecraft` constructor).
   - Registering in `onInitialize()` is therefore fine. Registering later throws `"Registry is already frozen"`
     (`MappedRegistry.java:75-83`).
2. **Serialization is by name.**
   - `EnvironmentAttributes.CODEC = BuiltInRegistries.ENVIRONMENT_ATTRIBUTE.byNameCodec()` (`:181`). Nothing
     serializes attributes by raw ID (grep finds no other use of the registry).
   - Fabric's registry sync marks only certain registries `SYNCED` (`FabricRegistryInit.java`), and
     `ENVIRONMENT_ATTRIBUTE` is **not** one of them. None is needed.
   - A client without the mod cannot decode the dimension type, because the key is unknown. That is expected, since the
     mod is required on both sides. The exact failure UI is **UNVERIFIED**.
3. **Datapack JSON decodes after mod init.** Dimension types, biomes and timelines are decoded at world load, long after
   mod init, so `"redplanet:gameplay/gravity": 0.379` in our dimension type resolves.
   - Identifier paths may contain `/`, as vanilla's do.
   - Biomes accept the attribute only if it is positional.

```java
// io.github.avi130805.redplanet.world.RedPlanetAttributes  (call init() from ModInitializer.onInitialize)
public static final EnvironmentAttribute<Float> GRAVITY = register("gameplay/gravity",
        EnvironmentAttribute.builder(AttributeTypes.FLOAT)
                .defaultValue(1.0F)                              // Earth = 1 g everywhere we don't say otherwise
                .valueRange(AttributeRange.ofFloat(0.0F, 10.0F))
                .notPositional()                                 // dimension-wide; read via getDimensionValue
                .syncable());                                    // client needs it for player-movement prediction
public static final EnvironmentAttribute<Float> SURFACE_PRESSURE_PA = register("gameplay/surface_pressure",
        EnvironmentAttribute.builder(AttributeTypes.FLOAT).defaultValue(101325.0F)
                .valueRange(AttributeRange.NON_NEGATIVE_FLOAT).spatiallyInterpolated().syncable()); // biome-tunable
public static final EnvironmentAttribute<Boolean> BREATHABLE = register("gameplay/breathable",
        EnvironmentAttribute.builder(AttributeTypes.BOOLEAN).defaultValue(true).syncable());

private static <V> EnvironmentAttribute<V> register(String path, EnvironmentAttribute.Builder<V> builder) {
    return Registry.register(BuiltInRegistries.ENVIRONMENT_ATTRIBUTE,
            Identifier.fromNamespaceAndPath(RedPlanet.MOD_ID, path), builder.build());
}
```

- A custom value type means a custom `AttributeType` registered in `BuiltInRegistries.ATTRIBUTE_TYPE` with
  `Registry.register(...)`.
- `AttributeTypes.register` is public, but it hard-codes the `minecraft` namespace, so don't use it.
- `EnvironmentAttributes.register` is `private`.

### 2.8 Reading values

```java
// Server (ServerLevel) or any Level, both sides — EnvironmentAttributeReader API:
<V> V getDimensionValue(EnvironmentAttribute<V> attr);                    // for notPositional attrs (cached per tick)
<V> V getValue(EnvironmentAttribute<V> attr, BlockPos pos);               // default method → Vec3.atCenterOf(pos)
<V> V getValue(EnvironmentAttribute<V> attr, Vec3 pos);
<V> V getValue(EnvironmentAttribute<V> attr, Vec3 pos, @Nullable SpatialAttributeInterpolator biomeInterpolator);
<V> V getValue(LootContext ctx, EnvironmentAttribute<V> attr);            // positional → ORIGIN param

float g = serverLevel.environmentAttributes().getDimensionValue(RedPlanetAttributes.GRAVITY);
boolean dry = level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos); // vanilla style
```

- `Level.environmentAttributes()` returns `EnvironmentAttributeSystem` (`Level.java:1107`, `ServerLevel.java:349`,
  `ClientLevel.java:803`).
- `LevelReader.environmentAttributes()` returns the `EnvironmentAttributeReader` interface. A `WorldGenRegion` has only
  static layers (`WorldGenRegion.java:109`).
- On the client there are two routes:
  - `Minecraft.getInstance().level.environmentAttributes()` (same API);
  - the camera probe that all renderers use, which is biome-blended and partial-tick smoothed:
    `minecraft.gameRenderer.mainCamera().attributeProbe().getValue(attr, partialTicks)`
    (`client/net/minecraft/client/Camera.java:80-88,423`).
- In IDE mode vanilla throws if `getDimensionValue` is called on a positional attribute (`SharedConstants.IS_RUNNING_IN_IDE`,
  `EnvironmentAttributeSystem.java:115`). Nothing in Loom sets that flag, but respect the rule anyway.

### 2.9 Adding custom layers (altitude sky, dust storms, habitats)

- **Fabric API has no hook for this.**
- **Model it on the weather layer.** `WeatherAttributes.addBuiltinLayers(builder, WeatherAccess.from(level))` is called
  from the private static `EnvironmentAttributeSystem.addDynamicLayers(builder, level)`, which adds
  `system.addTimeBasedLayer(attribute, (result, cacheTickId) -> ...)` for each weather attribute.
- **When the system is built.** Each level builds it once, in its constructor:
  - `ServerLevel` ctor: `EnvironmentAttributeSystem.builder().addDefaultLayers(this).build()` (`ServerLevel.java:309`);
  - `ClientLevel` ctor: `addEnvironmentAttributeLayers(builder)` runs `addDefaultLayers(this)` and then adds the
    sky-flash layers (`ClientLevel.java:269-281`).

Options:

1. **(Recommended) One small mixin** at `RETURN` of the public instance method
   `EnvironmentAttributeSystem.Builder#addDynamicLayers(Level)`.
   - Both `ServerLevel` and `ClientLevel` reach it through `addDefaultLayers`, and so does gametest
     `TestEnvironmentDefinition.Timelines`.
   - Our layers then sit **after** timelines and weather, and on the client before the lightning flash.
   - Use `level.isClientSide()` to add client-only visual layers.
2. Server only: `ServerLevel.setEnvironmentAttributes(EnvironmentAttributeSystem)`, which is
   `@Deprecated @VisibleForTesting` and used by gametests. The client field is `private final`, so this doesn't help
   there.

```java
@Mixin(EnvironmentAttributeSystem.Builder.class)
abstract class EnvironmentAttributeSystemBuilderMixin {
    // Vanilla builds each Level's attribute stack once (ServerLevel/ClientLevel constructors) through
    // addDefaultLayers -> addDynamicLayers. Append Red Planet layers after timelines + weather.
    @Inject(method = "addDynamicLayers", at = @At("RETURN"))
    private void redplanet$addLayers(Level level, CallbackInfoReturnable<EnvironmentAttributeSystem.Builder> cir) {
        RedPlanetEnvironmentLayers.install(cir.getReturnValue(), level);
    }
}

// Altitude layer: valid in the overworld too, for the ascent. pos = camera position (client probe) or the query pos.
builder.addPositionalLayer(EnvironmentAttributes.SKY_COLOR, (base, pos, interp) -> {
    float t = spaceFactor(pos.y);                         // 0 at y<=320 → 1 at y>=2000, smoothstep
    return t <= 0 ? base : ARGB.srgbLerp(t, base, BLACK); // ARGB.srgbLerp(float, Vector3fc, Vector3fc) exists
});
builder.addPositionalLayer(EnvironmentAttributes.STAR_BRIGHTNESS, (base, pos, interp) -> Math.max(base, spaceFactor(pos.y)));
// sunrise_sunset_color, fog_end_distance, sky_light_factor and cloud_height are positional too → also usable.
// sky_light_level is NOT positional → positional layers are ignored for it (computeValueNotPositional).
```

- **Dust storm.** Either add a `TimeBased` layer that reads a storm intensity synchronised by our own payload, or use
  the purely data-driven trick in §9.4: a dedicated world clock and timeline that the server starts, pauses and jumps.
  Clock changes reach clients automatically through `ClientboundSetTimePacket`.
- **Habitats.** A positional layer on `WATER_EVAPORATES` (or on our `BREATHABLE`) can return the "pressurised" value
  inside sealed habitat volumes.

---

## 3. Timelines and world clocks

### 3.1 World clocks

- **Definition and registry.**
  - `common/net/minecraft/world/clock/WorldClock.java`: `public record WorldClock()`. `DIRECT_CODEC` is a unit codec,
    so the JSON is `{}`. `CODEC` is a registry holder.
  - Vanilla `data/world_clock/overworld.json` and `the_end.json` are both `{}`.
  - The registry `minecraft:world_clock` is loaded from datapacks and synced (`RegistryDataLoader.java:143,192`).
- **One manager per server.**
  - `MinecraftServer.java:361-362`:
    `clockManager = getDataStorage().computeIfAbsent(ServerClockManager.TYPE); clockManager.init(this);`.
  - It is saved as `<world>/data/minecraft/world_clocks.dat` (`SavedDataType` id `minecraft:world_clocks`).
  - `init` creates an instance for **every** registered clock. Clocks new to an existing world start at 0. It also
    registers the time markers of **every** timeline (`ServerClockManager.java:39-50`).
- **Ticking.** `MinecraftServer.tickChildren` calls `clockManager.tick()` every tick (`:1079-1082`). Every unpaused
  clock advances by `rate`, with fractional `partialTick`, while `GameRules.ADVANCE_TIME` is on (`:60-66`, `:163-170`).
  Clocks do **not** depend on which dimension anyone is in.
- **State per clock:** `totalTicks` (long), `partialTick`, `rate` (positive float), `paused` (`ClockState.CODEC`:
  `total_ticks`, `partial_tick`, `rate`, `paused`).
- **API:** `setTotalTicks(clock, long)`, `addTicks(clock, int)`, `moveToTimeMarker(clock, markerKey)` (returns
  `MoveResult`), `setPaused`, `setRate`, `isAtTimeMarker`, `getInstance(clock).totalTicks()`.
  - Every mutation broadcasts `ClientboundSetTimePacket` to all players and invalidates every level's attribute caches
    (`modifyClock`, `:113-123`).
  - Players get a full sync in `PlayerList.sendLevelInfo` (`:650`).
- **Client.** `client/net/minecraft/client/ClientClockManager.java` creates instances on demand. It extrapolates
  `totalTicks += gameTimeDelta * rate` and applies server updates in `handleUpdates` (`ClientPacketListener.java:1104-1111`).

### 3.2 Timeline format (`common/net/minecraft/world/timeline/Timeline.java`)

```java
WorldClock.CODEC.fieldOf("clock")
ExtraCodecs.POSITIVE_INT.optionalFieldOf("period_ticks")
TRACKS_CODEC.optionalFieldOf("tracks", Map.of())        // dispatchedMap(EnvironmentAttributes.CODEC, AttributeTrack::createCodec)
Codec.unboundedMap(ClockTimeMarker.KEY_CODEC, TimeMarkerInfo.CODEC).optionalFieldOf("time_markers", Map.of())
//  TimeMarkerInfo: either an int (ticks, show_in_commands=false) or {"ticks": int>=0, "show_in_commands": bool}
// AttributeTrack (AttributeTrack.java): "modifier" (optional, default override) + KeyframeTrack.mapCodec(modifier.argumentCodec):
//  "keyframes": [{"ticks": int>=0, "value": <argument>}...]  (non-empty, sorted by ticks, ≤2 keyframes on the same tick)
//  "ease": optional, default "linear"
```

**Validation:**

- Timeline (`validateInternal`): time markers must be in `[0, period)` and keyframes in `[0, period]`.
- Registry (`validateRegistry`): a marker id may be defined **only once per clock** across *all* timelines, otherwise
  you get a loading error. Different clocks may reuse ids like `minecraft:noon`.

**Ease values** (`common/net/minecraft/util/EasingType.java`):

- `constant` (holds the "from" value) and `linear`;
- `in_*`, `out_*` and `in_out_*` for `back`, `bounce`, `circ`, `cubic`, `elastic`, `expo`, `quad`, `quart`, `quint`,
  `sine`;
- `{"cubic_bezier": [x1, y1, x2, y2]}`.

**Sampling** (`KeyframeTrackSampler.java`):

- `ticks = floorMod(clock.totalTicks, period)` when a period is set; otherwise the absolute clock ticks.
- With a period, wrap-around segments are added (last keyframe → first keyframe + period).
- Before the first keyframe or after the last one (no period), the edge value holds. A single keyframe gives a
  constant.
- Inside a segment the value is `lerp(ease(alpha))`.
- `AttributeTrackSampler` caches its argument per attribute-cache tick id.

Helpers on `Timeline`:

- `getCurrentTicks(clockManager)`: ticks within the period, i.e. "time of day";
- `getPeriodCount(clockManager)`: completed periods, i.e. the day number;
- `getTotalTicks(clockManager)`.

### 3.3 How vanilla drives the sky (`data/timeline/day.json`, `Timelines.java:36-165`)

```json
"clock": "minecraft:overworld", "period_ticks": 24000,
"time_markers": { "minecraft:day": {"show_in_commands": true, "ticks": 1000}, "minecraft:noon": {"show_in_commands": true, "ticks": 6000},
                  "minecraft:night": {"show_in_commands": true, "ticks": 13000}, "minecraft:midnight": {"show_in_commands": true, "ticks": 18000},
                  "minecraft:roll_village_siege": 18000, "minecraft:wake_up_from_sleep": 0 },
"minecraft:visual/sun_angle":  { "ease": {"cubic_bezier": [0.362, 0.241, 0.638, 0.759]}, "keyframes": [ {"ticks": 6000, "value": 360.0}, {"ticks": 6000, "value": 0.0} ] },
"minecraft:visual/moon_angle": { "ease": {"cubic_bezier": [0.362, 0.241, 0.638, 0.759]}, "keyframes": [ {"ticks": 6000, "value": 540.0}, {"ticks": 6000, "value": 180.0} ] },
"minecraft:gameplay/sky_light_level": { "modifier": "multiply", "keyframes": [ {"ticks": 133, "value": 1.0}, {"ticks": 11867, "value": 1.0}, {"ticks": 13670, "value": 0.26666668}, {"ticks": 22330, "value": 0.26666668} ] }
```

**Sun and star angles.**

- There are two keyframes on the same tick (6000: 360, then 0). In a 24000 period this makes one full turn per
  period. The angle is **0° at noon**: at tick 6000 the sampler returns the from-value 0.
- At tick t the angle is `ease(((t-6000) mod 24000)/24000) * 360`.
- Angle convention, checked against `AtmosphericFogEnvironment` (`sin(angle) > 0` means the sun is toward −X):
  - 0° is the zenith;
  - ≈90° is the west horizon (sunset, tick 12000);
  - 180° is the nadir (midnight);
  - ≈270° is the east horizon (sunrise, tick 0).
- The symmetric bezier reproduces the old celestial-angle curve.
- Stars follow the same track. The moon track is `+180°`.

**Gameplay light.** `sky_light_level` multiplies the base 15 by 1 → 0.2667, giving 15 by day and 4 at night. That
drives `skyDarken`.

**Visual light.** `sky_light_factor` (1 → 0.24) and `sky_light_color` (white → `#7a7aff`) feed the lightmap.
`sky_color`, `fog_color` and `cloud_color` are multiplied by white → near-black.

**Other tracks:**

- `star_brightness` with `maximum`, peaking at 0.5;
- `sunrise_sunset_color` as an override, ARGB with alpha as the intensity;
- tracks for eyeblossom, creaking, bees, monsters burning, turtle eggs and cat gifts.

**Other vanilla timelines:**

- `moon.json`: period 192000, drives `moon_phase` and `surface_slime_spawn_chance`;
- `villager_schedule.json`: period 24000, overworld clock;
- `early_game.json`: no period, `can_pillager_patrol_spawn` `and`.

**Tags:**

- `#minecraft:in_overworld` = `#universal`, `day`, `moon`, `early_game`;
- `#in_nether` and `#in_end` = `#universal`;
- `#universal` = `villager_schedule`, bound to the **overworld** clock even in the nether.

**The client renderer** reads `sun_angle`, `moon_angle`, `star_angle`, `star_brightness`, `sunrise_sunset_color`,
`moon_phase` and `sky_color` from the camera probe (`client/.../SkyRenderer.java:115-123`).

- The sun and moon alpha is `1 - level.getRainLevel()`.
- Stars are drawn only if brightness > 0.
- With `skybox: overworld` **the moon is always drawn.** Its blend is `OVERLAY` = additive
  (`RenderPipelines.CELESTIAL`, `BlendFunction.OVERLAY`). It draws at `moon_angle`, default 0, which is the zenith, in
  phase `moon_phase` (default full).

### 3.4 Can we define `redplanet:mars` with a 24 660-tick sol? **Yes.**

Every link in the chain is data:

- `world_clock/mars.json` (`{}`);
- a timeline with `"clock": "redplanet:mars"` and `"period_ticks": 24660` (any positive int);
- `dimension_type.default_clock` and `timelines`.

`EnvironmentAttributeSystem.addDynamicLayers` binds the timeline samplers to `level.clockManager()` on both sides.
`ServerClockManager.init` registers the clock and its markers. A full skeleton is in §9.

The real conversion: 24 h 39 m 35.244 s = 88 775.244 s. At 3.6 s per tick (86 400 s / 24 000) that is 24 659.8, so use
**24 660** ticks.

### 3.5 `/time`, time markers and sleeping

**`/time` syntax** (`TimeCommand.java:61-125`, permission `LEVEL_GAMEMASTERS`):

```
/time set <time>                /time set <timemarker id>      /time add <time>
/time pause | resume            /time rate <0.00001..1000>     /time query time | <timeline> [repetition]
/time query gametime
/time of <clock> <any of the above>     ← explicit clock, e.g. /time of redplanet:mars set minecraft:noon
```

- Without `of`, the command uses the **executing dimension's `default_clock`**. Run in Mars, `/time set noon` therefore
  moves the Mars clock to the Mars timeline's `minecraft:noon` marker.
- Only markers with `show_in_commands` are suggested, but any marker id is accepted.
- `ClockTimeMarker.resolveTimeToMoveTo` moves **forward** to the next occurrence on a periodic timeline.
  - If the clock is already on the marker, the result is `NOT_MOVED`.
  - On a timeline with no `period_ticks`, it jumps to the absolute tick, which can be **backwards**.

**Sleeping** (`ServerLevel.java:374-386`):

```java
if (sleepStatus.areEnoughSleeping(pct) && sleepStatus.areEnoughDeepSleeping(pct, this.players)) {
    Optional<Holder<WorldClock>> defaultClock = this.dimensionType().defaultClock();
    if (getGameRules().get(GameRules.ADVANCE_TIME) && defaultClock.isPresent())
        this.server.clockManager().moveToTimeMarker(defaultClock.get(), ClockTimeMarkers.WAKE_UP_FROM_SLEEP);
    this.wakeUpAllPlayers();
    if (getGameRules().get(GameRules.ADVANCE_WEATHER) && this.isRaining()) this.resetWeatherCycle();
}
```

- Sleep counting is per level, and the skip goes to `minecraft:wake_up_from_sleep` **on that dimension's clock**.
- The Mars timeline has to define `"minecraft:wake_up_from_sleep": <sunrise tick>`. Without it the result is
  `NO_TIME_MARKER_FOUND`: players wake but no time passes.
- Sleeping on Mars never moves the overworld clock, and sleeping on Earth never moves the Mars clock.
- Beds use `BedRule.Rule.WHEN_DARK`, which tests `level.isDarkOutside()`, i.e. `skyDarken >= 4`, i.e.
  `sky_light_level <= 11`.

### 3.6 Reading "day time" in code (`Level.getDayTime()` is gone)

- `level.getDefaultClockTime()`: total ticks of the dimension's default clock, or 0 if it has none
  (`Level.java:884-890`).
- `level.getOverworldClockTime()`: total ticks of `minecraft:overworld` (`:880-882`). Local difficulty
  (`ServerLevel.java:699`) and crash reports use it.
- `level.clockManager().getInstance(holder).totalTicks()`. On the server that is `ServerLevel.clockManager()`, which
  returns `server.clockManager()`. On the client it is `ClientLevel.clockManager()`.
- Time within the sol is `timelineHolder.value().getCurrentTicks(level.clockManager())`, and the sol number is
  `getPeriodCount(...)`. Get the holder with
  `level.registryAccess().lookupOrThrow(Registries.TIMELINE).getOrThrow(KEY)`.
- `level.isBrightOutside()` / `isDarkOutside()` / `getSkyDarken()`.
- The sun angle is the attribute `EnvironmentAttributes.SUN_ANGLE`.
- `level.getGameTime()` is still the overworld game time. `DerivedLevelData` shares it, and `tickTime` is true only for
  the overworld (`MinecraftServer.java:420-479`).

### 3.7 Caveats of a per-dimension day length

- **Daylight detectors** use `SUN_ANGLE` at their position and the effective sky brightness
  (`DaylightDetectorBlock.java:47-61`), so they follow the Mars sun automatically.
- **Clock item** (`client/.../item/properties/numeric/Time.java:57`) shows `SUN_ANGLE / 360` at the holder's position,
  so it shows Mars time on Mars.
- **Phantoms.**
  - `PhantomSpawner` is only in the overworld's custom spawners. `createLevels` passes `ImmutableList.of()` to every
    other level (`MinecraftServer.java:427-470`), so no phantoms appear on Mars.
  - But `Stats.TIME_SINCE_REST` keeps rising on Mars (`ServerPlayer.java:671-672`). A player who returns after a long
    stay without sleeping may meet phantoms on Earth. Sleeping on Mars resets it (`ServerPlayer.java:1281`).
- **Overworld-only spawners.** Patrols, cats, village sieges (which use the `roll_village_siege` marker) and wandering
  traders run only in the overworld.
- **Villagers.** `#minecraft:universal` (the villager schedule) is bound to the **overworld** clock with a 24 000
  period. If Mars includes it, villagers on Mars keep Earth time. Leave it out, or write a Mars schedule.
- **No moon timeline.** `moon_phase` stays `full_moon`. `getMoonBrightness` is then 1.0, which slightly raises local
  difficulty.
- **Clocks keep running.** The Mars clock runs while nobody is on Mars, which is realistic. `ADVANCE_TIME=false` pauses
  every clock.
- **Missing tracks fall back to defaults:**
  - `sun_angle` = 0 means permanent noon;
  - `moon_angle` = 0 means the moon at the zenith;
  - `star_brightness` = 0 means no stars;
  - `sky_light_level` = 15 means permanent day.

### 3.8 Gametest helpers

- `GameTestHelper.setTime(long)` sets the test level's default clock (`GameTestHelper.java:970-974`).
- Test environments (`TestEnvironmentDefinition`):
  - `clock_time` `{clock, time}`;
  - `timeline_attributes` `{timelines: [...]}`, which rebuilds the level's attribute system with extra timelines;
  - `weather`, `game_rules`, `function`, `difficulty`, `all_of`.

---

## 4. Weather

**Storage is global.**

- `MinecraftServer.java:347`: `weatherData = getDataStorage().computeIfAbsent(WeatherData.TYPE)`.
- It is saved as `data/minecraft/weather.dat` with `clear_weather_time`, `rain_time`, `thunder_time`, `raining` and
  `thundering`.
- `ServerLevel.getWeatherData()` returns `server.getWeatherData()` (`:1802`), and `/weather` calls
  `server.setWeatherParameters(...)` (`WeatherCommand.java:42-54`).
- Each `Level` keeps only its own *rendered* `rainLevel`/`thunderLevel`, which it ramps ±0.01 per tick toward the
  shared flags.

**Who has weather** (`Level.java:937-947`):

```java
public boolean canHaveWeather() {
    return this.dimensionType().hasSkyLight() && !this.dimensionType().hasCeiling() && this.dimension() != END;
}
public boolean isRaining() { return this.canHaveWeather() && this.getRainLevel(1.0F) > 0.2; }
```

**What happens to a custom dimension with skylight and no ceiling, such as Mars:**

1. **It shares the overworld's rain and thunder.** `prepareWeather` is called in the ctor, and `advanceWeatherCycle`
   reads the global flags.
2. **It runs `advanceWeatherCycle()` (`ServerLevel.java:716-800`) on the shared `WeatherData` too.** The rain and
   thunder timers are decremented once per weather-capable level, so with Mars the Earth's weather cycle runs **twice as
   fast**.
3. **It gets the rain/thunder attribute layer.** That greys the sky, cuts `sky_light_level` toward 4 (alpha 0.3125
   for rain, 0.527 for thunder) and zeroes `star_brightness` (`WeatherAttributes.java:13-36`).
4. **It is exposed to lightning and snow:** lightning (`tickThunder` needs `isRainingAt`), snow accumulation and
   cauldron filling (`tickPrecipitation`, `:585-615`).

**Broadcast leak.** When any level's `isRaining()` flips, it sends `START_RAINING`/`STOP_RAINING` and a
`RAIN_LEVEL_CHANGE`/`THUNDER_LEVEL_CHANGE` with **`broadcastAll(packet)` and no dimension filter**
(`ServerLevel.java:789-798`). The client handler is `ClientPacketListener.java:1505-1548`:

- `START_RAINING` sets `rainLevel = 0`;
- `STOP_RAINING` sets `rainLevel = 1`;
- `RAIN_LEVEL_CHANGE` sets the value.

Consequences:

- A client on Mars inherits whatever rain level the overworld broadcast last, often about 0.2.
- Even if Mars cannot have weather, the client still uses the raw `getRainLevel()` for:
  - the sun, moon and star alpha (`SkyRenderer.java:119`);
  - fog and sky darkening (`AtmosphericFogEnvironment.java:45,94`);
  - rain particles and splashes, which appear only where the biome precipitation is not `NONE`
    (`WeatherEffectRenderer.java:69`, `ClientLevel.java:362-381`).

**How to have no rain on Mars:**

- `has_precipitation: false` in every Mars biome turns `getPrecipitationAt` into `NONE` (`Biome.java:104-110`). That
  removes rain and snow rendering, lightning strikes and snow accumulation.
- It does **not** stop the shared weather state, the sky darkening layer or the double-speed timers.
- There is no JSON field or attribute for `canHaveWeather`.

Recommendation, using small commented mixins:

- `@ModifyReturnValue` on `Level.canHaveWeather()` returning false for `redplanet:*` dimensions. This works on both
  sides. It is safe even while the `Level` is still being constructed, because `dimension()` and `dimensionType()` are
  already set.
- Make `Level.getRainLevel`/`getThunderLevel` return 0 for those dimensions. That defeats the global broadcast leak on
  the client.
- Implement Mars "weather" (dust storms) ourselves (§2.9, §9.4).

A 26.x extra: `ServerPlayer.addPostEffect(Identifier)` with `sendPostEffects()` (`ClientboundPostEffectsPacket`)
switches client post-processing chains from `assets/<ns>/post_effect/*.json` on and off. They persist in the player NBT
under `post_effects`. That could be useful for a dust-storm overlay; the rendering side is **UNVERIFIED** here.

---

## 5. Registering the dimension from the mod

### 5.1 Data paths

| File | Registry |
|---|---|
| `data/redplanet/dimension_type/mars.json` | `Registries.DIMENSION_TYPE` |
| `data/redplanet/dimension/mars.json` | `Registries.LEVEL_STEM` (`minecraft:dimension`); this creates the level |
| `data/redplanet/world_clock/mars.json` | `Registries.WORLD_CLOCK` |
| `data/redplanet/timeline/*.json`, `data/redplanet/tags/timeline/in_mars.json` | `Registries.TIMELINE` |
| `data/redplanet/worldgen/biome/*.json`, `worldgen/noise_settings/*.json`, … | worldgen |

The level key in code:
`public static final ResourceKey<Level> MARS = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("redplanet", "mars"));`.
`Registries.DIMENSION` is the `minecraft:dimension` key for `Level`, and `Registries.levelStemToLevel` converts between
the two.

### 5.2 New worlds and existing worlds: **both**

`WorldDimensions.bake(Registry<LevelStem> baseDimensions)` (`WorldDimensions.java:152-174`):

```java
Set<ResourceKey<LevelStem>> knownDimensions = Sets.union(baseDimensions.registryKeySet(), this.dimensions.keySet());
keysInOrder(knownDimensions).forEach(key -> baseDimensions.getOptional(key)          // datapack version wins
        .or(() -> Optional.ofNullable(this.dimensions.get(key)))                     // else the one saved in the world
        .ifPresent(stem -> results.add(new Entry(key, stem))));
```

- **Existing worlds.** `LevelStorageSource.getLevelDataAndDimensions` (`:143-164`) reads the saved
  `WorldGenSettings` and calls `worldGenSettings.dimensions().bake(datapackDimensions)`. The client's
  `WorldOpenFlows.java:181,208` uses the same path.
- **New worlds.** These bake the preset dimensions with the datapack registry: `Main.createNewWorldData` (`:259`) and
  `CreateWorldScreen` / `WorldOpenFlows.createFreshLevel` (`:121`).
- **Fabric mod data packs are always enabled.** `ModResourcePackCreator` builds them with
  `new PackSelectionConfig(required = true, Pack.Position.TOP, false)` (`fabric/.../impl/resource/pack/ModResourcePackCreator.java:82`).
  So adding the mod to an old world creates `redplanet:mars` on the next load.
- **Storage folder.** It is `<world>/dimensions/redplanet/mars/`:
  `DimensionType.getStorageFolder` resolves `name.identifier().resolveAgainst(base.resolve("dimensions"))`.
  It contains `region`, `entities`, `poi`, and `data` (per-dimension saved data, e.g. `minecraft/chunk_tickets.dat`).

### 5.3 Level creation (`MinecraftServer.createLevels`, `:420-479`)

```java
for (Entry<ResourceKey<LevelStem>, LevelStem> entry : dimensions.entrySet()) {
    if (name != LevelStem.OVERWORLD) {
        DerivedLevelData derivedLevelData = new DerivedLevelData(this.worldData, levelData);
        level = new ServerLevel(this, executor, storageSource, derivedLevelData, dimension, entry.getValue(),
                                isDebug, biomeZoomSeed, ImmutableList.of() /* no custom spawners */, false /* tickTime */);
        this.levels.put(dimension, level);
    }
}
```

- Fabric's `ServerLevelEvents.LOAD` fires on each `levels.put` (`fabric/.../mixin/event/lifecycle/MinecraftServerMixin.java:87-91`).
- Look-ups:
  - `server.getLevel(MARS)` returns `@Nullable ServerLevel` (`MinecraftServer.java:1175`);
  - `server.levelKeys()`, `server.getAllLevels()`, `server.overworld()`.
- Mars needs no special startup, and nothing is pre-generated. `prepareLevels` only waits for persisted
  forced/ticketed chunks.

### 5.4 Fabric `fabric-dimensions-v1` in 0.161.0

This is the whole module:

- **`DimensionEvents.MODIFY_ATTRIBUTES`** (`api/dimension/v1/DimensionEvents.java`):

  ```java
  void modifyDimensionAttributes(Holder<DimensionType> dimension, EnvironmentAttributeMap.Builder attributes, HolderLookup.Provider registries);
  ```

  - It runs once at `SERVER_STARTING` (before `initServer()`, so before any `ServerLevel` exists) for every dimension
    type, in raw-id order.
  - Changed entries have their `knownPackInfo` cleared, so clients receive them (`impl/dimension/DimensionModificationImpl.java`).
  - The javadoc states the use case: "add modded attributes to vanilla or modded dimensions".
- **Fail-soft level.dat and world-gen-settings decoding** when a dimension mod is removed: `FailSoftMapCodec`, plus
  `TaggedChoice` and `V2832` datafixer mixins.
- **`WorldDimensionsMixin.betterModdedStabilityCheck`.** A mod-provided level stem, whose registration info has
  `knownPackInfo`, uses its own lifecycle, so there is **no "experimental settings" warning**. Vanilla `checkStability`
  marks every non-vanilla stem experimental.
- **What the module does not have:** no runtime dimension creation and no teleport helper. The old
  `FabricDimensions.teleport` is gone; use vanilla `TeleportTransition`.

---

## 6. Teleporting across dimensions

### 6.1 `TeleportTransition` (`common/net/minecraft/world/level/portal/TeleportTransition.java`)

```java
public record TeleportTransition(ServerLevel newLevel, Vec3 position, Vec3 deltaMovement, float yRot, float xRot,
        boolean missingRespawnBlock, boolean asPassenger, Set<Relative> relatives,
        TeleportTransition.PostTeleportTransition postTeleportTransition)
// convenience ctors:
new TeleportTransition(ServerLevel, Vec3 pos, Vec3 speed, float yRot, float xRot, PostTeleportTransition)
new TeleportTransition(ServerLevel, Vec3 pos, Vec3 speed, float yRot, float xRot, Set<Relative> relatives, PostTeleportTransition)
// factories / withers:
static TeleportTransition createDefault(ServerPlayer, PostTeleportTransition)        // world spawn (findRespawnDimension)
static TeleportTransition missingRespawnBlock(ServerPlayer, PostTeleportTransition)  // same + "no respawn block" flag
TeleportTransition withRotation(float yRot, float xRot) / withPosition(Vec3) / transitionAsPassenger()
// callbacks:
@FunctionalInterface interface PostTeleportTransition { void onTransition(Entity entity);
                                                        default PostTeleportTransition then(PostTeleportTransition next); }
DO_NOTHING, PLAY_PORTAL_SOUND (level event 1032 to a player), PLACE_PORTAL_TICKET (TicketType.PORTAL, radius 3)
```

- `Relative` has the values `X`, `Y`, `Z`, `Y_ROT`, `X_ROT`, `DELTA_X`, `DELTA_Y`, `DELTA_Z`, `ROTATE_DELTA`.
- When `relatives` is empty, the position, rotation and velocity in the transition are absolute
  (`PositionMoveRotation.calculateAbsolute`).

### 6.2 `Entity.teleport(TeleportTransition)` returns `@Nullable Entity` (`Entity.java:3142-3207`)

```java
if (!transition.asPassenger()) this.stopRiding();          // the moved entity leaves ITS vehicle
return otherDimension ? teleportCrossDimension(oldLevel, newLevel, t) : teleportSameDimension(oldLevel, t);
```

**Same dimension.** Passengers are teleported recursively. The entity's position is set and
`ClientboundTeleportEntityPacket` goes to the riding players and to trackers. The same instance is returned.

**Cross dimension** (`teleportCrossDimension`):

1. `oldPassengers = getPassengers(); ejectPassengers();`. Then each passenger is teleported with
   `calculatePassengerTransition`, which keeps its offset and relative yaw/pitch and is `asPassenger`.
2. `Entity newEntity = getType().create(newLevel, EntitySpawnReason.DIMENSION_TRAVEL)`. It returns null if the type
   cannot be created.
3. `newEntity.restoreFrom(this)` is an **NBT round trip** (`saveWithoutId` → `load`) plus the portal cooldown and
   portal process (`:3131-3140`).
   - **The UUID, custom name, tags, `data` component and everything in `addAdditionalSaveData` are preserved.**
   - Transient Java fields are **lost**.
   - The network entity id is new.
   - `load()` zeroes any velocity component with |v| > 10 blocks/tick (`:2192`). The transition's `deltaMovement` is
     applied afterwards anyway.
4. `this.removeAfterChangingDimensions()` sets `RemovalReason.CHANGED_DIMENSION`, **removes any leash** and untracks
   waypoints.
5. `newEntity.teleportSetPosition(PositionMoveRotation.of(this), PositionMoveRotation.of(transition), relatives)`, then
   `newLevel.addDuringTeleport(newEntity)`.
6. **Re-mount.** `newPassenger.startRiding(newEntity, true /*force*/, false)`. `startRiding` still requires
   `couldAcceptPassenger()` and a **serializable** vehicle type on the server (`:2478-2516`).
7. `newLevel.resetEmptyTime()`, then `transition.postTeleportTransition().onTransition(newEntity)`, then
   `teleportSpectators` (players spectating the entity follow it).

Passengers reach clients through `ServerEntity` (`ClientboundSetPassengersPacket` on pairing and on change,
`ServerEntity.java:98-104,329-335`).

### 6.3 `ServerPlayer.teleport(TeleportTransition)` returns `@Nullable ServerPlayer` (`ServerPlayer.java:1124-1180`)

- **The player is moved, not recreated: same instance, same id.**
- If `!asPassenger`, it calls `removeVehicle()`, which dismounts. To keep a player seated, teleport the **vehicle**; the
  player then rides along as a passenger.
- Cross dimension, in order:

```
isChangingDimension = true     (player invulnerable until ServerboundAcceptTeleportationPacket → hasChangedDimension(); :1319, :560)
→ ClientboundRespawnPacket(createCommonSpawnInfo(newLevel), KEEP_ALL_DATA=3)   (keep attribute modifiers + entity data)
→ ClientboundChangeDifficultyPacket → permission level
→ oldLevel.removePlayerImmediately(CHANGED_DIMENSION); unsetRemoved(); setServerLevel(newLevel)
→ connection.teleport(PositionMoveRotation.of(transition), relatives) (ClientboundPlayerPositionPacket incl. velocity)
→ newLevel.addDuringTeleport(this); triggerDimensionChangeTriggers (advancement CHANGED_DIMENSION)
→ ClientboundPlayerAbilitiesPacket
→ PlayerList.sendLevelInfo: InitializeBorder, full SetTime (all clocks), SetDefaultSpawnPosition,
  [START_RAINING + levels if newLevel.isRaining()], GameEvent LEVEL_CHUNKS_LOAD_START, tick-rate state
→ sendAllPlayerInfo (inventory, held slot), active effects, post effects
→ postTeleportTransition.onTransition(this); teleportSpectators
```

**Client side** (`ClientPacketListener.handleRespawn`, `:1222-1310`):

- For a new dimension it builds a **new `ClientLevel`**, a new `LocalPlayer` (keeping data, velocity and rotation when
  `KEEP_ENTITY_DATA` is set) and stops the music.
- It then calls `startWaitingForNewLevel`, which opens **`LevelLoadingScreen`** (`:1598-1610`).
  - The reason is `NETHER_PORTAL` if either end is the nether, `END_PORTAL` if either is the end, otherwise **`OTHER`**,
    which renders the panorama, blur and menu background (`LevelLoadingScreen.java:144-193`).
- `LevelLoadTracker` closes the screen after `LEVEL_CHUNKS_LOAD_START` arrives, once one of these holds:
  - the section around the camera has compiled;
  - the camera is **outside the build height**, or the player is a spectator or dead (ready immediately);
  - 30 s have passed.

  The client then sends `ServerboundPlayerLoadedPacket` (`client/.../LevelLoadTracker.java:112-134`).
- Arriving above the Mars build limit therefore closes the screen almost at once.

### 6.4 Fabric events (`fabric/.../api/entity/event/v1/ServerEntityLevelChangeEvents.java`)

- **`AFTER_ENTITY_CHANGE_LEVEL`**: `afterChangeLevel(Entity original, Entity newEntity, ServerLevel origin, ServerLevel destination)`.
  - Fired around `teleportCrossDimension`, and only on success (`mixin/entity/event/EntityMixin.java`).
  - Not fired for players.
- **`AFTER_PLAYER_CHANGE_LEVEL`**: `afterChangeLevel(ServerPlayer, ServerLevel origin, ServerLevel destination)`.
  - Fired at the tail of `triggerDimensionChangeTriggers`.
  - Also fired from `PlayerList.respawn` when the respawn changes dimension.
- **`ServerPlayerEvents.AFTER_RESPAWN` / `COPY_FROM`**: respawns recreate the player.
- **Data attachments.** These are copied on cross-level entity teleports. Do not keep references to the entity
  instance (`AttachmentType` javadoc).
- There is no cancellable "allow change level" event.

---

## 7. Chunk loading and tickets

### 7.1 `TicketType` (`common/net/minecraft/server/level/TicketType.java`)

```java
public record TicketType(long timeout, @TicketType.Flags int flags)   // registered in BuiltInRegistries.TICKET_TYPE
NO_TIMEOUT = 0; FLAG_PERSIST = 1; FLAG_LOADING = 2; FLAG_SIMULATION = 4; FLAG_KEEP_DIMENSION_ACTIVE = 8; FLAG_CAN_EXPIRE_IF_UNLOADED = 16;
```

| vanilla | timeout | flags |
|---|---|---|
| `player_spawn` | 20 | LOADING |
| `spawn_search` | 1 | LOADING |
| `dragon` | 0 | LOADING, SIMULATION |
| `player_loading` | 0 | LOADING |
| `player_simulation` | 0 | SIMULATION, KEEP_ACTIVE |
| `forced` | 0 | PERSIST, LOADING, SIMULATION, KEEP_ACTIVE |
| `portal` | 300 | PERSIST, LOADING, SIMULATION, KEEP_ACTIVE |
| `ender_pearl` | 40 | LOADING, SIMULATION, KEEP_ACTIVE |
| `unknown` | 1 | LOADING, CAN_EXPIRE_IF_UNLOADED |

Declaring a custom type:

```java
public static final TicketType LANDING_SITE = Registry.register(BuiltInRegistries.TICKET_TYPE,
        Identifier.fromNamespaceAndPath(RedPlanet.MOD_ID, "landing_site"),
        new TicketType(20L * 60L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));
```

- **Timeouts** (`TicketStorage.purgeStaleTickets` and `canTicketExpire`):
  - The timeout counts down only while the chunk holder is loaded and "ready for saving", unless
    `CAN_EXPIRE_IF_UNLOADED` is set.
  - Adding the same type at the same level again **resets** the countdown (`addTicket`, `:147-158`). Vanilla refreshes
    ender-pearl tickets every tick this way.
- **Persistence.**
  - `PERSIST` tickets are saved in the dimension's `TicketStorage` saved data (`chunk_tickets`). This replaces the old
    `ForcedChunksSavedData`.
  - `MinecraftServer.prepareLevels` re-activates them at startup and *blocks startup until they load*.
  - Persisted tickets reference the type by name, so leave persistence off.
- Fabric API has no ticket helpers.

### 7.2 Radius semantics (`ChunkLevel.java`)

- Chunk levels: 33 is FULL, 32 is BLOCK_TICKING, 31 is ENTITY_TICKING.
- `addTicketWithRadius(type, pos, r)` places a ticket at level `33 - r` (`TicketStorage.java:138-141`). Chunks at
  Chebyshev distance d get level `33 - r + d`. With `r` = 4:
  - FULL out to distance 4 (9×9);
  - block ticking out to 3 (7×7);
  - **entity ticking out to 2 (5×5)**.
- Simulation, i.e. entity and block ticking, comes only from tickets with `FLAG_SIMULATION`
  (`DistanceManager.java:135-141`).
- `FORCED` tickets sit at `ChunkMap.FORCED_TICKET_LEVEL = 31`.

### 7.3 API (`ServerChunkCache`, available as `serverLevel.getChunkSource()`)

```java
public CompletableFuture<?> addTicketAndLoadWithRadius(TicketType type, ChunkPos pos, int radius)  // async, all FULL; throws if
        // !type.doesLoad() or type.canExpireIfUnloaded()                                             (:479-493)
public void addTicketWithRadius(TicketType type, ChunkPos pos, int radius)                          (:495)
public void removeTicketWithRadius(TicketType type, ChunkPos pos, int radius)                       (:499)  // same type+radius
public void addTicket(Ticket ticket, ChunkPos pos)
public boolean updateChunkForced(ChunkPos pos, boolean forced) / LongSet getForceLoadedChunks()
public CompletableFuture<ChunkResult<ChunkAccess>> getChunkFuture(int x, int z, ChunkStatus s, boolean load)  // BLOCKS (managedBlock) on main thread!
public @Nullable ChunkAccess getChunk(int x, int z, ChunkStatus s, boolean load)                // BLOCKS on main thread
public @Nullable LevelChunk getChunkNow(int x, int z)                                            // non-blocking, main thread only
public ChunkGenerator getGenerator(); public RandomState randomState(); public boolean hasActiveTickets()
// ServerLevel:
public boolean setChunkForced(int chunkX, int chunkZ, boolean forced)   // adds FORCED and then getChunk() → BLOCKS
```

- `ChunkLoadCounter.track(level, () -> addTickets...)` with `readyChunks()`/`totalChunks()` gives you a progress figure.
  `PrepareSpawnTask` does this.
- **Dimension activity.**
  - `ServerLevel.tick` does `if (chunkSource.hasActiveTickets()) resetEmptyTime(); emptyTime++;` and skips **all entity
    and block-entity ticking** once `emptyTime >= 300` (`ServerLevel.java:417-426`).
  - `hasActiveTickets()` means some ticket has `FLAG_KEEP_DIMENSION_ACTIVE`; player simulation tickets have it.
  - An uncrewed ship arriving on Mars therefore needs such a ticket. `teleportCrossDimension` also calls
    `resetEmptyTime()` once.

### 7.4 Vanilla's async pattern (`PlayerSpawnFinder.java:113-145`, `PrepareSpawnTask.java:132-150`)

```java
level.getChunkSource().addTicketAndLoadWithRadius(TicketType.SPAWN_SEARCH, new ChunkPos(cx, cz), 0)
     .whenCompleteAsync((ignored, throwable) -> { /* runs on the server thread */ }, level.getServer());
```

### 7.5 Pre-generating and validating a landing site

1. **Pick a site.** Optionally pre-screen candidates with no generation at all:
   `generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, chunkSource.randomState())`
   (`ChunkGenerator.java:662`). Sample a few points per candidate to reject slopes, canyons and craters cheaply.
2. **Load it.** On the server thread, call
   `level.getChunkSource().addTicketAndLoadWithRadius(LANDING_SITE, ChunkPos.containing(BlockPos.containing(x, 0, z)), 4)`.
   Start it when the transfer cut-scene begins; that gives seconds of slack.
3. **Validate.** In `whenCompleteAsync(..., server)`, check the site:
   - `level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz)` over the ship footprint, for slope;
   - `level.getFluidState(pos).isEmpty()`;
   - `getBlockState(pos).isFaceSturdy(level, pos, Direction.UP)`.

   If it fails, remove the ticket and try the next candidate, as `PlayerSpawnFinder.scheduleNext()` does.
4. **Hold it.** Keep the ticket through landing by re-adding it every tick (re-adding resets the timeout) or with a long
   timeout. Then `removeTicketWithRadius(LANDING_SITE, center, 4)`.
5. **Teleport** the ship only after the future has completed. If it is late, either hold the cut-scene or use a timeout
   fallback.

---

## 8. Spawn and respawn

- **Death.** `PlayerList.respawn` (`:386-430`) calls `findRespawnPositionAndUseSpawnBlock(!keepAllPlayerData, DO_NOTHING)`
  (`ServerPlayer.java:1006-1021`):
  - **No respawn config:** `TeleportTransition.createDefault` sends the player to the **world spawn**, in the dimension
    `server.findRespawnDimension()` returns. That is the overworld unless the world spawn was moved; it falls back to
    the overworld if the level is missing (`MinecraftServer.java:1676-1681`).
  - **A config exists but the bed or anchor is invalid:** `missingRespawnBlock` gives the world spawn plus a "no
    respawn block" message.
  - **A config exists and is valid:** the player respawns in *that* dimension, even if it is Mars.
- **Dying on Mars** with no Mars bed or anchor puts the player back in the overworld. The player is **recreated** as a
  new `ServerPlayer` (`restoreFrom`), and the client goes through `handleRespawn` and the loading screen.
- **Validity checks** (`findRespawnAndUseSpawnBlock`, `:1080-1110`):
  - A respawn anchor needs charge (or "forced") **and** `RespawnAnchorBlock.canSetSpawn`, i.e. the
    `respawn_anchor_works` attribute at its position.
  - Beds need `bedRule.canSetSpawn(level)`.
  - A forced spawn needs two free blocks.
- **Bed rules come from attributes.** `AbstractBedBlock.getBedRule` reads `BED_RULE`, and `STRAW_BED_RULE` for straw
  beds, at the bed position.
  - `can_sleep` and `can_set_spawn` take `always`, `when_dark` or `never`.
  - The optional fields are `destroy_on_use` (the nether/end explosion), `destroy_on_leave` and `error_message`.
  - Defaults when a dimension sets nothing: **beds work** (sleep `when_dark`, set spawn `always`) and **anchors do
    not** (`respawn_anchor_works` false, so using a charged anchor explodes, `RespawnAnchorBlock.java:97-101`).
- **Design consequence.** If Mars beds may set spawn, a player who dies on Earth respawns on Mars with no rocket. Choose
  deliberately. One option is `"can_set_spawn": "never"` with an `error_message` on Mars plus a mod habitat spawn block.
  Another is to allow it and accept the shortcut.

---

## 9. Recommendations for redplanet

### 9.1 Data files (field names verified against the codecs above; numbers marked † are placeholders for `SCIENCE.md`)

**`data/redplanet/world_clock/mars.json`**

```json
{}
```

**`data/redplanet/tags/timeline/in_mars.json`.** `#minecraft:universal` is left out on purpose: that villager schedule
runs on the overworld clock.

```json
{ "values": [ "redplanet:mars_day", "redplanet:dust_storm" ] }
```

**`data/redplanet/dimension_type/mars.json`**

```json
{
  "has_skylight": true,
  "has_ceiling": false,
  "has_ender_dragon_fight": false,
  "has_fixed_time": false,
  "coordinate_scale": 1.0,
  "min_y": -64,
  "height": 512,
  "logical_height": 512,
  "infiniburn": "#minecraft:infiniburn_overworld",
  "ambient_light": 0.0,
  "monster_spawn_light_level": 0,
  "monster_spawn_block_light_limit": 0,
  "skybox": "overworld",
  "cardinal_light": "default",
  "default_clock": "redplanet:mars",
  "timelines": "#redplanet:in_mars",
  "attributes": {
    "minecraft:visual/sky_color": "#c9a27e",
    "minecraft:visual/fog_color": "#b8916c",
    "minecraft:visual/ambient_light_color": "#0a0a0a",
    "minecraft:visual/star_brightness": 0.8,
    "minecraft:visual/moon_phase": "new_moon",
    "minecraft:gameplay/water_evaporates": true,
    "minecraft:gameplay/can_start_raid": false,
    "minecraft:gameplay/bed_rule": { "can_sleep": "when_dark", "can_set_spawn": "always" },
    "minecraft:gameplay/respawn_anchor_works": false,
    "redplanet:gameplay/gravity": 0.379,
    "redplanet:gameplay/surface_pressure": 610.0,
    "redplanet:gameplay/breathable": false
  }
}
```

Notes on that file:

- **Height.** `height: 512` with `min_y: -64` gives a top build Y of 447. That is legal (multiples of 16, sum ≤ 2032).
  Benchmark memory on the 8 GB Mac before going taller; 4064 is the hard cap.
- **Sky colours† (`sky_color`, `fog_color`).** These are placeholders. The timeline multiplies them toward black at
  night. Mars biomes may override them per biome with plain values.
- **Stars.** `star_brightness: 0.8`† is the *night maximum*. The Mars timeline multiplies it by 0 by day and 1 at
  night, which keeps it commutative with the dust-storm multiplier.
- **Hiding the Earth moon.** `moon_phase: new_moon`, plus the timeline's `moon_angle` of 180° (nadir), keeps the moon
  out of sight. The additive blend makes a near-black sprite almost invisible. **UNVERIFIED visually**: look at a
  screenshot. Phobos and Deimos need custom rendering, but a separate timeline with period ≈ 11 120 ticks could drive
  `moon_angle` west→east for Phobos.
- **Custom attributes.** The three `redplanet:` attributes are valid only once the mod registers them (§2.7). The mod
  must load before world load, which is always true.
- **Sounds.** Add audio after the sound events exist: `"minecraft:audio/ambient_sounds": {"loop": "redplanet:ambient.mars.wind"}`
  needs `redplanet:ambient.mars.wind` registered in `BuiltInRegistries.SOUND_EVENT`. The inline form
  `{"sound_id": "redplanet:ambient.mars.wind"}` also works, because `SoundEvent.CODEC` is a registry-or-direct holder
  codec.
- **No clouds.** Vanilla's default `cloud_color` is fully transparent, so Mars has no clouds unless one is set.

**`data/redplanet/dimension/mars.json`.** The generator is a placeholder for the worldgen work.

```json
{
  "type": "redplanet:mars",
  "generator": {
    "type": "minecraft:noise",
    "biome_source": { "type": "minecraft:fixed", "biome": "redplanet:mars_lowlands" },
    "settings": "redplanet:mars"
  }
}
```

Every Mars biome should set:

- `"has_precipitation": false`;
- no `natural_mob_spawns` (the default is empty, so nothing spawns);
- the required `"temperature"`, `"downfall"`, `"effects": {"water_color": ...}`, `"carvers": []` and
  `"features": [...]`.

### 9.2 `data/redplanet/timeline/mars_day.json`

- This is the vanilla `day.json`, time-scaled by 24 660/24 000 = 1.0275.
- Day-life tracks for bees, creaking, eyeblossom and the like were dropped.
- The moon is parked, and stars are normalised to `multiply`.
- Sunrise and sunset recoloured blue† with vanilla's alpha envelope kept.
- Generated and checked by script: keyframes are sorted, inside [0, 24660], at most two per tick, and the markers are
  inside [0, 24660).
- Mars twilight is longer than Earth's, so `SCIENCE.md` should retune the transition ticks†.

```json
{
  "clock": "redplanet:mars",
  "period_ticks": 24660,
  "time_markers": {
    "minecraft:day": {"show_in_commands": true, "ticks": 1028},
    "minecraft:noon": {"show_in_commands": true, "ticks": 6165},
    "minecraft:night": {"show_in_commands": true, "ticks": 13358},
    "minecraft:midnight": {"show_in_commands": true, "ticks": 18495},
    "minecraft:wake_up_from_sleep": 0
  },
  "tracks": {
    "minecraft:visual/sun_angle": {
      "ease": {"cubic_bezier": [0.362, 0.241, 0.638, 0.759]},
      "keyframes": [
        {"ticks": 6165, "value": 360.0},
        {"ticks": 6165, "value": 0.0}
      ]
    },
    "minecraft:visual/star_angle": {
      "ease": {"cubic_bezier": [0.362, 0.241, 0.638, 0.759]},
      "keyframes": [
        {"ticks": 6165, "value": 360.0},
        {"ticks": 6165, "value": 0.0}
      ]
    },
    "minecraft:visual/moon_angle": {
      "keyframes": [
        {"ticks": 0, "value": 180.0}
      ]
    },
    "minecraft:gameplay/sky_light_level": {
      "modifier": "multiply",
      "keyframes": [
        {"ticks": 137, "value": 1.0},
        {"ticks": 12193, "value": 1.0},
        {"ticks": 14046, "value": 0.26666668},
        {"ticks": 22944, "value": 0.26666668}
      ]
    },
    "minecraft:visual/sky_light_factor": {
      "modifier": "multiply",
      "keyframes": [
        {"ticks": 750, "value": 1.0},
        {"ticks": 11580, "value": 1.0},
        {"ticks": 13501, "value": 0.24},
        {"ticks": 23489, "value": 0.24}
      ]
    },
    "minecraft:visual/sky_light_color": {
      "modifier": "multiply",
      "keyframes": [
        {"ticks": 750, "value": "#ffffff"},
        {"ticks": 11580, "value": "#ffffff"},
        {"ticks": 13501, "value": "#7a7aff"},
        {"ticks": 23489, "value": "#7a7aff"}
      ]
    },
    "minecraft:visual/sky_color": {
      "modifier": "multiply",
      "keyframes": [
        {"ticks": 137, "value": "#ffffff"},
        {"ticks": 12193, "value": "#ffffff"},
        {"ticks": 14046, "value": "#000000"},
        {"ticks": 22944, "value": "#000000"}
      ]
    },
    "minecraft:visual/fog_color": {
      "modifier": "multiply",
      "keyframes": [
        {"ticks": 137, "value": "#ffffff"},
        {"ticks": 12193, "value": "#ffffff"},
        {"ticks": 14046, "value": "#0f0f16"},
        {"ticks": 22944, "value": "#0f0f16"}
      ]
    },
    "minecraft:visual/cloud_color": {
      "modifier": "multiply",
      "keyframes": [
        {"ticks": 137, "value": "#ffffffff"},
        {"ticks": 12193, "value": "#ffffffff"},
        {"ticks": 14046, "value": "#ff191926"},
        {"ticks": 22944, "value": "#ff191926"}
      ]
    },
    "minecraft:visual/star_brightness": {
      "modifier": "multiply",
      "keyframes": [
        {"ticks": 95, "value": 0.074},
        {"ticks": 644, "value": 0.0},
        {"ticks": 11686, "value": 0.0},
        {"ticks": 12055, "value": 0.032},
        {"ticks": 12288, "value": 0.088},
        {"ticks": 12740, "value": 0.286},
        {"ticks": 13079, "value": 0.516},
        {"ticks": 13592, "value": 1.0},
        {"ticks": 23398, "value": 1.0},
        {"ticks": 23665, "value": 0.728},
        {"ticks": 23998, "value": 0.45},
        {"ticks": 24411, "value": 0.202}
      ]
    },
    "minecraft:visual/sunrise_sunset_color": {
      "keyframes": [
        {"ticks": 73, "value": "#5f7fa6e0"},
        {"ticks": 319, "value": "#297fa6e0"},
        {"ticks": 581, "value": "#067fa6e0"},
        {"ticks": 750, "value": "#007fa6e0"},
        {"ticks": 11580, "value": "#007fa6e0"},
        {"ticks": 11710, "value": "#047fa6e0"},
        {"ticks": 11839, "value": "#0f7fa6e0"},
        {"ticks": 12011, "value": "#297fa6e0"},
        {"ticks": 12257, "value": "#5f7fa6e0"},
        {"ticks": 12580, "value": "#b17fa6e0"},
        {"ticks": 12698, "value": "#cc7fa6e0"},
        {"ticks": 12856, "value": "#e97fa6e0"},
        {"ticks": 12960, "value": "#f67fa6e0"},
        {"ticks": 13082, "value": "#fe7fa6e0"},
        {"ticks": 13194, "value": "#fe7fa6e0"},
        {"ticks": 13393, "value": "#ec7fa6e0"},
        {"ticks": 13616, "value": "#c17fa6e0"},
        {"ticks": 14154, "value": "#367fa6e0"},
        {"ticks": 14270, "value": "#1f7fa6e0"},
        {"ticks": 14425, "value": "#097fa6e0"},
        {"ticks": 14582, "value": "#007fa6e0"},
        {"ticks": 22407, "value": "#007fa6e0"},
        {"ticks": 22565, "value": "#097fa6e0"},
        {"ticks": 22720, "value": "#1f7fa6e0"},
        {"ticks": 22836, "value": "#367fa6e0"},
        {"ticks": 23374, "value": "#c17fa6e0"},
        {"ticks": 23597, "value": "#ec7fa6e0"},
        {"ticks": 23796, "value": "#fe7fa6e0"},
        {"ticks": 23912, "value": "#fe7fa6e0"},
        {"ticks": 24134, "value": "#e97fa6e0"},
        {"ticks": 24292, "value": "#cc7fa6e0"},
        {"ticks": 24410, "value": "#b17fa6e0"}
      ]
    }
  }
}
```

- Only **this** timeline may define markers for the `redplanet:mars` clock. A second definition of the same marker id
  is a load error.
- **Starting time.** The Mars clock starts at 0 on first load, which is just before sunrise at 06:00 LMST. To land at a
  chosen local time, call `server.clockManager().setTotalTicks(marsClockHolder, t)` once. Get the holder with
  `registryAccess().getOrThrow(ResourceKey.create(Registries.WORLD_CLOCK, id))`.
- **Seasons.** A second timeline on the same clock with `period_ticks` = 668.6 × 24 660 ≈ **16 487 676** fits in an int.
  It could drive seasonal custom attributes (temperature, CO₂ frost) with `add`/`multiply` tracks, without markers.

### 9.3 Code skeleton (signatures verified)

**Attributes.** See §2.7 and register them in `onInitialize()`. Read them on the server with
`level.environmentAttributes().getDimensionValue(RedPlanetAttributes.GRAVITY)`. On the client, read them the same way
from `Minecraft.getInstance().level`, or through the camera probe for visuals.

**Ticket type.** See §7.1.

**Mixins.** Keep each one small and commented.

1. `Level.canHaveWeather()` returns false for `redplanet` dimensions. It must not call `environmentAttributes()`,
   because it runs while the attribute system is still being built.
2. `Level.getRainLevel(float)` and `getThunderLevel(float)` return 0 for `redplanet` dimensions. This blocks the global
   weather broadcast leak on the client.
3. `EnvironmentAttributeSystem.Builder#addDynamicLayers(Level)` at RETURN installs our layers:
   - altitude sky darkening and star fade-in, for the overworld ascent and the Mars descent;
   - optional pressure and temperature by altitude;
   - habitat overrides.

**Teleporting the ship**, crew included:

```java
ServerLevel mars = server.getLevel(RedPlanetDimensions.MARS);                 // never null once the mod's data loads
TeleportTransition t = new TeleportTransition(mars, arrivalPos, arrivalVelocity, yaw, 0.0F,
        TeleportTransition.DO_NOTHING.then(e -> ((StarshipEntity) e).onArrivedFromTransfer()));
Entity newShip = ship.teleport(t);   // players ride along and are re-mounted; returns the NEW instance (same UUID) or null
```

- The ship's whole state (flight phase, propellant, cargo, mission id) has to be in `addAdditionalSaveData` and
  `readAdditionalSaveData`, because cross-dimension teleport rebuilds the entity from NBT.
- The ship's `EntityType` must be serializable, i.e. not `noSave()`, or re-mounting fails.
- Use `ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL` to re-bind any external registry keyed on the old
  instance. Key it by UUID instead.

**Landing site.** Follow §7.5. Arrive above the build limit (y > 447 with the skeleton above) so the client loading
screen closes immediately (§6.3). Then fly the descent in-world.

### 9.4 Data-driven dust storms with no networking (optional)

- Add `data/redplanet/world_clock/dust_storm.json` = `{}` and the timeline below, listed in `#redplanet:in_mars`.
- Server control:
  - normally `clockManager.setPaused(stormClock, true)` at 0, which is calm;
  - to start a storm, resume the clock;
  - to end it early, `setTotalTicks(stormClock, 48000)` (the fade);
  - afterwards, reset to 0 and pause.
- Every change is broadcast to clients automatically (`ServerClockManager.modifyClock`). Admins can also run
  `/time of redplanet:dust_storm resume`.
- All colour and star operations are `multiply` and the fog operations are `minimum`, so the result does not depend on
  layer order relative to `mars_day`.
- Caveat: `ADVANCE_TIME=false` freezes storms as well.

```json
{
  "clock": "redplanet:dust_storm",
  "time_markers": { "redplanet:storm_calm": 0, "redplanet:storm_peak": 2400, "redplanet:storm_fade": 48000, "redplanet:storm_over": 50400 },
  "tracks": {
    "minecraft:visual/fog_end_distance":     { "modifier": "minimum",  "keyframes": [ {"ticks": 0, "value": 1024.0}, {"ticks": 2400, "value": 40.0}, {"ticks": 48000, "value": 40.0}, {"ticks": 50400, "value": 1024.0} ] },
    "minecraft:visual/sky_fog_end_distance": { "modifier": "minimum",  "keyframes": [ {"ticks": 0, "value": 512.0},  {"ticks": 2400, "value": 24.0}, {"ticks": 48000, "value": 24.0}, {"ticks": 50400, "value": 512.0} ] },
    "minecraft:visual/sky_color":            { "modifier": "multiply", "keyframes": [ {"ticks": 0, "value": "#ffffff"}, {"ticks": 2400, "value": "#9c7c5c"}, {"ticks": 48000, "value": "#9c7c5c"}, {"ticks": 50400, "value": "#ffffff"} ] },
    "minecraft:visual/fog_color":            { "modifier": "multiply", "keyframes": [ {"ticks": 0, "value": "#ffffff"}, {"ticks": 2400, "value": "#a8825e"}, {"ticks": 48000, "value": "#a8825e"}, {"ticks": 50400, "value": "#ffffff"} ] },
    "minecraft:visual/star_brightness":      { "modifier": "multiply", "keyframes": [ {"ticks": 0, "value": 1.0}, {"ticks": 2400, "value": 0.0}, {"ticks": 48000, "value": 0.0}, {"ticks": 50400, "value": 1.0} ] },
    "minecraft:visual/sky_light_factor":     { "modifier": "multiply", "keyframes": [ {"ticks": 0, "value": 1.0}, {"ticks": 2400, "value": 0.55}, {"ticks": 48000, "value": 0.55}, {"ticks": 50400, "value": 1.0} ] },
    "minecraft:visual/sunrise_sunset_color": { "modifier": "multiply", "keyframes": [ {"ticks": 0, "value": "#ffffffff"}, {"ticks": 2400, "value": "#33ffffff"}, {"ticks": 48000, "value": "#33ffffff"}, {"ticks": 50400, "value": "#ffffffff"} ] },
    "minecraft:visual/ambient_particles":    { "modifier": "append", "keyframes": [
        {"ticks": 0, "value": []},
        {"ticks": 2400,  "value": [{"particle": {"type": "minecraft:white_ash"}, "probability": 0.02}]},
        {"ticks": 48000, "value": [{"particle": {"type": "minecraft:white_ash"}, "probability": 0.02}]},
        {"ticks": 50400, "value": []} ] }
  }
}
```

- With no `period_ticks` the timeline is non-periodic: values hold at the edges.
- The `append` keyframe lerp cross-fades the particle lists, scaling their probabilities.
- Ambient particles spawn client-side in air blocks around the player (`ClientLevel.java:622-630`).
- Replace `minecraft:white_ash` with a custom dust particle later.
- A custom `redplanet:gameplay/solar_irradiance` attribute could be multiplied by the same storm timeline to dim solar
  panels.

### 9.5 Risks and surprises

1. **Global weather** doubles Earth's weather speed and makes it rain on Mars unless `canHaveWeather` is patched (§4).
2. **The overworld weather broadcast** dims the Mars sun and stars on clients, so patch the client rain level too (§4).
3. **Missing celestial tracks** leave the sun and moon parked at the zenith (§3.7).
4. **Clocks are global.** Markers are unique per clock, `ADVANCE_TIME` pauses every clock, and the Mars clock runs
   while nobody is there.
5. **Custom attributes need `.syncable()`** to exist client-side. Biomes accept only positional attributes.
6. **Cross-dimension teleport rebuilds the ship from NBT.** It gets a new instance and id; the UUID is kept; unsaved
   state is lost and leashes come off. Players stay the same instance and must ride the ship to stay seated.
7. **The `LevelLoadingScreen` always appears** on a dimension change. Arriving above the build height makes it close
   fastest. Overlay it client-side for the cut-scene.
8. **Mars stops ticking entities** 300 ticks after the last player leaves, unless a `KEEP_DIMENSION_ACTIVE` ticket is
   present.
9. **`setChunkForced`, `getChunk` and main-thread `getChunkFuture` block the server.** Use
   `addTicketAndLoadWithRadius` instead.
10. **Phantoms on return to Earth** if the player never slept on Mars.
11. **Respawn and beds:** a Mars bed makes Earth deaths respawn on Mars (§8).
12. **Uninstalling the mod.**
    - Fabric fail-softs the dimension list.
    - `world_clocks.dat` would hold an unknown clock key. It is decoded with `resultOrPartial`, so the overworld clock
      should survive. That is **UNVERIFIED** in game.

### 9.6 Still UNVERIFIED (needs an in-game or gametest check)

- How "moon at nadir + `new_moon`" and the blue sunset colours actually look. Take screenshots.
- The exact client failure mode when a client lacks our attributes.
- Whether `/reload` changes the timelines of already-built levels. The attribute systems are built once per level
  construction, so probably not.
- Partial decoding of `world_clocks.dat` after the mod is removed.
- The client registry-freeze timing relative to mod init. Fabric's standard documented flow is to register in
  `onInitialize`; the order was not traced through the Loader.
- Post-effect rendering details for a dust-storm overlay.
