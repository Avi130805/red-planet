package io.github.avi130805.redplanet.starship.geometry;

import java.util.EnumMap;

import io.github.avi130805.redplanet.starship.geometry.UvLayout.Region;
import io.github.avi130805.redplanet.starship.geometry.VehicleMesh.Joint;
import io.github.avi130805.redplanet.starship.geometry.VehicleMesh.PartMesh;

/**
 * Builds true-scale Starship and Super Heavy meshes. Dimensions follow the Block 2 vehicles flown in
 * 2025 (see docs/SCIENCE.md, "Starship"): 9 m diameter stainless-steel hulls, a 52.1 m ship with a tangent
 * ogive nose, four flaps, three sea-level Raptors and three Raptor Vacuums, and a 71 m booster with a
 * vented hot-staging ring, four grid fins and 33 Raptors in rings of 3, 10 and 20.
 *
 * <p>Meshes are cached per level of detail by callers; building one takes a few milliseconds.
 */
public final class StarshipGeometry {
	public static final double HULL_RADIUS = 4.5;

	public static final double SHIP_BARREL_HEIGHT = 34.4;
	public static final double SHIP_NOSE_HEIGHT = 17.7;
	public static final double SHIP_HEIGHT = SHIP_BARREL_HEIGHT + SHIP_NOSE_HEIGHT;
	/** Height of the crew cabin floor and window band (for the passenger view). */
	public static final double SHIP_CABIN_Y = 38.5;

	public static final double BOOSTER_BARREL_HEIGHT = 69.2;
	public static final double BOOSTER_RING_HEIGHT = 1.8;
	public static final double BOOSTER_HEIGHT = BOOSTER_BARREL_HEIGHT + BOOSTER_RING_HEIGHT;
	public static final double STACK_HEIGHT = BOOSTER_HEIGHT + SHIP_HEIGHT;

	/**
	 * Leg geometry. The legs telescope: stowed, they sit entirely inside the skirt's height (so they clear
	 * the booster when stacked); to deploy they slide down by {@link #LEG_EXTENSION} and then splay outward
	 * by {@link #LEG_DEPLOY_DEG} about the (lowered) hinge.
	 */
	public static final double LEG_PIVOT_Y = 6.8;
	public static final double LEG_LENGTH = 6.5;
	public static final double LEG_EXTENSION = 2.4;
	public static final double LEG_DEPLOY_DEG = 15.0;
	public static final double[] LEG_ANGLES_DEG = {0, 50, 130, 180, 230, 310};
	/** How far below the skirt bottom the feet reach when deployed (the hull rides this high when landed). */
	public static final double LANDED_SKIRT_HEIGHT = -(LEG_PIVOT_Y - LEG_EXTENSION - LEG_LENGTH * Math.cos(Math.toRadians(LEG_DEPLOY_DEG)));

	/** Sea-level Raptor positions (radius, angle) and dimensions. */
	public static final double SL_RING_RADIUS = 1.35;
	public static final double[] SL_ANGLES_DEG = {0, 120, 240};
	public static final double SL_EXIT_RADIUS = 0.65;
	public static final double SL_THROAT_Y = 1.25;
	public static final double SL_EXIT_Y = -0.45;
	/** Gimbal pivot above the throat. */
	public static final double SL_GIMBAL_Y = 2.6;

	public static final double VAC_RING_RADIUS = 3.2;
	public static final double[] VAC_ANGLES_DEG = {60, 180, 300};
	public static final double VAC_EXIT_RADIUS = 1.15;
	public static final double VAC_THROAT_Y = 2.55;
	public static final double VAC_EXIT_Y = -0.35;

	/** Booster engine rings: radius, count, and whether they gimbal. */
	public static final double[][] BOOSTER_RINGS = {{0.85, 3}, {2.45, 10}, {3.85, 20}};
	public static final double BOOSTER_ENGINE_EXIT_RADIUS = 0.58;
	public static final double BOOSTER_ENGINE_THROAT_Y = 0.9;
	public static final double BOOSTER_ENGINE_EXIT_Y = -1.0;

