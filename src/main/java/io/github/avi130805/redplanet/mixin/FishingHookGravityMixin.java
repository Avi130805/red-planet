package io.github.avi130805.redplanet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import io.github.avi130805.redplanet.environment.PlanetEnvironment;

import net.minecraft.world.entity.projectile.FishingHook;

/** The fishing hook applies getDefaultGravity() directly in tick(), bypassing getGravity(); scale it too. */
@Mixin(FishingHook.class)
abstract class FishingHookGravityMixin {
	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/FishingHook;getDefaultGravity()D"))
	private double redplanet$scaleGravity(double gravity) {
		return gravity * PlanetEnvironment.gravity(((FishingHook) (Object) this).level());
	}
}
