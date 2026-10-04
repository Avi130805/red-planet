package io.github.avi130805.redplanet.registry;

import static io.github.avi130805.redplanet.registry.RPRegistration.blockWithItem;
import static io.github.avi130805.redplanet.registry.RPRegistration.item;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.machine.OxygenConcentratorBlock;
import io.github.avi130805.redplanet.machine.OxygenConcentratorBlockEntity;
import io.github.avi130805.redplanet.machine.OxygenConcentratorMenu;
import io.github.avi130805.redplanet.suit.Oxygen;
import io.github.avi130805.redplanet.suit.OxygenItem;
import io.github.avi130805.redplanet.suit.SpaceSuit;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/** The Mars EVA suit, oxygen canisters and the oxygen concentrator. */
public final class RPSuit {
	/** Oxygen held by an item, kg. */
	public static final DataComponentType<Oxygen> OXYGEN = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, RedPlanet.id("oxygen"),
		DataComponentType.<Oxygen>builder().persistent(Oxygen.CODEC).networkSynchronized(Oxygen.STREAM_CODEC).build());

	public static final ResourceKey<EquipmentAsset> SPACESUIT_ASSET = ResourceKey.create(EquipmentAssets.ROOT_ID, RedPlanet.id("spacesuit"));
	public static final TagKey<Item> REPAIRS_SPACESUIT = TagKey.create(Registries.ITEM, RedPlanet.id("repairs_spacesuit"));

	/**
	 * Layered fabric and a hard upper torso: about chainmail's protection and a little more wear than iron. Putting it on
	 * plays the helmet seal.
	 */
	public static final ArmorMaterial SPACESUIT = new ArmorMaterial(18, defense(1, 3, 4, 2, 4), 10,
		BuiltInRegistries.SOUND_EVENT.wrapAsHolder(RPSounds.SUIT_HELMET_SEAL), 0.0F, 0.0F, REPAIRS_SPACESUIT, SPACESUIT_ASSET);

	/** The helmet also draws its visor over the first-person view (vanilla's camera overlay, as a carved pumpkin's). */
	public static final Item SPACESUIT_HELMET = item("spacesuit_helmet", Item::new,
		new Item.Properties().humanoidArmor(SPACESUIT, ArmorType.HELMET).rarity(Rarity.UNCOMMON)
			.component(DataComponents.EQUIPPABLE, Equippable.builder(EquipmentSlot.HEAD).setEquipSound(SPACESUIT.equipSound())
				.setAsset(SPACESUIT_ASSET).setCameraOverlay(RedPlanet.id("misc/spacesuit_visor")).build()));
	public static final Item SPACESUIT_TORSO = item("spacesuit_torso", p -> new OxygenItem(p, false),
		new Item.Properties().humanoidArmor(SPACESUIT, ArmorType.CHESTPLATE).rarity(Rarity.UNCOMMON)
			.component(OXYGEN, Oxygen.full(SpaceSuit.SUIT_CAPACITY_KG)));
	public static final Item SPACESUIT_LEGS = item("spacesuit_legs", Item::new,
		new Item.Properties().humanoidArmor(SPACESUIT, ArmorType.LEGGINGS).rarity(Rarity.UNCOMMON));
	public static final Item SPACESUIT_BOOTS = item("spacesuit_boots", Item::new,
		new Item.Properties().humanoidArmor(SPACESUIT, ArmorType.BOOTS).rarity(Rarity.UNCOMMON));
	public static final Item OXYGEN_CANISTER = item("oxygen_canister", p -> new OxygenItem(p, true),
		new Item.Properties().stacksTo(1).component(OXYGEN, Oxygen.full(SpaceSuit.CANISTER_CAPACITY_KG)));

	public static final OxygenConcentratorBlock OXYGEN_CONCENTRATOR = blockWithItem("oxygen_concentrator", OxygenConcentratorBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.SNOW).strength(3.5F).requiresCorrectToolForDrops().sound(SoundType.METAL)
			.lightLevel(state -> state.getValue(OxygenConcentratorBlock.LIT) ? 6 : 0));
	public static final BlockEntityType<OxygenConcentratorBlockEntity> OXYGEN_CONCENTRATOR_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, RedPlanet.id("oxygen_concentrator"),
		new BlockEntityType<>(OxygenConcentratorBlockEntity::new, Set.of(OXYGEN_CONCENTRATOR)));
	public static final MenuType<OxygenConcentratorMenu> OXYGEN_CONCENTRATOR_MENU = Registry.register(BuiltInRegistries.MENU,
		RedPlanet.id("oxygen_concentrator"), new MenuType<>(OxygenConcentratorMenu::new, FeatureFlags.VANILLA_SET));

	private RPSuit() {
	}

	private static Map<ArmorType, Integer> defense(int boots, int legs, int chest, int helmet, int body) {
		Map<ArmorType, Integer> map = new EnumMap<>(ArmorType.class);
		map.put(ArmorType.BOOTS, boots);
		map.put(ArmorType.LEGGINGS, legs);
		map.put(ArmorType.CHESTPLATE, chest);
		map.put(ArmorType.HELMET, helmet);
		map.put(ArmorType.BODY, body);
		return map;
	}

	public static void init() {
		SpaceSuit.init();
	}
}
