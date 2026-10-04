package io.github.avi130805.redplanet.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Layers of fine Martian dust (1 to 8), which settle out of the air after dust storms. Behaves like snow layers
 * but never melts.
 */
public class DustLayerBlock extends SnowLayerBlock {
	public DustLayerBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
	}
}
