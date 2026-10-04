# Physics and environment hooks: Minecraft Java 26.3 (verified)

Research notes for the Mars rules of **Red Planet: Starship to Mars** (`redplanet`): gravity, drag, elytra, fall damage,
air, combustion, water, sound, movement validation, spawning and world-height limits.

## How this was verified (2026-10-04)

- Decompiled 26.3 sources: `/home/user/mcsrc/common` (common/server) and `/home/user/mcsrc/client` (client-only). Paths
  below are relative to `/home/user/mcsrc/common` unless they start with `client/`. Line numbers come from those
  decompiled files.
- Vanilla data: `/home/user/mcsrc/jar-client/data/minecraft`. Fabric API sources: `/home/user/mcsrc/fabric-api/src`.
- I checked the bytecode of every proposed injection target (descriptors, `ldc` constants, invoke ordinals) with `javap -c -p`
  against the jars Loom uses (`.gradle/loom-cache/minecraftMaven/.../minecraft-common-7e9a32a5b8-26.3.jar` and
  `minecraft-clientOnly-...jar`). Where this note says "bytecode-checked", I confirmed the constant or the invoke count
  there.
- Mixin toolchain: Fabric Loader 0.19.5 bundles **MixinExtras 0.5.5** (`META-INF/jars/mixinextras-fabric-0.5.5.jar`).
  `@ModifyReturnValue`, `@ModifyExpressionValue`, `@WrapOperation`, `@WrapMethod`, `@WrapWithCondition` and `@Local` are
  available. Mixin is `sponge-mixin 0.17.4+mixin.0.8.7`.
- None of the proposed mixins have been compiled or run. Their *targets* are verified; their runtime behaviour is
  **UNVERIFIED** until the gametests cover it.

Notation: `g` means blocks/tick². Vanilla living-entity gravity is 0.08 (Mojang units, about 32 m/s²). Mars factors used
below: `G = 0.3794` (3.721 / 9.80665) and `K = 0.0163` (0.020 / 1.225 kg/m³, the density ratio). The simulations used
0.379 and 0.016, so the numbers differ by less than 1%.

---

## 0. Summary

| Rule | Recommended hook | Mixin count | Covers |
|---|---|---|---|
| A gravity | `@ModifyReturnValue` on `Entity.getGravity()D` (one choke point), plus 2 call-site fixes | 1 (+2 small) | All vanilla and modded entities that use `applyGravity()`/`getEffectiveGravity()` |
| B drag | Multi-target `@ModifyReturnValue` on `getAirDrag()F` (8 classes), plus `@ModifyExpressionValue` on the `0.98F` in `LivingEntity.travelInAir` | 2 | Items, XP, TNT, falling blocks, arrows, tridents, thrown items, llama spit, minecarts in the air, squid, and vertical drag for every living entity |
| B elytra | `@WrapOperation` on the single `updateFallFlyingMovement` call in `LivingEntity.travelFallFlying` | 1 | All gliding entities |
| C fall damage | `@ModifyArg` (index 0) on the 2 `checkFallDamage(...)` invocations in `Entity` | 1 | Every entity's fall distance becomes "Earth-equivalent energy": players, mobs, mace, anvils, boats |
| D air | `@ModifyReturnValue` on `LivingEntity.increaseAirSupply(I)I` + `@Inject(TAIL)` on `LivingEntity.baseTick()` | 1 | Hypoxia with the vanilla 300-tick air bar; custom damage type |
| E fire | `BlockStateBase.canSurvive` (tag) + `Level.setBlock` (unlit rewrite) + `Entity.setRemainingFireTicks` + `AbstractFurnaceBlockEntity.getBurnDuration`/`serverTick` | 4 | Fire, torches, lanterns, campfires, candles, entity burning, furnaces, smokers, blast furnaces |
| F water | Vanilla `minecraft:gameplay/water_evaporates` on the Mars dimension type + a positional "habitat" environment-attribute layer (mixin on `EnvironmentAttributeSystem$Builder.addDefaultLayers`) + optional `FlowingFluid.spreadTo` guard | 1 (+1) | Buckets, dispensers, ice melting/sublimation, wet sponge, dripstone mud; habitats re-enable water |
| Mob spawns | None needed | 0 | `natural_mob_spawns` defaults to EMPTY; custom spawners are Overworld-only |
| Passengers in a fast ship | None needed for kicks | 0 | Passenger positions are server-derived; vehicle checks apply only to *controlled* vehicles |

---

## 1. Gravity

### 1.1 The core: `Entity`

`net/minecraft/world/entity/Entity.java`:

```java
// :1543-1568
public boolean isNoGravity() { return this.entityData.get(DATA_NO_GRAVITY); }
protected double getDefaultGravity() { return 0.0; }                          // :1551
public final double getGravity() {                                            // :1555  <-- single choke point
    return this.isNoGravity() ? 0.0 : this.getDefaultGravity();
}
protected void applyGravity() {                                               // :1559
    double gravity = this.getGravity();
    if (gravity != 0.0) { this.setDeltaMovement(this.getDeltaMovement().add(0.0, -gravity, 0.0)); }
}
protected float getAirDrag() { return 0.98F; }                                // :1566
// :897
protected double getEffectiveGravity() { return this.getGravity(); }
```

`getEffectiveGravity()` is also used by the bounce logic `restituteMovementAfterCollisions` (`Entity.java:840-879`,
gravity compensation at `:855` and `:864`, drag lerp `:865`).

### 1.2 Every `getDefaultGravity()` override (grep-complete)

| Class (`net/minecraft/world/entity/...`) | Value | Line | How it is applied |
|---|---|---|---|
| `LivingEntity` | `getAttributeValue(Attributes.GRAVITY)`, base 0.08 | `:2390` | `travelInAir`/`travelInFluid`/elytra via `getEffectiveGravity()` (`:2395`) |
| `animal/squid/Squid` | **0.08 hard-coded** (ignores the GRAVITY attribute) | `:108` | `yd -= this.getGravity()` `:168` |
| `item/ItemEntity` | 0.04 | `:107` | `applyGravity()` `:130` |
| `item/FallingBlockEntity` | 0.04 | `:145` | `applyGravity()` `:156` |
| `item/PrimedTnt` | 0.04 | `:95` | `applyGravity()` `:102` |
| `ExperienceOrb` | 0.03 | `:96` | `applyGravity()` `:110` |
| `projectile/ThrowableProjectile` (snowball, egg, pearl, ...) | 0.03 | `:101` | `applyGravity()` `:46` |
| `projectile/throwableitemprojectile/AbstractThrownPotion` | 0.05 | `:45` | inherited tick |
| `projectile/throwableitemprojectile/ThrownExperienceBottle` | 0.07 | `:35` | inherited tick |
| `projectile/arrow/AbstractArrow` (arrow, spectral, trident) | 0.05 | `:337` | `applyGravity()` `:266` |
| `projectile/LlamaSpit` | 0.06 | `:38` | `applyGravity()` `:59` |
| `projectile/ShulkerBullet` | 0.04 | `:185` | `applyGravity()` `:200` (only with no target) |
| `projectile/FishingHook` | 0.03F | `:498` | **`add(0, -this.getDefaultGravity(), 0)` at `:238`, which bypasses `getGravity()`** |
| `vehicle/boat/AbstractBoat` | 0.04 | `:544` | `vspeed = -this.getGravity()` `:549`; **buoyancy uses `getDefaultGravity() / 0.65` at `:586`** |
| `vehicle/minecart/AbstractMinecart` | `isInWater() ? 0.005 : 0.04` | `:265` | `applyGravity()` (public override `:328`, called by `Old/NewMinecartBehavior.tick` `:52`/`:56`) |

### 1.3 How `LivingEntity` uses `Attributes.GRAVITY`

- `Attributes.GRAVITY = register("gravity", new RangedAttribute("attribute.name.gravity", 0.08, -1.0, 1.0).setSyncable(true)...)`
  (`ai/attributes/Attributes.java`). It is added to every living entity in `createLivingAttributes()`
  (`LivingEntity.java:336-363`).
- `LivingEntity.getDefaultGravity()` returns the attribute value (`:2389-2392`).
- `getEffectiveGravity()` (`:2394-2398`) applies Slow Falling:
  `isFalling && hasEffect(SLOW_FALLING) ? Math.min(getGravity(), 0.01) : getGravity()`.
- Consumers: `travelInAir` `:2449` (`movementY -= getEffectiveGravity()`), `travelInFluid` `:2475` (water and lava
  sinking via `getFluidFallingAdjustedMovement`, `baseGravity / 16.0` `:2663-2667`; lava `-baseGravity / 4.0`), and
  elytra `updateFallFlyingMovement` `:2579`.

### 1.4 Is there one choke point?

**Yes: `Entity.getGravity()` (final, `Entity.java:1555`).** Every caller in common and client code (grep-complete):

| Caller | Purpose |
|---|---|
| `Entity.applyGravity()` `:1560` | items, TNT, falling blocks, XP, thrown items, arrows, spit, shulker bullets, minecarts |
| `Entity.getEffectiveGravity()` `:898` and `LivingEntity.getEffectiveGravity()` `:2397` | all living movement, elytra, bounces |
| `Squid` `:168`, `AbstractBoat.floatBoat` `:549`, `ExperienceOrb` bounce check `:139` | entity-specific |
| `ai/behavior/LongJumpUtil.java:23` | frog and goat long-jump trajectories adapt automatically |
| `server/network/ServerGamePacketListenerImpl.java:370` | the anti-fly kick timer scales with gravity (see §10) |

The client code never calls `getGravity()` directly. Client-side prediction goes through the same entity methods.

**Gravity that does not go through `getGravity()`:**
1. `FishingHook.tick` `:238`: `-this.getDefaultGravity()`.
2. `AbstractBoat.floatBoat` buoyancy `:586`: `(deltaMovement.y + buoyancy * (this.getDefaultGravity() / 0.65)) * 0.75`. If you
   scale only `getGravity()`, buoyancy stays at Earth strength, so boats in habitat pools sit higher and bob.