	public static final double[] GRID_FIN_ANGLES_DEG = {45, 135, 225, 315};
	public static final double GRID_FIN_Y = 64.4;
	public static final double GRID_FIN_HEIGHT = 3.6;
	public static final double GRID_FIN_SPAN = 4.9;

	/** Detail levels: hull facets, nose rings, engine facets. */
	public enum Lod {
		HIGH(64, 18, 16, 6, true),
		MEDIUM(32, 10, 10, 4, true),
		LOW(16, 6, 6, 2, false);

		final int hullSegments;
		final int noseRings;
		final int engineSegments;
		final int engineRings;
		/** Nozzle interiors and powerheads; only visible up close. */
		final boolean engineDetail;

		Lod(int hullSegments, int noseRings, int engineSegments, int engineRings, boolean engineDetail) {
			this.hullSegments = hullSegments;
			this.noseRings = noseRings;
			this.engineSegments = engineSegments;
			this.engineRings = engineRings;
			this.engineDetail = engineDetail;
		}
	}

	private StarshipGeometry() {
	}

	// ------------------------------------------------------------------------------------------------ ship

	public static VehicleMesh buildShip(Lod lod) {
		EnumMap<VehiclePart, PartMesh> parts = new EnumMap<>(VehiclePart.class);

		// Hull: barrel + tangent ogive nose, one surface of revolution.
		MeshBuilder hull = new MeshBuilder();
		int barrelRings = 4;
		int noseRings = lod.noseRings;
		double rho = (HULL_RADIUS * HULL_RADIUS + SHIP_NOSE_HEIGHT * SHIP_NOSE_HEIGHT) / (2.0 * HULL_RADIUS);
		double[] br = new double[barrelRings + 1];
		double[] by = new double[barrelRings + 1];
		double[] bt = new double[barrelRings + 1];
		for (int i = 0; i <= barrelRings; i++) {
			by[i] = SHIP_BARREL_HEIGHT * i / barrelRings;
			br[i] = HULL_RADIUS;
			bt[i] = 1.0 - by[i] / SHIP_BARREL_HEIGHT;
		}
		hull.lathe(br, by, bt, lod.hullSegments, Region.SHIP_BARREL, false, 0, 0, 0);

		// Nose: sample the ogive so rings are denser near the tip where curvature is highest.
		double[] nr = new double[noseRings + 1];
		double[] ny = new double[noseRings + 1];
		double[] nt = new double[noseRings + 1];
		double tipRadius = 0.35; // blunt tip
		double[] arc = new double[noseRings + 1];
		for (int i = 0; i <= noseRings; i++) {
			double f = i / (double) noseRings; // 0 at base, 1 at tip
			double x = SHIP_NOSE_HEIGHT * (1.0 - Math.pow(1.0 - f, 1.6)); // height above the nose base
			// Tangent ogive measured from its base: r(x) = sqrt(rho^2 - x^2) + R - rho.
			double r = Math.sqrt(Math.max(0.0, rho * rho - x * x)) + HULL_RADIUS - rho;
			if (i == noseRings) {
				r = 0.0;
			} else {
				r = Math.max(r, tipRadius * (1.0 - f));
			}
			nr[i] = r;
			ny[i] = SHIP_BARREL_HEIGHT + x;
			if (i > 0) {
				arc[i] = arc[i - 1] + Math.hypot(nr[i] - nr[i - 1], ny[i] - ny[i - 1]);
			}
		}
		double totalArc = arc[noseRings];
		for (int i = 0; i <= noseRings; i++) {
			nt[i] = 1.0 - arc[i] / totalArc; // t = 0 at the tip
		}
		hull.lathe(nr, ny, nt, lod.hullSegments, Region.SHIP_NOSE, false, 0, 0, 0);
		// Aft bulkhead seen from below, ring between the engine bays.
		hull.disc(HULL_RADIUS, 0.0, 0.0, lod.hullSegments, Region.SHIP_AFT_DISC, false, 0, 0);
		parts.put(VehiclePart.SHIP_HULL, new PartMesh(hull.toArray(), Joint.FIXED));

		// Aft flaps: hinged on the hull sides (+X right, -X left), trapezoidal plates.
		double[][] aftOutline = {{0.0, 1.2}, {0.0, 13.5}, {4.3, 12.1}, {4.3, 2.5}};
		addFlap(parts, VehiclePart.SHIP_AFT_FLAP_RIGHT, aftOutline, 90.0, 0.0, 0.0, HULL_RADIUS, HULL_RADIUS, 0.6, 0.28,
			Region.SHIP_AFT_FLAP_WINDWARD, Region.SHIP_AFT_FLAP_LEEWARD, Region.SHIP_AFT_FLAP_EDGE);
		addFlap(parts, VehiclePart.SHIP_AFT_FLAP_LEFT, aftOutline, -90.0, 0.0, 0.0, HULL_RADIUS, HULL_RADIUS, 0.6, 0.28,
			Region.SHIP_AFT_FLAP_WINDWARD, Region.SHIP_AFT_FLAP_LEEWARD, Region.SHIP_AFT_FLAP_EDGE);

		// Fore flaps: on the nose, shifted toward the leeward side (as on Block 2 ships); the hinge line
		// follows the ogive surface, so it leans inward.
		double foreBottom = SHIP_BARREL_HEIGHT + 2.0;
		double foreTop = SHIP_BARREL_HEIGHT + 9.2;
		double rBottom = Math.sqrt(rho * rho - 2.0 * 2.0) + HULL_RADIUS - rho;
		double rTop = Math.sqrt(rho * rho - 9.2 * 9.2) + HULL_RADIUS - rho;
		double hingeLen = Math.hypot(foreTop - foreBottom, rBottom - rTop);
		double[][] foreOutline = {{0.0, 0.0}, {0.0, hingeLen}, {2.6, hingeLen - 1.6}, {2.6, 1.0}};
		addFlap(parts, VehiclePart.SHIP_FORE_FLAP_RIGHT, foreOutline, 115.0, foreBottom, foreTop, rBottom, rTop, 0.45, 0.22,
			Region.SHIP_FORE_FLAP_WINDWARD, Region.SHIP_FORE_FLAP_LEEWARD, Region.SHIP_FORE_FLAP_EDGE);
		addFlap(parts, VehiclePart.SHIP_FORE_FLAP_LEFT, foreOutline, -115.0, foreBottom, foreTop, rBottom, rTop, 0.45, 0.22,
			Region.SHIP_FORE_FLAP_WINDWARD, Region.SHIP_FORE_FLAP_LEEWARD, Region.SHIP_FORE_FLAP_EDGE);

		// Sea-level Raptors: gimballed individually.
		for (int i = 0; i < 3; i++) {
			double a = Math.toRadians(SL_ANGLES_DEG[i]);
			double cx = SL_RING_RADIUS * Math.sin(a);
			double cz = SL_RING_RADIUS * Math.cos(a);
			MeshBuilder e = new MeshBuilder();
			engineBell(e, lod, cx, cz, SL_THROAT_Y, SL_EXIT_Y, 0.24, SL_EXIT_RADIUS, 0.62, SL_GIMBAL_Y + 0.5, lod.engineSegments,
				Region.SHIP_ENGINE_SL_OUTER, Region.SHIP_ENGINE_SL_INNER);
			parts.put(VehiclePart.shipSeaLevelEngine(i), new PartMesh(e.toArray(),
				new Joint((float) cx, (float) SL_GIMBAL_Y, (float) cz, 1, 0, 0)));
		}
		// Raptor Vacuums: fixed.
		MeshBuilder vac = new MeshBuilder();
		for (int i = 0; i < 3; i++) {
			double a = Math.toRadians(VAC_ANGLES_DEG[i]);
			engineBell(vac, lod, VAC_RING_RADIUS * Math.sin(a), VAC_RING_RADIUS * Math.cos(a), VAC_THROAT_Y, VAC_EXIT_Y, 0.26,
				VAC_EXIT_RADIUS, 0.62, VAC_THROAT_Y + 1.4, lod.engineSegments + 4, Region.SHIP_ENGINE_VAC_OUTER, Region.SHIP_ENGINE_VAC_INNER);
		}
		parts.put(VehiclePart.SHIP_ENGINES_VAC, new PartMesh(vac.toArray(), Joint.FIXED));

		// Landing legs: struts folded flush against the skirt; they rotate outward about a tangential hinge.
		for (int i = 0; i < 6; i++) {
			double a = Math.toRadians(LEG_ANGLES_DEG[i]);
			double sin = Math.sin(a);
			double cos = Math.cos(a);
			double pr = HULL_RADIUS + 0.12;
			double[] origin = {pr * sin, LEG_PIVOT_Y, pr * cos};
			double[] radial = {sin, 0, cos};
			double[] down = {0, -1, 0};
			double[] tangent = {cos, 0, -sin};
			MeshBuilder leg = new MeshBuilder();
			// The strut: a thin plate whose span runs downward along the hull; "normal" points radially out.
			double[][] outline = {{0.0, -0.35}, {0.0, 0.35}, {LEG_LENGTH, 0.3}, {LEG_LENGTH, -0.3}};
			leg.plate(outline, 0.32, 0.24, LEG_LENGTH, new double[]{-0.35, 0.35}, origin, down, tangent, radial,
				Region.SHIP_LEG, Region.SHIP_LEG, Region.SHIP_LEG);
			// Foot pad at the strut end.
			double[] foot = {origin[0], origin[1] - LEG_LENGTH, origin[2]};
			double[][] padOutline = {{-0.55, -0.55}, {-0.55, 0.55}, {0.55, 0.55}, {0.55, -0.55}};
			leg.plate(padOutline, 0.18, 0.18, 1.1, new double[]{-0.55, 0.55}, foot, radial, tangent, new double[]{0, 1, 0},
				Region.SHIP_LEG_FOOT, Region.SHIP_LEG_FOOT, Region.SHIP_LEG_FOOT);
			// Hinge axis: rotating by +angle about this axis swings the foot outward.
			parts.put(VehiclePart.shipLeg(i), new PartMesh(leg.toArray(),
				new Joint((float) origin[0], (float) origin[1], (float) origin[2], (float) -cos, 0, (float) sin)));
		}

		return new VehicleMesh(parts, (float) SHIP_HEIGHT, (float) HULL_RADIUS);
	}

