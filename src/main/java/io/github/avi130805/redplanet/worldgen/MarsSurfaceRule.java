package io.github.avi130805.redplanet.worldgen;

import com.mojang.serialization.MapCodec;

import io.github.avi130805.redplanet.mars.geo.MarsProjection;
import io.github.avi130805.redplanet.mars.geo.MarsTerrain;
import io.github.avi130805.redplanet.registry.RPBlocks;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;

/**
 * {@code redplanet:mars_surface}: the block layering of the Martian crust, per column, from biome role and
 * geography: a surface layer (regolith, dust, sand, ice...), a filler layer, then bedrock-type rock with strata,
 * a bedrock floor, ground ice poleward of ~45 degrees, buried glacier ice in the mid-latitude glacier belt, and
 * bare rock on steep slopes and cliffs. Geology: docs/SCIENCE.md, "Geology".
 */
public record MarsSurfaceRule() implements MaterialRule {
	public static final MapCodec<MarsSurfaceRule> CODEC = MapCodec.unit(MarsSurfaceRule::new);
	private static final MarsTerrain GEOGRAPHY = new MarsTerrain(0L);

	@Override
	public RuleEvaluator compile(MaterialRuleContext context) {
		return new Evaluator(context);
	}

	@Override
	public MapCodec<MarsSurfaceRule> codec() {
		return CODEC;
	}

	private static final class Evaluator implements RuleEvaluator {
		private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();

		private final MaterialRuleContext context;
		private final MarsTerrain.Column column = new MarsTerrain.Column();
		private long columnKey = Long.MIN_VALUE;
		private MarsBiome biome = MarsBiome.CRATERED_HIGHLANDS;
		private int slope;

		Evaluator(MaterialRuleContext context) {
			this.context = context;
		}

		@Override
		public BlockState tryApply(int x, int y, int z) {
			long key = ((long) x << 32) ^ (z & 0xffffffffL);
			if (key != this.columnKey) {
				this.columnKey = key;
				GEOGRAPHY.sample(x, z, this.column);
				this.biome = MarsGeography.classify(this.column);
				this.slope = Math.max(Math.abs(this.context.surfaceGradientX()), Math.abs(this.context.surfaceGradientZ()));
			}
			int minY = MarsProjection.MIN_Y;
			if (y < minY + 5 && hash(x, y, z) % 5 >= y - minY) {
				return BEDROCK;
			}
			int depth = this.context.stoneDepthAbove(); // 1 = top solid block
			boolean steep = this.slope >= 4; // >= 2 blocks rise per block: a cliff
			int surfaceDepth = Math.max(1, this.context.surfaceDepth());
			double absLat = Math.abs(this.column.lat);

			if (steep && depth <= surfaceDepth + 1) {
				return cliff(x, y, z);
			}
			return switch (this.biome) {
				case NORTH_POLAR_CAP -> polar(x, y, z, depth, false);
				case SOUTH_POLAR_CAP -> polar(x, y, z, depth, true);
				case DUNE_FIELD -> layered(depth, RPBlocks.BASALTIC_SAND.defaultBlockState(), 2 + surfaceDepth,
					RPBlocks.BASALTIC_SAND.defaultBlockState(), x, y, z);
				case DUSTY_HIGHLANDS, IMPACT_BASIN -> layered(depth, RPBlocks.MARS_DUST.defaultBlockState(), 1 + surfaceDepth / 2,
					RPBlocks.REGOLITH.defaultBlockState(), x, y, z);
				case SHIELD_VOLCANO -> volcano(depth, surfaceDepth, x, y, z);
				case VOLCANIC_PLAINS -> depth == 1 && hash(x, 0, z) % 3 == 0
					? RPBlocks.MARS_BASALT.defaultBlockState()
					: layered(depth, RPBlocks.REGOLITH.defaultBlockState(), 1, RPBlocks.MARS_BASALT.defaultBlockState(), x, y, z);
				case MERIDIANI_PLANUM -> depth == 1 ? RPBlocks.HEMATITE_SPHERULE_REGOLITH.defaultBlockState()
					: depth <= surfaceDepth ? RPBlocks.REGOLITH.defaultBlockState() : sediment(y, x, z);
				case GALE_MOUND -> depth == 1 ? (this.column.dunes > 0.3 ? RPBlocks.BASALTIC_SAND : RPBlocks.REGOLITH).defaultBlockState()
					: depth <= 2 ? RPBlocks.REGOLITH.defaultBlockState() : galeStrata(y);
				case JEZERO_DELTA -> depth == 1 ? RPBlocks.REGOLITH.defaultBlockState() : jezeroStrata(x, y, z);
				case CANYON -> depth == 1 ? (hash(x, 1, z) % 4 == 0 ? RPBlocks.BASALTIC_SAND : RPBlocks.REGOLITH).defaultBlockState()
					: depth <= 2 ? RPBlocks.REGOLITH.defaultBlockState() : sediment(y, x, z);
				case MID_LATITUDE_GLACIERS -> depth <= 2 ? RPBlocks.REGOLITH.defaultBlockState()
					: depth <= 10 + hash(x, 2, z) % 4 ? RPBlocks.WATER_ICE.defaultBlockState() : rock(x, y, z);
				case NORTHERN_PLAINS -> plains(depth, surfaceDepth, absLat, x, y, z);
				default -> crater(depth, surfaceDepth, absLat, x, y, z);
			};
		}

