package io.github.avi130805.redplanet.worldgen.feature;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.IntProviders;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

/**
 * {@code redplanet:boulder}: a rock resting on the surface, from a single block to a lumpy blob a few blocks across,
 * partly buried. Every lander site on Mars is strewn with them (Viking 2's Utopia Planitia most of all).
 *
 * @param block the rock (a provider, so a biome can mix basalt, stone and cobble)
 * @param size boulder radius in blocks (0 = a single block)
 */
public record BoulderFeature(Holder<BlockStateProvider> block, IntProvider size) implements Feature {
	public static final MapCodec<BoulderFeature> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		BlockStateProvider.CODEC.fieldOf("block").forGetter(BoulderFeature::block),
		IntProviders.codec(0, 4).fieldOf("size").forGetter(BoulderFeature::size)
	).apply(i, BoulderFeature::new));

	@Override
	public MapCodec<BoulderFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, origin.getX(), origin.getZ());
		if (ground <= level.getMinY() + 4) {
			return false;
		}
		int r = this.size.sample(random);
		BlockPos center = new BlockPos(origin.getX(), ground - (r > 0 ? random.nextInt(r + 1) / 2 + 1 : 0), origin.getZ());
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		if (r == 0) {
			level.setBlock(pos.set(center.getX(), ground, center.getZ()), this.block.value().getState(level, random, center), 2);
			return true;
		}
		double rx = r + random.nextDouble() * 0.8;
		double ry = r * (0.6 + 0.4 * random.nextDouble());
		double rz = r + random.nextDouble() * 0.8;
		for (int dx = -r - 1; dx <= r + 1; dx++) {
			for (int dy = -r - 1; dy <= r + 1; dy++) {
				for (int dz = -r - 1; dz <= r + 1; dz++) {
					double d = (dx * dx) / (rx * rx) + (dy * dy) / (ry * ry) + (dz * dz) / (rz * rz);
					if (d <= 1.0 + 0.15 * (random.nextDouble() - 0.5)) {
						pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
						BlockState state = this.block.value().getState(level, random, pos);
						level.setBlock(pos, state, 2);
					}
				}
			}
		}
		return true;
	}
}
