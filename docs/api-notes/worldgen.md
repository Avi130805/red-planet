# World generation API notes: Minecraft Java 26.3 (Fabric)

Research notes for the Mars dimension in **Red Planet: Starship to Mars** (`redplanet`,
`io.github.avi130805.redplanet`). Every claim below comes from the decompiled 26.3 sources, the vanilla data in
the 26.3 client jar, or the Fabric API 0.161.0+26.3 sources. Nothing is from 1.21.x memory unless it is
explicitly labelled as a comparison. Speculative items are marked **UNVERIFIED**.

**How to read the citations.** Paths are relative to `/home/user/mcsrc/`:

- `common/...` holds the decompiled common and server classes. Fabric transitive access wideners are already
  applied, and the sources mark them with "Access widened by fabric-transitive-access-wideners-v1".
- `client/...` holds the client-only classes.
- `jar-client/data/minecraft/...` holds the vanilla data.
- `fabric-api/src/...` holds the Fabric API sources.

Line numbers are approximate (±3).

`version.json` reports `"world_version": 5023`, so the structure-NBT `DataVersion` is **5023**.

---

## 0. What changed compared with 1.21.x

The "26.3 reality" column is verified against the sources. The 1.21.x side of each comparison is general
background and was not re-checked. Removals marked "gone" were confirmed by grepping the 26.3 code and data.

| Area | 26.3 reality |
|---|---|
| `noise_settings` | `noise` holds only `{min_y, height}`. The `size_horizontal` and `size_vertical` fields are gone, and cell size now lives in each `interpolated` density function. `surface_rule` is replaced by **`material_rule`**, a registry holder. `aquifers_enabled` is replaced by an optional **`aquifers`** object. **`ore_veins_enabled` is gone**: ore veins are now a material rule (`minecraft:ore_vein`). A new optional `debug_functions` list feeds the F3 screen. |
| Noise router | It has only **8 fields**: `temperature`, `vegetation`, `continents`, `erosion`, `depth`, `ridges`, `chunk_surface_level` (new), and `final_density`. The barrier, fluid, lava, and vein fields moved or disappeared, and `preliminary_surface_level` now feeds `aquifers.surface_level` and `chunk_surface_level`. |
| `spawn_target` | It is now a list of maps, each from a density-function **id** to `[min, max]`. |
| Density functions | The API is completely new. It uses `compileSampler(CompileContext)` returning a `DensitySampler` (`sampleValue` / `sampleVolume`), plus `rewriteChildren(DfRewriteRule)`, `range()` returning an `Interval`, `domainAxes()`, and a plain `MapCodec`. Values are **`float`**. The following no longer exist: `KeyDispatchDataCodec`, `NoiseHolder`, `FunctionContext`, `fillArray`, `mapAll`, `flat_cache`, `cache_2d`, `cache_once`, `cache_all_in_cell`, `shifted_noise`, `y_clamped_gradient`, `weird_scaled_sampler`, and `end_islands` (grep finds nothing). |
| Noise JSON | The format is `{base_amplitude, base_octave, octave_count, normalize, amplitude_modifiers}`. Noise samplers are only created for **registered** noises. |
| BlockState JSON | The format is `"minecraft:stone"` or `{"id": ..., "properties": {...}}`, in lowercase. The old `Name`/`Properties` keys are gone, and NBT palettes also use `id`/`properties`. |
| Surface rules | They are now **material rules** and **material conditions**, held in registries `worldgen/material_rule` and `worldgen/material_condition`, with type registries `worldgen/material_rule_type` and `worldgen/material_condition_type`. |
| BiomeSource | The abstract method is now **`createResolver(Climate.Sampler)` returning a `BiomeResolver`**. `getNoiseBiome` is no longer a method on the source. There is an optional `createResolverForChunk(...)` batch hook. |
| Biome JSON | Sky, fog, music, particles, and **mob spawns** are now environment **`attributes`**. Spawns use `minecraft:gameplay/natural_mob_spawns`. `effects` keeps only water, foliage, and grass colours. |
| Features | `ConfiguredFeature`, `FeatureConfiguration`, and `FeaturePlaceContext` are gone. A `Feature` is now a **record carrying its own config**. The data folder is `worldgen/feature`, the type registry is `worldgen/feature_type`, and the method is `place(level, generator, random, origin)`. Notable 26.3 types include the `template` feature (vanilla builds the desert well from it) and the placement modifiers `fixed_placement`, `offset`, `randomly_selected`, `cuboid`, and `random_chance`. Whether each is new since 1.21 was not checked. |
| Carvers | There is no `config` wrapper, no `lava_level`, and no `replaceable`. Carving now fills through the aquifer, and only the `#minecraft:uncarvable` tag (bedrock) is protected. |
| Chunk pipeline | Noise, surface, and carvers are merged into a single **`terrain`** status. |
| Structure placement | `StructurePlacement` is now a small interface (`isStructureChunk`). A new `dimension_origin` placement exists but vanilla does not use it. |

---

## 1. Noise settings (`data/<ns>/worldgen/noise_settings/*.json`)

### 1.1 Java record

`common/net/minecraft/world/level/levelgen/NoiseGeneratorSettings.java:24-54`

```java
public record NoiseGeneratorSettings(
    NoiseSettings noiseSettings, BlockState defaultBlock, BlockState defaultFluid, NoiseRouter noiseRouter,
    Holder<MaterialRule> materialRule, List<SpawnTargetPoint> spawnTarget, int seaLevel,
    @Deprecated boolean disableMobGeneration, Optional<Aquifer.Config> aquifers, boolean useLegacyRandomSource,
    NoiseGeneratorSettings.DebugFunctions debugFunctions)
// codec fields:
//  "noise" (NoiseSettings), "default_block", "default_fluid", "noise_router", "material_rule" (MaterialRule.HOLDER_CODEC),
//  "spawn_target" (list), "sea_level" (int), "disable_mob_generation" (bool), "aquifers" (optional Aquifer.Config),
//  "legacy_random_source" (bool), "debug_functions" (optional list of {label, function})
```

| Field | Required | Meaning in 26.3 |
|---|---|---|
| `noise.min_y` | yes | `intRange(DimensionType.MIN_Y, MAX_Y)`, which is [-2032, 2031]. Must be a **multiple of 16**. |
| `noise.height` | yes | `intRange(0, Y_SIZE=4064)`. Must be a **multiple of 16**, and `min_y + height <= 2032` (`NoiseSettings.java:10-33`). |
| `default_block` | yes | Placed wherever `final_density > 0` (through the aquifer). |
| `default_fluid` | yes | The fluid placed below `sea_level` where density <= 0. Use `minecraft:air` for none. |
| `noise_router` | yes | 8 density functions (§1.3). |
| `material_rule` | yes | A registry id string or an inline rule object (§5). |
| `spawn_target` | yes | May be `[]` (§3.5). |
| `sea_level` | yes | int. Also drives the **hardcoded lava rule** (§3.3, §8.3), biome temperature height adjustment, and `relative_to_sea_level` anchors. |
| `disable_mob_generation` | yes | `@Deprecated`, but still required. `true` disables chunk-generation-time animal spawns (`NoiseBasedChunkGenerator.spawnOriginalMobs`, about line 464). |
| `aquifers` | no | Omitting it gives `Aquifer.createDisabled(globalFluidPicker)` (`NoiseChunk.java:41-47`). |
| `legacy_random_source` | yes | `false` selects Xoroshiro. `true` selects the legacy Java LCG (used by nether, end, caves, and floating islands). |
| `debug_functions` | no | `[{ "label": "N", "function": <df> }]`, shown on the F3 screen (`NoiseBasedChunkGenerator.addDebugScreenInfo`). |

### 1.2 Vanilla `overworld.json` (verbatim, with the long arrays trimmed)

```json
{
  "aquifers": {
    "barrier": { "type": "minecraft:noise", "noise": "minecraft:aquifer_barrier", "xz_scale": 1.0, "y_scale": 0.5 },
    "exclusion": { "type": "minecraft:min", "left": { "type": "minecraft:sub", "left": -0.225, "right": "minecraft:overworld/erosion" },
                   "right": { "type": "minecraft:max", "left": { "type": "minecraft:sub", "left": "minecraft:overworld/depth", "right": 0.9 }, "right": 0.0 } },
    "fluid_level_floodedness": { "type": "minecraft:noise", "noise": "minecraft:aquifer_fluid_level_floodedness", "xz_scale": 1.0, "y_scale": 0.67 },
    "fluid_level_spread": { "type": "minecraft:noise", "noise": "minecraft:aquifer_fluid_level_spread", "xz_scale": 1.0, "y_scale": 0.7142857142857143 },
    "lava": { "type": "minecraft:noise", "noise": "minecraft:aquifer_lava", "xz_scale": 1.0, "y_scale": 1.0 },
    "surface_level": "minecraft:overworld/preliminary_surface_level"
  },
  "debug_functions": [ { "function": "minecraft:overworld/final_density", "label": "N" }, "... T V C E D W PV PS ..." ],
  "default_block": "minecraft:stone",
  "default_fluid": "minecraft:water",
  "disable_mob_generation": false,
  "legacy_random_source": false,
  "material_rule": "minecraft:overworld",
  "noise": { "height": 384, "min_y": -64 },
  "noise_router": {
    "chunk_surface_level": "minecraft:overworld/chunk_surface_level",
    "continents": "minecraft:overworld/continents",
    "depth": "minecraft:overworld/depth",
    "erosion": "minecraft:overworld/erosion",
    "final_density": "minecraft:overworld/final_density",
    "ridges": "minecraft:overworld/ridges",
    "temperature": "minecraft:overworld/temperature",
    "vegetation": "minecraft:overworld/vegetation"
  },
  "sea_level": 63,
  "spawn_target": [
    { "minecraft:overworld/continents": [-0.11, 1.0], "minecraft:overworld/erosion": [-1.0, 1.0],
      "minecraft:overworld/ridges": [-1.0, -0.16], "minecraft:overworld/temperature": [-1.0, 1.0],
      "minecraft:overworld/vegetation": [-1.0, 1.0] },
    { "...": "same with ridges [0.16, 1.0]" }
  ]
}
```

Here are the other presets, summarised from the files in `jar-client/data/minecraft/worldgen/noise_settings/`.

| Preset | `noise` | default_block / fluid | sea | legacy RNG | `aquifers` | router (non-zero fields) |
|---|---|---|---|---|---|---|
| nether | 0 / 128 | netherrack / **lava** | 32 | true | — | temperature, vegetation (noise), final_density (inline) |
| end | 0 / 128 | end_stone / **air** | 0 | true | — | erosion = `minecraft:end/islands`, final_density (inline) |
| caves | -64 / 192 | stone / water | 32 | true | — | final_density only |
| floating_islands | 0 / 256 | stone / water | **-64** (below min_y) | true | — | final_density only |
| amplified, large_biomes | -64 / 384 | stone / water | 63 | false | yes | all 8 |

Here is the top of the End `final_density`, verbatim. Note the explicit cell size on `interpolated`:

```json
"final_density": { "type": "minecraft:add",
  "left": { "type": "minecraft:squeeze", "input": { "type": "minecraft:interpolated", "cell_size_xz": 8, "cell_size_y": 4,
     "input": { "type": "minecraft:mul", "left": { "type": "minecraft:blend_density", "input": { "type": "minecraft:lerp",
        "alpha": { "type": "minecraft:gradient", "axis": "y", "from_coordinate": 4, "from_value": 0.0, "to_coordinate": 32, "to_value": 1.0 },
        "first": -0.234375, "second": { "...": "lerp ... minecraft:end/sloped_cheese" } } }, "right": 0.64 } } },
  "right": { "type": "minecraft:beardifier" } }
```

Note that the End dimension type is 256 tall (`dimension_type/the_end.json`) while its noise height is 128. **The noise
volume may be smaller than the dimension.** `NoiseSettings.clampToHeightAccessor` clips it, and nothing is filled
above the noise top.

### 1.3 `NoiseRouter` (`common/net/minecraft/world/level/levelgen/NoiseRouter.java:9-43`)

```java
public record NoiseRouter(DensityFunction temperature, DensityFunction vegetation, DensityFunction continents,
    DensityFunction erosion, DensityFunction depth, DensityFunction ridges,
    DensityFunction chunkSurfaceLevel, DensityFunction finalDensity)
public Climate.Sampler createClimateSampler(DensitySamplerSet densitySamplers)  // temperature->temperature, vegetation->humidity, ... ridges->weirdness
```

Where each field is consumed:

- **`final_density`** fills every block (`NoiseBasedChunkGenerator.doFill`, about lines 391-430) and drives
  `getBaseHeight` and `getBaseColumn` (`iterateNoiseColumn`, about line 164).
- **`chunk_surface_level`** is passed into `MaterialSystem` as `preliminarySurfaceFunction`
  (`RandomState.java:71`). The material condition `above_preliminary_surface` uses it, through
  `MaterialRuleContext.getMinSurfaceLevel()` at about lines 121-141, as
  `blockY >= floor(PS(x,0,z)) + surfaceDepth - 8`. It is sampled at **y = 0** as a 2D function.
- The 6 climate fields are read only by biome sources, through `Climate.Sampler` (§4.5), and by `spawn_target`.

### 1.4 `aquifers` object (`common/net/minecraft/world/level/levelgen/Aquifer.java:47-73`)

The fields are `barrier`, `fluid_level_floodedness`, `fluid_level_spread`, `lava`, `exclusion`, and `surface_level`,
all density functions. Omitting the object disables aquifers (§8.3).

### 1.5 New registries: material rules, material conditions, block-state providers

These are data registries loaded from datapacks. Their order in
`common/net/minecraft/resources/RegistryDataLoader.java:99-149` is: ... `NOISE_SETTINGS`, `NOISE`, `DENSITY_FUNCTION`,
`MATERIAL_RULE`, `MATERIAL_CONDITION`, ..., `BLOCK_STATE_PROVIDER`.

| Registry key | Folder | Element | Codec |
|---|---|---|---|
| `worldgen/material_rule` | `data/<ns>/worldgen/material_rule/` | `MaterialRule` | dispatch on `"type"` from `BuiltInRegistries.MATERIAL_RULE_TYPE` |
| `worldgen/material_condition` | `.../worldgen/material_condition/` | `MaterialCondition` | dispatch from `MATERIAL_CONDITION_TYPE` |
| `worldgen/block_state_provider` | `.../worldgen/block_state_provider/` | `BlockStateProvider` | a bare block state, or a typed provider from `BLOCK_STATE_PROVIDER_TYPE` |

**Yes, surface rules are now a registry.** Any rule or condition may be either an inline object or an id string that
refers to a registered entry. Examples are `"minecraft:bedrock_floor"` and `"if_true": "minecraft:on_floor"`. See §5.

