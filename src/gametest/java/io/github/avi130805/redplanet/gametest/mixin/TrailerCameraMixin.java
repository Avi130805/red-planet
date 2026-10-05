package io.github.avi130805.redplanet.gametest.mixin;

import io.github.avi130805.redplanet.gametest.client.trailer.TrailerCamera;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

/**
 * Gametests only: the trailer's scripted camera. The pose runs after the mod's own camera mixin (higher priority
 * number), so a trailer pose wins over the flight's cinematic camera. The camera stays "attached", so the (spectating)
 * player isn't drawn wherever the camera happens to be.
 */
@Mixin(value = Camera.class, priority = 2000)
abstract class TrailerCameraMixin {
	@Shadow
	private boolean detached;

	@Shadow
	@Final
	private Quaternionf rotation;

	@Shadow
	@Final
	private Vector3f forwards;

	@Shadow
	@Final
	private Vector3f up;

	@Shadow
	@Final
	private Vector3f left;

	@Shadow
	private int matrixPropertiesDirty;

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void redplanetTrailer$pose(float partialTicks, CallbackInfo ci) {
		TrailerCamera.Pose pose = TrailerCamera.get();
		if (pose != null) {
			this.setPosition(pose.position());
			this.setRotation(pose.yaw(), pose.pitch());
			this.rotation.set(pose.orientation());
			this.forwards.set(0.0F, 0.0F, -1.0F).rotate(this.rotation);
			this.up.set(0.0F, 1.0F, 0.0F).rotate(this.rotation);
			this.left.set(-1.0F, 0.0F, 0.0F).rotate(this.rotation);
			this.matrixPropertiesDirty |= 3;
			this.detached = false;
		}
	}

	/**
	 * At the head, not the return: the mod's cinematic FOV handler sits at the return and cancels it, and a cancelled
	 * return skips every handler after it, so a trailer FOV there would be dropped whenever the flight camera is live.
	 */
	@Inject(method = "calculateFov", at = @At("HEAD"), cancellable = true)
	private void redplanetTrailer$fov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		TrailerCamera.Pose pose = TrailerCamera.get();
		if (pose != null) {
			cir.setReturnValue(pose.fov());
		}
	}
}
