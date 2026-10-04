package io.github.avi130805.redplanet.mars.geo;

/**
 * The Mars surface height field and the per-column facts that biomes and surface blocks depend on.
 *
 * <p>Height = MOLA elevation (bicubic) + real catalogue craters + fractal detail whose amplitude follows
 * the measured MOLA roughness + human-scale dunes on dark crater floors and in the north polar erg +
 * stepped polar layered deposits. Thread-safe: instances are immutable apart from per-call scratch
 * objects passed in by the caller.
 */
public final class MarsTerrain {
	/** Hurst exponent of the fractal detail; MOLA roughness studies find ~0.7-0.8 at km scales. */
	private static final double HURST = 0.75;

	private final MarsGeoData data;
	private final SimplexNoise detail;
	private final SimplexNoise duneNoise;
	/** Width (blocks) of the cross-fade where the map wraps around the planet. */
	static final double SEAM_BAND = 256.0;
	private final SimplexNoise warp;

	public MarsTerrain(long seed) {
		this.data = MarsGeoData.get();
		this.detail = new SimplexNoise(seed * 31 + 7);
		this.duneNoise = new SimplexNoise(seed * 31 + 11);
		this.warp = new SimplexNoise(seed * 31 + 13);
	}

	public MarsGeoData data() {
		return this.data;
	}

	/** Everything known about one column. Reuse instances to avoid allocation in hot paths. */
	public static final class Column {
		public double lat;
		public double lon;
		/** MOLA elevation, metres. */
		public double baseElevation;
		/** Elevation smoothed over ~3 degrees, metres. */
		public double regionalElevation;
		/** Sub-km RMS relief from MOLA, metres. */
		public double roughness;
		/** TES bolometric albedo, 0.06..0.32. */
		public double albedo;
		/** Final surface height in fractional block Y. */
		public double surfaceY;
		/** Surface elevation including craters and detail, metres. */
		public double elevation;
		public double craterInterior;
		public double craterRim;
		public boolean layeredEjecta;
		/** 0..1 strength of dune cover. */
		public double dunes;
		/** 0..1 polar cap ice cover. */
		public double polarCap;
		final CraterCatalog.Sample crater = new CraterCatalog.Sample();
	}

	public Column sample(double x, double z) {
		Column c = new Column();
		sample(x, z, c);
		return c;
	}

	public double surfaceY(double x, double z) {
		Column c = new Column();
		sample(x, z, c);
		return c.surfaceY;
	}

