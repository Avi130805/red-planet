package io.github.avi130805.redplanet.gametest.mixin;

import io.github.avi130805.redplanet.gametest.client.trailer.TrailerClock;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Gametests only: the transfer screen animates by {@link TrailerClock} (see {@code CinematicCameraClockMixin}). */
@Mixin(targets = "io.github.avi130805.redplanet.client.starship.interlude.InterludeScreen")
abstract class InterludeScreenClockMixin {
	@Redirect(method = {"<init>", "update", "tick", "extractBackground", "extractRenderState", "stars"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;getMillis()J"), require = 0)
	private long redplanetTrailer$millis() {
		return TrailerClock.millis();
	}
}
