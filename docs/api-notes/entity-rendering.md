# Entity, rendering and particle API notes: Minecraft Java 26.3 + Fabric API 0.161.0+26.3

Researched 2026-10-04 for **Red Planet: Starship to Mars** (`redplanet`, `io.github.avi130805.redplanet`).
Every claim below comes from reading these sources (nothing was compiled or run):

| Abbrev. | Path |
|---|---|
| `common/` | `/home/user/mcsrc/common` (decompiled common and server classes) |
| `client/` | `/home/user/mcsrc/client` (decompiled client classes, `com/mojang/blaze3d`, `com/mojang/renderpearl`) |
| `assets/` | `/home/user/mcsrc/jar-client/assets/minecraft` (vanilla shaders and data) |
| `fabric/` | `/home/user/mcsrc/fabric-api/src` (Fabric API sources, with mixins and the transitive class tweaker) |

Citations use `file:line` with approximate line numbers. **[UNVERIFIED]** marks anything inferred and not read
directly, such as runtime behaviour or code sketches that were never compiled. Everything else was read in source.

Notes on the decompiled sources:
- Comments like `/** Access widened by fabric-transitive-access-wideners-v1 to accessible */` mean that **Fabric API
  makes that member public**. The list is in `fabric/fabric-transitive-access-wideners-v1.classtweaker`. Mods compiled
  against Fabric API (Loom) can call those members directly.
- Vanilla entity-type constants moved from `EntityType` into **`EntityTypes`**, and their keys into **`EntityTypeIds`**
  (`common/net/minecraft/world/entity/EntityTypes.java`, `EntityTypeIds.java`). The same applies to `BlockEntityTypes`.

---

## 0. Key findings and risks

1. **Entity types:** use `EntityType.Builder.of(factory, MobCategory.MISC)...build(ResourceKey)` and register it with
   `Registry.register(BuiltInRegistries.ENTITY_TYPE, key, type)`. A plain `Entity` subclass works and needs no
   attributes. For `minecraft`-namespaced keys only, `build()` would try a datafixer lookup; Fabric skips that lookup for
   modded entities.
2. **Server tracking distance is horizontal only.** It is `min(clientTrackingRange*16*scale, playerViewDistance*16)`, and
   the entity's chunk must be tracked by the player. **Altitude never untracks the ship.**
3. **Client visibility is the real limit for a rocket at y≈1500.** The cull frustum and the projection both use
   `depthFar = max(renderDistance*16*4, cloudRange*16)`, and the render-distance fog uses `max(|xz|, |y|)` with
   `fogEnd = renderDistance*16`. Without a "far impostor" trick in the renderer (see §2.9 and §10.5), a ship more than
   about 192 blocks up is fogged into the sky colour at 12 chunks, and beyond `depthFar` it is clipped.
4. **Vanilla only finds an entity if the query box reaches the entity's own section.** Entity section lookups search the
   query box with margins of 2 blocks horizontally, 4 blocks down and 0 blocks up. A 50-block-tall AABB therefore
   **cannot be clicked, picked or collided with near its top**. Vanilla works around this only for `EnderDragonPart`.
   Board from a tower/arm block, or add small helper "hatch" entities.
5. **Rendering is deferred.** `extractRenderState(entity, state, partialTick)` copies data; `submit(state, poseStack,
   SubmitNodeCollector, CameraRenderState)` records nodes; `FeatureRenderDispatcher` executes them later in one render
   pass. `submitCustomGeometry(poseStack, RenderType, (pose, vertexConsumer) -> …)` is the hook for arbitrary procedural
   triangles and quads.
6. **"Improved transparency" (OIT) is a user toggle**, off by default. Any translucent `RenderType` (one with blending)
   **must** carry an `OitPipelineSet`, or the game **crashes** when OIT is on: `"Render type X does not have OIT
   pipelines set up."`. The same applies to translucent particle `Layer`s. Additive effects can reuse vanilla's
   `RenderPipelines.OIT_LIGHTNING` / `OIT_ENERGY_SWIRL`.
7. **All GLSL goes through shaderc to SPIR-V (Vulkan 1.2 environment).** The OpenGL backend then translates it back to
   GLSL 330 with SPIRV-Cross. Write shaders like the vanilla ones: `#version 330`,
   `#extension GL_ARB_separate_shader_objects : require`, `#include <ns:file.glsl>`, explicit `layout(location=…)`,
   vertex inputs **named** like the vertex-format elements, and every uniform declared in the pipeline's bind group
   layouts. Custom pipelines can reuse vanilla shaders through defines; new shaders are optional.
8. **Static GPU meshes are possible** with `GpuDevice.createBuffer(...)`, as `SkyRenderer` does. Drawing one needs a
   `RenderPass`, and the only clean in-pass hook is a Fabric **custom `FeatureRenderer`**
   (`FeatureRendererRegistry` + `submitCustom`). Re-emitting about 14k quads per frame through `submitCustomGeometry`
   uses `BufferBuilder`'s ENTITY-format fast path and should be cheap. Recommendation: use immediate geometry plus LODs
   first.
9. **Particles:** `ParticleRenderType` is now just a *group* key. Each group is capped at **16 384** particles, with
   probabilistic rejection from 12 288 up. Blending comes from `SingleQuadParticle.Layer`, a public record that a mod can
   instantiate with its own (additive) pipeline. Fabric's `ParticleGroupRegistry` adds a private group with its own
   budget. On the server, `sendParticles(..., overrideLimiter=true, ...)` reaches 512 blocks instead of 32.
10. **No Fabric event exists to veto dismounting.** Shift-dismount happens in `Player.rideTick()` → `wantsToStopRiding()`
    on the server. A 3-line mixin on `Player#wantsToStopRiding` is the minimal lock.

---

## 1. Entity type registration

### 1.1 `EntityType.Builder` (`common/net/minecraft/world/entity/EntityType.java:464-628`)

```java
public static class Builder<T extends Entity> implements net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityType.Builder<T> {
    // defaults (lines ~466-484)
    private boolean serialize = true;  private boolean summon = true;  private boolean fireImmune;
    private int clientTrackingRange = 5;           // in CHUNKS
    private int updateInterval = 3;                // ticks
    private EntityDimensions dimensions = EntityDimensions.scalable(0.6F, 1.8F);
    private boolean trackDeltas = true;
    public static <T extends Entity> Builder<T> of(EntityType.EntityFactory<T> factory, MobCategory category); // :491
    public static <T extends Entity> Builder<T> createNothing(MobCategory category);
    public Builder<T> sized(float width, float height);                 // :499 (EntityDimensions.scalable)
    public Builder<T> spawnDimensionsScale(float scale);
    public Builder<T> eyeHeight(float eyeHeight);                       // :509
    public Builder<T> passengerAttachments(float... offsetYs);          // :514
    public Builder<T> passengerAttachments(Vec3... points);             // :522 (seat points, rotated by yRot)
    public Builder<T> vehicleAttachment(Vec3 point);
    public Builder<T> ridingOffset(float ridingOffset);
    public Builder<T> nameTagOffset(float nameTagOffset);
    public Builder<T> attach(EntityAttachment a, float x, float y, float z);
    public Builder<T> noSummon();                                       // :552
    public Builder<T> noSave();                                         // :557  (DON'T use for vehicles: see §2.3)
    public Builder<T> fireImmune();                                     // :562
    public Builder<T> immuneTo(TagKey<Block> tag);
    public Builder<T> canSpawnFarFromPlayer();
    public Builder<T> clientTrackingRange(int clientChunkRange);        // :577
    public Builder<T> updateInterval(int updateInterval);               // :582
    public Builder<T> noUpdateInterval();                               // :587 (= Integer.MAX_VALUE → UpdateInterval.NEVER)
    public Builder<T> requiredFeatures(FeatureFlag... flags);
    public Builder<T> noLootTable();                                    // :596
    public Builder<T> notInPeaceful();
    public Builder<T> dontTrackDeltas();                                // :606
    public EntityType<T> build(ResourceKey<EntityType<?>> name);        // :611
}
@FunctionalInterface public interface EntityFactory<T extends Entity> { @Nullable T create(EntityType<T> entityType, Level level); }
```

`build()` calls `Util.fetchChoiceType(References.ENTITY_TREE, id)` when `serialize` is true (`:612-614`). That only
logs an error unless `SharedConstants.CHECK_DATA_FIXER_SCHEMA` is set (`common/net/minecraft/util/Util.java:309-327`).
Fabric wraps the call and returns `null` for non-`minecraft` namespaces
(`fabric/net/fabricmc/fabric/mixin/object/builder/EntityTypeBuilderMixin.java`, `allowNoModdedDatafixers`).

Vanilla registration helper (`common/net/minecraft/world/entity/EntityTypes.java:1155`):
```java
private static <T extends Entity> EntityType<T> register(final ResourceKey<EntityType<?>> id, final EntityType.Builder<T> builder) {
    return Registry.register(BuiltInRegistries.ENTITY_TYPE, id, builder.build(id));
}
```
Keys come from `EntityTypeIds.create(name)` = `ResourceKey.create(Registries.ENTITY_TYPE, Identifier.withDefaultNamespace(name))`.
`Registry.register` has these overloads (`common/net/minecraft/core/Registry.java:106-114`): `(Registry, String, T)`,
`(Registry<V>, Identifier, T)` and `(Registry<V>, ResourceKey<V>, T)`.

Relevant vanilla precedents (`EntityTypes.java`):
```java
ENDER_DRAGON  = ...of(EnderDragon::new, MONSTER).fireImmune().sized(16.0F, 8.0F).passengerAttachments(3.0F).clientTrackingRange(10)      // :377
END_CRYSTAL   = ...of(EndCrystal::new, MISC).noLootTable().fireImmune().sized(2.0F, 2.0F).clientTrackingRange(16).noUpdateInterval().dontTrackDeltas() // :385
LIGHTNING_BOLT= ...of(LightningBolt::new, MISC).noLootTable().noSave().sized(0.0F, 0.0F).clientTrackingRange(16).noUpdateInterval()       // :599
MINECART      = ...of(Minecart::new, MISC).noLootTable().sized(0.98F, 0.7F).passengerAttachments(0.1875F).clientTrackingRange(8)          // :658
*_BOAT        = ...noLootTable().sized(1.375F, 0.5625F).eyeHeight(0.5625F).clientTrackingRange(10)                                         // :150
PLAYER        = ...clientTrackingRange(32).updateInterval(2).dontTrackDeltas()                                                              // :1115
```

### 1.2 Fabric helpers (`fabric/net/fabricmc/fabric/api/object/builder/v1/entity/FabricEntityType.java`)

These are interface-injected onto `EntityType.Builder`. `FabricEntityTypeBuilder` no longer exists.
```java
default EntityType.Builder<T> alwaysUpdateVelocity(boolean alwaysUpdateVelocity);        // overrides EntityType#trackDeltas()
default EntityType.Builder<T> canPotentiallyExecuteCommands(boolean b);                  // overrides onlyOpCanSetNbt()
static <T extends LivingEntity> EntityType.Builder<T> createLiving(factory, category, UnaryOperator<Living<T>>); // attributes
static <T extends Mob> EntityType.Builder<T> createMob(factory, category, UnaryOperator<Mob<T>>);               // + spawn placement
```
`FabricDefaultAttributeRegistry` exists but isn't needed: the ship and booster are plain `Entity` subclasses.
`EndCrystal` (`common/.../boss/enderdragon/EndCrystal.java`) is the vanilla template for a non-living entity with synced
data and save data.

---

## 2. Entity basics for a vehicle

### 2.1 Mandatory overrides (`common/net/minecraft/world/entity/Entity.java`)

```java
public Entity(final EntityType<?> type, final Level level)                       // :315; calls defineSynchedData then createInterpolationHandler()
protected abstract void defineSynchedData(SynchedEntityData.Builder entityData); // :426
public abstract boolean hurtServer(ServerLevel level, DamageSource source, float damage); // :1969
public boolean hurtClient(final DamageSource source) { return false; }           // :1971
protected abstract void readAdditionalSaveData(ValueInput input);                // :2268
protected abstract void addAdditionalSaveData(ValueOutput output);               // :2270
```
Synced data (`common/net/minecraft/network/syncher/SynchedEntityData.java`):
```java
public static <T> EntityDataAccessor<T> defineId(Class<? extends SyncedDataHolder> clazz, EntityDataSerializer<T> type); // :29
public <T> SynchedEntityData.Builder define(EntityDataAccessor<T> accessor, T value);                                   // Builder :146
public <T> T get(EntityDataAccessor<T>);  public <T> void set(EntityDataAccessor<T>, T);  set(accessor, value, forceDirty)
```
Useful serializers (`EntityDataSerializers.java:54-146`): `BYTE`, `INT`, `LONG`, `FLOAT`, `STRING`, `BOOLEAN`,
`BLOCK_POS`, `OPTIONAL_BLOCK_POS`, `VECTOR3` (`Vector3fc`), **`QUATERNION` (`Quaternionfc`)** for ship attitude,
`PARTICLE`, `COMPONENT` and others.

`ValueInput` (`common/net/minecraft/world/level/storage/ValueInput.java`): `read(String, Codec<T>)` → `Optional<T>`,
`child(String)`, `childOrEmpty`, `list/listOrEmpty(String, Codec)`, `getBooleanOr`, `getIntOr`, `getLongOr`,
`getFloatOr`, `getDoubleOr`, `getStringOr`, `getInt/getLong/getString` (Optional) and `lookup()`.
`ValueOutput` (`ValueOutput.java`): `store(String, Codec<T>, T)`, `storeNullable`, `putBoolean/Byte/Short/Int/Long/Float/Double/String/IntArray`,
`child(String)`, `childrenList`, `list(String, Codec)` and `discard`. Example from `EndCrystal`:
```java
output.storeNullable("beam_target", BlockPos.CODEC, this.getBeamTarget());
this.setBeamTarget(input.read("beam_target", BlockPos.CODEC).orElse(null));
this.setShowBottom(input.getBooleanOr("ShowBottom", true));
```

### 2.2 Interaction, picking and damage

```java
public InteractionResult interact(final Player player, final InteractionHand hand, final Vec3 location) // :2317  (NOTE the 3rd param)
public boolean isPickable() { return false; }                     // :2054  (must be true to be targeted by the crosshair)
public boolean canBeHitByProjectile()  // = isAlive() && isPickable()
public boolean canBeCollidedWith(final @Nullable Entity other) { return false; } // :2426  (true → solid like a boat/shulker)
public boolean canCollideWith(final Entity e) { return e.canBeCollidedWith(this) && !this.isPassengerOfSameVehicle(e); } // :2422
public boolean isPushable() { return false; }                     // :2062
public @Nullable ItemStack getPickResult() { return null; }       // :3977
public boolean ignoreExplosion(final Explosion explosion)          // :3581
public boolean canUsePortal(final boolean ignorePassenger)         // :3303
```
`InteractionResult` (`common/net/minecraft/world/InteractionResult.java:11-15`) provides `SUCCESS`, `SUCCESS_SERVER`,
`CONSUME`, `FAIL` and `PASS`. The boat pattern (`common/.../vehicle/boat/AbstractBoat.java:720`):
```java
public InteractionResult interact(final Player player, final InteractionHand hand, final Vec3 location) {
    InteractionResult superInteraction = super.interact(player, hand, location);   // leash/shears handling
    if (superInteraction != InteractionResult.PASS) return superInteraction;
    return player.isSecondaryUseActive() || !(this.outOfControlTicks < 60.0F) || !this.level().isClientSide() && !player.startRiding(this)
        ? InteractionResult.PASS : InteractionResult.SUCCESS;
}
```
Crosshair picking (`client/net/minecraft/client/player/LocalPlayer.java:~1245-1257`) searches
`cameraEntity.getBoundingBox().expandTowards(dir*reach).inflate(1)` with `EntitySelector.CAN_BE_PICKED`
(= `Entity::isPickable`). See §2.8 for why that fails on tall entities.

