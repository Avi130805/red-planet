package io.github.avi130805.redplanet.block;

import io.github.avi130805.redplanet.habitat.HabitatIndex;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Martian water ice (polar caps, buried ground ice). At Mars' 0.6 kPa, below water's triple point, ice does not
 * melt: it sublimates when warm, slowly, and only where it is exposed to the sky. The polar reservoirs are
 * buffered (they persist), so ice that generated as part of a polar cap never sublimates. Inside a pressurized
 * habitat, ice melts into water like on Earth.
 */
public class WaterIceBlock extends Block {
	private final boolean reservoir;

	public WaterIceBlock(boolean reservoir, BlockBehaviour.Properties properties) {
		super(properties);
		this.reservoir = reservoir;
	}

	@Override
	protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (HabitatIndex.isInside(level, pos)) {
			level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
			return;
		}
		if (this.reservoir || !level.canSeeSky(pos.above())) {
			return;
		}
		double excess = Sublimation.temperature(level, pos) - Sublimation.WATER_ICE_LIMIT_K;
		// A fully exposed block lasts a few sols at equatorial afternoon temperatures (Phoenix saw exposed
		// ice chunks vanish in ~4 sols).
		if (excess > 0 && random.nextDouble() < Math.min(0.25, excess / 160.0)) {
			Sublimation.vanish(state, level, pos);
		}
	}
}
