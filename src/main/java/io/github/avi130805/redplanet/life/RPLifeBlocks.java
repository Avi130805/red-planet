package io.github.avi130805.redplanet.life;

import static io.github.avi130805.redplanet.registry.RPRegistration.block;
import static io.github.avi130805.redplanet.registry.RPRegistration.blockWithItem;

import io.github.avi130805.redplanet.RedPlanet;

import net.fabricmc.fabric.api.item.v1.BlockTransformerHelper;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockItemTagId;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.GlowLichenBlock;
import net.minecraft.world.level.block.NetherFungusBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/**
 * Mars' native cave life: FICTION (DESIGN.md section 8.4). No life has been found on Mars; these exist only in the
 * underground ecosystems of the fiction layer, and they obey the physics: nothing here burns, and light comes from
 * chemiluminescence instead of fire.
 */
public final class RPLifeBlocks {
	/** Rustcap stems and hyphae, stripped or not (block and item tags; part of #minecraft:logs, but not logs_that_burn). */
	public static final BlockItemTagId RUSTCAP_STEMS = BlockItemTagId.create(RedPlanet.id("rustcap_stems"), RedPlanet.id("rustcap_stems"));
	/** Blocks a rustcap fungus will grow on (ember moss). */
	public static final TagKey<Block> SUPPORTS_RUSTCAP = TagKey.create(Registries.BLOCK, RedPlanet.id("supports_rustcap"));
	/** The configured huge-fungus feature a bonemealed rustcap grows into. */
	public static final ResourceKey<Feature> RUSTCAP_PLANTED = ResourceKey.create(Registries.FEATURE, RedPlanet.id("rustcap_planted"));

	// ----------------------------------------------------------------------------------------------- light

	/** Glowing lichen crust: the main light source in the lava tubes. */
	public static final Block AREOLICHEN = blockWithItem("areolichen", GlowLichenBlock::new, BlockBehaviour.Properties.of()
		.mapColor(MapColor.COLOR_LIGHT_GREEN).replaceable().noCollision().strength(0.2F).sound(SoundType.GLOW_LICHEN)
		.lightLevel(GlowLichenBlock.emission(10)).pushReaction(PushReaction.POPPED));
	/** Warm-glowing moss over the cave floor; rustcaps root in it. */
	public static final Block EMBER_MOSS = blockWithItem("ember_moss", Block::new, BlockBehaviour.Properties.of()
		.mapColor(MapColor.COLOR_ORANGE).strength(0.1F).sound(SoundType.MOSS).lightLevel(state -> 4));
	public static final Block EMBER_MOSS_CARPET = blockWithItem("ember_moss_carpet", CarpetBlock::new, BlockBehaviour.Properties.of()
		.mapColor(MapColor.COLOR_ORANGE).strength(0.1F).sound(SoundType.MOSS_CARPET).lightLevel(state -> 5).pushReaction(PushReaction.POPPED));

	// --------------------------------------------------------------------------------------------- rustcap

	/** A rustcap sprout; bonemeal it on ember moss and it grows into a giant fungus. */
	public static final Block RUSTCAP_FUNGUS = blockWithItem("rustcap_fungus",
		p -> new NetherFungusBlock(RUSTCAP_PLANTED, EMBER_MOSS, SUPPORTS_RUSTCAP, p), BlockBehaviour.Properties.of()
			.mapColor(MapColor.COLOR_ORANGE).instabreak().noCollision().sound(SoundType.FUNGUS).pushReaction(PushReaction.POPPED));
	/** Rustcap stem: Mars' wood. Like the Nether's, it doesn't burn. */
	public static final Block RUSTCAP_STEM = blockWithItem("rustcap_stem", RotatedPillarBlock::new, stem());
	public static final Block STRIPPED_RUSTCAP_STEM = blockWithItem("stripped_rustcap_stem", RotatedPillarBlock::new, stem());
	public static final Block RUSTCAP_HYPHAE = blockWithItem("rustcap_hyphae", RotatedPillarBlock::new, stem());
	public static final Block STRIPPED_RUSTCAP_HYPHAE = blockWithItem("stripped_rustcap_hyphae", RotatedPillarBlock::new, stem());
	public static final Block RUSTCAP_CAP = blockWithItem("rustcap_cap", Block::new, BlockBehaviour.Properties.of()
		.mapColor(MapColor.CRIMSON_HYPHAE).strength(1.0F).sound(SoundType.WART_BLOCK));
	/** The glowing gills under the cap (the fungus' light, like shroomlight). */
	public static final Block RUSTCAP_GILLS = blockWithItem("rustcap_gills", Block::new, BlockBehaviour.Properties.of()
		.mapColor(MapColor.COLOR_YELLOW).strength(1.0F).sound(SoundType.SHROOMLIGHT).lightLevel(state -> 13));
	public static final Block RUSTCAP_PLANKS = blockWithItem("rustcap_planks", Block::new, planks());
	public static final Block RUSTCAP_STAIRS = blockWithItem("rustcap_stairs", p -> new StairBlock(RUSTCAP_PLANKS.defaultBlockState(), p), planks());
	public static final Block RUSTCAP_SLAB = blockWithItem("rustcap_slab", SlabBlock::new, planks());
	public static final Block RUSTCAP_FENCE = blockWithItem("rustcap_fence", FenceBlock::new, planks().forceSolidOn());
	public static final Block RUSTCAP_FENCE_GATE = blockWithItem("rustcap_fence_gate", p -> new FenceGateBlock(WoodType.CRIMSON, p),
		planks().forceSolidOn());
	public static final Block RUSTCAP_DOOR = blockWithItem("rustcap_door", p -> new DoorBlock(BlockSetType.CRIMSON, p),
		planks().strength(3.0F).noOcclusion().pushReaction(PushReaction.POPPED));
	public static final Block RUSTCAP_TRAPDOOR = blockWithItem("rustcap_trapdoor", p -> new TrapDoorBlock(BlockSetType.CRIMSON, p),
		planks().strength(3.0F).noOcclusion().isValidSpawn((state, level, pos, type) -> false));
	public static final Block RUSTCAP_PRESSURE_PLATE = blockWithItem("rustcap_pressure_plate",
		p -> new PressurePlateBlock(BlockSetType.CRIMSON, p), planks().forceSolidOn().noCollision().strength(0.5F).pushReaction(PushReaction.POPPED));
	public static final Block RUSTCAP_BUTTON = blockWithItem("rustcap_button", p -> new ButtonBlock(BlockSetType.CRIMSON, 30, p),
		BlockBehaviour.Properties.of().noCollision().strength(0.5F).pushReaction(PushReaction.POPPED));

