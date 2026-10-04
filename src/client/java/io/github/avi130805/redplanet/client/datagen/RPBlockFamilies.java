package io.github.avi130805.redplanet.client.datagen;

import io.github.avi130805.redplanet.life.RPLifeBlocks;

import net.minecraft.data.BlockFamily;

/** Block families for datagen: one family drives the models and the stairs/slab/door/... recipes together. */
final class RPBlockFamilies {
	/** Rustcap: Mars' fungal wood, built like the Nether woods (no signs: those need their own block entities). */
	static final BlockFamily RUSTCAP = new BlockFamily.Builder(RPLifeBlocks.RUSTCAP_PLANKS)
		.button(RPLifeBlocks.RUSTCAP_BUTTON)
		.fence(RPLifeBlocks.RUSTCAP_FENCE)
		.fenceGate(RPLifeBlocks.RUSTCAP_FENCE_GATE)
		.pressurePlate(RPLifeBlocks.RUSTCAP_PRESSURE_PLATE)
		.slab(RPLifeBlocks.RUSTCAP_SLAB)
		.stairs(RPLifeBlocks.RUSTCAP_STAIRS)
		.door(RPLifeBlocks.RUSTCAP_DOOR)
		.trapdoor(RPLifeBlocks.RUSTCAP_TRAPDOOR)
		.recipeGroupPrefix("wooden")
		.recipeUnlockedBy("has_planks")
		.getFamily();

	private RPBlockFamilies() {
	}
}
