package io.github.avi130805.redplanet.starship.geometry;

import java.util.Arrays;

import io.github.avi130805.redplanet.starship.geometry.UvLayout.Region;

/**
 * Accumulates quads for one vehicle part. All geometry helpers emit counter-clockwise quads as seen from
 * the side the normal points to, which is what back-face culling expects.
 */
final class MeshBuilder {
	private float[] data = new float[VehicleMesh.FLOATS_PER_QUAD * 64];
	private int size;

	float[] toArray() {
		return Arrays.copyOf(this.data, this.size);
	}

	private void put(double x, double y, double z, double nx, double ny, double nz, float u, float v) {
		if (this.size + VehicleMesh.FLOATS_PER_VERTEX > this.data.length) {
			this.data = Arrays.copyOf(this.data, this.data.length * 2);
		}
		float[] d = this.data;
		int i = this.size;
		d[i] = (float) x;
		d[i + 1] = (float) y;
		d[i + 2] = (float) z;
		d[i + 3] = (float) nx;
		d[i + 4] = (float) ny;
		d[i + 5] = (float) nz;
		d[i + 6] = u;
		d[i + 7] = v;
		this.size = i + VehicleMesh.FLOATS_PER_VERTEX;
	}

	/** A vertex: position, normal, uv. */
	record V(double x, double y, double z, double nx, double ny, double nz, float u, float v) {
	}

	/** Emits a quad; if its winding disagrees with the intended outward direction, it is reversed. */
	void quad(V a, V b, V c, V d, double ox, double oy, double oz) {
		double ux = b.x - a.x, uy = b.y - a.y, uz = b.z - a.z;
		double vx = d.x - a.x, vy = d.y - a.y, vz = d.z - a.z;
		double cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
		if (cx * cx + cy * cy + cz * cz < 1e-14) {
			// a==b (fan apex) or degenerate edge: use the other diagonal
			ux = c.x - a.x;
			uy = c.y - a.y;
			uz = c.z - a.z;
			cx = uy * vz - uz * vy;
			cy = uz * vx - ux * vz;
			cz = ux * vy - uy * vx;
			if (cx * cx + cy * cy + cz * cz < 1e-14) {
				vx = c.x - b.x;
				vy = c.y - b.y;
				vz = c.z - b.z;
				ux = b.x - a.x;
				uy = b.y - a.y;
				uz = b.z - a.z;
				cx = uy * vz - uz * vy;
				cy = uz * vx - ux * vz;
				cz = ux * vy - uy * vx;
			}
		}
		if (cx * ox + cy * oy + cz * oz >= 0) {
			emit(a);
			emit(b);
			emit(c);
			emit(d);
		} else {
			emit(a);
			emit(d);
			emit(c);
			emit(b);
		}
	}

	private void emit(V v) {
		put(v.x, v.y, v.z, v.nx, v.ny, v.nz, v.u, v.v);
	}

