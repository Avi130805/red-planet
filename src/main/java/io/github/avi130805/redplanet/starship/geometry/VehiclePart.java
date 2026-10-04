package io.github.avi130805.redplanet.starship.geometry;

/**
 * Animatable parts of the Starship and Super Heavy meshes.
 */
public enum VehiclePart {
	// Starship (upper stage)
	SHIP_HULL,
	/** The crew cabin interior (inward-facing wall with window openings, deck, ceiling, couches). */
	SHIP_CABIN,
	SHIP_FORE_FLAP_RIGHT,
	SHIP_FORE_FLAP_LEFT,
	SHIP_AFT_FLAP_RIGHT,
	SHIP_AFT_FLAP_LEFT,
	SHIP_ENGINE_SL_0,
	SHIP_ENGINE_SL_1,
	SHIP_ENGINE_SL_2,
	SHIP_ENGINES_VAC,
	SHIP_LEG_0,
	SHIP_LEG_1,
	SHIP_LEG_2,
	SHIP_LEG_3,
	SHIP_LEG_4,
	SHIP_LEG_5,
	// Super Heavy (booster)
	BOOSTER_HULL,
	BOOSTER_HOT_STAGE_RING,
	BOOSTER_GRID_FIN_0,
	BOOSTER_GRID_FIN_1,
	BOOSTER_GRID_FIN_2,
	BOOSTER_ENGINES_CENTER,
	BOOSTER_ENGINES_OUTER;

	public static VehiclePart shipSeaLevelEngine(int i) {
		return switch (i) {
			case 0 -> SHIP_ENGINE_SL_0;
			case 1 -> SHIP_ENGINE_SL_1;
			default -> SHIP_ENGINE_SL_2;
		};
	}

	public static VehiclePart shipLeg(int i) {
		return values()[SHIP_LEG_0.ordinal() + i];
	}

	public static VehiclePart gridFin(int i) {
		return values()[BOOSTER_GRID_FIN_0.ordinal() + i];
	}
}
