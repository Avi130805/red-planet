package io.github.avi130805.redplanet.gametest;

import io.github.avi130805.redplanet.environment.PlanetEnvironment;
import io.github.avi130805.redplanet.habitat.HabitatIndex;
import io.github.avi130805.redplanet.habitat.HabitatRegulatorBlock;
import io.github.avi130805.redplanet.habitat.HabitatRegulatorBlockEntity;
import io.github.avi130805.redplanet.habitat.OxygenTankBlockEntity;
import io.github.avi130805.redplanet.registry.RPHabitat;
import io.github.avi130805.redplanet.registry.RPSuit;
import io.github.avi130805.redplanet.suit.Oxygen;
import io.github.avi130805.redplanet.suit.SpaceSuit;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * Habitats (docs/SCIENCE.md, section 5): a room sealed with full blocks and closed doors is pressurized from the oxygen
 * stores and becomes breathable, with fire and liquid water allowed inside; an opened door, a hole or removing the
 * regulator lets the air go.
 *
 * <p>Every test is walled in with barriers (which hold air like any full block), and on Mars it also sits in a pocket
 * cut into the rock (the test grid is at y 4). The tests that need a breach to reach open air run on the flat overworld
 * test world with sky access instead.
 */
public class HabitatGameTests {
	private static final String MARS = "redplanet:mars";
	/** The room's inside: x, y, z from 2 to 4, 27 blocks of air. */
	private static final BlockPos CENTRE = new BlockPos(3, 3, 3);
	private static final BlockPos FLOOR = new BlockPos(3, 2, 3);
	private static final BlockPos REGULATOR = new BlockPos(3, 3, 1);
	private static final BlockPos TANK = new BlockPos(3, 3, 0);
	private static final BlockPos DOOR = new BlockPos(3, 2, 5);
	private static final int VOLUME = 27;