	/**
	 * Surface of revolution around the Y axis.
	 *
	 * @param r      profile radii, ordered from bottom to top
	 * @param y      profile heights, same order
	 * @param t      texture t at each profile point (region-relative, 0..1)
	 * @param segments number of facets around the axis
	 * @param inward true for an inside surface (normals toward the axis), e.g. a nozzle interior
	 * @param cx, cz axis position in the body frame
	 * @param thetaOffset angle (radians) added to every facet, to rotate seams away from view
	 */
	void lathe(double[] r, double[] y, double[] t, int segments, Region region, boolean inward,
			double cx, double cz, double thetaOffset) {
		int n = r.length;
		// Smooth profile normals in the (r, y) plane: average of adjacent segment normals.
		double[] nr = new double[n];
		double[] ny = new double[n];
		for (int i = 0; i < n; i++) {
			double sr = 0;
			double sy = 0;
			if (i > 0) {
				double dr = r[i] - r[i - 1];
				double dy = y[i] - y[i - 1];
				double l = Math.hypot(dr, dy);
				sr += dy / l;
				sy += -dr / l;
			}
			if (i < n - 1) {
				double dr = r[i + 1] - r[i];
				double dy = y[i + 1] - y[i];
				double l = Math.hypot(dr, dy);
				sr += dy / l;
				sy += -dr / l;
			}
			double l = Math.hypot(sr, sy);
			nr[i] = sr / l;
			ny[i] = sy / l;
			if (inward) {
				nr[i] = -nr[i];
				ny[i] = -ny[i];
			}
		}
		for (int j = 0; j < segments; j++) {
			double s0 = j / (double) segments;
			double s1 = (j + 1) / (double) segments;
			double th0 = thetaOffset + 2.0 * Math.PI * s0;
			double th1 = thetaOffset + 2.0 * Math.PI * s1;
			double sin0 = Math.sin(th0), cos0 = Math.cos(th0);
			double sin1 = Math.sin(th1), cos1 = Math.cos(th1);
			for (int i = 0; i < n - 1; i++) {
				V a = new V(cx + r[i] * sin0, y[i], cz + r[i] * cos0, nr[i] * sin0, ny[i], nr[i] * cos0, region.u(s0), region.v(t[i]));
				V b = new V(cx + r[i] * sin1, y[i], cz + r[i] * cos1, nr[i] * sin1, ny[i], nr[i] * cos1, region.u(s1), region.v(t[i]));
				V c = new V(cx + r[i + 1] * sin1, y[i + 1], cz + r[i + 1] * cos1, nr[i + 1] * sin1, ny[i + 1], nr[i + 1] * cos1, region.u(s1), region.v(t[i + 1]));
				V d = new V(cx + r[i + 1] * sin0, y[i + 1], cz + r[i + 1] * cos0, nr[i + 1] * sin0, ny[i + 1], nr[i + 1] * cos0, region.u(s0), region.v(t[i + 1]));
				double mid = (th0 + th1) * 0.5;
				double mnr = (nr[i] + nr[i + 1]) * 0.5;
				double mny = (ny[i] + ny[i + 1]) * 0.5;
				quad(a, b, c, d, mnr * Math.sin(mid), mny, mnr * Math.cos(mid));
			}
		}
	}

	/**
	 * A flat annulus (or disc when innerR = 0) perpendicular to Y at height y, facing +Y or -Y, with planar
	 * texture mapping across the outer diameter.
	 */
	void disc(double outerR, double innerR, double y, int segments, Region region, boolean facingUp, double cx, double cz) {
		double ny = facingUp ? 1 : -1;
		for (int j = 0; j < segments; j++) {
			double th0 = 2.0 * Math.PI * j / segments;
			double th1 = 2.0 * Math.PI * (j + 1) / segments;
			double[] xs = {outerR * Math.sin(th0), outerR * Math.sin(th1), innerR * Math.sin(th1), innerR * Math.sin(th0)};
			double[] zs = {outerR * Math.cos(th0), outerR * Math.cos(th1), innerR * Math.cos(th1), innerR * Math.cos(th0)};
			V[] vs = new V[4];
			for (int k = 0; k < 4; k++) {
				float u = region.u((xs[k] / outerR + 1.0) * 0.5);
				float v = region.v((zs[k] / outerR + 1.0) * 0.5);
				vs[k] = new V(cx + xs[k], y, cz + zs[k], 0, ny, 0, u, v);
			}
			quad(vs[0], vs[1], vs[2], vs[3], 0, ny, 0);
		}
	}

