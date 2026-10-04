package io.github.avi130805.redplanet;

import io.github.avi130805.redplanet.command.RPCommands;
import io.github.avi130805.redplanet.config.RedPlanetConfig;
import io.github.avi130805.redplanet.environment.RPAttributes;
import io.github.avi130805.redplanet.life.RPLifeBlocks;
import io.github.avi130805.redplanet.mars.weather.DustDevils;
import io.github.avi130805.redplanet.network.RPNetworking;
import io.github.avi130805.redplanet.registry.RPArean;
import io.github.avi130805.redplanet.registry.RPBlocks;
import io.github.avi130805.redplanet.registry.RPCreativeTabs;
import io.github.avi130805.redplanet.registry.RPCreatures;
import io.github.avi130805.redplanet.registry.RPEndgame;
import io.github.avi130805.redplanet.registry.RPEntities;
import io.github.avi130805.redplanet.registry.RPFiction;
import io.github.avi130805.redplanet.registry.RPHabitat;
import io.github.avi130805.redplanet.registry.RPItems;
import io.github.avi130805.redplanet.registry.RPLaunchSite;
import io.github.avi130805.redplanet.registry.RPMaterials;
import io.github.avi130805.redplanet.registry.RPMusic;
import io.github.avi130805.redplanet.registry.RPParticles;
import io.github.avi130805.redplanet.registry.RPPower;
import io.github.avi130805.redplanet.registry.RPProgress;
import io.github.avi130805.redplanet.registry.RPRegistries;
import io.github.avi130805.redplanet.registry.RPSounds;
import io.github.avi130805.redplanet.registry.RPStarship;
import io.github.avi130805.redplanet.registry.RPSuit;
import io.github.avi130805.redplanet.worldgen.RPWorldgen;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Registration order matters: environment attributes and registry entries must exist before
 * the registries freeze and before datapacks (dimension types, biomes) that reference them are loaded.
 */
public class RedPlanet implements ModInitializer {
	public static final String MOD_ID = "redplanet";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		RedPlanetConfig.load();
		RPAttributes.init();
		RPSounds.init();
		RPParticles.init();
		RPBlocks.init();
		RPLifeBlocks.init();
		RPItems.init();
		RPMaterials.init();
		RPEntities.init();
		RPStarship.init();
		RPSuit.init();
		RPHabitat.init();
		RPPower.init();
		RPLaunchSite.init();
		RPProgress.init();
		RPFiction.init();
		RPCreatures.init();
		RPArean.init();
		RPEndgame.init();
		RPMusic.init();
		RPRegistries.init();
		RPCreativeTabs.init();
		RPWorldgen.init();
		RPNetworking.init();
		RPCommands.init();
		DustDevils.init();
		LOGGER.info("Red Planet: Starship to Mars initialized");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