	/**
	 * A flap plate hinged on the hull at polar angle {@code hingeDeg} (0 = windward centre line, +90 = right).
	 * The hinge runs from (yBottom, rBottom) to (yTop, rTop); for vertical hinges pass yBottom = yTop = 0
	 * and the outline's own heights are used directly.
	 */
	private static void addFlap(EnumMap<VehiclePart, PartMesh> parts, VehiclePart part, double[][] outline, double hingeDeg,
			double yBottom, double yTop, double rBottom, double rTop, double thickRoot, double thickTip,
			Region windward, Region leeward, Region edge) {
		double a = Math.toRadians(hingeDeg);
		double sin = Math.sin(a);
		double cos = Math.cos(a);
		double[] radial = {sin, 0, cos};
		double[] origin;
		double[] heightDir;
		if (yTop > yBottom) {
			origin = new double[]{rBottom * sin, yBottom, rBottom * cos};
			double dy = yTop - yBottom;
			double dr = rTop - rBottom;
			double l = Math.hypot(dy, dr);
			heightDir = new double[]{dr / l * sin, dy / l, dr / l * cos};
		} else {
			origin = new double[]{rBottom * sin, 0, rBottom * cos};
			heightDir = new double[]{0, 1, 0};
		}
		// Plate normal: tangential, pointing toward increasing angle.
		double[] normal = {cos, 0, -sin};
		// Which face looks windward (+Z)? That face gets the heat-shield tiles.
		boolean plusIsWindward = normal[2] > 0;
		double maxSpan = 0;
		double hMin = Double.MAX_VALUE;
		double hMax = -Double.MAX_VALUE;
		for (double[] p : outline) {
			maxSpan = Math.max(maxSpan, p[0]);
			hMin = Math.min(hMin, p[1]);
			hMax = Math.max(hMax, p[1]);
		}
		MeshBuilder b = new MeshBuilder();
		b.plate(outline, thickRoot, thickTip, maxSpan, new double[]{hMin, hMax}, origin, radial, heightDir, normal,
			plusIsWindward ? windward : leeward, plusIsWindward ? leeward : windward, edge);
		// Positive joint rotation tucks the flap toward the leeward side (-Z) for both sides.
		float axisSign = sin >= 0 ? 1f : -1f;
		parts.put(part, new PartMesh(b.toArray(), new Joint((float) origin[0], (float) origin[1], (float) origin[2],
			(float) (heightDir[0] * axisSign), (float) (heightDir[1] * axisSign), (float) (heightDir[2] * axisSign))));
	}