### 2.3 Passengers

| Member | Signature / behaviour | Line |
|---|---|---|
| mount | `public final boolean startRiding(Entity e)` → `startRiding(e, false, true)` | :2470 |
| | `public boolean startRiding(Entity v, boolean force, boolean sendEventAndTriggers)`. The server **refuses if `!v.type.canSerialize()`** (so a `noSave()` vehicle cannot be ridden); then `force \|\| (canRide(v) && v.canAddPassenger(this))` | :2478-2516 |
| rider check | `protected boolean canRide(Entity v) { return !isShiftKeyDown() && boardingCooldown <= 0; }` | :2518 |
| capacity | `protected boolean canAddPassenger(Entity p) { return passengers.isEmpty(); }`, `protected boolean couldAcceptPassenger()` | :2587, :2591 |
| | `getMaxPassengers()` is **boat-only** (`AbstractBoat.java:773`); there is none on `Entity` | |
| ordering | `addPassenger`: on the server a `Player` is inserted at index 0 when the first passenger isn't a player | :2554 |
| controller | `public @Nullable LivingEntity getControllingPassenger() { return null; }`. Keep it null for a server-authoritative ship | :3616 |
| authority | `isLocalInstanceAuthoritative()`: client → `isLocalClientAuthoritative()` (controller is the local player); server → `!isClientAuthoritative()` | :3695-3707 |
| positioning | `public final void positionRider(Entity p)` → `positionRider(p, Entity::setPos)`; `protected void positionRider(Entity p, Entity.MoveFunction f)` uses `getPassengerRidingPosition(p) - p.getVehicleAttachmentPoint(this)` | :2438-2448 |
| seat point | `public Vec3 getPassengerRidingPosition(Entity p)` = `position() + getPassengerAttachmentPoint(p, dimensions, 1)` | :2457 |
| | `protected Vec3 getPassengerAttachmentPoint(Entity p, EntityDimensions d, float scale)`. The default uses the type's `PASSENGER` attachments indexed by passenger index and rotated by `-yRot` only (`EntityAttachments.java:68-79`) | :2461 |
| head clamp | `public void onPassengerTurned(Entity p)`; the boat clamps the rider's yaw to ±105° in `clampRotation` | :2450, boat :681-707 |
| dismount spot | `public Vec3 getDismountLocationForPassenger(LivingEntity p) { return new Vec3(getX(), getBoundingBox().maxY, getZ()); }`. **The default puts riders on top of the AABB, 50+ blocks up.** Override it. | :3733 |
| HUD | `public boolean showVehicleHealth()` (default `this instanceof LivingEntity`) | :2474 |

**Riders are positioned the same way on both sides** after the vehicle has ticked. Server: `ServerLevel.tickPassenger`
(`common/.../server/level/ServerLevel.java:842-851`) runs `passenger.commonTick(); passenger.rideTick();`. Client:
`ClientLevel.tickPassenger` (`client/.../multiplayer/ClientLevel.java:495-500`) does the same. `Entity.rideTick()`
(`:2430`) runs `setDeltaMovement(ZERO); tick(); getVehicle().positionRider(this)`. The server **never sends positions
for passengers**: `ServerEntity.sendChanges` sends only `MoveEntityPacket.Rot` when `entity.isPassenger()`
(`ServerEntity.java:~138-148`). When a position sync for the vehicle arrives and the local player rides it, the client
re-positions that player immediately
(`client/.../multiplayer/ClientPacketListener.java:650-675`: `entity.positionRider(this.minecraft.player); player.setOldPosAndRot();`).
Server movement packets from a riding player only update rotation
(`common/.../server/network/ServerGamePacketListenerImpl.java:~1116`). Passengers are excluded from the
"floating too long" kick (`:331`), and the vehicle floating check only applies to the *controlling* passenger (`:350`).
`LocalPlayer` sends `ServerboundPlayerInputPacket(keyPresses)` every tick, and sends `ServerboundMoveVehiclePacket`
only when the root vehicle `isLocalInstanceAuthoritative()` (`LocalPlayer.java:269-277`). The server reads rider input
via `ServerPlayer.getLastClientInput()` (`ServerPlayer.java:2230`), for example "jump = go for launch".

When the client receives `ClientboundSetPassengersPacket`, it shows the overlay `"mount.onboard"` ("Press Shift to
dismount") through `minecraft.gui.hud.setOverlayMessage(message, false)` (`ClientPacketListener.java:1118-1150`).
The mod can overwrite that message right after mounting.

### 2.4 Dismounting and how to prevent it

Path: `Player.rideTick()` (`common/net/minecraft/world/entity/player/Player.java:435`)
```java
if (!this.level().isClientSide() && this.wantsToStopRiding() && this.isPassenger()) { this.stopRiding(); } else { super.rideTick(); }
protected boolean wantsToStopRiding() { return this.isShiftKeyDown(); }   // :298
```
`LivingEntity.stopRiding()` (`LivingEntity.java:3257`) → `removeVehicle()` → `dismountVehicle(old)` (`:2317`), which
teleports to `vehicle.getDismountLocationForPassenger(this)` unless the vehicle was removed or stands in a portal.
`ServerPlayer.removeVehicle()` (`ServerPlayer.java:2162`) resends `ClientboundSetPassengersPacket`. The client never
dismounts on its own for vehicles it doesn't control. Fabric has **no** mount or dismount event; a grep of `fabric/`
for `dismount|stopRiding` found nothing.

To prevent dismounting:
- **Recommended:** a small mixin. `@Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)` on
  `Player`, returning `false` while `getVehicle() instanceof StarshipEntity s && s.isDismountLocked()`. It is also read
  by `ServerPlayer.tick` for spectator cameras (`ServerPlayer.java:600`), which is harmless.
- Vetoing in `removePassenger` is impossible. `removeVehicle()` clears `this.vehicle` first and then calls
  `old.removePassenger(this)` (`:2538-2548`), so refusing there corrupts state.
- Commands (`/ride … dismount`) and death still dismount, which is acceptable.
- Cross-dimension `Entity.teleport(TeleportTransition)` keeps passengers: it ejects them, teleports each, creates the new
  vehicle via `type.create(newLevel, DIMENSION_TRAVEL)`, `restoreFrom(this)`, and re-mounts with
  `startRiding(newEntity, true, false)` (`Entity.java:3142-3204`). `restoreFrom` copies saved NBT, so the flight state
  must be fully stored in `addAdditionalSaveData`.

### 2.5 Server → client synchronisation (`common/net/minecraft/server/level/ServerEntity.java`, `ChunkMap.java`)

- `ChunkMap.addEntity` (`ChunkMap.java:1130-1152`): `range = type.clientTrackingRange() * 16`. If it is 0, the entity
  is **never tracked**. `UpdateInterval.periodic(updateInterval)` is used, or `NEVER` (`common/.../entity/UpdateInterval.java`).
- `ChunkMap.tick` (`:~1170-1190`) calls `serverEntity.sendChanges()` only when the section position changed, when
  `entity.needsSync` is set, or when the chunk is in entity-ticking range.
- `ServerEntity.sendChanges()` (`ServerEntity.java:~96-205`). A movement update happens when
  `entity.needsSync || updateInterval.test(tickCount) || entityData.isDirty()`.
  - Passenger: rotation only (see above).
  - Otherwise `createMovePacket(...)` (`:~207-228`) chooses:
    - a full **`ClientboundEntityPositionSyncPacket`** (absolute doubles) when `getRequiresPrecisePosition()`, when
      `teleportDelay > 400`, after riding, when onGround flips, or when the delta exceeds the
      `VecDeltaCodec` short range. Deltas are encoded at 1/4096 block, so the limit is about **±8 blocks per update**
      (`common/net/minecraft/network/protocol/game/VecDeltaCodec.java:~10-35`, `isDeltaTooBig`);
    - else `MoveEntityPacket.PosRot/Pos/Rot`. Position is sent if it moved (squared distance ≥ 7.63e-6) or every
      60 ticks (`FORCED_POS_UPDATE_PERIOD`).
  - Velocity: `ClientboundSetEntityMotionPacket` is sent when `needsSync || trackDelta || fallFlying` **and** the velocity
    changed by more than 1e-7 (squared). `trackDelta` = `EntityType.trackDeltas()`, which Fabric's
    `alwaysUpdateVelocity` can override. `entity.syncVelocity` forces one immediately.
  - Dirty `SynchedEntityData` goes out in the same update (`sendDirtyEntityData`).
- `Entity` public fields: `needsSync`, `syncPosition`, `syncVelocity` (`Entity.java:283-285`) and `noPhysics` (`:254`).
  Setting **`needsSync = true` every flight tick** forces a sync per tick even when the type's `updateInterval` is
  larger. `setRequiresPrecisePosition(true)` (`:~392`) makes every position update absolute.

### 2.6 Client interpolation (new in 26.x)

`Entity.createInterpolationHandler()` returns `InterpolationHandler.NO_OP` by default (`Entity.java:2630`). Incoming
packets call `moveOrInterpolateTo(...)` (`:2599-2625`). With `NO_OP`, `interpolateTo` returns false, so the entity
**snaps** via `setPos/setRot`. Smoothness then depends only on the renderer lerping `xOld → x` with `partialTick`, and
the client sets `xOld = x` in `commonTick()` (`:~523-532`) before `interpolate()` and `tick()`.

```java
public interface InterpolationHandler {                                   // common/.../entity/InterpolationHandler.java
    InterpolationHandler NO_OP = ...;
    default InterpolationTracker interpolationTracker() { return InterpolationTracker.NO_OP; }  // server-side step recorder
    @Nullable PositionAndRotation target();
    boolean interpolateTo(@Nullable PositionPath position, float yRot, float xRot, boolean hasRotation); // return false → caller snaps
    void interpolate();                 // called each client tick from Entity.commonTick()
    void applyPredictedMovement(Vec3 delta);
    boolean hasActiveInterpolation();
    void cancel();
}
```
Implementations:
- `LinearInterpolationHandler.create(Entity e, int steps)`, client only, with the server getting `NO_OP`. It lerps toward
  the latest target over `steps` ticks; the default is 3. Boats and old-behaviour minecarts use it
  (`AbstractBoat.java:198`, `AbstractMinecart.java:348`).
- `SteppedInterpolationHandler.create(Entity e)`. The client gets a stepped player; the **server gets a
  `SteppedInterpolationTracker`** that records a `PositionStep` whenever `entity.syncPosition` is set and ships the
  steps as a `PositionPath.Stepped` inside the next move packet. `LivingEntity` uses it (`LivingEntity.java:3272`), and
  the interpolation length is `type.updateInterval()`. **[UNVERIFIED for non-living use]** Setting
  `syncPosition = true` every server tick should replay the exact per-tick trajectory on clients with a delay of
  `updateInterval` ticks.
- A custom handler can return `true` from `interpolateTo` to swallow packets, for example to blend server corrections
  into a client-side deterministic flight-profile prediction. **[UNVERIFIED design]**

Client packet handlers (`ClientPacketListener.java`):
- `handleMoveEntity` (`:724`) → `moveOrInterpolateTo(pos[, yRot, xRot])` (no distance check).
- `handleEntityPositionSync` (`:650-675`): `tooBigToInterpolate = entity.position().distanceToSqr(pos) > 4096.0`. A
  **jump of more than 64 blocks, or a lagging client copy that far behind, snaps (`snapTo`)**, and so does an entity
  that isn't ticking.
- `handleSetEntityMotion` (`:634`) → `entity.lerpMotion(movement)`, which by default is `setDeltaMovement`.

Do not call `move(MoverType.SELF, …)` on the client for a server-authoritative entity. `Entity.move` asserts
`canSimulateMovement()` when `SharedConstants.IS_RUNNING_IN_IDE` is set (`Entity.java:737-740`). Prefer
`noPhysics = true` with kinematic `setPos` on the server.

### 2.7 Tracking range (`ChunkMap.TrackedEntity`, `ChunkMap.java:1308-1420`)

```java
public void updatePlayer(final ServerPlayer player) {                                         // :1371
    Vec3 deltaToPlayer = player.position().subtract(this.entity.position());
    int playerViewDistance = ChunkMap.this.getPlayerViewDistance(player);                    // clamp(requestedViewDistance, 2, serverViewDistance) :812
    double visibleRange = Math.min(this.getEffectiveRange(), playerViewDistance * 16);
    double distanceSquared = deltaToPlayer.x * deltaToPlayer.x + deltaToPlayer.z * deltaToPlayer.z;   // HORIZONTAL ONLY
    boolean visibleToPlayer = distanceSquared <= rangeSquared && this.entity.broadcastToPlayer(player)
        && ChunkMap.this.isChunkTracked(player, this.entity.chunkPosition().x(), this.entity.chunkPosition().z());
```
- `getEffectiveRange()` (`:1400`) is the max of the type's range and **all indirect passengers' ranges**, so a player
  passenger contributes 32 chunks = 512 blocks. It is then scaled by `server.getScaledTrackingDistance(range)`.
  - Dedicated server: `entityBroadcastRangePercentage * range / 100` (`DedicatedServer.java:731`).
  - Integrated server: `options.entityDistanceScaling() * range` (`client/.../server/IntegratedServer.java:439`).
- **Answer:** `clientTrackingRange` **is clamped by the player's view distance**, and the entity's chunk must be in the
  player's chunk-tracking view. Height is ignored. Use `clientTrackingRange(64)` so the player's view distance is the
  only cap. Spectators 512 blocks away horizontally need 32-chunk view distance, which isn't realistic on 8 GB.
- `public boolean broadcastToPlayer(ServerPlayer)` (`Entity.java:3544`) can hide the entity per player.
- Above build height still works: `checkBelowWorld` only kills below `minY - 64` (`Entity.java:605`), and
  `Level.MAX_ENTITY_SPAWN_Y = 20000000`.

### 2.8 Big AABB implications

- `EntitySectionStorage.forEachAccessibleNonEmptySection(AABB bb, …)`
  (`common/net/minecraft/world/level/entity/EntitySectionStorage.java:37-43`):
  ```java
  int xMin = SectionPos.posToSectionCoord(bb.minX - 2.0);  int yMin = SectionPos.posToSectionCoord(bb.minY - 4.0);
  int zMin = SectionPos.posToSectionCoord(bb.minZ - 2.0);  int xMax = SectionPos.posToSectionCoord(bb.maxX + 2.0);
  int yMax = SectionPos.posToSectionCoord(bb.maxY + 0.0);  int zMax = SectionPos.posToSectionCoord(bb.maxZ + 2.0);
  ```
  An entity is stored in the 16³ section that contains its **position**, which for the ship is the skirt bottom. Any
  `level.getEntities(...)` query (crosshair pick, `getEntityCollisions`, `push`) near the upper hull, or more than
  about 2 blocks beyond a section border horizontally, **won't find it**. `Level.getEntities` special-cases only
  `EnderDragonPart` (`Level.java:768-784`). Fabric has no multipart API.
- Collisions: `EntityGetter.getEntityCollisions` (`common/.../level/EntityGetter.java:54-72`) treats other entities'
  AABBs as solid when `source.canCollideWith(e)`, which requires `e.canBeCollidedWith(source)`. Recommendation: return
  `false`, so the hull isn't solid, and use blocks (pad, tower) for physical contact.
- Make the collision box independent of `EntityType` dimensions by overriding
  `protected AABB makeBoundingBox(Vec3 position)` (`Entity.java:~493`). Override `getDimensions(Pose)` and call
  `refreshDimensions()` for dynamic sizes such as deployed legs.
- The culling box is separate: `EntityRenderer.getBoundingBoxForCulling(T, float)` (protected) and
  `affectedByCulling(T)` (§3.1).

### 2.9 Client visibility pipeline (why a rocket disappears)

1. `LevelExtractor.extractVisibleEntities` (`client/net/minecraft/client/renderer/extract/LevelExtractor.java:252-277`)
   sets `Entity.setViewScale(clamp(renderDistance/8, 1, 2.5) * entityDistanceScaling)` and calls `isEntityVisible`.
2. `isEntityVisible` (`:280-292`):
   `dispatcher.shouldRender(entity, frustum, cam…) || entity.hasIndirectPassenger(localPlayer)`, **and**
   `level.isOutsideBuildHeight(blockPos.y) || levelRenderer.isSectionCompiledAndVisible(blockPos, fade)`.
   `isSectionCompiledAndVisible` (`LevelRenderer.java:1246`) only checks that the origin section has been compiled and
   faded in to at least 0.3; it does no occlusion test. **Riding the ship always renders it**, and so does being above
   build height.
3. `EntityRenderer.shouldRender` (`client/.../entity/EntityRenderer.java:62-89`) first calls
   `entity.shouldRender(camX,camY,camZ)` → `shouldRenderAtSqrDistance(d²)`. It then skips culling if
   `!affectedByCulling(entity)`; otherwise it tests `culler.isVisible(getBoundingBoxForCulling(entity, pt).inflate(0.5))`.
4. `Entity.shouldRenderAtSqrDistance` (`Entity.java:2080`): `size = bb.getSize()*64*viewScale` (`getSize()` = mean edge
   length). For a 9×52×9 box that is about 1490 × viewScale blocks.
5. **The frustum has a far plane.** `Camera.createProjectionMatrixForCulling` (`client/net/minecraft/client/Camera.java:~178`)
   uses `depthFar = max(renderDistanceBlocks * 4, cloudRange*16)` (`Camera.java:95`). The draw projection uses the same
   `depthFar` with **reversed Z**: near/far swapped in `Projection.getMatrix` (`client/.../renderer/Projection.java:~62`),
   and every depth state is `GREATER_THAN_OR_EQUAL`. Geometry beyond `depthFar` is clipped.
6. **Fog:** `assets/shaders/include/fog.glsl` has `fog_cylindrical_distance(pos) = max(length(pos.xz), abs(pos.y))`,
   blended between `FogRenderDistanceStart/End`. Those are `renderDistanceBlocks - clamp(rd/10, 4, 64)` and
   `renderDistanceBlocks` (`client/.../fog/FogRenderer.java:172-176`). A ship more than `renderDistance*16` blocks up is
   fully fog-coloured. `CameraRenderState.fogData.renderDistanceStart` and `CameraRenderState.depthFar` are filled
   during extraction (`GameRenderer.java:~771`, `Camera.java:~135`), so they are available inside `submit(...)`.
7. Particles are culled with the same frustum: `particleEngine.extract(…, new Frustum(cullFrustum).offset(-3), …)`
   (`LevelExtractor.java:~226`).

**Far-impostor technique [UNVERIFIED visually].** In `submit`, the pose stack is already translated to
`rel = entityPos - cameraPos`. If `|rel| > D` with `D = 0.9*min(depthFar, fogData.renderDistanceStart)`, apply
`translate(-rel*(1-s))` and then `scale(s)` with `s = D/|rel|`. Angular size is preserved and the geometry stays inside
the depth range and the clear fog band. Compute `s` from one shared reference, the root vehicle, so the ship and
booster stay aligned. Also return `true` from `shouldRender`, or override `affectedByCulling` to `false`. Terrain closer
than D but farther than the true distance will incorrectly sort behind the ship, which is acceptable for the sky.

---

## 3. Entity renderers

### 3.1 `EntityRenderer<T extends Entity, S extends EntityRenderState>` (`client/net/minecraft/client/renderer/entity/EntityRenderer.java`)

```java
protected EntityRenderer(final EntityRendererProvider.Context context)                  // :44
protected float shadowRadius; protected float shadowStrength = 1.0F;                     // default radius 0 → no shadow; clamped to 32
public boolean shouldRender(T entity, Frustum culler, double camX, double camY, double camZ, float partialTicks) // :62
protected AABB getBoundingBoxForCulling(T entity, float partialTicks) { return entity.getInterpolatedBoundingBox(partialTicks); } // :91
protected boolean affectedByCulling(T entity) { return true; }                         // :95
public Vec3 getRenderOffset(S state)
public void submit(S state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) // :103 (super submits leashes + name tag)
public abstract S createRenderState();                                                 // :143
public final S createRenderState(T entity, float partialTicks) { S s = createRenderState(); extractRenderState(entity, s, partialTicks); finalizeRenderState(entity, s); return s; } // NEW state object every frame
public void extractRenderState(T entity, S state, float partialTicks)                  // :153  (sets x/y/z lerped, ageInTicks, lightCoords, distanceToCameraSq, outlineColor…)
protected void finalizeRenderState(T entity, S state)                                  // shadow extraction
public final int getPackedLightCoords(T entity, float partialTickTime)                 // block+sky light at entity.getLightProbePosition()
protected int getSkyLightLevel(T, BlockPos) / getBlockLightLevel(T, BlockPos)
```
`EntityRenderState` (`…/entity/state/EntityRenderState.java`) fields: `entityType`, `x`, `y`, `z`, `ageInTicks`,
`boundingBoxWidth/Height`, `eyeHeight`, `distanceToCameraSq`, `isInvisible`, `isDiscrete`, `displayFireAnimation`,
`lightCoords` (default 15728880), `outlineColor`, `passengerOffset`, `nameTag`, `scoreText`, `nameTagAttachment`,
`leashStates`, `shadowRadius` and `shadowPieces`. It implements Fabric `FabricRenderState`, which offers
`getData/setData(RenderStateDataKey<T>)` for extra data
(`fabric/.../client/rendering/v1/FabricRenderState.java`, `RenderStateDataKey.create(...)`).

Because a fresh state is created per frame and the submit callbacks run later on the render thread
(`FeatureRenderDispatcher.prepareFrame`), lambdas can safely capture the state object.

### 3.2 `EntityRendererProvider` and registration

```java
@FunctionalInterface public interface EntityRendererProvider<T extends Entity> { EntityRenderer<T, ?> create(Context context); }
class Context { getEntityRenderDispatcher(); getBlockModelResolver(); getItemModelResolver(); getMapRenderer(); getResourceManager();
                getModelSet(); getEquipmentAssets(); getEquipmentRenderer(); getSprites(); getAtlas(Identifier); ModelPart bakeLayer(ModelLayerLocation); getFont(); getPlayerSkinRenderCache(); }
```
(`client/.../entity/EntityRendererProvider.java`)

- Register with **`EntityRenderers.register(EntityType<? extends T>, EntityRendererProvider<T>)`**
  (`client/.../entity/EntityRenderers.java:31`). It is made public by Fabric's transitive access widener (classtweaker
  line 45).
