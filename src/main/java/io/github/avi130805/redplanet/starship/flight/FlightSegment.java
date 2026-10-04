package io.github.avi130805.redplanet.starship.flight;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * Where a flight phase happens. The server uses this to decide how the ship moves; the client uses it to
 * decide between in-world cinematics and the transfer interlude.
 */
public enum FlightSegment implements StringRepresentable {
	/** On the origin pad: propellant load, count, ignition. The ship does not move. */
	ORIGIN_PAD("origin_pad"),
	/** Climbing out of the origin world along the mapped ascent trajectory. */
	ASCENT("ascent"),
	/** Between worlds: the space interlude covers the dimension change. */
	TRANSFER("transfer"),
	/** Entry, belly flop, flip and landing burn above the destination site. */
	DESCENT("descent"),
	/** Landed at the destination; the mission is complete. */
	LANDED("landed");

	public static final Codec<FlightSegment> CODEC = StringRepresentable.fromEnum(FlightSegment::values);

	private final String name;

	FlightSegment(String name) {
		this.name = name;
	}

	@Override
	public String getSerializedName() {
		return this.name;
	}
}
