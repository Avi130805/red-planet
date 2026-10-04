package io.github.avi130805.redplanet.gametest;

import io.github.avi130805.redplanet.machine.OxygenConcentratorBlockEntity;
import io.github.avi130805.redplanet.registry.RPStarship;
import io.github.avi130805.redplanet.registry.RPSuit;
import io.github.avi130805.redplanet.starship.entity.StarshipEntity;
import io.github.avi130805.redplanet.suit.Oxygen;
import io.github.avi130805.redplanet.suit.SpaceSuit;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The spacesuit and its oxygen (docs/SCIENCE.md, section 5): it keeps its wearer breathing on Mars only when all four
 * pieces are worn and oxygen is left, uses 0.84 kg a day only in bad air, works under water, and refills from the
 * Starship, canisters and the concentrator (which needs air with oxygen in it).
 */
public class SuitGameTests {
	private static final String MARS = "redplanet:mars";

	private static Villager suited(GameTestHelper helper, float oxygenKg, boolean helmet) {
		Villager v = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, new BlockPos(4, 1, 4));
		if (helmet) {
			v.setItemSlot(EquipmentSlot.HEAD, new ItemStack(RPSuit.SPACESUIT_HELMET));
		}
		ItemStack torso = new ItemStack(RPSuit.SPACESUIT_TORSO);
		torso.set(RPSuit.OXYGEN, new Oxygen(oxygenKg, SpaceSuit.SUIT_CAPACITY_KG));
		v.setItemSlot(EquipmentSlot.CHEST, torso);
		v.setItemSlot(EquipmentSlot.LEGS, new ItemStack(RPSuit.SPACESUIT_LEGS));
		v.setItemSlot(EquipmentSlot.FEET, new ItemStack(RPSuit.SPACESUIT_BOOTS));
		v.setAirSupply(v.getMaxAirSupply());
		return v;
	}

	private static float oxygen(LivingEntity e) {
		return SpaceSuit.oxygenKg(e);
	}

	@GameTest(dimension = MARS, maxTicks = 140)
	public void suitKeepsItsWearerBreathingAndUsesOxygen(GameTestHelper helper) {
		Villager v = suited(helper, SpaceSuit.SUIT_CAPACITY_KG, true);
		int air = v.getMaxAirSupply();
		helper.runAfterDelay(100, () -> {
			helper.assertValueEqual(v.getAirSupply(), air, "air in a sealed suit on Mars");
			float used = SpaceSuit.SUIT_CAPACITY_KG - oxygen(v);
			float expected = 100 * SpaceSuit.USE_KG_PER_TICK;
			helper.assertTrue(Math.abs(used - expected) <= 3 * SpaceSuit.USE_KG_PER_TICK,
				"oxygen used in 100 ticks: " + used + " kg, expected about " + expected);
			helper.succeed();
		});
	}

	@GameTest(dimension = MARS, maxTicks = 60)
	public void emptySuitDoesNotHelp(GameTestHelper helper) {
		Villager v = suited(helper, 0.0F, true);
		int air = v.getMaxAirSupply();
		helper.runAfterDelay(40, () -> {
			helper.assertTrue(v.getAirSupply() < air - 30, "an empty suit can't be breathed from; air " + v.getAirSupply());
			helper.succeed();
		});
	}

	@GameTest(dimension = MARS, maxTicks = 60)
	public void suitWithoutHelmetIsNotSealed(GameTestHelper helper) {
		Villager v = suited(helper, SpaceSuit.SUIT_CAPACITY_KG, false);
		int air = v.getMaxAirSupply();
		helper.runAfterDelay(40, () -> {
			helper.assertTrue(v.getAirSupply() < air - 30, "without a helmet the suit isn't sealed; air " + v.getAirSupply());
			helper.assertValueEqual(oxygen(v), SpaceSuit.SUIT_CAPACITY_KG, "an unsealed suit doesn't use oxygen");
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 60)
	public void suitUsesNoOxygenInGoodAir(GameTestHelper helper) {
		Villager v = suited(helper, SpaceSuit.SUIT_CAPACITY_KG, true);
		helper.runAfterDelay(40, () -> {
			helper.assertValueEqual(oxygen(v), SpaceSuit.SUIT_CAPACITY_KG, "oxygen used on Earth with the visor open");
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 100)
	public void sealedSuitBreathesUnderWater(GameTestHelper helper) {
		for (int x = 2; x <= 6; x++) {
			for (int z = 2; z <= 6; z++) {
				for (int y = 1; y <= 3; y++) {
					helper.setBlock(new BlockPos(x, y, z), Blocks.WATER);
				}
			}
		}
		Villager v = suited(helper, SpaceSuit.SUIT_CAPACITY_KG, true);
		int air = v.getMaxAirSupply();
		helper.runAfterDelay(60, () -> {
			helper.assertTrue(v.isEyeInFluid(net.minecraft.tags.FluidTags.WATER), "the villager should be under water");
			helper.assertValueEqual(v.getAirSupply(), air, "air in a sealed suit under water");
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 80)
	public void starshipTopsUpItsCrew(GameTestHelper helper) {
		BlockPos ground = new BlockPos(4, 150, 4);
		StarshipEntity ship = RPStarship.STARSHIP.create(helper.getLevel(), EntitySpawnReason.COMMAND);
		helper.assertTrue(ship != null, "ship created");
		Vec3 at = helper.absoluteVec(Vec3.atBottomCenterOf(ground));
		ship.snapTo(at.x, at.y, at.z, 0.0F, 0.0F);
		helper.getLevel().addFreshEntity(ship);
		ServerPlayer crew = TestPlayers.survival(helper, "test-suit");
		ItemStack torso = new ItemStack(RPSuit.SPACESUIT_TORSO);
		torso.set(RPSuit.OXYGEN, Oxygen.empty(SpaceSuit.SUIT_CAPACITY_KG));
		crew.setItemSlot(EquipmentSlot.CHEST, torso);
		ItemStack canister = new ItemStack(RPSuit.OXYGEN_CANISTER);
		canister.set(RPSuit.OXYGEN, Oxygen.empty(SpaceSuit.CANISTER_CAPACITY_KG));
		crew.getInventory().add(canister);
		helper.assertTrue(crew.startRiding(ship), "boarding");
		helper.runAfterDelay(60, () -> {
			float suit = oxygen(crew);
			helper.assertTrue(suit > 0.5F * SpaceSuit.SUIT_CAPACITY_KG, "the suit should be refilling aboard, has " + suit + " kg");
			crew.stopRiding();
			ship.discard();
			crew.discard();
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 20)
	public void canisterTopsUpTheSuit(GameTestHelper helper) {
		ServerPlayer player = TestPlayers.survival(helper, "test-canister");
		ItemStack torso = new ItemStack(RPSuit.SPACESUIT_TORSO);
		torso.set(RPSuit.OXYGEN, new Oxygen(0.1F, SpaceSuit.SUIT_CAPACITY_KG));
		player.setItemSlot(EquipmentSlot.CHEST, torso);
		ItemStack canister = new ItemStack(RPSuit.OXYGEN_CANISTER);
		player.setItemInHand(InteractionHand.MAIN_HAND, canister);
		canister.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
		float suit = oxygen(player);
		Oxygen left = SpaceSuit.oxygen(player.getMainHandItem());
		helper.assertTrue(Math.abs(suit - SpaceSuit.SUIT_CAPACITY_KG) < 1e-4, "the suit should be full, has " + suit);
		helper.assertTrue(left != null && Math.abs(left.kg() - (SpaceSuit.CANISTER_CAPACITY_KG - (SpaceSuit.SUIT_CAPACITY_KG - 0.1F))) < 1e-4,
			"the canister should have given what the suit lacked, has " + (left == null ? "nothing" : left.kg()));
		player.discard();
		helper.succeed();
	}

	@GameTest(maxTicks = 300)
	public void concentratorFillsOnEarth(GameTestHelper helper) {
		OxygenConcentratorBlockEntity concentrator = placeConcentrator(helper);
		helper.runAfterDelay(200, () -> {
			Oxygen o = SpaceSuit.oxygen(concentrator.getItem(OxygenConcentratorBlockEntity.SLOT_VESSEL));
			helper.assertTrue(o != null, "the canister should still be filling");
			float expected = 199 * OxygenConcentratorBlockEntity.FILL_KG_PER_TICK;
			helper.assertTrue(o.kg() > 0.8F * expected, "filled " + o.kg() + " kg in 200 ticks, expected about " + expected);
			helper.succeed();
		});
	}

	@GameTest(dimension = MARS, maxTicks = 120)
	public void concentratorNeedsOxygenInTheAir(GameTestHelper helper) {
		OxygenConcentratorBlockEntity concentrator = placeConcentrator(helper);
		helper.runAfterDelay(100, () -> {
			Oxygen o = SpaceSuit.oxygen(concentrator.getItem(OxygenConcentratorBlockEntity.SLOT_VESSEL));
			helper.assertTrue(o != null && o.isEmpty(), "nothing to concentrate on Mars, has " + (o == null ? "no canister" : o.kg()));
			helper.assertValueEqual(concentrator.getItem(OxygenConcentratorBlockEntity.SLOT_FUEL).getCount(), 4, "no fuel burnt on Mars");
			helper.succeed();
		});
	}

	private static OxygenConcentratorBlockEntity placeConcentrator(GameTestHelper helper) {
		BlockPos pos = new BlockPos(3, 1, 3);
		helper.setBlock(pos, RPSuit.OXYGEN_CONCENTRATOR);
		OxygenConcentratorBlockEntity be = helper.getBlockEntity(pos, OxygenConcentratorBlockEntity.class);
		ItemStack canister = new ItemStack(RPSuit.OXYGEN_CANISTER);
		canister.set(RPSuit.OXYGEN, Oxygen.empty(SpaceSuit.CANISTER_CAPACITY_KG));
		be.setItem(OxygenConcentratorBlockEntity.SLOT_VESSEL, canister);
		be.setItem(OxygenConcentratorBlockEntity.SLOT_FUEL, new ItemStack(Items.COAL, 4));
		return be;
	}
}
