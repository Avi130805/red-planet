package io.github.avi130805.redplanet.registry;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.mars.weather.DustDevil;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Entity types. */
public final class RPEntities {
	/** Weather, not a creature: not saved with the world, never collides. */
	public static final EntityType<DustDevil> DUST_DEVIL = register("dust_devil",
		EntityType.Builder.<DustDevil>of(DustDevil::new, MobCategory.MISC).sized(2.0F, 6.0F).noSave().noLootTable()
			.clientTrackingRange(16).updateInterval(2));

	private RPEntities() {
	}

	private static <T extends Entity> EntityType<T> register(String id, EntityType.Builder<T> builder) {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, RedPlanet.id(id));
		return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
	}

	public static void init() {
	}
}
