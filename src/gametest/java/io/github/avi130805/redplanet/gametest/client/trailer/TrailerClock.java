package io.github.avi130805.redplanet.gametest.client.trailer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.util.Util;

/**
 * Game time for the parts of the mod that animate by the wall clock (the flight's cinematic camera, the transfer
 * screen). While the trailer is filmed a frame takes seconds to render, so those animations would race ahead; this
 * clock instead counts the ticks the world actually ran, plus the frame's partial tick. {@code TrailerClockMixin}
 * routes their {@code Util.getMillis()} calls here; until {@link #start()} it is the wall clock.
 */
public final class TrailerClock {
	private static volatile boolean active;
	private static volatile long ticks;
	private static volatile float partial;
	private static boolean registered;

	private TrailerClock() {
	}

	public static long millis() {
		return active ? ticks * 50L + (long) (partial * 50.0F) : Util.getMillis();
	}

	/** Starts game-time keeping. Call before anything that animates (a flight) begins, so its clock never jumps. */
	public static void start() {
		if (!registered) {
			registered = true;
			ClientTickEvents.END_CLIENT_TICK.register(mc -> {
				if (active && mc.level != null && mc.level.tickRateManager().runsNormally()) {
					ticks++;
				}
			});
		}
		ticks = 0L;
		partial = 0.0F;
		active = true;
	}

	public static void stop() {
		active = false;
	}

	static void partial(float value) {
		partial = value;
	}
}
