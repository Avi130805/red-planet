package io.github.avi130805.redplanet.starship.flight;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;

/**
 * A whole flight, loaded from {@code data/<namespace>/redplanet/flight_profile/<id>.json} (a synced datapack registry,
 * so clients read the same profile for the HUD and the camera).
 *
 * <p>The flight runs on real mission time (T± seconds): every phase covers a real span and plays for a gameplay
 * duration set by the pacing. Telemetry is keyed on mission time, so the HUD always shows true numbers while the
 * motion in the world is compressed. In-world positions come from the telemetry through two mappings: altitude
 * ({@link #altitude}) and downrange distance ({@link #downrange}), both true to scale near the ground.
 *
 * @param destination the dimension the flight lands in
 * @param vehicle what leaves the pad: the full stack (Earth) or the ship alone (Mars)
 * @param launchAzimuthDeg the ascent heading, degrees clockwise from north (90 = east)
 * @param altitude maps real altitude to blocks above the reference point
 * @param downrange maps real downrange distance to blocks along the heading
 * @param phases the timeline, contiguous in mission time and ordered by segment
 * @param ship the ship's telemetry
 * @param booster Super Heavy's telemetry (empty for a ship-only flight)
 * @param shots cinematic camera shots
 * @param interlude what the transfer interlude shows
 */
