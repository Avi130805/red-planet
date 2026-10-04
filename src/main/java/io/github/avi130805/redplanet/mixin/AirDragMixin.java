package io.github.avi130805.redplanet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import io.github.avi130805.redplanet.environment.AtmosphereModel;
import io.github.avi130805.redplanet.environment.PlanetEnvironment;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

/**
 * Thin air: every entity family's per-tick air drag goes through {@code getAirDrag()} (seven overrides in 26.3).
 * The drag term is scaled only while airborne, so ground friction (which multiplies the air drag for items and
 * XP orbs) and water/lava damping stay vanilla. Exactly 1 on Earth. Model: docs/SCIENCE.md, "Air drag".
 */
@Mixin({Entity.class, LivingEntity.class, ExperienceOrb.class, ThrowableProjectile.class, LlamaSpit.class,
	AbstractArrow.class, AbstractMinecart.class})
abstract class AirDragMixin {
	@ModifyReturnValue(method = "getAirDrag", at = @At("RETURN"))
	private float redplanet$thinAir(float drag) {
		Entity self = (Entity) (Object) this;
		if (self.onGround()) {
			return drag;
		}
		double scale = PlanetEnvironment.dragScale(self.level(), self.position());
		return scale == 1.0 ? drag : AtmosphereModel.scaleDrag(drag, scale);
	}
}
