package io.github.avi130805.redplanet.mars;

import io.github.avi130805.redplanet.environment.PlanetEnvironment;
import io.github.avi130805.redplanet.mars.astro.MarsCalendar;
import io.github.avi130805.redplanet.mars.astro.MarsClimate;
import io.github.avi130805.redplanet.mars.geo.MarsProjection;
import io.github.avi130805.redplanet.mars.weather.MarsWeather;
import io.github.avi130805.redplanet.registry.RPDimensions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Local conditions on Mars at a position and time: areographic coordinates, season, local time, pressure,
 * temperature. Works on both sides (the Mars clock is synced to clients).
 */
public final class MarsConditions {
	private MarsConditions() {
	}

	public static boolean applies(Level level) {
		return RPDimensions.isMars(level);
	}

	/** Mars clock ticks (the dimension's default clock). */
	public static long clockTicks(Level level) {
		return level.getDefaultClockTime();
	}

	public static double latitude(Vec3 pos) {
		return MarsProjection.latitude(pos.z);
	}

	public static double longitude(Vec3 pos) {
		return MarsProjection.longitude(pos.x);
	}

	public static double solarLongitude(Level level) {
		PlanetSettings s = PlanetSettings.of(level);
		return MarsCalendar.solarLongitude(clockTicks(level), s.startLs(), s.yearCompression());
	}

	public static double sunDistanceAu(Level level) {
		PlanetSettings s = PlanetSettings.of(level);
		return MarsCalendar.sunDistanceAu(clockTicks(level), s.startLs(), s.yearCompression());
	}

	public static double lmstFraction(Level level) {
		return MarsCalendar.lmstFraction(clockTicks(level));
	}

	public static long sol(Level level) {
		return MarsCalendar.sol(clockTicks(level));
	}

	/** Elevation above the MOLA areoid (m) of a position. */
	public static double elevation(Vec3 pos) {
		return MarsProjection.elevationOfY(pos.y);
	}

	/** Visible dust optical depth at a position now (background haze ~0.45-0.9; storms raise it). */
	public static double dustTau(Level level, Vec3 pos) {
		return MarsWeather.tauAt(level, pos.x, pos.z);
	}

	/** Ground temperature (K) at a position now. */
	public static double temperatureK(Level level, Vec3 pos) {
		return MarsClimate.surfaceTemperature(latitude(pos), solarLongitude(level), lmstFraction(level), sunDistanceAu(level),
			PlanetEnvironment.pressure(level, pos), dustTau(level, pos));
	}

	public static double temperatureK(Level level, BlockPos pos) {
		return temperatureK(level, Vec3.atCenterOf(pos));
	}
}
