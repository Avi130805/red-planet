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
import io.github.avi130805.redplanet.gametest.client.trailer.TrailerClock;
import io.github.avi130805.redplanet.registry.RPDimensions;
import io.github.avi130805.redplanet.starship.entity.SuperHeavyEntity;

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
		this.shots.put("test_mars_vista", this::testMarsVista);
		this.shots.put("test_stack_sunset", this::testStackSunset);
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

	/** Pipeline check: a slow push across Gale crater toward Aeolis Mons in the late-afternoon light. */
	private void testMarsVista(ClientGameTestContext context, TestSingleplayerContext sp, Recorder recorder) {
		toMars(context, sp, -4.59, 137.44);
		sp.getServer().runCommand("execute in redplanet:mars run time set 11000");
		Vec3 at = playerPos(sp);
		int ground = surface(sp, (int) at.x, (int) at.z);
		Vec3 base = new Vec3(at.x, ground, at.z);
		tp(sp, base.add(0, 6, 0));
		context.waitTicks(40);
		awaitTerrain(context, sp);
		CameraPath path = CameraPath.builder()
			.key(0.0, base.add(-12, 5, -6), base.add(40, 12, 120), 62.0F)
			.key(4.0, base.add(-4, 9, 10), base.add(46, 16, 130), 58.0F)
			.build();
		recorder.record("test_mars_vista", 4.0, (mc, t, partial) -> path.at(t));
	}

	/** Pipeline check: the full stack at sunset, the camera rising past the booster. */
	private void testStackSunset(ClientGameTestContext context, TestSingleplayerContext sp, Recorder recorder) {
		sp.getServer().runCommand("execute in minecraft:overworld run tp @a 0 120 0");
		context.waitFor(mc -> mc.level != null && !RPDimensions.MARS.equals(mc.level.dimension()), 2400);
		sp.getServer().runCommand("time set 12400");
		sp.getServer().runCommand("gamemode creative @a");
		int ground = surface(sp, 0, 0);
		tp(sp, new Vec3(0.5, ground, 0.5));
		context.waitTicks(10);
		sp.getServer().runCommand("execute as @a at @s run redplanet starship spawn stack");
		context.waitTicks(20);
		sp.getServer().runCommand("gamemode spectator @a");
		Vec3 b = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			return player.level().getEntitiesOfClass(SuperHeavyEntity.class, player.getBoundingBox().inflate(200)).getFirst().position();
		});
		tp(sp, b.add(40, 20, 60));
		context.waitTicks(40);
		awaitTerrain(context, sp);
		CameraPath path = CameraPath.builder()
			.key(0.0, b.add(55, 3, 70), b.add(0, 40, 0), 55.0F)
			.key(5.0, b.add(70, 60, 30), b.add(0, 70, 0), 50.0F)
			.build();
		recorder.record("test_stack_sunset", 5.0, (mc, t, partial) -> path.at(t));
	}
}
