package io.github.avi130805.redplanet.environment;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.Vec3;

/**
 * Convenience reads of the planet attributes. Cheap: dimension-wide values are cached per tick by vanilla, and
 * positional ones walk a handful of layers.
 */
public final class PlanetEnvironment {
	private PlanetEnvironment() {
	}

	/** Gravity relative to Earth. */
	public static double gravity(Level level) {
		return level.environmentAttributes().getDimensionValue(RPAttributes.GRAVITY);
	}

	/** Air density relative to Earth sea level, at a position (includes altitude and habitats). */
	public static double airDensity(Level level, Vec3 pos) {
		return level.environmentAttributes().getValue(RPAttributes.AIR_DENSITY, pos);
	}

	/** Pressure in pascals at a position. */
	public static double pressure(Level level, Vec3 pos) {
		return level.environmentAttributes().getValue(RPAttributes.PRESSURE, pos);
	}

	public static boolean breathable(Level level, Vec3 pos) {
		return level.environmentAttributes().getValue(RPAttributes.BREATHABLE, pos);
	}

	public static boolean combustion(LevelReader level, BlockPos pos) {
		return level.environmentAttributes().getValue(RPAttributes.COMBUSTION, pos);
	}

	public static double radiation(Level level, Vec3 pos) {
		return level.environmentAttributes().getValue(RPAttributes.RADIATION, pos);
	}

	public static double soundDamping(Level level, Vec3 pos) {
		return level.environmentAttributes().getValue(RPAttributes.SOUND_DAMPING, pos);
	}

	/** True when this dimension uses non-Earth physics (gravity or air differ from Earth's at the datum). */
	public static boolean isAlienWorld(Level level) {
		return Math.abs(gravity(level) - 1.0) > 1e-4
			|| level.environmentAttributes().getDimensionValue(RPAttributes.SCALE_HEIGHT) != RPAttributes.SCALE_HEIGHT.defaultValue();
	}

	/** Drag scale for the entity's surroundings (1 on Earth). */
	public static double dragScale(Level level, Vec3 pos) {
		return AtmosphereModel.dragScale(airDensity(level, pos), gravity(level));
	}
}
