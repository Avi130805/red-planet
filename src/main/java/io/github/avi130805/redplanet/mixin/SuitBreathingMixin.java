package io.github.avi130805.redplanet.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import io.github.avi130805.redplanet.suit.SpaceSuit;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.world.entity.LivingEntity;

/**
 * A sealed spacesuit with oxygen doesn't care what is outside it, water included: vanilla drowning asks
 * {@code canBreatheUnderwater}, and the suit's oxygen is used by {@code SpaceSuit}'s server tick.
 */
@Mixin(LivingEntity.class)
abstract class SuitBreathingMixin {
	@ModifyReturnValue(method = "canBreatheUnderwater", at = @At("RETURN"))
	private boolean redplanet$sealedSuitBreathes(boolean original) {
		return original || SpaceSuit.isSealed((LivingEntity) (Object) this);
	}
}
