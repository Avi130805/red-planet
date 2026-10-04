# Minecraft Java 26.3 API notes (Fabric)

Verified facts about 26.3 that the mod depends on, gathered by reading the decompiled sources (`./gradlew
genSources`), the vanilla data in the client jar and the Fabric API 0.161.0+26.3 sources, not from memory of
1.21.x. Each area has a detailed note with file and line citations under [api-notes/](api-notes/); this page is
the index and the short version. Update both when something new is learned.

| Area | Detailed note |
|---|---|
| Dimensions, environment attributes, timelines, clocks, weather, teleports, chunk tickets | [dimension-environment.md](api-notes/dimension-environment.md) |
| World generation: noise settings, density functions, biome sources, material rules, features, structures | [worldgen.md](api-notes/worldgen.md) |
| Entities and rendering: entity types, vehicles, renderers, render types/pipelines, particles | [entity-rendering.md](api-notes/entity-rendering.md) |
| Client: camera, HUD, screens, loading screen, sky, fog, sound, input | [client-camera-hud-sky.md](api-notes/client-camera-hud-sky.md) |
| Networking, persistence, events, commands, game rules, server and client gametests, Loom | [networking-persistence-gametest.md](api-notes/networking-persistence-gametest.md) |
| Physics and gameplay hooks: gravity, drag, fall damage, air, fire, water | [physics-hooks.md](api-notes/physics-hooks.md) |
| Blocks, items, equipment, menus, components, recipes, loot, advancements, datagen | [content-datagen.md](api-notes/content-datagen.md) |

## Toolchain

- Minecraft 26.3 (`world_version` 5023, protocol 777, resource pack 97.1, data pack 121.0), Java 25.
- Fabric Loader 0.19.5 (bundles MixinExtras 0.5.5), Fabric API 0.161.0+26.3, Loom 1.18.2, Gradle 9.7.1.
- Unobfuscated: Mojang names, no mappings; dependencies use `implementation`.
- `ResourceLocation` is now **`Identifier`** (`Identifier.fromNamespaceAndPath`).
- `fabricApi { configureTests { ... } }` creates the `gametest` source set and the `runGameTest` /
  `runClientGameTest` tasks; `check` depends on `runGameTest`. Loom run tasks have a `useXvfb` property (auto only
  when `CI` is set). Pass `--graphicsBackend opengl` for Mesa llvmpipe.
- Rendering backend: `com.mojang.renderpearl` with OpenGL and Vulkan backends (GL tried first by default). GLSL
  shaders are compiled to SPIR-V and back to GLSL 330 for GL. Depth is reverse-Z.

## Dimensions and environment

- Dimension types carry **environment attributes** (`attributes`), `default_clock` and `timelines`. Biomes carry only
  positional attributes; sky/fog/music/particles/mob spawns moved there from `effects`.
- **Mods can register their own environment attributes** into `BuiltInRegistries.ENVIRONMENT_ATTRIBUTE` during
  `onInitialize` (Fabric defers the freeze). They serialize by name (no registry sync needed); mark them
  `.syncable()` for the client and `.notPositional()` for dimension-wide values. Read with
  `level.environmentAttributes().getDimensionValue(attr)` / `getValue(attr, pos)`.
- Layer order: dimension → biome → timelines → weather → (client) lightning flash. Extra layers can be added by one
  mixin at `EnvironmentAttributeSystem.Builder#addDynamicLayers(Level)` RETURN (both sides) — used for altitude sky
  darkening, dust storms and habitat interiors.
- **World clocks and timelines:** `world_clock/<id>.json` = `{}`; a timeline binds to a clock with `period_ticks`.
  A 24,660-tick Mars sol needs only data. Sleeping jumps the dimension's default clock to
  `minecraft:wake_up_from_sleep`. `Level.getDayTime()` is gone: use `getDefaultClockTime()` or timelines. The
  timeline must drive `sun_angle`/`moon_angle`/`star_*` or the sky freezes at noon. Sun angle 0° = zenith, 90° = west
  horizon, 180° = nadir, 270° = east horizon.
- **Weather is global** (one `WeatherData` per server) and broadcast to all players. A custom skylight dimension shares
  the overworld's rain and doubles its timer speed unless `Level.canHaveWeather()` and the client rain level are
  patched (two small mixins).
- Mod `data/<ns>/dimension/*.json` dimensions are merged into **new and existing** worlds (no experimental warning).
- **Cross-dimension teleport** (`Entity.teleport(TeleportTransition)`) rebuilds non-player entities from NBT with the
  same UUID and re-mounts passengers; players are moved, not recreated. Loading clamps velocity components > 10 b/t.
- **Chunk tickets:** custom `TicketType(timeout, flags)` in `BuiltInRegistries.TICKET_TYPE`;
  `addTicketAndLoadWithRadius` is async (returns a future). A dimension stops ticking entities 300 ticks after it
  empties unless a `FLAG_KEEP_DIMENSION_ACTIVE` ticket exists. `setChunkForced`/`getChunk` block the main thread.
- The client shows `LevelLoadingScreen` on every dimension change; arriving above the build height closes it almost
  at once. Respawn without a valid bed/anchor goes to the overworld world spawn; bed rules come from attributes.

## World generation

- `noise_settings.noise` is only `{min_y, height}`; surface rules became **material rules** (registries
  `worldgen/material_rule`, `material_condition`); aquifers are an optional object; ore veins are a material rule.
