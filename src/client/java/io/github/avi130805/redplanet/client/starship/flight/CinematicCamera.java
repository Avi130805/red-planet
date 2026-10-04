package io.github.avi130805.redplanet.client.starship.flight;

import java.util.List;

import io.github.avi130805.redplanet.client.config.RedPlanetClientConfig;
import io.github.avi130805.redplanet.starship.entity.StarshipEntity;
import io.github.avi130805.redplanet.starship.entity.SuperHeavyEntity;
import io.github.avi130805.redplanet.starship.entity.VehicleEntity;
import io.github.avi130805.redplanet.starship.flight.CameraShot;
import io.github.avi130805.redplanet.starship.flight.FlightKinematics;
import io.github.avi130805.redplanet.starship.flight.FlightProfile;
import io.github.avi130805.redplanet.starship.flight.FlightSegment;
import io.github.avi130805.redplanet.starship.flight.PhaseDef;
import io.github.avi130805.redplanet.starship.geometry.StarshipGeometry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Films the flight. While the local player rides a flying ship, the camera follows the profile's shot list (pad wide
 * shots, chase, onboard cameras, the landing site looking up), blending or cutting between shots. The player can
 * switch to a free orbit around the ship or to their own eyes in the cabin.
 *
 * <p>The client only has the world around the crew: chunks within the view distance of the ship, and only those within
 * the render distance of the camera are drawn. A camera placed hundreds of metres away would film empty sky, so every
 * shot is pulled in to stay inside that area, widening the field of view to keep the framing.
 *
 * <p>In the cabin the camera turns with the ship: the mouse looks around a cabin that pitches over during the ascent and
 * lies on its back through the belly flop, as a strapped-in crew member would see it.
 */
public final class CinematicCamera {
	public static final CinematicCamera INSTANCE = new CinematicCamera();

	/** How the player wants to watch. */
	public enum Mode {
		CINEMATIC("cinematic"),
		ORBIT("orbit"),
		CABIN("cabin");

		private final String name;

		Mode(String name) {
			this.name = name;
		}

		public Component label() {
			return Component.translatable("camera.redplanet." + this.name);
		}
	}

	/**
	 * A camera pose: position, orientation (camera to world, vanilla's convention: the camera looks along -Z), vertical
	 * field of view, and whether it is the player's own eyes (their body isn't drawn then).
	 */
	public record Pose(Vec3 position, Quaternionf orientation, float fov, boolean firstPerson) {
		static Pose looking(Vec3 position, float yaw, float pitch, float fov) {
			return new Pose(position, rotation(yaw, pitch), fov, false);
		}

		/** Vanilla yaw (degrees) of the view direction, for code that reads the camera's angles. */
		public float yaw() {
			Vector3f f = this.orientation.transform(new Vector3f(0.0F, 0.0F, -1.0F));
			return (float) (Mth.atan2(-f.x, f.z) * Mth.RAD_TO_DEG);
		}

		/** Vanilla pitch (degrees, positive looking down) of the view direction. */
		public float pitch() {
			Vector3f f = this.orientation.transform(new Vector3f(0.0F, 0.0F, -1.0F));
			return (float) (-Math.asin(Mth.clamp(f.y, -1.0F, 1.0F)) * Mth.RAD_TO_DEG);
		}
	}

	private static final double BLEND_SECONDS = 1.4;
	/** Share of the render distance a camera may stand from the crew, and from what it films. */
	private static final double REACH_FROM_CREW = 0.75;
	private static final double REACH_FROM_SUBJECT = 0.7;
	private static final float MAX_FOV = 82.0F;

	private Mode mode = Mode.CINEMATIC;
	private int shotIndex = -2;
	private long shotStartMs;
	private @Nullable Pose lastPose;
	private @Nullable Pose blendFrom;
	private long blendStartMs;
	private @Nullable Vec3 chaseOffset;
	private @Nullable Pose current;

	private CinematicCamera() {
	}

	public Mode mode() {
		return this.mode;
	}

	public void cycleMode() {
		this.mode = Mode.values()[(this.mode.ordinal() + 1) % Mode.values().length];
		this.blendFrom = this.lastPose;
		this.blendStartMs = Util.getMillis();
	}

	/** Whether the camera is away from the vanilla first-person view this frame. */
	public boolean isActive() {
		return this.current != null;
	}

	/** Vanilla HUD elements (hotbar, hearts, crosshair) are hidden while the camera films. */
	public boolean hidesVanillaHud() {
		return ClientFlight.flyingShip() != null;
	}

	/** The pose for this frame, or null to keep the vanilla camera. Called once per frame by the camera mixin. */
	public @Nullable Pose update(float partialTick) {
		this.current = this.evaluate(partialTick);
		return this.current;
	}

