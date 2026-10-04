package io.github.avi130805.redplanet.client.datagen;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.github.avi130805.redplanet.registry.RPBlocks;
import io.github.avi130805.redplanet.registry.RPItems;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricRecipeProvider;

import net.minecraft.advancements.Advancement;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;

/** Crafting, smelting and stonecutting for the natural Mars materials. Machines and rocket parts add their own. */
public class RPRecipeProvider extends FabricRecipeProvider {
	public RPRecipeProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, registries);
	}

	@Override
	protected RecipeProvider createRecipeProvider(HolderLookup.Provider registries, BootstrapContext<Recipe<?>> recipes,
			BootstrapContext<Advancement> advancements) {
		return new RecipeProvider(recipes, advancements) {
			@Override
			public void buildRecipes() {
				// Ores: hematite (Fe2O3) and the hematite "blueberries" are iron; chromite gives chromium for stainless steel.
				// (Blasting uses cookingtime 200 like smelting, as vanilla 26.3's own blasting recipes do.)
				oreSmelting(List.of(RPItems.RAW_HEMATITE, RPBlocks.HEMATITE_ORE), RecipeCategory.MISC, CookingBookCategory.MISC, Items.IRON_INGOT,
					0.7F, 200, "iron_ingot");
				oreBlasting(List.of(RPItems.RAW_HEMATITE, RPBlocks.HEMATITE_ORE), RecipeCategory.MISC, CookingBookCategory.MISC, Items.IRON_INGOT,
					0.7F, 200, "iron_ingot");
				oreSmelting(List.of(RPItems.HEMATITE_SPHERULES), RecipeCategory.MISC, CookingBookCategory.MISC, Items.IRON_NUGGET, 0.1F, 200,
					"iron_nugget");
				oreSmelting(List.of(RPItems.IRON_NICKEL_CHUNK), RecipeCategory.MISC, CookingBookCategory.MISC, Items.IRON_INGOT, 0.7F, 200,
					"iron_ingot");
				oreSmelting(List.of(RPItems.RAW_CHROMITE, RPBlocks.MARS_CHROMITE_ORE, RPBlocks.CHROMITE_ORE, RPBlocks.DEEPSLATE_CHROMITE_ORE),
					RecipeCategory.MISC, CookingBookCategory.MISC, RPItems.CHROMIUM_INGOT, 0.8F, 200, "chromium_ingot");
				oreBlasting(List.of(RPItems.RAW_CHROMITE, RPBlocks.MARS_CHROMITE_ORE, RPBlocks.CHROMITE_ORE, RPBlocks.DEEPSLATE_CHROMITE_ORE),
					RecipeCategory.MISC, CookingBookCategory.MISC, RPItems.CHROMIUM_INGOT, 0.8F, 200, "chromium_ingot");
				nineBlockStoragePair(RPItems.NICKEL_NUGGET, RPItems.NICKEL_INGOT);

				// Stone: cobble smelts back to stone; bricks and polished variants as in vanilla.
				oreSmelting(List.of(RPBlocks.MARS_COBBLESTONE), RecipeCategory.BUILDING_BLOCKS, CookingBookCategory.BLOCKS, RPBlocks.MARS_STONE,
					0.1F, 200, "mars_stone");
				twoByTwoPacker(RecipeCategory.BUILDING_BLOCKS, RPBlocks.MARS_STONE_BRICKS, RPBlocks.MARS_STONE);
				twoByTwoPacker(RecipeCategory.BUILDING_BLOCKS, RPBlocks.POLISHED_MARS_BASALT, RPBlocks.MARS_BASALT);
				twoByTwoPacker(RecipeCategory.BUILDING_BLOCKS, RPBlocks.MARS_BASALT_BRICKS, RPBlocks.POLISHED_MARS_BASALT);
				twoByTwoPacker(RecipeCategory.BUILDING_BLOCKS, RPBlocks.POLISHED_MUDSTONE, RPBlocks.MUDSTONE);
				twoByTwoPacker(RecipeCategory.BUILDING_BLOCKS, RPBlocks.SMECTITE_CLAY, RPItems.SMECTITE_CLAY_BALL);
				stonecutterResultFromBase(RecipeCategory.BUILDING_BLOCKS, RPBlocks.MARS_STONE_BRICKS, RPBlocks.MARS_STONE);
				stonecutterResultFromBase(RecipeCategory.BUILDING_BLOCKS, RPBlocks.POLISHED_MARS_BASALT, RPBlocks.MARS_BASALT);
				stonecutterResultFromBase(RecipeCategory.BUILDING_BLOCKS, RPBlocks.MARS_BASALT_BRICKS, RPBlocks.POLISHED_MARS_BASALT);
				stonecutterResultFromBase(RecipeCategory.BUILDING_BLOCKS, RPBlocks.POLISHED_MUDSTONE, RPBlocks.MUDSTONE);

				family(RPBlocks.MARS_STONE, RPBlocks.MARS_STONE_STAIRS, RPBlocks.MARS_STONE_SLAB, null);
				family(RPBlocks.MARS_COBBLESTONE, RPBlocks.MARS_COBBLESTONE_STAIRS, RPBlocks.MARS_COBBLESTONE_SLAB, RPBlocks.MARS_COBBLESTONE_WALL);
				family(RPBlocks.MARS_STONE_BRICKS, RPBlocks.MARS_STONE_BRICK_STAIRS, RPBlocks.MARS_STONE_BRICK_SLAB, RPBlocks.MARS_STONE_BRICK_WALL);
				family(RPBlocks.POLISHED_MARS_BASALT, RPBlocks.POLISHED_MARS_BASALT_STAIRS, RPBlocks.POLISHED_MARS_BASALT_SLAB, null);
				family(RPBlocks.MARS_BASALT_BRICKS, RPBlocks.MARS_BASALT_BRICK_STAIRS, RPBlocks.MARS_BASALT_BRICK_SLAB, RPBlocks.MARS_BASALT_BRICK_WALL);
				family(RPBlocks.POLISHED_MUDSTONE, RPBlocks.POLISHED_MUDSTONE_STAIRS, RPBlocks.POLISHED_MUDSTONE_SLAB, RPBlocks.POLISHED_MUDSTONE_WALL);
				// Stonecutting the bricks' parents straight to their stairs, slabs and walls.
				stonecutterResultFromBase(RecipeCategory.BUILDING_BLOCKS, RPBlocks.MARS_STONE_BRICK_STAIRS, RPBlocks.MARS_STONE);
				stonecutterResultFromBase(RecipeCategory.BUILDING_BLOCKS, RPBlocks.MARS_STONE_BRICK_SLAB, RPBlocks.MARS_STONE, 2);
				stonecutterResultFromBase(RecipeCategory.DECORATIONS, RPBlocks.MARS_STONE_BRICK_WALL, RPBlocks.MARS_STONE);

				// Sulfur crystals pack into vanilla's sulfur rock.
				twoByTwoPacker(RecipeCategory.BUILDING_BLOCKS, Items.SULFUR, RPItems.SULFUR_CRYSTALS);
			}

			private void family(Block base, Block stairs, Block slab, Block wall) {
				stairBuilder(stairs, Ingredient.of(base)).unlockedBy(getHasName(base), has(base)).save(this.output);
				slab(RecipeCategory.BUILDING_BLOCKS, slab, base);
				stonecutterResultFromBase(RecipeCategory.BUILDING_BLOCKS, stairs, base);
				stonecutterResultFromBase(RecipeCategory.BUILDING_BLOCKS, slab, base, 2);
				if (wall != null) {
					wall(RecipeCategory.DECORATIONS, wall, base);
					stonecutterResultFromBase(RecipeCategory.DECORATIONS, wall, base);
				}
			}

			private void nineBlockStoragePair(ItemLike small, ItemLike large) {
				shaped(RecipeCategory.MISC, large).define('#', small).pattern("###").pattern("###").pattern("###")
					.unlockedBy(getHasName(small), has(small)).save(this.output);
				shapeless(RecipeCategory.MISC, small, 9).requires(large).unlockedBy(getHasName(large), has(large))
					.save(this.output, getSimpleRecipeName(small) + "_from_" + getSimpleRecipeName(large));
			}
		};
	}

	@Override
	public String getName() {
		return "Red Planet recipes";
	}
}
