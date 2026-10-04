package io.github.avi130805.redplanet.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import io.github.avi130805.redplanet.starship.entity.VehicleEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;

/**
 * Vanilla draws an entity only if the chunk section holding its feet is compiled and visible, which is right for
 * mobs but hides a 124 m rocket whenever its base is behind a hill, outside the camera's render area (a pad camera far
 * from the crew), or not meshed yet. Vehicles pass that test as if they stood above the build height; the frustum and
 * distance checks before it still apply.
 */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {
	@ModifyExpressionValue(method = "isEntityVisible", at = @At(value = "INVOKE", target = "isOutsideBuildHeight(I)Z"))
	private boolean redplanet$vehiclesIgnoreSections(boolean outside, @Local(argsOnly = true) Entity entity) {
		return outside || entity instanceof VehicleEntity;
	}
}
