package io.github.avi130805.redplanet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;

import io.github.avi130805.redplanet.environment.Combustion;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * No flames without oxygen. Every way fire appears (flint and steel, fire charges, dispensers, lightning, lava,
 * explosions, fireballs, spread) checks the state-level canSurvive before placing or in onPlace right after, and
 * torch/lantern placement checks it too, so one hook covers them all. Tag-gated, so the hot path is a tag test.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateCombustionMixin {
	@ModifyReturnValue(method = "canSurvive", at = @At("RETURN"))
	private boolean redplanet$needsOxygen(boolean survives, @Local(argsOnly = true) LevelReader level, @Local(argsOnly = true) BlockPos pos) {
		return survives && !Combustion.forbids((BlockState) (Object) this, level, pos);
	}
}
