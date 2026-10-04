package io.github.avi130805.redplanet.worldgen;

import io.github.avi130805.redplanet.RedPlanet;

import io.github.avi130805.redplanet.worldgen.carver.LavaTubeCarver;
import io.github.avi130805.redplanet.worldgen.feature.BoulderFeature;
import io.github.avi130805.redplanet.worldgen.feature.SmallCraterFeature;

import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.GenerationStep;
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
		Registry.register(BuiltInRegistries.FEATURE_TYPE, RedPlanet.id("small_crater"), SmallCraterFeature.CODEC);
		Registry.register(BuiltInRegistries.FEATURE_TYPE, RedPlanet.id("boulder"), BoulderFeature.CODEC);
		Registry.register(BuiltInRegistries.CARVER_TYPE, RedPlanet.id("lava_tube"), LavaTubeCarver.CODEC);
		// Earth's chromite (chromium for stainless steel), deep in every overworld biome.
		BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Decoration.UNDERGROUND_ORES,
			ResourceKey.create(Registries.PLACED_FEATURE, RedPlanet.id("ore_chromite")));
	}
}