	public @Nullable Pose current() {
		return this.current;
	}

	private @Nullable Pose evaluate(float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		StarshipEntity ship = ClientFlight.flyingShip();
		if (ship == null || mc.player == null || mc.gui.screen() instanceof LevelLoadingScreen) {
			this.reset();
			return null;
		}
		FlightProfile profile = ship.profile().orElse(null);
		if (profile == null) {
			this.reset();
			return null;
		}
		int phaseIndex = Math.min(ship.phase(), profile.phases().size() - 1);
		PhaseDef phase = profile.phases().get(phaseIndex);
		if (phase.segment() == FlightSegment.TRANSFER) {
			this.reset();
			return null;
		}
		long now = Util.getMillis();
		Pose pose;
		if (this.mode == Mode.CABIN) {
			pose = this.cabin(ship, mc.player, partialTick);
			this.shotIndex = -1;
		} else if (this.mode == Mode.ORBIT) {
			pose = this.orbitAroundShip(ship, mc.player, partialTick);
			this.shotIndex = -1;
		} else {
			double progress = ClientFlight.phaseProgress(ship, profile, partialTick);
			int index = findShot(profile.shots(), phase.id(), progress);
			CameraShot shot = index >= 0 ? profile.shots().get(index) : null;
			if (index != this.shotIndex || index < 0 && this.shotIndex != -3) {
				boolean cut = shot == null || shot.cut();
				this.blendFrom = cut ? null : this.lastPose;
				this.blendStartMs = now;
				this.shotStartMs = now;
				this.chaseOffset = null;
				this.shotIndex = index < 0 ? -3 : index;
			}
			double seconds = (now - this.shotStartMs) / 1000.0;
			pose = shot != null ? this.film(shot, ship, profile, mc.player, partialTick, seconds) : this.defaultChase(ship, profile, partialTick);
		}
		pose = this.blend(pose, now);
		this.lastPose = pose;
		return pose;
	}

	private void reset() {
		this.shotIndex = -2;
		this.lastPose = null;
		this.blendFrom = null;
		this.chaseOffset = null;
	}

	private static int findShot(List<CameraShot> shots, String phase, double progress) {
		for (int i = 0; i < shots.size(); i++) {
			CameraShot shot = shots.get(i);
			if (shot.phase().equals(phase) && shot.activeAt(progress)) {
				return i;
			}
		}
		// The last shot of the phase still holds at progress 1.
		for (int i = shots.size() - 1; i >= 0; i--) {
			CameraShot shot = shots.get(i);
			if (shot.phase().equals(phase) && progress >= shot.to()) {
				return i;
			}
		}
		return -1;
	}

	// --------------------------------------------------------------------------------------------- shot types

	private Pose film(CameraShot shot, StarshipEntity ship, FlightProfile profile, LocalPlayer player, float pt, double seconds) {
		if (shot.type() == CameraShot.Type.CABIN) {
			return shake(this.cabin(ship, player, pt), shot.shake() * 0.5, seconds);
		}
		if (shot.target() == CameraShot.Target.BOOSTER && !boosterInView(ship, player)) {
			// The booster flies home far from the crew, out of what this client can see: film the ship instead.
			return this.defaultChase(ship, profile, pt);
		}
		Target target = target(shot.target(), ship, pt);
		double[] h = FlightKinematics.heading(ship.azimuth());
		Vec3 forward = new Vec3(h[0], 0.0, h[1]);
		Vec3 right = new Vec3(-h[1], 0.0, h[0]);
		Vec3 up = new Vec3(0.0, 1.0, 0.0);
		Vec3 position;
		Vec3 look;
		switch (shot.type()) {
			case ORBIT -> {
				double angle = Math.toRadians(shot.angle() + shot.speed() * seconds);
				position = target.mid.add(forward.scale(-Math.cos(angle) * shot.radius())).add(right.scale(-Math.sin(angle) * shot.radius()))
					.add(0.0, shot.height(), 0.0);
				look = target.mid;
			}
			case FIXED -> {
				position = anchor(shot.anchor(), ship, forward, right).add(frame(shot.offset(), right, up, forward));
				look = target.mid;
			}
			case ONBOARD -> {
				Vector3f o = target.attitude.transform(new Vector3f(shot.offset().get(0).floatValue(), shot.offset().get(1).floatValue(),
					shot.offset().get(2).floatValue()));
				Vector3f l = target.attitude.transform(new Vector3f(shot.look().get(0).floatValue(), shot.look().get(1).floatValue(),
					shot.look().get(2).floatValue()));
				position = target.origin.add(o.x, o.y, o.z);
				look = position.add(l.x, l.y, l.z);
				return shake(lookAt(position, look, (float) shot.fov()), shot.shake(), seconds);
			}
			default -> { // CHASE
				Vec3 wanted = frame(shot.offset(), right, up, forward);
				this.chaseOffset = this.chaseOffset == null ? wanted : this.chaseOffset.lerp(wanted, 0.08);
				position = target.mid.add(this.chaseOffset);
				look = target.mid;
			}
		}
		return shake(framed(position, look, (float) shot.fov(), player), shot.shake(), seconds);
	}

