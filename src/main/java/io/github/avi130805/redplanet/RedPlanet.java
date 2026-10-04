package io.github.avi130805.redplanet;

import io.github.avi130805.redplanet.command.RPCommands;
import io.github.avi130805.redplanet.config.RedPlanetConfig;
import io.github.avi130805.redplanet.environment.RPAttributes;
import io.github.avi130805.redplanet.registry.RPBlocks;
import io.github.avi130805.redplanet.registry.RPCreativeTabs;
import io.github.avi130805.redplanet.registry.RPItems;
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
		RPBlocks.init();
		RPItems.init();
		RPCreativeTabs.init();
		RPWorldgen.init();
		RPCommands.init();
		LOGGER.info("Red Planet: Starship to Mars initialized");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
