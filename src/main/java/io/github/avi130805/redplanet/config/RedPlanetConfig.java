package io.github.avi130805.redplanet.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import io.github.avi130805.redplanet.RedPlanet;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Server-side gameplay settings, stored as JSON in {@code config/redplanet-server.json}. Client presentation
 * settings live in the client config. Every field has a documented default that matches the science.
 */
public final class RedPlanetConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static volatile Server server = new Server();

	private RedPlanetConfig() {
	}

	public static Server server() {
		return server;
	}

	/** Gameplay settings. Plain fields for Gson; read through the accessors. */
	public static final class Server {
		/** Solar longitude (degrees) at Mars clock tick 0. 0 = northern spring equinox. */
		public double startLs = 0.0;
		/** 1 = a real Mars year (668.6 sols); larger values compress the seasons for gameplay. */
		public double yearCompression = 1.0;
		/** Hypoxia damage when breathing Mars air without protection. */
		public boolean hypoxiaDamage = true;
		/** Flight pacing used when a mission does not specify one: short, standard, long. */
		public String defaultPacing = "standard";
		/** Allow passengers to skip the flight cinematics (multiplayer needs every passenger's vote). */
		public boolean allowSkip = true;
		/** Average sols between regional dust storms (0 disables storms). */
		public double dustStormIntervalSols = 30.0;
		/** Scale for ISRU production rates (1 = standard gameplay economy; realistic tonnages are always shown). */
		public double propellantProductionScale = 1.0;
		/** Radiation dose effects (sickness above high cumulative doses). Dose is always tracked. */
		public boolean radiationEffects = false;

		public double startLs() {
			return this.startLs;
		}

		public double yearCompression() {
			return Math.max(1.0, this.yearCompression);
		}
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("redplanet-server.json");
		Server loaded = null;
		if (Files.exists(path)) {
			try (Reader r = Files.newBufferedReader(path)) {
				loaded = GSON.fromJson(r, Server.class);
			} catch (IOException | JsonParseException e) {
				RedPlanet.LOGGER.warn("Could not read {}, using defaults", path, e);
			}
		}
		server = loaded != null ? loaded : new Server();
		try {
			Files.createDirectories(path.getParent());
			try (Writer w = Files.newBufferedWriter(path)) {
				GSON.toJson(server, w);
			}
		} catch (IOException e) {
			RedPlanet.LOGGER.warn("Could not write {}", path, e);
		}
	}
}
