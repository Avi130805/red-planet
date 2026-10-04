package io.github.avi130805.redplanet.gametest.client;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.client.starship.flight.MissionControlScreen;
import io.github.avi130805.redplanet.registry.RPDimensions;
import io.github.avi130805.redplanet.starship.entity.StarshipEntity;
import io.github.avi130805.redplanet.starship.flight.FlightProfile;
import io.github.avi130805.redplanet.starship.flight.FlightSegment;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.level.ServerPlayer;

/**
 * Photographs the Starship and a whole flight: the stack on the ground, the landed ship, mission control, then
 * Earth to Mars and back, phase by phase. Each phase is skipped to (the skip command), given a few seconds to play,
 * and photographed through the flight's own cinematic camera with the telemetry overlay. Screenshots: {@code ship_*},
 * {@code flight_*}, {@code return_*}.
 */
public class StarshipClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (!ClientTestSupport.enabled("starship")) {
			return;
		}
		try (TestSingleplayerContext sp = context.worldBuilder()
				.adjustSettings(s -> {
					s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
					s.setAllowCommands(true);
				})
				.create()) {
			sp.getConnection().waitForChunksRender();
			context.runOnClient(mc -> {
				mc.options.renderDistance().set(8);
				// The server tracks entities out to the view distance the client last reported (5 in client gametests).
				mc.options.broadcastOptions();
			});
			sp.getServer().runCommand("time set 1000");
			sp.getServer().runCommand("gamerule advance_time false");
			sp.getServer().runCommand("weather clear");

			// On the ground: a full stack and, beside it, a ship standing on its legs.
			sp.getServer().runCommand("execute as @a at @s run redplanet starship spawn stack");
			sp.getServer().runCommand("execute as @a at @s run tp @s ~40 ~ ~");
			sp.getServer().runCommand("execute as @a at @s run redplanet starship spawn ship");
			double[] b = sp.getServer().computeOnServer(server -> {
				var level = server.overworld();
				var booster = level.getEntitiesOfClass(io.github.avi130805.redplanet.starship.entity.SuperHeavyEntity.class,
					server.getPlayerList().getPlayers().getFirst().getBoundingBox().inflate(200)).getFirst();
				return new double[]{booster.getX(), booster.getY(), booster.getZ()};
			});
			context.waitTicks(20);
			ClientTestSupport.hideHud(context);
			// Spectators don't fall: the camera stays exactly where it is put.
			sp.getServer().runCommand("gamemode spectator @a");
			// Within the view distance (entities further away than it aren't sent to the client).
			this.look(sp, b[0] + 62, b[1] + 18, b[2] + 88, b[0] + 20, b[1] + 50, b[2]);
			context.waitTicks(60);
			ClientTestSupport.shot(context, "ship_01_stack_and_ship");
			this.look(sp, b[0] + 52, b[1] + 6, b[2] + 24, b[0] + 40, b[1] + 12, b[2]);
			context.waitTicks(40);
			ClientTestSupport.shot(context, "ship_02_landed_close");
			this.look(sp, b[0] + 30, b[1] + 1.5, b[2] + 10, b[0] + 40, b[1] - 0.5, b[2]);
			context.waitTicks(40);
			ClientTestSupport.shot(context, "ship_03_legs_and_engines");
			this.look(sp, b[0] + 14, b[1] + 3, b[2] + 10, b[0], b[1] + 60, b[2]);
			context.waitTicks(40);
			ClientTestSupport.shot(context, "ship_04_booster_from_below");
			this.look(sp, b[0] - 20, b[1] + 118, b[2] + 22, b[0], b[1] + 112, b[2]);
			context.waitTicks(40);
			ClientTestSupport.shot(context, "ship_05_nose_and_windows");
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @a %.1f %.1f %.1f", b[0] + 8, b[1], b[2] + 8));
			context.waitTicks(10);

			// Board the stacked ship and open mission control.
			sp.getServer().runCommand("execute as @a at @s run ride @s mount @e[type=redplanet:starship,limit=1,sort=nearest,nbt={stacked:1b}]");
			context.waitTicks(20);
			ClientTestSupport.showHud(context); // the telemetry overlay belongs in the flight photographs
			context.runOnClient(mc -> {
				if (mc.player != null && mc.player.getVehicle() instanceof StarshipEntity ship) {
					MissionControlScreen.open(ship);
				}
			});
			context.waitTicks(30);
			ClientTestSupport.shot(context, "ship_06_mission_control");
			context.runOnClient(mc -> mc.gui.setScreen(null));

			this.fly(context, sp, "flight", "redplanet starship launch short", 600);

			// On Mars: look at the landed ship, then fly home.
			ClientTestSupport.shot(context, "flight_zz_landed");
			this.fly(context, sp, "return", "redplanet starship launch short", 600);
			ClientTestSupport.shot(context, "return_zz_home");
		}
	}

	/** Launches, then visits every phase in turn and photographs it. */
	private void fly(ClientGameTestContext context, TestSingleplayerContext sp, String prefix, String launch, int maxPhaseTicks) {
		sp.getServer().runCommand("execute as @a at @s run " + launch);
		context.waitTicks(40);
		int phases = sp.getServer().computeOnServer(server -> {
			ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
			return p.getVehicle() instanceof StarshipEntity s ? s.profile().map(FlightProfile::phases).map(java.util.List::size).orElse(0) : 0;
		});
		RedPlanet.LOGGER.info("{}: {} phases", prefix, phases);
		if (phases == 0) {
			RedPlanet.LOGGER.warn("{}: the launch did not happen (no flight profile, or not aboard)", prefix);
			return;
		}
		for (int target = 0; target < phases; target++) {
			int shotPhase = target;
			int current = phase(sp);
			int guard = 0;
			while (current >= 0 && current < shotPhase && guard++ < 40) {
				sp.getServer().runCommand("execute as @a at @s run redplanet starship skip");
				context.waitTicks(4);
				current = phase(sp);
			}
			if (current < 0) {
				break; // landed
			}
			String segment = sp.getServer().computeOnServer(server -> {
				ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
				return p.getVehicle() instanceof StarshipEntity s ? s.segment().map(FlightSegment::getSerializedName).orElse("?") : "landed";
			});
			if ("descent".equals(segment)) {
				context.waitFor(mc -> mc.level != null && mc.gui.screen() == null, 2400);
			}
			// Let the phase play a little: the camera shot settles and the effects build up.
			context.waitTicks(segment.equals("transfer") ? 30 : 50);
			String id = sp.getServer().computeOnServer(server -> {
				ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
				return p.getVehicle() instanceof StarshipEntity s ? s.currentPhase().map(d -> d.id()).orElse("?") : "landed";
			});
			ClientTestSupport.shot(context, String.format("%s_%02d_%s_%s", prefix, shotPhase, segment, id));
		}
		// Let the flight finish.
		context.waitFor(mc -> mc.player != null && mc.player.getVehicle() instanceof StarshipEntity s && !s.isFlying(), 20 * 60 * 4);
		context.waitTicks(60);
		boolean onMars = context.computeOnClient(mc -> mc.level != null && RPDimensions.MARS.equals(mc.level.dimension()));
		RedPlanet.LOGGER.info("{} finished; on Mars: {}", prefix, onMars);
	}

	/** Puts the (spectating) player at a point, looking at another, in absolute coordinates. */
	private void look(TestSingleplayerContext sp, double x, double y, double z, double tx, double ty, double tz) {
		sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @a %.2f %.2f %.2f facing %.2f %.2f %.2f", x, y, z, tx, ty, tz));
	}

	private static int phase(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(server -> {
			ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
			return p.getVehicle() instanceof StarshipEntity s && s.isFlying() ? s.phase() : -1;
		});
	}
}
