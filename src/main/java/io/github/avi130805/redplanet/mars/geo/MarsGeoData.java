package io.github.avi130805.redplanet.mars.geo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The immutable Mars datasets shipped in the jar, loaded once per JVM and shared by every world.
 * About 30 MB on the heap once decoded (mostly the 8 ppd topography as shorts).
 */
public final class MarsGeoData {
	private static volatile MarsGeoData instance;

	public final GeoGrid topography;
	public final GeoGrid albedo;
	public final GeoGrid roughness;
	public final CraterCatalog craters;
	/** 1 pixel-per-degree box-filtered topography, for regional context (canyons, basins). */
	public final float[] regional;

	private MarsGeoData(GeoGrid topography, GeoGrid albedo, GeoGrid roughness, CraterCatalog craters) {
		this.topography = topography;
		this.albedo = albedo;
		this.roughness = roughness;
		this.craters = craters;
		this.regional = boxFilterToOneDegree(topography);
	}

	public static MarsGeoData get() {
		MarsGeoData d = instance;
		if (d == null) {
			synchronized (MarsGeoData.class) {
				d = instance;
				if (d == null) {
					d = load();
					instance = d;
				}
			}
		}
		return d;
	}

	private static MarsGeoData load() {
		try {
			GeoGrid topography = grid("topography.rpgrid");
			return new MarsGeoData(topography, grid("albedo.rpgrid"), grid("roughness.rpgrid"), craterCatalog(topography));
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to load the Mars datasets from the mod jar", e);
		}
	}

	private static GeoGrid grid(String name) throws IOException {
		try (InputStream in = open(name)) {
			return GeoGrid.read(in);
		}
	}

	private static CraterCatalog craterCatalog(GeoGrid topography) throws IOException {
		try (InputStream in = open("craters.bin")) {
			return CraterCatalog.read(in, topography);
		}
	}

	private static InputStream open(String name) throws IOException {
		InputStream in = MarsGeoData.class.getResourceAsStream("/redplanet/mars/" + name);
		if (in == null) {
			throw new IOException("Missing resource /redplanet/mars/" + name);
		}
		return in;
	}

	private static float[] boxFilterToOneDegree(GeoGrid g) {
		int ppd = (int) Math.round(g.pixelsPerDegree());
		float[] out = new float[360 * 180];
		for (int r = 0; r < 180; r++) {
			for (int c = 0; c < 360; c++) {
				double sum = 0;
				for (int dr = 0; dr < ppd; dr++) {
					for (int dc = 0; dc < ppd; dc++) {
						sum += g.cell(c * ppd + dc, r * ppd + dr);
					}
				}
				out[r * 360 + c] = (float) (sum / (ppd * ppd));
			}
		}
		// A second, wider pass (3x3 degrees) so canyon floors stand out against their surroundings.
		float[] wide = new float[out.length];
		for (int r = 0; r < 180; r++) {
			for (int c = 0; c < 360; c++) {
				double sum = 0;
				int n = 0;
				for (int dr = -1; dr <= 1; dr++) {
					int rr = Math.clamp(r + dr, 0, 179);
					for (int dc = -1; dc <= 1; dc++) {
						sum += out[rr * 360 + Math.floorMod(c + dc, 360)];
						n++;
					}
				}
				wide[r * 360 + c] = (float) (sum / n);
			}
		}
		return wide;
	}

	/** Bilinear sample of the regional (3-degree-smoothed) elevation in metres. */
	public double regionalElevation(double latDeg, double lonDeg) {
		double fx = lonDeg - 0.5;
		double fy = 90.0 - latDeg - 0.5;
		int x0 = (int) Math.floor(fx);
		int y0 = (int) Math.floor(fy);
		double tx = fx - x0;
		double ty = fy - y0;
		double a = reg(x0, y0) + (reg(x0 + 1, y0) - reg(x0, y0)) * tx;
		double b = reg(x0, y0 + 1) + (reg(x0 + 1, y0 + 1) - reg(x0, y0 + 1)) * tx;
		return a + (b - a) * ty;
	}

	private float reg(int c, int r) {
		return this.regional[Math.clamp(r, 0, 179) * 360 + Math.floorMod(c, 360)];
	}
}