	public void sample(double x, double z, Column out) {
		double lat = MarsProjection.latitude(z);
		double lon = MarsProjection.longitude(x);
		out.lat = lat;
		out.lon = lon;
		out.baseElevation = this.data.topography.sample(lat, lon);
		out.regionalElevation = this.data.regionalElevation(lat, lon);
		out.roughness = this.data.roughness.sampleBilinear(lat, lon);
		out.albedo = this.data.albedo.sampleBilinear(lat, lon);

		this.data.craters.sample(x, z, out.baseElevation, out.crater);
		out.craterInterior = out.crater.interior;
		out.craterRim = out.crater.rim;
		out.layeredEjecta = out.crater.layeredEjecta;

		double y = MarsProjection.yOfElevation(out.crater.elevation);

		// Polar caps: bright, high-latitude terrain is ice. The layered deposits are exposed as stepped
		// terraces on trough walls, so the smooth (pre-detail) surface is terraced, and the ice itself stays smooth.
		double absLat = Math.abs(lat);
		double cap = smoothstep(78.0, 81.0, absLat) * smoothstep(0.20, 0.25, out.albedo);
		out.polarCap = cap;
		if (cap > 0) {
			double step = 2.0;
			double fl = Math.floor(y / step);
			double fr = y / step - fl;
			double stepped = (fl + smoothstep(0.7, 1.0, fr)) * step;
			y += (stepped - y) * 0.8 * cap;
		}

		// Fractal detail below the data's resolution only (wavelengths 16 -> 2 blocks; the 8 ppd grid resolves
		// ~15 km and up). The MOLA roughness is measured at ~10 km; self-affine scaling (sigma ~ L^H) carries it
		// down to these shorter wavelengths, about half the amplitude. A floor of 0.2 block keeps even the
		// smoothest northern plains from looking machined.
		double amp = Math.clamp(0.5 * out.roughness / MarsProjection.METRES_PER_BLOCK_VERTICAL, 0.2, 3.0);
		amp *= 1.0 - 0.85 * cap; // wind-polished ice
		// The map repeats every circumference in x, but noise doesn't: evaluate it on the wrapped x and cross-fade
		// into the next lap over the last SEAM_BAND blocks, so walking around the planet has no seam.
		double u = x - MarsProjection.CIRCUMFERENCE_BLOCKS * Math.floor(x / MarsProjection.CIRCUMFERENCE_BLOCKS);
		double seam = smoothstep(MarsProjection.CIRCUMFERENCE_BLOCKS - SEAM_BAND, MarsProjection.CIRCUMFERENCE_BLOCKS, u);
		double detailNoise = detailNoise(u, z);
		if (seam > 0.0) {
			detailNoise = crossFade(seam, detailNoise, detailNoise(u - MarsProjection.CIRCUMFERENCE_BLOCKS, z));
		}
		y += amp * 0.8 * detailNoise;


		// Dunes: dark sand collects on crater floors and in the north polar erg (Olympia Undae, ~78-84 N).
		double dark = 1.0 - smoothstep(0.11, 0.155, out.albedo);
		double erg = lat > 0 ? smoothstep(76.0, 78.5, lat) * (1.0 - smoothstep(83.0, 84.5, lat)) * lonWindow(lon, 115.0, 245.0, 12.0) : 0.0;
		double lowland = 1.0 - smoothstep(-3500.0, -2500.0, out.regionalElevation);
		double duneStrength = Math.max(erg, dark * Math.max(smoothstep(0.25, 0.6, out.craterInterior), 0.35 * lowland));
		duneStrength *= 1.0 - cap * 0.8;
		out.dunes = duneStrength;
		if (duneStrength > 0.02) {
			double dune = duneHeight(u, z);
			if (seam > 0.0) {
				dune = (1.0 - seam) * dune + seam * duneHeight(u - MarsProjection.CIRCUMFERENCE_BLOCKS, z);
			}
			y += duneStrength * dune;
		}

		out.surfaceY = y;
		out.elevation = MarsProjection.elevationOfY(y);
	}

	/** Warped fractal detail at a (wrapped) position: zero mean, standard deviation about 1. */
	private double detailNoise(double x, double z) {
		double wx = x + 3.0 * this.warp.noise(x / 60.0, z / 60.0);
		double wz = z + 3.0 * this.warp.noise(z / 60.0 + 40.0, x / 60.0 - 40.0);
		return this.detail.fbm(wx, wz, 16.0, 4, HURST);
	}

	/** Blends two independent noise values, keeping the variance constant across the blend. */
	private static double crossFade(double t, double a, double b) {
		return ((1.0 - t) * a + t * b) / Math.sqrt((1.0 - t) * (1.0 - t) + t * t);
	}

	/**
	 * Transverse dunes with a gentle stoss slope and a steep lee face, broken into barchan-like segments.
	 * Drawn at human scale (about 12 blocks between crests, up to ~2 blocks high) for readability.
	 */
	private double duneHeight(double x, double z) {
		double angle = 0.6 + 0.8 * this.duneNoise.noise(x / 900.0, z / 900.0);
		double along = x * Math.cos(angle) + z * Math.sin(angle);
		double phase = along / 12.0 + 1.6 * this.warp.noise(x / 60.0, z / 60.0);
		double p = phase - Math.floor(phase);
		double tri = p < 0.72 ? p / 0.72 : (1.0 - p) / 0.28;
		double shaped = tri * tri * (3.0 - 2.0 * tri);
		double segment = Math.max(0.0, 0.55 + 0.6 * this.duneNoise.noise(x / 40.0, z / 40.0));
		return 2.1 * shaped * Math.min(1.0, segment);
	}

	private static double lonWindow(double lon, double from, double to, double feather) {
		return smoothstep(from - feather, from + feather, lon) * (1.0 - smoothstep(to - feather, to + feather, lon));
	}

	static double smoothstep(double a, double b, double x) {
		double t = Math.clamp((x - a) / (b - a), 0.0, 1.0);
		return t * t * (3.0 - 2.0 * t);
	}
}
