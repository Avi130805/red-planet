package io.github.avi130805.redplanet.mars.geo;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.InflaterInputStream;

/**
 * A global, cell-registered, simple-cylindrical grid of samples covering Mars (0..360 degrees east,
 * +90..-90 latitude), read from the ".rpgrid" format written by {@code tools/mars-data/build_mars_data.py}.
 *
 * <p>Samples are stored as shorts (topography, 10 m steps) or unsigned bytes (albedo) to keep memory low:
 * the 8 pixel-per-degree topography grid is 2880 x 1440 shorts, about 8 MB on the heap.
 *
 * <p>Sampling is bicubic (Catmull-Rom) and wraps in longitude. Latitude is clamped at the poles.
 */
public final class GeoGrid {
	private static final int MAGIC = 0x52504752; // "RPGR"

	private final int width;
	private final int height;
	private final float scale;
	private final float offset;
	private final short[] samples; // row-major, raw (unscaled) values

	private GeoGrid(int width, int height, float scale, float offset, short[] samples) {
		this.width = width;
		this.height = height;
		this.scale = scale;
		this.offset = offset;
		this.samples = samples;
	}

	public static GeoGrid read(InputStream in) throws IOException {
		DataInputStream header = new DataInputStream(in);
		if (header.readInt() != MAGIC) {
			throw new IOException("Not an rpgrid file");
		}
		int version = header.readUnsignedByte();
		if (version != 1) {
			throw new IOException("Unsupported rpgrid version " + version);
		}
		int kind = header.readUnsignedByte();
		int width = header.readInt();
		int height = header.readInt();
		float scale = header.readFloat();
		float offset = header.readFloat();
		if (width <= 0 || height <= 0 || (long) width * height > 64L * 1024 * 1024) {
			throw new IOException("Bad rpgrid dimensions " + width + "x" + height);
		}

		short[] samples = new short[width * height];
		DataInputStream body = new DataInputStream(new InflaterInputStream(in));
		for (int row = 0; row < height; row++) {
			int base = row * width;
			int prev = 0;
			for (int col = 0; col < width; col++) {
				int value;
				if (kind == 0) {
					int delta = body.readShort();
					value = col == 0 ? delta : (short) (prev + delta);
				} else if (kind == 1) {
					int delta = body.readUnsignedByte();
					value = col == 0 ? delta : (prev + delta) & 0xFF;
				} else {
					throw new IOException("Unsupported rpgrid kind " + kind);
				}
				samples[base + col] = (short) value;
				prev = value;
			}
		}
		return new GeoGrid(width, height, scale, offset, samples);
	}

	public int width() {
		return this.width;
	}

	public int height() {
		return this.height;
	}

	/** Pixels per degree of this grid. */
	public double pixelsPerDegree() {
		return this.width / 360.0;
	}

	/** Physical value of the cell at (col, row), wrapping longitude and clamping latitude. */
	public double cell(int col, int row) {
		int c = Math.floorMod(col, this.width);
		int r = Math.clamp(row, 0, this.height - 1);
		return this.samples[r * this.width + c] * this.scale + this.offset;
	}

	/**
	 * Bicubic sample at a latitude/longitude in degrees (longitude east, any range).
	 */
	public double sample(double latDeg, double lonDeg) {
		double ppd = this.pixelsPerDegree();
		// Cell-registered: the centre of column c is at longitude (c + 0.5) / ppd.
		double fx = lonDeg * ppd - 0.5;
		double fy = (90.0 - latDeg) * ppd - 0.5;
		int x0 = (int) Math.floor(fx);
		int y0 = (int) Math.floor(fy);
		double tx = fx - x0;
		double ty = fy - y0;

		double r0 = cubicRow(x0, y0 - 1, tx);
		double r1 = cubicRow(x0, y0, tx);
		double r2 = cubicRow(x0, y0 + 1, tx);
		double r3 = cubicRow(x0, y0 + 2, tx);
		return catmullRom(r0, r1, r2, r3, ty);
	}

	/** Bilinear sample; cheaper, used where smoothness of derivatives does not matter. */
	public double sampleBilinear(double latDeg, double lonDeg) {
		double ppd = this.pixelsPerDegree();
		double fx = lonDeg * ppd - 0.5;
		double fy = (90.0 - latDeg) * ppd - 0.5;
		int x0 = (int) Math.floor(fx);
		int y0 = (int) Math.floor(fy);
		double tx = fx - x0;
		double ty = fy - y0;
		double a = cell(x0, y0) + (cell(x0 + 1, y0) - cell(x0, y0)) * tx;
		double b = cell(x0, y0 + 1) + (cell(x0 + 1, y0 + 1) - cell(x0, y0 + 1)) * tx;
		return a + (b - a) * ty;
	}

	private double cubicRow(int x0, int row, double t) {
		return catmullRom(cell(x0 - 1, row), cell(x0, row), cell(x0 + 1, row), cell(x0 + 2, row), t);
	}

	static double catmullRom(double p0, double p1, double p2, double p3, double t) {
		double t2 = t * t;
		double t3 = t2 * t;
		return 0.5 * ((2.0 * p1)
			+ (-p0 + p2) * t
			+ (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2
			+ (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3);
	}
}