- **Density functions** compile to samplers (`compileSampler(CompileContext)` → `DensitySampler` with
  `sampleValue`/`sampleVolume`, float values, `MapCodec` in `DENSITY_FUNCTION_TYPE`). A function declaring
  `domainAxes() = X|Z` is evaluated **once per column**. Seeds: `CompileContext.createRandom(Identifier)`.
  `range()` must be conservative. `flat_cache`/`cache_2d` are gone (only `cache` and `interpolated` remain).
- Without aquifers, air below `min(-54, sea_level)` fills with lava: set `sea_level <= min_y`.
- **Biome sources** implement `codec()`, `collectPossibleBiomes()`, `createResolver(Climate.Sampler)` (+ optional
  `createResolverForChunk`). Biomes come in via `Biome.CODEC` or `RegistryOps.retrieveGetter`.
- Features are records carrying their config; `fixed_placement` and `template` exist. Structure placements implement
  `isStructureChunk`; custom placements are invisible to `/locate`. Structure NBT: `DataVersion` 5023, palette entries
  `{id, properties}`.
- Server gametests build their world from `flat_all_dimensions` without datapack dimensions; override that preset in
  the gametest datapack to add a Mars level.

## Entities and rendering

- `EntityType.Builder.of(factory, MobCategory.MISC)...build(ResourceKey)`; don't use `noSave()` for vehicles.
  `interact(Player, InteractionHand, Vec3)`; save data via `ValueInput`/`ValueOutput`; `QUATERNION` data serializer.
- Riders are positioned by the vehicle every tick on both sides; override the dismount location; a mixin on
  `Player#wantsToStopRiding` is the only way to veto dismounting.
- Renderers: `createRenderState`, `extractRenderState(entity, state, partialTick)`,
  `submit(state, PoseStack, SubmitNodeCollector, CameraRenderState)`; arbitrary geometry with
  `submitCustomGeometry(poseStack, renderType, (pose, consumer) -> ...)`. Register with `EntityRenderers.register`.
- Custom render types: `RenderPipeline.builder(snippet)` → `RenderPipelines.register` → `RenderType.create` with
  `RenderSetup`. With "Improved transparency" (OIT) every translucent type needs an OIT pipeline (reuse vanilla's).
- Entities beyond ~4× render distance are clipped by the far plane and fogged: very distant vehicles need an impostor.
  The client snaps an entity that lags > 64 blocks; fast vehicles need their own interpolation.
- Particles: 16,384 per group; `ParticleGroupRegistry` gives a mod its own budget; blending comes from the quad layer.

## Client

- Frame = update → extract → render. `GuiGraphics` is now **`GuiGraphicsExtractor`** (2D `Matrix3x2fStack` pose).
  Screens are set via `minecraft.gui.setScreen`. HUD elements: Fabric `HudElementRegistry` (hidden by F1 and during
  level loading). F1 toggles `Hud.isHidden` (`Options.hideGui` is gone).
- Camera: `Camera.update(DeltaTracker)`; override at the end of `alignWithEntity` and in `calculateFov` (mixins); roll
  by rebuilding the rotation quaternion. Chunks render around the camera but only those the server sent.
- An open `LevelLoadingScreen` subclass is **reused** across the respawn packet, which makes it the host for the
  transfer interlude. Pausing screens stop the integrated server.
- Sky: no Fabric sky API; vanilla's sun disc is 8.58° wide (about 16× real). Custom bodies are drawn by replacing
  `SkyRenderer.renderSunMoonAndStars` (mixin) inside the open sky pass.
- Sound: OpenAL `EXTEfx` is in lwjgl-openal 3.4.3 (low-pass via three small mixins); availability on macOS must be
  feature-detected. Key codes are SDL scancodes; key categories are records.

## Networking, persistence, gametests

- Payload registries: `PayloadTypeRegistry.clientboundPlay()`, `serverboundPlay()`, `clientboundConfiguration()`,
  `serverboundConfiguration()`. `DynamicRegistries.registerSynced` syncs datapack registries to clients.
- `SavedDataType` takes an `Identifier`; `MinecraftServer.getDataStorage()` is server-wide.
- A vehicle carrying exactly one player is saved into that player's file on logout and restored with them; with two
  or more players it stays in the world and returning players are not re-seated. Join events fire before the vehicle
  is restored.
- Events: `ServerTickEvents.*_LEVEL_TICK`, `ServerEntityLevelChangeEvents`, `ServerPlayerEvents.JOIN/LEAVE`.
- Client gametests run at ≤ 20 TPS real time with no global timeout; the run directory is wiped before each run, so
  screenshots must go elsewhere (`withDestinationDir`).

## Physics hooks

- `Entity.getGravity()` is final and is the single choke point for gravity (fix two direct `getDefaultGravity()` call
  sites: fishing hook, boat buoyancy). `getAirDrag()` is shared (7 overrides); living entities' vertical drag is the
  `0.98F` constant in `travelInAir`.
- Fall damage: scale the fall distance passed to `checkFallDamage`. Air: block `increaseAirSupply` and add a hypoxia
  step at the end of `baseTick`. Fire: four hooks (`canSurvive`, `Level.setBlock` lit state, fire ticks, furnace burn
  duration). Water: vanilla `water_evaporates` (positional) already handles buckets, dispensers, ice and sponges.
- Passengers of a vehicle with no controlling passenger are never movement-checked; there is no velocity clamp;
  entities far above the build limit keep ticking.
