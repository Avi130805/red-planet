package io.github.avi130805.redplanet.client.starship;

import com.mojang.blaze3d.vertex.VertexConsumer;

import org.joml.Matrix4f;

/**
 * Raptor exhaust as additive light: a bright core cone at the nozzle, a longer outer flame, and Mach diamonds while the
 * air is thick. In thin air (high altitude, or anywhere on Mars) the plume balloons into a wide, faint glow, as real
 * plumes do once the ambient pressure falls far below the nozzle's exit pressure.
 *
 * <p>Methalox burns nearly clean: the plume is a pale orange-white near the nozzle, fading to violet at the edges.
 * Geometry is in the vehicle's body frame (the nozzle exit at {@code y}, the plume running toward -Y).
 */
final class PlumeEmitter {
	private static final int SEGMENTS = 8;
	private static final float[] CORE = {1.0F, 0.86F, 0.62F};
	private static final float[] FLAME = {1.0F, 0.46F, 0.18F};
	private static final float[] HALO = {0.62F, 0.34F, 0.95F};
	private static final float[] DIAMOND = {1.0F, 0.95F, 0.85F};

	private PlumeEmitter() {
	}

	/**
	 * One engine's plume.
	 *
	 * @param exitRadius nozzle exit radius (m)
	 * @param intensity 0..1 (throttle and flicker)
	 * @param thinAir near-vacuum expansion
	 */
	static void engine(Matrix4f m, VertexConsumer buffer, float cx, float y, float cz, float exitRadius, float intensity, boolean thinAir,
			float time, int seed) {
		float flicker = 0.9F + 0.1F * (float) Math.sin(time * 37.0F + seed * 1.7F);
		float a = intensity * flicker;
		if (thinAir) {
			cone(m, buffer, cx, y, cz, exitRadius * 0.8F, exitRadius * 1.6F, exitRadius * 6.0F, CORE, 0.85F * a, 0.0F);
			cone(m, buffer, cx, y, cz, exitRadius * 1.0F, exitRadius * 9.0F, exitRadius * 34.0F, HALO, 0.16F * a, 0.0F);
			return;
		}
		cone(m, buffer, cx, y, cz, exitRadius * 0.85F, exitRadius * 0.55F, exitRadius * 7.0F, CORE, 0.9F * a, 0.0F);
		cone(m, buffer, cx, y, cz, exitRadius * 0.95F, exitRadius * 1.5F, exitRadius * 20.0F, FLAME, 0.42F * a, 0.0F);
		cone(m, buffer, cx, y, cz, exitRadius * 1.1F, exitRadius * 2.6F, exitRadius * 30.0F, HALO, 0.10F * a, 0.0F);
		// Mach diamonds: the standing shocks of an over-expanded jet, spaced about one exit diameter apart.
		for (int k = 1; k <= 4; k++) {
			float d = exitRadius * (1.4F + 2.1F * k);
			float size = exitRadius * (0.55F - 0.08F * k);
			diamond(m, buffer, cx, y - d, cz, size, DIAMOND, (0.75F - 0.13F * k) * a);
		}
	}

	/** The merged glow of many engines burning together (booster at liftoff). */
	static void cluster(Matrix4f m, VertexConsumer buffer, float y, float radius, float intensity, boolean thinAir) {
		if (thinAir) {
			cone(m, buffer, 0.0F, y, 0.0F, radius, radius * 5.0F, radius * 18.0F, HALO, 0.10F * intensity, 0.0F);
		} else {
			cone(m, buffer, 0.0F, y, 0.0F, radius, radius * 1.6F, radius * 12.0F, FLAME, 0.30F * intensity, 0.0F);
			cone(m, buffer, 0.0F, y, 0.0F, radius * 1.2F, radius * 2.4F, radius * 16.0F, HALO, 0.08F * intensity, 0.0F);
		}
	}

	/** The glowing sheath of entry plasma around the windward side and the flap edges, as a bright skin over the belly. */
	static void plasma(Matrix4f m, VertexConsumer buffer, float hullRadius, float height, float intensity, float time) {
		float[] color = {1.0F, 0.42F + 0.08F * (float) Math.sin(time * 5.0F), 0.62F};
		int rings = 6;
		float a = 0.35F * intensity;
		for (int i = 0; i < rings; i++) {
			float y0 = height * i / rings;
			float y1 = height * (i + 1) / rings;
			for (int j = -4; j < 4; j++) {
				// Windward half (+Z), standing off the hull by a metre or two (the bow shock layer).
				double t0 = Math.PI * j / 8.0;
				double t1 = Math.PI * (j + 1) / 8.0;
				float r = hullRadius + 1.2F + 0.4F * (float) Math.sin(time * 9.0F + i + j);
				float x0 = (float) (r * Math.sin(t0));
				float z0 = (float) (r * Math.cos(t0));
				float x1 = (float) (r * Math.sin(t1));
				float z1 = (float) (r * Math.cos(t1));
				float edge = 1.0F - Math.abs(j + 0.5F) / 4.0F;
				float alpha = a * (0.4F + 0.6F * edge);
				vertex(m, buffer, x0, y0, z0, color, alpha);
				vertex(m, buffer, x1, y0, z1, color, alpha);
				vertex(m, buffer, x1, y1, z1, color, alpha);
				vertex(m, buffer, x0, y1, z0, color, alpha);
			}
		}
	}

