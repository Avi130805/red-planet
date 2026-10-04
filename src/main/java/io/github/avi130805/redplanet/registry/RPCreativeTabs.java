package io.github.avi130805.redplanet.registry;

import io.github.avi130805.redplanet.RedPlanet;

import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** The mod's creative tab: every item, in registration order. */
public final class RPCreativeTabs {
	public static final ResourceKey<CreativeModeTab> MAIN = ResourceKey.create(Registries.CREATIVE_MODE_TAB, RedPlanet.id("main"));

	private RPCreativeTabs() {
	}

	public static void init() {
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, MAIN, FabricCreativeModeTab.builder()
			.title(Component.translatable("itemGroup.redplanet.main"))
			.icon(() -> new ItemStack(RPBlocks.REGOLITH))
			.displayItems((params, output) -> {
				for (Item item : RPRegistration.items()) {
					output.accept(item);
				}
			})
			.build());
	}
}
