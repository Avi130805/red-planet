package io.github.avi130805.redplanet.starship.entity;

import io.github.avi130805.redplanet.registry.RPDimensions;
import io.github.avi130805.redplanet.registry.RPParticles;
import io.github.avi130805.redplanet.registry.RPSounds;
import io.github.avi130805.redplanet.starship.flight.FlightProfile;
import io.github.avi130805.redplanet.starship.flight.FlightSegment;
import io.github.avi130805.redplanet.starship.flight.PhaseDef;
import io.github.avi130805.redplanet.starship.flight.TelemetryTrack;
import io.github.avi130805.redplanet.starship.geometry.StarshipGeometry;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3f;

/**
 * Client-side flight effects that follow from the timeline alone: steam rolling out across the pad (or Mars dust under
 * a landing), the contrail on the way up, boil-off venting on the pad, and the sounds of the events as they pass
 * (ignition, staging, sonic booms, legs, touchdown). Sounds play with the speed-of-sound delay, so a spectator a
 * kilometre away hears liftoff three seconds after seeing it. Runs on the client only (the level calls are client-side
 * no-ops on a server).
 */
final class FlightEffects {
	/** Events the booster plays in a stack flight (the stack's engines are its engines); the ship plays the rest. */
	private static final java.util.Set<String> BOOSTER_EVENTS = java.util.Set.of("ignition", "deluge", "liftoff", "meco", "hot_staging",
		"boostback_start", "boostback_end", "booster_landing_burn", "booster_catch");

	private FlightEffects() {
	}

	static void tick(VehicleEntity vehicle, FlightProfile profile, double t, double last) {
		Level level = vehicle.level();
		if (!level.isClientSide()) {
			return;
		}
		boolean booster = vehicle instanceof SuperHeavyEntity;
		boolean mars = RPDimensions.MARS.equals(level.dimension());
		TelemetryTrack.Sample sample = (booster ? profile.booster() : profile.ship()).sample(t);
		FlightSegment segment = vehicle.segment().orElse(FlightSegment.ASCENT);
		RandomSource random = vehicle.getRandom();
		boolean stacked = !booster && t < profile.stagingTime();

		// Boil-off venting while propellant loads.
		if (segment == FlightSegment.ORIGIN_PAD && sample.lox() > 0.05 && random.nextInt(3) == 0) {
			double height = booster ? StarshipGeometry.BOOSTER_HEIGHT : StarshipGeometry.SHIP_HEIGHT;
			double a = random.nextDouble() * Math.PI * 2.0;
			Vec3 base = vehicle.position();
			double y = base.y + height * (0.25 + 0.6 * random.nextDouble());
			level.addAlwaysVisibleParticle(RPParticles.VENT, true, base.x + Math.cos(a) * 4.7, y, base.z + Math.sin(a) * 4.7,
				Math.cos(a) * 0.12, 0.02, Math.sin(a) * 0.12);
		}

		int engines = stacked ? 0 : sample.engines();
		if (engines > 0) {
			Vector3f down = vehicle.attitude.transform(new Vector3f(0.0F, -1.0F, 0.0F));
			Vec3 nozzle = vehicle.position().add(down.x * 2.0, down.y * 2.0, down.z * 2.0);
			double groundY = vehicle.reference().y - (booster ? -StarshipGeometry.BOOSTER_ENGINE_EXIT_Y : StarshipGeometry.LANDED_SKIRT_HEIGHT);
			double above = nozzle.y - groundY;
			double strength = Math.min(1.0, engines / (booster ? 20.0 : 3.0));
			// The jet hits the ground: steam (or dust) rolls outward in a ring.
			if (above < 150.0 && down.y < -0.5) {
				double k = 1.0 - above / 150.0;
				int count = (int) Math.ceil(strength * k * (booster ? 7 : 4));
				for (int i = 0; i < count; i++) {
					double a = random.nextDouble() * Math.PI * 2.0;
					double r = random.nextDouble() * 6.0;
					double v = (0.5 + random.nextDouble() * 0.9) * (0.4 + 0.6 * k);
					level.addAlwaysVisibleParticle(mars ? RPParticles.DUST_CLOUD : RPParticles.STEAM_CLOUD, true,
						nozzle.x + Math.cos(a) * r, groundY + 1.0 + random.nextDouble() * 2.0, nozzle.z + Math.sin(a) * r,
						Math.cos(a) * v, 0.04 + random.nextDouble() * 0.08, Math.sin(a) * v);
				}
			}
			// Contrail and exhaust smoke in Earth's lower atmosphere.
			if (!mars && sample.altitudeKm() < 18.0 && above > 30.0) {
				int count = booster ? 2 : 1;
				for (int i = 0; i < count; i++) {
					level.addAlwaysVisibleParticle(RPParticles.STEAM_CLOUD, true, nozzle.x + down.x * 14.0 + random.nextGaussian(),
						nozzle.y + down.y * 14.0, nozzle.z + down.z * 14.0 + random.nextGaussian(),
						down.x * 0.25 + random.nextGaussian() * 0.03, down.y * 0.25, down.z * 0.25 + random.nextGaussian() * 0.03);
				}
			}
		}

		if (Double.isNaN(last) || t <= last) {
			return;
		}
		// The last ten seconds of the count.
		if (!booster && t < 0.0 && t >= -10.0 && Math.floor(t) != Math.floor(last)) {
			play(vehicle, RPSounds.ROCKET_COUNTDOWN_BEEP, 1.0F, 1.0F);
		}
		for (PhaseDef phase : profile.phases()) {
			if (phase.missionEnd() < last || phase.missionStart() > t) {
				continue;
			}
			for (PhaseDef.Event event : phase.events()) {
				double at = phase.missionTimeAt(event.at());
				if (at > last && at <= t && plays(event.type(), booster, profile)) {
					eventSound(vehicle, event.type());
				}
			}
		}
	}

