# Networking, persistence, events, commands, game rules and gametests (MC 26.3, Fabric API 0.161.0+26.3)

Research notes for **Red Planet: Starship to Mars** (`redplanet`, package `io.github.avi130805.redplanet`).
Written 2026-10-04. Everything below was read in the decompiled 26.3 sources, Fabric API 0.161.0+26.3 sources
and Loom 1.18.x sources. Nothing was taken from memory unless it is marked **UNVERIFIED**. Nothing here was compiled
or run; the skeletons in section 10 are written against the signatures quoted in sections 1 to 9.

## Path legend

| Prefix | Absolute path |
|---|---|
| `MC:` | `/home/user/mcsrc/common/` (decompiled common/server code) |
| `MCC:` | `/home/user/mcsrc/client/` (decompiled client-only code) |
| `FAPI:` | `/home/user/mcsrc/fabric-api/src/` (Fabric API sources, all modules merged) |
| `LOOM:` | `/home/user/mcsrc/loom/src/` |
| `DATA:` | `/home/user/mcsrc/jar-client/data/minecraft/` (vanilla data) |

A citation such as `MC:net/minecraft/server/players/PlayerList.java:301` means file plus line number.

---

## 0. Key findings (read this first)

1. **Payload registries were renamed.** They are now `PayloadTypeRegistry.clientboundPlay()`, `serverboundPlay()`,
   `clientboundConfiguration()` and `serverboundConfiguration()`. The old `playS2C`/`playC2S` names no longer exist
   anywhere in Fabric API (§1.2).
2. **`DimensionDataStorage` is now `SavedDataStorage`, and there is a real server-global store.**
   `MinecraftServer.getDataStorage()` writes to `<world>/data/<ns>/<path>.dat`. `ServerLevel.getDataStorage()` is per
   dimension. `SavedDataType` is `(Identifier id, Supplier<T>, Codec<T>, DataFixTypes)`, and Fabric accepts `null` as
   the `DataFixTypes` (§3.1).
3. **Game rules are server-global.** They live in the `BuiltInRegistries.GAME_RULE` registry, and
   `ServerLevel.getGameRules()` just returns `server.getGameRules()`. Fabric's builder is `GameRuleBuilder` (§6).
4. **Server gametests can run in another dimension.** `TestData` now has a `dimension` field, and Fabric's
   `@GameTest(dimension = "...")` sets it (§7.2).
5. **CRITICAL: Mars will not exist in the server gametest world.** `GameTestServer` builds its world from the
   `minecraft:flat_all_dimensions` preset and bakes it against an **empty** datapack-dimension registry. A
   `data/redplanet/dimension/mars.json` is therefore ignored, and any `@GameTest(dimension = "redplanet:mars")` crashes
   the batch factory with `IllegalStateException("Missing level for dimension")`. The fix is to override the world
   preset from the gametest datapack, with no mixin (§7.6).
6. **Tests in one batch run at the same time.** A batch is the set of tests sharing one (environment, dimension) pair,
   up to 50 of them, placed on a grid. Tests that touch global state, such as mission SavedData, game rules or another
   dimension, can interfere with each other (§7.4).
7. **The gametest world persists between runs** in `build/run/gameTest/world`. SavedData from an earlier run would
   leak into later tests, so delete that folder before each run (§7.9).
8. **The single test in our current build is vanilla's `minecraft:always_pass`.** It comes from
   `DATA:test_instance/always_pass.json` and is `required: false`. There are no mod tests yet, because the `gametest`
   source set is empty and has no `fabric.mod.json` (§7.8).
9. **Mock players are not ticked like real players.** `ServerPlayer.doTick()` is driven only by the network connection,
   so air, drowning and movement physics do not advance for a gametest mock player. Player movement is also
   client-authoritative. Fabric's `FakePlayer.startRiding()` always returns `false`, so it cannot be a passenger
   (§7.10).
10. **Vanilla vehicle persistence on logout:**
    - A ship with **exactly one player passenger** is never saved to chunks (`shouldBeSaved()` returns `false`). It is
      saved inside that player's file as `RootVehicle`, removed from the world on logout, and respawned and re-mounted
      on login.
    - A ship with **two or more** player passengers stays in the world. Players who log out are not re-mounted when
      they log back in.
    - Fabric's `JOIN` events fire **before** the vehicle is restored (§3.4).
11. **Client gametests:**
    - The client runs at most 20 ticks per second in real time, with exactly one server tick per client tick.
    - There is no global timeout.
    - Any open `Screen` whose `isPauseScreen()` returns `true` (the default) pauses the integrated server. An interlude
      screen must override it to return `false`.
    - The run directory, including `screenshots/`, is deleted before each `runClientGameTest` (§8).
12. **Loom:**
    - `runClientGameTest` runs in `build/run/clientGameTest` and is not part of `check`/`build`.
    - Enable Xvfb with `tasks.named("runClientGameTest") { useXvfb = true }`. It defaults to `true` only when `$CI` is
      set.
    - Force OpenGL with the program argument `--graphicsBackend opengl`. Minecraft 26.3 uses SDL3 and needs a GL 3.3
      core context. llvmpipe is installed in this container; lavapipe (Vulkan) is not (§9).

---

## 1. Networking (fabric-networking-api-v1 6.3.8)

### 1.1 Defining a payload

Vanilla `MC:net/minecraft/network/protocol/common/custom/CustomPacketPayload.java`:

```java
public interface CustomPacketPayload {
	CustomPacketPayload.Type<? extends CustomPacketPayload> type();                                   // :14
	static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> createType(final String id)    // :20  (minecraft namespace only!)
	record Type<T extends CustomPacketPayload>(Identifier id) {}                                       // :56
	record TypeAndCodec<B extends FriendlyByteBuf, T extends CustomPacketPayload>(Type<T> type, StreamCodec<B, T> codec) {} // :59
}
```

For a mod namespace, use `new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(...))`. Fabric does the same
itself: `FAPI:net/fabricmc/fabric/impl/client/gametest/util/GameTestSyncPayload.java` declares
`public static final Type<GameTestSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(..., "gametest_sync"));`
and `StreamCodec.unit(INSTANCE)`.

**`StreamCodec`** (`MC:net/minecraft/network/codec/StreamCodec.java`):

- `of(encoder, decoder)` (:23)
- `ofMember(memberEncoder, decoder)` (:37)
- `unit(instance)` (:51)
- `.map(to, from)` (:71)
- `.apply(CodecOperation)` (:67)
- `.dispatch(...)` (:99)
- `.cast()` (:762)
- `recursive(...)` (:746)
- `composite(...)` overloads for **1 to 14 fields** (:120 to :675). The 6-field form is:

```java
static <B, C, T1, T2, T3, T4, T5, T6> StreamCodec<B, C> composite(
	StreamCodec<? super B, T1> codec1, Function<C, T1> getter1, ..., StreamCodec<? super B, T6> codec6, Function<C, T6> getter6,
	Function6<T1, T2, T3, T4, T5, T6, C> constructor)                                                   // :251
```

**`ByteBufCodecs`** (`MC:net/minecraft/network/codec/ByteBufCodecs.java`):

- Constants:
  - `BOOL` (:63), `BYTE` (:72), `SHORT` (:82), `INT` (:100), `VAR_INT` (:109), `LONG` (:121), `VAR_LONG` (:130)
  - `FLOAT` (:139), `DOUBLE` (:148), `STRING_UTF8` (:184), `COMPOUND_TAG` (:187), `VECTOR3F` (:198), `QUATERNIONF` (:207)
- Factories:
  - `stringUtf8(max)`, `byteArray(max)` (:278)
  - `fromCodec(Codec)` (:365), `fromCodecWithRegistries(Codec)` (:391)
  - `optional(codec)` (:412), `collection(...)` (:446), `list()` / `list(max)` (:485, :489), `map(...)` (:531), `either(...)` (:564)
  - `idMapper(...)` (:623), `registry(key)` (:661), `holderRegistry(key)` (:665), `holder(...)` (:669)

Common `STREAM_CODEC` fields:

- `UUIDUtil.STREAM_CODEC` (`MC:net/minecraft/core/UUIDUtil.java:42`)
- `Identifier.STREAM_CODEC` (`MC:net/minecraft/resources/Identifier.java:20`)
- `Vec3.STREAM_CODEC` (`MC:net/minecraft/world/phys/Vec3.java:25`)
- `BlockPos.STREAM_CODEC` (`MC:net/minecraft/core/BlockPos.java:37`)
- `GlobalPos.STREAM_CODEC` (`MC:net/minecraft/core/GlobalPos.java:18`)
- `ResourceKey.streamCodec(registryKey)` (`MC:net/minecraft/resources/ResourceKey.java:24`)

### 1.2 Registering payload types

`FAPI:net/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry.java`:

```java
<T extends CustomPacketPayload> CustomPacketPayload.TypeAndCodec<? super B, T> register(CustomPacketPayload.Type<T> type, StreamCodec<? super B, T> codec); // :46
... registerLarge(Type<T>, StreamCodec<? super B, T>, int maxPacketSize)            // :63  (auto-split large payloads)
... registerLarge(Type<T>, StreamCodec<? super B, T>, IntSupplier maxPacketSizeSupplier) // :84
static PayloadTypeRegistry<FriendlyByteBuf>          serverboundConfiguration()  // :89
static PayloadTypeRegistry<FriendlyByteBuf>          clientboundConfiguration()  // :96
static PayloadTypeRegistry<RegistryFriendlyByteBuf>  serverboundPlay()           // :103
static PayloadTypeRegistry<RegistryFriendlyByteBuf>  clientboundPlay()           // :110
```

Grepping Fabric API for `playS2C|playC2S|configurationS2C` finds nothing, so the old names are gone.

Register in the **common** initializer, on both sides, **before** registering receivers. A second registration of the
same id throws `IllegalArgumentException("Packet type ... is already registered!")`
(`FAPI:net/fabricmc/fabric/impl/networking/PayloadTypeRegistryImpl.java`, `register`).

### 1.3 Sending (server to client)

`FAPI:net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking.java`:

```java
public static void send(ServerPlayer player, CustomPacketPayload payload)                       // :287
public static boolean canSend(ServerPlayer player, Identifier channelName)                      // :198
public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type)            // :211
public static <T extends CustomPacketPayload> Packet<ClientCommonPacketListener> createClientboundPacket(T packet)
public static PacketSender getSender(ServerPlayer player)                                       // :261
public static void reconfigure(ServerPlayer player)        // :300, send player back to CONFIGURATION phase
```

Player lookups in `FAPI:net/fabricmc/fabric/api/networking/v1/PlayerLookup.java`:

| Method | Line | Use |
|---|---|---|
| `all(MinecraftServer)` | :57 | everyone |
| `level(ServerLevel)` | :76 | everyone in a dimension |
| `tracking(ServerLevel, ChunkPos)` | :90 | watchers of a chunk |
| `tracking(Entity)` | :110 | **everyone tracking the ship**. Reads the entity tracker's `seenBy`. A player entity is not guaranteed to be in its own set. |
| `tracking(BlockEntity)` / `tracking(ServerLevel, BlockPos)` | :137 / :155 | launch pad watchers |
| `around(ServerLevel, Vec3, radius)` / `around(ServerLevel, Vec3i, radius)` | :171 / :190 | radius |

`EntityTrackingEvents.START_TRACKING` / `STOP_TRACKING`, with signature `(Entity trackedEntity, ServerPlayer player)`
(`FAPI:.../api/networking/v1/EntityTrackingEvents.java:35/45`), are the hook for sending a full flight snapshot when a
spectator starts seeing a ship.

### 1.4 Receiving

Server (`ServerPlayNetworking`):

```java
public static <T extends CustomPacketPayload> boolean registerGlobalReceiver(CustomPacketPayload.Type<T> type, PlayPayloadHandler<T> handler) // :78
@FunctionalInterface interface PlayPayloadHandler<T> { void receive(T payload, Context context); }  // :344, javadoc :327 "called on the server thread, and can safely manipulate the world"
interface Context { MinecraftServer server(); ServerPlayer player(); PacketSender responseSender(); default PacketContext packetContext() }  // :352, :357
```

Client (`FAPI:net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking.java`):

```java
public static <T extends CustomPacketPayload> boolean registerGlobalReceiver(CustomPacketPayload.Type<T> type, PlayPayloadHandler<T> handler) // :72
public static void send(CustomPacketPayload payload)                                              // :240 (throws if not in game)
interface PlayPayloadHandler<T> { void receive(T payload, Context context); }                      // :280, javadoc :263 "called on the render thread"
interface Context { Minecraft client(); LocalPlayer player(); PacketSender responseSender(); }      // :288, :293
```

Both handlers already run on the main thread, so there is no need to call `execute(...)`.

### 1.5 Connection events, and when they fire

`FAPI:net/fabricmc/fabric/api/networking/v1/ServerPlayConnectionEvents.java`:

- `INIT`, signature `onPlayInit(ServerGamePacketListenerImpl listener, MinecraftServer server)` (:35, :68).
- `JOIN`, signature `onPlayReady(ServerGamePacketListenerImpl listener, PacketSender sender, MinecraftServer server)`
  (:46, :73). It fires from `FAPI:.../mixin/networking/PlayerListMixin.java` *inside* `PlayerList.placeNewPlayer`, at the
  point where `ClientboundPlayerAbilitiesPacket` is constructed. That is **before** `level.addNewPlayer` and **before**
  `ServerPlayer.loadAndSpawnParentVehicle` (see §3.4).
- `DISCONNECT`, signature `onPlayDisconnect(ServerGamePacketListenerImpl listener, MinecraftServer server)` (:57, :78).
  It fires from `ConnectionMixin.handleDisconnection` just before `PacketListener.onDisconnect`, which leads to
  `PlayerList.remove`.

Client: `ClientPlayConnectionEvents.INIT/JOIN/DISCONNECT`, with `(ClientPacketListener, [PacketSender,] Minecraft)`
(`FAPI:.../api/client/networking/v1/ClientPlayConnectionEvents.java:36/48/59`).

### 1.6 Configuration phase (optional)

- `ServerConfigurationConnectionEvents.BEFORE_CONFIGURE` / `CONFIGURE`, with signature
  `onSendConfiguration(ServerConfigurationPacketListenerImpl, MinecraftServer)`
  (`FAPI:.../api/networking/v1/ServerConfigurationConnectionEvents.java:36/59`).
