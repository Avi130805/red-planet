package io.github.avi130805.redplanet.mars;

import io.github.avi130805.redplanet.config.RedPlanetConfig;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * The per-world calendar inputs that both sides need to agree on: where in the Mars year the clock started, how
 * fast the seasons run, and the initial positions of Earth, Phobos and Deimos (derived from the world seed so every
 * world has its own sky). The server builds them from its config and seed; clients receive them on join
 * ({@code PlanetSettingsPayload}).
 *
 * @param startLs solar longitude (degrees) at Mars clock tick 0
 * @param yearCompression 1 = a real Mars year
 * @param earthPhaseAtStartDeg heliocentric longitude of Earth minus that of Mars at tick 0
 * @param moonPhaseSeed initial orbital phase (radians) of Phobos (Deimos derives its own)
 */
public record PlanetSettings(double startLs, double yearCompression, double earthPhaseAtStartDeg, double moonPhaseSeed) {
	public static final PlanetSettings DEFAULT = new PlanetSettings(0.0, 1.0, 120.0, 1.3);

	private static volatile PlanetSettings client = DEFAULT;

	/** Settings for the side that owns this level. */
	public static PlanetSettings of(Level level) {
		if (level instanceof ServerLevel serverLevel) {
			return forServer(serverLevel.getSeed());
		}
		return client;
	}

	public static PlanetSettings forServer(long seed) {
		RedPlanetConfig.Server config = RedPlanetConfig.server();
		long h = mix(seed ^ 0x5DEECE66DL);
		double earthPhase = (h >>> 11) * 0x1.0p-53 * 360.0;
		double moonPhase = (mix(h) >>> 11) * 0x1.0p-53 * 2.0 * Math.PI;
		return new PlanetSettings(config.startLs(), config.yearCompression(), earthPhase, moonPhase);
	}

	/** Called on the client when the server's settings arrive. */
	public static void setClient(PlanetSettings settings) {
		client = settings;
	}

	public static void resetClient() {
		client = DEFAULT;
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
		z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
		return z ^ (z >>> 33);
	}
}
