package io.github.avi130805.redplanet.suit;

import io.github.avi130805.redplanet.environment.Breathing;
import io.github.avi130805.redplanet.registry.RPSuit;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.jspecify.annotations.Nullable;

/**
 * The Mars EVA suit: four pieces, sealed only when all four are worn, breathing from the oxygen in the life-support
 * torso. The suit lets its wearer breathe wherever the air can't be breathed (Mars outside habitats, and under water,
 * since a sealed suit doesn't care what is outside it) and uses oxygen only there: on Earth's air the visor is open.
 *
 * <p>Numbers (docs/SCIENCE.md, section 5): the primary tank holds the ISS EMU's 0.54 kg, a crew member uses 0.84 kg of
 * oxygen a day (NASA BVAD), and a game day is 24,000 ticks on both worlds (an hour is 1,000 ticks), so a full suit lasts
 * about 15.4 game hours, 12.9 minutes of play.
 */
public final class SpaceSuit {
	/** Oxygen in the suit's primary tank, kg: the ISS EMU's 1.2 lb at 900 psi. */
	public static final float SUIT_CAPACITY_KG = 0.54F;
	/** A spare cylinder, like the EMU's secondary oxygen pack: 2.6 lb (1.2 kg) at 6,000 psi. */
	public static final float CANISTER_CAPACITY_KG = 1.2F;
	/** Metabolic oxygen use, 0.84 kg a day, per tick. */
	public static final float USE_KG_PER_TICK = 0.84F / 24000.0F;
	/** Topping up from a ship's or habitat's stores: an empty suit in five seconds. */
	public static final float STATION_REFILL_KG_PER_TICK = SUIT_CAPACITY_KG / 100.0F;
	/** Suit pressure, kPa: the EMU's 4.3 psid of pure oxygen. */
	public static final float SUIT_PRESSURE_KPA = 29.6F;
	/** Below this share of a full tank the suit warns. */
	public static final float LOW_FRACTION = 0.2F;

	private SpaceSuit() {
	}

	public static void init() {
		// After creative/spectator, non-breathers and pressurized cabins: those don't use the suit's oxygen.
		Breathing.register((entity, tick) -> {
			if (!isSealed(entity)) {
				return false;
			}
			if (tick) {
				breathe(entity, USE_KG_PER_TICK);
			}
			return true;
		});
		// Under water vanilla drowning asks canBreatheUnderwater (SuitBreathingMixin); the oxygen is used here.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player.isAlive() && !player.isCreative() && !player.isSpectator() && player.isEyeInFluid(FluidTags.WATER) && isSealed(player)) {
					breathe(player, USE_KG_PER_TICK);
				}
			}
		});
	}

	// ------------------------------------------------------------------------------------------------ the suit

	public static boolean isPiece(ItemStack stack, EquipmentSlot slot) {
		return switch (slot) {
			case HEAD -> stack.is(RPSuit.SPACESUIT_HELMET);
			case CHEST -> stack.is(RPSuit.SPACESUIT_TORSO);
			case LEGS -> stack.is(RPSuit.SPACESUIT_LEGS);
			case FEET -> stack.is(RPSuit.SPACESUIT_BOOTS);
			default -> false;
		};
	}

	public static boolean wearsFullSuit(LivingEntity entity) {
		return isPiece(entity.getItemBySlot(EquipmentSlot.HEAD), EquipmentSlot.HEAD)
			&& isPiece(entity.getItemBySlot(EquipmentSlot.CHEST), EquipmentSlot.CHEST)
			&& isPiece(entity.getItemBySlot(EquipmentSlot.LEGS), EquipmentSlot.LEGS)
			&& isPiece(entity.getItemBySlot(EquipmentSlot.FEET), EquipmentSlot.FEET);
	}

	public static boolean wearsHelmet(LivingEntity entity) {
		return isPiece(entity.getItemBySlot(EquipmentSlot.HEAD), EquipmentSlot.HEAD);
	}

	/** The worn life-support torso, or an empty stack. */
	public static ItemStack torso(LivingEntity entity) {
		ItemStack chest = entity.getItemBySlot(EquipmentSlot.CHEST);
		return chest.is(RPSuit.SPACESUIT_TORSO) ? chest : ItemStack.EMPTY;
	}

	/** A full suit with oxygen left: its wearer breathes anywhere. */
	public static boolean isSealed(LivingEntity entity) {
		if (!wearsFullSuit(entity)) {
			return false;
		}
		Oxygen o = oxygen(torso(entity));
		return o != null && !o.isEmpty();
	}

	/** Oxygen in the worn torso, kg (0 without one). */
	public static float oxygenKg(LivingEntity entity) {
		Oxygen o = oxygen(torso(entity));
		return o == null ? 0.0F : o.kg();
	}

	/** Breathes {@code kg} from the worn torso; false if it was already empty. */
	public static boolean breathe(LivingEntity entity, float kg) {
		ItemStack torso = torso(entity);
		Oxygen o = oxygen(torso);
		if (o == null || o.isEmpty()) {
			return false;
		}
		torso.set(RPSuit.OXYGEN, o.withKg(o.kg() - kg));
		return true;
	}

	// ------------------------------------------------------------------------------------------------ oxygen in items

	public static @Nullable Oxygen oxygen(ItemStack stack) {
		return stack.isEmpty() ? null : stack.get(RPSuit.OXYGEN);
	}

	/** Adds up to {@code kg} to an item that holds oxygen; returns what went in. */
	public static float fill(ItemStack stack, float kg) {
		Oxygen o = oxygen(stack);
		if (o == null || kg <= 0.0F) {
			return 0.0F;
		}
		float added = Math.min(kg, o.room());
		if (added > 0.0F) {
			stack.set(RPSuit.OXYGEN, o.withKg(o.kg() + added));
		}
		return added;
	}

	/** Takes up to {@code kg} out of an item that holds oxygen; returns what came out. */
	public static float drain(ItemStack stack, float kg) {
		Oxygen o = oxygen(stack);
		if (o == null || kg <= 0.0F) {
			return 0.0F;
		}
		float taken = Math.min(kg, o.kg());
		if (taken > 0.0F) {
			stack.set(RPSuit.OXYGEN, o.withKg(o.kg() - taken));
		}
		return taken;
	}

	/**
	 * Tops up a player from a station's stores (a ship's cabin, a habitat): the worn torso first, then every torso and
	 * canister they carry. Returns the oxygen handed over, kg.
	 */
	public static float refillFrom(Player player, float budgetKg) {
		float given = fill(torso(player), budgetKg);
		for (int i = 0; i < player.getInventory().getContainerSize() && given < budgetKg; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (stack != torso(player)) {
				given += fill(stack, budgetKg - given);
			}
		}
		return given;
	}
}
