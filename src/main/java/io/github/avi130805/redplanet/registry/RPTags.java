package io.github.avi130805.redplanet.registry;

import io.github.avi130805.redplanet.RedPlanet;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

public final class RPTags {
	/** Blocks that need free oxygen to exist: fire, torches, lanterns (not redstone torches). */
	public static final TagKey<Block> REQUIRES_OXYGEN = TagKey.create(Registries.BLOCK, RedPlanet.id("requires_oxygen"));
	/** Blocks with a LIT property that cannot stay lit without oxygen: campfires, candles, candle cakes. */
	public static final TagKey<Block> EXTINGUISHED_WITHOUT_OXYGEN = TagKey.create(Registries.BLOCK, RedPlanet.id("extinguished_without_oxygen"));
	/** Entities that do not need to breathe (undead, golems, armor stands...). */
	public static final TagKey<EntityType<?>> DOES_NOT_BREATHE = TagKey.create(Registries.ENTITY_TYPE, RedPlanet.id("does_not_breathe"));

	private RPTags() {
	}
}
