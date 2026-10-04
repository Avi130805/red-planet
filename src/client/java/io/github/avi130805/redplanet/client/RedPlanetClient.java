package io.github.avi130805.redplanet.client;

import io.github.avi130805.redplanet.client.config.RedPlanetClientConfig;
import io.github.avi130805.redplanet.client.sky.MarsSkyClient;
import io.github.avi130805.redplanet.client.sky.RPRenderPipelines;
import io.github.avi130805.redplanet.mars.PlanetSettings;
import io.github.avi130805.redplanet.mars.weather.MarsWeather;
import io.github.avi130805.redplanet.network.MarsWeatherPayload;
import io.github.avi130805.redplanet.network.PlanetSettingsPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Client entrypoint. Keep it free of GPU calls: it also runs before client-mode datagen. */
public class RedPlanetClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		RedPlanetClientConfig.load();
		RPRenderPipelines.init();
		MarsSkyClient.init();

		ClientPlayNetworking.registerGlobalReceiver(PlanetSettingsPayload.TYPE, (payload, context) -> PlanetSettings.setClient(payload.settings()));
		ClientPlayNetworking.registerGlobalReceiver(MarsWeatherPayload.TYPE, (payload, context) ->
			MarsWeather.setClientStorm(payload.storm().orElse(null)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			PlanetSettings.resetClient();
			MarsWeather.setClientStorm(null);
		});
	}
}
