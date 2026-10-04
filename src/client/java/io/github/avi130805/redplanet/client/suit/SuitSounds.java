package io.github.avi130805.redplanet.client.suit;

import io.github.avi130805.redplanet.registry.RPSounds;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

/** The wearer's own breathing inside the sealed helmet, while the suit is working. */
public final class SuitSounds {
	private static Breathing loop;

	private SuitSounds() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(SuitSounds::tick);
	}

	private static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null) {
			loop = null;
			return;
		}
		if ((loop == null || loop.isStopped()) && SuitHud.active(player) && !player.isCreative()) {
			loop = new Breathing(player);
			mc.getSoundManager().play(loop);
		}
	}

	private static final class Breathing extends AbstractTickableSoundInstance {
		private final LocalPlayer player;

		Breathing(LocalPlayer player) {
			super(RPSounds.SUIT_BREATHING, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
			this.player = player;
			this.looping = true;
			this.delay = 0;
			this.relative = true;
			this.attenuation = SoundInstance.Attenuation.NONE;
			this.volume = 0.0F;
		}

		@Override
		public void tick() {
			boolean on = !this.player.isRemoved() && SuitHud.active(this.player);
			this.volume += ((on ? 0.35F : 0.0F) - this.volume) * 0.1F;
			if (!on && this.volume < 0.02F) {
				this.stop();
			}
		}
	}
}
