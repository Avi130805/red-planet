package io.github.avi130805.redplanet.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Seasonal CO2 frost: thin layers of dry-ice frost that blanket the high latitudes in winter, when the ground
 * cools to the CO2 frost point, and sublimate away layer by layer in spring.
 */
public class Co2FrostBlock extends SnowLayerBlock {
	public Co2FrostBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		double excess = Sublimation.temperature(level, pos) - (Sublimation.co2FrostPoint(level, pos) + Sublimation.CO2_MARGIN_K);
		if (excess > 0 && random.nextDouble() < Math.min(0.9, excess / 20.0)) {
			int layers = state.getValue(LAYERS);
			if (layers > 1) {
				level.setBlockAndUpdate(pos, state.setValue(LAYERS, layers - 1));
			} else {
				Sublimation.vanish(state, level, pos);
			}
		}
	}
}
