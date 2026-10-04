package io.github.avi130805.redplanet.worldgen;

import com.mojang.serialization.Codec;

import io.github.avi130805.redplanet.RedPlanet;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.biome.Biome;

/**
 * Mars biome roles. Each role maps to a biome JSON ({@code data/redplanet/worldgen/biome/<name>.json}); the
 * biome source picks a role per column from real geography (see {@link MarsGeography}).
 */
public enum MarsBiome implements StringRepresentable {
	NORTHERN_PLAINS("northern_plains"),
	CRATERED_HIGHLANDS("cratered_highlands"),
	DUSTY_HIGHLANDS("dusty_highlands"),
	VOLCANIC_PLAINS("volcanic_plains"),
	SHIELD_VOLCANO("shield_volcano"),
	CANYON("canyon"),
	IMPACT_BASIN("impact_basin"),
	DUNE_FIELD("dune_field"),
	NORTH_POLAR_CAP("north_polar_cap"),
	SOUTH_POLAR_CAP("south_polar_cap"),
	MID_LATITUDE_GLACIERS("mid_latitude_glaciers"),
	MERIDIANI_PLANUM("meridiani_planum"),
	GALE_MOUND("gale_mound"),
	JEZERO_DELTA("jezero_delta");

	public static final Codec<MarsBiome> CODEC = StringRepresentable.fromEnum(MarsBiome::values);

	private final String name;
	private final ResourceKey<Biome> key;

	MarsBiome(String name) {
		this.name = name;
		this.key = ResourceKey.create(Registries.BIOME, RedPlanet.id(name));
	}

	public ResourceKey<Biome> key() {
		return this.key;
	}

	@Override
	public String getSerializedName() {
		return this.name;
	}
}
