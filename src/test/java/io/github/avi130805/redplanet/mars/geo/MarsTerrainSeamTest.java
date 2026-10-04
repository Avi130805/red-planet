package io.github.avi130805.redplanet.mars.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Walking east around the planet must not cross a cliff where the map wraps (x = one circumference). */
class MarsTerrainSeamTest {
	private final MarsTerrain terrain = new MarsTerrain(12345L);

	@Test
	void terrainIsContinuousAcrossTheWrap() {
		double c = MarsProjection.CIRCUMFERENCE_BLOCKS;
		for (double z = -3000.0; z <= 3000.0; z += 37.0) {
			// Continuity: 0.02 blocks apart across the wrap point, the surface can't differ by more than a steep slope allows.
			double seamStep = Math.abs(this.terrain.surfaceY(c + 0.01, z) - this.terrain.surfaceY(c - 0.01, z));
			assertTrue(seamStep < 0.2, "seam step " + seamStep + " at z=" + z);
		}
	}

	@Test
	void theWorldRepeatsEveryCircumference() {
		double c = MarsProjection.CIRCUMFERENCE_BLOCKS;
		for (double x : new double[]{100.0, 5000.0, 12000.0}) {
			assertEquals(this.terrain.surfaceY(x, 300.0), this.terrain.surfaceY(x + c, 300.0), 1e-6);
			assertEquals(this.terrain.surfaceY(x, 300.0), this.terrain.surfaceY(x - c, 300.0), 1e-6);
		}
	}
}