	/**
	 * Walls in a 3x3x3 room with habitat panels (the shell spans 1 to 5), puts the regulator in the north wall facing
	 * in, and a full oxygen tank behind it. Optionally an airlock door in the south wall, or a hole in the east wall.
	 */
	private static HabitatRegulatorBlockEntity room(GameTestHelper helper, boolean door, boolean hole) {
		for (int x = 1; x <= 5; x++) {
			for (int y = 1; y <= 5; y++) {
				for (int z = 1; z <= 5; z++) {
					boolean shell = x == 1 || x == 5 || y == 1 || y == 5 || z == 1 || z == 5;
					helper.setBlock(new BlockPos(x, y, z), shell ? RPHabitat.HABITAT_PANEL.defaultBlockState() : Blocks.AIR.defaultBlockState());
				}
			}
		}
		if (door) {
			BlockState lower = RPHabitat.AIRLOCK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
				.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
			helper.setBlock(DOOR, lower);
			helper.setBlock(DOOR.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		}
		if (hole) {
			helper.setBlock(new BlockPos(5, 3, 3), Blocks.AIR);
		}
		helper.setBlock(TANK, RPHabitat.OXYGEN_TANK);
		helper.getBlockEntity(TANK, OxygenTankBlockEntity.class).put(OxygenTankBlockEntity.CAPACITY_KG);
		helper.setBlock(REGULATOR, RPHabitat.HABITAT_REGULATOR.defaultBlockState().setValue(HabitatRegulatorBlock.FACING, Direction.SOUTH));
		return helper.getBlockEntity(REGULATOR, HabitatRegulatorBlockEntity.class);
	}

	private static boolean inside(GameTestHelper helper, BlockPos relative) {
		return HabitatIndex.isInside(helper.getLevel(), helper.absolutePos(relative));
	}

	private static boolean torchCanBurn(GameTestHelper helper, BlockPos relative) {
		return Blocks.TORCH.defaultBlockState().canSurvive(helper.getLevel(), helper.absolutePos(relative));
	}

	@GameTest(dimension = MARS, maxTicks = 400)
	public void sealedHabitatBecomesBreathableOnMars(GameTestHelper helper) {
		HabitatRegulatorBlockEntity regulator = room(helper, false, false);
		Villager crew = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, FLOOR);
		helper.assertFalse(torchCanBurn(helper, FLOOR), "no fire in the room before it holds air");
		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(regulator.isSealed(), "the room should be found sealed"))
			.thenExecute(() -> helper.assertValueEqual(regulator.volume(), VOLUME, "habitat volume in blocks"))
			.thenWaitUntil(() -> helper.assertTrue(regulator.isBreathable(), "the habitat should become breathable, pressure "
				+ regulator.pressureFraction()))
			.thenExecute(() -> {
				helper.assertTrue(inside(helper, CENTRE), "the habitat's air should be in the index");
				helper.assertTrue(helper.getBlockState(REGULATOR).getValue(HabitatRegulatorBlock.LIT), "the regulator lights up");
				helper.assertTrue(PlanetEnvironment.breathable(helper.getLevel(), helper.absoluteVec(Vec3.atCenterOf(CENTRE))),
					"breathable inside");
				helper.assertTrue(torchCanBurn(helper, FLOOR), "a torch burns in the habitat's air");
				helper.assertFalse(helper.getLevel().environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES,
					helper.absoluteVec(Vec3.atCenterOf(CENTRE))), "water stays liquid inside");
				helper.assertFalse(inside(helper, new BlockPos(6, 3, 3)), "outside the walls is not in the habitat");
				helper.assertTrue(helper.getLevel().environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES,
					helper.absoluteVec(new Vec3(6.5, 3.5, 3.5))), "water still boils away outside");
			})
			.thenExecuteAfter(60, () -> {
				helper.assertValueEqual(crew.getAirSupply(), crew.getMaxAirSupply(), "the villager's air inside the habitat");
				float drawn = OxygenTankBlockEntity.CAPACITY_KG - helper.getBlockEntity(TANK, OxygenTankBlockEntity.class).kg();
				helper.assertTrue(drawn > 0.6F * VOLUME * HabitatRegulatorBlockEntity.O2_KG_PER_BLOCK
					&& drawn <= VOLUME * HabitatRegulatorBlockEntity.O2_KG_PER_BLOCK + 0.1F,
					"oxygen drawn from the tank to pressurize 27 blocks: " + drawn + " kg");
				crew.discard();
			})
			.thenSucceed();
	}

	@GameTest(dimension = MARS, maxTicks = 400)
	public void removingTheRegulatorReleasesTheAir(GameTestHelper helper) {
		HabitatRegulatorBlockEntity regulator = room(helper, false, false);
		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(regulator.isBreathable(), "the habitat should become breathable"))
			.thenExecute(() -> helper.destroyBlock(REGULATOR))
			.thenExecute(() -> {
				helper.assertFalse(inside(helper, CENTRE), "no regulator, no habitat");
				helper.assertFalse(torchCanBurn(helper, FLOOR), "and no fire");
			})
			.thenSucceed();
	}

	@GameTest(maxTicks = 400, skyAccess = true)
	public void openingTheDoorVentsTheHabitat(GameTestHelper helper) {
		HabitatRegulatorBlockEntity regulator = room(helper, true, false);
		helper.startSequence()
			.thenWaitUntil(() -> helper.assertTrue(regulator.isBreathable(), "a closed airlock door holds the air"))
			.thenExecute(() -> {
				helper.assertTrue(inside(helper, CENTRE), "habitat registered");
				BlockState door = helper.getBlockState(DOOR);
				((DoorBlock) RPHabitat.AIRLOCK_DOOR).setOpen(null, helper.getLevel(), door, helper.absolutePos(DOOR), true);
				helper.assertTrue(helper.getBlockState(DOOR.above()).getValue(DoorBlock.OPEN), "both halves of the door open");
			})
			.thenWaitUntil(() -> { // at the next scan, within two seconds
				helper.assertFalse(regulator.isSealed(), "an open door is a breach");
				helper.assertValueEqual(regulator.airKg(), 0.0F, "oxygen left in a vented habitat");
				helper.assertFalse(inside(helper, CENTRE), "the vented habitat is out of the index");
				helper.assertFalse(helper.getBlockState(REGULATOR).getValue(HabitatRegulatorBlock.LIT), "the regulator goes dark");
			})
			.thenSucceed();
	}

	@GameTest(maxTicks = 100, skyAccess = true)
	public void aHoleKeepsTheRoomFromSealing(GameTestHelper helper) {
		HabitatRegulatorBlockEntity regulator = room(helper, false, true);
		helper.runAfterDelay(60, () -> {
			helper.assertFalse(regulator.isSealed(), "a room with a hole in the wall");
			helper.assertValueEqual(regulator.volume(), 0, "volume kept by a leaking regulator");
			helper.assertValueEqual(helper.getBlockEntity(TANK, OxygenTankBlockEntity.class).kg(), OxygenTankBlockEntity.CAPACITY_KG,
				"no oxygen is wasted on a room that can't hold it");
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 120)
	public void regulatorSlotsFillAndEmptyVessels(GameTestHelper helper) {
		// The outlet is walled up, so no air is kept: only the slots move oxygen.
		BlockPos at = new BlockPos(2, 1, 2);
		helper.setBlock(at.south(), RPHabitat.HABITAT_PANEL);
		helper.setBlock(at.north(), RPHabitat.OXYGEN_TANK);
		helper.getBlockEntity(at.north(), OxygenTankBlockEntity.class).put(OxygenTankBlockEntity.CAPACITY_KG);
		helper.setBlock(at, RPHabitat.HABITAT_REGULATOR.defaultBlockState().setValue(HabitatRegulatorBlock.FACING, Direction.SOUTH));
		HabitatRegulatorBlockEntity regulator = helper.getBlockEntity(at, HabitatRegulatorBlockEntity.class);
		ItemStack empty = new ItemStack(RPSuit.OXYGEN_CANISTER);
		empty.set(RPSuit.OXYGEN, Oxygen.empty(SpaceSuit.CANISTER_CAPACITY_KG));
		regulator.setItem(HabitatRegulatorBlockEntity.SLOT_FILL, empty);
		ItemStack full = new ItemStack(RPSuit.SPACESUIT_TORSO);
		regulator.setItem(HabitatRegulatorBlockEntity.SLOT_DRAIN, full);
		helper.runAfterDelay(50, () -> {
			Oxygen filled = SpaceSuit.oxygen(regulator.getItem(HabitatRegulatorBlockEntity.SLOT_FILL));
			Oxygen drained = SpaceSuit.oxygen(regulator.getItem(HabitatRegulatorBlockEntity.SLOT_DRAIN));
			helper.assertTrue(filled != null && filled.isFull(), "the canister in the fill slot should be full: " + filled);
			helper.assertTrue(drained != null && drained.isEmpty(), "the suit in the drain slot should be empty: " + drained);
			helper.assertFalse(regulator.isSealed(), "a walled-up outlet keeps no air");
			float stores = regulator.stores(helper.getLevel(), helper.absolutePos(at));
			float expected = OxygenTankBlockEntity.CAPACITY_KG - SpaceSuit.CANISTER_CAPACITY_KG + SpaceSuit.SUIT_CAPACITY_KG;
			helper.assertTrue(Math.abs(stores - expected) < 0.01F, "stores after the transfers: " + stores + " kg, expected " + expected);
			helper.succeed();
		});
	}
}
