package io.github.avi130805.redplanet.mars.geo;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.InflaterInputStream;

/**
 * Real Mars impact craters (Robbins & Hynek 2012, diameter >= 3 km) placed at their true positions in the
 * Mercator block map, with a spatial index for per-column queries.
 *
 * <p>Large craters (D >= ~25 km) are already resolved by the MOLA grid, so their procedural profile fades
 * out; smaller craters are drawn entirely from the catalogue, using measured rim-to-floor depth and rim
 * height where available and Mars depth-diameter relations otherwise.
 */
public final class CraterCatalog {
	public static final int FLAG_CENTRAL_PEAK = 1;
	public static final int FLAG_CENTRAL_PIT = 2;
	public static final int FLAG_LAYERED_EJECTA = 4;
	public static final int FLAG_MULTI_LAYER_EJECTA = 8;
	public static final int FLAG_TERRACED = 16;
	public static final int FLAG_FLAT_FLOOR = 32;

	/** Below this diameter craters are added on top of the terrain (MOLA barely sees them). */
	public static final double ADDITIVE_MAX_KM = 15.0;
	/**
	 * Between {@link #ADDITIVE_MAX_KM} and {@link #REPLACE_END_KM} the crater interior replaces the smoothed
	 * MOLA surface with a crisp profile anchored to the surrounding plains (MOLA at 8 ppd smears a 45 km crater
	 * such as Jezero over ~6 pixels); the replacement fades out from {@link #REPLACE_FADE_KM}, where MOLA itself
	 * resolves the crater well (Gale, at 154 km, keeps its MOLA shape and its central mound).
	 */
	public static final double REPLACE_FADE_KM = 45.0;
	public static final double REPLACE_END_KM = 60.0;
	/** Influence radius in crater radii (rim + ejecta + rampart). */
	private static final double INFLUENCE = 2.6;
	private static final int CELL = 64;

	private final int count;
	private final float[] lat;
	private final float[] lon;
	private final float[] diameterKm;
	private final float[] depthM;
	private final float[] rimM;
	private final byte[] flags;
	private final byte[] degradation;
	private final float[] cx;
	private final float[] cz;
	/** Elevation (m) of the plains around each replaced crater, from MOLA at 1.5 radii. */
	private final float[] refElevation;

	private final int cellsX;
	private final int cellsZ;
	private final int[] cellStart;
	private final int[] cellItems;

	private CraterCatalog(int count, float[] lat, float[] lon, float[] diameterKm, float[] depthM, float[] rimM, byte[] flags, byte[] degradation,
			GeoGrid topography) {
		this.count = count;
		this.lat = lat;
		this.lon = lon;
		this.diameterKm = diameterKm;
		this.depthM = depthM;
		this.rimM = rimM;
		this.flags = flags;
		this.degradation = degradation;
		this.cx = new float[count];
		this.cz = new float[count];
		this.refElevation = new float[count];
		for (int i = 0; i < count; i++) {
			this.cx[i] = (float) MarsProjection.xOf(lon[i]);
			this.cz[i] = (float) MarsProjection.zOf(lat[i]);
			if (diameterKm[i] >= ADDITIVE_MAX_KM && diameterKm[i] < REPLACE_END_KM && topography != null) {
				// Mean MOLA elevation on a ring at 1.5 crater radii: the level of the surrounding plains.
				double ringDeg = Math.toDegrees(0.75 * diameterKm[i] / MarsProjection.RADIUS_KM);
				double sum = 0;
				for (int k = 0; k < 16; k++) {
					double a = 2 * Math.PI * k / 16;
					double dLat = ringDeg * Math.cos(a);
					double dLon = ringDeg * Math.sin(a) / Math.max(0.05, Math.cos(Math.toRadians(lat[i])));
					sum += topography.sample(lat[i] + dLat, lon[i] + dLon);
				}
				this.refElevation[i] = (float) (sum / 16);
			}
		}

		// Spatial hash over one copy of the map: x in [0, C), z in [-M, M].
		this.cellsX = (int) Math.ceil(MarsProjection.CIRCUMFERENCE_BLOCKS / CELL);
		this.cellsZ = (int) Math.ceil(2.0 * MarsProjection.MIRROR_Z / CELL) + 1;
		int[] counts = new int[this.cellsX * this.cellsZ + 1];
		forEachCell((cell, i) -> counts[cell]++);
		this.cellStart = new int[counts.length];
		int sum = 0;
		for (int c = 0; c < counts.length; c++) {
			this.cellStart[c] = sum;
			sum += counts[c];
		}
		this.cellItems = new int[sum];
		int[] fill = Arrays.copyOf(this.cellStart, this.cellStart.length);
		forEachCell((cell, i) -> this.cellItems[fill[cell]++] = i);
		// Within a cell, apply big craters first so smaller (usually younger) ones overprint them.
		for (int c = 0; c + 1 < this.cellStart.length; c++) {
			int from = this.cellStart[c];
			int to = this.cellStart[c + 1];
			if (to - from > 1) {
				Integer[] boxed = new Integer[to - from];
				for (int k = from; k < to; k++) {
					boxed[k - from] = this.cellItems[k];
				}
				Arrays.sort(boxed, (a, b) -> Float.compare(diameterKm[b], diameterKm[a]));
				for (int k = from; k < to; k++) {
					this.cellItems[k] = boxed[k - from];
				}
			}
		}
	}

