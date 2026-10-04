package io.github.avi130805.redplanet.worldgen;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.avi130805.redplanet.mars.geo.MarsProjection;
import io.github.avi130805.redplanet.mars.geo.MarsTerrain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * {@code redplanet:mars}: picks each column's surface biome from real geography, and the cave biomes below it
 * ({@link MarsCaves}). JSON: {@code {"type": "redplanet:mars", "biomes": {"<role>": "<biome id>", ...}}}; a cave
 * role left out of the map falls back to the default biome.
 */
public final class MarsBiomeSource extends BiomeSource {
	public static final MapCodec<MarsBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		Codec.unboundedMap(MarsBiome.CODEC, Biome.CODEC).fieldOf("biomes").forGetter(s -> s.biomes)
	).apply(i, MarsBiomeSource::new));

	/** Geography lookups don't need the world seed; only the per-column facts are used. */
	private static final MarsTerrain GEOGRAPHY = new MarsTerrain(0L);

	private final Map<MarsBiome, Holder<Biome>> biomes;
	private final Holder<Biome> fallback;

	private MarsBiomeSource(Map<MarsBiome, Holder<Biome>> biomes) {
		this.biomes = new EnumMap<>(biomes);
		Holder<Biome> fb = biomes.get(MarsBiome.CRATERED_HIGHLANDS);
		if (fb == null) {
			throw new IllegalArgumentException("redplanet:mars biome source needs a 'cratered_highlands' entry");
		}
		this.fallback = fb;
	}

	@Override
	protected MapCodec<MarsBiomeSource> codec() {
		return CODEC;
	}

	@Override
	protected Stream<Holder<Biome>> collectPossibleBiomes() {
		return this.biomes.values().stream().distinct();
	}

	@Override
	public BiomeResolver createResolver(Climate.Sampler sampler) {
		MarsTerrain.Column column = new MarsTerrain.Column();
		return (qx, qy, qz) -> {
			int x = QuartPos.toBlock(qx) + 2;
			int z = QuartPos.toBlock(qz) + 2;
			GEOGRAPHY.sample(x, z, column);
			MarsBiome surface = MarsGeography.classify(column);
			return this.holder(MarsCaves.roleAt(surface, column.surfaceY, x, QuartPos.toBlock(qy) + 2, z), surface);
		};
	}

	@Override
	public BiomeResolver createResolverForChunk(Climate.Sampler sampler, int minQuartX, int minQuartY, int minQuartZ,
			int quartSizeX, int quartSizeY, int quartSizeZ) {
		// The geography is 2D: classify each quart column once, then only the depth test runs per quart.
		MarsTerrain.Column column = new MarsTerrain.Column();
		MarsBiome[] roles = new MarsBiome[quartSizeX * quartSizeZ];
		double[] surfaceY = new double[quartSizeX * quartSizeZ];
		for (int dz = 0; dz < quartSizeZ; dz++) {
			for (int dx = 0; dx < quartSizeX; dx++) {
				GEOGRAPHY.sample(QuartPos.toBlock(minQuartX + dx) + 2, QuartPos.toBlock(minQuartZ + dz) + 2, column);
				roles[dx + dz * quartSizeX] = MarsGeography.classify(column);
				surfaceY[dx + dz * quartSizeX] = column.surfaceY;
			}
		}
		BiomeResolver fallbackResolver = this.createResolver(sampler);
		return (qx, qy, qz) -> {
			int dx = qx - minQuartX;
			int dz = qz - minQuartZ;
			if (dx < 0 || dz < 0 || dx >= quartSizeX || dz >= quartSizeZ) {
				return fallbackResolver.getNoiseBiome(qx, qy, qz);
			}
			int i = dx + dz * quartSizeX;
			return this.holder(MarsCaves.roleAt(roles[i], surfaceY[i], QuartPos.toBlock(qx) + 2, QuartPos.toBlock(qy) + 2,
				QuartPos.toBlock(qz) + 2), roles[i]);
		};
	}

	/** The biome for a role; a cave role missing from the map (an older data pack) keeps the surface biome. */
	private Holder<Biome> holder(MarsBiome role, MarsBiome surface) {
		Holder<Biome> biome = this.biomes.get(role);
		return biome != null ? biome : this.biomes.getOrDefault(surface, this.fallback);
	}

	@Override
	public void addDebugInfo(List<String> result, BlockPos feetPos, Climate.Sampler sampler) {
		MarsTerrain.Column c = GEOGRAPHY.sample(feetPos.getX(), feetPos.getZ());
		result.add(String.format("Mars: %.3f%s %.3f E  elev %.2f km (here %.2f km)  albedo %.2f  rough %.0f m",
			Math.abs(c.lat), c.lat >= 0 ? "N" : "S", c.lon, c.baseElevation / 1000.0,
			MarsProjection.elevationOfY(feetPos.getY()) / 1000.0, c.albedo, c.roughness));
		MarsBiome surface = MarsGeography.classify(c);
		result.add(String.format("Mars region: %s, %d blocks below the surface (%s)", surface.getSerializedName(),
			Math.round(c.surfaceY - feetPos.getY()),
			MarsCaves.roleAt(surface, c.surfaceY, feetPos.getX(), feetPos.getY(), feetPos.getZ()).getSerializedName()));
	}
}