- Fabric's `EntityRendererRegistry.register(...)` still exists but is `@Deprecated`, with the javadoc "Replaced with
  transitive access wideners" (`fabric/.../client/rendering/v1/EntityRendererRegistry.java`). There is no
  `EntityRendererFactories`.
- Every registered `EntityType` needs a renderer: `getRenderer` returns null otherwise, and `validateRegistrations`
  warns. Use **`NoopRenderer::new`** (`client/.../entity/NoopRenderer.java`) for invisible helpers.
- Renderers are rebuilt on every resource reload: `EntityRenderDispatcher.onResourceManagerReload` →
  `EntityRenderers.createEntityRenderers(context)` (`EntityRenderDispatcher.java:201-217`).

### 3.3 Dispatch flow

1. **Extract:** `LevelExtractor.extract` → `extractVisibleEntities` → `EntityRenderDispatcher.extractEntity(entity, pt)`
   (`EntityRenderDispatcher.java:127`) → `renderer.createRenderState(entity, pt)`. The results go into
   `levelRenderState.entityRenderStates`.
2. **Submit:** `LevelRenderer.render` (`LevelRenderer.java:185`) → `submitFeatures` (`:327`) → `submitEntities`
   (`:974`). For each state it calls `dispatcher.submit(state, cameraState, state.x-camX, state.y-camY, state.z-camZ,
   poseStack, storage)` (`EntityRenderDispatcher.java:142-185`), which pushes, translates to the camera-relative
   position, and calls `renderer.submit(...)`. After that come the flame (if `displayFireAnimation`) and shadow pieces.
   The PoseStack starts as identity: camera rotation lives in `RenderSystem` model-view, not in the PoseStack.
3. **Prepare and execute:** `FeatureRenderDispatcher.prepareFrame(storage)` sorts nodes into phases, calls each
   `FeatureRenderer.prepareGroup` (where your custom-geometry lambdas run), and uploads one shared
   `StagedVertexBuffer`. Then the main render pass executes the phases (§4.2).

### 3.4 Vanilla patterns

**Boat** (`client/.../entity/AbstractBoatRenderer.java`) shows an animated cube model:
```java
public void submit(final BoatRenderState state, final PoseStack poseStack, final SubmitNodeCollector c, final CameraRenderState camera) {
    poseStack.pushPose();
    poseStack.translate(0.0F, 0.375F, 0.0F);
    poseStack.rotateDegrees(Axis.YP, 180.0F - state.yRot);
    ... poseStack.scale(-1.0F, -1.0F, 1.0F); poseStack.rotateDegrees(Axis.YP, 90.0F);
    c.submitModel(this.model(), state, poseStack, this.texture, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
    poseStack.popPose();
    super.submit(state, poseStack, c, camera);
}
public void extractRenderState(final AbstractBoat entity, final BoatRenderState state, final float partialTicks) {
    super.extractRenderState(entity, state, partialTicks);
    state.yRot = entity.getYRot(partialTicks); state.hurtTime = entity.getHurtTime() - partialTicks; ...
}
```
**Lightning** (`client/.../entity/LightningBoltRenderer.java`) shows procedural *additive* geometry, untextured
POSITION_COLOR quads:
```java
submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buffer) -> {
    Matrix4fc poseMatrix = pose.pose();
    ... buffer.addVertex(poseMatrix, x, y, z).setColor(0.45F, 0.45F, 0.5F, 0.3F);   // 4 vertices per quad
});
protected boolean affectedByCulling(final LightningBolt entity) { return false; }
```
**Ender dragon** (`client/.../entity/EnderDragonRenderer.java`) shows model, emissive eyes, additive rays and a textured
beam:
```java
private static final RenderType EYES = RenderTypes.eyes(DRAGON_EYES_LOCATION);
c.submitModel(this.model, state, poseStack, DRAGON_TEXTURE_LOCATION, state.lightCoords, overlayCoords, state.outlineColor);
c.submitModel(this.model, state, poseStack, EYES, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
c.submitCustomGeometry(poseStack, RenderTypes.dragonRays(), (pose, buffer) -> { pose.rotate(rayRotation); buffer.addVertex(pose, origin).setColor(innerColor); ... }); // TRIANGLES
// crystal beam: full ENTITY vertex = position + color + uv + overlay + light + normal
buffer.addVertex(pose, x, y, z).setColor(-1).setUv(u, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(lightCoords).setNormal(pose, 0.0F, -1.0F, 0.0F);
protected boolean affectedByCulling(final EnderDragon entity) { return false; }
```
**End crystal** (`EndCrystalRenderer.java`) overrides `shouldRender` to OR in "has beam target", and `EndCrystal`
overrides `shouldRenderAtSqrDistance` the same way.

**Beacon (block entity)** (`client/.../blockentity/BeaconRenderer.java`) uses
`RenderTypes.beaconBeam(tex, false/true)` with `submitCustomGeometry`, light 15728880 (full bright),
`shouldRenderOffScreen() → true`, `getViewDistance() = renderDistance*16`, and a horizontal-only `shouldRender`.

---

## 4. SubmitNodeCollector, RenderType and RenderPipeline

### 4.1 Every submit method (`client/net/minecraft/client/renderer/OrderedSubmitNodeCollector.java`, `SubmitNodeCollector.java`)

