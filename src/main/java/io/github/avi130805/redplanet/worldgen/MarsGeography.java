package io.github.avi130805.redplanet.worldgen;

import io.github.avi130805.redplanet.mars.geo.MarsTerrain;

/**
 * Chooses a biome role from the geography of a column. Regions are defined by real areographic coordinates
 * (volcano summits and basin centres from MOLA; sites from the mission landing ellipses) and by measured
 * properties (elevation, regional depression, albedo, ice cover, dune cover). Sources: docs/SCIENCE.md.
 */
public final class MarsGeography {
	/** A circular region: centre (deg N, deg E) and radius (deg of arc). */
	record Circle(double lat, double lon, double radiusDeg) {
		boolean contains(double latDeg, double lonDeg) {
			return angularDistance(this.lat, this.lon, latDeg, lonDeg) <= this.radiusDeg;
		}
	}

	static final Circle[] SHIELD_VOLCANOES = {
		new Circle(18.65, 226.2, 4.6),  // Olympus Mons (~600 km across incl. the scarp)
		new Circle(11.8, 255.5, 3.6),   // Ascraeus Mons
		new Circle(1.5, 247.0, 3.2),    // Pavonis Mons
		new Circle(-8.35, 239.9, 3.8),  // Arsia Mons
		new Circle(25.02, 147.2, 2.4),  // Elysium Mons
		new Circle(40.5, 250.4, 6.0),   // Alba Mons (broad, low)
	};
	static final Circle HELLAS = new Circle(-42.4, 70.5, 17.0);
	static final Circle ARGYRE = new Circle(-49.7, 316.0, 8.5);
	static final Circle ISIDIS = new Circle(13.0, 87.0, 8.0);
	static final Circle GALE = new Circle(-5.37, 137.81, 1.3);
	static final Circle JEZERO = new Circle(18.38, 77.58, 0.4);

	private MarsGeography() {
	}

	public static MarsBiome classify(MarsTerrain.Column c) {
		double lat = c.lat;
		double lon = c.lon;
		if (GALE.contains(lat, lon)) {
			return MarsBiome.GALE_MOUND;
		}
		if (JEZERO.contains(lat, lon)) {
			return MarsBiome.JEZERO_DELTA;
		}
		if (lat > -5.5 && lat < 3.0 && (lon > 352.0 || lon < 8.0)) {
			return MarsBiome.MERIDIANI_PLANUM;
		}
		if (c.polarCap > 0.5) {
			return lat > 0 ? MarsBiome.NORTH_POLAR_CAP : MarsBiome.SOUTH_POLAR_CAP;
		}
		for (Circle v : SHIELD_VOLCANOES) {
			if (v.contains(lat, lon)) {
				return MarsBiome.SHIELD_VOLCANO;
			}
		}
		double relief = c.baseElevation - c.regionalElevation;
		if (lon > 258.0 && lon < 323.0 && lat > -17.0 && lat < 3.0 && relief < -1200.0) {
			return MarsBiome.CANYON; // Valles Marineris and Noctis Labyrinthus
		}
		if ((HELLAS.contains(lat, lon) && c.baseElevation < -3500.0)
			|| (ARGYRE.contains(lat, lon) && c.baseElevation < -1500.0)
			|| (ISIDIS.contains(lat, lon) && c.baseElevation < -3000.0)) {
			return MarsBiome.IMPACT_BASIN;
		}
		if (c.dunes > 0.45) {
			return MarsBiome.DUNE_FIELD;
		}
		if ((lat > 35.0 && lat < 50.0 && lon > 0.0 && lon < 75.0) || (lat > -46.0 && lat < -34.0 && lon > 95.0 && lon < 115.0)) {
			return MarsBiome.MID_LATITUDE_GLACIERS;
		}
		boolean tharsis = lon > 220.0 && lon < 300.0 && lat > -35.0 && lat < 45.0 && c.regionalElevation > 1000.0;
		boolean elysium = lon > 130.0 && lon < 165.0 && lat > 5.0 && lat < 40.0 && c.regionalElevation > -1500.0;
		if (tharsis || elysium) {
			return MarsBiome.VOLCANIC_PLAINS;
		}
		if (c.regionalElevation < -2500.0 && lat > -25.0) {
			return MarsBiome.NORTHERN_PLAINS;
		}
		if (c.albedo > 0.245) {
			return MarsBiome.DUSTY_HIGHLANDS;
		}
		return MarsBiome.CRATERED_HIGHLANDS;
	}

	/** Great-circle distance in degrees. */
	static double angularDistance(double lat1, double lon1, double lat2, double lon2) {
		double p1 = Math.toRadians(lat1);
		double p2 = Math.toRadians(lat2);
		double dl = Math.toRadians(lon2 - lon1);
		double c = Math.sin(p1) * Math.sin(p2) + Math.cos(p1) * Math.cos(p2) * Math.cos(dl);
		return Math.toDegrees(Math.acos(Math.clamp(c, -1.0, 1.0)));
	}
}
