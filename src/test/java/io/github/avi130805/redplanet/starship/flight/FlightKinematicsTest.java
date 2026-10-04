package io.github.avi130805.redplanet.starship.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class FlightKinematicsTest {
	@Test
	void headingFollowsTheCompass() {
		double[] east = FlightKinematics.heading(90.0);
		assertEquals(1.0, east[0], 1e-9);
		assertEquals(0.0, east[1], 1e-9);
		double[] north = FlightKinematics.heading(0.0);
		assertEquals(0.0, north[0], 1e-9);
		assertEquals(-1.0, north[1], 1e-9); // north is -Z in Minecraft
	}

	@Test
	void attitudeIsAProperRotation() {
		for (double pitch = -90.0; pitch <= 270.0; pitch += 15.0) {
			for (double azimuth = 0.0; azimuth < 360.0; azimuth += 45.0) {
				Quaternionf q = FlightKinematics.attitude(pitch, azimuth);
				Matrix3f m = q.get(new Matrix3f());
				assertEquals(1.0, m.determinant(), 1e-4, "pitch " + pitch + " azimuth " + azimuth);
				assertEquals(1.0, q.lengthSquared(), 1e-4);
			}
		}
	}

	@Test
	void noseAndBellyPointWhereThePitchSays() {
		double[] h = FlightKinematics.heading(90.0);
		Vector3f up = FlightKinematics.attitude(90.0, 90.0).transform(new Vector3f(0, 1, 0));
		assertEquals(1.0, up.y, 1e-5, "vertical: nose up");
		Quaternionf flat = FlightKinematics.attitude(0.0, 90.0);
		Vector3f nose = flat.transform(new Vector3f(0, 1, 0));
		Vector3f belly = flat.transform(new Vector3f(0, 0, 1));
		assertEquals(h[0], nose.x, 1e-5, "belly flop: nose along the heading");
		assertEquals(-1.0, belly.y, 1e-5, "belly flop: heat shield down");
		Vector3f back = FlightKinematics.attitude(180.0, 90.0).transform(new Vector3f(0, 1, 0));
		assertEquals(-h[0], back.x, 1e-5, "boostback: nose pointing back along the heading");
	}

	@Test
	void offsetsMapAltitudeAndDownrange() {
		FlightProfile.Interlude interlude = FlightProfile.Interlude.DEFAULT;
		AltitudeMapping alt = AltitudeMapping.DEFAULT;
		TelemetryTrack.Sample s = new TelemetryTrack.Sample(68.0, 5300.0, 0.1, 0.1, 33, 60.0, 40.0);
		FlightProfile profile = new FlightProfile(null, FlightProfile.Vehicle.STACK, 90.0, alt,
			FlightProfile.DEFAULT_DOWNRANGE, java.util.List.of(new PhaseDef("p", FlightSegment.ASCENT, 0, 10,
				new PhaseDef.Durations(1, 1, 1), PhaseDef.Easing.LINEAR, java.util.List.of())),
			new TelemetryTrack(java.util.List.of()), new TelemetryTrack(java.util.List.of()), java.util.List.of(), interlude);
		org.joml.Vector3d o = FlightKinematics.offset(profile, s, 90.0);
		assertEquals(alt.toBlocks(68000.0), o.y, 1e-6);
		assertTrue(o.x > 300.0 && o.x < 1000.0, "60 km downrange maps east, " + o.x + " blocks");
		assertEquals(0.0, o.z, 1e-6);
	}
}