```java
public interface SubmitNodeCollector extends OrderedSubmitNodeCollector {
    OrderedSubmitNodeCollector order(int order);           // later orders draw after lower ones within each phase (e.g. order(1) overlays)
    interface CustomGeometryRenderer { void render(PoseStack.Pose pose, VertexConsumer buffer); }
}
// OrderedSubmitNodeCollector:
void submitShadow(PoseStack, float radius, List<EntityRenderState.ShadowPiece> pieces);
void submitNameTag(PoseStack, @Nullable Vec3 nameTagAttachment, int offset, Component name, boolean seeThrough, int lightCoords, CameraRenderState camera);
void submitText(PoseStack, float x, float y, FormattedCharSequence string, boolean dropShadow, Font.DisplayMode displayMode, int lightCoords, int color, int backgroundColor, int outlineColor);
void submitTextBackground(PoseStack, float x0, float y0, float x1, float y1, int color, Font.DisplayMode displayMode, int lightCoords);
void submitFlame(PoseStack, EntityRenderState, Quaternionf rotation);
void submitLeash(PoseStack, EntityRenderState.LeashState);
<S> void submitModel(Model<? super S> model, S state, PoseStack, RenderType, int lightCoords, int overlayCoords, int tintedColor, @Nullable UvMapping uvMapping, int outlineColor);
default <S> void submitModel(model, state, poseStack, RenderType, light, overlay, outlineColor);                // tint -1
default <S> void submitModel(model, state, poseStack, Identifier texture, light, overlay, outlineColor);        // model.renderType(texture)
default <S> void submitModel(Model<S>, S, PoseStack, light, overlay, tint, SpriteId sprite, SpriteGetter sprites, outlineColor);
<S> void submitCrumblingOverlay(Model<? super S>, S, PoseStack, RenderType, light, overlay, tint, ModelFeatureRenderer.CrumblingOverlay);
default void submitCrumblingOverlay(ModelPart, PoseStack, RenderType, light, overlay, tint, CrumblingOverlay);
default void submitModelPart(ModelPart, PoseStack, RenderType, light, overlay, @Nullable UvMapping[, int tint[, int outline]]);
void submitMovingBlock(PoseStack, MovingBlockRenderState, int outlineColor);
void submitBlockModel(PoseStack, RenderType, List<BlockStateModelPart> parts, int[] tintLayers, int light, int overlay, int outlineColor);
void submitBreakingBlockModel(PoseStack, List<BlockStateModelPart> parts, int progress, boolean isBlockTranslucent);
void submitShapeOutline(PoseStack, VoxelShape, RenderType, int color, float width, boolean afterTerrain);
void submitItem(PoseStack, ItemDisplayContext, int light, int overlay, int outlineColor, int[] tintLayers, ItemQuads quads, ItemStackRenderState.FoilType);
void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer r);   // :193
void submitQuadParticleGroup(QuadParticleRenderState particles);
void submitGizmoPrimitives(DrawableGizmoPrimitives.Group, CameraRenderState, boolean onTop);
// Fabric (interface-injected): fabric/.../client/rendering/v1/FabricOrderedSubmitNodeCollector.java:40
default <T extends SubmitNode> void submitCustom(SubmitRenderPhase<T> phase, T node);
// Fabric renderer-v1 (fabric/.../client/renderer/v1/render/FabricOrderedSubmitNodeCollector.java): submitBlockModel(...Mesh...), submitBreakingBlockModel(...Mesh...), submitItem(...MeshView...)
```
`submitCustomGeometry` stores `new CustomFeatureRenderer.Submit(poseStack.last().copy(), renderType, renderer)`
(`SubmitNodeCollection.java:427-438`). The lambda is invoked later in `CustomFeatureRenderer.buildGroup`
(`client/.../feature/CustomFeatureRenderer.java`) with a `VertexConsumer` for that `RenderType`. Submits with the same
`RenderType` are batched into one draw (`RenderTypeFeatureRenderer.Group.getOrAddDraw`).

### 4.2 Phase routing and draw order

`SubmitNodeCollection` (`client/.../renderer/SubmitNodeCollection.java:70-115`):
- `submitCustomGeometry` goes to `outline` if `renderType.isOutline()`, to `translucentCustomGeometry` if
  `renderType.hasBlending()`, and to `solid` otherwise.
- `submitModel` goes to `waterMask` for the water mask, to `translucentModels` if `hasBlending() &&
  !forceSolidModelPhase()`, and to `solid` otherwise. If `outlineColor != 0`, it also goes to `outline`.
- With **improved transparency (OIT) on**, every translucent phase is aliased to one `oitTranslucent` phase
  (`:72-84`).

Execution in the main pass (`LevelRenderer.addMainPass` `:383`, `executeSolid` `:506`, `executeClassicTransparency` `:665`;
`FeatureRenderDispatcher.PreparedFrame` `:181-300`):
1. Opaque terrain, then `executeSolid` (all `solid` phases).
2. Classic mode: `executeTranslucent` (shadows, translucentModels, name tags, texts, **translucentCustomGeometry**, …,
   translucentBlocksAndItems), then translucent terrain (water), then `executeTranslucentAfterTerrain` (translucent
   particles), then **clouds**, weather and world border.
   So in classic mode clouds draw *after* translucent custom geometry. A plume without depth writes sits under clouds
   that are actually behind it. **[UNVERIFIED visually]**
3. OIT mode: `executeOit` runs three passes (DEPTH_BOUNDS, TRANSMITTANCE, ACCUMULATE) over `oitTranslucent` plus the
   world border, weather and clouds, then a composite. Each translucent draw uses
   `PreparedRenderType.drawFromBufferOit`, which **throws if the RenderType has no `OitPipelineSet`**
   (`client/.../rendertype/PreparedRenderType.java:29-32`).
4. Outline, see-through and always-on-top passes follow.

The option is `Options.improvedTransparency` (default `false`, `client/net/minecraft/client/Options.java:225`), and
`GameRenderer.useImprovedTransparency()` (`:853`) reads it.

### 4.3 VertexConsumer and vertex formats

`VertexConsumer` (`client/com/mojang/blaze3d/vertex/VertexConsumer.java`):
```java
VertexConsumer addVertex(float x, float y, float z);  setColor(int r,int g,int b,int a); setColor(int argb); setColor(float r,g,b,a);
VertexConsumer setUv(float u, float v); setUv1(int,int); setUv2(int,int); setLight(int packed); setOverlay(int packed); setNormal(float,float,float);
default VertexConsumer addVertex(PoseStack.Pose pose, float x, float y, float z);   // allocates a Vector3f per call
default VertexConsumer addVertex(Matrix4fc pose, float x, float y, float z);
default VertexConsumer setNormal(PoseStack.Pose pose, float x, float y, float z);   // allocates
default void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords, float nx, float ny, float nz); // bulk
```
- `BufferBuilder` (`BufferBuilder.java:62-65, 299-330`) has a **direct-memory fast path for the bulk `addVertex`**
  when the format is exactly `DefaultVertexFormat.ENTITY` or `BLOCK`. `ModelPart.Cube.compile` uses this pattern: a
  scratch `Vector3f`, `pose.transformNormal`, then bulk `addVertex` (`client/.../model/geom/ModelPart.java:~323-340`).
- `BufferBuilder` throws `"Missing elements in vertex: …"` unless **every element of the format** is written
  (`:137-145`). Elements not in the format are ignored silently (`beginElement` returns -1).
- `DefaultVertexFormat` (`DefaultVertexFormat.java:27-110`) defines:
  - `ENTITY` = Position(RGB32F), Color(RGBA8), UV0(RG32F), UV1/overlay(RG16 sint), UV2/light(RG16 sint), Normal(RGBA8 snorm) = **36 bytes**;
  - `BLOCK` = Position, Color, UV0, UV2;
  - `PARTICLE` = Position, UV0, Color, UV2;
  - `POSITION_COLOR`, `POSITION_TEX_COLOR`, `POSITION_COLOR_NORMAL` and others.
- Packed values: `LightCoordsUtil.FULL_BRIGHT = 15728880`, `FULL_SKY = 15728640`, `pack(block, sky)`
  (`common/net/minecraft/util/LightCoordsUtil.java:9-13`); `OverlayTexture.NO_OVERLAY = pack(0, 10)`.
- `PoseStack` (`PoseStack.java`) has `translate`, `scale`, `rotate(Quaternionfc)`, `rotate(Axis, rad)`,
  `rotateDegrees(Axis, deg)`, `rotateAround(q, px, py, pz)`, `mulPose(Matrix4fc|Transformation)` and
  `pushPose/popPose/last()`. `PoseStack.Pose` has `pose()` (Matrix4f), `normal()` (Matrix3f), `transformNormal(...)`,
  the same transform ops, and `copy()`. A uniform positive `scale` leaves the normal matrix untouched (`:132-142`).

### 4.4 Where RenderTypes live

- `client/net/minecraft/client/renderer/rendertype/RenderType.java`: `RenderType.create(String name, RenderSetup)`
  (made public by Fabric AW, classtweaker line 128). It exposes `hasBlending()` (true if any color target has a blend
  function), `prepare()`, `format()`, `primitiveTopology()`, `pipeline()`, `outline()`, `isOutline()`,
  `sortOnUpload()` and `forceSolidModelPhase()`.
- `…/rendertype/RenderSetup.java`, `RenderSetup.builder(RenderPipeline)`:
  ```java
  withTexture(String samplerName, Identifier texture)  withTexture(String, Identifier, @Nullable Supplier<GpuSampler>)
  useLightmap() /* binds "Sampler2" */  useOverlay() /* binds "Sampler1" */  affectsCrumbling()  sortOnUpload()
  setLayeringTransform(LayeringTransform)  setTextureTransform(TextureTransform)  setOutline(OutlineProperty[, String])
  setOitPipelines(OitPipelineSet)  withForcedSolidModelPhase()  createRenderSetup()
  ```
  Textures are resolved each frame via `textureManager.getTexture(id)`, so a registered `DynamicTexture` or
  `MipmappedTexture` works (§9).
- `…/rendertype/RenderTypes.java` holds the vanilla factories, memoized per texture. `TextureTransform` (public
  constructor `TextureTransform(String, Supplier<Matrix4f>)`, plus `OffsetTextureTransform(u, v)`) feeds
  `TextureMat`, which shaders only use with `APPLY_TEXTURE_MATRIX`. `LayeringTransform.VIEW_OFFSET_Z_LAYERING[_FORWARD]`
  handles decals.
- `client/net/minecraft/client/renderer/RenderPipelines.java` holds all pipelines and snippets.
  `register(RenderPipeline)` and `register(OitPipelineSet)` add them to the **required** list precompiled on every
  shader reload (`:1383-1398`); `registerOptional` adds optional ones (`:1401`). All three are made public by Fabric AW
  (classtweaker 433-435), as are most `*_SNIPPET` fields (392-431).

### 4.5 Which RenderType for what (all read in `RenderTypes.java` / `RenderPipelines.java`)

| Use | Factory | Pipeline facts | OIT set |
|---|---|---|---|
| (a) opaque, lit, culled hull | `RenderTypes.entitySolid(tex)` (`:567`) | `ENTITY_SOLID` (`:596`): entity shader, no blend, depth write, **cull on**; lightmap + overlay | not needed (solid) |
| (a) opaque, alpha-tested, double-sided | `entityCutout(tex)` (`:587`) / `entityCutoutCull(tex)` | `ALPHA_CUTOUT 0.1`, `PER_FACE_LIGHTING`, cull off / on | not needed |
| (b) translucent | `entityTranslucent(tex)` (`:635`) / `entityTranslucentCull` | TRANSLUCENT blend, depth write ON, cull off, `sortOnUpload` | `OIT_ENTITY` / `OIT_ENTITY_CULL` |
| (c) emissive translucent (eyes) | `eyes(tex)` (`:659`) | `EMISSIVE`+`NO_OVERLAY`+`NO_CARDINAL_LIGHTING`, TRANSLUCENT blend, **no depth write** | `OIT_EYES` |
| (c) emissive translucent, shaded | `entityTranslucentEmissive(tex)` (`:639`) | `EMISSIVE`+`PER_FACE_LIGHTING`, TRANSLUCENT, no depth write, cull off | `OIT_ENTITY_EMISSIVE` |
| (c) beacon-style beam | `beaconBeam(tex, translucent)` (`:647`) | `rendertype_beacon_beam` shader, **BLOCK** vertex format; opaque or TRANSLUCENT+no depth write | `OIT_BEACON_BEAM` (translucent only) |
| (d) additive, untextured | `lightning()` (`:742`) | `rendertype_lightning`, **POSITION_COLOR**, QUADS, `BlendFunction.LIGHTNING` = (SRC_ALPHA, ONE), **depth write ON**, cull on | `OIT_LIGHTNING` (`OIT_ADDITIVE`) |
| (d) additive triangles | `dragonRays()` (`:746`) | as lightning, TRIANGLES | `OIT_DRAGON_RAYS` |
| (d) additive, textured | `energySwirl(tex, u, v)` (`:680`). **A new RenderType per call**, not memoized | entity shader with `EMISSIVE, NO_OVERLAY, NO_CARDINAL_LIGHTING, APPLY_TEXTURE_MATRIX, ALPHA_CUTOUT 0.1`, cull off, `BlendFunction.ADDITIVE` = (ONE, ONE), depth write ON | `OIT_ENERGY_SWIRL` (`OIT_ADDITIVE`) |

`BlendFunction` constants (`client/com/mojang/renderpearl/api/pipeline/BlendFunction.java`): `LIGHTNING` (SRC_ALPHA, ONE),
`ADDITIVE` (ONE, ONE), `TRANSLUCENT`, `TRANSLUCENT_PREMULTIPLIED_ALPHA`, `GLINT`, `OVERLAY`, `INVERT`, `MAX` and
`ENTITY_OUTLINE_BLIT`. In OIT accumulate, `OIT_ADDITIVE` outputs `rgb*a` (`assets/shaders/include/oit_sample.glsl:~93-103`).
For the **same look in classic and OIT mode, prefer `BlendFunction.LIGHTNING`** (which also multiplies by alpha) over
`ADDITIVE`. Keep alpha ≤ 0.99, as `entity.fsh` clamps under `OIT_ADDITIVE`.

### 4.6 Custom RenderPipeline and RenderType from a mod

`com.mojang.renderpearl.api.pipeline.RenderPipeline.Builder` (`client/com/mojang/renderpearl/api/pipeline/RenderPipeline.java:141-426`):
```java
static RenderPipeline.Builder builder(RenderPipeline.Snippet... snippets)
withLocation(String /*minecraft ns*/ | Identifier)        // REQUIRED, unique key in RenderPipelines' map
withVertexShader(String|Identifier)  withFragmentShader(String|Identifier)   // REQUIRED, e.g. "core/entity" or redplanet:core/plume
withShaderDefine(String) / (String,int) / (String,float)
withBindGroupLayout(BindGroupLayout)                       // every uniform/sampler the shader uses (BindGroupLayouts.SAMPLER0, LIGHTING, …)
withPolygonMode(PolygonMode)  withCull(boolean)            // cull defaults to true
withColorTargetState(ColorTargetState) / (int idx, …)      // NO color target → pipeline has none; always set it
withDepthStencilState(DepthStencilState | Optional.empty())
withVertexBinding(int binding, VertexFormat)  withPrimitiveTopology(PrimitiveTopology)  // REQUIRED topology
withPushConstantSize(int ≤ 128)  withSnippet(Snippet)  buildSnippet()  build()
```
Build-time checks: location, vertex and fragment shaders and topology are required; there are at most 16 vertex
attributes; one blend function is shared across all color targets.
`ColorTargetState(BlendFunction)` gives RGBA8 and write-all; `ColorTargetState.DEFAULT` has no blending.
`DepthStencilState(CompareOp, boolean writeDepth[, biasScale, biasConstant])`; `DEFAULT = (GREATER_THAN_OR_EQUAL, true)`
for reversed Z. `PrimitiveTopology` covers `QUADS`, `TRIANGLES`, `TRIANGLE_STRIP`, `TRIANGLE_FAN`, `LINES` and others.

`OitPipelineSet.builder(String locationSuffix, RenderPipeline.Builder base)`
(`client/.../oit/OitPipelineSet.java`) gives `withDepthBoundsModifier`, `withTransmittanceModifier`,
`withAccumulateModifier`, `withoutDepthTest` and `build()`. It builds three pipelines at `pipeline/oit_*_<suffix>`
(minecraft namespace, so **prefix the suffix with `redplanet_`**). OIT snippets only fill color targets that are still
null (`RenderPipeline.Builder.withSnippet`, `:~315`), so **never put `withColorTargetState` in the builder you pass
to `OitPipelineSet.builder`**.