	/**
	 * A Raptor: powerhead cylinder above the throat plus a bell nozzle (outside and inside surfaces).
	 */
	private static void engineBell(MeshBuilder b, Lod lod, double cx, double cz, double throatY, double exitY, double throatR, double exitR,
			double powerheadR, double powerheadTopY, int segments, Region outer, Region inner) {
		int rings = lod.engineRings;
		double[] r = new double[rings + 1];
		double[] y = new double[rings + 1];
		double[] t = new double[rings + 1];
		for (int i = 0; i <= rings; i++) {
			double s = i / (double) rings; // 0 at exit, 1 at throat (bottom to top for outward normals)
			double flare = 1.0 - Math.pow(s, 0.55); // bell: wide quickly, straightening toward the exit
			r[i] = throatR + (exitR - throatR) * flare;
			y[i] = exitY + (throatY - exitY) * s;
			t[i] = 1.0 - s;
		}
		b.lathe(r, y, t, segments, outer, false, cx, cz, 0);
		if (!lod.engineDetail) {
			return;
		}
		b.lathe(r, y, t, segments, inner, true, cx, cz, 0);
		// Powerhead (turbopumps, preburners) above the throat.
		double[] pr = {throatR, powerheadR, powerheadR, powerheadR * 0.6};
		double[] py = {throatY, throatY + 0.25, powerheadTopY - 0.2, powerheadTopY};
		double[] pt = {1.0, 0.8, 0.2, 0.0};
		b.lathe(pr, py, pt, Math.max(6, segments / 2), outer, false, cx, cz, 0);
	}

