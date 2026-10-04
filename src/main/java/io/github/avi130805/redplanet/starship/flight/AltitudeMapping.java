package io.github.avi130805.redplanet.starship.flight;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Maps real distances (metres of altitude or downrange) to in-world blocks during ascent and descent.
 *
 * <p>The first {@code trueScale} metres are drawn 1:1, so liftoff, the tower clear and the landing are at true
 * scale. Beyond that, distance is compressed logarithmically with a continuous slope:
 * {@code blocks = t + s * ln(1 + (h - t) / s)}. With the defaults (t = 300 m, s = 190 m) the 65 km staging
 * altitude lands about 1,400 blocks up and 150 km about 1,570 blocks up, so the climb stays in view, keeps
 * accelerating visually, and still clears the clouds and darkens the sky.
 */
public record AltitudeMapping(double trueScaleMetres, double logScaleMetres) {
	public static final AltitudeMapping DEFAULT = new AltitudeMapping(300.0, 190.0);

	public static final Codec<AltitudeMapping> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.DOUBLE.optionalFieldOf("true_scale_m", DEFAULT.trueScaleMetres).forGetter(AltitudeMapping::trueScaleMetres),
		Codec.DOUBLE.optionalFieldOf("log_scale_m", DEFAULT.logScaleMetres).forGetter(AltitudeMapping::logScaleMetres)
	).apply(i, AltitudeMapping::new));

	public AltitudeMapping {
		if (trueScaleMetres < 0 || logScaleMetres <= 0) {
			throw new IllegalArgumentException("Invalid altitude mapping");
		}
	}

	/** In-world blocks for a real distance in metres (monotonic, continuous slope). */
	public double toBlocks(double metres) {
		if (metres <= this.trueScaleMetres) {
			return metres;
		}
		return this.trueScaleMetres + this.logScaleMetres * Math.log1p((metres - this.trueScaleMetres) / this.logScaleMetres);
	}

	/** Inverse of {@link #toBlocks}. */
	public double toMetres(double blocks) {
		if (blocks <= this.trueScaleMetres) {
			return blocks;
		}
		return this.trueScaleMetres + this.logScaleMetres * Math.expm1((blocks - this.trueScaleMetres) / this.logScaleMetres);
	}
}