Vanilla reference: an additive textured effect (`RenderPipelines.java:743-765`):
```java
public static final RenderPipeline.Snippet ENERGY_SWIRL_SNIPPET = RenderPipeline.builder(MATRICES_FOG_SNIPPET)
    .withVertexShader("core/entity").withFragmentShader("core/entity")
    .withShaderDefine("ALPHA_CUTOUT", 0.1F).withShaderDefine("EMISSIVE").withShaderDefine("NO_OVERLAY")
    .withShaderDefine("NO_CARDINAL_LIGHTING").withShaderDefine("APPLY_TEXTURE_MATRIX")
    .withBindGroupLayout(BindGroupLayouts.SAMPLER0).withCull(false)
    .withVertexBinding(0, DefaultVertexFormat.ENTITY).withPrimitiveTopology(PrimitiveTopology.QUADS).buildSnippet();
public static final RenderPipeline ENERGY_SWIRL = register(RenderPipeline.builder(ENERGY_SWIRL_SNIPPET)
    .withLocation("pipeline/energy_swirl").withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
    .withDepthStencilState(DepthStencilState.DEFAULT).build());
public static final OitPipelineSet OIT_ENERGY_SWIRL = register(
    OitPipelineSet.builder("energy_swirl", RenderPipeline.builder(ENERGY_SWIRL_SNIPPET).withShaderDefine("OIT_ADDITIVE")).build());
```
Pipeline compilation (`client/net/minecraft/client/renderer/ShaderManager.java:65-100, 176-225`): every required
pipeline is compiled during each resource reload. **A failure throws** `"Failed to load required shader programs"`
and the reload fails; optional pipelines only log a warning. A pipeline that was never registered compiles **lazily and
synchronously** on first `RenderSystem.getCompiledPipeline` (`client/com/mojang/blaze3d/pipeline/PipelineCache.java:get`),
which causes a hitch. Register pipelines during client init, before the first reload. **[UNVERIFIED timing]** Fabric
invokes client entrypoints before the first resource reload.

### 4.7 Shaders: location, includes and backend constraints

- Shader IDs map to `assets/<ns>/shaders/<path>.vsh|.fsh` (`ShaderType` with `FileToIdConverter("shaders", ".vsh")`).
  For example `Identifier.fromNamespaceAndPath("redplanet","core/plume")` resolves to
  `assets/redplanet/shaders/core/plume.vsh` and `.fsh`. Resources are listed across all namespaces
  (`ShaderManager.loadConfigs`, `:103-125`).
- Includes live in `assets/<ns>/shaders/include/<name>.glsl` and are referenced as
  **`#include <ns:name.glsl>`**; vanilla uses `#include <minecraft:fog.glsl>`. `#moj_import` is gone.
  `SHADER_INCLUDE_CONVERTER` is at `ShaderManager.java:51`.
- Vanilla uniform blocks: `DynamicTransforms {ModelViewMat, TextureMat, ColorModulator, ModelOffset}`,
  `Projection {ProjMat}`, `Fog {…}`, `Globals`, and `Lighting {Light0_Direction, Light1_Direction}` (world-space).
  Samplers are `Sampler0` (texture), `Sampler1` (overlay) and `Sampler2` (lightmap).
- **Compile path:** `GlslCompiler.compileToSpv` uses LWJGL **shaderc** with target env `4202496` (Vulkan 1.2),
  `auto_bind_uniforms`, and macros such as `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE`
  (`client/com/mojang/renderpearl/frontend/shaders/GlslCompiler.java:74-131`). The **OpenGL backend cross-compiles
  SPIR-V to GLSL 330 with SPIRV-Cross (`Spvc`)** (`…/backend/opengl/GlPipelineRecompiler.java:53-67`). The Vulkan
  backend uses the SPIR-V directly. macOS gets Vulkan through `VK_KHR_portability_enumeration` (MoltenVK) as well as
  OpenGL (`…/backend/vulkan/VulkanInstance.java:67-90`).
- **Validation** (`client/com/mojang/renderpearl/frontend/shaders/PipelineBuilder.java:~95-300`):
  - vertex shader inputs are matched to vertex-format elements **by name** (`Position`, `Color`, `UV0`, `UV1`, `UV2`,
    `Normal`) and must have compatible base types and component counts;
  - vertex outputs must match fragment inputs by `location`, type, size and interpolation;
  - every descriptor must be declared in the pipeline's `BindGroupLayout`s, or compilation fails with
    `"Unable to find shader defined uniform (X)"`;
  - no `component` decorations; 1D and 3D textures are unsupported.
- Copy the vanilla header exactly:
  `#version 330` / `#extension GL_ARB_separate_shader_objects : require` / `layout(location = N) in/out …`
  (see `assets/shaders/core/rendertype_lightning.vsh`, `entity.vsh`, `entity.fsh`). Use `#ifdef OIT_ALPHA_ONLY` /
  `OIT_ACCUMULATE` / `executeAlphaOnlyPhase` the way vanilla shaders do if the shader must also serve an `OitPipelineSet`.
- **Custom shaders are optional.** Vanilla `core/entity` supports the defines `ALPHA_CUTOUT`, `EMISSIVE`, `NO_OVERLAY`,
  `NO_CARDINAL_LIGHTING`, `PER_FACE_LIGHTING`, `APPLY_TEXTURE_MATRIX`, `DISSOLVE`, `GLINT` and `OIT_ADDITIVE`
  (`assets/shaders/core/entity.vsh/.fsh`). Together with `core/rendertype_lightning`, `core/particle` and
  `core/rendertype_beacon_beam`, they cover every effect in this brief.

---

## 5. Model layers vs procedural mesh

- `ModelLayerLocation(Identifier model, String layer)` is a record (`client/.../model/geom/ModelLayerLocation.java`).
- `LayerDefinition.create(MeshDefinition mesh, int texW, int texH)`, plus `bakeRoot()` and `apply(MeshTransformer)`
  (`…/geom/builders/LayerDefinition.java`).
- `new MeshDefinition().getRoot().addOrReplaceChild(String name, CubeListBuilder cubes, PartPose pose)`
  (`PartDefinition.java:24`). Cubes come from `CubeListBuilder.create().texOffs(u,v).addBox(x0,y0,z0,w,h,d[, CubeDeformation])`
  (axis-aligned boxes in 1/16-block units). `PartPose.offset/rotation/offsetAndRotation(...)`.
- Fabric: **`ModelLayerRegistry.registerModelLayer(ModelLayerLocation, TexturedLayerDefinitionProvider)`**
  (`fabric/.../client/rendering/v1/ModelLayerRegistry.java:38`). The old name was `EntityModelLayerRegistry`.
  Bake with `context.bakeLayer(location)`, which returns a `ModelPart`.
- `Model<S>` / `EntityModel<T extends EntityRenderState>` (`client/.../model/Model.java`, `EntityModel.java`):
  `setupAnim(S state)` (resetPose by default) and `renderToBuffer(PoseStack, VertexConsumer, light, overlay, color)`.
  `setupAnim` runs **during feature preparation** (`ModelFeatureRenderer.prepareModel`: `model.setupAnim(submit.state())`),
  not inside `submit`. The default render type is `RenderTypes::entityCutout`.
- `ModelPart.Polygon(Vertex[] vertices, Vector3fc normal)` uses **one flat normal per face**, and cubes are the only
  geometry the builders create (`ModelPart.java:231-400`). `Polygon` and `Vertex` are made accessible by Fabric AW, but
  `Cube` builds box faces only.

**Recommendation:** don't use model parts for the hull. Smooth 48–64-segment cylinders, an ogive nose and bells need
per-vertex normals and free UVs. Emit the existing `VehicleMesh` float arrays
(`src/main/java/.../starship/geometry/VehicleMesh.java`: per vertex `x y z nx ny nz u v`, quads, per-part `Joint`)
through `submitCustomGeometry` with the bulk `addVertex` and manual matrix maths. Animate flaps, legs and gimbals with a
per-part `pose.copy().rotateAround(quat, px, py, pz)` derived from each `Joint`. Model layers are fine for small blocks
or block entities (plaques, machines).

---

## 6. Large-geometry performance

### 6.1 Immediate path cost (recommended baseline)

All features in a frame share one `StagedVertexBuffer` (`client/net/minecraft/client/renderer/StagedVertexBuffer.java`),
uploaded once per frame (`FeatureRenderDispatcher.prepareFrameWithContext`, `:~78-104`). The current geometry budget is
under 14 000 quads for the HIGH stack and under 4 000 for LOW (`src/test/.../StarshipGeometryTest.java:polygonBudget`).
At HIGH that is about 56k vertices × 36 B ≈ 2 MB per frame. With the ENTITY-format fast path and no per-vertex
allocations, this should cost well under a millisecond on an M2 **[UNVERIFIED, benchmark it]**. Use the LODs
(HIGH/MEDIUM/LOW, chosen from `state.distanceToCameraSq`) and skip hidden parts, such as nozzle interiors far away.

### 6.2 Static GPU buffers (vanilla pattern: `SkyRenderer`)

`client/net/minecraft/client/renderer/SkyRenderer.java:76-101, 265-282, 401-430`:
```java
try (ByteBufferBuilder bb = ByteBufferBuilder.exactlySized(DefaultVertexFormat.POSITION.getVertexSize() * 1500 * 4)) {
    BufferBuilder builder = new BufferBuilder(bb, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION);
    ... builder.addVertex(...);
    try (MeshData mesh = builder.buildOrThrow()) {
        this.starIndexCount = mesh.drawState().indexCount();
        return RenderSystem.getDevice().createBuffer(() -> "Stars vertex buffer", 40 /* VERTEX|COPY_DST */, mesh.vertexBuffer());
    }
}
// draw, inside an open RenderPass:
GpuBuffer indexBuffer = this.quadIndices.getBuffer(this.starIndexCount);      // RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)
GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(modelViewStack), colorModulator);
renderPass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.STARS));
RenderSystem.bindDefaultUniforms(renderPass);                                 // Projection, Fog, Globals, Lighting
renderPass.setUniform("DynamicTransforms", dynamicTransforms);
renderPass.setVertexBuffer(0, this.starBuffer.slice());
renderPass.setIndexBuffer(indexBuffer, this.quadIndices.type());
renderPass.drawIndexed(this.starIndexCount, 1, 0, 0, 0);
```
API facts:
- `GpuDevice.createBuffer(Supplier<String>, int usage, ByteBuffer | long size)` (`…/api/device/GpuDevice.java:43-45`).
- `GpuBuffer.USAGE_*`: MAP_READ 1, MAP_WRITE 2, CLIENT_STORAGE 4, COPY_DST 8, COPY_SRC 16, VERTEX 32, INDEX 64,
  UNIFORM 128 (`…/api/buffers/GpuBuffer.java`).
- `RenderPass` has `setPipeline`, `setUniform(name, buffer | slice | (textureView, sampler))`, `setVertexBuffer`,
  `setIndexBuffer`, `drawIndexed`, `draw` and `pushConstants` (`…/api/commands/RenderPass.java`).
- `DynamicGpuData.writeTransform(Matrix4f modelView[, Vector4f colorModulator][, Vector3f modelOffset, Matrix4f textureMatrix])`
  (`client/.../renderer/DynamicGpuData.java:59-77`). Vanilla only calls it in *prepare* code, before passes open.
- **Only one command encoder exists** (`FrontendGpuDevice.createCommandEncoder()` returns the same instance), and it
  refuses `createRenderPass`, `writeToTexture` and `writeToBuffer` while a pass is open
  (`…/frontend/FrontendCommandEncoder.java:101, 287, 394`).

A cached mesh has one catch: the entity shader lights with the **world-space** `Normal` attribute against world-space
`Light0/1_Direction` (`assets/shaders/include/light.glsl`). Baked model-space normals light wrongly once the ship
rotates. Either accept flat lighting (`NO_CARDINAL_LIGHTING`), or write a custom vertex shader that rotates the normal
by a matrix you pass in, for example through the otherwise-unused `TextureMat`. **[UNVERIFIED trick]**

### 6.3 Hooks for custom world-space drawing

Fabric `LevelRenderEvents` (`fabric/.../client/rendering/v1/level/LevelRenderEvents.java`; the injection points are in
`fabric/.../mixin/client/rendering/LevelRendererMixin.java`):

| Event (line) | When | Context | Can draw? |
|---|---|---|---|
| `START_MAIN` (46) | Inside the open main pass, before opaque terrain | `LevelTerrainRenderContext` (gameRenderer, levelRenderer, levelState, sectionsToRender) | **No.** The pass isn't exposed and a new pass can't be opened |
| `AFTER_OPAQUE_TERRAIN` (58) | Inside the main pass, after opaque terrain | same | No (as above) |
| `COLLECT_SUBMITS` (71) | After entities, block entities and particles have submitted, before `prepareFrame` | `LevelRenderContext` (+ `submitNodeCollector()`, `poseStack()`) | **Yes, via submits.** The PoseStack is identity, so translate by `pos - levelState().cameraRenderState.pos` yourself |
| `AFTER_SOLID_FEATURES` (81) | Inside the main pass, after solid features | same | No |
| `AFTER_TRANSLUCENT_FEATURES` (91) | After classic translucent features, or after OIT | same | No (classic) |
| `BEFORE_BLOCK_OUTLINE` (114) | Before the block outline is submitted, cancellable | + `BlockOutlineRenderState` | submits |
| `BEFORE_GIZMOS` (131) | Before gizmo finalization, in `submitFeatures` | same | submits |
| `BEFORE_/AFTER_TRANSLUCENT_TERRAIN` (142/154) | Around translucent terrain (classic only) | same | No |
| `END_MAIN` (165) | **After the main pass lambda; every pass is closed** (OIT composite done) | same | **Yes:** open your own `RenderPass` on `Minecraft.getInstance().gameRenderer.mainRenderTarget()` color + depth. Model-view and projection are still set |
| `LevelExtractionEvents.END_EXTRACTION` / `AFTER_BLOCK_OUTLINE_EXTRACTION` | End of extraction (`fabric/.../mixin/client/rendering/LevelExtractorMixin.java:64-72`) | `LevelExtractionContext` (level, camera, deltaTracker) | Prepare data; texture uploads are safe here |

**The cleanest in-pass custom draw is a Fabric custom feature renderer:**
`FeatureRendererRegistry.register(FeatureRendererType<T>, Supplier<FeatureRenderer<T>>)`
(`fabric/.../client/rendering/v1/FeatureRendererRegistry.java:45`), registered during client init, plus
`submitNodeCollector.submitCustom(SubmitRenderPhases.SOLID, node)`. The phases are listed in
`fabric/.../client/rendering/v1/SubmitRenderPhases.java`: SOLID, SHADOWS, NAME_TAGS, SEE_THROUGH_NAME_TAGS, TEXTS,
SHAPE_OUTLINES, TRANSLUCENT_BLOCKS_AND_ITEMS, TRANSLUCENT_MODELS, TRANSLUCENT_CUSTOM_GEOMETRY, GIZMOS,
BREAKING_OVERLAY, WATER_MASK, AFTER_TERRAIN, ALWAYS_ON_TOP and OUTLINE.

