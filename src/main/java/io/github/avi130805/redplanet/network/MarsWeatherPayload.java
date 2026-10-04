package io.github.avi130805.redplanet.network;

import java.util.Optional;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.mars.weather.DustStorm;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** S2C: the current Mars dust storm, if any. Sent when it changes and when a player arrives on Mars. */
public record MarsWeatherPayload(Optional<DustStorm> storm) implements CustomPacketPayload {
	public static final Type<MarsWeatherPayload> TYPE = new Type<>(RedPlanet.id("mars_weather"));
	public static final StreamCodec<RegistryFriendlyByteBuf, MarsWeatherPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.optional(ByteBufCodecs.fromCodecWithRegistries(DustStorm.CODEC)), MarsWeatherPayload::storm,
		MarsWeatherPayload::new);

	@Override
	public Type<MarsWeatherPayload> type() {
		return TYPE;
	}
}
