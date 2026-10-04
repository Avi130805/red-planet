package io.github.avi130805.redplanet.environment;

/**
 * Pure formulas for the atmosphere and the drag model, shared by the mixins and unit-tested in isolation.
 */
public final class AtmosphereModel {
	private AtmosphereModel() {
	}

	/**
	 * Barometric factor exp(-dz / H) for a height above the datum.
	 *
	 * @param blocksAboveDatum block Y minus datum Y (negative below the datum)
	 * @param metresPerBlock vertical scale
	 * @param scaleHeightMetres atmospheric scale height
	 */
	public static double barometricFactor(double blocksAboveDatum, double metresPerBlock, double scaleHeightMetres) {
		return Math.exp(-blocksAboveDatum * metresPerBlock / scaleHeightMetres);
	}

	/**
	 * The effective drag scale for Minecraft's linear (per-tick) drag.
	 *
	 * <p>Real drag is quadratic in speed, so terminal speed scales as sqrt(g / rho). Minecraft's drag is linear: each
	 * tick velocity is multiplied by d, and the terminal speed is g d / (1 - d). Scaling the per-tick loss (1 - d) by
	 * K = sqrt(rho_rel * g_rel) makes the linear model reproduce the real ratio of terminal speeds (Mars:
	 * sqrt(0.01135 * 0.3794) = 0.066, so a falling player's terminal speed is ~23 blocks/tick instead of 3.9). K is
	 * exactly 1 on Earth, so vanilla behaviour there is untouched. See docs/SCIENCE.md, section 4.
	 */
	public static double dragScale(double densityRelative, double gravityRelative) {
		if (densityRelative >= 0.9999 && gravityRelative >= 0.9999 && densityRelative <= 1.0001 && gravityRelative <= 1.0001) {
			return 1.0;
		}
		return Math.sqrt(Math.max(0.0, densityRelative * gravityRelative));
	}

	/** Applies a drag scale to a per-tick velocity multiplier d (e.g. 0.98 becomes 1 - 0.02 * scale). */
	public static float scaleDrag(float multiplier, double scale) {
		return (float) (1.0 - (1.0 - multiplier) * scale);
	}

	/**
	 * Fraction of the vanilla elytra aerodynamics that applies: aerodynamic forces scale with dynamic pressure
	 * (rho * v^2), calibrated so Earth (rho_rel = 1) is always fully vanilla. On Mars the elytra only bites at
	 * speeds above ~14 blocks/tick.
	 */
	public static double elytraFactor(double densityRelative, double speedBlocksPerTick) {
		double q = Math.max(1.0, (speedBlocksPerTick / 1.5) * (speedBlocksPerTick / 1.5));
		return Math.min(1.0, densityRelative * q);
	}
}
