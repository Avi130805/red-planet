package io.github.avi130805.redplanet.client.starship.interlude;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

import org.joml.Matrix3x2fc;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * A planet as a GUI element: an equirectangular texture wrapped on a sphere and projected orthographically on the CPU,
 * lit by the Sun with a soft terminator and a faint night side.
 */
public record GlobeRenderState(Matrix3x2fc pose, float cx, float cy, float radius, Identifier texture, Quaternionfc spin, Vector3fc sunDir,
		float nightLight, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements GuiElementRenderState {
	private static final int LAT = 32;
	private static final int LON = 64;

	public GlobeRenderState(Matrix3x2fc pose, float cx, float cy, float radius, Identifier texture, Quaternionfc spin, Vector3fc sunDir,
			float nightLight, @Nullable ScreenRectangle scissorArea) {
		this(pose, cx, cy, radius, texture, spin, sunDir, nightLight, scissorArea, bounds(pose, cx, cy, radius, scissorArea));
	}

	private static @Nullable ScreenRectangle bounds(Matrix3x2fc pose, float cx, float cy, float r, @Nullable ScreenRectangle scissor) {
		int size = (int) Math.ceil(2 * r) + 2;
		ScreenRectangle b = new ScreenRectangle(Mth.floor(cx - r) - 1, Mth.floor(cy - r) - 1, size, size).transformMaxBounds(pose);
		return scissor != null ? scissor.intersection(b) : b;
	}

	@Override
	public void buildVertices(VertexConsumer vc) {
		Vector3f tmp = new Vector3f();
		for (int i = 0; i < LAT; i++) {
			for (int j = 0; j < LON; j++) {
				if (this.point(i + 0.5F, j + 0.5F, tmp).z < -0.08F) {
					continue; // far side
				}
				this.emit(vc, i, j, tmp);
				this.emit(vc, i + 1, j, tmp);
				this.emit(vc, i + 1, j + 1, tmp);
				this.emit(vc, i, j + 1, tmp);
			}
		}
	}

	/** Unit-sphere point at grid (i down from the north pole, j east from longitude -180), rotated toward the viewer (+z). */
	private Vector3f point(float i, float j, Vector3f out) {
		float lat = Mth.PI * (0.5F - i / LAT);
		float lon = 2.0F * Mth.PI * j / LON - Mth.PI;
		return this.spin.transform(out.set(Mth.cos(lat) * Mth.sin(lon), Mth.sin(lat), Mth.cos(lat) * Mth.cos(lon)));
	}

	private void emit(VertexConsumer vc, int i, int j, Vector3f tmp) {
		Vector3f n = this.point(i, j, tmp);
		float day = n.dot(this.sunDir);
		float light = Mth.clamp(day * 1.25F + 0.1F, this.nightLight, 1.0F);
		// Limb darkening: a little dimmer toward the edge of the disc.
		light *= 0.75F + 0.25F * Mth.clamp(n.z, 0.0F, 1.0F);
		vc.addVertexWith2DPose(this.pose, this.cx + n.x * this.radius, this.cy - n.y * this.radius)
			.setUv((float) j / LON, (float) i / LAT)
			.setColor(ARGB.colorFromFloat(1.0F, light, light, light));
	}

	@Override
	public RenderPipeline pipeline() {
		return RenderPipelines.GUI_TEXTURED;
	}

	@Override
	public TextureSetup textureSetup() {
		AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(this.texture);
		return TextureSetup.singleTexture(tex.getTextureView(), RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
	}
}