`FeatureRenderer<Submit extends SubmitNode>` (`client/.../feature/FeatureRenderer.java`) has these methods:
- `beginPrepare(ctx)`
- `prepareGroup(FeatureFrameContext, List<Submit>, boolean strictlyOrdered)`, before any pass, where uniforms are
  written;
- `finishPrepare(ctx)`
- `executeGroup(ctx, @Nullable OitStage stage, RenderPass renderPass, int groupIndex, List<Submit>, boolean)`, which
  **receives the open RenderPass**; `stage` is null outside OIT;
- `finishExecute(ctx)` and `close()`.

`FeatureFrameContext` exposes `stagedVertexBuffer()`, `lightmap()`, `textureManager()`, `font()` and more. Vanilla
`QuadParticleFeatureRenderer` is a complete, small example of binding buffers and textures by hand. Fabric's own Indigo
renderer registers feature renderers the same way (`fabric/.../impl/client/indigo/Indigo.java:138-145`).

---

## 7. Particles

### 7.1 Types and registration

- Common side: `FabricParticleTypes.simple()` / `simple(boolean alwaysSpawn)` (`fabric/.../api/particle/v1/FabricParticleTypes.java:52-63`),
  and `complex(MapCodec, StreamCodec)` for parameterised options. `alwaysSpawn` is the vanilla `overrideLimiter`
  (`common/.../core/particles/ParticleType.java:14`); `SimpleParticleType`'s constructor is `protected`. Register with
  `Registry.register(BuiltInRegistries.PARTICLE_TYPE, id, type)`; vanilla uses
  `register(name, new SimpleParticleType(overrideLimiter))` (`ParticleTypes.java:176`).
- Client side: **`ParticleProviderRegistry.getInstance().register(type, ParticleProvider<T>)`**, or
  `register(type, PendingParticleProvider<T>)` whose `create(FabricSpriteSet)` returns the provider
  (`fabric/.../client/particle/v1/ParticleProviderRegistry.java:43-52`). The old name was `ParticleFactoryRegistry`.
- Provider: `@Nullable Particle createParticle(T options, ClientLevel level, double x, double y, double z, double xAux,
  double yAux, double zAux, RandomSource random)` (`client/.../particle/ParticleProvider.java`).
- Sprite sets come from `assets/<ns>/particles/<path>.json` as `{"textures": ["redplanet:exhaust_0", …]}`
  (`ParticleResources.java:44, 198-270`). Textures go in `assets/<ns>/textures/particle/*.png`. The particle atlas
  definition `atlases/particles.json` is `{"type":"minecraft:directory","source":"particle","prefix":""}`, which pulls
  in every namespace. `SpriteSet` has `get(int age, int lifetime)`, `get(RandomSource)` and `first()`.

### 7.2 Engine, render types and limits

- `ParticleRenderType` is now `record ParticleRenderType(String name, String shorthand)`, with `SINGLE_QUADS`,
  `ITEM_PICKUP`, `ELDER_GUARDIANS` and `NO_RENDER` (`client/.../particle/ParticleRenderType.java`). **It is a group
  key, not a blend mode.**
- `ParticleGroup` (`ParticleGroup.java`): **`MAX_PARTICLES = 16384` per group**. From 12 288 up, new particles are
  accepted with probability `((16384-n)/4096)²`. Every vanilla quad particle shares `SINGLE_QUADS`.
- Optional per-type caps: override `Particle.getParticleLimit()` → `Optional.of(new ParticleLimit(n))`. `ParticleLimit`
  is a record, so **equal limits share a counter** (`common/.../core/particles/ParticleLimit.java`).
- Blending and atlas come from `SingleQuadParticle.Layer(boolean translucent, Identifier atlas, RenderPipeline pipeline,
  @Nullable OitPipelineSet oit)`, a public record (`client/.../particle/SingleQuadParticle.java:~170-200`). Vanilla
  layers are `OPAQUE`, `TRANSLUCENT`, `OPAQUE_TERRAIN/ITEMS` and `TRANSLUCENT_TERRAIN/ITEMS`, using
  `RenderPipelines.OPAQUE_PARTICLE` / `TRANSLUCENT_PARTICLE` / `OIT_PARTICLE` (`RenderPipelines.java:1121-1130`;
  `PARTICLE_SNIPPET` / `OIT_PARTICLE_SNIPPET` at `:370-390`). **There is no additive layer, but you can build one.**
  `QuadParticleFeatureRenderer` draws each layer with `layer.pipeline()`, or `layer.oitPipelineSet().getPipeline(stage)`
  in OIT mode, and throws `"OIT pipeline set for particle layer not specified."` if it is missing
  (`client/.../feature/QuadParticleFeatureRenderer.java`). Translucent layers render in the after-terrain phase
  (`SubmitNodeCollection.submitQuadParticleGroup`).
- **Separate budget:** `ParticleGroupRegistry.register(ParticleRenderType, Function<ParticleEngine, ParticleGroup<?>>)`
  (`fabric/.../client/particle/v1/ParticleGroupRegistry.java:41`) appends the group to `ParticleEngine.RENDER_ORDER`
  (`fabric/.../mixin/client/particle/ParticleEngineMixin.java`). The type's `name` must parse as an `Identifier`, for
  example `new ParticleRenderType("redplanet:exhaust", "RPX")`. The factory can be
  `engine -> new QuadParticleGroup(engine, type)` (public constructor), and particles return that type from `getGroup()`.
- `Particle` basics (`client/.../particle/Particle.java`): protected fields `x, y, z, xd, yd, zd, age, lifetime,
  gravity, friction, hasPhysics, alpha` (alpha lives in `SingleQuadParticle`). The constructor with velocity
  **randomises and normalises** the velocity (`:53-63`), so use the position-only constructor and set `xd`/`yd`/`zd`
  yourself, as `CampfireSmokeParticle` does. `hasPhysics = true` runs block collision every tick; turn it off for mass
  exhaust. `getLightCoords(float)` can be overridden to return `LightCoordsUtil.FULL_BRIGHT` for glowing particles.
- `SingleQuadParticle` (`SingleQuadParticle.java`): `quadSize`, `rCol/gCol/bCol/alpha`, `roll/oRoll`, `sprite`,
  `getQuadSize(float pt)`, `getFacingCameraMode()` (`LOOKAT_XYZ` / `LOOKAT_Y`), `setSpriteFromAge(SpriteSet)` and the
  abstract `getLayer()`.

### 7.3 How vanilla makes big smoke

- `CampfireSmokeParticle` (`client/.../particle/CampfireSmokeParticle.java`) uses `scale(3.0F)`, a 280–330-tick
  lifetime for signal smoke, tiny gravity (3e-6), random horizontal drift each tick, an alpha fade over the last 60
  ticks and `Layer.TRANSLUCENT`. Sprites come from 12 `big_smoke_*` frames.
- The explosion emitter `HugeExplosionSeedParticle` is a `NoRenderParticle` that, for 8 ticks, spawns 6 `EXPLOSION`
  particles per tick at ±4 blocks. This is the **emitter** pattern: one cheap invisible particle seeds many others over
  time.
- Plan for deluge steam and dust plumes: fewer, **much larger** quads (4–16 blocks) that grow over their lifetime
  through `getQuadSize`, with random roll, slow drag, alpha fade, a sprite chosen by age, and emitters to spread the
  spawning out.

### 7.4 Sending, and the long-distance flag

`ServerLevel.sendParticles` (`common/.../server/level/ServerLevel.java:1314-1480`):
```java
<T extends ParticleOptions> int sendParticles(T particle, double x, double y, double z, int count, double xDist, double yDist, double zDist, double speed)
<T extends ParticleOptions> int sendParticles(T particle, boolean overrideLimiter, boolean alwaysShow, double x, double y, double z, int count, double xDist, double yDist, double zDist, double speed)
<T extends ParticleOptions> int sendParticles(T, boolean overrideLimiter, boolean alwaysShow, x, y, z, int count, xDist, yDist, zDist, double xSpeed, double ySpeed, double zSpeed, ClientboundLevelParticlesPacket.RandomizationType type)
public final boolean sendParticles(ServerPlayer player, boolean overrideLimiter, double x, double y, double z, Packet<?> packet) // per-player gate
```
- Server gate: the player's block position must be within **32 blocks, or 512 with `overrideLimiter`** (3D).
- Client `handleParticleEvent` (`ClientPacketListener.java:2206-2247`): with `count == 0` the speed fields are a
  direction × speed. Otherwise there are `count` Gaussian samples, or uniform samples for `ALTERNATIVE*`.
- `ClientLevel.doAddParticle` (`client/.../multiplayer/ClientLevel.java:927-950`): **`overrideLimiter` skips both the
  32-block camera-distance check and the `MINIMAL` particle setting.** Without it, particles beyond 32 blocks are
  dropped. `alwaysShow` only softens `MINIMAL`. Respect `options.particles()` yourself when overriding.
- **Recommendation:** spawn rocket exhaust, venting and plume particles **client-side** from the entity's client tick,
  driven by synced throttle and phase data. Use `level.addParticle(opts, true, false, …)` for far visibility. Use server
  `sendParticles` only for one-off events.

---

## 8. Block entity renderers and text

```java
public interface BlockEntityRenderer<T extends BlockEntity, S extends BlockEntityRenderState> {   // client/.../blockentity/BlockEntityRenderer.java
    S createRenderState();
    default void extractRenderState(T be, S state, float partialTicks, Vec3 cameraPosition, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress)
        { BlockEntityRenderState.extractBase(be, state, breakProgress); }        // blockPos, blockState, type, lightCoords
    void submit(S state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera);
    default boolean shouldRenderOffScreen() { return false; }    // true → "globally rendered" list, ignores section visibility
    default int getViewDistance() { return 64; }
    default boolean shouldRender(T be, Vec3 cameraPosition) { return Vec3.atCenterOf(be.getBlockPos()).closerThan(cameraPosition, getViewDistance()); }
}
record BlockEntityRendererProvider.Context(BlockEntityRenderDispatcher, BlockModelResolver, ItemModelResolver, EntityRenderDispatcher,
                                           EntityModelSet entityModelSet, Font font, SpriteGetter sprites, PlayerSkinRenderCache) { ModelPart bakeLayer(ModelLayerLocation); }
```
- Register with **`BlockEntityRenderers.register(BlockEntityType<T>, BlockEntityRendererProvider<T, S>)`**, made public
  by AW (classtweaker 42). Fabric's `BlockEntityRendererRegistry` is deprecated. The PoseStack arrives translated to
  the block's corner, camera-relative (`LevelRenderer.submitBlockEntities`, `:985-997`).
- Block entity types use `new BlockEntityType<>(factory, Set<Block>)` (`common/.../block/entity/BlockEntityType.java:18`)
  or `FabricBlockEntityTypeBuilder.create(factory, blocks...)`.
- **Sign text pattern** (`AbstractSignRenderer.java`, `StandingSignRenderer.java`). The sign *board* is now a regular
  block model; the renderer only draws text:
  ```java
  poseStack.mulPose(state.transformations.frontText());      // Transformation: translate(0.5,0.5,0.5)·rotY·offset·scale(0.010416667, -0.010416667, 0.010416667)
  submitNodeCollector.submitText(poseStack, x1, i * state.textLineHeight - signMidpoint, line, false,
          Font.DisplayMode.POLYGON_OFFSET, lightVal /* 15728880 when glowing */, textColor, 0 /* bg */, drawOutline ? darkColor : 0);
  ```
  Measure text with `font.width(FormattedCharSequence)` and `font.split(Component, width)`. Get the `Font` from
  `context.font()`.

---

## 9. Dynamic textures

- `DynamicTexture` (`client/net/minecraft/client/renderer/texture/DynamicTexture.java`) constructors:
  `(Supplier<String> label, NativeImage image)` (uploads immediately), `(String label, int w, int h, boolean zero)` and
  `(Supplier<String>, int w, int h, boolean zero)`. Methods: `upload()` (→ `commandEncoder.writeToTexture(texture,
  pixels)`), `getPixels()`, `setPixels(NativeImage)` (closes the old one) and `close()`. The GPU texture is
  `RGBA8_UNORM` with usage `5` (COPY_DST | TEXTURE_BINDING) and sampler `getRepeat(FilterMode.NEAREST)`. Subclass it to
  change `sampler`, a protected field of `AbstractTexture`.
- `NativeImage` (`client/com/mojang/blaze3d/platform/NativeImage.java`): `new NativeImage(w, h, zero)`,
  `setPixel(x, y, argb)` / `getPixel` (ARGB), `setPixelABGR`, `fillRect`, `copyFrom`, `read(InputStream|byte[])` and
  `close()`.
- `TextureManager` (`TextureManager.java`):
  - `register(Identifier, AbstractTexture)` closes any previous texture at that id;
  - `registerAndLoad(Identifier, ReloadableTexture)` loads immediately;
  - `getTexture(Identifier)` auto-creates a `SimpleTexture` if missing;
  - `release(Identifier)`;
  - registered `ReloadableTexture`s reload on every resource reload (`:121-137`).

  Reach it with `Minecraft.getInstance().getTextureManager()` or `entityRenderDispatcher.textureManager` (a public
  field).
- **Uploads must happen outside render passes.** `writeToTexture` throws while a pass is open
  (`FrontendCommandEncoder.java:394`). Upload in a client tick or in `LevelExtractionEvents.END_EXTRACTION`.
- **Mipmapped static textures:** `new MipmappedTexture(Identifier, int maxMipLevel)` (`MipmappedTexture.java`) generates
  mips with a linear min filter, the same way `WorldBorderRenderer` registers its forcefield texture
  (`WorldBorderRenderer.java:53`). Use it for the 1024×1024 and 1024×2048 hull textures, or the hex tiles will alias
  badly at distance. Register it with `textureManager.registerAndLoad(id, new MipmappedTexture(id, 4))` before the
  first use of that id.
- To use a dynamic texture in a RenderType, register it under an `Identifier` and pass that id to `RenderTypes.*(id)`
  or `RenderSetup.withTexture("Sampler0", id[, samplerSupplier])`.

---

## 10. Recommendations for redplanet (sketches, **not compiled** [UNVERIFIED])

### 10.1 Architecture decisions

1. **Entities:** `StarshipEntity` and `SuperHeavyEntity` extend `Entity` (MISC), with `noPhysics = true` and
   server-kinematic motion. Attitude goes in `DATA_ATTITUDE` (`EntityDataSerializers.QUATERNION`); phase, phase start
   tick, throttle, flap and leg angles go in synced data. Set `needsSync = true` every flight tick.
2. **Stacking:** while stacked, make the ship a **passenger of the booster**. The client then positions it rigidly
   through `positionRider` every tick, with no interface jitter. Staging is `ship.stopRiding()`, after which the ship's
   own position packets resume (`wasRiding` forces a full sync). **[UNVERIFIED in play]**
3. **Hitbox:** keep the AABB honest for culling and save data, but make it **non-solid** (`canBeCollidedWith → false`).
   Do **not** rely on clicking the hull (§2.8). Board through a tower **crew-access-arm block** whose `useWithoutItem`
   calls `player.startRiding(ship)` server-side, or through a small `noSave` "hatch" helper entity with `NoopRenderer`.
