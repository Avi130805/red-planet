package io.github.avi130805.redplanet.gametest.client;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.mars.MarsConditions;
import io.github.avi130805.redplanet.mars.PlanetSettings;
import io.github.avi130805.redplanet.mars.astro.MarsAstronomy;
import io.github.avi130805.redplanet.mars.geo.MarsProjection;
import io.github.avi130805.redplanet.mars.weather.DustDevil;
import io.github.avi130805.redplanet.registry.RPDimensions;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.level.ServerPlayer;

/**
 * Lands at Curiosity's site in Gale crater for the noon sky over Aeolis Mons, then moves to InSight's flat plain
 * for the low Sun, the blue sunset, twilight and the star field at midnight, then a regional and a global dust storm. Screenshots go to
 * {@code build/gametest-screenshots} (curated copies in docs/screenshots/).
 */
public class MarsSkyClientGameTest implements FabricClientGameTest {
	private static final int SETTLE_TICKS = 30;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!ClientTestSupport.enabled("sky")) {
			return;
		}
		try (TestSingleplayerContext sp = context.worldBuilder()
				.adjustSettings(s -> {
					s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
					s.setAllowCommands(true);
				})
				.create()) {
			sp.getConnection().waitForChunksRender();
			context.runOnClient(mc -> mc.options.renderDistance().set(6));
			ClientTestSupport.shot(context, "sky_00_earth_noon");

			sp.getServer().runCommand("execute as @a run redplanet tp mars -4.59 137.44");
			context.waitFor(mc -> mc.level != null && RPDimensions.MARS.equals(mc.level.dimension())
				&& !(mc.gui.screen() instanceof LevelLoadingScreen), 2400);
			// Software rendering (llvmpipe under Xvfb) shares four cores with the integrated server generating 448-block-tall
			// Mars chunks, and the server paces chunk sending by how fast the client keeps up. Four chunks of terrain is
			// plenty for sky photographs: wait for that disc to arrive, then give the mesher a fixed time.
			context.runOnClient(mc -> mc.options.renderDistance().set(ClientTestSupport.MARS_RENDER_DISTANCE));
			ClientTestSupport.waitForTerrain(context);
			context.waitTicks(200);
			context.runOnClient(mc -> RedPlanet.LOGGER.info("Client gametest FPS on Mars: {}", mc.getFps()));
			sp.getServer().runCommand("gamemode spectator @a");
			// Photograph the sky, not the HUD; float a little above Bradbury Landing so the horizon clears the nearby knolls.
			ClientTestSupport.hideHud(context);
			sp.getServer().runCommand("execute as @a at @s run tp @s 8146.5 162 272.5");

			scene(context, sp, 6165, 0.0F, 8.0F, "sky_01_gale_noon_south");
			scene(context, sp, 6165, 180.0F, -75.0F, "sky_02_gale_noon_zenith");

			// The low Sun needs a flat horizon (the 10x vertical exaggeration turns Gale's knolls into ridges that hide it):
			// InSight's landing site in Elysium Planitia was picked for being one of the flattest places on Mars.
			sp.getServer().runCommand("execute as @a run redplanet tp mars 4.50 135.62");
			context.waitTicks(20);
			ClientTestSupport.waitForTerrain(context);
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
			context.waitTicks(240); // let the dust settle

			// A dust devil crossing the plain at noon.
			sp.getServer().runCommand("execute in redplanet:mars run time set 6165");
			sp.getServer().runCommand("execute as @a at @s run redplanet weather devil");
			context.waitTicks(150); // spinning up
			double[] devil = sp.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				return server.getLevel(RPDimensions.MARS).getEntitiesOfClass(DustDevil.class, player.getBoundingBox().inflate(80.0)).stream()
					.findFirst()
					.map(d -> new double[]{d.getX() - player.getX(), d.getY() + d.columnHeight() * 0.35 - player.getEyeY(), d.getZ() - player.getZ()})
					.orElse(null);
			});
			if (devil != null) {
				float yaw = (float) Math.toDegrees(Math.atan2(-devil[0], devil[2]));
				float pitch = (float) -Math.toDegrees(Math.atan2(devil[1], Math.sqrt(devil[0] * devil[0] + devil[2] * devil[2])));
				sp.getServer().runCommand("execute as @a at @s run tp @s ~ ~ ~ " + yaw + " " + pitch);
				context.waitTicks(10);
				ClientTestSupport.shot(context, "sky_14_dust_devil");
			} else {
				RedPlanet.LOGGER.warn("No dust devil to photograph");
			}
		}
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
		// Phobos is up most nights. Earth can sit close to the Sun for many months (its synodic period from Mars is
		// 780 days), so search a whole synodic period for a dark sky with Earth well up.
		for (int t = 12400; t < 12400 + 2 * 24660 && phobosTick < 0; t += 40) {
			MarsAstronomy.Sky sky = MarsAstronomy.compute(t + SETTLE_TICKS, 0.0, lat, s.startLs(), s.yearCompression(), s.earthPhaseAtStartDeg(),
				s.moonPhaseSeed());
			if (sky.sunAltitudeDeg() < -18.0 && sky.phobos()[1] > Math.sin(Math.toRadians(35.0)) && !sky.phobosEclipsed()) {
				phobosTick = t;
			}
		}
		for (long t = 12400; t < 12400 + 760L * 24660 && earthTick < 0; t += 200) {
			MarsAstronomy.Sky sky = MarsAstronomy.compute(t + SETTLE_TICKS, 0.0, lat, s.startLs(), s.yearCompression(), s.earthPhaseAtStartDeg(),
				s.moonPhaseSeed());
			if (sky.sunAltitudeDeg() < -10.0 && sky.earth()[1] > Math.sin(Math.toRadians(8.0))) {
				earthTick = (int) t;
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
			RedPlanet.LOGGER.warn("No moment found for {} within the search; skipped", name);
			return;
		}
		// Client gametest worlds freeze the daylight cycle, so the clock stays at the tick we set.
		double[] d = body.apply(MarsAstronomy.compute(tick, 0.0, lat, s.startLs(), s.yearCompression(), s.earthPhaseAtStartDeg(),
			s.moonPhaseSeed()));
		// World axes +x east, +y up, +z south; Minecraft yaw 0 faces south and 90 faces west, negative pitch looks up.
		float yaw = (float) Math.toDegrees(Math.atan2(-d[0], d[2]));
		float pitch = (float) -Math.toDegrees(Math.asin(d[1]));
		RedPlanet.LOGGER.info("{}: clock {} -> yaw {}, pitch {} (server settings {})", name, tick, yaw, pitch, s);
		scene(context, sp, tick, yaw, pitch, name);
		// Cross-check: the client's own sky computation at the moment of the photograph.
		context.runOnClient(mc -> {
			PlanetSettings cs = PlanetSettings.of(mc.level);
			long clock = MarsConditions.clockTicks(mc.level);
			double clientLat = MarsProjection.latitude(mc.gameRenderer.mainCamera().position().z);
			double[] cd = body.apply(MarsAstronomy.compute(clock, 0.0, clientLat, cs.startLs(), cs.yearCompression(), cs.earthPhaseAtStartDeg(),
				cs.moonPhaseSeed()));
			RedPlanet.LOGGER.info("{} on the client: clock {}, lat {}, settings {} -> yaw {}, pitch {}", name, clock, clientLat, cs,
				Math.toDegrees(Math.atan2(-cd[0], cd[2])), -Math.toDegrees(Math.asin(cd[1])));
		});
	}

	private static void scene(ClientGameTestContext context, TestSingleplayerContext sp, int clockTick, float yaw, float pitch, String name) {
		sp.getServer().runCommand("execute in redplanet:mars run time set " + clockTick);
		sp.getServer().runCommand("execute as @a at @s run tp @s ~ ~ ~ " + yaw + " " + pitch);
		context.waitTicks(SETTLE_TICKS); // attribute probes ease between ticks; let the sky settle
		ClientTestSupport.shot(context, name);
	}

}
