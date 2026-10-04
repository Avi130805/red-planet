package io.github.avi130805.redplanet.client.starship.interlude;

import java.util.Locale;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.client.starship.flight.ClientFlight;
import io.github.avi130805.redplanet.client.starship.flight.FlightKeys;
import io.github.avi130805.redplanet.client.starship.flight.MarsMapTexture;
import io.github.avi130805.redplanet.client.starship.flight.TelemetryHud;
import io.github.avi130805.redplanet.starship.entity.StarshipEntity;
import io.github.avi130805.redplanet.starship.flight.FlightProfile;
import io.github.avi130805.redplanet.starship.flight.FlightSegment;
import io.github.avi130805.redplanet.starship.flight.PhaseDef;
import io.github.avi130805.redplanet.starship.flight.Propellant;
import io.github.avi130805.redplanet.starship.flight.TelemetryTrack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;

import org.joml.Matrix3x2f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Between worlds: a full-screen space scene drawn over the world while the ship is in the transfer segment, and in
 * place of the loading screen when it arrives (it is a {@link LevelLoadingScreen}, so vanilla hands it the new level's
 * load tracker instead of opening its own).
 *
 * <p>It follows the profile's transfer events: low orbit over Earth, refilling from tankers, the injection burn as
 * Earth falls away, the cruise on a Hohmann-style transfer with the mission clock running months ahead, and Mars
 * growing on approach. The readouts are the profile's (docs/SCIENCE.md, section 19).
 */
public class InterludeScreen extends LevelLoadingScreen {
	public static final Identifier EARTH_TEXTURE = RedPlanet.id("textures/environment/earth.png");
	private static final int STARS = 420;
	/** Semi-major axes (AU): Earth 1.000, Mars 1.524 (docs/SCIENCE.md, section 9). */
	private static final float MARS_ORBIT_AU = 1.524F;

	private enum Stage {
		ORBIT, REFILL, INJECTION, CRUISE, APPROACH
	}

	private LevelLoadTracker tracker;
	private boolean arrivalStarted;
	private long arrivalMs;
	private final long openedMs = Util.getMillis();
	private final float[] starX = new float[STARS];
	private final float[] starY = new float[STARS];
	private final float[] starB = new float[STARS];
	/** Remembered from the flight (the ship entity disappears while changing worlds). */
	private @Nullable FlightProfile profile;
	private double lastMissionTime;
	private boolean toMars = true;

	public InterludeScreen() {
		this(new LevelLoadTracker(), false);
	}

	public InterludeScreen(LevelLoadTracker tracker, boolean arrivalStarted) {
		super(tracker, LevelLoadingScreen.Reason.OTHER);
		this.tracker = tracker;
		this.arrivalStarted = arrivalStarted;
		this.arrivalMs = arrivalStarted ? Util.getMillis() : 0L;
		RandomSource random = RandomSource.create(461L);
		for (int i = 0; i < STARS; i++) {
			this.starX[i] = random.nextFloat();
			this.starY[i] = random.nextFloat();
			float m = random.nextFloat();
			this.starB[i] = 0.25F + 0.75F * m * m * m;
		}
	}

	@Override
	public void update(LevelLoadTracker loadTracker, LevelLoadingScreen.Reason reason) {
		super.update(loadTracker, reason);
		this.tracker = loadTracker;
		if (!this.arrivalStarted) {
			this.arrivalStarted = true;
			this.arrivalMs = Util.getMillis();
		}
	}

	public boolean arrivalStarted() {
		return this.arrivalStarted;
	}