Here is a block-state provider (`block_state_provider/cave_vines_body.json`, verbatim):

```json
{ "type": "minecraft:weighted",
  "entries": [ { "data": "minecraft:cave_vines_plant", "weight": 4 },
               { "data": { "id": "minecraft:cave_vines_plant", "properties": { "berries": "true" } }, "weight": 1 } ] }
```

The provider types are `copy_properties`, `dual_noise`, `noise`, `noise_threshold`, `random_block`,
`randomized_int`, `rotated`, `rule_based`, `simple`, and `weighted` (`feature/stateproviders/BlockStateProviderTypes.java:8-17`).
`BlockStateProvider.CODEC` is a holder codec, so features may reference providers by id.

**BlockState JSON** (`common/net/minecraft/world/level/block/state/BlockState.java:11-16`, `StateHolder.java:148-162`)
is `CODEC = either(blockId, FULL_CODEC)`. `FULL_CODEC` dispatches on `"id"`, with an optional `"properties"`
map. So write `"minecraft:stone"` or `{"id":"minecraft:chest","properties":{"facing":"north"}}`.

**Noise JSON** (`common/net/minecraft/world/level/levelgen/synth/NormalNoise.java:352-370`) has these fields:
`base_amplitude` (optional, default 1.0), `base_octave` (int in [-32, 32]), `octave_count` (optional, default 1),
`normalize` (true/false/"legacy", default true), and `amplitude_modifiers` (optional, its size must equal
`octave_count`). Here is the vanilla `noise/surface.json`:

```json
{ "base_amplitude": 0.9381732587751008, "base_octave": -6, "octave_count": 3 }
```

The octave frequency is `2^base_octave` per noise unit, then multiplied by the density function's `xz_scale`/`y_scale`.

---

## 2. Density functions

### 2.1 The interface (`common/net/minecraft/world/level/levelgen/densityfunction/DensityFunction.java:19-164`)

```java
public interface DensityFunction {
    Codec<Holder<DensityFunction>> REFERENCE_CODEC = RegistryCodecs.holder(Registries.DENSITY_FUNCTION);   // ids only
    Codec<DensityFunction> CODEC = /* holder codec: id string -> HolderHolder(reference), number/object -> direct */;
    int AXIS_X = 1, AXIS_Y = 2, AXIS_Z = 4;  @Axes int NO_AXES = 0, ALL_AXES = 7;

    DensitySampler compileSampler(DensityFunction.CompileContext context);
    DensityFunction rewriteChildren(DfRewriteRule rule);
    Interval range();                      // net.minecraft.util.Interval
    @DensityFunction.Axes int domainAxes();
    MapCodec<? extends DensityFunction> codec();
    // default helpers: clamp, abs, square, cube, sqrt, halfNegative, quarterNegative, reciprocal, negate, squeeze,
    // log, sign, add, sub, mul, div, pow

    interface CompileContext {
        Noise createNoiseSampler(Holder<NormalNoise> parameters);
        RandomSource createRandom(Identifier seed);
        @Deprecated RandomSource createEndIslandRandom();
    }
}
```

`DensitySampler` (`DensitySampler.java:3-44`):

```java
public interface DensitySampler {
    void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume);
    float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ);
    static void sampleVolumeNaive(SamplerContext c, DensityBuffer out, DensityVolume v, DensitySampler s); // loops sampleValue
    default DensitySampler.Bound bind(SamplerContext context);
    record Bound(DensitySampler sampler, SamplerContext context) { float sampleValue(x,y,z); void sampleVolume(out, vol); ScopedDensityBuffer sampleVolume(vol); }
}
```

`DensityVolume` (`DensityVolume.java:6-95`) is the record
`(sizeX, sizeY, sizeZ, minBlockX, minBlockY, minBlockZ, stepBlockX, stepBlockY, stepBlockZ)`.
**The index order is `indexUnchecked(x,y,z) = y + (x + z*sizeX)*sizeY`, so Y varies fastest.** The world
coordinate of an index is `blockX(i) = minBlockX + i*stepBlockX`. A volume may be **strided**: steps of 4 for biome
quarts, or equal to the cell size inside `interpolated`. Implementations must honour `blockX()`, `blockY()`, and
`blockZ()`.

`DensityBuffer` (`DensityBuffer.java`) provides `set`, `setRange(index, size, value)`, `addTo`, `get`, `fill`, and
`copyFrom`. `SamplerContext.acquireBuffer(volume)` returns a pooled `ScopedDensityBuffer`; use it in
try-with-resources.

### 2.2 How a density function becomes a sampler

The compiler is `DensityFunctionCompiler.java:12-79`, and it is owned by `RandomState` (one instance per dimension
per world, `ChunkMap.java:179-185`).

1. `RandomState.getSampler(fn)` calls `samplers.computeIfAbsent(fn, optimizeAndCompile)`. The map is a
   `ConcurrentHashMap` keyed by the **root** function, so `equals`/`hashCode` matter. Compilation runs under a
   `ReentrantLock` and happens once per root.
2. The optimizer rule is `sequence(R1, DfRewriteRule.SLICE_UNIFORM_AXES)` (lines 16-22):
   - **R1** inlines `HolderHolder` references (`DfRewriteRule.INLINE_REFERENCE`) and replaces every `cache` node with
     a shared **`PreparedCache`**. That cache is **deduplicated by `cache.input()` equality** across the whole
     `RandomState` and gets a numeric cache id (lines 46-61).
   - **`SLICE_UNIFORM_AXES`** (`DfRewriteRule.java:26-75`) walks the tree. Wherever a child's `domainAxes()`
     lacks an axis its parent has, it wraps the child in `SliceFunction(axis, 0, child)`. **A function that
     declares `AXIS_X|AXIS_Z` (5) inside a 3D parent is therefore evaluated once per column at y = 0**, and its
     result is broadcast down the column with `setRange` (`SliceFunction.YSampler`, lines 141-168). This replaces
     the old `flat_cache`/`cache_2d`. The root parent counts as 7, so a 2D root gets the same treatment.
3. `compileSampler(ctx)` runs on the rewritten tree. Composite functions must call `child.compileSampler(ctx)`.
   **A `CacheFunction` that was never rewritten throws** `IllegalStateException("Cannot compile cache before it has been deduplicated")`
   (`op/CacheFunction.java:16-18`). So a custom function with children **must** implement `rewriteChildren`
   properly.

Evaluation contexts are per task:

- `NoiseChunk` (one per chunk in the terrain step) builds
  `SamplerContext.builder().setUserFields(beardifier/blender).useBufferArena(pool).enableCaches()`
  (`NoiseChunk.java:38-40`). `doFill`, the material system, and the carvers all share it.
- Biomes get their own context, with caches enabled (`ChunkGenerator.doCreateBiomes`, about line 134).
- `SamplerContext.EMPTY_UNCACHED` is used by the uncached biome resolver and by debug code.

`range()` is used for compile-time optimisations, and it **must be conservative**. `min`/`max` with
non-overlapping input ranges compile to just one side (`op/BinaryFunction.java:70-106`).
`MinSampler.sampleValue` skips the right side when `left <= right.range().min` (lines 314-336). A range that is
too narrow therefore gives **wrong terrain**, not just slower terrain.

### 2.3 All built-in density function types

The registry is `BuiltInRegistries.DENSITY_FUNCTION_TYPE`, key `worldgen/density_function_type`, and it holds
`MapCodec<? extends DensityFunction>`. It is bootstrapped by `DensityFunctions.bootstrap`
(`DensityFunctions.java:55-95`). A bare JSON number is a `constant` (`DIRECT_CODEC`, lines 46-52). An id string
refers to a `worldgen/density_function` entry. Vanilla defines `minecraft:zero`, `minecraft:y` (a gradient from
-4064 to 4062), `minecraft:shift_x`, and `minecraft:shift_z`.

| Type | JSON fields | Notes |
|---|---|---|
| `constant` | `value`, or just a number | |
| `blend_alpha`, `blend_offset` | — | Context-bound. They default to 1 and 0, and are only non-trivial when blending old chunks. |
| `beardifier` | — | Context-bound per-chunk structure terrain adaptation (§2.6). Defaults to 0. |
| `noise` | `noise` (id), `xz_scale`, `y_scale`, optional `shift_x`/`shift_y`/`shift_z` (density functions) | Replaces old `shifted_noise`. A domain axis drops out when its scale is 0. |
| `end_outer_islands` | — | Unit codec. |
| `distance_to_point` | `point` `[x,y,z]`, `metric` (`euclidean`, `euclidean_squared`, `manhattan`, `chebyshev`) | |
| `gradient` | `axis` (x/y/z), `tiling` (`clamp_to_edge` default, `repeat`, `mirrored_repeat`), `from_coordinate`, `to_coordinate`, `from_value`, `to_value` | Replaces `y_clamped_gradient`. |
| `shift_a`, `shift_b`, `shift` | `noise` | Coordinate ×0.25, value ×4. |
| `abs`, `square`, `cube`, `sqrt`, `half_negative`, `quarter_negative`, `reciprocal`, `negate`, `squeeze`, `log`, `sign` | `input` | |
| `floor`, `round`, `ceil`, `truncate` | `input`, optional `multiple` (default 1) | |
| `add`, `sub`, `mul`, `div`, `min`, `max` | `left`, `right` | Constant operands get specialised samplers. |
| `pow` | `base`, `exponent` | |
| `spline` | `spline`: `{coordinate: df, points: [{location, value (number or spline), derivative}]}` | |
| `lerp` | `alpha`, `first`, `second` | |
| `clamp` | `input`, `min`, `max` | Bounds lie in [-1e6, 1e6]. |
| `range_choice` | `input`, `min_inclusive`, `max_exclusive`, `when_in_range`, `when_out_of_range` | |
| `interval_select` | `input`, `thresholds` (ascending), `functions` (count = thresholds + 1, at least 2) | Vanilla's replacement for `weird_scaled_sampler` (`overworld/caves/spaghetti_2d.json`). |
| `cache` | `input` | The only cache type left (§2.4). |
| `blend_density` | `input` | |
| `interpolated` | `input`, `cell_size_xz`, `cell_size_y` (positive ints) | |
| `slice` | `axis`, `coordinate`, `input` | Usually inserted automatically by the compiler. |
| `find_top_surface` | `density`, `upper_bound` (df), `lower_bound` (int), `cell_height` | Steps down from `upper_bound` in increments of `cell_height` until density > 0. Used by `overworld/preliminary_surface_level`. |
| `old_blended_noise` | `xz_scale`, `y_scale`, `xz_factor`, `y_factor`, `smear_scale_multiplier` | Vanilla's base 3D terrain noise. |

### 2.4 `interpolated` and `cache`: how they work and what they cost

There is **no** `flat_cache`, `cache_2d`, `cache_once`, or `cache_all_in_cell` any more (grep finds no matches).

- **`interpolated`** (`op/InterpolatedFunction.java:16-228`) samples its input only at cell corners: a strided
  volume with step `(cell_size_xz, cell_size_y, cell_size_xz)` covering the request, plus one extra corner. It then
  fills blocks trilinearly. For a 16×H×16 chunk with cells of 4×8 (the overworld), that is 5×(H/8+1)×5 input
  evaluations: 1,225 for H=384, against 98,304 blocks. If the requested volume already matches the cell grid, the
  input is sampled directly (lines 55-61). `sampleValue` at a non-corner point samples a 2×2×2 corner volume.
  - **Cost:** one cell buffer plus a trilinear fill per block.
  - **Accuracy:** linear between corners.
  - Vanilla also uses `interpolated` with `cell_size_y = 1` to smooth a **2D** function:
    `overworld/chunk_surface_level = interpolated(preliminary_surface_level, 16, 1)`.
- **`cache`** (`op/CacheFunction.java`, `CachingDensitySampler.java`, `SamplerContext.java:58-99`) has one
  `CacheCell` per cache id per `SamplerContext`.
  - `sampleVolume` keeps the last volume and its buffer. A repeated request for the same `DensityVolume` (a record,
    compared with `equals`) is answered by an `arraycopy`.
  - `sampleValue` checks a one-entry block-position cache, then whether the point lies inside the last cached
    volume, before computing.
  - Caches are active only when the context was built with `enableCaches()`. The context is per chunk, so nothing
    is shared across chunks or threads.
  - **Cost:** memory equal to the volume size per cache per context. A 3D chunk volume is
    `16×H×16×4 B` (384 KiB at H=384, 512 KiB at H=512); a 2D slice is 1 KiB. Buffers come from a
    `DensityBufferPool`.
  - Use `cache` when **the same sub-function is needed by more than one consumer in one context**. An example is
    the heightmap feeding both `final_density` and `chunk_surface_level`, which share `noiseChunk.cachingSamplers()`.
- **2D or 1D functions** need no marker. Declare `domainAxes()` correctly and the compiler slices them (§2.2).

### 2.5 Seeds and randomness for custom functions

There are no `NoiseHolder`s or visitor any more. `RandomState`'s constructor builds an anonymous
`DensityFunction.CompileContext` (`common/net/minecraft/world/level/levelgen/RandomState.java:72-99`):

```java
public Noise createNoiseSampler(Holder<NormalNoise> parameters) {
    if (parameters.is(Noises.TEMPERATURE_NETHER)) return parameters.value().createForLegacyNetherBiome(newLegacyInstance(0L));
    else return parameters.is(Noises.VEGETATION_NETHER) ? ...(newLegacyInstance(1L))
         : RandomState.this.getOrCreateNoise(parameters.unwrapKey().orElseThrow());   // registered noises only!
}
public RandomSource createRandom(Identifier seed) {
    return useLegacyRandom && seed.equals(BlendedNoise.NOISE_SEED) ? newLegacyInstance(0L) : RandomState.this.random.fromHashOf(seed);
}
public RandomSource createEndIslandRandom() { return new LegacyRandomSource(seed); }    // deprecated
```

Here `RandomState.random = algorithm.newInstance(worldSeed).forkPositional()` (line 66). Three ways to get
world-seeded data follow:

- **`ctx.createRandom(Identifier.fromNamespaceAndPath("redplanet", "..."))`** is the recommended way. It gives a
  `RandomSource` that is deterministic per world seed and per id, so `nextLong()` yields a per-world sub-seed.
- **`ctx.createNoiseSampler(holder)`** works only for noises registered under `worldgen/noise`. An inline noise
  definition decodes, but then throws on `unwrapKey().orElseThrow()`. Each noise is seeded with
  `random.fromHashOf(noiseId)` (`Noises.instantiate`, `Noises.java:81-84`).
- **`NormalNoise.create(RandomSource)`** is public (`NormalNoise.java:172`), so you can build a noise in code
  (`NormalNoise.builder().setBaseOctave(..).setOctaveCount(..).build().create(ctx.createRandom(id))`) without
  registering JSON.

