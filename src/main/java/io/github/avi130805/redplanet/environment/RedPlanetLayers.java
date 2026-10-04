package io.github.avi130805.redplanet.environment;

import io.github.avi130805.redplanet.habitat.HabitatIndex;
import io.github.avi130805.redplanet.mars.MarsConditions;
import io.github.avi130805.redplanet.mars.astro.MarsClimate;
import io.github.avi130805.redplanet.registry.RPDimensions;

import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Extra environment-attribute layers, appended after the dimension, biome, timeline and weather layers by
 * {@code EnvironmentAttributeSystemBuilderMixin} (on both the server and the client level), for this mod's
 * dimensions only.
 *
 * <ul>
 * <li><b>Altitude and season:</b> pressure and air density fall off exponentially above the datum with the
 * planet's scale height (and rise below it, e.g. in Hellas), and on Mars follow the seasonal CO2 cycle.</li>
 * <li><b>Habitats:</b> inside a pressurized habitat volume the air is breathable, fuels burn, water stays liquid,
 * and sound and radiation are back to normal.</li>
 * </ul>
 */
public final class RedPlanetLayers {
	/** Pressure maintained inside habitats (Pa); sea-level air, as on the ISS. */
	public static final float HABITAT_PRESSURE = 101325.0F;

	private RedPlanetLayers() {
	}

	public static void install(EnvironmentAttributeSystem.Builder builder, Level level) {
		if (!RPDimensions.isRedPlanetDimension(level)) {
			return; // Earth and every other dimension stay exactly vanilla
		}
		SeasonCache season = new SeasonCache(level);
		builder.addPositionalLayer(RPAttributes.PRESSURE, (base, pos, interp) ->
			HabitatIndex.isInside(level, pos) ? HABITAT_PRESSURE : (float) (base * barometric(level, pos) * season.factor()));
		builder.addPositionalLayer(RPAttributes.AIR_DENSITY, (base, pos, interp) ->
			HabitatIndex.isInside(level, pos) ? 1.0F : (float) (base * barometric(level, pos) * season.factor()));
		builder.addPositionalLayer(RPAttributes.BREATHABLE, (base, pos, interp) -> base || HabitatIndex.isInside(level, pos));
		builder.addPositionalLayer(RPAttributes.COMBUSTION, (base, pos, interp) -> base || HabitatIndex.isInside(level, pos));
		builder.addPositionalLayer(EnvironmentAttributes.WATER_EVAPORATES, (base, pos, interp) -> base && !HabitatIndex.isInside(level, pos));
		builder.addPositionalLayer(RPAttributes.SOUND_DAMPING, (base, pos, interp) -> HabitatIndex.isInside(level, pos) ? 0.0F : base);
		builder.addPositionalLayer(RPAttributes.RADIATION, (base, pos, interp) ->
			HabitatIndex.isInside(level, pos) ? base * HabitatIndex.SHIELDING : base);
	}

	/**
	 * Mars' seasonal CO2 cycle (+-13 %; MarsClimate#seasonalPressureFactor), recomputed once per clock tick because
	 * the physics reads pressure and density for every entity every tick.
	 */
	private static final class SeasonCache {
		private final Level level;
		private final boolean mars;
		private long tick = Long.MIN_VALUE;
		private double factor = 1.0;

		SeasonCache(Level level) {
			this.level = level;
			this.mars = MarsConditions.applies(level);
		}

		double factor() {
			if (!this.mars) {
				return 1.0;
			}
			long now = MarsConditions.clockTicks(this.level);
			if (now != this.tick) {
				this.tick = now;
				this.factor = MarsClimate.seasonalPressureFactor(MarsConditions.solarLongitude(this.level));
			}
			return this.factor;
		}
	}

	private static double barometric(Level level, Vec3 pos) {
		var attrs = level.environmentAttributes();
		double datum = attrs.getDimensionValue(RPAttributes.DATUM_Y);
		double scale = attrs.getDimensionValue(RPAttributes.VERTICAL_SCALE);
		double h = attrs.getDimensionValue(RPAttributes.SCALE_HEIGHT);
		return AtmosphereModel.barometricFactor(pos.y - datum, scale, h);
	}
}
