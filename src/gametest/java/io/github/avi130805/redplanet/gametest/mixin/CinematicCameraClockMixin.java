package io.github.avi130805.redplanet.gametest.mixin;

import io.github.avi130805.redplanet.gametest.client.trailer.TrailerClock;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Gametests only: the flight camera's wall-clock reads (shot timing, blends) go through {@link TrailerClock}, which is
 * the wall clock except while the trailer is filmed. Lenient ({@code require = 0}): if the camera is refactored the
 * game still starts, and the trailer would just time those moves by the wall clock.
 */
@Mixin(targets = "io.github.avi130805.redplanet.client.starship.flight.CinematicCamera")
abstract class CinematicCameraClockMixin {
	@Redirect(method = {"cycleMode", "evaluate"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;getMillis()J"), require = 0)
	private long redplanetTrailer$millis() {
		return TrailerClock.millis();
	}
}
