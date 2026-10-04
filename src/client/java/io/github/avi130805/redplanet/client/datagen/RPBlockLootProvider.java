package io.github.avi130805.redplanet.client.datagen;

import java.util.concurrent.CompletableFuture;

import io.github.avi130805.redplanet.registry.RPBlocks;
import io.github.avi130805.redplanet.registry.RPItems;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricBlockLootSubProvider;

import net.minecraft.advancements.predicates.StatePropertiesPredicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.AlternativesEntry;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.predicates.MatchBlock;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders;

/** Block loot tables. */
public class RPBlockLootProvider extends FabricBlockLootSubProvider {
	public RPBlockLootProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, registries);
	}

	@Override
	public void generate() {
		for (Block block : new Block[]{
			RPBlocks.REGOLITH, RPBlocks.MARS_DUST, RPBlocks.BASALTIC_SAND, RPBlocks.ICE_RICH_REGOLITH, RPBlocks.MARS_COBBLESTONE,
			RPBlocks.MARS_STONE_BRICKS, RPBlocks.MARS_BASALT, RPBlocks.POLISHED_MARS_BASALT, RPBlocks.MARS_BASALT_BRICKS, RPBlocks.MUDSTONE,
			RPBlocks.POLISHED_MUDSTONE, RPBlocks.LAYERED_SEDIMENT, RPBlocks.DELTA_SEDIMENT, RPBlocks.CARBONATE_ROCK,
			RPBlocks.POLAR_LAYERED_DEPOSIT, RPBlocks.MARS_STONE_STAIRS, RPBlocks.MARS_COBBLESTONE_STAIRS, RPBlocks.MARS_COBBLESTONE_WALL,
			RPBlocks.MARS_STONE_BRICK_STAIRS, RPBlocks.MARS_STONE_BRICK_WALL, RPBlocks.POLISHED_MARS_BASALT_STAIRS,
			RPBlocks.MARS_BASALT_BRICK_STAIRS, RPBlocks.MARS_BASALT_BRICK_WALL, RPBlocks.POLISHED_MUDSTONE_STAIRS,
			RPBlocks.POLISHED_MUDSTONE_WALL}) {
			dropSelf(block);
		}
		for (Block slab : new Block[]{RPBlocks.MARS_STONE_SLAB, RPBlocks.MARS_COBBLESTONE_SLAB, RPBlocks.MARS_STONE_BRICK_SLAB,
			RPBlocks.POLISHED_MARS_BASALT_SLAB, RPBlocks.MARS_BASALT_BRICK_SLAB, RPBlocks.POLISHED_MUDSTONE_SLAB}) {
			add(slab, this::createSlabItemTable);
		}

		add(RPBlocks.MARS_STONE, block -> createSingleItemTableWithSilkTouch(block, RPBlocks.MARS_COBBLESTONE));
		add(RPBlocks.HEMATITE_SPHERULE_REGOLITH, block -> createSingleItemTableWithSilkTouch(block, RPItems.HEMATITE_SPHERULES,
			ContextIntProviders.between(1, 3)));
		add(RPBlocks.SMECTITE_CLAY, block -> createSingleItemTableWithSilkTouch(block, RPItems.SMECTITE_CLAY_BALL, ContextIntProviders.exactly(4)));

		// Ice: a water (or CO2) resource on Mars, so it breaks into shards rather than vanishing like vanilla ice.
		add(RPBlocks.POLAR_WATER_ICE, block -> createSingleItemTableWithSilkTouch(block, RPItems.ICE_SHARD, ContextIntProviders.between(2, 4)));
		add(RPBlocks.WATER_ICE, block -> createSingleItemTableWithSilkTouch(block, RPItems.ICE_SHARD, ContextIntProviders.between(2, 4)));
		add(RPBlocks.CO2_ICE, block -> createSingleItemTableWithSilkTouch(block, RPItems.DRY_ICE_CHUNK, ContextIntProviders.between(2, 4)));
		add(RPBlocks.DRY_ICE, block -> createSingleItemTableWithSilkTouch(block, RPItems.DRY_ICE_CHUNK, ContextIntProviders.between(2, 4)));
		add(RPBlocks.CO2_FROST, noDrop());
		add(RPBlocks.MARS_DUST_LAYER, this::layerDrops);

		add(RPBlocks.HEMATITE_ORE, block -> createOreDrop(block, RPItems.RAW_HEMATITE));
		add(RPBlocks.OLIVINE_BASALT, block -> createOreDrop(block, RPItems.OLIVINE));
		add(RPBlocks.JAROSITE_ORE, block -> createOreDrop(block, RPItems.JAROSITE));
		add(RPBlocks.GYPSUM_VEIN, block -> createOreDrop(block, RPItems.GYPSUM));
		add(RPBlocks.SULFUR_DEPOSIT, block -> createOreDrop(block, RPItems.SULFUR_CRYSTALS));
		add(RPBlocks.MARS_CHROMITE_ORE, block -> createOreDrop(block, RPItems.RAW_CHROMITE));
		add(RPBlocks.CHROMITE_ORE, block -> createOreDrop(block, RPItems.RAW_CHROMITE));
		add(RPBlocks.DEEPSLATE_CHROMITE_ORE, block -> createOreDrop(block, RPItems.RAW_CHROMITE));
		add(RPBlocks.IRON_NICKEL_METEORITE, block -> createSingleItemTableWithSilkTouch(block, RPItems.IRON_NICKEL_CHUNK,
			ContextIntProviders.between(2, 4)));
	}

	/** Like vanilla snow layers: one layer item per layer. */
	private LootTable.Builder layerDrops(Block block) {
		return LootTable.lootTable().withPool(LootPool.lootPool().add(AlternativesEntry.alternatives(SnowLayerBlock.LAYERS.getPossibleValues(),
			layers -> LootItem.lootTableItem(block)
				.when(MatchBlock.blockMatches(this.blocks, block, StatePropertiesPredicate.Builder.properties().hasProperty(SnowLayerBlock.LAYERS,
					layers.intValue())))
				.apply(SetItemCountFunction.setCount(ContextIntProviders.exactly(layers))))));
	}
}
