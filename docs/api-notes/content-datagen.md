# Content registration and data generation: Minecraft 26.3 + Fabric API 0.161.0+26.3

Researched 2026-10-04 by reading the decompiled 26.3 sources, the vanilla client jar data and assets, the Fabric API
0.161.0+26.3 sources and the Loom 1.18 sources. Every claim below cites a file and approximate line number. Nothing here
was compiled or run, except where it says so. Items marked **UNVERIFIED** are inferences that still need a build or a
gametest to confirm.

## Source legend

| Prefix | Absolute path |
|---|---|
| `MC/` | `/home/user/mcsrc/common/net/minecraft/` (decompiled common/server code) |
| `MCC/` | `/home/user/mcsrc/client/net/minecraft/client/` (decompiled client-only code) |
| `B3D/` | `/home/user/mcsrc/client/com/mojang/blaze3d/` |
| `DATA/` | `/home/user/mcsrc/jar-client/data/minecraft/` (vanilla data pack inside the client jar) |
| `ASSETS/` | `/home/user/mcsrc/jar-client/assets/minecraft/` (vanilla resource pack inside the client jar) |
| `FAPI/` | `/home/user/mcsrc/fabric-api/src/net/fabricmc/fabric/` |
| `CT/` | `/home/user/mcsrc/fabric-api/src/<module>.classtweaker` (Fabric access wideners) |
| `LOOM/` | `/home/user/mcsrc/loom/src/net/fabricmc/loom/` |

The decompiled sources contain comments like `Access widened by fabric-transitive-access-wideners-v1 to accessible`.
They mark members that Fabric widens. Entries listed as `transitive-accessible` in the classtweaker files are visible to
mods at compile time.

---

## 0. What changed since 1.21.x (read this first)

1. **Block and item ids must be set before construction.** `BlockBehaviour(Properties)` calls
   `properties.effectiveDrops()` and `effectiveDescriptionId()`, which throw `"Block id not set"`
   (`MC/world/level/block/state/BlockBehaviour.java:104-107, 1181-1183, 1315-1317`). `Item(Properties)` calls
   `properties.itemIdOrThrow()` (`MC/world/item/Item.java:147-152`). Always call `props.setId(key)` first.
2. **Blocks no longer have `codec()`.** `FallingBlock`, `BaseEntityBlock` and the rest have no `MapCodec` method
   (a grep for `codec()` in `MC/world/level/block` finds nothing).
3. **`Items.registerBlock`/`registerItem` are private.** Only `Blocks.register(ResourceKey, Function, Properties)` is
   widened for mods (`CT/fabric-transitive-access-wideners-v1.classtweaker:121-122`). Write your own helper.
4. **A BlockItem needs `useBlockDescriptionPrefix()`.** Vanilla adds it in `Items.registerBlock`
   (`MC/world/item/Items.java:2907-2911`). Without it the item name key is `item.redplanet.x`, not `block.redplanet.x`.
5. **Default item components are bound late,** on data reload or registry sync, not at construction
   (`MC/core/component/DataComponentInitializers.java`, applied from `MC/server/ReloadableServerResources.java:87` and
   `MCC/multiplayer/RegistryDataCollector.java:144`). Don't read `item.components()` during mod init.
6. **Save/load uses `ValueInput`/`ValueOutput`** (`MC/world/level/storage/ValueInput.java`, `ValueOutput.java`), not
   `CompoundTag` plus a `HolderLookup`.
7. **The GUI is "extract render state" based.** `GuiGraphics` is now `GuiGraphicsExtractor`, and `render`/`renderBg` are
   now `extractRenderState`/`extractBackground`/`extractLabels` (`MCC/gui/screens/inventory/AbstractContainerScreen.java:88-193`).
   **Text colours must be ARGB.** Text with alpha 0 is dropped (`MCC/gui/GuiGraphicsExtractor.java:266`).
8. **Recipes are a registry.** Data generation goes through `BootstrapContext<Recipe<?>>`.
   `FabricRecipeProvider.createRecipeProvider(HolderLookup.Provider, BootstrapContext<Recipe<?>>, BootstrapContext<Advancement>)`
   (`FAPI/api/datagen/v1/provider/FabricRecipeProvider.java:79`). `RecipeSerializer` is now a **record**
   `(MapCodec, StreamCodec)` (`MC/world/item/crafting/RecipeSerializer.java`).
9. **The loot table JSON changed a lot.** It uses `"condition"` (one condition, an inline object or a **predicate
   reference** such as `"minecraft:tool/can_silk_touch"`) and `"modifier"` (one or a list). Condition and function
   objects use the key `"type"` (`MC/world/level/storage/loot/LootPool.java:34-40`,
   `DATA/loot_table/blocks/iron_ore.json`).
10. **The advancement `"player"` condition is a single loot condition holder.** It is either an inline
    `{"type":"minecraft:entity_properties",...}` or a predicate id. Entity sub-predicates are keyed `"minecraft:location"`
    (`DATA/advancement/nether/find_fortress.json`). The trigger package is now `net.minecraft.advancements.triggers`.
11. **Chunk render layers are automatic.** A quad's layer (solid/cutout/translucent) comes from its sprite's pixel alpha.
    No render-layer map is needed (`MCC/resources/model/geometry/BakedQuad.java:70-90`, `MCC/renderer/chunk/ChunkSectionLayer.java:28-34`).
12. **Item model definitions `assets/<ns>/items/<id>.json` are required** for every item, block items included.
    `models/` only holds geometry.
13. **Armor:** `ArmorMaterial` is a record ending in `ResourceKey<EquipmentAsset>`. The client reads
    `assets/<ns>/equipment/<id>.json`, which has a new `humanoid_baby` layer (64x64 texture).
14. **Fabric renames:** `FabricDataOutput` became `FabricPackOutput`, `ExtendedScreenHandlerType` became
    `ExtendedMenuType`, `FabricItemGroup`/`ItemGroupEvents` became `FabricCreativeModeTab`/`CreativeModeTabEvents`,
    `FabricBlockLootTableProvider` became `FabricBlockLootSubProvider`, and `FabricTagProvider` became `FabricTagsProvider`.
    **`FabricModelProvider` and `FabricSoundsProvider` live in the client package**, so datagen must run on the client.
15. **`PushReaction` values changed:** `PUSH_PULL, PUSH, POPPED, IMMOVEABLE, IGNORE_ENTITY` (`MC/world/level/material/PushReaction.java`).

---

## 1. Blocks and items

### 1.1 Vanilla registration helpers

`MC/world/level/block/Blocks.java:5648-5669`:

```java
private static Block register(final BlockItemId id, final Function<BlockBehaviour.Properties, Block> factory, final BlockBehaviour.Properties properties) {
    return register(id.block(), factory, properties);
}
/** Access widened by fabric-transitive-access-wideners-v1 to accessible */
public static Block register(final ResourceKey<Block> id, final Function<BlockBehaviour.Properties, Block> factory, final BlockBehaviour.Properties properties) {
    Block block = factory.apply(properties.setId(id));
    return Registry.register(BuiltInRegistries.BLOCK, id, block);
}
public static Block register(final ResourceKey<Block> id, final BlockBehaviour.Properties properties) {
    return register(id, Block::new, properties);
}
```

`MC/world/item/Items.java:2903-2944`, where each block item and item is registered:

```java
private static Item registerBlock(final BlockItemId id, final Block block, final BiFunction<Block, Item.Properties, Item> itemFactory, final Item.Properties properties) {
    return registerItem(id.item(), p -> itemFactory.apply(block, p), properties.useBlockDescriptionPrefix().requiredFeatures(block.requiredFeatures()));
}
private static Item registerItem(final ResourceKey<Item> id, final Function<Item.Properties, Item> itemFactory, final Item.Properties properties) {
    Item item = itemFactory.apply(properties.setId(id));
    if (item instanceof BlockItem blockItem) {
        blockItem.registerBlocks(Item.BY_BLOCK, item);
    }
    return Registry.register(BuiltInRegistries.ITEM, id, item);
}
```

- These helpers are **private** (no widening comment). Mods copy the pattern.
- You don't need to fill `Item.BY_BLOCK` yourself for mod BlockItems. Fabric's
  `FAPI/impl/registry/sync/trackers/vanilla/BlockItemTracker.java:35-39` calls `registerBlocks(Item.BY_BLOCK, ...)` from a
  `RegistryEntryAddedCallback`, so `Block.asItem()` works (`MC/world/level/block/Block.java:558-563`).
- New id records: `MC/references/BlockItemId.java`, `record BlockItemId(ResourceKey<Block> block, ResourceKey<Item> item)`
  with `create(Identifier blockId, Identifier itemId)`. Tag appenders accept them (§9).
- Registry helpers, `MC/core/Registry.java:106-125`:
  `register(Registry<? super T>, String name, T)` (parses `ns:path`), `register(Registry<V>, Identifier, T)`,
  `register(Registry<V>, ResourceKey<V>, T)` and `registerForHolder(Registry<R>, ResourceKey<R>|Identifier, T) -> Holder.Reference<T>`.
- Vanilla puts this in the block and item class constructors (logged only in a dev environment): class names should end
  in `Block`/`Item`, or you get `"Block classes should end with Block..."` (`MC/world/level/block/Block.java:238-243`,
  `MC/world/item/Item.java:155-160`). Avoid anonymous `Block`/`Item` subclasses.

### 1.2 `BlockBehaviour.Properties` (`MC/world/level/block/state/BlockBehaviour.java:991-1318`)

Factories: `of()`, `ofFullCopy(BlockBehaviour)` (1041) and `@Deprecated ofLegacyCopy(BlockBehaviour)`.

| Method (all return `Properties`) | Line |
|---|---|
| `mapColor(MapColor)` / `mapColor(DyeColor)` / `mapColor(Function<BlockState,MapColor>)` | 1087-1100 |
| `noCollision()` (also clears occlusion), `noOcclusion()` | 1102-1111 |
| `friction(float)`, `speedFactor(float)`, `jumpFactor(float)`, `bounceRestitution(float)`, `fallDistanceReduction(float)` | 1113-1136 |
| `sound(SoundType)` | 1138 |
| `lightLevel(ToIntFunction<BlockState>)` | 1143 |
| `strength(float destroyTime, float explosionResistance)`, `strength(float)`, `instabreak()`, `destroyTime(float)`, `explosionResistance(float)` | 1148-1159, 1251-1259 |
| `randomTicks()` | 1161 |
| `dynamicShape()` | 1166 |
| `noLootTable()`, `overrideLootTable(Optional<ResourceKey<LootTable>>)` | 1171-1179 |
| `ignitedByLava()`, `liquid()`, `forceSolidOn()`, `@Deprecated forceSolidOff()` | 1186-1205 |
| `pushReaction(PushReaction)` | 1207 |
| `air()`, `isValidSpawn(...)`, `isRedstoneConductor(StatePredicate)`, `isSuffocating(StatePredicate)` | 1212-1229 |
| `isViewBlocking(StateArgumentPredicate<AABB>)` (**new 4-argument predicate**: `(state, level, pos, aabb)`) | 1231 |
| `postProcess(...)`, `emissiveRendering(Predicate<BlockState>)` | 1236-1244 |
| `requiresCorrectToolForDrops()` | 1246 |
| `offsetType(OffsetType)`, `noTerrainParticles()`, `requiredFeatures(FeatureFlag...)`, `instrument(NoteBlockInstrument)`, `replaceable()` | 1261-1303 |
| `setId(ResourceKey<Block>)` **required** | 1305 |
| `overrideDescription(String)` | 1310 |

The default loot table key is `<ns>:blocks/<path>` and the default description id is `block.<ns>.<path>` (`:1005-1008`).

Vanilla examples to copy (`MC/world/level/block/Blocks.java`):

```java
// :289  sand
public static final Block SAND = register(BlockItemIds.SAND, p -> new SandBlock(new ColorRGBA(14406560), p),
    BlockBehaviour.Properties.of().mapColor(MapColor.SAND).instrument(NoteBlockInstrument.SNARE).strength(0.5F).sound(SoundType.SAND));
// :334  ore
public static final Block IRON_ORE = register(BlockItemIds.IRON_ORE, p -> new DropExperienceBlock(ConstantInt.of(0), p),
    BlockBehaviour.Properties.of().mapColor(MapColor.STONE).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(3.0F, 3.0F));
// :2031 snow layers
public static final Block SNOW = register(BlockItemIds.SNOW, SnowLayerBlock::new, BlockBehaviour.Properties.of()
    .mapColor(MapColor.SNOW).replaceable().forceSolidOff().randomTicks().strength(0.1F).requiresCorrectToolForDrops()
    .sound(SoundType.SNOW).isViewBlocking((state, level, pos, aabb) -> state.getValue(SnowLayerBlock.LAYERS) >= 8)
    .pushReaction(PushReaction.POPPED));
// :2045 ice
public static final Block ICE = register(BlockItemIds.ICE, IceBlock::new, BlockBehaviour.Properties.of()
    .mapColor(MapColor.ICE).friction(0.98F).randomTicks().strength(0.5F).sound(SoundType.GLASS).noOcclusion()
    .isValidSpawn((state, blockGetter, blockPos, entityType) -> entityType == EntityTypes.POLAR_BEAR).isRedstoneConductor(Blocks::never));
// :2128 basalt
public static final Block BASALT = register(BlockItemIds.BASALT, RotatedPillarBlock::new, BlockBehaviour.Properties.of()
    .mapColor(MapColor.COLOR_BLACK).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.25F, 4.2F) /* .sound(SoundType.BASALT) */);
// :1349 furnace (lit light level)
... .requiresCorrectToolForDrops().strength(3.5F).lightLevel(litBlockEmission(13))
```

`Blocks.litBlockEmission(int)`, `Blocks.never`/`always`, `buttonProperties()` and `flowerPotProperties()` are
transitive-accessible (`CT/fabric-transitive-access-wideners-v1.classtweaker:106-118`).

### 1.3 `Item.Properties` (`MC/world/item/Item.java:390-718`)

| Method | Line | Notes |
|---|---|---|
| `setId(ResourceKey<Item>)` | 665 | required |
| `useBlockDescriptionPrefix()` / `useItemDescriptionPrefix()` / `overrideDescription(String)` | 670-683 | BlockItems need `useBlockDescriptionPrefix()` |
| `stacksTo(int)` | 420 | sets `MAX_STACK_SIZE` |
| `durability(int)` | 424 | sets `MAX_DAMAGE`, `MAX_STACK_SIZE=1`, `DAMAGE=0` |
| `rarity(Rarity)`, `fireResistant()`, `enchantable(int)`, `repairable(Item)`, `repairable(TagKey<Item>)` | 440-466 | |
| `equippable(EquipmentSlot)`, `equippableUnswappable(EquipmentSlot)` | 469-475 | |
| `food(FoodProperties[, Consumable])`, `useCooldown(float)`, `usingConvertsTo(Item)`, `craftRemainder(Item)` | 400-438 | |
| `tool/pickaxe/axe/hoe/shovel/sword/spear(ToolMaterial, ...)` | 477-571 | |
| `humanoidArmor(ArmorMaterial, ArmorType)` | 574 | see §5 |
| `<T> component(DataComponentType<T>, T)` | 700 | any component, custom ones included |
| `<T> delayedComponent(DataComponentType<T>, SingleComponentInitializer<T>)`, `delayedHolderComponent(...)` | 705-713 | for registry-dependent values |
| `attributes(ItemAttributeModifiers)` | 715 | |
| `cookingFuel(ResourceKey<ContextIntProvider>)`, `compostable(...)` | 643, 719 | **fuel is a component now** (`COOKING_FUEL`) |

Fabric adds `modelId(Identifier)`, `equipmentSlot(...)`, `customDamage(...)`, `modifyComponent(...)` and
`itemId()` through interface injection (`FAPI/api/item/v1/FabricItem.java:174-235`).

Overridable `Item` methods: `use(Level, Player, InteractionHand)` (203), `useOn(UseOnContext)` (192),
`inventoryTick(ItemStack, ServerLevel, Entity, @Nullable EquipmentSlot)` (303, **server-only**),
`isBarVisible/getBarWidth/getBarColor(ItemStack)` (233-245) and the deprecated but still called
`appendHoverText(ItemStack, Item.TooltipContext, TooltipDisplay, Consumer<Component>, TooltipFlag)` (337-341).

### 1.4 BlockItem

`public BlockItem(final Block block, final Item.Properties properties)` (`MC/world/item/BlockItem.java:35`). It does **not**
add the block description prefix by itself, so pass `props.useBlockDescriptionPrefix()`.

### 1.5 Falling blocks (sand-like dust)

`MC/world/level/block/FallingBlock.java:19-79`: `public abstract class FallingBlock extends Block implements Fallable` with
an abstract `int getDustColor(BlockState, BlockGetter, BlockPos)`. Ready-made concrete classes:

- `ColoredFallingBlock(ColorRGBA dustColor, Properties)` (`MC/world/level/block/ColoredFallingBlock.java:12`). Gravel uses it.
- `SandBlock(ColorRGBA dustColor, Properties)` (`MC/world/level/block/SandBlock.java:12`). It adds desert ambient sounds.
- `ColorRGBA` is `record ColorRGBA(int rgba)` (`MC/util/ColorRGBA.java`). Vanilla passes 0xRRGGBB, for example red sand
  is `new ColorRGBA(11098145)`.
- Hooks: `falling(FallingBlockEntity)`, `getDelayAfterPlace()` and `FallingBlock.isFree(BlockState)`.

### 1.6 Layered blocks (dust layers, like snow)

