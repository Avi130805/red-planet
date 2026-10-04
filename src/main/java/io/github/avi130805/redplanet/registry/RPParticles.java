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

	/**
	 * A billowing cloud of steam and exhaust: the deluge flashing to steam under the engines at liftoff, rolling out
	 * across the pad for hundreds of metres, and the contrail on the way up. Big, slow and long-lived.
	 */
	public static final SimpleParticleType STEAM_CLOUD = Registry.register(BuiltInRegistries.PARTICLE_TYPE, RedPlanet.id("steam_cloud"),
		FabricParticleTypes.simple(true));

	/** The ochre twin of the steam cloud: dust blasted off the Martian ground by a landing or a launch. */
	public static final SimpleParticleType DUST_CLOUD = Registry.register(BuiltInRegistries.PARTICLE_TYPE, RedPlanet.id("dust_cloud"),
		FabricParticleTypes.simple(true));

	/** A white puff of boil-off venting from the tanks while propellant loads. */
	public static final SimpleParticleType VENT = Registry.register(BuiltInRegistries.PARTICLE_TYPE, RedPlanet.id("vent"),
		FabricParticleTypes.simple(true));

	private RPParticles() {
	}

	public static void init() {
	}
}