- Tasks: `listener.addTask(ConfigurationTask)` and `listener.completeTask(ConfigurationTask.Type)` are interface-injected
  (`FAPI:.../api/networking/v1/FabricServerConfigurationPacketListenerImpl.java:40/50`).
- Vanilla `ConfigurationTask` is `start(Consumer<Packet<?>>)`, `default boolean tick()` and `Type type()`, where
  `Type(String id)` is a record (`MC:net/minecraft/server/network/ConfigurationTask.java`).
- Sending: `ServerConfigurationNetworking.send(listener, payload)` (`:216`). Receiving:
  `ClientConfigurationNetworking.registerGlobalReceiver(type, handler)`.

**Verdict:** this is not needed for flight profiles. A **synced dynamic registry** (§2.6) delivers datapack JSON to
clients during configuration automatically.

### 1.7 Other built-in sync channels worth using

- **`SynchedEntityData` on the ship entity.** This is the simplest way to carry phase and phase tick to every tracking
  client. API: `SynchedEntityData.defineId(Class, EntityDataSerializer)`, `entityData.get/set`
  (`MC:net/minecraft/network/syncher/SynchedEntityData.java:29/52/56`), and
  `defineSynchedData(SynchedEntityData.Builder)` (`MC:net/minecraft/world/entity/Entity.java:426`). Built-in
  serializers include `INT`, `LONG`, `FLOAT`, `BOOLEAN`, `STRING` and `VECTOR3`
  (`MC:net/minecraft/network/syncher/EntityDataSerializers.java:54-142`). Custom serializers go through
  `FabricEntityDataRegistry.register(Identifier, EntityDataSerializer<?>)`
  (`FAPI:.../api/object/builder/v1/entity/FabricEntityDataRegistry.java`).
- **Data attachments with `syncWith(...)`.** See §3.3.

---

## 2. Datapack resource loading

### 2.1 Mod data folders load automatically

Each mod with a `data/` (or `assets/`) folder becomes its own pack (the pack id is the mod id). It has
`PackSelectionConfig(required, Pack.Position.TOP, ...)` and `shouldAddAutomatically() == true`
(`FAPI:net/fabricmc/fabric/impl/resource/pack/ModResourcePackCreator.java:62, :82`). The ordering comment in that file
(:94-104) reads:

```
Register order rule globally:
1. Default and Vanilla built-in resource packs
2. Mod resource packs
3. Mod built-in resource packs
4. User resource packs
```

Mod packs are ordered among themselves by `ModPackResourcesSorter` (a phase per mod id, sorted with `NodeSorting`,
using mod id as the tie-breaker). Our mod's files override vanilla files that have the same path.

**Evidence:** the current gametest `server.properties` lists
`initial-enabled-packs=vanilla,fabric-convention-tags-v2,fabric-gametest-api-v1`. `redplanet` is missing only because it
has no `data/` folder yet.

### 2.2 Reload listeners: fabric-resource-loader **v1** (v0 is deprecated)

`FAPI:net/fabricmc/fabric/api/resource/v1/ResourceLoader.java`:

```java
PreparableReloadListener.StateKey<HolderLookup.Provider> REGISTRY_LOOKUP_KEY = ...;   // :42 (SERVER_DATA only)
static ResourceLoader get(PackType type)                                              // :50
void registerReloadListener(Identifier id, PreparableReloadListener listener)          // :61
void addListenerOrdering(Identifier first, Identifier second)                         // :76
static boolean registerBuiltinPack(Identifier id, ModContainer container, PackActivationType activationType) // :95
```

`FAPI:net/fabricmc/fabric/api/resource/v1/DataResourceLoader.java`:

```java
PreparableReloadListener.StateKey<DataResourceStore.Mutable> DATA_RESOURCE_STORE_KEY  // :42
static DataResourceLoader get()                                                       // :44
void registerReloadListener(Identifier id, Function<HolderLookup.Provider, PreparableReloadListener> factory) // :63
```

`DataResourceStore` is interface-injected onto `MinecraftServer` (`fabric-resource-loader-v1.classtweaker`:
`transitive-inject-interface net/minecraft/server/MinecraftServer .../DataResourceStore`). It provides
`server.getOrThrow(DataResourceStore.Key<T>)`, and you fill it during the apply phase with
`state.get(DATA_RESOURCE_STORE_KEY).put(key, data)`.

The listener ids for vanilla ordering are in `FAPI:.../api/resource/v1/reloader/ResourceReloaderKeys.java`:
`BEFORE_VANILLA` and `AFTER_VANILLA` (`fabric:` namespace), plus `Server.FUNCTIONS` and others.

v0 `ResourceManagerHelper`, `IdentifiableResourceReloadListener` and `SimpleSynchronousResourceReloadListener` are all
`@Deprecated` (`FAPI:net/fabricmc/fabric/api/resource/ResourceManagerHelper.java:39`, etc.).

### 2.3 Listener base classes in 26.3

- **Vanilla `PreparableReloadListener`** (`MC:net/minecraft/server/packs/resources/PreparableReloadListener.java`) has a
  single method, `CompletableFuture<Void> reload(SharedState currentReload, Executor taskExecutor, PreparationBarrier preparationBarrier, Executor reloadExecutor)`.
  `SharedState` has `resourceManager()` and `get/set(StateKey<T>)`.
- **`SimplePreparableReloadListener<T>`** has `prepare(ResourceManager, ProfilerFiller)` and
  `apply(T, ResourceManager, ProfilerFiller)`.
- **`SimpleJsonResourceReloadListener<T>`** (`MC:.../SimpleJsonResourceReloadListener.java:20-56`) has the
  **protected** constructor `(Codec<T> codec, FileToIdConverter lister)`, which always uses `JsonOps.INSTANCE`. The
  `DynamicOps` constructor is private, so **there is no RegistryOps variant**. `prepare` returns
  `Map<Identifier, T>`, and duplicate IDs throw.
- **Lister:** `FileToIdConverter.json("redplanet/flight_profile")` (`MC:net/minecraft/resources/FileToIdConverter.java:11`)
  lists `data/<ns>/redplanet/flight_profile/**.json`.
- **Fabric `SimpleReloadListener<T>`** (`FAPI:.../api/resource/v1/reloader/SimpleReloadListener.java:43`) has
  `protected abstract T prepare(SharedState state)` (:58) and `protected abstract void apply(T prepared, SharedState state)`
  (:66). Use it when the codec needs registry access:
  `RegistryOps.create(JsonOps.INSTANCE, state.get(ResourceLoader.REGISTRY_LOOKUP_KEY))`.

### 2.4 Sending reloadable data to clients

`ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS` has signature `onSyncDataPackContents(ServerPlayer player, boolean joined)`
(`FAPI:.../api/event/lifecycle/v1/ServerLifecycleEvents.java:84, :160`). It fires inside `placeNewPlayer`, right before
`ClientboundUpdateRecipesPacket` (`joined = true`), and after each successful `/reload` (`joined = false`)
(`FAPI:.../mixin/event/lifecycle/PlayerListMixin.java:33-47`). Send a play payload from here.

### 2.5 Recommended for flight profiles: a synced dynamic registry

`FAPI:net/fabricmc/fabric/api/event/registry/DynamicRegistries.java`:

```java
public static <T> void register(ResourceKey<? extends Registry<T>> key, Codec<T> codec)                                   // :149  (not synced)
public static <T> void registerSynced(ResourceKey<? extends Registry<T>> key, Codec<T> codec, SyncOption... options)       // :171
public static <T> void registerSynced(ResourceKey<? extends Registry<T>> key, Codec<T> serverCodec, Codec<T> clientCodec, SyncOption... options) // :194
public static <T> void registerReloadable(ResourceKey<? extends Registry<T>> key, Codec<T> codec)                          // :212  (NOT synced)
enum SyncOption { SKIP_WHEN_EMPTY }                                                                                         // :225
```

- **File path** (javadoc): `data/<entry namespace>/<registry namespace>/<registry path>/<entry path>.json`, for example
  `data/redplanet/redplanet/flight_profile/earth_to_mars.json`.
- **Registry key:** create it with `ResourceKey.createRegistryKey(Identifier)`
  (`MC:net/minecraft/resources/ResourceKey.java:32`). The Javadoc example's `ResourceKey.create(Identifier)` does not
  exist; only `create(registryKey, id)` does (:28).
- **When to call it:** during mod init, once per key. A duplicate throws
  (`FAPI:.../impl/registry/sync/DynamicRegistriesImpl.java:84-118`).
- **Lifecycle:** this is a *world* registry. It loads at world load, including in `GameTestServer` and in client test
  worlds, and syncs during configuration. It does **not** change on `/reload`, so editing a profile needs a world
  restart. `registerReloadable` reloads but is not synced; for that, use §2.2 plus §2.4.
- **Lookup:** `server.registryAccess().lookupOrThrow(KEY)` returns `Registry<T>`. Then use
  `getValue(ResourceKey)`, `getOptional(Identifier)` or `getValueOrThrow` (`MC:net/minecraft/core/Registry.java:65-81`).
  On the client, use `mc.level.registryAccess()` (or `ClientPacketListener.registryAccess()`).

### 2.6 Built-in optional packs

`ResourceLoader.registerBuiltinPack(id, container, [displayName,] PackActivationType)` loads from
`resourcepacks/<id path>/` inside the jar. This could ship an optional "short flight" datapack that overrides the
profiles.

---

## 3. Persistence

### 3.1 SavedData

```java
public abstract class SavedData { setDirty(); setDirty(boolean); isDirty(); }                     // MC:net/minecraft/world/level/saveddata/SavedData.java
public record SavedDataType<T extends SavedData>(Identifier id, Supplier<T> constructor, Codec<T> codec, DataFixTypes dataFixType) // MC:.../SavedDataType.java:8
```

Vanilla example: `WeatherData.TYPE = new SavedDataType<>(Identifier.withDefaultNamespace("weather"), WeatherData::new, CODEC, DataFixTypes.SAVED_DATA_WEATHER)`
(`MC:net/minecraft/world/level/saveddata/WeatherData.java`).

Storage class: `MC:net/minecraft/world/level/storage/SavedDataStorage.java`. This is the old `DimensionDataStorage`.

```java
public <T extends SavedData> T computeIfAbsent(final SavedDataType<T> type)      // :65
public <T extends SavedData> @Nullable T get(final SavedDataType<T> type)        // :76
public <T extends SavedData> void set(final SavedDataType<T> type, final T data) // :101
private Path getDataFile(Identifier id) { id.withSuffix(".dat").resolveAgainst(dataFolder) }  // :56  -> <folder>/<ns>/<path>.dat
```

- **Global store:** `MinecraftServer.getDataStorage()` (`MC:net/minecraft/server/MinecraftServer.java:2109`) is built
  at :333 from `storageSource.getLevelPath(LevelResource.DATA)`, i.e. `<world>/data/`. In this world folder it already
  holds `data/minecraft/{weather,game_rules,world_clocks,scoreboard,...}.dat`.
- **Per dimension:** `ServerLevel.getDataStorage()` (`MC:net/minecraft/server/level/ServerLevel.java:1586`) delegates to
  `ServerChunkCache`, whose folder is `levelStorage.getDimensionPath(dim).resolve("data")`
  (`MC:.../ServerChunkCache.java:92-100`), i.e. `<world>/dimensions/<ns>/<dim>/data/`.
- **Serialization:** data is written as `{"data": <codec output>, DataVersion}` using `RegistryOps` over `NbtOps`
  (:91, :195-200), so codecs may use holders.
- **`DataFixTypes` may be `null`.** Fabric's `FAPI:.../mixin/object/builder/SavedDataStorageMixin.java` skips datafixing
  for `null`. Fabric itself uses `new SavedDataType<>(AttachmentSavedData.ID, ..., null)`
  (`FAPI:.../mixin/attachment/MinecraftServerMixin.java`).

### 3.2 Saving and loading entities (ValueOutput / ValueInput)

`MC:net/minecraft/world/entity/Entity.java`:

```java
protected abstract void readAdditionalSaveData(ValueInput input);   // :2268
protected abstract void addAdditionalSaveData(ValueOutput output);  // :2270
public boolean saveAsPassenger(ValueOutput)                          // :2090 (needs type.canSerialize())
public boolean save(ValueOutput)        // :2105 -> false if isPassenger()
public void saveWithoutId(ValueOutput)  // :2109 (writes Pos, Motion, UUID..., addAdditionalSaveData, then "Passengers" list)
public void load(ValueInput)            // :2187
```

`MC:net/minecraft/world/level/storage/ValueOutput.java`:

- `store(String, Codec<T>, T)`, `storeNullable(...)`
- `putBoolean`, `putByte`, `putShort`, `putInt`, `putLong`, `putFloat`, `putDouble`, `putString`, `putIntArray`
- `child(String)`, `childrenList(String)` with `ValueOutputList.addChild()`
- `list(String, Codec<T>)` with `TypedOutputList.add(T)`
- `discard(String)`, `isEmpty()`

`MC:.../ValueInput.java`:

- `read(String, Codec<T>)` returns `Optional<T>`
- `child(String)` returns `Optional<ValueInput>`; `childOrEmpty`
- `childrenList` / `childrenListOrEmpty`; `list` / `listOrEmpty(String, Codec<T>)` (iterable)
- `getBooleanOr`, `getIntOr`, `getLongOr`, `getFloatOr`, `getDoubleOr`, `getStringOr`, `getInt` / `getLong` / `getString` (Optional)

Fabric adds `putLongArray`/`putByteArray` and `keySet()`/`contains()`/`getOptionalLongArray`
(`FAPI:.../api/serialization/v1/value/FabricValue{Output,Input}.java`).

For a manual round trip in tests:

- `TagValueOutput.createWithContext(ProblemReporter, HolderLookup.Provider)` (`MC:.../TagValueOutput.java:27`), then
  `.buildResult()` (:152)
- `TagValueInput.create(ProblemReporter, HolderLookup.Provider, CompoundTag)` (`MC:.../TagValueInput.java:40`)
- `ProblemReporter.DISCARDING` (`MC:net/minecraft/util/ProblemReporter.java:19`)

Gotchas for the ship entity:

- **Velocity is clamped on load.** `load` zeroes any motion component with `|v| > 10` blocks per tick (`Entity.java:2192`).
  Store the true flight velocity in our own field.
- **Riding requires a serializable vehicle type.** `startRiding` rejects vehicles whose type cannot serialize
  (`!entityToRide.type.canSerialize()`, `Entity.java:2487`). The Starship `EntityType` must not use `.noSave()`.
