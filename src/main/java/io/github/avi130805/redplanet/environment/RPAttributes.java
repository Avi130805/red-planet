package io.github.avi130805.redplanet.environment;

import io.github.avi130805.redplanet.RedPlanet;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.attribute.AttributeRange;
import net.minecraft.world.attribute.AttributeTypes;
import net.minecraft.world.attribute.EnvironmentAttribute;

/**
 * Planet parameters as environment attributes, so a dimension type (or a biome, for the positional ones) sets
 * them in data: {@code data/redplanet/dimension_type/mars.json}. Defaults are Earth's, so every dimension that
 * does not mention them behaves exactly like vanilla. All of them are syncable because movement is simulated on
 * both sides (players are client-authoritative).
 *
 * <p>Values and sources: docs/SCIENCE.md.
 */
public final class RPAttributes {
	/** Surface gravity relative to Earth's (Mars 0.3794). Dimension-wide. */
	public static final EnvironmentAttribute<Float> GRAVITY = register("gameplay/gravity",
		EnvironmentAttribute.builder(AttributeTypes.FLOAT).defaultValue(1.0F).valueRange(AttributeRange.ofFloat(0.0F, 10.0F))
			.notPositional().syncable());

	/**
	 * Air density relative to Earth sea level (1.225 kg/m3) at the datum (Mars 0.01135). Positional: the
	 * altitude layer scales it with the scale height, and habitats override it.
	 */
	public static final EnvironmentAttribute<Float> AIR_DENSITY = register("gameplay/air_density",
		EnvironmentAttribute.builder(AttributeTypes.FLOAT).defaultValue(1.0F).valueRange(AttributeRange.ofFloat(0.0F, 100.0F))
			.syncable());

	/** Atmospheric pressure in pascals at the datum (Mars 560, annual mean). Positional (altitude layer, habitats). */
	public static final EnvironmentAttribute<Float> PRESSURE = register("gameplay/pressure",
		EnvironmentAttribute.builder(AttributeTypes.FLOAT).defaultValue(101325.0F).valueRange(AttributeRange.NON_NEGATIVE_FLOAT)
			.syncable());

	/** Whether the air can be breathed (false on Mars outside habitats). */
	public static final EnvironmentAttribute<Boolean> BREATHABLE = register("gameplay/breathable",
		EnvironmentAttribute.builder(AttributeTypes.BOOLEAN).defaultValue(true).syncable());

	/** Whether fuels can burn (false without free oxygen). */
	public static final EnvironmentAttribute<Boolean> COMBUSTION = register("gameplay/combustion",
		EnvironmentAttribute.builder(AttributeTypes.BOOLEAN).defaultValue(true).syncable());

	/** Ionizing radiation dose rate, mSv per day (Earth background ~0.0066; Mars surface 0.64). */
	public static final EnvironmentAttribute<Float> RADIATION = register("gameplay/radiation",
		EnvironmentAttribute.builder(AttributeTypes.FLOAT).defaultValue(0.0066F).valueRange(AttributeRange.NON_NEGATIVE_FLOAT)
			.syncable());

	/** Atmospheric scale height in metres (Earth 8,500; Mars 11,000). */
	public static final EnvironmentAttribute<Float> SCALE_HEIGHT = register("gameplay/scale_height",
		EnvironmentAttribute.builder(AttributeTypes.FLOAT).defaultValue(8500.0F).valueRange(AttributeRange.ofFloat(1.0F, 1.0E6F))
			.notPositional().syncable());

	/** Block Y of the reference datum where {@link #PRESSURE} and {@link #AIR_DENSITY} apply (Earth: sea level). */
	public static final EnvironmentAttribute<Float> DATUM_Y = register("gameplay/datum_y",
		EnvironmentAttribute.builder(AttributeTypes.FLOAT).defaultValue(63.0F).notPositional().syncable());

	/** Metres of real elevation per block (Earth 1; Mars 100, the map's vertical scale). */
	public static final EnvironmentAttribute<Float> VERTICAL_SCALE = register("gameplay/vertical_scale",
		EnvironmentAttribute.builder(AttributeTypes.FLOAT).defaultValue(1.0F).valueRange(AttributeRange.ofFloat(0.001F, 10000.0F))
			.notPositional().syncable());

	/** How much the thin air damps sound, 0 (Earth) to 1 (vacuum). Positional (habitats restore sound). */
	public static final EnvironmentAttribute<Float> SOUND_DAMPING = register("audio/sound_damping",
		EnvironmentAttribute.builder(AttributeTypes.FLOAT).defaultValue(0.0F).valueRange(AttributeRange.UNIT_FLOAT).syncable());

	private RPAttributes() {
	}

	private static <V> EnvironmentAttribute<V> register(String path, EnvironmentAttribute.Builder<V> builder) {
		return Registry.register(BuiltInRegistries.ENVIRONMENT_ATTRIBUTE, RedPlanet.id(path), builder.build());
	}

	/** Forces class initialization from {@code RedPlanet#onInitialize}, before the registries freeze. */
	public static void init() {
	}
}