4. **Seats:** override `getPassengerAttachmentPoint` with a seat table in the body frame, rotated by the attitude
   quaternion; keep `getControllingPassenger() == null`. Lock dismounting with the `Player#wantsToStopRiding` mixin.
   Override `getDismountLocationForPassenger` to put riders on the arm or pad.
5. **Tracking:** `clientTrackingRange(64)` and `updateInterval(1)`, or 2 with `needsSync`. Client smoothing starts with
   `LinearInterpolationHandler.create(this, 2)`, then a custom predictive handler if the flight speed makes
   `handleEntityPositionSync` snap (lag over 64 blocks).
6. **Rendering:** immediate `submitCustomGeometry` with LODs and `entitySolid` for the hull. Use the custom additive
   RenderTypes below for plumes and plasma (with OIT sets), `eyes()` for emissive details, and a far impostor for
   high-altitude viewing. Keep an optional cached-mesh `FeatureRenderer` (§10.7) for later if profiling demands it.
7. **Particles:** client-spawned, in a private `ParticleRenderType` group (16 384 budget), with an additive `Layer` for
   exhaust and a translucent layer for steam and dust.

### 10.2 Entity

```java
package io.github.avi130805.redplanet.starship.entity;

import net.minecraft.network.syncher.*;               // EntityDataAccessor, EntityDataSerializers, SynchedEntityData
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;                  // Entity, EntityDimensions, EntityType, InterpolationHandler, LinearInterpolationHandler, LivingEntity
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

public class StarshipEntity extends Entity {
	private static final EntityDataAccessor<Byte> DATA_PHASE = SynchedEntityData.defineId(StarshipEntity.class, EntityDataSerializers.BYTE);
	private static final EntityDataAccessor<Long> DATA_PHASE_START = SynchedEntityData.defineId(StarshipEntity.class, EntityDataSerializers.LONG);
	private static final EntityDataAccessor<Float> DATA_THROTTLE = SynchedEntityData.defineId(StarshipEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Quaternionfc> DATA_ATTITUDE = SynchedEntityData.defineId(StarshipEntity.class, EntityDataSerializers.QUATERNION);
	private static final EntityDataAccessor<Float> DATA_FLAPS_FORE = SynchedEntityData.defineId(StarshipEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_FLAPS_AFT = SynchedEntityData.defineId(StarshipEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_LEGS = SynchedEntityData.defineId(StarshipEntity.class, EntityDataSerializers.FLOAT);

	/** Seat positions in the body frame (metres; origin at skirt bottom, +Y to nose). StarshipGeometry.SHIP_CABIN_Y ≈ 38.5. */
	private static final Vector3f[] SEATS = {
		new Vector3f(-1.2F, 38.6F, -1.0F), new Vector3f(1.2F, 38.6F, -1.0F), new Vector3f(-1.2F, 38.6F, 1.0F),
		new Vector3f(1.2F, 38.6F, 1.0F), new Vector3f(-1.2F, 41.4F, 0.0F), new Vector3f(1.2F, 41.4F, 0.0F) };

	private Quaternionf attitudeO = new Quaternionf();   // client-side previous attitude for slerp

	public StarshipEntity(final EntityType<? extends StarshipEntity> type, final Level level) {
		super(type, level);
		this.noPhysics = true;        // kinematic: server sets positions directly
		this.blocksBuilding = true;   // like EndCrystal
	}

	@Override
	protected void defineSynchedData(final SynchedEntityData.Builder b) {
		b.define(DATA_PHASE, (byte) 0);
		b.define(DATA_PHASE_START, 0L);
		b.define(DATA_THROTTLE, 0.0F);
		b.define(DATA_ATTITUDE, new Quaternionf());
		b.define(DATA_FLAPS_FORE, 0.0F);
		b.define(DATA_FLAPS_AFT, 0.0F);
		b.define(DATA_LEGS, 1.0F);
	}

	@Override
	public void tick() {
		this.attitudeO.set(this.getEntityData().get(DATA_ATTITUDE));
		super.tick();                                         // baseTick (fire, portals, etc.)
		if (this.level() instanceof ServerLevel serverLevel) {
			// FlightController.tick(this, serverLevel): setPos(...), set attitude/throttle data...
			if (this.isFlying()) this.needsSync = true;       // force a tracker update every tick (ServerEntity.sendChanges)
		} else {
			// client: spawn exhaust/venting particles from synced throttle/phase (see §10.6)
		}
	}

	@Override protected void readAdditionalSaveData(final ValueInput in) {
		this.getEntityData().set(DATA_PHASE, (byte) in.getIntOr("phase", 0));
		this.getEntityData().set(DATA_PHASE_START, in.getLongOr("phase_start", 0L));
		// flight state, crew/cargo, propellant ... (restoreFrom() on dimension change relies on this)
	}
	@Override protected void addAdditionalSaveData(final ValueOutput out) {
		out.putInt("phase", this.getEntityData().get(DATA_PHASE));
		out.putLong("phase_start", this.getEntityData().get(DATA_PHASE_START));
	}

	@Override public boolean hurtServer(final ServerLevel level, final DamageSource source, final float damage) { return false; }
	@Override public boolean isPickable() { return !this.isRemoved(); }      // only works near the base, see §2.8
	@Override public boolean canBeCollidedWith(final @Nullable Entity other) { return false; }
	@Override public boolean ignoreExplosion(final Explosion explosion) { return true; }
	@Override public boolean isPushedByFluid() { return false; }
	@Override public boolean canUsePortal(final boolean ignorePassenger) { return false; }
	@Override public boolean showVehicleHealth() { return false; }

	@Override
	public InteractionResult interact(final Player player, final InteractionHand hand, final Vec3 location) {
		if (player.isSecondaryUseActive() || this.isFlying()) return InteractionResult.PASS;
		if (this.level().isClientSide()) return InteractionResult.SUCCESS;
		return player.startRiding(this) ? InteractionResult.SUCCESS_SERVER : InteractionResult.PASS;
	}

	@Override protected boolean canAddPassenger(final Entity p) { return this.getPassengers().size() < SEATS.length && !this.isFlying(); }
	@Override public @Nullable LivingEntity getControllingPassenger() { return null; }   // server-authoritative

	@Override
	protected Vec3 getPassengerAttachmentPoint(final Entity passenger, final EntityDimensions dims, final float scale) {
		int i = Math.max(0, this.getPassengers().indexOf(passenger));
		Vector3f seat = this.getAttitude(1.0F).transform(new Vector3f(SEATS[Math.min(i, SEATS.length - 1)]));
		return new Vec3(seat.x, seat.y, seat.z);
	}

	@Override
	public Vec3 getDismountLocationForPassenger(final LivingEntity passenger) {
		// e.g. the crew-access-arm platform, or a ring around the landed skirt; NOT bb.maxY (default)
		return this.position().add(0.0, 0.0, 7.0);
	}

	@Override protected InterpolationHandler createInterpolationHandler() { return LinearInterpolationHandler.create(this, 2); }
	@Override public boolean shouldRenderAtSqrDistance(final double d2) { return d2 < 4096.0 * 4096.0; }

	public boolean isFlying() { return this.getEntityData().get(DATA_PHASE) != 0; }
	public boolean isDismountLocked() { return this.isFlying(); }
	public float getThrottle() { return this.getEntityData().get(DATA_THROTTLE); }
	public Quaternionf getAttitude(final float pt) { return new Quaternionf(this.attitudeO).slerp(this.getEntityData().get(DATA_ATTITUDE), pt); }
}
```
Registration (common init):
```java
public static final ResourceKey<EntityType<?>> STARSHIP_KEY = ResourceKey.create(Registries.ENTITY_TYPE, RedPlanet.id("starship"));
public static final EntityType<StarshipEntity> STARSHIP = Registry.register(BuiltInRegistries.ENTITY_TYPE, STARSHIP_KEY,
	EntityType.Builder.<StarshipEntity>of(StarshipEntity::new, MobCategory.MISC)
		.sized(9.0F, 52.0F)            // see §2.8 about picking/collisions
		.noLootTable().fireImmune()
		.clientTrackingRange(64)       // chunks; real cap = player view distance (horizontal only)
		.updateInterval(1)
		.build(STARSHIP_KEY));
```
Dismount lock (the only mixin needed; add a `redplanet.mixins.json`):
```java
@Mixin(Player.class)
abstract class PlayerSeatLockMixin {
	// Player#rideTick (server) calls wantsToStopRiding() == isShiftKeyDown(); keep crews seated during flight.
	@Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
	private void redplanet$lockStarshipSeat(CallbackInfoReturnable<Boolean> cir) {
		if (((Player) (Object) this).getVehicle() instanceof StarshipEntity ship && ship.isDismountLocked()) cir.setReturnValue(false);
	}
}
```

### 10.3 Client init

```java
public final class RedPlanetClient implements ClientModInitializer {
	@Override public void onInitializeClient() {
		RedPlanetRenderTypes.bootstrap();                                        // class-load → RenderPipelines.register(...) before first reload
		EntityRenderers.register(RedPlanetEntities.STARSHIP, StarshipRenderer::new);
		EntityRenderers.register(RedPlanetEntities.SUPER_HEAVY, SuperHeavyRenderer::new);
		EntityRenderers.register(RedPlanetEntities.HATCH, NoopRenderer::new);   // if helper entities are used
		ParticleGroupRegistry.register(RedPlanetParticleGroups.EXHAUST, engine -> new QuadParticleGroup(engine, RedPlanetParticleGroups.EXHAUST));
		ParticleProviderRegistry.getInstance().register(RedPlanetParticles.ROCKET_EXHAUST, ExhaustParticle.Provider::new);   // PendingParticleProvider(FabricSpriteSet)
		ParticleProviderRegistry.getInstance().register(RedPlanetParticles.DELUGE_STEAM, SteamCloudParticle.Provider::new);
	}
}
```

### 10.4 Custom pipelines and RenderTypes (all OIT-safe)

```java
public final class RedPlanetRenderTypes {
	public static final Identifier SHIP_TEXTURE = RedPlanet.id("textures/entity/starship/ship.png");
	public static final Identifier PLUME_TEXTURE = RedPlanet.id("textures/effect/raptor_plume.png");

	/** Additive textured plume: vanilla entity shader + energy-swirl defines, alpha-weighted additive, NO depth write. */
	public static final RenderPipeline PLUME_PIPELINE = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.ENERGY_SWIRL_SNIPPET)               // EMISSIVE, NO_OVERLAY, NO_CARDINAL_LIGHTING, APPLY_TEXTURE_MATRIX, cull off
			.withLocation(RedPlanet.id("pipeline/plume_additive"))
			.withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))   // SRC_ALPHA, ONE  (matches OIT_ADDITIVE's rgb*a)
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
			.build());

	/** Scrolls UVs along the plume (shader applies TextureMat because APPLY_TEXTURE_MATRIX is defined). Evaluated each frame in RenderType.prepare(). */
	public static final TextureTransform PLUME_SCROLL = new TextureTransform("redplanet_plume_scroll",
		() -> new Matrix4f().translation(0.0F, -(Util.getMillis() % 1000L) / 1000.0F, 0.0F));

	public static final RenderType PLUME = RenderType.create("redplanet_plume",
		RenderSetup.builder(PLUME_PIPELINE)
			.setOitPipelines(RenderPipelines.OIT_ENERGY_SWIRL)                    // reuse vanilla OIT_ADDITIVE set (same shader/defines/format)
			.withTexture("Sampler0", PLUME_TEXTURE)
			.setTextureTransform(PLUME_SCROLL)
			.createRenderSetup());

	/** Untextured additive glow (POSITION_COLOR quads): shock diamonds, entry plasma sheath, flame cores. */
	public static final RenderPipeline GLOW_PIPELINE = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.LIGHTNING_SNIPPET)                  // rendertype_lightning shader, POSITION_COLOR, QUADS
			.withLocation(RedPlanet.id("pipeline/glow_additive"))
			.withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
			.withCull(false)
			.build());
	public static final RenderType GLOW = RenderType.create("redplanet_glow",
		RenderSetup.builder(GLOW_PIPELINE).setOitPipelines(RenderPipelines.OIT_LIGHTNING).createRenderSetup());

	/** Opaque lit hull: vanilla (culling on, lightmap + overlay). Interior/cabin faces need inward geometry or entityCutout (cull off). */
	public static RenderType hull() { return RenderTypes.entitySolid(SHIP_TEXTURE); }
	/** Emissive details (window glow, engine-bell hot ring): vanilla eyes() (translucent, emissive, no depth write, has OIT). */
	public static RenderType emissive(Identifier tex) { return RenderTypes.eyes(tex); }

	public static void bootstrap() {}
	private RedPlanetRenderTypes() {}
}
```
Vertex rules: `PLUME` uses the **ENTITY** format, so every vertex needs color, uv, overlay, light and normal. The bulk
`addVertex` writes all of them. Put intensity in vertex alpha (≤ 0.99) and keep texture alpha ≥ 0.1 where light should
appear (`ALPHA_CUTOUT 0.1`). `GLOW` uses **POSITION_COLOR**: `addVertex(...).setColor(...)` only.

### 10.5 Renderer: render state, far impostor and custom geometry

