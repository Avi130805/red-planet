package io.github.avi130805.redplanet.block;

import io.github.avi130805.redplanet.environment.PlanetEnvironment;
import io.github.avi130805.redplanet.habitat.HabitatIndex;
import io.github.avi130805.redplanet.mars.MarsConditions;
import io.github.avi130805.redplanet.mars.astro.MarsClimate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * Shared sublimation rules for water ice and CO2 ice on Mars. See docs/SCIENCE.md, section 7.
 */
final class Sublimation {
	/**
	 * Above this ground temperature (K), exposed water ice is unstable: the frost point of Mars' ~10 precipitable
	 * microns of water vapour, ~196 K (docs/SCIENCE.md, section 7).
	 */
	static final double WATER_ICE_LIMIT_K = 196.0;
	/** Margin above the CO2 frost point at which CO2 ice sublimates. */
	static final double CO2_MARGIN_K = 2.0;

	private Sublimation() {
	}

	/** Ground temperature at a block; inside habitats the air is kept at room temperature. */
	static double temperature(ServerLevel level, BlockPos pos) {
		if (HabitatIndex.isInside(level, pos)) {
			return 293.0;
		}
		if (!MarsConditions.applies(level)) {
			return 288.0;
		}
		return MarsConditions.temperatureK(level, pos);
	}

	static double co2FrostPoint(ServerLevel level, BlockPos pos) {
		return MarsClimate.co2FrostPoint(PlanetEnvironment.pressure(level, net.minecraft.world.phys.Vec3.atCenterOf(pos)));
	}

	static void vanish(BlockState state, ServerLevel level, BlockPos pos) {
		level.removeBlock(pos, false);
		level.sendParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 5, 0.3, 0.2, 0.3, 0.01);
		level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.2F, 1.6F);
		level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(state));
	}
}
