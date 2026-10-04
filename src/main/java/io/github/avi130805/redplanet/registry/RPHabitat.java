package io.github.avi130805.redplanet.registry;

import static io.github.avi130805.redplanet.registry.RPRegistration.block;
import static io.github.avi130805.redplanet.registry.RPRegistration.blockWithItem;
import static io.github.avi130805.redplanet.registry.RPRegistration.item;

import java.util.Set;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.habitat.HabitatManager;
import io.github.avi130805.redplanet.habitat.HabitatRegulatorBlock;
import io.github.avi130805.redplanet.habitat.HabitatRegulatorBlockEntity;
import io.github.avi130805.redplanet.habitat.HabitatRegulatorMenu;
import io.github.avi130805.redplanet.habitat.LedLampBlock;
import io.github.avi130805.redplanet.habitat.OxygenTankBlock;
import io.github.avi130805.redplanet.habitat.OxygenTankBlockEntity;
import io.github.avi130805.redplanet.suit.Oxygen;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/** Habitats: the regulator, oxygen tanks, and the blocks to build a pressurized base with. */
public final class RPHabitat {
	/** A pressure hatch: opened by hand, heavy steel sounds. */
	public static final BlockSetType AIRLOCK = new BlockSetType("redplanet_airlock", true, false, false,
		BlockSetType.PressurePlateSensitivity.EVERYTHING, SoundType.METAL, SoundEvents.IRON_DOOR_CLOSE, SoundEvents.IRON_DOOR_OPEN,
		SoundEvents.IRON_TRAPDOOR_CLOSE, SoundEvents.IRON_TRAPDOOR_OPEN, SoundEvents.METAL_PRESSURE_PLATE_CLICK_OFF,
		SoundEvents.METAL_PRESSURE_PLATE_CLICK_ON, SoundEvents.STONE_BUTTON_CLICK_OFF, SoundEvents.STONE_BUTTON_CLICK_ON);

	public static final HabitatRegulatorBlock HABITAT_REGULATOR = blockWithItem("habitat_regulator", HabitatRegulatorBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.SNOW).strength(3.5F).requiresCorrectToolForDrops().sound(SoundType.METAL)
			.lightLevel(state -> state.getValue(HabitatRegulatorBlock.LIT) ? 7 : 0));
	public static final BlockEntityType<HabitatRegulatorBlockEntity> HABITAT_REGULATOR_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, RedPlanet.id("habitat_regulator"),
		new BlockEntityType<>(HabitatRegulatorBlockEntity::new, Set.of(HABITAT_REGULATOR)));
	public static final MenuType<HabitatRegulatorMenu> HABITAT_REGULATOR_MENU = Registry.register(BuiltInRegistries.MENU,
		RedPlanet.id("habitat_regulator"), new MenuType<>(HabitatRegulatorMenu::new, FeatureFlags.VANILLA_SET));

	public static final OxygenTankBlock OXYGEN_TANK = block("oxygen_tank", OxygenTankBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.SNOW).strength(4.0F).requiresCorrectToolForDrops().sound(SoundType.METAL)
			.pushReaction(PushReaction.IMMOVEABLE));
	/** Factory-filled: a new tank holds its full 59 kg. */
	public static final Item OXYGEN_TANK_ITEM = item("oxygen_tank", p -> new OxygenTankBlock.TankItem(OXYGEN_TANK, p),
		new Item.Properties().useBlockDescriptionPrefix().stacksTo(1)
			.component(RPSuit.OXYGEN, Oxygen.full(OxygenTankBlockEntity.CAPACITY_KG)));
	public static final BlockEntityType<OxygenTankBlockEntity> OXYGEN_TANK_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, RedPlanet.id("oxygen_tank"),
		new BlockEntityType<>(OxygenTankBlockEntity::new, Set.of(OXYGEN_TANK)));

	public static final Block HABITAT_PANEL = blockWithItem("habitat_panel", Block::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.SNOW).strength(3.0F, 6.0F).requiresCorrectToolForDrops().sound(SoundType.METAL));
	public static final Block HABITAT_PANEL_STAIRS = blockWithItem("habitat_panel_stairs",
		p -> new StairBlock(HABITAT_PANEL.defaultBlockState(), p), BlockBehaviour.Properties.ofFullCopy(HABITAT_PANEL));
	public static final Block HABITAT_PANEL_SLAB = blockWithItem("habitat_panel_slab", SlabBlock::new,
		BlockBehaviour.Properties.ofFullCopy(HABITAT_PANEL));
	public static final Block HABITAT_PANEL_WALL = blockWithItem("habitat_panel_wall", WallBlock::new,
		BlockBehaviour.Properties.ofFullCopy(HABITAT_PANEL).forceSolidOn());
	/** Thick pressure glass: a full block, so it holds the air. */
	public static final Block HABITAT_WINDOW = blockWithItem("habitat_window", TransparentBlock::new,
		BlockBehaviour.Properties.ofFullCopy(Blocks.GLASS).strength(3.0F, 6.0F).requiresCorrectToolForDrops().sound(SoundType.GLASS));
	public static final Block AIRLOCK_DOOR = blockWithItem("airlock_door", p -> new DoorBlock(AIRLOCK, p),
		BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0F).requiresCorrectToolForDrops().noOcclusion()
			.sound(SoundType.METAL).pushReaction(PushReaction.POPPED));
	public static final Block LED_LAMP = blockWithItem("led_lamp", LedLampBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.QUARTZ).strength(1.5F).sound(SoundType.GLASS)
			.lightLevel(state -> state.getValue(LedLampBlock.LIT) ? 15 : 0));

	private RPHabitat() {
	}

	public static void init() {
		HabitatManager.init();
	}
}
