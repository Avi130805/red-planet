package io.github.avi130805.redplanet.mars.astro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarsSkyModelTest {
	@Test
	void clearNoonIsButterscotchWithNoStars() {
		MarsSkyModel.Look look = MarsSkyModel.compute(60.0, 0.5);
		assertEquals(MarsSkyModel.SKY_DAY[0], look.sky()[0], 0.02);
		assertEquals(MarsSkyModel.SKY_DAY[2], look.sky()[2], 0.02);
		assertEquals(0.0, look.starBrightness(), 1e-6);
		assertTrue(look.sunVisibility() > 0.8, "sun " + look.sunVisibility());
		assertEquals(1.0, look.skyLightFactor(), 1e-6);
	}

	@Test
	void midnightIsDarkAndStarry() {
		MarsSkyModel.Look look = MarsSkyModel.compute(-60.0, 0.5);
		assertTrue(look.sky()[0] < 0.02 && look.sky()[2] < 0.02);
		assertEquals(0.85, look.starBrightness(), 1e-6);
		assertEquals(0.0, look.sunVisibility(), 1e-9);
	}

	@Test
	void twilightLingersAfterSunset() {
		MarsSkyModel.Look dusk = MarsSkyModel.compute(-10.0, 0.5);
		MarsSkyModel.Look night = MarsSkyModel.compute(-30.0, 0.5);
		assertTrue(dusk.sky()[2] > 2 * night.sky()[2], "a faint glow after sunset");
		assertTrue(dusk.starBrightness() < night.starBrightness());
	}

	@Test
	void noStarsUntilAfterSunset() {
		assertEquals(0.0, MarsSkyModel.compute(5.0, 0.5).starBrightness(), 1e-9);
		assertEquals(0.0, MarsSkyModel.compute(0.0, 0.5).starBrightness(), 1e-9);
		float dusk = MarsSkyModel.compute(-10.0, 0.5).starBrightness();
		assertTrue(dusk > 0.1 && dusk < 0.6, "the first stars at -10 degrees: " + dusk);
	}

	@Test
	void aureoleIsBluestNearTheHorizon() {
		assertTrue(MarsSkyModel.compute(3.0, 0.5).aureoleBlue() > 0.95);
		assertTrue(MarsSkyModel.compute(60.0, 0.5).aureoleBlue() < 0.4);
	}

	@Test
	void globalStormDarkensBrownsAndCloses() {
		MarsSkyModel.Look clear = MarsSkyModel.compute(60.0, 0.5);
		MarsSkyModel.Look storm = MarsSkyModel.compute(60.0, 8.5);
		assertTrue(storm.sky()[0] < 0.5 * clear.sky()[0], "much darker");
		assertTrue(storm.sky()[0] / storm.sky()[2] > clear.sky()[0] / clear.sky()[2], "browner");
		assertTrue(storm.sunVisibility() < 0.15, "the Sun is a dim spot: " + storm.sunVisibility());
		assertEquals(48.0 + 900.0 / (8.5 * 8.5), storm.fogEnd(), 1e-3);
		assertTrue(storm.fogEnd() < 70.0);
	}
}
