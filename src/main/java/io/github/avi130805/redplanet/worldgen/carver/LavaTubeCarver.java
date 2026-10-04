package io.github.avi130805.redplanet.worldgen.carver;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.avi130805.redplanet.mars.geo.MarsTerrain;

import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.util.valueproviders.FloatProviders;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.IntProviders;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.CarverOutput;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;

/**
 * {@code redplanet:lava_tube}: a drained lava tube. Real tubes form under the crust of flowing lava and run down the
 * flanks of the Tharsis and Elysium volcanoes; where their roofs collapse they leave chains of pits and the
 * "skylights" seen on Arsia and Pavonis Mons (Cushing et al. 2007, pointer). This one keeps a steady depth below the
 * local surface (it follows the terrain, as lava does), meanders gently, has a flat floor, and now and then opens a
 * skylight shaft to the surface. Drawn at human scale: wide enough to walk and build in; the fiction's lichen
 * hollows live inside.
 *
 * @param probability chance that a chunk starts a tube
 * @param depth blocks from the surface down to the tube's roof
 * @param radius horizontal half-width in blocks
 * @param length tube length in steps (about one block each)
 * @param skylightChance chance per step of a skylight shaft
 */
public record LavaTubeCarver(float probability, IntProvider depth, FloatProvider radius, IntProvider length, float skylightChance)
		implements WorldCarver {
	public static final MapCodec<LavaTubeCarver> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		Codec.floatRange(0.0F, 1.0F).fieldOf("probability").forGetter(LavaTubeCarver::probability),
		IntProviders.codec(4, 80).fieldOf("depth").forGetter(LavaTubeCarver::depth),
		FloatProviders.codec(1.0F, 12.0F).fieldOf("radius").forGetter(LavaTubeCarver::radius),
		IntProviders.codec(16, 256).fieldOf("length").forGetter(LavaTubeCarver::length),
		Codec.floatRange(0.0F, 0.2F).fieldOf("skylight_chance").forGetter(LavaTubeCarver::skylightChance)
	).apply(i, LavaTubeCarver::new));

	/** The same geography the material rule uses (detail noise is a few blocks off the real surface; tubes don't mind). */
	private static final MarsTerrain GEOGRAPHY = new MarsTerrain(0L);

	@Override
	public MapCodec<LavaTubeCarver> codec() {
		return CODEC;
	}

	@Override
	public int getRange() {
		return 8;
	}

	@Override
	public boolean isStartChunk(RandomSource random) {
		return random.nextFloat() <= this.probability;
	}

	@Override
	public boolean carve(WorldGenerationContext context, RandomSource random, ChunkPos chunkPos, ChunkPos sourceChunkPos, CarverOutput output) {
		// Every random draw happens whatever chunk is being carved, so each chunk sees the same tube.
		double x = sourceChunkPos.getBlockX(random.nextInt(16));
		double z = sourceChunkPos.getBlockZ(random.nextInt(16));
		double heading = random.nextDouble() * Math.PI * 2.0;
		double baseRadius = this.radius.sample(random);
		int roofDepth = this.depth.sample(random);
		int steps = this.length.sample(random);
		double wobble = random.nextDouble() * Math.PI * 2.0;
		WorldCarver.CarveSkipChecker flatFloor = (xd, yd, zd, worldY) -> yd < -0.72 || xd * xd + yd * yd + zd * zd >= 1.0;
		for (int step = 0; step < steps; step++) {
			heading += (random.nextDouble() - 0.5) * 0.18;
			x += Math.cos(heading);
			z += Math.sin(heading);
			double r = baseRadius * (0.85 + 0.3 * Math.sin(step * 0.06 + wobble));
			double vr = r * 0.62;
			boolean skylight = random.nextFloat() < this.skylightChance;
			double skylightRadius = 1.5 + random.nextDouble() * 2.0;
			if (!WorldCarver.canReach(chunkPos, x, z, step, steps, (float) r)) {
				continue;
			}
			double surface = GEOGRAPHY.surfaceY(x, z);
			double y = surface - roofDepth - vr;
			if (y - vr < output.minY() + 6) {
				continue;
			}
			WorldCarver.carveEllipsoid(chunkPos, x, y, z, r, vr, output, flatFloor);
			if (skylight) {
				// A collapse pit: a shaft from the tube roof up through the surface.
				for (double sy = y + vr * 0.5; sy < surface + 2.0; sy += skylightRadius) {
					WorldCarver.carveEllipsoid(chunkPos, x, sy, z, skylightRadius, skylightRadius, output,
						(xd, yd, zd, worldY) -> xd * xd + yd * yd + zd * zd >= 1.0);
				}
			}
		}
		return true;
	}
}
