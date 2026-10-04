package io.github.avi130805.redplanet.network;

import io.github.avi130805.redplanet.RedPlanet;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * S2C: the pressurized air of every habitat in a level, as 4096-bit sets per chunk section (the client's
 * {@code HabitatIndex}). Sent when a habitat pressurizes, changes shape or vents, and when a player arrives.
 */
public record HabitatSyncPayload(ResourceKey<Level> level, Long2ObjectMap<long[]> sections) implements CustomPacketPayload {
	public static final Type<HabitatSyncPayload> TYPE = new Type<>(RedPlanet.id("habitat_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, HabitatSyncPayload> CODEC = StreamCodec.of(HabitatSyncPayload::write,
		HabitatSyncPayload::read);

	private static void write(RegistryFriendlyByteBuf buf, HabitatSyncPayload payload) {
		buf.writeResourceKey(payload.level);
		buf.writeVarInt(payload.sections.size());
		for (Long2ObjectMap.Entry<long[]> e : payload.sections.long2ObjectEntrySet()) {
			buf.writeLong(e.getLongKey());
			for (long word : e.getValue()) {
				buf.writeLong(word);
			}
		}
	}

	private static HabitatSyncPayload read(RegistryFriendlyByteBuf buf) {
		ResourceKey<Level> level = buf.readResourceKey(Registries.DIMENSION);
		int count = buf.readVarInt();
		Long2ObjectOpenHashMap<long[]> sections = new Long2ObjectOpenHashMap<>(count);
		for (int i = 0; i < count; i++) {
			long key = buf.readLong();
			long[] bits = new long[64];
			for (int w = 0; w < 64; w++) {
				bits[w] = buf.readLong();
			}
			sections.put(key, bits);
		}
		return new HabitatSyncPayload(level, sections);
	}

	@Override
	public Type<HabitatSyncPayload> type() {
		return TYPE;
	}
}
