package io.github.avi130805.redplanet.registry;

import static io.github.avi130805.redplanet.registry.RPRegistration.blockWithItem;
import static io.github.avi130805.redplanet.registry.RPRegistration.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * Engineering materials shared by the launch site, the machines and the Starship's parts: stainless steel (the hull
 * alloy: iron with chromium and nickel) and a copper alloy for the Raptors' regeneratively cooled chambers
 * (docs/SCIENCE.md, section 18).
 */
public final class RPMaterials {
	public static final Item STAINLESS_STEEL_INGOT = item("stainless_steel_ingot");
	/** Coil-rolled sheet: what the barrel rings are welded from. */
	public static final Item STAINLESS_STEEL_SHEET = item("stainless_steel_sheet");
	public static final Block STAINLESS_STEEL_BLOCK = blockWithItem("stainless_steel_block", Block::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0F, 6.0F).requiresCorrectToolForDrops().sound(SoundType.METAL));
	/** The chamber liner alloy. SpaceX's is unconfirmed; NASA's GRCop family (Cu-Cr-Nb) is the reference. */
	public static final Item GRCOP_INGOT = item("grcop_ingot");

	private RPMaterials() {
	}

	public static void init() {
	}
}
