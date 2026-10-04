package io.github.avi130805.redplanet.client.datagen;

import java.util.Optional;

import io.github.avi130805.redplanet.life.RPLifeBlocks;
import io.github.avi130805.redplanet.registry.RPBlocks;
import io.github.avi130805.redplanet.registry.RPItems;

import net.fabricmc.fabric.api.client.datagen.v1.provider.FabricModelProvider;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;

import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.MultiVariant;
import net.minecraft.client.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.client.data.models.blockstates.PropertyDispatch;
import net.minecraft.client.data.models.model.ModelLocationUtils;
import net.minecraft.client.data.models.model.ModelTemplate;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.data.models.model.TexturedModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Block states, block models and item definitions. Textures: {@code tools/textures/gen_textures.py}. */
public class RPModelProvider extends FabricModelProvider {
	public RPModelProvider(FabricPackOutput output) {
		super(output);
	}

	@Override
	public void generateBlockStateModels(BlockModelGenerators g) {
		for (Block block : new Block[]{
			RPBlocks.REGOLITH, RPBlocks.MARS_DUST, RPBlocks.BASALTIC_SAND, RPBlocks.HEMATITE_SPHERULE_REGOLITH, RPBlocks.ICE_RICH_REGOLITH,
			RPBlocks.MUDSTONE, RPBlocks.DELTA_SEDIMENT, RPBlocks.SMECTITE_CLAY, RPBlocks.CARBONATE_ROCK,
			RPBlocks.POLAR_WATER_ICE, RPBlocks.WATER_ICE, RPBlocks.CO2_ICE, RPBlocks.DRY_ICE,
			RPBlocks.HEMATITE_ORE, RPBlocks.OLIVINE_BASALT, RPBlocks.JAROSITE_ORE, RPBlocks.GYPSUM_VEIN, RPBlocks.SULFUR_DEPOSIT,
			RPBlocks.MARS_CHROMITE_ORE, RPBlocks.IRON_NICKEL_METEORITE, RPBlocks.CHROMITE_ORE, RPBlocks.DEEPSLATE_CHROMITE_ORE}) {
			g.createTrivialCube(block);
		}
		g.createAxisAlignedPillarBlock(RPBlocks.MARS_BASALT, TexturedModel.COLUMN);
		g.createAxisAlignedPillarBlock(RPBlocks.LAYERED_SEDIMENT, TexturedModel.COLUMN);
		g.createAxisAlignedPillarBlock(RPBlocks.POLAR_LAYERED_DEPOSIT, TexturedModel.COLUMN);

		g.family(RPBlocks.MARS_STONE).stairs(RPBlocks.MARS_STONE_STAIRS).slab(RPBlocks.MARS_STONE_SLAB);
		g.family(RPBlocks.MARS_COBBLESTONE).stairs(RPBlocks.MARS_COBBLESTONE_STAIRS).slab(RPBlocks.MARS_COBBLESTONE_SLAB)
			.wall(RPBlocks.MARS_COBBLESTONE_WALL);
		g.family(RPBlocks.MARS_STONE_BRICKS).stairs(RPBlocks.MARS_STONE_BRICK_STAIRS).slab(RPBlocks.MARS_STONE_BRICK_SLAB)
			.wall(RPBlocks.MARS_STONE_BRICK_WALL);
		g.family(RPBlocks.POLISHED_MARS_BASALT).stairs(RPBlocks.POLISHED_MARS_BASALT_STAIRS).slab(RPBlocks.POLISHED_MARS_BASALT_SLAB);
		g.family(RPBlocks.MARS_BASALT_BRICKS).stairs(RPBlocks.MARS_BASALT_BRICK_STAIRS).slab(RPBlocks.MARS_BASALT_BRICK_SLAB)
			.wall(RPBlocks.MARS_BASALT_BRICK_WALL);
		g.family(RPBlocks.POLISHED_MUDSTONE).stairs(RPBlocks.POLISHED_MUDSTONE_STAIRS).slab(RPBlocks.POLISHED_MUDSTONE_SLAB)
			.wall(RPBlocks.POLISHED_MUDSTONE_WALL);

		lifeBlocks(g);

		layers(g, RPBlocks.MARS_DUST_LAYER, TextureMapping.getBlockTexture(RPBlocks.MARS_DUST),
			BlockModelGenerators.plainVariant(ModelLocationUtils.getModelLocation(RPBlocks.MARS_DUST)));
		Identifier frostFull = ModelTemplates.CUBE_ALL.createWithSuffix(RPBlocks.CO2_FROST, "_full",
			TextureMapping.cube(RPBlocks.CO2_FROST), g.modelOutput);
		layers(g, RPBlocks.CO2_FROST, TextureMapping.getBlockTexture(RPBlocks.CO2_FROST), BlockModelGenerators.plainVariant(frostFull));
	}