`MC/world/level/block/SnowLayerBlock.java` is a public class with a public constructor (Fabric widens it, `:24-35`).
`LAYERS = BlockStateProperties.LAYERS` (`IntegerProperty` 1..8, `:29`). It handles shapes, `canSurvive` (with the tags
`CANNOT_SUPPORT_SNOW_LAYER`/`SUPPORT_OVERRIDE_SNOW_LAYER`), stacking via `canBeReplaced`/`getStateForPlacement`, and a
`randomTick` that melts at block light > 11 (`:105-111`). Extend it and override `randomTick` with a no-op. The melting
only runs if the block has `randomTicks()`. Item models and loot need explicit handling (§6, §9).

### 1.7 Random ticks and sublimation

`protected void randomTick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random)`
(`BlockBehaviour.java:337`) is called only when `isRandomlyTicking(state)` is true. That defaults to the
`randomTicks()` property (`:399-401`). Vanilla ice is a model for environment-dependent removal
(`MC/world/level/block/IceBlock.java:53-68`):

```java
protected void melt(final BlockState state, final Level level, final BlockPos pos) {
    if (level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos)) {
        level.removeBlock(pos, false);
    } else { level.setBlockAndUpdate(pos, meltsInto()); ... }
}
```

Use `level.removeBlock(pos, false)` plus `level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(state))`, as
`SnowLayerBlock.java:107-110` does. Particles: `ServerLevel.sendParticles(T particle, double x, y, z, int count, double xDist, yDist, zDist, double speed)`
(`MC/server/level/ServerLevel.java:1314`).

### 1.8 Other common signatures

- `useWithoutItem(BlockState, Level, BlockPos, Player, BlockHitResult) -> InteractionResult` (`BlockBehaviour.java:193`).
- `useItemOn(ItemStack, BlockState, Level, BlockPos, Player, InteractionHand, BlockHitResult)` (`:197`) defaults to `TRY_WITH_EMPTY_HAND`.
- `tick(...)` (`:340`), `onPlace(...)` (`:161`), `affectNeighborsAfterRemoval(BlockState, ServerLevel, BlockPos, boolean)` (`:164`).
- `updateShape(BlockState, LevelReader, ScheduledTickAccess, BlockPos, Direction, BlockPos, BlockState, RandomSource)` (`:139`).
- `getStateForPlacement(BlockPlaceContext)`, `createBlockStateDefinition(StateDefinition.Builder<Block,BlockState>)` and
  `registerDefaultState(...)` (`MC/world/level/block/Block.java:451, 532, 539`).
- `getRenderShape` defaults to `RenderShape.MODEL` (`BlockBehaviour.java:217`), so BaseEntityBlocks render their JSON model by default.
- `Level.isClientSide()` is a **method** (`MC/world/level/Level.java:164`).
- `InteractionResult.SUCCESS / SUCCESS_SERVER / CONSUME / FAIL / PASS` (`MC/world/InteractionResult.java:11-15`).
- `HorizontalDirectionalBlock.FACING = BlockStateProperties.HORIZONTAL_FACING` ("facing") and `BlockStateProperties.LIT`
  (`MC/world/level/block/state/properties/BlockStateProperties.java:30, 57`).
- `DropExperienceBlock(IntProvider xpRange, Properties)` (`MC/world/level/block/DropExperienceBlock.java:13`), with
  `UniformInt.of(a,b)` / `ConstantInt.of(n)`.

### 1.9 Vanilla 26.3 already has sulfur and cinnabar

`minecraft:sulfur`, `potent_sulfur`, `sulfur_spike` and `cinnabar` exist, with `SoundType.SULFUR` and
`SoundType.CINNABAR` (`MC/world/level/block/Blocks.java:4858-4890, 5144`, `MC/world/item/Items.java:97-123`).
Name the mod's ore `redplanet:sulfur_ore` (or similar) to avoid confusion. You can reuse `SoundType.SULFUR`.

---

## 2. Block entities

### 2.1 `BlockEntityType`

The vanilla constructor is **public** in 26.3 (`MC/world/level/block/entity/BlockEntityType.java:18`):

```java
public BlockEntityType(final BlockEntityType.BlockEntitySupplier<? extends T> factory, final Set<Block> validBlocks)
@FunctionalInterface public interface BlockEntitySupplier<T extends BlockEntity> { T create(BlockPos worldPosition, BlockState blockState); }
```

Vanilla registers with `Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, key, new BlockEntityType<>(factory, Set.of(validBlocks)))`
(`MC/world/level/block/entity/BlockEntityTypes.java`, `register(...)`). It warns when there are no valid blocks.

Fabric builder (`FAPI/api/object/builder/v1/block/entity/FabricBlockEntityTypeBuilder.java:48-99`):

```java
public static <T extends BlockEntity> FabricBlockEntityTypeBuilder<T> create(Factory<? extends T> factory, Block... blocks)
public FabricBlockEntityTypeBuilder<T> addBlock(Block) / addBlocks(Block...) / addBlocks(Collection<? extends Block>)
public FabricBlockEntityTypeBuilder<T> canPotentiallyExecuteCommands(boolean)
public BlockEntityType<T> build()            // build(Type<?>) is deprecated
```

### 2.2 Ticking

- `EntityBlock.getTicker`: `default <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState blockState, BlockEntityType<T> type)`
  (`MC/world/level/block/EntityBlock.java:16`).
- `BaseEntityBlock.createTickerHelper`: `protected static <E extends BlockEntity, A extends BlockEntity> @Nullable BlockEntityTicker<A> createTickerHelper(BlockEntityType<A> actual, BlockEntityType<E> expected, @Nullable BlockEntityTicker<? super E> ticker)`
  (`MC/world/level/block/BaseEntityBlock.java:30-34`).
- `BlockEntityTicker<T>`: `void tick(Level, BlockPos, BlockState, T)` (`MC/world/level/block/entity/BlockEntityTicker.java`).
- Vanilla's server-only pattern (`MC/world/level/block/AbstractFurnaceBlock.java:80-86`):
  ```java
  return level instanceof ServerLevel serverLevel
      ? createTickerHelper(actualType, expectedType, (innerLevel, pos, state, entity) -> AbstractFurnaceBlockEntity.serverTick(serverLevel, pos, state, entity))
      : null;
  ```
- `BaseEntityBlock` gives `getMenuProvider` (it returns the BE if it is a `MenuProvider`, `:26-28`) and `triggerEvent`.

### 2.3 Save and load (`MC/world/level/block/entity/BlockEntity.java:97-157`)

```java
protected void loadAdditional(final ValueInput input)
protected void saveAdditional(final ValueOutput output)
public final CompoundTag saveWithoutMetadata(HolderLookup.Provider) / saveCustomOnly(HolderLookup.Provider) / saveWithFullMetadata(...)
```

`ValueInput` (`MC/world/level/storage/ValueInput.java`): `<T> Optional<T> read(String, Codec<T>)`, `getIntOr(String,int)`,
`getLongOr`, `getFloatOr`, `getDoubleOr`, `getBooleanOr`, `getStringOr`, `Optional<Integer> getInt(String)`,
`child(String)`, `childOrEmpty(String)`, `list(String, Codec<T>)`, `childrenList(String)`.
`ValueOutput` (`ValueOutput.java`): `<T> store(String, Codec<T>, T)`, `storeNullable(...)`, `putInt/putLong/putFloat/putDouble/putBoolean/putString/putIntArray`,
`child(String)`, `list(String, Codec<T>)`, `childrenList(String)` and `discard(String)`.
Item lists use `ContainerHelper.saveAllItems(ValueOutput, NonNullList<ItemStack>)` and `loadAllItems(ValueInput, NonNullList<ItemStack>)`
(`MC/world/ContainerHelper.java:21-40`). Furnace example: `MC/world/level/block/entity/AbstractFurnaceBlockEntity.java:122-146`.

### 2.4 Client sync

```java
public @Nullable Packet<ClientGamePacketListener> getUpdatePacket()   // BlockEntity.java:215, default null
public CompoundTag getUpdateTag(final HolderLookup.Provider registries) // BlockEntity.java:219, default empty
```

Vanilla pattern (`MC/world/level/block/entity/CampfireBlockEntity.java:154-165`, `BeaconBlockEntity.java:306`):

```java
@Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
@Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return this.saveCustomOnly(registries); }
```

The client applies it with `blockEntity.loadWithComponents(TagValueInput.create(...))`
(`MCC/multiplayer/ClientPacketListener.java:1442-1449`), so `loadAdditional` runs on the client. Push updates with
`level.sendBlockUpdated(pos, oldState, newState, Block.UPDATE_CLIENTS)` (`MC/world/level/Level.java:300`,
`Block.UPDATE_CLIENTS = 2`, `MC/world/level/block/Block.java:92`). **You don't need this for GUI values.** Use `ContainerData` for those (§3.3).

### 2.5 Container block entities

`BaseContainerBlockEntity extends BlockEntity implements Container, MenuProvider, Nameable`
(`MC/world/level/block/entity/BaseContainerBlockEntity.java`). Abstract members: `Component getDefaultName()` (72),
`NonNullList<ItemStack> getItems()`, `void setItems(NonNullList<ItemStack>)`, `AbstractContainerMenu createMenu(int, Inventory)` (152),
plus `int getContainerSize()` from `Container`. It handles the lock, the custom name and the item components.
**Contents drop automatically when the block is broken**: `BlockEntity.preRemoveSideEffects` calls
`Containers.dropContents` for any `Container` BE (`BlockEntity.java:235-239`). The block should call
`Containers.updateNeighboursAfterDestroy(state, level, pos)` from `affectNeighborsAfterRemoval`
(`AbstractFurnaceBlock.java`, `MC/world/Containers.java:49`).

---

## 3. Menus and screens

### 3.1 `MenuType` and Fabric `ExtendedMenuType`

Vanilla: `public MenuType(MenuType.MenuSupplier<T> constructor, FeatureFlagSet requiredFeatures)`, which Fabric widens
(`MC/world/inventory/MenuType.java:56`, `CT/fabric-transitive-access-wideners-v1.classtweaker:7-8`).
`MenuSupplier<T>`: `T create(int containerId, Inventory inventory)`.

Fabric (`FAPI/api/menu/v1/ExtendedMenuType.java:103-170`):

```java
public class ExtendedMenuType<T extends AbstractContainerMenu, D> extends MenuType<T> {
    public ExtendedMenuType(ExtendedFactory<T, D> factory, StreamCodec<? super RegistryFriendlyByteBuf, D> streamCodec)
    public T create(int containerId, Inventory inventory, D data)
    public StreamCodec<? super RegistryFriendlyByteBuf, D> getStreamCodec()
    @FunctionalInterface public interface ExtendedFactory<T extends AbstractContainerMenu, D> { T create(int containerId, Inventory inventory, D data); }
}
public interface ExtendedMenuProvider<D> extends MenuProvider { D getScreenOpeningData(ServerPlayer player); }   // ExtendedMenuProvider.java:35
public interface FabricMenuProvider { default boolean shouldCloseCurrentScreen() { return true; } }           // injected into MenuProvider
```

Javadoc example (`ExtendedMenuType.java:30-90`): `Registry.register(BuiltInRegistries.MENU, Identifier.fromNamespaceAndPath("modid","oven"), new ExtendedMenuType<>(OvenMenu::new, OvenData.STREAM_CODEC))`,
then `player.openMenu(ovenBlockEntity)`, which only works on `ServerPlayer`. `Player.openMenu(@Nullable MenuProvider)` returns
`OptionalInt` (`MC/world/entity/player/Player.java:791`). `BlockPos.STREAM_CODEC` is a `StreamCodec<ByteBuf, BlockPos>`
(`MC/core/BlockPos.java:37`) and works as opening data.

### 3.2 `AbstractContainerMenu` essentials (`MC/world/inventory/AbstractContainerMenu.java`)

- Constructor: `protected AbstractContainerMenu(@Nullable MenuType<?> menuType, int containerId)` (70).
- `public abstract ItemStack quickMoveStack(Player player, int slotIndex)` (312) and `public abstract boolean stillValid(Player player)` (649).
- Helpers: `addSlot(Slot)` (126), `addDataSlots(ContainerData)` (140), `addStandardInventorySlots(Container playerInv, int left, int top)` (89, the 27 inventory slots plus the hotbar 58 px below),
  `moveItemStackTo(ItemStack, int start, int end, boolean backwards)` (651), `checkContainerSize`, `checkContainerDataCount`,
  and `protected static boolean stillValid(ContainerLevelAccess access, Player player, Block block)` (96, distance check of 4 blocks).
- `ContainerLevelAccess.create(Level, BlockPos)` (`MC/world/inventory/ContainerLevelAccess.java:17`).
- `Slot(Container, int slot, int x, int y)`, with overridable `mayPlace(ItemStack)`, `mayPickup(Player)` and `getMaxStackSize()` (`MC/world/inventory/Slot.java:17-89`).
- Full `quickMoveStack` reference: `MC/world/inventory/AbstractFurnaceMenu.java:88-134` (`quickMoveStack`).

### 3.3 `ContainerData` progress sync is 16-bit

`ContainerData { int get(int); void set(int,int); int getCount(); }` and `SimpleContainerData(int count)`
(`MC/world/inventory/ContainerData.java`, `SimpleContainerData.java`). On the wire the value is a **short**:
`this.value = input.readShort();` (`MC/network/protocol/game/ClientboundContainerSetDataPacket.java:25`). Values outside
-32768..32767 wrap around. Split large energy or oxygen numbers into two slots, or scale them.

### 3.4 Screen registration (client)

`MCC/gui/screens/MenuScreens.java:59-67`, which Fabric widens:

```java
public static <M extends AbstractContainerMenu, U extends Screen & MenuAccess<M>> void register(MenuType<? extends M> type, MenuScreens.ScreenConstructor<M, U> factory)
public interface ScreenConstructor<T extends AbstractContainerMenu, U extends Screen & MenuAccess<T>> { U create(T menu, Inventory inventory, Component title); }
```

Call `MenuScreens.register(RPMenus.MOXIE, MoxieScreen::new)` in the `ClientModInitializer`. Registering the same type twice throws.

### 3.5 `AbstractContainerScreen` in 26.3 (`MCC/gui/screens/inventory/AbstractContainerScreen.java`)

There is **no `renderBg`/`render`**. The frame pipeline is `Screen.extractRenderStateWithTooltipAndSubtitles`
(`MCC/gui/screens/Screen.java:112-118`), which calls `extractBackground(...)` and then `extractRenderState(...)`.

```java
public AbstractContainerScreen(T menu, Inventory inventory, Component title)                       // 176x166, :57
public AbstractContainerScreen(T menu, Inventory inventory, Component title, int imageWidth, int imageHeight) // :61
protected void init()                                                                              // :76, sets leftPos/topPos
public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a)     // :88, contents + carried item + tooltip
public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a)        // :94, translates the pose to (leftPos, topPos) and calls extractLabels and extractSlots
protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym)                         // :188, coordinates relative to the GUI origin
protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY)               // :164
public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a)      // inherited from Screen :425, override it to draw the panel
protected boolean isHovering(int left, int top, int w, int h, double xm, double ym)                // :451
fields: imageWidth, imageHeight, leftPos, topPos, titleLabelX/Y, inventoryLabelX/Y, menu, font
```

Vanilla background drawing (`MCC/gui/screens/inventory/HopperScreen.java:19-24`):

```java
@Override
public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
    super.extractBackground(graphics, mouseX, mouseY, a);
    int xo = (this.width - this.imageWidth) / 2;
    int yo = (this.height - this.imageHeight) / 2;
    graphics.blit(RenderPipelines.GUI_TEXTURED, HOPPER_LOCATION, xo, yo, 0.0F, 0.0F, this.imageWidth, this.imageHeight, 256, 256);
}
```

Progress sprite (`MCC/gui/screens/inventory/AbstractFurnaceScreen.java:48-63`):
`graphics.blitSprite(RenderPipelines.GUI_TEXTURED, this.burnProgressSprite, 24, 16, 0, 0, xo + 79, yo + 34, burnProgressWidth, 16);`
That is the overload `blitSprite(RenderPipeline, Identifier, int spriteW, int spriteH, int texX, int texY, int x, int y, int w, int h)`
(`MCC/gui/GuiGraphicsExtractor.java:443`).

`GuiGraphicsExtractor` API (`MCC/gui/GuiGraphicsExtractor.java`): `fill(x0,y0,x1,y1,argb)` (190), `fillGradient` (210),
`outline` (218), `text(Font, Component|String|FormattedCharSequence, x, y, argb[, dropShadow])` (251-277), `centeredText` (279),
`blit(RenderPipeline, Identifier texture, x, y, u, v, w, h, texW, texH[, color])` (315-390), `blitSprite(...)` (414-490),
`item(ItemStack, x, y)` (876), `itemDecorations(...)` (914), `setTooltipForNextFrame(Font, Component, x, y)` (1130) and
`pose()` (a `Matrix3x2fStack`). **`text()` returns early if `ARGB.alpha(color) == 0`** (`:266`), so write `0xFF404040`, not `0x404040`.
Vanilla label colour is `-12566464` (`0xFF404040`, `AbstractContainerScreen.java:189`).

### 3.6 GUI texture locations

- Full background PNG: `Identifier.fromNamespaceAndPath("redplanet", "textures/gui/container/moxie.png")` maps to
  `assets/redplanet/textures/gui/container/moxie.png`. Vanilla uses a 256x256 canvas (`HopperScreen.java:11`).
- Sprites (gui atlas): `ASSETS/atlases/gui.json` uses directory source `gui/sprites` with no prefix, plus `mob_effect` with
  prefix `mob_effect/`. So `assets/redplanet/textures/gui/sprites/container/moxie/progress.png` is
  `Identifier("redplanet", "container/moxie/progress")`. Optional nine-slice scaling lives in `<png>.mcmeta`, for example
  `ASSETS/textures/gui/sprites/recipe_book/overlay_recipe.png.mcmeta`:
  `{"gui":{"scaling":{"type":"nine_slice","width":32,"height":32,"border":4}}}`.

---

## 4. Data components

