package io.github.avi130805.redplanet.worldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.avi130805.redplanet.registry.RPBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.IntProviders;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * {@code redplanet:small_crater}: a human-scale simple impact crater, smaller than anything in the crater catalogue
 * (which stops at 3 km). A paraboloid bowl about 0.2 times as deep as it is wide, a raised rim of ~4 % of the
 * diameter, and ejecta thrown over the surroundings, following the simple-crater shape (Garvin et al. 2003; Melosh
 * 1989). A few are fresh: blocky ejecta and, rarely, an iron-nickel fragment of the impactor at the bottom.
 *
 * @param radius bowl radius in blocks (keep within 10 so the ejecta stays inside the 3x3-chunk write window)
 * @param freshChance chance that a crater is fresh
 * @param meteoriteChance chance that a fresh crater keeps a meteorite fragment
 */
public record SmallCraterFeature(IntProvider radius, float freshChance, float meteoriteChance) implements Feature {
	public static final MapCodec<SmallCraterFeature> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		IntProviders.codec(2, 10).fieldOf("radius").forGetter(SmallCraterFeature::radius),
		Codec.floatRange(0.0F, 1.0F).fieldOf("fresh_chance").forGetter(SmallCraterFeature::freshChance),
		Codec.floatRange(0.0F, 1.0F).fieldOf("meteorite_chance").forGetter(SmallCraterFeature::meteoriteChance)
	).apply(i, SmallCraterFeature::new));

	@Override
	public MapCodec<SmallCraterFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		int r = this.radius.sample(random);
		boolean fresh = random.nextFloat() < this.freshChance;
		double depth = 0.4 * r; // depth/diameter 0.2
		double rim = Math.max(1.0, 0.08 * r * (fresh ? 1.3 : 0.8)); // rim/diameter ~4 %
		int outer = (int) Math.ceil(1.7 * r);
		int cx = origin.getX();
		int cz = origin.getZ();
		int centerTop = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, cx, cz);
		if (centerTop <= level.getMinY() + 8) {
			return false;
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -outer; dx <= outer; dx++) {
			for (int dz = -outer; dz <= outer; dz++) {
				double rho = Math.sqrt(dx * dx + dz * dz) / r;
				if (rho > 1.7) {
					continue;
				}
				int x = cx + dx;
				int z = cz + dz;
				int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
				if (top <= level.getMinY() + 4) {
					continue;
				}
				BlockState surface = level.getBlockState(pos.set(x, top, z));
				if (surface.isAir()) {
					continue;
				}
				double target;
				if (rho < 1.0) {
					// Bowl relative to the crater's mean surface, blended into the rim.
					double bowl = -depth * (1.0 - rho * rho);
					target = centerTop - 1 + bowl + rim * smooth(0.75, 1.0, rho);
				} else {
					// Rim crest falling off over the ejecta blanket (thickness ~ (r/rho)^3).
					target = top + rim * Math.pow(1.0 / rho, 3.0) * (1.0 - smooth(1.4, 1.7, rho)) * (0.8 + 0.4 * random.nextDouble());
				}
				int t = (int) Math.round(target);
				if (t < top) {
					for (int y = top; y > t; y--) {
						level.setBlock(pos.set(x, y, z), Blocks.AIR.defaultBlockState(), 2);
					}
					level.setBlock(pos.set(x, t, z), surface, 2);
				} else if (t > top) {
					BlockState fill = fresh && random.nextInt(3) == 0 ? RPBlocks.MARS_COBBLESTONE.defaultBlockState() : surface;
					for (int y = top + 1; y <= t; y++) {
						level.setBlock(pos.set(x, y, z), fill, 2);
					}
				}
			}
		}
		if (fresh) {
			// Blocky ejecta: scattered rock just outside the rim.
			int rocks = 2 + random.nextInt(4 + r);
			for (int k = 0; k < rocks; k++) {
				double a = random.nextDouble() * Math.PI * 2.0;
				double d = r * (1.1 + 0.6 * random.nextDouble());
				int x = cx + (int) Math.round(Math.cos(a) * d);
				int z = cz + (int) Math.round(Math.sin(a) * d);
				int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
				level.setBlock(pos.set(x, y, z), RPBlocks.MARS_COBBLESTONE.defaultBlockState(), 2);
			}
			if (random.nextFloat() < this.meteoriteChance) {
				int y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, cx, cz) - 1;
				level.setBlock(pos.set(cx, y, cz), RPBlocks.IRON_NICKEL_METEORITE.defaultBlockState(), 2);
			}
		}
		return true;
	}

	private static double smooth(double a, double b, double x) {
		double t = Math.clamp((x - a) / (b - a), 0.0, 1.0);
		return t * t * (3.0 - 2.0 * t);
	}
}
