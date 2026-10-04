package io.github.avi130805.redplanet.starship.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.avi130805.redplanet.starship.geometry.VehicleMesh.Joint;
import io.github.avi130805.redplanet.starship.geometry.VehicleMesh.PartMesh;

class StarshipGeometryTest {
	private static final int F = VehicleMesh.FLOATS_PER_VERTEX;

	@Test
	void trueScaleDimensions() {
		assertEquals(52.1, StarshipGeometry.SHIP_HEIGHT, 1e-9);
		assertEquals(71.9, StarshipGeometry.BOOSTER_HEIGHT, 1e-9);
		assertEquals(124.0, StarshipGeometry.STACK_HEIGHT, 1e-9);

		VehicleMesh ship = StarshipGeometry.buildShip(StarshipGeometry.Lod.HIGH);
		float[] hull = ship.part(VehiclePart.SHIP_HULL).quads();
		float maxY = Float.NEGATIVE_INFINITY;
		float maxR = 0;
		for (int i = 0; i < hull.length; i += F) {
			maxY = Math.max(maxY, hull[i + 1]);
			maxR = Math.max(maxR, (float) Math.hypot(hull[i], hull[i + 2]));
		}
		assertEquals(52.1, maxY, 1e-3);
		assertEquals(4.5, maxR, 1e-3);
	}

	@Test
	void everyQuadIsWoundOutwardAndNormalsAreUnit() {
		for (VehicleMesh mesh : new VehicleMesh[]{StarshipGeometry.buildShip(StarshipGeometry.Lod.HIGH), StarshipGeometry.buildBooster(StarshipGeometry.Lod.HIGH)}) {
			for (Map.Entry<VehiclePart, PartMesh> e : mesh.parts().entrySet()) {
				float[] q = e.getValue().quads();
				assertEquals(0, q.length % VehicleMesh.FLOATS_PER_QUAD, e.getKey().name());
				for (int i = 0; i < q.length; i += VehicleMesh.FLOATS_PER_QUAD) {
					double nx = 0, ny = 0, nz = 0;
					for (int k = 0; k < 4; k++) {
						int o = i + k * F;
						double len = Math.sqrt(q[o + 3] * q[o + 3] + q[o + 4] * q[o + 4] + q[o + 5] * q[o + 5]);
						assertEquals(1.0, len, 1e-3, e.getKey() + " normal length");
						assertTrue(q[o + 6] >= 0 && q[o + 6] <= 1 && q[o + 7] >= 0 && q[o + 7] <= 1, e.getKey() + " uv out of range");
						nx += q[o + 3];
						ny += q[o + 4];
						nz += q[o + 5];
					}
					// Geometric normal from the winding (Newell's method) must agree with the vertex normals.
					double gx = 0, gy = 0, gz = 0;
					for (int k = 0; k < 4; k++) {
						int a = i + k * F;
						int b = i + ((k + 1) % 4) * F;
						gx += (q[a + 1] - q[b + 1]) * (q[a + 2] + q[b + 2]);
						gy += (q[a + 2] - q[b + 2]) * (q[a] + q[b]);
						gz += (q[a] - q[b]) * (q[a + 1] + q[b + 1]);
					}
					double glen = Math.sqrt(gx * gx + gy * gy + gz * gz);
					if (glen < 1e-6) {
						continue; // degenerate sliver at the nose tip
					}
					double dot = (gx * nx + gy * ny + gz * nz) / glen;
					assertTrue(dot > 0, e.getKey() + " quad " + (i / VehicleMesh.FLOATS_PER_QUAD) + " wound inward");
				}
			}
		}
	}

	@Test
	void legsSwingOutwardAndCarryTheHullClearOfTheGround() {
		VehicleMesh ship = StarshipGeometry.buildShip(StarshipGeometry.Lod.LOW);
		for (int i = 0; i < 6; i++) {
			PartMesh leg = ship.part(VehiclePart.shipLeg(i));
			float[] q = leg.quads();
			// The lowest vertex is on the foot pad.
			int lowest = 0;
			for (int k = 0; k < q.length; k += F) {
				if (q[k + 1] < q[lowest + 1]) {
					lowest = k;
				}
			}
			double[] p = {q[lowest], q[lowest + 1], q[lowest + 2]};
			assertTrue(p[1] >= 0.0, "stowed leg " + i + " must stay above the skirt bottom, y=" + p[1]);
			double[] rotated = deployLeg(p, leg.joint());
			assertTrue(Math.hypot(rotated[0], rotated[2]) > Math.hypot(p[0], p[2]) + 1.2, "leg " + i + " should swing outward");
			assertTrue(rotated[1] < -1.2, "deployed foot y " + rotated[1]);
			assertTrue(rotated[1] < StarshipGeometry.SL_EXIT_Y - 1.0, "feet must reach below the engine bells");
		}
		assertTrue(StarshipGeometry.LANDED_SKIRT_HEIGHT > 0.9);
	}

