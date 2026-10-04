package io.github.avi130805.redplanet.mars.astro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Calibration checks for the temperature model against the measurements quoted in docs/SCIENCE.md, section 8.
 */
class MarsClimateTest {
	private static double sunDistance(double ls) {
		double nu = Math.toRadians(ls - MarsCalendar.LS_PERIHELION);
		double e = MarsCalendar.ECCENTRICITY;
		return 1.52368 * (1 - e * e) / (1 + e * Math.cos(nu));
	}

	private static double[] diurnal(double lat, double ls, double pressure, double tau) {
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		double sum = 0;
		int n = 240;
		for (int i = 0; i < n; i++) {
			double t = MarsClimate.surfaceTemperature(lat, ls, i / (double) n, sunDistance(ls), pressure, tau);
			min = Math.min(min, t);
			max = Math.max(max, t);
			sum += t;
		}
		return new double[]{min, max, sum / n};
	}

	@Test
	void co2FrostPointMatchesFanale() {
		assertEquals(147.9, MarsClimate.co2FrostPoint(610.0), 0.1);
		assertEquals(152.5, MarsClimate.co2FrostPoint(1170.0), 0.2);
		assertEquals(135.1, MarsClimate.co2FrostPoint(80.0), 0.2);
	}

	@Test
	void globalMeanIsAbout214K() {
		double total = 0;
		double weight = 0;
		for (int lat = -89; lat < 90; lat += 2) {
			double w = Math.cos(Math.toRadians(lat));
			for (int ls = 0; ls < 360; ls += 10) {
				total += diurnal(lat, ls, 600, 0.5)[2] * w;
				weight += w;
			}
		}
		assertEquals(214.0, total / weight, 3.0);
	}

	@Test
	void galeLateWinterMatchesRems() {
		// REMS before the 2018 storm (Ls ~150-185): ground max ~286 K, min ~187 K.
		double[] early = diurnal(-5.4, 150, 750, 0.5);
		double[] late = diurnal(-5.4, 180, 750, 0.5);
		assertTrue(early[1] > 265 && late[1] < 295, "max " + early[1] + " / " + late[1]);
		assertTrue(early[0] > 180 && late[0] < 205, "min " + early[0] + " / " + late[0]);
	}

	@Test
	void hottestGroundIsWithinNasaRange() {
		double max = 0;
		for (int ls = 0; ls < 360; ls += 5) {
			max = Math.max(max, diurnal(-15, ls, 600, 0.5)[1]);
		}
		// NASA: up to +20 C; JPL: up to +27 C.
		assertTrue(max > 285 && max < 301, "max " + max);
	}

	@Test
	void polarNightSitsAtTheFrostPoint() {
		double[] t = diurnal(80, 270, 600, 0.5);
		assertEquals(MarsClimate.co2FrostPoint(600), t[0], 1e-9);
		assertEquals(MarsClimate.co2FrostPoint(600), t[1], 1e-9);
	}

	@Test
	void seasonalPressureFollowsVikingAndRems() {
		double min = Double.MAX_VALUE;
		double max = 0;
		double minLs = 0;
		double maxLs = 0;
		double sum = 0;
		for (int i = 0; i < 3600; i++) {
			double ls = i / 10.0;
			double f = MarsClimate.seasonalPressureFactor(ls);
			sum += f;
			if (f < min) {
				min = f;
				minLs = ls;
			}
			if (f > max) {
				max = f;
				maxLs = ls;
			}
		}
		assertEquals(1.0, sum / 3600, 1e-6);
		assertEquals(0.87, min, 0.01);
		assertEquals(1.14, max, 0.01);
		assertEquals(152.8, minLs, 4.0);
		assertEquals(255.1, maxLs, 4.0);
	}

	@Test
	void dustStormsShrinkTheDailyRange() {
		double[] clear = diurnal(-5.4, 200, 750, 0.5);
		double[] storm = diurnal(-5.4, 200, 750, 8.5);
		assertTrue(storm[1] - storm[0] < 0.35 * (clear[1] - clear[0]));
	}
}
