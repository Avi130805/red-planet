package io.github.avi130805.redplanet.gametest.client;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.gametest.client.trailer.CameraPath;
import io.github.avi130805.redplanet.gametest.client.trailer.Recorder;
import io.github.avi130805.redplanet.gametest.client.trailer.TrailerCamera;
import io.github.avi130805.redplanet.gametest.client.trailer.TrailerClock;
import io.github.avi130805.redplanet.mars.PlanetSettings;
import io.github.avi130805.redplanet.mars.astro.MarsAstronomy;
import io.github.avi130805.redplanet.mars.geo.MarsProjection;
import io.github.avi130805.redplanet.mars.weather.DustDevil;
import io.github.avi130805.redplanet.registry.RPDimensions;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Films the launch trailer's shots at 1080p (see tools/trailer/README.md). Runs only when asked for by name
 * ({@code -PclientTests=trailer}), never with the other client gametests: it takes hours under software rendering.
 * {@code -PtrailerShots=a,b} films only those shots. Each shot becomes {@code build/trailer/shots/<name>.mp4}.
 */
public class TrailerClientGameTest implements FabricClientGameTest {
	private final Map<String, Shot> shots = new LinkedHashMap<>();

	@FunctionalInterface
	private interface Shot {
		void film(ClientGameTestContext context, TestSingleplayerContext sp, Recorder recorder);
	}

	public TrailerClientGameTest() {
		this.shots.put("mars_sunset", this::marsSunset);
		this.shots.put("mars_gale", this::marsGale);
		this.shots.put("olympus_mons", this::olympusMons);
		this.shots.put("valles_marineris", this::vallesMarineris);
		this.shots.put("dust_devil", this::dustDevil);
		this.shots.put("phobos_night", this::phobosNight);
		this.shots.put("mars_storm", this::marsStorm);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		String only = System.getProperty("redplanet.gametest.only", "");
		if (Stream.of(only.split(",")).noneMatch(s -> s.trim().equalsIgnoreCase("trailer"))) {
			return; // only on request
		}
		Set<String> wanted = Stream.of(System.getProperty("redplanet.trailer.shots", "").split(","))
			.map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
		Path out = Path.of(System.getProperty("redplanet.trailer.out", "trailer"));
		try (TestSingleplayerContext sp = context.worldBuilder()
				.adjustSettings(s -> {
					s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
					s.setAllowCommands(true);
				})
				.create()) {
			awaitTerrain(context, sp);
			context.runOnClient(mc -> {
				mc.options.graphicsPreset().set(GraphicsPreset.FANCY);
				mc.options.renderDistance().set(8);
				mc.options.simulationDistance().set(8);
				mc.options.entityDistanceScaling().set(5.0);
				mc.options.chunkSectionFadeInTime().set(0.0); // no chunks fading in mid-shot
				mc.options.particles().set(ParticleStatus.ALL);
				mc.options.cloudStatus().set(CloudStatus.FANCY);
				mc.options.ambientOcclusion().set(true);
				mc.options.bobView().set(false);
				mc.options.vignette().set(true);
				mc.options.broadcastOptions(); // the server tracks entities out to the reported view distance
			});
			ClientTestSupport.hideHud(context);
			sp.getServer().runCommand("gamerule advance_time false");
			sp.getServer().runCommand("gamerule advance_weather false");
			sp.getServer().runCommand("weather clear");
			sp.getServer().runCommand("gamemode spectator @a");
			TrailerClock.start();
			Recorder recorder = new Recorder(context, sp, out);
			recorder.idleSmall();
			for (Map.Entry<String, Shot> shot : this.shots.entrySet()) {
				if (wanted.isEmpty() || wanted.contains(shot.getKey())) {
					RedPlanet.LOGGER.info("Trailer: filming {}", shot.getKey());
					recorder.unfreeze();
					shot.getValue().film(context, sp, recorder);
				}
			}
			recorder.unfreeze();
			TrailerClock.stop();
		}
	}

	// ------------------------------------------------------------------------------------------------ scenes

	/** Goes to Mars at a latitude and longitude and waits for the terrain around the player. */
	private static void toMars(ClientGameTestContext context, TestSingleplayerContext sp, double lat, double lon) {
		sp.getServer().runCommand(String.format(Locale.ROOT, "execute as @a run redplanet tp mars %.4f %.4f", lat, lon));
		context.waitFor(mc -> mc.level != null && RPDimensions.MARS.equals(mc.level.dimension())
			&& !(mc.gui.screen() instanceof LevelLoadingScreen), 2400);
		ClientTestSupport.waitForTerrain(context);
		awaitTerrain(context, sp);
	}

