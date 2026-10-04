package io.github.avi130805.redplanet.client.datagen;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

/**
 * Data generation ({@code ./gradlew runDatagen}): block states, models, item definitions, loot tables, tags,
 * recipes and the English language file, written to {@code src/main/generated}. The Mars dimension, biomes and
 * timelines come from {@code tools/datagen/gen_mars_data.py} instead (they are pure data with physical numbers).
 */
public class RedPlanetDataGenerator implements DataGeneratorEntrypoint {
	@Override
	public void onInitializeDataGenerator(FabricDataGenerator generator) {
		FabricDataGenerator.Pack pack = generator.createPack();
		pack.addProvider(RPModelProvider::new);
		pack.addProvider(RPBlockLootProvider::new);
		RPBlockTagProvider blockTags = pack.addProvider(RPBlockTagProvider::new);
		pack.addProvider((output, registries) -> new RPItemTagProvider(output, registries, blockTags));
		pack.addProvider(RPRecipeProvider::new);
		pack.addProvider(RPLanguageProvider::new);
	}
}
