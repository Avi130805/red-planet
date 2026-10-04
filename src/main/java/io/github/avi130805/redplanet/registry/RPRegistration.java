package io.github.avi130805.redplanet.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import io.github.avi130805.redplanet.RedPlanet;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Registration helpers. In 26.3 a block or item's id must be set on its properties before construction
 * ({@code setId}), and block items need {@code useBlockDescriptionPrefix()} for the "block.redplanet.x" name key.
 */
public final class RPRegistration {
	/** Every item this mod registers, in registration order (creative tab, datagen). */
	private static final List<Item> ITEMS = new ArrayList<>();

	private RPRegistration() {
	}

	public static ResourceKey<Block> blockKey(String path) {
		return ResourceKey.create(Registries.BLOCK, RedPlanet.id(path));
	}

	public static ResourceKey<Item> itemKey(String path) {
		return ResourceKey.create(Registries.ITEM, RedPlanet.id(path));
	}

	public static <B extends Block> B block(String path, Function<BlockBehaviour.Properties, B> factory, BlockBehaviour.Properties props) {
		ResourceKey<Block> key = blockKey(path);
		return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(props.setId(key)));
	}

	public static <I extends Item> I item(String path, Function<Item.Properties, I> factory, Item.Properties props) {
		ResourceKey<Item> key = itemKey(path);
		I item = Registry.register(BuiltInRegistries.ITEM, key, factory.apply(props.setId(key)));
		ITEMS.add(item);
		return item;
	}

	public static Item item(String path) {
		return item(path, Item::new, new Item.Properties());
	}

	public static BlockItem blockItem(Block block, Item.Properties props) {
		ResourceKey<Item> key = itemKey(BuiltInRegistries.BLOCK.getKey(block).getPath());
		BlockItem item = Registry.register(BuiltInRegistries.ITEM, key, new BlockItem(block, props.setId(key).useBlockDescriptionPrefix()));
		ITEMS.add(item);
		return item;
	}

	public static <B extends Block> B blockWithItem(String path, Function<BlockBehaviour.Properties, B> factory, BlockBehaviour.Properties props) {
		B block = block(path, factory, props);
		blockItem(block, new Item.Properties());
		return block;
	}

	public static List<Item> items() {
		return Collections.unmodifiableList(ITEMS);
	}
}