### 4.1 `DataComponentType` (`MC/core/component/DataComponentType.java:16-110`)

```java
static <T> DataComponentType.Builder<T> builder()
Builder<T> persistent(Codec<T> codec)                                              // :55, saved to disk (otherwise transient)
Builder<T> networkSynchronized(StreamCodec<? super RegistryFriendlyByteBuf, T> sc) // :60, OPTIONAL in 26.3
Builder<T> cacheEncoding()
Builder<T> ignoreSwapAnimation()                                                   // :78
DataComponentType<T> build()                                                       // :70
```

If `networkSynchronized` is omitted, `build()` derives the stream codec from the codec:
`Objects.requireNonNullElseGet(this.streamCodec, () -> ByteBufCodecs.fromCodecWithRegistries(...codec...))` (`:71-73`).
Vanilla registration (`MC/core/component/DataComponents.java:123-125, 473-475`):

```java
public static final DataComponentType<Integer> DAMAGE = register(
    "damage", b -> b.persistent(ExtraCodecs.NON_NEGATIVE_INT).ignoreSwapAnimation().networkSynchronized(ByteBufCodecs.VAR_INT));
private static <T> DataComponentType<T> register(final String id, final UnaryOperator<DataComponentType.Builder<T>> builder) {
    return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id, builder.apply(DataComponentType.builder()).build());
}
```

Component values must be immutable and implement `equals`/`hashCode`, so records work well. The type must be registered
**before** any item whose `Item.Properties.component(...)` references it.

### 4.2 Reading and writing on an `ItemStack`

From `DataComponentHolder` (`MC/core/component/DataComponentHolder.java:10-23`): `get(type)`, `getOrDefault(type, def)`,
`has(type)`. From `ItemStack` (`MC/world/item/ItemStack.java:764-787`):

```java
public <T> @Nullable T set(DataComponentType<T> type, @Nullable T value)
public <T> @Nullable T update(DataComponentType<T> type, T defaultValue, UnaryOperator<T> function)
public <T, U> @Nullable T update(DataComponentType<T> type, T defaultValue, U value, BiFunction<T, U, T> combiner)
public <T> @Nullable T remove(DataComponentType<? extends T> type)
```

### 4.3 Default item components are resolved late

`new Item(props)` only registers an initializer: `BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.add(properties.itemIdOrThrow(), componentInitializer)`
(`Item.java:149-152`). The map is built on reload (`MC/server/ReloadableServerResources.java:87`) and on client sync
(`MCC/multiplayer/RegistryDataCollector.java:144`). Don't inspect default components during init. Creative tab icons are
`Supplier`s, which keeps them safe.

### 4.4 Tooltips

- Component-driven: `public interface TooltipProvider { void addToTooltip(Item.TooltipContext context, Consumer<Component> consumer, TooltipFlag flag, DataComponentGetter components); }`
  (`MC/world/item/component/TooltipProvider.java`). Vanilla calls only a hard-coded list (`ItemStack.java:880-905`).
  To include a mod component, register it with Fabric:
  `ItemComponentTooltipProviderRegistry.addLast(DataComponentType<? extends TooltipProvider>)`, or `addFirst`/`addBefore`/`addAfter`
  (`FAPI/api/item/v1/ItemComponentTooltipProviderRegistry.java:40-140`).
- Item-driven: override `Item.appendHoverText(ItemStack, Item.TooltipContext, TooltipDisplay, Consumer<Component>, TooltipFlag)`
  (`Item.java:337-341`, deprecated but still called first in `ItemStack.addDetailsToTooltip`, `ItemStack.java:882`).
- Client event: `ItemTooltipCallback.EVENT`, `void getTooltip(ItemStack, Item.TooltipContext, TooltipFlag, List<Component>)`
  (`FAPI/api/client/item/v1/ItemTooltipCallback.java`).

### 4.5 Item bar (for the O2 level)

Override `isBarVisible(ItemStack)`, `getBarWidth(ItemStack)` (0..13, `Item.MAX_BAR_WIDTH = 13`) and
`getBarColor(ItemStack)` (`Item.java:233-245`). The GUI draws the bar with `ARGB.opaque(itemStack.getBarColor())`
(`MCC/gui/GuiGraphicsExtractor.java:928-934`), so plain `0xRRGGBB` works.

### 4.6 No "re-equip bob" when O2 changes

The first-person renderer compares stacks with `ItemStack.matchesIgnoringComponents(..., DataComponentType::ignoreSwapAnimation)`
(`MCC/player/FirstPersonHandsAndItems.java:110`). Building the O2 component with `.ignoreSwapAnimation()` avoids the
hand animation on every update. The Fabric alternative is `FabricItem.allowComponentsUpdateAnimation(Player, InteractionHand, ItemStack, ItemStack)`
(`FAPI/api/item/v1/FabricItem.java:65`).

---

## 5. Equipment and armor

### 5.1 Types

```java
// MC/world/item/equipment/ArmorMaterial.java
public record ArmorMaterial(int durability, Map<ArmorType, Integer> defense, int enchantmentValue, Holder<SoundEvent> equipSound,
                            float toughness, float knockbackResistance, TagKey<Item> repairIngredient, ResourceKey<EquipmentAsset> assetId) {
    public ItemAttributeModifiers createAttributes(final ArmorType type)   // armor, toughness and knockback; modifier id = minecraft:armor.<type>
}
// MC/world/item/equipment/ArmorType.java
HELMET(EquipmentSlot.HEAD, 11, "helmet"), CHESTPLATE(EquipmentSlot.CHEST, 16, ...), LEGGINGS(LEGS, 15), BOOTS(FEET, 13), BODY(BODY, 16)
public int getDurability(int multiplier) { return unitDurability * multiplier; }
// MC/world/item/equipment/EquipmentAssets.java
ResourceKey<? extends Registry<EquipmentAsset>> ROOT_ID = ResourceKey.createRegistryKey(Identifier.withDefaultNamespace("equipment_asset"));
static ResourceKey<EquipmentAsset> createId(String name) { return ResourceKey.create(ROOT_ID, Identifier.withDefaultNamespace(name)); }
```

Vanilla iron (`MC/world/item/equipment/ArmorMaterials.java`):
`new ArmorMaterial(15, makeDefense(2, 5, 6, 2, 5), 9, SoundEvents.ARMOR_EQUIP_IRON, 0.0F, 0.0F, ItemTags.REPAIRS_IRON_ARMOR, EquipmentAssets.IRON)`.
`ArmorMaterials.makeDefense(int boots, int legs, int chest, int helm, int body)` is transitive-accessible
(`CT/fabric-transitive-access-wideners-v1.classtweaker:131`). `SoundEvents.ARMOR_EQUIP_*` are `Holder<SoundEvent>`
(`MC/sounds/SoundEvents.java:94-96`).

### 5.2 `Item.Properties.humanoidArmor` (`MC/world/item/Item.java:574-580`)

```java
public Item.Properties humanoidArmor(final ArmorMaterial material, final ArmorType type) {
    return this.durability(type.getDurability(material.durability()))
        .attributes(material.createAttributes(type))
        .enchantable(material.enchantmentValue())
        .component(DataComponents.EQUIPPABLE, Equippable.builder(type.getSlot()).setEquipSound(material.equipSound()).setAsset(material.assetId()).build())
        .repairable(material.repairIngredient());
}
```

A later `.component(DataComponents.EQUIPPABLE, ...)` replaces the earlier one, because initializers run in order
(`Item.java:700-703`, `DataComponentInitializers.Initializer.add`). Use that to add a camera overlay.

### 5.3 `Equippable` (`MC/world/item/equipment/Equippable.java`)

The record fields `slot, equipSound, assetId, cameraOverlay, allowedEntities, dispensable, swappable, damageOnHurt, equipOnInteract, canBeSheared, shearingSound`
map to the JSON keys `slot, equip_sound, asset_id, camera_overlay, allowed_entities, dispensable, swappable, damage_on_hurt, equip_on_interact, can_be_sheared, shearing_sound`.
The builder is `Equippable.builder(EquipmentSlot)` with `setEquipSound(Holder<SoundEvent>)`, `setAsset(ResourceKey<EquipmentAsset>)`,
`setCameraOverlay(Identifier)`, `setAllowedEntities(...)`, `setDispensable/setSwappable/setDamageOnHurt/setEquipOnInteract/setCanBeSheared(boolean)`,
`setShearingSound(...)` and `build()` (`:180-260`).

### 5.4 Equipment asset JSON and textures

The file lives at `assets/<ns>/equipment/<asset path>.json` (`FileToIdConverter.json("equipment")`,
`MCC/resources/model/EquipmentAssetManager.java:18`). A missing asset resolves to `MISSING` (empty layers), so **nothing
renders and no error is logged** (`:31-33`). Verbatim `ASSETS/equipment/iron.json`:

```json
{
  "layers": {
    "horse_body": [ { "texture": "minecraft:iron" } ],
    "humanoid": [ { "texture": "minecraft:iron" } ],
    "humanoid_baby": [ { "texture": "minecraft:iron" } ],
    "humanoid_leggings": [ { "texture": "minecraft:iron" } ],
    "nautilus_body": [ { "texture": "minecraft:iron" } ]
  },
  "trim_overrides": [ { "palette": "minecraft:trim/iron_darker", "when": { "material": "minecraft:iron" } } ]
}
```

`ASSETS/equipment/turtle_scute.json` shows a helmet-only asset with just `humanoid` and `humanoid_baby`. Layer fields are
`texture`, optional `dyeable: {color_when_undyed}` and `use_player_texture` (`MCC/resources/model/EquipmentClientInfo.java:96-108`).
Texture path: `textureId.withPath(p -> "textures/entity/equipment/" + layerType + "/" + p + ".png")` (`:115-117`).
Measured vanilla sizes: `humanoid/iron.png` 64x32, `humanoid_leggings/iron.png` 64x32, and **`humanoid_baby/iron.png` 64x64**.
The layer choice is in `MCC/renderer/entity/layers/HumanoidArmorLayer.java:63-71`: `HUMANOID_BABY` for babies (except armor stands),
`HUMANOID_LEGGINGS` for the legs slot, `HUMANOID` otherwise. Leave out `humanoid_baby` and baby mobs simply render no armor.
Layer types are listed at `EquipmentClientInfo.java:120-140`.

### 5.5 Helmet visor overlay (no code)

The HUD draws `Equippable.cameraOverlay` full-screen in first person:
`extractTextureOverlay(graphics, cameraOverlay.withPath(p -> "textures/" + p + ".png"), 1.0F)` (`MCC/gui/Hud.java:284-290`).
The carved pumpkin uses `setCameraOverlay(Identifier.withDefaultNamespace("misc/pumpkinblur"))` (`MC/world/item/Items.java:838`).
For the mod, `setCameraOverlay(RedPlanet.id("misc/spacesuit_visor"))` maps to `assets/redplanet/textures/misc/spacesuit_visor.png`.

### 5.6 Can the helmet render a 3D bubble? Yes, in three ways

1. **Data only, no code.** If the helmet's `Equippable` has **no `asset_id`**, `HumanoidArmorLayer.shouldRender` returns false
   (`HumanoidArmorLayer.java:36-43`). `LivingEntityRenderer` then renders the item model with `ItemDisplayContext.HEAD`
   (`MCC/renderer/entity/LivingEntityRenderer.java:281-292`). Give the helmet a 3D block-style item model (elements, glass
   texture with partial alpha so it renders translucent) and a `display.head` transform. Use a `minecraft:select` on
   `minecraft:display_context` to show a flat icon in the GUI, as `ASSETS/items/spyglass.json` does (§6.1). Cost: the
   helmet uses no armor texture.
2. **Fabric `ArmorRenderer`** (`FAPI/api/client/rendering/v1/ArmorRenderer.java:55-131`):
   `ArmorRenderer.register(ArmorRenderer.Factory, ItemLike...)` with
   `void render(PoseStack, SubmitNodeCollector, ItemStack, HumanoidRenderState, EquipmentSlot, int light, HumanoidModel<HumanoidRenderState> contextModel)`
   and the helper `submitTransformCopyingModel(...)` to copy head transforms onto a custom `Model`. This needs a client
   `ModelLayerLocation` and a `LayerDefinition` (UNVERIFIED in detail).
3. Keep the flat armor texture and add the `camera_overlay` for the first-person visor (§5.5). This can be combined with option 1 or 2.

---

## 6. Item and block models, block states and atlases

### 6.1 Item model definitions, `assets/<ns>/items/<id>.json` (required for every item)

The default `ITEM_MODEL` component equals the item id (`Item.java:398`, `finalizeInitializer` at `:732`). Top-level keys:
`model` plus the optional `hand_animation_on_swap`, `oversized_in_gui` and `swap_animation_scale`
(`MCC/renderer/item/ClientItem.java`). Model types: `minecraft:empty, model, range_dispatch, special, composite, bundle/selected_item, select, condition`
(`MCC/renderer/item/ItemModels.java:19-26`).

Simple (`ASSETS/items/iron_ingot.json`), with a block item pointing at a block model (`ASSETS/items/iron_ore.json`):

```json
{ "model": { "type": "minecraft:model", "model": "minecraft:item/iron_ingot" } }
{ "model": { "type": "minecraft:model", "model": "minecraft:block/iron_ore" } }
```

Tinted (`ASSETS/items/potion.json`):

```json
{ "model": { "type": "minecraft:model", "model": "minecraft:item/potion",
  "tints": [ { "type": "minecraft:potion", "default": -13083194 } ] } }
```

Conditional (`ASSETS/items/elytra.json`):

```json
{ "model": { "type": "minecraft:condition", "property": "minecraft:broken",
  "on_false": { "type": "minecraft:model", "model": "minecraft:item/elytra" },
  "on_true":  { "type": "minecraft:model", "model": "minecraft:item/elytra_broken" } } }
```

Select on display context, which gives a flat icon in the GUI and a 3D model elsewhere (`ASSETS/items/spyglass.json`):

```json
{ "model": { "type": "minecraft:select", "property": "minecraft:display_context",
  "cases": [ { "when": ["gui", "ground", "fixed", "on_shelf"], "model": { "type": "minecraft:model", "model": "minecraft:item/spyglass" } } ],
  "fallback": { "type": "minecraft:model", "model": "minecraft:item/spyglass_in_hand" } } }
```

Range dispatch (`ASSETS/items/brush.json`) uses `"property": "minecraft:use_cycle"`, `"entries": [{ "threshold": 0.25, "model": {...}}]`,
`"fallback"`, `"scale"` and `"period"`.

Built-in properties:
- numeric: `custom_model_data, bundle/fullness, damage, cooldown, time, compass, crossbow/pull, use_cycle, use_duration, count`
  (`MCC/renderer/item/properties/numeric/RangeSelectItemModelProperties.java`)
- conditional: `custom_model_data, using_item, broken, damaged, fishing_rod/cast, has_component, bundle/has_selected_item, selected, carried, extended_view, keybind_down, view_entity, component`
- select: `custom_model_data, main_hand, charge_type, trim_material, block_state, display_context, local_time, context_entity_type, context_dimension, component`

`context_dimension` lets an item (a dosimeter, say) change model on Mars. `ItemModelUtils.inOverworld(...)`
(`MCC/data/models/model/ItemModelUtils.java:160`) shows the idea.

For an **O2-fill texture** there are two options:
1. Register a custom numeric property on the client:
   `RangeSelectItemModelProperties.ID_MAPPER.put(RedPlanet.id("oxygen"), OxygenFill.MAP_CODEC)`. `ID_MAPPER` is
   transitive-accessible (`CT/fabric-transitive-access-wideners-v1.classtweaker:48-53`). The interface is
   `float get(ItemStack, @Nullable ClientLevel, @Nullable ItemOwner, int seed)` plus `MapCodec<...> type()`.
2. Mirror the fraction into `DataComponents.CUSTOM_MODEL_DATA` floats and use `"property":"minecraft:custom_model_data","index":0`
   (`.../numeric/CustomModelDataProperty.java`). This needs no client code.

Special models: `"type": "minecraft:special", "base": "<model for particles/transforms>", "model": {"type": "minecraft:chest" | "shield" | "trident" | "head" | "banner" | "bell" | "book" | "conduit" | "decorated_pot" | "shulker_box" | "player_head" | "copper_golem_statue" | "end_cube"}`
(`MCC/renderer/special/SpecialModelRenderers.java`, `ASSETS/items/chest.json`). Custom ones go through
`SpecialModelRenderers.ID_MAPPER` (transitive-accessible) with `SpecialModelRenderer<T>`:
`submit(T, PoseStack, SubmitNodeCollector, int light, int overlay, boolean foil, int outline)`, `getExtents(Consumer<Vector3fc>)` and `extractArgument(ItemStack)`.

### 6.2 `models/block` and `models/item`

The format is the same as before (`parent`, `textures`, `elements`, `display`, `gui_light`). One addition: a texture
value can be an object `{ "sprite": "...", "force_translucent": true }` (`MCC/resources/model/sprite/Material.java`,
`ASSETS/models/block/glass.json`).

```json
// ASSETS/models/block/iron_ore.json
{ "parent": "minecraft:block/cube_all", "textures": { "all": "minecraft:block/iron_ore" } }
// ASSETS/models/item/iron_ingot.json
{ "parent": "minecraft:item/generated", "textures": { "layer0": "minecraft:item/iron_ingot" } }
// ASSETS/models/block/furnace.json
{ "parent": "minecraft:block/orientable", "textures": { "front": "minecraft:block/furnace_front", "side": "minecraft:block/furnace_side", "top": "minecraft:block/furnace_top" } }
```

The snow layers `ASSETS/models/block/snow_height2.json` through `snow_height14.json` are hand-written element models
with texture slots `particle` and `texture`. Dust layers can use them as **parents** and override both slots (§15.8).
`ASSETS/models/item/generated.json` contains the default `display.head` transform for flat items.

