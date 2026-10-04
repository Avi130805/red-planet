package io.github.avi130805.redplanet.mars.astro;

/**
 * Positions of the Sun, Phobos, Deimos, Earth (with the Moon) and the fixed stars in the sky of an
 * observer on Mars, computed from simple but physically consistent models:
 *
 * <ul>
 * <li>Sun: hour angle from the Mars clock, declination from the season (obliquity 25.19 degrees), distance
 * from Mars' eccentric orbit.</li>
 * <li>Phobos and Deimos: circular equatorial orbits with real radii and periods, seen topocentrically
 * from the surface (parallax matters: Phobos orbits only 2.76 Mars radii from the centre). This alone
 * reproduces Phobos rising in the west, crossing the sky in about 4 h 15 min, growing larger near the
 * zenith, and never rising above about 69 degrees latitude. Shadowing by Mars (moon eclipses) and phases
 * are included.</li>
 * <li>Earth: a circular 1 AU orbit seen from Mars' Keplerian orbit, so its elongation from the Sun never
 * exceeds about 46 degrees and it appears as an evening or morning star.</li>
 * <li>Stars: a rotation from the Mars equatorial frame to the local horizon (star catalogue vectors are
 * precomputed in Mars equatorial coordinates; Mars' north celestial pole lies in Cygnus).</li>
 * </ul>
 *
 * <p>Output vectors are unit vectors in Minecraft world axes: +x east, +y up, +z south.
 */
public final class MarsAstronomy {
	public static final double OBLIQUITY_DEG = 25.19;
	public static final double MARS_RADIUS_KM = 3389.5;
	/** Sidereal rotation period of Mars, hours. */
	public static final double SIDEREAL_DAY_H = 24.6229;
	public static final double SOL_H = MarsCalendar.SECONDS_PER_SOL / 3600.0;

	public static final double PHOBOS_A_KM = 9376.0;
	public static final double PHOBOS_PERIOD_H = 7.6538;
	public static final double PHOBOS_RADIUS_KM = 11.08;
	public static final double DEIMOS_A_KM = 23463.2;
	public static final double DEIMOS_PERIOD_H = 30.312;
	public static final double DEIMOS_RADIUS_KM = 6.2;

	public static final double EARTH_ORBIT_AU = 1.0;
	public static final double EARTH_YEAR_DAYS = 365.256;
	public static final double MARS_SEMI_MAJOR_AU = 1.52368;
	public static final double AU_KM = 1.495978707e8;
	public static final double MOON_DISTANCE_KM = 384400.0;
	public static final double MOON_PERIOD_DAYS = 27.3217;

	private MarsAstronomy() {
	}

	/** Everything the sky renderer and gameplay need for one instant at one latitude. */
	public record Sky(
		double[] sun, double sunAltitudeDeg, double sunAngularDiameterDeg, double sunDistanceAu,
		double[] phobos, double phobosAngularDiameterDeg, double phobosIllumination, boolean phobosEclipsed,
		double[] deimos, double deimosAngularDiameterDeg, double deimosIllumination, boolean deimosEclipsed,
		double[] earth, double earthElongationDeg, double earthMagnitude,
		double[] moon,
		double[] starRotation, // row-major 3x3: Mars equatorial frame -> world axes
		double solarLongitudeDeg, double solarDeclinationDeg
	) {
	}

