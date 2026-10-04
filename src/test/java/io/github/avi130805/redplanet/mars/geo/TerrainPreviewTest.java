package io.github.avi130805.redplanet.mars.geo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Renders hillshaded terrain previews around real landmarks into build/previews/terrain_*.png and checks
 * per-column generation cost.
 */
class TerrainPreviewTest {
	private static final MarsTerrain TERRAIN = new MarsTerrain(12345L);

	@Test
	void columnCostIsSmall() {
		MarsTerrain.Column c = new MarsTerrain.Column();
		// warm up
		for (int i = 0; i < 200_000; i++) {
			TERRAIN.sample(i * 3.7, (i % 977) * 5.3, c);
		}
		long t0 = System.nanoTime();
		int n = 400_000;
		double sink = 0;
		for (int i = 0; i < n; i++) {
			TERRAIN.sample(10000 + (i % 640), -2000 + i / 640.0, c);
			sink += c.surfaceY;
		}
		double usPerColumn = (System.nanoTime() - t0) / 1e3 / n;
		System.out.printf("terrain column cost: %.3f us (sink %.1f)%n", usPerColumn, sink);
		assertTrue(usPerColumn < 5.0, "column cost " + usPerColumn + " us");
	}

	@Test
	void renderLandmarks() throws IOException {
		render("gale", -5.4, 137.8, 1.0, 512);
		render("jezero", 18.4, 77.5, 1.0, 512);
		render("olympus_mons", 18.4, 226.2, 2.0, 512);
		render("valles_marineris", -10.0, 287.0, 6.0, 512);
		render("arcadia_planitia", 40.0, 190.0, 1.0, 512);
		render("north_polar_cap", 81.0, 30.0, 1.0, 512);
		render("meridiani_planum", -1.95, 354.47, 1.0, 512);
	}

	private static void render(String name, double lat, double lon, double blocksPerPixel, int size) throws IOException {
		double cx = MarsProjection.xOf(lon);
		double cz = MarsProjection.zOf(lat);
		double[][] h = new double[size][size];
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		MarsTerrain.Column c = new MarsTerrain.Column();
		double[][] dune = new double[size][size];
		double[][] cap = new double[size][size];
		for (int py = 0; py < size; py++) {
			for (int px = 0; px < size; px++) {
				double x = cx + (px - size / 2.0) * blocksPerPixel;
				double z = cz + (py - size / 2.0) * blocksPerPixel;
				TERRAIN.sample(x, z, c);
				h[py][px] = c.surfaceY;
				dune[py][px] = c.dunes;
				cap[py][px] = c.polarCap;
				min = Math.min(min, c.surfaceY);
				max = Math.max(max, c.surfaceY);
			}
		}
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		for (int py = 0; py < size; py++) {
			for (int px = 0; px < size; px++) {
				int x1 = Math.min(size - 1, px + 1);
				int y1 = Math.min(size - 1, py + 1);
				double dx = (h[py][x1] - h[py][px]) / blocksPerPixel;
				double dy = (h[y1][px] - h[py][px]) / blocksPerPixel;
				// light from the north-west, as in planetary shaded-relief maps
				double nx = -dx;
				double ny = -dy;
				double nz = 1.0;
				double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
				double shade = Math.max(0.0, (nx * -0.6 + ny * -0.6 + nz * 0.55) / l / Math.sqrt(0.6 * 0.6 * 2 + 0.55 * 0.55));
				double t = (h[py][px] - min) / Math.max(1e-6, max - min);
				// regolith tint, darker for low elevations; dunes darker, ice white
				double r = 120 + 110 * t;
				double g = 70 + 70 * t;
				double b = 45 + 45 * t;
				double d = dune[py][px];
				r = r * (1 - 0.45 * d) + 60 * 0.45 * d;
				g = g * (1 - 0.45 * d) + 55 * 0.45 * d;
				b = b * (1 - 0.45 * d) + 52 * 0.45 * d;
				double ic = cap[py][px];
				r = r * (1 - ic) + 235 * ic;
				g = g * (1 - ic) + 235 * ic;
				b = b * (1 - ic) + 240 * ic;
				double s = 0.35 + 0.9 * shade;
				int ri = (int) Math.min(255, r * s);
				int gi = (int) Math.min(255, g * s);
				int bi = (int) Math.min(255, b * s);
				img.setRGB(px, py, (ri << 16) | (gi << 8) | bi);
			}
		}
		File dir = new File("build/previews");
		dir.mkdirs();
		ImageIO.write(img, "png", new File(dir, "terrain_" + name + ".png"));
		System.out.printf("%s: y %.1f .. %.1f (%.0f blocks of relief)%n", name, min, max, max - min);
	}
}
