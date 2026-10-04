package io.github.avi130805.redplanet.client.starship.flight;

import com.mojang.blaze3d.platform.NativeImage;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.mars.geo.GeoGrid;
import io.github.avi130805.redplanet.mars.geo.MarsGeoData;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * A shaded-relief map of Mars built at runtime from the mod's own datasets: MGS TES albedo for the colour (bright dusty
 * Tharsis and Arabia, dark basaltic Syrtis Major and Acidalia) and MOLA topography for hill shading. Equirectangular,
 * east longitude 0-360 left to right, north up. Used by mission control and by the interlude's globe.
 */
public final class MarsMapTexture {
	public static final Identifier ID = RedPlanet.id("dynamic/mars_map");
	public static final int WIDTH = 720;
	public static final int HEIGHT = 360;

	private static boolean built;

	private MarsMapTexture() {
	}

	/** Builds and registers the texture on first use (render thread). */
	public static Identifier get() {
		if (!built) {
			built = true;
			NativeImage image = render();
			Minecraft.getInstance().getTextureManager().register(ID, new DynamicTexture(() -> "Mars map", image));
		}
		return ID;
	}

	private static NativeImage render() {
		MarsGeoData data = MarsGeoData.get();
		GeoGrid topo = data.topography;
		GeoGrid albedo = data.albedo;
		NativeImage image = new NativeImage(WIDTH, HEIGHT, false);
		double step = 360.0 / WIDTH;
		// Light from the north-west, 35 degrees up; heights exaggerated so the volcanoes and canyons read at this scale.
		double lx = -0.5;
		double ly = 0.5;
		double lz = 0.7;
		for (int row = 0; row < HEIGHT; row++) {
			double lat = 90.0 - (row + 0.5) * step;
			double kmPerDegLon = Math.max(1.0, 59.27 * Math.cos(Math.toRadians(lat)));
			for (int col = 0; col < WIDTH; col++) {
				double lon = (col + 0.5) * step;
				double e = topo.sampleBilinear(lat, lon);
				double ex = topo.sampleBilinear(lat, lon + step) - topo.sampleBilinear(lat, lon - step);
				double ey = topo.sampleBilinear(Math.min(89.9, lat + step), lon) - topo.sampleBilinear(Math.max(-89.9, lat - step), lon);
				double dzdx = ex / (2.0 * step * kmPerDegLon * 1000.0) * 25.0;
				double dzdy = ey / (2.0 * step * 59.27 * 1000.0) * 25.0;
				double nx = -dzdx;
				double ny = -dzdy;
				double nz = 1.0;
				double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
				double shade = Mth.clamp((nx * lx + ny * ly + nz * lz) / len, 0.0, 1.0);
				double a = Mth.clamp((albedo.sampleBilinear(lat, lon) - 0.08) / 0.22, 0.0, 1.0);
				// Albedo ramp: dark basalt to bright ochre dust; the highest volcano summits pale toward grey-pink.
				double r = Mth.lerp(a, 86.0, 214.0);
				double g = Mth.lerp(a, 56.0, 148.0);
				double b = Mth.lerp(a, 42.0, 96.0);
				double peak = Mth.clamp((e - 8000.0) / 12000.0, 0.0, 1.0);
				r = Mth.lerp(peak, r, 196.0);
				g = Mth.lerp(peak, g, 168.0);
				b = Mth.lerp(peak, b, 150.0);
				double low = Mth.clamp((-4000.0 - e) / 4000.0, 0.0, 1.0);
				r *= 1.0 - 0.18 * low;
				g *= 1.0 - 0.12 * low;
				double light = 0.55 + 0.6 * shade;
				int ri = (int) Mth.clamp(r * light, 0.0, 255.0);
				int gi = (int) Mth.clamp(g * light, 0.0, 255.0);
				int bi = (int) Mth.clamp(b * light, 0.0, 255.0);
				image.setPixel(col, row, 0xFF000000 | ri << 16 | gi << 8 | bi);
			}
		}
		return image;
	}

	/** Map pixel (0..WIDTH, 0..HEIGHT) to latitude and east longitude. */
	public static double latitude(double v) {
		return 90.0 - v * 180.0;
	}

	public static double longitude(double u) {
		return u * 360.0;
	}
}