- **Keep the server authoritative.** `isClientAuthoritative()` is `true` whenever `getControllingPassenger()` is a
  client-authoritative player (`Entity.java:3704`). Movement is then simulated only on the authoritative side
  (`canSimulateMovement`, :3713). Keep `getControllingPassenger()` returning `null`, which is the default at :3616.
- **Interaction signature changed.** It is now `interact(Player, InteractionHand, Vec3 location)` (`Entity.java:2317`).

### 3.3 Fabric Data Attachment API (fabric-data-attachment-api-v1 2.2.30)

`FAPI:net/fabricmc/fabric/api/attachment/v1/AttachmentRegistry.java`:

```java
public static <A> AttachmentType<A> create(Identifier id, Consumer<Builder<A>> consumer)   // :56
public static <A> AttachmentType<A> createDefaulted(Identifier id, Supplier<A> initializer) // :84
public static <A> AttachmentType<A> createPersistent(Identifier id, Codec<A> codec)         // :96
interface Builder<A> {
  Builder<A> persistent(Codec<A> codec);                                                    // :126
  Builder<A> copyOnDeath();                                                                 // :133
  Builder<A> initializer(Supplier<A> initializer);                                          // :150
  Builder<A> syncWith(StreamCodec<? super RegistryFriendlyByteBuf, A> codec, AttachmentSyncPredicate p);            // :159
  Builder<A> syncWith(StreamCodec<? super RegistryFriendlyByteBuf, A> codec, AttachmentSyncPredicate p, int maxSyncSize); // :171
  AttachmentType<A> buildAndRegister(Identifier id);
}
```

`AttachmentSyncPredicate` extends `BiPredicate<AttachmentTarget, ServerPlayer>`. It provides `all()` (:37),
`targetOnly()` (:45) and `allButTarget()` (:53).

**`AttachmentTarget`** (`FAPI:.../AttachmentTarget.java`) is implemented on `Entity`, `BlockEntity`, `ServerLevel`,
`ChunkAccess` and `GlobalAttachments`. Its methods:

- `getAttached` (:81), `getAttachedOrThrow`, `getAttachedOrSet`, `getAttachedOrCreate(type[, init])` (:146)
- `getAttachedOrElse`, `setAttached(type, value)` (:197), `hasAttached`, `removeAttached`
- `modifyAttached(type, UnaryOperator)` (:248), `onAttachedSet(type)`
- The NBT key is `"fabric:attachments"` (:71).

**Global attachments (new)** are `server.globalAttachments()` / `level.globalAttachments()`
(`GlobalAttachmentsProvider.java:25`). They persist in the global SavedData `fabric:attachments`, i.e.
`<world>/data/fabric/attachments.dat`, and sync to every play-phase connection that passes the predicate
(`FAPI:.../impl/attachment/GlobalAttachmentsImpl.java:40-54`).

How persistence and sync are wired:

- **Entities:** attachments are written in `saveWithoutId` and read in `load`
  (`FAPI:.../mixin/attachment/EntityMixin.java:53, :61`). They therefore travel with the RootVehicle data (§3.4) and
  with `restoreFrom`.
- **Sync targets:** entity attachments sync to the player itself (if it is a player and the predicate allows) and to
  `PlayerLookup.tracking(entity)` (`EntityMixin.java:72-86`). Level attachments sync to `PlayerLookup.level(level)`
  (`ServerLevelMixin.java:79-88`).
- **Transfer to new entity instances:** `AttachmentTargetImpl.transfer(original, target, isDeath)` copies **all**
  attachments, or only the `copyOnDeath()` ones when `isDeath` (`FAPI:.../impl/attachment/AttachmentTargetImpl.java:43`).
  It is called on `ServerPlayerEvents.AFTER_RESPAWN` (`isDeath = !alive`) and on
  `ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL` (`isDeath = false`), so a ship's attachments survive a
  cross-dimension teleport (`FAPI:.../impl/attachment/AttachmentEntrypoint.java:32-36`). A `ServerPlayer` changing
  dimension keeps the same instance.
- **Immutability:** `setAttached` triggers the sync. Mutating a stored object in place does **not** sync, so use
  immutable records and `modifyAttached`.

### 3.4 How vanilla persists the player's vehicle (logout, login, crash)

`MC:net/minecraft/server/level/ServerPlayer.java`:

```java
private void saveParentVehicle(final ValueOutput playerOutput) {                         // :439
	Entity rootVehicle = this.getRootVehicle(); Entity vehicle = this.getVehicle();
	if (vehicle != null && rootVehicle != this && rootVehicle.hasExactlyOnePlayerPassenger()) {
		ValueOutput vehicleWrapper = playerOutput.child("RootVehicle");
		vehicleWrapper.store("Attach", UUIDUtil.CODEC, vehicle.getUUID());
		rootVehicle.save(vehicleWrapper.child("Entity"));
	}
}
public void loadAndSpawnParentVehicle(final ValueInput playerInput) {                     // :449
	... Entity vehicle = EntityType.loadEntityRecursive(root.childOrEmpty("Entity"), serverLevel, EntitySpawnReason.LOAD,
	        e -> !serverLevel.addWithUUID(e) ? null : e);
	... startRiding(vehicle or matching indirect passenger, true, false);
	if (!this.isPassenger()) { LOGGER.warn("Couldn't reattach entity to player"); vehicle.discard(); ... }   // :470
}
```

Logout (`MC:net/minecraft/server/players/PlayerList.java:301` `remove`):

1. `save(player)` runs first, which writes `RootVehicle`.
2. If `vehicle.hasExactlyOnePlayerPassenger()` (:307), the player is dismounted and the whole vehicle tree is
   `setRemoved(UNLOADED_WITH_PLAYER)` (:310).

Chunk saving: `Entity.shouldBeSaved()` returns `!isVehicle() || !hasExactlyOnePlayerPassenger()` (`Entity.java:4080`).
**A ship carrying exactly one player is never written to chunk storage.** It exists only inside the player file.

Login (`MC:net/minecraft/server/network/config/PrepareSpawnTask.java`):

1. The player data is loaded.
2. `placeNewPlayer(...)` runs (:181). Both Fabric JOIN events fire inside or at the end of this call.
3. **Then** `player.loadAndSpawnEnderPearls(tag)` and `player.loadAndSpawnParentVehicle(tag)` run (:183-184).

So at `ServerPlayConnectionEvents.JOIN` and at `ServerPlayerEvents.JOIN` (`placeNewPlayer` RETURN,
`FAPI:.../mixin/entity/event/PlayerListMixin.java:46`) the player is **not yet riding**. Defer any re-seat logic by
one tick.

Edge cases:

| Situation | Vanilla behavior |
|---|---|
| Ship plus 1 player, clean logout | Ship is stored in the player file. On login it respawns in the player's saved level with the same UUID and is re-mounted. Flight state inside the entity data, and persistent attachments, come back. The flight is **frozen** while the player is offline. |
| Ship plus 2 or more players, one logs out | Ship stays in the world and keeps flying with the others. The leaving player is saved at their position. **On login they are not re-mounted** (no RootVehicle) and may appear mid-air. The mod must re-seat or recover them. |
| Server crash with ship plus 1 player | Only the last autosave of the player file is restored, with RootVehicle as of that save. That is a consistent rollback to the last autosave. **UNVERIFIED:** exact autosave interval. |
| Server crash with ship plus 2 or more players | The ship comes back from chunk storage, if its chunk was saved. Players come back unseated. |
| `addWithUUID` fails because an entity with that UUID already exists, or the entity type is unknown | `vehicle == null`. Nothing is spawned or re-mounted and there is no warning. |
| Saved dimension no longer exists | `loadedPosition.dimension().map(server::getLevel)` is empty, so the player falls back to the respawn or overworld level **but keeps the saved coordinates** (`PrepareSpawnTask.java:55-61`). The vehicle spawns in that fallback level. |
| Vehicle "in another dimension" | Cannot happen. Passengers always share the vehicle's level, because cross-dimension teleport moves the passengers first and then re-mounts them (§7.6). |

### 3.5 Design consequences for "survive logout or crash mid-flight"

- Keep the full state machine (phase, phase tick, profile id, direction, true velocity, crew UUIDs, mission id) **in the
  ship entity's saved data**. Vanilla then preserves it in both the one-player case (player file) and the multi-player
  case (chunks).
- Mirror a small "mission" record per player in a **persistent player attachment**. It holds the active ship UUID,
  progress and last known phase. On `ServerPlayerEvents.JOIN`, schedule a check one tick later:
  - If the player has an active mission but `getVehicle()` is not that ship, look the ship up with
    `level.getEntity(UUID)` (`MC:net/minecraft/world/level/Level.java:857`).
  - Re-seat the player if the ship is found. Otherwise roll back to the pad, or teleport to the landing site.
- Use a server-global `SavedData` mission registry, keyed by ship UUID, as the source of truth for "which ships are
  mid-flight". It covers ships whose chunk is unloaded or whose only crew member is offline.
- Make the interlude client-side only. At a phase boundary the server teleports the ship cross-dimension atomically, so
  persisted state is always in exactly one dimension.

---

## 4. Events (exact names in this version)

| Need | Class.field | Callback signature | Source |
|---|---|---|---|
| Server lifecycle | `ServerLifecycleEvents.SERVER_STARTING / SERVER_STARTED / SERVER_STOPPING / SERVER_STOPPED` | `(MinecraftServer)` | `FAPI:.../api/event/lifecycle/v1/ServerLifecycleEvents.java:36/47/61/74` |
| Datapack reload | `START_DATA_PACK_RELOAD`, `END_DATA_PACK_RELOAD`, `SYNC_DATA_PACK_CONTENTS` | `(server, CloseableResourceManager)`, `(server, rm, boolean success)`, `(ServerPlayer, boolean joined)` | same file :93/104/84 |
| Saving | `BEFORE_SAVE`, `AFTER_SAVE` | `(server, boolean flush, boolean force)` | :113/122 |
| Server tick | `ServerTickEvents.START_SERVER_TICK / END_SERVER_TICK` | `onStartTick/onEndTick(MinecraftServer)` | `.../ServerTickEvents.java:42/53` |
| Level tick (**renamed from WORLD**) | `ServerTickEvents.START_LEVEL_TICK / END_LEVEL_TICK` | `(ServerLevel)` | :64/77 |
| Level load | `ServerLevelEvents.LOAD / UNLOAD` | `onLevelLoad(MinecraftServer, ServerLevel)` | `.../ServerLevelEvents.java:32/44` |
| Entity load and unload | `ServerEntityEvents.ENTITY_LOAD / ENTITY_UNLOAD / ALLOW_LOAD / EQUIPMENT_CHANGE` | `(Entity, ServerLevel)`; `ALLOW_LOAD: (entity, level, @Nullable EntitySpawnReason, boolean isLoadedFromDisk)` | `.../ServerEntityEvents.java:43/67/52/79` |
| Player join and leave (network) | `ServerPlayConnectionEvents.INIT / JOIN / DISCONNECT` | §1.5 | §1.5 |
| Player join and leave (entity) | `ServerPlayerEvents.JOIN / LEAVE` | `onJoin(ServerPlayer)`: RETURN of `placeNewPlayer`, before vehicle restore. `onLeave(ServerPlayer)`: HEAD of `PlayerList.remove`, before save. | `FAPI:.../api/entity/event/v1/ServerPlayerEvents.java:57/69`; hooks `.../mixin/entity/event/PlayerListMixin.java:46/51` |
| Respawn | `ServerPlayerEvents.AFTER_RESPAWN`, `COPY_FROM` | `afterRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive)` | :45/34 |
| Dimension change (**renamed** from `ServerEntityWorldChangeEvents`) | `ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL`, `AFTER_PLAYER_CHANGE_LEVEL` | `(Entity originalEntity, Entity newEntity, ServerLevel origin, ServerLevel destination)`; `(ServerPlayer, origin, destination)` | `.../ServerEntityLevelChangeEvents.java:42/59` |
| Tracking | `EntityTrackingEvents.START_TRACKING / STOP_TRACKING` | `(Entity tracked, ServerPlayer player)` | §1.3 |
| Use block, item or entity | `UseBlockCallback.EVENT`, `UseItemCallback.EVENT`, `UseEntityCallback.EVENT`, `AttackEntityCallback.EVENT`, `AttackBlockCallback.EVENT` | `InteractionResult interact(Player, Level, InteractionHand, BlockHitResult)`; `(Player, Level, InteractionHand)`; `(Player, Level, InteractionHand, Entity, EntityHitResult)`; `(..., Entity, @Nullable EntityHitResult)`; `(..., BlockPos, Direction)` | `FAPI:.../api/event/player/*.java` (EVENT at :38/:37/:48/:41/:48) |
| New finer hooks | `BlockEvents.USE_ITEM_ON / USE_WITHOUT_ITEM`, `ItemEvents.USE_ON / USE` | wrap `BlockState#useItemOn` / `useWithoutItem`, `Item#useOn` / `use` | `BlockEvents.java:40/57`, `ItemEvents.java:39/56` |
| Block break | `PlayerBlockBreakEvents.BEFORE / AFTER / CANCELED` | `boolean beforeBlockBreak(Level, Player, BlockPos, BlockState, @Nullable BlockEntity)`; `afterBlockBreak(...)`; `onBlockBreakCanceled(...)` | `.../PlayerBlockBreakEvents.java:44/64/77` |
| Client | `ClientTickEvents.START/END_CLIENT_TICK`, `START/END_LEVEL_TICK`; `ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE`; `ClientEntityEvents.ENTITY_LOAD/UNLOAD` | (see files) | `FAPI:.../api/client/event/lifecycle/v1/*` |

`InteractionResult` constants are `SUCCESS`, `SUCCESS_SERVER`, `CONSUME`, `FAIL`, `PASS` and `TRY_WITH_EMPTY_HAND`
(`MC:net/minecraft/world/InteractionResult.java:11-16`).

---

## 5. Commands and permissions

`FAPI:net/fabricmc/fabric/api/command/v2/CommandRegistrationCallback.java`:

```java
Event<CommandRegistrationCallback> EVENT = ...;                                                         // :41
void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext, Commands.CommandSelection selection); // :54
```

**Builders** (`MC:net/minecraft/commands/Commands.java`): `literal(String)` (:443) and `argument(String, ArgumentType<T>)` (:447).

**Permissions** (26.3 has a permissions system, not integer levels):