The raw world seed is **not** exposed. `RandomState.seed()` exists but is deprecated, and the context does not
expose it. Fabric's internal `MultiNoiseSamplerHooks.fabric_getSeed()` on `Climate.Sampler`
(`fabric-api/src/net/fabricmc/fabric/mixin/biome/RandomStateMixin.java`) is an implementation detail, not API.

### 2.6 Vanilla examples of custom compute

**BlendedNoise** is seeded through `createRandom` (`common/net/minecraft/world/level/levelgen/synth/BlendedNoise.java:38, 93-107`):

```java
public static final Identifier NOISE_SEED = Identifier.withDefaultNamespace("terrain");
public DensitySampler compileSampler(DensityFunction.CompileContext context) { return this.compileSampler(context.createRandom(NOISE_SEED)); }
public DensitySampler compileSampler(RandomSource random) {
    BlendedNoise.FbmSet fbms = this.createFbmSet(random);
    ... return new LerpFunction.Sampler(choice, minLimitNoise, maxLimitNoise);   // composes vanilla samplers
}
public @Axes int domainAxes() { return 7; }   public DensityFunction rewriteChildren(DfRewriteRule r) { return this; }
```

**EndIslandFunction** is a 2D function with its own sampler (`densityfunction/generator/EndIslandFunction.java:44-91`):

```java
public DensitySampler compileSampler(CompileContext context) {
    RandomSource islandRandom = context.createEndIslandRandom(); islandRandom.consumeCount(17292);
    return new EndIslandFunction.Sampler(new SimplexNoise(islandRandom, true));
}
public Interval range() { return Interval.of(-0.84375F, 0.5625F); }
public int domainAxes() { return 5; }                       // X|Z
private record Sampler(SimplexNoise islandNoise) implements DensitySampler {
    public void sampleVolume(SamplerContext c, DensityBuffer out, DensityVolume v) {
        for (z..) for (x..) { float value = sampleValue(c, v.blockX(x), 0, v.blockZ(z));
                              out.setRange(v.indexUnchecked(x, 0, z), v.sizeY(), value); } }
    public float sampleValue(SamplerContext c, int x, int y, int z) { return (getHeightValue(islandNoise, x/8, z/8) - 8.0F) / 128.0F; }
}
```

**Beardifier** is per-chunk data delivered through the sampler context:

- `generator/SimpleDensityFunction.java:29` compiles `beardifier` to
  `new ContextBoundSampler(Beardifier.CONTEXT_KEY, new ConstantFunction.Sampler(0.0F))`.
- `NoiseChunk.java:30` puts `Beardifier.forStructuresInChunk(...)` into the context's `ContextMap`.
- `Beardifier` (`common/net/minecraft/world/level/levelgen/Beardifier.java:26-175`) itself implements
  `DensitySampler`, with `sampleVolume` limited to the structure's affected box.
- **Beardifier contributions are about ±0.8 to 1.0**, and vanilla `final_density` is scaled so that this moves the
  surface by many blocks (§10.1).

**NoiseFunction** (`generator/NoiseFunction.java:34-49`) calls `context.createNoiseSampler(this.noise)`, then chooses
a specialised sampler depending on which shifts are zero.

### 2.7 Registering a custom density function type

```java
Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,
                  Identifier.fromNamespaceAndPath("redplanet", "mars_height"), MarsHeightFunction.CODEC);
```

Do this in `ModInitializer.onInitialize`. Then any JSON can use `{"type": "redplanet:mars_height", ...}`. These
requirements come from the code paths above:

1. **`codec()` must return the registered `MapCodec` instance.** Encoding dispatches through
   `DENSITY_FUNCTION_TYPE.byNameCodec()` (`DensityFunctions.java:43-45`).
2. **Use a record**, or implement `equals`/`hashCode`. Samplers are memoised by function equality, and caches are
   deduplicated by input equality.
3. **`range()` must be conservative** (§2.2).
4. **`domainAxes()` must be honest.** Declare 5 only if the value never depends on y, because the sampler will be
   called with y = 0.
5. **`rewriteChildren(rule)`** returns `this` for leaves. For composites it returns
   `new X(rule.rewrite(child), ...)`, or `this` when nothing changed.
6. **Samplers are shared by all worldgen threads.** Keep them immutable, with no mutable fields; per-call scratch
   space comes from `context.acquireBuffer`. Heavy one-time setup such as loading data belongs in `compileSampler`,
   or better in a thread-safe lazy singleton that is preloaded at startup. `compileSampler` runs lazily on a
   worldgen thread under the compiler lock.
7. Implement **both** `sampleValue` and `sampleVolume`. `sampleVolume` is the hot path. `sampleValue` serves
   `find_top_surface`, the per-quart `Climate.Sampler.sample`, `NoiseSpawnFinder`, F3 debug, and some
   material-rule paths.

### 2.8 Can one function compute a 2D height and set `final_density` from it? Yes

Make a 2D function (`domainAxes = X|Z`) that returns the surface **height in blocks**. Then use:

```json
"final_density": { "type": "minecraft:sub", "left": "redplanet:mars/surface_height", "right": "minecraft:y" }
```

The compiler wraps the height in `Slice(Y,0)`, so it costs **one evaluation per column (256 per chunk)**. `y` is a
cheap Y gradient, and `sub` runs in place. Wrap the result in `clamp`/`mul` to tune the scale, and add `beardifier`
if structures should adapt the terrain (§10.1). Interpolation is unnecessary for the exact 2D part. Use
`interpolated` only for optional 3D noise such as overhangs or lava tubes, and add that noise term separately.

---

## 3. `NoiseBasedChunkGenerator` and the chunk pipeline

`common/net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.java` is declared
**`public final class`** (line 60), so it cannot be subclassed. Its codec is
`{biome_source, settings}`, registered as `minecraft:noise` in `chunk/ChunkGenerators.java`. The other generators are
`flat` and `debug`.

### 3.1 Statuses and dependencies (`chunk/status/ChunkStatus.java:21-30`, `ChunkPyramid.java:10-35`)

`empty` → `structure_starts` → `structure_references` (needs starts within 8 chunks) → `biomes` (starts within 8) →
**`terrain`** (starts within 8, biomes within 1, write radius 0) → **`features`** (terrain within 1,
**write radius 1**) → `initialize_light` → `light` → `spawn` → `full`.

### 3.2 The phases

1. **Structure starts** (`ChunkGenerator.createStructures`, about line 483). For each structure set the biome
   source could support, it calls `placement.isStructureChunk(state, chunkX, chunkZ)` and then
   `Structure.generate(...)`.
2. **Biomes** (`ChunkGenerator.doCreateBiomes`, about lines 134-154) calls
   `biomeSource.createResolverForChunk(climateSampler, quartMinX, quartMinY, quartMinZ, 4, height/4, 4)` and then
   `protoChunk.fillBiomesFromNoise(resolver)` (`ChunkAccess.java:433-444`, `LevelChunkSection.java:196-209`). That
   asks for every quart, 4×4×4 per section.
3. **Terrain** (`buildTerrain`, about line 343) runs asynchronously on `Util.backgroundExecutor()` and does three
   things in sequence:
   - **`doFill`** (about line 391) samples `final_density` once over the whole volume, 16 × `noise.height` × 16.
     For each block it calls `aquifer.computeSubstance(x,y,z,density)`. `null` means `default_block`, and AIR is
     skipped. It also updates the `OCEAN_FLOOR_WG` and `WORLD_SURFACE_WG` heightmaps.
   - **`buildSurface`** calls `MaterialSystem.buildSurface` (§5.5).
   - **`generateCarvers`** looks at carvers from source chunks within ±8 chunks, which yields a `CarvingMask`. It
     then calls `applyCarvingMask` with `aquifer.computeSubstance(...,0.0)` (§8).
4. **Features** (`ChunkGenerator.applyBiomeDecoration`, about line 338) runs per decoration step. It places
   structure pieces first (`StructureStart.placeInChunk`), then the placed features of every biome present in the
   3×3 chunk area, deduplicated and ordered by `FeatureSorter`.

### 3.3 Fluids: the lava rule

`createFluidPicker` (lines 78-92) reads:

```java
Aquifer.FluidStatus lavaStatus = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
Aquifer.FluidStatus seaStatus  = new Aquifer.FluidStatus(seaLevel, settings.defaultFluid());
return (x, y, z) -> y < Math.min(-54, seaLevel) ? lavaStatus : seaStatus;
```

`Aquifer.createDisabled` (`Aquifer.java:27-40`) returns `density > 0 ? null : picker.computeFluid(x,y,z).at(y)`,
and `FluidStatus.at(y)` is `y < fluidLevel ? fluid : AIR`. So **without aquifers, every air pocket below
`min(-54, sea_level)` becomes lava**, even when `default_fluid` is air. Carvers use the same picker (§8). Two ways
avoid it: keep the noise `min_y >= -48`, or set `sea_level <= noise.min_y`. Vanilla `floating_islands` does the
latter, with sea_level -64 and min_y 0.

### 3.4 `getBaseHeight`, `getBaseColumn`, structures, and `/locate`

`iterateNoiseColumn` (lines 164-207) builds a `NoiseChunk` over a 1×H×1 volume and samples `final_density` for the
column. Structures call it through `getFirstOccupiedHeight`. With a 2D height function this costs one height
evaluation per call.

### 3.5 `spawn_target` and the initial spawn

- `SpawnTargetPoint` (`SpawnTargetPoint.java:12-27`) maps a density-function holder to a `Climate.Parameter`. The
  fitness is the sum of squared parameter distances, sampled at the **quart-snapped (x, 0, z)**.
- `NoiseSpawnFinder` (`NoiseSpawnFinder.java:9-62`) evaluates (0,0), then spirals out to radius 2048 in steps of
  512, then out to 512 in steps of 32. It minimises `fitness*2048² + distance²`.
- `NoiseBasedChunkGenerator.getOrigin` (lines 111-119) returns that chunk, or `ChunkPos.ZERO` when the list is empty.
- `MinecraftServer.setInitialSpawn` (`common/net/minecraft/server/MinecraftServer.java:481-530`) calls it **only for
  the overworld** (line 440).

For Mars, `getOrigin` matters only as `ChunkGeneratorStructureState.getDimensionOrigin()`, which the
`dimension_origin` placement uses. The landing position has to be computed by our own code, so `"spawn_target": []`
is fine.

### 3.6 Height constraints

These come from `DimensionType.java:46-80` and `NoiseSettings.java:25-33`. `BlockPos.PACKED_Y_LENGTH` is 12, which
gives `Y_SIZE` = 4064, `MAX_Y` = 2031, and `MIN_Y` = -2032.

- Dimension type: `height >= 16`, `min_y + height <= 2032`, `logical_height <= height`, and both `height` and
  `min_y` are multiples of 16.
- Noise settings follow the same rules, and `height` may be smaller than the dimension's.
- `find_top_surface.lower_bound` lies in [-4064, 4062].
- The client darkens the fog within 32 blocks of `min_y`
  (`client/net/minecraft/client/renderer/fog/FogRenderer.java:107-108`). It also draws the "dark disc" sky below a
  hardcoded horizon of y = 63 for non-flat worlds (`client/.../multiplayer/ClientLevel.java:1259-1261`,
  `SkyRenderer.java:324-327`). This is relevant for Hellas-style basins.

### 3.7 Performance, and 512 versus 384 tall

The following is analysis from the code paths, **not measured**.

| Work per chunk | How it scales | 384 → 512 |
|---|---|---|
| `doFill` loop plus `computeSubstance` | 16·H·16 blocks, always | 98,304 → 131,072 (+33%) |
| `final_density` buffers | `float[16·H·16]` per live intermediate (pooled) | 384 KiB → 512 KiB each |
| 2D height function | 256 columns (sliced) | unchanged |
| 3D noise inside `interpolated(4,8)` | (5·5)·(H/8+1) | 1,225 → 1,625 |
| Biome resolver calls | 4·(H/4)·4 quarts (a precomputed per-column resolver keeps this cheap) | 1,536 → 2,048 |
| Material rules | Every **solid** block from `WORLD_SURFACE_WG` down to min Y (`MaterialSystem.java:139-191`) | Proportional to column depth |
| Light, storage, network, client meshes | Proportional to non-empty sections; all-air sections are cheap | Depends on terrain, not on the empty headroom |

In vanilla the time goes mainly to the 3D noise in `final_density` (`old_blended_noise` plus cave networks), then
aquifers, material rules, features (dozens of ore placements), and lighting. A Mars generator built from a 2D
heightmap plus `y` avoids the expensive part.

**Recommendation:** keep the **noise volume tight**, from `min_y` to just above Olympus Mons plus detail. The
**dimension type** can be taller to give building headroom, following the End's precedent.

---

## 4. Biome sources

### 4.1 Registry and built-in types

The registry is `BuiltInRegistries.BIOME_SOURCE`, key `worldgen/biome_source`, holding
`MapCodec<? extends BiomeSource>`. Vanilla registers `fixed`, `multi_noise`, `checkerboard`, and `the_end`
(`biome/BiomeSources.java`).

### 4.2 What a subclass must implement (`common/net/minecraft/world/level/biome/BiomeSource.java:28-178`)

```java
protected abstract MapCodec<? extends BiomeSource> codec();
protected abstract Stream<Holder<Biome>> collectPossibleBiomes();     // memoised into possibleBiomes()
public abstract BiomeResolver createResolver(Climate.Sampler sampler);
// optional overrides:
public BiomeResolver createResolverForChunk(Climate.Sampler sampler, int minQuartX, int minQuartY, int minQuartZ,
                                            int quartSizeX, int quartSizeY, int quartSizeZ) { return createResolver(sampler); }
public void addDebugInfo(List<String> result, BlockPos feetPos, Climate.Sampler sampler) { }   // F3 lines
// non-abstract helpers you inherit: findBiomeHorizontal, findClosestBiome3d, createUncachedResolver, createCachingResolver
```

`BiomeResolver` (`BiomeResolver.java:9-31`) is `@FunctionalInterface Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ)`.
It takes **quart** coordinates, which are blocks >> 2, so use `QuartPos.toBlock(q)`.

`possibleBiomes()` serves three purposes:

- It filters structure sets (`ChunkGeneratorStructureState.hasBiomesForStructureSet`, about lines 66-72).
- It builds the per-step feature lists (`ChunkGenerator` constructor, through `FeatureSorter`).
- It feeds `/locate biome`.

`createResolverForChunk` receives exactly the chunk's quart box during the biome step. Structure code also calls it
with a 1×N×1 column (`Structure.GenerationContext.couldStructureExistInColumn`). Index safely and fall back to
`createResolver` when a quart is out of range.

