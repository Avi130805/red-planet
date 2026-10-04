package io.github.avi130805.redplanet.registry;

import static io.github.avi130805.redplanet.registry.RPRegistration.blockWithItem;

import io.github.avi130805.redplanet.block.Co2FrostBlock;
import io.github.avi130805.redplanet.block.DryIceBlock;
import io.github.avi130805.redplanet.block.DustLayerBlock;
import io.github.avi130805.redplanet.block.WaterIceBlock;

import net.minecraft.util.ColorRGBA;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ColoredFallingBlock;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/**
 * Mars (and Earth) natural blocks, ores and building blocks. Geology and sources: docs/SCIENCE.md, "Geology".
 */
public final class RPBlocks {
	// ---------------------------------------------------------------------------------- surface materials

	/** The reddish, fine-grained soil covering most of Mars (basaltic fragments + nanophase iron-oxide dust). */
	public static final Block REGOLITH = blockWithItem("regolith", Block::new,
		props(MapColor.TERRACOTTA_ORANGE).strength(0.6F).sound(SoundType.GRAVEL));
	/** Bright airfall dust, ~1.5 um iron-oxide-rich grains (Tharsis, Arabia Terra). Loose: falls like sand. */
	public static final Block MARS_DUST = blockWithItem("mars_dust", p -> new ColoredFallingBlock(new ColorRGBA(0xC98A5A), p),
		props(MapColor.COLOR_ORANGE).instrument(NoteBlockInstrument.SNARE).strength(0.4F).sound(SoundType.SAND));
	public static final Block MARS_DUST_LAYER = blockWithItem("mars_dust_layer", DustLayerBlock::new,
		props(MapColor.COLOR_ORANGE).replaceable().forceSolidOff().strength(0.1F).sound(SoundType.SAND)
			.pushReaction(PushReaction.POPPED).isViewBlocking((state, level, pos, aabb) -> state.getValue(SnowLayerBlock.LAYERS) >= 8));
	/** Dark basaltic sand of the dune fields. */
	public static final Block BASALTIC_SAND = blockWithItem("basaltic_sand", p -> new ColoredFallingBlock(new ColorRGBA(0x3D3634), p),
		props(MapColor.COLOR_GRAY).instrument(NoteBlockInstrument.SNARE).strength(0.5F).sound(SoundType.SAND));
	/** Regolith with a lag of hematite concretions ("blueberries"), Meridiani Planum. */
	public static final Block HEMATITE_SPHERULE_REGOLITH = blockWithItem("hematite_spherule_regolith", Block::new,
		props(MapColor.TERRACOTTA_BROWN).strength(0.7F).sound(SoundType.GRAVEL));
	/** Regolith cemented by ground ice: shallow permafrost poleward of ~45 degrees (Phoenix dug it at 5 cm). */
	public static final Block ICE_RICH_REGOLITH = blockWithItem("ice_rich_regolith", Block::new,
		props(MapColor.TERRACOTTA_LIGHT_GRAY).strength(1.2F).sound(SoundType.GRAVEL));

	// ---------------------------------------------------------------------------------------- rock