- Predicates: `Commands.LEVEL_ALL / LEVEL_MODERATORS / LEVEL_GAMEMASTERS / LEVEL_ADMINS / LEVEL_OWNERS` are
  `PermissionCheck` values (`Commands.java:165-169`). Turn one into a predicate with
  `Commands.hasPermission(PermissionCheck)`, which returns `PermissionProviderCheck<T>` (:543). Vanilla uses this as
  `.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))` (`MC:.../server/commands/TeleportCommand.java:50`).
- Direct check: `source.permissions()` returns a `PermissionSet` (`CommandSourceStack.java:387`). Call
  `.hasPermission(Permission)` on it, for example with `Permissions.COMMANDS_GAMEMASTER`
  (`MC:net/minecraft/server/permissions/Permissions.java:7`). `PermissionLevel` has `ALL`, `MODERATORS`,
  `GAMEMASTERS`, `ADMINS` and `OWNERS`.
- Fabric: `PermissionPredicates.require(Identifier node, PermissionLevel fallback)`
  (`FAPI:.../api/permission/v1/PermissionPredicates.java:77`). `PermissionContextOwner` is interface-injected onto
  `CommandSourceStack` and `Entity` (`fabric-permission-api-v1.classtweaker`), so permission mods can grant
  `redplanet:command.launch` and similar nodes.

**Feedback** (`MC:net/minecraft/commands/CommandSourceStack.java`):

- `sendSuccess(Supplier<Component> messageSupplier, boolean broadcast)` (:474)
- `sendFailure(Component)` (:505), `sendSystemMessage(Component)` (:463)
- `getPlayerOrException()` (:411), `getPlayer()` (:419), `getLevel()` (:395), `getServer()` (:431)

**Argument types:**

| Argument | Constructor | Getter |
|---|---|---|
| Dimension | `DimensionArgument.dimension()` | `DimensionArgument.getDimension(ctx, name)` returns `ServerLevel` (`MC:.../commands/arguments/DimensionArgument.java:47/51`) |
| Entity | `EntityArgument.entity()/entities()/player()/players()` | `getEntity`, `getEntities`, `getPlayer` (`EntityArgument.java:46-83`) |
| Coordinates | `Vec3Argument.vec3()`, `BlockPosArgument.blockPos()` | `Vec3Argument.getVec3`; `BlockPosArgument.getLoadedBlockPos / getBlockPos` (`.../coordinates/*.java`) |
| Registry entries (e.g. flight profiles) | `ResourceKeyArgument.key(registryKey)`, `ResourceArgument.resource(buildContext, registryKey)` | `getResource(...)` returns `Holder.Reference<T>`; `IdentifierArgument.id()` |

Debug teleport command skeleton (written against the signatures above, not compiled):

```java
CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> dispatcher.register(
		Commands.literal("redplanet")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))   // or PermissionPredicates.require(RedPlanet.id("command"), PermissionLevel.GAMEMASTERS)
				.then(Commands.literal("tp")
						.then(Commands.argument("dimension", DimensionArgument.dimension())
								.then(Commands.argument("pos", Vec3Argument.vec3())
										.executes(ctx -> {
											ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
											Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
											ServerPlayer p = ctx.getSource().getPlayerOrException();
											p.teleport(new TeleportTransition(level, pos, Vec3.ZERO, p.getYRot(), p.getXRot(), TeleportTransition.DO_NOTHING));
											ctx.getSource().sendSuccess(() -> Component.literal("Teleported to " + level.dimension().identifier()), true);
											return 1;
										}))))));
```

---

## 6. Game rules (fabric-game-rule-api-v1 4.0.10)

Vanilla, in `MC:net/minecraft/world/level/gamerules/`:

- `GameRule<T>` is registered in `BuiltInRegistries.GAME_RULE`.
- `GameRules.get(GameRule<T>)` (:120) and `set(GameRule<T>, T, @Nullable MinecraftServer)` (:129).
- Categories are records: `GameRuleCategory.register(Identifier)` is **public** (`GameRuleCategory.java:28`). Its lang
  key is `gamerule.category.<ns>.<path>`. A rule's lang key is `Util.makeDescriptionId("gamerule", id)`, i.e.
  `gamerule.redplanet.<path>` (`GameRule.java:63`).
- Rules are **global**: `ServerLevel.getGameRules()` returns `this.server.getGameRules()` (`ServerLevel.java:1941`), and
  they persist in global SavedData `minecraft:game_rules`.

Fabric `FAPI:net/fabricmc/fabric/api/gamerule/v1/GameRuleBuilder.java`:

```java
public static BooleanRuleBuilder forBoolean(boolean defaultValue)    // :81
public static IntegerRuleBuilder forInteger(int defaultValue)        // :85   .range(min,max) :269 / .minValue(min)
public static DoubleRuleBuilder forDouble(double defaultValue)       // :89
public static <E extends Enum<E>> EnumRuleBuilder<E> forEnum(E defaultValue) // :93
... .category(GameRuleCategory) ... public GameRule<T> buildAndRegister(Identifier id)   // :157
```

`GameRuleEvents.changeCallback(GameRule<T>)` returns an `Event<ValueUpdate<T>>` with
`onGameRuleUpdated(T value, MinecraftServer server)` (`GameRuleEvents.java:32/48`).

**Reading:** `server.getGameRules().get(RULE)` or `level.getGameRules().get(RULE)`.

**Clients do not receive game rules automatically.** `ClientboundGameRuleValuesPacket` is sent only on request, to
gamemasters (`MC:.../server/network/ServerGamePacketListenerImpl.java:1955-1965`). Sync client-relevant rules yourself,
for example with a global attachment using `syncWith(..., all())` or a payload, triggered from `changeCallback`.

Test environments can set rules:
`{"type":"minecraft:game_rules","rules":{"redplanet:flight_time_scale":20}}`. The `rules` codec is
`GameRuleMap.CODEC`, a dispatched map over `GAME_RULE.byNameCodec()` (`GameRuleMap.java:19`).

Skeleton (written against the signatures above, not compiled):

```java
public final class RedPlanetGameRules {
	public static final GameRuleCategory CATEGORY = GameRuleCategory.register(RedPlanet.id("red_planet")); // lang: gamerule.category.redplanet.red_planet
	public static final GameRule<Boolean> MARS_PHYSICS =
			GameRuleBuilder.forBoolean(true).category(CATEGORY).buildAndRegister(RedPlanet.id("mars_physics"));   // /gamerule redplanet:mars_physics
	public static final GameRule<Integer> FLIGHT_TIME_SCALE =
			GameRuleBuilder.forInteger(1).range(1, 100).category(CATEGORY).buildAndRegister(RedPlanet.id("flight_time_scale"));

	public static void init() {   // call from RedPlanet#onInitialize, which also forces class init
		GameRuleEvents.changeCallback(FLIGHT_TIME_SCALE).register((value, server) -> RedPlanetSync.pushRules(server));
	}
}
// read: server.getGameRules().get(RedPlanetGameRules.MARS_PHYSICS)   (global; level.getGameRules() returns the same object)
```

---

## 7. Server gametests

### 7.1 Data model (vanilla)

- **Registries:** `minecraft:test_instance` (`GameTestInstance`), `minecraft:test_environment`
  (`TestEnvironmentDefinition<?>`), and the static `BuiltInRegistries.TEST_FUNCTION` (`Consumer<GameTestHelper>`).
- **Vanilla data:**
  - `DATA:test_environment/default.json` is `{"type":"minecraft:all_of","definitions":[]}`.
  - `DATA:test_instance/always_pass.json` is
    `{"type":"minecraft:function","environment":"minecraft:default","function":"minecraft:always_pass","max_ticks":1,"required":false,"setup_ticks":1,"structure":"minecraft:empty"}`.
- **Instance types:** `minecraft:block_based` and `minecraft:function` (`GameTestInstance.java` `bootstrap`).
  `FunctionGameTestInstance(ResourceKey<Consumer<GameTestHelper>> function, TestData<Holder<TestEnvironmentDefinition<?>>> info)`
  looks up the function in the registry at run time.

`MC:net/minecraft/gametest/framework/TestData.java:15-45`:

```java
public record TestData<EnvironmentType>(EnvironmentType environment, ResourceKey<Level> dimension, Identifier structure,
	int maxTicks, int setupTicks, boolean required, Rotation rotation, boolean manualOnly, int maxAttempts,
	int requiredSuccesses, boolean skyAccess, int padding)
// JSON: environment (req), dimension (opt, default minecraft:overworld), structure (req), max_ticks (req, >0),
// setup_ticks (0), required (true), rotation (none), manual_only (false), max_attempts (1),
// required_successes (1), sky_access (false), padding (0..128, default 0)
```

**Environment types.** `TestEnvironmentDefinition.bootstrap` (`TestEnvironmentDefinition.java:40-48`) registers:

| Type | Fields |
|---|---|
| `all_of` | `definitions: [env...]` |
| `clock_time` | `clock: <world_clock>`, `time: int`. Sets **server** clock total ticks. |
| `difficulty` | `difficulty` |
| `function` | `setup` and `teardown` function ids, run as GAMEMASTER in the batch level |
| `game_rules` | `rules: {id: value}` |
| `timeline_attributes` | `timelines: [...]` |
| `weather` | `weather: clear / rain / thunder` |

**There is no "dimension" environment.** The dimension comes from `TestData.dimension`. Every environment is
`setup(ServerLevel)` / `teardown(ServerLevel, saved)`, and is activated once per batch against
`server.getLevel(batch.dimension())` (`GameTestRunner.java:109`).

### 7.2 Fabric API (fabric-gametest-api-v1 4.0.32)

The annotation is `net.fabricmc.fabric.api.gametest.v1.GameTest`
(`FAPI:net/fabricmc/fabric/api/gametest/v1/GameTest.java:34-99`):

| Field | Default |
|---|---|
| `environment` | `"minecraft:default"` |
| `dimension` | `"minecraft:overworld"` |
| `structure` | `"fabric-gametest-api-v1:empty"` (an 8×8×8 air structure, `FAPI:data/fabric-gametest-api-v1/gametest/structure/empty.snbt`) |
| `maxTicks` | `20` |
| `setupTicks` | `0` |
| `required` | `true` |
| `rotation` | `Rotation.NONE` |
| `manualOnly` | `false` |
| `maxAttempts` | `1` |
| `requiredSuccesses` | `1` |
| `skyAccess` | `false` |
| `padding` | `1` |

- **Discovery.** The entrypoint key is `"fabric-gametest"` (`FAPI:.../impl/gametest/TestAnnotationLocator.java:47`).
  Every listed class is instantiated. All `@GameTest` methods in it and in its superclasses are found.
- **Method contract.** Validated at :101: `public`, not `static`, returns `void`, and takes exactly one
  `GameTestHelper`. Validation is skipped if the class implements `CustomTestMethodInvoker`.
- **Test id.** `<providing mod id>:<snake_case(SimpleClassName + "_" + methodName)>` (:129-130). For example
  `FlightGameTests.countdownReachesLiftoff` becomes `redplanet-gametest:flight_game_tests_countdown_reaches_liftoff`.
- **Registration:**
  - `TEST_FUNCTION` entries are registered in mod init when `-Dfabric-api.gametest` is set **or in any dev environment**
    (`FabricGameTestModInitializer.java:44-51`).
  - `TEST_INSTANCE` entries are injected whenever dynamic registries load (`FAPI:.../mixin/gametest/RegistryDataLoaderMixin.java`
    calls `FabricGameTestModInitializer.registerDynamicEntries`, `FabricGameTestModInitializer.java:55-69`). This means
    `/test run redplanet-gametest:*` also works in a dev client.
- **`CustomTestMethodInvoker`** has `void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException`
  (`CustomTestMethodInvoker.java:36`). Use it for per-test setup and teardown.
- **Fabric mixins:**
  - `GameTestServerMixin` makes `isDedicatedServer()` return `true`, so dedicated-only commands register.
  - `server/MainMixin` skips the EULA, starts the test server instead of a dedicated server, and calls
    `System.exit(-1)` if startup fails.
  - `StructureTemplateManagerMixin` loads `data/<ns>/gametest/structure/<path>.snbt` (`FabricGameTestRunner.java:38-40`).

### 7.3 GameTestHelper API (`MC:net/minecraft/gametest/framework/GameTestHelper.java`)

- **Level and blocks:** `getLevel(): ServerLevel` (:105), `getBlockState`, `setBlock(...)` (:450-470),
  `destroyBlock`, `placeAt`, `useBlock`, `pressButton`, `pullLever`, `randomTick`, `tickBlock`, `tickPrecipitation`,
  `getHeight` (:1007), `setBiome(ResourceKey<Biome>)` (:1156), `setTime(long)` (:970).
  - `setTime` calls `level.dimensionType().defaultClock().orElseThrow()`, so it throws for a dimension type without a
    default clock.
- **Coordinates:** everything is relative to the structure. `absolutePos`, `relativePos`, `absoluteVec`, `relativeVec`
  (:1040-1072), `getBounds`, `getBoundsWithPadding`, `getRelativeBounds` (:1129-1145).
- **Spawning:**
  - `spawn(EntityType, BlockPos|Vec3|x,y,z[, EntitySpawnReason])` (:159-272)
  - `spawnEntity(...)` returns a `GameTestEntityBuilder` with `.spawnReason()`, `.rotation()`,
    `.requirePersistence()` and `.spawn()`. It creates the entity with `EntitySpawnReason.STRUCTURE` and calls
    `addFreshEntityWithPassengers` (`GameTestEntityBuilder.java:44-66`).
  - `spawnMob` returns a `GameTestMobBuilder` with `.withNoFreeWill()`. There is also `spawnWithNoFreeWill` (:274),
    `spawnItem` (:134), `kill`, `discard`, `hurt`, `killAllEntities[OfClass]`.
- **Finding:** `findOneEntity`, `findClosestEntity`, `findEntities`, `getEntities` (:231-264, :655).
- **Assertions:**
  - `assertTrue` / `assertFalse(boolean, String|Component)` (:1087-1123)
  - `assertValueEqual(N value, N expected, String name)` (:1097), `assertValueInBetween(lo, v, hi, name)` (:1107)
  - `assertEntityPresent` / `NotPresent` (:601-752), `assertEntitiesPresent`, `assertEntityInstancePresent`,
    `assertEntityData`, `assertEntityProperty`
  - `assertItemEntity*`, `assertBlock*`, `assertContainer*`, `assertLivingEntityHasMobEffect`
