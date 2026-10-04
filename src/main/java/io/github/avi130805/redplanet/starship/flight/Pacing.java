package io.github.avi130805.redplanet.starship.flight;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * How long the cinematic flight takes. Telemetry always shows the real profile; pacing only changes how
 * much real mission time each second of gameplay covers.
 */
public enum Pacing implements StringRepresentable {
	SHORT("short"),
	STANDARD("standard"),
	LONG("long");

	public static final Codec<Pacing> CODEC = StringRepresentable.fromEnum(Pacing::values);

	private final String name;

	Pacing(String name) {
		this.name = name;
	}

	@Override
	public String getSerializedName() {
		return this.name;
	}
}
