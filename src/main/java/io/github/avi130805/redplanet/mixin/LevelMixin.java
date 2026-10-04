package io.github.avi130805.redplanet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;

import io.github.avi130805.redplanet.environment.Combustion;
import io.github.avi130805.redplanet.registry.RPDimensions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Two level-wide rules:
 * <ul>
 * <li>Weather: vanilla weather is one server-wide state that every skylit, ceiling-less dimension shares (and
 * ticks), and rain packets go to all players. Mars never rains, so our dimensions opt out on both sides and
 * report zero rain/thunder (the client would otherwise inherit the overworld's rain level and dim the sky).</li>
 * <li>Combustion: every way a campfire/candle gets lit ends in setBlock with LIT=true; force it off where there
 * is no oxygen.</li>
 * </ul>
 */
@Mixin(Level.class)
abstract class LevelMixin {
	@ModifyReturnValue(method = "canHaveWeather", at = @At("RETURN"))
	private boolean redplanet$noWeather(boolean canHaveWeather) {
		return canHaveWeather && !RPDimensions.isRedPlanetDimension((Level) (Object) this);
	}

	@ModifyReturnValue(method = "getRainLevel", at = @At("RETURN"))
	private float redplanet$noRain(float rain) {
		return RPDimensions.isRedPlanetDimension((Level) (Object) this) ? 0.0F : rain;
	}

	@ModifyReturnValue(method = "getThunderLevel", at = @At("RETURN"))
	private float redplanet$noThunder(float thunder) {
		return RPDimensions.isRedPlanetDimension((Level) (Object) this) ? 0.0F : thunder;
	}

	@ModifyVariable(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), argsOnly = true)
	private BlockState redplanet$extinguish(BlockState state, @Local(argsOnly = true) BlockPos pos) {
		return Combustion.extinguishIfNeeded(state, (Level) (Object) this, pos);
	}
}
