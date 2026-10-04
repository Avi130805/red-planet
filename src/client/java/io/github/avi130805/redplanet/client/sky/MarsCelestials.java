package io.github.avi130805.redplanet.client.sky;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.client.config.RedPlanetClientConfig;
import io.github.avi130805.redplanet.mars.astro.MarsSkyModel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.server.packs.resources.Resource;

/**
 * Draws Mars' sky bodies inside vanilla's open "Sky" render pass, replacing the vanilla sun, moon and random stars:
 * the catalogue stars turned about Mars' pole, the 0.35-degree Sun with its glare and blue aureole, Phobos (with
 * its transits of the Sun), Deimos, Earth and the Moon. Positions and sizes come from {@link MarsSkyData}.
 *
 * <p>GPU buffers are built once per vanilla {@code SkyRenderer} (which is recreated after every resource reload);
 * per-frame data only goes into the dynamic transform uniforms, like vanilla.
 */
public final class MarsCelestials implements AutoCloseable {
	private static final float DISTANCE = 100.0F;
	/** Angular radius of the aureole sprite: the forward-scattering halo reaches 20-30 degrees from the Sun. */
	private static final double AUREOLE_RADIUS_DEG = 25.0;
	/** Disc radius as a fraction of the sprite half-width (tools/textures/gen_sky_sprites.py). */
	private static final float SUN_DISC_FRACTION = 0.25F;
	private static final float PHOBOS_DISC_FRACTION = 0.80F;

	private enum Sprite {
		SUN("sun_mars"), GLARE("glare"), AUREOLE("aureole"), PHOBOS("phobos"), DEIMOS("deimos"), EARTH("earth"), MOON("moon_point");

		final String path;

		Sprite(String path) {
			this.path = path;
		}

		int baseVertex() {
			return ordinal() * 4;
		}
	}

