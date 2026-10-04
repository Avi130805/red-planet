package io.github.avi130805.redplanet.mars.weather;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.avi130805.redplanet.mars.geo.MarsProjection;

/**
 * One dust storm: where it is, how thick it gets and when. Shared by both sides; the optical depth at any place and
 * game time is a pure function of these fields, so the server only sends a storm once (MarsWeatherPayload).
 *
 * <p>Model: docs/SCIENCE.md section 11. Regional storms are a few thousand blocks across and last a few sols; a
 * global storm covers the planet for months, with optical depths like 2018's (tau ~8.5 at Gale, 10.8 at
 * Opportunity).
 *
 * @param global whether it covers the whole planet
 * @param centerX block x of a regional storm's centre
 * @param centerZ block z of a regional storm's centre
 * @param radius blocks from the centre to the storm's edge (regional)
 * @param peakTau visible optical depth at the core at full strength
 * @param startTime game time when it begins to grow
 * @param rampTicks ticks to reach full strength
 * @param holdUntil game time when it starts to decay
 * @param decayTicks ticks to clear
 */
public record DustStorm(boolean global, double centerX, double centerZ, double radius, double peakTau, long startTime, long rampTicks,
		long holdUntil, long decayTicks) {
	public static final Codec<DustStorm> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.BOOL.fieldOf("global").forGetter(DustStorm::global),
		Codec.DOUBLE.fieldOf("center_x").forGetter(DustStorm::centerX),
		Codec.DOUBLE.fieldOf("center_z").forGetter(DustStorm::centerZ),
		Codec.DOUBLE.fieldOf("radius").forGetter(DustStorm::radius),
		Codec.DOUBLE.fieldOf("peak_tau").forGetter(DustStorm::peakTau),
		Codec.LONG.fieldOf("start_time").forGetter(DustStorm::startTime),
		Codec.LONG.fieldOf("ramp_ticks").forGetter(DustStorm::rampTicks),
		Codec.LONG.fieldOf("hold_until").forGetter(DustStorm::holdUntil),
		Codec.LONG.fieldOf("decay_ticks").forGetter(DustStorm::decayTicks)
	).apply(i, DustStorm::new));

	public long endTime() {
		return this.holdUntil + this.decayTicks;
	}

	public boolean isOver(long gameTime) {
		return gameTime >= endTime();
	}

	/** Strength of the storm over time, 0..1. */
	public double envelope(double gameTime) {
		if (gameTime <= this.startTime || gameTime >= endTime()) {
			return 0.0;
		}
		if (gameTime < this.startTime + this.rampTicks) {
			return smooth((gameTime - this.startTime) / Math.max(1.0, this.rampTicks));
		}
		if (gameTime <= this.holdUntil) {
			return 1.0;
		}
		return 1.0 - smooth((gameTime - this.holdUntil) / Math.max(1.0, this.decayTicks));
	}

	/** Spatial weight 0..1: 1 inside the core, fading over the outer half of the radius (the map wraps in x). */
	public double weight(double x, double z) {
		if (this.global) {
			return 1.0;
		}
		double c = MarsProjection.CIRCUMFERENCE_BLOCKS;
		double dx = x - this.centerX;
		dx -= c * Math.round(dx / c);
		double d = Math.hypot(dx, z - this.centerZ);
		if (d >= this.radius) {
			return 0.0;
		}
		return d <= 0.5 * this.radius ? 1.0 : 1.0 - smooth((d - 0.5 * this.radius) / (0.5 * this.radius));
	}

	/** This storm's optical depth at a place and time, on top of the background haze. */
	public double tau(double x, double z, double gameTime, double backgroundTau) {
		double strength = envelope(gameTime) * weight(x, z);
		return backgroundTau + Math.max(0.0, this.peakTau - backgroundTau) * strength;
	}

	private static double smooth(double t) {
		double u = Math.clamp(t, 0.0, 1.0);
		return u * u * (3.0 - 2.0 * u);
	}
}