	private Pose defaultChase(StarshipEntity ship, FlightProfile profile, float pt) {
		boolean stacked = ship.missionTime(pt) < profile.stagingTime();
		Target target = target(stacked ? CameraShot.Target.STACK : CameraShot.Target.SHIP, ship, pt);
		double[] h = FlightKinematics.heading(ship.azimuth());
		double distance = stacked ? 190.0 : 95.0;
		Vec3 position = target.mid.add(-h[0] * distance * 0.8 + h[1] * distance * 0.45, distance * 0.25, -h[1] * distance * 0.8 - h[0] * distance * 0.45);
		return framed(position, target.mid, 60.0F, Minecraft.getInstance().player);
	}

	/** Free orbit: the player's own mouse look swings the camera around the ship. */
	private Pose orbitAroundShip(StarshipEntity ship, LocalPlayer player, float pt) {
		Target target = target(CameraShot.Target.SHIP, ship, pt);
		float yaw = player.getViewYRot(pt);
		float pitch = player.getViewXRot(pt);
		Vec3 view = Vec3.directionFromRotation(pitch, yaw);
		return framed(target.mid.subtract(view.scale(110.0)), target.mid, 65.0F, player);
	}

	/**
	 * The player's own eyes, strapped into their couch: the mouse looks around inside the cabin, and the whole view turns
	 * with the ship from the attitude it had standing on the pad.
	 */
	private Pose cabin(StarshipEntity ship, LocalPlayer player, float pt) {
		Quaternionf attitude = ship.attitude(pt, new Quaternionf());
		Quaternionf upright = FlightKinematics.attitude(90.0, ship.azimuth());
		Quaternionf turn = attitude.mul(upright.conjugate(), new Quaternionf());
		Vector3f eye = turn.transform(new Vector3f(0.0F, player.getEyeHeight(), 0.0F));
		Vec3 position = player.getPosition(pt).add(eye.x, eye.y, eye.z);
		Quaternionf orientation = turn.mul(rotation(player.getViewYRot(pt), player.getViewXRot(pt)), new Quaternionf());
		float fov = Minecraft.getInstance().options.fov().get().floatValue();
		return new Pose(position, orientation, fov, true);
	}

	// --------------------------------------------------------------------------------------------- helpers

	private record Target(Vec3 origin, Vec3 mid, Quaternionf attitude) {
	}

	private static Target target(CameraShot.Target kind, StarshipEntity ship, float pt) {
		VehicleEntity vehicle = ship;
		double height = StarshipGeometry.SHIP_HEIGHT;
		if (kind != CameraShot.Target.SHIP) {
			SuperHeavyEntity booster = ClientFlight.booster(ship);
			if (booster != null) {
				vehicle = booster;
				height = kind == CameraShot.Target.STACK ? StarshipGeometry.STACK_HEIGHT : StarshipGeometry.BOOSTER_HEIGHT;
			}
		}
		Quaternionf attitude = vehicle.attitude(pt, new Quaternionf());
		Vec3 origin = vehicle.getPosition(pt);
		Vector3f half = attitude.transform(new Vector3f(0.0F, (float) (height * 0.5), 0.0F));
		return new Target(origin, origin.add(half.x, half.y, half.z), attitude);
	}

	/** Whether this client has the booster, close enough to the crew that the world around it is drawn. */
	private static boolean boosterInView(StarshipEntity ship, LocalPlayer player) {
		SuperHeavyEntity booster = ClientFlight.booster(ship);
		return booster != null && horizontalDistance(booster.position(), player.position()) < renderDistanceBlocks() * REACH_FROM_CREW;
	}

	private static Vec3 anchor(CameraShot.Anchor anchor, StarshipEntity ship, Vec3 forward, Vec3 right) {
		Vec3 reference = ship.reference();
		return switch (anchor) {
			// No tower block yet: stand where one would be, beside the pad.
			case TOWER -> reference.add(right.scale(-22.0)).add(forward.scale(-6.0));
			default -> reference;
		};
	}

	private static Vec3 frame(List<Double> offset, Vec3 right, Vec3 up, Vec3 forward) {
		return right.scale(offset.get(0)).add(up.scale(offset.get(1))).add(forward.scale(offset.get(2)));
	}

