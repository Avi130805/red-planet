package io.github.avi130805.redplanet.starship.flight;

import io.github.avi130805.redplanet.starship.geometry.StarshipGeometry;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/**
 * Turns telemetry into motion in the world: where a vehicle is and which way it points at a given mission time.
 * Pure math, shared by the server (authoritative motion) and the client (smooth prediction between ticks).
 *
 * <p>Frames: the <i>heading</i> h is the horizontal flight direction from the azimuth (degrees clockwise from north;
 * north is -Z and east +X in Minecraft). A vehicle's body frame has x to its right, y toward the nose and z toward the
 * belly. At pitch p the nose points along {@code cos p * h + sin p * up}, the belly along {@code sin p * h - cos p * up},
 * and the right side along {@code up x h} for every pitch, so a pitch change is a rotation about the right axis.
 */
public final class FlightKinematics {
	private FlightKinematics() {
	}

	/** Unit heading vector (x, z) for an azimuth in degrees clockwise from north. */
	public static double[] heading(double azimuthDeg) {
		double a = Math.toRadians(azimuthDeg);
		return new double[]{Math.sin(a), -Math.cos(a)};
	}

	/**
	 * In-world offset of a telemetry sample from its reference point, in blocks. Altitude and downrange are each mapped
	 * through their profile mapping (true scale near the ground, logarithmic beyond).
	 */
	public static Vector3d offset(FlightProfile profile, TelemetryTrack.Sample sample, double azimuthDeg) {
		double up = profile.altitude().toBlocks(Math.max(0.0, sample.altitudeKm() * 1000.0));
		double downrangeMetres = sample.downrangeKm() * 1000.0;
		double along = Math.signum(downrangeMetres) * profile.downrange().toBlocks(Math.abs(downrangeMetres));
		double[] h = heading(azimuthDeg);
		return new Vector3d(h[0] * along, up, h[1] * along);
	}

	/** Attitude (body frame to world) for a pitch in degrees and an azimuth. */
	public static Quaternionf attitude(double pitchDeg, double azimuthDeg) {
		double[] h = heading(azimuthDeg);
		double p = Math.toRadians(pitchDeg);
		double cp = Math.cos(p);
		double sp = Math.sin(p);
		// Columns: right = up x h, nose = cos p h + sin p up, belly = sin p h - cos p up.
		float rx = (float) h[1];
		float rz = (float) -h[0];
		Matrix3f m = new Matrix3f(
			rx, 0.0F, rz,
			(float) (cp * h[0]), (float) sp, (float) (cp * h[1]),
			(float) (sp * h[0]), (float) -cp, (float) (sp * h[1]));
		return new Quaternionf().setFromNormalized(m);
	}

	/**
	 * Body-frame offset from the booster's origin to the ship's origin while stacked: the ship stands on the hot-staging
	 * ring.
	 */
	public static double stackOffset() {
		return StarshipGeometry.BOOSTER_HEIGHT;
	}
}
