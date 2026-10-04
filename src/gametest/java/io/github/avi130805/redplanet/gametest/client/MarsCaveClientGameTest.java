package io.github.avi130805.redplanet.gametest.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.life.RPLifeBlocks;
import io.github.avi130805.redplanet.registry.RPDimensions;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Photographs each cave biome in real generated terrain (fiction layer, DESIGN.md section 8.3). For each habitat
 * it lands at sites over that habitat, searches the loaded chunks for the densest patch of its signature life,
 * then floats the camera into the open cave beside it and looks back. No light is added: the caves are lit by their
 * own life (areolichen 10, rustcap gills 13, ember moss 4, rime bloom 3). Screenshots: {@code cave_*}.
 */
public class MarsCaveClientGameTest implements FabricClientGameTest {
	private record Habitat(String name, Set<Block> signature, double[][] sites) {
	}

	record Viewpoint(BlockPos camera, BlockPos target, int score) {
	}

	private static final List<Habitat> HABITATS = List.of(
		new Habitat("lichen_hollows", Set.of(RPLifeBlocks.AREOLICHEN, RPLifeBlocks.EMBER_MOSS, RPLifeBlocks.RUSTCAP_CAP,
			RPLifeBlocks.RUSTCAP_GILLS), new double[][]{{-5.0, 250.0}, {-2.0, 240.0}, {20.0, 233.0}, {22.0, 150.0}}),
		new Habitat("brine_grottoes", Set.of(RPLifeBlocks.RIME_BLOOM, RPLifeBlocks.SALT_SPIRE, RPLifeBlocks.PERCHLORATE_CRUST),
			new double[][]{{84.0, 0.0}, {82.0, 60.0}, {-84.0, 180.0}, {42.0, 30.0}}),
		new Habitat("gypsum_geodes", Set.of(RPLifeBlocks.SELENITE_BLOCK, RPLifeBlocks.SELENITE_CLUSTER),
			new double[][]{{-5.6, 137.6}, {-1.95, 354.47}, {-8.0, 290.0}, {-6.6, 289.1}}));

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!ClientTestSupport.enabled("caves")) {
			return;
		}
		try (TestSingleplayerContext sp = context.worldBuilder()
				.adjustSettings(s -> {
					s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
					s.setAllowCommands(true);
				})
				.create()) {
			sp.getConnection().waitForChunksRender();
			context.runOnClient(mc -> mc.options.renderDistance().set(ClientTestSupport.MARS_RENDER_DISTANCE));
			ClientTestSupport.hideHud(context);
			for (Habitat habitat : HABITATS) {
				int shots = 0;
				for (double[] site : habitat.sites()) {
					if (shots >= 2) {
						break;
					}
					sp.getServer().runCommand("gamemode creative @a");
					sp.getServer().runCommand("execute as @a run redplanet tp mars " + site[0] + " " + site[1]);
					context.waitFor(mc -> mc.level != null && RPDimensions.MARS.equals(mc.level.dimension()), 2400);
					context.waitTicks(20);
					ClientTestSupport.waitForTerrain(context);
					Viewpoint vp = sp.getServer().computeOnServer(server -> {
						ServerLevel mars = server.getLevel(RPDimensions.MARS);
						ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
						return find(mars, player.blockPosition(), habitat.signature());
					});
					if (vp == null) {
						RedPlanet.LOGGER.info("No {} cave life found near {}, {}", habitat.name(), site[0], site[1]);
						continue;
					}
					double dx = vp.target().getX() - vp.camera().getX();
					double dy = vp.target().getY() - vp.camera().getY() - 1.12; // eyes sit 1.62 above the feet
					double dz = vp.target().getZ() - vp.camera().getZ();
					float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
					float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
					RedPlanet.LOGGER.info("{} at {}, {}: camera {} looking at {} (score {})", habitat.name(), site[0], site[1], vp.camera(),
						vp.target(), vp.score());
					sp.getServer().runCommand("gamemode spectator @a");
					sp.getServer().runCommand(String.format("execute as @a at @s run tp @s %d.5 %d %d.5 %.1f %.1f",
						vp.camera().getX(), vp.camera().getY(), vp.camera().getZ(), yaw, pitch));
					sp.getServer().runCommand("execute in redplanet:mars run time set 18000"); // night above: only the caves' own light
					context.waitTicks(160); // let the cave sections mesh
					ClientTestSupport.shot(context, "cave_" + habitat.name() + "_" + (++shots));
				}
				RedPlanet.LOGGER.info("Cave photographs of {}: {}", habitat.name(), shots);
			}
		}
	}

	/**
	 * The densest cluster of signature blocks within three chunks, and an open-air spot a few blocks out from it to
	 * look back from. Null when the loaded terrain holds none of that life.
	 */
	static Viewpoint find(ServerLevel level, BlockPos centre, Set<Block> signature) {
		Set<Long> found = new HashSet<>();
		List<BlockPos> candidates = new ArrayList<>();
		int cx = centre.getX() >> 4;
		int cz = centre.getZ() >> 4;
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
				if (chunk == null) {
					continue;
				}
				LevelChunkSection[] sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					LevelChunkSection section = sections[i];
					if (section.hasOnlyAir() || !section.maybeHas(state -> signature.contains(state.getBlock()))) {
						continue;
					}
					int baseY = level.getSectionYFromSectionIndex(i) << 4;
					for (int x = 0; x < 16; x++) {
						for (int y = 0; y < 16; y++) {
							for (int z = 0; z < 16; z++) {
								if (signature.contains(section.getBlockState(x, y, z).getBlock())) {
									BlockPos p = new BlockPos(chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z);
									found.add(p.asLong());
									if (candidates.size() < 4000) {
										candidates.add(p);
									}
								}
							}
						}
					}
				}
			}
		}
		Viewpoint best = null;
		for (int i = 0; i < candidates.size(); i += Math.max(1, candidates.size() / 400)) {
			BlockPos t = candidates.get(i);
			int score = 0;
			for (int ox = -6; ox <= 6; ox += 2) {
				for (int oy = -6; oy <= 6; oy += 2) {
					for (int oz = -6; oz <= 6; oz += 2) {
						if (found.contains(t.offset(ox, oy, oz).asLong())) {
							score++;
						}
					}
				}
			}
			if (best != null && score <= best.score()) {
				continue;
			}
			BlockPos camera = openSpot(level, t);
			if (camera != null) {
				best = new Viewpoint(camera, t, score);
			}
		}
		return best;
	}

	/** Walks out sideways from a block through open air to a spot 4-7 blocks away (views from above read poorly). */
	private static BlockPos openSpot(ServerLevel level, BlockPos target) {
		return openSpot(level, target, Direction.Plane.HORIZONTAL);
	}

	private static BlockPos openSpot(ServerLevel level, BlockPos target, Iterable<Direction> directions) {
		BlockPos bestSpot = null;
		int bestDistance = 0;
		for (Direction d : directions) {
			BlockPos p = target.relative(d);
			int steps = 0;
			while (steps < 7 && level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()) {
				steps++;
				p = p.relative(d);
			}
			if (steps >= 4 && steps > bestDistance) {
				bestDistance = steps;
				bestSpot = p.relative(d.getOpposite());
			}
		}
		return bestSpot;
	}
}