	private interface CellVisitor {
		void visit(int cell, int crater);
	}

	private void forEachCell(CellVisitor v) {
		for (int i = 0; i < this.count; i++) {
			if (this.diameterKm[i] >= REPLACE_END_KM || Math.abs(this.lat[i]) > MarsProjection.MIRROR_LATITUDE) {
				continue;
			}
			double rBlocks = radiusBlocks(i) * INFLUENCE;
			int z0 = cellZ(this.cz[i] - rBlocks);
			int z1 = cellZ(this.cz[i] + rBlocks);
			int x0 = (int) Math.floor((this.cx[i] - rBlocks) / CELL);
			int x1 = (int) Math.floor((this.cx[i] + rBlocks) / CELL);
			for (int cz2 = z0; cz2 <= z1; cz2++) {
				for (int cx2 = x0; cx2 <= x1; cx2++) {
					int wx = Math.floorMod(cx2, this.cellsX);
					v.visit(cz2 * this.cellsX + wx, i);
				}
			}
		}
	}

	private int cellZ(double z) {
		return Math.clamp((int) Math.floor((z + MarsProjection.MIRROR_Z) / CELL), 0, this.cellsZ - 1);
	}

	public static CraterCatalog read(InputStream in, GeoGrid topography) throws IOException {
		DataInputStream header = new DataInputStream(in);
		if (header.readInt() != 0x52504352) { // "RPCR"
			throw new IOException("Not a crater catalogue");
		}
		int n = header.readInt();
		DataInputStream body = new DataInputStream(new InflaterInputStream(in));
		float[] lat = new float[n];
		float[] lon = new float[n];
		float[] d = new float[n];
		float[] depth = new float[n];
		float[] rim = new float[n];
		byte[] flags = new byte[n];
		byte[] deg = new byte[n];
		for (int i = 0; i < n; i++) {
			lat[i] = body.readFloat();
			lon[i] = body.readFloat();
			d[i] = body.readUnsignedShort() / 100.0f;
			depth[i] = body.readUnsignedShort();
			rim[i] = body.readUnsignedShort();
			flags[i] = body.readByte();
			deg[i] = body.readByte();
		}
		return new CraterCatalog(n, lat, lon, d, depth, rim, flags, deg, topography);
	}

	public int size() {
		return this.count;
	}

	/** Crater radius in blocks at its own latitude (Mercator scale). */
	double radiusBlocks(int i) {
		return 0.5 * this.diameterKm[i] / MarsProjection.kmPerBlock(this.lat[i]);
	}

	/** Result of a column query. */
	public static final class Sample {
		/** Elevation after crater relief, metres. */
		public double elevation;
		/** 0..1: how deep inside a crater bowl the column is (1 = floor centre); 0 outside. */
		public double interior;
		/** 0..1: proximity to a fresh crater rim (for rocky rim/ejecta surface blocks). */
		public double rim;
		/** True if the column lies on the ejecta blanket of a layered-ejecta ("rampart") crater. */
		public boolean layeredEjecta;

		void reset(double base) {
			this.elevation = base;
			this.interior = 0;
			this.rim = 0;
			this.layeredEjecta = false;
		}
	}

	/**
	 * Applies crater relief at block (x, z) to the MOLA elevation {@code baseElevation} (metres).
	 */
	public void sample(double x, double z, double baseElevation, Sample out) {
		out.reset(baseElevation);
		double zf = MarsProjection.foldZ(z);
		double xw = x % MarsProjection.CIRCUMFERENCE_BLOCKS;
		if (xw < 0) {
			xw += MarsProjection.CIRCUMFERENCE_BLOCKS;
		}
		int cxi = Math.floorMod((int) Math.floor(xw / CELL), this.cellsX);
		int czi = cellZ(zf);
		int cell = czi * this.cellsX + cxi;
		int start = this.cellStart[cell];
		int end = this.cellStart[cell + 1];
		for (int k = start; k < end; k++) {
			int i = this.cellItems[k];
			double dx = xw - this.cx[i];
			// wrap the shorter way around the planet
			if (dx > MarsProjection.CIRCUMFERENCE_BLOCKS / 2) {
				dx -= MarsProjection.CIRCUMFERENCE_BLOCKS;
			} else if (dx < -MarsProjection.CIRCUMFERENCE_BLOCKS / 2) {
				dx += MarsProjection.CIRCUMFERENCE_BLOCKS;
			}
			double dz = zf - this.cz[i];
			double rr = radiusBlocks(i);
			double dist = Math.sqrt(dx * dx + dz * dz) / rr; // in crater radii
			if (dist >= INFLUENCE) {
				continue;
			}
			addProfile(i, dist, out);
		}
	}

