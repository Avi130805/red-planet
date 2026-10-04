package io.github.avi130805.redplanet.mixin.client;

import io.github.avi130805.redplanet.client.starship.flight.CinematicCamera;

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
 * The flight's cinematic camera. {@code alignWithEntity} places the camera on the player every frame; at its end the
 * cinematic pose replaces it, and {@code update} then builds the frustum and projection from it. The pose's full
 * orientation is written into the camera's rotation (vanilla's yaw and pitch can't roll, and the cabin view rolls with
 * the ship), with the direction vectors recomputed the way {@code setRotation} does. The field of view is swapped the
 * same way.
 */
@Mixin(Camera.class)
abstract class CameraMixin {
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
	private void redplanet$cinematicPose(float partialTicks, CallbackInfo ci) {
		CinematicCamera.Pose pose = CinematicCamera.INSTANCE.update(partialTicks);
		if (pose != null) {
			this.setPosition(pose.position());
			this.setRotation(pose.yaw(), pose.pitch()); // keeps xRot and yRot meaningful for code that reads them
			this.rotation.set(pose.orientation());
			this.forwards.set(0.0F, 0.0F, -1.0F).rotate(this.rotation);
			this.up.set(0.0F, 1.0F, 0.0F).rotate(this.rotation);
			this.left.set(-1.0F, 0.0F, 0.0F).rotate(this.rotation);
			this.matrixPropertiesDirty |= 3;
			// Outside the player, draw the world (and the player) as seen from there; in the cabin they are the camera.
			this.detached = !pose.firstPerson();
		}
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void redplanet$cinematicFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		CinematicCamera.Pose pose = CinematicCamera.INSTANCE.current();
		if (pose != null) {
			cir.setReturnValue(pose.fov());
		}
	}
}
