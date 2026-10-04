package io.github.avi130805.redplanet.client.sky;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import io.github.avi130805.redplanet.RedPlanet;

import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * The mod's render pipelines, built from vanilla shaders and registered at client start (with vanilla's, so they are
 * compiled with the rest).
 */
public final class RPRenderPipelines {
	/** Catalogue stars: per-vertex colour and brightness, added to the sky (vanilla's STARS is one colour). */
	public static final RenderPipeline MARS_STARS = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
		.withBindGroupLayout(BindGroupLayouts.PROJECTION)
		.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
		.withLocation(RedPlanet.id("pipeline/mars_stars"))
		.withVertexShader("core/position_color")
		.withFragmentShader("core/position_color")
		.withColorTargetState(new ColorTargetState(BlendFunction.OVERLAY))
		.withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
		.withPrimitiveTopology(PrimitiveTopology.QUADS)
		.build());

	/** Like vanilla's CELESTIAL but alpha-blended: for Phobos' dark silhouette when it crosses the Sun. */
	public static final RenderPipeline CELESTIAL_SILHOUETTE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
		.withBindGroupLayout(BindGroupLayouts.PROJECTION)
		.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
		.withLocation(RedPlanet.id("pipeline/celestial_silhouette"))
		.withVertexShader("core/position_tex")
		.withFragmentShader("core/position_tex")
		.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
		.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
		.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
		.withPrimitiveTopology(PrimitiveTopology.QUADS)
		.build());

	private RPRenderPipelines() {
	}

	/** Forces class initialization (registration) from the client entrypoint. */
	public static void init() {
	}
}
