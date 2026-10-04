package io.github.avi130805.redplanet.environment;

import io.github.avi130805.redplanet.registry.RPTags;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Combustion needs free oxygen. Mars' air holds 0.16% O2 at 0.6% of Earth's pressure, so no flame can be
 * sustained: fire, torches, lanterns, campfires and candles can't burn, furnaces won't burn fuel and burning
 * entities go out. Black powder (TNT, fireworks) carries its own oxidizer (potassium nitrate) and still works.
 * Habitats with oxygen allow combustion again. See docs/SCIENCE.md, "Combustion".
 */
public final class Combustion {
	private Combustion() {
	}

	public static boolean canBurnAt(LevelReader level, BlockPos pos) {
		return PlanetEnvironment.combustion(level, pos);
	}

	/** Should this block state be refused at this position (fire, torches, lanterns)? */
	public static boolean forbids(BlockState state, LevelReader level, BlockPos pos) {
		return state.is(RPTags.REQUIRES_OXYGEN) && !canBurnAt(level, pos);
	}

	/** Returns the state with LIT=false if it is a combustion light that cannot burn here. */
	public static BlockState extinguishIfNeeded(BlockState state, LevelReader level, BlockPos pos) {
		if (state.is(RPTags.EXTINGUISHED_WITHOUT_OXYGEN) && state.hasProperty(BlockStateProperties.LIT)
			&& state.getValue(BlockStateProperties.LIT) && !canBurnAt(level, pos)) {
			return state.setValue(BlockStateProperties.LIT, false);
		}
		return state;
	}
}
