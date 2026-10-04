package io.github.avi130805.redplanet.mars.astro;

/**
 * A compact physical model of Mars surface temperature, used for the suit HUD, frost and ice sublimation.
 *
 * <ul>
 * <li><b>Daily mean:</b> radiative balance of the daily-mean insolation Q (latitude, solar declination, Sun
 * distance): T = (Q (1 - A) / (eps sigma))^(1/4) with albedo A = 0.25 and emissivity 0.95; the thin CO2
 * atmosphere adds little greenhouse warming (~5 K). Averaged over the planet and the year this gives 214.5 K,
 * against NSSDCA's ~214 K.</li>
 * <li><b>Diurnal cycle:</b> the low thermal inertia of regolith gives large swings (Gale crater: about -90 C at
 * night to +10 C in the afternoon at the ground). Modelled as a skewed cosine peaking at 13:30 LMST whose
 * amplitude scales with the noon insolation and shrinks with dust optical depth.</li>
 * <li><b>CO2 frost floor:</b> the surface cannot cool below the CO2 frost point at the local pressure, because
 * the atmosphere condenses onto it (polar night: ~148 K).</li>
 * </ul>
 * Calibration and sources: docs/SCIENCE.md, "Temperature".
 */
public final class MarsClimate {
	public static final double SOLAR_CONSTANT_1AU = 1361.0;
	private static final double SIGMA = 5.670374419e-8;
	/** Mean bolometric albedo of Mars' ground (TES global mix of bright dust and dark sand). */
	public static final double ALBEDO = 0.25;
	private static final double EMISSIVITY = 0.95;
	private static final double GREENHOUSE_K = 5.0;

	private MarsClimate() {
	}

	/** Top-of-atmosphere irradiance (W/m2) at a Sun distance. */
	public static double irradiance(double sunDistanceAu) {
		return SOLAR_CONSTANT_1AU / (sunDistanceAu * sunDistanceAu);
	}

	/**
	 * Daily-mean insolation (W/m2) on a horizontal surface at the top of the atmosphere.
	 */
	public static double dailyMeanInsolation(double latDeg, double declinationRad, double sunDistanceAu) {
		double phi = Math.toRadians(latDeg);
		double s0 = irradiance(sunDistanceAu);
		double x = -Math.tan(phi) * Math.tan(declinationRad);
		double h0;
		if (x >= 1.0) {
			return 0.0; // polar night
		} else if (x <= -1.0) {
			h0 = Math.PI; // polar day
		} else {
			h0 = Math.acos(x);
		}
		return s0 / Math.PI * (h0 * Math.sin(phi) * Math.sin(declinationRad) + Math.cos(phi) * Math.cos(declinationRad) * Math.sin(h0));
	}

	/** Noon insolation (W/m2) on a horizontal surface. */
	public static double noonInsolation(double latDeg, double declinationRad, double sunDistanceAu) {
		double zenithCos = Math.cos(Math.toRadians(latDeg) - declinationRad);
		return Math.max(0.0, irradiance(sunDistanceAu) * zenithCos);
	}

	/**
	 * CO2 frost point (K) at a pressure (Pa): Fanale et al. (1982), T = -3167.8 / (ln(0.01 p) - 23.23), as used by
	 * Forget et al. (2013). 147.9 K at 610 Pa.
	 */
	public static double co2FrostPoint(double pressurePa) {
		return 3167.8 / (27.835 - Math.log(Math.max(1.0, pressurePa)));
	}

	/**
	 * Surface (ground) temperature in kelvin.
	 *
	 * @param latDeg latitude
	 * @param lsDeg solar longitude
	 * @param lmstFraction local mean solar time as a fraction of a sol (0 = midnight)
	 * @param sunDistanceAu Sun distance
	 * @param pressurePa local pressure, for the frost floor
	 * @param dustTau atmospheric dust optical depth (~0.5 clear, 2-10 in storms)
	 */
	public static double surfaceTemperature(double latDeg, double lsDeg, double lmstFraction, double sunDistanceAu,
			double pressurePa, double dustTau) {
		return surfaceTemperature(latDeg, lsDeg, lmstFraction, sunDistanceAu, pressurePa, dustTau, ALBEDO);
	}

	/**
	 * Surface temperature for ground of a given bolometric albedo (TES: 0.08 dark basalt to 0.33 bright dust;
	 * ice-covered ground ~0.45).
	 */
	public static double surfaceTemperature(double latDeg, double lsDeg, double lmstFraction, double sunDistanceAu,
			double pressurePa, double dustTau, double albedo) {
		double decl = MarsCalendar.solarDeclination(lsDeg);
		double q = dailyMeanInsolation(latDeg, decl, sunDistanceAu);
		// Dust absorbs and re-radiates: it lowers daytime peaks but barely changes the daily mean.
		double tMean = Math.pow(Math.max(q, 0.0) * (1.0 - albedo) / (EMISSIVITY * SIGMA), 0.25) + GREENHOUSE_K;
		double qNoon = noonInsolation(latDeg, decl, sunDistanceAu);
		double dustDamping = 1.0 / (1.0 + 0.45 * Math.max(0.0, dustTau - 0.3));
		// Calibrated against Curiosity REMS at Gale (diurnal range ~80-95 K) while keeping the hottest ground on the
		// planet within NASA's +20 to +27 C (docs/SCIENCE.md, section 8).
		double amplitude = 46.0 * Math.min(1.15, Math.sqrt(qNoon / 590.0)) * dustDamping;
		// Skewed day: fast morning warming, peak near 13:30, long cooling through the night.
		double phase = lmstFraction - 13.5 / 24.0;
		double wave = Math.cos(2.0 * Math.PI * phase) + 0.25 * Math.cos(4.0 * Math.PI * phase - 0.6);
		double t = tMean + amplitude * wave / 1.18;
		return Math.max(t, co2FrostPoint(pressurePa));
	}

	/**
	 * Seasonal pressure factor relative to the annual mean: CO2 condenses onto the winter polar cap and sublimates
	 * in spring, so the whole atmosphere breathes by about +-13 %. Two-harmonic fit,
	 * 1 + 0.0631 cos(Ls - 286.1) + 0.0884 cos(2 (Ls - 67.9)), with extrema at Ls 60.5 (1.041), 150.5 (0.869),
	 * 253.5 (1.140) and 347.0 (0.947). Those match Curiosity REMS (Ls 57.5, 152.8, 255.1, 343.4) and Viking
	 * Lander 1's 6.9-9.0 mbar around its 7.9 mbar mean (docs/SCIENCE.md, section 3).
	 */
	public static double seasonalPressureFactor(double lsDeg) {
		double ls = Math.toRadians(lsDeg);
		return 1.0 + 0.0631 * Math.cos(ls - Math.toRadians(286.1)) + 0.0884 * Math.cos(2.0 * (ls - Math.toRadians(67.9)));
	}

	public static double kelvinToCelsius(double k) {
		return k - 273.15;
	}
}
