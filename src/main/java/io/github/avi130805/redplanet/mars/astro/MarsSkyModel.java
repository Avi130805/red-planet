package io.github.avi130805.redplanet.mars.astro;

/**
 * How the Martian sky looks for a given Sun altitude and dust optical depth: a small, documented model that drives
 * the client's sky colour, fog, star visibility, ambient sky light, the blue aureole and the Sun's dimming
 * (docs/SCIENCE.md sections 10-11).
 *
 * <ul>
 * <li>Day: butterscotch zenith and a brighter, dustier horizon (rover true-colour images).</li>
 * <li>Twilight: long, because dust high in the atmosphere stays sunlit (Pathfinder); a faint glow lingers until
 * the Sun is ~20 degrees down.</li>
 * <li>Blue aureole: fine dust forward-scatters blue light into a halo around the Sun, strongest near the horizon
 * (PIA19400).</li>
 * <li>Direct sunlight follows Beer-Lambert, exp(-tau / mu); a perceptual curve keeps the disc readable.</li>
 * <li>Dust storms darken and brown the sky and shorten the view (fog end ~ 48 + 900 / tau^2 blocks).</li>
 * </ul>
 */
public final class MarsSkyModel {
	public static final float[] SKY_DAY = rgb(0xB98A63);
	public static final float[] FOG_DAY = rgb(0xCFA27A);
	public static final float[] SKY_STORM = rgb(0x8A5C3A);
	public static final float[] FOG_STORM = rgb(0x9E6E48);
	public static final float[] NIGHT = {0.012F, 0.011F, 0.016F};
	/**
	 * The whole-sky twilight glow: a muted grey (dust high up stays sunlit). The famous blue is local to the Sun and
	 * comes from the aureole, not from this term.
	 */
	public static final float[] TWILIGHT = {0.21F, 0.20F, 0.23F};

	/** Everything the client sky needs. Colours are sRGB 0..1. */
	public record Look(float[] sky, float[] fog, float starBrightness, float skyLightFactor, float aureole, float aureoleBlue,
			float sunVisibility, float fogEnd) {
	}

	private MarsSkyModel() {
	}

	public static Look compute(double sunAltDeg, double tau) {
		double day = smooth(-6.0, 8.0, sunAltDeg);
		double twilight = smooth(-20.0, -4.0, sunAltDeg) * (1.0 - day);
		double storm = smooth(1.0, 6.0, tau);
		// Diffuse light lost to thick dust. Martian dust mostly scatters (single-scattering albedo ~0.9-0.97), so even at
		// tau ~8 a tenth or so of the light reaches the ground: ~0.4 in sRGB, a dark brown noon rather than night.
		double dim = Math.exp(-0.12 * Math.max(0.0, tau - 0.9));

		float[] skyDay = mix(SKY_DAY, SKY_STORM, storm);
		float[] fogDay = mix(FOG_DAY, FOG_STORM, storm);
		float[] sky = new float[3];
		float[] fog = new float[3];
		for (int i = 0; i < 3; i++) {
			sky[i] = (float) (NIGHT[i] + (skyDay[i] * dim - NIGHT[i]) * day + TWILIGHT[i] * 0.55 * twilight * dim);
			fog[i] = (float) (NIGHT[i] + (fogDay[i] * dim - NIGHT[i]) * day + TWILIGHT[i] * 0.65 * twilight * dim);
		}
		// The first stars come out a few degrees after sunset; the full field once the Sun is ~20 degrees down.
		double stars = 0.85 * (1.0 - smooth(-20.0, -3.0, sunAltDeg)) * Math.exp(-0.35 * Math.max(0.0, tau - 0.5));
		double skyLight = Math.max(0.06, (0.12 + 0.88 * day) * dim);
		double aureole = Math.min(1.0, day + twilight) * Math.exp(-0.25 * Math.max(0.0, tau - 0.9));
		double blue = 0.35 + 0.65 * (1.0 - smooth(5.0, 35.0, sunAltDeg));
		double mu = Math.max(Math.sin(Math.toRadians(sunAltDeg)), 0.03);
		double direct = Math.exp(-tau / mu);
		double sunVisibility = sunAltDeg < -1.0 ? 0.0 : Math.pow(direct, 0.3) * smooth(-1.0, 0.5, sunAltDeg);
		double fogEnd = 48.0 + 900.0 / (tau * tau);
		return new Look(sky, fog, (float) Math.max(0.0, stars), (float) skyLight, (float) aureole, (float) blue,
			(float) sunVisibility, (float) fogEnd);
	}

	static double smooth(double edge0, double edge1, double x) {
		double t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
		return t * t * (3.0 - 2.0 * t);
	}

	private static float[] mix(float[] a, float[] b, double t) {
		return new float[]{(float) (a[0] + (b[0] - a[0]) * t), (float) (a[1] + (b[1] - a[1]) * t), (float) (a[2] + (b[2] - a[2]) * t)};
	}

	private static float[] rgb(int hex) {
		return new float[]{((hex >> 16) & 0xFF) / 255.0F, ((hex >> 8) & 0xFF) / 255.0F, (hex & 0xFF) / 255.0F};
	}
}
