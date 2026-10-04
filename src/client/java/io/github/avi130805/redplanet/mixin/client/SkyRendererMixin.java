package io.github.avi130805.redplanet.mixin.client;

import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;

import io.github.avi130805.redplanet.client.sky.MarsCelestials;
import io.github.avi130805.redplanet.client.sky.MarsSkyData;

import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.world.level.MoonPhase;

/**
 * The Mars sky. On Mars (when {@code MarsSkyClient} attached a {@link MarsSkyData} to the sky state) the vanilla
 * sun, moon and random stars are replaced by {@link MarsCelestials}, drawn in the same open "Sky" pass, and the
 * horizon sunrise fan is replaced by the Sun-centred aureole. Everywhere else vanilla runs untouched. There is no
 * Fabric sky hook in 26.3.
 */
@Mixin(SkyRenderer.class)
abstract class SkyRendererMixin {
	@Shadow
	@Final
	private TextureAtlas celestialsAtlas;

	@Unique
	private MarsCelestials redplanet$mars;
	@Unique
	private MarsSkyData redplanet$frame;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void redplanet$init(TextureManager textureManager, AtlasManager atlasManager, RenderTarget renderTarget, CallbackInfo ci) {
		this.redplanet$mars = new MarsCelestials(this.celestialsAtlas);
	}

	@Inject(method = "close", at = @At("TAIL"))
	private void redplanet$close(CallbackInfo ci) {
		if (this.redplanet$mars != null) {
			this.redplanet$mars.close();
		}
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void redplanet$frame(GpuBufferSlice skyFog, SkyRenderState state, CallbackInfo ci) {
		this.redplanet$frame = state.getData(MarsSkyData.KEY);
	}

	@Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
	private void redplanet$noHorizonFan(RenderPass renderPass, PoseStack poseStack, float sunAngle, Vector4fc color, CallbackInfo ci) {
		if (this.redplanet$frame != null) {
			ci.cancel();
		}
	}

	@Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
	private void redplanet$marsBodies(RenderPass renderPass, PoseStack poseStack, float sunAngle, float moonAngle, float starAngle,
			MoonPhase moonPhase, float rainBrightness, float starBrightness, CallbackInfo ci) {
		if (this.redplanet$frame != null && this.redplanet$mars != null) {
			this.redplanet$mars.draw(renderPass, this.redplanet$frame);
			ci.cancel();
		}
	}
}