### 6.3 Block states (unchanged format)

```json
// ASSETS/blockstates/iron_ore.json
{ "variants": { "": { "model": "minecraft:block/iron_ore" } } }
// ASSETS/blockstates/basalt.json
{ "variants": { "axis=x": { "model": "minecraft:block/basalt", "x": 90, "y": 90 }, "axis=y": { "model": "minecraft:block/basalt" }, "axis=z": { "model": "minecraft:block/basalt", "x": 90 } } }
// ASSETS/blockstates/snow.json
{ "variants": { "layers=1": { "model": "minecraft:block/snow_height2" }, "...": "...", "layers=8": { "model": "minecraft:block/snow_block" } } }
// ASSETS/blockstates/furnace.json uses "facing=east,lit=false": { "model": "minecraft:block/furnace", "y": 90 }, and so on
// ASSETS/blockstates/oak_fence.json uses "multipart": [ { "apply": {...} }, { "apply": { "model": "...", "uvlock": true, "y": 90 }, "when": { "east": "true" } } ]
```

### 6.4 Atlases: mod textures need no registration if they sit in the standard folders

- `ASSETS/atlases/blocks.json`: `{"type":"minecraft:directory","prefix":"block/","source":"block"}` plus a few singles.
- `ASSETS/atlases/items.json`: `{"type":"minecraft:directory","prefix":"item/","source":"item"}` plus the trim permutations.
  **Items are on their own atlas.**
- `DirectoryLister` scans `textures/<source>` in **all namespaces** (`FileToIdConverter.listMatchingResources`,
  `MCC/renderer/texture/atlas/sources/DirectoryLister.java:19-20`). So `assets/redplanet/textures/block/**` and `textures/item/**`
  are picked up automatically.
- Atlas definitions **merge across packs** (`resourceManager.getResourceStack(...)`,
  `MCC/renderer/texture/atlas/SpriteSourceList.java:65-75`). A texture outside those folders that a block model needs can be
  added with `assets/minecraft/atlases/blocks.json` in the mod jar. Fabric also offers `FAPI/api/client/rendering/v1/AtlasRegistry`
  and `SpriteSourceRegistry` (not investigated).

### 6.5 Render layers are automatic; author alpha deliberately

`BakedQuad.MaterialInfo.of(...)` does `ChunkSectionLayer layer = ChunkSectionLayer.byTransparency(transparency);`
(`MCC/resources/model/geometry/BakedQuad.java:70-90`). `byTransparency` returns `TRANSLUCENT` if any pixel has
0 < alpha < 255, `CUTOUT` if any pixel has alpha 0, and `SOLID` otherwise (`MCC/renderer/chunk/ChunkSectionLayer.java:28-34`).
The scan happens in `NativeImage.computeTransparency()` (`B3D/platform/NativeImage.java:449-485`).
**Consequences:**
- Solid blocks need exactly alpha 255 everywhere. A stray 254 makes the block translucent, which costs sorting time on an 8 GB M2.
- Glass-like blocks (CO2 ice, water ice, helmet bubble) become translucent just from their PNG.
- `force_translucent` forces the translucent layer.
- Non-full or transparent blocks still need `noOcclusion()` so neighbouring faces render. Vanilla ICE sets it.
- Fabric API 0.161 has no `BlockRenderLayerMap` (a grep in `FAPI/` finds nothing).

---

## 7. Creative tabs

- Fabric: `FabricCreativeModeTab.builder()` returns a `CreativeModeTab.Builder` (`FAPI/api/creativetab/v1/FabricCreativeModeTab.java:60`).
  Register it into `BuiltInRegistries.CREATIVE_MODE_TAB`. **It must have `title(Component)`.**
- Builder (`MC/world/item/CreativeModeTab.java:120-180`): `title(Component)`, `icon(Supplier<ItemStack>)`,
  `displayItems(DisplayItemsGenerator)`, where `void accept(ItemDisplayParameters parameters, Output output)`, plus `alignedRight()`,
  `hideTitle()`, `noScrollBar()`, `backgroundTexture(Identifier)` and `build()`. `Output.accept(ItemLike)` / `accept(ItemStack)`
  (`:249-270`). `ItemDisplayParameters(FeatureFlagSet enabledFeatures, boolean hasPermissions, HolderLookup.Provider holders)` (`:240`).
- Adding to vanilla tabs: `CreativeModeTabEvents.modifyOutputEvent(ResourceKey<CreativeModeTab>).register(output -> output.accept(...))`
  (`FAPI/api/creativetab/v1/CreativeModeTabEvents.java:53`). `FabricCreativeModeTabOutput` has `insertAfter`, `insertBefore` and `prepend`.
  Vanilla keys include `CreativeModeTabs.BUILDING_BLOCKS, NATURAL_BLOCKS, FUNCTIONAL_BLOCKS, REDSTONE_BLOCKS, TOOLS_AND_UTILITIES, COMBAT, INGREDIENTS`
  (`MC/world/item/CreativeModeTabs.java:56-108`).
- Javadoc example: `FabricCreativeModeTab.java:36-52`.

---

## 8. Recipes

### 8.1 JSON (verbatim from `DATA/recipe/`)

```json
// iron_block.json
{ "type": "minecraft:crafting_shaped", "category": "building", "key": { "#": "minecraft:iron_ingot" },
  "pattern": [ "###", "###", "###" ], "result": { "id": "minecraft:iron_block" } }
// furnace.json, a tag ingredient
{ "type": "minecraft:crafting_shaped", "key": { "#": "#minecraft:stone_crafting_materials" },
  "pattern": [ "###", "# #", "###" ], "result": { "id": "minecraft:furnace" } }
// torch.json, an ingredient list
{ "type": "minecraft:crafting_shaped", "key": { "#": "minecraft:stick", "X": [ "minecraft:coal", "minecraft:charcoal" ] },
  "pattern": [ "X", "#" ], "result": { "count": 4, "id": "minecraft:torch" } }
// iron_ingot_from_iron_block.json
{ "type": "minecraft:crafting_shapeless", "group": "iron_ingot", "ingredients": [ "minecraft:iron_block" ],
  "result": { "count": 9, "id": "minecraft:iron_ingot" } }
// iron_ingot_from_smelting_raw_iron.json
{ "type": "minecraft:smelting", "cookingtime": 200, "experience": 0.7, "group": "iron_ingot",
  "ingredient": "minecraft:raw_iron", "result": { "id": "minecraft:iron_ingot" } }
// iron_ingot_from_blasting_raw_iron.json: also cookingtime 200 (see the note below)
{ "type": "minecraft:blasting", "cookingtime": 200, "experience": 0.7, "group": "iron_ingot",
  "ingredient": "minecraft:raw_iron", "result": { "id": "minecraft:iron_ingot" } }
// stone_brick_slab_from_stone_stonecutting.json
{ "type": "minecraft:stonecutting", "ingredient": "minecraft:stone", "result": { "count": 2, "id": "minecraft:stone_brick_slab" } }
```

- An ingredient is a HolderSet: `"ns:item"`, `"#ns:tag"` or `[...]` (`MC/world/item/crafting/Ingredient.java:34-35`).
- A result is an `ItemStackTemplate`: `{ "id", "count" (1..99, default 1), "components" }`. A bare string also parses
  (`MC/world/item/ItemStackTemplate.java`).
- `category` is optional and defaults to `misc`. Crafting values are `building/redstone/equipment/misc`. Optional
  `show_notification` defaults to true, and `group` (`MC/world/item/crafting/Recipe.java:53-87`). Cooking uses
  `ingredient, result, experience, cookingtime` (`AbstractCookingRecipe.java:74-86`).
- **Blasting and smoking recipes now use the same `cookingtime` as smelting (200).** The fast furnaces get their 2x speed
  from the fuel speed multiplier, which checks the `minecraft:block/fast_cooking` predicate
  (`AbstractFurnaceBlockEntity.java:276-283`, `DATA/predicate/block/fast_cooking.json`, `VanillaRecipeProvider.java:2347-2352`).

### 8.2 Unlock advancements

- In-game: recipes work without an advancement. Only the `limited_crafting` gamerule (default false,
  `MC/world/level/gamerules/GameRules.java:44`) restricts crafting to unlocked recipes. The advancement only adds the recipe
  to the recipe book.
- In datagen: **`unlockedBy` is effectively required.** `RecipeUnlockAdvancementBuilder.build` throws
  `"No way of obtaining recipe ..."` when there are no criteria (`MC/data/recipes/RecipeUnlockAdvancementBuilder.java`).
  It writes `advancement/recipes/<category>/<name>.json` with parent `minecraft:recipes/root`. The vanilla shape
  (`DATA/advancement/recipes/building_blocks/iron_block.json`) has criteria `has_the_recipe` (`minecraft:recipe_unlocked`,
  `"conditions": {"recipes": "minecraft:iron_block"}`) plus `has_iron_ingot` (`minecraft:inventory_changed`), with
  `"requirements": [["has_the_recipe","has_iron_ingot"]]` and `"rewards": {"recipes": ["minecraft:iron_block"]}`.

### 8.3 Custom recipe types (outline only)

- `RecipeType`: `Registry.register(BuiltInRegistries.RECIPE_TYPE, id, new RecipeType<MyRecipe>() { public String toString() { return "..."; } })`
  (pattern from `MC/world/item/crafting/RecipeType.java`).
- `RecipeSerializer` is a **record**: `new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC)`, registered into `BuiltInRegistries.RECIPE_SERIALIZER`
  (`RecipeSerializer.java`, `RecipeSerializers.java`).
- `Recipe<I extends RecipeInput>` needs `matches(I, Level)`, `assemble(I)` (no registry argument any more),
  `showNotification()`, `group()`, `getSerializer()`, `getType()`, `placementInfo()` (`PlacementInfo.create(...)` or
  `PlacementInfo.NOT_PLACEABLE`) and `recipeBookCategory()` (registry `Registries.RECIPE_BOOK_CATEGORY`) (`Recipe.java:21-51`).
- Lookup: `serverLevel.recipeAccess()` / `RecipeManager.getRecipeFor(RecipeType<T>, I, Level)` (`RecipeManager.java:104-117`).
- The MOXIE, Sabatier and electrolyzer machines can hard-code their chemistry in Java, which avoids this machinery.

---

## 9. Loot tables, predicates and tags

### 9.1 Block loot JSON (verbatim, `DATA/loot_table/blocks/`)

```json
// iron_ore.json: silk touch or fortune
{ "type": "minecraft:block", "pools": [ { "entries": [ { "type": "minecraft:alternatives", "children": [
    { "type": "minecraft:item", "condition": "minecraft:tool/can_silk_touch", "name": "minecraft:iron_ore" },
    { "type": "minecraft:item", "modifier": [
        { "type": "minecraft:apply_bonus", "enchantment": "minecraft:fortune", "formula": "minecraft:ore_drops" },
        { "type": "minecraft:explosion_decay" } ], "name": "minecraft:raw_iron" } ] } ], "rolls": 1 } ],
  "random_sequence": "minecraft:blocks/iron_ore" }
// iron_block.json: drop self
{ "type": "minecraft:block", "pools": [ { "condition": { "type": "minecraft:survives_explosion" },
  "entries": [ { "type": "minecraft:item", "name": "minecraft:iron_block" } ], "rolls": 1 } ], "random_sequence": "minecraft:blocks/iron_block" }
// ice.json: silk touch only
{ "type": "minecraft:block", "pools": [ { "condition": "minecraft:tool/can_silk_touch",
  "entries": [ { "type": "minecraft:item", "name": "minecraft:ice" } ], "rolls": 1 } ], "random_sequence": "minecraft:blocks/ice" }
// furnace.json: keep the custom name
... "modifier": { "type": "minecraft:copy_components", "include": [ "minecraft:custom_name" ], "source": "block_entity" } ...
```

- Codec (`MC/world/level/storage/loot/LootPool.java:34-40`): `entries`, `condition` (`LootItemCondition.CODEC` = holder of
  registry `minecraft:predicate`, either inline or by id), `modifier` (`LootItemFunctions.CODEC`, a single function, an
  inline list or an `item_modifier` id), `rolls`, `bonus_rolls`. Table: `type`, `random_sequence`, `pools`, `modifier`
  (`LootTable.java:38-46`).
- Predicates are a reloadable registry. Vanilla ships `DATA/predicate/tool/can_silk_touch.json`
  (`{"type":"minecraft:match_tool","predicate":{"predicates":{"minecraft:enchantments":[{"enchantments":"minecraft:silk_touch","levels":{"min":1}}]}}}`),
  `tool/can_shear.json` and `block/fast_cooking.json`.
- Condition type ids include `inverted, any_of, all_of (field "terms"), random_chance, entity_properties, match_block, match_tool, table_bonus, survives_explosion, location_check, environment_attribute_check, ...`
  (`MC/world/level/storage/loot/predicates/LootItemConditionTypes.java`, `CompositeLootItemCondition.java:36`).
- **Hand-writing these is error-prone. Use datagen** (§14).

### 9.2 Block tags (`DATA/tags/block/`)

`mineable/pickaxe.json`, `mineable/shovel.json`, `needs_stone_tool.json`, `needs_iron_tool.json` and `needs_diamond_tool.json`
use the format `{"values": ["minecraft:iron_ore", "#minecraft:copper_chests", ...]}`. The `incorrect_for_*_tool` tags are built
from the `needs_*` tags (`MC/data/tags/VanillaBlockTagsProvider.java:876-879`), so you only add `needs_*`. Constants are
`BlockTags.MINEABLE_WITH_PICKAXE/SHOVEL, NEEDS_STONE_TOOL/IRON_TOOL/DIAMOND_TOOL` (`MC/tags/BlockTags.java:159-170`).
`requiresCorrectToolForDrops()` plus the `mineable` and `needs_*` tags gate drops.

### 9.3 Item tags for armor and enchanting

Add the suit pieces to `minecraft:head_armor`, `chest_armor`, `leg_armor` and `foot_armor` (`ItemTags.HEAD_ARMOR` and so on,
`MC/tags/ItemTags.java:167-170`). The `enchantable/*`, `enchantable/durability` and `enchantable/equippable` tags include them
through those tags (`DATA/tags/item/enchantable/head_armor.json` = `["#minecraft:head_armor"]`). Add them to `trimmable_armor`
only if you ship trim textures.

### 9.4 Fabric convention tags (`c:` namespace, `FAPI/api/tag/convention/v2/`)

- `ConventionalBlockItemTags` holds `BlockItemTagId` pairs: `ORES = c:ores` and per-ore `c:ores/<name>` (for example
  `IRON_ORES = c:ores/iron`), `ORES_IN_GROUND_STONE/DEEPSLATE/NETHERRACK`, `ORE_RATES_SPARSE/SINGULAR/DENSE`, `STORAGE_BLOCKS`,
  `SANDS`, `RED_SANDS`, and so on. `ConventionalBlockTags.ORES = ConventionalBlockItemTags.ORES.block()` (`ConventionalBlockTags.java:59`).
- `ConventionalItemTags`: `INGOTS = c:ingots`, `IRON_INGOTS = c:ingots/iron`, `RAW_MATERIALS = c:raw_materials`, `DUSTS`,
  `GEMS`, `NUGGETS`, `ORES` (item side) (`ConventionalItemTags.java:127-149`).
- The convention is a parent tag that includes `#c:ores/<name>`. Hematite gets `c:ores/hematite`, and the steel ingot gets
  `c:ingots/stainless_steel`. `MC/tags/BlockItemTagId.java` has `create(Identifier blockId, Identifier itemId)`.

---

## 10. Advancements and criteria

### 10.1 JSON

Verbatim from `DATA/advancement/story/root.json` (root with a background) and `story/enter_the_nether.json`:

```json
{ "criteria": { "crafting_table": { "conditions": { "items": [ { "items": "minecraft:crafting_table" } ] }, "trigger": "minecraft:inventory_changed" } },
  "display": { "announce_to_chat": false, "background": "minecraft:gui/advancements/backgrounds/stone",
    "description": { "translate": "advancements.story.root.description" }, "icon": { "id": "minecraft:grass_block" },
    "show_toast": false, "title": { "translate": "advancements.story.root.title" } },
  "requirements": [ [ "crafting_table" ] ], "sends_telemetry_event": true }

{ "parent": "minecraft:story/form_obsidian",
  "criteria": { "entered_nether": { "conditions": { "to": "minecraft:the_nether" }, "trigger": "minecraft:changed_dimension" } },
  "display": { "description": { "translate": "advancements.story.enter_the_nether.description" }, "icon": { "id": "minecraft:flint_and_steel" },
    "title": { "translate": "advancements.story.enter_the_nether.title" } },
  "requirements": [ [ "entered_nether" ] ], "sends_telemetry_event": true }
```

- `DisplayInfo` fields (`MC/advancements/DisplayInfo.java`): `icon` (ItemStackTemplate, `{"id":...}` or a string),
  `title`, `description`, optional `background`, `frame` (`task`, `goal` or `challenge`, default task), `show_toast` (true),
  `announce_to_chat` (true) and `hidden` (false).
- `background` is a short id that resolves to `textures/<path>.png` (`MC/core/ClientAsset.java:25-27`). For example
  `redplanet:block/regolith` maps to `assets/redplanet/textures/block/regolith.png`.
- Location trigger with a dimension or biome (`DATA/advancement/adventure/adventuring_time.json`, `nether/find_fortress.json`):

```json
"criteria": { "on_mars": { "trigger": "minecraft:location", "conditions": { "player": {
    "type": "minecraft:entity_properties", "entity": "this",
    "predicate": { "minecraft:location": { "dimension": "redplanet:mars" } } } } } }
```

  `LocationPredicate` keys are `position {x,y,z}`, `biomes`, `structures`, `dimension`, `smokey`, `light`, `block`, `fluid` and
  `can_see_sky` (`MC/advancements/predicates/LocationPredicate.java:31-39`). Entity sub-predicate ids are `location`,
  `stepping_on`, `effects`, `equipment`, `flags`, `vehicle`, `type_specific/player`, ... (`MC/advancements/predicates/entity/EntitySubPredicates.java`).
