package io.github.avi130805.redplanet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import io.github.avi130805.redplanet.environment.PlanetEnvironment;

import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

/**
 * Boat buoyancy uses getDefaultGravity() directly; buoyancy scales with g (Archimedes), so scale it like gravity
 * or boats in habitat pools would float too high and bob.
 */
@Mixin(AbstractBoat.class)
abstract class AbstractBoatBuoyancyMixin {
	@ModifyExpressionValue(method = "floatBoat", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;getDefaultGravity()D"))
	private double redplanet$scaleBuoyancy(double gravity) {
		return gravity * PlanetEnvironment.gravity(((AbstractBoat) (Object) this).level());
	}
}
