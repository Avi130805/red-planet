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
 * {@code redplanet:mars}: picks each column's biome from real geography (2D: biomes don't vary with height).
 * JSON: {@code {"type": "redplanet:mars", "biomes": {"<role>": "<biome id>", ...}}}.
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
		return (qx, qy, qz) -> this.pick(QuartPos.toBlock(qx), QuartPos.toBlock(qz), column);
	}

	@Override
	public BiomeResolver createResolverForChunk(Climate.Sampler sampler, int minQuartX, int minQuartY, int minQuartZ,
			int quartSizeX, int quartSizeY, int quartSizeZ) {
		MarsTerrain.Column column = new MarsTerrain.Column();
		@SuppressWarnings("unchecked")
		Holder<Biome>[] columns = new Holder[quartSizeX * quartSizeZ];
		for (int dz = 0; dz < quartSizeZ; dz++) {
			for (int dx = 0; dx < quartSizeX; dx++) {
				columns[dx + dz * quartSizeX] = this.pick(QuartPos.toBlock(minQuartX + dx) + 2, QuartPos.toBlock(minQuartZ + dz) + 2, column);
			}
		}
		return (qx, qy, qz) -> {
			int dx = qx - minQuartX;
			int dz = qz - minQuartZ;
			if (dx >= 0 && dz >= 0 && dx < quartSizeX && dz < quartSizeZ) {
				return columns[dx + dz * quartSizeX];
			}
			return this.pick(QuartPos.toBlock(qx) + 2, QuartPos.toBlock(qz) + 2, new MarsTerrain.Column());
		};
	}

	private Holder<Biome> pick(int blockX, int blockZ, MarsTerrain.Column column) {
		GEOGRAPHY.sample(blockX, blockZ, column);
		return this.biomes.getOrDefault(MarsGeography.classify(column), this.fallback);
	}

	@Override
	public void addDebugInfo(List<String> result, BlockPos feetPos, Climate.Sampler sampler) {
		MarsTerrain.Column c = GEOGRAPHY.sample(feetPos.getX(), feetPos.getZ());
		result.add(String.format("Mars: %.3f%s %.3f E  elev %.2f km (here %.2f km)  albedo %.2f  rough %.0f m",
			Math.abs(c.lat), c.lat >= 0 ? "N" : "S", c.lon, c.baseElevation / 1000.0,
			MarsProjection.elevationOfY(feetPos.getY()) / 1000.0, c.albedo, c.roughness));
	}
}
