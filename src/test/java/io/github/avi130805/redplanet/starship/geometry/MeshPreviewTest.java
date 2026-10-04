package io.github.avi130805.redplanet.starship.geometry;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.function.Function;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import io.github.avi130805.redplanet.starship.geometry.VehicleMesh.PartMesh;

/**
 * Renders flat-shaded previews of the vehicle meshes into build/previews/ with a tiny z-buffered
 * rasterizer, so the shapes can be inspected without starting the game.
 */
class MeshPreviewTest {
	private static final int F = VehicleMesh.FLOATS_PER_VERTEX;

	@Test
	void renderPreviews() throws IOException {
		File dir = new File("build/previews");
		dir.mkdirs();
		VehicleMesh ship = StarshipGeometry.buildShip(StarshipGeometry.Lod.HIGH);
		VehicleMesh booster = StarshipGeometry.buildBooster(StarshipGeometry.Lod.HIGH);

		// Full stack, three-quarter view from the windward-right side, flaps tucked, legs stowed.
		BufferedImage img = new BufferedImage(900, 1400, BufferedImage.TYPE_INT_RGB);
		double[] zbuf = new double[img.getWidth() * img.getHeight()];
		java.util.Arrays.fill(zbuf, Double.NEGATIVE_INFINITY);
		fill(img, 0x1a1d24);
		double yaw = Math.toRadians(35);
		double pitch = Math.toRadians(-8);
		double scale = 10.0;
		draw(img, zbuf, booster, p -> p, yaw, pitch, scale, 450, 1360);
		draw(img, zbuf, ship, p -> new double[]{p[0], p[1] + StarshipGeometry.BOOSTER_HEIGHT, p[2]}, yaw, pitch, scale, 450, 1360);
		ImageIO.write(img, "png", new File(dir, "stack.png"));

		// Ship alone, landed configuration: legs deployed, flaps out, seen from below-right.
		BufferedImage img2 = new BufferedImage(900, 900, BufferedImage.TYPE_INT_RGB);
		double[] z2 = new double[img2.getWidth() * img2.getHeight()];
		java.util.Arrays.fill(z2, Double.NEGATIVE_INFINITY);
		fill(img2, 0x1a1d24);
		drawAnimated(img2, z2, ship, Math.toRadians(-50), Math.toRadians(-14), 14.0, 450, 820);
		ImageIO.write(img2, "png", new File(dir, "ship_landed.png"));
	}

	private static void drawAnimated(BufferedImage img, double[] zbuf, VehicleMesh mesh, double yaw, double pitch, double scale, int ox, int oy) {
		for (Map.Entry<VehiclePart, PartMesh> e : mesh.parts().entrySet()) {
			VehiclePart part = e.getKey();
			double angle = 0;
			if (part.name().startsWith("SHIP_LEG")) {
				angle = Math.toRadians(StarshipGeometry.LEG_DEPLOY_DEG);
			} else if (part.name().contains("FLAP")) {
				angle = Math.toRadians(20);
			}
			double a = angle;
			PartMesh pm = e.getValue();
			boolean leg = part.name().startsWith("SHIP_LEG");
			Function<double[], double[]> tf = p -> {
				double[] r = leg ? StarshipGeometryTest.deployLeg(p, pm.joint()) : StarshipGeometryTest.rotate(p, pm.joint(), a);
				return new double[]{r[0], r[1] + StarshipGeometry.LANDED_SKIRT_HEIGHT, r[2]};
			};
			drawPart(img, zbuf, pm.quads(), tf, yaw, pitch, scale, ox, oy, part);
		}
	}

	private static void draw(BufferedImage img, double[] zbuf, VehicleMesh mesh, Function<double[], double[]> tf, double yaw, double pitch, double scale, int ox, int oy) {
		for (Map.Entry<VehiclePart, PartMesh> e : mesh.parts().entrySet()) {
			PartMesh pm = e.getValue();
			double a = e.getKey().name().contains("FLAP") ? Math.toRadians(55) : 0;
			drawPart(img, zbuf, pm.quads(), p -> tf.apply(StarshipGeometryTest.rotate(p, pm.joint(), a)), yaw, pitch, scale, ox, oy, e.getKey());
		}
	}

