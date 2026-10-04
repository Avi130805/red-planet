package io.github.avi130805.redplanet.client.starship.interlude;

import io.github.avi130805.redplanet.client.starship.flight.ClientFlight;
import io.github.avi130805.redplanet.registry.RPSounds;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;

/**
 * Opens the interlude when the local player's ship enters the transfer, and remembers that an arrival is due, so the
 * loading screen of the world change becomes the interlude even if the ship's state arrived late.
 */
public final class InterludeController {
	private static boolean expectingArrival;

	private InterludeController() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(InterludeController::tick);
	}

	private static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		if (ClientFlight.inTransfer()) {
			expectingArrival = true;
			if (!(mc.gui.screen() instanceof InterludeScreen)) {
				mc.gui.setScreen(new InterludeScreen());
				mc.getSoundManager().play(SimpleSoundInstance.forUI(RPSounds.UI_INTERLUDE_WHOOSH, 1.0F, 0.8F));
			}
		}
	}

	/** Whether the next world change belongs to a flight (the fallback in ClientPacketListenerMixin uses it). */
	public static boolean expectingArrival() {
		return expectingArrival;
	}

	static void closed() {
		expectingArrival = false;
	}
}
