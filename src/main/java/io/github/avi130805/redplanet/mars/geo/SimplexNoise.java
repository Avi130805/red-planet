package io.github.avi130805.redplanet.mars.geo;

/**
 * Seeded 2D simplex noise (after Stefan Gustavson's public-domain reference implementation), plus
 * fractal Brownian motion. Output of {@link #noise} is in roughly [-1, 1].
 */
public final class SimplexNoise {
	private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
	private static final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;
	private static final int[][] GRAD = {
		{1, 1}, {-1, 1}, {1, -1}, {-1, -1}, {1, 0}, {-1, 0}, {0, 1}, {0, -1},
		{1, 1}, {-1, 1}, {1, -1}, {-1, -1}
	};

	private final short[] perm = new short[512];

	public SimplexNoise(long seed) {
		short[] p = new short[256];
		for (short i = 0; i < 256; i++) {
			p[i] = i;
		}
		long s = seed ^ 0x5DEECE66DL;
		for (int i = 255; i > 0; i--) {
			s = s * 6364136223846793005L + 1442695040888963407L;
			int j = (int) ((s >>> 33) % (i + 1));
			short t = p[i];
			p[i] = p[j];
			p[j] = t;
		}
		for (int i = 0; i < 512; i++) {
			this.perm[i] = p[i & 255];
		}
	}

	public double noise(double x, double y) {
		double s = (x + y) * F2;
		int i = fastFloor(x + s);
		int j = fastFloor(y + s);
		double t = (i + j) * G2;
		double x0 = x - (i - t);
		double y0 = y - (j - t);
		int i1 = x0 > y0 ? 1 : 0;
		int j1 = 1 - i1;
		double x1 = x0 - i1 + G2;
		double y1 = y0 - j1 + G2;
		double x2 = x0 - 1.0 + 2.0 * G2;
		double y2 = y0 - 1.0 + 2.0 * G2;
		int ii = i & 255;
		int jj = j & 255;
		double n = 0;
		double t0 = 0.5 - x0 * x0 - y0 * y0;
		if (t0 > 0) {
			int[] g = GRAD[this.perm[ii + this.perm[jj]] % 12];
			t0 *= t0;
			n += t0 * t0 * (g[0] * x0 + g[1] * y0);
		}
		double t1 = 0.5 - x1 * x1 - y1 * y1;
		if (t1 > 0) {
			int[] g = GRAD[this.perm[ii + i1 + this.perm[jj + j1]] % 12];
			t1 *= t1;
			n += t1 * t1 * (g[0] * x1 + g[1] * y1);
		}
		double t2 = 0.5 - x2 * x2 - y2 * y2;
		if (t2 > 0) {
			int[] g = GRAD[this.perm[ii + 1 + this.perm[jj + 1]] % 12];
			t2 *= t2;
			n += t2 * t2 * (g[0] * x2 + g[1] * y2);
		}
		return 70.0 * n;
	}

	/**
	 * Fractal sum of {@code octaves} layers starting at wavelength {@code wavelength}, each half the
	 * wavelength of the previous, with amplitude scaling by 2^-hurst per octave (self-affine terrain).
	 * Normalized so its standard deviation is about 1 regardless of octave count.
	 */
	public double fbm(double x, double y, double wavelength, int octaves, double hurst) {
		double sum = 0;
		double amp = 1;
		double norm = 0;
		double f = 1.0 / wavelength;
		double gain = Math.pow(2.0, -hurst);
		for (int o = 0; o < octaves; o++) {
			// Offset each octave so lattice artifacts do not line up.
			sum += amp * noise(x * f + o * 17.31, y * f - o * 29.73);
			norm += amp * amp;
			amp *= gain;
			f *= 2.0;
		}
		return sum / Math.sqrt(norm) * 2.6; // single simplex octave has std ~0.38
	}

	private static int fastFloor(double v) {
		int i = (int) v;
		return v < i ? i - 1 : i;
	}
}