	/**
	 * Waits for the terrain around the player: first every chunk in the disc the server sends (Fabric's own wait checks
	 * a square, whose corners never arrive), then every loaded section meshed. Tall Mars chunks mesh slowly under
	 * software rendering, so allow up to 15 minutes for each, then film anyway.
	 */
	private static void awaitTerrain(ClientGameTestContext context, TestSingleplayerContext sp) {
		long start = System.nanoTime();
		for (int waited = 0; waited < 15 * 60 * 20; waited += 20) {
			int[] counts = context.computeOnClient(TrailerClientGameTest::discChunks);
			if (counts[0] >= counts[1]) {
				break;
			}
			if (waited % 1200 == 0) {
				RedPlanet.LOGGER.info("Trailer: {}/{} chunks after {} ticks", counts[0], counts[1], waited);
			}
			context.waitTicks(20);
		}
		try {
			sp.getConnection().waitForChunksRender(false, 15 * 60 * 20);
		} catch (AssertionError e) {
			RedPlanet.LOGGER.warn("Trailer: terrain still meshing after 15 minutes; filming anyway");
		}
		RedPlanet.LOGGER.info("Trailer: terrain ready in {} s", (System.nanoTime() - start) / 1_000_000_000L);
	}

	/** Chunks loaded, and chunks expected, in the disc of the render distance around the player. */
	private static int[] discChunks(net.minecraft.client.Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			return new int[]{0, 1};
		}
		int r = mc.options.getEffectiveRenderDistance();
		net.minecraft.world.level.ChunkPos centre = mc.player.chunkPosition();
		int loaded = 0;
		int total = 0;
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				if (dx * dx + dz * dz > r * r) {
					continue;
				}
				total++;
				if (mc.level.getChunk(centre.x() + dx, centre.z() + dz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) != null) {
					loaded++;
				}
			}
		}
		return new int[]{loaded, total};
	}

	/** The top of the ground at x, z in the player's level (generating it if needed). */
	private static int surface(TestSingleplayerContext sp, int x, int z) {
		return sp.getServer().computeOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			ServerLevel level = player.level();
			// Load (or generate) the chunk first: Level.getHeight answers the world's floor for chunks not loaded.
			level.getChunk(x >> 4, z >> 4);
			return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		});
	}

	private static Vec3 playerPos(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().position());
	}

	/** Teleports the player within their own world (a console /tp would take them to the overworld). */
	private static void tp(TestSingleplayerContext sp, Vec3 at) {
		sp.getServer().runCommand(String.format(Locale.ROOT, "execute as @a at @s run tp @s %.2f %.2f %.2f", at.x, at.y, at.z));
	}

	// ------------------------------------------------------------------------------------------------ shots

	/** A ground point on Mars at a latitude and longitude: block x and z, and the surface height there. */
	private static Vec3 marsGround(TestSingleplayerContext sp, double lat, double lon) {
		int x = (int) Math.floor(MarsProjection.xOf(lon));
		int z = (int) Math.floor(MarsProjection.zOf(lat));
		return new Vec3(x + 0.5, surface(sp, x, z), z + 0.5);
	}

	private static void marsClock(TestSingleplayerContext sp, int tick) {
		sp.getServer().runCommand("execute in redplanet:mars run time set " + tick);
	}

	/** Teleports there, waits for the terrain, and returns the ground point (re-measured once the chunks exist). */
	private static Vec3 goTo(ClientGameTestContext context, TestSingleplayerContext sp, double lat, double lon, double above) {
		toMars(context, sp, lat, lon);
		Vec3 g = marsGround(sp, lat, lon);
		tp(sp, g.add(0, above, 0));
		context.waitTicks(20);
		awaitTerrain(context, sp);
		return marsGround(sp, lat, lon);
	}

	/**
	 * Sunset at InSight's landing site, one of the flattest places on Mars: a landed Starship stands against the blue
	 * glow (Mars' sunsets are blue: fine dust scatters blue light forward, toward the Sun). The camera drifts past
	 * the ship at eye height.
	 */
	private void marsSunset(ClientGameTestContext context, TestSingleplayerContext sp, Recorder recorder) {
		Vec3 g = goTo(context, sp, 4.50, 135.62, 2);
		marsClock(sp, 12330);
		// The ship 70 blocks west, between the camera and the setting Sun.
		Vec3 ship = marsGround(sp, 4.50, 135.62 - 70.0 / MarsProjection.RADIUS_KM * 180.0 / Math.PI);
		sp.getServer().runCommand("gamemode creative @a");
		tp(sp, ship);
		context.waitTicks(10);
		sp.getServer().runCommand("execute as @a at @s run redplanet starship spawn ship");
		sp.getServer().runCommand("gamemode spectator @a");
		tp(sp, g.add(0, 2, 0));
		context.waitTicks(60);
		awaitTerrain(context, sp);
		CameraPath path = CameraPath.builder()
			.key(0.0, g.add(-4, 1.7, -14), ship.add(0, 26, 0), 52.0F)
			.key(4.0, g.add(-16, 2.4, 12), ship.add(0, 24, 0), 46.0F)
			.build();
		recorder.record("mars_sunset", 4.0, (mc, t, partial) -> path.at(t));
	}

	/** Gale crater from above Curiosity's landing site, rising toward Aeolis Mons (Mount Sharp) in the afternoon light. */
	private void marsGale(ClientGameTestContext context, TestSingleplayerContext sp, Recorder recorder) {
		Vec3 g = goTo(context, sp, -4.59, 137.44, 30);
		marsClock(sp, 10500);
		Vec3 peak = marsGround(sp, -5.08, 137.85);
		Vec3 toward = peak.subtract(g).multiply(1, 0, 1).normalize();
		Vec3 side = new Vec3(-toward.z, 0, toward.x);
		CameraPath path = CameraPath.builder()
			.key(0.0, g.add(0, 14, 0).subtract(toward.scale(20)).add(side.scale(-10)), peak.add(0, 6, 0), 60.0F)
			.key(4.0, g.add(0, 34, 0).add(toward.scale(10)).add(side.scale(8)), peak.add(0, 12, 0), 58.0F)
			.build();
		recorder.record("mars_gale", 4.0, (mc, t, partial) -> path.at(t));
	}

	/**
	 * Olympus Mons' basal escarpment: cliffs up to 8 km high around the volcano (80 blocks at the map's 10x vertical
	 * scale). The cliff is found by walking out from the summit and taking the steepest drop; the camera then glides
	 * along the foot of it.
	 */
	private void olympusMons(ClientGameTestContext context, TestSingleplayerContext sp, Recorder recorder) {
		Vec3 summit = goTo(context, sp, 18.65, 226.20, 10);
		marsClock(sp, 8200);
		// Toward the north-west, where the scarp is highest.
		Vec3 dir = new Vec3(-Math.sin(Math.toRadians(45)), 0, -Math.cos(Math.toRadians(45)));
		int best = 260;
		double bestDrop = 0;
		int[] h = new int[421];
		for (int i = 0; i <= 420; i += 4) {
			Vec3 p = summit.add(dir.scale(i));
			h[i] = surface(sp, (int) p.x, (int) p.z);
		}
		for (int i = 12; i <= 420; i += 4) {
			double drop = h[i - 12] - h[i];
			if (drop > bestDrop) {
				bestDrop = drop;
				best = i;
			}
		}
		RedPlanet.LOGGER.info("Trailer: Olympus Mons scarp {} blocks from the summit, {} blocks of drop over 12", best, bestDrop);
		Vec3 foot = summit.add(dir.scale(best + 40));
		foot = new Vec3(foot.x, surface(sp, (int) foot.x, (int) foot.z), foot.z);
		Vec3 cliff = summit.add(dir.scale(best - 10));
		cliff = new Vec3(cliff.x, surface(sp, (int) cliff.x, (int) cliff.z), cliff.z);
		tp(sp, foot.add(0, 20, 0));
		context.waitTicks(40);
		awaitTerrain(context, sp);
		Vec3 along = new Vec3(-dir.z, 0, dir.x);
		Vec3 a = foot.add(0, 12, 0).add(along.scale(-30));
		Vec3 b = foot.add(0, 22, 0).add(along.scale(30));
		CameraPath path = CameraPath.builder()
			.key(0.0, a, cliff.add(along.scale(-10)).add(0, -10, 0), 64.0F)
			.key(4.0, b, cliff.add(along.scale(10)).add(0, -6, 0), 60.0F)
			.build();
		recorder.record("olympus_mons", 4.0, (mc, t, partial) -> path.at(t));
	}

	/** Melas Chasma, in the middle of Valles Marineris: the camera low over the canyon floor, pushing along it. */
	private void vallesMarineris(ClientGameTestContext context, TestSingleplayerContext sp, Recorder recorder) {
		Vec3 g = goTo(context, sp, -10.2, 287.2, 20);
		marsClock(sp, 9000);
		Vec3 east = new Vec3(1, 0, 0);
		Vec3 far = g.add(east.scale(90));
		far = new Vec3(far.x, surface(sp, (int) far.x, (int) far.z), far.z);
		CameraPath path = CameraPath.builder()
			.key(0.0, g.add(0, 8, 0).add(east.scale(-25)), far.add(0, 14, 0), 66.0F)
			.key(4.0, g.add(0, 15, 0).add(east.scale(5)), far.add(0, 18, 0), 62.0F)
			.build();
		recorder.record("valles_marineris", 4.0, (mc, t, partial) -> path.at(t));
	}

	/** A dust devil crossing the plain on an afternoon in the dusty season, followed from thirty blocks away. */
	private void dustDevil(ClientGameTestContext context, TestSingleplayerContext sp, Recorder recorder) {
		Vec3 g = goTo(context, sp, 4.50, 135.62, 2);
		marsClock(sp, 7400);
		sp.getServer().runCommand("execute as @a at @s run redplanet weather devil");
		recorder.unfreeze();
		context.waitTicks(150); // spinning up
		int devilId = context.computeOnClient(mc -> mc.level == null ? -1 : mc.level.getEntitiesOfClass(DustDevil.class,
			mc.player.getBoundingBox().inflate(90.0)).stream().findFirst().map(e -> e.getId()).orElse(-1));
		if (devilId < 0) {
			RedPlanet.LOGGER.warn("Trailer: no dust devil came up; skipping the shot");
			return;
		}
		recorder.record("dust_devil", 3.0, (mc, t, partial) -> {
			var devil = mc.level.getEntity(devilId);
			Vec3 at = devil == null ? g : devil.getPosition(partial);
			Vec3 cam = new Vec3(at.x - 26 + 6 * t, g.y + 3.0, at.z + 30);
			return TrailerCamera.Pose.looking(cam, at.add(0, 18, 0), 0.0F, 58.0F);
		});
	}

	/**
	 * Night at InSight: Phobos high in a dark sky, found from the real orbits, through a narrow lens. The clock runs
	 * for this shot, so Phobos visibly slides across the stars (it laps Mars in 7 h 39 min, rising in the west).
	 */
	private void phobosNight(ClientGameTestContext context, TestSingleplayerContext sp, Recorder recorder) {
		Vec3 g = goTo(context, sp, 4.50, 135.62, 2);
		PlanetSettings s = sp.getServer().computeOnServer(server -> PlanetSettings.of(server.getLevel(RPDimensions.MARS)));
		int tick = -1;
		for (int t = 12400; t < 12400 + 2 * 24660 && tick < 0; t += 40) {
			MarsAstronomy.Sky sky = MarsAstronomy.compute(t, 0.0, 4.50, s.startLs(), s.yearCompression(), s.earthPhaseAtStartDeg(), s.moonPhaseSeed());
			if (sky.sunAltitudeDeg() < -18.0 && sky.phobos()[1] > Math.sin(Math.toRadians(30.0)) && !sky.phobosEclipsed()) {
				tick = t;
			}
		}
		if (tick < 0) {
			RedPlanet.LOGGER.warn("Trailer: no dark sky with Phobos up; skipping the shot");
			return;
		}
		marsClock(sp, tick);
		double[] d = MarsAstronomy.compute(tick + 45, 0.0, 4.50, s.startLs(), s.yearCompression(), s.earthPhaseAtStartDeg(), s.moonPhaseSeed()).phobos();
		Vec3 eye = g.add(0, 1.7, 0);
		Vec3 target = eye.add(new Vec3(d[0], d[1], d[2]).scale(100));
		sp.getServer().runCommand("gamerule advance_time true");
		CameraPath path = CameraPath.builder()
			.key(0.0, eye, target.add(-3, -2, 0), 34.0F)
			.key(3.0, eye.add(0, 0.3, 0), target.add(3, 1, 0), 30.0F)
			.build();
		recorder.record("phobos_night", 3.0, (mc, t, partial) -> path.at(t));
		recorder.unfreeze();
		sp.getServer().runCommand("gamerule advance_time false");
	}

	/** A regional dust storm rolling over the plain: the Sun dims to a disc, the horizon closes in. */
	private void marsStorm(ClientGameTestContext context, TestSingleplayerContext sp, Recorder recorder) {
		Vec3 g = goTo(context, sp, 4.50, 135.62, 2);
		marsClock(sp, 6800);
		sp.getServer().runCommand("execute as @a at @s run redplanet weather dust regional");
		recorder.unfreeze();
		context.waitTicks(260);
		CameraPath path = CameraPath.builder()
			.key(0.0, g.add(0, 2.2, 0), g.add(40, 10, -60), 60.0F)
			.key(3.0, g.add(4, 2.6, -6), g.add(48, 12, -66), 56.0F)
			.build();
		recorder.record("mars_storm", 3.0, (mc, t, partial) -> path.at(t));
		recorder.unfreeze();
		sp.getServer().runCommand("redplanet weather dust clear");
	}
}
