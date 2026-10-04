package io.github.avi130805.redplanet.client.starship.flight;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import io.github.avi130805.redplanet.mars.geo.MarsLandmarks;
import io.github.avi130805.redplanet.registry.RPDimensions;
import io.github.avi130805.redplanet.registry.RPRegistries;
import io.github.avi130805.redplanet.starship.entity.StarshipEntity;
import io.github.avi130805.redplanet.starship.flight.FlightProfile;
import io.github.avi130805.redplanet.starship.flight.Pacing;
import io.github.avi130805.redplanet.starship.geometry.StarshipGeometry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import org.jspecify.annotations.Nullable;

/**
 * Mission control: choose where to land and how long the flight takes, then launch. From Earth the screen shows a
 * shaded-relief map of Mars (TES albedo and MOLA relief) with the real landing sites; click anywhere to land there.
 * From Mars the ship flies home to the pad it launched from.
 */
public class MissionControlScreen extends Screen {
	private static final int MARKER = 0xFFFFE08A;
	private static final int SELECTED = 0xFF7CFFB2;

	private final StarshipEntity ship;
	private final boolean toMars;
	private final @Nullable FlightProfile profile;
	private Pacing pacing = Pacing.STANDARD;
	private MarsLandmarks.@Nullable Landmark site = MarsLandmarks.byId("curiosity").orElse(null);
	private double siteLat = -4.59;
	private double siteLon = 137.44;
	private int mapX;
	private int mapY;
	private int mapW;
	private int mapH;

	protected MissionControlScreen(StarshipEntity ship) {
		super(Component.translatable("mission.redplanet.title"));
		this.ship = ship;
		this.toMars = !RPDimensions.MARS.equals(ship.level().dimension());
		Identifier id = this.toMars ? RPRegistries.EARTH_TO_MARS : RPRegistries.MARS_TO_EARTH;
		Minecraft mc = Minecraft.getInstance();
		this.profile = mc.level == null ? null : RPRegistries.profile(mc.level.registryAccess(), id).orElse(null);
	}

	public static void open(StarshipEntity ship) {
		Minecraft.getInstance().gui.setScreen(new MissionControlScreen(ship));
	}

	@Override
	protected void init() {
		// The map takes what the title, the site line, the buttons and the status line leave.
		this.mapW = Math.min(Math.min(this.width - 40, 400), (this.height - 150) * 2);
		this.mapW = Math.max(120, this.mapW - this.mapW % 2);
		this.mapH = this.mapW / 2;
		this.mapX = (this.width - this.mapW) / 2;
		this.mapY = 34;
		int below = this.toMars ? this.mapY + this.mapH + 32 : this.height / 2;
		this.addRenderableWidget(CycleButton.builder(this::pacingLabel, this.pacing).withValues(List.of(Pacing.values())).displayOnlyValue()
			.create(this.width / 2 - 155, below, 150, 20, Component.translatable("mission.redplanet.pacing"), (button, value) -> this.pacing = value));
		this.addRenderableWidget(Button.builder(Component.translatable("mission.redplanet.launch"), b -> this.launch())
			.bounds(this.width / 2 + 5, below, 150, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> this.onClose())
			.bounds(this.width / 2 - 75, below + 26, 150, 20).build());
	}

	private Component pacingLabel(Pacing p) {
		Component name = Component.translatable("mission.redplanet.pacing." + p.getSerializedName());
		if (this.profile == null) {
			return name;
		}
		int minutes = Math.max(1, Math.round(this.profile.totalTicks(p) / 1200.0F));
		return Component.translatable("mission.redplanet.pacing_minutes", name, minutes);
	}

