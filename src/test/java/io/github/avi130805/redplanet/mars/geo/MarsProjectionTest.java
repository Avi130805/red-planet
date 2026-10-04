package io.github.avi130805.redplanet.mars.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarsProjectionTest {
	@Test
	void equatorIsOneKilometrePerBlock() {
		assertEquals(0.0, MarsProjection.latitude(0.0), 1e-12);
		assertEquals(1.0, MarsProjection.kmPerBlock(0.0), 1e-12);
		// one block east on the equator moves 1/3396 rad = 0.01687 degrees
		assertEquals(Math.toDegrees(1.0 / 3396.0), MarsProjection.longitude(1.0), 1e-12);
	}

	@Test
	void longitudeWrapsEveryCircumference() {
		double c = MarsProjection.CIRCUMFERENCE_BLOCKS;
		assertEquals(21337.7, c, 0.1);
		assertEquals(MarsProjection.longitude(1234.5), MarsProjection.longitude(1234.5 + c), 1e-9);
		assertEquals(MarsProjection.longitude(1234.5), MarsProjection.longitude(1234.5 - 3 * c), 1e-9);
		assertEquals(270.0, MarsProjection.longitude(-c / 4.0), 1e-9);
	}

	@Test
	void mercatorRoundTrip() {
		for (double lat = -84.0; lat <= 84.0; lat += 3.5) {
			double z = MarsProjection.zOf(lat);
			assertEquals(lat, MarsProjection.latitude(z), 1e-9, "lat " + lat);
		}
		for (double lon = 0.0; lon < 360.0; lon += 7.25) {
			assertEquals(lon, MarsProjection.longitude(MarsProjection.xOf(lon)), 1e-9);
		}
	}

	@Test
	void northIsNegativeZ() {
		assertTrue(MarsProjection.zOf(45.0) < 0);
		assertTrue(MarsProjection.latitude(-1000.0) > 0);
	}

	@Test
	void mirroredBeyondPolarLimit() {
		double m = MarsProjection.MIRROR_Z;
		assertEquals(10634.0, m, 1.0);
		assertEquals(MarsProjection.MIRROR_LATITUDE, MarsProjection.latitude(-m), 1e-9);
		// 500 blocks past the northern limit looks like 500 blocks before it
		assertEquals(MarsProjection.latitude(-m + 500), MarsProjection.latitude(-m - 500), 1e-9);
		// far beyond: still a valid latitude, and continuous (no jumps)
		double prev = MarsProjection.latitude(-5 * m);
		for (double z = -5 * m; z < 5 * m; z += 37.0) {
			double lat = MarsProjection.latitude(z);
			assertTrue(Math.abs(lat) <= MarsProjection.MIRROR_LATITUDE + 1e-9);
			assertTrue(Math.abs(lat - prev) < 1.0, "discontinuity at z=" + z);
			prev = lat;
		}
	}

	@Test
	void verticalMapping() {
		assertEquals(MarsProjection.DATUM_Y, MarsProjection.yOfElevation(0.0), 1e-12);
		assertEquals(MarsProjection.DATUM_Y + 219.0, MarsProjection.yOfElevation(21900.0), 1e-9);
		assertEquals(-8200.0, MarsProjection.elevationOfY(MarsProjection.DATUM_Y - 82.0), 1e-9);
		// The deepest and highest MOLA points fit inside the dimension with room to spare.
		assertTrue(MarsProjection.yOfElevation(-8200) > MarsProjection.MIN_Y + 100);
		assertTrue(MarsProjection.yOfElevation(21200) < MarsProjection.MIN_Y + MarsProjection.HEIGHT - 40);
	}
}