	// ------------------------------------------------------------------------------------------- ice caves

	/** Crystalline frost flowers with a cold bioluminescent glow: the brine grottoes' lamps. */
	public static final Block RIME_BLOOM = blockWithItem("rime_bloom", RimeBloomBlock::new, BlockBehaviour.Properties.of()
		.mapColor(MapColor.ICE).noCollision().instabreak().sound(SoundType.AMETHYST_CLUSTER).lightLevel(state -> 7)
		.offsetType(BlockBehaviour.OffsetType.XZ).pushReaction(PushReaction.POPPED));
	/** A crust of perchlorate salts left by evaporating brine (real chemistry, ~0.5 wt% in Phoenix soil). */
	public static final Block PERCHLORATE_CRUST = blockWithItem("perchlorate_crust", Block::new, BlockBehaviour.Properties.of()
		.mapColor(MapColor.TERRACOTTA_WHITE).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.0F)
		.sound(SoundType.CALCITE));
	public static final Block SALT_SPIRE = blockWithItem("salt_spire", p -> new SaltSpireBlock(PERCHLORATE_CRUST.defaultBlockState(), p),
		BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).forceSolidOn().instrument(NoteBlockInstrument.BASEDRUM).noOcclusion()
			.sound(SoundType.CALCITE).randomTicks().strength(1.0F, 3.0F).dynamicShape().offsetType(BlockBehaviour.OffsetType.XZ)
			.pushReaction(PushReaction.POPPED));

	// ------------------------------------------------------------------------------------------------ pots

	public static final Block POTTED_RUSTCAP_FUNGUS = block("potted_rustcap_fungus", p -> new FlowerPotBlock(RUSTCAP_FUNGUS, p),
		Blocks.flowerPotProperties());
	public static final Block POTTED_RIME_BLOOM = block("potted_rime_bloom", p -> new FlowerPotBlock(RIME_BLOOM, p),
		Blocks.flowerPotProperties().lightLevel(state -> 7));

	// ---------------------------------------------------------------------------------------------- geodes

	/** Selenite: clear gypsum crystal (CaSO4.2H2O), like the giant crystals of Naica; it lets light through. */
	public static final Block SELENITE_BLOCK = blockWithItem("selenite_block", TransparentBlock::new, BlockBehaviour.Properties.of()
		.mapColor(MapColor.QUARTZ).strength(1.0F).sound(SoundType.CALCITE).noOcclusion().isValidSpawn((s, l, p, t) -> false)
		.isRedstoneConductor((s, l, p) -> false).isSuffocating((s, l, p) -> false).isViewBlocking((s, l, p, a) -> false));
	public static final Block SELENITE_CLUSTER = blockWithItem("selenite_cluster", p -> new AmethystClusterBlock(7.0F, 3.0F, p),
		BlockBehaviour.Properties.of().mapColor(MapColor.QUARTZ).forceSolidOn().noOcclusion().sound(SoundType.AMETHYST_CLUSTER).strength(1.5F)
			.lightLevel(state -> 5).pushReaction(PushReaction.POPPED));

	private RPLifeBlocks() {
	}

	private static BlockBehaviour.Properties stem() {
		return BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_ORANGE).instrument(NoteBlockInstrument.BASS).strength(2.0F)
			.sound(SoundType.STEM);
	}

	private static BlockBehaviour.Properties planks() {
		return BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_ORANGE).instrument(NoteBlockInstrument.BASS).strength(2.0F, 3.0F)
			.sound(SoundType.NETHER_WOOD);
	}

	public static void init() {
		// Axes strip rustcap like Nether stems (26.3 keeps stripping in the data-driven minecraft:axe block transformer).
		BlockTransformerHelper.registerStripping(RUSTCAP_STEM, STRIPPED_RUSTCAP_STEM);
		BlockTransformerHelper.registerStripping(RUSTCAP_HYPHAE, STRIPPED_RUSTCAP_HYPHAE);
	}
}