	@Test
	void positiveFlapAngleTucksTowardLeeward() {
		VehicleMesh ship = StarshipGeometry.buildShip(StarshipGeometry.Lod.LOW);
		for (VehiclePart flap : new VehiclePart[]{VehiclePart.SHIP_AFT_FLAP_RIGHT, VehiclePart.SHIP_AFT_FLAP_LEFT,
				VehiclePart.SHIP_FORE_FLAP_RIGHT, VehiclePart.SHIP_FORE_FLAP_LEFT}) {
			PartMesh m = ship.part(flap);
			float[] q = m.quads();
			int tip = 0;
			for (int k = 0; k < q.length; k += F) {
				if (Math.hypot(q[k], q[k + 2]) > Math.hypot(q[tip], q[tip + 2])) {
					tip = k;
				}
			}
			double[] p = {q[tip], q[tip + 1], q[tip + 2]};
			double[] r = rotate(p, m.joint(), Math.toRadians(45));
			assertTrue(r[2] < p[2] - 0.5, flap + " should move toward -Z when tucked");
		}
	}

	@Test
	void boosterEnginesDoNotOverlap() {
		double[][] pos = StarshipGeometry.boosterEnginePositions();
		assertEquals(33, pos.length);
		double minGap = Double.MAX_VALUE;
		for (int i = 0; i < pos.length; i++) {
			for (int j = i + 1; j < pos.length; j++) {
				double gap = Math.hypot(pos[i][0] - pos[j][0], pos[i][1] - pos[j][1]) - pos[i][2] - pos[j][2];
				minGap = Math.min(minGap, gap);
			}
			assertTrue(Math.hypot(pos[i][0], pos[i][1]) + pos[i][2] <= StarshipGeometry.HULL_RADIUS);
		}
		assertTrue(minGap >= 0.0, "nozzles overlap by " + -minGap);
	}

	@Test
	void polygonBudget() {
		int high = StarshipGeometry.buildShip(StarshipGeometry.Lod.HIGH).totalQuads()
			+ StarshipGeometry.buildBooster(StarshipGeometry.Lod.HIGH).totalQuads();
		int low = StarshipGeometry.buildShip(StarshipGeometry.Lod.LOW).totalQuads()
			+ StarshipGeometry.buildBooster(StarshipGeometry.Lod.LOW).totalQuads();
		assertTrue(high < 14000, "full stack HIGH quads " + high);
		assertTrue(low < 4000, "full stack LOW quads " + low);
	}

	/** Telescoping deployment: slide down by LEG_EXTENSION, then splay about the lowered hinge. */
	static double[] deployLeg(double[] p, Joint j) {
		double ext = StarshipGeometry.LEG_EXTENSION;
		Joint lowered = new Joint(j.px(), (float) (j.py() - ext), j.pz(), j.ax(), j.ay(), j.az());
		return rotate(new double[]{p[0], p[1] - ext, p[2]}, lowered, Math.toRadians(StarshipGeometry.LEG_DEPLOY_DEG));
	}

	/** Rodrigues rotation of p about the joint axis through the joint pivot. */
	static double[] rotate(double[] p, Joint j, double angle) {
		double[] k = {j.ax(), j.ay(), j.az()};
		double[] v = {p[0] - j.px(), p[1] - j.py(), p[2] - j.pz()};
		double c = Math.cos(angle);
		double s = Math.sin(angle);
		double dot = k[0] * v[0] + k[1] * v[1] + k[2] * v[2];
		double[] cross = {k[1] * v[2] - k[2] * v[1], k[2] * v[0] - k[0] * v[2], k[0] * v[1] - k[1] * v[0]};
		return new double[]{
			j.px() + v[0] * c + cross[0] * s + k[0] * dot * (1 - c),
			j.py() + v[1] * c + cross[1] * s + k[1] * dot * (1 - c),
			j.pz() + v[2] * c + cross[2] * s + k[2] * dot * (1 - c)};
	}
}
