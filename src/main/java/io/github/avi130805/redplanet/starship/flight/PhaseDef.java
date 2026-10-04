package io.github.avi130805.redplanet.starship.flight;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.StringRepresentable;

/**
 * One phase of a flight profile.
 *
 * <p>{@code missionStart}/{@code missionEnd} are real mission time in seconds (T minus is negative). The phase
 * plays for {@code durations.get(pacing)} seconds of gameplay, so the mission clock runs faster than real time by
 * {@code (missionEnd - missionStart) / duration}; telemetry stays true at every displayed T.
 */
public record PhaseDef(
	String id,
	FlightSegment segment,
	double missionStart,
	double missionEnd,
	Durations durations,
	Easing clock,
	List<Event> events
) {
	public static final Codec<PhaseDef> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.STRING.fieldOf("id").forGetter(PhaseDef::id),
		FlightSegment.CODEC.fieldOf("segment").forGetter(PhaseDef::segment),
		Codec.DOUBLE.fieldOf("mission_start").forGetter(PhaseDef::missionStart),
		Codec.DOUBLE.fieldOf("mission_end").forGetter(PhaseDef::missionEnd),
		Durations.CODEC.fieldOf("duration").forGetter(PhaseDef::durations),
		Easing.CODEC.optionalFieldOf("clock", Easing.LINEAR).forGetter(PhaseDef::clock),
		Event.CODEC.listOf().optionalFieldOf("events", List.of()).forGetter(PhaseDef::events)
	).apply(i, PhaseDef::new));

	public PhaseDef {
		events = List.copyOf(events);
		if (missionEnd < missionStart) {
			throw new IllegalArgumentException("Phase " + id + " ends before it starts");
		}
	}

	/** Gameplay seconds per pacing preset. */
	public record Durations(double shortSeconds, double standardSeconds, double longSeconds) {
		public static final Codec<Durations> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.DOUBLE.fieldOf("short").forGetter(Durations::shortSeconds),
			Codec.DOUBLE.fieldOf("standard").forGetter(Durations::standardSeconds),
			Codec.DOUBLE.fieldOf("long").forGetter(Durations::longSeconds)
		).apply(i, Durations::new));

		public double get(Pacing pacing) {
			return switch (pacing) {
				case SHORT -> this.shortSeconds;
				case STANDARD -> this.standardSeconds;
				case LONG -> this.longSeconds;
			};
		}
	}

	/** How mission time advances within the phase. */
	public enum Easing implements StringRepresentable {
		LINEAR("linear"),
		EASE_IN("ease_in"),
		EASE_OUT("ease_out"),
		EASE_IN_OUT("ease_in_out");

		public static final Codec<Easing> CODEC = StringRepresentable.fromEnum(Easing::values);
		private final String name;

		Easing(String name) {
			this.name = name;
		}

		public double apply(double p) {
			double t = Math.clamp(p, 0.0, 1.0);
			return switch (this) {
				case LINEAR -> t;
				case EASE_IN -> t * t;
				case EASE_OUT -> 1.0 - (1.0 - t) * (1.0 - t);
				case EASE_IN_OUT -> t * t * (3.0 - 2.0 * t);
			};
		}

		@Override
		public String getSerializedName() {
			return this.name;
		}
	}

	/** A named event at a fraction of the phase (e.g. "max_q", "hot_staging", "legs_deploy"). */
	public record Event(double at, String type, Optional<String> label) {
		public static final Codec<Event> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.doubleRange(0.0, 1.0).fieldOf("at").forGetter(Event::at),
			Codec.STRING.fieldOf("type").forGetter(Event::type),
			Codec.STRING.optionalFieldOf("label").forGetter(Event::label)
		).apply(i, Event::new));
	}

	/** Mission time (s) at a progress fraction of this phase. */
	public double missionTimeAt(double progress) {
		return this.missionStart + (this.missionEnd - this.missionStart) * this.clock.apply(progress);
	}
}
