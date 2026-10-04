package io.github.avi130805.redplanet.habitat;

import java.util.HashMap;
import java.util.Map;

import io.github.avi130805.redplanet.network.HabitatSyncPayload;
import io.github.avi130805.redplanet.network.RPNetworking;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import org.jspecify.annotations.Nullable;

/**
 * The server's list of breathable habitats: each regulator reports the air it keeps pressurized; their union goes into
 * {@link HabitatIndex} (which the environment rules read: breathing, fire, water, sound, radiation, pressure) and out to
 * the players in that level.
 */
public final class HabitatManager {
	private static final Map<ResourceKey<Level>, Map<BlockPos, LongOpenHashSet>> HABITATS = new HashMap<>();

	private HabitatManager() {
	}

	public static void init() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendTo(handler.getPlayer()));
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> sendTo(player));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> HABITATS.clear());
	}

	/** Reports a regulator's breathable air, or null once it no longer holds any. */
	public static void set(ServerLevel level, BlockPos regulator, @Nullable LongOpenHashSet air) {
		Map<BlockPos, LongOpenHashSet> habitats = HABITATS.computeIfAbsent(level.dimension(), k -> new HashMap<>());
		boolean changed;
		if (air == null) {
			changed = habitats.remove(regulator) != null;
		} else {
			LongOpenHashSet copy = new LongOpenHashSet(air);
			changed = !copy.equals(habitats.put(regulator.immutable(), copy));
		}
		if (changed) {
			publish(level, habitats);
		}
	}

	private static void publish(ServerLevel level, Map<BlockPos, LongOpenHashSet> habitats) {
		LongOpenHashSet union = new LongOpenHashSet();
		for (LongOpenHashSet air : habitats.values()) {
			union.addAll(air);
		}
		HabitatIndex.replacePacked(level, union);
		HabitatSyncPayload payload = new HabitatSyncPayload(level.dimension(), HabitatIndex.snapshot(level));
		for (ServerPlayer player : level.players()) {
			RPNetworking.send(player, payload);
		}
	}

	public static void sendTo(ServerPlayer player) {
		ServerLevel level = player.level();
		RPNetworking.send(player, new HabitatSyncPayload(level.dimension(), HabitatIndex.snapshot(level)));
	}
}
