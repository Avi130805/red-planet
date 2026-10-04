package io.github.avi130805.redplanet.mars.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Dust devils form when and where Spirit saw them (docs/SCIENCE.md section 11). */
class DustDevilsTest {
	@Test
	void peakAtEarlyAfternoonInSouthernSpring() {
		assertEquals(1.0, DustDevils.activity(250.0, 13.0), 1e-9);
		assertTrue(DustDevils.activity(250.0, 11.0) < DustDevils.activity(250.0, 13.0));
		assertTrue(DustDevils.activity(200.0, 13.0) < DustDevils.activity(250.0, 13.0));
	}

	@Test
	void noneOutsideTheSeasonOrTheHours() {
		assertEquals(0.0, DustDevils.activity(90.0, 13.0), 1e-12, "northern summer: out of season");
		assertEquals(0.0, DustDevils.activity(172.0, 13.0), 1e-12);
		assertEquals(0.0, DustDevils.activity(341.0, 13.0), 1e-12);
		assertEquals(0.0, DustDevils.activity(250.0, 9.0), 1e-12, "before 09:30");
		assertEquals(0.0, DustDevils.activity(250.0, 17.0), 1e-12, "after 16:30");
		assertEquals(0.0, DustDevils.activity(250.0, 0.0), 1e-12, "night");
	}
}