	private void addProfile(int i, double r, Sample out) {
		double dKm = this.diameterKm[i];
		int deg = this.degradation[i];
		double degFactor = switch (deg) {
			case 4 -> 1.0;
			case 3 -> 0.72;
			case 2 -> 0.5;
			case 1 -> 0.3;
			default -> 0.55;
		};
		double depth = this.depthM[i] > 0 ? this.depthM[i] : estimateDepthMetres(dKm) * degFactor;
		double rim = this.rimM[i] > 0 ? this.rimM[i] : 0.28 * depth;
		int f = this.flags[i];
		boolean replace = dKm >= ADDITIVE_MAX_KM;
		double weight = replace ? 1.0 - smoothstep(REPLACE_FADE_KM, REPLACE_END_KM, dKm) : 1.0;
		if (weight <= 0) {
			return;
		}

		if (r <= 1.0) {
			double h;
			// Complex (flat-floored) craters: floor out to ~0.45 R, then a wall to the rim.
			boolean complex = (f & FLAG_FLAT_FLOOR) != 0 || dKm > 7.0;
			if (complex) {
				double floorR = 0.45;
				double t = r < floorR ? 0.0 : (r - floorR) / (1.0 - floorR);
				double wall = (f & FLAG_TERRACED) != 0 ? terrace(t, 3) : t * t * (3.0 - 2.0 * t);
				h = -depth + (depth + rim) * wall;
			} else {
				h = -depth + (depth + rim) * r * r; // simple bowl
			}
			if ((f & FLAG_CENTRAL_PEAK) != 0) {
				h += 0.45 * depth * Math.exp(-(r * r) / (2 * 0.12 * 0.12));
			}
			if ((f & FLAG_CENTRAL_PIT) != 0) {
				h -= 0.25 * depth * Math.exp(-(r * r) / (2 * 0.08 * 0.08));
			}
			if (replace) {
				// Blend the smoothed MOLA surface toward the crisp profile anchored on the surrounding plains.
				double w = weight * (1.0 - smoothstep(0.86, 1.0, r));
				double target = this.refElevation[i] + h;
				double rimBlend = weight * smoothstep(0.86, 1.0, r) * (1.0 - smoothstep(1.0, 1.15, r));
				out.elevation += (target - out.elevation) * w + rimBlend * Math.max(0.0, this.refElevation[i] + rim - out.elevation) * 0.5;
			} else {
				out.elevation += h;
			}
			out.interior = Math.max(out.interior, weight * (1.0 - r));
		} else {
			// Ejecta blanket thinning as r^-3 (McGetchin et al. 1973).
			double h = rim * Math.pow(r, -3.0);
			if ((f & FLAG_LAYERED_EJECTA) != 0) {
				// Fluidized ejecta: a near-continuous blanket ending in a distal rampart ridge.
				double rampartR = (f & FLAG_MULTI_LAYER_EJECTA) != 0 ? 2.3 : 1.9;
				double blanket = 0.18 * rim * (1.0 - smoothstep(rampartR - 0.1, rampartR + 0.05, r));
				double ridge = 0.35 * rim * Math.exp(-Math.pow((r - rampartR) / 0.08, 2));
				h += blanket + ridge;
				if (r < rampartR + 0.1) {
					out.layeredEjecta = true;
				}
			}
			if (replace && r < 1.15) {
				// keep the rim crest continuous with the replaced interior
				double rimTarget = this.refElevation[i] + rim;
				double w = weight * (1.0 - smoothstep(1.0, 1.15, r));
				out.elevation += Math.max(0.0, rimTarget - out.elevation) * w * 0.5;
			}
			out.elevation += weight * h;
		}
		if (r > 0.85 && r < 1.35) {
			out.rim = Math.max(out.rim, weight * (deg >= 3 || deg == 0 ? 1.0 : 0.5) * (1.0 - Math.abs(r - 1.0) / 0.35));
		}
	}

	/** Fresh Mars crater depth (m) from diameter (km), after Garvin et al. (2003): simple d = 0.21 D^0.81, complex d = 0.36 D^0.49. */
	static double estimateDepthMetres(double dKm) {
		double simple = 0.21 * Math.pow(dKm, 0.81);
		double complex = 0.36 * Math.pow(dKm, 0.49);
		double t = smoothstep(5.0, 9.0, dKm);
		return 1000.0 * (simple * (1 - t) + complex * t);
	}

	private static double terrace(double t, int steps) {
		double s = t * steps;
		double fl = Math.floor(s);
		double fr = s - fl;
		return (fl + fr * fr * fr * (fr * (fr * 6 - 15) + 10)) / steps;
	}

	static double smoothstep(double a, double b, double x) {
		double t = Math.clamp((x - a) / (b - a), 0.0, 1.0);
		return t * t * (3.0 - 2.0 * t);
	}

	public float latitude(int i) {
		return this.lat[i];
	}

	public float longitude(int i) {
		return this.lon[i];
	}

	public float diameterKm(int i) {
		return this.diameterKm[i];
	}
}
