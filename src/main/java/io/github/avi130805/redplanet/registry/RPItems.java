package io.github.avi130805.redplanet.registry;

import static io.github.avi130805.redplanet.registry.RPRegistration.item;

import net.minecraft.world.item.Item;

/**
 * Materials. Rocket parts, suit and machines are registered by their own feature classes.
 */
public final class RPItems {
	/** Hematite (Fe2O3) chunks: smelt to iron. */
	public static final Item RAW_HEMATITE = item("raw_hematite");
	/** Hematite concretions ("blueberries"), a few mm across: smelt to iron nuggets. */
	public static final Item HEMATITE_SPHERULES = item("hematite_spherules");
	public static final Item OLIVINE = item("olivine");
	public static final Item JAROSITE = item("jarosite");
	public static final Item GYPSUM = item("gypsum");
	/** Yellow crystals of elemental sulfur (Curiosity, Gediz Vallis, 2024). Vanilla's sulfur rock is a different block. */
	public static final Item SULFUR_CRYSTALS = item("sulfur_crystals");
	public static final Item RAW_CHROMITE = item("raw_chromite");
	public static final Item CHROMIUM_INGOT = item("chromium_ingot");
	/** Iron-nickel meteorite fragments (Fe ~93%, Ni ~6%): smelt to iron and nickel. */
	public static final Item IRON_NICKEL_CHUNK = item("iron_nickel_chunk");
	public static final Item NICKEL_INGOT = item("nickel_ingot");
	public static final Item NICKEL_NUGGET = item("nickel_nugget");
	public static final Item SMECTITE_CLAY_BALL = item("smectite_clay_ball");
	/** Perchlorate salts (ClO4-, ~0.5 wt% of regolith): toxic, but an oxygen source (oxygen candles). */
	public static final Item PERCHLORATE_SALT = item("perchlorate_salt");
	public static final Item ICE_SHARD = item("ice_shard");
	public static final Item DRY_ICE_CHUNK = item("dry_ice_chunk");

	private RPItems() {
	}

	public static void init() {
	}
}
