package io.github.avi130805.redplanet.client.datagen;

import java.util.concurrent.CompletableFuture;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;

import net.minecraft.core.HolderLookup;
import net.minecraft.tags.BlockItemTags;

/** Item tags mirrored from the block tags. */
public class RPItemTagProvider extends FabricTagsProvider.ItemTagsProvider {
	public RPItemTagProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries, RPBlockTagProvider blockTags) {
		super(output, registries, blockTags);
	}

	@Override
	protected void addTags(HolderLookup.Provider registries) {
		copy(BlockItemTags.STAIRS);
		copy(BlockItemTags.SLABS);
		copy(BlockItemTags.WALLS);
	}
}