- **Completion and scheduling:**
  - `succeed()` (:931), `succeedIf(Runnable)` (:943), `succeedWhen(Runnable)` (:948), `succeedOnTickWhen(int, Runnable)` (:953)
  - `runAtTickTime(long, Runnable)` (:958), `runBeforeTestEnd` (:962), `runAfterDelay(long, Runnable)` (:966)
  - `onEachTick(Runnable)` (:1152), `fail(String|Component[, BlockPos|Entity])` (:1012-1026), `failIf` (:1028),
    `failIfEver` (:1032), `getTick()` (:1125)
  - `startSequence()` returns a `GameTestSequence` with `thenWaitUntil`, `thenIdle`, `thenExecute`,
    `thenExecuteAfter`, `thenExecuteFor`, `thenSucceed` and `thenFail` (`GameTestSequence.java:19-68`).
  - Only one final clause is allowed (`ensureSingleFinalCheck`).
- **Mock players:**
  - `makeMockPlayer(GameType)` (:358) and `makeMockServerPlayer(GameType)` (:372) are **not** added to the level, and
    `isClientAuthoritative()` returns `false` for them.
  - `makeMockServerPlayerInLevel()` (:395, `@Deprecated(forRemoval = true)`) puts a real `ServerPlayer` through
    `PlayerList.placeNewPlayer` over an `EmbeddedChannel`.
  - `makeAboutToDrown(LivingEntity)` (:347) and `withLowHealth`.

### 7.4 Execution model

- **Server construction.** `GameTestServer.create` (`GameTestServer.java:95-146`):
  - Enables every available pack, with vanilla first.
  - Enables all feature flags except redstone experiments and minecart improvements (:83-85).
  - World is CREATIVE, from preset `FLAT_ALL_DIMENSIONS`, baked against an **empty** `LEVEL_STEM` registry (:119-125).
  - `getMaxPlayers() == 1`.
- **Placement.** All tests start at one random position, `(±14,999,992, y=4, ±14,999,992)` (:316-321). A
  `StructureGridSpawner(…, 8 per row, false)` keeps a grid **per dimension**, with a 5-block column gap and a 6-block
  row gap (`StructureGridSpawner.java:17-34`).
- **Batching.**
  - Tests are grouped by `(environment, dimension)` and partitioned into batches of 50
    (`GameTestBatchFactory.java:24-41`).
  - **All tests in a batch tick concurrently** (`GameTestRunner.java:149`), and batches run one after another.
  - Test lists are filtered with `!manualOnly()` (:190, :207). `required = false` tests still run.
- **Structure setup.**
  - The test bounding box is cleared to air, or stone below the floor (`StructureUtils.java:70-78, 121-132`).
  - The structure is placed and `encaseStructure()` puts barriers on the sides and floor, plus a ceiling unless
    `skyAccess` (`TestInstanceBlockEntity.java:359-392`).
  - The structure's chunks are force-loaded (`TestInstanceBlockEntity.java:340-343`). The runner un-forces them only
    in **that test's level** when the batch ends (`GameTestRunner.java:117-118`).
- **Timing.**
  - The test waits until the structure chunks are entity-ticking (`GameTestInfo.java:97`).
  - It starts after `setupTicks`.
  - It fails with "timeout.no_result" once `tick > maxTicks` if nothing called `succeed` (:139-147).
  - Exceptions in the test function become failures (:161).
- **Success cleanup.** `succeed()` discards all **non-player** entities inside the bounds plus one block (:242-249).
  Entities you moved to another dimension, and mock players, are your responsibility.
- **Speed.** The server tick loop is unthrottled: `waitUntilNextTick()` just runs pending tasks
  (`GameTestServer.java:291-294`). A 2,000-tick flight takes seconds, not 100 s.

### 7.5 Other dimensions

**Running a test inside Mars:** use `@GameTest(dimension = "redplanet:mars")`. The structure is placed in Mars, and
`helper.getLevel()` returns the Mars `ServerLevel`. This requires the level to exist; see the next point.

**Problem: Mars does not exist in GameTestServer.**

- `Registry<LevelStem> noDatapackDimensions = new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.stable()).freeze();`
  and then `worldDimensions.bake(noDatapackDimensions)` (`GameTestServer.java:119-125`). Compare the dedicated server
  (`MC:net/minecraft/server/Main.java:172`) and the client world creation (`MCC:.../CreateWorldScreen.java:292`),
  which pass `context.datapackDimensions()`.
- The result is that `server.getLevel(MARS)` returns `null`, and a Mars-dimension test throws
  `IllegalStateException("Missing level for dimension: redplanet:mars")` in `GameTestBatchFactory.java:33-35`.
- Fabric does not change this. `fabric-dimensions-v1` only adds `DimensionEvents.MODIFY_ATTRIBUTES` and fail-soft
  codecs (`FAPI:.../mixin/dimension/WorldDimensionsMixin.java`).

**Datapack-only fix.**

- The preset's dimensions are an arbitrary `Map<ResourceKey<LevelStem>, LevelStem>`
  (`MC:.../levelgen/presets/WorldPreset.java:20-43`). `bake` keeps keys outside the vanilla three (`keysInOrder`,
  `WorldDimensions.java:55-56, 152-173`), and `createLevels` makes one `ServerLevel` per stem
  (`MinecraftServer.java:420-470`).
- So ship `src/gametest/resources/data/minecraft/worldgen/world_preset/flat_all_dimensions.json`. Copy the vanilla file
  (`DATA:worldgen/world_preset/flat_all_dimensions.json`, which has overworld as a desert flat world of 1 bedrock plus
  67 sandstone, and end and nether as flat worlds) and add a `"redplanet:mars"` stem that uses our dimension type with a
  flat generator (skeleton in §10.7).
- The gametest mod's pack sits above vanilla, so the override wins.
- **UNVERIFIED in practice.** The code path supports it, but it has not been run.
- Benefit: tests run on deterministic flat Mars terrain with the real Mars `dimension_type` (environment attributes,
  clock, gravity rules keyed on the dimension).

**Fallback option:** a small commented mixin in the gametest source set that wraps the `WorldDimensions#bake` call
inside the `GameTestServer.create` lambda and passes `context.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM)`.
Targeting a lambda is brittle, so this is not recommended.

**Interacting with another dimension from an overworld test:**

- `ServerLevel mars = helper.getLevel().getServer().getLevel(MARS_KEY)`. Then create the entity yourself
  (`type.create(mars, EntitySpawnReason.COMMAND)`, `snapTo`, `mars.addFreshEntity`), or teleport it there with
  `entity.teleport(new TeleportTransition(...))`. Find it again with `mars.getEntity(uuid)`.
- **You must keep the Mars chunks loaded and the level active.** Entities tick only while `emptyTime < 300`, and that
  counter resets only when a ticket with `FLAG_KEEP_DIMENSION_ACTIVE` exists (`ServerLevel.java:417-426`,
  `ServerChunkCache.hasActiveTickets` → `ticketStorage.shouldKeepDimensionActive()`). The entity must also be in
  entity-ticking range.
  - Use `mars.setChunkForced(x, z, true)` (`ServerLevel.java:1617`). `TicketType.FORCED` has flags 15, which include
    `KEEP_DIMENSION_ACTIVE (8)` (`TicketType.java:15, 22`).
  - Un-force the chunks yourself; the runner only un-forces the test level's chunks.
  - Wait a few ticks for chunks to load, using `runAfterDelay` / `succeedWhen`.
- **Choose arrival coordinates away from the per-dimension test grid.** Mars tests use the same start XZ as the
  overworld tests. Offset by about 4,096 blocks or more.

**Cross-dimension teleport semantics, which are what we test:** `Entity.teleport(TeleportTransition)` (`Entity.java:3142`)
calls `teleportCrossDimension` (:3173). That method:

1. Calls `ejectPassengers()` and teleports each passenger first (players via `ServerPlayer.teleport`; non-players are
   re-created).
2. Creates a **new** vehicle instance with `getType().create(newLevel, DIMENSION_TRAVEL)` and calls
   `restoreFrom(this)`. `restoreFrom` does save-then-load, so the UUID, the saved data and persistent attachments carry
   over (:3131-3136).
3. Re-mounts the passengers with `startRiding(newEntity, true, false)` (:3199).
4. Teleports spectating players with `teleportSpectators` (:3204). This gives "spectators watch the launch" for free.

The method **returns the new entity**, so old references become stale. Fabric fires `AFTER_ENTITY_CHANGE_LEVEL` and
copies all attachments. The `TeleportTransition` record constructors are `(ServerLevel, Vec3 pos, Vec3 speed, float yRot, float xRot, PostTeleportTransition)`
and a full form with `asPassenger` and `relatives` (`MC:net/minecraft/world/level/portal/TeleportTransition.java:13-49`).
The post-transition constants are `DO_NOTHING`, `PLAY_PORTAL_SOUND` and `PLACE_PORTAL_TICKET` (the last adds a radius-3
`PORTAL` ticket, `Entity.java:3282`).

### 7.6 Structures

- `fabric-gametest-api-v1:empty` is 8×8×8 of air, written as SNBT with a `data` list, `palette` and `DataVersion 2730`.
  It is datafixed on load.
- Vanilla `minecraft:empty` (`DATA:structure/empty.nbt`) is **1×1×1**, DataVersion 5023. 26.3 has
  `world_version` 5023 and data pack format 121.0 (`/home/user/mcsrc/jar-client/version.json`).
- Custom structures go in `src/gametest/resources/data/redplanet-gametest/gametest/structure/<name>.snbt` and are
  referenced as `"redplanet-gametest:<name>"`.
- A 9 m-wide ship does not fit inside the 8×8 barrier box. Either use a larger custom pad, at least 16×N×16, or spawn
  the ship above the box's top. With `skyAccess = true` nothing is placed above `bounds.maxY`.

### 7.7 Which test runs today

`build/run/gameTest/logs/latest.log`:

- "Class path entries reference missing files: …/build/classes/java/gametest, …/build/resources/gametest". The source
  set is empty.
- "1 tests are now running…", "Running test environment 'minecraft:default' batch 0 (1 tests)…", `[+]`,
  "All 1 required tests passed :)".

The single test is **`minecraft:always_pass`**:

- Its function is `BuiltinTestFunctions.ALWAYS_PASS = GameTestHelper::succeed`.
- The instance comes from `GameTestInstances.bootstrap` (`required = false`, `GameTestInstances.java:11-20`) and from
  `DATA:test_instance/always_pass.json`.
- The message "All N required tests passed" prints `getTotalCount()` (`GameTestServer.java:261`), which explains the
  misleading wording.
- To exclude it, set `-Dfabric-api.gametest.filter=redplanet-gametest:*`.

### 7.8 Running, failing the build, reports

- Loom creates the run config `gameTest`: it inherits `server`, sets system property `fabric-api.gametest`, uses
  `runDir build/run/gameTest`, and `check` depends on `runGameTest` (`LOOM:.../configuration/fabricapi/FabricApiTesting.java:96-103`).
  This is why `./gradlew build` already runs it.
- System properties (`FAPI:.../impl/gametest/GameTestSystemProperties.java:23-38`):

  | Property | Effect |
  |---|---|
  | `fabric-api.gametest` | enable |
  | `fabric-api.gametest.report-file` | JUnit-style XML report via `SavingXmlTestReporter` |
  | `fabric-api.gametest.filter` | `ResourceSelectorArgument` glob, e.g. `redplanet-gametest:*`, `*:flight_*` (`*` and `?` are wildcards). If nothing matches, it calls `System.exit(-1)`. |
  | `fabric-api.gametest.verify` | runs each test 100 times per rotation |

- **Exit codes, i.e. how the build fails:**
  - `onServerExit` calls `System.exit(failedRequiredCount)` (`GameTestServer.java:303-307`). A crash exits with 1
    (:310-314). A startup failure exits with -1 (Fabric `MainMixin`).
  - `RunGameTask`/`AbstractRunTask` do not ignore exit values, so any failing **required** test fails
    `runGameTest` → `check` → `build`.
  - Optional (`required = false`) failures are only logged.
- **The world persists between runs.** `build/run/gameTest/world` (level.dat, `data/minecraft/*.dat`, players, regions)
  is reused, because Loom's `clearRunDirectory` applies only to the client run. Our SavedData, such as
  `world/data/redplanet/missions.dat`, would leak between runs. Add a Delete task (§10.9).

### 7.9 Testing players and physics: caveats

- **`ServerPlayer.doTick()` only runs from the network.** It is what runs `LivingEntity.tick`, i.e. air, drowning,
  fire, food and travel physics. It is called only from `ServerGamePacketListenerImpl.tick()` → `tickPlayer()`
  (`ServerGamePacketListenerImpl.java:301-327`). That in turn runs only for connections registered in
  `ServerConnectionListener` (`.../ServerConnectionListener.java:140-156`).
  - The per-level entity tick calls `ServerPlayer.tick()` (:581), which is lightweight and skips that physics.
  - **A mock player in a server gametest therefore does not lose air or fall unless you call `player.doTick()`
    yourself**, for example `helper.onEachTick(player::doTick)`. **UNVERIFIED:** side effects of doing so.
  - Prefer mobs (Pig, Villager) for air and suffocation rules, and items, arrows and falling blocks for gravity and
    drag. Test real player feel in client gametests.
- **Player movement is client-authoritative.** `Player.isClientAuthoritative()` returns `true`
  (`MC:net/minecraft/world/entity/player/Player.java:1244`). Mars gravity for the local player must also be applied on
  the client. Attribute-based gravity syncs; custom code must run on both sides.
- **`FakePlayer` cannot ride.** `FakePlayer.startRiding(...)` returns `false` and `tick()` is empty
  (`FAPI:.../api/entity/FakePlayer.java:142, 112`). Use `makeMockServerPlayerInLevel()` for passenger tests.
- **Mock players outlive the test.** They stay in the `PlayerList` after the test, so call
  `server.getPlayerList().remove(p)`.

---

## 8. Client gametests (fabric-client-gametest-api-v1 6.0.7)

### 8.1 Entrypoint

- The key is `"fabric-client-gametest"` (`FAPI:.../impl/client/gametest/FabricClientGameTestRunner.java:36`). The
  interface is `FabricClientGameTest { void runTest(ClientGameTestContext context); }`.
- Tests run **sequentially in entrypoint order** on a dedicated "Test thread". They start on the first client tick with
  no overlay (`mixin/client/gametest/lifecycle/MinecraftMixin.java`).
- A filter by mod id is available: `-Dfabric.client.gametest.modid=redplanet-gametest`
  (`TestSystemProperties.java:37`).
