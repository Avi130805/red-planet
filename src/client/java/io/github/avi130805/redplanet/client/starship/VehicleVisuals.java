package io.github.avi130805.redplanet.client.starship;

import java.util.Optional;

import io.github.avi130805.redplanet.registry.RPDimensions;
import io.github.avi130805.redplanet.starship.entity.StarshipEntity;
import io.github.avi130805.redplanet.starship.entity.SuperHeavyEntity;
import io.github.avi130805.redplanet.starship.entity.VehicleEntity;
import io.github.avi130805.redplanet.starship.flight.FlightProfile;
import io.github.avi130805.redplanet.starship.flight.FlightSegment;
import io.github.avi130805.redplanet.starship.flight.PhaseDef;
import io.github.avi130805.redplanet.starship.flight.TelemetryTrack;

import net.minecraft.util.Mth;

/**
 * What a vehicle looks like at a moment of its flight, derived from the flight profile and mission time, so every
 * client animates the same way without extra network traffic: legs, flaps, engine gimbals, which engines burn, frost
 * on the tanks and entry plasma.
 */
public final class VehicleVisuals {
	/** Flap angles (radians, positive tucks toward the leeward side) for each flight regime. */
	private static final float FLAPS_NEUTRAL = 0.35F;
	private static final float FLAPS_TUCKED = 0.95F;

	public boolean flying;
	/** Not drawn: between worlds (the transfer). */
	public boolean hidden;
	/** Landing legs: 0 stowed, 1 deployed. */
	public float legs;
	public float flapsFore;
	public float flapsAft;
	/** Gimbal of the ship's three sea-level engines, or of the booster's inner 13 (index 0), radians. */
	public final float[] gimbal = new float[3];
	/** Raptors burning. */
	public int engines;
	/** Frost on the tanks, 0..1. */
	public float frost;
	/** Entry plasma glow, 0..1. */
	public float plasma;
	/** Grid fin deflection (booster), radians. */
	public float fins;
	/** Hot staging: the ship's engines blasting against the booster's vented ring, 0..1 (booster only). */
	public float stagingFlash;
	/** Real altitude, km (plumes widen as the air thins). */
	public double altitudeKm;
	/** Whether the plume is in near-vacuum (above ~40 km on Earth; Mars' air is 1 % as thick, so most of a Mars flight). */
	public boolean thinAir;

	public void compute(VehicleEntity vehicle, float partialTick) {
		this.reset();
		Optional<FlightProfile> found = vehicle.profile();
		boolean mars = RPDimensions.MARS.equals(vehicle.level().dimension());
		if (!vehicle.isFlying() || found.isEmpty()) {
			if (vehicle instanceof StarshipEntity ship) {
				this.legs = ship.restingLegs();
			}
			this.thinAir = mars;
			return;
		}
		FlightProfile profile = found.get();
		double t = vehicle.missionTime(partialTick);
		int phaseIndex = Math.min(vehicle.phase(), profile.phases().size() - 1);
		PhaseDef phase = profile.phases().get(phaseIndex);
		FlightSegment segment = phase.segment();
		this.flying = true;
		this.hidden = segment == FlightSegment.TRANSFER;
		if (vehicle instanceof SuperHeavyEntity) {
			this.booster(profile, t, segment);
		} else {
			this.ship(profile, t, segment);
		}
		this.thinAir = mars || this.altitudeKm > 40.0;
	}

	private void reset() {
		this.flying = false;
		this.hidden = false;
		this.legs = 0.0F;
		this.flapsFore = FLAPS_NEUTRAL;
		this.flapsAft = FLAPS_NEUTRAL;
		this.gimbal[0] = this.gimbal[1] = this.gimbal[2] = 0.0F;
		this.engines = 0;
		this.frost = 0.0F;
		this.plasma = 0.0F;
		this.fins = 0.0F;
		this.stagingFlash = 0.0F;
		this.altitudeKm = 0.0;
		this.thinAir = false;
	}

