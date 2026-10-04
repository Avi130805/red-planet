package io.github.avi130805.redplanet.client.datagen;

import java.util.Optional;

import io.github.avi130805.redplanet.life.RPLifeBlocks;
import io.github.avi130805.redplanet.registry.RPBlocks;
import io.github.avi130805.redplanet.registry.RPItems;
import io.github.avi130805.redplanet.registry.RPMaterials;
import io.github.avi130805.redplanet.registry.RPStarship;
import io.github.avi130805.redplanet.registry.RPSuit;
import io.github.avi130805.redplanet.registry.RPHabitat;
import io.github.avi130805.redplanet.habitat.LedLampBlock;

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
		g.createFurnace(RPSuit.OXYGEN_CONCENTRATOR, TexturedModel.ORIENTABLE_ONLY_TOP);
		g.createFurnace(RPHabitat.HABITAT_REGULATOR, TexturedModel.ORIENTABLE_ONLY_TOP);
		g.createTrivialBlock(RPHabitat.OXYGEN_TANK, TexturedModel.COLUMN);
		g.family(RPHabitat.HABITAT_PANEL).stairs(RPHabitat.HABITAT_PANEL_STAIRS).slab(RPHabitat.HABITAT_PANEL_SLAB)
			.wall(RPHabitat.HABITAT_PANEL_WALL);
		g.createTrivialCube(RPHabitat.HABITAT_WINDOW);
		g.createDoor(RPHabitat.AIRLOCK_DOOR);
		ledLamp(g, RPHabitat.LED_LAMP);
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

		materials(g);
		power(g);
		launchSite(g);
		progress(g);
	}

	/** Stainless steel and the chamber copper alloy. */
	private static void materials(BlockModelGenerators g) {
		g.createTrivialCube(RPMaterials.STAINLESS_STEEL_BLOCK);
	}

	/** Power and ISRU: solar panels, batteries, cables, Kilopower, MOXIE, water extractor, electrolyzer, Sabatier, depot, soil. */
	private static void power(BlockModelGenerators g) {
	}

	/** The launch site: launch mount, launch tower and chopsticks, tank farm. */
	private static void launchSite(BlockModelGenerators g) {
	}

	/** Progression: Starship parts, plaques, the Mars atlas. */
	private static void progress(BlockModelGenerators g) {
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

	/** Lit (the default) and switched-off models for the LED lamp. */
	private static void ledLamp(BlockModelGenerators g, Block lamp) {
		MultiVariant on = BlockModelGenerators.plainVariant(TexturedModel.CUBE.create(lamp, g.modelOutput));
		MultiVariant off = BlockModelGenerators.plainVariant(TexturedModel.CUBE.get(lamp)
			.updateTextures(t -> t.put(TextureSlot.ALL, TextureMapping.getBlockTexture(lamp, "_off"))).createWithSuffix(lamp, "_off", g.modelOutput));
		g.blockStateOutput.accept(MultiVariantGenerator.dispatch(lamp)
			.with(BlockModelGenerators.createBooleanModelDispatch(LedLampBlock.LIT, on, off)));
	}

	@Override
	public void generateItemModels(ItemModelGenerators g) {
		for (Item item : new Item[]{
			RPItems.RAW_HEMATITE, RPItems.HEMATITE_SPHERULES, RPItems.OLIVINE, RPItems.JAROSITE, RPItems.GYPSUM, RPItems.SULFUR_CRYSTALS,
			RPItems.RAW_CHROMITE, RPItems.CHROMIUM_INGOT, RPItems.IRON_NICKEL_CHUNK, RPItems.NICKEL_INGOT, RPItems.NICKEL_NUGGET,
			RPItems.SMECTITE_CLAY_BALL, RPItems.PERCHLORATE_SALT, RPItems.ICE_SHARD, RPItems.DRY_ICE_CHUNK}) {
			g.generateFlatItem(item, ModelTemplates.FLAT_ITEM);
		}
		g.generateFlatItem(RPStarship.STARSHIP_ITEM, ModelTemplates.FLAT_ITEM);
		g.generateFlatItem(RPStarship.SUPER_HEAVY_ITEM, ModelTemplates.FLAT_ITEM);
		for (Item item : new Item[]{RPSuit.SPACESUIT_HELMET, RPSuit.SPACESUIT_TORSO, RPSuit.SPACESUIT_LEGS, RPSuit.SPACESUIT_BOOTS,
			RPSuit.OXYGEN_CANISTER}) {
			g.generateFlatItem(item, ModelTemplates.FLAT_ITEM);
		}

		for (Item item : new Item[]{RPMaterials.STAINLESS_STEEL_INGOT, RPMaterials.STAINLESS_STEEL_SHEET, RPMaterials.GRCOP_INGOT}) {
			g.generateFlatItem(item, ModelTemplates.FLAT_ITEM);
		}
		powerItems(g);
		launchSiteItems(g);
		progressItems(g);
	}

	/** Power and ISRU items. */
	private static void powerItems(ItemModelGenerators g) {
	}

	/** Launch site items. */
	private static void launchSiteItems(ItemModelGenerators g) {
	}

	/** Progression items: Starship parts, the atlas. */
	private static void progressItems(ItemModelGenerators g) {
	}
}
