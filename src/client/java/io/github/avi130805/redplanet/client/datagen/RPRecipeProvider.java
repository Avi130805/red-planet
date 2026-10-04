package io.github.avi130805.redplanet.client.datagen;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.github.avi130805.redplanet.life.RPLifeBlocks;
import io.github.avi130805.redplanet.registry.RPBlocks;
import io.github.avi130805.redplanet.registry.RPItems;
import io.github.avi130805.redplanet.registry.RPMaterials;
import io.github.avi130805.redplanet.registry.RPStarship;
import io.github.avi130805.redplanet.registry.RPSuit;
import io.github.avi130805.redplanet.registry.RPHabitat;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricRecipeProvider;

import net.minecraft.advancements.Advancement;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
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

				// The rocket, until the full crafting chain (stainless steel plate, Raptors, heat-shield tiles) arrives: a stainless
				// hull (iron and chromium), copper-alloy combustion chambers and, for the ship, cabin windows.
				shaped(RecipeCategory.TRANSPORTATION, RPStarship.STARSHIP_ITEM)
					.define('C', RPItems.CHROMIUM_INGOT).define('I', Items.IRON_BLOCK).define('G', Items.GLASS_PANE).define('B', Items.COPPER_BLOCK.weathering().unaffected())
					.pattern(" C ").pattern("IGI").pattern("IBI")
					.unlockedBy(getHasName(RPItems.CHROMIUM_INGOT), has(RPItems.CHROMIUM_INGOT)).save(this.output);
				shaped(RecipeCategory.TRANSPORTATION, RPStarship.SUPER_HEAVY_ITEM)
					.define('C', RPItems.CHROMIUM_INGOT).define('I', Items.IRON_BLOCK).define('B', Items.COPPER_BLOCK.weathering().unaffected())
					.pattern("ICI").pattern("ICI").pattern("BBB")
					.unlockedBy(getHasName(RPItems.CHROMIUM_INGOT), has(RPItems.CHROMIUM_INGOT)).save(this.output);

				// The Mars EVA suit: layered white fabric, an iron hard-upper-torso and helmet shell, a gold-coated visor, and a
				// life-support torso built around an oxygen canister with its electronics (redstone) and chromium fittings.
				shaped(RecipeCategory.COMBAT, RPSuit.SPACESUIT_HELMET)
					.define('W', Items.WOOL.pick(DyeColor.WHITE)).define('G', Items.GOLD_INGOT).define('I', Items.IRON_INGOT).define('P', Items.GLASS_PANE)
					.pattern("WGW").pattern("IPI")
					.unlockedBy(getHasName(RPItems.CHROMIUM_INGOT), has(RPItems.CHROMIUM_INGOT)).save(this.output);
				shaped(RecipeCategory.COMBAT, RPSuit.SPACESUIT_TORSO)
					.define('I', Items.IRON_INGOT).define('C', RPItems.CHROMIUM_INGOT).define('W', Items.WOOL.pick(DyeColor.WHITE))
					.define('O', RPSuit.OXYGEN_CANISTER).define('R', Items.REDSTONE)
					.pattern("ICI").pattern("WOW").pattern("IRI")
					.unlockedBy(getHasName(RPSuit.OXYGEN_CANISTER), has(RPSuit.OXYGEN_CANISTER)).save(this.output);
				shaped(RecipeCategory.COMBAT, RPSuit.SPACESUIT_LEGS)
					.define('W', Items.WOOL.pick(DyeColor.WHITE)).define('I', Items.IRON_INGOT).define('L', Items.LEATHER)
					.pattern("WIW").pattern("W W").pattern("L L")
					.unlockedBy(getHasName(RPItems.CHROMIUM_INGOT), has(RPItems.CHROMIUM_INGOT)).save(this.output);
				shaped(RecipeCategory.COMBAT, RPSuit.SPACESUIT_BOOTS)
					.define('L', Items.LEATHER).define('I', Items.IRON_INGOT)
					.pattern("L L").pattern("I I")
					.unlockedBy(getHasName(RPItems.CHROMIUM_INGOT), has(RPItems.CHROMIUM_INGOT)).save(this.output);
				// A steel cylinder with a copper valve; it comes filled from the factory.
				shaped(RecipeCategory.TOOLS, RPSuit.OXYGEN_CANISTER)
					.define('K', Items.COPPER_INGOT).define('I', Items.IRON_INGOT)
					.pattern(" K ").pattern("I I").pattern("III")
					.unlockedBy(getHasName(Items.COPPER_INGOT), has(Items.COPPER_INGOT)).save(this.output);
				// A compressor (furnace), copper plumbing and controls (redstone) in an iron case.
				shaped(RecipeCategory.MISC, RPSuit.OXYGEN_CONCENTRATOR)
					.define('I', Items.IRON_INGOT).define('K', Items.COPPER_INGOT).define('R', Items.REDSTONE).define('F', Items.FURNACE)
					.pattern("III").pattern("KRK").pattern("IFI")
					.unlockedBy(getHasName(Items.FURNACE), has(Items.FURNACE)).save(this.output);

				// Habitats: the regulator (pumps, plumbing, controls), factory-filled oxygen tanks, hull panels (from Earth stone or
				// sintered Martian stone bricks, both bound with steel), pressure glass, airlock hatches and LED lamps.
				shaped(RecipeCategory.MISC, RPHabitat.HABITAT_REGULATOR)
					.define('I', Items.IRON_INGOT).define('K', Items.COPPER_INGOT).define('P', Items.PISTON).define('R', Items.REDSTONE_BLOCK)
					.pattern("IKI").pattern("PRP").pattern("IKI")
					.unlockedBy(getHasName(Items.REDSTONE_BLOCK), has(Items.REDSTONE_BLOCK)).save(this.output);
				shaped(RecipeCategory.MISC, RPHabitat.OXYGEN_TANK_ITEM)
					.define('I', Items.IRON_INGOT).define('K', Items.COPPER_INGOT).define('B', Items.IRON_BLOCK)
					.pattern("IKI").pattern("B B").pattern("IBI")
					.unlockedBy(getHasName(Items.IRON_BLOCK), has(Items.IRON_BLOCK)).save(this.output);
				shaped(RecipeCategory.BUILDING_BLOCKS, RPHabitat.HABITAT_PANEL, 8)
					.define('S', Items.SMOOTH_STONE).define('I', Items.IRON_INGOT)
					.pattern("SSS").pattern("SIS").pattern("SSS")
					.unlockedBy(getHasName(Items.SMOOTH_STONE), has(Items.SMOOTH_STONE)).save(this.output);
				shaped(RecipeCategory.BUILDING_BLOCKS, RPHabitat.HABITAT_PANEL, 8)
					.define('M', RPBlocks.MARS_STONE_BRICKS).define('I', Items.IRON_INGOT)
					.pattern("MMM").pattern("MIM").pattern("MMM")
					.unlockedBy(getHasName(RPBlocks.MARS_STONE_BRICKS), has(RPBlocks.MARS_STONE_BRICKS))
					.save(this.output, "habitat_panel_from_mars_stone_bricks");
				family(RPHabitat.HABITAT_PANEL, RPHabitat.HABITAT_PANEL_STAIRS, RPHabitat.HABITAT_PANEL_SLAB, RPHabitat.HABITAT_PANEL_WALL);
				shaped(RecipeCategory.BUILDING_BLOCKS, RPHabitat.HABITAT_WINDOW, 4)
					.define('I', Items.IRON_INGOT).define('G', Items.GLASS)
					.pattern("IGI").pattern("GGG").pattern("IGI")
					.unlockedBy(getHasName(Items.GLASS), has(Items.GLASS)).save(this.output);
				shaped(RecipeCategory.REDSTONE, RPHabitat.AIRLOCK_DOOR, 2)
					.define('I', Items.IRON_INGOT).define('G', Items.GLASS_PANE)
					.pattern("II").pattern("IG").pattern("II")
					.unlockedBy(getHasName(Items.IRON_INGOT), has(Items.IRON_INGOT)).save(this.output);
				shaped(RecipeCategory.REDSTONE, RPHabitat.LED_LAMP, 4)
					.define('G', Items.GLASS_PANE).define('D', Items.GLOWSTONE_DUST).define('I', Items.IRON_INGOT).define('R', Items.REDSTONE)
					.pattern("GGG").pattern("GDG").pattern("IRI")
					.unlockedBy(getHasName(Items.GLOWSTONE_DUST), has(Items.GLOWSTONE_DUST)).save(this.output);

				// Sulfur crystals pack into vanilla's sulfur rock.
				twoByTwoPacker(RecipeCategory.BUILDING_BLOCKS, Items.SULFUR, RPItems.SULFUR_CRYSTALS);

				// Native cave life (fiction layer). Rustcap is wood: planks, hyphae, and the whole family from the planks.
				planksFromLogs(RPLifeBlocks.RUSTCAP_PLANKS, RPLifeBlocks.RUSTCAP_STEMS.item(), 4);
				woodFromLogs(RPLifeBlocks.RUSTCAP_HYPHAE, RPLifeBlocks.RUSTCAP_STEM);
				woodFromLogs(RPLifeBlocks.STRIPPED_RUSTCAP_HYPHAE, RPLifeBlocks.STRIPPED_RUSTCAP_STEM);
				generateRecipes(RPBlockFamilies.RUSTCAP, FeatureFlagSet.of(FeatureFlags.VANILLA));
				carpet(RPLifeBlocks.EMBER_MOSS_CARPET, RPLifeBlocks.EMBER_MOSS);
				// Selenite is crystalline gypsum; perchlorate salt cakes back into crust.
				twoByTwoPacker(RecipeCategory.BUILDING_BLOCKS, RPLifeBlocks.SELENITE_BLOCK, RPItems.GYPSUM);
				twoByTwoPacker(RecipeCategory.BUILDING_BLOCKS, RPLifeBlocks.PERCHLORATE_CRUST, RPItems.PERCHLORATE_SALT);

				nineBlockStoragePair(RPMaterials.STAINLESS_STEEL_INGOT, RPMaterials.STAINLESS_STEEL_BLOCK);
				power();
				launchSite();
				progress();
			}

			/** Power and ISRU: solar panels, batteries, cables, Kilopower, MOXIE, water extractor, electrolyzer, Sabatier, depot, soil. */
			private void power() {
			}

			/** The launch site: launch mount, launch tower and chopsticks, tank farm. */
			private void launchSite() {
			}

			/** Progression: stainless steel and copper alloy, Starship parts, the Starship and Super Heavy, the atlas. */
			private void progress() {
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
