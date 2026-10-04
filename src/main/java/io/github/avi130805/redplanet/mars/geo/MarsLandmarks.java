package io.github.avi130805.redplanet.mars.geo;

import java.util.List;
import java.util.Optional;

/**
 * Named places on Mars, in planetocentric latitude and east longitude: the great landforms and the landing sites of
 * real missions. Coordinates from NASA/JPL mission pages and the IAU/USGS Gazetteer of Planetary Nomenclature
 * (docs/SCIENCE.md, section 20).
 */
public final class MarsLandmarks {
	public enum Kind {
		LANDFORM,
		LANDING_SITE
	}

	public record Landmark(String id, String name, double lat, double lon, Kind kind) {
		/** Block x of this landmark on the Mars map. */
		public double x() {
			return MarsProjection.xOf(this.lon);
		}

		/** Block z of this landmark on the Mars map. */
		public double z() {
			return MarsProjection.zOf(this.lat);
		}
	}

	public static final List<Landmark> ALL = List.of(
		new Landmark("olympus_mons", "Olympus Mons", 18.65, 226.20, Kind.LANDFORM),
		new Landmark("ascraeus_mons", "Ascraeus Mons", 11.92, 255.92, Kind.LANDFORM),
		new Landmark("pavonis_mons", "Pavonis Mons", 1.48, 247.04, Kind.LANDFORM),
		new Landmark("arsia_mons", "Arsia Mons", -8.26, 239.91, Kind.LANDFORM),
		new Landmark("alba_mons", "Alba Mons", 40.47, 250.40, Kind.LANDFORM),
		new Landmark("elysium_mons", "Elysium Mons", 24.80, 146.90, Kind.LANDFORM),
		new Landmark("valles_marineris", "Valles Marineris (Melas Chasma)", -10.20, 287.20, Kind.LANDFORM),
		new Landmark("candor_chasma", "Candor Chasma", -6.60, 289.10, Kind.LANDFORM),
		new Landmark("noctis_labyrinthus", "Noctis Labyrinthus", -6.90, 257.20, Kind.LANDFORM),
		new Landmark("hellas_planitia", "Hellas Planitia", -42.40, 70.50, Kind.LANDFORM),
		new Landmark("argyre_planitia", "Argyre Planitia", -49.70, 316.00, Kind.LANDFORM),
		new Landmark("isidis_planitia", "Isidis Planitia", 12.90, 87.00, Kind.LANDFORM),
		new Landmark("gale_crater", "Gale crater (Aeolis Mons)", -5.37, 137.81, Kind.LANDFORM),
		new Landmark("jezero_crater", "Jezero crater", 18.38, 77.58, Kind.LANDFORM),
		new Landmark("cydonia", "Cydonia Mensae (the Face)", 40.75, 350.54, Kind.LANDFORM),
		new Landmark("planum_boreum", "Planum Boreum (north polar cap)", 84.00, 0.00, Kind.LANDFORM),
		new Landmark("planum_australe", "Planum Australe (south polar cap)", -84.00, 180.00, Kind.LANDFORM),
		new Landmark("viking_1", "Viking 1 (Chryse Planitia)", 22.27, 312.05, Kind.LANDING_SITE),
		new Landmark("viking_2", "Viking 2 (Utopia Planitia)", 47.64, 134.29, Kind.LANDING_SITE),
		new Landmark("pathfinder", "Mars Pathfinder (Ares Vallis)", 19.13, 326.78, Kind.LANDING_SITE),
		new Landmark("spirit", "Spirit (Gusev crater)", -14.57, 175.47, Kind.LANDING_SITE),
		new Landmark("opportunity", "Opportunity (Meridiani Planum)", -1.95, 354.47, Kind.LANDING_SITE),
		new Landmark("phoenix", "Phoenix (Vastitas Borealis)", 68.22, 234.25, Kind.LANDING_SITE),
		new Landmark("curiosity", "Curiosity (Bradbury Landing, Gale)", -4.59, 137.44, Kind.LANDING_SITE),
		new Landmark("insight", "InSight (Elysium Planitia)", 4.50, 135.62, Kind.LANDING_SITE),
		new Landmark("perseverance", "Perseverance (Octavia E. Butler Landing, Jezero)", 18.44, 77.45, Kind.LANDING_SITE),
		new Landmark("zhurong", "Zhurong (Utopia Planitia)", 25.07, 109.93, Kind.LANDING_SITE)
	);

	private MarsLandmarks() {
	}

	public static Optional<Landmark> byId(String id) {
		return ALL.stream().filter(l -> l.id().equals(id)).findFirst();
	}
}
