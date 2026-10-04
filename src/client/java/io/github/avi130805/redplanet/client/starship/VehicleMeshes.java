package io.github.avi130805.redplanet.client.starship;

import java.util.EnumMap;
import java.util.Map;

import io.github.avi130805.redplanet.starship.geometry.StarshipGeometry;
import io.github.avi130805.redplanet.starship.geometry.VehicleMesh;

/** The vehicle meshes at each level of detail, built once on first use (a few milliseconds each). */
public final class VehicleMeshes {
	private static final Map<StarshipGeometry.Lod, VehicleMesh> SHIP = new EnumMap<>(StarshipGeometry.Lod.class);
	private static final Map<StarshipGeometry.Lod, VehicleMesh> BOOSTER = new EnumMap<>(StarshipGeometry.Lod.class);

	private VehicleMeshes() {
	}

	public static synchronized VehicleMesh ship(StarshipGeometry.Lod lod) {
		return SHIP.computeIfAbsent(lod, StarshipGeometry::buildShip);
	}

	public static synchronized VehicleMesh booster(StarshipGeometry.Lod lod) {
		return BOOSTER.computeIfAbsent(lod, StarshipGeometry::buildBooster);
	}

	/** Detail for a squared distance in blocks (after any far-impostor scaling, apparent size is what matters). */
	public static StarshipGeometry.Lod lodFor(double distanceSq) {
		if (distanceSq < 128.0 * 128.0) {
			return StarshipGeometry.Lod.HIGH;
		}
		return distanceSq < 400.0 * 400.0 ? StarshipGeometry.Lod.MEDIUM : StarshipGeometry.Lod.LOW;
	}
}
