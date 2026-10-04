package io.github.avi130805.redplanet.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import io.github.avi130805.redplanet.starship.entity.VehicleEntity;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Vanilla stops sending an entity to a player while the entity's chunk is still queued for that player. A flying ship
 * that skips ahead jumps hundreds of blocks into a chunk its crew's client hasn't received yet, so the ship would be
 * taken off their client and sent again a moment later, dismounting and remounting everyone aboard. Its own crew keeps
 * it; the client holds it until the chunk arrives.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class VehicleTrackingMixin {
	@Shadow
	@Final
	private Entity entity;

	@ModifyExpressionValue(method = "updatePlayer", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/server/level/ChunkMap;isChunkTracked(Lnet/minecraft/server/level/ServerPlayer;II)Z"))
	private boolean redplanet$crewKeepsTheirShip(boolean tracked, @Local(argsOnly = true) ServerPlayer player) {
		return tracked || this.entity instanceof VehicleEntity && this.entity.hasIndirectPassenger(player);
	}
}