- Every test **must end with no server running, no level, and the TitleScreen showing**, or an `AssertionError` is
  thrown (`FabricClientGameTestRunner.java:104-120`). Use try-with-resources on the contexts.

### 8.2 `ClientGameTestContext` (`FAPI:.../api/client/gametest/v1/context/ClientGameTestContext.java`)

| Member | Line | Notes |
|---|---|---|
| `int NO_TIMEOUT = -1`, `int DEFAULT_TIMEOUT = 200` (10 s) | :48, :53 | |
| `void waitTick()`, `void waitTicks(int)` | :58, :65 | no timeout |
| `int waitFor(Predicate<Minecraft>)` / `(…, int timeout)` | :73, :83 | default 200 ticks; throws `AssertionError("Timed out waiting for predicate")` |
| `int waitForScreen(@Nullable Class<? extends Screen>)` | :92 | |
| `void setScreen(Supplier<@Nullable Screen>)` | :100 | calls `client.gui.setScreen(...)`. In 26.3, `Minecraft.gui.screen()` / `gui.setScreen()` replace `Minecraft.screen` (`MCC:net/minecraft/client/gui/Gui.java:230/234`) |
| `void clickScreenButton(String translationKey)`, `boolean tryClickScreenButton(String)` | :108, :117 | `Button` and `CycleButton` by label |
| `Path takeScreenshot(String name)`, `Path takeScreenshot(TestScreenshotOptions)` | :125, :135 | |
| `void assertScreenshotEquals(String/TestScreenshotComparisonOptions)`, `Vector2i assertScreenshotContains(...)` | :144-177 | fuzzy matching (mean squared difference 0.005 by default) |
| `TestInput getInput()` | :186 | any thread |
| `TestWorldBuilder worldBuilder()` | :195 | |
| `void restoreDefaultGameOptions()` | :202 | auto-called before each test |
| `<E> void runOnClient(FailableConsumer<Minecraft,E>)`, `<T,E> T computeOnClient(FailableFunction<Minecraft,T,E>)` | :214, :228 | `Minecraft.getInstance()` **throws** on the test thread (`threading/MinecraftMixin`) |

### 8.3 Worlds, servers and connections

- **`TestWorldBuilder`** (`.../world/TestWorldBuilder.java`):
  - `setUseConsistentSettings(boolean)` (:48), `adjustSettings(Consumer<WorldCreationUiState>)` (:57),
    `TestSingleplayerContext create()` (:64), `TestDedicatedServerContext createServer([Properties])` (:74/87).
  - It works by driving `CreateWorldScreen` and clicking `selectWorld.create`, so **datapack dimensions (Mars) are
    present**.
  - `WorldCreationUiState` setters: `setGameMode(SelectedGameMode.CREATIVE)`, `setAllowCommands`, `setSeed`,
    `setWorldType`, `setGameRules`, `setDifficulty` (`MCC:.../worldselection/WorldCreationUiState.java:88-283`).
- **`TestSingleplayerContext`** (AutoCloseable): `getWorldSave()` (:37), `getConnection()` (:46), `getServer()` (:55),
  `close()` (:61). `close()` disconnects, waits up to 1200 ticks for the server to stop, then shows `TitleScreen`
  (`TestSingleplayerContextImpl.java:66-76`).
- **`TestWorldSave`**: `getSaveDirectory()` and `TestSingleplayerContext open()`. **Use this to reopen the same world
  for the client-side "logout mid-flight" test.**
- **`TestServerContext`**:
  - `runCommand(String)` (:41) runs as `server.createCommandSourceStack()`, i.e. the console.
  - `runOnServer(FailableConsumer<MinecraftServer>)` (:53), `computeOnServer(...)` (:67),
    `waitFor(Predicate<MinecraftServer>[, timeout])` (:75/85).
- **`TestServerConnection`**:
  - `waitForChunksDownload([timeout])` (:62), `waitForChunksRender([waitForDownload][, timeout])` (:107, default 1200 ticks)
  - `waitForClientboundPackets()` (:127), `waitForServerboundPackets()` (:132),
    `waitForClientboundEntityUpdates(EntityType, ...)` (:146)
  - `getClientPlayer()` and `getClientLevel()` are **render thread only** (:155, :173). `getServerPlayer()` and
    `getServerLevel()` are **server thread only** (:164, :182). There is **no `getClientLevel()` on
    TestSingleplayerContext**; use `sp.getConnection().getClientLevel()` inside `runOnClient`.
- **`TestDedicatedServerContext`** (AutoCloseable; `connect()` returns a `TestDedicatedServerConnection`):
  - It is an in-process dedicated server (`new Thread(() -> Main.main(new String[]{}))`) running in the client run dir.
  - Server properties default to `online-mode=false`, `spawn-protection=0`, `max-players=1`, and `sync-chunk-writes`
    false on Unix (`DedicatedServerImplUtil.java:46-59`).
  - It needs `eula.txt`; `eula = true` in `configureTests` writes it (`FabricApiTesting.java:136-146`).
  - Use it to test the real network path (codecs, client-only class leaks on the server). Only one client exists, so
    true multi-passenger cases belong in server gametests.

### 8.4 `TestInput` (`.../api/client/gametest/v1/TestInput.java`)

- **Keys:** `holdKey`, `releaseKey`, `pressKey` and `holdKeyFor(…, ticks)` each accept a `KeyMapping`, a
  `Function<Options,KeyMapping>`, an `InputConstants.Key` or an `int` **SDL scancode**. Minecraft 26.3 uses SDL3, not
  GLFW.
- **Mouse:** `holdMouse`, `releaseMouse`, `pressMouse`, `holdMouseFor`. Modifiers: `holdControl`, `holdShift`,
  `holdAlt` and the matching `release*`.
- **Other:** `lookAt(float yaw, float pitch)`, `lookAt(BlockPos)`, `typeChar`, `typeChars`, `scroll`, `setCursorPos`,
  `moveCursor`, `resizeWindow(int,int)`.
- Most key mappings react only after a tick has been waited.

### 8.5 Screenshots

- **Options:** `TestScreenshotOptions.of(name)` (`.../screenshot/TestScreenshotOptions.java:35`). The common options
  are `disableCounterPrefix()` (:36), `withDeltaTicks(float)` (:46, default 1), `withSize(int w, int h)` (:55) and
  `withDestinationDir(Path)` (:64) (`TestScreenshotCommonOptions.java`).
- **Location:** `<gameDir>/screenshots/<NNNN_><name>.png`, i.e. `build/run/clientGameTest/screenshots/0000_00_countdown.png`.
  The counter is global across tests (`ClientGameTestContextImpl.java:439-452`).
- **Arbitrary size:** `withSize` temporarily resizes the render target and renders an extra frame
  (`ClientGameTestContextImpl.java:382-436`). This works independently of the Xvfb screen size, e.g. 1920×1080 while
  Xvfb is 1280×1024.
- **Comparisons:** `TestScreenshotComparisonOptions.of(templateName)` reads templates from `<testModResources>/templates/`.
  Loom sets `fabric.client.gametest.testModResourcesPath` to the first resources dir of the gametest source set
  (`FabricApiTesting.java:107-116`), i.e. `src/gametest/resources`. Missing templates are written there.
  Options: `.withAlgorithm(TestScreenshotComparisonAlgorithm.meanSquaredDifference(f) | exact())`, `.withGrayscale()`,
  `.withRegion(...)`, `.save()`.
- **Persistence warning:** Loom's `deleteGameTestRunDir` deletes `build/run/clientGameTest` before every
  `runClientGameTest` (`clearRunDirectory` defaults to `true`, `FabricApiTesting.java:76, 127-134`). Copy screenshots
  out, or write them elsewhere with `withDestinationDir`.

### 8.6 Defaults applied for determinism

**Game options** (`ClientGameTestContextImpl.initGameOptions`, :94-110; package docs table):

| Option | Gametest value |
|---|---|
| Tutorial | NONE |
| Clouds | OFF |
| Anisotropic filtering | 0 |
| Chunk fade | 0 |
| Onboard accessibility | `false` |
| **Render distance** | **5** |
| Music | 0 |

**World creation** (`TestWorldBuilderImpl.setConsistentSettings`, :137-146):

| Setting | Gametest value |
|---|---|
| Preset | **FLAT** |
| Seed | "1" |
| Structures | off |
| `ADVANCE_TIME`, `ADVANCE_WEATHER`, `SPAWN_MOBS` | `false` |
| `RESPAWN_RADIUS` | 0 |

Disable these with `setUseConsistentSettings(false)`.

**Window:** the default size comes from `--width`/`--height`, defaulting to 854×480
(`MCC:net/minecraft/client/main/Main.java:106-107`). The gametest `WindowMixin` fixes the logical window to that size
and ignores OS resize and focus events (`FAPI:.../mixin/client/gametest/input/WindowMixin.java:95`).

**Username:** `Player0`, set with `--username` by Loom (`FabricApiTesting.java:77, 121`).

### 8.7 Timing, threading and long tests

- **One server tick per client tick, at most one client tick per frame** (package docs :41;
  `threading/MinecraftMixin.captureTicksPerFrame`, :75-80).
- **The client tick rate is still real-time.** The test thread is released only on frames that actually tick
  (`postRunTasksHook`). The client tick period is `max(default 50 ms, server millisecondsPerTick)`
  (`MCC:net/minecraft/client/Minecraft.java:3048-3055`), so `/tick rate` cannot speed it up. **A client gametest runs
  at no more than 20 TPS in real time**, and slower if llvmpipe renders fewer than 20 FPS.
  - Example: a 3-minute flight leg takes at least 3 minutes. Use a test-only time-compressed flight profile.
- **Pause screens freeze the server.** The integrated server pauses whenever a `Screen` with `isPauseScreen() == true`
  (the default, `Screen.java:477`) or a pausing overlay is open (`Gui.java:305`, `Minecraft.java:1283`). An interlude
  `Screen` that does not override this to `false` freezes the flight state machine in singleplayer.
- **There is no global test timeout.** `waitFor` defaults to 200 ticks, chunk waits to 1200 ticks, and world load to
  1200 ticks (`ClientGameTestImpl.waitForWorldLoad`, :39-57). Add a Gradle task `timeout` (§10.9).
- **Failure path:**
  - Any exception on the test thread is stored, and the client is stopped (`ThreadingImpl.java:138-152`).
  - `Minecraft.run` then rethrows it (`threading/MinecraftMixin.java:63`). A server crash becomes
    `Throwable("The server crashed")`.
  - **UNVERIFIED:** the exact process exit code. The rethrown throwable propagates out of `main`, which normally means
    exit code 1, so the `JavaExec` task fails.
- **Waiting for a dimension change.** The client shows `LevelLoadingScreen` (`MCC:.../multiplayer/ClientPacketListener.java:1230, 1598-1608`).
  Wait with:

  ```java
  context.waitFor(mc -> mc.level != null && MARS.equals(mc.level.dimension()) && !(mc.gui.screen() instanceof LevelLoadingScreen), timeout)
  ```

  Then call `sp.getConnection().waitForChunksRender()`.

---

## 9. Loom, Xvfb and the graphics backend

### 9.1 Tasks

| Task | What | Source |
|---|---|---|
| `runGameTest` | Server gametests in `build/run/gameTest`. `check` (so `build`) depends on it. | `FabricApiTesting.java:96-103` |
| `runClientGameTest` | Client gametests in `build/run/clientGameTest`, with system properties `fabric.client.gametest` and `fabric.client.gametest.testModResourcesPath`, and `--username Player0`. **Not** wired into `check`. | `FabricApiTesting.java:106-125` |
| `deleteGameTestRunDir` | Deletes the client run dir before `runClientGameTest` | :127-134 |
| `acceptGameTestEula` | Writes `build/run/clientGameTest/eula.txt` (the file present today) | :136-146 |

Task names come from `"run" + capitalize(configName)` (`LOOM:.../task/LoomTasks.java:192-195`). Tasks are registered
through `runConfigs.whenObjectAdded` (:201-210), so `tasks.named("runClientGameTest")` works right after the
`fabricApi { configureTests { … } }` block.

The gametest source set depends on `main` and `client` because `splitEnvironmentSourceSets()` is on
(`FabricApiAbstractSourceSet.java`). Loom creates a mod classpath group named after `modId`. **The source set needs its
own `src/gametest/resources/fabric.mod.json`** with id `redplanet-gametest`, or Fabric Loader will not see the
entrypoints. **UNVERIFIED:** Loom's failure mode when it is missing, which today simply means "no tests".

### 9.2 Xvfb

- **Dev run tasks:** `AbstractRunTask` has `@Input public abstract Property<Boolean> getUseXvfb();`
  (`LOOM:net/fabricmc/loom/task/AbstractRunTask.java:106`).
  - The convention is `true` only if env `CI` is set, the OS is Linux, the run environment is `client`, and
    `xvfb-run --help` exits 0 (:181-187).
  - **`CI` is unset in this container, so set it explicitly.**
  - It then executes `xvfb-run --auto-servernum <java> <jvmArgs> <main> <args>` with the task environment (:287-310).
  - The Xvfb screen is xvfb-run's default `-screen 0 1280x1024x24` (`/usr/bin/xvfb-run:16`).
- **Production run tasks:** `ClientProductionRunTask` has `getUseXVFB()` (`LOOM:.../task/prod/ClientProductionRunTask.java:64`)
  and runs `xvfb-run -a`. It must be registered manually and takes `mods`, `jvmArgs`, `programArgs`, `runDir` and
  `javaLauncher` (`AbstractProductionRunTask.java:81-109`). This is a possible extra smoke test of the built jar.
- **Groovy DSL:** `tasks.named("runClientGameTest") { useXvfb = true }`, or `useXvfb.set(true)`.

### 9.3 Graphics backend (26.3 "renderpearl", SDL3)

- **Choosing a backend.** `PreferredGraphicsApi { DEFAULT, OPENGL, VULKAN }`, where `getBackendsToTry()` returns
  `{vulkan, gl}` for VULKAN and `{gl, vulkan}` otherwise (`MCC:net/minecraft/client/PreferredGraphicsApi.java:10-36`).
  - The program argument `--graphicsBackend <value>` (`MCC:.../main/Main.java:82`; jopt `EnumConverter` matches with
    `equalsIgnoreCase`) overrides the in-game setting (`MCC:.../Minecraft.java:473-488`).
  - DEFAULT already tries OpenGL first. **Pass `--graphicsBackend opengl`** so a stale `options.txt` cannot select
    Vulkan.