```java
public class StarshipRenderState extends EntityRenderState {
	public final Quaternionf attitude = new Quaternionf();
	public float throttle, flapsFore, flapsAft, legs, plasma;
	public double rootRelX, rootRelY, rootRelZ;   // camera → root-vehicle offset (shared impostor scale)
	public StarshipGeometry.Lod lod = StarshipGeometry.Lod.HIGH;
}

public class StarshipRenderer extends EntityRenderer<StarshipEntity, StarshipRenderState> {
	private final VehicleMesh high = StarshipGeometry.buildShip(StarshipGeometry.Lod.HIGH);
	private final VehicleMesh medium = StarshipGeometry.buildShip(StarshipGeometry.Lod.MEDIUM);
	private final VehicleMesh low = StarshipGeometry.buildShip(StarshipGeometry.Lod.LOW);

	public StarshipRenderer(final EntityRendererProvider.Context ctx) {
		super(ctx);
		// mipmaps for the hull texture (must be registered before RenderTypes resolve it)
		ctx.getEntityRenderDispatcher().textureManager.registerAndLoad(RedPlanetRenderTypes.SHIP_TEXTURE,
			new MipmappedTexture(RedPlanetRenderTypes.SHIP_TEXTURE, 4));
	}

	@Override public StarshipRenderState createRenderState() { return new StarshipRenderState(); }

	@Override
	public void extractRenderState(final StarshipEntity e, final StarshipRenderState s, final float pt) {
		super.extractRenderState(e, s, pt);                      // x/y/z (lerped), lightCoords, distanceToCameraSq, ...
		s.attitude.set(e.getAttitude(pt));
		s.throttle = e.getThrottle();
		// s.flapsFore/flapsAft/legs/plasma = lerped synced values
		Entity root = e.getRootVehicle();
		Vec3 cam = this.entityRenderDispatcher.camera.position();
		Vec3 rp = root.getPosition(pt);
		s.rootRelX = rp.x - cam.x; s.rootRelY = rp.y - cam.y; s.rootRelZ = rp.z - cam.z;
		double d2 = s.distanceToCameraSq;
		s.lod = d2 < 96 * 96 ? StarshipGeometry.Lod.HIGH : d2 < 320 * 320 ? StarshipGeometry.Lod.MEDIUM : StarshipGeometry.Lod.LOW;
	}

	// The impostor scaling makes vanilla distance/frustum culling wrong; do our own (or none).
	@Override public boolean shouldRender(final StarshipEntity e, final Frustum f, final double cx, final double cy, final double cz, final float pt) { return true; }

	@Override
	public void submit(final StarshipRenderState s, final PoseStack ps, final SubmitNodeCollector c, final CameraRenderState cam) {
		ps.pushPose();
		applyFarImpostor(s, ps, cam);
		ps.rotate(s.attitude);
		VehicleMesh mesh = s.lod == StarshipGeometry.Lod.HIGH ? this.high : s.lod == StarshipGeometry.Lod.MEDIUM ? this.medium : this.low;
		final int light = s.lightCoords;
		c.submitCustomGeometry(ps, RedPlanetRenderTypes.hull(), (pose, buf) -> {
			for (Map.Entry<VehiclePart, VehicleMesh.PartMesh> part : mesh.parts().entrySet()) {
				PoseStack.Pose p = jointPose(pose, part.getKey(), part.getValue().joint(), s);   // pose.copy().rotateAround(q, px, py, pz) for animated parts
				emitQuads(part.getValue().quads(), p, buf, 0xFFFFFFFF, light);
			}
		});
		if (s.throttle > 0.0F) {
			c.submitCustomGeometry(ps, RedPlanetRenderTypes.PLUME, (pose, buf) -> PlumeMesh.emit(pose, buf, s.throttle, s.ageInTicks));
			c.submitCustomGeometry(ps, RedPlanetRenderTypes.GLOW, (pose, buf) -> PlumeMesh.emitCores(pose, buf, s.throttle));
		}
		if (s.plasma > 0.0F) c.submitCustomGeometry(ps, RedPlanetRenderTypes.GLOW, (pose, buf) -> PlasmaSheath.emit(pose, buf, s.plasma));
		ps.popPose();
		super.submit(s, ps, c, cam);                              // name tag/leash (none)
	}

	/** Keep angular size, pull the mesh inside depthFar and the clear fog band (§2.9). Shared root offset keeps ship+booster aligned. */
	private static void applyFarImpostor(final StarshipRenderState s, final PoseStack ps, final CameraRenderState cam) {
		double d = Math.sqrt(s.rootRelX * s.rootRelX + s.rootRelY * s.rootRelY + s.rootRelZ * s.rootRelZ);
		double limit = 0.9 * Math.min(cam.depthFar, cam.fogData.renderDistanceStart);
		if (d > limit && limit > 16.0) {
			float k = (float) (limit / d);
			// pose is at T(rel); want T(rel·k)·S(k) about the camera, using the ROOT's rel for a common k
			double ex = s.x - cam.pos.x, ey = s.y - cam.pos.y, ez = s.z - cam.pos.z;
			ps.translate(-ex * (1.0 - k), -ey * (1.0 - k), -ez * (1.0 - k));
			ps.scale(k, k, k);
		}
	}

	/** Allocation-free transform + BufferBuilder ENTITY fast path. quads: x y z nx ny nz u v per vertex (VehicleMesh). */
	static void emitQuads(final float[] q, final PoseStack.Pose pose, final VertexConsumer buf, final int argb, final int light) {
		Matrix4f m = pose.pose();
		Matrix3f n = pose.normal();
		for (int i = 0; i < q.length; i += VehicleMesh.FLOATS_PER_VERTEX) {
			float x = q[i], y = q[i + 1], z = q[i + 2], a = q[i + 3], b = q[i + 4], cc = q[i + 5];
			float px = Math.fma(m.m00(), x, Math.fma(m.m10(), y, Math.fma(m.m20(), z, m.m30())));
			float py = Math.fma(m.m01(), x, Math.fma(m.m11(), y, Math.fma(m.m21(), z, m.m31())));
			float pz = Math.fma(m.m02(), x, Math.fma(m.m12(), y, Math.fma(m.m22(), z, m.m32())));
			float nx = n.m00() * a + n.m10() * b + n.m20() * cc;
			float ny = n.m01() * a + n.m11() * b + n.m21() * cc;
			float nz = n.m02() * a + n.m12() * b + n.m22() * cc;
			buf.addVertex(px, py, pz, argb, q[i + 6], q[i + 7], OverlayTexture.NO_OVERLAY, light, nx, ny, nz);
		}
	}
}
```
Notes:
- Camera-relative positions keep float precision fine. The dispatcher has already translated by `state.x - camX`.
- Without the impostor, return `false` from `affectedByCulling` (like `LightningBoltRenderer` and
  `EnderDragonRenderer`), or inflate `getBoundingBoxForCulling` to cover the plume.
- First-person passengers sit *inside* a back-face-culled hull. Add a cabin interior mesh, with inward normals and
  windows, rendered with `entityCutout` (cull off) or `entitySolid`.
- The booster renderer is identical. It uses the same `rootRel` (its own position, since it is the root), so `k` is
  equal for both.

### 10.6 Particles

```java
// common
public final class RedPlanetParticles {
	public static final SimpleParticleType ROCKET_EXHAUST = Registry.register(BuiltInRegistries.PARTICLE_TYPE, RedPlanet.id("rocket_exhaust"), FabricParticleTypes.simple(true));
	public static final SimpleParticleType DELUGE_STEAM  = Registry.register(BuiltInRegistries.PARTICLE_TYPE, RedPlanet.id("deluge_steam"),  FabricParticleTypes.simple(true));
	public static final SimpleParticleType VENT_FROST    = Registry.register(BuiltInRegistries.PARTICLE_TYPE, RedPlanet.id("vent_frost"),    FabricParticleTypes.simple(false));
	public static void bootstrap() {}
}
// client
public final class RedPlanetParticleGroups {
	public static final ParticleRenderType EXHAUST = new ParticleRenderType("redplanet:exhaust", "RPX");   // own 16384 budget via ParticleGroupRegistry
	public static final RenderPipeline ADDITIVE_PARTICLE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.PARTICLE_SNIPPET)
		.withLocation(RedPlanet.id("pipeline/additive_particle"))
		.withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
		.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
		.build());
	public static final OitPipelineSet OIT_ADDITIVE_PARTICLE = RenderPipelines.register(
		OitPipelineSet.builder("redplanet_additive_particle", RenderPipeline.builder(RenderPipelines.OIT_PARTICLE_SNIPPET).withShaderDefine("OIT_ADDITIVE")).build());
	public static final SingleQuadParticle.Layer ADDITIVE = new SingleQuadParticle.Layer(true, TextureAtlas.LOCATION_PARTICLES, ADDITIVE_PARTICLE, OIT_ADDITIVE_PARTICLE);
}

public class SteamCloudParticle extends SingleQuadParticle {
	private final SpriteSet sprites; private final float size0, size1;
	SteamCloudParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, SpriteSet sprites) {
		super(level, x, y, z, sprites.get(level.getRandom()));   // position-only ctor: no velocity randomisation
		this.sprites = sprites; this.xd = xd; this.yd = yd; this.zd = zd;
		this.lifetime = 200 + this.random.nextInt(120); this.gravity = -0.002F; this.friction = 0.96F; this.hasPhysics = false;
		this.size0 = 3.0F + this.random.nextFloat() * 2.0F; this.size1 = this.size0 * 3.5F;
		this.roll = this.oRoll = this.random.nextFloat() * Mth.TWO_PI; this.alpha = 0.85F;
	}
	@Override public void tick() { super.tick(); this.oRoll = this.roll; this.roll += 0.004F; this.setSpriteFromAge(this.sprites);
		if (this.age > this.lifetime - 60) this.alpha = Math.max(0.0F, this.alpha - 0.014F); }
	@Override public float getQuadSize(float pt) { float t = (this.age + pt) / this.lifetime; return Mth.lerp(1.0F - (1.0F - t) * (1.0F - t), this.size0, this.size1); }
	@Override protected Layer getLayer() { return Layer.TRANSLUCENT; }
	@Override public ParticleRenderType getGroup() { return RedPlanetParticleGroups.EXHAUST; }
	public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
		@Override public Particle createParticle(SimpleParticleType o, ClientLevel l, double x, double y, double z, double xd, double yd, double zd, RandomSource r) {
			return new SteamCloudParticle(l, x, y, z, xd, yd, zd, this.sprites);
		}
	}
}
// ExhaustParticle: same shape, getLayer() → RedPlanetParticleGroups.ADDITIVE, getLightCoords(pt) → LightCoordsUtil.FULL_BRIGHT.
```
Assets: `assets/redplanet/particles/deluge_steam.json` → `{"textures":["redplanet:steam_0", …]}`, with the images in
`assets/redplanet/textures/particle/steam_0.png` and so on.
Spawning: from `StarshipEntity.tick()` on the client, call `level().addParticle(RedPlanetParticles.DELUGE_STEAM, true,
false, x, y, z, xd, yd, zd)`. Scale the counts by `Minecraft.getInstance().options.particles().get()` and by
distance, because `overrideLimiter` ignores the setting.

### 10.7 Optional: cached static mesh via a Fabric feature renderer **[UNVERIFIED design]**

```java
public final class ShipMeshFeatureRenderer implements FeatureRenderer<ShipMeshFeatureRenderer.Submit> {
	public static final FeatureRendererType<Submit> TYPE = FeatureRendererType.create("redplanet:ship_mesh");
	public record Submit(Matrix4f pose, int light) implements SubmitNode { public FeatureRendererType<Submit> featureType() { return TYPE; } }

	private @Nullable GpuBuffer vbo, ibo; private int indexCount; private IndexType indexType;
	private final List<List<GpuBufferSlice>> groupTransforms = new ArrayList<>();

	@Override public void prepareGroup(FeatureFrameContext ctx, List<Submit> submits, boolean strictlyOrdered) {
		this.ensureUploaded();   // once: BufferBuilder(ENTITY) → MeshData → device.createBuffer(label, GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer()); own index buffer (USAGE_INDEX)
		List<GpuBufferSlice> t = new ArrayList<>();
		for (Submit s : submits) t.add(RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy().mul(s.pose())));
		this.groupTransforms.add(t);
	}
	@Override public void executeGroup(FeatureFrameContext ctx, @Nullable OitStage stage, RenderPass pass, int groupIndex, List<Submit> submits, boolean strictlyOrdered) {
		pass.setPipeline(RenderSystem.getCompiledPipeline(RedPlanetPipelines.SHIP_STATIC));   // custom shader: normal = mat3(TextureMat) * Normal
		RenderSystem.bindDefaultUniforms(pass);
		AbstractTexture tex = ctx.textureManager().getTexture(RedPlanetRenderTypes.SHIP_TEXTURE);
		pass.setUniform("Sampler0", tex.getTextureView(), tex.getSampler());
		pass.setUniform("Sampler2", ctx.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
		pass.setVertexBuffer(0, this.vbo.slice()); pass.setIndexBuffer(this.ibo, this.indexType);
		for (GpuBufferSlice t : this.groupTransforms.get(groupIndex)) { pass.setUniform("DynamicTransforms", t); pass.drawIndexed(this.indexCount, 1, 0, 0, 0); }
	}
	@Override public void finishExecute(FeatureFrameContext ctx) { this.groupTransforms.clear(); }
	@Override public void close() { if (this.vbo != null) this.vbo.close(); if (this.ibo != null) this.ibo.close(); }
}
// client init: FeatureRendererRegistry.register(ShipMeshFeatureRenderer.TYPE, ShipMeshFeatureRenderer::new);
// renderer.submit: c.submitCustom(SubmitRenderPhases.SOLID, new ShipMeshFeatureRenderer.Submit(new Matrix4f(ps.last().pose()), s.lightCoords));
```
Animated parts (flaps, legs, gimbals) would be separate cached sub-meshes with their own transforms. Per-frame light
would need a uniform or a custom shader, because the vertex `UV2` light is baked. Only build this if profiling shows
the immediate path is a bottleneck.

---

## Appendix: quick file index

- Entity core: `common/net/minecraft/world/entity/{Entity, EntityType, EntityTypes, EntityTypeIds, EntityAttachments,
  InterpolationHandler, AbstractInterpolationHandler, LinearInterpolationHandler, SteppedInterpolationHandler,
  SteppedInterpolationTracker, PositionPath, UpdateInterval, MoverType}.java`
- Vehicles: `common/.../vehicle/boat/AbstractBoat.java`, `common/.../vehicle/DismountHelper.java`,
  `common/.../player/Player.java`, `common/.../server/level/ServerPlayer.java`
- Sync and tracking: `common/net/minecraft/server/level/{ServerEntity, ChunkMap, ServerLevel}.java`,
  `common/net/minecraft/network/protocol/game/VecDeltaCodec.java`,
  `client/net/minecraft/client/multiplayer/{ClientPacketListener, ClientLevel}.java`
- Rendering: `client/net/minecraft/client/renderer/{LevelRenderer, extract/LevelExtractor, SubmitNodeCollector,
  OrderedSubmitNodeCollector, SubmitNodeCollection, SubmitNodeStorage, RenderPipelines, BindGroupLayouts,
  StagedVertexBuffer, SkyRenderer, CloudRenderer, ShaderManager, Projection}.java`, `…/renderer/feature/*`,
  `…/renderer/rendertype/*`, `…/renderer/oit/*`, `…/renderer/entity/*`, `…/renderer/blockentity/*`,
  `client/com/mojang/renderpearl/api/**`, `client/com/mojang/renderpearl/frontend/shaders/*`
- Shaders: `assets/shaders/core/{entity, rendertype_lightning, particle, rendertype_beacon_beam}.{vsh,fsh}`,
  `assets/shaders/include/{fog, light, dynamictransforms, projection, oit, oit_sample}.glsl`
- Particles: `client/net/minecraft/client/particle/{ParticleEngine, ParticleGroup, QuadParticleGroup,
  SingleQuadParticle, Particle, ParticleResources, CampfireSmokeParticle, HugeExplosionSeedParticle}.java`
- Textures: `client/net/minecraft/client/renderer/texture/{DynamicTexture, TextureManager, MipmappedTexture,
  ReloadableTexture, AbstractTexture}.java`
- Fabric: `fabric/net/fabricmc/fabric/api/client/rendering/v1/**`, `fabric/.../api/client/particle/v1/*`,
  `fabric/.../api/particle/v1/FabricParticleTypes.java`, `fabric/.../api/object/builder/v1/entity/FabricEntityType.java`,
  `fabric/.../mixin/client/rendering/{LevelRendererMixin, LevelExtractorMixin, SubmitNodeCollectionMixin,
  FeatureRenderDispatcherMixin}.java`, `fabric/fabric-transitive-access-wideners-v1.classtweaker`
