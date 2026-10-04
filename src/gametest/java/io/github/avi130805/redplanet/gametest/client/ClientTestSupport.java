package io.github.avi130805.redplanet.gametest.client;

import java.nio.file.Path;

import io.github.avi130805.redplanet.RedPlanet;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Shared pieces of the client gametests: waiting for Mars terrain under software rendering, and screenshots. */
final class ClientTestSupport {
	static final Path OUT = Path.of(System.getProperty("redplanet.gametest.screenshotDir", "screenshots"));
	/** Four chunks of terrain is plenty for photographs, and llvmpipe meshes 448-block-tall chunks slowly. */
	static final int MARS_RENDER_DISTANCE = 4;
	static final int TERRAIN_TIMEOUT_TICKS = 4800;

	private ClientTestSupport() {
	}

	/** Whether a client gametest should run: all of them, unless {@code -PclientTests=a,b} picked some. */
	static boolean enabled(String name) {
		String only = System.getProperty("redplanet.gametest.only", "");
		if (only.isBlank()) {
			return true;
		}
		for (String part : only.split(",")) {
			if (part.trim().equalsIgnoreCase(name)) {
				return true;
			}
		}
		RedPlanet.LOGGER.info("Skipping client gametest {} (redplanet.gametest.only={})", name, only);
		return false;
	}

	/**
	 * Waits until every chunk within {@link #MARS_RENDER_DISTANCE} (a disc, as the server sends them) is on the client,
	 * logging progress. It gives up quietly after {@link #TERRAIN_TIMEOUT_TICKS}, so a slow machine still gets its
	 * photographs.
	 */
	static void waitForTerrain(ClientGameTestContext context) {
		long start = System.nanoTime();
		int total = 0;
		int loaded = 0;
		for (int waited = 0; waited <= TERRAIN_TIMEOUT_TICKS; waited += 20) {
			int[] counts = context.computeOnClient(ClientTestSupport::countTerrainChunks);
			loaded = counts[0];
			total = counts[1];
			if (loaded == total) {
				break;
			}
			if (waited % 400 == 0) {
				RedPlanet.LOGGER.info("Waiting for Mars terrain: {}/{} chunks after {} ticks", loaded, total, waited);
			}
			context.waitTicks(20);
		}
		RedPlanet.LOGGER.info("Mars terrain on the client: {}/{} chunks in {} s", loaded, total, (System.nanoTime() - start) / 1_000_000_000L);
	}

	private static int[] countTerrainChunks(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			return new int[]{0, 1};
		}
		int r = Math.min(MARS_RENDER_DISTANCE, mc.options.getEffectiveRenderDistance());
		ChunkPos centre = mc.player.chunkPosition();
		int loaded = 0;
		int total = 0;
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				if (dx * dx + dz * dz > r * r) {
					continue;
				}
				total++;
				if (level.getChunk(centre.x() + dx, centre.z() + dz, ChunkStatus.FULL, false) != null) {
					loaded++;
				}
			}
		}
		return new int[]{loaded, total};
	}

	static void hideHud(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			if (!mc.gui.hud.isHidden()) {
				mc.gui.hud.toggle(); // F1
			}
		});
	}

	static void showHud(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			if (mc.gui.hud.isHidden()) {
				mc.gui.hud.toggle(); // F1
			}
		});
	}

	static void shot(ClientGameTestContext context, String name) {
		context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withSize(1280, 720).withDestinationDir(OUT));
	}
}