- **OpenGL requirement.** The GL backend creates its context through SDL3 with `SDL_GL_SetAttribute(17,3)`,
  `(18,3)` and core profile, i.e. **OpenGL 3.3 core** (`MCC:com/mojang/renderpearl/backend/opengl/GlBackend.java:60-65`;
  `GlDevice.java:97` "Failed to create OpenGL 3.3 context"). Direct State Access is used when available, with
  emulation otherwise (`DirectStateAccess.java:14`). Mesa llvmpipe provides GL 4.5 core.
- **Container facts** (checked read-only):

  | Item | Status |
  |---|---|
  | `/usr/bin/xvfb-run`, `Xvfb` | present |
  | Mesa 25.2.8 (`swrast_dri.so`, `libgallium`, `libGLX_mesa`) | present, so llvmpipe works |
  | `/usr/share/vulkan/icd.d` | missing, so **no lavapipe**; Vulkan would fail and fall back to GL |
  | X11 client libraries SDL3 needs | present |
  | `libEGL` | absent; SDL uses GLX on X11 |
  | SDL3 | bundled by LWJGL 3.4.3 (`lwjgl-sdl-3.4.3-natives-linux.jar`) |
  | `JAVA_HOME` | `/usr/lib/jvm/java-21-openjdk-amd64` |
  | Java 25 | `/opt/jdk/jdk-25.0.4.1+1` (Temurin) |

- **Environment variables** (Loom `environmentVariable`, `LOOM:.../configuration/ide/RunConfigSettings.java:357`), all
  **UNVERIFIED** but harmless:

  | Variable | Purpose |
  |---|---|
  | `LIBGL_ALWAYS_SOFTWARE=1` | force the software rasterizer |
  | `GALLIUM_DRIVER=llvmpipe` | pick llvmpipe explicitly |
  | `SDL_VIDEO_DRIVER=x11` | force SDL's X11 backend under Xvfb |
  | `ALSOFT_DRIVERS=null` | OpenAL Soft null audio backend, if audio init is noisy |

  No `-Dorg.lwjgl…` flags are needed. Use `-Dorg.lwjgl.util.Debug=true` only for troubleshooting.

---

## 10. Recommendations for redplanet

### 10.1 Architecture (justified by sections 1 to 9)

| Concern | Choice |
|---|---|
| Flight state authority | Server-side state machine inside `StarshipEntity`. `getControllingPassenger()` returns `null`. Vehicle type is serializable (§3.2). |
| State to clients | `SynchedEntityData` (phase, phase tick, profile index) for every tracker, **plus** a `FlightStatePayload` on phase changes and on `EntityTrackingEvents.START_TRACKING` (§1). |
| Flight profiles | `DynamicRegistries.registerSynced(FLIGHT_PROFILE, FlightProfile.CODEC)` with JSON in `data/redplanet/redplanet/flight_profile/*.json`. Add a test-only short profile in the gametest datapack (§2.5). |
| Ship persistence | Entity saved data, with our own velocity field (§3.2, §3.4). |
| Mission source of truth | Global `SavedData` `redplanet:missions`, i.e. `<world>/data/redplanet/missions.dat` (§3.1). |
| Per-player data | Persistent `copyOnDeath` player attachment synced `targetOnly()`. Re-seat check one tick after `ServerPlayerEvents.JOIN` (§3.3, §3.5). |
| Landing-site chunks | Custom `TicketType` registered in `BuiltInRegistries.TICKET_TYPE` with `FLAG_LOADING | FLAG_SIMULATION | FLAG_KEEP_DIMENSION_ACTIVE`, optionally `FLAG_PERSIST` (`MC:net/minecraft/server/level/TicketType.java:10-30`). Add it before arrival with `ServerChunkCache.addTicketWithRadius(type, ChunkPos, r)` (`ServerChunkCache.java:495`). |
| Interlude UI | Must not pause: `isPauseScreen()` returns `false` (§8.7). |

### 10.2 Payload, registration, send and receive

```java
// src/main/java/io/github/avi130805/redplanet/network/FlightStatePayload.java
package io.github.avi130805.redplanet.network;

import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import io.github.avi130805.redplanet.RedPlanet;

/** S2C: authoritative snapshot of one ship's flight (sent on phase change and when a player starts tracking the ship). */
public record FlightStatePayload(int shipEntityId, UUID shipUuid, Identifier profile, int phase, int phaseTick, long missionTick)
		implements CustomPacketPayload {
	public static final Type<FlightStatePayload> TYPE = new Type<>(RedPlanet.id("flight_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FlightStatePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, FlightStatePayload::shipEntityId,
			UUIDUtil.STREAM_CODEC, FlightStatePayload::shipUuid,
			Identifier.STREAM_CODEC, FlightStatePayload::profile,
			ByteBufCodecs.VAR_INT, FlightStatePayload::phase,
			ByteBufCodecs.VAR_INT, FlightStatePayload::phaseTick,
			ByteBufCodecs.VAR_LONG, FlightStatePayload::missionTick,
			FlightStatePayload::new);

	@Override
	public Type<FlightStatePayload> type() {
		return TYPE;
	}
}
```

```java
// C2S: a passenger asks to skip the cinematic; the server validates it.
public record SkipCinematicPayload(int shipEntityId) implements CustomPacketPayload {
	public static final Type<SkipCinematicPayload> TYPE = new Type<>(RedPlanet.id("skip_cinematic"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SkipCinematicPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.VAR_INT, SkipCinematicPayload::shipEntityId, SkipCinematicPayload::new);

	@Override
	public Type<SkipCinematicPayload> type() {
		return TYPE;
	}
}
```

```java
// src/main/java/io/github/avi130805/redplanet/network/RedPlanetNetworking.java  (call init() from RedPlanet#onInitialize)
public final class RedPlanetNetworking {
	public static void init() {
		// Both directions are registered on both sides, before any receiver.
		PayloadTypeRegistry.clientboundPlay().register(FlightStatePayload.TYPE, FlightStatePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SkipCinematicPayload.TYPE, SkipCinematicPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(SkipCinematicPayload.TYPE, (payload, ctx) -> {
			ServerPlayer player = ctx.player();                     // already on the server thread
			if (player.level().getEntity(payload.shipEntityId()) instanceof StarshipEntity ship && ship.hasPassenger(player)) {
				ship.flight().requestSkip(player);
			}
		});

		// New spectators get a full snapshot.
		EntityTrackingEvents.START_TRACKING.register((tracked, player) -> {
			if (tracked instanceof StarshipEntity ship) {
				send(player, ship.flight().snapshot(ship));
			}
		});
	}

	/** Everyone who can see the ship. Riders track their vehicle like any other entity. */
	public static void broadcast(StarshipEntity ship, FlightStatePayload payload) {
		for (ServerPlayer p : PlayerLookup.tracking(ship)) {
			send(p, payload);
		}
	}

	private static void send(ServerPlayer p, FlightStatePayload payload) {
		if (ServerPlayNetworking.canSend(p, FlightStatePayload.TYPE)) {
			ServerPlayNetworking.send(p, payload);
		}
	}
}
```

```java
// src/client/java/.../RedPlanetClient#onInitializeClient
ClientPlayNetworking.registerGlobalReceiver(FlightStatePayload.TYPE,
		(payload, ctx) -> ClientFlightStates.update(ctx.client(), payload));   // runs on the render thread
// Send: ClientPlayNetworking.send(new SkipCinematicPayload(ship.getId()));
```

### 10.3 Flight profiles as a synced dynamic registry

```java
public static final ResourceKey<Registry<FlightProfile>> FLIGHT_PROFILE =
		ResourceKey.createRegistryKey(RedPlanet.id("flight_profile"));         // createRegistryKey(Identifier)

// RedPlanet#onInitialize (once):
DynamicRegistries.registerSynced(FLIGHT_PROFILE, FlightProfile.CODEC);
// Files:  src/main/resources/data/redplanet/redplanet/flight_profile/earth_to_mars.json, mars_to_earth.json
// Tests:  src/gametest/resources/data/redplanet-gametest/redplanet/flight_profile/fast_round_trip.json
// Server: server.registryAccess().lookupOrThrow(FLIGHT_PROFILE).getOptional(id)
// Client: mc.level.registryAccess().lookupOrThrow(FLIGHT_PROFILE)
```

Changing a profile needs a world reload, not `/reload` (§2.5). If live reloading matters during development, add a
`SimpleReloadListener` via `ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(...)` and push the data from
`SYNC_DATA_PACK_CONTENTS` (§2.2, §2.4).

### 10.4 SavedData: the global mission registry

```java
package io.github.avi130805.redplanet.mission;

public final class MissionRegistry extends SavedData {
	public record Mission(UUID ship, ResourceKey<Level> dimension, String phase, int phaseTick, List<UUID> crew) {
		public static final Codec<Mission> CODEC = RecordCodecBuilder.create(i -> i.group(
				UUIDUtil.CODEC.fieldOf("ship").forGetter(Mission::ship),
				Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Mission::dimension),
				Codec.STRING.fieldOf("phase").forGetter(Mission::phase),
				Codec.INT.fieldOf("phase_tick").forGetter(Mission::phaseTick),
				UUIDUtil.CODEC.listOf().fieldOf("crew").forGetter(Mission::crew)
		).apply(i, Mission::new));
	}

	public static final Codec<MissionRegistry> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Mission.CODEC)
			.xmap(MissionRegistry::new, r -> Map.copyOf(r.missions));

	/** <world>/data/redplanet/missions.dat. A null DataFixTypes is tolerated by Fabric (SavedDataStorageMixin). */
	public static final SavedDataType<MissionRegistry> TYPE =
			new SavedDataType<>(RedPlanet.id("missions"), MissionRegistry::new, CODEC, null);

	private final Map<UUID, Mission> missions;

	public MissionRegistry() {
		this(Map.of());
	}

	private MissionRegistry(Map<UUID, Mission> loaded) {
		this.missions = new HashMap<>(loaded);
	}

	public static MissionRegistry get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);   // global, not per-dimension
	}

	public Optional<Mission> get(UUID ship) {
		return Optional.ofNullable(missions.get(ship));
	}

	public void put(Mission m) {
		missions.put(m.ship(), m);
		setDirty();
	}

	public void remove(UUID ship) {
		if (missions.remove(ship) != null) setDirty();
	}
}
```

### 10.5 Persistent, synced player attachment

```java
public record MissionData(Optional<UUID> activeShip, int flightsCompleted, boolean visitedMars, long lastPhaseChangeGameTime) {
	public static final MissionData EMPTY = new MissionData(Optional.empty(), 0, false, 0L);
	public static final Codec<MissionData> CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.CODEC.optionalFieldOf("active_ship").forGetter(MissionData::activeShip),
			Codec.INT.optionalFieldOf("flights_completed", 0).forGetter(MissionData::flightsCompleted),
			Codec.BOOL.optionalFieldOf("visited_mars", false).forGetter(MissionData::visitedMars),
			Codec.LONG.optionalFieldOf("last_phase_change", 0L).forGetter(MissionData::lastPhaseChangeGameTime)
	).apply(i, MissionData::new));
	public static final StreamCodec<ByteBuf, MissionData> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), MissionData::activeShip,
			ByteBufCodecs.VAR_INT, MissionData::flightsCompleted,
			ByteBufCodecs.BOOL, MissionData::visitedMars,
			ByteBufCodecs.VAR_LONG, MissionData::lastPhaseChangeGameTime,
			MissionData::new);
}

public final class RedPlanetAttachments {
	public static final AttachmentType<MissionData> MISSION = AttachmentRegistry.create(RedPlanet.id("mission"),
			builder -> builder
					.initializer(() -> MissionData.EMPTY)
					.persistent(MissionData.CODEC)                    // stored in the player .dat ("fabric:attachments")
					.copyOnDeath()                                    // survives respawn
					.syncWith(MissionData.STREAM_CODEC, AttachmentSyncPredicate.targetOnly()));

	public static void init() {
		// The vehicle is restored AFTER ServerPlayerEvents.JOIN (PrepareSpawnTask:181-184), so check one tick later.
		ServerPlayerEvents.JOIN.register(player -> player.level().getServer().execute(() -> MissionRecovery.reseatOrRecover(player)));
	}
}
// Usage: player.getAttachedOrCreate(MISSION); player.modifyAttached(MISSION, d -> new MissionData(...));
```

**UNVERIFIED:** whether `server.execute(...)` from inside the tick runs on the same tick or the next one. Using a
one-tick countdown queue drained in `ServerTickEvents.END_SERVER_TICK` is more deterministic.

### 10.6 Ship entity save data

```java
@Override
protected void addAdditionalSaveData(ValueOutput out) {
	out.store("flight", FlightState.CODEC, this.flight);      // phase, phaseTick, profile id, direction, mission id
	out.store("true_velocity", Vec3.CODEC, this.trueVelocity); // vanilla zeroes |Motion| > 10 on load (Entity.java:2192)
	ValueOutput.TypedOutputList<UUID> crew = out.list("crew", UUIDUtil.CODEC);
	this.crewUuids.forEach(crew::add);
}

@Override
protected void readAdditionalSaveData(ValueInput in) {
	this.flight = in.read("flight", FlightState.CODEC).orElse(FlightState.IDLE);
	this.trueVelocity = in.read("true_velocity", Vec3.CODEC).orElse(Vec3.ZERO);
	this.crewUuids.clear();
	in.listOrEmpty("crew", UUIDUtil.CODEC).forEach(this.crewUuids::add);
}
```

### 10.7 Server gametests

**`src/gametest/resources/fabric.mod.json`:**

```json
{
	"schemaVersion": 1,
	"id": "redplanet-gametest",
	"version": "1.0.0",
	"name": "Red Planet (gametests)",
	"environment": "*",
	"entrypoints": {
		"fabric-gametest": [
			"io.github.avi130805.redplanet.gametest.FlightGameTests",
			"io.github.avi130805.redplanet.gametest.MarsPhysicsGameTests",
			"io.github.avi130805.redplanet.gametest.PersistenceGameTests"
		],
		"fabric-client-gametest": [
			"io.github.avi130805.redplanet.gametest.client.RoundTripClientGameTest"
		]
	},
	"depends": { "redplanet": "*", "fabric-api": "*" }
}
```

The version is a literal because only `processResources` (main) expands `${version}`.

