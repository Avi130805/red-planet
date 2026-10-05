package io.github.avi130805.redplanet.gametest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import io.github.avi130805.redplanet.client.starship.flight.MissionControlScreen;

/** Gametests only: where a landing site sits on the mission control map, for the trailer's cursor. */
@Mixin(MissionControlScreen.class)
public interface MissionControlScreenAccessor {
	@Invoker("lonX")
	double redplanetTrailer$lonX(double lon);

	@Invoker("latY")
	double redplanetTrailer$latY(double lat);
}
