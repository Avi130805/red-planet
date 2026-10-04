package io.github.avi130805.redplanet.registry;

import static io.github.avi130805.redplanet.registry.RPRegistration.item;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.starship.entity.StarshipEntity;
import io.github.avi130805.redplanet.starship.entity.SuperHeavyEntity;
import io.github.avi130805.redplanet.starship.geometry.StarshipGeometry;
import io.github.avi130805.redplanet.starship.item.VehicleItem;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

/** The rocket: Starship and Super Heavy entity types and the items that place them. */
public final class RPStarship {
	/**
	 * The ship. Tracked as far as each player's view distance allows (tracking is horizontal only, so altitude never
	 * hides it); clients compute its flight themselves, so it needs few position updates.
	 */
	public static final EntityType<StarshipEntity> STARSHIP = register("starship",
		EntityType.Builder.<StarshipEntity>of(StarshipEntity::new, MobCategory.MISC)
			.sized((float) (2.0 * StarshipGeometry.HULL_RADIUS), (float) StarshipGeometry.SHIP_HEIGHT)
			.fireImmune().noLootTable().clientTrackingRange(64).updateInterval(3).dontTrackDeltas());

	public static final EntityType<SuperHeavyEntity> SUPER_HEAVY = register("super_heavy",
		EntityType.Builder.<SuperHeavyEntity>of(SuperHeavyEntity::new, MobCategory.MISC)
			.sized((float) (2.0 * StarshipGeometry.HULL_RADIUS), (float) StarshipGeometry.BOOSTER_HEIGHT)
			.fireImmune().noLootTable().clientTrackingRange(64).updateInterval(3).dontTrackDeltas());

	public static final Item STARSHIP_ITEM = item("starship", p -> new VehicleItem(() -> STARSHIP, p),
		new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
	public static final Item SUPER_HEAVY_ITEM = item("super_heavy", p -> new VehicleItem(() -> SUPER_HEAVY, p),
		new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));

	private RPStarship() {
	}

	private static <T extends Entity> EntityType<T> register(String id, EntityType.Builder<T> builder) {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, RedPlanet.id(id));
		return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
	}

	public static void init() {
	}
}