- Useful built-in trigger ids (`MC/advancements/triggers/CriteriaTriggers.java`): `changed_dimension`, `location`, `tick`,
  `inventory_changed`, `started_riding` (boarding the Starship), `placed_block`, `item_used_on_block`, `consume_item`,
  `effects_changed`, `entity_hurt_player`, `levitation`, `fall_from_height`, `using_item`, `recipe_crafted`, `impossible`.

### 10.2 Custom trigger (`MC/advancements/triggers/SimpleCriterionTrigger.java`, `PlayerTrigger.java`, `ConstructBeaconTrigger.java`)

```java
public abstract class SimpleCriterionTrigger<T extends SimpleCriterionTrigger.SimpleInstance> implements CriterionTrigger<T> {
    protected void trigger(final ServerPlayer player, final Predicate<T> matcher)        // :20
    public interface SimpleInstance extends CriterionTriggerInstance { Optional<Holder<LootItemCondition>> player(); }
}
public interface CriterionTrigger<T extends CriterionTriggerInstance> { Codec<T> codec(); default Criterion<T> createCriterion(T instance) {...} }
// the instance codec field for the player is now:
LootItemCondition.CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player)   // Codec<Holder<LootItemCondition>>
```

Register with `Registry.register(BuiltInRegistries.TRIGGER_TYPES, id, new MyTrigger())`. Vanilla uses the same call in
`CriteriaTriggers.register` (`:68-70`). The registry key is `Registries.TRIGGER_TYPE`. Fire it from server code with
`MY_TRIGGER.trigger(serverPlayer, ...)`. Entity predicates for code-built criteria use
`EntityPredicate.wrap(EntityPredicate.Builder...) -> Holder<LootItemCondition>` (`MC/advancements/predicates/entity/EntityPredicate.java:68-82`).

### 10.3 `Advancement.Builder` (`MC/advancements/Advancement.java:118-245`)

`advancement()` (with telemetry), `parent(AdvancementHolder)` (`parent(Identifier)` is deprecated for removal),
`rootDisplay(Item|ItemStackTemplate icon, Component title, Component desc, Identifier background, AdvancementType, boolean showToast, boolean announceChat, boolean hidden)`,
`display(Item|ItemStackTemplate, Component, Component, AdvancementType, boolean, boolean, boolean)`, `addCriterion(String, Criterion<?>)`,
`requirements(...)`, `rewards(AdvancementRewards.Builder)` (`experience(int)`, `recipe(ResourceKey<Recipe<?>>)`, `loot(Holder<LootTable>)`, `function(Identifier)`)
and `build(Identifier) -> AdvancementHolder`. Criteria helpers: `InventoryChangeTrigger.TriggerInstance.hasItems(ItemLike...)`,
`ChangeDimensionTrigger.TriggerInstance.changedDimensionTo(ResourceKey<Level>)`, `PlayerTrigger.TriggerInstance.located(LocationPredicate.Builder)`
and `LocationPredicate.Builder.inDimension(ResourceKey<Level>)`.

---

## 11. Sounds

- `SoundEvent` is `record SoundEvent(Identifier location, Optional<Float> fixedRange)`, with
  `createVariableRangeEvent(Identifier)` and `createFixedRangeEvent(Identifier, float)`, both widened (`MC/sounds/SoundEvent.java:38-47`).
  The range is `fixedRange.orElse(volume > 1 ? 16 * volume : 16)` (`:49-51`).
- Vanilla registration: `Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id))`, or
  `Registry.registerForHolder(...)` when a `Holder<SoundEvent>` is needed for `Equippable` or `ArmorMaterial`
  (`MC/sounds/SoundEvents.java:1933-1955`).
- `assets/<ns>/sounds.json` is **not in the client jar** (it comes from the asset index). The format below is reconstructed from
  the deserializer `MCC/resources/sounds/SoundEventRegistrationSerializer.java:23-70`: top-level keys are the event **path**,
  and each value is `{ "replace": bool, "subtitle": "key", "sounds": [ "ns:path" | { "name", "type": "file"|"event", "volume" (1), "pitch" (1), "weight" (1), "preload" (false), "stream" (false), "attenuation_distance" (16) } ] }`.
  Files live at `assets/<ns>/sounds/<path>.ogg` (`FileToIdConverter("sounds", ".ogg")`, `Sound.java:12`).
- Client attenuation: `getAttenuationDistance(volume) = max(volume, 1) * attenuation_distance` (`MCC/resources/sounds/Sound.java:90-92`).
  Gain is clamped: `Mth.clamp(volume, 0, 1)` (`MCC/sounds/SoundEngine.java:485-487`). **Volume above 1 only increases range.**
- Server broadcast: `ServerLevel.playSeededSound(...)` sends to players within `sound.value().getRange(volume)` in the same dimension
  (`MC/server/level/ServerLevel.java:1040-1086`).
- **To hear a launch far away**, call `level.playSound(null, x, y, z, LAUNCH, SoundSource.BLOCKS, 64f, 1f)`. That broadcasts to
  1024 blocks and attenuates over 64 x 16 = 1024 blocks. For a dimension-wide sound, loop over `serverLevel.players()` and send a
  `ClientboundSoundPacket(holder, source, x, y, z, volume, pitch, seed)` at each player's own position. For a moving sound, use a
  client `AbstractTickableSoundInstance` (not researched here).
- Fabric: `FabricSoundsProvider` (client datagen, §14) and the client `FabricSoundInstance` (`FAPI/api/client/sound/v1/`).
- Mono OGGs are needed for positional attenuation. **UNVERIFIED** in 26.3 code, but standard OpenAL behaviour.

---

## 12. Damage types

- Vanilla files (verbatim, `DATA/damage_type/`):
  `drown.json` is `{"effects":"drowning","exhaustion":0.0,"message_id":"drown","scaling":"when_caused_by_living_non_player"}`,
  `freeze.json` is `{"effects":"freezing","exhaustion":0.0,"message_id":"freeze","scaling":"when_caused_by_living_non_player"}`,
  and `in_wall.json` is `{"exhaustion":0.0,"message_id":"inWall","scaling":"when_caused_by_living_non_player"}`.
- Codec (`MC/world/damagesource/DamageType.java:13-23`): `message_id` (required), `scaling` (required:
  `never|when_caused_by_living_non_player|always`), `exhaustion` (required), `effects` (`hurt|thorns|drowning|burning|poking|freezing`,
  which picks the hurt sound) and `death_message_type` (`default|fall_variants|intentional_game_design`).
- Mod files go in `data/redplanet/damage_type/hypoxia.json`. It is a datapack (world) registry, `Registries.DAMAGE_TYPE`.
- Code: `ResourceKey<DamageType> HYPOXIA = ResourceKey.create(Registries.DAMAGE_TYPE, RedPlanet.id("hypoxia"))`, then
  `level.damageSources().source(HYPOXIA)`. The `source(...)` overloads are widened (`CT/fabric-transitive-access-wideners-v1.classtweaker:97-100`,
  `MC/world/damagesource/DamageSources.java:84-100`). Apply it with `entity.hurtServer(ServerLevel, DamageSource, float)`
  (`MC/world/entity/LivingEntity.java:1171`).
- Death message: `"death.attack." + msgId`, with `.player` when a kill-credit mob exists and `.item` for a named weapon
  (`MC/world/damagesource/DamageSource.java:71-86`). Use a namespaced msgId such as `redplanet.hypoxia`, which gives the key
  `death.attack.redplanet.hypoxia`.
- Tags go in `data/minecraft/tags/damage_type/*.json` (list at `DATA/tags/damage_type/`). Useful ones:
  `bypasses_armor`, `bypasses_shield`, `bypasses_enchantments` (Protection ignored, `LivingEntity.java:1933`), `bypasses_effects`,
  `bypasses_resistance`, `no_knockback` (`LivingEntity.java:1243`) and `no_impact` (no hurt tilt, `:1238`). Avoid `is_drowning`
  unless you want the `drowning_damage` gamerule to disable it (`MC/world/entity/player/Player.java:660`).

---

## 13. Mob effects (a "Hypoxia" status effect is a good fit)

- `protected MobEffect(MobEffectCategory category, int color)` is **protected**, so subclass it
  (`MC/world/effect/MobEffect.java:55`). There's no widening in `CT/`. Overridables:
  `boolean applyEffectTick(ServerLevel, LivingEntity, int amplification)` (82, return false to remove),
  `boolean shouldApplyEffectTickThisTick(int tickCount, int amplification)` (92), `onEffectStarted`, `onEffectAdded`,
  `onMobRemoved` and `onMobHurt`. Builders: `addAttributeModifier(Holder<Attribute>, Identifier, double, Operation)`,
  `setBlendDuration(...)`, `withSoundOnAdded(SoundEvent)`.
- Registration as vanilla does it: `Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, id, (MobEffect) effect)` returns
  `Holder<MobEffect>` (`MC/world/effect/MobEffects.java:123-125`). Keep the static type `MobEffect` so the result is a
  `Holder<MobEffect>`, because generics are invariant. Use it with `new MobEffectInstance(Holder<MobEffect>, int duration, int amplifier)`
  (`MobEffectInstance.java:56`) and `LivingEntity.addEffect`/`hasEffect(Holder<MobEffect>)` (`LivingEntity.java:988-1005`).
- Tick pattern from `MC/world/effect/PoisonMobEffect.java`: `int interval = 25 >> amplification; return interval > 0 ? tickCount % interval == 0 : true;`.
- Icon: `assets/redplanet/textures/mob_effect/hypoxia.png` (gui atlas prefix `mob_effect/`, `MCC/gui/Hud.java:535`).
  Name key: `effect.redplanet.hypoxia` (`MobEffect.java:115`).

---

## 14. Fabric data generation and Loom

### 14.1 Entrypoint

`FAPI/api/datagen/v1/DataGeneratorEntrypoint.java`:

```java
@FunctionalInterface public interface DataGeneratorEntrypoint {
    void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator);          // :39
    default @Nullable String getEffectiveModId() { return null; }                     // :49
    default void buildRegistry(RegistrySetBuilder registryBuilder) {}                 // :62, world registries (damage_type, biome, dimension_type...)
    default void buildReloadableRegistry(RegistrySetBuilder registryBuilder) {}       // :74, loot tables, predicates, advancements...
    default void addJsonKeySortOrders(JsonKeySortOrderCallback callback) {}
}
```

The `fabric.mod.json` key is **`"fabric-datagen"`** (`FAPI/impl/datagen/FabricDataGenHelper.java:85`). System properties
(`:64-80`): `fabric-api.datagen` (enable), `fabric-api.datagen.output-dir`, `fabric-api.datagen.strict-validation` and
`fabric-api.datagen.modid` (filters entrypoints by **providing** mod id). `RegistrySetBuilder.add(ResourceKey<? extends Registry<T>>, SingleRegistryBootstrap<T>)`,
where the bootstrap is `void run(BootstrapContext<T>)` (`MC/core/RegistrySetBuilder.java:38`, `MC/core/registries/SingleRegistryBootstrap.java`).
`BootstrapContext` is `net.minecraft.data.worldgen.BootstrapContext`. The vanilla world and reloadable registry lists are in
`MC/data/registries/VanillaRegistries.java:82-139`.

### 14.2 `FabricDataGenerator` and `Pack` (`FAPI/api/datagen/v1/FabricDataGenerator.java`)

`createPack()`, `createBuiltinResourcePack(Identifier)`, `getModId()`, `isStrictValidationEnabled()` and `getRegistries()`.
`Pack.addProvider(Factory<T>)` takes `T create(FabricPackOutput output)`. `Pack.addProvider(RegistryDependentFactory<T>)` takes
`T create(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registriesFuture)`. Both **return the provider**.
Providers run **sequentially in insertion order** (`LinkedHashMap`, `.join()`, `MC/data/DataGenerator.java:20, 50-70`).
**Provider names must be unique per pack** (`"Duplicate provider: ..."`, `:86-91`). Two `FabricTagsProvider`s for the same
registry collide because both are named `"Tags for <registry>"`. `FabricPackOutput` (`FAPI/api/datagen/v1/FabricPackOutput.java`)
offers `getModId()` and `isStrictValidationEnabled()`.

### 14.3 Providers in 0.161.0+26.3

| Provider | Constructor | Implement | Side |
|---|---|---|---|
| `FabricModelProvider` (`FAPI/api/client/datagen/v1/provider/FabricModelProvider.java:30-38`) | `(FabricPackOutput)` | `generateBlockStateModels(BlockModelGenerators)`, `generateItemModels(ItemModelGenerators)` | **client** |
| `FabricSoundsProvider` (`.../client/datagen/v1/provider/FabricSoundsProvider.java:53-81`) | `(PackOutput, CompletableFuture<HolderLookup.Provider>)` | `configure(HolderLookup.Provider, SoundExporter)` + `getName()` | **client** |
| `FabricRecipeProvider` (`.../datagen/v1/provider/FabricRecipeProvider.java:61-79`) | `(FabricPackOutput, CompletableFuture<HolderLookup.Provider>)` | `RecipeProvider createRecipeProvider(HolderLookup.Provider, BootstrapContext<Recipe<?>>, BootstrapContext<Advancement>)` + `getName()` | common |
| `FabricBlockLootSubProvider` (`.../FabricBlockLootSubProvider.java:56-68`) | `(FabricPackOutput, CompletableFuture<HolderLookup.Provider>)` | `generate()` | common |
| `FabricTagsProvider<T>` and its nested `BlockTagsProvider`, `ItemTagsProvider(out, reg[, BlockTagsProvider])`, `FluidTagsProvider`, `EntityTypeTagsProvider`, `BlockEntityTypeTagsProvider` (`.../FabricTagsProvider.java:66-200`) | `(FabricPackOutput, ResourceKey<? extends Registry<T>>, CompletableFuture<...>)` | `addTags(HolderLookup.Provider)` | common |
| `FabricLanguageProvider` (`.../FabricLanguageProvider.java:69-84`) | `(FabricPackOutput[, String code], CompletableFuture<...>)` | `generateTranslations(HolderLookup.Provider, TranslationBuilder)` | common |
| `FabricAdvancementProvider` (`.../FabricAdvancementProvider.java:57-68`) | `(FabricPackOutput, CompletableFuture<...>)` | `generateAdvancement(HolderLookup.Provider, Consumer<AdvancementHolder>)` | common |
| `FabricDynamicRegistryProvider` (`.../FabricDynamicRegistryProvider.java:70-75`) | `(FabricPackOutput, CompletableFuture<...>)` | `configure(HolderLookup.Provider, Entries)` + `getName()` | common |
| `FabricCodecDataProvider<T>` (`.../FabricCodecDataProvider.java:23-91`) | `(out, reg, PackOutput.Target, String dir, Codec<T>)` or `(out, reg, ResourceKey<registry>, Codec<T>)` | `configure(BiConsumer<Identifier,T>, HolderLookup.Provider)` + `getName()` | either |
| `FabricEntityLootSubProvider`, `SimpleFabricLootTableSubProvider`, `FabricBrewingProvider` | (not needed) | | |

