package io.github.avi130805.redplanet.fiction;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Where the Arean structures meet the endgame (docs/DESIGN.md sections 8.6 and 8.7): the Cydonia gate calls
 * {@link #openArena} when the seventh vault key goes into its sockets, and the guardians' fight registers the handler
 * that opens the arena below and wakes Phobos and Deimos.
 */
public final class CydoniaHooks {
	/** Opens the guardians' arena under the gate at {@code gate}; {@code player} placed the last key. */
	@FunctionalInterface
	public interface ArenaOpener {
		void open(ServerLevel level, BlockPos gate, ServerPlayer player);
	}

	private static ArenaOpener opener = (level, gate, player) -> {
	};

	private CydoniaHooks() {
	}

	public static void setArenaOpener(ArenaOpener handler) {
		opener = handler;
	}

	public static void openArena(ServerLevel level, BlockPos gate, ServerPlayer player) {
		opener.open(level, gate, player);
	}
}
