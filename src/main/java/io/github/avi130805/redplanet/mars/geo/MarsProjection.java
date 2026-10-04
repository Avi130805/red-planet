package io.github.avi130805.redplanet.mars.geo;

/**
 * Maps Minecraft block coordinates in the Mars dimension to areographic coordinates and back.
 *
 * <p>Horizontal: a Mercator projection of the MOLA reference sphere (radius 3396.0 km) at
 * 1 block = 1 km on the equator. Mercator is conformal, so craters stay round at every latitude; the
 * price is that the map scale grows as 1/cos(latitude) toward the poles (at 60 degrees one block is
 * 500 m). +x is east and the map repeats every 2*pi*3396 = 21,337.7 blocks, so walking east forever
 * circles the planet. -z is north (Minecraft's north). Beyond +/-85 degrees latitude, where Mercator
 * diverges, the map is mirrored back onto itself, so the polar cap continues seamlessly instead of
 * ending in a wall.
 *
 * <p>Vertical: elevation above the MOLA areoid maps linearly at 100 m per block, with the areoid
 * ("datum", 0 m) at {@link #DATUM_Y}. Relief is therefore exaggerated about 10x relative to the
 * horizontal scale at the equator, which keeps Olympus Mons, Valles Marineris and Hellas readable
 * in a 448-block-tall world. See docs/SCIENCE.md, "Map scale".
 */
public final class MarsProjection {
	/** MOLA reference sphere radius, km. Also the projection radius in blocks (1 block = 1 km at the equator). */
	public static final double RADIUS_KM = 3396.0;
	/** Blocks per full turn of longitude. */
	public static final double CIRCUMFERENCE_BLOCKS = 2.0 * Math.PI * RADIUS_KM;
	/** Latitude (degrees) at which the Mercator map is mirrored. */
	public static final double MIRROR_LATITUDE = 85.0;
	/** |z| at {@link #MIRROR_LATITUDE}. */
	public static final double MIRROR_Z = RADIUS_KM * Math.log(Math.tan(Math.PI / 4.0 + Math.toRadians(MIRROR_LATITUDE) / 2.0));

	/** Dimension floor (inclusive) and height; must match data/redplanet/dimension_type/mars.json. */
	public static final int MIN_Y = 0;
	public static final int HEIGHT = 448;
	/** Block Y of the MOLA areoid (0 m elevation). */
	public static final int DATUM_Y = 192;
	/** Metres of elevation per block. */
	public static final double METRES_PER_BLOCK_VERTICAL = 100.0;

	private MarsProjection() {
	}

	/** Longitude in degrees east, normalized to [0, 360). */
	public static double longitude(double x) {
		double lon = Math.toDegrees(x / RADIUS_KM);
		lon %= 360.0;
		return lon < 0 ? lon + 360.0 : lon;
	}

	/** Latitude in degrees north, in [-MIRROR_LATITUDE, MIRROR_LATITUDE]. */
	public static double latitude(double z) {
		double zf = foldZ(z);
		return Math.toDegrees(2.0 * Math.atan(Math.exp(-zf / RADIUS_KM)) - Math.PI / 2.0);
	}

	/**
	 * Folds z into [-MIRROR_Z, MIRROR_Z] with a triangle wave, mirroring the map at the polar limits.
	 */
	public static double foldZ(double z) {
		double period = 4.0 * MIRROR_Z;
		double t = (z + MIRROR_Z) % period;
		if (t < 0) {
			t += period;
		}
		// t in [0, 4M): rising 0..2M maps to -M..M, falling 2M..4M maps back M..-M
		return t <= 2.0 * MIRROR_Z ? t - MIRROR_Z : 3.0 * MIRROR_Z - t;
	}

	/** Block x of a longitude (degrees east), in the copy of the map that starts at x = 0. */
	public static double xOf(double lonDeg) {
		double lon = lonDeg % 360.0;
		if (lon < 0) {
			lon += 360.0;
		}
		return Math.toRadians(lon) * RADIUS_KM;
	}

	/** Block z of a latitude (degrees north), in the unmirrored band |z| <= MIRROR_Z. */
	public static double zOf(double latDeg) {
		double lat = Math.clamp(latDeg, -MIRROR_LATITUDE, MIRROR_LATITUDE);
		return -RADIUS_KM * Math.log(Math.tan(Math.PI / 4.0 + Math.toRadians(lat) / 2.0));
	}

	/** Horizontal size of one block in kilometres at a latitude (Mercator scale factor). */
	public static double kmPerBlock(double latDeg) {
		return Math.cos(Math.toRadians(latDeg));
	}

	/** Fractional block Y of an elevation (metres above the areoid). */
	public static double yOfElevation(double elevationMetres) {
		return DATUM_Y + elevationMetres / METRES_PER_BLOCK_VERTICAL;
	}

	/** Elevation in metres above the areoid of a block Y. */
	public static double elevationOfY(double y) {
		return (y - DATUM_Y) * METRES_PER_BLOCK_VERTICAL;
	}
}
