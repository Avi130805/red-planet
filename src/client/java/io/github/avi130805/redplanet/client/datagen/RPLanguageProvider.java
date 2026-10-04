package io.github.avi130805.redplanet.client.datagen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import io.github.avi130805.redplanet.registry.RPBlocks;
import io.github.avi130805.redplanet.registry.RPCreativeTabs;
import io.github.avi130805.redplanet.registry.RPItems;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;

import net.minecraft.core.HolderLookup;

/** English text. Sound subtitles come from {@code tools/sounds/subtitles_en_us.json}, written by the sound generator. */
public class RPLanguageProvider extends FabricLanguageProvider {
	private final FabricPackOutput output;

	public RPLanguageProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, "en_us", registries);
		this.output = output;
	}

	@Override
	public void generateTranslations(HolderLookup.Provider registries, TranslationBuilder t) {
		t.add(RPCreativeTabs.MAIN, "Red Planet");

		// Blocks
		t.add(RPBlocks.REGOLITH, "Regolith");
		t.add(RPBlocks.MARS_DUST, "Mars Dust");
		t.add(RPBlocks.MARS_DUST_LAYER, "Mars Dust Layer");
		t.add(RPBlocks.BASALTIC_SAND, "Basaltic Sand");
		t.add(RPBlocks.HEMATITE_SPHERULE_REGOLITH, "Hematite Spherule Regolith");
		t.add(RPBlocks.ICE_RICH_REGOLITH, "Ice-Rich Regolith");
		t.add(RPBlocks.MARS_STONE, "Mars Stone");
		t.add(RPBlocks.MARS_COBBLESTONE, "Mars Cobblestone");
		t.add(RPBlocks.MARS_STONE_BRICKS, "Mars Stone Bricks");
		t.add(RPBlocks.MARS_BASALT, "Mars Basalt");
		t.add(RPBlocks.POLISHED_MARS_BASALT, "Polished Mars Basalt");
		t.add(RPBlocks.MARS_BASALT_BRICKS, "Mars Basalt Bricks");
		t.add(RPBlocks.MUDSTONE, "Mudstone");
		t.add(RPBlocks.POLISHED_MUDSTONE, "Polished Mudstone");
		t.add(RPBlocks.LAYERED_SEDIMENT, "Layered Sediment");
		t.add(RPBlocks.DELTA_SEDIMENT, "Delta Sediment");
		t.add(RPBlocks.SMECTITE_CLAY, "Smectite Clay");
		t.add(RPBlocks.CARBONATE_ROCK, "Carbonate Rock");
		t.add(RPBlocks.POLAR_WATER_ICE, "Polar Water Ice");
		t.add(RPBlocks.WATER_ICE, "Water Ice");
		t.add(RPBlocks.POLAR_LAYERED_DEPOSIT, "Polar Layered Deposit");
		t.add(RPBlocks.CO2_ICE, "CO₂ Ice");
		t.add(RPBlocks.DRY_ICE, "Dry Ice");
		t.add(RPBlocks.CO2_FROST, "CO₂ Frost");
		t.add(RPBlocks.HEMATITE_ORE, "Hematite Ore");
		t.add(RPBlocks.OLIVINE_BASALT, "Olivine Basalt");
		t.add(RPBlocks.JAROSITE_ORE, "Jarosite Ore");
		t.add(RPBlocks.GYPSUM_VEIN, "Gypsum Vein");
		t.add(RPBlocks.SULFUR_DEPOSIT, "Sulfur Deposit");
		t.add(RPBlocks.MARS_CHROMITE_ORE, "Mars Chromite Ore");
		t.add(RPBlocks.IRON_NICKEL_METEORITE, "Iron-Nickel Meteorite");
		t.add(RPBlocks.CHROMITE_ORE, "Chromite Ore");
		t.add(RPBlocks.DEEPSLATE_CHROMITE_ORE, "Deepslate Chromite Ore");
		t.add(RPBlocks.MARS_STONE_STAIRS, "Mars Stone Stairs");
		t.add(RPBlocks.MARS_STONE_SLAB, "Mars Stone Slab");
		t.add(RPBlocks.MARS_COBBLESTONE_STAIRS, "Mars Cobblestone Stairs");
		t.add(RPBlocks.MARS_COBBLESTONE_SLAB, "Mars Cobblestone Slab");
		t.add(RPBlocks.MARS_COBBLESTONE_WALL, "Mars Cobblestone Wall");
		t.add(RPBlocks.MARS_STONE_BRICK_STAIRS, "Mars Stone Brick Stairs");
		t.add(RPBlocks.MARS_STONE_BRICK_SLAB, "Mars Stone Brick Slab");
		t.add(RPBlocks.MARS_STONE_BRICK_WALL, "Mars Stone Brick Wall");
		t.add(RPBlocks.POLISHED_MARS_BASALT_STAIRS, "Polished Mars Basalt Stairs");
		t.add(RPBlocks.POLISHED_MARS_BASALT_SLAB, "Polished Mars Basalt Slab");
		t.add(RPBlocks.MARS_BASALT_BRICK_STAIRS, "Mars Basalt Brick Stairs");
		t.add(RPBlocks.MARS_BASALT_BRICK_SLAB, "Mars Basalt Brick Slab");
		t.add(RPBlocks.MARS_BASALT_BRICK_WALL, "Mars Basalt Brick Wall");
		t.add(RPBlocks.POLISHED_MUDSTONE_STAIRS, "Polished Mudstone Stairs");
		t.add(RPBlocks.POLISHED_MUDSTONE_SLAB, "Polished Mudstone Slab");
		t.add(RPBlocks.POLISHED_MUDSTONE_WALL, "Polished Mudstone Wall");

		// Items
		t.add(RPItems.RAW_HEMATITE, "Raw Hematite");
		t.add(RPItems.HEMATITE_SPHERULES, "Hematite Spherules");
		t.add(RPItems.OLIVINE, "Olivine");
		t.add(RPItems.JAROSITE, "Jarosite");
		t.add(RPItems.GYPSUM, "Gypsum");
		t.add(RPItems.SULFUR_CRYSTALS, "Sulfur Crystals");
		t.add(RPItems.RAW_CHROMITE, "Raw Chromite");
		t.add(RPItems.CHROMIUM_INGOT, "Chromium Ingot");
		t.add(RPItems.IRON_NICKEL_CHUNK, "Iron-Nickel Chunk");
		t.add(RPItems.NICKEL_INGOT, "Nickel Ingot");
		t.add(RPItems.NICKEL_NUGGET, "Nickel Nugget");
		t.add(RPItems.SMECTITE_CLAY_BALL, "Smectite Clay Ball");
		t.add(RPItems.PERCHLORATE_SALT, "Perchlorate Salt");
		t.add(RPItems.ICE_SHARD, "Ice Shard");
		t.add(RPItems.DRY_ICE_CHUNK, "Dry Ice Chunk");

		// Biomes
		t.add("biome.redplanet.northern_plains", "Northern Plains");
		t.add("biome.redplanet.cratered_highlands", "Cratered Highlands");
		t.add("biome.redplanet.dusty_highlands", "Dusty Highlands");
		t.add("biome.redplanet.volcanic_plains", "Volcanic Plains");
		t.add("biome.redplanet.shield_volcano", "Shield Volcano");
		t.add("biome.redplanet.canyon", "Canyon");
		t.add("biome.redplanet.impact_basin", "Impact Basin");
		t.add("biome.redplanet.dune_field", "Dune Field");
		t.add("biome.redplanet.north_polar_cap", "North Polar Cap");
		t.add("biome.redplanet.south_polar_cap", "South Polar Cap");
		t.add("biome.redplanet.mid_latitude_glaciers", "Mid-Latitude Glaciers");
		t.add("biome.redplanet.meridiani_planum", "Meridiani Planum");
		t.add("biome.redplanet.gale_mound", "Gale Crater");
		t.add("biome.redplanet.jezero_delta", "Jezero Delta");

		// Seasons
		t.add("season.redplanet.spring", "spring");
		t.add("season.redplanet.summer", "summer");
		t.add("season.redplanet.autumn", "autumn");
		t.add("season.redplanet.winter", "winter");

		// Environment
		t.add("death.attack.redplanet.hypoxia", "%1$s ran out of air on Mars");
		t.add("death.attack.redplanet.hypoxia.player", "%1$s ran out of air on Mars while fighting %2$s");
		t.add("block.redplanet.bed.unpressurized", "You can't sleep in Mars' air: build a pressurized habitat");

		// Commands
		t.add("commands.redplanet.no_mars", "The Mars dimension is not loaded on this server");
		t.add("commands.redplanet.tp.mars", "Landed on Mars at %s°, %s° (block %s, %s, %s)");
		t.add("commands.redplanet.tp.earth", "Back on Earth at the world spawn");
		t.add("commands.redplanet.info.position", "Mars %s° N, %s° E, %s m above the datum");
		t.add("commands.redplanet.info.time", "Sol %s, %s local time, Ls %s° (northern %s, southern %s)");
		t.add("commands.redplanet.info.weather", "Ground %s K (%s °C), dust optical depth %s");
		t.add("commands.redplanet.info.air", "Air %s Pa, %s kg/m³, %s");
		t.add("commands.redplanet.info.breathable", "breathable");
		t.add("commands.redplanet.info.unbreathable", "not breathable");
		t.add("commands.redplanet.info.physics", "Gravity %s g (%s m/s²), radiation %s mSv/day");
		t.add("commands.redplanet.info.biome", "Biome %s");
		t.add("commands.redplanet.locate.unknown", "Unknown landmark: %s");
		t.add("commands.redplanet.locate.found", "%s (%s°, %s°) is at %s on Mars");
		t.add("commands.redplanet.locate.found_distance", "%s (%s°, %s°) is at %s (%s blocks away)");
		t.add("commands.redplanet.locate.click", "Click to fill in a teleport command");

		Path subtitles = this.output.getOutputFolder().resolve("../../../tools/sounds/subtitles_en_us.json").normalize();
		if (Files.exists(subtitles)) {
			try {
				t.add(subtitles);
			} catch (IOException e) {
				throw new IllegalStateException("Could not read " + subtitles, e);
			}
		}
	}
}
