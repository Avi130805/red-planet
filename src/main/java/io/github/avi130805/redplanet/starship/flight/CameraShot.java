package io.github.avi130805.redplanet.starship.flight;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.StringRepresentable;

/**
 * A cinematic camera shot, active during a phase between two progress fractions.
 *
 * <p>Shot types:
 * <ul>
 * <li>{@code orbit}: circles the target at {@code radius}, {@code height} above its base, at {@code speed} deg/s
 * starting from {@code angle} (degrees, 0 = east).</li>
 * <li>{@code fixed}: a world position relative to the anchor (pad or landing site), looking at the target.</li>
 * <li>{@code chase}: follows the target from an offset in the target's body frame, smoothed.</li>
 * <li>{@code onboard}: rigidly mounted on the target at a body-frame offset, looking along {@code look}.</li>
 * <li>{@code cabin}: the passenger's own view out of the crew cabin windows.</li>
 * </ul>
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
