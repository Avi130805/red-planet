package io.github.avi130805.redplanet.starship.flight;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.StringRepresentable;

/**
 * A cinematic camera shot, active during a phase between two progress fractions.
 *
 * <p>Two frames are used. The <b>heading frame</b> keeps the horizon level: x to the right of the flight heading,
 * y up, z forward along the heading (the launch azimuth on ascent, the approach direction on descent). The
 * <b>body frame</b> turns with the vehicle: origin at the bottom of the aft skirt, x to the vehicle's right, y toward
 * the nose, z toward the belly (the heat shield). Distances are blocks (= metres near the ground).
 *
 * <p>Shot types:
 * <ul>
 * <li>{@code orbit}: circles the target's mid-height point at {@code radius}, {@code height} above it, at
 * {@code speed} degrees per second, starting {@code angle} degrees clockwise from straight behind (0 = looking
 * along the heading).</li>
 * <li>{@code fixed}: stands at {@code offset} (heading frame) from the anchor (pad, landing site or tower) and
 * tracks the target.</li>
 * <li>{@code chase}: follows the target's mid-height point at {@code offset} (heading frame), smoothed, looking at
 * it.</li>
 * <li>{@code onboard}: rigidly mounted at {@code offset} (body frame), looking along {@code look} (body frame).</li>
 * <li>{@code cabin}: the passenger's own eyes in the crew cabin, free to look around.</li>
 * </ul>
 * {@code fov} is the vertical field of view in degrees, {@code shake} the shake amplitude (0..1, scaled by the
 * player's reduce-shake setting) and {@code cut} whether the shot starts with a hard cut (false: blend from the
 * previous shot).
 */
public record CameraShot(
	String phase,
	double from,
	double to,
	Type type,
	Target target,
	Anchor anchor,
	List<Double> offset,
	List<Double> look,
	double radius,
	double height,
	double speed,
	double angle,
	double fov,
	double shake,
	boolean cut
) {
	public static final Codec<CameraShot> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.STRING.fieldOf("phase").forGetter(CameraShot::phase),
		Codec.doubleRange(0.0, 1.0).optionalFieldOf("from", 0.0).forGetter(CameraShot::from),
		Codec.doubleRange(0.0, 1.0).optionalFieldOf("to", 1.0).forGetter(CameraShot::to),
		Type.CODEC.fieldOf("type").forGetter(CameraShot::type),
		Target.CODEC.optionalFieldOf("target", Target.SHIP).forGetter(CameraShot::target),
		Anchor.CODEC.optionalFieldOf("anchor", Anchor.PAD).forGetter(CameraShot::anchor),
		Codec.DOUBLE.listOf(3, 3).optionalFieldOf("offset", List.of(0.0, 0.0, 0.0)).forGetter(CameraShot::offset),
		Codec.DOUBLE.listOf(3, 3).optionalFieldOf("look", List.of(0.0, 0.0, 0.0)).forGetter(CameraShot::look),
		Codec.DOUBLE.optionalFieldOf("radius", 120.0).forGetter(CameraShot::radius),
		Codec.DOUBLE.optionalFieldOf("height", 30.0).forGetter(CameraShot::height),
		Codec.DOUBLE.optionalFieldOf("speed", 0.0).forGetter(CameraShot::speed),
		Codec.DOUBLE.optionalFieldOf("angle", 0.0).forGetter(CameraShot::angle),
		Codec.DOUBLE.optionalFieldOf("fov", 70.0).forGetter(CameraShot::fov),
		Codec.DOUBLE.optionalFieldOf("shake", 0.0).forGetter(CameraShot::shake),
		Codec.BOOL.optionalFieldOf("cut", true).forGetter(CameraShot::cut)
	).apply(i, CameraShot::new));

	public enum Type implements StringRepresentable {
		ORBIT("orbit"), FIXED("fixed"), CHASE("chase"), ONBOARD("onboard"), CABIN("cabin");

		public static final Codec<Type> CODEC = StringRepresentable.fromEnum(Type::values);
		private final String name;

		Type(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return this.name;
		}
	}

	/** What the camera looks at or rides on. */
	public enum Target implements StringRepresentable {
		SHIP("ship"), BOOSTER("booster"), STACK("stack");

		public static final Codec<Target> CODEC = StringRepresentable.fromEnum(Target::values);
		private final String name;

		Target(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return this.name;
		}
	}

	/** World reference for fixed shots. */
	public enum Anchor implements StringRepresentable {
		PAD("pad"), LANDING_SITE("landing_site"), TOWER("tower");

		public static final Codec<Anchor> CODEC = StringRepresentable.fromEnum(Anchor::values);
		private final String name;

		Anchor(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return this.name;
		}
	}

	public boolean activeAt(double progress) {
		return progress >= this.from && progress < this.to;
	}
}
