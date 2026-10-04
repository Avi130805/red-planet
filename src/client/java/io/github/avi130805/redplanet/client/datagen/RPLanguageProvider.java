package io.github.avi130805.redplanet.client.datagen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import io.github.avi130805.redplanet.life.RPLifeBlocks;
import io.github.avi130805.redplanet.registry.RPBlocks;
import io.github.avi130805.redplanet.registry.RPCreativeTabs;
import io.github.avi130805.redplanet.registry.RPItems;
import io.github.avi130805.redplanet.registry.RPStarship;
import io.github.avi130805.redplanet.registry.RPSuit;
import io.github.avi130805.redplanet.registry.RPHabitat;

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

		// Native cave life (fiction layer)
		t.add(RPLifeBlocks.AREOLICHEN, "Areolichen");
		t.add(RPLifeBlocks.EMBER_MOSS, "Ember Moss");
		t.add(RPLifeBlocks.EMBER_MOSS_CARPET, "Ember Moss Carpet");
		t.add(RPLifeBlocks.RUSTCAP_FUNGUS, "Rustcap Fungus");
		t.add(RPLifeBlocks.POTTED_RUSTCAP_FUNGUS, "Potted Rustcap Fungus");
		t.add(RPLifeBlocks.RUSTCAP_STEM, "Rustcap Stem");
		t.add(RPLifeBlocks.STRIPPED_RUSTCAP_STEM, "Stripped Rustcap Stem");
		t.add(RPLifeBlocks.RUSTCAP_HYPHAE, "Rustcap Hyphae");
		t.add(RPLifeBlocks.STRIPPED_RUSTCAP_HYPHAE, "Stripped Rustcap Hyphae");
		t.add(RPLifeBlocks.RUSTCAP_CAP, "Rustcap Cap");
		t.add(RPLifeBlocks.RUSTCAP_GILLS, "Rustcap Gills");
		t.add(RPLifeBlocks.RUSTCAP_PLANKS, "Rustcap Planks");
		t.add(RPLifeBlocks.RUSTCAP_STAIRS, "Rustcap Stairs");
		t.add(RPLifeBlocks.RUSTCAP_SLAB, "Rustcap Slab");
		t.add(RPLifeBlocks.RUSTCAP_FENCE, "Rustcap Fence");
		t.add(RPLifeBlocks.RUSTCAP_FENCE_GATE, "Rustcap Fence Gate");
		t.add(RPLifeBlocks.RUSTCAP_DOOR, "Rustcap Door");
		t.add(RPLifeBlocks.RUSTCAP_TRAPDOOR, "Rustcap Trapdoor");
		t.add(RPLifeBlocks.RUSTCAP_PRESSURE_PLATE, "Rustcap Pressure Plate");
		t.add(RPLifeBlocks.RUSTCAP_BUTTON, "Rustcap Button");
		t.add(RPLifeBlocks.RIME_BLOOM, "Rime Bloom");
		t.add(RPLifeBlocks.POTTED_RIME_BLOOM, "Potted Rime Bloom");
		t.add(RPLifeBlocks.PERCHLORATE_CRUST, "Perchlorate Crust");
		t.add(RPLifeBlocks.SALT_SPIRE, "Salt Spire");
		t.add(RPLifeBlocks.SELENITE_BLOCK, "Selenite Block");
		t.add(RPLifeBlocks.SELENITE_CLUSTER, "Selenite Cluster");

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
		t.add("biome.redplanet.lichen_hollows", "Lichen Hollows");
		t.add("biome.redplanet.brine_grottoes", "Brine Grottoes");
		t.add("biome.redplanet.gypsum_geodes", "Gypsum Geodes");
		t.add("biome.redplanet.arean_deep", "Arean Deep");

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
		t.add("commands.redplanet.weather.clear", "The dust settles: no storm on Mars");
		t.add("commands.redplanet.weather.storm", "A %s dust storm rises on Mars (peak optical depth %s, about %s sols)");
		t.add("commands.redplanet.weather.regional", "regional");
		t.add("commands.redplanet.weather.global", "global");
		t.add("commands.redplanet.weather.devil", "A dust devil spins up nearby (%s blocks across, %s blocks tall)");
		t.add("commands.redplanet.weather.devil.not_mars", "Dust devils only form on Mars");
		t.add("entity.redplanet.dust_devil", "Dust Devil");

		// The suit and oxygen
		t.add(RPSuit.SPACESUIT_HELMET, "Spacesuit Helmet");
		t.add(RPSuit.SPACESUIT_TORSO, "Life-Support Torso");
		t.add(RPSuit.SPACESUIT_LEGS, "Spacesuit Legs");
		t.add(RPSuit.SPACESUIT_BOOTS, "Spacesuit Boots");
		t.add(RPSuit.OXYGEN_CANISTER, "Oxygen Canister");
		t.add(RPSuit.OXYGEN_CONCENTRATOR, "Oxygen Concentrator");
		t.add("item.redplanet.oxygen.amount", "Oxygen: %s / %s kg");
		t.add("item.redplanet.oxygen.duration", "Lasts about %s min of breathing");
		t.add("item.redplanet.oxygen_canister.hint", "Use to top up the suit you wear");
		t.add("item.redplanet.oxygen_canister.no_suit", "Wear a life-support torso to fill it from the canister");
		t.add("item.redplanet.oxygen_canister.empty", "The canister is empty");
		t.add("item.redplanet.oxygen_canister.suit_full", "The suit's tank is already full");
		t.add("item.redplanet.oxygen_canister.transferred", "Transferred %s kg of oxygen to the suit");
		t.add("container.redplanet.oxygen_concentrator", "Oxygen Concentrator");
		t.add("container.redplanet.oxygen_concentrator.fill", "%s%% full");
		t.add("container.redplanet.oxygen_concentrator.no_air", "No oxygen in this air");
		t.add("hud.redplanet.suit.oxygen", "O₂");
		t.add("hud.redplanet.suit.time", "%s min of oxygen left");
		t.add("hud.redplanet.suit.low", "LOW OXYGEN: %s min left");
		t.add("hud.redplanet.suit.depleted", "OXYGEN DEPLETED");
		t.add("hud.redplanet.suit.unsealed", "SUIT NOT SEALED: wear all four pieces");
		t.add("hud.redplanet.suit.visor_open", "Visor open: breathing outside air");
		t.add("hud.redplanet.suit.pressure", "Suit %s kPa · outside %s kPa");
		t.add("hud.redplanet.suit.temperature", "Ground %s °C");

		// Habitats
		t.add(RPHabitat.HABITAT_REGULATOR, "Habitat Regulator");
		t.add(RPHabitat.OXYGEN_TANK, "Oxygen Tank");
		t.add(RPHabitat.HABITAT_PANEL, "Habitat Panel");
		t.add(RPHabitat.HABITAT_PANEL_STAIRS, "Habitat Panel Stairs");
		t.add(RPHabitat.HABITAT_PANEL_SLAB, "Habitat Panel Slab");
		t.add(RPHabitat.HABITAT_PANEL_WALL, "Habitat Panel Wall");
		t.add(RPHabitat.HABITAT_WINDOW, "Habitat Window");
		t.add(RPHabitat.AIRLOCK_DOOR, "Airlock Door");
		t.add(RPHabitat.LED_LAMP, "LED Lamp");
		t.add("item.redplanet.oxygen_tank.person_days", "Enough for %s person-days (%s h of play)");
		t.add("container.redplanet.habitat_regulator", "Habitat Regulator");
		t.add("container.redplanet.habitat_regulator.breathable", "SEALED · AIR BREATHABLE");
		t.add("container.redplanet.habitat_regulator.pressurizing", "SEALED · PRESSURIZING");
		t.add("container.redplanet.habitat_regulator.leak", "NOT SEALED: AIR ESCAPES");
		t.add("container.redplanet.habitat_regulator.volume", "Volume %s m³");
		t.add("container.redplanet.habitat_regulator.pressure", "Pressure %s kPa");
		t.add("container.redplanet.habitat_regulator.stores", "O₂ stores %s kg · crew %s");
		t.add("container.redplanet.habitat_regulator.drain", "Empty");
		t.add("container.redplanet.habitat_regulator.fill", "Fill");

		// Starship
		t.add(RPStarship.STARSHIP_ITEM, "Starship");
		t.add(RPStarship.SUPER_HEAVY_ITEM, "Super Heavy");
		t.add("entity.redplanet.starship", "Starship");
		t.add("entity.redplanet.super_heavy", "Super Heavy");
		t.add("starship.redplanet.boarded", "Strapped in. Sneak to unbuckle while the ship is on the ground");
		t.add("starship.redplanet.full", "All eight couches are taken");
		t.add("starship.redplanet.landed", "Touchdown. Sneak to unbuckle and step out");
		t.add("starship.redplanet.stacked", "Ship stacked on the booster. Board it from the booster");
		t.add("starship.redplanet.already_stacked", "A ship already stands on this booster");
		t.add("starship.redplanet.unstack_first", "Take the ship off the booster first");
		t.add("starship.redplanet.booster_placed", "Super Heavy placed. Use a Starship on it to stack the ship on top");
		t.add("starship.redplanet.no_room", "Not enough room: it needs a clear column 9 blocks wide and %s blocks tall");
		t.add("starship.redplanet.launch.flying", "The ship is already flying");
		t.add("starship.redplanet.launch.same_world", "That flight lands in the world the ship is already in");
		t.add("starship.redplanet.launch.no_destination", "The destination world isn't loaded on this server");
		t.add("starship.redplanet.launch.needs_booster", "Leaving Earth takes the full stack: stand the ship on a Super Heavy");
		t.add("starship.redplanet.launch.ship_only", "On Mars the ship launches on its own: it can't be stacked");
		t.add("commands.redplanet.starship.not_aboard", "You aren't aboard a Starship");
		t.add("commands.redplanet.starship.no_profile", "Unknown flight profile: %s");
		t.add("commands.redplanet.starship.launch", "Launching to %s (%s pacing). Godspeed");
		t.add("commands.redplanet.starship.launch_failed", "Launch scrubbed: %s");
		t.add("commands.redplanet.starship.skip", "Skipping ahead to %s");
		t.add("commands.redplanet.starship.skip_vote", "%s wants to skip ahead: %s more of the crew must agree (press the skip key)");
		t.add("commands.redplanet.starship.not_flying", "The ship isn't flying");
		t.add("commands.redplanet.starship.status", "%s, phase %s of %s (%s), T%s");
		t.add("commands.redplanet.starship.status.ground", "On the ground at %s, %s, %s");
		t.add("commands.redplanet.starship.spawned", "Placed a %s at %s, %s, %s");
		t.add("commands.redplanet.starship.no_room", "Not enough room here for a %s");
		t.add("destination.redplanet.mars", "Mars");
		t.add("destination.redplanet.overworld", "Earth");
		t.add("pacing.redplanet.short", "short");
		t.add("pacing.redplanet.standard", "standard");
		t.add("pacing.redplanet.long", "long");
		t.add("mission.redplanet.title", "MISSION CONTROL");
		t.add("mission.redplanet.destination", "Destination: %s");
		t.add("mission.redplanet.pacing", "Pacing");
		t.add("mission.redplanet.pacing_minutes", "%s pacing, ~%s min");
		t.add("mission.redplanet.pacing.short", "Short");
		t.add("mission.redplanet.pacing.standard", "Standard");
		t.add("mission.redplanet.pacing.long", "Long");
		t.add("mission.redplanet.launch", "Launch");
		t.add("mission.redplanet.site", "Landing site: %s (%s°, %s°)");
		t.add("mission.redplanet.custom_site", "chosen coordinates");
		t.add("mission.redplanet.click_map", "Click a landing site, or anywhere on the map");
		t.add("mission.redplanet.home_pad", "Landing beside your home pad at %s, %s");
		t.add("mission.redplanet.world_spawn", "Landing near the world spawn (no home pad recorded)");
		t.add("mission.redplanet.vehicle_stack", "Starship on Super Heavy");
		t.add("mission.redplanet.vehicle_ship", "Starship, ship only");
		t.add("mission.redplanet.status", "%s · crew %s/%s");
		t.add("mission.redplanet.no_profile", "This world has no flight profile for that trip");
		t.add("camera.redplanet.cinematic", "cinematic");
		t.add("camera.redplanet.orbit", "free orbit");
		t.add("camera.redplanet.cabin", "cabin");
		t.add("camera.redplanet.switched", "Camera: %s");
		t.add("key.category.redplanet.starship", "Red Planet: Starship");
		t.add("key.redplanet.camera", "Switch flight camera");
		t.add("key.redplanet.skip", "Skip ahead (flight phase)");
		t.add("key.redplanet.mission_control", "Mission control");
		t.add("interlude.redplanet.between_worlds", "Between worlds");
		t.add("interlude.redplanet.skip", "[%s] skip ahead");
		t.add("interlude.redplanet.earth_orbit", "LOW EARTH ORBIT");
		t.add("interlude.redplanet.mars_orbit", "LEAVING MARS");
		t.add("interlude.redplanet.orbit_numbers", "%s km up, %s km/h");
		t.add("interlude.redplanet.refilling", "PROPELLANT TRANSFER: TANKER %s OF %s");
		t.add("interlude.redplanet.propellant", "Ship propellant: %s t of %s t");
		t.add("interlude.redplanet.tmi", "TRANS-MARS INJECTION");
		t.add("interlude.redplanet.tei", "TRANS-EARTH INJECTION");
		t.add("interlude.redplanet.delta_v", "Burn of %s km/s");
		t.add("interlude.redplanet.cruise", "CRUISE: DAY %s OF %s");
		t.add("interlude.redplanet.cruise_numbers", "%s AU from the Sun");
		t.add("interlude.redplanet.mars_approach", "MARS APPROACH");
		t.add("interlude.redplanet.earth_approach", "EARTH APPROACH");
		t.add("interlude.redplanet.entry", "ENTRY INTERFACE");
		t.add("interlude.redplanet.arrival_speed", "Arriving at %s km/s");
		t.add("telemetry.redplanet.booster", "SUPER HEAVY");
		t.add("telemetry.redplanet.ship", "STARSHIP");
		t.add("telemetry.redplanet.speed", "SPEED");
		t.add("telemetry.redplanet.altitude", "ALT");
		t.add("telemetry.redplanet.keys", "[%s] camera: %s   [%s] skip ahead");
		String[][] events = {
			{"propellant_load", "Propellant load"}, {"vent", "Venting"}, {"terminal_count", "Terminal count"},
			{"deluge", "Deluge"}, {"ignition", "Ignition"}, {"liftoff", "Liftoff"}, {"max_q", "Max-Q"},
			{"meco", "Booster MECO"}, {"hot_staging", "Hot staging"}, {"boostback_start", "Boostback burn"},
			{"boostback_end", "Boostback complete"}, {"booster_landing_burn", "Booster landing burn"},
			{"booster_catch", "Booster catch"}, {"seco", "Ship engine cutoff"}, {"orbit", "Orbit"},
			{"refilling", "Propellant transfer"}, {"tmi", "Trans-Mars injection"}, {"coast", "Coast"},
			{"approach", "Approach"}, {"entry_interface", "Entry interface"}, {"peak_heating", "Peak heating"},
			{"plasma_end", "Out of the plasma"}, {"belly_flop", "Belly flop"}, {"flip", "Flip"},
			{"landing_burn", "Landing burn"}, {"legs_deploy", "Legs deployed"}, {"touchdown", "Touchdown"},
			{"engine_cutoff", "Engine cutoff"}, {"safing", "Vehicle safing"}};
		for (String[] e : events) {
			t.add("event.redplanet." + e[0], e[1]);
		}

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
