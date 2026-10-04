package io.github.avi130805.redplanet.habitat;

import java.util.Map;
import java.util.WeakHashMap;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterable;
import it.unimi.dsi.fastutil.longs.LongIterator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Which block positions lie inside pressurized habitats, per level (server and client keep their own copy;
 * the server syncs changes). Stored as one 4096-bit set per chunk section that contains habitat air, so the
 * per-query cost is a hash lookup and a bit test, and levels without habitats exit immediately.
 *
 * <p>Queried from environment-attribute layers on hot paths (block survival checks, breathing, drag), so all
 * reads are lock-free against an immutable-on-publish map: writers replace the per-level map atomically.
 */
public final class HabitatIndex {
	/**
	 * Fraction of the outside dose rate inside a habitat: a 30 % cut, a gameplay estimate (galactic cosmic rays are
	 * hard to shield; thin walls help little). docs/SCIENCE.md, section 12.
	 */
	public static final float SHIELDING = 0.7F;

	private static final Map<Level, Long2ObjectMap<long[]>> VOLUMES = new WeakHashMap<>();

	private HabitatIndex() {
	}

	public static boolean isInside(Level level, Vec3 pos) {
		Long2ObjectMap<long[]> sections;
		synchronized (VOLUMES) {
			sections = VOLUMES.get(level);
		}
		if (sections == null || sections.isEmpty()) {
			return false;
		}
		int x = (int) Math.floor(pos.x);
		int y = (int) Math.floor(pos.y);
		int z = (int) Math.floor(pos.z);
		long[] bits = sections.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
		if (bits == null) {
			return false;
		}
		int index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
		return (bits[index >>> 6] & (1L << (index & 63))) != 0;
	}

	public static boolean isInside(Level level, BlockPos pos) {
		return isInside(level, Vec3.atCenterOf(pos));
	}

	/**
	 * Replaces the habitat volume set of a level with the given positions. Called by the habitat manager after a
	 * flood fill or a breach, and on the client when a sync packet arrives.
	 */
	public static void replace(Level level, Iterable<BlockPos> positions) {
		Long2ObjectOpenHashMap<long[]> sections = new Long2ObjectOpenHashMap<>();
		for (BlockPos p : positions) {
			long key = SectionPos.asLong(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4);
			long[] bits = sections.computeIfAbsent(key, k -> new long[64]);
			int index = ((p.getY() & 15) << 8) | ((p.getZ() & 15) << 4) | (p.getX() & 15);
			bits[index >>> 6] |= 1L << (index & 63);
		}
		synchronized (VOLUMES) {
			if (sections.isEmpty()) {
				VOLUMES.remove(level);
			} else {
				VOLUMES.put(level, sections);
			}
		}
	}

	/** Like {@link #replace}, from packed block positions ({@link BlockPos#asLong}). */
	public static void replacePacked(Level level, LongIterable positions) {
		Long2ObjectOpenHashMap<long[]> sections = new Long2ObjectOpenHashMap<>();
		LongIterator it = positions.iterator();
		while (it.hasNext()) {
			long packed = it.nextLong();
			int x = BlockPos.getX(packed);
			int y = BlockPos.getY(packed);
			int z = BlockPos.getZ(packed);
			long key = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
			long[] bits = sections.computeIfAbsent(key, k -> new long[64]);
			int index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
			bits[index >>> 6] |= 1L << (index & 63);
		}
		synchronized (VOLUMES) {
			if (sections.isEmpty()) {
				VOLUMES.remove(level);
			} else {
				VOLUMES.put(level, sections);
			}
		}
	}

	/** Packed sections for syncing (section key, 64 longs). */
	public static Long2ObjectMap<long[]> snapshot(Level level) {
		synchronized (VOLUMES) {
			Long2ObjectMap<long[]> s = VOLUMES.get(level);
			return s == null ? new Long2ObjectOpenHashMap<>() : new Long2ObjectOpenHashMap<>(s);
		}
	}

	/** Installs packed sections received from the server. */
	public static void install(Level level, Long2ObjectMap<long[]> sections) {
		synchronized (VOLUMES) {
			if (sections.isEmpty()) {
				VOLUMES.remove(level);
			} else {
				VOLUMES.put(level, new Long2ObjectOpenHashMap<>(sections));
			}
		}
	}

	public static void clear(Level level) {
		synchronized (VOLUMES) {
			VOLUMES.remove(level);
		}
	}
}
