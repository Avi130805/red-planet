package io.github.avi130805.redplanet.mixin;

import io.github.avi130805.redplanet.starship.entity.StarshipEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.player.Player;

/**
 * Keeps the crew in their couches from launch to touchdown. Sneaking dismounts in {@code Player.rideTick} through
 * {@code wantsToStopRiding()}, and Fabric has no dismount event, so this is the smallest hook.
 */
@Mixin(Player.class)
abstract class PlayerSeatLockMixin {
	@Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
	private void redplanet$stayInFlight(CallbackInfoReturnable<Boolean> cir) {
		if (((Player) (Object) this).getVehicle() instanceof StarshipEntity ship && ship.isDismountLocked()) {
			cir.setReturnValue(false);
		}
	}
}