### 4.3 Getting `Holder<Biome>` into the source

- **Explicit fields** use `Biome.CODEC`, which is `RegistryCodecs.holder(Registries.BIOME, Biome.DIRECT_CODEC)`
  (`Biome.java:57`). That accepts an id string or an inline biome. `FixedBiomeSource` does this:
  `Biome.CODEC.fieldOf("biome")`. `CheckerboardColumnBiomeSource` uses `Biome.LIST_CODEC.fieldOf("biomes")`.
- **Hardcoded keys** use `RegistryOps.retrieveElement(ResourceKey)`, which returns `Holder.Reference<E>` and adds
  no JSON field (`RegistryOps.java:74-86`). `TheEndBiomeSource.java:57-66` groups five of these.
- **A whole lookup** uses `RegistryOps.retrieveGetter(Registries.BIOME)`, which returns `HolderGetter<Biome>`
  (`RegistryOps.java:62-72`). `MultiNoiseBiomeSourceParameterList.java:25` uses it. Resolve keys with
  `getter.getOrThrow(key)` in the constructor. This avoids the 16-field limit of a `RecordCodecBuilder` group.

### 4.4 `MultiNoiseBiomeSource.createResolverForChunk`, the batch pattern (`MultiNoiseBiomeSource.java:65-96`)

It samples the six climate density functions over a strided volume,
`new DensityVolume(qsx, qsy, qsz, toBlock(minQX), toBlock(minQY), toBlock(minQZ), 4, 4, 4)`, into six buffers. It
then returns a lambda that looks up `Climate.target(...)` per quart.

### 4.5 `Climate.Sampler` (`biome/Climate.java:456-477`)

```java
public record Sampler(DensitySampler.Bound temperature, DensitySampler.Bound humidity, DensitySampler.Bound continentalness,
                      DensitySampler.Bound erosion, DensitySampler.Bound depth, DensitySampler.Bound weirdness) {
    public Climate.TargetPoint sample(int quartX, int quartY, int quartZ)   // converts to block coords, quantizes (*10000)
}
```

A custom source may read the router's density functions with
`sampler.temperature().sampleValue(blockX, blockY, blockZ)`, as `TheEndBiomeSource.java:119` does with `erosion()`.
This is how a biome source gets **seeded** data, because the source has no seed access of its own. Put the seeded
function into a router slot and read it here.

### 4.6 The `multi_noise` alternative

`MultiNoiseBiomeSource.CODEC` accepts either `{"preset": "<multi_noise_biome_source_parameter_list id>"}` or an
inline list:

```json
"biome_source": { "type": "minecraft:multi_noise", "biomes": [
  { "biome": "redplanet:tharsis",
    "parameters": { "temperature": [-1.0, 1.0], "humidity": 0.0, "continentalness": [0.5, 2.0],
                    "erosion": [-2.0, 2.0], "depth": 0.0, "weirdness": [-2.0, 2.0], "offset": 0.0 } } ] }
```

Each parameter is a float, `[min, max]`, or `{min, max}`, constrained to [-2, 2] (`Climate.Parameter.CODEC`,
`ExtraCodecs.intervalCodec`). `offset` lies in [0, 1].

This works if the six router slots carry geographic fields from custom density functions, such as latitude,
elevation, slope, and albedo. Its limits are nearest-point selection in a 6D space, the [-2, 2] parameter range,
and quantisation. Named polygonal regions are awkward to express this way, so **a custom BiomeSource is the
better fit**.

### 4.7 Biome JSON (`data/<ns>/worldgen/biome/*.json`)

The codec is `Biome.java:40-49`. These are the fields:

| Field | Required? | Contents |
|---|---|---|
| `has_precipitation`, `temperature`, `downfall` | required | `temperature_modifier` is optional (`none` or `frozen`). |
| `attributes` | optional | Environment attributes, **positional only** (`EnvironmentAttributeMap.CODEC_ONLY_POSITIONAL`). |
| `effects` | required | `{water_color (required), foliage_color?, dry_foliage_color?, grass_color?, grass_color_modifier?}` as `"#rrggbb"` strings (`BiomeSpecialEffects.java`). **Sky and fog colours are no longer here.** |
| `carvers` | required | A single id, a list, or a tag. |
| `features` | required | A list of lists. The index is the `GenerationStep.Decoration` ordinal, and trailing steps may be omitted. |

The decoration steps (`GenerationStep.java`) are `raw_generation`, `lakes`, `local_modifications`,
`underground_structures`, `surface_structures`, `strongholds`, `underground_ores`, `underground_decoration`,
`fluid_springs`, `vegetal_decoration`, and `top_layer_modification`.

Here is the vanilla `desert.json`, verbatim except for the trimmed spawner and feature lists:

```json
{
  "attributes": {
    "minecraft:audio/background_music": { "default": { "max_delay": 24000, "min_delay": 12000, "sound": "minecraft:music.overworld.desert" } },
    "minecraft:gameplay/natural_mob_spawns": {
      "argument": { "spawn_costs": {}, "spawns_by_category": {
          "ambient": [ { "type": "minecraft:bat", "count": 8, "weight": 10 } ],
          "creature": [ { "type": "minecraft:rabbit", "count": { "type": "minecraft:uniform", "max_inclusive": 3, "min_inclusive": 2 }, "weight": 12 },
                        { "type": "minecraft:camel", "count": 1, "weight": 1 } ],
          "monster": [ "... spider, zombie, husk, parched ..." ] } },
      "modifier": "overlay" },
    "minecraft:gameplay/snow_golem_melts": true,
    "minecraft:visual/sky_color": "#6eb1ff"
  },
  "carvers": [ "minecraft:cave", "minecraft:cave_extra_underground", "minecraft:canyon" ],
  "downfall": 0.0,
  "effects": { "water_color": "#3f76e4" },
  "features": [ [], [ "minecraft:lake_lava_underground", "minecraft:lake_lava_surface" ], [ "minecraft:amethyst_geode" ],
                [ "minecraft:fossil_upper", "..." ], [ "minecraft:desert_well" ], [], [ "minecraft:ore_dirt", "..." ], [],
                [ "minecraft:spring_water", "minecraft:spring_lava" ], [ "minecraft:glow_lichen", "..." ], [ "minecraft:freeze_top_layer" ] ],
  "has_precipitation": false,
  "temperature": 2.0
}
```

Here is the nether `basalt_deltas.json`, trimmed:

```json
{ "attributes": {
    "minecraft:audio/ambient_sounds": { "additions": { "sound": "minecraft:ambient.basalt_deltas.additions", "tick_chance": 0.0111 },
                                        "loop": "minecraft:ambient.basalt_deltas.loop",
                                        "mood": { "block_search_extent": 8, "offset": 2.0, "sound": "minecraft:ambient.basalt_deltas.mood", "tick_delay": 6000 } },
    "minecraft:gameplay/natural_mob_spawns": { "argument": { "spawn_costs": {}, "spawns_by_category": { "monster": [ "..." ] } }, "modifier": "overlay" },
    "minecraft:visual/ambient_particles": { "argument": [ { "particle": { "type": "minecraft:white_ash" }, "probability": 0.118093334 } ], "modifier": "append" },
    "minecraft:visual/fog_color": "#685f70" },
  "carvers": "minecraft:nether_cave", "downfall": 0.0, "effects": { "water_color": "#3f76e4" },
  "features": [ [], [], [], [], [ "minecraft:delta", "minecraft:small_basalt_columns", "minecraft:large_basalt_columns" ], [], [],
                [ "minecraft:basalt_blobs", "..." ] ],
  "has_precipitation": false, "temperature": 2.0 }
```

**Mob spawns** are the attribute `gameplay/natural_mob_spawns`, a `MobSpawnSettings` with the `overlay` modifier.
Spawner `count` is an int or an IntProvider. Omit the attribute for none, since it defaults to
`MobSpawnSettings.EMPTY`. An attribute entry is either a bare value (override) or
`{"modifier": "<name>", "argument": ...}`. The modifier names are `override`, `alpha_blend`, `add`, `subtract`,
`multiply`, `blend_to_gray`, `minimum`, `maximum`, `and`, `nand`, `or`, `nor`, `xor`, `xnor`, `append`, and
`overlay` (`world/attribute/modifier/AttributeModifier.java:97-112`).

These attribute ids come from `world/attribute/EnvironmentAttributes.java`. Items marked **NP** are not positional
and so are allowed only on the dimension type.

- `visual/`: `fog_color`, `fog_start_distance`, `fog_end_distance`, `sky_fog_end_distance`,
  `cloud_fog_end_distance`, `water_fog_color`, `water_fog_start_distance`, `water_fog_end_distance`, `sky_color`,
  `sunrise_sunset_color`, `cloud_color`, `cloud_height`, `sun_angle`, `moon_angle`, `star_angle`, `moon_phase`,
  `star_brightness`, `block_light_tint`, `sky_light_color`, `sky_light_factor`, `night_vision_color`,
  `ambient_light_color`, `default_dripstone_particle`, `ambient_particles`.
- `audio/`: `background_music`, `music_volume`, `ambient_sounds`, `firefly_bush_sounds`.
- `gameplay/`: `sky_light_level` (NP), `can_start_raid`, `water_evaporates`, `bed_rule`, `straw_bed_rule`,
  `respawn_anchor_works`, `nether_portal_spawns_piglin`, `fast_lava` (NP), `increased_fire_burnout`,
  `eyeblossom_open`, `turtle_egg_hatch_chance`, `piglins_zombify`, `snow_golem_melts`, `creaking_active`,
  `surface_slime_spawn_chance`, `cat_waking_up_gift_chance`, `bees_stay_in_hive`, `monsters_burn`,
  `can_pillager_patrol_spawn`, `natural_mob_spawns`, `creature_world_gen_spawn_probability`, `villager_activity`,
  `baby_villager_activity`.

Temperature depends on `sea_level` (`Biome.java:112-121`). Above `seaLevel + 17`, temperature drops by
`(noise + y - snowLevel) * 0.05/40`. It feeds `coldEnoughToSnow`, `shouldFreeze` (water turning to ice in
`ServerLevel.tickPrecipitation`), the material condition `temperature`, and the feature `freeze_top_layer`.

---

## 5. Material rules (formerly surface rules)

### 5.1 Interfaces (`levelgen/material/rule/MaterialRule.java`, `.../condition/MaterialCondition.java`)

```java
public interface MaterialRule {
    Codec<MaterialRule> DIRECT_CODEC = BuiltInRegistries.MATERIAL_RULE_TYPE.byNameCodec().dispatch(MaterialRule::codec, identity());
    Codec<Holder<MaterialRule>> HOLDER_CODEC = RegistryCodecs.holder(Registries.MATERIAL_RULE, DIRECT_CODEC);
    Codec<MaterialRule> CODEC = /* holder -> HolderHolder or direct */;
    RuleEvaluator compile(MaterialRuleContext context);         // RuleEvaluator: @Nullable BlockState tryApply(int x, int y, int z)
    MapCodec<? extends MaterialRule> codec();
}
public interface MaterialCondition {
    ConditionEvaluator compile(MaterialRuleContext context);    // ConditionEvaluator: boolean test()
    MapCodec<? extends MaterialCondition> codec();
}
```

Rules are compiled **per chunk** (`MaterialSystem.buildSurface`, line 136), so keep `compile` cheap.

### 5.2 Rule types (`MaterialRules.bootstrapRules`, `MaterialRules.java:147-154`)

| Type | Fields |
|---|---|
| `block` | `result_state` |
| `sequence` | `sequence: [rule...]`, where the first non-null result wins |
| `condition` | `if_true` (condition), `then_run` (rule) |
| `bandlands` | — (clay-band terracotta) |
| `ore_vein` | `ore_block`, `raw_ore_block`, `filler_block`, `raw_ore_chance` in [0, 1], `density`, `richness`, `filler_gap` (density functions). This **replaces `ore_veins_enabled`** (`rule/OreVeinRule.java`). |

### 5.3 Condition types (`MaterialRules.bootstrapConditions`, lines 156-168)

| Type | Fields | Semantics (from the source) |
|---|---|---|
| `biome` | `biome_is` (holder set) | Constant-folded when `possibleBiomes` rules it out or guarantees it. |
| `noise_threshold` | `noise` (key), `min_threshold`, `max_threshold`, `is_3d` (default false) | 2D samples at (x, 0, z). |
| `vertical_gradient` | `random_name` (id), `true_at_and_below`, `false_at_and_above` (anchors) | Random dither between the two anchors. |
| `y_above` | `anchor`, `surface_depth_multiplier` in [-20, 20], `add_stone_depth` | `y (+stoneDepthAbove) >= anchorY + surfaceDepth*mult` |
| `water` | `offset`, `surface_depth_multiplier`, `add_stone_depth` | True if there is no water above, or the same comparison holds against the water height. |
| `temperature` | — | `biome.coldEnoughToSnow(pos, seaLevel)` |
| `steep` | — | `surfaceGradientX <= -4 OR surfaceGradientZ >= 4`. This uses in-chunk heightmap gradients and is **asymmetric**, catching only two slope directions. |
| `not` | `invert` | |
| `hole` | — | `surfaceDepth <= 0` |
| `above_preliminary_surface` | — | `y >= floor(chunk_surface_level) + surfaceDepth - 8` |
| `stone_depth` | `offset`, `add_surface_depth`, `secondary_depth_range`, `surface_type` (`floor` or `ceiling`) | `stoneDepth <= 1 + offset (+surfaceDepth) (+secondary)` |

Vertical anchors are `{"absolute": n}`, `{"above_bottom": n}`, `{"below_top": n}`, or
`{"relative_to_sea_level": n}` (`VerticalAnchor.java:69-120`).

Here are some vanilla registered entries, verbatim:

```json
// worldgen/material_rule/bedrock_floor.json
{ "type": "minecraft:condition",
  "if_true": { "type": "minecraft:vertical_gradient", "false_at_and_above": { "above_bottom": 5 },
               "random_name": "minecraft:bedrock_floor", "true_at_and_below": { "above_bottom": 0 } },
  "then_run": { "type": "minecraft:block", "result_state": "minecraft:bedrock" } }
// worldgen/material_condition/on_floor.json
{ "type": "minecraft:stone_depth", "add_surface_depth": false, "offset": 0, "secondary_depth_range": 0, "surface_type": "floor" }
// worldgen/material_condition/under_floor.json  (add_surface_depth: true)
// worldgen/material_rule/overworld.json
{ "type": "minecraft:sequence", "sequence": [
    "minecraft:bedrock_floor", "minecraft:overworld/copper_ore_vein", "minecraft:overworld/iron_ore_vein",
    { "type": "minecraft:condition", "if_true": { "type": "minecraft:above_preliminary_surface" }, "then_run": "minecraft:overworld/surface" },
    "minecraft:overworld/underground" ] }
// worldgen/material_rule/end.json
{ "type": "minecraft:block", "result_state": "minecraft:end_stone" }
```

