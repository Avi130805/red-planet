package io.github.avi130805.redplanet.mixin.client;

import com.mojang.renderpearl.api.textures.GpuTextureView;

import io.github.avi130805.redplanet.client.starship.flight.CinematicCamera;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/** The first-person hand is drawn whenever the camera type is first person, even with the cinematic camera elsewhere. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void redplanet$hideHandWhileFilming(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depth, CallbackInfo ci) {
		if (CinematicCamera.INSTANCE.isActive()) {
			ci.cancel();
		}
	}
}
