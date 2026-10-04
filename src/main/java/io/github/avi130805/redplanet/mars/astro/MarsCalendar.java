package io.github.avi130805.redplanet.mars.astro;

/**
 * Mars timekeeping for the Mars dimension clock.
 *
 * <p>A sol (mean solar day) is 88,775.244 s = 1.027491 Earth days. Vanilla maps one Earth day to
 * 24,000 ticks, so one sol is 24,000 x 1.027491 = 24,659.8, rounded to {@link #TICKS_PER_SOL} = 24,660
 * ticks (41.1 real minutes). The clock follows vanilla's convention that tick 0 of a day is 06:00
 * local time (sunrise at the equator at equinox) and a quarter-period later is noon.
 *
 * <p>Seasons use areocentric solar longitude Ls (0 = northern spring equinox, 90 = northern summer
 * solstice, 180 = autumn equinox, 270 = winter solstice), computed from Mars' Keplerian orbit.
 * By default a Mars year is the real 668.6 sols; {@code yearCompression} shortens it for gameplay.
 */
public final class MarsCalendar {
	public static final int TICKS_PER_SOL = 24660;
	/** Mars tropical year in sols (686.9725 Earth days; Mars24). Seasons follow the tropical year. */
	public static final double SOLS_PER_YEAR = 668.5921;
	/** Orbital eccentricity of Mars. */
	public static final double ECCENTRICITY = 0.0934;
	/** Ls of perihelion, degrees (J2000; late southern spring, just before the southern summer solstice at Ls 270). */
	public static final double LS_PERIHELION = 251.0;
	/** Seconds in one sol. */
	public static final double SECONDS_PER_SOL = 88775.244;
	/** Mars clock hours (1/24 sol) per sol, for LMST display. */
	public static final int HOURS_PER_SOL = 24;

	private MarsCalendar() {
	}

	/** Sol number (0-based) of a clock tick count. */
	public static long sol(long ticks) {
		return Math.floorDiv(ticks, TICKS_PER_SOL);
	}

	/** Ticks into the current sol, 0..TICKS_PER_SOL-1. */
	public static int tickOfSol(long ticks) {
		return (int) Math.floorMod(ticks, (long) TICKS_PER_SOL);
	}

	/**
	 * Local mean solar time as a fraction of a sol, 0 = midnight, 0.5 = noon.
	 * Tick 0 of a sol is 06:00, matching vanilla's day timeline convention.
	 */
	public static double lmstFraction(long ticks) {
		double f = tickOfSol(ticks) / (double) TICKS_PER_SOL + 0.25;
		return f >= 1.0 ? f - 1.0 : f;
	}

	/** Solar hour angle in radians, 0 at local noon, positive in the afternoon (sun in the west). */
	public static double hourAngle(long ticks, double partialTick) {
		double f = (tickOfSol(ticks) + partialTick) / TICKS_PER_SOL + 0.25;
		return 2.0 * Math.PI * (f - 0.5);
	}

	/** "HH:MM" local mean solar time in Mars clock hours. */
	public static String formatLmst(long ticks) {
		double hours = lmstFraction(ticks) * HOURS_PER_SOL;
		int h = (int) Math.floor(hours);
		int m = (int) Math.floor((hours - h) * 60.0);
		return String.format("%02d:%02d", h, m);
	}

	/**
	 * Mean anomaly (radians) at a clock tick, assuming the clock started at Ls = {@code startLs}.
	 *
	 * @param yearCompression 1.0 for a real Mars year; e.g. 10.0 makes a year 66.9 sols long
	 */
	public static double meanAnomaly(long ticks, double startLs, double yearCompression) {
		double sols = ticks / (double) TICKS_PER_SOL;
		double m0 = meanAnomalyOfLs(startLs);
		return m0 + 2.0 * Math.PI * sols * yearCompression / SOLS_PER_YEAR;
	}

	/** Solar longitude Ls in degrees [0, 360) at a clock tick. */
	public static double solarLongitude(long ticks, double startLs, double yearCompression) {
		double nu = trueAnomaly(meanAnomaly(ticks, startLs, yearCompression));
		return normalizeDegrees(Math.toDegrees(nu) + LS_PERIHELION);
	}

	/** Sun-Mars distance in AU at a clock tick. */
	public static double sunDistanceAu(long ticks, double startLs, double yearCompression) {
		double m = meanAnomaly(ticks, startLs, yearCompression);
		double e = eccentricAnomaly(m);
		return 1.52368 * (1.0 - ECCENTRICITY * Math.cos(e));
	}

	/** Solar declination (radians) seen from Mars at a given Ls (degrees). */
	public static double solarDeclination(double lsDeg) {
		return Math.asin(Math.sin(Math.toRadians(MarsAstronomy.OBLIQUITY_DEG)) * Math.sin(Math.toRadians(lsDeg)));
	}

	/** Northern-hemisphere season name for an Ls. */
	public static String seasonKey(double lsDeg, boolean northernHemisphere) {
		int q = (int) Math.floor(normalizeDegrees(lsDeg) / 90.0);
		if (!northernHemisphere) {
			q = (q + 2) % 4;
		}
		return switch (q) {
			case 0 -> "spring";
			case 1 -> "summer";
			case 2 -> "autumn";
			default -> "winter";
		};
	}

	static double eccentricAnomaly(double meanAnomaly) {
		double m = meanAnomaly % (2.0 * Math.PI);
		double e = m + ECCENTRICITY * Math.sin(m);
		for (int i = 0; i < 8; i++) {
			e -= (e - ECCENTRICITY * Math.sin(e) - m) / (1.0 - ECCENTRICITY * Math.cos(e));
		}
		return e;
	}

	static double trueAnomaly(double meanAnomaly) {
		double e = eccentricAnomaly(meanAnomaly);
		return 2.0 * Math.atan2(Math.sqrt(1 + ECCENTRICITY) * Math.sin(e / 2), Math.sqrt(1 - ECCENTRICITY) * Math.cos(e / 2));
	}

	static double meanAnomalyOfLs(double lsDeg) {
		double nu = Math.toRadians(lsDeg - LS_PERIHELION);
		double e = 2.0 * Math.atan2(Math.sqrt(1 - ECCENTRICITY) * Math.sin(nu / 2), Math.sqrt(1 + ECCENTRICITY) * Math.cos(nu / 2));
		return e - ECCENTRICITY * Math.sin(e);
	}

	static double normalizeDegrees(double deg) {
		double d = deg % 360.0;
		return d < 0 ? d + 360.0 : d;
	}
}