The registered conditions are `deep_under_floor`, `not_under_deep_water`, `not_underwater`, `on_ceiling`,
`on_floor`, `under_ceiling`, `under_floor`, and `very_deep_under_floor`.

### 5.4 How the material system applies rules (`levelgen/material/MaterialSystem.java:85-198`)

1. The system builds one `MaterialRuleContext` per chunk, using `noiseChunk.cachingSamplers()`.
2. For each column, it starts at `WORLD_SURFACE_WG + 1` and walks down to `minY`.
3. Air resets `stoneDepthAbove`. Fluid records `waterHeight`.
4. For **each solid block** it calls `context.updateY(...)`, then `rule.tryApply(x,y,z)`, and writes a non-null
   result.

`surfaceDepth` is `surface noise*2.75 + 3 + rand*0.25`, using the vanilla `minecraft:surface` noise. The system
also has hardcoded extensions for `eroded_badlands` and the frozen oceans.

### 5.5 Context API for custom types (`levelgen/material/MaterialRuleContext.java`)

These getters are public: `blockX()`, `blockY()`, `blockZ()`, `blockPos()`, `getBiome()`, `getSeaLevel()`,
`getMinSurfaceLevel()`, `surfaceDepth()`, `stoneDepthAbove()`, `stoneDepthBelow()`, `waterHeight()`,
`surfaceGradientX()`, `surfaceGradientZ()`, `getSurfaceSecondary()`, `resolveAnchorY(anchor)`,
`getNoiseSampler(noiseKey, is3d)`, `getOrCreateRandomFactory(id)`, `possibleBiomes()`, `getBand(..)`, and
**`getDensitiesInChunk(DensityFunction, boolean prefill)`**. The last one returns a `MaterialRules.DensityGetter`
that evaluates any density function at the current block, and `OreVeinRule` uses it.

There are also public abstract helpers, **`MaterialRuleContext.LazyXZCondition`** and **`LazyYCondition`**. Each
has a protected `(MaterialRuleContext)` constructor, a `protected final MaterialRuleContext context` field, and
`protected abstract boolean compute()`. The XZ variant caches its result per column; the Y variant caches per block.

### 5.6 Custom conditions and rules

These are needed for a latitude condition. Register them like this:

```java
Registry.register(BuiltInRegistries.MATERIAL_CONDITION_TYPE, Identifier.fromNamespaceAndPath("redplanet", "latitude"), LatitudeCondition.CODEC);
Registry.register(BuiltInRegistries.MATERIAL_RULE_TYPE, Identifier.fromNamespaceAndPath("redplanet", "layered_sediment"), LayeredSedimentRule.CODEC);
```

```java
public record LatitudeCondition(float minLatitude, float maxLatitude) implements MaterialCondition {
    public static final MapCodec<LatitudeCondition> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.floatRange(-90F, 90F).fieldOf("min_latitude").forGetter(LatitudeCondition::minLatitude),
            Codec.floatRange(-90F, 90F).fieldOf("max_latitude").forGetter(LatitudeCondition::maxLatitude)
    ).apply(i, LatitudeCondition::new));
    @Override public MapCodec<LatitudeCondition> codec() { return CODEC; }
    @Override public ConditionEvaluator compile(MaterialRuleContext ctx) {
        return new MaterialRuleContext.LazyXZCondition(ctx) {          // evaluated once per column
            @Override protected boolean compute() {
                double lat = MarsProjection.latitudeDeg(this.context.blockZ());
                return lat >= minLatitude && lat <= maxLatitude;
            }
        };
    }
}
```

A more general alternative is `redplanet:density_range`, with fields `function`, `min`, `max`, and `prefill`. In
`compile`, call `ctx.getDensitiesInChunk(function, prefill)` and test the range. A single condition type then covers
latitude, slope, albedo, and elevation density functions. Use `prefill = false` for 2D functions, because
`prefill = true` samples the whole 3D chunk volume.

---

## 6. Features

### 6.1 The new model (`levelgen/feature/Feature.java:20-55`, `placement/PlacedFeature.java`)

```java
public interface Feature {                                    // a configured feature IS a Feature record
    Codec<Feature> DIRECT_CODEC = BuiltInRegistries.FEATURE_TYPE.byNameCodec().dispatch(Feature::codec, t -> t);
    Codec<Holder<Feature>> CODEC = RegistryCodecs.holder(Registries.FEATURE, DIRECT_CODEC);   // "worldgen/feature"
    MapCodec<? extends Feature> codec();
    boolean place(WorldGenLevel level, ChunkGenerator chunkGenerator, RandomSource random, BlockPos origin);
    default Stream<Holder<Feature>> getSubFeatures();  default void setBlock(..); default void safeSetBlock(..); ...
}
public record PlacedFeature(Holder<Feature> feature, List<PlacementModifier> placement)   // {"feature": id|inline, "placement": [...]}
```

The registries are as follows:

- Type registry: `BuiltInRegistries.FEATURE_TYPE`, key `worldgen/feature_type`.
- Configured features: `Registries.FEATURE`, folder `data/<ns>/worldgen/feature/`.
- Placed features: `Registries.PLACED_FEATURE`, folder `worldgen/placed_feature/`.

These feature types are registered in `FeatureTypes.java:8-65`: `bamboo`, `block_blob`, `block_column`,
`block_pile`, `blue_ice`, `bonus_chest`, `chorus_plant`, `coral_claw`, `coral_tree`, `delta_feature`, `disk`,
`end_gateway`, `end_island`, `end_platform`, `end_podium`, `end_spike`, `fallen_tree`, `fill_layer`, `fossil`,
`freeze_top_layer`, `geode`, `huge_brown_mushroom`, `huge_fungus`, `huge_red_mushroom`, `iceberg`, `lake`,
`large_dripstone`, `monster_room`, `multiface_growth`, `netherrack_replace_blobs`, `no_op`, `ore`, `overlay`,
`projected_random_patchy_square`, `random_boolean_selector`, `random_neighbor_spread`, `random_selector`,
`replace_single_block`, `root_system`, `scattered_ore`, `sculk_patch`, `sequence`, `simple_block`,
`simple_random_selector`, `single_block_pillar`, `speleothem`, `speleothem_cluster`, `spike`, `spring_feature`,
`stepped_column_cluster`, **`template`**, `tree`, `underwater_magma`, `vegetation_patch`, `vines`,
`void_start_platform`, `waterlogged_vegetation_patch`, and `weighted_random_selector`.

### 6.2 Placement modifiers (`placement/PlacementModifierTypes.java:8-26`)

The interface is `void modify(PlacementContext ctx, RandomSource random, BlockPos origin, Consumer<BlockPos> output)`
plus `codec()`. `PlacementFilter` adds `shouldPlace`.

| Type | Fields |
|---|---|
| `count`, `count_on_every_layer` | `count` (IntProvider) |
| `in_square`, `biome` | — |
| `rarity_filter` | `chance` (positive int, as 1/chance) |
| `random_chance` | `chance` in [0, 1] |
| `heightmap` | `heightmap` (`WORLD_SURFACE_WG`, `MOTION_BLOCKING`, ...). It replaces Y with the heightmap value. |
| `height_range` | `height` (HeightProvider: `constant`, `uniform`, `biased_to_bottom`, `very_biased_to_bottom`, `trapezoid`, `weighted_list`) |
| `surface_relative_threshold_filter` | `heightmap`, `min_inclusive`, `max_inclusive` |
| `surface_water_depth_filter` | `max_water_depth` |
| `noise_based_count` | `noise_to_count_ratio`, `noise_factor`, `noise_offset` |
| `noise_threshold_count` | `noise_level`, `below_noise`, `above_noise` |
| `environment_scan` | `direction_of_search`, `target_condition`, `allowed_search_condition`, `max_steps` in [1, 32] |
| `block_predicate_filter` | `predicate` |
| `offset` | `x`, `y`, `z` (IntProviders) |
| `randomly_selected` | `placements` (weighted list) |
| `cuboid` | `xz_size`, `y_size` (IntProviders in [1, 16]), `include_edges`, `include_interior` |
| **`fixed_placement`** | `positions: [[x,y,z], ...]`. It emits the positions that fall in the current chunk (`placement/FixedPlacement.java:11-40`). |

`biome` (`BiomeFilter.java`) only works for features that are registered **and** listed in the biome at the
position, because it calls `getBiomeGenerationSettings(biome).hasFeature(feature)`.

Vanilla uses fixed coordinates for the End's obsidian platform (`placed_feature/end_platform.json`, verbatim):

```json
{ "feature": "minecraft:end_platform",
  "placement": [ { "type": "minecraft:fixed_placement", "positions": [ [ 100, 49, 0 ] ] }, { "type": "minecraft:biome" } ] }
```

### 6.3 Ore features (there is no `OreConfiguration` class)

The fields sit on the record (`feature/AbstractOreFeature.java`): `targets` (a list of `{target: RuleTest, state}`),
`size` in [0, 64], and `discard_chance_on_air_exposure` in [0, 1]. The types are `ore` and `scattered_ore`. The
RuleTest `predicate_type` values are `always_true`, `block_match`, `blockstate_match`, `tag_match`,
`random_block_match`, `random_blockstate_match`, `not`, `all_of`, `any_of`, and `height_match`.

Here is the vanilla `ore_iron` (trimmed) with its placement:

```json
// worldgen/feature/ore_iron.json
{ "type": "minecraft:ore", "discard_chance_on_air_exposure": 0.0, "size": 9,
  "targets": [ { "state": "minecraft:iron_ore", "target": { "predicate_type": "minecraft:any_of", "rules": [
      { "predicate_type": "minecraft:all_of", "rules": [ { "predicate_type": "minecraft:tag_match", "tag": "minecraft:height_specific_ore_replaceables" },
                                                         { "max_inclusive": 2031, "min_inclusive": 0, "predicate_type": "minecraft:height_match" } ] },
      { "...": "stone_ore_replaceables branch" } ] } },
    { "state": "minecraft:deepslate_iron_ore", "target": { "...": "height_match -2032..8 / deepslate_ore_replaceables" } } ] }
// worldgen/placed_feature/ore_iron_upper.json
{ "feature": "minecraft:ore_iron", "placement": [ { "type": "minecraft:count", "count": 90 }, { "type": "minecraft:in_square" },
    { "type": "minecraft:height_range", "height": { "type": "minecraft:trapezoid", "max_inclusive": { "absolute": 384 }, "min_inclusive": { "absolute": 80 } } },
    { "type": "minecraft:biome" } ] }
```

### 6.4 A custom feature type

```java
public record CraterEjectaFeature(Holder<BlockStateProvider> block, IntProvider count) implements Feature {
    public static final MapCodec<CraterEjectaFeature> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BlockStateProvider.CODEC.fieldOf("block").forGetter(CraterEjectaFeature::block),
            IntProviders.CODEC.fieldOf("count").forGetter(CraterEjectaFeature::count)
    ).apply(i, CraterEjectaFeature::new));
    @Override public MapCodec<CraterEjectaFeature> codec() { return CODEC; }
    @Override public boolean place(WorldGenLevel level, ChunkGenerator gen, RandomSource random, BlockPos origin) {
        BlockState s = block.value().getState(level, random, origin); /* ... */ return true;
    }
}
Registry.register(BuiltInRegistries.FEATURE_TYPE, Identifier.fromNamespaceAndPath("redplanet", "crater_ejecta"), CraterEjectaFeature.CODEC);
// data/redplanet/worldgen/feature/ejecta.json        : {"type": "redplanet:crater_ejecta", "block": "minecraft:basalt", "count": 4}
// data/redplanet/worldgen/placed_feature/ejecta.json : {"feature": "redplanet:ejecta", "placement": [...]}
```

### 6.5 Adding features to biomes

- **Our own biomes:** put the placed-feature ids in the biome JSON `features[stepOrdinal]`.
- **Cross-mod additions:** use Fabric `BiomeModifications.addFeature(selector, step, key)` (§9).
- `FeatureSorter.buildFeaturesPerStep` throws **"Feature order cycle found"** if two biomes list shared features in
  conflicting relative orders (`biome/FeatureSorter.java:72, 96`). Keep one canonical order across all Mars biomes.
- A feature placed at origin O may write only within the **3×3 chunks around the decorating chunk**
  (`blockStateWriteRadius(1)`). Writes outside that log "Detected setBlock in a far chunk" and are dropped
  (`server/level/WorldGenRegion.java:297-319`).

### 6.6 The `template` feature (`feature/TemplateFeature.java:26-88`)

```json
{ "type": "minecraft:template",
  "templates": [ { "data": { "id": "redplanet:landers/viking1", "rotations": [ "none" ] }, "weight": 1 } ],
  "processors": "<optional processor list holder>" }
```

The template is **centred** on the origin: it is shifted by half its size in X and Z, after rotation. `rotations`
defaults to all four; the names are `none`, `clockwise_90`, `180`, and `counterclockwise_90`. The template's air
blocks are placed unless a processor removes them. Vanilla `feature/desert_well.json` is an `overlay` of
`template` features.

---

## 7. Structures

### 7.1 Structure JSON (`levelgen/structure/Structure.java`)

`StructureSettings` has these fields: `biomes` (holder set, required), `spawn_overrides` (map, required),
`step` (decoration step), and `terrain_adaptation` (`none` default, `bury`, `beard_thin`, `beard_box`,
`encapsulate`).

The `type` comes from `BuiltInRegistries.STRUCTURE_TYPE`. The types are `buried_treasure`, `desert_pyramid`,
`end_city`, `fortress`, `igloo`, `jigsaw`, `jungle_temple`, `mineshaft`, `nether_fossil`, `ocean_monument`,
`ocean_ruin`, `ruined_portal`, `shipwreck`, `stronghold`, `swamp_hut`, and `woodland_mansion` (`StructureType.java`).

```json
// worldgen/structure/igloo.json
{ "type": "minecraft:igloo", "biomes": "#minecraft:has_structure/igloo", "spawn_overrides": {}, "step": "surface_structures" }
// worldgen/structure/abandoned_camp_savanna.json (jigsaw)
{ "type": "minecraft:jigsaw", "biomes": "#minecraft:has_structure/abandoned_camp_savanna", "max_distance_from_center": 80,
  "project_start_to_heightmap": "WORLD_SURFACE_WG", "size": 2, "spawn_overrides": {}, "start_height": { "absolute": 0 },
  "start_pool": "minecraft:abandoned_camp/tent/savanna", "step": "surface_structures", "terrain_adaptation": "beard_thin",
  "use_expansion_hack": true }
```

