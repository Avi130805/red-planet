package io.github.avi130805.redplanet.starship.flight;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Real telemetry of one vehicle (booster or ship) as keyframes over mission time, interpolated linearly.
 * Values are the ones a SpaceX webcast shows: altitude in km, speed in km/h, LOX and CH4 fill fractions
 * and the number of Raptors burning.
 */
public record TelemetryTrack(List<Point> points) {
	public static final Codec<TelemetryTrack> CODEC = Point.CODEC.listOf().xmap(TelemetryTrack::new, TelemetryTrack::points);

	public record Point(double t, double altitudeKm, double speedKmh, double lox, double ch4, int engines) {
		public static final Codec<Point> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.DOUBLE.fieldOf("t").forGetter(Point::t),
			Codec.DOUBLE.fieldOf("alt_km").forGetter(Point::altitudeKm),
			Codec.DOUBLE.fieldOf("speed_kmh").forGetter(Point::speedKmh),
			Codec.DOUBLE.optionalFieldOf("lox", 1.0).forGetter(Point::lox),
			Codec.DOUBLE.optionalFieldOf("ch4", 1.0).forGetter(Point::ch4),
			Codec.INT.optionalFieldOf("engines", 0).forGetter(Point::engines)
		).apply(i, Point::new));
	}

	/** Interpolated sample. Engines switch at keyframes (no fractional engines). */
	public record Sample(double altitudeKm, double speedKmh, double lox, double ch4, int engines) {
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
			return new Sample(first.altitudeKm(), first.speedKmh(), first.lox(), first.ch4(), first.engines());
		}
		Point last = this.points.getLast();
		if (t >= last.t()) {
			return new Sample(last.altitudeKm(), last.speedKmh(), last.lox(), last.ch4(), last.engines());
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
			a.engines());
	}

	private static double lerp(double a, double b, double f) {
		return a + (b - a) * f;
	}
}
