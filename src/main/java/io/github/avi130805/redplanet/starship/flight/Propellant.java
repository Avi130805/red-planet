package io.github.avi130805.redplanet.starship.flight;

/**
 * Propellant capacities of the V3 vehicles and the methalox mixture (docs/SCIENCE.md, sections 16 and 18). Telemetry
 * carries each tank's fill as a fraction; these turn it into tonnes.
 */
public final class Propellant {
	/** Total propellant, tonnes. */
	public static final double SHIP_TONNES = 1600.0;
	public static final double BOOSTER_TONNES = 3650.0;
	/** Oxidiser-to-fuel mass ratio of a Raptor. */
	public static final double MIXTURE_RATIO = 3.5;

	private Propellant() {
	}

	/** Tonnes aboard for tank fills (0..1) of liquid oxygen and methane, out of a vehicle's {@code capacity}. */
	public static double tonnes(double capacity, double lox, double ch4) {
		double loxShare = MIXTURE_RATIO / (MIXTURE_RATIO + 1.0);
		return capacity * (loxShare * clamp(lox) + (1.0 - loxShare) * clamp(ch4));
	}

	private static double clamp(double fill) {
		return Math.max(0.0, Math.min(1.0, fill));
	}
}
