package io.github.avi130805.redplanet.worldgen;

import com.mojang.serialization.MapCodec;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.mars.geo.MarsProjection;
import io.github.avi130805.redplanet.mars.geo.MarsTerrain;

import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * {@code redplanet:mars_height}: the Mars surface height (fractional block Y) at a column, from real MOLA
 * topography, catalogue craters and seeded detail ({@link MarsTerrain}). Declared 2D (X|Z), so 26.3's compiler
 * evaluates it once per column; the noise settings use {@code final_density = surface_height - y}.
 */
public record MarsHeightFunction() implements DensityFunction {
	public static final MapCodec<MarsHeightFunction> CODEC = MapCodec.unit(MarsHeightFunction::new);
	private static final net.minecraft.resources.Identifier SEED = RedPlanet.id("mars_height");

	@Override
	public DensitySampler compileSampler(CompileContext context) {
		long seed = context.createRandom(SEED).nextLong();
		return new Sampler(new MarsTerrain(seed));
	}

	@Override
	public DensityFunction rewriteChildren(DfRewriteRule rule) {
		return this;
	}

	@Override
	public Interval range() {
		// Hellas floor minus the deepest crater/detail to above Olympus Mons plus rims and detail (conservative).
		return Interval.of(MarsProjection.MIN_Y - 16.0F, MarsProjection.MIN_Y + MarsProjection.HEIGHT + 16.0F);
	}

	@Override
	public int domainAxes() {
		return AXIS_X | AXIS_Z;
	}

	@Override
	public MapCodec<MarsHeightFunction> codec() {
		return CODEC;
	}

	private record Sampler(MarsTerrain terrain) implements DensitySampler {
		@Override
		public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
			return (float) this.terrain.surfaceY(blockX, blockZ);
		}

		@Override
		public void sampleVolume(SamplerContext context, DensityBuffer out, DensityVolume volume) {
			MarsTerrain.Column column = new MarsTerrain.Column();
			for (int iz = 0; iz < volume.sizeZ(); iz++) {
				int z = volume.blockZ(iz);
				for (int ix = 0; ix < volume.sizeX(); ix++) {
					this.terrain.sample(volume.blockX(ix), z, column);
					out.setRange(volume.indexUnchecked(ix, 0, iz), volume.sizeY(), (float) column.surfaceY);
				}
			}
		}
	}
}
