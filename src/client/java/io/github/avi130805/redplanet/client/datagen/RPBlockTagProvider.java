package io.github.avi130805.redplanet.client.datagen;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.life.RPLifeBlocks;
import io.github.avi130805.redplanet.registry.RPBlocks;
import io.github.avi130805.redplanet.registry.RPHabitat;
import io.github.avi130805.redplanet.registry.RPMaterials;
import io.github.avi130805.redplanet.registry.RPSuit;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockItemTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SpeleothemBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;

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
		tag(BlockTags.MINEABLE_WITH_PICKAXE, RPSuit.OXYGEN_CONCENTRATOR, RPHabitat.HABITAT_REGULATOR, RPHabitat.OXYGEN_TANK, RPHabitat.HABITAT_PANEL,
			RPHabitat.HABITAT_PANEL_STAIRS, RPHabitat.HABITAT_PANEL_SLAB, RPHabitat.HABITAT_PANEL_WALL, RPHabitat.HABITAT_WINDOW,
			RPHabitat.AIRLOCK_DOOR, RPHabitat.LED_LAMP);
		tag(BlockTags.DOORS, RPHabitat.AIRLOCK_DOOR);
		tag(BlockItemTags.STAIRS.block(), RPHabitat.HABITAT_PANEL_STAIRS);
		tag(BlockItemTags.SLABS.block(), RPHabitat.HABITAT_PANEL_SLAB);
		tag(BlockItemTags.WALLS.block(), RPHabitat.HABITAT_PANEL_WALL);
		tag(BlockItemTags.STAIRS.block(), RPBlocks.MARS_STONE_STAIRS, RPBlocks.MARS_COBBLESTONE_STAIRS, RPBlocks.MARS_STONE_BRICK_STAIRS,
			RPBlocks.POLISHED_MARS_BASALT_STAIRS, RPBlocks.MARS_BASALT_BRICK_STAIRS, RPBlocks.POLISHED_MUDSTONE_STAIRS);
		tag(BlockItemTags.SLABS.block(), RPBlocks.MARS_STONE_SLAB, RPBlocks.MARS_COBBLESTONE_SLAB, RPBlocks.MARS_STONE_BRICK_SLAB,
			RPBlocks.POLISHED_MARS_BASALT_SLAB, RPBlocks.MARS_BASALT_BRICK_SLAB, RPBlocks.POLISHED_MUDSTONE_SLAB);
		tag(BlockItemTags.WALLS.block(), RPBlocks.MARS_COBBLESTONE_WALL, RPBlocks.MARS_STONE_BRICK_WALL, RPBlocks.MARS_BASALT_BRICK_WALL,
			RPBlocks.POLISHED_MUDSTONE_WALL);
		tag(BlockTags.ICE, RPBlocks.POLAR_WATER_ICE, RPBlocks.WATER_ICE);

		lifeBlocks();

		tag(BlockTags.MINEABLE_WITH_PICKAXE, RPMaterials.STAINLESS_STEEL_BLOCK);
		tag(BlockTags.NEEDS_IRON_TOOL, RPMaterials.STAINLESS_STEEL_BLOCK);
		power();
		launchSite();
		progress();
		creatures();
		arean();
		endgame();

		// 26.3 replaced BlockState#blocksMotion with this tag: heightmaps (MOTION_BLOCKING) and everything built on
		// them only see blocks in it. Every solid Mars block goes in. Thin layers (dust, CO2 frost), carpets, plants,
		// lichen, buttons and pots stay out, as their vanilla counterparts do. Stairs, slabs, walls, fences, gates,
		// doors, trapdoors, pressure plates, logs, planks and speleothems arrive through their family tags.
		Set<Block> notMotionBlocking = Set.of(RPBlocks.MARS_DUST_LAYER, RPBlocks.CO2_FROST, RPLifeBlocks.AREOLICHEN,
			RPLifeBlocks.EMBER_MOSS_CARPET, RPLifeBlocks.RUSTCAP_FUNGUS, RPLifeBlocks.RIME_BLOOM, RPLifeBlocks.RUSTCAP_BUTTON,
			RPLifeBlocks.POTTED_RUSTCAP_FUNGUS, RPLifeBlocks.POTTED_RIME_BLOOM);
		builder(BlockTags.BLOCKS_MOTION_NO_LEAVES).add(BuiltInRegistries.BLOCK.stream()
			.filter(b -> BuiltInRegistries.BLOCK.getKey(b).getNamespace().equals(RedPlanet.MOD_ID))
			.filter(b -> !notMotionBlocking.contains(b))
			.filter(b -> !(b instanceof StairBlock) && !(b instanceof SlabBlock) && !(b instanceof WallBlock) && !(b instanceof FenceBlock)
				&& !(b instanceof FenceGateBlock) && !(b instanceof DoorBlock) && !(b instanceof TrapDoorBlock)
				&& !(b instanceof PressurePlateBlock) && !(b instanceof SpeleothemBlock))
			.map(RPBlockTagProvider::key)
			.toArray(ResourceKey[]::new));
	}

	/** Creatures (fiction): rimeback, lumen moth, brine eel, dust stalker, dust wraith, and their items. */
	private void creatures() {
	}

	/** The Areans (fiction): ruins, camps, sanctums, vaults, the Cydonia gate, custodians, sentries, relics, Arean blocks. */
	private void arean() {
	}

	/** The endgame (fiction): Phobos and Deimos, the Heart of Ares, terraforming, Arean alloy, the thruster pack, tonics. */
	private void endgame() {
	}

	/** Power and ISRU: solar panels, batteries, cables, Kilopower, MOXIE, water extractor, electrolyzer, Sabatier, depot, soil. */
	private void power() {
	}

	/** The launch site: launch mount, launch tower and chopsticks, tank farm. */
	private void launchSite() {
	}

	/** Progression: Starship parts, plaques, the Mars atlas. */
	private void progress() {
	}

	/** Native cave life (fiction layer): the rustcap wood joins the vanilla wood tags, so vanilla recipes accept it. */
	private void lifeBlocks() {
		tag(RPLifeBlocks.RUSTCAP_STEMS.block(), RPLifeBlocks.RUSTCAP_STEM, RPLifeBlocks.STRIPPED_RUSTCAP_STEM, RPLifeBlocks.RUSTCAP_HYPHAE,
			RPLifeBlocks.STRIPPED_RUSTCAP_HYPHAE);
		builder(BlockItemTags.LOGS.block()).addTag(RPLifeBlocks.RUSTCAP_STEMS.block());
		tag(BlockItemTags.PLANKS.block(), RPLifeBlocks.RUSTCAP_PLANKS);
		tag(BlockItemTags.WOODEN_STAIRS.block(), RPLifeBlocks.RUSTCAP_STAIRS);
		tag(BlockItemTags.WOODEN_SLABS.block(), RPLifeBlocks.RUSTCAP_SLAB);
		tag(BlockItemTags.WOODEN_FENCES.block(), RPLifeBlocks.RUSTCAP_FENCE);
		tag(BlockItemTags.FENCE_GATES.block(), RPLifeBlocks.RUSTCAP_FENCE_GATE);
		tag(BlockItemTags.WOODEN_DOORS.block(), RPLifeBlocks.RUSTCAP_DOOR);
		tag(BlockItemTags.WOODEN_TRAPDOORS.block(), RPLifeBlocks.RUSTCAP_TRAPDOOR);
		tag(BlockItemTags.WOODEN_PRESSURE_PLATES.block(), RPLifeBlocks.RUSTCAP_PRESSURE_PLATE);
		tag(BlockItemTags.WOODEN_BUTTONS.block(), RPLifeBlocks.RUSTCAP_BUTTON);
		tag(RPLifeBlocks.SUPPORTS_RUSTCAP, RPLifeBlocks.EMBER_MOSS);

		// Salt spires must be speleothems: SpeleothemBlock checks the tag to stack, grow and fall (and the tag brings the
		// pickaxe and motion-blocking tags with it).
		tag(BlockTags.SPELEOTHEMS, RPLifeBlocks.SALT_SPIRE);
		tag(BlockTags.MINEABLE_WITH_HOE, RPLifeBlocks.EMBER_MOSS, RPLifeBlocks.EMBER_MOSS_CARPET, RPLifeBlocks.RUSTCAP_CAP,
			RPLifeBlocks.RUSTCAP_GILLS);
		tag(BlockTags.MINEABLE_WITH_AXE, RPLifeBlocks.AREOLICHEN);
		tag(BlockTags.MINEABLE_WITH_PICKAXE, RPLifeBlocks.PERCHLORATE_CRUST, RPLifeBlocks.SELENITE_BLOCK, RPLifeBlocks.SELENITE_CLUSTER);
		tag(BlockTags.FLOWER_POTS, RPLifeBlocks.POTTED_RUSTCAP_FUNGUS, RPLifeBlocks.POTTED_RIME_BLOOM);
		tag(BlockTags.IMPERMEABLE, RPLifeBlocks.SELENITE_BLOCK);
	}

	private void tag(TagKey<Block> tag, Block... blocks) {
		builder(tag).add(Arrays.stream(blocks).map(RPBlockTagProvider::key).toArray(ResourceKey[]::new));
	}

	static ResourceKey<Block> key(Block block) {
		return block.builtInRegistryHolder().key();
	}
}