	@Override
	public void tick() {
		StarshipEntity ship = ClientFlight.flyingShip();
		if (ship != null) {
			ship.profile().ifPresent(p -> {
				this.profile = p;
				this.toMars = !"mars".equals(p.interlude().from());
			});
			this.lastMissionTime = ship.missionTime(0.0F);
		}
		long now = Util.getMillis();
		if (this.arrivalStarted) {
			// Hold the arrival a moment so the approach reads, then hand over to the world.
			if (this.tracker.isLevelReady() && now - this.arrivalMs > 2500L) {
				this.onClose();
			} else if (now - this.arrivalMs > 60000L) {
				this.onClose(); // never trap the player
			}
		} else if (ship == null || ship.segment().orElse(null) != FlightSegment.TRANSFER) {
			// The flight left the transfer without a world change (aborted, or a profile that lands where it started).
			if (now - this.openedMs > 1000L) {
				this.onClose();
			}
		}
	}

	@Override
	public void onClose() {
		InterludeController.closed();
		super.onClose();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (FlightKeys.SKIP.matches(event)) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null && !this.arrivalStarted) {
				mc.player.connection.sendCommand("redplanet starship skip");
			}
			return true;
		}
		return super.keyPressed(event);
	}

	// ------------------------------------------------------------------------------------------------- drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		float fadeIn = Mth.clamp((Util.getMillis() - this.openedMs) / 900.0F, 0.0F, 1.0F);
		g.fill(0, 0, this.width, this.height, ARGB.color(Math.round(255 * fadeIn), 2, 3, 8));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		float fadeIn = Mth.clamp((Util.getMillis() - this.openedMs) / 900.0F, 0.0F, 1.0F);
		this.stars(g, fadeIn);
		FlightProfile p = this.profile;
		StarshipEntity ship = ClientFlight.flyingShip();
		double t = ship != null ? ship.missionTime(a) : this.lastMissionTime;
		if (p == null) {
			this.caption(g, Component.translatable("interlude.redplanet.between_worlds"), Component.empty());
			return;
		}
		Stage stage = this.arrivalStarted ? Stage.APPROACH : stage(p, t);
		double stageProgress = this.arrivalStarted ? 1.0 : progress(p, t, stage);
		long ms = Util.getMillis();
		switch (stage) {
			case ORBIT, REFILL -> this.orbit(g, p, t, stage, stageProgress, ms);
			case INJECTION -> this.injection(g, p, t, stageProgress, ms);
			case CRUISE -> this.cruise(g, p, t, stageProgress);
			case APPROACH -> this.approach(g, p, stageProgress, ms);
		}
		// Mission clock, top centre.
		g.pose().pushMatrix();
		g.pose().translate(this.width / 2.0F, 14.0F);
		g.pose().scale(1.6F, 1.6F);
		g.centeredText(this.font, "T" + TelemetryHud.clock(t), 0, 0, 0xFFFFFFFF);
		g.pose().popMatrix();
		Component hint = Component.translatable("interlude.redplanet.skip", FlightKeys.SKIP.getTranslatedKeyMessage());
		g.text(this.font, hint, this.width - this.font.width(hint) - 6, this.height - 12, 0x80FFFFFF, false);
	}

	private void stars(GuiGraphicsExtractor g, float fade) {
		long ms = Util.getMillis();
		for (int i = 0; i < STARS; i++) {
			float twinkle = 0.85F + 0.15F * Mth.sin(ms / 700.0F + i * 1.7F);
			int alpha = Math.round(255 * fade * this.starB[i] * twinkle);
			int x = (int) (this.starX[i] * this.width);
			int y = (int) (this.starY[i] * this.height);
			g.fill(x, y, x + 1, y + 1, ARGB.color(alpha, 255, 250, 240));
		}
	}

	private void caption(GuiGraphicsExtractor g, Component title, Component detail) {
		g.centeredText(this.font, title, this.width / 2, this.height - 46, 0xFFFFFFFF);
		g.centeredText(this.font, detail, this.width / 2, this.height - 34, 0xFFB8C4D0);
	}

	private void globe(GuiGraphicsExtractor g, float cx, float cy, float radius, Identifier texture, float spinDeg, float tiltDeg, Vector3f sun) {
		Quaternionf spin = new Quaternionf().rotateX((float) Math.toRadians(tiltDeg)).rotateY((float) Math.toRadians(spinDeg));
		g.guiRenderState.addGuiElement(new GlobeRenderState(new Matrix3x2f(g.pose()), cx, cy, radius, texture, spin, sun.normalize(), 0.06F,
			g.scissorStack.peek()));
		// Atmosphere: a thin halo just outside the limb.
		int halo = texture == EARTH_TEXTURE ? 0x5070A8FF : 0x30E0A070;
		for (int k = 1; k <= 3; k++) {
			ring(g, cx, cy, radius + k, ARGB.color(ARGB.alpha(halo) / k, ARGB.red(halo), ARGB.green(halo), ARGB.blue(halo)));
		}
	}

	/** A one-pixel ring as short segments (cheap and good enough at these sizes). */
	private static void ring(GuiGraphicsExtractor g, float cx, float cy, float r, int color) {
		int n = Math.max(24, (int) (r * 0.8F));
		for (int i = 0; i < n; i++) {
			double a = 2.0 * Math.PI * i / n;
			int x = Math.round(cx + (float) Math.cos(a) * r);
			int y = Math.round(cy + (float) Math.sin(a) * r);
			g.fill(x, y, x + 1, y + 1, color);
		}
	}

	private Identifier departureTexture() {
		return this.toMars ? EARTH_TEXTURE : MarsMapTexture.get();
	}

	private Identifier arrivalTexture() {
		return this.toMars ? MarsMapTexture.get() : EARTH_TEXTURE;
	}

	private void orbit(GuiGraphicsExtractor g, FlightProfile p, double t, Stage stage, double k, long ms) {
		float r = this.height * 0.62F;
		this.globe(g, this.width * 0.42F, this.height + r * 0.38F, r, this.departureTexture(), ms / 400.0F, 18.0F, new Vector3f(-0.6F, 0.5F, 0.6F));
		// The ship: a bright point drifting along the limb.
		float sx = this.width * (0.25F + 0.5F * (float) ((ms / 30000.0) % 1.0));
		float sy = this.height * 0.40F;
		g.fill(Math.round(sx) - 1, Math.round(sy), Math.round(sx) + 2, Math.round(sy) + 1, 0xFFFFFFFF);
		TelemetryTrack.Sample s = p.ship().sample(t);
		if (stage == Stage.REFILL) {
			int tankers = Math.max(1, p.interlude().tankerFlights());
			int done = Math.min(tankers, (int) Math.floor(k * tankers));
			// A tanker closing in for the next transfer.
			float approach = (float) ((k * tankers) % 1.0);
			float tx = sx - 40.0F * (1.0F - Math.min(1.0F, approach * 2.0F));
			g.fill(Math.round(tx) - 1, Math.round(sy) + 1, Math.round(tx) + 2, Math.round(sy) + 2, 0xFFB8D8FF);
			long aboard = Math.round(Propellant.tonnes(Propellant.SHIP_TONNES, s.lox(), s.ch4()));
			this.caption(g, Component.translatable("interlude.redplanet.refilling", Math.min(tankers, done + 1), tankers),
				Component.translatable("interlude.redplanet.propellant", String.format(Locale.ROOT, "%,d", aboard),
					String.format(Locale.ROOT, "%,d", Math.round(Propellant.SHIP_TONNES))));
			int bw = 160;
			int bx = (this.width - bw) / 2;
			g.fill(bx, this.height - 24, bx + bw, this.height - 20, 0x40FFFFFF);
			g.fill(bx, this.height - 24, bx + (int) Math.round(bw * aboard / Propellant.SHIP_TONNES), this.height - 20, 0xFF8FD3FF);
		} else {
			this.caption(g, Component.translatable(this.toMars ? "interlude.redplanet.earth_orbit" : "interlude.redplanet.mars_orbit"),
				Component.translatable("interlude.redplanet.orbit_numbers", Math.round(s.altitudeKm()), String.format(Locale.ROOT, "%,d", Math.round(s.speedKmh()))));
		}
	}

	private void injection(GuiGraphicsExtractor g, FlightProfile p, double t, double k, long ms) {
		float r = Mth.lerp((float) smooth(k), this.height * 0.5F, this.height * 0.08F);
		this.globe(g, this.width * 0.35F, this.height * 0.62F, r, this.departureTexture(), ms / 400.0F, 18.0F, new Vector3f(-0.6F, 0.5F, 0.6F));
		// The burn: a flaring point leaving the planet behind.
		float sx = this.width * Mth.lerp((float) k, 0.55F, 0.82F);
		float sy = this.height * Mth.lerp((float) k, 0.45F, 0.3F);
		int glow = Math.round(120 + 100 * Mth.sin(ms / 60.0F));
		for (int d = 3; d >= 1; d--) {
			g.fill(Math.round(sx) - d, Math.round(sy) - d, Math.round(sx) + d + 1, Math.round(sy) + d + 1, ARGB.color(glow / d, 255, 210, 160));
		}
		this.caption(g, Component.translatable(this.toMars ? "interlude.redplanet.tmi" : "interlude.redplanet.tei"),
			Component.translatable("interlude.redplanet.delta_v", String.format(Locale.ROOT, "%.1f", p.interlude().departureDvKmS())));
	}

	/** The transfer drawn to scale: Sun, both orbits, the transfer arc and the ship on it. */
	private void cruise(GuiGraphicsExtractor g, FlightProfile p, double t, double k) {
		float cx = this.width / 2.0F;
		float cy = this.height / 2.0F + 6.0F;
		float scale = Math.min(this.width, this.height) * 0.30F;
		float rEarth = scale;
		float rMars = scale * MARS_ORBIT_AU;
		ring(g, cx, cy, rEarth, 0x704A90FF);
		ring(g, cx, cy, rMars, 0x70E08050);
		g.fill(Math.round(cx) - 2, Math.round(cy) - 2, Math.round(cx) + 3, Math.round(cy) + 3, 0xFFFFE9A0);
		// Hohmann half-ellipse from perihelion (departure planet's orbit) to aphelion (the other's), counter-clockwise.
		float r1 = this.toMars ? rEarth : rMars;
		float r2 = this.toMars ? rMars : rEarth;
		float a = (r1 + r2) / 2.0F;
		float e = Math.abs(r2 - r1) / (r1 + r2);
		double depart = Math.PI; // departure on the left
		int steps = 90;
		float shipX = cx;
		float shipY = cy;
		for (int i = 0; i <= steps; i++) {
			double nu = Math.PI * i / steps; // true anomaly from perihelion (or aphelion, for the return)
			double r = this.toMars ? a * (1 - e * e) / (1 + e * Math.cos(nu)) : a * (1 - e * e) / (1 - e * Math.cos(nu));
			double ang = depart + nu;
			float x = cx + (float) (r * Math.cos(ang));
			float y = cy - (float) (r * Math.sin(ang));
			boolean flown = i <= k * steps;
			g.fill(Math.round(x), Math.round(y), Math.round(x) + 1, Math.round(y) + 1, flown ? 0xFFFFFFFF : 0x60FFFFFF);
			if (i == Math.round(k * steps)) {
				shipX = x;
				shipY = y;
			}
		}
		// Planets: departure where the ship left, arrival where it meets the other orbit (half an orbit later).
		double travel = Math.PI * k;
		double depAngle = depart + travel * (this.toMars ? 1.0 : 0.53); // the inner planet laps ahead
		double arrAngle = depart + Math.PI - Math.PI * (1.0 - k) * (this.toMars ? 0.53 : 1.0);
		dot(g, cx + (float) (rEarth * Math.cos(this.toMars ? depAngle : arrAngle)), cy - (float) (rEarth * Math.sin(this.toMars ? depAngle : arrAngle)), 2, 0xFF6FA8FF);
		dot(g, cx + (float) (rMars * Math.cos(this.toMars ? arrAngle : depAngle)), cy - (float) (rMars * Math.sin(this.toMars ? arrAngle : depAngle)), 2, 0xFFE07A4A);
		dot(g, shipX, shipY, 1, 0xFFFFFFFF);
		double days = p.interlude().transferDays();
		double rAu = (Math.hypot(shipX - cx, shipY - cy)) / scale;
		this.caption(g, Component.translatable("interlude.redplanet.cruise", Math.round(k * days), Math.round(days)),
			Component.translatable("interlude.redplanet.cruise_numbers", String.format(Locale.ROOT, "%.2f", rAu)));
	}

	private void approach(GuiGraphicsExtractor g, FlightProfile p, double k, long ms) {
		float grow = (float) smooth(k);
		float r = Mth.lerp(grow, this.height * 0.06F, this.height * 0.55F);
		float spin = this.toMars ? 137.0F - 180.0F + ms / 900.0F : ms / 900.0F;
		this.globe(g, this.width * 0.5F, this.height * 0.48F + r * 0.25F * grow, r, this.arrivalTexture(), spin, 12.0F, new Vector3f(0.5F, 0.4F, 0.75F));
		Component title = Component.translatable(this.arrivalStarted ? "interlude.redplanet.entry"
			: this.toMars ? "interlude.redplanet.mars_approach" : "interlude.redplanet.earth_approach");
		this.caption(g, title, Component.translatable("interlude.redplanet.arrival_speed", String.format(Locale.ROOT, "%.1f", p.interlude().arrivalSpeedKmS())));
	}

	private static void dot(GuiGraphicsExtractor g, float x, float y, int r, int color) {
		g.fill(Math.round(x) - r, Math.round(y) - r, Math.round(x) + r + 1, Math.round(y) + r + 1, color);
	}

	private static double smooth(double x) {
		double t = Mth.clamp(x, 0.0, 1.0);
		return t * t * (3.0 - 2.0 * t);
	}

	// --------------------------------------------------------------------------------------------- the timeline

	/** Which part of the transfer we're in, from the last transfer event passed. */
	private static Stage stage(FlightProfile p, double t) {
		Stage stage = Stage.ORBIT;
		for (PhaseDef phase : p.phases()) {
			if (phase.segment() != FlightSegment.TRANSFER) {
				continue;
			}
			for (PhaseDef.Event e : phase.events()) {
				if (phase.missionTimeAt(e.at()) <= t) {
					stage = switch (e.type()) {
						case "refilling" -> Stage.REFILL;
						case "tmi" -> Stage.INJECTION;
						case "coast" -> Stage.CRUISE;
						case "approach" -> Stage.APPROACH;
						default -> stage;
					};
				}
			}
		}
		return stage;
	}

	/** Progress through a stage, from its event to the next stage's event (or the end of the transfer). */
	private static double progress(FlightProfile p, double t, Stage stage) {
		double start = Double.NaN;
		double end = Double.NaN;
		double transferStart = Double.NaN;
		double transferEnd = Double.NaN;
		String[] order = {"orbit", "refilling", "tmi", "coast", "approach"};
		double[] at = new double[order.length];
		java.util.Arrays.fill(at, Double.NaN);
		for (PhaseDef phase : p.phases()) {
			if (phase.segment() != FlightSegment.TRANSFER) {
				continue;
			}
			transferStart = Double.isNaN(transferStart) ? phase.missionStart() : transferStart;
			transferEnd = phase.missionEnd();
			for (PhaseDef.Event e : phase.events()) {
				for (int i = 0; i < order.length; i++) {
					if (order[i].equals(e.type()) && Double.isNaN(at[i])) {
						at[i] = phase.missionTimeAt(e.at());
					}
				}
			}
		}
		int index = stage.ordinal();
		start = Double.isNaN(at[index]) ? transferStart : at[index];
		for (int i = index + 1; i < order.length && Double.isNaN(end); i++) {
			end = at[i];
		}
		if (Double.isNaN(end)) {
			end = transferEnd;
		}
		if (Double.isNaN(start) || !(end > start)) {
			return 0.0;
		}
		return Mth.clamp((t - start) / (end - start), 0.0, 1.0);
	}
}
