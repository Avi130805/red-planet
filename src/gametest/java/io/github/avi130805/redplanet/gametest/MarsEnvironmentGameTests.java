package io.github.avi130805.redplanet.gametest;

import io.github.avi130805.redplanet.environment.Breathing;
import io.github.avi130805.redplanet.environment.PlanetEnvironment;
import io.github.avi130805.redplanet.environment.RPAttributes;
import io.github.avi130805.redplanet.mars.weather.DustDevil;
import io.github.avi130805.redplanet.registry.RPDimensions;
import io.github.avi130805.redplanet.registry.RPEntities;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Mars environment rules (docs/SCIENCE.md sections 2-7), checked in a real {@code redplanet:mars} level: the
 * gametest datapack's {@code flat_all_dimensions} world preset adds a flat Mars (bedrock and three layers of
 * regolith, surface at y = 4). Earth controls check that vanilla behaviour is untouched.
 */
public class MarsEnvironmentGameTests {
	private static final String MARS = "redplanet:mars";

	// ------------------------------------------------------------------------------------------------ setup

	@GameTest
	public void marsLevelExists(GameTestHelper helper) {
		helper.assertTrue(helper.getLevel().getServer().getLevel(RPDimensions.MARS) != null,
			"redplanet:mars missing from the gametest server (world preset override)");
		helper.succeed();
	}