	// --------------------------------------------------------------------------------------------- booster

	public static VehicleMesh buildBooster(Lod lod) {
		EnumMap<VehiclePart, PartMesh> parts = new EnumMap<>(VehiclePart.class);

		MeshBuilder hull = new MeshBuilder();
		int rings = 6;
		double[] r = new double[rings + 1];
		double[] y = new double[rings + 1];
		double[] t = new double[rings + 1];
		for (int i = 0; i <= rings; i++) {
			y[i] = BOOSTER_BARREL_HEIGHT * i / rings;
			r[i] = HULL_RADIUS;
			t[i] = 1.0 - y[i] / BOOSTER_BARREL_HEIGHT;
		}
		hull.lathe(r, y, t, lod.hullSegments, Region.BOOSTER_BARREL, false, 0, 0, 0);
		hull.disc(HULL_RADIUS, 0.0, 0.0, lod.hullSegments, Region.BOOSTER_AFT_DISC, false, 0, 0);
		parts.put(VehiclePart.BOOSTER_HULL, new PartMesh(hull.toArray(), Joint.FIXED));

		// Hot-staging ring: a vented interstage that stays with the booster.
		MeshBuilder ring = new MeshBuilder();
		ring.lathe(new double[]{HULL_RADIUS, HULL_RADIUS}, new double[]{BOOSTER_BARREL_HEIGHT, BOOSTER_HEIGHT}, new double[]{1, 0},
			lod.hullSegments, Region.BOOSTER_HOT_STAGE_RING, false, 0, 0, 0);
		ring.disc(HULL_RADIUS, 0.0, BOOSTER_HEIGHT, lod.hullSegments, Region.BOOSTER_HOT_STAGE_RING_TOP, true, 0, 0);
		parts.put(VehiclePart.BOOSTER_HOT_STAGE_RING, new PartMesh(ring.toArray(), Joint.FIXED));

		// Grid fins: lattice plates sticking straight out; they rotate about their radial axis to steer.
		for (int i = 0; i < 4; i++) {
			double a = Math.toRadians(GRID_FIN_ANGLES_DEG[i]);
			double sin = Math.sin(a);
			double cos = Math.cos(a);
			double[] radial = {sin, 0, cos};
			double[] tangent = {cos, 0, -sin};
			double[] origin = {HULL_RADIUS * sin, GRID_FIN_Y + GRID_FIN_HEIGHT / 2.0, HULL_RADIUS * cos};
			MeshBuilder fin = new MeshBuilder();
			double h = GRID_FIN_HEIGHT / 2.0;
			double[][] outline = {{0.0, -h * 0.85}, {0.0, h * 0.85}, {GRID_FIN_SPAN, h}, {GRID_FIN_SPAN, -h}};
			fin.plate(outline, 0.7, 0.7, GRID_FIN_SPAN, new double[]{-h, h}, origin, radial, tangent,
				new double[]{0, 1, 0}, Region.BOOSTER_GRID_FIN, Region.BOOSTER_GRID_FIN, Region.BOOSTER_GRID_FIN_EDGE);
			parts.put(VehiclePart.gridFin(i), new PartMesh(fin.toArray(),
				new Joint((float) origin[0], (float) origin[1], (float) origin[2], (float) sin, 0, (float) cos)));
		}

		// 33 Raptors: the inner 13 gimbal (one part, steered together), the outer 20 are fixed.
		MeshBuilder center = new MeshBuilder();
		MeshBuilder outer = new MeshBuilder();
		for (int ringIndex = 0; ringIndex < BOOSTER_RINGS.length; ringIndex++) {
			double rr = BOOSTER_RINGS[ringIndex][0];
			int count = (int) BOOSTER_RINGS[ringIndex][1];
			for (int k = 0; k < count; k++) {
				double a = 2.0 * Math.PI * (k + (ringIndex == 1 ? 0.5 : 0.0)) / count;
				MeshBuilder target = ringIndex < 2 ? center : outer;
				engineBell(target, lod, rr * Math.sin(a), rr * Math.cos(a), BOOSTER_ENGINE_THROAT_Y, BOOSTER_ENGINE_EXIT_Y, 0.22,
					BOOSTER_ENGINE_EXIT_RADIUS, 0.5, BOOSTER_ENGINE_THROAT_Y + 0.6, Math.max(6, lod.engineSegments - 4),
					Region.BOOSTER_ENGINE_OUTER, Region.BOOSTER_ENGINE_INNER);
			}
		}
		parts.put(VehiclePart.BOOSTER_ENGINES_CENTER, new PartMesh(center.toArray(), new Joint(0, (float) (BOOSTER_ENGINE_THROAT_Y + 1.5), 0, 1, 0, 0)));
		parts.put(VehiclePart.BOOSTER_ENGINES_OUTER, new PartMesh(outer.toArray(), Joint.FIXED));

		return new VehicleMesh(parts, (float) BOOSTER_HEIGHT, (float) HULL_RADIUS);
	}

