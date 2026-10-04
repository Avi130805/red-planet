package io.github.avi130805.redplanet.client.datagen;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import io.github.avi130805.redplanet.registry.RPBlocks;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockItemTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/** Tool and family tags for the mod's blocks. */
public class RPBlockTagProvider extends FabricTagsProvider.BlockTagsProvider {
	public RPBlockTagProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, registries);
	}

	@Override
	protected void addTags(HolderLookup.Provider registries) {
		tag(BlockTags.MINEABLE_WITH_SHOVEL, RPBlocks.REGOLITH, RPBlocks.MARS_DUST, RPBlocks.MARS_DUST_LAYER, RPBlocks.BASALTIC_SAND,
			RPBlocks.HEMATITE_SPHERULE_REGOLITH, RPBlocks.ICE_RICH_REGOLITH, RPBlocks.SMECTITE_CLAY, RPBlocks.CO2_FROST);
		tag(BlockTags.MINEABLE_WITH_PICKAXE, RPBlocks.MARS_STONE, RPBlocks.MARS_COBBLESTONE, RPBlocks.MARS_STONE_BRICKS, RPBlocks.MARS_BASALT,
			RPBlocks.POLISHED_MARS_BASALT, RPBlocks.MARS_BASALT_BRICKS, RPBlocks.MUDSTONE, RPBlocks.POLISHED_MUDSTONE, RPBlocks.LAYERED_SEDIMENT,
			RPBlocks.DELTA_SEDIMENT, RPBlocks.CARBONATE_ROCK, RPBlocks.POLAR_WATER_ICE, RPBlocks.WATER_ICE, RPBlocks.POLAR_LAYERED_DEPOSIT,
			RPBlocks.CO2_ICE, RPBlocks.DRY_ICE, RPBlocks.HEMATITE_ORE, RPBlocks.OLIVINE_BASALT, RPBlocks.JAROSITE_ORE, RPBlocks.GYPSUM_VEIN,
			RPBlocks.SULFUR_DEPOSIT, RPBlocks.MARS_CHROMITE_ORE, RPBlocks.IRON_NICKEL_METEORITE, RPBlocks.CHROMITE_ORE,
			RPBlocks.DEEPSLATE_CHROMITE_ORE, RPBlocks.MARS_STONE_STAIRS, RPBlocks.MARS_STONE_SLAB, RPBlocks.MARS_COBBLESTONE_STAIRS,
			RPBlocks.MARS_COBBLESTONE_SLAB, RPBlocks.MARS_COBBLESTONE_WALL, RPBlocks.MARS_STONE_BRICK_STAIRS, RPBlocks.MARS_STONE_BRICK_SLAB,
			RPBlocks.MARS_STONE_BRICK_WALL, RPBlocks.POLISHED_MARS_BASALT_STAIRS, RPBlocks.POLISHED_MARS_BASALT_SLAB,
			RPBlocks.MARS_BASALT_BRICK_STAIRS, RPBlocks.MARS_BASALT_BRICK_SLAB, RPBlocks.MARS_BASALT_BRICK_WALL,
			RPBlocks.POLISHED_MUDSTONE_STAIRS, RPBlocks.POLISHED_MUDSTONE_SLAB, RPBlocks.POLISHED_MUDSTONE_WALL);
		tag(BlockTags.NEEDS_STONE_TOOL, RPBlocks.HEMATITE_ORE, RPBlocks.JAROSITE_ORE, RPBlocks.MARS_CHROMITE_ORE, RPBlocks.CHROMITE_ORE,
			RPBlocks.DEEPSLATE_CHROMITE_ORE, RPBlocks.OLIVINE_BASALT);
		tag(BlockTags.NEEDS_IRON_TOOL, RPBlocks.IRON_NICKEL_METEORITE);
		tag(BlockItemTags.STAIRS.block(), RPBlocks.MARS_STONE_STAIRS, RPBlocks.MARS_COBBLESTONE_STAIRS, RPBlocks.MARS_STONE_BRICK_STAIRS,
			RPBlocks.POLISHED_MARS_BASALT_STAIRS, RPBlocks.MARS_BASALT_BRICK_STAIRS, RPBlocks.POLISHED_MUDSTONE_STAIRS);
		tag(BlockItemTags.SLABS.block(), RPBlocks.MARS_STONE_SLAB, RPBlocks.MARS_COBBLESTONE_SLAB, RPBlocks.MARS_STONE_BRICK_SLAB,
			RPBlocks.POLISHED_MARS_BASALT_SLAB, RPBlocks.MARS_BASALT_BRICK_SLAB, RPBlocks.POLISHED_MUDSTONE_SLAB);
		tag(BlockItemTags.WALLS.block(), RPBlocks.MARS_COBBLESTONE_WALL, RPBlocks.MARS_STONE_BRICK_WALL, RPBlocks.MARS_BASALT_BRICK_WALL,
			RPBlocks.POLISHED_MUDSTONE_WALL);
		tag(BlockTags.ICE, RPBlocks.POLAR_WATER_ICE, RPBlocks.WATER_ICE);
	}

	private void tag(TagKey<Block> tag, Block... blocks) {
		builder(tag).add(Arrays.stream(blocks).map(RPBlockTagProvider::key).toArray(ResourceKey[]::new));
	}

	static ResourceKey<Block> key(Block block) {
		return block.builtInRegistryHolder().key();
	}
}
