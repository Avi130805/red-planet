package io.github.avi130805.redplanet.gametest.client.trailer;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.phys.Vec3;

/**
 * A camera move through keyframes: the camera position and the point it looks at each follow a smooth cubic curve
 * through their keys (Catmull-Rom tangents for uneven spacing, at rest at both ends), with roll and field of view
 * eased between keys. Positions and targets are relative to an anchor given per frame (zero for world coordinates,
 * or a moving vehicle's interpolated position), so a camera can ride alongside a rocket.
 */
public final class CameraPath {
	private final List<Key> keys;
	private final boolean restAtEnds;

	public record Key(double time, Vec3 position, Vec3 target, float roll, float fov) {
	}

	private CameraPath(List<Key> keys, boolean restAtEnds) {
		this.keys = List.copyOf(keys);
		this.restAtEnds = restAtEnds;
	}

	public static Builder builder() {
		return new Builder();
	}

	public double duration() {
		return this.keys.getLast().time();
	}

	public TrailerCamera.Pose at(double t) {
		return this.at(t, Vec3.ZERO);
	}

	public TrailerCamera.Pose at(double t, Vec3 anchor) {
		Vec3 position = this.spline(t, true).add(anchor);
		Vec3 target = this.spline(t, false).add(anchor);
		int i = this.segment(t);
		Key a = this.keys.get(i);
		Key b = this.keys.get(Math.min(i + 1, this.keys.size() - 1));
		double u = b.time() == a.time() ? 0.0 : clamp((t - a.time()) / (b.time() - a.time()));
		double s = u * u * (3.0 - 2.0 * u);
		float roll = (float) (a.roll() + (b.roll() - a.roll()) * s);
		float fov = (float) (a.fov() + (b.fov() - a.fov()) * s);
		return TrailerCamera.Pose.looking(position, target, roll, fov);
	}

	private int segment(double t) {
		for (int i = 0; i < this.keys.size() - 1; i++) {
			if (t < this.keys.get(i + 1).time()) {
				return i;
			}
		}
		return Math.max(0, this.keys.size() - 2);
	}

	private Vec3 point(int i, boolean position) {
		Key k = this.keys.get(i);
		return position ? k.position() : k.target();
	}

	/** Tangent (per second) at key i: Catmull-Rom for uneven spacing; zero at the ends when the move starts and stops at rest. */
	private Vec3 tangent(int i, boolean position) {
		int n = this.keys.size();
		if (n < 2 || (this.restAtEnds && (i == 0 || i == n - 1))) {
			return Vec3.ZERO;
		}
		int lo = Math.max(0, i - 1);
		int hi = Math.min(n - 1, i + 1);
		double dt = this.keys.get(hi).time() - this.keys.get(lo).time();
		return dt <= 0.0 ? Vec3.ZERO : this.point(hi, position).subtract(this.point(lo, position)).scale(1.0 / dt);
	}

	/** Cubic Hermite interpolation between the keys around t. */
	private Vec3 spline(double t, boolean position) {
		if (this.keys.size() == 1) {
			return this.point(0, position);
		}
		int i = this.segment(t);
		Key a = this.keys.get(i);
		Key b = this.keys.get(i + 1);
		double h = b.time() - a.time();
		double u = h <= 0.0 ? 0.0 : clamp((t - a.time()) / h);
		double u2 = u * u;
		double u3 = u2 * u;
		double h00 = 2 * u3 - 3 * u2 + 1;
		double h10 = u3 - 2 * u2 + u;
		double h01 = -2 * u3 + 3 * u2;
		double h11 = u3 - u2;
		Vec3 p0 = this.point(i, position);
		Vec3 p1 = this.point(i + 1, position);
		Vec3 m0 = this.tangent(i, position).scale(h);
		Vec3 m1 = this.tangent(i + 1, position).scale(h);
		return p0.scale(h00).add(m0.scale(h10)).add(p1.scale(h01)).add(m1.scale(h11));
	}

	private static double clamp(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}

	public static final class Builder {
		private final List<Key> keys = new ArrayList<>();
		private boolean restAtEnds = true;

		/** A key at {@code time} seconds: camera at {@code position}, looking at {@code target}. */
		public Builder key(double time, Vec3 position, Vec3 target, float roll, float fov) {
			this.keys.add(new Key(time, position, target, roll, fov));
			return this;
		}

		public Builder key(double time, Vec3 position, Vec3 target, float fov) {
			return this.key(time, position, target, 0.0F, fov);
		}

		/** Keep moving through the first and last keys instead of starting and stopping at rest (for cuts mid-move). */
		public Builder moving() {
			this.restAtEnds = false;
			return this;
		}

		public CameraPath build() {
			if (this.keys.isEmpty()) {
				throw new IllegalStateException("A camera path needs at least one key");
			}
			return new CameraPath(this.keys, this.restAtEnds);
		}
	}
}