	private static double renderDistanceBlocks() {
		return Math.max(2, Minecraft.getInstance().options.getEffectiveRenderDistance()) * 16.0;
	}

	private static double horizontalDistance(Vec3 a, Vec3 b) {
		double dx = a.x - b.x;
		double dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/**
	 * Aims a camera at its subject, first pulling it in along the line of sight until it stands within the crew's loaded
	 * area and close enough to the subject that the ground around it is drawn, then widening the field of view by the
	 * same ratio so the subject keeps its size on screen (up to {@value #MAX_FOV} degrees).
	 */
	private static Pose framed(Vec3 position, Vec3 subject, float fov, @Nullable LocalPlayer player) {
		double view = renderDistanceBlocks();
		double reachCrew = view * REACH_FROM_CREW;
		double reachSubject = view * REACH_FROM_SUBJECT;
		Vec3 crew = player != null ? player.position() : subject;
		Vec3 out = position.subtract(subject);
		double distance = out.length();
		double k = 1.0;
		if (distance > reachSubject) {
			k = reachSubject / distance;
		}
		for (int i = 0; i < 40 && horizontalDistance(subject.add(out.scale(k)), crew) > reachCrew && k * distance > 16.0; i++) {
			k *= 0.9;
		}
		Vec3 camera = subject.add(out.scale(k));
		float wider = fov;
		if (k < 1.0) {
			double half = Math.tan(Math.toRadians(fov) * 0.5) / k;
			wider = (float) Math.min(MAX_FOV, Math.toDegrees(2.0 * Math.atan(half)));
			wider = Math.max(fov, wider);
		}
		return lookAt(clearOfTerrain(camera), subject, wider);
	}

	private static Pose lookAt(Vec3 from, Vec3 to, float fov) {
		Vec3 d = to.subtract(from);
		float yaw = (float) (Mth.atan2(-d.x, d.z) * Mth.RAD_TO_DEG);
		float pitch = (float) (-Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
		return Pose.looking(from, yaw, pitch, fov);
	}

	/** Vanilla's camera rotation for a yaw and pitch (Camera.setRotation). */
	private static Quaternionf rotation(float yaw, float pitch) {
		return new Quaternionf().rotationYXZ((float) Math.PI - yaw * Mth.DEG_TO_RAD, -pitch * Mth.DEG_TO_RAD, 0.0F);
	}

	/** Lifts a camera out of the ground (cameras near the pad can end up inside hills). */
	private static Vec3 clearOfTerrain(Vec3 position) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return position;
		}
		Vec3 p = position;
		for (int i = 0; i < 64; i++) {
			BlockPos block = BlockPos.containing(p);
			if (mc.level.getBlockState(block).isAir() && mc.level.getBlockState(block.below()).getCollisionShape(mc.level, block).isEmpty()) {
				return p;
			}
			p = p.add(0.0, 1.0, 0.0);
		}
		return p;
	}

	private static Pose shake(Pose pose, double amount, double seconds) {
		double scale = amount * (1.0 - RedPlanetClientConfig.get().reduceShake());
		if (scale <= 0.0) {
			return pose;
		}
		double t = seconds * 23.0;
		double dx = (Math.sin(t * 1.3) + Math.sin(t * 2.9 + 1.0) * 0.5) * 0.35 * scale;
		double dy = (Math.sin(t * 1.7 + 2.0) + Math.sin(t * 3.7) * 0.5) * 0.35 * scale;
		float dyaw = (float) ((Math.sin(t * 2.3 + 0.5) + Math.sin(t * 4.1) * 0.4) * 0.6 * scale);
		float dpitch = (float) ((Math.sin(t * 1.9 + 1.5) + Math.sin(t * 3.3) * 0.4) * 0.6 * scale);
		Quaternionf jitter = new Quaternionf(pose.orientation()).rotateY(-dyaw * Mth.DEG_TO_RAD).rotateX(-dpitch * Mth.DEG_TO_RAD);
		return new Pose(pose.position().add(dx, dy, 0.0), jitter, pose.fov(), pose.firstPerson());
	}

	private Pose blend(Pose pose, long now) {
		if (this.blendFrom == null) {
			return pose;
		}
		double k = (now - this.blendStartMs) / 1000.0 / BLEND_SECONDS;
		if (k >= 1.0) {
			this.blendFrom = null;
			return pose;
		}
		float s = (float) (k * k * (3.0 - 2.0 * k));
		Pose a = this.blendFrom;
		Vec3 position = a.position().lerp(pose.position(), s);
		Quaternionf orientation = new Quaternionf(a.orientation()).slerp(pose.orientation(), s);
		float fov = Mth.lerp(s, a.fov(), pose.fov());
		return new Pose(position, orientation, fov, s < 0.5F ? a.firstPerson() : pose.firstPerson());
	}
}
