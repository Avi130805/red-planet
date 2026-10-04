package io.github.avi130805.redplanet.client.sound;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.github.avi130805.redplanet.client.starship.VehicleVisuals;
import io.github.avi130805.redplanet.client.starship.flight.ClientFlight;
import io.github.avi130805.redplanet.registry.RPSounds;
import io.github.avi130805.redplanet.starship.entity.StarshipEntity;
import io.github.avi130805.redplanet.starship.entity.SuperHeavyEntity;
import io.github.avi130805.redplanet.starship.entity.VehicleEntity;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

/**
 * The roar of burning Raptors, as a loop that follows each vehicle. Super Heavy's 33 engines carry the furthest; in thin
 * air (high up, or anywhere on Mars) the roar fades, because sound needs air. Aboard, the crew hears the engines through
 * the structure: a low hum that never fades, plus the roar while the air is thick.
 */
public final class EngineSounds {
	private static final Map<UUID, Loop> LOOPS = new HashMap<>();
	private static Loop cabin;

	private EngineSounds() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(EngineSounds::tick);
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null) {
			LOOPS.clear();
			return;
		}
		LOOPS.values().removeIf(Loop::isStopped);
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity instanceof VehicleEntity vehicle && vehicle.isFlying() && !LOOPS.containsKey(vehicle.getUUID())) {
				SoundEvent sound = vehicle instanceof SuperHeavyEntity ? RPSounds.ROCKET_BOOSTER_ROAR : RPSounds.ROCKET_SHIP_ROAR;
				Loop loop = new Loop(vehicle, sound, false);
				if (loop.target() > 0.01F) {
					LOOPS.put(vehicle.getUUID(), loop);
					mc.getSoundManager().play(loop);
				}
			}
		}
		StarshipEntity ship = ClientFlight.flyingShip();
		if (ship != null && (cabin == null || cabin.isStopped())) {
			cabin = new Loop(ship, RPSounds.ROCKET_VACUUM_HUM, true);
			mc.getSoundManager().play(cabin);
		}
	}

	private static final class Loop extends AbstractTickableSoundInstance {
		private final VehicleEntity vehicle;
		private final boolean structureBorne;
		private final VehicleVisuals visuals = new VehicleVisuals();

		Loop(VehicleEntity vehicle, SoundEvent sound, boolean structureBorne) {
			super(sound, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
			this.vehicle = vehicle;
			this.structureBorne = structureBorne;
			this.looping = true;
			this.delay = 0;
			this.volume = 0.0F;
			if (structureBorne) {
				this.relative = true;
				this.attenuation = SoundInstance.Attenuation.NONE;
			}
			this.follow();
		}

		/** Target loudness from the engines burning and the air around them. */
		float target() {
			if (this.vehicle.isRemoved() || !this.vehicle.isFlying()) {
				return 0.0F;
			}
			this.visuals.compute(this.vehicle, 0.0F);
			if (this.visuals.hidden || this.visuals.engines <= 0) {
				return 0.0F;
			}
			double max = this.vehicle instanceof SuperHeavyEntity ? 33.0 : 6.0;
			float burning = (float) Math.min(1.0, 0.35 + this.visuals.engines / max);
			if (this.structureBorne) {
				boolean aboard = ClientFlight.flyingShip() == this.vehicle;
				return aboard ? 0.55F * burning : 0.0F;
			}
			// Sound needs air: fade with altitude on Earth (half by ~12 km, gone above ~40), and Mars' air carries ~1 %.
			float air = this.visuals.thinAir ? 0.12F : (float) Math.exp(-this.visuals.altitudeKm / 15.0);
			return (this.vehicle instanceof SuperHeavyEntity ? 4.0F : 2.5F) * burning * air;
		}

		private void follow() {
			if (!this.structureBorne) {
				this.x = this.vehicle.getX();
				this.y = this.vehicle.getY();
				this.z = this.vehicle.getZ();
			}
		}

		@Override
		public void tick() {
			float target = this.target();
			this.volume += (target - this.volume) * 0.15F;
			this.pitch = 0.9F + 0.06F * (float) Math.sin(this.vehicle.tickCount * 0.05);
			this.follow();
			if (target <= 0.01F && this.volume < 0.02F) {
				this.stop();
			}
		}
	}
}
