package io.github.avi130805.redplanet.client.datagen;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import io.github.avi130805.redplanet.life.RPLifeBlocks;
import io.github.avi130805.redplanet.registry.RPSuit;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockItemTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;

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

		// Rustcap wood counts as wood for vanilla recipes (sticks, crafting tables, chests, ...).
		copy(RPLifeBlocks.RUSTCAP_STEMS);
		copy(BlockItemTags.LOGS);
		copy(BlockItemTags.PLANKS);
		copy(BlockItemTags.WOODEN_STAIRS);
		copy(BlockItemTags.WOODEN_SLABS);
		copy(BlockItemTags.WOODEN_FENCES);
		copy(BlockItemTags.FENCE_GATES);
		copy(BlockItemTags.WOODEN_DOORS);
		copy(BlockItemTags.WOODEN_TRAPDOORS);
		copy(BlockItemTags.WOODEN_PRESSURE_PLATES);
		copy(BlockItemTags.WOODEN_BUTTONS);
		// The suit is armour: armour enchantments and the trim-free armour tags apply.
		builder(ItemTags.HEAD_ARMOR).add(key(RPSuit.SPACESUIT_HELMET));
		builder(ItemTags.CHEST_ARMOR).add(key(RPSuit.SPACESUIT_TORSO));
		builder(ItemTags.LEG_ARMOR).add(key(RPSuit.SPACESUIT_LEGS));
		builder(ItemTags.FOOT_ARMOR).add(key(RPSuit.SPACESUIT_BOOTS));
		builder(RPSuit.REPAIRS_SPACESUIT).add(key(Items.WOOL.pick(DyeColor.WHITE)));

		// ... but like the Nether woods it doesn't burn, so it is no furnace fuel either.
		builder(ItemTags.NON_FLAMMABLE_WOOD).add(Stream.of(RPLifeBlocks.RUSTCAP_STEM, RPLifeBlocks.STRIPPED_RUSTCAP_STEM,
			RPLifeBlocks.RUSTCAP_HYPHAE, RPLifeBlocks.STRIPPED_RUSTCAP_HYPHAE, RPLifeBlocks.RUSTCAP_PLANKS, RPLifeBlocks.RUSTCAP_STAIRS,
			RPLifeBlocks.RUSTCAP_SLAB, RPLifeBlocks.RUSTCAP_FENCE, RPLifeBlocks.RUSTCAP_FENCE_GATE, RPLifeBlocks.RUSTCAP_DOOR,
			RPLifeBlocks.RUSTCAP_TRAPDOOR, RPLifeBlocks.RUSTCAP_PRESSURE_PLATE, RPLifeBlocks.RUSTCAP_BUTTON)
			.map(b -> b.asItem().builtInRegistryHolder().key()).toArray(ResourceKey[]::new));
	}

	private static ResourceKey<Item> key(Item item) {
		return item.builtInRegistryHolder().key();
	}
}
