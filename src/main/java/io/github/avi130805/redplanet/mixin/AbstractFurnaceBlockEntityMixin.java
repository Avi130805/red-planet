package io.github.avi130805.redplanet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;

import io.github.avi130805.redplanet.environment.Combustion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fuel-burning furnaces, smokers and blast furnaces need oxygen: no fuel is accepted where combustion is
 * impossible, and a lit furnace goes out (vanilla then flips its LIT state itself).
 */
@Mixin(AbstractFurnaceBlockEntity.class)
abstract class AbstractFurnaceBlockEntityMixin {
	@ModifyReturnValue(method = "getBurnDuration", at = @At("RETURN"))
	private int redplanet$noFuelWithoutOxygen(int duration, @Local(argsOnly = true) ServerLevel level) {
		return duration > 0 && !Combustion.canBurnAt(level, ((BlockEntity) (Object) this).getBlockPos()) ? 0 : duration;
	}

	@Inject(method = "serverTick", at = @At("HEAD"))
	private static void redplanet$goOut(ServerLevel level, BlockPos pos, BlockState state, AbstractFurnaceBlockEntity entity, CallbackInfo ci) {
		AbstractFurnaceAccessor furnace = (AbstractFurnaceAccessor) entity;
		if (furnace.redplanet$getLitTimeRemaining() > 0 && !Combustion.canBurnAt(level, pos)) {
			furnace.redplanet$setLitTimeRemaining(0);
		}
	}
}
