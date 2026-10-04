package io.github.avi130805.redplanet.habitat;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Finds the air a habitat holds: a flood fill from the regulator's outlet through every block gas can pass. Full blocks
 * (stone, glass, habitat panels) and closed doors and trapdoors hold the pressure; open doors, slabs, fences, plants and
 * torches let it through. A fill that grows past the size limit or leaves the world isn't sealed; one that reaches a
 * chunk not loaded yet is incomplete (no verdict, try again later).
 */
public final class HabitatSeal {
	private HabitatSeal() {
	}

	/** The air found, whether it is sealed in, and whether the fill could finish at all. */
	public record Result(LongOpenHashSet volume, boolean sealed, boolean incomplete) {
		public int size() {
			return this.volume.size();
		}
	}

	public static Result fill(Level level, BlockPos start, int maxVolume) {
		LongOpenHashSet volume = new LongOpenHashSet();
		if (!isOpen(level, start)) {
			return new Result(volume, false, false);
		}
		LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
		long first = start.asLong();
		volume.add(first);
		queue.enqueue(first);
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
		while (!queue.isEmpty()) {
			p.set(queue.dequeueLong());
			for (Direction d : Direction.values()) {
				n.setWithOffset(p, d);
				long key = n.asLong();
				if (volume.contains(key)) {
					continue;
				}
				if (level.isOutsideBuildHeight(n)) {
					return new Result(volume, false, false);
				}
				if (!level.isLoaded(n)) {
					return new Result(volume, false, true);
				}
				if (!isOpen(level, n)) {
					continue;
				}
				volume.add(key);
				if (volume.size() > maxVolume) {
					return new Result(volume, false, false);
				}
				queue.enqueue(key);
			}
		}
		return new Result(volume, true, false);
	}

	/** Can gas fill and pass through this block? */
	public static boolean isOpen(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return true;
		}
		if (state.getBlock() instanceof DoorBlock) {
			return state.getValue(DoorBlock.OPEN);
		}
		if (state.getBlock() instanceof TrapDoorBlock) {
			return state.getValue(TrapDoorBlock.OPEN);
		}
		if (state.getBlock() instanceof FenceGateBlock) {
			return true;
		}
		return !state.isCollisionShapeFullBlock(level, pos);
	}
}
