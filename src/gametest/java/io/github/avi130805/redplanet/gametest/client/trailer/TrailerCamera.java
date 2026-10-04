package io.github.avi130805.redplanet.gametest.client.trailer;

import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import net.minecraft.world.phys.Vec3;

/**
 * The trailer's scripted camera. While a pose is set it replaces whatever the game would show (the player's view or the
 * flight's cinematic camera); {@code TrailerCameraMixin} applies it. Only the trailer recorder sets it.
 */
public final class TrailerCamera {
	private static final float DEG = (float) (Math.PI / 180.0);
	private static volatile @Nullable Pose pose;

	private TrailerCamera() {
	}

	/** Position, heading (vanilla yaw: 0 looks south, +z), pitch (positive looks down), roll (positive banks right), FOV. */
	public record Pose(Vec3 position, float yaw, float pitch, float roll, float fov) {
		/** The camera's orientation, built as vanilla's {@code Camera.setRotation} does, plus the roll. */
		public Quaternionf orientation() {
			return new Quaternionf().rotationYXZ((float) Math.PI - this.yaw * DEG, -this.pitch * DEG, -this.roll * DEG);
		}

		/** A pose at {@code from} looking at {@code target}. */
		public static Pose looking(Vec3 from, Vec3 target, float roll, float fov) {
			Vec3 d = target.subtract(from);
			double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
			float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
			float pitch = (float) -Math.toDegrees(Math.atan2(d.y, horizontal));
			return new Pose(from, yaw, pitch, roll, fov);
		}
	}

	public static @Nullable Pose get() {
		return pose;
	}

	public static void set(@Nullable Pose next) {
		pose = next;
	}
}
