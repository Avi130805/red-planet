package io.github.avi130805.redplanet.mars;

import io.github.avi130805.redplanet.config.RedPlanetConfig;
import io.github.avi130805.redplanet.environment.PlanetEnvironment;
import io.github.avi130805.redplanet.mars.astro.MarsCalendar;
import io.github.avi130805.redplanet.mars.astro.MarsClimate;
import io.github.avi130805.redplanet.mars.geo.MarsProjection;
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
		return MarsCalendar.solarLongitude(clockTicks(level), RedPlanetConfig.server().startLs(), RedPlanetConfig.server().yearCompression());
	}

	public static double sunDistanceAu(Level level) {
		return MarsCalendar.sunDistanceAu(clockTicks(level), RedPlanetConfig.server().startLs(), RedPlanetConfig.server().yearCompression());
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

	/** Current atmospheric dust optical depth (clear sky ~0.5; storms raise it). */
	public static double dustTau(Level level) {
		return DustState.tau(level);
	}

	/** Ground temperature (K) at a position now. */
	public static double temperatureK(Level level, Vec3 pos) {
		return MarsClimate.surfaceTemperature(latitude(pos), solarLongitude(level), lmstFraction(level), sunDistanceAu(level),
			PlanetEnvironment.pressure(level, pos), dustTau(level));
	}

	public static double temperatureK(Level level, BlockPos pos) {
		return temperatureK(level, Vec3.atCenterOf(pos));
	}

	/** Holder for the dust optical depth; the dust-storm system (M3) updates it. */
	public static final class DustState {
		private static volatile double clearTau = 0.5;
		private static volatile double stormTau = 0.0;

		private DustState() {
		}

		public static double tau(Level level) {
			return clearTau + stormTau;
		}

		public static void setStormTau(double tau) {
			stormTau = Math.max(0.0, tau);
		}
	}
}
