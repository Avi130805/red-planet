package io.github.avi130805.redplanet.registry;

import java.util.Optional;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.starship.flight.FlightProfile;

import net.fabricmc.fabric.api.event.registry.DynamicRegistries;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.TicketType;

/** Datapack registries and other registry entries that aren't blocks, items or entities. */
public final class RPRegistries {
	/**
	 * Flight profiles: {@code data/<namespace>/redplanet/flight_profile/<id>.json}. Synced to clients, which read the same
	 * profile for the HUD, the camera and smooth motion. Loaded with the world (editing one needs a world reload).
	 */
	public static final ResourceKey<Registry<FlightProfile>> FLIGHT_PROFILE = ResourceKey.createRegistryKey(RedPlanet.id("flight_profile"));

	/**
	 * Keeps a flying vehicle's chunks loaded and ticking (like an ender pearl's), so an uncrewed booster still flies home
	 * and a ship keeps flying when its crew logs out.
	 */
	public static final TicketType FLIGHT_TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, RedPlanet.id("starship_flight"),
		new TicketType(40L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

	public static final Identifier EARTH_TO_MARS = RedPlanet.id("earth_to_mars");
	public static final Identifier MARS_TO_EARTH = RedPlanet.id("mars_to_earth");

	private RPRegistries() {
	}

	public static Optional<FlightProfile> profile(RegistryAccess access, Identifier id) {
		return access.lookup(FLIGHT_PROFILE).flatMap(registry -> registry.getOptional(id));
	}

	public static void init() {
		DynamicRegistries.registerSynced(FLIGHT_PROFILE, FlightProfile.CODEC);
	}
}
