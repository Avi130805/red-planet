package io.github.avi130805.redplanet.worldgen;

import io.github.avi130805.redplanet.mars.geo.MarsProjection;
import io.github.avi130805.redplanet.mars.geo.SimplexNoise;

/**
 * Where the cave biomes of the fiction layer lie (DESIGN.md section 8.3). Life hid underground from the radiation,
 * and each habitat sits under the terrain that would host it:
 *
 * <ul>
 * <li>{@code lichen_hollows}: lava tubes and caves under the volcanic provinces (Tharsis, Elysium);</li>
 * <li>{@code brine_grottoes}: meltwater channels in the polar caps and the mid-latitude glacier belt;</li>
 * <li>{@code gypsum_geodes}: the sulfate-rich sediments of Valles Marineris, Gale and Meridiani (real: Mount Sharp's
 * sulfate unit, the Meridiani sandstones, the canyon's interior layered deposits);</li>
 * <li>{@code arean_deep}: the deepest crust, just above bedrock, everywhere.</li>
 * </ul>
 *
 * <p>Each habitat fills a depth window below the local surface, broken into lobes by 3D noise, so caves inside the
 * window are mostly, not uniformly, of that biome. The layout is the same in every world (a biome source gets no
 * seed in 26.3), like the MOLA terrain itself.
 */
public final class MarsCaves {
	/**
	 * Blocks below this keep the surface biome: 26.3 picks a chunk's carvers from the biome at quart y 0 (block y 0-3,
	 * Mars' floor), and the carvers belong to the surface region (lava tubes under volcanoes, ice tubes under caps).
	 */
	public static final int CARVER_ROWS_TOP = 4;
	/** Mean top of the Arean deep; it undulates by {@link #AREAN_DEEP_WOBBLE} blocks. */
	static final int AREAN_DEEP_TOP = 34;
	static final int AREAN_DEEP_WOBBLE = 6;

	private static final SimplexNoise LOBES = new SimplexNoise(0x0A2EA4L);
	private static final SimplexNoise FLOOR = new SimplexNoise(0x0DEE9L);
	/** Width (blocks) of the cross-fade where the map wraps around the planet (as in MarsTerrain). */
	private static final double SEAM_BAND = 256.0;

	private MarsCaves() {
	}

	/** The cave habitat under a surface role, or null where the crust is plain rock. */
	static MarsBiome habitatUnder(MarsBiome surface) {
		return switch (surface) {
			case SHIELD_VOLCANO, VOLCANIC_PLAINS -> MarsBiome.LICHEN_HOLLOWS;
			case NORTH_POLAR_CAP, SOUTH_POLAR_CAP, MID_LATITUDE_GLACIERS -> MarsBiome.BRINE_GROTTOES;
			case CANYON, GALE_MOUND, MERIDIANI_PLANUM -> MarsBiome.GYPSUM_GEODES;
			default -> null;
		};
	}

	/**
	 * The biome role at a position: the surface role, a cave role, or the Arean deep.
	 *
	 * @param surface the column's surface role
	 * @param surfaceY the column's surface height (block Y)
	 */
	public static MarsBiome roleAt(MarsBiome surface, double surfaceY, int x, int y, int z) {
		if (y < CARVER_ROWS_TOP) {
			return surface;
		}
		if (y < AREAN_DEEP_TOP + AREAN_DEEP_WOBBLE * wrapped2(FLOOR, x, z, 140.0)) {
			return MarsBiome.AREAN_DEEP;
		}
		MarsBiome habitat = habitatUnder(surface);
		if (habitat == null) {
			return surface;
		}
		double depth = surfaceY - y;
		double top;
		double bottom;
		double threshold;
		switch (habitat) {
			case LICHEN_HOLLOWS -> {
				top = 8.0;
				bottom = 120.0;
				threshold = -0.25;
			}
			case BRINE_GROTTOES -> {
				top = 5.0;
				bottom = 70.0;
				threshold = -0.3;
			}
			default -> {
				top = 10.0;
				bottom = 140.0;
				threshold = -0.1;
			}
		}
		if (depth < top || depth > bottom) {
			return surface;
		}
		// Lobes, thinning toward the window's edges.
		double edge = Math.min(depth - top, bottom - depth) / 12.0;
		double lobes = wrapped3(LOBES, x, y, z) - 0.25 * Math.max(0.0, 1.0 - edge);
		return lobes > threshold ? habitat : surface;
	}

	/** 3D lobe noise (wavelengths ~90 blocks across, ~45 tall), periodic across the map's wrap in x. */
	private static double wrapped3(SimplexNoise noise, double x, double y, double z) {
		double c = MarsProjection.CIRCUMFERENCE_BLOCKS;
		double u = x - c * Math.floor(x / c);
		double v = noise.noise3(u / 90.0, y / 45.0, z / 90.0);
		double seam = smoothstep(c - SEAM_BAND, c, u);
		if (seam > 0.0) {
			v = (1.0 - seam) * v + seam * noise.noise3((u - c) / 90.0, y / 45.0, z / 90.0);
		}
		return v;
	}

	private static double wrapped2(SimplexNoise noise, double x, double z, double wavelength) {
		double c = MarsProjection.CIRCUMFERENCE_BLOCKS;
		double u = x - c * Math.floor(x / c);
		double v = noise.noise(u / wavelength, z / wavelength);
		double seam = smoothstep(c - SEAM_BAND, c, u);
		if (seam > 0.0) {
			v = (1.0 - seam) * v + seam * noise.noise((u - c) / wavelength, z / wavelength);
		}
		return v;
	}

	private static double smoothstep(double a, double b, double x) {
		double t = Math.clamp((x - a) / (b - a), 0.0, 1.0);
		return t * t * (3.0 - 2.0 * t);
	}
}
