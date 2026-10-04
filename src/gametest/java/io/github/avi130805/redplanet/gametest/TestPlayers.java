package io.github.avi130805.redplanet.gametest;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import io.netty.channel.embedded.EmbeddedChannel;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** Players for gametests. */
final class TestPlayers {
	private TestPlayers() {
	}

	/**
	 * A connected survival player in the test level (the vanilla helper only makes creative ones), so it can change
	 * worlds and isn't exempt from survival rules. Its own base tick doesn't run (no connection ticks it).
	 */
	static ServerPlayer survival(GameTestHelper helper, String name) {
		ServerLevel level = helper.getLevel();
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
		ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation()) {
			@Override
			public GameType gameMode() {
				return GameType.SURVIVAL;
			}
		};
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
		Vec3 spot = helper.absoluteVec(new Vec3(2.5, 1.0, 2.5));
		player.teleportTo(spot.x, spot.y, spot.z);
		return player;
	}
}
