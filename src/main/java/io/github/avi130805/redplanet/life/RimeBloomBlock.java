package io.github.avi130805.redplanet.life;

import io.github.avi130805.redplanet.registry.RPBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Rime bloom (fiction): crystalline frost flowers of the ice caves, rooted in ice and ice-rich regolith. Brewed into a
 * cryo tonic that slows the body's oxygen use (DESIGN.md section 8.4).
 */
public class RimeBloomBlock extends VegetationBlock {
	private static final VoxelShape SHAPE = Block.column(10.0, 0.0, 12.0);

	public RimeBloomBlock(BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
		return state.is(BlockTags.ICE) || state.is(RPBlocks.ICE_RICH_REGOLITH) || state.is(RPBlocks.POLAR_LAYERED_DEPOSIT)
			|| state.is(RPBlocks.REGOLITH);
	}
}
