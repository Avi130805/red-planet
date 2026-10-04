package io.github.avi130805.redplanet.client.config;

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

/** Client presentation settings, {@code config/redplanet-client.json}. */
public final class RedPlanetClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static volatile Settings settings = new Settings();

	private RedPlanetClientConfig() {
	}

	public static Settings get() {
		return settings;
	}

	public static final class Settings {
		/**
		 * Size of the Sun and moons in the Mars sky relative to their true angular size. 1 = true size (the Sun is
		 * 0.35 degrees); vanilla draws Earth's Sun about 16x too big, so 16 matches vanilla's exaggeration.
		 */
		public double skyBodyScale = 1.0;
		/** Draw the catalogue star field (5,080 stars) on Mars. */
		public boolean catalogueStars = true;

		public double skyBodyScale() {
			return Math.clamp(this.skyBodyScale, 0.5, 20.0);
		}
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("redplanet-client.json");
		Settings loaded = null;
		if (Files.exists(path)) {
			try (Reader r = Files.newBufferedReader(path)) {
				loaded = GSON.fromJson(r, Settings.class);
			} catch (IOException | JsonParseException e) {
				RedPlanet.LOGGER.warn("Could not read {}, using defaults", path, e);
			}
		}
		settings = loaded != null ? loaded : new Settings();
		try {
			Files.createDirectories(path.getParent());
			try (Writer w = Files.newBufferedWriter(path)) {
				GSON.toJson(settings, w);
			}
		} catch (IOException e) {
			RedPlanet.LOGGER.warn("Could not write {}", path, e);
		}
	}
}