	private void launch() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		String pacingName = this.pacing.getSerializedName();
		String command;
		if (!this.toMars) {
			command = "redplanet starship launch " + pacingName;
		} else if (this.site != null) {
			command = "redplanet starship launch " + pacingName + " " + this.site.id();
		} else {
			command = String.format(Locale.ROOT, "redplanet starship launch %s at %.2f %.2f", pacingName, this.siteLat, this.siteLon);
		}
		mc.player.connection.sendCommand(command);
		this.onClose();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (this.toMars && event.x() >= this.mapX && event.x() < this.mapX + this.mapW && event.y() >= this.mapY && event.y() < this.mapY + this.mapH) {
			MarsLandmarks.Landmark nearest = this.landmarkAt(event.x(), event.y());
			if (nearest != null) {
				this.site = nearest;
				this.siteLat = nearest.lat();
				this.siteLon = nearest.lon();
			} else {
				this.site = null;
				this.siteLat = MarsMapTexture.latitude((event.y() - this.mapY) / this.mapH);
				this.siteLon = MarsMapTexture.longitude((event.x() - this.mapX) / this.mapW);
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private MarsLandmarks.@Nullable Landmark landmarkAt(double mx, double my) {
		MarsLandmarks.Landmark best = null;
		double bestD = 7.0 * 7.0;
		for (MarsLandmarks.Landmark l : MarsLandmarks.ALL) {
			double dx = this.lonX(l.lon()) - mx;
			double dy = this.latY(l.lat()) - my;
			double d = dx * dx + dy * dy;
			if (d < bestD) {
				bestD = d;
				best = l;
			}
		}
		return best;
	}

	private double lonX(double lon) {
		return this.mapX + Math.floorMod((long) Math.round(lon * 1000.0), 360000L) / 1000.0 / 360.0 * this.mapW;
	}

	private double latY(double lat) {
		return this.mapY + (90.0 - lat) / 180.0 * this.mapH;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
		Component destination = Component.translatable(this.toMars ? "destination.redplanet.mars" : "destination.redplanet.overworld");
		g.centeredText(this.font, Component.translatable("mission.redplanet.destination", destination), this.width / 2, 21, 0xFFB8C4D0);
		if (this.toMars) {
			this.drawMap(g, mouseX, mouseY);
			Component siteName = this.site != null ? Component.literal(this.site.name())
				: Component.translatable("mission.redplanet.custom_site");
			g.centeredText(this.font, Component.translatable("mission.redplanet.site", siteName, fmt(this.siteLat), fmt(this.siteLon)),
				this.width / 2, this.mapY + this.mapH + 6, 0xFFFFFFFF);
			g.centeredText(this.font, Component.translatable("mission.redplanet.click_map"), this.width / 2, this.mapY + this.mapH + 18, 0xFF8A96A3);
		} else {
			Component home = this.ship.homePad().map(p -> (Component) Component.translatable("mission.redplanet.home_pad", p.pos().getX(), p.pos().getZ()))
				.orElse(Component.translatable("mission.redplanet.world_spawn"));
			g.centeredText(this.font, home, this.width / 2, this.height / 2 - 40, 0xFFFFFFFF);
		}
		int crew = this.ship.getPassengers().size();
		Component vehicle = Component.translatable(this.toMars ? "mission.redplanet.vehicle_stack" : "mission.redplanet.vehicle_ship");
		Component status = Component.translatable("mission.redplanet.status", vehicle, crew, StarshipGeometry.SEAT_COUNT);
		g.centeredText(this.font, status, this.width / 2, this.height - 14, 0xFFB8C4D0);
		Optional<Component> problem = this.problem();
		problem.ifPresent(p -> g.centeredText(this.font, p, this.width / 2, this.height - 26, 0xFFFF7A6A));
	}

	private Optional<Component> problem() {
		if (this.profile == null) {
			return Optional.of(Component.translatable("mission.redplanet.no_profile"));
		}
		if (this.toMars && !this.ship.isStacked()) {
			return Optional.of(Component.translatable("starship.redplanet.launch.needs_booster"));
		}
		return Optional.empty();
	}

	private void drawMap(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		g.fill(this.mapX - 2, this.mapY - 2, this.mapX + this.mapW + 2, this.mapY + this.mapH + 2, 0xFF0A0C10);
		g.blit(MarsMapTexture.get(), this.mapX, this.mapY, this.mapX + this.mapW, this.mapY + this.mapH, 0.0F, 1.0F, 0.0F, 1.0F);
		MarsLandmarks.Landmark hovered = this.landmarkAt(mouseX, mouseY);
		for (MarsLandmarks.Landmark l : MarsLandmarks.ALL) {
			int x = (int) Math.round(this.lonX(l.lon()));
			int y = (int) Math.round(this.latY(l.lat()));
			int color = l == this.site ? SELECTED : l.kind() == MarsLandmarks.Kind.LANDING_SITE ? MARKER : 0xFFD8D8D8;
			int r = l == hovered || l == this.site ? 2 : 1;
			g.fill(x - r, y - r, x + r + 1, y + r + 1, color);
		}
		if (this.site == null) {
			int x = (int) Math.round(this.lonX(this.siteLon));
			int y = (int) Math.round(this.latY(this.siteLat));
			g.fill(x - 3, y, x + 4, y + 1, SELECTED);
			g.fill(x, y - 3, x + 1, y + 4, SELECTED);
		}
		if (hovered != null) {
			g.setTooltipForNextFrame(this.font, Component.literal(hovered.name()), mouseX, mouseY);
		}
	}

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