3. Minecart slope acceleration, hard-coded `double slideSpeed = 0.0078125;` in `vehicle/minecart/OldMinecartBehavior.java:112`
   and `Math.max(0.0078125, horizontalDistance * 0.02)` in `NewMinecartBehavior.java:351`.
4. Wing or levitation "fall slowing", hard-coded `multiply(1.0, 0.6, 1.0)` when falling: `animal/chicken/Chicken.java:131`,
   `animal/parrot/Parrot.java:226`, `ambient/Bat.java:122`, `monster/Blaze.java:87`, `boss/wither/WitherBoss.java:155`.
5. Swim strokes (not gravity): `goDownInWater -0.04F` `:2374`, `jumpInLiquid +0.04F` `:2378`; bubble columns
   (`Entity.java:2933`, `:2964`).
6. **No gravity at all by design**, so there is nothing to scale: `FireworkRocketEntity` (thrust +0.04 y and ×1.15 horizontal,
   `:152-153`), `AbstractHurtingProjectile` and its subclasses (fireballs, wither skulls, dragon fireball, wind charges),
   `EyeOfEnder`, a homing `ShulkerBullet`, and flying mobs using `travelFlying` (ghast, phantom, allay, happy ghast).

### 1.5 Jumping

```java
// LivingEntity.java:2350
protected float getJumpPower(final float multiplier) {
    return (float)this.getAttributeValue(Attributes.JUMP_STRENGTH) * multiplier * this.getBlockJumpFactor() + this.getJumpBoostPower();
}
// :2359 jumpFromGround(): setDeltaMovement(x, Math.max(jumpPower, movement.y), z); sprint adds 0.2 horizontal
```

`JUMP_STRENGTH` defaults to `0.42F` (range 0 to 32). The jump sets an **initial velocity**, so lower gravity raises the jump
height automatically. Simulated with the real update order (`move`, then `vy = (vy - g) * drag`):

| Case | Peak height | Airtime |
|---|---|---|
| Earth vanilla (g 0.08, drag 0.98) | 1.252 blocks | 12 ticks |
| Mars g, vanilla vertical drag | 2.695 | 27 |
| Mars g, vertical drag `1 - 0.02·K` | 3.113 (2.49× Earth) | 29 |
| Mars g, no drag (ideal 1/0.379 = 2.64×) | 3.121 | 29 |

Mob pathfinding still treats jumps as 1-block steps (they will overshoot, which is harmless). Horse `JUMP_STRENGTH` jumps
scale the same way.

---

## 2. Air drag

### 2.1 `getAirDrag()` is the shared method in 26.3

`Entity.getAirDrag()` (`:1566`, 0.98) and every override (grep-complete; descriptor `getAirDrag()F`, bytecode-checked in
`Entity`):

| Class | Return | Line | Where it is called |
|---|---|---|---|
| `Entity` (used by `FallingBlockEntity`, `PrimedTnt`, `ItemEntity`) | 0.98F | `:1566` | `FallingBlockEntity:240`, `PrimedTnt:105`, `ItemEntity:147`, bounce `Entity:865` |
| `LivingEntity` | `computeModifiedFriction(omni ? 0.91F : 0.98F, AIR_DRAG_MODIFIER)` | `:2468` | **not** used by `travelInAir`; only bounce and `Squid:171` |
| `ExperienceOrb` | 0.98F | `:151` | `:133`, multiplied by block friction when on the ground |
| `projectile/ThrowableProjectile` | 0.99F | `:87` | `applyInertia` `:65-84`, air branch only (water uses 0.8F) |
| `projectile/LlamaSpit` | 0.99F | `:65` | `:58` |
| `projectile/arrow/AbstractArrow` | 0.99F | `:274` | `:262`, air only; water uses `getWaterInertia()` 0.6F (`:714`), 0.99F for tridents (`ThrownTrident:218`) |
| `vehicle/boat/AbstractBoat` | 1.0F | `:592` | bounce only |
| `vehicle/minecart/AbstractMinecart` | 0.95F | `:387` | `comeOffTrack` `:382`, only when `!onGround()` |

### 2.2 Every per-tick damping site, by family

