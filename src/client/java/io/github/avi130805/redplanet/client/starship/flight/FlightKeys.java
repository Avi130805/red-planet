package io.github.avi130805.redplanet.client.starship.flight;

import com.mojang.blaze3d.platform.InputConstants;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.starship.entity.StarshipEntity;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Keys while aboard: switch camera (V), skip the rest of a phase (N), mission control (M). */
public final class FlightKeys {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(RedPlanet.id("starship"));
	public static final KeyMapping CAMERA = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.redplanet.camera", InputConstants.KEY_V, CATEGORY));
	public static final KeyMapping SKIP = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.redplanet.skip", InputConstants.KEY_N, CATEGORY));
	public static final KeyMapping MISSION_CONTROL = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.redplanet.mission_control", InputConstants.KEY_M, CATEGORY));

	private FlightKeys() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(FlightKeys::tick);
	}

	private static void tick(Minecraft mc) {
		if (mc.player == null) {
			return;
		}
		StarshipEntity ship = ClientFlight.ship();
		while (CAMERA.consumeClick()) {
			if (ship != null && ship.isFlying()) {
				CinematicCamera.INSTANCE.cycleMode();
				mc.player.sendOverlayMessage(Component.translatable("camera.redplanet.switched", CinematicCamera.INSTANCE.mode().label()));
			}
		}
		while (SKIP.consumeClick()) {
			if (ship != null && ship.isFlying()) {
				mc.player.connection.sendCommand("redplanet starship skip");
			}
		}
		while (MISSION_CONTROL.consumeClick()) {
			if (ship != null && !ship.isFlying()) {
				MissionControlScreen.open(ship);
			}
		}
	}
}
