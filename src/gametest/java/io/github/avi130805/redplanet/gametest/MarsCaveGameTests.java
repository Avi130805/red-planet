package io.github.avi130805.redplanet.gametest;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.life.RPLifeBlocks;
import io.github.avi130805.redplanet.mars.geo.MarsProjection;
import io.github.avi130805.redplanet.mars.geo.MarsTerrain;
import io.github.avi130805.redplanet.registry.RPBlocks;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.BonemealSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * The cave layer (fiction, DESIGN.md section 8.3): where the cave biomes lie under the real map, and that the cave
 * features grow as designed. Biomes are read from the real Mars stem ({@code redplanet-gametest:mars_terrain});
 * features are placed by hand in a 16-block test box.
 */
public class MarsCaveGameTests {
	private static final ResourceKey<Level> TERRAIN = ResourceKey.create(Registries.DIMENSION,
		Identifier.fromNamespaceAndPath("redplanet-gametest", "mars_terrain"));
	private static final String BOX = "redplanet-gametest:empty16";
	/** The same seedless geography the biome source uses. */
	private static final MarsTerrain GEOGRAPHY = new MarsTerrain(0L);

	private static BiomeResolver resolver(GameTestHelper helper) {
		ServerLevel level = helper.getLevel().getServer().getLevel(TERRAIN);
		helper.assertTrue(level != null, "redplanet-gametest:mars_terrain missing (world preset override)");
		// MarsBiomeSource reads only geography, never the climate sampler (26.3 builds one per SamplerContext).
		return level.getChunkSource().getGenerator().getBiomeSource().createResolver(null);
	}

