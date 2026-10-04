package io.github.avi130805.redplanet.client;

import io.github.avi130805.redplanet.client.config.RedPlanetClientConfig;
import io.github.avi130805.redplanet.client.particle.DustMoteParticle;
import io.github.avi130805.redplanet.client.particle.SteamCloudParticle;
import io.github.avi130805.redplanet.client.sky.MarsSkyClient;
import io.github.avi130805.redplanet.client.sky.RPRenderPipelines;
import io.github.avi130805.redplanet.client.sound.EngineSounds;
import io.github.avi130805.redplanet.client.sound.MarsStormAmbience;
import io.github.avi130805.redplanet.client.starship.StarshipRenderTypes;
import io.github.avi130805.redplanet.client.starship.StarshipRenderer;
import io.github.avi130805.redplanet.client.starship.SuperHeavyRenderer;
import io.github.avi130805.redplanet.client.starship.flight.FlightKeys;
import io.github.avi130805.redplanet.client.starship.flight.TelemetryHud;
import io.github.avi130805.redplanet.client.starship.interlude.InterludeController;
import io.github.avi130805.redplanet.mars.PlanetSettings;
import io.github.avi130805.redplanet.mars.weather.MarsWeather;
import io.github.avi130805.redplanet.network.MarsWeatherPayload;
import io.github.avi130805.redplanet.network.PlanetSettingsPayload;
import io.github.avi130805.redplanet.registry.RPEntities;
import io.github.avi130805.redplanet.registry.RPParticles;
import io.github.avi130805.redplanet.registry.RPStarship;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.NoopRenderer;

/** Client entrypoint. Keep it free of GPU calls: it also runs before client-mode datagen. */
public class RedPlanetClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		RedPlanetClientConfig.load();
		RPRenderPipelines.init();
		MarsSkyClient.init();
		ParticleProviderRegistry.getInstance().register(RPParticles.DUST_MOTE, DustMoteParticle.Provider::new);
		ParticleProviderRegistry.getInstance().register(RPParticles.DUST_PUFF, DustMoteParticle.PuffProvider::new);
		ParticleProviderRegistry.getInstance().register(RPParticles.STEAM_CLOUD, SteamCloudParticle.Provider::new);
		ParticleProviderRegistry.getInstance().register(RPParticles.DUST_CLOUD, SteamCloudParticle.DustProvider::new);
		ParticleProviderRegistry.getInstance().register(RPParticles.VENT, SteamCloudParticle.VentProvider::new);
		MarsStormAmbience.init();
		// Dust devils have no body: DustDevil draws itself as spiralling dust motes.
		EntityRendererRegistry.register(RPEntities.DUST_DEVIL, NoopRenderer::new);
		StarshipRenderTypes.init();
		EntityRenderers.register(RPStarship.STARSHIP, StarshipRenderer::new);
		EntityRenderers.register(RPStarship.SUPER_HEAVY, SuperHeavyRenderer::new);
		FlightKeys.init();
		TelemetryHud.register();
		InterludeController.init();
		EngineSounds.init();

		ClientPlayNetworking.registerGlobalReceiver(PlanetSettingsPayload.TYPE, (payload, context) -> PlanetSettings.setClient(payload.settings()));
		ClientPlayNetworking.registerGlobalReceiver(MarsWeatherPayload.TYPE, (payload, context) ->
			MarsWeather.setClientStorm(payload.storm().orElse(null)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			PlanetSettings.resetClient();
			MarsWeather.setClientStorm(null);
		});
	}
}
