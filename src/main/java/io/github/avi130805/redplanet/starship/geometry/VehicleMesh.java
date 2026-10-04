package io.github.avi130805.redplanet.starship.geometry;

import java.util.EnumMap;
import java.util.Map;

/**
 * A procedurally built vehicle mesh, split into animatable parts.
 *
 * <p>Units are metres (= blocks). The body frame has its origin at the bottom centre of the hull (the
 * bottom edge of the aft skirt), +Y along the vehicle axis toward the nose, +Z toward the windward
 * "belly" (the heat-shield side) and +X to the vehicle's right.
 *
 * <p>Each part stores quads as interleaved floats: per vertex x, y, z, nx, ny, nz, u, v (8 floats),
 * 4 vertices per quad. UVs are normalized to the part's texture (see {@link UvLayout}).
 */
public final class VehicleMesh {
	public static final int FLOATS_PER_VERTEX = 8;
	public static final int FLOATS_PER_QUAD = FLOATS_PER_VERTEX * 4;

	/** A part and the joint it rotates about when animated. */
	public record PartMesh(float[] quads, Joint joint) {
		public int quadCount() {
			return this.quads.length / FLOATS_PER_QUAD;
		}
	}

	/**
	 * Rotation joint: pivot point and unit axis in the body frame. Parts without animation use
	 * {@link #FIXED}.
	 */
	public record Joint(float px, float py, float pz, float ax, float ay, float az) {
		public static final Joint FIXED = new Joint(0, 0, 0, 0, 1, 0);
	}

	private final Map<VehiclePart, PartMesh> parts;
	private final float height;
	private final float radius;

	VehicleMesh(EnumMap<VehiclePart, PartMesh> parts, float height, float radius) {
		this.parts = parts;
		this.height = height;
		this.radius = radius;
	}

	public Map<VehiclePart, PartMesh> parts() {
		return this.parts;
	}

	public PartMesh part(VehiclePart part) {
		return this.parts.get(part);
	}

	/** Total height of the hull from skirt bottom to nose tip (or booster top), metres. */
	public float height() {
		return this.height;
	}

	public float radius() {
		return this.radius;
	}

	public int totalQuads() {
		int n = 0;
		for (PartMesh p : this.parts.values()) {
			n += p.quadCount();
		}
		return n;
	}
}
