package io.github.avi130805.redplanet.gametest.client;

import java.nio.file.Path;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.mars.PlanetSettings;
import io.github.avi130805.redplanet.mars.astro.MarsAstronomy;
import io.github.avi130805.redplanet.registry.RPDimensions;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Lands at Curiosity's site in Gale crater for the noon sky over Aeolis Mons, then moves to InSight's flat plain
 * for the low Sun, the blue sunset, twilight and the star field at midnight, then a regional and a global dust storm. Screenshots go to
 * {@code build/gametest-screenshots} (curated copies in docs/screenshots/).
 */
public class MarsSkyClientGameTest implements FabricClientGameTest {
	private static final Path OUT = Path.of(System.getProperty("redplanet.gametest.screenshotDir", "screenshots"));
	private static final int MARS_RENDER_DISTANCE = 4;
	private static final int TERRAIN_TIMEOUT_TICKS = 4800;
	private static final int SETTLE_TICKS = 30;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder()
				.adjustSettings(s -> {
					s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
					s.setAllowCommands(true);
				})
				.create()) {
			sp.getConnection().waitForChunksRender();
			context.runOnClient(mc -> mc.options.renderDistance().set(6));
			shot(context, "sky_00_earth_noon");

			sp.getServer().runCommand("execute as @a run redplanet tp mars -4.59 137.44");
			context.waitFor(mc -> mc.level != null && RPDimensions.MARS.equals(mc.level.dimension())
				&& !(mc.gui.screen() instanceof LevelLoadingScreen), 2400);
			// Software rendering (llvmpipe under Xvfb) shares four cores with the integrated server generating 448-block-tall
			// Mars chunks, and the server paces chunk sending by how fast the client keeps up. Four chunks of terrain is
			// plenty for sky photographs: wait for that disc to arrive, then give the mesher a fixed time.
			context.runOnClient(mc -> mc.options.renderDistance().set(MARS_RENDER_DISTANCE));
			waitForTerrain(context);
			context.waitTicks(200);
			context.runOnClient(mc -> RedPlanet.LOGGER.info("Client gametest FPS on Mars: {}", mc.getFps()));
			sp.getServer().runCommand("gamemode spectator @a");
			// Photograph the sky, not the HUD; float a little above Bradbury Landing so the horizon clears the nearby knolls.
			context.runOnClient(mc -> {
				if (!mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle(); // F1
				}
			});
			sp.getServer().runCommand("execute as @a at @s run tp @s 8146.5 162 272.5");

			scene(context, sp, 6165, 0.0F, 8.0F, "sky_01_gale_noon_south");
			scene(context, sp, 6165, 180.0F, -75.0F, "sky_02_gale_noon_zenith");

			// The low Sun needs a flat horizon (the 10x vertical exaggeration turns Gale's knolls into ridges that hide it):
			// InSight's landing site in Elysium Planitia was picked for being one of the flattest places on Mars.
			sp.getServer().runCommand("execute as @a run redplanet tp mars 4.50 135.62");
			context.waitTicks(20);
			waitForTerrain(context);
			sp.getServer().runCommand("execute as @a at @s run tp @s ~ ~8 ~");
			context.waitTicks(100);
			scene(context, sp, 11950, 90.0F, -4.0F, "sky_03_low_sun_west");
			scene(context, sp, 12330, 90.0F, -2.0F, "sky_04_sunset_west");
			scene(context, sp, 12700, 90.0F, -6.0F, "sky_05_twilight_west");
			scene(context, sp, 13300, 90.0F, -10.0F, "sky_06_late_twilight_west");
			scene(context, sp, 18495, 180.0F, -60.0F, "sky_07_midnight_north");
			scene(context, sp, 18495, 0.0F, -45.0F, "sky_08_midnight_south");
			aimedScenes(context, sp);

			sp.getServer().runCommand("execute as @a at @s run redplanet weather dust regional");
			context.waitTicks(260);
			scene(context, sp, 6165, 45.0F, 2.0F, "sky_11_regional_storm_noon");
			sp.getServer().runCommand("redplanet weather dust global");
			context.waitTicks(260);
			scene(context, sp, 6165, 45.0F, 2.0F, "sky_12_global_storm_noon");
			scene(context, sp, 6165, 180.0F, -60.0F, "sky_13_global_storm_sun");
			sp.getServer().runCommand("redplanet weather dust clear");
		}
	}

	/**
	 * Waits until every chunk within {@link #MARS_RENDER_DISTANCE} (a disc, as the server sends them) is on the client,
	 * logging progress; gives up quietly after {@link #TERRAIN_TIMEOUT_TICKS} so a slow machine still gets its sky shots.
	 */
	private static void waitForTerrain(ClientGameTestContext context) {
		long start = System.nanoTime();
		int total = 0;
		int loaded = 0;
		for (int waited = 0; waited <= TERRAIN_TIMEOUT_TICKS; waited += 20) {
			int[] counts = context.computeOnClient(MarsSkyClientGameTest::countTerrainChunks);
			loaded = counts[0];
			total = counts[1];
			if (loaded == total) {
				break;
			}
			if (waited % 400 == 0) {
				RedPlanet.LOGGER.info("Waiting for Mars terrain: {}/{} chunks after {} ticks", loaded, total, waited);
			}
			context.waitTicks(20);
		}
		RedPlanet.LOGGER.info("Mars terrain on the client: {}/{} chunks in {} s", loaded, total, (System.nanoTime() - start) / 1_000_000_000L);
	}

	private static int[] countTerrainChunks(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			return new int[]{0, 1};
		}
		int r = Math.min(MARS_RENDER_DISTANCE, mc.options.getEffectiveRenderDistance());
		ChunkPos centre = mc.player.chunkPosition();
		int loaded = 0;
		int total = 0;
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				if (dx * dx + dz * dz > r * r) {
					continue;
				}
				total++;
				if (level.getChunk(centre.x() + dx, centre.z() + dz, ChunkStatus.FULL, false) != null) {
					loaded++;
				}
			}
		}
		return new int[]{loaded, total};
	}

	/**
	 * Finds, from the real orbits, a moment with Phobos high in a dark sky and one with Earth as an evening or morning
	 * star, and points the camera at each through a 30-degree "telephoto" field (both are true size: Phobos is ~0.2
	 * degrees, Earth a point).
	 */
	private static void aimedScenes(ClientGameTestContext context, TestSingleplayerContext sp) {
		PlanetSettings s = sp.getServer().computeOnServer(server -> PlanetSettings.of(server.getLevel(RPDimensions.MARS)));
		double lat = 4.50;
		int phobosTick = -1;
		int earthTick = -1;
		for (int t = 12400; t < 12400 + 2 * 24660 && (phobosTick < 0 || earthTick < 0); t += 40) {
			MarsAstronomy.Sky sky = MarsAstronomy.compute(t + SETTLE_TICKS, 0.0, lat, s.startLs(), s.yearCompression(), s.earthPhaseAtStartDeg(),
				s.moonPhaseSeed());
			if (phobosTick < 0 && sky.sunAltitudeDeg() < -18.0 && sky.phobos()[1] > Math.sin(Math.toRadians(35.0)) && !sky.phobosEclipsed()) {
				phobosTick = t;
			}
			if (earthTick < 0 && sky.sunAltitudeDeg() < -8.0 && sky.earth()[1] > Math.sin(Math.toRadians(6.0))) {
				earthTick = t;
			}
		}
		context.runOnClient(mc -> mc.options.fov().set(30));
		aim(context, sp, s, lat, phobosTick, MarsAstronomy.Sky::phobos, "sky_09_phobos_telephoto");
		aim(context, sp, s, lat, earthTick, MarsAstronomy.Sky::earth, "sky_10_earth_evening_star_telephoto");
		context.runOnClient(mc -> mc.options.fov().set(70));
	}

	private static void aim(ClientGameTestContext context, TestSingleplayerContext sp, PlanetSettings s, double lat, int tick,
			java.util.function.Function<MarsAstronomy.Sky, double[]> body, String name) {
		if (tick < 0) {
			RedPlanet.LOGGER.warn("No moment found for {} in two sols; skipped", name);
			return;
		}
		double[] d = body.apply(MarsAstronomy.compute(tick + SETTLE_TICKS, 0.0, lat, s.startLs(), s.yearCompression(), s.earthPhaseAtStartDeg(),
			s.moonPhaseSeed()));
		// World axes +x east, +y up, +z south; Minecraft yaw 0 faces south and 90 faces west, negative pitch looks up.
		float yaw = (float) Math.toDegrees(Math.atan2(-d[0], d[2]));
		float pitch = (float) -Math.toDegrees(Math.asin(d[1]));
		RedPlanet.LOGGER.info("{}: clock {} -> yaw {}, pitch {}", name, tick, yaw, pitch);
		scene(context, sp, tick, yaw, pitch, name);
	}

	private static void scene(ClientGameTestContext context, TestSingleplayerContext sp, int clockTick, float yaw, float pitch, String name) {
		sp.getServer().runCommand("execute in redplanet:mars run time set " + clockTick);
		sp.getServer().runCommand("execute as @a at @s run tp @s ~ ~ ~ " + yaw + " " + pitch);
		context.waitTicks(SETTLE_TICKS); // attribute probes ease between ticks; let the sky settle
		shot(context, name);
	}

	private static void shot(ClientGameTestContext context, String name) {
		context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withSize(1280, 720).withDestinationDir(OUT));
	}
}
