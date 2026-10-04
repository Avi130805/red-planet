package io.github.avi130805.redplanet.suit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Oxygen carried by an item (a suit's life-support torso, a canister), in kilograms, with the vessel's capacity. An
 * item data component, so every stack keeps its own amount.
 */
public record Oxygen(float kg, float capacityKg) {
	public static final Codec<Oxygen> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.floatRange(0.0F, 1000.0F).fieldOf("kg").forGetter(Oxygen::kg),
		Codec.floatRange(0.0F, 1000.0F).fieldOf("capacity_kg").forGetter(Oxygen::capacityKg)
	).apply(i, Oxygen::new));

	public static final StreamCodec<ByteBuf, Oxygen> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.FLOAT, Oxygen::kg,
		ByteBufCodecs.FLOAT, Oxygen::capacityKg,
		Oxygen::new);

	public static Oxygen full(float capacityKg) {
		return new Oxygen(capacityKg, capacityKg);
	}

	public static Oxygen empty(float capacityKg) {
		return new Oxygen(0.0F, capacityKg);
	}

	public Oxygen withKg(float value) {
		return new Oxygen(Math.max(0.0F, Math.min(this.capacityKg, value)), this.capacityKg);
	}

	public float room() {
		return Math.max(0.0F, this.capacityKg - this.kg);
	}

	public float fraction() {
		return this.capacityKg <= 0.0F ? 0.0F : this.kg / this.capacityKg;
	}

	public boolean isEmpty() {
		return this.kg <= 0.0F;
	}

	public boolean isFull() {
		return this.kg >= this.capacityKg;
	}
}