	@GameTest(dimension = MARS)
	public void marsAttributes(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Vec3 pos = helper.absoluteVec(new Vec3(4, 1, 4));
		helper.assertValueEqual((float) PlanetEnvironment.gravity(level), 0.3794F, "gravity");
		helper.assertFalse(PlanetEnvironment.breathable(level, pos), "Mars air must not be breathable");
		helper.assertFalse(PlanetEnvironment.combustion(level, BlockPos.containing(pos)), "nothing burns on Mars");
		helper.assertTrue(level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos), "water evaporates");
		helper.succeed();
	}

	// ---------------------------------------------------------------------------------------------- gravity

	@GameTest(dimension = MARS, maxTicks = 40, skyAccess = true)
	public void itemFallsWithMarsGravity(GameTestHelper helper) {
		ItemEntity item = helper.spawnItem(Items.STONE, new Vec3(4, 7, 4));
		item.setDeltaMovement(Vec3.ZERO);
		helper.runAfterDelay(10, () -> {
			// 10 ticks of 0.04 x 0.3794 blocks/tick^2 with almost no drag: about -0.15 (Earth: -0.36).
			double vy = item.getDeltaMovement().y;
			helper.assertTrue(vy < -0.12 && vy > -0.18, "vertical speed after 10 ticks on Mars was " + vy);
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 40, skyAccess = true)
	public void earthItemFallsAtVanillaRate(GameTestHelper helper) {
		ItemEntity item = helper.spawnItem(Items.STONE, new Vec3(4, 7, 4));
		item.setDeltaMovement(Vec3.ZERO);
		helper.runAfterDelay(10, () -> {
			double vy = item.getDeltaMovement().y;
			helper.assertTrue(vy < -0.32 && vy > -0.40, "vertical speed after 10 ticks on Earth was " + vy);
			helper.succeed();
		});
	}

	@GameTest(dimension = MARS, maxTicks = 200, skyAccess = true)
	public void safeFallIsLongerOnMars(GameTestHelper helper) {
		// Fall damage counts the Earth-equivalent distance (x 0.3794): 7 blocks on Mars is safe, 13 hurts a little.
		Pig low = helper.spawn(EntityTypes.PIG, new Vec3(1.5, 7.0, 1.5));
		Pig high = helper.spawn(EntityTypes.PIG, new Vec3(6.5, 13.0, 6.5));
		float lowHealth = low.getHealth();
		float highHealth = high.getHealth();
		helper.succeedWhen(() -> {
			helper.assertTrue(low.onGround() && high.onGround(), "pigs still falling");
			helper.assertValueEqual(low.getHealth(), lowHealth, "health after a 7-block fall on Mars");
			float lost = highHealth - high.getHealth();
			helper.assertTrue(lost > 0.0F && lost < 3.0F, "damage from a 13-block fall on Mars was " + lost);
		});
	}

	// ------------------------------------------------------------------------------------------------ air

	@GameTest(dimension = MARS)
	public void pressureFallsWithAltitude(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Vec3 low = helper.absoluteVec(new Vec3(4, 1, 4));
		Vec3 high = low.add(0, 10, 0);
		double ratio = PlanetEnvironment.pressure(level, high) / PlanetEnvironment.pressure(level, low);
		double expected = Math.exp(-10 * 100.0 / 11000.0); // 10 blocks = 1 km, scale height 11 km
		helper.assertTrue(Math.abs(ratio - expected) < 1e-3, "pressure ratio over 1 km was " + ratio + ", expected " + expected);
		double densityRatio = PlanetEnvironment.airDensity(level, high) / PlanetEnvironment.airDensity(level, low);
		helper.assertTrue(Math.abs(densityRatio - expected) < 1e-3, "density ratio over 1 km was " + densityRatio);
		helper.assertValueEqual(level.environmentAttributes().getDimensionValue(RPAttributes.SCALE_HEIGHT), 11000.0F, "scale height");
		helper.succeed();
	}

	@GameTest(dimension = MARS, maxTicks = 300)
	public void pigBlacksOutThenSuffocates(GameTestHelper helper) {
		Pig pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(4, 1, 4));
		pig.setAirSupply(5);
		float startHealth = pig.getHealth();
		helper.runAfterDelay(30, () -> {
			helper.assertTrue(pig.getAirSupply() <= 0, "air should be gone, was " + pig.getAirSupply());
			helper.assertTrue(pig.hasEffect(MobEffects.BLINDNESS), "blackout (blindness) once the air is gone");
			helper.assertValueEqual(pig.getHealth(), startHealth, "health before the first hypoxia interval");
		});
		helper.runAfterDelay(5 + Breathing.DAMAGE_INTERVAL_TICKS + 20, () -> {
			helper.assertTrue(pig.getHealth() < startHealth, "hypoxia damage after one interval");
			helper.assertTrue(pig.getHealth() >= startHealth - 2.0F, "hypoxia damage is slow (1 per interval), health " + pig.getHealth());
			helper.succeed();
		});
	}

	@GameTest(dimension = MARS, maxTicks = 60)
	public void skeletonDoesNotBreathe(GameTestHelper helper) {
		Skeleton skeleton = helper.spawnWithNoFreeWill(EntityTypes.SKELETON, new BlockPos(4, 1, 4));
		int air = skeleton.getMaxAirSupply();
		skeleton.setAirSupply(air);
		helper.runAfterDelay(40, () -> {
			helper.assertValueEqual(skeleton.getAirSupply(), air, "skeleton air on Mars");
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 60)
	public void earthPigBreathes(GameTestHelper helper) {
		Pig pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(4, 1, 4));
		int air = pig.getMaxAirSupply();
		helper.runAfterDelay(40, () -> {
			helper.assertValueEqual(pig.getAirSupply(), air, "pig air on Earth");
			helper.succeed();
		});
	}

	// ----------------------------------------------------------------------------------------------- fire

	@GameTest(dimension = MARS)
	public void torchesAndCampfiresDoNotBurn(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos torch = new BlockPos(2, 0, 2);
		helper.setBlock(torch, Blocks.TORCH);
		helper.assertFalse(helper.getBlockState(torch).canSurvive(level, helper.absolutePos(torch)), "a torch can't survive on Mars");
		BlockPos campfire = new BlockPos(5, 0, 5);
		helper.setBlock(campfire, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
		BlockState placed = helper.getBlockState(campfire);
		helper.assertTrue(placed.is(Blocks.CAMPFIRE) && !placed.getValue(CampfireBlock.LIT), "a campfire placed lit must go out on Mars");
		helper.succeed();
	}

	@GameTest(dimension = MARS, maxTicks = 20)
	public void burningMobsGoOut(GameTestHelper helper) {
		Pig pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(4, 1, 4));
		pig.setRemainingFireTicks(200);
		helper.assertValueEqual(pig.getRemainingFireTicks(), 0, "fire ticks on Mars");
		helper.succeed();
	}

	@GameTest
	public void earthTorchSurvives(GameTestHelper helper) {
		BlockPos torch = new BlockPos(2, 0, 2);
		helper.setBlock(torch, Blocks.TORCH);
		helper.assertTrue(helper.getBlockState(torch).canSurvive(helper.getLevel(), helper.absolutePos(torch)), "a torch survives on Earth");
		helper.succeed();
	}
	// ------------------------------------------------------------------------------------------- dust devils

	@GameTest(dimension = MARS, maxTicks = 120, skyAccess = true)
	public void dustDevilSwirlsLooseItems(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos centre = helper.absolutePos(new BlockPos(4, 1, 4));
		DustDevil devil = RPEntities.DUST_DEVIL.create(level, EntitySpawnReason.COMMAND);
		helper.assertTrue(devil != null, "dust devil type");
		devil.configure(6.0F, 30.0F, 0.0, 0.0, 400);
		devil.setPos(centre.getX() + 0.5, centre.getY(), centre.getZ() + 0.5);
		level.addFreshEntity(devil);
		ItemEntity item = helper.spawnItem(Items.STONE, new Vec3(6.5, 1.2, 4.5));
		item.setDeltaMovement(Vec3.ZERO);
		Vec3 start = item.position();
		helper.runAfterDelay(80, () -> {
			helper.assertTrue(devil.strength() > 0.3F, "the dust devil spun up: " + devil.strength());
			double moved = item.position().distanceTo(start);
			helper.assertTrue(moved > 0.5, "the item should be swirled, moved " + moved);
			devil.discard();
			helper.succeed();
		});
	}
}
