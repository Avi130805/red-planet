package io.github.avi130805.redplanet.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * CO2 ice (dry ice). Stable only near the CO2 frost point (~148 K at Mars pressure): the south polar residual
 * cap (a reservoir, kept cold by its high albedo) and seasonal winter frost. Anywhere warmer it sublimates
 * quickly, straight to gas.
 */
public class DryIceBlock extends Block {
	private final boolean reservoir;

	public DryIceBlock(boolean reservoir, BlockBehaviour.Properties properties) {
		super(properties);
		this.reservoir = reservoir;
	}

	@Override
	protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (this.reservoir && level.canSeeSky(pos.above())) {
			return;
		}
		double excess = Sublimation.temperature(level, pos) - (Sublimation.co2FrostPoint(level, pos) + Sublimation.CO2_MARGIN_K);
		if (excess > 0 && random.nextDouble() < Math.min(0.9, excess / 30.0)) {
			Sublimation.vanish(state, level, pos);
		}
	}
}
