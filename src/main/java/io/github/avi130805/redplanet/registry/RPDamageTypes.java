package io.github.avi130805.redplanet.registry;

import io.github.avi130805.redplanet.RedPlanet;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;

/**
 * Damage types; the definitions are data in {@code data/redplanet/damage_type/}.
 */
public final class RPDamageTypes {
	/** Hypoxia and ebullism from breathing (or failing to breathe) the Martian atmosphere. */
	public static final ResourceKey<DamageType> HYPOXIA = ResourceKey.create(Registries.DAMAGE_TYPE, RedPlanet.id("hypoxia"));

	private RPDamageTypes() {
	}
}
