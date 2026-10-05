package io.github.avi130805.redplanet.gametest.client;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.mojang.blaze3d.platform.Window;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.client.starship.flight.MissionControlScreen;
import io.github.avi130805.redplanet.life.RPLifeBlocks;
import io.github.avi130805.redplanet.starship.entity.StarshipEntity;
import io.github.avi130805.redplanet.starship.entity.SuperHeavyEntity;
import io.github.avi130805.redplanet.starship.entity.VehicleEntity;
import io.github.avi130805.redplanet.starship.flight.FlightKinematics;
import io.github.avi130805.redplanet.starship.flight.FlightProfile;
import io.github.avi130805.redplanet.starship.geometry.StarshipGeometry;
import io.github.avi130805.redplanet.gametest.client.trailer.CameraPath;
import io.github.avi130805.redplanet.gametest.client.trailer.Recorder;
import io.github.avi130805.redplanet.gametest.client.trailer.Recorder.Take;
import io.github.avi130805.redplanet.gametest.client.trailer.TrailerCamera;
import io.github.avi130805.redplanet.gametest.client.trailer.TrailerClock;
import io.github.avi130805.redplanet.gametest.mixin.MissionControlScreenAccessor;
import io.github.avi130805.redplanet.gametest.mixin.MouseHandlerAccessor;
import io.github.avi130805.redplanet.mars.MarsConditions;
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
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

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
		this.shots.put("voyage", this::voyage);
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
				.setUseConsistentSettings(false) // real terrain: the launch pad stands on a beach
				.adjustSettings(s -> {
					s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
					s.setAllowCommands(true);
					s.setSeed("red planet");
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
				// Every frame steps the world with a command, which would otherwise scroll through the chat on screen.
				mc.options.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.HIDDEN);
				mc.options.broadcastOptions(); // the server tracks entities out to the reported view distance
			});
			ClientTestSupport.hideHud(context);
			sp.getServer().runCommand("gamerule advance_time false");
			sp.getServer().runCommand("gamerule advance_weather false");
			sp.getServer().runCommand("gamerule send_command_feedback false");
			sp.getServer().runCommand("gamerule log_admin_commands false");
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
			context.waitTicks(40);
			// Every shot is on disk now. Closing the world has been seen to deadlock in the gametest framework's tick
			// synchronisation; if it does, end the run after five minutes rather than hang the build.
			Thread watchdog = new Thread(() -> {
				try {
					Thread.sleep(5 * 60 * 1000L);
				} catch (InterruptedException e) {
					return;
				}
				RedPlanet.LOGGER.warn("Trailer: closing the world hung; all shots were written, so stopping here");
				Runtime.getRuntime().halt(0);
			}, "trailer-watchdog");
			watchdog.setDaemon(true);
			watchdog.start();
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

	// ------------------------------------------------------------------------------------------------ the voyage

	private static ServerPlayer player(net.minecraft.server.MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static StarshipEntity aboard(net.minecraft.server.MinecraftServer server) {
		return player(server).getVehicle() instanceof StarshipEntity ship ? ship : null;
	}

	private static String phaseId(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(server -> {
			StarshipEntity ship = aboard(server);
			return ship != null && ship.isFlying() ? ship.currentPhase().map(d -> d.id()).orElse("?") : "ground";
		});
	}

	/** Skips the flight forward until the named phase is current (the world must not be frozen). */
	private static void skipTo(ClientGameTestContext context, TestSingleplayerContext sp, String id) {
		int target = sp.getServer().computeOnServer(server -> {
			StarshipEntity ship = aboard(server);
			if (ship == null) {
				return -1;
			}
			var phases = ship.profile().map(FlightProfile::phases).orElse(java.util.List.of());
			for (int i = 0; i < phases.size(); i++) {
				if (phases.get(i).id().equals(id)) {
					return i;
				}
			}
			return -1;
		});
		if (target < 0) {
			RedPlanet.LOGGER.warn("Trailer: no phase {} in this flight", id);
			return;
		}
		for (int guard = 0; guard < 40; guard++) {
			int now = sp.getServer().computeOnServer(server -> {
				StarshipEntity ship = aboard(server);
				return ship != null && ship.isFlying() ? ship.phase() : Integer.MAX_VALUE;
			});
			if (now >= target) {
				break;
			}
			sp.getServer().runCommand("execute as @a at @s run redplanet starship skip");
			context.waitTicks(4);
		}
		RedPlanet.LOGGER.info("Trailer: flight at phase {}", phaseId(sp));
	}

	/** Lets the current phase run (unfrozen) until it is the given fraction done. */
	private static void runPhaseUntil(ClientGameTestContext context, TestSingleplayerContext sp, double fraction) {
		for (int guard = 0; guard < 2400; guard += 5) {
			double done = sp.getServer().computeOnServer(server -> {
				StarshipEntity ship = aboard(server);
				if (ship == null || !ship.isFlying() || ship.profile().isEmpty()) {
					return 1.0;
				}
				FlightProfile p = ship.profile().get();
				int phase = Math.min(ship.phase(), p.phases().size() - 1);
				return ship.phaseTick(0.0F) / p.phaseTicks(phase, ship.pacing());
			});
			if (done >= fraction) {
				return;
			}
			context.waitTicks(5);
		}
	}

	/** Waits for the descent after a transfer: the destination world loaded and the transfer screen gone. */
	private static void awaitArrival(ClientGameTestContext context, TestSingleplayerContext sp, ResourceKey<Level> world) {
		context.waitFor(mc -> mc.level != null && world.equals(mc.level.dimension()) && mc.gui.screen() == null, 4800);
		awaitTerrain(context, sp);
	}

	private static Vec3 entityPos(net.minecraft.client.Minecraft mc, Class<? extends Entity> type, float partial) {
		var list = mc.level.getEntitiesOfClass(type, mc.player.getBoundingBox().inflate(4000.0));
		return list.isEmpty() ? null : list.getFirst().getPosition(partial);
	}

	/**
	 * The whole flight, filmed in order: a dusk launch from a concrete pad on a beach (mission control, the hook's
	 * ignition and liftoff, the climb and hot staging with the telemetry overlay), the transfer screens, entry and
	 * landing on Mars, a suit, a habitat and a cave, then liftoff from Mars at sunset and the landing back home.
	 */
	private void voyage(ClientGameTestContext context, TestSingleplayerContext sp, Recorder recorder) {
		// The pad: a concrete apron on the nearest beach, at sea level, cleared above.
		BlockPos beach = sp.getServer().computeOnServer(server -> {
			var found = server.overworld().findClosestBiome3d(h -> h.is(Biomes.BEACH), new BlockPos(0, 63, 0), 4000, 32, 64);
			return found == null ? new BlockPos(0, 63, 0) : found.getFirst();
		});
		int px = beach.getX();
		int pz = beach.getZ();
		RedPlanet.LOGGER.info("Trailer: launch pad at {}, {}", px, pz);
		sp.getServer().runCommand(String.format(Locale.ROOT, "execute in minecraft:overworld run tp @a %d 90 %d", px, pz));
		context.waitTicks(20);
		awaitTerrain(context, sp);
		String ow = "execute in minecraft:overworld run ";
		sp.getServer().runCommand(ow + String.format(Locale.ROOT, "fill %d 59 %d %d 63 %d minecraft:gray_concrete", px - 24, pz - 24, px + 24, pz + 24));
		for (int y = 64; y < 100; y += 12) {
			sp.getServer().runCommand(ow + String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", px - 24, y, pz - 24, px + 24,
				Math.min(99, y + 11), pz + 24));
		}
		sp.getServer().runCommand(ow + String.format(Locale.ROOT, "fill %d 63 %d %d 63 %d minecraft:light_gray_concrete", px - 6, pz - 6, px + 6, pz + 6));
		sp.getServer().runCommand(ow + "time set 12500");
		sp.getServer().runCommand("gamemode creative @a");
		tp(sp, new Vec3(px + 0.5, 64, pz + 0.5));
		context.waitTicks(10);
		sp.getServer().runCommand("execute as @a at @s run redplanet starship spawn stack");
		context.waitTicks(20);
		Vec3 pad = new Vec3(px + 0.5, 64, pz + 0.5);
		// The open sea: the direction with water 60 blocks out.
		Vec3 sea = sp.getServer().computeOnServer(server -> {
			for (int a = 0; a < 360; a += 45) {
				int x = (int) (px + 60 * Math.sin(Math.toRadians(a)));
				int z = (int) (pz + 60 * Math.cos(Math.toRadians(a)));
				server.overworld().getChunk(x >> 4, z >> 4);
				if (server.overworld().getFluidState(new BlockPos(x, 62, z)).is(net.minecraft.tags.FluidTags.WATER)) {
					return new Vec3(Math.sin(Math.toRadians(a)), 0, Math.cos(Math.toRadians(a)));
				}
			}
			return new Vec3(1, 0, 0);
		});
		Vec3 land = sea.scale(-1);
		Vec3 side = new Vec3(-sea.z, 0, sea.x);
		sp.getServer().runCommand("gamemode spectator @a");

		// The stack on the pad at dusk, seen from the east so the sun sets behind it (it sets in the west, -x). The
		// camera keeps low but clear of the ground along its whole path.
		Vec3 duskFrom = pad.add(150, 0, 34);
		Vec3 duskTo = pad.add(104, 0, 14);
		tp(sp, pad.add(80, 20, 16));
		context.waitTicks(40);
		awaitTerrain(context, sp);
		// No trees between the cameras east of the pad and the stack (fill works only on loaded chunks: the player
		// stands in the middle of this area now).
		sp.getServer().runCommand("gamerule max_block_modifications 2000000");
		clearTrees(sp, px + 10, pz - 30, px + 165, pz + 60);
		double ground = pad.y;
		for (int i = 0; i <= 8; i++) {
			Vec3 at = duskFrom.lerp(duskTo, i / 8.0);
			ground = Math.max(ground, surface(sp, (int) Math.floor(at.x), (int) Math.floor(at.z)));
		}
		double duskY = ground + 2.5 - pad.y;
		CameraPath dusk = CameraPath.builder()
			.key(0.0, duskFrom.add(0, duskY, 0), pad.add(0, 52, 0), 38.0F)
			.key(5.0, duskTo.add(0, duskY + 2, 0), pad.add(0, 60, 0), 42.0F)
			.build();
		recorder.record("pad_dusk", 5.0, (mc, t, partial) -> dusk.at(t));
		recorder.unfreeze();
		tp(sp, pad.add(land.scale(60)).add(0, 20, 0));
		context.waitTicks(40);
		awaitTerrain(context, sp);
		// And none between the land-side cameras and the stack.
		Vec3 nearA = pad.add(side.scale(48));
		Vec3 nearB = pad.add(side.scale(-48));
		Vec3 farA = nearA.add(land.scale(112));
		Vec3 farB = nearB.add(land.scale(112));
		clearTrees(sp, (int) Math.min(Math.min(nearA.x, nearB.x), Math.min(farA.x, farB.x)), (int) Math.min(Math.min(nearA.z, nearB.z), Math.min(farA.z, farB.z)),
			(int) Math.max(Math.max(nearA.x, nearB.x), Math.max(farA.x, farB.x)), (int) Math.max(Math.max(nearA.z, nearB.z), Math.max(farA.z, farB.z)));

		// Aboard, at mission control.
		sp.getServer().runCommand("gamemode creative @a");
		tp(sp, pad.add(side.scale(8)));
		context.waitTicks(10);
		sp.getServer().runCommand("execute as @a at @s run ride @s mount @e[type=redplanet:starship,limit=1,sort=nearest,nbt={stacked:1b}]");
		context.waitTicks(20);
		context.runOnClient(mc -> {
			if (mc.player != null && mc.player.getVehicle() instanceof StarshipEntity ship) {
				MissionControlScreen.open(ship);
			}
		});
		context.waitTicks(30);
		// The cursor glides over the map, each site naming itself as it passes, and picks Olympus Mons.
		boolean[] picked = {false};
		recorder.record("mission_control", 3.0, (mc, t, partial) -> {
			if (mc.gui.screen() instanceof MissionControlScreen screen) {
				double[] at = cursor(t);
				MissionControlScreenAccessor map = (MissionControlScreenAccessor) screen;
				double x = map.redplanetTrailer$lonX(at[1]);
				double y = map.redplanetTrailer$latY(at[0]);
				Window window = mc.getWindow();
				MouseHandlerAccessor mouse = (MouseHandlerAccessor) mc.mouseHandler;
				mouse.redplanetTrailer$setXpos(x * window.getScreenWidth() / window.getGuiScaledWidth());
				mouse.redplanetTrailer$setYpos(y * window.getScreenHeight() / window.getGuiScaledHeight());
				if (t >= 2.3 && !picked[0]) {
					picked[0] = true;
					screen.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0)), false);
				}
			}
			return null;
		});
		recorder.unfreeze();
		context.runOnClient(mc -> mc.gui.setScreen(null));

		// Launch. Ignition (6 s into its phase) from the foot of the booster and from the east with the sunset behind
		// the stack; then liftoff from far off on the land side, from low beside the pad, and from the east.
		sp.getServer().runCommand("execute as @a at @s run redplanet starship launch standard");
		context.waitTicks(40);
		skipTo(context, sp, "ignition");
		Vec3 base = pad;
		// Every camera near the ground stands clear of it (the beach around the apron may rise or be water).
		Vec3 wide = clear(sp, base.add(100, 1.5, 30), 2.0);
		double wideY = Math.max(wide.y, clear(sp, base.add(91, 1.5, 30), 2.0).y);
		Vec3 far = clear(sp, base.add(land.scale(105)).add(side.scale(-20)).add(0, 2.0, 0), 2.0);
		Vec3 low = clear(sp, base.add(land.scale(55)).add(side.scale(40)).add(0, 0.9, 0), 1.0);
		Vec3 east = clear(sp, base.add(118, 2.0, 36), 2.0);
		runPhaseUntil(context, sp, 0.35);
		recorder.record(6.0,
			Take.of("hook_ignition", (mc, t, partial) -> TrailerCamera.Pose.looking(
				base.add(land.scale(22 - 0.15 * t)).add(side.scale(9)).add(0, 1.2, 0), base.add(0, 7, 0), 0.0F, 62.0F)),
			Take.of("ignition_wide", (mc, t, partial) -> TrailerCamera.Pose.looking(
				new Vec3(base.x + 100 - 1.5 * t, wideY, base.z + 30), base.add(0, 34, 0), 0.0F, 42.0F)));
		recorder.unfreeze();
		skipTo(context, sp, "liftoff");
		recorder.record(10.0,
			Take.of("hook_liftoff", (mc, t, partial) -> TrailerCamera.Pose.looking(far, boosterAim(mc, base, partial, 40), 0.0F, 50.0F)),
			Take.of("liftoff_low", (mc, t, partial) -> TrailerCamera.Pose.looking(low, boosterAim(mc, base, partial, 46), 0.0F, 72.0F)),
			Take.of("liftoff_sunset", (mc, t, partial) -> TrailerCamera.Pose.looking(east, boosterAim(mc, base, partial, 50), 0.0F, 36.0F)));
		recorder.unfreeze();

		// The climb and staging: the flight's own cameras with the webcast-style telemetry, and the trailer's chase and
		// side cameras without it.
		ClientTestSupport.showHud(context);
		skipTo(context, sp, "max_q");
		recorder.record(5.0,
			Take.withHud("ascent_track", (mc, t, partial) -> null),
			Take.withoutHud("ascent_chase", (mc, t, partial) -> chase(mc, partial, true, -95.0, 55.0, -25.0, 52.0F)));
		recorder.unfreeze();
		skipTo(context, sp, "hot_staging");
		runPhaseUntil(context, sp, 0.05);
		recorder.record(6.0,
			Take.withHud("hot_staging", (mc, t, partial) -> null),
			Take.withoutHud("staging_side", (mc, t, partial) -> stagingSide(mc, partial)));
		recorder.unfreeze();
		ClientTestSupport.hideHud(context);
		skipTo(context, sp, "ship_ascent");
		recorder.record(4.0,
			Take.of("ship_ascent", (mc, t, partial) -> null),
			Take.of("ship_chase", (mc, t, partial) -> chase(mc, partial, false, -120.0, 45.0, 12.0, 50.0F)));
		recorder.unfreeze();

		// The transfer screens: refilling in orbit, the transfer orbit, Mars approaching.
		skipTo(context, sp, "refilling");
		context.waitTicks(20);
		recorder.record("interlude_earth", 4.0, (mc, t, partial) -> null);
		recorder.unfreeze();
		skipTo(context, sp, "coast");
		context.waitTicks(20);
		recorder.record("interlude_transfer", 4.0, (mc, t, partial) -> null);
		recorder.unfreeze();
		skipTo(context, sp, "approach");
		context.waitTicks(10);
		recorder.record("interlude_mars", 4.0, (mc, t, partial) -> null);
		recorder.unfreeze();

		// Mars: entry, the belly flop, the flip and landing burn, touchdown.
		marsClock(sp, 9800);
		skipTo(context, sp, "entry");
		awaitArrival(context, sp, RPDimensions.MARS);
		recorder.record(5.0,
			Take.of("entry_plasma", (mc, t, partial) -> null),
			Take.of("entry_side", (mc, t, partial) -> alongside(mc, partial, 72.0, -26.0, -12.0, 52.0F)));
		recorder.unfreeze();
		skipTo(context, sp, "belly_flop");
		// Just before the ship swings belly-down (56 % into the phase).
		runPhaseUntil(context, sp, 0.50);
		recorder.record(5.0,
			Take.of("belly_flop", (mc, t, partial) -> null),
			Take.of("belly_flop_side", (mc, t, partial) -> alongside(mc, partial, 64.0, 18.0, -20.0, 50.0F)));
		recorder.unfreeze();
		skipTo(context, sp, "landing");
		// The last of the landing burn: legs out, touchdown (the end of the phase) about 6 s into the shot.
		runPhaseUntil(context, sp, 0.84);
		Vec3 marsLow = groundCamera(sp, 58, 34);
		recorder.record(9.0,
			Take.of("mars_landing", (mc, t, partial) -> null),
			Take.of("mars_landing_low", (mc, t, partial) -> TrailerCamera.Pose.looking(marsLow, shipAim(mc, marsLow, partial, 22), 0.0F, 52.0F)));
		recorder.unfreeze();
		context.waitFor(mc -> mc.player != null && mc.player.getVehicle() instanceof StarshipEntity s && !s.isFlying(), 20 * 120);
		context.waitTicks(60);
		Vec3 ship = sp.getServer().computeOnServer(server -> aboard(server) == null ? player(server).position() : aboard(server).position());

		// Out in the suit: the visor and the suit's readout, the view turning to the ship.
		sp.getServer().runCommand("execute as @a run ride @s dismount");
		sp.getServer().runCommand("gamemode survival @a");
		for (String piece : new String[]{"head spacesuit_helmet", "chest spacesuit_torso", "legs spacesuit_legs", "feet spacesuit_boots"}) {
			String[] p = piece.split(" ");
			sp.getServer().runCommand("item replace entity @a armor." + p[0] + " with redplanet:" + p[1]);
		}
		Vec3 stand = ship.add(-26, 0, -18);
		int standY = surface(sp, (int) stand.x, (int) stand.z);
		tp(sp, new Vec3(stand.x, standY, stand.z));
		context.waitTicks(40);
		ClientTestSupport.showHud(context);
		float shipYaw = (float) Math.toDegrees(Math.atan2(-(ship.x - stand.x), ship.z - stand.z));
		recorder.record("suit_visor", 4.0, (mc, t, partial) -> {
			float yaw = shipYaw - 40.0F + 40.0F * (float) (t / 4.0);
			float pitch = -8.0F;
			mc.player.setYRot(yaw);
			mc.player.yRotO = yaw;
			mc.player.setXRot(pitch);
			mc.player.xRotO = pitch;
			return null;
		});
		recorder.unfreeze();
		ClientTestSupport.hideHud(context);

		// A habitat beside the ship, pressurized: lamps on, a torch burning, Mars through the window.
		sp.getServer().runCommand("gamemode creative @a");
		int hx = (int) ship.x + 22;
		int hz = (int) ship.z + 6;
		BlockPos hab = new BlockPos(hx, surface(sp, hx, hz), hz);
		BaseClientGameTest.buildHabitat(sp, hab);
		for (int waited = 0; waited < 600 && !sp.getServer().computeOnServer(server -> server.getLevel(RPDimensions.MARS)
				.getBlockEntity(hab.offset(3, 2, 0)) instanceof io.github.avi130805.redplanet.habitat.HabitatRegulatorBlockEntity r
				&& r.isBreathable()); waited += 10) {
			context.waitTicks(10);
		}
		sp.getServer().runCommand(String.format(Locale.ROOT, "execute as @a at @s run setblock %d %d %d minecraft:torch", hx + 4, hab.getY() + 1, hz + 5));
		sp.getServer().runCommand(String.format(Locale.ROOT, "execute as @a at @s run setblock %d %d %d minecraft:lantern", hx + 2, hab.getY() + 1, hz + 5));
		marsClock(sp, 11400);
		sp.getServer().runCommand("gamemode spectator @a");
		Vec3 in = new Vec3(hx, hab.getY(), hz);
		tp(sp, in.add(3.5, 1.5, 3.5));
		context.waitTicks(30);
		CameraPath habitat = CameraPath.builder()
			.key(0.0, in.add(4.6, 2.7, 1.5), in.add(1.0, 1.6, 4.5), 70.0F)
			.key(4.0, in.add(3.6, 2.4, 1.6), in.add(0.6, 2.0, 3.0), 66.0F)
			.build();
		recorder.record("habitat", 4.0, (mc, t, partial) -> habitat.at(t));
		recorder.unfreeze();

		// Underground: a lava tube lit by its own life (fiction).
		MarsCaveClientGameTest.Viewpoint cave = null;
		for (double[] site : new double[][]{{-5.0, 250.0}, {-2.0, 240.0}, {20.0, 233.0}, {22.0, 150.0}}) {
			sp.getServer().runCommand("gamemode creative @a");
			toMars(context, sp, site[0], site[1]);
			cave = sp.getServer().computeOnServer(server -> MarsCaveClientGameTest.find(server.getLevel(RPDimensions.MARS),
				player(server).blockPosition(), java.util.Set.of(RPLifeBlocks.AREOLICHEN, RPLifeBlocks.EMBER_MOSS, RPLifeBlocks.RUSTCAP_CAP,
					RPLifeBlocks.RUSTCAP_GILLS)));
			if (cave != null) {
				break;
			}
		}
		if (cave != null) {
			sp.getServer().runCommand("gamemode spectator @a");
			marsClock(sp, 18000);
			Vec3 cam = Vec3.atCenterOf(cave.camera()).add(0, 0.6, 0);
			Vec3 look = Vec3.atCenterOf(cave.target());
			tp(sp, cam);
			context.waitTicks(160);
			Vec3 push = look.subtract(cam).normalize();
			CameraPath tube = CameraPath.builder()
				.key(0.0, cam.subtract(push.scale(1.5)), look, 70.0F)
				.key(5.0, cam.add(push.scale(1.5)).add(0, 0.4, 0), look.add(0, 0.6, 0), 66.0F)
				.build();
			recorder.record("caves", 5.0, (mc, t, partial) -> tube.at(t));
			recorder.unfreeze();
		} else {
			RedPlanet.LOGGER.warn("Trailer: no lit cave found; no cave shot");
		}

		// Home: liftoff from Mars at sunset, entry over Earth, landing beside the pad.
		sp.getServer().runCommand("gamemode creative @a");
		sp.getServer().runCommand(String.format(Locale.ROOT, "execute in redplanet:mars run tp @a %.1f %.1f %.1f", ship.x + 6, ship.y, ship.z + 6));
		context.waitFor(mc -> mc.level != null && RPDimensions.MARS.equals(mc.level.dimension()), 2400);
		awaitTerrain(context, sp);
		sp.getServer().runCommand("execute as @a at @s run ride @s mount @e[type=redplanet:starship,limit=1,sort=nearest]");
		context.waitTicks(20);
		marsClock(sp, 12330);
		sp.getServer().runCommand("execute in minecraft:overworld run time set 23200");
		sp.getServer().runCommand("execute as @a at @s run redplanet starship launch standard");
		context.waitTicks(40);
		skipTo(context, sp, "liftoff");
		Vec3 marsPad = ship;
		Vec3 marsNear = groundCamera(sp, -40, 30);
		recorder.record(8.0,
			Take.of("mars_liftoff", (mc, t, partial) -> TrailerCamera.Pose.looking(marsPad.add(-95, 3, 40), shipAim(mc, marsPad, partial, 24), 0.0F, 46.0F)),
			Take.of("mars_liftoff_low", (mc, t, partial) -> TrailerCamera.Pose.looking(marsNear, shipAim(mc, marsPad, partial, 26), 0.0F, 70.0F)));
		recorder.unfreeze();
		skipTo(context, sp, "entry");
		awaitArrival(context, sp, Level.OVERWORLD);
		recorder.record("earth_entry", 5.0, (mc, t, partial) -> null);
		recorder.unfreeze();
		skipTo(context, sp, "landing");
		runPhaseUntil(context, sp, 0.84);
		Vec3 homeLow = groundCamera(sp, 52, -40);
		recorder.record(9.0,
			Take.of("home_landing", (mc, t, partial) -> null),
			Take.of("home_landing_low", (mc, t, partial) -> TrailerCamera.Pose.looking(homeLow, shipAim(mc, homeLow, partial, 22), 0.0F, 52.0F)));
		recorder.unfreeze();
	}

	/** Removes the trees (logs and leaves) in an area of the overworld, from sea level up. */
	private static void clearTrees(TestSingleplayerContext sp, int x1, int z1, int x2, int z2) {
		for (String tag : new String[]{"#minecraft:leaves", "#minecraft:logs"}) {
			sp.getServer().runCommand(String.format(Locale.ROOT, "execute in minecraft:overworld run fill %d 62 %d %d 110 %d minecraft:air replace %s",
				x1, z1, x2, z2, tag));
		}
	}

	/** {@code at}, raised if need be to stand {@code above} metres over the ground (or water) there. */
	private static Vec3 clear(TestSingleplayerContext sp, Vec3 at, double above) {
		return new Vec3(at.x, Math.max(at.y, surface(sp, (int) Math.floor(at.x), (int) Math.floor(at.z)) + above), at.z);
	}

	/** Where to aim at the rising booster: {@code above} metres up it, or above the pad before it shows. */
	private static Vec3 boosterAim(net.minecraft.client.Minecraft mc, Vec3 pad, float partial, double above) {
		Vec3 b = entityPos(mc, SuperHeavyEntity.class, partial);
		return (b == null ? pad : b).add(0, above, 0);
	}

	/** Where to aim at the ship: {@code above} metres up it (its origin is its base), or at {@code fallback}. */
	private static Vec3 shipAim(net.minecraft.client.Minecraft mc, Vec3 fallback, float partial, double above) {
		Vec3 s = entityPos(mc, StarshipEntity.class, partial);
		return (s == null ? fallback : s).add(0, above, 0);
	}

	/**
	 * A camera on the ground {@code dx}, {@code dz} metres from the ship (where it stands or is about to land), 1.6 m
	 * above the surface there.
	 */
	private static Vec3 groundCamera(TestSingleplayerContext sp, double dx, double dz) {
		Vec3 at = sp.getServer().computeOnServer(server -> {
			StarshipEntity ship = aboard(server);
			return ship != null ? ship.position() : player(server).position();
		});
		int x = (int) Math.floor(at.x + dx);
		int z = (int) Math.floor(at.z + dz);
		return new Vec3(x + 0.5, surface(sp, x, z) + 1.6, z + 0.5);
	}

	/**
	 * A camera riding along with the flying stack ({@code stack}) or the ship: {@code back} metres along its axis (negative
	 * is behind the engines), {@code out} metres to the side of its flight path and {@code up} metres up, looking at
	 * its middle.
	 */
	private static TrailerCamera.@Nullable Pose chase(net.minecraft.client.Minecraft mc, float partial, boolean stack, double back,
			double out, double up, float fov) {
		VehicleEntity vehicle = vehicle(mc, stack ? SuperHeavyEntity.class : StarshipEntity.class);
		if (vehicle == null) {
			return null;
		}
		double height = stack ? StarshipGeometry.STACK_HEIGHT : StarshipGeometry.SHIP_HEIGHT;
		Quaternionf attitude = vehicle.attitude(partial, new Quaternionf());
		Vector3f axis = attitude.transform(new Vector3f(0.0F, 1.0F, 0.0F));
		double[] h = FlightKinematics.heading(vehicle.azimuth());
		Vec3 right = new Vec3(-h[1], 0.0, h[0]);
		Vec3 origin = vehicle.getPosition(partial);
		Vec3 middle = origin.add(axis.x * height * 0.5, axis.y * height * 0.5, axis.z * height * 0.5);
		Vec3 camera = middle.add(axis.x * back, axis.y * back, axis.z * back).add(right.scale(out)).add(0, up, 0);
		return TrailerCamera.Pose.looking(camera, middle, 0.0F, fov);
	}

	/** Hot staging from the side: level with the joint between ship and booster, riding along with the ship. */
	private static TrailerCamera.@Nullable Pose stagingSide(net.minecraft.client.Minecraft mc, float partial) {
		VehicleEntity ship = vehicle(mc, StarshipEntity.class);
		if (ship == null) {
			return null;
		}
		double[] h = FlightKinematics.heading(ship.azimuth());
		Vec3 forward = new Vec3(h[0], 0.0, h[1]);
		Vec3 right = new Vec3(-h[1], 0.0, h[0]);
		Vec3 joint = ship.getPosition(partial);
		return TrailerCamera.Pose.looking(joint.add(right.scale(92)).add(forward.scale(-14)).add(0, -12, 0), joint.add(0, -8, 0), 0.0F, 54.0F);
	}

	/**
	 * A camera riding along beside the ship, offset in the frame of its flight path ({@code out} to the side, {@code up},
	 * {@code ahead} downrange), looking at its middle.
	 */
	private static TrailerCamera.@Nullable Pose alongside(net.minecraft.client.Minecraft mc, float partial, double out, double up,
			double ahead, float fov) {
		VehicleEntity ship = vehicle(mc, StarshipEntity.class);
		if (ship == null) {
			return null;
		}
		Quaternionf attitude = ship.attitude(partial, new Quaternionf());
		Vector3f axis = attitude.transform(new Vector3f(0.0F, 1.0F, 0.0F));
		double half = StarshipGeometry.SHIP_HEIGHT * 0.5;
		Vec3 middle = ship.getPosition(partial).add(axis.x * half, axis.y * half, axis.z * half);
		double[] h = FlightKinematics.heading(ship.azimuth());
		Vec3 camera = middle.add(-h[1] * out + h[0] * ahead, up, h[0] * out + h[1] * ahead);
		return TrailerCamera.Pose.looking(camera, middle, 0.0F, fov);
	}

	private static @Nullable VehicleEntity vehicle(net.minecraft.client.Minecraft mc, Class<? extends VehicleEntity> type) {
		var list = mc.level.getEntitiesOfClass(type, mc.player.getBoundingBox().inflate(4000.0));
		return list.isEmpty() ? null : list.getFirst();
	}

	/**
	 * The mission control cursor at {@code t} seconds, as {latitude, longitude}: from the southern highlands to Jezero,
	 * Gale and Olympus Mons, easing between them and resting on each long enough for its name to show.
	 */
	private static double[] cursor(double t) {
		double[][] route = {
			{0.00, -22.0, 40.0}, {0.45, 18.38, 77.58}, {0.85, 18.38, 77.58}, {1.30, -5.37, 137.81}, {1.65, -5.37, 137.81},
			{2.10, 18.65, 226.20}, {3.00, 18.65, 226.20},
		};
		for (int i = 1; i < route.length; i++) {
			if (t <= route[i][0]) {
				double u = (t - route[i - 1][0]) / (route[i][0] - route[i - 1][0]);
				u = u * u * (3 - 2 * u);
				return new double[]{route[i - 1][1] + (route[i][1] - route[i - 1][1]) * u, route[i - 1][2] + (route[i][2] - route[i - 1][2]) * u};
			}
		}
		double[] last = route[route.length - 1];
		return new double[]{last[1], last[2]};
	}

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
		sp.getServer().runCommand("kill @e[type=redplanet:dust_devil]");
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
		sp.getServer().runCommand("kill @e[type=redplanet:dust_devil]");
		Vec3 eye = g.add(0, 1.7, 0);
		sp.getServer().runCommand("gamerule advance_time true");
		// A telephoto lens tracking Phobos from its real orbit, so it lands a little off centre and drifts across.
		recorder.record("phobos_night", 3.0, (mc, t, partial) -> {
			long clock = MarsConditions.clockTicks(mc.level);
			double[] d = MarsAstronomy.compute(clock, partial, 4.50, s.startLs(), s.yearCompression(), s.earthPhaseAtStartDeg(),
				s.moonPhaseSeed()).phobos();
			Vec3 dir = new Vec3(d[0], d[1], d[2]).normalize();
			Vec3 right = dir.cross(new Vec3(0, 1, 0)).normalize();
			// Aim slightly ahead, then let Phobos slide to the left third of the frame.
			Vec3 aim = dir.add(right.scale(0.012 - 0.008 * t / 3.0));
			return TrailerCamera.Pose.looking(eye, eye.add(aim.scale(100)), 0.0F, 12.0F);
		});
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
