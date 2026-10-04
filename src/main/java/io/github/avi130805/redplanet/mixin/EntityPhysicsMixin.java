package io.github.avi130805.redplanet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import io.github.avi130805.redplanet.environment.Combustion;
import io.github.avi130805.redplanet.environment.PlanetEnvironment;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Planet physics for every entity:
 * <ul>
 * <li>gravity: {@code getGravity()} is final and is the single path every entity's gravity takes (both sides);</li>
 * <li>fall damage: the fall distance accumulates Earth-equivalent height (proportional to impact energy m*g*h),
 * so the safe fall on Mars is 3 / 0.379 = 7.9 blocks and every vanilla threshold stays consistent;</li>
 * <li>burning: nothing can be set on fire without oxygen.</li>
 * </ul>
 */
@Mixin(Entity.class)
abstract class EntityPhysicsMixin {
	@Shadow
	public abstract Level level();

	@Shadow
	public abstract BlockPos blockPosition();

	@ModifyReturnValue(method = "getGravity", at = @At("RETURN"))
	private double redplanet$scaleGravity(double gravity) {
		return gravity == 0.0 ? 0.0 : gravity * PlanetEnvironment.gravity(this.level());
	}

	@ModifyArg(
		method = {"move", "doCheckFallDamage"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;checkFallDamage(DZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V"),
		index = 0
	)
	private double redplanet$scaleFallDistance(double ya) {
		return ya * PlanetEnvironment.gravity(this.level());
	}

	@ModifyVariable(method = "setRemainingFireTicks", at = @At("HEAD"), argsOnly = true)
	private int redplanet$noFireWithoutOxygen(int ticks) {
		if (ticks > 0 && !Combustion.canBurnAt(this.level(), this.blockPosition())) {
			return 0;
		}
		return ticks;
	}
}