Notes:
- **Recipes** (`FabricRecipeProvider.java:123-180`): the provider calls `recipeProvider.buildRecipes()`, encodes
  `Recipe.DIRECT_CODEC` to `data/<ns>/recipe/` and recipe advancements to `data/<ns>/advancement/`. Recipe ids are normalised to the
  mod namespace (`getRecipeIdentifier`, `:222`, applied to every builder's `save` by `FAPI/mixin/datagen/recipe/AllCraftingRecipeJsonBuildersMixin.java`).
  `RecipeProvider` helpers (`MC/data/recipes/RecipeProvider.java`): the protected constructor
  `(BootstrapContext<Recipe<?>>, BootstrapContext<Advancement>)` (98), `abstract buildRecipes()` (136), `protected final RecipeOutput output`,
  `shaped(RecipeCategory, ItemLike[, int])` (1191), `shapeless(...)` (1205-1219), `tag(TagKey<Item>)` (1184), `has(ItemLike|TagKey)` (1113/1120),
  static `getHasName(ItemLike)`, `oreSmelting/oreBlasting(List<ItemLike>, RecipeCategory, CookingBookCategory, ItemLike, float xp, int time, String group)` (166/181),
  `stonecutterResultFromBase(RecipeCategory, ItemLike result, ItemLike base[, int count])` (710/717), `nineBlockStorageRecipes(...)` (735) and
  `SimpleCookingRecipeBuilder.smelting/blasting(Ingredient, RecipeCategory, CookingBookCategory, ItemLike, float, int)` (`SimpleCookingRecipeBuilder.java:77-97`).
- **Loot** (`MC/data/loot/BlockLootSubProvider.java`): `dropSelf(Block)` (871), `dropOther(Block, ItemLike)` (857),
  `dropWhenSilkTouch(Block)` (864), `add(Block, LootTable.Builder | Function<Block, LootTable.Builder>)` (878/885),
  `createOreDrop(Block, Item)` (452: silk touch, otherwise fortune `ore_drops` plus explosion decay),
  `createSilkTouchDispatchTable(Block, LootPoolEntryContainer.Builder<?>)` (165), `createSingleItemTableWithSilkTouch(...)` (186-205),
  `createNameableBlockEntityTable(Block)` (274), `createSlabItemTable` (233), static `noDrop()` (781), `hasSilkTouch()` (101, returns the
  `Holder` of the `tool/can_silk_touch` predicate) and `applyExplosionDecay/applyExplosionCondition` (136/143). Fabric's constructor passes
  an empty explosion-resistant set, so explosion decay always applies (`FabricBlockLootSubProvider.java:57`). With strict validation, a mod
  block with a loot key and no table throws (`:88-107`). `excludeFromStrictValidation(Block)` (73) opts out. Builder bits:
  `ContextIntProviders.exactly(n)` / `between(a,b)` (`MC/world/level/storage/loot/providers/number/ints/ContextIntProviders.java:145, 202`),
  `SetItemCountFunction.setCount(...)` and `MatchBlock.blockMatches(this.blocks, block, StatePropertiesPredicate.Builder...)`. The snow
  table at `MC/data/loot/packs/VanillaBlockLoot.java:1192-1221` is the template for dust layers.
- **Tags**: appenders take `ResourceKey<T>` (`MC/data/tags/TagAppender.java`: `add(ResourceKey<T>...)`, `addOptional`, `addTag(TagKey)`, `addOptionalTag`).
  Block and item builders take `BlockItemId...` (`MC/data/tags/BlockItemTagAppender.java`). Fabric adds `forceAddTag(TagKey)` (skips existence
  validation), `remove(...)` and `setReplace(boolean)` (`FAPI/api/datagen/v1/provider/FabricTagAppender.java`). `ItemTagsProvider.copy(TagKey<Block>, TagKey<Item>)`
  and `copy(BlockItemTagId)` need the block provider passed to the constructor (`FabricTagsProvider.java:163-200`).
  **Validation**: every element must exist in the registry lookup, and every `#tag` must be generated by this provider or be optional/forced.
  Otherwise you get `"Couldn't define tag ... missing following references"` (`MC/data/tags/TagsProvider.java:78-100`). So mod
  damage types must be in the datagen registry (`buildRegistry`) before a damage type tag provider can reference them.
- **Language**: `TranslationBuilder.add(String,String)`, `add(Item,…)`, `add(Block,…)`, `addCreativeModeTab(ResourceKey<CreativeModeTab>,…)`
  (the tab must be registered with a translatable title), `add(MobEffect,…)`, `add(SoundEvent,…)` (gives `subtitles.<ns>.<path>`),
  `add(TagKey<?>,…)`, `addEnchantment`, `add(Path existingFile)` (`FabricLanguageProvider.java:137-330`). Duplicate keys throw.
- **Dynamic registries**: `Entries.addAll(HolderLookup.RegistryLookup<T>)` writes only entries in the mod namespace (`:198-204`).
  Output is `data/<ns>/<registry path>/` (`:270-276`).
- **Models**: generated item definitions are added automatically for BlockItems whose block got a blockstate in this provider
  (`FAPI/mixin/datagen/client/ModelProviderItemInfoCollectorMixin.java`, vanilla `ModelProvider.ItemInfoCollector.finalizeAndValidate`).
  The "missing blockstate" and "missing item model" checks only run with strict validation and only for the mod namespace
  (`ModelProviderBlockStateGeneratorCollectorMixin.java`). Useful `BlockModelGenerators` calls (`MCC/data/models/BlockModelGenerators.java`):
  `createTrivialCube(Block)` (1068), `createTrivialBlock(Block, TexturedModel.Provider)` (1075), `createAxisAlignedPillarBlock(Block, TexturedModel.Provider)` (964),
  `createHorizontallyRotatedBlock(Block, TexturedModel.Provider)` (972), `createFurnace(Block, TexturedModel.Provider)` (1841, FACING plus LIT, `_front_on` texture),
  `createRotatedVariantBlock(Block)`, `createGlassBlocks`, `createNonTemplateModelBlock(Block)` (1209, a blockstate pointing at a hand-written model),
  `registerSimpleItemModel(Block|Item, Identifier)` (415-422), `registerSimpleFlatItemModel(Block|Item)` (471-478), and the static
  `plainVariant(Identifier)`, `createSimpleBlock(Block, MultiVariant)`, `ROTATION_HORIZONTAL_FACING` (221). Fields are `blockStateOutput`,
  `itemModelOutput` and `modelOutput` (131-139). `ItemModelGenerators.generateFlatItem(Item, ModelTemplate)` (111), with `ModelTemplates.FLAT_ITEM`
  and `FLAT_HANDHELD_ITEM` (`MCC/data/models/model/ModelTemplates.java:213-215`). `generateTrimmableArmorSet(...)` (266) needs trim textures.
  Templates: `new ModelTemplate(Optional<Identifier> parent, Optional<String> suffix, TextureSlot... required)` (`ModelTemplate.java:24`).
  Texture helpers: `TextureMapping.getBlockTexture(Block[, suffix])` returns a `Material` for `<ns>:block/<path><suffix>`. `TexturedModel.CUBE`
  uses `<id>`, `COLUMN` uses `<id>_side`/`<id>_top`, and `ORIENTABLE_ONLY_TOP` uses `_front/_side/_top` (`TextureMapping.java:166-320, 430-450`).
  All of these are transitive-accessible through `CT/fabric-data-generation-api-v1.classtweaker` (for example `:331` for `ROTATION_HORIZONTAL_FACING`).
- **Equipment assets**: Fabric has no dedicated provider. Use `FabricCodecDataProvider<EquipmentClientInfo>` with
  `PackOutput.Target.RESOURCE_PACK, "equipment", EquipmentClientInfo.CODEC` in the client source set (vanilla equivalent:
  `MCC/data/models/EquipmentAssetProvider.java`). `EquipmentClientInfo.builder().addHumanoidLayers(id)` adds `humanoid`,
  `humanoid_baby` **and** `humanoid_leggings` (`EquipmentClientInfo.java:51-65`). Use `addLayers(...)` to skip the baby layer.

### 14.4 Loom DSL (`LOOM/api/fabricapi/DataGenerationSettings.java`, `LOOM/configuration/fabricapi/FabricApiDataGeneration.java`)

```groovy
fabricApi {
    configureDataGeneration {
        client = true                // Property<Boolean>, default false: run datagen in the client env (required for FabricModelProvider/FabricSoundsProvider)
        modId = "redplanet"          // Property<String>: passed as -Dfabric-api.datagen.modid (entrypoint provider filter)
        strictValidation = true      // default false: -Dfabric-api.datagen.strict-validation=true
        // outputDirectory = file("src/main/generated")   // RegularFileProperty, this is the default
        // addToResources = true                          // default: adds the output dir to the main resources srcDirs
        // createRunConfiguration = true                  // default: creates the "datagen" run config and the runDatagen task
        // createSourceSet = false                        // default: true creates a "datagen" source set (+ client deps when split)
    }
}
```

Facts from `FabricApiDataGeneration.java:58-121`:
- Defaults: `outputDirectory = src/main/generated`, `createRunConfiguration = true`, `createSourceSet = false`, `strictValidation = false`,
  `addToResources = true`, `client = false`.
- The run config named `datagen` **inherits the `client` run when `client = true`, otherwise `server`**, sets the system properties
  above and uses run directory `run/datagen`. The task is **`runDatagen`** ("run" plus the capitalised config name, `LOOM/task/LoomTasks.java:194`),
  and the output dir is declared as a task output.
- The jar excludes `.cache/**` from the output dir.
- `createSourceSet = true` creates source set `datagen`, which depends on `main`, plus `client` when the source sets are split and `client = true`.
  It also registers a Loom mod group named after `modId` (`FabricApiAbstractSourceSet.java:47-80`). That `modId` must differ from `redplanet`,
  because a group with that name already exists in `loom.mods`. The datagen source set then needs its own `fabric.mod.json`.

### 14.5 How the run works

- Server mode: `FAPI/mixin/datagen/server/MainMixin.java` hijacks dedicated-server startup.
- Client mode (`client = true`): `FAPI/mixin/datagen/client/MinecraftMixin.java` injects into the `Minecraft` constructor at
  `RenderSystem.getBackendDescription()`, runs `FabricDataGenHelper.run()` and then calls `System.exit(0)`. That call sits at
  `MCC/Minecraft.java:451`, **before** `RenderSystem.initBackendSystem()` (468) and window creation (535), and client `Main` only calls
  `RenderSystem.initRenderThread()`, which just records the thread (`B3D/systems/RenderSystem.java:126-131`). So client datagen
  **shouldn't need a display or GPU** (verified from the code path, **not executed**).
- Mod initializers run before datagen. Fabric Loader's `EntrypointPatch` anchors the client hook at the same `"Backend library: {}"`
  log call (seen with `javap` on `fabric-loader-0.19.5.jar`). Datagen needs mod registrations, so the order must be init first, then datagen.
  Client-side registrations, such as a custom item-model property codec, are therefore available to the model provider
  (**UNVERIFIED by execution**). Keep `ClientModInitializer` free of GPU calls.

### 14.6 Split source sets: client-only model generation

- `net.minecraft.client.data.models.*` (`BlockModelGenerators`, `ItemModelGenerators`, `ModelTemplate`, `TextureMapping`), `EquipmentClientInfo`
  and the Fabric `client.datagen` providers compile **only in the client source set**. Put the `DataGeneratorEntrypoint` and every provider in
  `src/client/java/...` (option A), or use `createSourceSet = true` (option B).
- **Option A (simplest):** set `client = true`, keep `createSourceSet` false, and add `"fabric-datagen": ["io.github.avi130805.redplanet.client.datagen.RedPlanetDataGenerator"]`
  to the existing `src/main/resources/fabric.mod.json`. The `client` entrypoint already points into the client source set. The entrypoint is only
  instantiated in datagen runs, but the classes ship in the jar (a few KB). Whether Loom validation accepts a main `fabric.mod.json` entry that
  points at a client source-set class is **UNVERIFIED**, though no such check was found.
- **Option B (cleaner jar):** set `createSourceSet = true`, `client = true` and `modId = "redplanet-datagen"`, add
  `src/datagen/resources/fabric.mod.json` (id `redplanet-datagen`, depends on `redplanet`, with the `fabric-datagen` entrypoint), and
  **override `getEffectiveModId()` to return `"redplanet"`**. Otherwise `FabricPackOutput.getModId()` would be `redplanet-datagen`,
  recipe ids would be normalised into that namespace, and strict validation would check the wrong namespace.
- Generated files land in `src/main/generated`, which is part of the main resources. **Never keep the same relative path in both**
  `src/main/resources` and `src/main/generated`, for example a hand-written `en_us.json`, or `processResources` reports a duplicate
  (**UNVERIFIED**, standard Gradle behaviour). Commit the generated output and check in CI that `runDatagen` produces no diff.
- `org.gradle.configuration-cache=true` together with `runDatagen` is **UNVERIFIED**.

### 14.7 Datagen or hand-write?

| Content | Recommendation | Why |
|---|---|---|
| Loot tables | **datagen** | the 26.3 format changed a lot (predicate refs, `condition`/`modifier`) |
| Recipes plus unlock advancements | **datagen** | holder references and auto-generated advancements |
| Block and item tags (mineable, needs_*, c:*) | **datagen** | existence validation catches typos |
| Blockstates, block models, `items/*.json` | **datagen** (`FabricModelProvider`) | avoids missing item definitions; strict validation |
| en_us | **datagen** | keys derived from objects |
| Advancements | **datagen** | criterion codecs |
| Damage types plus their tags | **datagen** (`buildRegistry` + `FabricDynamicRegistryProvider` + a `FabricTagsProvider<DamageType>`) | tags must see the types |
| `sounds.json` | datagen (`FabricSoundsProvider`) or hand-write | small either way |
| `equipment/spacesuit.json` | hand-write or `FabricCodecDataProvider` | one file |
| Complex element models (Starship, launch mount, solar panel) | hand-write, or Blockbench export plus `createNonTemplateModelBlock` | |

---

## 15. Recommendations for redplanet: skeletons

These are written against the sources above and **have not been compiled**. Package root: `io.github.avi130805.redplanet`.
Start init in this order: components, sounds, blocks, items, block entities, menus, effects, triggers, creative tab.

### 15.1 Registration helper, blocks and items (common)

```java
package io.github.avi130805.redplanet.registry;

public final class RPRegistration {
    private RPRegistration() {}
    public static ResourceKey<Block> blockKey(String path) { return ResourceKey.create(Registries.BLOCK, RedPlanet.id(path)); }
    public static ResourceKey<Item> itemKey(String path) { return ResourceKey.create(Registries.ITEM, RedPlanet.id(path)); }

    /** Same shape as Blocks.register(ResourceKey, Function, Properties) (Blocks.java:5655). setId BEFORE the constructor. */
    public static <B extends Block> B block(String path, Function<BlockBehaviour.Properties, B> factory, BlockBehaviour.Properties props) {
        ResourceKey<Block> key = blockKey(path);
        return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(props.setId(key)));
    }
    /** Same shape as Items.registerItem (Items.java:2937). */
    public static <I extends Item> I item(String path, Function<Item.Properties, I> factory, Item.Properties props) {
        ResourceKey<Item> key = itemKey(path);
        return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(props.setId(key)));
    }
    /** Same shape as Items.registerBlock (Items.java:2907): useBlockDescriptionPrefix() gives the "block.redplanet.x" name. */
    public static BlockItem blockItem(Block block, Item.Properties props) {
        ResourceKey<Item> key = itemKey(BuiltInRegistries.BLOCK.getKey(block).getPath());
        return Registry.register(BuiltInRegistries.ITEM, key, new BlockItem(block, props.setId(key).useBlockDescriptionPrefix()));
        // Item.BY_BLOCK is filled by Fabric's BlockItemTracker
    }
    public static <B extends Block> B blockWithItem(String path, Function<BlockBehaviour.Properties, B> factory, BlockBehaviour.Properties props) {
        B b = block(path, factory, props);
        blockItem(b, new Item.Properties());
        return b;
    }
}
```

```java
public final class RPBlocks {
    public static final Block REGOLITH = RPRegistration.blockWithItem("regolith", Block::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_ORANGE).strength(0.6F).sound(SoundType.GRAVEL));
    public static final Block MARS_SAND = RPRegistration.blockWithItem("mars_sand", p -> new SandBlock(new ColorRGBA(0xB5643C), p),
        BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).instrument(NoteBlockInstrument.SNARE).strength(0.5F).sound(SoundType.SAND));
    public static final Block DUST_BLOCK = RPRegistration.blockWithItem("dust_block", Block::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_ORANGE).strength(0.2F).sound(SoundType.SAND));
    public static final Block DUST_LAYER = RPRegistration.blockWithItem("dust_layer", DustLayerBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_ORANGE).replaceable().forceSolidOff().strength(0.1F)
            .sound(SoundType.SAND).pushReaction(PushReaction.POPPED)
            .isViewBlocking((state, level, pos, aabb) -> state.getValue(SnowLayerBlock.LAYERS) >= 8));   // no randomTicks(): dust never melts
    public static final Block HEMATITE_ORE = RPRegistration.blockWithItem("hematite_ore", p -> new DropExperienceBlock(UniformInt.of(0, 2), p),
        BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_RED).instrument(NoteBlockInstrument.BASEDRUM)
            .requiresCorrectToolForDrops().strength(3.0F, 3.0F));
    public static final Block MARS_BASALT = RPRegistration.blockWithItem("mars_basalt", RotatedPillarBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).instrument(NoteBlockInstrument.BASEDRUM)
            .requiresCorrectToolForDrops().strength(1.25F, 4.2F).sound(SoundType.BASALT));
    public static final Block CO2_ICE = RPRegistration.blockWithItem("co2_ice", DryIceBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.SNOW).randomTicks().friction(0.98F).strength(0.5F)
            .sound(SoundType.GLASS).noOcclusion());                      // partial-alpha texture renders translucent automatically
    public static final Block MOXIE = RPRegistration.blockWithItem("moxie", MoxieBlock::new,
        BlockBehaviour.Properties.of().mapColor(MapColor.METAL).requiresCorrectToolForDrops().strength(3.5F).sound(SoundType.METAL)
            .lightLevel(state -> state.getValue(MoxieBlock.LIT) ? 7 : 0));
    public static void init() {}
}

public class DustLayerBlock extends SnowLayerBlock {          // SnowLayerBlock + ctor are public (Fabric-widened)
    public DustLayerBlock(BlockBehaviour.Properties p) { super(p); }
    @Override protected void randomTick(BlockState s, ServerLevel l, BlockPos p, RandomSource r) { }   // never melts
}

public class DryIceBlock extends Block {
    public DryIceBlock(BlockBehaviour.Properties p) { super(p); }
    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!level.dimension().equals(RPDimensions.MARS) || level.getBrightness(LightLayer.BLOCK, pos) > 11) {   // design rule, put it in SCIENCE.md
            level.removeBlock(pos, false);
            level.sendParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.01);
            level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(state));
        }
    }
}
```

### 15.2 Machine: block, block entity, ticker, menu and screen

```java
// --- RPBlockEntities (common)
public static final BlockEntityType<MoxieBlockEntity> MOXIE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
    RedPlanet.id("moxie"), FabricBlockEntityTypeBuilder.create(MoxieBlockEntity::new, RPBlocks.MOXIE).build());

// --- RPMenus (common)
public static final ExtendedMenuType<MoxieMenu, BlockPos> MOXIE = Registry.register(BuiltInRegistries.MENU,
    RedPlanet.id("moxie"), new ExtendedMenuType<>(MoxieMenu::new, BlockPos.STREAM_CODEC));   // StreamCodec<ByteBuf,BlockPos> fits "? super RegistryFriendlyByteBuf"

// --- MoxieBlock (common)
public class MoxieBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    public MoxieBlock(BlockBehaviour.Properties p) {
        super(p);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIT, false));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING, LIT); }
    @Override public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }
    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new MoxieBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level instanceof ServerLevel server
            ? createTickerHelper(type, RPBlockEntities.MOXIE, (lvl, pos, st, be) -> be.serverTick(server, pos, st))
            : null;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof MoxieBlockEntity be) player.openMenu(be);
        return InteractionResult.SUCCESS;
    }
    @Override protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
        Containers.updateNeighboursAfterDestroy(state, level, pos);   // contents drop via BlockEntity.preRemoveSideEffects
    }
    @Override protected BlockState rotate(BlockState s, Rotation r) { return s.setValue(FACING, r.rotate(s.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState s, Mirror m) { return s.rotate(m.getRotation(s.getValue(FACING))); }
}

// --- MoxieBlockEntity (common)
public class MoxieBlockEntity extends BaseContainerBlockEntity implements ExtendedMenuProvider<BlockPos> {
    public static final int SLOT_INPUT = 0, SLOT_TANK = 1, SLOTS = 2;
    public static final int DATA_PROGRESS = 0, DATA_PROGRESS_MAX = 1, DATA_O2 = 2, DATA_COUNT = 3;   // each value is synced as a SHORT
    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private int progress, oxygen;
    private final ContainerData data = new ContainerData() {
        @Override public int get(int i) { return switch (i) { case DATA_PROGRESS -> progress; case DATA_PROGRESS_MAX -> 200; case DATA_O2 -> oxygen; default -> 0; }; }
        @Override public void set(int i, int v) { switch (i) { case DATA_PROGRESS -> progress = v; case DATA_O2 -> oxygen = v; default -> { } } }
        @Override public int getCount() { return DATA_COUNT; }
    };
    public MoxieBlockEntity(BlockPos pos, BlockState state) { super(RPBlockEntities.MOXIE, pos, state); }

    void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        boolean changed = false;
        // ... electrolysis of CO2 -> O2 (numbers in SCIENCE.md) ...
        if (state.getValue(MoxieBlock.LIT) != (progress > 0)) { level.setBlock(pos, state.setValue(MoxieBlock.LIT, progress > 0), Block.UPDATE_ALL); changed = true; }
        if (changed) setChanged();
    }
    @Override protected Component getDefaultName() { return Component.translatable("container.redplanet.moxie"); }
    @Override protected NonNullList<ItemStack> getItems() { return items; }
    @Override protected void setItems(NonNullList<ItemStack> items) { this.items = items; }
    @Override public int getContainerSize() { return SLOTS; }
    @Override protected AbstractContainerMenu createMenu(int id, Inventory inv) {
        return new MoxieMenu(id, inv, this, data, ContainerLevelAccess.create(level, worldPosition));
    }
    @Override public BlockPos getScreenOpeningData(ServerPlayer player) { return worldPosition; }
    @Override protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(in, items);
        progress = in.getIntOr("progress", 0);
        oxygen = in.getIntOr("oxygen", 0);
    }
    @Override protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        ContainerHelper.saveAllItems(out, items);
        out.putInt("progress", progress);
        out.putInt("oxygen", oxygen);
    }
    // only if a BlockEntityRenderer needs live data:
    // @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    // @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) { return saveCustomOnly(r); }
}

// --- MoxieMenu (common)
public class MoxieMenu extends AbstractContainerMenu {
    private final ContainerData data;
    private final ContainerLevelAccess access;
    /** Client: built by ExtendedMenuType from the synced BlockPos. */
    public MoxieMenu(int id, Inventory inv, BlockPos pos) {
        this(id, inv, new SimpleContainer(MoxieBlockEntity.SLOTS), new SimpleContainerData(MoxieBlockEntity.DATA_COUNT),
            ContainerLevelAccess.create(inv.player.level(), pos));
    }
    /** Server. */
    public MoxieMenu(int id, Inventory inv, Container container, ContainerData data, ContainerLevelAccess access) {
        super(RPMenus.MOXIE, id);
        checkContainerSize(container, MoxieBlockEntity.SLOTS);
        checkContainerDataCount(data, MoxieBlockEntity.DATA_COUNT);
        this.data = data; this.access = access;
        addSlot(new Slot(container, MoxieBlockEntity.SLOT_INPUT, 56, 35));
        addSlot(new Slot(container, MoxieBlockEntity.SLOT_TANK, 116, 35) {
            @Override public boolean mayPlace(ItemStack s) { return s.has(RPComponents.OXYGEN); }
        });
        addStandardInventorySlots(inv, 8, 84);   // menu slots 2..37
        addDataSlots(data);
    }
    @Override public boolean stillValid(Player player) { return stillValid(access, player, RPBlocks.MOXIE); }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        ItemStack moved = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot.hasItem()) {
            ItemStack stack = slot.getItem(); moved = stack.copy();
            int invStart = MoxieBlockEntity.SLOTS, invEnd = invStart + 36;
            if (index < invStart) { if (!moveItemStackTo(stack, invStart, invEnd, true)) return ItemStack.EMPTY; }
            else if (stack.has(RPComponents.OXYGEN)) { if (!moveItemStackTo(stack, MoxieBlockEntity.SLOT_TANK, MoxieBlockEntity.SLOT_TANK + 1, false)) return ItemStack.EMPTY; }
            else if (!moveItemStackTo(stack, MoxieBlockEntity.SLOT_INPUT, MoxieBlockEntity.SLOT_INPUT + 1, false)) return ItemStack.EMPTY;
            if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
            if (stack.getCount() == moved.getCount()) return ItemStack.EMPTY;
            slot.onTake(player, stack);
        }
        return moved;
    }
    public float progress() { int max = data.get(MoxieBlockEntity.DATA_PROGRESS_MAX); return max == 0 ? 0F : (float) data.get(MoxieBlockEntity.DATA_PROGRESS) / max; }
    public int oxygen() { return data.get(MoxieBlockEntity.DATA_O2); }
}

// --- MoxieScreen (client source set)
public class MoxieScreen extends AbstractContainerScreen<MoxieMenu> {
    private static final Identifier BG = RedPlanet.id("textures/gui/container/moxie.png");    // 256x256 canvas, 176x166 used
    private static final Identifier PROGRESS = RedPlanet.id("container/moxie/progress");      // textures/gui/sprites/container/moxie/progress.png (24x16)
    public MoxieScreen(MoxieMenu menu, Inventory inv, Component title) { super(menu, inv, title); }
    @Override public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractBackground(g, mouseX, mouseY, a);
        g.blit(RenderPipelines.GUI_TEXTURED, BG, leftPos, topPos, 0.0F, 0.0F, imageWidth, imageHeight, 256, 256);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PROGRESS, 24, 16, 0, 0, leftPos + 79, topPos + 34, Mth.ceil(menu.progress() * 24.0F), 16);
    }
    @Override protected void extractLabels(GuiGraphicsExtractor g, int xm, int ym) {
        super.extractLabels(g, xm, ym);   // title + "Inventory", coordinates relative to leftPos/topPos
        g.text(font, Component.translatable("gui.redplanet.oxygen", menu.oxygen()), 8, 60, 0xFF404040, false);   // ARGB, alpha must be > 0
    }
}
// RedPlanetClient.onInitializeClient(): MenuScreens.register(RPMenus.MOXIE, MoxieScreen::new);
```

### 15.3 O2 data component and oxygen tank item

```java
public record OxygenContents(int amount, int capacity) implements TooltipProvider {   // amount in litres at STP, defined in SCIENCE.md
    public static final Codec<OxygenContents> CODEC = RecordCodecBuilder.create(i -> i.group(
        ExtraCodecs.NON_NEGATIVE_INT.fieldOf("amount").forGetter(OxygenContents::amount),
        ExtraCodecs.POSITIVE_INT.fieldOf("capacity").forGetter(OxygenContents::capacity)
    ).apply(i, OxygenContents::new));
    public static final StreamCodec<ByteBuf, OxygenContents> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, OxygenContents::amount, ByteBufCodecs.VAR_INT, OxygenContents::capacity, OxygenContents::new);
    public OxygenContents withAmount(int a) { return new OxygenContents(Mth.clamp(a, 0, capacity), capacity); }
    public float fraction() { return capacity == 0 ? 0F : (float) amount / capacity; }
    @Override public void addToTooltip(Item.TooltipContext ctx, Consumer<Component> out, TooltipFlag flag, DataComponentGetter comps) {
        out.accept(Component.translatable("tooltip.redplanet.oxygen", amount, capacity).withStyle(ChatFormatting.AQUA));
    }
}

public final class RPComponents {
    public static final DataComponentType<OxygenContents> OXYGEN = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, RedPlanet.id("oxygen"),
        DataComponentType.<OxygenContents>builder().persistent(OxygenContents.CODEC).networkSynchronized(OxygenContents.STREAM_CODEC)
            .ignoreSwapAnimation()   // no hand bob on every O2 change (FirstPersonHandsAndItems.java:110)
            .build());
    public static void init() { ItemComponentTooltipProviderRegistry.addLast(OXYGEN); }   // otherwise vanilla never prints it
}

public class OxygenTankItem extends Item {                       // class name ends with "Item"
    public OxygenTankItem(Item.Properties p) { super(p); }
    @Override public boolean isBarVisible(ItemStack s) { return s.has(RPComponents.OXYGEN); }
    @Override public int getBarWidth(ItemStack s) { OxygenContents o = s.get(RPComponents.OXYGEN); return o == null ? 0 : Math.round(13F * o.fraction()); }
    @Override public int getBarColor(ItemStack s) { return 0x4FC3F7; }   // RGB; the GUI applies ARGB.opaque
}
// RPItems: OXYGEN_TANK = RPRegistration.item("oxygen_tank", OxygenTankItem::new,
//     new Item.Properties().stacksTo(1).component(RPComponents.OXYGEN, new OxygenContents(0, 1000)));
// update: stack.update(RPComponents.OXYGEN, new OxygenContents(0, 1000), o -> o.withAmount(o.amount() - used));
```

### 15.4 Spacesuit armor material and equipment asset

```java
public final class RPArmorMaterials {
    public static final ResourceKey<EquipmentAsset> SPACESUIT_ASSET = ResourceKey.create(EquipmentAssets.ROOT_ID, RedPlanet.id("spacesuit"));
    public static final TagKey<Item> REPAIRS_SPACESUIT = TagKey.create(Registries.ITEM, RedPlanet.id("repairs_spacesuit"));
    public static final ArmorMaterial SPACESUIT = new ArmorMaterial(
        25,                                                  // durability multiplier (iron 15, diamond 33)
        ArmorMaterials.makeDefense(2, 5, 6, 2, 6),           // boots, legs, chest, helm, body (transitive-accessible)
        10, SoundEvents.ARMOR_EQUIP_IRON, 1.0F, 0.0F, REPAIRS_SPACESUIT, SPACESUIT_ASSET);
}
// RPItems
SPACESUIT_HELMET = RPRegistration.item("spacesuit_helmet", Item::new, new Item.Properties()
    .humanoidArmor(RPArmorMaterials.SPACESUIT, ArmorType.HELMET)
    .component(DataComponents.EQUIPPABLE, Equippable.builder(EquipmentSlot.HEAD)       // replaces the one set by humanoidArmor
        .setEquipSound(SoundEvents.ARMOR_EQUIP_IRON).setAsset(RPArmorMaterials.SPACESUIT_ASSET)
        .setCameraOverlay(RedPlanet.id("misc/spacesuit_visor"))                       // assets/redplanet/textures/misc/spacesuit_visor.png
        .build()));
SPACESUIT_CHESTPLATE = RPRegistration.item("spacesuit_chestplate", Item::new, new Item.Properties().humanoidArmor(RPArmorMaterials.SPACESUIT, ArmorType.CHESTPLATE));
// leggings and boots follow the same pattern
```

`src/main/resources/assets/redplanet/equipment/spacesuit.json` (hand-written; no baby layer, so baby mobs show nothing):

```json
{
  "layers": {
    "humanoid": [ { "texture": "redplanet:spacesuit" } ],
    "humanoid_leggings": [ { "texture": "redplanet:spacesuit" } ]
  }
}
```

Textures: `assets/redplanet/textures/entity/equipment/humanoid/spacesuit.png` (64x32, head/body/arms/boots) and
`.../humanoid_leggings/spacesuit.png` (64x32). Item icons: `assets/redplanet/textures/item/spacesuit_*.png`. Tags: add the
pieces to `minecraft:head_armor` and the other three slot tags, and fill `redplanet:repairs_spacesuit`. For a 3D bubble
helmet, see §5.6 option 1: drop `setAsset(...)` and use a 3D item model with a `display.head` transform and a
`display_context` select.

### 15.5 Custom advancement trigger

```java
public class StarshipLaunchTrigger extends SimpleCriterionTrigger<StarshipLaunchTrigger.TriggerInstance> {
    @Override public Codec<TriggerInstance> codec() { return TriggerInstance.CODEC; }
    public void trigger(ServerPlayer player, ResourceKey<Level> destination) { this.trigger(player, t -> t.matches(destination)); }

    public record TriggerInstance(Optional<Holder<LootItemCondition>> player, Optional<ResourceKey<Level>> destination)
            implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(i -> i.group(
            LootItemCondition.CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
            ResourceKey.codec(Registries.DIMENSION).optionalFieldOf("destination").forGetter(TriggerInstance::destination)
        ).apply(i, TriggerInstance::new));
        public static Criterion<TriggerInstance> launchedTo(ResourceKey<Level> dest) {
            return RPTriggers.STARSHIP_LAUNCH.createCriterion(new TriggerInstance(Optional.empty(), Optional.of(dest)));
        }
        public boolean matches(ResourceKey<Level> dest) { return destination.isEmpty() || destination.get().equals(dest); }
    }
}
public final class RPTriggers {
    public static final StarshipLaunchTrigger STARSHIP_LAUNCH =
        Registry.register(BuiltInRegistries.TRIGGER_TYPES, RedPlanet.id("starship_launch"), new StarshipLaunchTrigger());
    public static void init() {}
}
// fire it (server): RPTriggers.STARSHIP_LAUNCH.trigger(serverPlayer, RPDimensions.MARS);
// JSON use: "criteria": { "launched": { "trigger": "redplanet:starship_launch", "conditions": { "destination": "redplanet:mars" } } }
```

### 15.6 Damage types and the hypoxia effect

`data/redplanet/damage_type/hypoxia.json` and `ebullism.json` (or generate them, §15.8):

```json
{ "message_id": "redplanet.hypoxia", "scaling": "never", "exhaustion": 0.0, "effects": "drowning" }
{ "message_id": "redplanet.ebullism", "scaling": "never", "exhaustion": 0.0, "effects": "hurt" }
```

`data/minecraft/tags/damage_type/bypasses_armor.json`, `bypasses_shield.json`, `no_knockback.json` and `no_impact.json`:
`{ "values": [ "redplanet:hypoxia", "redplanet:ebullism" ] }`. The lang keys are `death.attack.redplanet.hypoxia` and
`death.attack.redplanet.hypoxia.player`.

```java
public final class RPDamageTypes {
    public static final ResourceKey<DamageType> HYPOXIA = ResourceKey.create(Registries.DAMAGE_TYPE, RedPlanet.id("hypoxia"));
    public static final ResourceKey<DamageType> EBULLISM = ResourceKey.create(Registries.DAMAGE_TYPE, RedPlanet.id("ebullism"));
    public static void bootstrap(BootstrapContext<DamageType> ctx) {   // net.minecraft.data.worldgen.BootstrapContext, used by datagen
        ctx.register(HYPOXIA, new DamageType("redplanet.hypoxia", DamageScaling.NEVER, 0.0F, DamageEffects.DROWNING));
        ctx.register(EBULLISM, new DamageType("redplanet.ebullism", DamageScaling.NEVER, 0.0F));
    }
}
public class HypoxiaMobEffect extends MobEffect {
    public HypoxiaMobEffect() { super(MobEffectCategory.HARMFUL, 0x3B4A6B); }
    @Override public boolean shouldApplyEffectTickThisTick(int tickCount, int amp) { int iv = 40 >> amp; return iv <= 0 || tickCount % iv == 0; }
    @Override public boolean applyEffectTick(ServerLevel level, LivingEntity mob, int amp) {
        mob.hurtServer(level, level.damageSources().source(RPDamageTypes.HYPOXIA), 1.0F + amp);
        return true;
    }
}
public final class RPMobEffects {
    public static final Holder<MobEffect> HYPOXIA = register("hypoxia", new HypoxiaMobEffect());
    private static Holder<MobEffect> register(String path, MobEffect effect) {   // MobEffect parameter, so the result is Holder<MobEffect>
        return Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, RedPlanet.id(path), effect);
    }
    public static void init() {}
}
```

### 15.7 Creative tab and sounds

```java
public static final ResourceKey<CreativeModeTab> MAIN_KEY = ResourceKey.create(Registries.CREATIVE_MODE_TAB, RedPlanet.id("main"));
public static void init() {
    Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, MAIN_KEY, FabricCreativeModeTab.builder()
        .title(Component.translatable("itemGroup.redplanet.main"))
        .icon(() -> new ItemStack(RPItems.STARSHIP))
        .displayItems((params, out) -> { out.accept(RPBlocks.REGOLITH); out.accept(RPItems.OXYGEN_TANK); /* ... */ })
        .build());
}
// sounds (a Holder is needed wherever an equip sound is expected)
public static final SoundEvent STARSHIP_LAUNCH = register("starship.launch");
private static SoundEvent register(String path) {
    Identifier id = RedPlanet.id(path);
    return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
}
```

`assets/redplanet/sounds.json`, if hand-written:
`{ "starship.launch": { "subtitle": "subtitles.redplanet.starship.launch", "sounds": [ { "name": "redplanet:starship/launch", "stream": true, "attenuation_distance": 64 } ] } }`.

### 15.8 Datagen entrypoint and providers (client source set, option A)

```java
package io.github.avi130805.redplanet.client.datagen;

public class RedPlanetDataGenerator implements DataGeneratorEntrypoint {
    @Override public void onInitializeDataGenerator(FabricDataGenerator gen) {
        FabricDataGenerator.Pack pack = gen.createPack();
        pack.addProvider(RPModelProvider::new);
        pack.addProvider(RPRecipeProvider::new);
        pack.addProvider(RPBlockLootProvider::new);
        RPBlockTagProvider blockTags = pack.addProvider(RPBlockTagProvider::new);   // must come before the item tags (sequential run)
        pack.addProvider((out, reg) -> new RPItemTagProvider(out, reg, blockTags));
        pack.addProvider(RPDynamicRegistryProvider::new);                         // writes data/redplanet/damage_type/*.json
        pack.addProvider(RPDamageTypeTagProvider::new);
        pack.addProvider(RPEnglishLangProvider::new);
        pack.addProvider(RPAdvancementProvider::new);
        pack.addProvider(RPSoundsProvider::new);
        pack.addProvider(RPEquipmentAssetProvider::new);
    }
    @Override public void buildRegistry(RegistrySetBuilder b) { b.add(Registries.DAMAGE_TYPE, RPDamageTypes::bootstrap); }
}

public class RPModelProvider extends FabricModelProvider {
    public RPModelProvider(FabricPackOutput out) { super(out); }
    @Override public void generateBlockStateModels(BlockModelGenerators g) {
        g.createTrivialCube(RPBlocks.REGOLITH);                                       // textures/block/regolith.png
        g.createTrivialCube(RPBlocks.MARS_SAND);
        g.createTrivialCube(RPBlocks.HEMATITE_ORE);
        g.createTrivialCube(RPBlocks.CO2_ICE);
        g.createTrivialCube(RPBlocks.DUST_BLOCK);
        g.createAxisAlignedPillarBlock(RPBlocks.MARS_BASALT, TexturedModel.COLUMN);   // mars_basalt_side / mars_basalt_top
        g.createFurnace(RPBlocks.MOXIE, TexturedModel.ORIENTABLE_ONLY_TOP);           // moxie_front/_side/_top + moxie_front_on
        dustLayers(g, RPBlocks.DUST_LAYER, RPBlocks.DUST_BLOCK);
    }
    /** Like the private BlockModelGenerators.createSnowBlocks (:3219): children of the vanilla snow_heightN models. */
    private static void dustLayers(BlockModelGenerators g, Block layer, Block full) {
        TextureMapping tex = TextureMapping.defaultTexture(TextureMapping.getBlockTexture(full));   // TEXTURE; PARTICLE falls back to TEXTURE
        Identifier[] models = new Identifier[8];
        for (int level = 1; level <= 7; level++) {
            ModelTemplate t = new ModelTemplate(Optional.of(Identifier.withDefaultNamespace("block/snow_height" + level * 2)),
                Optional.of("_height" + level * 2), TextureSlot.TEXTURE, TextureSlot.PARTICLE);
            models[level - 1] = t.create(layer, tex, g.modelOutput);
        }
        models[7] = ModelLocationUtils.getModelLocation(full);
        g.blockStateOutput.accept(MultiVariantGenerator.dispatch(layer).with(
            PropertyDispatch.<Integer>initial(BlockStateProperties.LAYERS).generate(l -> BlockModelGenerators.plainVariant(models[l - 1]))));
        g.registerSimpleItemModel(layer, models[0]);   // otherwise the auto item model would point at the missing block/dust_layer
    }
    @Override public void generateItemModels(ItemModelGenerators g) {
        g.generateFlatItem(RPItems.STAINLESS_STEEL_INGOT, ModelTemplates.FLAT_ITEM);
        g.generateFlatItem(RPItems.OXYGEN_TANK, ModelTemplates.FLAT_ITEM);
        g.generateFlatItem(RPItems.SPACESUIT_CHESTPLATE, ModelTemplates.FLAT_ITEM);
    }
}

public class RPRecipeProvider extends FabricRecipeProvider {
    public RPRecipeProvider(FabricPackOutput out, CompletableFuture<HolderLookup.Provider> reg) { super(out, reg); }
    @Override protected RecipeProvider createRecipeProvider(HolderLookup.Provider registries, BootstrapContext<Recipe<?>> recipes, BootstrapContext<Advancement> advancements) {
        return new RecipeProvider(recipes, advancements) {
            @Override public void buildRecipes() {
                shaped(RecipeCategory.TOOLS, RPItems.OXYGEN_TANK)
                    .pattern(" S ").pattern("S S").pattern("SSS")
                    .define('S', RPItems.STAINLESS_STEEL_INGOT)
                    .unlockedBy(getHasName(RPItems.STAINLESS_STEEL_INGOT), has(RPItems.STAINLESS_STEEL_INGOT))   // required, or build() throws
                    .save(output);                                               // id: redplanet:oxygen_tank
                oreSmelting(List.of(RPBlocks.HEMATITE_ORE), RecipeCategory.MISC, CookingBookCategory.MISC, Items.IRON_INGOT, 0.7F, 200, "iron_ingot");
                oreBlasting(List.of(RPBlocks.HEMATITE_ORE), RecipeCategory.MISC, CookingBookCategory.MISC, Items.IRON_INGOT, 0.7F, 200, "iron_ingot"); // 200, not 100
            }
        };
    }
    @Override public String getName() { return "Red Planet recipes"; }
}

public class RPBlockLootProvider extends FabricBlockLootSubProvider {
    public RPBlockLootProvider(FabricPackOutput out, CompletableFuture<HolderLookup.Provider> reg) { super(out, reg); }
    @Override public void generate() {
        dropSelf(RPBlocks.REGOLITH); dropSelf(RPBlocks.MARS_SAND); dropSelf(RPBlocks.MARS_BASALT); dropSelf(RPBlocks.DUST_BLOCK);
        add(RPBlocks.HEMATITE_ORE, b -> createOreDrop(b, RPItems.RAW_HEMATITE));
        add(RPBlocks.CO2_ICE, noDrop());
        add(RPBlocks.MOXIE, createNameableBlockEntityTable(RPBlocks.MOXIE));
        // DUST_LAYER: copy the snow table shape (VanillaBlockLoot.java:1192) with MatchBlock + SetItemCountFunction per LAYERS value
    }
}

public class RPBlockTagProvider extends FabricTagsProvider.BlockTagsProvider {
    public RPBlockTagProvider(FabricPackOutput out, CompletableFuture<HolderLookup.Provider> reg) { super(out, reg); }
    @Override protected void addTags(HolderLookup.Provider registries) {
        builder(BlockTags.MINEABLE_WITH_PICKAXE).add(k(RPBlocks.HEMATITE_ORE), k(RPBlocks.MARS_BASALT), k(RPBlocks.MOXIE), k(RPBlocks.CO2_ICE));
        builder(BlockTags.MINEABLE_WITH_SHOVEL).add(k(RPBlocks.REGOLITH), k(RPBlocks.MARS_SAND), k(RPBlocks.DUST_LAYER), k(RPBlocks.DUST_BLOCK));
        builder(BlockTags.NEEDS_STONE_TOOL).add(k(RPBlocks.HEMATITE_ORE));
        builder(RPTags.HEMATITE_ORES.block()).add(k(RPBlocks.HEMATITE_ORE));          // RPTags.HEMATITE_ORES = BlockItemTagId.create(c:ores/hematite, c:ores/hematite)
        builder(ConventionalBlockTags.ORES).addTag(RPTags.HEMATITE_ORES.block());
    }
    static ResourceKey<Block> k(Block b) { return b.builtInRegistryHolder().key(); }
}

public class RPItemTagProvider extends FabricTagsProvider.ItemTagsProvider {
    public RPItemTagProvider(FabricPackOutput out, CompletableFuture<HolderLookup.Provider> reg, RPBlockTagProvider blocks) { super(out, reg, blocks); }
    @Override protected void addTags(HolderLookup.Provider registries) {
        copy(RPTags.HEMATITE_ORES);
        copy(ConventionalBlockItemTags.ORES);
        builder(ItemTags.HEAD_ARMOR).add(k(RPItems.SPACESUIT_HELMET));
        builder(ItemTags.CHEST_ARMOR).add(k(RPItems.SPACESUIT_CHESTPLATE));
        builder(RPArmorMaterials.REPAIRS_SPACESUIT).add(k(RPItems.SPACESUIT_FABRIC));
        builder(RPTags.STAINLESS_STEEL_INGOTS).add(k(RPItems.STAINLESS_STEEL_INGOT));   // c:ingots/stainless_steel
        builder(ConventionalItemTags.INGOTS).addTag(RPTags.STAINLESS_STEEL_INGOTS);
    }
    static ResourceKey<Item> k(Item i) { return i.builtInRegistryHolder().key(); }
}

public class RPDynamicRegistryProvider extends FabricDynamicRegistryProvider {
    public RPDynamicRegistryProvider(FabricPackOutput out, CompletableFuture<HolderLookup.Provider> reg) { super(out, reg); }
    @Override protected void configure(HolderLookup.Provider registries, Entries entries) { entries.addAll(registries.lookupOrThrow(Registries.DAMAGE_TYPE)); }
    @Override public String getName() { return "Red Planet dynamic registries"; }
}

public class RPDamageTypeTagProvider extends FabricTagsProvider<DamageType> {
    public RPDamageTypeTagProvider(FabricPackOutput out, CompletableFuture<HolderLookup.Provider> reg) { super(out, Registries.DAMAGE_TYPE, reg); }
    @Override protected void addTags(HolderLookup.Provider registries) {
        builder(DamageTypeTags.BYPASSES_ARMOR).add(RPDamageTypes.HYPOXIA, RPDamageTypes.EBULLISM);
        builder(DamageTypeTags.BYPASSES_SHIELD).add(RPDamageTypes.HYPOXIA, RPDamageTypes.EBULLISM);
        builder(DamageTypeTags.NO_KNOCKBACK).add(RPDamageTypes.HYPOXIA, RPDamageTypes.EBULLISM);
        builder(DamageTypeTags.NO_IMPACT).add(RPDamageTypes.HYPOXIA);
    }
}

public class RPEnglishLangProvider extends FabricLanguageProvider {
    public RPEnglishLangProvider(FabricPackOutput out, CompletableFuture<HolderLookup.Provider> reg) { super(out, "en_us", reg); }
    @Override public void generateTranslations(HolderLookup.Provider registries, TranslationBuilder t) {
        t.add(RPBlocks.REGOLITH, "Martian Regolith");
        t.add(RPItems.OXYGEN_TANK, "Oxygen Tank");
        t.addCreativeModeTab(RPCreativeTabs.MAIN_KEY, "Red Planet");
        t.add("container.redplanet.moxie", "MOXIE Oxygen Generator");
        t.add("gui.redplanet.oxygen", "O₂: %s L");
        t.add("tooltip.redplanet.oxygen", "Oxygen: %s / %s L");
        t.add("death.attack.redplanet.hypoxia", "%1$s ran out of oxygen");
        t.add("death.attack.redplanet.hypoxia.player", "%1$s ran out of oxygen whilst fighting %2$s");
        t.add(RPMobEffects.HYPOXIA.value(), "Hypoxia");
        t.add(RPSounds.STARSHIP_LAUNCH, "Starship engines roar");             // subtitles.redplanet.starship.launch
        t.add(RPTags.STAINLESS_STEEL_INGOTS, "Stainless Steel Ingots");
        t.add("advancements.redplanet.root.title", "Red Planet");
        t.add("advancements.redplanet.root.description", "Gather Martian regolith");
    }
}

public class RPAdvancementProvider extends FabricAdvancementProvider {
    public RPAdvancementProvider(FabricPackOutput out, CompletableFuture<HolderLookup.Provider> reg) { super(out, reg); }
    @Override public void generateAdvancement(HolderLookup.Provider registries, Consumer<AdvancementHolder> out) {
        AdvancementHolder root = Advancement.Builder.advancement()
            .rootDisplay(RPItems.STARSHIP, Component.translatable("advancements.redplanet.root.title"),
                Component.translatable("advancements.redplanet.root.description"), RedPlanet.id("block/regolith"),
                AdvancementType.TASK, false, false, false)
            .addCriterion("has_regolith", InventoryChangeTrigger.TriggerInstance.hasItems(RPBlocks.REGOLITH))
            .build(RedPlanet.id("root"));
        out.accept(root);
        out.accept(Advancement.Builder.advancement().parent(root)
            .display(RPItems.STARSHIP, Component.translatable("advancements.redplanet.launch.title"),
                Component.translatable("advancements.redplanet.launch.description"), AdvancementType.GOAL, true, true, false)
            .addCriterion("launched", StarshipLaunchTrigger.TriggerInstance.launchedTo(RPDimensions.MARS))
            .build(RedPlanet.id("launch_to_mars")));
        out.accept(Advancement.Builder.advancement().parent(root)
            .display(RPBlocks.REGOLITH.asItem(), Component.translatable("advancements.redplanet.arrive.title"),
                Component.translatable("advancements.redplanet.arrive.description"), AdvancementType.CHALLENGE, true, true, false)
            .addCriterion("arrived", ChangeDimensionTrigger.TriggerInstance.changedDimensionTo(RPDimensions.MARS))
            .build(RedPlanet.id("arrive_on_mars")));
    }
}

public class RPSoundsProvider extends FabricSoundsProvider {
    public RPSoundsProvider(FabricPackOutput out, CompletableFuture<HolderLookup.Provider> reg) { super(out, reg); }
    @Override protected void configure(HolderLookup.Provider registries, SoundExporter exporter) {
        exporter.add(RPSounds.STARSHIP_LAUNCH, SoundTypeBuilder.of(RPSounds.STARSHIP_LAUNCH)
            .sound(SoundTypeBuilder.RegistrationBuilder.ofFile(RedPlanet.id("starship/launch")).stream(true).attenuationDistance(64)));
    }
    @Override public String getName() { return "Red Planet sounds"; }
}

public class RPEquipmentAssetProvider extends FabricCodecDataProvider<EquipmentClientInfo> {
    public RPEquipmentAssetProvider(FabricPackOutput out, CompletableFuture<HolderLookup.Provider> reg) {
        super(out, reg, PackOutput.Target.RESOURCE_PACK, "equipment", EquipmentClientInfo.CODEC);
    }
    @Override protected void configure(BiConsumer<Identifier, EquipmentClientInfo> provider, HolderLookup.Provider registries) {
        Identifier tex = RedPlanet.id("spacesuit");
        provider.accept(RPArmorMaterials.SPACESUIT_ASSET.identifier(), EquipmentClientInfo.builder()
            .addLayers(EquipmentClientInfo.LayerType.HUMANOID, new EquipmentClientInfo.Layer(tex))
            .addLayers(EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS, new EquipmentClientInfo.Layer(tex))
            .build());
    }
    @Override public String getName() { return "Red Planet equipment assets"; }
}
```

### 15.9 `build.gradle` and `fabric.mod.json`

```groovy
fabricApi {
    configureDataGeneration {
        client = true
        modId = "redplanet"
        strictValidation = true
    }
    configureTests {            // unchanged from the current build.gradle
        createSourceSet = true
        modId = "redplanet-gametest"
        enableGameTests = true
        enableClientGameTests = true
        eula = true
    }
}
// run with: ./gradlew runDatagen   (output: src/main/generated, already added to main resources)
```

```json
"entrypoints": {
  "main": [ "io.github.avi130805.redplanet.RedPlanet" ],
  "client": [ "io.github.avi130805.redplanet.client.RedPlanetClient" ],
  "fabric-datagen": [ "io.github.avi130805.redplanet.client.datagen.RedPlanetDataGenerator" ]
}
```

---

## 16. Gotcha checklist

1. Call `setId(key)` before `new Block(...)` or `new Item(...)`, or you get an NPE.
2. A BlockItem without `useBlockDescriptionPrefix()` shows a raw or untranslated name.
3. Every item needs `assets/redplanet/items/<id>.json`. Models alone aren't enough.
4. Non-standard block items, such as layers, need an explicit item model. The auto definition points at `block/<id>`.
5. Text and fill colours in GUIs are ARGB. Alpha 0 text is skipped silently.
6. `ContainerData` values are 16-bit on the wire.
7. Solid block PNGs need alpha 255 everywhere. The render layer is chosen from the pixels.
8. A missing equipment asset renders nothing and logs nothing. Check it with a client gametest screenshot.
9. Loot JSON uses `condition`/`modifier` with `type` keys and predicate refs. Generate it.
10. Datagen `unlockedBy(...)` is required on every recipe builder.
11. Provider names must be unique per pack.
12. Tag elements must exist in the datagen registry lookup. Damage types therefore go through `buildRegistry`.
13. Custom components must be registered before the items that use them. Register custom tooltip components with `ItemComponentTooltipProviderRegistry`.
14. Mod blasting recipes follow vanilla and use cookingtime 200.
15. Random ticks only run with `randomTicks()`.
16. `MobEffect` constructors are protected. Keep the `Holder<MobEffect>` static type.
17. Under split source sets, all model, sound and equipment datagen must live in the client source set, with `client = true`.
18. Don't keep a file at the same path in both `src/main/resources` and `src/main/generated`.

## 17. UNVERIFIED items (confirm with `./gradlew build`, `runDatagen` and gametests)

- Client-mode `runDatagen` works without Xvfb or a GPU. Based on the code path (`MCC/Minecraft.java:451` against 468-535); not executed.
- Client mod initializers run before the client datagen hook. Based on `javap` of Fabric Loader's `EntrypointPatch` (anchor `"Backend library: {}"`); not executed.
- Loom accepts a main `fabric.mod.json` `fabric-datagen` entry that names a class in the `client` source set (option A).
- `runDatagen` with `org.gradle.configuration-cache=true`.
- Duplicate-resource failure between `src/main/resources` and `src/main/generated` (standard Gradle behaviour).
- `ArmorRenderer` usage details beyond the signatures (model layers and baking in 26.3).
- Mono OGG required for positional sound attenuation.
- All skeleton code in §15 (not compiled). Generic inference in `pack.addProvider(X::new)` is ambiguous if a provider class has both constructor shapes.