	/**
	 * A thick flat plate (flap, fin, leg). The outline is given in the plate's local 2D coordinates
	 * (a = along the span axis, b = along the height axis); the plate is centred on the plane spanned by
	 * {@code spanDir} and {@code heightDir} through {@code origin}, with half-thickness offsets along
	 * {@code normalDir}. Face "+normal" uses {@code plusRegion}, "-normal" uses {@code minusRegion}, edges use
	 * {@code edgeRegion}. Texture s runs along the span (0 at the root), t along the height (0 at the top).
	 *
	 * @param outline four corners in order root-bottom, root-top, tip-top, tip-bottom: {a, b} pairs
	 * @param span the full span (for texture scaling)
	 * @param heightRange {bMin, bMax} for texture scaling
	 */
	void plate(double[][] outline, double thicknessRoot, double thicknessTip, double span, double[] heightRange,
			double[] origin, double[] spanDir, double[] heightDir, double[] normalDir,
			Region plusRegion, Region minusRegion, Region edgeRegion) {
		int m = outline.length;
		V[] plus = new V[m];
		V[] minus = new V[m];
		double hMin = heightRange[0];
		double hMax = heightRange[1];
		for (int k = 0; k < m; k++) {
			double a = outline[k][0];
			double b = outline[k][1];
			double half = 0.5 * (thicknessRoot + (thicknessTip - thicknessRoot) * (a / span));
			double px = origin[0] + spanDir[0] * a + heightDir[0] * b;
			double py = origin[1] + spanDir[1] * a + heightDir[1] * b;
			double pz = origin[2] + spanDir[2] * a + heightDir[2] * b;
			double s = a / span;
			double t = 1.0 - (b - hMin) / (hMax - hMin);
			plus[k] = new V(px + normalDir[0] * half, py + normalDir[1] * half, pz + normalDir[2] * half,
				normalDir[0], normalDir[1], normalDir[2], plusRegion.u(s), plusRegion.v(t));
			minus[k] = new V(px - normalDir[0] * half, py - normalDir[1] * half, pz - normalDir[2] * half,
				-normalDir[0], -normalDir[1], -normalDir[2], minusRegion.u(s), minusRegion.v(t));
		}
		quad(plus[0], plus[1], plus[2], plus[3], normalDir[0], normalDir[1], normalDir[2]);
		quad(minus[0], minus[1], minus[2], minus[3], -normalDir[0], -normalDir[1], -normalDir[2]);
		// Edges: one quad per outline edge, normal pointing away from the outline centroid.
		double ca = 0;
		double cb = 0;
		for (double[] p : outline) {
			ca += p[0] / m;
			cb += p[1] / m;
		}
		for (int k = 0; k < m; k++) {
			int k2 = (k + 1) % m;
			double ea = (outline[k][0] + outline[k2][0]) * 0.5 - ca;
			double eb = (outline[k][1] + outline[k2][1]) * 0.5 - cb;
			double ox = spanDir[0] * ea + heightDir[0] * eb;
			double oy = spanDir[1] * ea + heightDir[1] * eb;
			double oz = spanDir[2] * ea + heightDir[2] * eb;
			double ol = Math.sqrt(ox * ox + oy * oy + oz * oz);
			ox /= ol;
			oy /= ol;
			oz /= ol;
			V p0 = withNormalUv(plus[k], ox, oy, oz, edgeRegion.u(0), edgeRegion.v(k / (double) m));
			V p1 = withNormalUv(plus[k2], ox, oy, oz, edgeRegion.u(0), edgeRegion.v((k + 1) / (double) m));
			V m1 = withNormalUv(minus[k2], ox, oy, oz, edgeRegion.u(1), edgeRegion.v((k + 1) / (double) m));
			V m0 = withNormalUv(minus[k], ox, oy, oz, edgeRegion.u(1), edgeRegion.v(k / (double) m));
			quad(p0, p1, m1, m0, ox, oy, oz);
		}
	}

	private static V withNormalUv(V v, double nx, double ny, double nz, float u, float vv) {
		return new V(v.x, v.y, v.z, nx, ny, nz, u, vv);
	}

	/** Axis-aligned-ish box given by 8 corners is rarely needed; boxes are built as 4-sided lathes or plates. */
	boolean isEmpty() {
		return this.size == 0;
	}
}
