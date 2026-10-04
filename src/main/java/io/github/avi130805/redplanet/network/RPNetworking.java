package io.github.avi130805.redplanet.network;

import io.github.avi130805.redplanet.mars.PlanetSettings;
import io.github.avi130805.redplanet.mars.weather.MarsWeather;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.level.ServerPlayer;

/** Payload registration (both sides), the server-side senders, and the server events that feed them. */
public final class RPNetworking {
	private RPNetworking() {
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(PlanetSettingsPayload.TYPE, PlanetSettingsPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MarsWeatherPayload.TYPE, MarsWeatherPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(HabitatSyncPayload.TYPE, HabitatSyncPayload.CODEC);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			send(player, PlanetSettingsPayload.of(PlanetSettings.forServer(server.overworld().getSeed())));
			MarsWeather.sendTo(player);
		});
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> MarsWeather.sendTo(player));
		ServerTickEvents.END_LEVEL_TICK.register(MarsWeather::tick);
	}

	public static <T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> void send(ServerPlayer player, T payload) {
		if (ServerPlayNetworking.canSend(player, payload.type())) {
			ServerPlayNetworking.send(player, payload);
		}
	}
}