Jigsaw fields (`structures/JigsawStructure.java`) are `start_pool`, `start_jigsaw_name?`, `size` in [0, 20],
`start_height`, `use_expansion_hack`, `project_start_to_heightmap?`, `max_distance_from_center` (an int in [1, 128], or `{horizontal: 1..128, vertical?}`; `horizontal` plus 12 must stay within 128 when terrain adaptation is on),
`pool_aliases?`, `dimension_padding?`, and `liquid_settings?`.

A template pool element looks like this:

```json
{ "element_type": "minecraft:single_pool_element" | "minecraft:legacy_single_pool_element",
  "location": "ns:path", "processors": {...}, "projection": "rigid" }
```

The other element types are `list_pool_element`, `feature_pool_element`, and `empty_pool_element`.

### 7.2 Structure sets and placements

```json
// worldgen/structure_set/igloos.json
{ "placement": { "type": "minecraft:random_spread", "salt": 14357618, "separation": 8, "spacing": 32 },
  "structures": [ { "structure": "minecraft:igloo", "weight": 1 } ] }
// worldgen/structure_set/strongholds.json
{ "placement": { "type": "minecraft:concentric_rings", "count": 128, "distance": 32, "preferred_biomes": "#minecraft:stronghold_biased_to",
                 "salt": 0, "spread": 3 }, "structures": [ { "structure": "minecraft:stronghold", "weight": 1 } ] }
```

The placement registry is `BuiltInRegistries.STRUCTURE_PLACEMENT`, key `worldgen/structure_placement`
(`StructurePlacements.java`). It holds `concentric_rings`, **`dimension_origin`** (unit codec; matches only
`state.getDimensionOrigin()`; unused by vanilla), and `random_spread`.

`random_spread` and `concentric_rings` share these fields from `AbstractSpreadingStructurePlacement`: `salt`,
`locate_offset?`, `frequency_reduction_method?` (`default`, `legacy_type_1` through `legacy_type_3`), `frequency?`,
and `exclusion_zone?`. `random_spread` adds `spacing` and `separation` (each in [0, 4096] chunks, spacing >
separation) and `spread_type` (`linear` or `triangular`). `concentric_rings` adds `distance`, `spread`, `count`, and
`preferred_biomes`.

The interface (`placement/StructurePlacement.java`):

```java
public interface StructurePlacement {
    boolean isStructureChunk(ChunkGeneratorStructureState state, int sourceX, int sourceZ);   // chunk coords
    default boolean applyAdditionalChunkRestrictions(int sourceX, int sourceZ, long levelSeed) { return true; }
    default BlockPos getLocatePos(ChunkPos chunkPos);   default Vec3i locateOffset();
    MapCodec<? extends StructurePlacement> codec();
}
```

Two constraints apply:

- **Biome filtering.** A structure set is considered in a dimension only if some structure's `biomes` intersect
  `biomeSource.possibleBiomes()` (`ChunkGeneratorStructureState.java:57-72`). The start is also rejected if the
  biome at its generation point is not in `biomes` (`Structure.findValidGenerationPoint`).
- **`/locate` limits.** `ChunkGenerator.findNearestMapStructure` (lines 160-232) handles only
  `ConcentricRingsStructurePlacement` and `RandomSpreadStructurePlacement` (lines 191-203). **Custom placement
  types, and `dimension_origin`, cannot be located with `/locate structure`.**
- Structure pieces may extend up to **8 chunks** from the start chunk (`createReferences`, lines 616-646).

### 7.3 Registering a custom structure type, piece, and placement

```java
StructureType<LanderSiteStructure> LANDER_SITE = Registry.register(BuiltInRegistries.STRUCTURE_TYPE,
        Identifier.fromNamespaceAndPath("redplanet", "lander_site"), () -> LanderSiteStructure.CODEC);
StructurePieceType LANDER_SITE_PIECE = Registry.register(BuiltInRegistries.STRUCTURE_PIECE,
        Identifier.fromNamespaceAndPath("redplanet", "lander_site"), (StructurePieceType.StructureTemplateType) LanderSitePiece::new);
Registry.register(BuiltInRegistries.STRUCTURE_PLACEMENT, Identifier.fromNamespaceAndPath("redplanet", "geo_fixed"), GeoFixedPlacement.CODEC);
```

`StructurePieceType.StructureTemplateType` is a nested interface, so it is implicitly public. Its method is
`load(StructureTemplateManager, CompoundTag)` (`pieces/StructurePieceType.java`). A single-template piece model is
`structures/NetherFossilPieces.NetherFossilPiece`, which extends `TemplateStructurePiece` with two constructors
`(manager, Identifier, BlockPos, Rotation)` and `(manager, CompoundTag)`. It overrides `addAdditionalSaveData`
(storing `"Rot"` with `Rotation.LEGACY_CODEC`) and `handleDataMarker`. Full skeletons are in §10.4.

### 7.4 Structure template NBT format

This is decoded from vanilla files with the parser in the appendix. Templates are loaded from
`data/<ns>/structure/<path>.nbt` as **gzip-compressed NBT** (`templatesystem/loader/TemplateSource.java:60-75`,
`NbtIo.readCompressed`). `.snbt` files are read only from test directories.

If `DataVersion` is missing it is treated as **500**, and the template is run through the datafixer with
`DataFixTypes.STRUCTURE.updateToCurrentVersion` (line 73). **Always write `DataVersion` 5023.**

The palette block states are read by `NbtUtils.readBlockState` (`nbt/NbtUtils.java:124-145`). It reads **`"id"`
plus an optional `"properties"`** (string values). A missing or unknown `id` becomes **AIR silently**, so the old
`Name`/`Properties` keys with DataVersion 5023 produce an empty template.

These are the root keys (`templatesystem/StructureTemplate.java:69-79, 609-735`):

| Key | Type | Notes |
|---|---|---|
| `size` | List<Int>[3] | |
| `palette` | List<Compound> | Each entry is `{id, properties?}`. An alternative is `palettes` (a list of palettes, chosen at random). |
| `blocks` | List<Compound> | Each entry is `{pos: List<Int>[3], state: Int (palette index), nbt?: Compound}`, where `nbt` is block-entity data that includes `"id"`. |
| `entities` | List<Compound> | Each entry is `{pos: List<Double>[3], blockPos: List<Int>[3], nbt: Compound}`. |
| `DataVersion` | Int | 5023 |

Here is `structure/village/desert/camel_spawn.nbt`, decoded:

```
size: [1, 4, 2]
entities: [ { nbt: {id: "minecraft:camel"}, blockPos: [0,0,1], pos: [0.0,0.0,0.0] } ]
blocks: [ {pos:[0,0,1], state:0}, {pos:[0,1,0], state:0}, ... ,
          {pos:[0,0,0], state:1, nbt:{joint:"aligned", final_state:"minecraft:air", name:"minecraft:empty",
                                      pool:"minecraft:empty", id:"minecraft:jigsaw", target:"minecraft:empty"}} ]
palette: [ {id:"minecraft:air"}, {id:"minecraft:jigsaw", properties:{orientation:"down_north"}} ]
DataVersion: 5023
```

Here are block-entity examples from `abandoned_camp/camp/default/campsite_default_chest_1.nbt`:

```
palette entry: {"id":"minecraft:chest","properties":{"waterlogged":"false","facing":"east","type":"single"}}
block: {"nbt":{"LootTable":"minecraft:chests/abandoned_camp_common_chest","components":{},"id":"minecraft:chest"},"pos":[6,1,7],"state":3}
```

Air blocks are listed explicitly in vanilla templates. A position missing from `blocks` is "void" and leaves the
world untouched.

---

## 8. Carvers and fluids

### 8.1 Carver JSON

The registries are `Registries.CARVER` (`worldgen/carver`) and the type registry `BuiltInRegistries.CARVER_TYPE`.
The only types are `cave` and `canyon` (`carver/WorldCarverTypes.java`).

```json
// worldgen/carver/cave.json (verbatim)
{ "type": "minecraft:cave",
  "count": { "type": "minecraft:very_biased_to_bottom", "max_inclusive": 14, "min_inclusive": 0 },
  "floor_level": { "type": "minecraft:uniform", "max_exclusive": -0.4, "min_inclusive": -1.0 },
  "horizontal_radius_multiplier": { "type": "minecraft:uniform", "max_exclusive": 1.4, "min_inclusive": 0.7 },
  "probability": 0.15,
  "room_vertical_radius_multiplier": { "type": "minecraft:uniform", "max_exclusive": 0.9, "min_inclusive": 0.1 },
  "thickness": { "type": "minecraft:trapezoid", "max": 3.0, "min": 0.0, "plateau": 1.0 },
  "vertical_radius_multiplier": { "type": "minecraft:uniform", "max_exclusive": 1.3, "min_inclusive": 0.8 },
  "weird_thickness_bias": true,
  "y": { "type": "minecraft:uniform", "max_inclusive": { "absolute": 180 }, "min_inclusive": { "above_bottom": 8 } } }
// worldgen/carver/canyon.json (verbatim)
{ "type": "minecraft:canyon", "probability": 0.01,
  "shape": { "distance_factor": { "type": "minecraft:uniform", "max_exclusive": 1.0, "min_inclusive": 0.75 },
             "horizontal_radius_factor": { "type": "minecraft:uniform", "max_exclusive": 1.0, "min_inclusive": 0.75 },
             "thickness": { "type": "minecraft:trapezoid", "max": 6.0, "min": 0.0, "plateau": 2.0 },
             "vertical_radius_center_factor": 0.0, "vertical_radius_default_factor": 1.0, "width_smoothness": 3, "y_scale": 3.0 },
  "vertical_rotation": { "type": "minecraft:uniform", "max_exclusive": 0.125, "min_inclusive": -0.125 },
  "y": { "type": "minecraft:uniform", "max_inclusive": { "absolute": 67 }, "min_inclusive": { "absolute": 10 } } }
```

The cave codec (`CaveWorldCarver.MAP_CODEC`) has `probability`, `y`, `count`, `thickness`,
`weird_thickness_bias?`, `room_vertical_radius_multiplier`, `horizontal_radius_multiplier`,
`vertical_radius_multiplier`, `start_vertical_radius_multiplier?` (default 1), and `floor_level`. There is **no**
`lava_level`, `replaceable`, `debug_settings`, or `config` wrapper. Carvers carve any block not in
`#minecraft:uncarvable`, which vanilla defines as `["minecraft:bedrock"]`.

### 8.2 How carving fills

`NoiseBasedChunkGenerator.applyCarvingMask` sets each carved block to `aquifer.computeSubstance(x,y,z,0.0)`. That
means water below sea level or air with aquifers, and with aquifers disabled it means the global picker, including
**lava below `min(-54, sea_level)`**.

Carving stops 7 blocks below the top of the generation depth (`protectedBlocksOnTop`, line 240). Carvers come from
the biome of each source chunk within ±8 chunks.

### 8.3 Recipe for no water and no lava anywhere

1. Set `"default_fluid": "minecraft:air"` so the sea fill is air.
2. Omit `"aquifers"` to get `Aquifer.createDisabled`.
3. Set `"sea_level"` to at most the noise `min_y` (the floating_islands precedent), or keep the noise `min_y`
   at -48 or higher. This disables the hardcoded lava band.
4. Leave `lake`, `spring_feature`, `underwater_magma`, and `freeze_top_layer` out of every Mars biome's
   `features`. Watch for `monster_room`, which contains no fluid but is unwanted.
5. Optionally set the dimension or biome attribute `minecraft:gameplay/water_evaporates: true`, which makes
   placed water evaporate as in the Nether. It is a positional attribute, so biomes may use it too.

These are the side effects of a very low `sea_level`:

- Biome temperature falls with height above `sea_level + 17`, which affects freezing (`shouldFreeze`), snow
  (only with `has_precipitation`), and the `temperature` condition.
- The `relative_to_sea_level` anchors shift.
- Phantoms need `y >= sea_level` (`PhantomSpawner.java:36`).
- Maps sample biomes at sea level (`MapItem.java:222`).
- Sea level is sent to the client in the spawn info.

None of these matter for Mars if biomes are 2D and have no precipitation.

---

## 9. Fabric API (0.161.0+26.3) worldgen helpers

- **fabric-biome-api-v1** (`fabric-api/src/net/fabricmc/fabric/api/biome/v1/`):
  - `BiomeModifications.addFeature(Predicate<BiomeSelectionContext>, GenerationStep.Decoration, ResourceKey<PlacedFeature>)`,
    `addCarver(...)`, `addSpawn(...)`, and `create(Identifier)`, which gives fine-grained `BiomeModification`
    phases.
  - The `BiomeModificationContext` sub-contexts are `WeatherContext`, `AttributesContext` (environment
    attributes), `EffectsContext`, `GenerationSettingsContext`, and `MobSpawnSettingsContext`.
  - `BiomeSelectors` offers `all`, `vanilla`, `foundInOverworld`, `foundInTheNether`, `foundInTheEnd`, `tag`,
    `includeByKey`, `excludeByKey`, and `spawnsOneOf`. "foundIn" means `canGenerateIn(LevelStem.X)`, so Mars biomes
    are not overworld biomes. Other mods' `BiomeSelectors.all()` additions **would** reach Mars biomes.
  - `NetherBiomes.addNetherBiome` and the `TheEndBiomes.*` helpers exist but are not needed.
  - **There is no Fabric API for chunk generators, biome sources, density functions, or structures.** Register
    them through `BuiltInRegistries` directly, as shown above.
- **fabric-dimensions-v1** offers only `DimensionEvents.MODIFY_ATTRIBUTES`, which edits a dimension type's
  environment attributes. Its `WorldDimensionsMixin` makes the saved dimension map **fail-soft**, so a world still
  loads after the mod is removed. It also gives mod-provided dimensions **their pack lifecycle**, so there is no
  "experimental settings" warning (`mixin/dimension/WorldDimensionsMixin.java`). The old
  `FabricDimensions.teleport` is gone.
- **Datapack dimensions with the default world preset.** `WorldDimensions.bake(datapackDimensions)`
  (`levelgen/WorldDimensions.java:152-174`) unions the preset's dimensions with **every `dimension` entry from the
  enabled datapacks**, which include mod resources. It prefers the datapack entry. The client calls it in
  `CreateWorldScreen.onCreate` (about line 292) and `WorldOpenFlows` (line 121); the server calls it in
  `Main.java:259` and `LevelStorageSource.java:159`. So `data/redplanet/dimension/mars.json` appears in every new
  **and** existing world. Only the preset's dimensions are saved in `world_gen_settings` (`new WorldGenSettings(options, worldDimensions)`),
  so the Mars definition is re-read from the jar on each load.