| Family | Air | Water / lava / ground | Source |
|---|---|---|---|
| Living, `travelInAir` | horizontal `blockFriction * computeModifiedFriction(0.91F, AIR_DRAG_MODIFIER)`; vertical `computeModifiedFriction(0.98F, mod)` (0.91 for omnidirectional movers: Bee, Parrot, SulfurCube) | ground: `blockFriction` (via `FRICTION_MODIFIER`), with input scaled by `getFrictionInfluencedSpeed` (`0.21600002F / f³`) `:2692` | `LivingEntity.java:2438-2465` |
| Living, `travelFlying` (flying mobs) | ×0.91 all axes | water ×0.8, lava ×0.5 | `:2422-2436` |
| Living in water | n/a | `slowDown` 0.8 (sprint 0.9, Dolphin's Grace 0.96, depth strider lerp to 0.546), vertical ×0.8 | `:2485-2510` |
| Living in lava | n/a | ×0.5, or (0.5, 0.8, 0.5) when shallow | `:2518-2534` |
| Elytra | (0.99, 0.98, 0.99) plus aerodynamic terms (§3) | n/a | `:2574-2599` |
| Creative flight | the vertical result of `travelInAir` is replaced with `originalY * 0.6` | n/a | `player/Player.java:1381-1384` |
| Any entity | soul sand, honey: `getBlockSpeedFactor()` horizontal | | `Entity.java:826-827` |
| `ItemEntity` | `getAirDrag()` all axes | ground: `airDrag * blockFriction` horizontal, `-0.5` y bounce; water `setFluidMovement(0.99)`, lava 0.95 | `item/ItemEntity.java:112-183` |
| `ExperienceOrb` | `getAirDrag()` all axes | ground: `* blockFriction`; homing toward the player `:155-176` | `ExperienceOrb.java:101-148` |
| `FallingBlockEntity` | `scale(getAirDrag())` every tick | ground: `multiply(0.7, -0.5, 0.7)` | `item/FallingBlockEntity.java:150-243` |
| `PrimedTnt` | `scale(getAirDrag())` | ground: `multiply(0.7, -0.5, 0.7)` | `item/PrimedTnt.java:100-121` |
| Thrown items | `v = (v - g) * 0.99` (gravity *before* drag) | water 0.8 | `projectile/ThrowableProjectile.java:44-89` |
| Arrows and tridents | move, then `×0.99`, then `-g` | water ×0.6 (trident 0.99) before the move | `projectile/arrow/AbstractArrow.java:173-270` |
| Fireballs, skulls, wind charges | `v = (v + accelPower·v̂) * inertia`; inertia 0.95 (dangerous wither skull 0.73, wind charge 1.0) | liquid 0.8 | `projectile/hurtingprojectile/AbstractHurtingProjectile.java:72-155`, `WitherSkull:42`, `windcharge/AbstractWindCharge:133-140` |
| Firework | none | n/a | `projectile/FireworkRocketEntity.java:113-...` |
| `FishingHook` | **hard-coded `scale(0.92)`** in air and water | bobbing ×0.9 | `projectile/FishingHook.java:248-249` |
| `LlamaSpit` | `×0.99`, then gravity | discarded in water | `projectile/LlamaSpit.java:43-62` |
| `ShulkerBullet` | none (homing lerp 0.2) | | `projectile/ShulkerBullet.java:190-205` |
| `AbstractBoat` | horizontal `invFriction = 0.9F` when `IN_AIR`; no vertical drag | water 0.9, under water 0.45, land `landFriction`; buoyancy ×0.75 | `vehicle/boat/AbstractBoat.java:548-590` |
| `AbstractMinecart` | off rail, in air: `getAirDrag()` 0.95 | rail `getSlowdownFactor()` 0.997 ridden / 0.96 (old) or 0.975 (new); off-rail ground ×0.5; water 0.95 | `vehicle/minecart/AbstractMinecart.java:372-389`, `OldMinecartBehavior:403`, `NewMinecartBehavior:495` |
| `Squid` out of water | `yd * getAirDrag()` (LivingEntity, 0.98) | n/a | `animal/squid/Squid.java:164-171` |

### 2.3 The new `AIR_DRAG_MODIFIER` and `FRICTION_MODIFIER` attributes, and why not to use them here

`Attributes.AIR_DRAG_MODIFIER` (default 1.0, range 0 to 2048, syncable) and `FRICTION_MODIFIER` are new. They are used only
by `SulfurCubeArchetypes.java:191-192`. They feed

```java
// LivingEntity.java:519
private static float computeModifiedFriction(final float friction, final float modifier) {
    return Mth.clamp(1.0F - (1.0F - friction) * modifier, 0.0F, 1.0F);
}
// travelInAir :2459-2463
float entityAirDragModifier = (float)this.getAttributeValue(Attributes.AIR_DRAG_MODIFIER);
float airDrag = computeModifiedFriction(0.91F, entityAirDragModifier);
float friction = blockFriction * airDrag;                     // NOTE: ground friction includes airDrag
float verticalFriction = this.omnidirectionalAirMover() ? airDrag : computeModifiedFriction(0.98F, entityAirDragModifier);
this.setDeltaMovement(movement.x * friction, movementY * verticalFriction, movement.z * friction);
```

Setting `AIR_DRAG_MODIFIER = K` would give exactly "1 − (1 − d)·K" (the formula we want), but it changes the horizontal
0.91 too, and therefore **ground** friction (0.6·0.91 = 0.546 becomes 0.6·0.9985 ≈ 0.599: about 13% faster walking and
sliding). That breaks the "keep player horizontal air control" requirement, and it only covers living entities. Don't
use it for the Mars drag rule. It stays useful for per-entity effects such as a suit with drag fins.

### 2.4 Minimal mixin set for drag

1. **Multi-target `@ModifyReturnValue` on `getAirDrag()F`.** Targets: `Entity`, `LivingEntity`, `ExperienceOrb`,
   `ThrowableProjectile`, `LlamaSpit`, `AbstractArrow`, `AbstractMinecart` (`AbstractBoat` returns 1.0 and can be left out).
   Return `1 - (1 - original) * K` **only when `!onGround()`**. That keeps the `airDrag × blockFriction` ground friction
   of items and XP orbs, and the ground behaviour of TNT and falling blocks, exactly vanilla. Water and lava never go
   through `getAirDrag()`, so they stay untouched automatically.
2. **Living vertical drag:** `@ModifyExpressionValue(method = "travelInAir", at = @At(value = "CONSTANT", args = "floatValue=0.98F"))`
   in `LivingEntity`. Bytecode-checked: `travelInAir` has exactly one `ldc 0.98f` (offset 224) and one `ldc 0.91f`
   (offset 197). Return `1 - (1 - c) * K`. The 0.91 horizontal factor and the `AIR_DRAG_MODIFIER` attribute keep working on
   top.
3. Deliberately **not** scaled (document these in SCIENCE.md):
   - `AbstractHurtingProjectile.getInertia()`. These are self-propelled, with terminal speed `a·i/(1−i)` = 1.9 b/t. With
     `i' = 1 − 0.05·K` the terminal speed becomes about 120 b/t, which is absurd.
   - `FishingHook`'s 0.92 and the boat's `IN_AIR` 0.9 (both irrelevant on Mars).
   - `travelFlying`'s 0.91 for magic flyers.
   - The wing-flap ×0.6 constants.

### 2.5 Linear versus quadratic drag (decision for SCIENCE.md)

Vanilla drag is **linear** (`v *= d` is exponential decay), so terminal speed is `g·d/(1−d)`. Real drag is quadratic,
which gives `v_t ∝ sqrt(g/ρ)`. Scaling the linear coefficient by K overestimates the terminal speed on Mars by about 5×:

| Model, living entity | Mars terminal speed |
|---|---|
| Vanilla drag with Mars g | 1.49 b/t (feels floaty) |
| Linear, `d' = 1 − 0.02·K` | 94.7 b/t (1,894 m/s, effectively unlimited) |
| Quadratic-equivalent, `f(v) = 1 − 0.02·K·|v|/3.92` (matches vanilla at Earth's terminal 3.92 b/t) | **19.3 b/t** (≈ sqrt(G/K) × Earth) |
| Linear with an "effective" `K_eff ≈ 0.079` | 19.2 b/t (same terminal speed, simpler) |

For falls of 20 to 300 blocks the models barely differ (impact at 300 blocks: 4.24 b/t with either, against 3.59 on Earth).
They only diverge for the ship-scale drops of 1,000 to 3,000 blocks. Arrow range with a full bow (3.0 b/t), simulated:
Earth 117 blocks; Mars gravity only 191; Mars gravity + drag K 467; with `K_eff = 0.079`, 428. Long-range projectiles will
leave entity-ticking chunks and freeze in place (§12).

---

## 3. Elytra

`LivingEntity.travelFallFlying` (`:2553-2567`) calls `this.setDeltaMovement(this.updateFallFlyingMovement(lastMovement))`.
Bytecode-checked: this is the only call site.

```java
// LivingEntity.java:2574-2599
private Vec3 updateFallFlyingMovement(Vec3 movement) {
    Vec3 lookAngle = this.getLookAngle();
    float leanAngle = this.getXRot() * (float) (Math.PI / 180.0);
    double lookHorLength = Math.sqrt(lookAngle.x * lookAngle.x + lookAngle.z * lookAngle.z);
    double moveHorLength = movement.horizontalDistance();
    double gravity = this.getEffectiveGravity();
    double liftForce = Mth.square(Math.cos(leanAngle));
    movement = movement.add(0.0, gravity * (-1.0 + liftForce * 0.75), 0.0);          // (1) gravity minus "lift" 0.75·g·cos²θ
    if (movement.y < 0.0 && lookHorLength > 0.0) {                                    // (2) sink → forward speed
        double convert = movement.y * -0.1 * liftForce;
        movement = movement.add(lookAngle.x * convert / lookHorLength, convert, lookAngle.z * convert / lookHorLength);
    }
    if (leanAngle < 0.0F && lookHorLength > 0.0) {                                    // (3) pitch up: speed → climb
        double convert = moveHorLength * -Mth.sin(leanAngle) * 0.04;
        movement = movement.add(-lookAngle.x * convert / lookHorLength, convert * 3.2, -lookAngle.z * convert / lookHorLength);
    }
    if (lookHorLength > 0.0) {                                                         // (4) steer toward look (10%/tick)
        movement = movement.add((lookAngle.x / lookHorLength * moveHorLength - movement.x) * 0.1, 0.0,
                                (lookAngle.z / lookHorLength * moveHorLength - movement.z) * 0.1);
    }
    return movement.multiply(0.99F, 0.98F, 0.99F);                                     // (5) drag
}
```

Vanilla "lift" (1) is proportional to **g, not v²**, so it is speed-independent and unphysical. Terms (2) to (5) are all
aerodynamic.

**Recommended hook (robust to constant changes):** `@WrapOperation(method = "travelFallFlying", at = @At(value = "INVOKE",
target = "Lnet/minecraft/world/entity/LivingEntity;updateFallFlyingMovement(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))`.
In vacuum the update is pure free fall, `free = v + (0, −g_eff, 0)`, so blend: `result = free + k·(original.call(v) − free)`.
That scales every aerodynamic term at once: lift, sink-to-speed conversion, climb, steering, and drag.

Do not use `@ModifyReturnValue` with `@Local(argsOnly)`. The method reassigns its `movement` parameter, so you would
capture the *modified* value, not the input. `@WrapOperation` at the call site, or `@WrapMethod`, sees the original
argument.

Simulated steady glides on Mars (looking 0° to 30° down):

| Model | Speed | Glide ratio |
|---|---|---|
| Earth vanilla | 1.5 to 2.5 b/t | 9.5 to 6.6 |
| Linear blend `k = K` ("barely glides") | sink about −11 b/t after 600 ticks | 0.36 to 0.45 (essentially ballistic) |
| Linear blend `k = 0.079` | about 9 b/t | 1.4 to 1.9 |
| **Dynamic-pressure blend** `k = clamp(K·(|v|/1.5)², 0, 1)` | 6.3 b/t (126 m/s) | **about 9, the same as Earth**, but it takes about 300 ticks and about 550 blocks of dive to get there |

The physically correct statement for SCIENCE.md: L/D is density-independent, and the equilibrium airspeed scales by
`sqrt(G/K) ≈ 4.8×`. On a 512-high Mars the dynamic-pressure model reads as "barely glides" at normal heights while
staying honest.

Firework boost pitfall: `FireworkRocketEntity.java:129-136` uses
`movement + look·0.1 + (look·1.5 − movement)·0.5`, which pulls speed *toward* 1.5 b/t. At Mars dive speeds (> 1.5 b/t) a
rocket acts as a brake. If thrust should only add Δv, a small `@ModifyArg` on that `setDeltaMovement` is needed.

Wall impacts: `handleFallFlyingCollisions` (`:2601-2610`) deals `Δspeed·10 − 3`, so collisions at Mars glide speeds are
lethal. Fabric API also offers `EntityElytraEvents.ALLOW` / `CUSTOM` (`fabric-entity-events-v1`) if gliding should be
disabled outright.

---

## 4. Fall damage, fall distance and the attribute API

### 4.1 How fall distance accumulates

```java
// Entity.java:810, inside move(): only when isLocalInstanceAuthoritative()
this.checkFallDamage(movement.y, this.onGround(), effectState, effectPos);
// Entity.java:1578  (used by ServerGamePacketListenerImpl :518/:1205/:1221 for players and client-driven vehicles)
public final void doCheckFallDamage(final double xa, final double ya, final double za, final boolean onGround) { ... this.checkFallDamage(ya, onGround, state, pos); }
// Entity.java:1587
protected void checkFallDamage(final double ya, final boolean onGround, final BlockState onState, final BlockPos pos) {
    if (!this.isInWater() && ya < 0.0) { this.fallDistance -= (float)ya; }
    if (onGround) {
        if (this.fallDistance > 0.0) { onState.getBlock().fallOn(this.level(), onState, pos, this, this.fallDistance); ... }
        this.resetFallDistance();
    }
}
// Block.java:479
public void fallOn(Level level, BlockState state, BlockPos pos, Entity entity, double fallDistance) {
    double reducedFallDistance = fallDistance * (1.0F - this.getFallDistanceReduction());
    entity.causeFallDamage(reducedFallDistance, 1.0F, entity.damageSources().fall());
}
```

Bytecode-checked: `Entity` has exactly 2 invocations of `checkFallDamage(DZLBlockState;LBlockPos;)V`, in `move` and in
`doCheckFallDamage`. Overrides that do not call super: `AbstractBoat:741` (its own accumulation) and
Bee/Parrot/Allay/HappyGhast/Phantom/Ghast/Bat/Strider (they ignore falls). The server never runs `move()`-based fall
checks for `ServerPlayer`: `Player.isClientAuthoritative()` returns true (`player/Player.java:1244`), so its fall distance
comes from `doCheckFallDamage` with the movement deltas the client reports.

### 4.2 Damage

```java
// LivingEntity.java:1778  causeFallDamage(double fallDistance, float damageModifier, DamageSource source)
// (wind-charge impulse grace: effective = min(fallDistance, currentImpulseImpactPos.y - getY()))
// :1837
protected int calculateFallDamage(final double fallDistance, final float damageModifier) {
    if (this.is(EntityTypeTags.FALL_DAMAGE_IMMUNE)) return 0;
    double baseDamage = this.calculateFallPower(fallDistance);
    return Mth.floor(baseDamage * damageModifier * this.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER));
}
// :1846
private double calculateFallPower(final double fallDistance) {
    return fallDistance + 1.0E-6 - this.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);   // SAFE_FALL_DISTANCE base 3.0
}
```

`Entity.causeFallDamage` (`:1611`) **propagates to passengers** (`propagateFallToPassengers`, `:1620`). Other
fall-distance consumers: `item/FallingBlockEntity.causeFallDamage` (anvil and dripstone damage `ceil(fd−1)·perDistance`),
`MaceItem` (`:97-101`, smash damage from `attacker.fallDistance`), farmland trampling, turtle eggs, slime/hay/honey.

### 4.3 Recommended: scale the accumulator (energy-equivalent)

`@ModifyArg(method = {"move", "doCheckFallDamage"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;checkFallDamage(DZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V"), index = 0)`
returns `ya * G`.

With that, `fallDistance` means "Earth-equivalent drop height", which is proportional to impact kinetic energy `m·g·h`.
Every vanilla threshold then becomes physically consistent:
- Safe fall is 3 / 0.3794 = **7.91 Mars blocks**.
- Damage is `floor(0.3794 · (h − 7.91))`.
- A Mars jump (3.1 blocks) maps to 1.18, the same as an Earth jump.
- The mace, anvils, boats (via their own `checkFallDamage` override, since the call-site hook also feeds them) and
  farmland all scale.

Checked by hand: 7.9 blocks gives 2.99, so 0 damage; 11 blocks gives 4.17, so 1 damage. Both match the attribute formula.

### 4.4 Alternative: transient attribute modifiers (works, but more failure modes)

```java
// ai/attributes/AttributeModifier.java
public record AttributeModifier(Identifier id, double amount, AttributeModifier.Operation operation)
// Operation: ADD_VALUE(0), ADD_MULTIPLIED_BASE(1), ADD_MULTIPLIED_TOTAL(2)
// ai/attributes/AttributeInstance.java
public void addTransientModifier(AttributeModifier m)         // :91, throws IllegalArgumentException if the id already exists
public void addOrUpdateTransientModifier(AttributeModifier m) // :83, idempotent; prefer this
public boolean removeModifier(Identifier id)                  // :121
public boolean hasModifier(Identifier id)                     // :69
// calculateValue :148-170: base + ΣADD_VALUE; + base·ΣADD_MULTIPLIED_BASE; × Π(1 + ADD_MULTIPLIED_TOTAL); then sanitize (clamp to range)
public AttributeInstance.Packed pack()                         // :184, serializes ONLY permanent modifiers
```

For Mars: `GRAVITY` ADD_MULTIPLIED_TOTAL −0.6206; `SAFE_FALL_DISTANCE` ADD_MULTIPLIED_TOTAL +1.6357 (= 1/G − 1);
`FALL_DAMAGE_MULTIPLIER` ADD_MULTIPLIED_TOTAL −0.6206. Use `Identifier.fromNamespaceAndPath("redplanet", "mars_gravity")`.

Downsides, all verified in the code:
1. Living entities only. Items, arrows, TNT and boats still need the `getGravity()` mixin. `Squid` ignores `GRAVITY`
   (`:108`).
2. Transient modifiers are not saved (`pack()`), so they must be re-applied on every
   `ServerEntityEvents.ENTITY_LOAD`, removed on `ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL` (the
   `ServerPlayer` object survives dimension changes, `ServerPlayer.teleport` `:1124-1182`), and re-added after a respawn
   (`ServerPlayerEvents.AFTER_RESPAWN`; whether `ENTITY_LOAD` also fires for the respawned player is **UNVERIFIED**).
3. Client prediction lags by one attribute packet after a dimension change. Transient modifiers *are* synced
   (`ClientboundUpdateAttributesPacket` uses `getModifiers()`, `:35`; the respawn packet uses flag `3` = keep all data),
   but an environment attribute is correct the moment the client builds its new `ClientLevel`.
4. The fall-distance consumers in §4.2 (mace, anvils) are not covered.

**Verdict:** the `getGravity()` mixin for A and fall-distance scaling for C. Use attributes only for gear such as boots.

---

## 5. Air supply, drowning and the HUD

### 5.1 Server logic (`LivingEntity.baseTick`, `:413`; breathing block `:445-464`, server side only)

```java
if (this.isEyeInFluid(FluidTags.WATER) && !level.getBlockState(BlockPos.containing(getX(), getEyeY(), getZ())).is(Blocks.BUBBLE_COLUMN)) {
    boolean canDrownInWater = !this.canBreatheUnderwater()                     // tag minecraft:can_breathe_under_water (#undead, fish, armor_stand, copper_golem, ...)
        && !MobEffectUtil.hasWaterBreathing(this)                              // WATER_BREATHING || CONDUIT_POWER || BREATH_OF_THE_NAUTILUS
        && (!isPlayer || !((Player)this).getAbilities().invulnerable);
    if (canDrownInWater) {
        this.setAirSupply(this.decreaseAirSupply(this.getAirSupply()));
        if (this.shouldTakeDrowningDamage()) {                                  // getAirSupply() <= -20  (:510)
            this.setAirSupply(0);
            level.broadcastEntityEvent(this, (byte)67);                         // client: makeDrownParticles() (:2116)
            this.hurtServer(level, this.damageSources().drown(), 2.0F);
        }
    } else if (getAirSupply() < getMaxAirSupply() && MobEffectUtil.shouldEffectsRefillAirsupply(this)) {
        this.setAirSupply(this.increaseAirSupply(this.getAirSupply()));        // increaseAirSupply ordinal 0
    }
    if (this.isPassenger() && this.getVehicle() != null && this.getVehicle().dismountsUnderwater()) this.stopRiding();
} else if (this.getAirSupply() < this.getMaxAirSupply()) {
    this.setAirSupply(this.increaseAirSupply(this.getAirSupply()));            // increaseAirSupply ordinal 1
}
```

```java
// :592  respiration via the OXYGEN_BONUS attribute (enchantment/respiration.json adds minecraft:oxygen_bonus, add_value 1/level)
protected int decreaseAirSupply(final int currentSupply) {
    double oxygenBonus = respiration != null ? respiration.getValue() : 0.0;
    return oxygenBonus > 0.0 && this.random.nextDouble() >= 1.0 / (oxygenBonus + 1.0) ? currentSupply : currentSupply - 1;
}
protected int increaseAirSupply(final int currentSupply) { return Math.min(currentSupply + 4, this.getMaxAirSupply()); } // :604
```

`Entity.TOTAL_AIR_SUPPLY = 300` (`Entity.java:198`). Air is synced entity data (`DATA_AIR_SUPPLY_ID`, `:272`). Cadence: 300
ticks to 0, then down to −20, so the **first hit comes after 320 ticks (16 s)**. After that it is **2.0 damage every 20 ticks**.
Refill is +4 per tick (full in 75 ticks). Overrides: `IronGolem.decreaseAirSupply` (never drops), `Dolphin`, `Axolotl` and
`Nautilus` change the max; water animals use `handleAirSupply`. Bytecode-checked: `baseTick` has 1 `isEyeInFluid` call and
2 `increaseAirSupply` calls.

### 5.2 Why not "pretend underwater"

`@ModifyExpressionValue` on that `isEyeInFluid` would reuse the vanilla branch, but it brings in four things we don't want:
- Water Breathing, Conduit Power and Breath of the Nautilus would protect on Mars.
- `#minecraft:can_breathe_under_water` would protect entities that should not be protected.
- The damage would be `drown()`, with the message "drowned".
- `dismountsUnderwater()` would throw riders off horses and camels (tag `minecraft:dismounts_underwater`).

Fabric API already wraps the same expression (`fabric/mixin/content/registry/fluid/LivingEntityMixin.java:84`, custom-fluid
drowning). MixinExtras chains the two, but there is no reason to pile onto that call.

### 5.3 Recommended hook (one mixin class on `LivingEntity`)

1. `@ModifyReturnValue(method = "increaseAirSupply", at = @At("RETURN"))` with `@Local(argsOnly = true) int currentSupply`:
   return `currentSupply` (no refill) when the entity is anoxic. The parameter is never reassigned, so the capture is
   safe.
2. `@Inject(method = "baseTick", at = @At("TAIL"))`: run only on the server, only for living entities with the eye not in
   water (vanilla handles water). If anoxic and not protected:
   `setAirSupply(getAirSupply() - 1)` (or `decreaseAirSupply(...)` if Respiration should count; it shouldn't);
   at `<= -20`: `setAirSupply(0)` and `hurtServer(level, damageSources().source(HYPOXIA), 2.0F)`.
   Don't broadcast event 67, which spawns water bubbles.
3. Protection checks:
   - creative/spectator (`getAbilities().invulnerable`);
   - an entity tag `redplanet:does_not_breathe` (seed it with `#minecraft:undead`, `minecraft:armor_stand`,
     `minecraft:iron_golem`, `minecraft:copper_golem`);
   - a suit with O2 (consume O2 here);
   - the habitat attribute (§13) sampled at `getEyePosition()`;
   - **riding a pressurized vehicle**: passengers tick `baseTick` through `rideTick()` (`Entity.java:2430`), so the
     Starship must count as protection.

### 5.4 HUD (`client/net/minecraft/client/gui/Hud.java:902-935`, `extractAirBubbles(GuiGraphicsExtractor, Player, int, int, int)`)

```java
int maxAirSupplyTicks = player.getMaxAirSupply();
int currentAirSupplyTicks = Math.clamp(player.getAirSupply(), 0, maxAirSupplyTicks);
boolean isUnderWater = player.isEyeInFluid(FluidTags.WATER);
if (isUnderWater || currentAirSupplyTicks < maxAirSupplyTicks) {   // bubbles DO show out of water while air < max
    ... AIR_SPRITE / AIR_EMPTY_SPRITE ...
    else if (isPoppingBubble && airBubble == poppingAirBubblePosition && isUnderWater) { AIR_POPPING_SPRITE + playAirBubblePoppedSound }
}
```

So on Mars the vanilla bubbles appear and drain with no extra work, because air supply is synced. The **popping sprite
and pop sound are only played when `isUnderWater`**. Fabric's status-bar layout uses the same condition
(`impl/client/rendering/hud/HudStatusBarHeightRegistryImpl.java:119-124`).

Options:
- (a) Accept it as is.
- (b) A client `@ModifyExpressionValue` on the single `Player.isEyeInFluid` call inside `extractAirBubbles`
  (bytecode-checked, offset 23).
- (c) Preferred, no mixin: `HudElementRegistry.replaceElement(VanillaHudElements.AIR_BAR, old -> suitO2Bar)`
  (`fabric/api/client/rendering/v1/hud/HudElementRegistry.java`; the id is `minecraft:air_bar`), and register a
  `HudStatusBarHeightRegistry` provider if the height changes.

---

## 6. Damage sources from a custom type

```java
// world/damagesource/DamageSources.java:84-100 ("Access widened by fabric-transitive-access-wideners-v1 to accessible")
public final DamageSource source(final ResourceKey<DamageType> key)
public final DamageSource source(final ResourceKey<DamageType> key, final @Nullable Entity cause)
public final DamageSource source(final ResourceKey<DamageType> key, final @Nullable Entity directEntity, final @Nullable Entity causingEntity)
// Entity.damageSources() -> level().damageSources()   (Entity.java:4110)
// DamageSource ctor: new DamageSource(Holder<DamageType>) etc. (DamageSource.java:43-55)
```

```java
public static final ResourceKey<DamageType> HYPOXIA =
    ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath("redplanet", "hypoxia"));
entity.hurtServer(serverLevel, entity.damageSources().source(HYPOXIA), 2.0F);
```

`DamageType` is a data-driven record: `(String msgId, DamageScaling scaling, float exhaustion, DamageEffects effects,
DeathMessageType deathMessageType)` (`DamageType.java`). Data file `data/redplanet/damage_type/hypoxia.json`:
`{"message_id":"redplanet.hypoxia","scaling":"never","exhaustion":0.0,"effects":"drowning"}`. `effects` takes
`hurt|thorns|drowning|burning|poking|freezing`; `drowning` plays `PLAYER_HURT_DROWN`. Lang keys:
`death.attack.redplanet.hypoxia` and `death.attack.redplanet.hypoxia.player` (`DamageSource.java:72` builds
`"death.attack." + msgId`).

Vanilla `drown` is in these tags: `bypasses_armor`, `bypasses_wolf_armor`, `is_drowning`, `no_impact`, `no_knockback`,
`wither_immune_to`. Copy the set. `is_drowning` also makes the `drowningDamage` gamerule disable hypoxia
(`Player.isInvulnerableTo`, `:660`), which is probably the right behaviour.

---

## 7. Fire and combustion

### 7.1 Fire blocks

- `BaseFireBlock.getState(BlockGetter, BlockPos)` (`:42`) picks soul fire or fire.
- `BaseFireBlock.canBePlacedAt(Level, BlockPos, Direction)` (`:185`):
  `state.isAir() && (getState(level,pos).canSurvive(level,pos) || isPortal(...))`. `isPortal` applies to the Overworld and
  Nether only (`:168`).
- `BaseFireBlock.onPlace` (`:152-166`): `if (!state.canSurvive(level, pos)) level.removeBlock(pos, false);`. **Every fire
  placed by `setBlockAndUpdate` that cannot survive is removed immediately.**
- `FireBlock.canSurvive` (`:121`) needs a sturdy block below or a flammable neighbour. `SoulFireBlock.canSurvive` needs
  `#soul_fire_base_blocks`.
- `FireBlock.tick` (`:127-200`) begins `if (!state.canSurvive(level, pos)) level.removeBlock(...)`, but *does not return*
  and goes on to spread. Every spread placement then hits `onPlace` and is removed.
- Callers that place fire:

  | Caller | Location | Check before placing |
  |---|---|---|
  | Flint & steel | `FlintAndSteelItem.useOn` `:31-48` | `canBePlacedAt` |
  | Fire charge | `FireChargeItem.useOn` `:31-50` | `canBePlacedAt` |
  | Dispenser (flint & steel) | `FlintAndSteelDispenseItemBehavior:29-30` | `canBePlacedAt` |
  | Lightning | `LightningBolt.spawnFire` `:153-170` | explicit `fire.canSurvive` |
  | Lava | `LavaFluid.randomTick` `:80-114` | none, relies on `onPlace` |
  | Explosions | `ServerExplosion.createFire` `:226-232` | none, relies on `onPlace` |
  | Blaze or dispenser small fireballs | `SmallFireball.onHitBlock` `:51-62` | none, relies on `onPlace` |
  | End crystals | `EndCrystal.tick` `:58-62` | none, relies on `onPlace` |

- **All of them go through `BlockBehaviour.BlockStateBase.canSurvive(LevelReader, BlockPos)`**
  (`state/BlockBehaviour.java:841`, bytecode-checked as public, not final), either before placing or in `onPlace` after.

### 7.2 Torches and lanterns

- `BaseTorchBlock.canSurvive` (`:43`), `WallTorchBlock.canSurvive` (`:48`), `LanternBlock.canSurvive` (`:61`).
- `RedstoneTorchBlock` extends `BaseTorchBlock` and is electrical, so it must stay allowed.
- Placement uses `BlockItem.canPlace(...)`: `stateForPlacement.canSurvive(level, pos)` (`item/BlockItem.java:135`).
  `StandingAndWallBlockItem.canPlace` → `possibleState.canSurvive(level,pos)` (`:23`); `WallTorchBlock.getStateForPlacement`
  calls `state.canSurvive` (`:69`). All of these use the state-level method, so the same `BlockStateBase.canSurvive` hook
  blocks placement.
- `updateShape` calls the *block-level* `this.canSurvive(...)`, so torches that already exist do not pop through this
  hook. Handle habitat depressurisation explicitly (see the pitfalls).
- Vanilla tags: `#minecraft:fire` = fire, soul_fire; `#minecraft:lanterns` = lantern, soul_lantern and the 8 copper
  lanterns. There is **no torch tag**: list torch, wall_torch, soul_torch, soul_wall_torch, copper_torch and
  copper_wall_torch explicitly.

### 7.3 Lit-state blocks

- **Campfires** (`CampfireBlock`):
  - `getStateForPlacement` (`:123-132`) sets `LIT = !replacedWater`.
  - `onProjectileHit` (`:218-227`) lights the campfire when the projectile `isOnFire()`.
  - `static canLight(BlockState)` (`:323`) is used by flint & steel, fire charge and the dispenser. It takes no
    position, so you cannot hook location there.
  - `getTicker` (`:302-317`) cooks only while lit.
- **Candles**: `CandleBlock.canLight` (`:156`); `AbstractCandleBlock.onProjectileHit` (`:41`) → `setLit(level, state, pos,
  true)` → `level.setBlock(pos, state.setValue(LIT, lit), 11)` (`:88-90`).
- **Candle cakes**: `CandleCakeBlock.canLight` (`:148`).
- Every lighting path ends in `Level.setBlock(BlockPos, BlockState, int, int)` (`world/level/Level.java:218`,
  bytecode-checked). That is the live-world choke point: `LevelWriter.setBlockAndUpdate` and `setBlock(pos, state, flags)`
  default into it. Worldgen does not use it.

### 7.4 Entity burning (`Entity.java`)

- The per-tick burn in `baseTick` (`:559-572`) deals `onFire` damage every 20 ticks and calls
  `setRemainingFireTicks(remainingFireTicks - 1)`.
- `lavaIgnite` (`:633`) → `igniteForSeconds(15)`; `lavaHurt` (`:639`) deals 4 thermal damage.
- `igniteForTicks` (`:660`) → `setRemainingFireTicks` (`:668`). `LivingEntity.igniteForTicks` (`:4017`) multiplies by the
  `BURNING_TIME` attribute. `Player.setRemainingFireTicks` (`:1737`) calls super.
- `InsideBlockEffectType.FIRE_IGNITE` → `BaseFireBlock::fireIgnite`, `LAVA_IGNITE` → `Entity::lavaIgnite`.
- Rain extinguishes in `applyEffectsFromBlocks`: `if (this.isInRain()) this.clearFire();` (`:1001-1003`).
- Every ignition path (fire blocks, lava, Fire Aspect via `enchantment/effects/Ignite`, flaming arrows, small fireballs,
  zombies, the daylight burning in `Mob:493` gated by the `gameplay/monsters_burn` attribute) ends in
  **`Entity.setRemainingFireTicks(int)`**.

### 7.5 Furnaces, smokers and blast furnaces (`level/block/entity/AbstractFurnaceBlockEntity.java`)

- `serverTick(ServerLevel, BlockPos, BlockState, AbstractFurnaceBlockEntity)` (`:148`): decrements `litTimeRemaining`
  (a private int, `:68`). When it is not lit and fuel plus a valid recipe are present, it sets
  `newLitTime = entity.getBurnDuration(level, fuel)`, and only `if (newLitTime > 0)` does it consume fuel and set
  `isLit`. On a change it writes `state.setValue(AbstractFurnaceBlock.LIT, isLit)`.
- `protected int getBurnDuration(ServerLevel, ItemStack)` (`:272`) =
  `ResolvableInt.getFromItem(fuel, DataComponents.COOKING_FUEL, CookingFuel::burnTime, getLootContext(level), 0)`.
- In 26.3, fuel is the data component `cooking_fuel`. Burn times are data-driven `context_int_provider` entries
  (`data/minecraft/context_int_provider/cooking/time_*.json`), all
  `div(N, conditional(block/fast_cooking, normal_burn_time_reduction_factor=1, fast=2))`. The loot context carries
  `ORIGIN` = the furnace centre (`BaseContainerBlockEntity.getLootContext`, `:180-190`).
- There is a vanilla **`minecraft:environment_attribute_check` loot condition**
  (`storage/loot/predicates/EnvironmentAttributeCheck.java`; positional, using `ORIGIN`). A fully data-driven option
  exists: override the two `*_burn_time_reduction_factor` providers with
  `conditional(env_check(redplanet:gameplay/combustion=false), 1000000, 1)`, so integer division yields 0. It overrides
  vanilla files, so a mixin is cleaner.

### 7.6 `increased_fire_burnout` (the existing attribute)

`EnvironmentAttributes.INCREASED_FIRE_BURNOUT` is a positional boolean set in jungle, swamp and mangrove-like biomes. In
`FireBlock.tick` (`:162-189`) it only lowers the `checkBurnOut` chances by 50 and halves the spread odds. It **does not
prevent** fire, so it is useless for "no combustion".

### 7.7 Minimal hook set for E

1. `@ModifyReturnValue` on `BlockBehaviour$BlockStateBase.canSurvive(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z`:
   if `state.is(redplanet:requires_oxygen)` (`#minecraft:fire`, torches, `#minecraft:lanterns`) and the position is
   anoxic, return `false`. This one hook blocks flint & steel, fire charges, dispensers and lightning, auto-removes fire
   from lava, explosions, fireballs and spread, and blocks torch and lantern placement by players, dispensers and
   endermen. `LevelReader.environmentAttributes()` exists (`LevelReader.java:231`), so it also works in `WorldGenRegion`.
   Check the tag first so the hot path stays cheap.
2. `@ModifyVariable(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), argsOnly = true)`
   on `Level`: if the state is in `redplanet:extinguished_without_oxygen` (`#campfires`, `#candles`, `#candle_cakes`) and
   `LIT` is true at an anoxic position, set `LIT` to false. This covers placement, flint & steel, fire charges,
   dispensers, flaming arrows and other mods.
3. `@ModifyVariable(method = "setRemainingFireTicks", at = @At("HEAD"), argsOnly = true)` on `Entity`: when the value is
   above 0 and the entity is anoxic, return `Math.min(value, 0)`. Burning entities go out on their next decrement, and
   lava, Fire Aspect and fireballs no longer ignite. Lava still deals its 4 thermal damage (`lavaHurt`).
4. `@ModifyReturnValue(method = "getBurnDuration", at = @At("RETURN"))` on `AbstractFurnaceBlockEntity`: return 0 when
   anoxic at `worldPosition`. Add `@Inject(method = "serverTick", at = @At("HEAD"))` (static; cast `entity` to the mixin
   and `@Shadow private int litTimeRemaining`) to set `litTimeRemaining = 0` so an already-lit furnace goes out. The
   vanilla `wasLit != isLit` branch then writes `LIT=false` itself.
5. Optional:
   - `@Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)` on `LavaFluid` (`:80`), to avoid placing and
     removing fire.
   - `UseBlockCallback` (`fabric/api/event/player/UseBlockCallback.java:52`,
     `interact(Player, Level, InteractionHand, BlockHitResult)`) returning `FAIL` for flint & steel or fire charge
     targets. That is nicer UX: no wasted durability and no success sound. It is player-only, so it does not replace the
     hooks above.
   - An optional `CampfireBlockEntity` tick guard.
6. Document in SCIENCE.md:
   - **Gunpowder, TNT and fireworks carry their own oxidizer** (KNO₃), so they keep working. Vanilla TNT is lit directly in
     `TntBlock.useItemOn`, which the hooks above do not touch. Decide whether flint-and-steel sparks can light it.
   - The brewing stand (blaze powder) is not combustion.
   - Redstone torches stay allowed.

---

## 8. Water

### 8.1 Every read of `EnvironmentAttributes.WATER_EVAPORATES` (positional boolean, default false, syncable)

| Site | Behaviour |
|---|---|
| `item/BucketItem.java:121` `emptyContents` | Water is not placed; plays fizz plus `LARGE_SMOKE`. The check runs **before** the `LiquidBlockContainer` branch, so waterlogging is blocked too. It runs client-side as well, for prediction. Dispensers go through `emptyContents`. |
| `level/block/IceBlock.java:40` `playerDestroy` | Breaking ice gives `removeBlock` (no water). |
| `level/block/IceBlock.java:60` `melt` | Melting gives `removeBlock`: **sublimation**. `FrostedIceBlock` melts through this too. |
| `level/block/WetSpongeBlock.java:27` `onPlace` | A wet sponge dries instantly. |
| `level/block/PointedDripstoneBlock.java:175` | Mud above a stalactite no longer counts as a water source. |
| `data/worldgen/DimensionTypes.java:94` | The Nether sets it to true; it is in `the_nether.json` as `"minecraft:gameplay/water_evaporates": true`. |

Fabric API doesn't read it (only `FAST_LAVA` in the transfer API). Not covered by the attribute:
- Water that **flows** into an evaporating area. `FlowingFluid.spreadTo(LevelAccessor, BlockPos, BlockState, Direction,
  FluidState)` (`material/FlowingFluid.java:267`) is the guard point; `WaterFluid` does not override it, while
  `LavaFluid` does.
- Water cauldrons.
- Water already present from structures or commands.
- Worldgen fluids. Use `default_fluid` air and no aquifers in the Mars noise settings.

### 8.2 Ice and snow ticks

- `IceBlock.randomTick` (`:53-57`): melts when `getBrightness(BLOCK, pos) > 11 - state.getLightDampening()`. Ice is
  `noOcclusion` with a full shape, so dampening is 1 and ice melts at **block light ≥ 11**. On Mars that means removal
  (sublimation). Sunlight never melts ice in vanilla. Temperature-driven sublimation (equator versus poles) needs our own
  random tick, either our own ice block or a `randomTick` hook.
- Snow layers melt at block light > 11 and drop items; they never make water.
- **Freezing:** `ServerLevel.tickChunk` → `tickPrecipitation` (`server/level/ServerLevel.java:503-515`, `:585-591`) runs
  every chunk tick with a 1/48 chance, *independent of weather*. It freezes the top `MOTION_BLOCKING` block if
  `biome.shouldFreeze(level, pos)` (`level/biome/Biome.java:141-166`): temperature `< 0.15`, block light `< 10`, a water
  source, and not fully surrounded by water. With cold Mars biomes (temperature < 0.15), exposed water freezes. Water
  under a habitat roof is not the heightmap top, so it does not freeze.
- Biome temperature also drives snow-versus-rain visuals and foliage colours. Keep `has_precipitation: false`.

### 8.3 Habitats

Every vanilla read is positional, so a positional environment-attribute layer that returns `false` inside habitat
volumes automatically re-enables buckets, ice melting into water, and the rest (§13). The client also evaluates
`BucketItem` (the attribute is syncable), so habitat volumes should be synced to clients. Otherwise the client predicts
evaporation and the server's block update corrects it a moment later.

---

## 9. Sound (client)

- **There is no environment-based filtering or DSP in vanilla.** Grepping `client/net/minecraft/client/sounds`,
  `client/com/mojang/blaze3d/audio` and the sound resources finds no EFX, low-pass or filter use. "Underwater" only plays
  ambient loops: `UnderwaterAmbientSoundHandler` and `UnderLiquidAmbientSoundInstance`.
- Volume: `SoundEngine.calculateVolume(float, SoundSource)` (private, `client/.../sounds/SoundEngine.java:486`) =
  `clamp(volume) * options.getFinalSoundSourceVolume(source) * gainBySource.getFloat(source)`.
  `SoundManager.updateCategoryVolume(SoundSource, float)` (`SoundManager.java:226`) sets the per-category gain and
  refreshes sounds already playing. It is public, and only `MusicManager` uses it, for `MUSIC`. That makes a **no-mixin
  per-category muffling** path for Mars (restore on leaving).
- Distance: `Channel.linearAttenuation(float maxDistance)` (`client/com/mojang/blaze3d/audio/Channel.java:108`) sets the
  linear-clamped model with max distance = `sound.getAttenuationDistance(volume)`. A `@ModifyVariable` there shortens
  audible range on Mars.
- Low-pass: LWJGL 3.4.3's `lwjgl-openal` contains `org/lwjgl/openal/EXTEfx.class` (checked in the Gradle cache). A direct
  `AL_FILTER_LOWPASS` on each source (`AL_DIRECT_FILTER`) needs a mixin where the source is configured: the
  `handle.execute(channel -> {...})` in `SoundEngine.play` (`:436-448`), or a hook on `Channel.create()` (static,
  `Channel.java:23`). The context is created without auxiliary sends (`Library.createAttributes`, `:106-117`), but a
  direct filter does not need them. **UNVERIFIED:** that the macOS arm64 OpenAL Soft runtime exposes `ALC_EXT_EFX`
  (expected, but check `alcIsExtensionPresent` and fall back to volume only).
- Data-driven ambience exists: the environment attributes `audio/ambient_sounds`, `audio/background_music` and
  `audio/music_volume`.

---

## 10. Movement validation and fast vehicles (`server/network/ServerGamePacketListenerImpl.java`)

- **Passengers are never position-checked.** In `handlePlayerPositionChange` (`:1102-1226`):

  ```java
  if (this.player.isPassenger()) {
      this.player.absSnapTo(this.player.getX(), this.player.getY(), this.player.getZ(), targetYRot, targetXRot);
      this.player.level().getChunkSource().move(this.player);
  } else { ... "moved too quickly" (100, or 300 when gliding; :1143-1150) ... "moved wrongly" (:1176-1186) ... }
  ```

  The passenger's position is server-derived: `Entity.rideTick()` → `vehicle.positionRider(passenger)` → `setPos`
  (`Entity.java:2430-2448`). That holds on both sides.
- **Floating kicks:** in `tickPlayer` (`:331-366`) the player check requires `!this.player.isPassenger()`. The vehicle
  check runs only when `lastVehicle.getControllingPassenger() == this.player`. `clientVehicleIsFloating` also excludes
  `vehicle.isFlyingVehicle()` (`Entity.java:4097`, default false) and `vehicle.isNoGravity()` (`:520-525`). The limit is
  `getMaximumFlyingTicks` (`:369-377`) = `ceil(80 · max(0.08 / entity.getGravity(), 1))`, which is about 211 ticks under Mars
  gravity when the `getGravity()` mixin is used.
- **Who sends `ServerboundMoveVehiclePacket`:** only the client that is authoritative for the root vehicle
  (`client/.../player/LocalPlayer.java:273-280`):

  ```java
  if (this.isPassenger()) {
      this.connection.send(new Rot(...));                       // rotation only
      Entity vehicle = this.getRootVehicle();
      if (vehicle != this && vehicle.isLocalInstanceAuthoritative()) { this.connection.send(ServerboundMoveVehiclePacket.fromEntity(vehicle)); }
  }
  ```

  `isLocalInstanceAuthoritative()` (`Entity.java:3695`) is `level.isClientSide() ? isLocalClientAuthoritative() :
  !isClientAuthoritative()`. Both depend on `getControllingPassenger()`, which defaults to `null` (`:3616`). **A Starship
  with no controlling passenger is fully server-authoritative and the client never sends vehicle moves.** If a client
  does send one for a vehicle it does not control, `handleMoveVehicle` (`:530-535`) only logs and resyncs, at most every 20
  ticks.
- **No speed caps:**
  - `Entity.setDeltaMovement` (`:3845`) only rejects non-finite values.
  - Velocity is sent as `LpVec3` (`network/LpVec3.java`): range ±1.7e10, 15-bit precision relative to the largest
    component (about 0.0018 b/t at 30 b/t). There is **no ±3.9 clamp in 26.3** (`ClientboundSetEntityMotionPacket` and
    `ClientboundAddEntityPacket` use `Vec3.LP_STREAM_CODEC`).
  - Position deltas are limited to ±8 blocks (`VecDeltaCodec.isDeltaTooBig`, 1/4096 steps). Larger moves fall back to
    `ClientboundEntityPositionSyncPacket` with full precision (`ServerEntity.createMovePacket`, `:217-237`).
  - For passengers `ServerEntity` sends only rotation (`:136-145`).
  - Set the Starship's `EntityType.Builder.updateInterval(1)` and a large `clientTrackingRange`.
- **Notes for the Starship agent:**
  - Sneak dismounts on the server: `Player.rideTick` → `wantsToStopRiding()` = `isShiftKeyDown()`
    (`player/Player.java:298`, `:435-440`). Vanilla gives the vehicle no veto, so locking passengers in needs a small
    mixin.
  - **The ship's own `fallDistance` accumulates while it descends, and `Entity.causeFallDamage` propagates it to
    passengers** (`Entity.java:1611-1625`). Override `causeFallDamage` or call `resetFallDistance()` every tick, or the
    crew dies at touchdown.
  - Dimension changes dismount unless `TeleportTransition.asPassenger()` (`ServerPlayer.teleport`, `:1136-1138`).
  - Opt the ship out of the gravity and drag hooks (`setNoGravity(true)`, kinematic control).
  - Keep the ascent column inside one chunk (§12).

---

## 11. Mob spawning

- In 26.3, biome spawn lists are the environment attribute **`minecraft:gameplay/natural_mob_spawns`**
  (`EnvironmentAttributes.NATURAL_MOB_SPAWNS`, default `MobSpawnSettings.EMPTY`, `fullResolutionBiomes`). They are read by
  `NaturalSpawner` (`:81`, `:352`, `:476`, `:496`) and `ChunkGenerator.getMobsAt` (`:480`, after structure spawn
  overrides). `Biome.BiomeBuilder.mobSpawnSettings` writes the attribute with the `overlay` modifier (`Biome.java:301-304`).
- The biome codec has no `spawners` field any more; the fields are climate, `attributes` (positional only), `effects` and
  generation (`Biome.java:40-48`).
- **A Mars biome that sets nothing, or sets an empty overlay like `the_void.json`, gets no natural spawns.** That
  includes the chunk-generation creature pass (`CREATURE_WORLD_GEN_SPAWN_PROBABILITY`, `NaturalSpawner:352-355`).
- Custom spawners: `MinecraftServer.createLevels` passes `PhantomSpawner`, `PatrolSpawner`, `CatSpawner`, `VillageSiege`
  and `WanderingTraderSpawner` **only to the Overworld** (`server/MinecraftServer.java:427-432`). Every other `LevelStem`
  gets `ImmutableList.of()` (`:466-469`), so Mars has no phantoms, patrols, cats, sieges or traders.
- Skeleton-horse traps need weather and thunder (`ServerLevel.tickThunder`). Also set `gameplay/can_start_raid=false`;
  `can_pillager_patrol_spawn` is moot.

---

## 12. Height limits and ticking far above the build limit

- **No removal for being high.** `Entity.checkBelowWorld` (`:605-609`) only acts below `minY − 64`: `discard`, or for
  living entities `fellOutOfWorld` damage, 4 per tick (`LivingEntity:2169`). The exceptions:
  - `FallingBlockEntity` discards after 100 ticks above `maxY` or after 600 ticks of falling (`:175`).
  - `AbstractWindCharge` explodes above `maxY + 30` (`:149`).
- Player packets clamp Y to ±2e7 (`clampVertical`, `:447`); X and Z are clamped to ±3e7.
- **Entity ticking is 2D.** `ServerLevel.tick` (`:434-456`) ticks an entity if it is a `ServerPlayer` or if
  `inEntityTickingRange(entity.chunkPosition().pack())`, which depends on chunk X/Z only. `isPositionEntityTicking`
  (`:1911`) is also chunk-based. Entity sections are stored per chunk column at any Y (`EntitySectionStorage`,
  `PersistentEntitySectionManager:185-198`). An entity at y = 1500 to 3000 ticks and saves normally as long as its column
  is entity-ticking.
- Tracking range is horizontal distance only (`ChunkMap.TrackedEntity.updatePlayer`, `:1371-1394`), so altitude does
  not untrack the ship.
- Client rendering: entities outside build height skip the "section compiled" test
  (`client/.../renderer/extract/LevelExtractor.java:288`: `level.isOutsideBuildHeight(y) || isSectionCompiledAndVisible`).
  Sky light above the top section reads 15 (`lighting/SkyLightSectionStorage.java:24-49`).
- **Pitfall:** fast *horizontal* motion can enter chunks that are not yet entity-ticking, and the entity then freezes.
  The passenger's chunk tickets follow it through `getChunkSource().move(player)` on each rotation packet, but loading
  lags. Keep the flight column vertical, or add a chunk ticket along the path. Long-range Mars projectiles will also
  freeze at the edge of simulation distance.

---

## 13. Shared infrastructure: custom environment attributes and the habitat layer

- Registry: `BuiltInRegistries.ENVIRONMENT_ATTRIBUTE`. Vanilla registers with
  `Registry.register(BuiltInRegistries.ENVIRONMENT_ATTRIBUTE, Identifier.withDefaultNamespace(id), attr)`
  (`attribute/EnvironmentAttributes.java:187-191`). Mods do the same with their own namespace at init.
- Builder (`attribute/EnvironmentAttribute.java`):
  `EnvironmentAttribute.builder(AttributeTypes.FLOAT|BOOLEAN).defaultValue(v).valueRange(...).syncable().notPositional().spatiallyInterpolated().build()`.
  Only `syncable()` attributes reach the client: `EnvironmentAttributeMap.NETWORK_CODEC` filters them, and both
  `DimensionType.NETWORK_CODEC` and `Biome.NETWORK_CODEC` use it. **Physics attributes must be syncable**, because
  living, projectile, item, XP and falling-block movement is simulated on both sides: `Projectile`, `ItemEntity`,
  `ExperienceOrb` and `FallingBlockEntity` return `MoveSimulationType.SERVER_AND_CLIENT`, and players are client-authoritative.
- Biomes accept only positional attributes (`Biome` uses `EnvironmentAttributeMap.CODEC_ONLY_POSITIONAL`). A
  `notPositional()` attribute can only be set on the dimension type or a timeline, which is right for gravity.
- Reading:
  - `level.environmentAttributes()` returns `EnvironmentAttributeSystem`; `LevelReader.environmentAttributes()` returns
    `EnvironmentAttributeReader`.
  - `getDimensionValue(attr)` is cached per tick (`ValueSampler.getDimensionValue`).
  - `getValue(attr, Vec3|BlockPos)` falls back to the cached dimension value when no positional layer exists
    (`EnvironmentAttributeSystem.java:255`).
  - `getDimensionValue` on a positional attribute throws when `SharedConstants.IS_RUNNING_IN_IDE` (`:115-116`). Nothing
    in the sources sets that flag, but always pass a position for positional attributes.
- Proposed attributes. Set them in `data/redplanet/dimension_type/mars.json` under `"attributes"`, alongside
  `"minecraft:gameplay/water_evaporates": true`:

  | Attribute | Type | Default | Flags | Mars value |
  |---|---|---|---|---|
  | `redplanet:gameplay/gravity_scale` | FLOAT | 1.0 | syncable, notPositional | 0.3794 |
  | `redplanet:gameplay/air_density` | FLOAT | 1.0 | syncable, notPositional | 0.0163 (or `K_eff`) |
  | `redplanet:gameplay/breathable` | BOOLEAN | true | syncable, positional | false |
  | `redplanet:gameplay/combustion` | BOOLEAN | true | syncable, positional | false |

- Fabric API: `DimensionEvents.MODIFY_ATTRIBUTES` (`fabric/api/dimension/v1/DimensionEvents.java`,
  `modifyDimensionAttributes(Holder<DimensionType>, EnvironmentAttributeMap.Builder, HolderLookup.Provider)`) can add
  attributes to other dimensions.
- **UNVERIFIED:** whether Fabric registry sync needs extra metadata for modded `ENVIRONMENT_ATTRIBUTE` entries. Values
  are encoded by name, so a client without the mod fails to decode the dimension type. The mod is required on both sides
  anyway.
- **Habitat layer.** Both `ServerLevel` (`:309`) and `ClientLevel` (`client/.../multiplayer/ClientLevel.java:269-274`) build
  their attribute system through `EnvironmentAttributeSystem$Builder.addDefaultLayers(Level)` (bytecode-checked as public).
  A common `@Inject(method = "addDefaultLayers", at = @At("TAIL"))` can call the public
  `addPositionalLayer(attr, (base, pos, interp) -> habitatIndex(level).contains(pos) ? overrideValue : base)` for
  `breathable`, `combustion` and `minecraft:gameplay/water_evaporates`. The layer interface is
  `EnvironmentAttributeLayer.Positional.applyPositional(Value, Vec3, @Nullable SpatialAttributeInterpolator)`. Layers
  apply in insertion order (dimension, biome, timelines, weather, then ours).
  - **Vanilla code that reads `water_evaporates`, and the loot condition `environment_attribute_check`, then respects
    habitats with no further hooks.**
  - The index must be cheap (per chunk or per section), since `canSurvive`, `setBlock` and air checks sample it.
  - It must be synced to clients, through a custom payload.

---

## Recommendations for redplanet

### Rule A: gravity 0.379 g for all entities

- **Hook:** `EntityMixin`, `@ModifyReturnValue(method = "getGravity", at = @At("RETURN"))` on `Entity.getGravity()D`:
  `return original * gravityScale(level())`, where `gravityScale` reads `redplanet:gameplay/gravity_scale` with
  `getDimensionValue`. It runs on both sides.
- **Call-site fixes:**
  - `@ModifyExpressionValue` on the `getDefaultGravity()D` INVOKE in `FishingHook.tick` (`:238`).
  - The same on the `getDefaultGravity()D` INVOKE in `AbstractBoat.floatBoat` (`:586`). Buoyancy scales with g, so scale
    it too.
  - Optional: `@ModifyExpressionValue` CONSTANT `doubleValue=0.0078125` in `OldMinecartBehavior` (`:112`) and
    `NewMinecartBehavior.calculateSlopeSpeed` (`:351`).
- **Automatic side effects:** jumps reach about 3.1 blocks; frog and goat long jumps retarget; the anti-fly kick timer
  scales; Slow Falling still caps at 0.01.
- **Pitfalls:**
  - Don't also scale through the GRAVITY attribute; that would double-apply to living entities.
  - Nothing to scale on fireworks, fireballs, wind charges, ender eyes or homing shulker bullets. Say so in SCIENCE.md.
  - Wing-flap ×0.6 for chickens, parrots and bats is left alone.
  - The Starship should use NoGravity or its own kinematics.

### Rule B: drag ∝ density, vertical only for living entities; elytra scaled

- **Hook 1:** multi-target `@Mixin({Entity.class, LivingEntity.class, ExperienceOrb.class, ThrowableProjectile.class,
  LlamaSpit.class, AbstractArrow.class, AbstractMinecart.class})` with `@ModifyReturnValue(method = "getAirDrag", at = @At("RETURN"))`:
  `onGround() ? d : 1 - (1 - d) * k`.
- **Hook 2:** `@ModifyExpressionValue(method = "travelInAir", at = @At(value = "CONSTANT", args = "floatValue=0.98F"))` in
  `LivingEntity`. The 0.91 horizontal factor is untouched, so ground friction and air control stay vanilla.
- **Hook 3 (elytra):** `@WrapOperation` on the `updateFallFlyingMovement` INVOKE in `LivingEntity.travelFallFlying`:
  `free + kEff·(vanilla − free)`, with `kEff = clamp(K·(|v|/1.5)², 0, 1)` recommended (§3).
- **Choose k:** the linear `K = 0.0163` (the brief's literal mapping; terminal speed effectively unlimited) or the
  quadratic-equivalent `f(v)` or `K_eff ≈ 0.079` (§2.5). Record the choice in SCIENCE.md.
- **Pitfalls:**
  - XP-orb homing (`ExperienceOrb:155-176`) relies on 0.98 damping. With drag near 1, orbs may overshoot or orbit the
    player (**UNVERIFIED**; test this, or keep vanilla drag while `followingPlayer != null`).
  - Arrows reach about 430 to 470 blocks and freeze outside simulation distance.
  - Don't scale `AbstractHurtingProjectile` inertia, because its terminal speed explodes.
  - Fireworks brake elytra at Mars dive speeds (§3).

### Rule C: fall damage ∝ g·h (safe fall ≈ 7.9 blocks)

- **Hook:** `EntityMixin`, `@ModifyArg(method = {"move", "doCheckFallDamage"}, at = @At(value = "INVOKE", target =
  "Lnet/minecraft/world/entity/Entity;checkFallDamage(DZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V"), index = 0)`:
  `ya * gravityScale`. Safe fall becomes 7.91 blocks and damage `floor(0.379 · (h − 7.91))`. Mace, anvil, boat and
  farmland consistency come for free.
- **Pitfalls:**
  - `fallDistance` becomes "Earth-equivalent"; document it.
  - Ship passengers get the ship's fall damage through `propagateFallToPassengers` unless the ship resets or overrides it.
  - **Alternative:** transient attribute modifiers (§4.4) on `SAFE_FALL_DISTANCE` and `FALL_DAMAGE_MULTIPLIER`. They are
    living-only, need `ENTITY_LOAD`, change-level and respawn bookkeeping, and are not persisted.

### Rule D: unbreathable atmosphere

- **Hooks:** one `LivingEntityMixin` with:
  - `@ModifyReturnValue` on `increaseAirSupply(I)I` (no refill when anoxic);
  - `@Inject(method = "baseTick", at = @At("TAIL"))`: server-side hypoxia, −1 air per tick, and at ≤ −20 reset to 0 and
    deal 2.0 `redplanet:hypoxia` damage.
- **Damage:** `damageSources().source(HYPOXIA_KEY)`; data JSON and tags as in §6.
- **Protection:** creative/spectator; the `redplanet:does_not_breathe` tag; a suit with O2; `breathable` at the eye
  position (habitat layer); a pressurized vehicle.
- **HUD:** vanilla bubbles already show when air < max; there is no pop animation out of water. Replace the bar through
  Fabric `HudElementRegistry.replaceElement(VanillaHudElements.AIR_BAR, ...)` for the suit O2 gauge.
- **Pitfalls:**
  - Don't fake "underwater": that brings in Water Breathing and Conduit protection, drown messages, and mount dismounts.
  - Passengers run `baseTick`, so the ship must protect them.
  - Respiration (`OXYGEN_BONUS`) should not extend hypoxia unless that is intended.

### Rule E: no combustion without oxygen

- **Hooks:**
  - (1) `BlockStateBase.canSurvive` `@ModifyReturnValue` plus tag `redplanet:requires_oxygen`.
  - (2) `Level.setBlock(BlockPos, BlockState, int, int)` `@ModifyVariable` HEAD on the `BlockState` arg, forcing `LIT=false`
    for tag `redplanet:extinguished_without_oxygen`.
  - (3) `Entity.setRemainingFireTicks` `@ModifyVariable` HEAD, `min(v, 0)` when anoxic.
  - (4) `AbstractFurnaceBlockEntity.getBurnDuration` `@ModifyReturnValue` 0, plus `serverTick` HEAD to zero
    `litTimeRemaining`.
  - Optional: `LavaFluid.randomTick` cancel; `UseBlockCallback` for flint & steel UX.
- **Pitfalls:**
  - The `canSurvive` hook does not pop torches or lanterns that already exist. When a habitat depressurises, scan its
    volume and extinguish torches, candles and campfires.
  - The client also runs (1) and (2) for prediction, so sync habitat data or accept a brief flicker.
  - Keep redstone torches allowed.
  - TNT, fireworks and gunpowder keep working (they carry their own oxidizer). Decide whether flint & steel may still
    prime TNT (`TntBlock.useItemOn`).
  - `increased_fire_burnout` doesn't help.

### Rule F: liquid water unstable, ice sublimates, habitats allow water

- **Data:** `"minecraft:gameplay/water_evaporates": true` in the Mars dimension type. It covers buckets (client and
  server), dispensers, ice that sublimates instead of melting (`IceBlock.melt` and `playerDestroy`), wet sponges and
  dripstone mud. Cold biomes (temperature < 0.15) make any exposed water freeze through `tickPrecipitation`.
- **Hook:** the habitat positional layer on `EnvironmentAttributeSystem$Builder.addDefaultLayers(Level)` TAIL (§13), which
  sets `water_evaporates=false`, `breathable=true` and `combustion=true` inside habitats. The same layer serves D and E.
- **Optional:** `FlowingFluid.spreadTo` HEAD cancel (with a fizz) for water flowing into an evaporating position, which
  covers leaks out of habitats. Temperature-based sublimation needs our own random tick, because vanilla ice only melts at
  block light ≥ 11. Decide about cauldrons.
- **Pitfalls:**
  - Water placed by worldgen or structures is not affected (set `default_fluid` air and no aquifers).
  - Without client sync of habitats, the client mispredicts bucket evaporation inside a habitat.

### Also verified (no action needed)

- Mars natural spawns are off by default.
- Overworld-only custom spawners exclude phantoms, patrols, cats, sieges and wandering traders.
- Passengers of a server-controlled fast ship are never kicked or rubber-banded.
- There are no velocity caps (`LpVec3`).
- Entities above the build limit keep ticking and rendering.

---

## UNVERIFIED (to be confirmed by gametests or a runtime check)

1. Runtime behaviour of every proposed mixin. Targets, descriptors and ordinals are verified; the mixins have not been
   compiled or run.
2. XP-orb pickup behaviour with near-1 drag.
3. `ALC_EXT_EFX` availability in the macOS arm64 OpenAL runtime.
4. Whether `ServerEntityEvents.ENTITY_LOAD` fires for a respawned `ServerPlayer`. This only matters for the attribute
   alternative.
5. Fabric registry-sync requirements for modded `ENVIRONMENT_ATTRIBUTE` entries.
6. Exact client and server flicker when habitat data is not synced. Expected to be cosmetic only.
