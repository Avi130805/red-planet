package io.github.avi130805.redplanet.worldgen.feature;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.avi130805.redplanet.life.RPLifeBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.IntProviders;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * {@code redplanet:selenite_crystal}: a giant selenite beam crossing a cave, like the gypsum crystals of Naica's Cave
 * of Crystals (up to 12 m long, grown from warm, slowly cooling sulfate-rich water). The setting is fiction; the
 * mineral and its habit are real. From a rock anchor next to the origin, a straight prism of selenite grows into the
 * open cave at a random tilt until it meets rock or reaches its length, and small crystal clusters sprout around its
 * root.
 *
 * <p>Writes stay within 16 blocks of the origin, inside the 3x3-chunk window a feature may touch.
 *
 * @param length beam length in blocks
 * @param thickness 1 = a single-block beam, 2 = a two-by-two prism
 */
public record SeleniteCrystalFeature(IntProvider length, IntProvider thickness) implements Feature {
	public static final MapCodec<SeleniteCrystalFeature> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		IntProviders.codec(3, 15).fieldOf("length").forGetter(SeleniteCrystalFeature::length),
		IntProviders.codec(1, 2).fieldOf("thickness").forGetter(SeleniteCrystalFeature::thickness)
	).apply(i, SeleniteCrystalFeature::new));

	@Override
	public MapCodec<SeleniteCrystalFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		if (!level.getBlockState(origin).isAir()) {
			return false;
		}
		// Find the rock the crystal grows from: the first solid neighbour, floor first.
		Direction anchorSide = null;
		for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP}) {
			if (level.getBlockState(origin.relative(d)).isSolidRender()) {
				anchorSide = d;
				break;
			}
		}
		if (anchorSide == null) {
			return false;
		}
		// Grow away from the anchor, tilted: elevation 10-70 degrees off the anchor's plane, random azimuth.
		Direction out = anchorSide.getOpposite();
		double azimuth = random.nextDouble() * Math.PI * 2.0;
		double tilt = Math.toRadians(10.0 + random.nextDouble() * 60.0);
		double ax = Math.cos(azimuth) * Math.cos(tilt);
		double az = Math.sin(azimuth) * Math.cos(tilt);
		double up = Math.sin(tilt);
		// Rotate the "up" component onto the outward direction of the anchor face.
		double dx;
		double dy;
		double dz;
		switch (out.getAxis()) {
			case Y -> {
				dx = ax;
				dy = up * out.getStepY();
				dz = az;
			}
			case X -> {
				dx = up * out.getStepX();
				dy = ax;
				dz = az;
			}
			default -> {
				dx = ax;
				dy = az;
				dz = up * out.getStepZ();
			}
		}
		int length = this.length.sample(random);
		int thickness = this.thickness.sample(random);
		BlockState selenite = RPLifeBlocks.SELENITE_BLOCK.defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int placed = 0;
		// Start half a block inside the rock so the beam is rooted.
		for (double s = -0.5; s <= length; s += 0.5) {
			int x = Mth.floor(origin.getX() + 0.5 + dx * s);
			int y = Mth.floor(origin.getY() + 0.5 + dy * s);
			int z = Mth.floor(origin.getZ() + 0.5 + dz * s);
			if (Math.abs(x - origin.getX()) > 15 || Math.abs(z - origin.getZ()) > 15) {
				break;
			}
			boolean blocked = false;
			for (int tx = 0; tx < thickness; tx++) {
				for (int ty = 0; ty < thickness; ty++) {
					for (int tz = 0; tz < thickness; tz++) {
						pos.set(x + tx, y + ty, z + tz);
						BlockState there = level.getBlockState(pos);
						if (there.isAir()) {
							level.setBlock(pos, selenite, 2);
							placed++;
						} else if (s > 1.5 && !there.is(RPLifeBlocks.SELENITE_BLOCK)) {
							blocked = true;
						}
					}
				}
			}
			if (blocked) {
				break; // reached the far wall
			}
		}
		// Small clusters around the root, each on a face of the anchor rock.
		BlockPos root = origin.relative(anchorSide);
		for (int n = 0; n < 6; n++) {
			BlockPos spot = root.offset(random.nextInt(5) - 2, random.nextInt(3) - 1, random.nextInt(5) - 2);
			Direction face = Direction.getRandom(random);
			BlockPos target = spot.relative(face);
			if (level.getBlockState(spot).isSolidRender() && level.getBlockState(target).isAir()) {
				level.setBlock(target, RPLifeBlocks.SELENITE_CLUSTER.defaultBlockState().setValue(AmethystClusterBlock.FACING, face), 2);
			}
		}
		return placed > 0;
	}
}
