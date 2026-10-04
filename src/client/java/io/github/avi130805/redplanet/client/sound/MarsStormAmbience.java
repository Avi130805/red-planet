package io.github.avi130805.redplanet.client.sound;

import io.github.avi130805.redplanet.mars.weather.MarsWeather;
import io.github.avi130805.redplanet.registry.RPDimensions;
import io.github.avi130805.redplanet.registry.RPSounds;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * The roar of a dust storm, layered over the biome's thin wind loop. Its loudness follows the local dust optical
 * depth: silent below tau 1.2, full in a global storm. Mars' air is so thin that storm winds of 17-30 m/s carry little
 * force (docs/SCIENCE.md section 11) but plenty of sound energy at low frequencies, and the mod's sound damping
 * already applies.
 */
public final class MarsStormAmbience {
	private static Loop current;

	private MarsStormAmbience() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(MarsStormAmbience::tick);
	}

	/** Target loudness 0..1 at the camera. */
	static float target(Minecraft mc) {
		if (mc.level == null || mc.player == null || !RPDimensions.isMars(mc.level)) {
			return 0.0F;
		}
		double tau = MarsWeather.tauAt(mc.level, mc.player.getX(), mc.player.getZ());
		// Underground the storm is muffled: scale by the sky light at the player.
		float sky = mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, mc.player.blockPosition()) / 15.0F;
		return (float) Mth.clamp((tau - 1.2) / 5.0, 0.0, 1.0) * (0.25F + 0.75F * sky);
	}

	private static void tick(Minecraft mc) {
		float target = target(mc);
		if (target > 0.01F && (current == null || current.isStopped())) {
			current = new Loop();
			mc.getSoundManager().play(current);
		}
	}

	private static final class Loop extends AbstractTickableSoundInstance {
		Loop() {
			super(RPSounds.MARS_DUST_STORM, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
			this.looping = true;
			this.delay = 0;
			this.volume = 0.0F;
			this.relative = true;
			this.attenuation = SoundInstance.Attenuation.NONE;
		}

		@Override
		public void tick() {
			float target = target(Minecraft.getInstance());
			// Ease toward the target over about two seconds.
			this.volume += (target - this.volume) * 0.025F;
			if (target <= 0.01F && this.volume < 0.01F) {
				this.stop();
			}
		}
	}
}