	private static String biome(BiomeResolver resolver, int x, int y, int z) {
		return resolver.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z)).unwrapKey()
			.map(k -> k.identifier().getPath()).orElse("?");
	}

	/** Share of quarts of {@code biome} in a 7x7 grid of columns (16 blocks apart) between two depths below the surface. */
	private static double share(BiomeResolver resolver, double lat, double lon, String biome, int minDepth, int maxDepth) {
		int cx = (int) Math.floor(MarsProjection.xOf(lon));
		int cz = (int) Math.floor(MarsProjection.zOf(lat));
		int hits = 0;
		int total = 0;
		for (int gx = -3; gx <= 3; gx++) {
			for (int gz = -3; gz <= 3; gz++) {
				int x = cx + gx * 16;
				int z = cz + gz * 16;
				double surface = GEOGRAPHY.sample(x, z).surfaceY;
				for (int depth = minDepth; depth <= maxDepth; depth += 4) {
					int y = (int) Math.floor(surface) - depth;
					if (y < 40) {
						continue; // the Arean deep
					}
					total++;
					if (biome(resolver, x, y, z).equals(biome)) {
						hits++;
					}
				}
			}
		}
		return total == 0 ? 0.0 : (double) hits / total;
	}

	private static void assertShare(GameTestHelper helper, String where, double share, double min, double max) {
		RedPlanet.LOGGER.info("Cave biome share {}: {}", where, String.format("%.2f", share));
		helper.assertTrue(share >= min && share <= max, where + ": share " + share + ", expected " + min + ".." + max);
	}

	// ------------------------------------------------------------------------------------------- where caves lie

	@GameTest(maxTicks = 100)
	public void lichenHollowsLieUnderTharsis(GameTestHelper helper) {
		BiomeResolver r = resolver(helper);
		assertShare(helper, "lichen hollows under the Tharsis plains", share(r, -5.0, 250.0, "lichen_hollows", 10, 198), 0.25, 0.95);
		assertShare(helper, "lichen hollows under Olympus Mons", share(r, 18.65, 226.2, "lichen_hollows", 10, 198), 0.25, 0.95);
		helper.succeed();
	}

	@GameTest(maxTicks = 100)
	public void brineGrottoesLieUnderTheIce(GameTestHelper helper) {
		BiomeResolver r = resolver(helper);
		assertShare(helper, "brine grottoes under Planum Boreum", share(r, 85.0, 30.0, "brine_grottoes", 6, 138), 0.25, 0.95);
		helper.succeed();
	}

	@GameTest(maxTicks = 100)
	public void gypsumGeodesLieUnderTheSulfates(GameTestHelper helper) {
		BiomeResolver r = resolver(helper);
		assertShare(helper, "gypsum geodes under Gale", share(r, -5.37, 137.81, "gypsum_geodes", 12, 138), 0.25, 0.95);
		assertShare(helper, "gypsum geodes under Meridiani", share(r, -1.95, 354.47, "gypsum_geodes", 12, 138), 0.25, 0.95);
		helper.succeed();
	}

	@GameTest(maxTicks = 100)
	public void highlandCrustIsPlainRock(GameTestHelper helper) {
		BiomeResolver r = resolver(helper);
		// Noachis Terra: no habitat above the deep.
		for (String cave : new String[]{"lichen_hollows", "brine_grottoes", "gypsum_geodes"}) {
			assertShare(helper, cave + " under Noachis Terra", share(r, -30.0, 20.0, cave, 10, 140), 0.0, 0.0);
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 100)
	public void areanDeepLiesAboveBedrockAndCarversReadTheSurface(GameTestHelper helper) {
		BiomeResolver r = resolver(helper);
		double[][] places = {{-5.37, 137.81}, {85.0, 30.0}, {-30.0, 20.0}, {18.65, 226.2}};
		for (double[] p : places) {
			int x = (int) Math.floor(MarsProjection.xOf(p[1]));
			int z = (int) Math.floor(MarsProjection.zOf(p[0]));
			for (int y = 6; y <= 26; y += 4) {
				helper.assertValueEqual(biome(r, x, y, z), "arean_deep", "biome at y=" + y + " under " + p[0] + ", " + p[1]);
			}
			// Carvers come from the biome at quart y 0: that must stay the surface biome.
			String bottom = biome(r, x, 1, z);
			String surface = biome(r, x, (int) Math.floor(GEOGRAPHY.sample(x, z).surfaceY) - 1, z);
			helper.assertValueEqual(bottom, surface, "bottom-row biome under " + p[0] + ", " + p[1]);
		}
		helper.succeed();
	}

	// --------------------------------------------------------------------------------------------- cave features

	private static boolean placeFeature(GameTestHelper helper, String id, BlockPos relative) {
		ServerLevel level = helper.getLevel();
		Feature feature = level.registryAccess().lookupOrThrow(Registries.FEATURE)
			.getValueOrThrow(ResourceKey.create(Registries.FEATURE, RedPlanet.id(id)));
		return feature.place(level, level.getChunkSource().getGenerator(), level.getRandom(), helper.absolutePos(relative));
	}

	private static void fill(GameTestHelper helper, BlockState state, int x0, int y0, int z0, int x1, int y1, int z1) {
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					helper.setBlock(new BlockPos(x, y, z), state);
				}
			}
		}
	}

	private static int count(GameTestHelper helper, net.minecraft.world.level.block.Block block, int yMin, int yMax) {
		int n = 0;
		for (int x = 0; x < 16; x++) {
			for (int y = yMin; y <= yMax; y++) {
				for (int z = 0; z < 16; z++) {
					if (helper.getBlockState(new BlockPos(x, y, z)).is(block)) {
						n++;
					}
				}
			}
		}
		return n;
	}

	@GameTest(structure = BOX, maxTicks = 40)
	public void rustcapGrowsToFitTheCave(GameTestHelper helper) {
		// A tube 9 blocks tall: ember moss floor at y=1, rock roof at y=11.
		fill(helper, RPLifeBlocks.EMBER_MOSS.defaultBlockState(), 2, 1, 2, 13, 1, 13);
		fill(helper, RPBlocks.MARS_STONE.defaultBlockState(), 0, 11, 0, 15, 11, 15);
		boolean grew = false;
		for (int attempt = 0; attempt < 8 && !grew; attempt++) {
			grew = placeFeature(helper, "rustcap", new BlockPos(8, 2, 8));
		}
		helper.assertTrue(grew, "the rustcap did not grow in a 9-block-tall cave");
		helper.assertBlockPresent(RPLifeBlocks.RUSTCAP_STEM, new BlockPos(8, 2, 8));
		helper.assertTrue(count(helper, RPLifeBlocks.RUSTCAP_CAP, 2, 10) > 8, "no cap");
		helper.assertTrue(count(helper, RPLifeBlocks.RUSTCAP_GILLS, 2, 10) > 0, "no glowing gills");
		for (net.minecraft.world.level.block.Block b : new net.minecraft.world.level.block.Block[]{
			RPLifeBlocks.RUSTCAP_CAP, RPLifeBlocks.RUSTCAP_GILLS, RPLifeBlocks.RUSTCAP_STEM}) {
			helper.assertTrue(count(helper, b, 12, 15) == 0, "the fungus grew through the roof: " + b);
		}
		helper.succeed();
	}

	@GameTest(structure = BOX, maxTicks = 40, skyAccess = true)
	public void rustcapSproutGrowsWithBonemealOnlyOnEmberMoss(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		helper.setBlock(new BlockPos(8, 1, 8), RPLifeBlocks.EMBER_MOSS);
		helper.setBlock(new BlockPos(8, 2, 8), RPLifeBlocks.RUSTCAP_FUNGUS);
		BlockPos sprout = helper.absolutePos(new BlockPos(8, 2, 8));
		BlockState state = level.getBlockState(sprout);
		var fungus = (net.minecraft.world.level.block.BonemealableBlock) state.getBlock();
		helper.assertTrue(fungus.isValidBonemealTarget(level, sprout, state, BonemealSource.INTERACTION), "sprout on ember moss");
		fungus.performBonemeal(level, level.getRandom(), sprout, state, BonemealSource.INTERACTION);
		helper.assertBlockPresent(RPLifeBlocks.RUSTCAP_STEM, new BlockPos(8, 2, 8));
		// On ordinary rock a sprout can't be planted at all.
		helper.setBlock(new BlockPos(3, 1, 3), RPBlocks.MARS_STONE);
		helper.assertFalse(RPLifeBlocks.RUSTCAP_FUNGUS.defaultBlockState().canSurvive(level, helper.absolutePos(new BlockPos(3, 2, 3))),
			"rustcap sprouts need ember moss");
		helper.succeed();
	}

	@GameTest(structure = BOX, maxTicks = 40)
	public void seleniteBeamsGrowFromRockAndStayInside(GameTestHelper helper) {
		fill(helper, RPBlocks.MUDSTONE.defaultBlockState(), 0, 0, 0, 15, 0, 15);
		fill(helper, RPBlocks.MUDSTONE.defaultBlockState(), 0, 12, 0, 15, 12, 15);
		int grown = 0;
		for (int attempt = 0; attempt < 6; attempt++) {
			if (placeFeature(helper, "selenite_crystal", new BlockPos(8, 1, 8))) {
				grown++;
			}
		}
		helper.assertTrue(grown > 0, "no selenite beam grew");
		int selenite = count(helper, RPLifeBlocks.SELENITE_BLOCK, 0, 15);
		helper.assertTrue(selenite >= 4, "selenite blocks: " + selenite);
		helper.succeed();
	}

	@GameTest
	public void saltSpiresAreSpeleothems(GameTestHelper helper) {
		helper.assertTrue(RPLifeBlocks.SALT_SPIRE.defaultBlockState().is(BlockTags.SPELEOTHEMS),
			"salt spires must be in #minecraft:speleothems (SpeleothemBlock checks it)");
		helper.assertTrue(RPLifeBlocks.RUSTCAP_STEM.defaultBlockState().is(BlockTags.LOGS), "rustcap stems are logs");
		helper.assertFalse(Blocks.AIR.defaultBlockState().is(RPLifeBlocks.SUPPORTS_RUSTCAP), "sanity");
		helper.succeed();
	}
}
