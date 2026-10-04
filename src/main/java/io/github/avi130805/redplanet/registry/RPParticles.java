package io.github.avi130805.redplanet.registry;

import io.github.avi130805.redplanet.RedPlanet;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;

import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;

/** Particle types. */
public final class RPParticles {
	/**
	 * Airborne Mars dust: a soft ochre mote that drifts with the wind and settles slowly. It replaces vanilla's dust
	 * particle, whose per-channel colour jitter turns an ochre base olive-green.
	 */
	public static final SimpleParticleType DUST_MOTE = Registry.register(BuiltInRegistries.PARTICLE_TYPE, RedPlanet.id("dust_mote"),
		FabricParticleTypes.simple());

	/**
	 * A big, short-lived puff of dust for dust devils and storm fronts. It is spawned "always visible", because vanilla
	 * drops ordinary particles more than 32 blocks from the camera, and a dust devil should read from far off.
	 */
	public static final SimpleParticleType DUST_PUFF = Registry.register(BuiltInRegistries.PARTICLE_TYPE, RedPlanet.id("dust_puff"),
		FabricParticleTypes.simple(true));

	private RPParticles() {
	}

	public static void init() {
	}
}
