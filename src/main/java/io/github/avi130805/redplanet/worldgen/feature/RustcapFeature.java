package io.github.avi130805.redplanet.worldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.avi130805.redplanet.life.RPLifeBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.IntProviders;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * {@code redplanet:rustcap}: a giant rustcap fungus, the "tree" of Mars' lava-tube groves (fiction, DESIGN.md
 * section 8.4). It has a rustcap stem, a broad, gently domed cap with a drooping rim, glowing gills underneath, and
 * hyphae roots spreading over the ember moss.
 *
 * <p>Unlike vanilla's huge fungi it measures the headroom first and grows to fit the cave, so a cap never pokes
 * through a tube's roof. It grows only from {@code #redplanet:supports_rustcap} (ember moss).
 *
 * @param height stem height before the headroom limit
 * @param planted grown from a sprout: the sprout at the origin may be replaced
 */
public record RustcapFeature(IntProvider height, boolean planted) implements Feature {
	public static final MapCodec<RustcapFeature> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		IntProviders.codec(3, 24).fieldOf("height").forGetter(RustcapFeature::height),
		Codec.BOOL.optionalFieldOf("planted", false).forGetter(RustcapFeature::planted)
	).apply(i, RustcapFeature::new));

	private static final int MIN_HEIGHT = 4;
	private static final int MAX_SCAN = 26;

	@Override
	public MapCodec<RustcapFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		if (!level.getBlockState(origin.below()).is(RPLifeBlocks.SUPPORTS_RUSTCAP)) {
			return false;
		}
		if (!this.planted && !replaceable(level.getBlockState(origin))) {
			return false;
		}
		// Headroom: free blocks above the origin, up to the scan limit or the build height.
		BlockPos.MutableBlockPos pos = origin.mutable();
		int headroom = 0;
		while (headroom < MAX_SCAN && pos.getY() < level.getMaxY()) {
			BlockState state = level.getBlockState(pos);
			if (!(replaceable(state) || (headroom == 0 && this.planted && state.is(RPLifeBlocks.RUSTCAP_FUNGUS)))) {
				break;
			}
			headroom++;
			pos.move(Direction.UP);
		}
		// The stem plus a two-block cap must fit, with one block of air left under the roof when there's room.
		int height = Math.min(this.height.sample(random), headroom - (headroom > 8 ? 3 : 2));
		if (height < MIN_HEIGHT) {
			return false;
		}
		int top = origin.getY() + height;
		int radius = Math.min(6, 2 + height / 4 + random.nextInt(2));

		BlockState stem = RPLifeBlocks.RUSTCAP_STEM.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
		BlockState cap = RPLifeBlocks.RUSTCAP_CAP.defaultBlockState();
		BlockState gills = RPLifeBlocks.RUSTCAP_GILLS.defaultBlockState();
		BlockState hyphae = RPLifeBlocks.RUSTCAP_HYPHAE.defaultBlockState();

		// Stem (the sprout is replaced when planted).
		for (int y = origin.getY(); y < top; y++) {
			pos.set(origin.getX(), y, origin.getZ());
			if (y == origin.getY() || replaceable(level.getBlockState(pos))) {
				level.setBlock(pos, stem, 3);
			}
		}

		// Roots: hyphae fanning out over the moss in a few directions.
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			if (random.nextInt(3) == 0) {
				continue;
			}
			int reach = height >= 9 ? 2 : 1;
			for (int r = 1; r <= reach; r++) {
				pos.set(origin.getX() + dir.getStepX() * r, origin.getY(), origin.getZ() + dir.getStepZ() * r);
				if (!replaceable(level.getBlockState(pos)) || level.getBlockState(pos.below()).isAir()) {
					break;
				}
				level.setBlock(pos, hyphae.setValue(RotatedPillarBlock.AXIS, dir.getAxis()), 3);
			}
		}

		// Cap: a flat dome two blocks thick with a rim that droops one block at the edge, and gills underneath.
		double rimRadius = radius + 0.5;
		for (int dx = -radius - 1; dx <= radius + 1; dx++) {
			for (int dz = -radius - 1; dz <= radius + 1; dz++) {
				double d = Math.sqrt(dx * dx + dz * dz);
				if (d > rimRadius) {
					continue;
				}
				boolean rim = d > radius - 0.9;
				// Top layer: a slightly smaller disc, so the cap reads as domed.
				if (d <= radius - 1.2 || (d <= radius - 0.6 && random.nextInt(3) != 0)) {
					put(level, pos.set(origin.getX() + dx, top + 1, origin.getZ() + dz), cap);
				}
				put(level, pos.set(origin.getX() + dx, top, origin.getZ() + dz), cap);
				if (rim) {
					put(level, pos.set(origin.getX() + dx, top - 1, origin.getZ() + dz), cap);
				} else if (dx != 0 || dz != 0) {
					// Gills hang under the cap inside the rim: the grove's light.
					if (random.nextInt(5) != 0) {
						put(level, pos.set(origin.getX() + dx, top - 1, origin.getZ() + dz), gills);
					}
				}
			}
		}
		pos.set(origin.getX(), top - 1, origin.getZ());
		level.setBlock(pos, stem, 3);
		return true;
	}

	private static void put(WorldGenLevel level, BlockPos pos, BlockState state) {
		if (replaceable(level.getBlockState(pos))) {
			level.setBlock(pos, state, 3);
		}
	}

	/** Air, and the soft cave life a fungus may grow through. */
	private static boolean replaceable(BlockState state) {
		return state.isAir() || state.is(RPLifeBlocks.AREOLICHEN) || state.is(RPLifeBlocks.EMBER_MOSS_CARPET)
			|| state.is(RPLifeBlocks.RUSTCAP_FUNGUS) || state.canBeReplaced();
	}
}