	private static boolean plays(String type, boolean booster, FlightProfile profile) {
		if (profile.vehicle() != FlightProfile.Vehicle.STACK) {
			return !booster;
		}
		return booster == BOOSTER_EVENTS.contains(type);
	}

	private static void eventSound(VehicleEntity vehicle, String type) {
		switch (type) {
			case "ignition", "boostback_start", "landing_burn" -> play(vehicle, RPSounds.ROCKET_RAPTOR_IGNITION, 6.0F, 0.9F);
			case "flip" -> play(vehicle, RPSounds.ROCKET_RAPTOR_IGNITION, 4.0F, 1.05F);
			case "deluge" -> play(vehicle, RPSounds.ROCKET_DELUGE, 5.0F, 1.0F);
			case "liftoff" -> play(vehicle, RPSounds.ROCKET_GO_TONE, 1.0F, 1.0F);
			case "meco", "seco", "engine_cutoff" -> play(vehicle, RPSounds.ROCKET_ENGINE_CUTOFF, 4.0F, 1.0F);
			case "hot_staging" -> {
				play(vehicle, RPSounds.ROCKET_HOT_STAGING, 8.0F, 1.0F);
				play(vehicle, RPSounds.ROCKET_STAGE_SEPARATION, 2.0F, 1.0F);
			}
			case "booster_landing_burn" -> {
				// Coming home supersonic: the double sonic boom reaches the pad before the landing burn's roar.
				play(vehicle, RPSounds.ROCKET_SONIC_BOOM, 10.0F, 1.0F);
				play(vehicle, RPSounds.ROCKET_RAPTOR_IGNITION, 6.0F, 0.9F);
			}
			case "booster_catch" -> play(vehicle, RPSounds.ROCKET_CHOPSTICKS, 4.0F, 1.0F);
			case "entry_interface" -> play(vehicle, RPSounds.ROCKET_ENTRY_PLASMA, 3.0F, 1.0F);
			case "belly_flop" -> play(vehicle, RPSounds.ROCKET_FLAP_ACTUATOR, 1.5F, 1.0F);
			case "legs_deploy" -> play(vehicle, RPSounds.ROCKET_LANDING_LEGS, 2.0F, 1.0F);
			case "touchdown" -> play(vehicle, RPSounds.ROCKET_TOUCHDOWN, 4.0F, 1.0F);
			case "vent", "propellant_load" -> play(vehicle, RPSounds.ROCKET_VENT, 2.0F, 1.0F);
			default -> {
			}
		}
	}

	private static void play(VehicleEntity vehicle, SoundEvent sound, float volume, float pitch) {
		Vec3 p = vehicle.position();
		vehicle.level().playLocalSound(p.x, p.y, p.z, sound, SoundSource.NEUTRAL, volume, pitch, true);
	}
}