- **Gametests.** The vanilla `GameTestServer` bakes with an **empty** datapack-dimension registry
  (`gametest/framework/GameTestServer.java:119-125`). Fabric enables mod datapacks there but does not change this.
  **Server gametests will therefore likely have no `redplanet:mars` level** (per source; confirm at runtime).
  Client gametests create worlds through `CreateWorldScreen` (`impl/client/gametest/world/TestWorldBuilderImpl.java:112-139`),
  so they do get the Mars dimension. In server gametests, test the generator directly: build
  `RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, marsSettings)` and sample density functions
  and biomes.
- **Datagen.** `FabricDynamicRegistryProvider` (`api/datagen/v1/provider/FabricDynamicRegistryProvider.java`) can
  write JSON for any dynamic registry, including `worldgen/material_rule`. `DynamicRegistries.register...` exists
  for custom dynamic registries.

---

## 10. Recommendations for redplanet

Assumptions: x maps to longitude with period `PERIOD_X = 21338` blocks; z maps to latitude through Mercator; one
block is 1 km horizontally at the equator and 100 m vertically, so `blocks_per_km = 10`. Mars relief is roughly
-8 to +22 km about the datum (put the exact values in `docs/SCIENCE.md`), which is about 300 blocks.

Here is an **illustrative** layout. The `datum_y` and Y numbers are placeholders, not requirements:

- `datum_y = 64` puts Hellas at about y -18 and Olympus Mons at about y 283.
- Noise `min_y = -128` keeps the void-fog onset (below about -96) well under Hellas.
- Noise `height = 448` puts the top at y 319.
- The dimension type has `min_y = -128` and `height = 512`, giving headroom.

**21338 = 2 × 47 × 227.** If crater or detail cells are indexed in block space and must tile at the wrap seam,
pick cell sizes that divide the period (94, 227, 454, ...). The alternative is to index cells in longitude space.
Non-periodic noise must be sampled on a cylinder (see `height()` below) to avoid a seam.

### 10.1 (a) The 2D Mars height density function, with seed access

```java
package io.github.avi130805.redplanet.worldgen.density;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Interval;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.densityfunction.*;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** Mars surface height in block Y at (x, z). 2D (X|Z) so the compiler evaluates it once per column. */
public record MarsHeightFunction(float datumY, float blocksPerKm, float detailBlocks, boolean craters) implements DensityFunction {
    public static final MapCodec<MarsHeightFunction> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.fieldOf("datum_y").forGetter(MarsHeightFunction::datumY),
            Codec.FLOAT.optionalFieldOf("blocks_per_km", 10.0F).forGetter(MarsHeightFunction::blocksPerKm),
            Codec.FLOAT.optionalFieldOf("detail_blocks", 2.0F).forGetter(MarsHeightFunction::detailBlocks),
            Codec.BOOL.optionalFieldOf("craters", true).forGetter(MarsHeightFunction::craters)
    ).apply(i, MarsHeightFunction::new));

    private static final Identifier DETAIL_SEED = Identifier.fromNamespaceAndPath("redplanet", "mars_height/detail");
    private static final Identifier CRATER_SEED = Identifier.fromNamespaceAndPath("redplanet", "mars_height/craters");
    private static final NormalNoise DETAIL = NormalNoise.builder().setBaseOctave(-6).setOctaveCount(4).build();

    @Override public MapCodec<MarsHeightFunction> codec() { return CODEC; }        // must be the registered instance
    @Override public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; } // leaf: no children
    @Override public int domainAxes() { return DensityFunction.AXIS_X | DensityFunction.AXIS_Z; } // never depends on y

    @Override public Interval range() {                                            // MUST bound every output
        float lo = datumY + MarsTopography.MIN_ELEVATION_KM * blocksPerKm - CraterField.MAX_DEPTH_BLOCKS - 2 * detailBlocks;
        float hi = datumY + MarsTopography.MAX_ELEVATION_KM * blocksPerKm + CraterField.MAX_RIM_BLOCKS + 2 * detailBlocks;
        return Interval.of(lo, hi);
    }

    @Override public DensitySampler compileSampler(DensityFunction.CompileContext ctx) {
        // Once per (world, dimension, root function), on a worldgen thread, under the compiler lock.
        RandomSource detailRandom = ctx.createRandom(DETAIL_SEED);   // = RandomState.random.fromHashOf("redplanet:mars_height/detail")
        long craterSeed = ctx.createRandom(CRATER_SEED).nextLong();  // per-world sub-seed
        Noise detail = DETAIL.create(detailRandom);                  // or ctx.createNoiseSampler(<registered noise holder>)
        return new Sampler(MarsTopography.get() /* preloaded, immutable short[] */, detail, craterSeed,
                           datumY, blocksPerKm, detailBlocks, craters);
    }

    private record Sampler(MarsTopography topo, Noise detail, long craterSeed, float datumY, float blocksPerKm,
                           float detailBlocks, boolean craters) implements DensitySampler {          // immutable: thread-safe
        @Override public float sampleValue(SamplerContext c, int x, int y, int z) { return height(x, z); }

        @Override public void sampleVolume(SamplerContext c, DensityBuffer out, DensityVolume v) {
            // Normally called with sizeY == 1 at y == 0 (Slice(Y) inserted by the compiler); stay correct for any volume.
            for (int iz = 0; iz < v.sizeZ(); iz++) {
                int bz = v.blockZ(iz);
                for (int ix = 0; ix < v.sizeX(); ix++) {
                    out.setRange(v.indexUnchecked(ix, 0, iz), v.sizeY(), height(v.blockX(ix), bz));
                }
            }
        }

        private float height(int x, int z) {
            double lon = MarsProjection.longitudeDeg(x);              // floorMod(x, PERIOD_X)
            double lat = MarsProjection.latitudeDeg(z);               // inverse Mercator, clamped near the poles
            double km = topo.elevationKm(lat, lon);                   // bicubic/bilinear over the 8 px/deg MOLA grid
            float h = (float) (datumY + km * blocksPerKm);
            if (craters) h += CraterField.deltaBlocks(craterSeed, lat, lon);   // rims, ejecta, central peaks
            // Periodic detail: sample 3D noise on a cylinder so it tiles every PERIOD_X blocks.
            double theta = 2 * Math.PI * Math.floorMod(x, MarsProjection.PERIOD_X) / MarsProjection.PERIOD_X;
            double r = MarsProjection.PERIOD_X / (2 * Math.PI);
            h += detail.get(r * Math.cos(theta), z, r * Math.sin(theta)) * detailBlocks;
            return h;
        }
    }
}
// ModInitializer: Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,
//                     Identifier.fromNamespaceAndPath("redplanet", "mars_height"), MarsHeightFunction.CODEC);
```

`data/redplanet/worldgen/density_function/mars/surface_height.json` is wrapped in a `cache`. Within a chunk's
terrain step, `final_density` and `chunk_surface_level` then share one 16×1×16 evaluation (§2.4):

```json
{ "type": "minecraft:cache", "input": { "type": "redplanet:mars_height", "datum_y": 64, "blocks_per_km": 10.0, "craters": true } }
```

If craters prove expensive per column, evaluate on a 4-block grid and interpolate:
`{"type":"minecraft:interpolated","cell_size_xz":4,"cell_size_y":1,"input":{...}}`. This mirrors vanilla's
`chunk_surface_level` and gives 25 samples per chunk instead of 256.

Optional seeded climate density functions can feed the biome source in the same way. Examples are
`redplanet:mars_latitude` and `redplanet:mars_albedo`, both declaring `domainAxes = 5`.

### 10.2 (b) The custom BiomeSource

```java
public final class MarsBiomeSource extends BiomeSource {
    public static final MapCodec<MarsBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.unboundedMap(MarsBiomeRole.CODEC, Biome.CODEC).fieldOf("biomes").forGetter(s -> s.biomes)
    ).apply(i, MarsBiomeSource::new));
    // MarsBiomeRole: enum implements StringRepresentable; CODEC = StringRepresentable.fromEnum(MarsBiomeRole::values)
    // Alternative with no JSON field: RegistryOps.retrieveGetter(Registries.BIOME) and resolve ResourceKeys in the ctor.

    private final Map<MarsBiomeRole, Holder<Biome>> biomes;
    private final Holder<Biome> fallback;

    private MarsBiomeSource(Map<MarsBiomeRole, Holder<Biome>> biomes) {
        this.biomes = Map.copyOf(biomes);
        this.fallback = Objects.requireNonNull(biomes.get(MarsBiomeRole.DEFAULT), "biomes.default is required");
    }

    @Override protected MapCodec<MarsBiomeSource> codec() { return CODEC; }
    @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return biomes.values().stream(); }

    @Override public BiomeResolver createResolver(Climate.Sampler sampler) {
        return (qx, qy, qz) -> pick(QuartPos.toBlock(qx), QuartPos.toBlock(qz), sampler);
    }

    @Override public BiomeResolver createResolverForChunk(Climate.Sampler sampler, int minQX, int minQY, int minQZ,
                                                         int sizeQX, int sizeQY, int sizeQZ) {
        @SuppressWarnings("unchecked") Holder<Biome>[] cols = new Holder[sizeQX * sizeQZ];   // biomes are 2D
        for (int dz = 0; dz < sizeQZ; dz++)
            for (int dx = 0; dx < sizeQX; dx++)
                cols[dx + dz * sizeQX] = pick(QuartPos.toBlock(minQX + dx), QuartPos.toBlock(minQZ + dz), sampler);
        return (qx, qy, qz) -> {
            int dx = qx - minQX, dz = qz - minQZ;
            return dx >= 0 && dz >= 0 && dx < sizeQX && dz < sizeQZ ? cols[dx + dz * sizeQX]
                                                                    : pick(QuartPos.toBlock(qx), QuartPos.toBlock(qz), sampler);
        };
    }

    @Override public void addDebugInfo(List<String> out, BlockPos pos, Climate.Sampler sampler) {
        out.add(MarsGeography.debugLine(pos.getX(), pos.getZ()));   // lat/lon, elevation km, slope, albedo, region
    }

    private Holder<Biome> pick(int blockX, int blockZ, Climate.Sampler sampler) {
        double lat = MarsProjection.latitudeDeg(blockZ), lon = MarsProjection.longitudeDeg(blockX);
        // Geography first (named regions, polar caps, elevation, slope, albedo). Seeded jitter, if any, comes from a
        // router slot: sampler.humidity().sampleValue(blockX, 0, blockZ).
        return biomes.getOrDefault(MarsGeography.classify(lat, lon), fallback);
    }
}
// ModInitializer: Registry.register(BuiltInRegistries.BIOME_SOURCE, Identifier.fromNamespaceAndPath("redplanet", "mars"), MarsBiomeSource.CODEC);
```

`data/redplanet/dimension/mars.json`:

```json
{ "type": "redplanet:mars",
  "generator": { "type": "minecraft:noise", "settings": "redplanet:mars",
    "biome_source": { "type": "redplanet:mars", "biomes": {
        "default": "redplanet:cratered_highlands",
        "northern_lowlands": "redplanet:vastitas_borealis",
        "volcanic_province": "redplanet:tharsis",
        "olympus_mons": "redplanet:olympus_mons",
        "canyon": "redplanet:valles_marineris",
        "impact_basin": "redplanet:hellas_planitia",
        "north_polar_cap": "redplanet:planum_boreum",
        "south_polar_cap": "redplanet:planum_australe",
        "dune_field": "redplanet:dune_field" } } } }
```

Here is a minimal Mars biome, `data/redplanet/worldgen/biome/tharsis.json`:

```json
{ "has_precipitation": false, "temperature": -0.5, "downfall": 0.0,
  "attributes": { "minecraft:visual/sky_color": "#c9a27e", "minecraft:visual/fog_color": "#d6b08c",
                  "minecraft:gameplay/water_evaporates": true },
  "effects": { "water_color": "#3f76e4" },
  "carvers": [],
  "features": [ [], [], [], [], [ "redplanet:lander_site_feature" ], [], [], [], [], [ "redplanet:boulders" ], [] ] }
```

Keep the `features` lists in **the same relative order in every Mars biome** (FeatureSorter). The sky and fog
colours above are placeholders; source them in `SCIENCE.md`.

### 10.3 (c) Mars `noise_settings`: air fluid, no aquifers, no ore veins

`data/redplanet/worldgen/noise_settings/mars.json`:

```json
{
  "noise": { "min_y": -128, "height": 448 },
  "default_block": "minecraft:smooth_basalt",
  "default_fluid": "minecraft:air",
  "noise_router": {
    "temperature": 0.0, "vegetation": 0.0, "continents": 0.0, "erosion": 0.0, "depth": 0.0, "ridges": 0.0,
    "chunk_surface_level": "redplanet:mars/surface_height",
    "final_density": {
      "type": "minecraft:add",
      "left": { "type": "minecraft:clamp", "min": -1.0, "max": 1.0,
                "input": { "type": "minecraft:mul", "right": 0.1,
                           "left": { "type": "minecraft:sub", "left": "redplanet:mars/surface_height", "right": "minecraft:y" } } },
      "right": { "type": "minecraft:beardifier" }
    }
  },
  "material_rule": "redplanet:mars",
  "spawn_target": [],
  "sea_level": -128,
  "disable_mob_generation": true,
  "legacy_random_source": false,
  "debug_functions": [ { "label": "H", "function": "redplanet:mars/surface_height" },
                       { "label": "N", "function": "redplanet:mars/final_density_preview" } ]
}
```

Notes on these settings:

- **No `aquifers` key** disables aquifers. `default_fluid` is air, and **`sea_level` equals `min_y`**, which
  disables the hardcoded lava band below -54 (§3.3).
- **No ore veins**, because `ore_vein` rules are left out of `material_rule`. Ores, if wanted, are `ore` features.
- The `mul 0.1` and `clamp ±1` give about 0.1 density per block, so the beardifier's ±0.8 can flatten or fill about
  8 blocks under `beard_box` or `beard_thin` structures. **UNVERIFIED tuning**: confirm visually in gametest
  screenshots. Without structure adaptation, `sub(height, y)` alone is enough.
- Swap `default_block` for the mod's own regolith or basalt block when it exists.
- The router climate slots can carry seeded geography density functions for the biome source (§4.5).
- `debug_functions` entries must be valid density functions; `final_density_preview` is a hypothetical named copy.
  Remove entries you don't register.

Here is a sketch of `data/redplanet/worldgen/material_rule/mars.json` with placeholder blocks:

```json
{ "type": "minecraft:sequence", "sequence": [
  "minecraft:bedrock_floor",
  { "type": "minecraft:condition",
    "if_true": { "type": "redplanet:latitude", "min_latitude": 80.0, "max_latitude": 90.0 },
    "then_run": { "type": "minecraft:condition", "if_true": "minecraft:under_floor",
                  "then_run": { "type": "minecraft:block", "result_state": "minecraft:packed_ice" } } },
  { "type": "minecraft:condition", "if_true": { "type": "minecraft:above_preliminary_surface" },
    "then_run": { "type": "minecraft:sequence", "sequence": [
      { "type": "minecraft:condition", "if_true": "minecraft:on_floor",
        "then_run": { "type": "minecraft:block", "result_state": "minecraft:red_sand" } },
      { "type": "minecraft:condition", "if_true": "minecraft:under_floor",
        "then_run": { "type": "minecraft:block", "result_state": "minecraft:red_sandstone" } } ] } } ] }
```

The dimension type, `data/redplanet/dimension_type/mars.json`, is sketched here. Its environment attributes, clock,
and timeline are out of scope for this note:

```json
{ "has_skylight": true, "has_ceiling": false, "has_ender_dragon_fight": false, "coordinate_scale": 1.0,
  "min_y": -128, "height": 512, "logical_height": 512, "infiniburn": "#minecraft:infiniburn_overworld",
  "ambient_light": 0.0, "monster_spawn_light_level": 0, "monster_spawn_block_light_limit": 0,
  "attributes": { "minecraft:gameplay/water_evaporates": true } }
```

### 10.4 (d) Structures at fixed lander coordinates, repeating every 21,338 blocks in x

**Option A, recommended: a structure with a custom placement.** It has no size limit beyond 8 chunks, gets terrain
adaptation from the beardifier, and does chunk-safe placement of large templates.

```java
/** Matches exactly the chunk(s) containing the lander, in every x-wrap copy. */
public record GeoFixedPlacement(double latitude, double longitude) implements StructurePlacement {
    public static final MapCodec<GeoFixedPlacement> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.doubleRange(-90.0, 90.0).fieldOf("latitude").forGetter(GeoFixedPlacement::latitude),
            Codec.doubleRange(-360.0, 360.0).fieldOf("longitude").forGetter(GeoFixedPlacement::longitude)
    ).apply(i, GeoFixedPlacement::new));

    @Override public boolean isStructureChunk(ChunkGeneratorStructureState state, int chunkX, int chunkZ) {
        int bz = MarsProjection.blockZ(latitude);
        if (SectionPos.blockToSectionCoord(bz) != chunkZ) return false;
        int bx = MarsProjection.wrappedBlockXNear(longitude, SectionPos.sectionToBlockCoord(chunkX, 8)); // x0 + k*PERIOD_X nearest
        return SectionPos.blockToSectionCoord(bx) == chunkX;
    }
    @Override public BlockPos getLocatePos(ChunkPos cp) {
        return new BlockPos(MarsProjection.wrappedBlockXNear(longitude, cp.getMiddleBlockX()), 0, MarsProjection.blockZ(latitude));
    }
    @Override public MapCodec<GeoFixedPlacement> codec() { return CODEC; }
}

public final class LanderSiteStructure extends Structure {
    public static final MapCodec<LanderSiteStructure> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            settingsCodec(i),
            Identifier.CODEC.fieldOf("template").forGetter(s -> s.template),
            Codec.DOUBLE.fieldOf("latitude").forGetter(s -> s.latitude),
            Codec.DOUBLE.fieldOf("longitude").forGetter(s -> s.longitude),
            Codec.INT.optionalFieldOf("y_offset", 0).forGetter(s -> s.yOffset)
    ).apply(i, LanderSiteStructure::new));
    final Identifier template; final double latitude, longitude; final int yOffset;

    LanderSiteStructure(StructureSettings settings, Identifier template, double latitude, double longitude, int yOffset) {
        super(settings); this.template = template; this.latitude = latitude; this.longitude = longitude; this.yOffset = yOffset;
    }

    @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext ctx) {
        int bx = MarsProjection.wrappedBlockXNear(longitude, ctx.chunkPos().getMiddleBlockX());
        int bz = MarsProjection.blockZ(latitude);
        int y = ctx.chunkGenerator().getFirstOccupiedHeight(bx, bz, Heightmap.Types.WORLD_SURFACE_WG, ctx.heightAccessor(), ctx.randomState());
        BlockPos pos = new BlockPos(bx, y + yOffset, bz);   // template min corner; centre it by subtracting size/2 if desired
        return Optional.of(new GenerationStub(pos, b -> b.addPiece(new LanderSitePiece(ctx.structureTemplateManager(), template, pos, Rotation.NONE))));
    }
    @Override public StructureType<?> type() { return RedPlanetStructures.LANDER_SITE; }
}

public final class LanderSitePiece extends TemplateStructurePiece {
    public LanderSitePiece(StructureTemplateManager mgr, Identifier template, BlockPos pos, Rotation rot) {
        super(RedPlanetStructures.LANDER_SITE_PIECE, 0, mgr, template, template.toString(), settings(rot), pos);
    }
    public LanderSitePiece(StructureTemplateManager mgr, CompoundTag tag) {           // load path (StructureTemplateType)
        super(RedPlanetStructures.LANDER_SITE_PIECE, tag, mgr, id -> settings(tag.read("Rot", Rotation.LEGACY_CODEC).orElseThrow()));
    }
    private static StructurePlaceSettings settings(Rotation rot) {
        return new StructurePlaceSettings().setRotation(rot).setMirror(Mirror.NONE).addProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK);
    }
    @Override protected void addAdditionalSaveData(StructurePieceSerializationContext ctx, CompoundTag tag) {
        super.addAdditionalSaveData(ctx, tag); tag.store("Rot", Rotation.LEGACY_CODEC, this.placeSettings.getRotation());
    }
    @Override protected void handleDataMarker(String marker, BlockPos pos, ServerLevelAccessor level, RandomSource random, BoundingBox chunkBB) { }
}
```

The JSON uses one set per site. The coordinates are **placeholders**; source them in `SCIENCE.md`.

```json
// data/redplanet/worldgen/structure/viking1_site.json
{ "type": "redplanet:lander_site", "biomes": "#redplanet:is_mars", "spawn_overrides": {}, "step": "surface_structures",
  "terrain_adaptation": "beard_box", "template": "redplanet:landers/viking1", "latitude": 22.27, "longitude": -47.95 }
// data/redplanet/worldgen/structure_set/viking1_site.json
{ "placement": { "type": "redplanet:geo_fixed", "latitude": 22.27, "longitude": -47.95 },
  "structures": [ { "structure": "redplanet:viking1_site", "weight": 1 } ] }
// data/redplanet/tags/worldgen/biome/is_mars.json -> all Mars biomes (structure-set filter + generation-point biome check)
```

`/locate structure` will not find `geo_fixed` (§7.2). There are two ways around it. One is our own command, such
as `/redplanet locate viking1`, which already has the coordinates. The other is **UNVERIFIED**: make
`GeoFixedPlacement extends RandomSpreadStructurePlacement`, using the public constructor with
`spacing ≈ PERIOD_X/16` chunks, and override `getPotentialStructureChunk(seed, sx, sz)` to return the nearest wrapped
lander chunk. Vanilla's `isPlacementChunk` and `/locate` both call that method, but the trick depends on the
`instanceof` check at line 201.

**Option B, simplest: a feature with a placement modifier.** The `template` feature works with
`fixed_placement`, following the End-platform precedent, or with a custom wrapped modifier. The **footprint must be
about 32 blocks or less** (3×3 write radius, template centred on the origin), and there is no beardifier
terrain adaptation.

```java
public record GeoFixedModifier(double latitude, double longitude) implements PlacementModifier {
    public static final MapCodec<GeoFixedModifier> CODEC = /* latitude, longitude */;
    @Override public void modify(PlacementContext ctx, RandomSource random, BlockPos origin, Consumer<BlockPos> out) {
        int cx = SectionPos.blockToSectionCoord(origin.getX()), cz = SectionPos.blockToSectionCoord(origin.getZ());
        int bz = MarsProjection.blockZ(latitude);
        if (SectionPos.blockToSectionCoord(bz) != cz) return;
        int bx = MarsProjection.wrappedBlockXNear(longitude, SectionPos.sectionToBlockCoord(cx, 8));
        if (SectionPos.blockToSectionCoord(bx) == cx) out.accept(new BlockPos(bx, origin.getY(), bz));
    }
    @Override public MapCodec<GeoFixedModifier> codec() { return CODEC; }
}
// Registry.register(BuiltInRegistries.PLACEMENT_MODIFIER_TYPE, Identifier.fromNamespaceAndPath("redplanet", "geo_fixed"), GeoFixedModifier.CODEC);
```

```json
// data/redplanet/worldgen/placed_feature/viking1_marker.json
{ "feature": { "type": "minecraft:template", "templates": [ { "data": { "id": "redplanet:landers/viking1", "rotations": [ "none" ] }, "weight": 1 } ] },
  "placement": [ { "type": "redplanet:geo_fixed", "latitude": 22.27, "longitude": -47.95 },
                 { "type": "minecraft:heightmap", "heightmap": "WORLD_SURFACE_WG" } ] }
```

The placed feature must appear in **every** Mars biome's `features` list at the same step, because decoration only
runs features listed by biomes present in the 3×3 area. Omit `minecraft:biome` from its placement unless you want
the biome check.

**Generating templates from Python.** The verified writer is in the appendix. Write `DataVersion` 5023, palette
entries `{"id", "properties"}`, gzip, and save to `src/main/resources/data/redplanet/structure/landers/viking1.nbt`.

### 10.5 Risks and pitfalls checklist

1. **Lava below y -54** when aquifers are off. Fix it with `sea_level <= min_y`, or keep noise `min_y >= -48`.
2. **The density-function `range()` must be conservative**, or `min`/`max` silently pick the wrong branch.
3. **`domainAxes` must be honest.** A function declared 2D is sampled at y = 0 only.
4. **A custom density function with children must implement `rewriteChildren`.** Otherwise a nested `cache`
   throws at compile time.
5. **Noises must be registered** to use `createNoiseSampler`. Inline noise holders throw.
6. **The NBT palette uses `id`/`properties` with DataVersion 5023.** The old keys produce air.
7. **There is a feature-order cycle** if Mars biomes list shared features in different orders.
8. **`/locate` does not work for custom placements.**
9. **Server gametests probably lack the datapack dimension.** Use client gametests or sample the generator directly.
10. **Void fog is within 32 blocks of `min_y`**, and the sky draws a "dark disc" below y 63. Keep basin floors well
    above `min_y`. The disc is a rendering concern.
11. **`steep` is asymmetric.** Write a custom slope condition (|gradX|, |gradZ|) for slope-based materials.
12. **`possibleBiomes()` must list every biome** the source can return. Structures, features, and `/locate`
    depend on it.
13. **Load the MOLA grid once** (about 8.3 MB as `short[2880*1440]`), preloaded and immutable. Don't do I/O
    inside samplers.
14. Other mods' `BiomeModifications` with `BiomeSelectors.all()` can inject features into Mars biomes.

---

## Appendix: minimal NBT reader and writer (Python 3, verified round trip)

This was verified by decoding vanilla `igloo/top.nbt`, `village/desert/camel_spawn.nbt`, and a campsite chest
template, and by round-tripping a generated 3×2×3 template.

```python
import gzip, struct, io
TAG_END, TAG_BYTE, TAG_SHORT, TAG_INT, TAG_LONG, TAG_FLOAT, TAG_DOUBLE, TAG_BYTE_ARRAY, TAG_STRING, TAG_LIST, TAG_COMPOUND, TAG_INT_ARRAY, TAG_LONG_ARRAY = range(13)

class T:                                   # explicitly typed value for writing
    def __init__(self, t, v): self.t, self.v = t, v

def _w(f, t, v):
    if t == TAG_BYTE: f.write(struct.pack('>b', v))
    elif t == TAG_SHORT: f.write(struct.pack('>h', v))
    elif t == TAG_INT: f.write(struct.pack('>i', v))
    elif t == TAG_LONG: f.write(struct.pack('>q', v))
    elif t == TAG_FLOAT: f.write(struct.pack('>f', v))
    elif t == TAG_DOUBLE: f.write(struct.pack('>d', v))
    elif t == TAG_STRING: b = v.encode('utf-8'); f.write(struct.pack('>H', len(b))); f.write(b)
    elif t == TAG_LIST:
        et, items = v
        f.write(bytes([et if items else TAG_END])); f.write(struct.pack('>i', len(items)))
        for it in items: _w(f, et, it.v if isinstance(it, T) else it)
    elif t == TAG_COMPOUND:
        for k, tv in v.items():
            kb = k.encode(); f.write(bytes([tv.t])); f.write(struct.pack('>H', len(kb))); f.write(kb); _w(f, tv.t, tv.v)
        f.write(bytes([TAG_END]))
    elif t == TAG_INT_ARRAY: f.write(struct.pack('>i', len(v))); f.write(struct.pack('>%di' % len(v), *v))
    else: raise ValueError(t)

def write_nbt(path, root):                 # unnamed root compound, gzip (NbtIo.readCompressed)
    buf = io.BytesIO(); buf.write(bytes([TAG_COMPOUND])); buf.write(struct.pack('>H', 0)); _w(buf, TAG_COMPOUND, root)
    with gzip.open(path, 'wb') as f: f.write(buf.getvalue())

def ints(*xs): return T(TAG_LIST, (TAG_INT, list(xs)))

def make_template(path, size, palette, blocks, entities=()):
    """palette: [{'id': 'minecraft:iron_block', 'properties': {...}}]; blocks: [((x,y,z), paletteIndex, nbtDictOrNone)]"""
    def ptag(p):
        d = {'id': T(TAG_STRING, p['id'])}
        if p.get('properties'): d['properties'] = T(TAG_COMPOUND, {k: T(TAG_STRING, str(v)) for k, v in p['properties'].items()})
        return d
    def btag(pos, state, nbt):
        d = {'pos': ints(*pos), 'state': T(TAG_INT, state)}
        if nbt: d['nbt'] = T(TAG_COMPOUND, nbt)      # e.g. {'id': T(TAG_STRING,'minecraft:chest'), 'LootTable': T(TAG_STRING,'...')}
        return d
    write_nbt(path, {
        'size': ints(*size),
        'entities': T(TAG_LIST, (TAG_COMPOUND, list(entities))),   # {'pos': doubles, 'blockPos': ints, 'nbt': {...,'id':...}}
        'blocks': T(TAG_LIST, (TAG_COMPOUND, [btag(*b) for b in blocks])),
        'palette': T(TAG_LIST, (TAG_COMPOUND, [ptag(p) for p in palette])),
        'DataVersion': T(TAG_INT, 5023),
    })
```

The full reader and writer (with a typed dump) is at
`/tmp/claude-0/-home-user-Minecraft-Mods/1c10d1d5-baaa-587e-aff5-004c51257ed4/scratchpad/nbt.py`. Copy it into
`tools/` if you want to keep it.
