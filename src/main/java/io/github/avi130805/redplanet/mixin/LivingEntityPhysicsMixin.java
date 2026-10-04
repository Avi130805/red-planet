package io.github.avi130805.redplanet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import io.github.avi130805.redplanet.environment.AtmosphereModel;
import io.github.avi130805.redplanet.environment.Breathing;
import io.github.avi130805.redplanet.environment.PlanetEnvironment;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Living-entity physics on alien worlds:
 * <ul>
 * <li>vertical air drag (the single 0.98 in travelInAir) follows the thin air; the horizontal 0.91 is Minecraft's
 * movement-control damping and stays vanilla so walking and air control feel normal;</li>
 * <li>elytra aerodynamics scale with dynamic pressure (rho * v^2), blended against vacuum free fall;</li>
 * <li>breathing: no air refill and hypoxia damage where the air is unbreathable.</li>
 * </ul>
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityPhysicsMixin {
	@ModifyExpressionValue(method = "travelInAir", at = @At(value = "CONSTANT", args = "floatValue=0.98F"))
	private float redplanet$thinAirVertical(float drag) {
		LivingEntity self = (LivingEntity) (Object) this;
		double scale = PlanetEnvironment.dragScale(self.level(), self.position());
		return scale == 1.0 ? drag : AtmosphereModel.scaleDrag(drag, scale);
	}

	@WrapOperation(
		method = "travelFallFlying",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;updateFallFlyingMovement(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;")
	)
	private Vec3 redplanet$thinAirElytra(LivingEntity self, Vec3 movement, Operation<Vec3> original) {
		Vec3 vanilla = original.call(self, movement);
		double density = PlanetEnvironment.airDensity(self.level(), self.position());
		double k = AtmosphereModel.elytraFactor(density, movement.length());
		if (k >= 1.0) {
			return vanilla;
		}
		// In vacuum the update is pure free fall; blend the aerodynamic result toward it.
		Vec3 freeFall = movement.add(0.0, -self.getGravity(), 0.0);
		return freeFall.add(vanilla.subtract(freeFall).scale(k));
	}

	@ModifyReturnValue(method = "increaseAirSupply", at = @At("RETURN"))
	private int redplanet$noRefillInThinAir(int refilled, @Local(argsOnly = true) int currentSupply) {
		LivingEntity self = (LivingEntity) (Object) this;
		return Breathing.blocksRefill(self) ? currentSupply : refilled;
	}

	@Inject(method = "baseTick", at = @At("TAIL"))
	private void redplanet$hypoxia(CallbackInfo ci) {
		Breathing.tick((LivingEntity) (Object) this);
	}
}
