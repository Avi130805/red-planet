package io.github.avi130805.redplanet.habitat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.redstone.Orientation;

import org.jspecify.annotations.Nullable;

/**
 * An LED lamp: light without fire, so it works anywhere on Mars. On by default; a redstone signal switches it off.
 */
public class LedLampBlock extends Block {
	public static final BooleanProperty LIT = BlockStateProperties.LIT;

	public LedLampBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.defaultBlockState().setValue(LIT, true));
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
		return this.defaultBlockState().setValue(LIT, !context.getLevel().hasNeighborSignal(context.getClickedPos()));
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
		if (!level.isClientSide()) {
			boolean shouldLight = !level.hasNeighborSignal(pos);
			if (state.getValue(LIT) != shouldLight) {
				level.scheduleTick(pos, this, 2);
			}
		}
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		boolean shouldLight = !level.hasNeighborSignal(pos);
		if (state.getValue(LIT) != shouldLight) {
			level.setBlock(pos, state.setValue(LIT, shouldLight), 2);
		}
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LIT);
	}
}