	/** Martian crust: basaltic rock and impact breccia. */
	public static final Block MARS_STONE = blockWithItem("mars_stone", Block::new,
		props(MapColor.TERRACOTTA_RED).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.5F, 6.0F));
	public static final Block MARS_COBBLESTONE = blockWithItem("mars_cobblestone", Block::new,
		props(MapColor.TERRACOTTA_RED).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(2.0F, 6.0F));
	public static final Block MARS_STONE_BRICKS = blockWithItem("mars_stone_bricks", Block::new,
		props(MapColor.TERRACOTTA_RED).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.5F, 6.0F));
	/** Dark volcanic basalt of the Tharsis and Elysium lava plains. */
	public static final Block MARS_BASALT = blockWithItem("mars_basalt", RotatedPillarBlock::new,
		props(MapColor.COLOR_BLACK).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.25F, 4.2F).sound(SoundType.BASALT));
	public static final Block POLISHED_MARS_BASALT = blockWithItem("polished_mars_basalt", Block::new,
		props(MapColor.COLOR_BLACK).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.25F, 4.2F).sound(SoundType.POLISHED_DEEPSLATE));
	public static final Block MARS_BASALT_BRICKS = blockWithItem("mars_basalt_bricks", Block::new,
		props(MapColor.COLOR_BLACK).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.5F, 6.0F).sound(SoundType.DEEPSLATE_BRICKS));
	/** Fine-grained lake-bed mudstone, Gale crater (Yellowknife Bay, Murray formation). */
	public static final Block MUDSTONE = blockWithItem("mudstone", Block::new,
		props(MapColor.TERRACOTTA_WHITE).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.2F, 5.0F));
	public static final Block POLISHED_MUDSTONE = blockWithItem("polished_mudstone", Block::new,
		props(MapColor.TERRACOTTA_WHITE).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.2F, 5.0F));
	/** Banded sulfate-bearing sedimentary layers (Mount Sharp, Valles Marineris interior deposits). */
	public static final Block LAYERED_SEDIMENT = blockWithItem("layered_sediment", RotatedPillarBlock::new,
		props(MapColor.TERRACOTTA_YELLOW).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.2F, 5.0F));
	/** Delta deposits of Jezero crater: fine sandstone and mudstone with carbonates. */
	public static final Block DELTA_SEDIMENT = blockWithItem("delta_sediment", Block::new,
		props(MapColor.TERRACOTTA_LIGHT_GRAY).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.0F, 4.0F));
	/** Smectite (Fe/Mg phyllosilicate) clay, Mawrth Vallis, Jezero, Gale. */
	public static final Block SMECTITE_CLAY = blockWithItem("smectite_clay", Block::new,
		props(MapColor.TERRACOTTA_CYAN).strength(0.6F).sound(SoundType.GRAVEL));
	/** Magnesium-carbonate-bearing rock (Jezero margin, Nili Fossae). */
	public static final Block CARBONATE_ROCK = blockWithItem("carbonate_rock", Block::new,
		props(MapColor.TERRACOTTA_LIGHT_GREEN).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.3F, 5.0F));

	// ---------------------------------------------------------------------------------------- ices

	/** Water ice of the polar caps (dusty, layered). A reservoir: it doesn't sublimate where it formed. */
	public static final Block POLAR_WATER_ICE = blockWithItem("polar_water_ice", p -> new WaterIceBlock(true, p),
		props(MapColor.ICE).friction(0.98F).randomTicks().strength(0.8F).sound(SoundType.GLASS));
	/** Water ice mined or exposed elsewhere: sublimates when warm and exposed. */
	public static final Block WATER_ICE = blockWithItem("water_ice", p -> new WaterIceBlock(false, p),
		props(MapColor.ICE).friction(0.98F).randomTicks().strength(0.6F).sound(SoundType.GLASS));
	/** Polar layered deposits: alternating ice-rich and dust-rich layers. */
	public static final Block POLAR_LAYERED_DEPOSIT = blockWithItem("polar_layered_deposit", RotatedPillarBlock::new,
		props(MapColor.TERRACOTTA_WHITE).strength(1.0F).sound(SoundType.GLASS));
	/** CO2 ice of the south polar residual cap. */
	public static final Block CO2_ICE = blockWithItem("co2_ice", p -> new DryIceBlock(true, p),
		props(MapColor.SNOW).randomTicks().friction(0.98F).strength(0.5F).sound(SoundType.GLASS));
	/** CO2 ice moved elsewhere (sublimates). */
	public static final Block DRY_ICE = blockWithItem("dry_ice", p -> new DryIceBlock(false, p),
		props(MapColor.SNOW).randomTicks().friction(0.98F).strength(0.4F).sound(SoundType.GLASS));
	/** Seasonal CO2 frost layers. */
	public static final Block CO2_FROST = blockWithItem("co2_frost", Co2FrostBlock::new,
		props(MapColor.SNOW).replaceable().forceSolidOff().randomTicks().strength(0.1F).sound(SoundType.SNOW)
			.pushReaction(PushReaction.POPPED).isViewBlocking((state, level, pos, aabb) -> state.getValue(SnowLayerBlock.LAYERS) >= 8));

	// ---------------------------------------------------------------------------------------- ores

	/** Grey crystalline hematite (Fe2O3): Mars' iron ore. */
	public static final Block HEMATITE_ORE = blockWithItem("hematite_ore", p -> new DropExperienceBlock(ConstantInt.of(0), p),
		ore(MapColor.TERRACOTTA_RED, 3.0F));
	/** Olivine-rich basalt (Nili Fossae, Jezero's Seitah): green (Mg,Fe)2SiO4 crystals. */
	public static final Block OLIVINE_BASALT = blockWithItem("olivine_basalt", p -> new DropExperienceBlock(UniformInt.of(1, 3), p),
		ore(MapColor.COLOR_BLACK, 3.0F));
	/** Jarosite, KFe3(SO4)2(OH)6: an acidic-water mineral found by Opportunity. */
	public static final Block JAROSITE_ORE = blockWithItem("jarosite_ore", p -> new DropExperienceBlock(UniformInt.of(0, 2), p),
		ore(MapColor.TERRACOTTA_YELLOW, 2.5F));
	/** Gypsum (CaSO4.2H2O) veins in mudstone, Gale crater; heating it releases its water. */
	public static final Block GYPSUM_VEIN = blockWithItem("gypsum_vein", p -> new DropExperienceBlock(ConstantInt.of(0), p),
		ore(MapColor.TERRACOTTA_WHITE, 1.5F));
	/** Elemental sulfur crystals (Gediz Vallis channel, Gale crater, 2024). */
	public static final Block SULFUR_DEPOSIT = blockWithItem("sulfur_deposit", p -> new DropExperienceBlock(UniformInt.of(1, 3), p),
		ore(MapColor.COLOR_YELLOW, 1.5F));
	/** Chromite (FeCr2O4) in olivine-rich rock: chromium for stainless steel. */
	public static final Block MARS_CHROMITE_ORE = blockWithItem("mars_chromite_ore", p -> new DropExperienceBlock(UniformInt.of(0, 2), p),
		ore(MapColor.TERRACOTTA_BLACK, 3.0F));
	/** Iron-nickel meteorite (kamacite/taenite), like Opportunity's "Heat Shield Rock". */
	public static final Block IRON_NICKEL_METEORITE = blockWithItem("iron_nickel_meteorite", Block::new,
		props(MapColor.METAL).instrument(NoteBlockInstrument.IRON_XYLOPHONE).requiresCorrectToolForDrops().strength(5.0F, 9.0F).sound(SoundType.METAL));
	/** Chromite on Earth: ultramafic rock, found in the deeper overworld. */
	public static final Block CHROMITE_ORE = blockWithItem("chromite_ore", p -> new DropExperienceBlock(UniformInt.of(0, 2), p),
		props(MapColor.STONE).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(3.0F, 3.0F));
	public static final Block DEEPSLATE_CHROMITE_ORE = blockWithItem("deepslate_chromite_ore", p -> new DropExperienceBlock(UniformInt.of(0, 2), p),
		props(MapColor.DEEPSLATE).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(4.5F, 3.0F).sound(SoundType.DEEPSLATE));

	// ---------------------------------------------------------------------------------------- building families

	public static final Block MARS_STONE_STAIRS = stairs("mars_stone_stairs", MARS_STONE);
	public static final Block MARS_STONE_SLAB = slab("mars_stone_slab", MARS_STONE);
	public static final Block MARS_COBBLESTONE_STAIRS = stairs("mars_cobblestone_stairs", MARS_COBBLESTONE);
	public static final Block MARS_COBBLESTONE_SLAB = slab("mars_cobblestone_slab", MARS_COBBLESTONE);
	public static final Block MARS_COBBLESTONE_WALL = wall("mars_cobblestone_wall", MARS_COBBLESTONE);
	public static final Block MARS_STONE_BRICK_STAIRS = stairs("mars_stone_brick_stairs", MARS_STONE_BRICKS);
	public static final Block MARS_STONE_BRICK_SLAB = slab("mars_stone_brick_slab", MARS_STONE_BRICKS);
	public static final Block MARS_STONE_BRICK_WALL = wall("mars_stone_brick_wall", MARS_STONE_BRICKS);
	public static final Block POLISHED_MARS_BASALT_STAIRS = stairs("polished_mars_basalt_stairs", POLISHED_MARS_BASALT);
	public static final Block POLISHED_MARS_BASALT_SLAB = slab("polished_mars_basalt_slab", POLISHED_MARS_BASALT);
	public static final Block MARS_BASALT_BRICK_STAIRS = stairs("mars_basalt_brick_stairs", MARS_BASALT_BRICKS);
	public static final Block MARS_BASALT_BRICK_SLAB = slab("mars_basalt_brick_slab", MARS_BASALT_BRICKS);
	public static final Block MARS_BASALT_BRICK_WALL = wall("mars_basalt_brick_wall", MARS_BASALT_BRICKS);
	public static final Block POLISHED_MUDSTONE_STAIRS = stairs("polished_mudstone_stairs", POLISHED_MUDSTONE);
	public static final Block POLISHED_MUDSTONE_SLAB = slab("polished_mudstone_slab", POLISHED_MUDSTONE);
	public static final Block POLISHED_MUDSTONE_WALL = wall("polished_mudstone_wall", POLISHED_MUDSTONE);

	private RPBlocks() {
	}

	private static BlockBehaviour.Properties props(MapColor color) {
		return BlockBehaviour.Properties.of().mapColor(color);
	}

	private static BlockBehaviour.Properties ore(MapColor color, float strength) {
		return props(color).instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(strength, 3.0F);
	}

	private static Block stairs(String name, Block base) {
		return blockWithItem(name, p -> new StairBlock(base.defaultBlockState(), p), BlockBehaviour.Properties.ofFullCopy(base));
	}

	private static Block slab(String name, Block base) {
		return blockWithItem(name, SlabBlock::new, BlockBehaviour.Properties.ofFullCopy(base));
	}

	private static Block wall(String name, Block base) {
		return blockWithItem(name, WallBlock::new, BlockBehaviour.Properties.ofFullCopy(base).forceSolidOn());
	}

	public static void init() {
	}
}