	private static void drawPart(BufferedImage img, double[] zbuf, float[] q, Function<double[], double[]> tf, double yaw, double pitch,
			double scale, int ox, int oy, VehiclePart part) {
		double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
		double[] light = norm(new double[]{0.5, 0.7, 0.6});
		for (int i = 0; i < q.length; i += VehicleMesh.FLOATS_PER_QUAD) {
			double[][] sv = new double[4][];
			double[][] wv = new double[4][];
			for (int k = 0; k < 4; k++) {
				int o = i + k * F;
				double[] w = tf.apply(new double[]{q[o], q[o + 1], q[o + 2]});
				wv[k] = w;
				// view: rotate around Y by yaw, then around X by pitch; camera looks along -Z_view
				double x1 = w[0] * cy - w[2] * sy;
				double z1 = w[0] * sy + w[2] * cy;
				double y2 = w[1] * cp - z1 * sp;
				double z2 = w[1] * sp + z1 * cp;
				sv[k] = new double[]{ox + x1 * scale, oy - y2 * scale, z2};
			}
			double[] n = norm(cross(sub(wv[1], wv[0]), sub(wv[3], wv[0])));
			if (Double.isNaN(n[0])) {
				n = norm(cross(sub(wv[2], wv[1]), sub(wv[3], wv[1])));
				if (Double.isNaN(n[0])) {
					continue;
				}
			}
			double lambert = Math.max(0.0, dot(n, light)) * 0.75 + 0.25;
			int base = colorFor(part, q[i + 6], q[i + 7]);
			int r = (int) (((base >> 16) & 255) * lambert);
			int g = (int) (((base >> 8) & 255) * lambert);
			int b = (int) ((base & 255) * lambert);
			int rgb = (r << 16) | (g << 8) | b;
			tri(img, zbuf, sv[0], sv[1], sv[2], rgb);
			tri(img, zbuf, sv[0], sv[2], sv[3], rgb);
		}
	}

	private static int colorFor(VehiclePart part, float u, float v) {
		String n = part.name();
		if (n.contains("ENGINE")) {
			return 0x6b5a4a;
		}
		if (n.contains("LEG")) {
			return 0x9a9a9a;
		}
		if (n.contains("GRID_FIN")) {
			return 0x555555;
		}
		if (n.contains("RING")) {
			return 0x777777;
		}
		return 0xc8ccd2;
	}

	private static void tri(BufferedImage img, double[] zbuf, double[] a, double[] b, double[] c, int rgb) {
		int minX = (int) Math.max(0, Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
		int maxX = (int) Math.min(img.getWidth() - 1, Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
		int minY = (int) Math.max(0, Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
		int maxY = (int) Math.min(img.getHeight() - 1, Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
		double area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
		if (Math.abs(area) < 1e-9) {
			return;
		}
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				double px = x + 0.5, py = y + 0.5;
				double w0 = ((b[0] - px) * (c[1] - py) - (b[1] - py) * (c[0] - px)) / area;
				double w1 = ((c[0] - px) * (a[1] - py) - (c[1] - py) * (a[0] - px)) / area;
				double w2 = 1 - w0 - w1;
				if (w0 < 0 || w1 < 0 || w2 < 0) {
					continue;
				}
				double z = w0 * a[2] + w1 * b[2] + w2 * c[2];
				int idx = y * img.getWidth() + x;
				if (z > zbuf[idx]) {
					zbuf[idx] = z;
					img.setRGB(x, y, rgb);
				}
			}
		}
	}

	private static void fill(BufferedImage img, int rgb) {
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				img.setRGB(x, y, rgb);
			}
		}
	}

	private static double[] sub(double[] a, double[] b) {
		return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	private static double[] cross(double[] a, double[] b) {
		return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	private static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	private static double[] norm(double[] v) {
		double l = Math.sqrt(dot(v, v));
		return new double[]{v[0] / l, v[1] / l, v[2] / l};
	}
}