	/**
	 * @param clockTicks Mars clock ticks
	 * @param partialTick render partial tick (0..1)
	 * @param latitudeDeg observer latitude, degrees north
	 * @param startLs Ls at clock tick 0
	 * @param yearCompression 1 = real Mars year
	 * @param earthPhaseAtStartDeg heliocentric longitude of Earth minus that of Mars at tick 0
	 * @param moonPhaseSeed initial orbital phases (radians) to decorrelate Phobos and Deimos between worlds
	 */
	public static Sky compute(long clockTicks, double partialTick, double latitudeDeg, double startLs, double yearCompression,
			double earthPhaseAtStartDeg, double moonPhaseSeed) {
		double phi = Math.toRadians(latitudeDeg);
		double sinPhi = Math.sin(phi);
		double cosPhi = Math.cos(phi);
		double eps = Math.toRadians(OBLIQUITY_DEG);

		double ls = MarsCalendar.solarLongitude(clockTicks, startLs, yearCompression);
		double lsRad = Math.toRadians(ls);
		double decl = MarsCalendar.solarDeclination(ls);
		double hourAngle = MarsCalendar.hourAngle(clockTicks, partialTick);

		// Sun in the Mars equatorial frame (x = vernal equinox, z = north pole).
		double[] sunEq = {Math.cos(lsRad), Math.cos(eps) * Math.sin(lsRad), Math.sin(eps) * Math.sin(lsRad)};
		double sunRa = Math.atan2(sunEq[1], sunEq[0]);
		// Local sidereal time follows from the sun: H = LST - RA.
		double lst = hourAngle + sunRa;

		double[] sun = equatorialToWorld(sunEq, lst, sinPhi, cosPhi);
		double sunDist = MarsCalendar.sunDistanceAu(clockTicks, startLs, yearCompression);
		double sunDiameter = Math.toDegrees(2.0 * Math.atan(695700.0 / (sunDist * AU_KM)));

		double hours = (clockTicks + partialTick) / MarsCalendar.TICKS_PER_SOL * SOL_H;

		Moon phobos = moon(hours, PHOBOS_A_KM, PHOBOS_PERIOD_H, PHOBOS_RADIUS_KM, moonPhaseSeed, lst, sinPhi, cosPhi, sunEq);
		Moon deimos = moon(hours, DEIMOS_A_KM, DEIMOS_PERIOD_H, DEIMOS_RADIUS_KM, moonPhaseSeed * 2.39 + 1.1, lst, sinPhi, cosPhi, sunEq);

		// Earth: heliocentric angles measured in Mars' orbital plane from the Mars equinox direction.
		double thetaMars = lsRad + Math.PI; // Mars sits opposite the Sun as seen from Mars
		double days = hours / 24.0;
		double thetaMars0 = Math.toRadians(MarsCalendar.solarLongitude(0, startLs, yearCompression)) + Math.PI;
		double thetaEarth = thetaMars0 + Math.toRadians(earthPhaseAtStartDeg) + 2.0 * Math.PI * days * yearCompression / EARTH_YEAR_DAYS;
		double rMars = sunDist;
		double dx = EARTH_ORBIT_AU * Math.cos(thetaEarth) - rMars * Math.cos(thetaMars);
		double dy = EARTH_ORBIT_AU * Math.sin(thetaEarth) - rMars * Math.sin(thetaMars);
		double earthDistAu = Math.hypot(dx, dy);
		// Orbital-plane vector -> Mars equatorial: plane basis e = x, (n x e) = (0, cos eps, sin eps)
		double[] earthEq = normalize(new double[]{dx, dy * Math.cos(eps), dy * Math.sin(eps)});
		double[] earth = equatorialToWorld(earthEq, lst, sinPhi, cosPhi);
		double elongation = Math.toDegrees(Math.acos(Math.clamp(dot(earthEq, sunEq), -1.0, 1.0)));
		// Phase angle at Earth between the Sun and Mars, for brightness.
		double ex = EARTH_ORBIT_AU * Math.cos(thetaEarth);
		double ey = EARTH_ORBIT_AU * Math.sin(thetaEarth);
		double cosPhase = (-ex * -dx + -ey * -dy) / (EARTH_ORBIT_AU * earthDistAu); // (sun - earth) . (mars - earth)
		double phaseAngle = Math.toDegrees(Math.acos(Math.clamp(cosPhase, -1.0, 1.0)));
		// Earth's absolute magnitude H = -3.99 with a linear phase law (~0.015 mag/deg); seen from Mars it
		// peaks near -2.5 at inferior conjunction-free geometry.
		double earthMag = -3.99 + 5.0 * Math.log10(EARTH_ORBIT_AU * earthDistAu) + 0.015 * phaseAngle;

		// The Moon: offset from Earth by up to atan(384,400 km / distance), circling with a 27.3-day period.
		double moonSep = Math.atan(MOON_DISTANCE_KM / (earthDistAu * AU_KM));
		double moonAngle = 2.0 * Math.PI * days / MOON_PERIOD_DAYS;
		double[] moonEq = offsetDirection(earthEq, moonSep * Math.cos(moonAngle), moonSep * 0.15 * Math.sin(moonAngle));
		double[] moon = equatorialToWorld(moonEq, lst, sinPhi, cosPhi);

		double[] rot = starRotation(lst, sinPhi, cosPhi);

		return new Sky(
			sun, Math.toDegrees(Math.asin(Math.clamp(sun[1], -1.0, 1.0))), sunDiameter, sunDist,
			phobos.dir, phobos.diameterDeg, phobos.illumination, phobos.eclipsed,
			deimos.dir, deimos.diameterDeg, deimos.illumination, deimos.eclipsed,
			earth, elongation, earthMag, moon, rot, ls, Math.toDegrees(decl));
	}

