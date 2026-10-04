package io.github.avi130805.redplanet.gametest;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.mars.geo.MarsLandmarks;
import io.github.avi130805.redplanet.mars.geo.MarsLandmarks.Landmark;
import io.github.avi130805.redplanet.registry.RPBlocks;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Generates real Mars terrain (MOLA heights, the geographic biome source and the surface rule) at known landmarks
 * and checks it against the map: the gametest preset adds {@code redplanet-gametest:mars_terrain}, a copy of the
 * Mars dimension stem, next to the flat Mars used by the physics tests. Elevations: docs/SCIENCE.md section 14
 * (y = 192 + elevation / 100 m).
 */
public class MarsTerrainGameTests {
	private static final ResourceKey<Level> TERRAIN = ResourceKey.create(Registries.DIMENSION,
		Identifier.fromNamespaceAndPath("redplanet-gametest", "mars_terrain"));

	private record Column(int surfaceY, BlockState top, String biome) {
	}

	private static Column column(GameTestHelper helper, String landmarkId) {
		ServerLevel level = helper.getLevel().getServer().getLevel(TERRAIN);
		helper.assertTrue(level != null, "redplanet-gametest:mars_terrain missing (world preset override)");
		Landmark landmark = MarsLandmarks.byId(landmarkId).orElseThrow();
		int x = (int) Math.floor(landmark.x());
		int z = (int) Math.floor(landmark.z());
		level.getChunk(x >> 4, z >> 4); // generates the chunk (and its neighbourhood) on this thread
		int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
		BlockPos top = new BlockPos(x, surface - 1, z);
		String biome = level.getBiome(top).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
		Column column = new Column(surface, level.getBlockState(top), biome);
		RedPlanet.LOGGER.info("Mars terrain at {} ({}, {}): surface y={} ({} m), top {}, biome {}", landmark.name(), x, z, surface,
			(surface - 192) * 100, column.top(), biome);
		return column;
	}

	private static void assertSurface(GameTestHelper helper, String landmark, Column c, int minY, int maxY) {
		helper.assertTrue(c.surfaceY() >= minY && c.surfaceY() <= maxY,
			landmark + ": surface at y=" + c.surfaceY() + ", expected " + minY + ".." + maxY);
	}

	private static void assertBiome(GameTestHelper helper, String landmark, Column c, String biome) {
		helper.assertTrue(c.biome().equals(RedPlanet.MOD_ID + ":" + biome), landmark + ": biome " + c.biome() + ", expected " + biome);
	}

	private static void assertTop(GameTestHelper helper, String landmark, Column c, Block block) {
		helper.assertTrue(c.top().is(block), landmark + ": top block " + c.top() + ", expected " + block);
	}

	@GameTest(maxTicks = 200)
	public void olympusMonsTowersOverEverything(GameTestHelper helper) {
		Column c = column(helper, "olympus_mons");
		// Summit plateau and caldera: 18-21 km above the datum.
		assertSurface(helper, "Olympus Mons", c, 355, 412);
		assertBiome(helper, "Olympus Mons", c, "shield_volcano");
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void hellasIsTheDeepestBasin(GameTestHelper helper) {
		Column c = column(helper, "hellas_planitia");
		// Hellas floor: about -7 km.
		assertSurface(helper, "Hellas", c, 105, 140);
		assertBiome(helper, "Hellas", c, "impact_basin");
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void curiosityLandedOnGalesFloor(GameTestHelper helper) {
		Column c = column(helper, "curiosity");
		// Bradbury Landing: -4.5 km.
		assertSurface(helper, "Bradbury Landing", c, 135, 160);
		assertBiome(helper, "Bradbury Landing", c, "gale_mound");
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void opportunityRollsOnBlueberries(GameTestHelper helper) {
		Column c = column(helper, "opportunity");
		// Meridiani Planum: about -1.4 km, a hematite spherule lag at the surface.
		assertSurface(helper, "Meridiani", c, 165, 190);
		assertBiome(helper, "Meridiani", c, "meridiani_planum");
		assertTop(helper, "Meridiani", c, RPBlocks.HEMATITE_SPHERULE_REGOLITH);
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void perseveranceLandedInJezero(GameTestHelper helper) {
		Column c = column(helper, "perseverance");
		// Jezero floor: about -2.6 km.
		assertSurface(helper, "Jezero", c, 155, 180);
		assertBiome(helper, "Jezero", c, "jezero_delta");
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void northPoleIsIce(GameTestHelper helper) {
		Column c = column(helper, "planum_boreum");
		assertBiome(helper, "Planum Boreum", c, "north_polar_cap");
		assertTop(helper, "Planum Boreum", c, RPBlocks.POLAR_WATER_ICE);
		helper.succeed();
	}
}
