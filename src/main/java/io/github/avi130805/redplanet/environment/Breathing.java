package io.github.avi130805.redplanet.environment;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.avi130805.redplanet.config.RedPlanetConfig;
import io.github.avi130805.redplanet.registry.RPDamageTypes;
import io.github.avi130805.redplanet.registry.RPTags;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Breathing in an unbreathable atmosphere.
 *
 * <p>Mars' air is 0.6 % of Earth's pressure and 95 % CO2: below the Armstrong limit (6.3 kPa) body fluids boil
 * and there is essentially no oxygen. Useful consciousness lasts 9-15 s, which matches vanilla's 300-tick air
 * supply, so the vanilla bubbles are reused and drain at 1 per tick. Once they are gone the entity blacks out
 * (blindness, slowness, weakness, mining fatigue) and takes 1 {@code redplanet:hypoxia} damage every
 * {@link #DAMAGE_INTERVAL_TICKS} ticks: death at about two minutes from full health, in line with NASA's
 * vacuum-exposure studies (all dogs exposed for less than 120 s survived). See docs/SCIENCE.md, section 5.
 */
public final class Breathing {
	/** Ticks between hypoxia damage points once the air is gone (20 HP: death after 2,000 ticks). */
	public static final int DAMAGE_INTERVAL_TICKS = 100;
	/** How often the blackout effects are refreshed, and how long each application lasts. */
	private static final int EFFECT_REFRESH_TICKS = 20;
	private static final int EFFECT_DURATION_TICKS = 50;

	/** Something that lets an entity breathe (a crewed vehicle, a suit with oxygen, ...). */
	@FunctionalInterface
	public interface Protection {
		/**
		 * @param tick true once per server tick for the real check (implementations may consume oxygen);
		 * false for side-effect-free queries such as HUD previews
		 */
		boolean protects(LivingEntity entity, boolean tick);
	}

	/** Vehicles whose cabin is pressurized (the Starship). */
	public interface PressurizedVehicle {
		boolean isCabinPressurized();
	}

	private static final List<Protection> PROTECTIONS = new CopyOnWriteArrayList<>();

	static {
		register((entity, tick) -> entity instanceof Player p && (p.isCreative() || p.isSpectator()));
		register((entity, tick) -> entity.is(RPTags.DOES_NOT_BREATHE));
		register((entity, tick) -> {
			for (Entity v = entity.getVehicle(); v != null; v = v.getVehicle()) {
				if (v instanceof PressurizedVehicle pv && pv.isCabinPressurized()) {
					return true;
				}
			}
			return false;
		});
	}

	private Breathing() {
	}

	public static void register(Protection protection) {
		PROTECTIONS.add(protection);
	}

	/** True when the air at the entity's eyes can't be breathed. */
	public static boolean isAnoxic(LivingEntity entity) {
		return !PlanetEnvironment.breathable(entity.level(), entity.getEyePosition());
	}

	public static boolean isProtected(LivingEntity entity, boolean tick) {
		for (Protection p : PROTECTIONS) {
			if (p.protects(entity, tick)) {
				return true;
			}
		}
		return false;
	}

	/** Should the air supply be blocked from refilling this tick? */
	public static boolean blocksRefill(LivingEntity entity) {
		return isAnoxic(entity) && !isProtected(entity, false);
	}

	/**
	 * One server tick of hypoxia, run after vanilla's breathing logic (which only handles water).
	 */
	public static void tick(LivingEntity entity) {
		if (!(entity.level() instanceof ServerLevel level) || !entity.isAlive()) {
			return;
		}
		if (entity.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)) {
			return; // vanilla drowning handles it
		}
		if (!isAnoxic(entity) || isProtected(entity, true)) {
			return;
		}
		int air = entity.getAirSupply() - 1;
		if (air > 0) {
			entity.setAirSupply(air);
			return;
		}
		// Out of air: the air supply counts down below zero as a hypoxia timer.
		if (air % EFFECT_REFRESH_TICKS == 0) {
			blackout(entity);
		}
		if (air <= -DAMAGE_INTERVAL_TICKS) {
			air = 0;
			if (RedPlanetConfig.server().hypoxiaDamage) {
				entity.hurtServer(level, entity.damageSources().source(RPDamageTypes.HYPOXIA), 1.0F);
			}
		}
		entity.setAirSupply(air);
	}

	private static void blackout(LivingEntity entity) {
		entity.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, EFFECT_DURATION_TICKS, 0, false, false, true));
		entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, EFFECT_DURATION_TICKS, 2, false, false, true));
		entity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, EFFECT_DURATION_TICKS, 1, false, false, true));
		entity.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, EFFECT_DURATION_TICKS, 2, false, false, true));
	}
}
