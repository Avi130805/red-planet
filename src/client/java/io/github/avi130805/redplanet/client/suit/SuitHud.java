package io.github.avi130805.redplanet.client.suit;

import java.util.Locale;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.client.starship.flight.CinematicCamera;
import io.github.avi130805.redplanet.environment.Breathing;
import io.github.avi130805.redplanet.environment.PlanetEnvironment;
import io.github.avi130805.redplanet.mars.MarsConditions;
import io.github.avi130805.redplanet.registry.RPDimensions;
import io.github.avi130805.redplanet.registry.RPSounds;
import io.github.avi130805.redplanet.suit.Oxygen;
import io.github.avi130805.redplanet.suit.OxygenItem;
import io.github.avi130805.redplanet.suit.SpaceSuit;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;

/**
 * The suit's readout, top left: oxygen left and how long it lasts, suit and outside pressure, and the ground temperature
 * on Mars. Shown while the suit's torso is worn. When oxygen runs low it flashes and the suit beeps; an unsealed suit in
 * bad air says which piece is missing.
 */
public final class SuitHud implements HudElement {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int DIM = 0xFFA8B4BE;
	private static final int CYAN = 0xFF6EC6FF;
	private static final int RED = 0xFFFF5A3C;
	private static int beepCooldown;

	private SuitHud() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, RedPlanet.id("suit"), new SuitHud());
		ClientTickEvents.END_CLIENT_TICK.register(SuitHud::tick);
	}

	/** The suit works now: worn and sealed where the air can't be breathed. */
	static boolean active(LocalPlayer player) {
		return SpaceSuit.wearsFullSuit(player) && (Breathing.isAnoxic(player) || player.isEyeInFluid(FluidTags.WATER));
	}

	private static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.isPaused()) {
			return;
		}
		if (beepCooldown > 0) {
			beepCooldown--;
		}
		Oxygen o = SpaceSuit.oxygen(SpaceSuit.torso(player));
		if (o != null && active(player) && o.fraction() < SpaceSuit.LOW_FRACTION && beepCooldown == 0 && !player.isCreative()) {
			player.level().playLocalSound(player.getX(), player.getY(), player.getZ(), RPSounds.SUIT_LOW_OXYGEN, SoundSource.PLAYERS,
				0.6F, o.isEmpty() ? 1.4F : 1.0F, false);
			beepCooldown = o.isEmpty() ? 20 : 40;
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.gui.hud.isHidden() || CinematicCamera.INSTANCE.hidesVanillaHud()) {
			return;
		}
		Oxygen o = SpaceSuit.oxygen(SpaceSuit.torso(player));
		boolean anoxic = Breathing.isAnoxic(player);
		if (o == null) {
			return;
		}
		Font font = mc.font;
		int x = 6;
		int y = 6;
		boolean sealed = SpaceSuit.wearsFullSuit(player);
		boolean active = sealed && (anoxic || player.isEyeInFluid(FluidTags.WATER));
		boolean low = o.fraction() < SpaceSuit.LOW_FRACTION;
		boolean flash = (player.tickCount / 8) % 2 == 0;

		g.fill(x - 3, y - 3, x + 150, y + (RPDimensions.isMars(player.level()) ? 50 : 40), 0x60000000);
		g.text(font, Component.translatable("hud.redplanet.suit.oxygen"), x, y, DIM, false);
		g.text(font, String.format(Locale.ROOT, "%.2f / %.2f kg", o.kg(), o.capacityKg()), x + 22, y, low && active ? RED : WHITE, false);
		int barW = 140;
		g.fill(x, y + 10, x + barW, y + 13, 0x40FFFFFF);
		g.fill(x, y + 10, x + Math.round(barW * Mth.clamp(o.fraction(), 0.0F, 1.0F)), y + 13, low ? RED : CYAN);

		Component state;
		int stateColor = DIM;
		if (!sealed && anoxic) {
			state = Component.translatable("hud.redplanet.suit.unsealed");
			stateColor = flash ? RED : WHITE;
		} else if (active && o.isEmpty()) {
			state = Component.translatable("hud.redplanet.suit.depleted");
			stateColor = flash ? RED : WHITE;
		} else if (active) {
			state = Component.translatable(low ? "hud.redplanet.suit.low" : "hud.redplanet.suit.time", OxygenItem.minutesOfPlay(o.kg()));
			stateColor = low && flash ? RED : WHITE;
		} else {
			state = Component.translatable("hud.redplanet.suit.visor_open");
		}
		g.text(font, state, x, y + 16, stateColor, false);

		double outsideKpa = PlanetEnvironment.pressure(player.level(), player.getEyePosition()) / 1000.0;
		String suitPressure = sealed ? String.format(Locale.ROOT, "%.1f", SpaceSuit.SUIT_PRESSURE_KPA) : "—";
		g.text(font, Component.translatable("hud.redplanet.suit.pressure", suitPressure,
			String.format(Locale.ROOT, outsideKpa < 10.0 ? "%.2f" : "%.0f", outsideKpa)), x, y + 27, DIM, false);
		if (RPDimensions.isMars(player.level())) {
			double celsius = MarsConditions.temperatureK(player.level(), player.position()) - 273.15;
			g.text(font, Component.translatable("hud.redplanet.suit.temperature", Math.round(celsius)), x, y + 37, DIM, false);
		}
	}
}