**Make Mars exist in GameTestServer:**
`src/gametest/resources/data/minecraft/worldgen/world_preset/flat_all_dimensions.json`. Copy the three vanilla stems
verbatim from `DATA:worldgen/world_preset/flat_all_dimensions.json` and add the Mars stem:

```json
{
  "dimensions": {
    "minecraft:overworld": { "...": "copy verbatim from vanilla" },
    "minecraft:the_end":   { "...": "copy verbatim from vanilla" },
    "minecraft:the_nether":{ "...": "copy verbatim from vanilla" },
    "redplanet:mars": {
      "type": "redplanet:mars",
      "generator": {
        "type": "minecraft:flat",
        "settings": {
          "biome": "redplanet:northern_lowlands",
          "features": false,
          "lakes": false,
          "layers": [
            { "block": "minecraft:bedrock", "height": 1 },
            { "block": "redplanet:regolith", "height": 67 }
          ]
        }
      }
    }
  }
}
```

The layers fill upward from the Mars `min_y`. With `min_y = -64`, 68 layers put the surface at y = 4, the same height
the test grid uses. Adjust the counts if the Mars dimension type uses another `min_y`. This override is **UNVERIFIED in
practice** (§7.5).

**Optional test environment:** `src/gametest/resources/data/redplanet-gametest/test_environment/fast_flight.json`

```json
{ "type": "minecraft:game_rules", "rules": { "redplanet:flight_time_scale": 20 } }
```

**Test class:**

```java
package io.github.avi130805.redplanet.gametest;

public class FlightGameTests {
	static final ResourceKey<Level> MARS = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("redplanet", "mars"));

	@GameTest(maxTicks = 600, skyAccess = true, environment = "redplanet-gametest:fast_flight")
	public void countdownReachesLiftoff(GameTestHelper helper) {
		StarshipEntity ship = helper.spawn(RedPlanetEntities.STARSHIP, new BlockPos(4, 12, 4)); // above the 8x8 barrier box
		ship.flight().start(TestProfiles.FAST_OUTBOUND);
		helper.succeedWhen(() -> helper.assertValueEqual(ship.flight().phase(), FlightPhase.LIFTOFF, "phase"));
	}

	@GameTest(maxTicks = 40)
	public void flightStateSurvivesSaveLoad(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		StarshipEntity ship = helper.spawn(RedPlanetEntities.STARSHIP, new BlockPos(4, 12, 4));
		ship.flight().forceState(FlightPhase.MAX_Q, 37);
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		ship.saveWithoutId(out);
		StarshipEntity copy = RedPlanetEntities.STARSHIP.create(level, EntitySpawnReason.LOAD);
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), out.buildResult()));
		helper.assertValueEqual(copy.flight().phase(), FlightPhase.MAX_Q, "phase after reload");
		helper.assertValueEqual(copy.flight().phaseTick(), 37, "phase tick after reload");
		helper.succeed();
	}

	@GameTest(maxTicks = 400, skyAccess = true)
	public void dimensionTransferKeepsPassengers(GameTestHelper helper) {
		ServerLevel mars = helper.getLevel().getServer().getLevel(MARS);
		helper.assertTrue(mars != null, "redplanet:mars missing from GameTestServer (world_preset override)");
		StarshipEntity ship = helper.spawn(RedPlanetEntities.STARSHIP, new BlockPos(4, 12, 4));
		Villager crew = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(4, 12, 4));
		helper.assertTrue(crew.startRiding(ship, true, false), "villager boards");

		BlockPos dest = BlockPos.containing(ship.getX() + 4096, 150, ship.getZ());      // away from the Mars test grid
		ChunkPos chunk = ChunkPos.containing(dest);
		mars.setChunkForced(chunk.x(), chunk.z(), true);                                 // FORCED keeps Mars active

		Entity moved = ship.teleport(new TeleportTransition(mars, Vec3.atCenterOf(dest), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING));
		helper.assertTrue(moved instanceof StarshipEntity && moved.level() == mars, "a NEW ship instance in Mars");
		helper.runAfterDelay(5, () -> {
			helper.assertValueEqual(moved.getPassengers().size(), 1, "passengers after transfer");
			helper.assertTrue(moved.getPassengers().getFirst().level() == mars, "passenger is in Mars");
			moved.getPassengers().forEach(Entity::discard);
			moved.discard();                                                             // succeed() only cleans the test box
			mars.setChunkForced(chunk.x(), chunk.z(), false);
			helper.succeed();
		});
	}
}
```

**A test that runs inside Mars:**

```java
public class MarsPhysicsGameTests {
	@GameTest(dimension = "redplanet:mars", maxTicks = 60, skyAccess = true)
	public void itemFallsWithMarsGravity(GameTestHelper helper) {
		ItemEntity item = helper.spawnItem(Items.STONE, new Vec3(4, 7, 4));
		item.setDeltaMovement(Vec3.ZERO);
		helper.runAfterDelay(10, () -> {
			double dy = item.getDeltaMovement().y;
			helper.assertValueInBetween(-0.2, dy, -0.05, "vy after 10 ticks (expected about 0.379 x Earth)");  // derive bounds from SCIENCE.md
			helper.succeed();
		});
	}
	// Suffocation: use a mob (air logic runs in LivingEntity.baseTick each server tick); mock players need manual doTick().
}
```

**Logout mid-flight**, mirroring `PlayerList.remove` and `PrepareSpawnTask.Ready#spawn`. **UNVERIFIED:** it has not
been run, and the deprecated helper may print warnings.

```java
@GameTest(maxTicks = 200, skyAccess = true)
@SuppressWarnings("removal")
public void soloShipLeavesAndReturnsWithPlayer(GameTestHelper helper) {
	MinecraftServer server = helper.getLevel().getServer();
	ServerPlayer player = helper.makeMockServerPlayerInLevel();            // real PlayerList entry (EmbeddedChannel)
	StarshipEntity ship = helper.spawn(RedPlanetEntities.STARSHIP, new BlockPos(4, 12, 4));
	player.startRiding(ship, true, false);
	ship.flight().forceState(FlightPhase.MAX_Q, 12);
	UUID shipId = ship.getUUID();

	server.getPlayerList().remove(player);                                 // vanilla logout: save (RootVehicle) + remove ship
	helper.assertTrue(ship.isRemoved(), "a solo-crewed ship leaves the world with its player");
	CompoundTag saved = server.getPlayerList().loadPlayerData(player.nameAndId()).orElseThrow();

	ValueInput in = TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), saved);
	ServerPlayer back = new ServerPlayer(server, helper.getLevel(), player.getGameProfile(), ClientInformation.createDefault());
	back.load(in);
	Connection conn = new Connection(PacketFlow.SERVERBOUND);
	new EmbeddedChannel(conn);
	server.getPlayerList().placeNewPlayer(conn, back, CommonListenerCookie.createInitial(back.getGameProfile(), false));
	back.loadAndSpawnParentVehicle(in);                                    // what PrepareSpawnTask does after placeNewPlayer

	helper.assertTrue(back.getVehicle() instanceof StarshipEntity s && s.getUUID().equals(shipId)
			&& s.flight().phase() == FlightPhase.MAX_Q && s.flight().phaseTick() == 12, "ship and state restored, player re-seated");
	Entity v = back.getVehicle();
	server.getPlayerList().remove(back);
	if (v != null && !v.isRemoved()) v.discard();
	helper.succeed();
}
```

Write the multi-crew variant with two mock players. Expected result: the ship stays in the world, the returning player
is not re-seated by vanilla, and our JOIN+1 recovery must re-seat them.

### 10.8 Client gametest (full round trip with screenshots)

```java
package io.github.avi130805.redplanet.gametest.client;

public class RoundTripClientGameTest implements FabricClientGameTest {
	static final ResourceKey<Level> MARS = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("redplanet", "mars"));
	static final int PHASE_TIMEOUT = 20 * 60 * 3;                      // ticks; the client runs at <= 20 TPS real time
	static final Path OUT = Path.of(System.getProperty("redplanet.gametest.screenshotDir", "screenshots"));

	@Override
	public void runTest(ClientGameTestContext context) {
		TestWorldSave save;
		try (TestSingleplayerContext sp = context.worldBuilder()
				.adjustSettings(s -> {
					s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
					s.setAllowCommands(true);
				})
				.create()) {
			save = sp.getWorldSave();
			sp.getConnection().waitForChunksRender();
			sp.getServer().runOnServer(server -> {
				ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
				StarshipEntity ship = TestShips.spawnOnPad(p.level(), p.blockPosition().offset(12, 0, 0));
				p.startRiding(ship, true, true);
				ship.flight().start(TestProfiles.FAST_ROUND_TRIP);    // time-compressed test profile from the gametest datapack
			});
			sp.getConnection().waitForClientboundEntityUpdates(RedPlanetEntities.STARSHIP);
			shot(context, "00_countdown");
			phase(context, FlightPhase.LIFTOFF);     shot(context, "01_liftoff");
			phase(context, FlightPhase.CLOUD_PASS);  shot(context, "02_cloud_pass");
			phase(context, FlightPhase.HOT_STAGING); shot(context, "03_staging");
			phase(context, FlightPhase.INTERLUDE);   shot(context, "04_interlude");   // the interlude Screen must not pause
			context.waitFor(mc -> mc.level != null && MARS.equals(mc.level.dimension())
					&& !(mc.gui.screen() instanceof LevelLoadingScreen), PHASE_TIMEOUT);
			sp.getConnection().waitForChunksRender();
			phase(context, FlightPhase.ENTRY);         shot(context, "05_entry_plasma");
			phase(context, FlightPhase.BELLY_FLOP);    shot(context, "06_belly_flop");
			phase(context, FlightPhase.FLIP);          shot(context, "07_flip");
			phase(context, FlightPhase.LANDING_BURN);  shot(context, "08_landing_burn");
			phase(context, FlightPhase.LANDED);        shot(context, "09_touchdown");
			sp.getServer().runCommand("time set noon");          // assumes Mars uses a clock /time can address (UNVERIFIED)
			context.waitTicks(5);                    shot(context, "10_mars_noon");
			// ... sunset and night, then start the return leg, then close mid-flight:
		}
		try (TestSingleplayerContext again = save.open()) {     // logout or rejoin mid-flight: the ship must come back with us
			again.getConnection().waitForChunksRender();
			context.waitFor(mc -> mc.player != null && mc.player.getVehicle() instanceof StarshipEntity, 200);
			shot(context, "20_rejoined_mid_flight");
			// ... continue to Earth landing, screenshot each phase
		}
	}

	private static void phase(ClientGameTestContext ctx, FlightPhase p) {
		ctx.waitFor(mc -> ClientFlightStates.localPhase(mc) == p, PHASE_TIMEOUT);
	}

	private static void shot(ClientGameTestContext ctx, String name) {
		ctx.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 720).withDestinationDir(OUT));
	}
}
```

### 10.9 build.gradle additions

Place these **after** the existing `fabricApi { configureTests { … } }` block. A `loom.runs.gameTest {}` that runs
earlier would create the config first and collide with Loom's `create("gameTest")`.

```groovy
loom {
	runs {
		gameTest {
			property "fabric-api.gametest.report-file", file("build/reports/gametest/server-gametests.xml").absolutePath
			// property "fabric-api.gametest.filter", "redplanet-gametest:*"   // skip minecraft:always_pass
		}
		clientGameTest {
			programArgs "--graphicsBackend", "opengl", "--width", "1280", "--height", "720"
			environmentVariable "LIBGL_ALWAYS_SOFTWARE", "1"     // Mesa llvmpipe (UNVERIFIED necessity)
			environmentVariable "GALLIUM_DRIVER", "llvmpipe"
			environmentVariable "SDL_VIDEO_DRIVER", "x11"        // SDL3 under Xvfb
			property "redplanet.gametest.screenshotDir", file("build/gametest-screenshots").absolutePath
		}
	}
}

// The gametest world is reused between runs (only the client run dir is cleared), so wipe it to avoid stale SavedData.
def cleanGameTestWorld = tasks.register("cleanGameTestWorld", Delete) {
	delete file("build/run/gameTest/world")
}
tasks.named("runGameTest") {
	dependsOn cleanGameTestWorld
}

tasks.named("runClientGameTest") {
	useXvfb = true                                   // AbstractRunTask#getUseXvfb(); defaults to true only when $CI is set
	timeout = java.time.Duration.ofMinutes(60)       // standard Gradle Task#timeout (UNVERIFIED with Loom's xvfb exec path)
}
```

Run them with:

```
JAVA_HOME=/opt/jdk/jdk-25.0.4.1+1 ./gradlew build
JAVA_HOME=/opt/jdk/jdk-25.0.4.1+1 ./gradlew runClientGameTest
```

The alternative to Loom's Xvfb support is
`xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew runClientGameTest`.

Screenshots land in `build/gametest-screenshots/NNNN_<name>.png`. Copy the curated ones to `docs/screenshots/`.

### 10.10 Risk register

| # | Risk | Mitigation |
|---|---|---|
| 1 | Mars is absent in GameTestServer (§7.5) | World preset override in the gametest datapack (UNVERIFIED). Fallback: a gametest-only mixin. Fallback 2: `/test run` inside a client gametest world. |
| 2 | Concurrent tests in a batch share global state | Give global-state tests distinct `environment` ids (each id is its own batch); key all data by test-local UUIDs; clean up. |
| 3 | Stale gametest world | `cleanGameTestWorld` task |
| 4 | Mock players are not physics-ticked; player movement is client-authoritative; FakePlayer cannot ride | Use mobs and items for physics tests, manual `doTick()` where needed, and client tests for player feel |
| 5 | JOIN fires before the vehicle is restored; multi-crew players are not re-seated | One-tick-deferred recovery driven by the player attachment and the SavedData registry |
| 6 | `Entity.load` velocity clamp; vehicle must be serializable; controlling passenger must be null | §3.2 |
| 7 | Pause screens freeze singleplayer; client tests capped at 20 TPS real time | Non-pausing interlude; test-only fast profile; Gradle timeout |
| 8 | Client run dir wiped each run | `withDestinationDir` |
| 9 | Vulkan unavailable here | `--graphicsBackend opengl` |
| 10 | Synced dynamic registries do not `/reload` | Accept for release builds, or use the reload listener plus payload path |
| 11 | `makeMockServerPlayerInLevel` is `@Deprecated(forRemoval = true)` | Fine for 26.3; re-check on update |
| 12 | `GameTestHelper.setTime` needs a default clock on the dimension type | Give Mars a `default_clock`, or use the `clock_time` environment |
