package io.github.avi130805.redplanet.life;

import net.minecraft.world.level.block.SpeleothemBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Perchlorate salt spires: stalagmites and stalactites of salt crystals in the brine grottoes (fiction setting, real
 * chemistry: perchlorate brines deposit salts as they evaporate). Vanilla's speleothem behaviour (like sulfur spikes):
 * pointed tips, falling stalactites, and short growth on perchlorate crust.
 */
public class SaltSpireBlock extends SpeleothemBlock {
	public SaltSpireBlock(BlockState blockToGrowOn, BlockBehaviour.Properties properties) {
		super(blockToGrowOn, properties);
	}

	@Override
	protected int getStalactiteLandingSound() {
		return 1052; // same "crystal shatters" event as sulfur spikes
	}

	@Override
	protected int getMaxGrowthLength() {
		return 3;
	}
}
