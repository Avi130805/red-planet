package io.github.avi130805.redplanet.client.habitat;

import io.github.avi130805.redplanet.habitat.HabitatIndex;
import io.github.avi130805.redplanet.network.HabitatSyncPayload;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;

import org.jspecify.annotations.Nullable;

/**
 * Installs the server's habitat air into the client's {@link HabitatIndex}. A sync can arrive while the client is still
 * changing worlds; it then waits until the matching level is loaded.
 */
public final class ClientHabitats {
	private static @Nullable HabitatSyncPayload pending;

	private ClientHabitats() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(HabitatSyncPayload.TYPE, (payload, context) -> {
			pending = payload;
			apply(context.client());
		});
		ClientTickEvents.END_CLIENT_TICK.register(ClientHabitats::apply);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> pending = null);
	}

	private static void apply(Minecraft mc) {
		HabitatSyncPayload payload = pending;
		if (payload != null && mc.level != null && mc.level.dimension().equals(payload.level())) {
			HabitatIndex.install(mc.level, payload.sections());
			pending = null;
		}
	}
}
