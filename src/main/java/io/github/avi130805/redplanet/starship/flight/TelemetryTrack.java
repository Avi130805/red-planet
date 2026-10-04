package io.github.avi130805.redplanet.starship.flight;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Real telemetry of one vehicle (booster or ship) as keyframes over mission time, interpolated linearly.
 * Values are the ones a SpaceX webcast shows: altitude in km, speed in km/h, LOX and CH4 fill fractions
 * and the number of Raptors burning, plus the two that place the vehicle in the world:
 * <ul>
 * <li>{@code downrange_km}: signed distance along the flight heading from the reference point (the launch pad
 * during the origin and ascent segments, the landing site during descent; negative before the landing site);</li>
 * <li>{@code pitch_deg}: the angle of the nose above the local horizon, in the vertical plane of the heading.
 * 90 is nose up, 0 is horizontal with the nose leading and the belly (heat shield) down, 180 is nose pointing back
 * along the heading (a booster during boostback). Values run continuously, so a flip is a sweep, not a jump.</li>
 * </ul>
 */
public record TelemetryTrack(List<Point> points) {
	public static final Codec<TelemetryTrack> CODEC = Point.CODEC.listOf().xmap(TelemetryTrack::new, TelemetryTrack::points);

	public record Point(double t, double altitudeKm, double speedKmh, double lox, double ch4, int engines, double downrangeKm,
			double pitchDeg) {
		public static final Codec<Point> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.DOUBLE.fieldOf("t").forGetter(Point::t),
			Codec.DOUBLE.fieldOf("alt_km").forGetter(Point::altitudeKm),
			Codec.DOUBLE.fieldOf("speed_kmh").forGetter(Point::speedKmh),
			Codec.DOUBLE.optionalFieldOf("lox", 1.0).forGetter(Point::lox),
			Codec.DOUBLE.optionalFieldOf("ch4", 1.0).forGetter(Point::ch4),
			Codec.INT.optionalFieldOf("engines", 0).forGetter(Point::engines),
			Codec.DOUBLE.optionalFieldOf("downrange_km", 0.0).forGetter(Point::downrangeKm),
			Codec.DOUBLE.optionalFieldOf("pitch_deg", 90.0).forGetter(Point::pitchDeg)
		).apply(i, Point::new));

		public Point(double t, double altitudeKm, double speedKmh, double lox, double ch4, int engines) {
			this(t, altitudeKm, speedKmh, lox, ch4, engines, 0.0, 90.0);
		}
	}

	/** Interpolated sample. Engines switch at keyframes (no fractional engines). */
	public record Sample(double altitudeKm, double speedKmh, double lox, double ch4, int engines, double downrangeKm, double pitchDeg) {
		public Sample(double altitudeKm, double speedKmh, double lox, double ch4, int engines) {
			this(altitudeKm, speedKmh, lox, ch4, engines, 0.0, 90.0);
		}
	}

	public TelemetryTrack {
		points = List.copyOf(points);
		for (int k = 1; k < points.size(); k++) {
			if (points.get(k).t() < points.get(k - 1).t()) {
				throw new IllegalArgumentException("Telemetry keyframes must be sorted by t");
			}
		}
	}

	public boolean isEmpty() {
		return this.points.isEmpty();
	}

	public double startTime() {
		return this.points.isEmpty() ? 0 : this.points.getFirst().t();
	}

	public double endTime() {
		return this.points.isEmpty() ? 0 : this.points.getLast().t();
	}

	public Sample sample(double t) {
		if (this.points.isEmpty()) {
			return new Sample(0, 0, 0, 0, 0);
		}
		Point first = this.points.getFirst();
		if (t <= first.t()) {
			return at(first);
		}
		Point last = this.points.getLast();
		if (t >= last.t()) {
			return at(last);
		}
		int lo = 0;
		int hi = this.points.size() - 1;
		while (hi - lo > 1) {
			int mid = (lo + hi) >>> 1;
			if (this.points.get(mid).t() <= t) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		Point a = this.points.get(lo);
		Point b = this.points.get(hi);
		double f = (t - a.t()) / (b.t() - a.t());
		return new Sample(
			lerp(a.altitudeKm(), b.altitudeKm(), f),
			lerp(a.speedKmh(), b.speedKmh(), f),
			lerp(a.lox(), b.lox(), f),
			lerp(a.ch4(), b.ch4(), f),
			a.engines(),
			lerp(a.downrangeKm(), b.downrangeKm(), f),
			lerp(a.pitchDeg(), b.pitchDeg(), f));
	}

	private static Sample at(Point p) {
		return new Sample(p.altitudeKm(), p.speedKmh(), p.lox(), p.ch4(), p.engines(), p.downrangeKm(), p.pitchDeg());
	}

	private static double lerp(double a, double b, double f) {
		return a + (b - a) * f;
	}
}
