package io.github.avi130805.redplanet.client.starship.flight;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.client.config.RedPlanetClientConfig;
import io.github.avi130805.redplanet.starship.entity.StarshipEntity;
import io.github.avi130805.redplanet.starship.flight.FlightProfile;
import io.github.avi130805.redplanet.starship.flight.FlightSegment;
import io.github.avi130805.redplanet.starship.flight.PhaseDef;
import io.github.avi130805.redplanet.starship.flight.TelemetryTrack;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * The flight overlay, styled after a launch webcast: the mission clock and the current milestone in the middle, a
 * panel per stage with speed, altitude, the Raptors that are burning and the LOX and methane left, and the timeline of
 * events across the top. Every number is the real profile's at the displayed T (docs/SCIENCE.md, section 19).
 */
public final class TelemetryHud implements HudElement {
	/** Width of a stage panel: name, numbers, propellant bars and the engine diagram. */
	private static final int PANEL_WIDTH = 140;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int DIM = 0xFF9AA4AE;
	private static final int OFF = 0x50FFFFFF;
	private static final int LOX = 0xFF8FD3FF;
	private static final int CH4 = 0xFFFFC27A;

	private TelemetryHud() {
	}

	public static void register() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, RedPlanet.id("telemetry"), new TelemetryHud());
		// The cinematic camera films from outside: hide the hotbar, hearts and crosshair meanwhile.
		for (Identifier id : List.of(VanillaHudElements.CROSSHAIR, VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR,
				VanillaHudElements.ARMOR_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH,
				VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.HELD_ITEM_TOOLTIP)) {
			HudElementRegistry.replaceElement(id, vanilla -> (g, dt) -> {
				if (!CinematicCamera.INSTANCE.hidesVanillaHud()) {
					vanilla.extractRenderState(g, dt);
				}
			});
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		if (!RedPlanetClientConfig.get().telemetryHud) {
			return;
		}
		StarshipEntity ship = ClientFlight.flyingShip();
		if (ship == null) {
			return;
		}
		FlightProfile profile = ship.profile().orElse(null);
		if (profile == null) {
			return;
		}
		float pt = deltaTracker.getGameTimeDeltaPartialTick(false);
		double t = ship.missionTime(pt);
		int phaseIndex = Math.min(ship.phase(), profile.phases().size() - 1);
		PhaseDef phase = profile.phases().get(phaseIndex);
		if (phase.segment() == FlightSegment.TRANSFER) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		int w = g.guiWidth();
		int h = g.guiHeight();
		g.fillGradient(0, h - 64, w, h, 0x00000000, 0xC0000000);

		// Mission clock and milestone. Twice the font size, smaller when a long clock (days into the transfer) would run
		// into the stage panels on a narrow screen.
		String clock = "T" + clock(t);
		float gap = w - 2.0F * (PANEL_WIDTH + 18.0F);
		float clockScale = Mth.clamp(gap / Math.max(1, font.width(clock)), 1.0F, 2.0F);
		g.pose().pushMatrix();
		g.pose().translate(w / 2.0F, h - 36.0F - 4.0F * clockScale);
		g.pose().scale(clockScale, clockScale);
		g.centeredText(font, clock, 0, 0, WHITE);
		g.pose().popMatrix();
		g.centeredText(font, milestone(profile, phase, t), w / 2, h - 22, DIM);

		boolean stack = profile.vehicle() == FlightProfile.Vehicle.STACK;
		boolean boosterFlying = stack && t <= profile.booster().endTime() && phase.segment() == FlightSegment.ASCENT || stack
			&& phase.segment() == FlightSegment.ORIGIN_PAD;
		if (boosterFlying) {
			panel(g, font, 12, h - 58, Component.translatable("telemetry.redplanet.booster"), profile.booster().sample(t), 33);
		}
		panel(g, font, w - PANEL_WIDTH - 12, h - 58, Component.translatable("telemetry.redplanet.ship"), profile.ship().sample(t), 6);
		this.timeline(g, font, profile, t, w);

		Component hint = Component.translatable("telemetry.redplanet.keys", FlightKeys.CAMERA.getTranslatedKeyMessage(),
			CinematicCamera.INSTANCE.mode().label(), FlightKeys.SKIP.getTranslatedKeyMessage());
		g.text(font, hint, w - font.width(hint) - 6, 22, 0xA0FFFFFF, true);
	}

	/** One stage: name, speed and altitude, engine diagram, propellant bars. */
	private static void panel(GuiGraphicsExtractor g, Font font, int x, int y, Component name, TelemetryTrack.Sample s, int engineCount) {
		g.text(font, name, x, y, WHITE, true);
		g.text(font, Component.translatable("telemetry.redplanet.speed"), x, y + 12, DIM, false);
		g.text(font, String.format(Locale.ROOT, "%,d km/h", Math.round(s.speedKmh())), x + 34, y + 12, WHITE, false);
		g.text(font, Component.translatable("telemetry.redplanet.altitude"), x, y + 22, DIM, false);
		g.text(font, altitude(s.altitudeKm()), x + 34, y + 22, WHITE, false);
		bar(g, font, x, y + 34, "LOX", s.lox(), LOX);
		bar(g, font, x, y + 42, "CH4", s.ch4(), CH4);
		engines(g, x + 118, y + 26, s.engines(), engineCount);
	}

	private static String altitude(double km) {
		return km < 10.0 ? String.format(Locale.ROOT, "%.2f km", km) : String.format(Locale.ROOT, "%,d km", Math.round(km));
	}

	private static void bar(GuiGraphicsExtractor g, Font font, int x, int y, String label, double fill, int color) {
		g.text(font, label, x, y - 1, DIM, false);
		g.fill(x + 24, y + 1, x + 84, y + 5, 0x40FFFFFF);
		g.fill(x + 24, y + 1, x + 24 + (int) Math.round(60 * Mth.clamp(fill, 0.0, 1.0)), y + 5, color);
	}

	/** Raptors as dots: the booster's 3 + 10 + 20 rings, or the ship's 3 sea-level engines inside 3 vacuum engines. */
	private static void engines(GuiGraphicsExtractor g, int cx, int cy, int lit, int count) {
		if (count == 33) {
			int[][] rings = {{3, 3}, {10, 8}, {20, 13}};
			int n = 0;
			for (int[] ring : rings) {
				for (int k = 0; k < ring[0]; k++) {
					double a = 2.0 * Math.PI * (k + (ring[0] == 10 ? 0.5 : 0.0)) / ring[0];
					dot(g, cx + (int) Math.round(ring[1] * Math.sin(a)), cy - (int) Math.round(ring[1] * Math.cos(a)), 1, n++ < lit ? WHITE : OFF);
				}
			}
		} else {
			for (int k = 0; k < 3; k++) {
				double a = 2.0 * Math.PI * k / 3.0;
				dot(g, cx + (int) Math.round(4 * Math.sin(a)), cy - (int) Math.round(4 * Math.cos(a)), 1, lit > k ? WHITE : OFF);
				double b = a + Math.PI / 3.0;
				dot(g, cx + (int) Math.round(11 * Math.sin(b)), cy - (int) Math.round(11 * Math.cos(b)), 2, lit > 3 + k ? WHITE : OFF);
			}
		}
	}

	private static void dot(GuiGraphicsExtractor g, int x, int y, int r, int color) {
		g.fill(x - r, y - r, x + r + 1, y + r + 1, color);
	}

	/** The milestone: an event that just happened or is about to, else the phase name. */
	private static Component milestone(FlightProfile profile, PhaseDef phase, double t) {
		for (PhaseDef p : profile.phases()) {
			for (PhaseDef.Event e : p.events()) {
				double at = p.missionTimeAt(e.at());
				if (t >= at && t < at + 6.0) {
					return Component.translatableWithFallback("event.redplanet." + e.type(), pretty(e.type()));
				}
			}
		}
		return Component.translatableWithFallback("phase.redplanet." + phase.id(), pretty(phase.id()));
	}

	/** "belly_flop" to "Belly flop", for phases a datapack adds without a translation. */
	private static String pretty(String id) {
		String words = id.replace('_', ' ');
		return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
	}

	/** The events of the current segment as ticks along the top, with the next few labelled. */
	private void timeline(GuiGraphicsExtractor g, Font font, FlightProfile profile, double t, int w) {
		FlightSegment segment = profile.phases().get(0).segment();
		List<double[]> times = new ArrayList<>();
		List<String> types = new ArrayList<>();
		double start = Double.NaN;
		double end = Double.NaN;
		StarshipEntity ship = ClientFlight.flyingShip();
		if (ship != null) {
			segment = ship.segment().orElse(segment);
		}
		for (PhaseDef p : profile.phases()) {
			boolean ours = p.segment() == segment || segment == FlightSegment.ORIGIN_PAD && p.segment() == FlightSegment.ASCENT;
			if (!ours) {
				continue;
			}
			start = Double.isNaN(start) ? p.missionStart() : start;
			end = p.missionEnd();
			for (PhaseDef.Event e : p.events()) {
				times.add(new double[]{p.missionTimeAt(e.at())});
				types.add(e.type());
			}
		}
		if (Double.isNaN(start) || end <= start) {
			return;
		}
		int x0 = 40;
		int x1 = w - 40;
		int y = 8;
		g.fill(x0, y, x1, y + 1, 0x80FFFFFF);
		double span = end - start;
		int marker = x0 + (int) Math.round((x1 - x0) * Mth.clamp((t - start) / span, 0.0, 1.0));
		g.fill(x0, y, marker, y + 1, WHITE);
		int labelled = 0;
		int lastLabelX = Integer.MIN_VALUE;
		for (int i = 0; i < times.size(); i++) {
			double at = times.get(i)[0];
			int x = x0 + (int) Math.round((x1 - x0) * Mth.clamp((at - start) / span, 0.0, 1.0));
			boolean past = at <= t;
			g.fill(x - 1, y - 2, x + 1, y + 3, past ? WHITE : DIM);
			if (!past && labelled < 3 && x - lastLabelX > 70) {
				Component label = Component.translatableWithFallback("event.redplanet." + types.get(i), pretty(types.get(i)));
				g.centeredText(font, label, x, y + 5, DIM);
				lastLabelX = x;
				labelled++;
			}
		}
		g.fill(marker - 2, y - 3, marker + 2, y + 4, 0xFFFF6A3D);
	}

	/** Mission time as ±hh:mm:ss, or with days during long coasts. */
	public static String clock(double t) {
		String sign = t < 0 ? "-" : "+";
		long s = (long) Math.floor(Math.abs(t));
		long days = s / 86400;
		long hours = s / 3600 % 24;
		long minutes = s / 60 % 60;
		long seconds = s % 60;
		return days > 0 ? String.format(Locale.ROOT, "%s%dd %02d:%02d:%02d", sign, days, hours, minutes, seconds)
			: String.format(Locale.ROOT, "%s%02d:%02d:%02d", sign, hours, minutes, seconds);
	}
}
