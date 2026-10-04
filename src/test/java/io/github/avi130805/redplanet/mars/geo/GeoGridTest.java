package io.github.avi130805.redplanet.mars.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks the shipped MOLA/TES grids against well-known Mars landmarks.
 */
class GeoGridTest {
	private static GeoGrid topo;
	private static GeoGrid albedo;

	@BeforeAll
	static void load() throws IOException {
		topo = load("/redplanet/mars/topography.rpgrid");
		albedo = load("/redplanet/mars/albedo.rpgrid");
	}

	private static GeoGrid load(String path) throws IOException {
		try (InputStream in = GeoGridTest.class.getResourceAsStream(path)) {
			assertNotNull(in, path);
			return GeoGrid.read(in);
		}
	}

	@Test
	void dimensions() {
		assertEquals(2880, topo.width());
		assertEquals(1440, topo.height());
		assertEquals(8.0, topo.pixelsPerDegree(), 1e-12);
		assertEquals(1440, albedo.width());
	}

	@Test
	void olympusMonsSummitIsAbout21Km() {
		// Summit caldera near 18.4 N, 226.2 E (MOLA: 21.2 km above the areoid).
		double best = Double.NEGATIVE_INFINITY;
		for (double lat = 17.0; lat <= 20.0; lat += 0.05) {
			for (double lon = 225.0; lon <= 228.0; lon += 0.05) {
				best = Math.max(best, topo.sample(lat, lon));
			}
		}
		assertTrue(best > 20500 && best < 21500, "Olympus Mons summit " + best);
	}

	@Test
	void hellasFloorIsBelowMinus7Km() {
		double lowest = Double.POSITIVE_INFINITY;
		for (double lat = -50.0; lat <= -30.0; lat += 0.25) {
			for (double lon = 55.0; lon <= 85.0; lon += 0.25) {
				lowest = Math.min(lowest, topo.sample(lat, lon));
			}
		}
		assertTrue(lowest < -7000 && lowest > -8300, "Hellas floor " + lowest);
	}

	@Test
	void dichotomyNorthLowerThanSouth() {
		// Vastitas Borealis vs Noachis Terra: several km of difference.
		double north = topo.sample(60.0, 30.0);
		double south = topo.sample(-45.0, 0.0);
		assertTrue(south - north > 3000, "north " + north + " south " + south);
	}

	@Test
	void galeCraterHasCentralMoundAboveFloor() {
		// Gale crater centred ~5.4 S, 137.8 E; Aeolis Mons (Mount Sharp) rises ~5 km above the floor.
		double mound = topo.sample(-5.08, 137.85);
		double floorNorth = topo.sample(-4.6, 137.4); // Bradbury Landing area, about -4.5 km
		assertTrue(floorNorth < -4000, "Gale floor " + floorNorth);
		assertTrue(mound - floorNorth > 2000, "Mount Sharp relief " + (mound - floorNorth));
	}

	@Test
	void syrtisMajorIsDarkAndArabiaBright() {
		double syrtis = albedo.sample(8.0, 70.0);
		double arabia = albedo.sample(20.0, 20.0);
		double tharsis = albedo.sample(5.0, 250.0);
		assertTrue(syrtis < 0.16, "Syrtis Major albedo " + syrtis);
		assertTrue(arabia > 0.22, "Arabia Terra albedo " + arabia);
		assertTrue(tharsis > 0.22, "Tharsis albedo " + tharsis);
	}

	@Test
	void samplingWrapsAcrossPrimeMeridian() {
		assertEquals(topo.sample(10.0, 359.99), topo.sample(10.0, -0.01), 1e-9);
		double a = topo.sample(10.0, 359.95);
		double b = topo.sample(10.0, 0.05);
		assertTrue(Math.abs(a - b) < 800, "seam " + a + " vs " + b);
	}
}