		private BlockState plains(int depth, int surfaceDepth, double absLat, int x, int y, int z) {
			if (depth == 1) {
				return RPBlocks.REGOLITH.defaultBlockState();
			}
			if (absLat > 45.0 && depth <= 2 + (absLat > 60.0 ? 6 : 3)) {
				return RPBlocks.ICE_RICH_REGOLITH.defaultBlockState(); // shallow ground ice (Phoenix, 68 N)
			}
			return depth <= surfaceDepth + 1 ? RPBlocks.REGOLITH.defaultBlockState() : rock(x, y, z);
		}

		private BlockState crater(int depth, int surfaceDepth, double absLat, int x, int y, int z) {
			if (depth == 1 && this.column.craterRim > 0.6 && hash(x, 3, z) % 3 == 0) {
				return RPBlocks.MARS_COBBLESTONE.defaultBlockState(); // blocky ejecta on fresh rims
			}
			if (depth > 1 && absLat > 50.0 && depth <= 4) {
				return RPBlocks.ICE_RICH_REGOLITH.defaultBlockState();
			}
			return depth <= surfaceDepth ? RPBlocks.REGOLITH.defaultBlockState() : rock(x, y, z);
		}

		private BlockState volcano(int depth, int surfaceDepth, int x, int y, int z) {
			// The upper flanks and summits of the big volcanoes are mantled in dust (very bright in TES albedo).
			if (depth == 1 && this.column.albedo > 0.25) {
				return RPBlocks.MARS_DUST.defaultBlockState();
			}
			return depth <= 1 ? RPBlocks.REGOLITH.defaultBlockState() : RPBlocks.MARS_BASALT.defaultBlockState();
		}

		private BlockState polar(int x, int y, int z, int depth, boolean south) {
			if (south && depth <= 1) {
				return RPBlocks.CO2_ICE.defaultBlockState();
			}
			if (depth <= 3) {
				return RPBlocks.POLAR_WATER_ICE.defaultBlockState();
			}
			if (depth <= 28) {
				// Polar layered deposits: ice-rich and dust-rich layers, a few blocks each.
				return Math.floorMod(y + (hash(x, 4, z) % 2), 5) < 3 ? RPBlocks.POLAR_WATER_ICE.defaultBlockState()
					: RPBlocks.POLAR_LAYERED_DEPOSIT.defaultBlockState();
			}
			return rock(x, y, z);
		}

		private BlockState cliff(int x, int y, int z) {
			return switch (this.biome) {
				case NORTH_POLAR_CAP, SOUTH_POLAR_CAP -> RPBlocks.POLAR_LAYERED_DEPOSIT.defaultBlockState();
				case CANYON, MERIDIANI_PLANUM -> sediment(y, x, z);
				case GALE_MOUND -> galeStrata(y);
				case JEZERO_DELTA -> jezeroStrata(x, y, z);
				case SHIELD_VOLCANO, VOLCANIC_PLAINS -> RPBlocks.MARS_BASALT.defaultBlockState();
				default -> rock(x, y, z);
			};
		}

		private static BlockState layered(int depth, BlockState top, int topDepth, BlockState filler, int x, int y, int z) {
			if (depth <= topDepth) {
				return top;
			}
			return depth <= topDepth + 3 ? filler : rock(x, y, z);
		}

		/** Crustal rock: Mars stone with occasional basalt sills. */
		private static BlockState rock(int x, int y, int z) {
			int band = Math.floorMod(y * 7 + (hash(x >> 3, 5, z >> 3) % 3), 23);
			return band < 3 ? RPBlocks.MARS_BASALT.defaultBlockState() : RPBlocks.MARS_STONE.defaultBlockState();
		}

		/** Layered sedimentary rock (sulfate-bearing layers, Meridiani/Valles Marineris interior deposits). */
		private static BlockState sediment(int y, int x, int z) {
			return Math.floorMod(y, 7) < 2 ? RPBlocks.MUDSTONE.defaultBlockState() : RPBlocks.LAYERED_SEDIMENT.defaultBlockState();
		}

		/** Gale crater: lake-bed mudstone below, Mount Sharp's sulfate layers above (~ -4 km). */
		private static BlockState galeStrata(int y) {
			double elevation = MarsProjection.elevationOfY(y);
			if (elevation < -4100.0) {
				return RPBlocks.MUDSTONE.defaultBlockState();
			}
			return Math.floorMod(y, 6) < 1 ? RPBlocks.MUDSTONE.defaultBlockState() : RPBlocks.LAYERED_SEDIMENT.defaultBlockState();
		}

		/** Jezero: delta sediments with clay-rich layers, carbonate-bearing rock near the base. */
		private static BlockState jezeroStrata(int x, int y, int z) {
			int band = Math.floorMod(y + (hash(x >> 2, 6, z >> 2) % 2), 8);
			if (band == 0) {
				return RPBlocks.SMECTITE_CLAY.defaultBlockState();
			}
			if (band == 5) {
				return RPBlocks.CARBONATE_ROCK.defaultBlockState();
			}
			return RPBlocks.DELTA_SEDIMENT.defaultBlockState();
		}

		/** Fast positional hash, non-negative. */
		private static int hash(int x, int y, int z) {
			int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
			h ^= h >>> 13;
			h *= 0x5bd1e995;
			h ^= h >>> 15;
			return h & 0x7fffffff;
		}
	}
}
