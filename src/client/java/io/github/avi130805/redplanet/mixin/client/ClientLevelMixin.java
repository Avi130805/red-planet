package io.github.avi130805.redplanet.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.github.avi130805.redplanet.client.sky.AltitudeSky;
import io.github.avi130805.redplanet.client.sky.MarsSkyClient;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;

/** Appends the Mars sky-colour layers (real Sun altitude and dust), then the high-altitude darkening, after vanilla's. */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Inject(method = "addEnvironmentAttributeLayers", at = @At("RETURN"))
	private void redplanet$marsSkyLayers(EnvironmentAttributeSystem.Builder builder,
			CallbackInfoReturnable<EnvironmentAttributeSystem.Builder> cir) {
		MarsSkyClient.addLayers(cir.getReturnValue(), (ClientLevel) (Object) this);
		AltitudeSky.addLayers(cir.getReturnValue(), (ClientLevel) (Object) this);
	}
}