	/** The native cave life of the fiction layer (DESIGN.md section 8.4), modelled like its vanilla counterparts. */
	private static void lifeBlocks(BlockModelGenerators g) {
		// Areolichen is glow lichen's shape: the multiface model is hand-written (models/block/areolichen.json).
		g.createMultiface(RPLifeBlocks.AREOLICHEN);
		g.createFullAndCarpetBlocks(RPLifeBlocks.EMBER_MOSS, RPLifeBlocks.EMBER_MOSS_CARPET);
		g.createPlantWithDefaultItem(RPLifeBlocks.RUSTCAP_FUNGUS, RPLifeBlocks.POTTED_RUSTCAP_FUNGUS, BlockModelGenerators.PlantType.NOT_TINTED);
		g.createPlantWithDefaultItem(RPLifeBlocks.RIME_BLOOM, RPLifeBlocks.POTTED_RIME_BLOOM, BlockModelGenerators.PlantType.NOT_TINTED);

		g.woodProvider(RPLifeBlocks.RUSTCAP_STEM).log(RPLifeBlocks.RUSTCAP_STEM).wood(RPLifeBlocks.RUSTCAP_HYPHAE);
		g.woodProvider(RPLifeBlocks.STRIPPED_RUSTCAP_STEM).log(RPLifeBlocks.STRIPPED_RUSTCAP_STEM).wood(RPLifeBlocks.STRIPPED_RUSTCAP_HYPHAE);
		g.createTrivialCube(RPLifeBlocks.RUSTCAP_CAP);
		g.createTrivialCube(RPLifeBlocks.RUSTCAP_GILLS);
		g.family(RPLifeBlocks.RUSTCAP_PLANKS).generateFor(RPBlockFamilies.RUSTCAP);

		g.createTrivialCube(RPLifeBlocks.PERCHLORATE_CRUST);
		g.createSpeleothem(RPLifeBlocks.SALT_SPIRE);
		g.registerSimpleFlatItemModel(RPLifeBlocks.SALT_SPIRE.asItem());
		// Selenite's texture is partly transparent, so 26.3 puts it in the translucent layer by itself.
		g.createTrivialCube(RPLifeBlocks.SELENITE_BLOCK);
		g.createAmethystCluster(RPLifeBlocks.SELENITE_CLUSTER);
		g.registerSimpleFlatItemModel(RPLifeBlocks.SELENITE_CLUSTER);
	}

	/**
	 * Snow-like layer blocks (the private {@code createSnowBlocks} pattern): children of vanilla's {@code snow_heightN}
	 * models with this block's texture, and a full block at 8 layers.
	 */
	private static void layers(BlockModelGenerators g, Block layer, Material texture, MultiVariant full) {
		TextureMapping textures = new TextureMapping().put(TextureSlot.TEXTURE, texture).put(TextureSlot.PARTICLE, texture);
		Identifier[] heights = new Identifier[7];
		for (int level = 1; level <= 7; level++) {
			ModelTemplate template = new ModelTemplate(Optional.of(Identifier.withDefaultNamespace("block/snow_height" + level * 2)),
				Optional.of("_height" + level * 2), TextureSlot.TEXTURE, TextureSlot.PARTICLE);
			heights[level - 1] = template.create(layer, textures, g.modelOutput);
		}
		g.blockStateOutput.accept(MultiVariantGenerator.dispatch(layer).with(PropertyDispatch.<Integer>initial(BlockStateProperties.LAYERS)
			.generate(level -> level < 8 ? BlockModelGenerators.plainVariant(heights[level - 1]) : full)));
		g.registerSimpleItemModel(layer, heights[0]);
	}

	@Override
	public void generateItemModels(ItemModelGenerators g) {
		for (Item item : new Item[]{
			RPItems.RAW_HEMATITE, RPItems.HEMATITE_SPHERULES, RPItems.OLIVINE, RPItems.JAROSITE, RPItems.GYPSUM, RPItems.SULFUR_CRYSTALS,
			RPItems.RAW_CHROMITE, RPItems.CHROMIUM_INGOT, RPItems.IRON_NICKEL_CHUNK, RPItems.NICKEL_INGOT, RPItems.NICKEL_NUGGET,
			RPItems.SMECTITE_CLAY_BALL, RPItems.PERCHLORATE_SALT, RPItems.ICE_SHARD, RPItems.DRY_ICE_CHUNK}) {
			g.generateFlatItem(item, ModelTemplates.FLAT_ITEM);
		}
	}
}
