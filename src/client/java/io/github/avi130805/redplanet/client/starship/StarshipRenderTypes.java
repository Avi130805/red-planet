package io.github.avi130805.redplanet.client.starship;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import io.github.avi130805.redplanet.RedPlanet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.MipmappedTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

/**
 * Textures and render types of the vehicles. The hull atlases are large (16 px per metre), so they are loaded with
 * mipmaps, or the heat-shield tiles would shimmer at a distance.
 */
public final class StarshipRenderTypes {
	public static final Identifier SHIP_TEXTURE = RedPlanet.id("textures/entity/starship/ship.png");
	public static final Identifier SHIP_FROST_TEXTURE = RedPlanet.id("textures/entity/starship/ship_frost.png");
	public static final Identifier SHIP_LIGHTS_TEXTURE = RedPlanet.id("textures/entity/starship/ship_lights.png");
	public static final Identifier BOOSTER_TEXTURE = RedPlanet.id("textures/entity/starship/booster.png");
	public static final Identifier BOOSTER_FROST_TEXTURE = RedPlanet.id("textures/entity/starship/booster_frost.png");

	/**
	 * Untextured additive light (engine plumes, Mach diamonds, entry plasma): vanilla's lightning shader and blending,
	 * without depth writes so overlapping layers add up in any order, and drawn from both sides.
	 */
	public static final RenderPipeline GLOW_PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LIGHTNING_SNIPPET)
		.withLocation(RedPlanet.id("pipeline/vehicle_glow"))
		.withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
		.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
		.withCull(false)
		.build());

	public static final RenderType GLOW = RenderType.create("redplanet_vehicle_glow",
		RenderSetup.builder(GLOW_PIPELINE).setOitPipelines(RenderPipelines.OIT_LIGHTNING).sortOnUpload().createRenderSetup());

	private static boolean texturesRegistered;

	private StarshipRenderTypes() {
	}

	/** Opaque hull and the alpha-tested grid fins and cabin windows, lit, back faces culled. */
	public static RenderType hull(Identifier texture) {
		return RenderTypes.entityCutoutCull(texture);
	}

	/** Frost on the tanks: translucent, over the hull. */
	public static RenderType frost(Identifier texture) {
		return RenderTypes.entityTranslucentCull(texture);
	}

	/** Cabin lights in the windows: emissive. */
	public static RenderType lights() {
		return RenderTypes.eyes(SHIP_LIGHTS_TEXTURE);
	}

	/** Registers the hull atlases with mipmaps (once; the texture manager reloads them with resource packs). */
	public static void registerTextures() {
		if (texturesRegistered) {
			return;
		}
		texturesRegistered = true;
		TextureManager textures = Minecraft.getInstance().getTextureManager();
		for (Identifier id : new Identifier[]{SHIP_TEXTURE, BOOSTER_TEXTURE}) {
			textures.registerAndLoad(id, new MipmappedTexture(id, 4));
		}
		// The frost is a soft translucent layer: average its alpha plainly, or a faint tint covers the hull far away.
		for (Identifier id : new Identifier[]{SHIP_FROST_TEXTURE, BOOSTER_FROST_TEXTURE}) {
			textures.registerAndLoad(id, new MeanMipmappedTexture(id, 4));
		}
	}

	/** Forces class initialization (pipeline registration) from the client entrypoint. */
	public static void init() {
	}
}