public record FlightProfile(
	ResourceKey<Level> destination,
	Vehicle vehicle,
	double launchAzimuthDeg,
	AltitudeMapping altitude,
	AltitudeMapping downrange,
	List<PhaseDef> phases,
	TelemetryTrack ship,
	TelemetryTrack booster,
	List<CameraShot> shots,
	Interlude interlude
) {
	/** Downrange is squeezed harder than altitude, so the climb arcs away without outrunning chunk generation. */
	public static final AltitudeMapping DEFAULT_DOWNRANGE = new AltitudeMapping(300.0, 60.0);

	private static final Codec<FlightProfile> RAW_CODEC = RecordCodecBuilder.create(i -> i.group(
		ResourceKey.codec(Registries.DIMENSION).fieldOf("destination").forGetter(FlightProfile::destination),
		Vehicle.CODEC.optionalFieldOf("vehicle", Vehicle.STACK).forGetter(FlightProfile::vehicle),
		Codec.DOUBLE.optionalFieldOf("launch_azimuth_deg", 90.0).forGetter(FlightProfile::launchAzimuthDeg),
		AltitudeMapping.CODEC.optionalFieldOf("altitude_mapping", AltitudeMapping.DEFAULT).forGetter(FlightProfile::altitude),
		AltitudeMapping.CODEC.optionalFieldOf("downrange_mapping", DEFAULT_DOWNRANGE).forGetter(FlightProfile::downrange),
		PhaseDef.CODEC.listOf(1, Integer.MAX_VALUE).fieldOf("phases").forGetter(FlightProfile::phases),
		TelemetryTrack.CODEC.fieldOf("ship").forGetter(FlightProfile::ship),
		TelemetryTrack.CODEC.optionalFieldOf("booster", new TelemetryTrack(List.of())).forGetter(FlightProfile::booster),
		CameraShot.CODEC.listOf().optionalFieldOf("shots", List.of()).forGetter(FlightProfile::shots),
		Interlude.CODEC.optionalFieldOf("interlude", Interlude.DEFAULT).forGetter(FlightProfile::interlude)
	).apply(i, FlightProfile::new));

	public static final Codec<FlightProfile> CODEC = RAW_CODEC.validate(FlightProfile::validate);

	public FlightProfile {
		phases = List.copyOf(phases);
		shots = List.copyOf(shots);
	}

	/** Checks that the timeline is contiguous and the segments come in flight order. */
	private static DataResult<FlightProfile> validate(FlightProfile profile) {
		List<PhaseDef> phases = profile.phases();
		for (int k = 1; k < phases.size(); k++) {
			PhaseDef a = phases.get(k - 1);
			PhaseDef b = phases.get(k);
			if (Math.abs(a.missionEnd() - b.missionStart()) > 1.0E-6) {
				return DataResult.error(() -> "Phase " + b.id() + " starts at T" + b.missionStart() + " but " + a.id() + " ends at T" + a.missionEnd());
			}
			if (b.segment().ordinal() < a.segment().ordinal()) {
				return DataResult.error(() -> "Phase " + b.id() + " (" + b.segment().getSerializedName() + ") comes after a later segment");
			}
		}
		for (PhaseDef phase : phases) {
			for (Pacing pacing : Pacing.values()) {
				if (!(phase.durations().get(pacing) > 0.0)) {
					return DataResult.error(() -> "Phase " + phase.id() + " has no " + pacing.getSerializedName() + " duration");
				}
			}
		}
		if (profile.vehicle() == Vehicle.STACK && profile.booster().isEmpty()) {
			return DataResult.error(() -> "A stack flight needs booster telemetry");
		}
		for (CameraShot shot : profile.shots()) {
			if (profile.phaseIndex(shot.phase()) < 0) {
				return DataResult.error(() -> "Camera shot for unknown phase " + shot.phase());
			}
		}
		return DataResult.success(profile);
	}

	/** Index of the phase with this id, or -1. */
	public int phaseIndex(String id) {
		for (int k = 0; k < this.phases.size(); k++) {
			if (this.phases.get(k).id().equals(id)) {
				return k;
			}
		}
		return -1;
	}

	/** Index of the first phase of a segment, or -1. */
	public int firstPhaseOf(FlightSegment segment) {
		for (int k = 0; k < this.phases.size(); k++) {
			if (this.phases.get(k).segment() == segment) {
				return k;
			}
		}
		return -1;
	}

	/** Gameplay length of a phase in ticks (at least one). */
	public int phaseTicks(int phase, Pacing pacing) {
		return Math.max(1, (int) Math.round(this.phases.get(phase).durations().get(pacing) * 20.0));
	}

	/** Gameplay length of the whole flight in ticks. */
	public int totalTicks(Pacing pacing) {
		int total = 0;
		for (int k = 0; k < this.phases.size(); k++) {
			total += this.phaseTicks(k, pacing);
		}
		return total;
	}

	/** Mission time (s) at a tick within a phase; partial ticks give smooth client clocks. */
	public double missionTime(int phase, double phaseTick, Pacing pacing) {
		PhaseDef def = this.phases.get(phase);
		return def.missionTimeAt(phaseTick / this.phaseTicks(phase, pacing));
	}

	/** The first mission time at which an event of this type happens, if the profile has one. */
	public Optional<Double> eventTime(String type) {
		for (PhaseDef phase : this.phases) {
			for (PhaseDef.Event event : phase.events()) {
				if (event.type().equals(type)) {
					return Optional.of(phase.missionTimeAt(event.at()));
				}
			}
		}
		return Optional.empty();
	}

	/** Mission time of hot staging, when the ship leaves the booster; infinite for a ship-only flight. */
	public double stagingTime() {
		return this.vehicle == Vehicle.STACK ? this.eventTime("hot_staging").orElse(Double.POSITIVE_INFINITY) : Double.NEGATIVE_INFINITY;
	}

	/** What lifts off. */
	public enum Vehicle implements StringRepresentable {
		/** Ship on Super Heavy: launches from Earth. */
		STACK("stack"),
		/** The ship alone: launches from Mars, where gravity is 0.38 g. */
		SHIP("ship");

		public static final Codec<Vehicle> CODEC = StringRepresentable.fromEnum(Vehicle::values);
		private final String name;

		Vehicle(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return this.name;
		}
	}

	/**
	 * The transfer interlude's readouts.
	 *
	 * @param from the departure planet ("earth" or "mars")
	 * @param to the arrival planet
	 * @param transferDays the coast length shown by the mission clock
	 * @param departureDvKmS the injection burn's delta-v
	 * @param arrivalSpeedKmS the speed at the entry interface
	 * @param tankerFlights refilling flights shown in orbit (0 for none)
	 */
	public record Interlude(String from, String to, double transferDays, double departureDvKmS, double arrivalSpeedKmS, int tankerFlights) {
		public static final Interlude DEFAULT = new Interlude("earth", "mars", 182.0, 3.6, 7.5, 0);

		public static final Codec<Interlude> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("from").forGetter(Interlude::from),
			Codec.STRING.fieldOf("to").forGetter(Interlude::to),
			Codec.DOUBLE.fieldOf("transfer_days").forGetter(Interlude::transferDays),
			Codec.DOUBLE.fieldOf("departure_dv_km_s").forGetter(Interlude::departureDvKmS),
			Codec.DOUBLE.fieldOf("arrival_speed_km_s").forGetter(Interlude::arrivalSpeedKmS),
			Codec.INT.optionalFieldOf("tanker_flights", 0).forGetter(Interlude::tankerFlights)
		).apply(i, Interlude::new));
	}
}