	private record Moon(double[] dir, double diameterDeg, double illumination, boolean eclipsed) {
	}

	private static Moon moon(double hours, double aKm, double periodH, double radiusKm, double phase0, double lst,
			double sinPhi, double cosPhi, double[] sunEq) {
		// Inertial right ascension of the moon (equatorial, circular, prograde).
		double ra = phase0 + 2.0 * Math.PI * hours / periodH;
		double[] posEq = {aKm * Math.cos(ra), aKm * Math.sin(ra), 0.0};
		// Observer position in the equatorial frame: on the meridian of LST.
		double[] obsEq = {MARS_RADIUS_KM * cosPhi * Math.cos(lst), MARS_RADIUS_KM * cosPhi * Math.sin(lst), MARS_RADIUS_KM * sinPhi};
		double[] d = {posEq[0] - obsEq[0], posEq[1] - obsEq[1], posEq[2] - obsEq[2]};
		double dist = Math.sqrt(dot(d, d));
		double[] dirEq = {d[0] / dist, d[1] / dist, d[2] / dist};
		double diameter = Math.toDegrees(2.0 * Math.atan(radiusKm / dist));
		// Illuminated fraction: phase angle at the moon between the Sun and the observer.
		double cosPhase = -dot(sunEq, dirEq); // sun direction vs (observer - moon)
		double illumination = 0.5 * (1.0 + cosPhase);
		// Eclipse: behind Mars relative to the Sun and inside the cylindrical shadow.
		double along = dot(posEq, sunEq);
		boolean eclipsed = false;
		if (along < 0) {
			double[] perp = {posEq[0] - along * sunEq[0], posEq[1] - along * sunEq[1], posEq[2] - along * sunEq[2]};
			eclipsed = dot(perp, perp) < MARS_RADIUS_KM * MARS_RADIUS_KM;
		}
		return new Moon(equatorialToWorld(dirEq, lst, sinPhi, cosPhi), diameter, illumination, eclipsed);
	}

	/**
	 * Rotation taking Mars-equatorial unit vectors to world axes for the given local sidereal time and latitude.
	 * Row-major 3x3.
	 */
	public static double[] starRotation(double lst, double sinPhi, double cosPhi) {
		double[] ex = equatorialToWorld(new double[]{1, 0, 0}, lst, sinPhi, cosPhi);
		double[] ey = equatorialToWorld(new double[]{0, 1, 0}, lst, sinPhi, cosPhi);
		double[] ez = equatorialToWorld(new double[]{0, 0, 1}, lst, sinPhi, cosPhi);
		return new double[]{
			ex[0], ey[0], ez[0],
			ex[1], ey[1], ez[1],
			ex[2], ey[2], ez[2]
		};
	}

	/**
	 * Mars-equatorial unit vector -> world axes (x east, y up, z south).
	 */
	public static double[] equatorialToWorld(double[] v, double lst, double sinPhi, double cosPhi) {
		// Hour-angle frame: rotate by -LST about the pole. x toward the local meridian, y east, z north pole.
		double c = Math.cos(lst);
		double s = Math.sin(lst);
		double hx = c * v[0] + s * v[1];
		double hy = -s * v[0] + c * v[1];
		double hz = v[2];
		double east = hy;
		double north = -sinPhi * hx + cosPhi * hz;
		double up = cosPhi * hx + sinPhi * hz;
		return new double[]{east, up, -north};
	}

	/** Small angular offset of a unit vector along two perpendicular directions. */
	private static double[] offsetDirection(double[] v, double a, double b) {
		double[] helper = Math.abs(v[2]) < 0.9 ? new double[]{0, 0, 1} : new double[]{1, 0, 0};
		double[] u = normalize(cross(v, helper));
		double[] w = cross(v, u);
		return normalize(new double[]{
			v[0] + a * u[0] + b * w[0],
			v[1] + a * u[1] + b * w[1],
			v[2] + a * u[2] + b * w[2]});
	}

	static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	static double[] cross(double[] a, double[] b) {
		return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	static double[] normalize(double[] v) {
		double l = Math.sqrt(dot(v, v));
		return new double[]{v[0] / l, v[1] / l, v[2] / l};
	}
}
