package io.github.avi130805.redplanet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.github.avi130805.redplanet.environment.RedPlanetLayers;

import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.level.Level;

/**
 * Appends Red Planet's environment-attribute layers (altitude pressure/density, habitat interiors) after the
 * dimension, biome, timeline and weather layers. Every ServerLevel and ClientLevel builds its attribute system
 * through addDefaultLayers -> addDynamicLayers, and Fabric API offers no hook for adding layers.
 */
@Mixin(EnvironmentAttributeSystem.Builder.class)
abstract class EnvironmentAttributeSystemBuilderMixin {
	@Inject(method = "addDynamicLayers", at = @At("RETURN"))
	private void redplanet$addLayers(Level level, CallbackInfoReturnable<EnvironmentAttributeSystem.Builder> cir) {
		RedPlanetLayers.install(cir.getReturnValue(), level);
	}
}