	/** The hot-staging burst: flame jetting radially out of the vented interstage ring, fading over a few seconds. */
	static void stagingFlash(Matrix4f m, VertexConsumer buffer, float hullRadius, float ringBottom, float ringHeight, float intensity, float time) {
		float[] color = {1.0F, 0.62F, 0.3F};
		int vents = 24;
		float y0 = ringBottom;
		float y1 = ringBottom + ringHeight;
		float reach = hullRadius + 6.0F + 6.0F * (1.0F - intensity);
		for (int i = 0; i < vents; i++) {
			double a0 = 2.0 * Math.PI * (i + 0.15) / vents;
			double a1 = 2.0 * Math.PI * (i + 0.85) / vents;
			float flicker = 0.75F + 0.25F * (float) Math.sin(time * 41.0F + i * 2.3F);
			float alpha = 0.8F * intensity * flicker;
			float s0 = (float) Math.sin(a0);
			float c0 = (float) Math.cos(a0);
			float s1 = (float) Math.sin(a1);
			float c1 = (float) Math.cos(a1);
			// A flat sheet of flame per vent, from the hull out to {@code reach}, top and bottom fading.
			vertex(m, buffer, hullRadius * s0, y0, hullRadius * c0, color, alpha);
			vertex(m, buffer, hullRadius * s1, y0, hullRadius * c1, color, alpha);
			vertex(m, buffer, reach * s1, y0 - 1.5F, reach * c1, color, 0.0F);
			vertex(m, buffer, reach * s0, y0 - 1.5F, reach * c0, color, 0.0F);
			vertex(m, buffer, hullRadius * s0, y1, hullRadius * c0, color, alpha);
			vertex(m, buffer, reach * s0, y1 + 1.5F, reach * c0, color, 0.0F);
			vertex(m, buffer, reach * s1, y1 + 1.5F, reach * c1, color, 0.0F);
			vertex(m, buffer, hullRadius * s1, y1, hullRadius * c1, color, alpha);
		}
	}

	/** A cone of light from the nozzle exit (radius r0) to its end (radius r1) after {@code length}, fading out. */
	private static void cone(Matrix4f m, VertexConsumer buffer, float cx, float y, float cz, float r0, float r1, float length, float[] color,
			float alpha, float swirl) {
		if (alpha <= 0.002F) {
			return;
		}
		int rings = 4;
		for (int k = 0; k < rings; k++) {
			float f0 = k / (float) rings;
			float f1 = (k + 1) / (float) rings;
			float d0 = length * (float) Math.pow(f0, 1.25);
			float d1 = length * (float) Math.pow(f1, 1.25);
			float ra = r0 + (r1 - r0) * (float) Math.pow(f0, 0.8);
			float rb = r0 + (r1 - r0) * (float) Math.pow(f1, 0.8);
			float a0 = alpha * (float) Math.pow(1.0F - f0, 1.6);
			float a1 = alpha * (float) Math.pow(1.0F - f1, 1.6);
			for (int j = 0; j < SEGMENTS; j++) {
				double t0 = 2.0 * Math.PI * j / SEGMENTS + swirl;
				double t1 = 2.0 * Math.PI * (j + 1) / SEGMENTS + swirl;
				float s0 = (float) Math.sin(t0);
				float c0 = (float) Math.cos(t0);
				float s1 = (float) Math.sin(t1);
				float c1 = (float) Math.cos(t1);
				vertex(m, buffer, cx + ra * s0, y - d0, cz + ra * c0, color, a0);
				vertex(m, buffer, cx + ra * s1, y - d0, cz + ra * c1, color, a0);
				vertex(m, buffer, cx + rb * s1, y - d1, cz + rb * c1, color, a1);
				vertex(m, buffer, cx + rb * s0, y - d1, cz + rb * c0, color, a1);
			}
		}
	}

	/** Two crossed rhombi on the axis: a shock diamond. */
	private static void diamond(Matrix4f m, VertexConsumer buffer, float cx, float y, float cz, float size, float[] color, float alpha) {
		if (alpha <= 0.002F) {
			return;
		}
		float h = size * 1.6F;
		vertex(m, buffer, cx, y + h, cz, color, 0.0F);
		vertex(m, buffer, cx + size, y, cz, color, alpha);
		vertex(m, buffer, cx, y - h, cz, color, 0.0F);
		vertex(m, buffer, cx - size, y, cz, color, alpha);
		vertex(m, buffer, cx, y + h, cz, color, 0.0F);
		vertex(m, buffer, cx, y, cz + size, color, alpha);
		vertex(m, buffer, cx, y - h, cz, color, 0.0F);
		vertex(m, buffer, cx, y, cz - size, color, alpha);
	}

	private static void vertex(Matrix4f m, VertexConsumer buffer, float x, float y, float z, float[] color, float alpha) {
		buffer.addVertex(m, x, y, z).setColor(color[0], color[1], color[2], Math.min(0.99F, alpha));
	}
}
