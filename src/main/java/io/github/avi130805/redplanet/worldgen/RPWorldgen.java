package io.github.avi130805.redplanet.worldgen;

import io.github.avi130805.redplanet.RedPlanet;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Registers the Mars worldgen types. The data that uses them lives in {@code data/redplanet/worldgen/} and
 * {@code data/redplanet/dimension/mars.json}.
 */
public final class RPWorldgen {
	private RPWorldgen() {
	}

	public static void init() {
		Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, RedPlanet.id("mars_height"), MarsHeightFunction.CODEC);
		Registry.register(BuiltInRegistries.BIOME_SOURCE, RedPlanet.id("mars"), MarsBiomeSource.CODEC);
		Registry.register(BuiltInRegistries.MATERIAL_RULE_TYPE, RedPlanet.id("mars_surface"), MarsSurfaceRule.CODEC);
	}
}