	private final TextureAtlas atlas;
	private final GpuBuffer quads;
	private final GpuBuffer stars;
	private final int starIndexCount;
	private final RenderSystem.AutoStorageIndexBuffer quadIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);

	public MarsCelestials(TextureAtlas celestialsAtlas) {
		this.atlas = celestialsAtlas;
		this.quads = buildQuads(celestialsAtlas);
		float[][] catalogue = loadStars();
		this.starIndexCount = catalogue.length * 6;
		this.stars = buildStars(catalogue);
	}

	public void draw(RenderPass pass, MarsSkyData d) {
		MarsSkyModel.Look look = d.look();
		float night = Math.clamp(look.starBrightness() / 0.85F, 0.0F, 1.0F);
		float k = d.bodyScale();

		if (this.stars != null && look.starBrightness() > 0.001F && RedPlanetClientConfig.get().catalogueStars) {
			Matrix4f mv = RenderSystem.getModelViewMatrixCopy().mul(new Matrix4f(d.starRotation()));
			float b = look.starBrightness();
			GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(mv, new Vector4f(b, b, b, b));
			pass.pushDebugGroup(() -> "Mars stars");
			pass.setPipeline(RenderSystem.getCompiledPipeline(RPRenderPipelines.MARS_STARS));
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transform);
			pass.setVertexBuffer(0, this.stars.slice());
			pass.setIndexBuffer(this.quadIndices.getBuffer(this.starIndexCount), this.quadIndices.type());
			pass.drawIndexed(this.starIndexCount, 1, 0, 0, 0);
			pass.popDebugGroup();
		}

		// Earth and its Moon: a blue-white evening or morning star (about -2.5 mag at best) with a faint companion.
		float earthBright = (float) Math.clamp(0.9 * Math.pow(10.0, -0.4 * (d.earthMagnitude() + 2.5)), 0.15, 1.0) * night;
		body(pass, () -> "Earth", Sprite.EARTH, d.earth(), 0.42F, color(0.85F, 0.92F, 1.0F, earthBright), RenderPipelines.CELESTIAL);
		body(pass, () -> "Moon", Sprite.MOON, d.moon(), 0.30F, color(1.0F, 1.0F, 1.0F, earthBright * 0.35F), RenderPipelines.CELESTIAL);
		// Deimos: star-like, about magnitude -0.1 at full phase from the equator.
		float deimosBright = (d.deimosEclipsed() ? 0.05F : 1.0F) * (0.25F + 0.75F * d.deimosIllumination()) * night;
		body(pass, () -> "Deimos", Sprite.DEIMOS, d.deimos(), 0.40F, color(1.0F, 0.97F, 0.92F, deimosBright), RenderPipelines.CELESTIAL);

		// The Sun: aureole, glare, then the true-size disc.
		float sunVis = look.sunVisibility();
		if (sunVis > 0.001F || look.aureole() > 0.001F) {
			float blue = look.aureoleBlue();
			Vector4f aureole = color(1.0F - 0.55F * blue, 1.0F - 0.30F * blue, 1.0F, 0.55F * look.aureole());
			// The aureole is 25 degrees across, so it keeps glowing over the horizon through twilight after the disc has set
			// (look.aureole() fades it as the Sun sinks); terrain covers the part below the horizon.
			body(pass, () -> "Aureole", Sprite.AUREOLE, d.sun(), (float) (DISTANCE * Math.tan(Math.toRadians(AUREOLE_RADIUS_DEG))), aureole,
				RenderPipelines.CELESTIAL, AUREOLE_RADIUS_DEG);
			body(pass, () -> "Glare", Sprite.GLARE, d.sun(), (float) (DISTANCE * Math.tan(Math.toRadians(3.0 * Math.max(1.0, Math.sqrt(k))))),
				color(1.0F, 0.96F, 0.88F, 0.7F * sunVis), RenderPipelines.CELESTIAL);
			body(pass, () -> "Mars Sun", Sprite.SUN, d.sun(), halfWidth(d.sunDiameterDeg() * k, SUN_DISC_FRACTION),
				color(1.0F, 1.0F, 1.0F, sunVis), RenderPipelines.CELESTIAL);
		}

		// Phobos: lit by the Sun (dark grey, albedo ~0.07), dark in Mars' shadow, a silhouette when it crosses the Sun.
		float phobosHalf = halfWidth(d.phobosDiameterDeg() * k, PHOBOS_DISC_FRACTION);
		double separation = Math.toDegrees(Math.acos(Math.clamp(d.phobos().dot(d.sun()), -1.0F, 1.0F)));
		boolean transit = separation < 0.5 * (d.sunDiameterDeg() + d.phobosDiameterDeg()) * k + 0.05 && sunVis > 0.01F;
		if (transit) {
			body(pass, () -> "Phobos transit", Sprite.PHOBOS, d.phobos(), phobosHalf, color(0.04F, 0.03F, 0.03F, 0.97F),
				RPRenderPipelines.CELESTIAL_SILHOUETTE);
		} else {
			float lit = d.phobosEclipsed() ? 0.04F : d.phobosIllumination();
			float phobosBright = lit * Math.max(night, 0.10F);
			body(pass, () -> "Phobos", Sprite.PHOBOS, d.phobos(), phobosHalf, color(1.0F, 0.95F, 0.90F, phobosBright), RenderPipelines.CELESTIAL);
		}
	}

	private void body(RenderPass pass, Supplier<String> label, Sprite sprite, Vector3fc dir, float halfWidth, Vector4f color,
			RenderPipeline pipeline) {
		body(pass, label, sprite, dir, halfWidth, color, pipeline, 0.0);
	}

	/**
	 * Draws a sprite centred on {@code dir}. It fades out once its centre is more than {@code radiusDeg} plus two degrees
	 * below the horizon: the terrain hides it anyway, and this covers flat or high views.
	 */
	private void body(RenderPass pass, Supplier<String> label, Sprite sprite, Vector3fc dir, float halfWidth, Vector4f color,
			RenderPipeline pipeline, double radiusDeg) {
		float below = (float) Math.sin(Math.toRadians(radiusDeg + 2.0));
		float horizon = Math.clamp((dir.y() + below) / 0.035F, 0.0F, 1.0F);
		float alpha = color.w * horizon;
		if (alpha <= 0.002F) {
			return;
		}
		Matrix4f mv = RenderSystem.getModelViewMatrixCopy()
			.rotate(new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F, dir.x(), dir.y(), dir.z()))
			.translate(0.0F, DISTANCE, 0.0F)
			.scale(halfWidth, 1.0F, halfWidth);
		GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(mv, new Vector4f(color.x, color.y, color.z, alpha));
		pass.pushDebugGroup(label);
		pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
		RenderSystem.bindDefaultUniforms(pass);
		pass.setUniform("DynamicTransforms", transform);
		pass.setUniform("Sampler0", this.atlas.getTextureView(), this.atlas.getSampler());
		pass.setVertexBuffer(0, this.quads.slice());
		pass.setIndexBuffer(this.quadIndices.getBuffer(6), this.quadIndices.type());
		pass.drawIndexed(6, 1, 0, sprite.baseVertex(), 0);
		pass.popDebugGroup();
	}

	/** Quad half-width at {@link #DISTANCE} for a disc of the given angular diameter filling a fraction of the sprite. */
	private static float halfWidth(double diameterDeg, float discFraction) {
		return (float) (DISTANCE * Math.tan(Math.toRadians(diameterDeg / 2.0)) / discFraction);
	}

	private static Vector4f color(float r, float g, float b, float a) {
		return new Vector4f(r, g, b, a);
	}

	private static GpuBuffer buildQuads(TextureAtlas atlas) {
		Sprite[] sprites = Sprite.values();
		int vertexSize = DefaultVertexFormat.POSITION_TEX.getVertexSize();
		try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(sprites.length * 4 * vertexSize)) {
			BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX);
			for (Sprite sprite : sprites) {
				TextureAtlasSprite s = atlas.getSprite(RedPlanet.id(sprite.path));
				builder.addVertex(-1.0F, 0.0F, -1.0F).setUv(s.getU0(), s.getV0());
				builder.addVertex(1.0F, 0.0F, -1.0F).setUv(s.getU1(), s.getV0());
				builder.addVertex(1.0F, 0.0F, 1.0F).setUv(s.getU1(), s.getV1());
				builder.addVertex(-1.0F, 0.0F, 1.0F).setUv(s.getU0(), s.getV1());
			}
			try (MeshData mesh = builder.buildOrThrow()) {
				return RenderSystem.getDevice().createBuffer(() -> "Mars sky bodies", 32, mesh.vertexBuffer());
			}
		}
	}

	/** Reads {@code assets/redplanet/sky/stars.bin}: "RPST", u32 count, then x, y, z, V magnitude, B-V (big-endian floats). */
	private static float[][] loadStars() {
		var id = RedPlanet.id("sky/stars.bin");
		Resource resource = Minecraft.getInstance().getResourceManager().getResource(id).orElse(null);
		if (resource == null) {
			RedPlanet.LOGGER.warn("Missing {}: the Mars sky will have no stars", id);
			return new float[0][];
		}
		try (InputStream raw = resource.open(); DataInputStream in = new DataInputStream(raw)) {
			byte[] magic = in.readNBytes(4);
			if (!"RPST".equals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII))) {
				throw new IOException("bad magic");
			}
			int count = in.readInt();
			float[][] stars = new float[count][5];
			for (int i = 0; i < count; i++) {
				for (int j = 0; j < 5; j++) {
					stars[i][j] = in.readFloat();
				}
			}
			return stars;
		} catch (IOException e) {
			RedPlanet.LOGGER.warn("Could not read {}", id, e);
			return new float[0][];
		}
	}

	private static GpuBuffer buildStars(float[][] catalogue) {
		if (catalogue.length == 0) {
			return null;
		}
		int vertexSize = DefaultVertexFormat.POSITION_COLOR.getVertexSize();
		try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(catalogue.length * 4 * vertexSize)) {
			BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);
			for (float[] star : catalogue) {
				Vector3f center = new Vector3f(star[0], star[1], star[2]).normalize(DISTANCE);
				float vmag = star[3];
				float size = 0.10F + 0.20F * Math.clamp((6.0F - vmag) / 7.5F, 0.0F, 1.0F);
				double flux = Math.pow(10.0, -0.4 * (vmag - 1.0));
				int alpha = (int) (255 * Math.clamp(0.2 + 0.8 * Math.sqrt(flux), 0.2, 1.0));
				int rgb = starColor(star[4]);
				int argb = (alpha << 24) | rgb;
				Vector3f up = Math.abs(center.y) > 0.999F * DISTANCE ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
				Matrix3f rotation = new Matrix3f().rotateTowards(new Vector3f(center).negate(), up);
				builder.addVertex(new Vector3f(size, -size, 0.0F).mul(rotation).add(center)).setColor(argb);
				builder.addVertex(new Vector3f(size, size, 0.0F).mul(rotation).add(center)).setColor(argb);
				builder.addVertex(new Vector3f(-size, size, 0.0F).mul(rotation).add(center)).setColor(argb);
				builder.addVertex(new Vector3f(-size, -size, 0.0F).mul(rotation).add(center)).setColor(argb);
			}
			try (MeshData mesh = builder.buildOrThrow()) {
				return RenderSystem.getDevice().createBuffer(() -> "Mars catalogue stars", 40, mesh.vertexBuffer());
			}
		}
	}

	/**
	 * Star colour from its B-V index: Ballesteros (2012) temperature, then a blackbody-to-sRGB fit (Helland), softened
	 * toward white because the eye sees faint stars as nearly colourless.
	 */
	static int starColor(float bv) {
		double t = 4600.0 * (1.0 / (0.92 * bv + 1.7) + 1.0 / (0.92 * bv + 0.62));
		double k = Math.clamp(t, 1000.0, 40000.0) / 100.0;
		double r = k <= 66 ? 255 : 329.698727446 * Math.pow(k - 60, -0.1332047592);
		double g = k <= 66 ? 99.4708025861 * Math.log(k) - 161.1195681661 : 288.1221695283 * Math.pow(k - 60, -0.0755148492);
		double b = k >= 66 ? 255 : k <= 19 ? 0 : 138.5177312231 * Math.log(k - 10) - 305.0447927307;
		int ri = (int) Math.clamp(0.55 * Math.clamp(r, 0, 255) + 0.45 * 255, 0, 255);
		int gi = (int) Math.clamp(0.55 * Math.clamp(g, 0, 255) + 0.45 * 255, 0, 255);
		int bi = (int) Math.clamp(0.55 * Math.clamp(b, 0, 255) + 0.45 * 255, 0, 255);
		return (ri << 16) | (gi << 8) | bi;
	}

	@Override
	public void close() {
		this.quads.close();
		if (this.stars != null) {
			this.stars.close();
		}
	}
}
