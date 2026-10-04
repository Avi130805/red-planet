package io.github.avi130805.redplanet.mars.astro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarsAstronomyTest {
	private static final int SOL = MarsCalendar.TICKS_PER_SOL;

	private static MarsAstronomy.Sky sky(long ticks, double lat) {
		return MarsAstronomy.compute(ticks, 0.0, lat, 0.0, 1.0, 75.0, 0.7);
	}

	@Test
	void sunRisesEastAtSixAndCulminatesAtNoonOnEquatorAtEquinox() {
		MarsAstronomy.Sky dawn = sky(0, 0.0);
		assertEquals(0.0, dawn.sunAltitudeDeg(), 0.5);
		assertTrue(dawn.sun()[0] > 0.99, "sun should be due east at 06:00");
		MarsAstronomy.Sky noon = sky(SOL / 4, 0.0);
		assertTrue(noon.sunAltitudeDeg() > 89.0, "noon altitude " + noon.sunAltitudeDeg());
		MarsAstronomy.Sky dusk = sky(SOL / 2, 0.0);
		assertTrue(dusk.sun()[0] < -0.99, "sun should be due west at 18:00");
		assertTrue(sky(3 * SOL / 4, 0.0).sunAltitudeDeg() < -89.0);
	}

	@Test
	void sunIsAboutTwoThirdsTheSizeSeenFromEarth() {
		MarsAstronomy.Sky s = sky(0, 0.0);
		assertTrue(s.sunAngularDiameterDeg() > 0.32 && s.sunAngularDiameterDeg() < 0.39, "sun " + s.sunAngularDiameterDeg());
	}

	@Test
	void noonSunAltitudeFollowsLatitudeAndSeason() {
		// At 45 N on the equinox the noon sun stands 45 degrees high, due south.
		MarsAstronomy.Sky s = sky(SOL / 4, 45.0);
		assertEquals(45.0, s.sunAltitudeDeg(), 0.6);
		assertTrue(s.sun()[2] > 0.7, "noon sun should be in the south (+z)");
	}

	@Test
	void phobosRisesInTheWestAndSetsInTheEast() {
		double prevAlt = Double.NaN;
		int rises = 0;
		int sets = 0;
		for (long t = 0; t < 3L * SOL; t += 5) {
			MarsAstronomy.Sky s = sky(t, 0.0);
			double alt = s.phobos()[1];
			if (!Double.isNaN(prevAlt)) {
				if (prevAlt <= 0 && alt > 0) {
					rises++;
					assertTrue(s.phobos()[0] < -0.9, "Phobos should rise in the west, x=" + s.phobos()[0]);
				} else if (prevAlt > 0 && alt <= 0) {
					sets++;
					assertTrue(s.phobos()[0] > 0.9, "Phobos should set in the east, x=" + s.phobos()[0]);
				}
			}
			prevAlt = alt;
		}
		// Synodic period relative to the surface is about 11.1 h, so ~2.2 rises per sol.
		assertTrue(rises >= 6 && rises <= 7, "rises in 3 sols: " + rises);
		assertTrue(Math.abs(rises - sets) <= 1);
	}

	@Test
	void phobosPassLastsAboutFourAndAQuarterHours() {
		long upStart = -1;
		double longest = 0;
		double prevAlt = Double.NaN;
		for (long t = 0; t < 2L * SOL; t += 2) {
			double alt = sky(t, 0.0).phobos()[1];
			if (!Double.isNaN(prevAlt)) {
				if (prevAlt <= 0 && alt > 0) {
					upStart = t;
				} else if (prevAlt > 0 && alt <= 0 && upStart >= 0) {
					longest = Math.max(longest, (t - upStart) * MarsAstronomy.SOL_H / SOL);
				}
			}
			prevAlt = alt;
		}
		assertEquals(4.25, longest, 0.2, "Phobos pass hours");
	}

	@Test
	void phobosAngularSizeAndPolarInvisibility() {
		double maxSize = 0;
		boolean seenAt75 = false;
		for (long t = 0; t < 2L * SOL; t += 10) {
			MarsAstronomy.Sky eq = sky(t, 0.0);
			if (eq.phobos()[1] > 0) {
				maxSize = Math.max(maxSize, eq.phobosAngularDiameterDeg());
			}
			if (sky(t, 75.0).phobos()[1] > 0) {
				seenAt75 = true;
			}
		}
		assertEquals(0.21, maxSize, 0.02, "Phobos max angular diameter");
		assertFalse(seenAt75, "Phobos must never rise at 75 N");
	}

	@Test
	void deimosRisesInTheEastAndIsStarLike() {
		double prevAlt = Double.NaN;
		boolean sawRise = false;
		for (long t = 0; t < 12L * SOL; t += 20) {
			MarsAstronomy.Sky s = sky(t, 0.0);
			double alt = s.deimos()[1];
			if (!Double.isNaN(prevAlt) && prevAlt <= 0 && alt > 0) {
				sawRise = true;
				assertTrue(s.deimos()[0] > 0.9, "Deimos should rise in the east");
			}
			assertTrue(s.deimosAngularDiameterDeg() < 0.04);
			prevAlt = alt;
		}
		assertTrue(sawRise);
	}

	@Test
	void earthStaysWithinAbout47DegreesOfTheSun() {
		double maxElong = 0;
		for (long sol = 0; sol < 1600; sol += 3) {
			maxElong = Math.max(maxElong, sky(sol * SOL, 0.0).earthElongationDeg());
		}
		assertTrue(maxElong > 40.0 && maxElong < 47.5, "max elongation " + maxElong);
	}

	@Test
	void earthIsBrightNearGreatestElongation() {
		double best = 10;
		for (long sol = 0; sol < 800; sol += 2) {
			MarsAstronomy.Sky s = sky(sol * SOL, 0.0);
			if (s.earthElongationDeg() > 35) {
				best = Math.min(best, s.earthMagnitude());
			}
		}
		assertTrue(best < -2.0 && best > -3.5, "Earth magnitude " + best);
	}

	@Test
	void celestialPoleAltitudeEqualsLatitude() {
		double[] rot = sky(1234, 30.0).starRotation();
		// Mars-equatorial north pole (0,0,1) -> third column
		double up = rot[3 + 2];
		double south = rot[6 + 2];
		assertEquals(Math.sin(Math.toRadians(30.0)), up, 1e-9);
		assertTrue(south < 0, "pole should be in the north (-z)");
	}

	@Test
	void seasonsAdvanceWithKeplerianSpeed() {
		long year = Math.round(MarsCalendar.SOLS_PER_YEAR * SOL);
		assertEquals(0.0, MarsCalendar.solarLongitude(0, 0.0, 1.0), 1e-9);
		double lsHalf = MarsCalendar.solarLongitude(year / 2, 0.0, 1.0);
		// Northern spring+summer (Ls 0-180) is the long half of the year (aphelion near Ls 71),
		// so after half a year Ls has not yet reached 180.
		assertTrue(lsHalf < 180.0 && lsHalf > 150.0, "Ls after half a year " + lsHalf);
		double lsYear = MarsCalendar.solarLongitude(year, 0.0, 1.0);
		assertEquals(0.0, Math.min(lsYear, 360.0 - lsYear), 0.5);
		assertEquals("summer", MarsCalendar.seasonKey(100, true));
		assertEquals("winter", MarsCalendar.seasonKey(100, false));
	}

	@Test
	void seasonLengthsMatchNasa() {
		// NASA Mars Facts: northern spring 194 sols, summer 178, autumn 142, winter 154.
		double[] expected = {194, 178, 142, 154};
		for (int season = 0; season < 4; season++) {
			double start = solsToReach(season * 90.0);
			double end = solsToReach(season * 90.0 + 90.0);
			assertEquals(expected[season], end - start, 1.0, "season " + season);
		}
	}

	/** Sols from Ls 0 until the given Ls (0-360], by bisection on the calendar. */
	private static double solsToReach(double ls) {
		if (ls <= 0.0) {
			return 0.0;
		}
		double lo = 0.0;
		double hi = MarsCalendar.SOLS_PER_YEAR;
		for (int i = 0; i < 60; i++) {
			double mid = 0.5 * (lo + hi);
			double value = MarsCalendar.solarLongitude(Math.round(mid * SOL), 0.0, 1.0);
			boolean wrapped = mid > MarsCalendar.SOLS_PER_YEAR / 2 && value < 90.0;
			double unwrapped = wrapped ? value + 360.0 : value;
			if (unwrapped < ls) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return lo;
	}

	@Test
	void lmstFormatting() {
		assertEquals("06:00", MarsCalendar.formatLmst(0));
		assertEquals("12:00", MarsCalendar.formatLmst(SOL / 4));
		assertEquals("00:00", MarsCalendar.formatLmst(3 * SOL / 4));
	}
}
