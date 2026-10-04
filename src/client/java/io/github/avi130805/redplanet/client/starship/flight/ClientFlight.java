package io.github.avi130805.redplanet.client.starship.flight;

import java.util.Optional;

import io.github.avi130805.redplanet.starship.entity.StarshipEntity;
import io.github.avi130805.redplanet.starship.entity.SuperHeavyEntity;
import io.github.avi130805.redplanet.starship.flight.FlightProfile;
import io.github.avi130805.redplanet.starship.flight.FlightSegment;
import io.github.avi130805.redplanet.starship.flight.PhaseDef;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

/** The local player's flight, as the client sees it: the ship they ride and its booster. */
public final class ClientFlight {
	private static @Nullable SuperHeavyEntity cachedBooster;
	private static long cachedAt = Long.MIN_VALUE;

	private ClientFlight() {
	}

	/** The ship the local player is aboard, if any. */
	public static @Nullable StarshipEntity ship() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.player.getVehicle() instanceof StarshipEntity ship ? ship : null;
	}

	/** The ship the local player is aboard, while it flies. */
	public static @Nullable StarshipEntity flyingShip() {
		StarshipEntity ship = ship();
		return ship != null && ship.isFlying() ? ship : null;
	}

	public static Optional<FlightSegment> segment() {
		StarshipEntity ship = flyingShip();
		return ship == null ? Optional.empty() : ship.segment();
	}

	public static boolean inTransfer() {
		return segment().orElse(null) == FlightSegment.TRANSFER;
	}

	/**
	 * The booster flying the same flight as {@code ship}: same profile, same pad, still in flight. Looked up at most once
	 * a tick.
	 */
	public static @Nullable SuperHeavyEntity booster(StarshipEntity ship) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return null;
		}
		long now = mc.level.getGameTime();
		if (now == cachedAt && (cachedBooster == null || !cachedBooster.isRemoved())) {
			return cachedBooster;
		}
		cachedAt = now;
		cachedBooster = null;
		if (ship.profile().map(p -> p.vehicle() != FlightProfile.Vehicle.STACK).orElse(true)) {
			return null;
		}
		Vec3 pad = ship.reference();
		double best = Double.MAX_VALUE;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof SuperHeavyEntity booster && booster.isFlying() && booster.profileId().equals(ship.profileId())
				&& booster.reference().distanceToSqr(pad) < 4.0) {
				double d = booster.distanceToSqr(ship);
				if (d < best) {
					best = d;
					cachedBooster = booster;
				}
			}
		}
		return cachedBooster;
	}

	/** Progress (0..1) through the ship's current phase. */
	public static double phaseProgress(StarshipEntity ship, FlightProfile profile, float partialTick) {
		int phase = Math.min(ship.phase(), profile.phases().size() - 1);
		return Math.min(1.0, ship.phaseTick(partialTick) / profile.phaseTicks(phase, ship.pacing()));
	}

	public static Optional<PhaseDef> phase(StarshipEntity ship) {
		return ship.currentPhase();
	}
}