	/** Positions (x, z) of all 33 booster engines in ring order: 3 centre, 10 middle, 20 outer. */
	public static double[][] boosterEnginePositions() {
		double[][] out = new double[33][];
		int n = 0;
		for (int ringIndex = 0; ringIndex < BOOSTER_RINGS.length; ringIndex++) {
			double rr = BOOSTER_RINGS[ringIndex][0];
			int count = (int) BOOSTER_RINGS[ringIndex][1];
			for (int k = 0; k < count; k++) {
				double a = 2.0 * Math.PI * (k + (ringIndex == 1 ? 0.5 : 0.0)) / count;
				out[n++] = new double[]{rr * Math.sin(a), rr * Math.cos(a)};
			}
		}
		return out;
	}

	/** Positions (x, z) of the 6 ship engines: 3 sea-level then 3 vacuum. */
	public static double[][] shipEnginePositions() {
		double[][] out = new double[6][];
		for (int i = 0; i < 3; i++) {
			double a = Math.toRadians(SL_ANGLES_DEG[i]);
			out[i] = new double[]{SL_RING_RADIUS * Math.sin(a), SL_RING_RADIUS * Math.cos(a)};
			double b = Math.toRadians(VAC_ANGLES_DEG[i]);
			out[3 + i] = new double[]{VAC_RING_RADIUS * Math.sin(b), VAC_RING_RADIUS * Math.cos(b)};
		}
		return out;
	}
}
