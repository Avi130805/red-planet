package io.github.avi130805.redplanet.network;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.mars.PlanetSettings;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** S2C on join: the world's calendar and sky inputs ({@link PlanetSettings}). */
public record PlanetSettingsPayload(double startLs, double yearCompression, double earthPhaseAtStartDeg, double moonPhaseSeed)
		implements CustomPacketPayload {
	public static final Type<PlanetSettingsPayload> TYPE = new Type<>(RedPlanet.id("planet_settings"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PlanetSettingsPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.DOUBLE, PlanetSettingsPayload::startLs,
		ByteBufCodecs.DOUBLE, PlanetSettingsPayload::yearCompression,
		ByteBufCodecs.DOUBLE, PlanetSettingsPayload::earthPhaseAtStartDeg,
		ByteBufCodecs.DOUBLE, PlanetSettingsPayload::moonPhaseSeed,
		PlanetSettingsPayload::new);

	public static PlanetSettingsPayload of(PlanetSettings s) {
		return new PlanetSettingsPayload(s.startLs(), s.yearCompression(), s.earthPhaseAtStartDeg(), s.moonPhaseSeed());
	}

	public PlanetSettings settings() {
		return new PlanetSettings(this.startLs, this.yearCompression, this.earthPhaseAtStartDeg, this.moonPhaseSeed);
	}

	@Override
	public Type<PlanetSettingsPayload> type() {
		return TYPE;
	}
}