	private void ship(FlightProfile profile, double t, FlightSegment segment) {
		TelemetryTrack.Sample sample = profile.ship().sample(t);
		boolean stacked = t < profile.stagingTime();
		this.altitudeKm = (stacked ? profile.booster().sample(t) : sample).altitudeKm();
		this.engines = sample.engines();
		this.frost = frost(sample, segment, t);
		switch (segment) {
			case ASCENT -> {
				this.flapsFore = FLAPS_TUCKED;
				this.flapsAft = FLAPS_TUCKED;
			}
			case DESCENT -> this.descent(profile, t);
			default -> {
			}
		}
		if (segment == FlightSegment.DESCENT || segment == FlightSegment.LANDED) {
			double deploy = profile.eventTime("legs_deploy").orElse(Double.POSITIVE_INFINITY);
			this.legs = segment == FlightSegment.LANDED ? 1.0F : (float) Mth.clamp((t - deploy) / 3.0, 0.0, 1.0);
		} else if (profile.vehicle() == FlightProfile.Vehicle.SHIP && segment != FlightSegment.TRANSFER) {
			// Launching on its own the ship stands on its legs, and folds them a few seconds after liftoff.
			this.legs = (float) Mth.clamp(1.0 - t / 6.0, 0.0, 1.0);
		}
		double flip = profile.eventTime("flip").orElse(Double.NaN);
		boolean flipping = segment == FlightSegment.DESCENT && t >= flip && t < flip + 8.0;
		for (int i = 0; i < 3; i++) {
			if (this.engines > i || this.engines >= 6) {
				double amplitude = flipping ? 0.15 : 0.025;
				this.gimbal[i] = (float) (amplitude * Math.sin(t * 2.7 + i * 2.1));
			}
		}
	}

	/** Entry with the flaps steering, the belly flop with active flap control, then flaps parked for the landing burn. */
	private void descent(FlightProfile profile, double t) {
		double bellyFlop = profile.eventTime("belly_flop").orElse(Double.POSITIVE_INFINITY);
		double flip = profile.eventTime("flip").orElse(Double.POSITIVE_INFINITY);
		double w = 1.3;
		if (t < bellyFlop) {
			this.flapsFore = (float) (0.7 + 0.05 * Math.sin(t * w));
			this.flapsAft = (float) (0.2 + 0.05 * Math.sin(t * w + 1.0));
		} else if (t < flip) {
			this.flapsFore = (float) (0.5 + 0.12 * Math.sin(t * w));
			this.flapsAft = (float) (0.25 + 0.12 * Math.sin(t * w + 1.3));
		} else {
			float k = (float) Mth.clamp((t - flip) / 4.0, 0.0, 1.0);
			this.flapsFore = Mth.lerp(k, 0.5F, 1.3F);
			this.flapsAft = Mth.lerp(k, 0.25F, 0.0F);
		}
		double entry = profile.eventTime("entry_interface").orElse(Double.NaN);
		double peak = profile.eventTime("peak_heating").orElse(Double.NaN);
		double end = profile.eventTime("plasma_end").orElse(Double.NaN);
		if (!Double.isNaN(entry) && !Double.isNaN(end) && t > entry && t < end) {
			double top = Double.isNaN(peak) ? (entry + end) * 0.5 : peak;
			double k = t < top ? (t - entry) / Math.max(1.0, top - entry) : 1.0 - (t - top) / Math.max(1.0, end - top);
			this.plasma = (float) Mth.clamp(Math.sqrt(Math.max(0.0, k)), 0.0, 1.0);
		}
	}

	private void booster(FlightProfile profile, double t, FlightSegment segment) {
		TelemetryTrack.Sample sample = profile.booster().sample(t);
		this.altitudeKm = sample.altitudeKm();
		this.engines = sample.engines();
		this.frost = frost(sample, segment, t);
		double staging = profile.stagingTime();
		if (t >= staging) {
			this.fins = (float) (0.3 * Math.sin(t * 1.1));
			this.stagingFlash = (float) Mth.clamp(1.0 - (t - staging) / 4.0, 0.0, 1.0);
		}
		if (this.engines > 0) {
			this.gimbal[0] = (float) (0.03 * Math.sin(t * 2.3));
		}
	}

	/** Frost while the tanks are full on the pad; the ascent shakes it off within a minute or two. */
	private static float frost(TelemetryTrack.Sample sample, FlightSegment segment, double t) {
		double fill = Mth.clamp((sample.lox() + sample.ch4()) * 0.5, 0.0, 1.0);
		return switch (segment) {
			case ORIGIN_PAD -> (float) fill;
			case ASCENT -> (float) (fill * Mth.clamp(1.0 - t / 90.0, 0.0, 1.0));
			default -> 0.0F;
		};
	}
}
