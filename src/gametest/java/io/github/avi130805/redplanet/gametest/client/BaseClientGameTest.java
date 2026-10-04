package io.github.avi130805.redplanet.gametest.client;

import java.util.Locale;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.habitat.HabitatRegulatorBlockEntity;
import io.github.avi130805.redplanet.machine.OxygenConcentratorBlockEntity;
import io.github.avi130805.redplanet.registry.RPDimensions;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Photographs a small base on Mars: a habitat built from the mod's blocks, pressurized by its regulator (lit, with a
 * torch burning inside), its screen and the oxygen concentrator's, and a player in the spacesuit from outside and
 * through the helmet visor with the suit readout. Screenshots: {@code base_*}.
 */
public class BaseClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (!ClientTestSupport.enabled("base")) {
			return;
		}
		try (TestSingleplayerContext sp = context.worldBuilder()
				.adjustSettings(s -> {
					s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
					s.setAllowCommands(true);
				})
				.create()) {
			sp.getConnection().waitForChunksRender();
			// InSight's landing site in Elysium Planitia: one of the flattest places on Mars.
			sp.getServer().runCommand("execute as @a run redplanet tp mars 4.50 135.62");
			context.waitFor(mc -> mc.level != null && RPDimensions.MARS.equals(mc.level.dimension())
				&& !(mc.gui.screen() instanceof LevelLoadingScreen), 2400);
			context.runOnClient(mc -> mc.options.renderDistance().set(ClientTestSupport.MARS_RENDER_DISTANCE));
			ClientTestSupport.waitForTerrain(context);
			sp.getServer().runCommand("time set 3500");
			sp.getServer().runCommand("gamerule advance_time false");

			BlockPos base = sp.getServer().computeOnServer(server -> {
				ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
				BlockPos at = p.blockPosition().offset(6, 0, 6);
				return new BlockPos(at.getX(), p.level().getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()), at.getZ());
			});
			this.buildHabitat(sp, base);
			context.waitTicks(20);

			// The regulator floods the room, finds it sealed and pressurizes it from the tank (about ten seconds).
			int waited = 0;
			while (waited < 20 * 30 && !breathable(sp, base)) {
				context.waitTicks(10);
				waited += 10;
			}
			RedPlanet.LOGGER.info("Habitat breathable: {} after {} ticks", breathable(sp, base), waited);
			sp.getServer().runCommand(cmd("setblock %d %d %d minecraft:torch", base.getX() + 4, base.getY() + 1, base.getZ() + 5));
			sp.getServer().runCommand(cmd("setblock %d %d %d minecraft:lantern", base.getX() + 2, base.getY() + 1, base.getZ() + 5));

			ClientTestSupport.hideHud(context);
			sp.getServer().runCommand("gamemode spectator @a");
			look(sp, base.getX() - 7.5, base.getY() + 4.5, base.getZ() - 6.5, base.getX() + 3.5, base.getY() + 2, base.getZ() + 3.5);
			context.waitTicks(60);
			ClientTestSupport.shot(context, "base_01_habitat_outside");
			look(sp, base.getX() + 4.5, base.getY() + 2.6, base.getZ() + 1.6, base.getX() + 3.0, base.getY() + 1.4, base.getZ() + 6.0);
			context.waitTicks(40);
			ClientTestSupport.shot(context, "base_02_habitat_inside");

			// The regulator's and the concentrator's screens, opened for the player.
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand(cmd("tp @a %.1f %d %.1f", base.getX() + 3.5, base.getY() + 1, base.getZ() + 3.5));
			context.waitTicks(10);
			ClientTestSupport.showHud(context);
			this.openMenu(sp, base.offset(3, 2, 0));
			context.waitTicks(30);
			ClientTestSupport.shot(context, "base_03_regulator_screen");
			context.runOnClient(mc -> mc.player.closeContainer());
			this.openMenu(sp, base.offset(5, 1, 5));
			context.waitTicks(30);
			ClientTestSupport.shot(context, "base_04_concentrator_screen");
			context.runOnClient(mc -> mc.player.closeContainer());

			// Outside in the suit: seen from the front, then through the visor with the suit readout.
			sp.getServer().runCommand("gamemode survival @a");
			for (String piece : new String[]{"head spacesuit_helmet", "chest spacesuit_torso", "legs spacesuit_legs", "feet spacesuit_boots"}) {
				String[] p = piece.split(" ");
				sp.getServer().runCommand("item replace entity @a armor." + p[0] + " with redplanet:" + p[1]);
			}
			sp.getServer().runCommand("give @a redplanet:oxygen_canister");
			sp.getServer().runCommand(cmd("tp @a %.1f %d %.1f facing %.1f %d %.1f", base.getX() - 4.5, base.getY(), base.getZ() - 4.5,
				base.getX() + 3.5, base.getY() + 1, base.getZ() + 3.5));
			context.waitTicks(40);
			context.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
			context.waitTicks(20);
			ClientTestSupport.hideHud(context);
			ClientTestSupport.shot(context, "base_05_suit_front");
			context.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			context.waitTicks(20);
			ClientTestSupport.shot(context, "base_06_suit_back");
			context.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
			ClientTestSupport.showHud(context);
			context.waitTicks(60);
			ClientTestSupport.shot(context, "base_07_visor_and_readout");
			sp.getServer().runCommand("gamemode creative @a");
		}
	}

	/** A 7 x 4 x 7 habitat: panel walls, floor and roof, a window wall, an airlock door, LED lamps, the regulator. */
	private void buildHabitat(TestSingleplayerContext sp, BlockPos b) {
		int x = b.getX();
		int y = b.getY();
		int z = b.getZ();
		sp.getServer().runCommand(cmd("fill %d %d %d %d %d %d redplanet:habitat_panel hollow", x, y, z, x + 6, y + 4, z + 6));
		sp.getServer().runCommand(cmd("fill %d %d %d %d %d %d redplanet:habitat_window", x, y + 1, z + 2, x, y + 3, z + 4));
		sp.getServer().runCommand(cmd("setblock %d %d %d redplanet:airlock_door[half=lower,facing=east]", x + 6, y + 1, z + 3));
		sp.getServer().runCommand(cmd("setblock %d %d %d redplanet:airlock_door[half=upper,facing=east]", x + 6, y + 2, z + 3));
		sp.getServer().runCommand(cmd("setblock %d %d %d redplanet:led_lamp", x + 2, y + 4, z + 2));
		sp.getServer().runCommand(cmd("setblock %d %d %d redplanet:led_lamp", x + 4, y + 4, z + 4));
		// The regulator in the north wall, its outlet facing into the room, a full tank behind it outside.
		sp.getServer().runCommand(cmd("setblock %d %d %d redplanet:habitat_regulator[facing=south]", x + 3, y + 2, z));
		sp.getServer().runCommand(cmd("setblock %d %d %d redplanet:oxygen_tank{oxygen_kg:59.0f}", x + 3, y + 2, z - 1));
		sp.getServer().runCommand(cmd("setblock %d %d %d redplanet:oxygen_tank{oxygen_kg:59.0f}", x + 3, y + 1, z - 1));
		sp.getServer().runCommand(cmd("setblock %d %d %d redplanet:oxygen_concentrator[facing=north]", x + 5, y + 1, z + 5));
		sp.getServer().runCommand(cmd("setblock %d %d %d minecraft:red_bed[facing=west,part=head]", x + 1, y + 1, z + 1));
		sp.getServer().runCommand(cmd("setblock %d %d %d minecraft:red_bed[facing=west,part=foot]", x + 2, y + 1, z + 1));
		sp.getServer().runCommand(cmd("setblock %d %d %d minecraft:crafting_table", x + 5, y + 1, z + 1));
	}

	private static boolean breathable(TestSingleplayerContext sp, BlockPos base) {
		return sp.getServer().computeOnServer(server -> server.getLevel(RPDimensions.MARS)
			.getBlockEntity(base.offset(3, 2, 0)) instanceof HabitatRegulatorBlockEntity r && r.isBreathable());
	}

	private void openMenu(TestSingleplayerContext sp, BlockPos pos) {
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
			var be = p.level().getBlockEntity(pos);
			if (be instanceof HabitatRegulatorBlockEntity r) {
				p.openMenu(r);
			} else if (be instanceof OxygenConcentratorBlockEntity c) {
				p.openMenu(c);
			} else {
				RedPlanet.LOGGER.warn("No menu block at {}", pos);
			}
		});
	}

	private static void look(TestSingleplayerContext sp, double x, double y, double z, double tx, double ty, double tz) {
		sp.getServer().runCommand(cmd("tp @a %.2f %.2f %.2f facing %.2f %.2f %.2f", x, y, z, tx, ty, tz));
	}

	private static String cmd(String format, Object... args) {
		return String.format(Locale.ROOT, format, args);
	}
}
