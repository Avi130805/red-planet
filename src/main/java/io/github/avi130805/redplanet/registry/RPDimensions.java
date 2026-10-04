package io.github.avi130805.redplanet.registry;

import io.github.avi130805.redplanet.RedPlanet;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Dimension keys. The Mars dimension itself is data: {@code data/redplanet/dimension/mars.json} and
 * {@code dimension_type/mars.json}; Fabric merges it into new and existing worlds.
 */
public final class RPDimensions {
	public static final ResourceKey<Level> MARS = ResourceKey.create(Registries.DIMENSION, RedPlanet.id("mars"));
	public static final ResourceKey<DimensionType> MARS_TYPE = ResourceKey.create(Registries.DIMENSION_TYPE, RedPlanet.id("mars"));

	private RPDimensions() {
	}

	/** True for dimensions this mod defines (no weather, custom sky). Safe during level construction. */
	public static boolean isRedPlanetDimension(Level level) {
		return RedPlanet.MOD_ID.equals(level.dimension().identifier().getNamespace());
	}

	public static boolean isMars(Level level) {
		return MARS.equals(level.dimension());
	}
}
