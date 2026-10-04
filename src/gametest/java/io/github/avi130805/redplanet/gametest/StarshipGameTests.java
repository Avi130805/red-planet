package io.github.avi130805.redplanet.gametest;

import java.util.UUID;

import io.github.avi130805.redplanet.registry.RPDimensions;
import io.github.avi130805.redplanet.registry.RPRegistries;
import io.github.avi130805.redplanet.registry.RPStarship;
import io.github.avi130805.redplanet.starship.entity.StarshipEntity;
import io.github.avi130805.redplanet.starship.entity.SuperHeavyEntity;
import io.github.avi130805.redplanet.starship.flight.FlightProfile;
import io.github.avi130805.redplanet.starship.flight.FlightSegment;
import io.github.avi130805.redplanet.starship.flight.Pacing;
import io.github.avi130805.redplanet.starship.geometry.StarshipGeometry;
import io.github.avi130805.redplanet.starship.item.VehicleItem;



import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * The Starship: flight profiles, placement, boarding and whole flights on the short {@code test_hop} profile (about ten
 * seconds from ignition to touchdown on Mars).
 */
public class StarshipGameTests {
	/**
	 * The gametest server runs ticks as fast as it can while chunks load in real time, so a ship flying downrange
	 * outruns chunk loading there (at 20 ticks a second it doesn't). This profile flies straight up and down, staying in
	 * its chunk column, so the tests exercise the timeline, staging, the transfer and the landing.
	 */
	private static final Identifier TEST_HOP = Identifier.fromNamespaceAndPath("redplanet-gametest", "vertical_hop");

	@GameTest
	public void flightProfilesLoad(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (Identifier id : new Identifier[]{RPRegistries.EARTH_TO_MARS, RPRegistries.MARS_TO_EARTH, TEST_HOP,
				Identifier.fromNamespaceAndPath("redplanet-gametest", "test_hop")}) {
			FlightProfile profile = RPRegistries.profile(level.registryAccess(), id).orElse(null);
			helper.assertTrue(profile != null, "flight profile " + id + " missing (it failed to load or decode)");
			helper.assertTrue(profile.firstPhaseOf(FlightSegment.DESCENT) > 0, id + " has no descent");
			helper.assertTrue(profile.ship().endTime() >= profile.phases().getLast().missionStart(), id + ": ship telemetry ends early");
		}
		FlightProfile out = RPRegistries.profile(level.registryAccess(), RPRegistries.EARTH_TO_MARS).orElseThrow();
		helper.assertTrue(out.vehicle() == FlightProfile.Vehicle.STACK, "Earth launches fly the full stack");
		helper.assertTrue(RPDimensions.MARS.equals(out.destination()), "earth_to_mars lands on Mars");
		FlightProfile back = RPRegistries.profile(level.registryAccess(), RPRegistries.MARS_TO_EARTH).orElseThrow();
		helper.assertTrue(back.vehicle() == FlightProfile.Vehicle.SHIP, "Mars launches fly the ship alone");
		helper.succeed();
	}

	@GameTest(maxTicks = 20)
	public void placementNeedsAClearColumn(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		// High above the test area, clear of the neighbouring tests' blocks and vehicles.
		BlockPos ground = helper.absolutePos(new BlockPos(4, 150, 4));
		helper.assertTrue(VehicleItem.hasRoom(level, ground, RPStarship.STARSHIP), "open sky");
		helper.setBlock(new BlockPos(4, 180, 4), net.minecraft.world.level.block.Blocks.STONE);
		helper.assertFalse(VehicleItem.hasRoom(level, ground, RPStarship.STARSHIP), "a block 30 m up is inside the hull");
		helper.setBlock(new BlockPos(4, 180, 4), net.minecraft.world.level.block.Blocks.AIR);
		helper.succeed();
	}

	@GameTest(maxTicks = 40)
	public void boardAndStayBuckledInFlight(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		StarshipEntity ship = VehicleItem.place(level, helper.absolutePos(new BlockPos(4, 0, 4)), RPStarship.STARSHIP);
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		helper.assertTrue(player.startRiding(ship), "a player boards a landed ship");
		helper.assertTrue(player.getVehicle() == ship, "seated");
		helper.assertFalse(ship.isDismountLocked(), "on the ground the crew may step out");
		helper.assertTrue(ship.isCabinPressurized(), "the cabin holds air");
		ship.positionRider(player);
		Vec3 seat = player.position().subtract(ship.position());
		helper.assertTrue(seat.y > StarshipGeometry.CABIN_FLOOR_Y - 1.0 && seat.y < StarshipGeometry.CABIN_CEILING_Y,
			"the passenger sits on the cabin deck, " + seat.y + " m up");
		ship.discard();
		helper.succeed();
	}

	/** A launched stack keeps ticking on the pad (and stays findable) through the count. */
	@GameTest(maxTicks = 60)
	public void launchedStackKeepsTicking(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos ground = helper.absolutePos(new BlockPos(4, 0, 4));
		SuperHeavyEntity booster = VehicleItem.place(level, ground, RPStarship.SUPER_HEAVY);
		StarshipEntity ship = RPStarship.STARSHIP.create(level, EntitySpawnReason.COMMAND);
		ship.stackOn(booster);
		helper.assertTrue(level.addFreshEntity(ship), "ship added");
		helper.assertTrue(ship.launch(level, TEST_HOP, Pacing.LONG, null), "launch");
		int start = ship.tickCount;
		helper.runAfterDelay(15, () -> {
			helper.assertTrue(ship.tickCount > start + 10, "the ship stopped ticking: " + ship.tickCount);
			helper.assertTrue(level.getEntity(ship.getUUID()) == ship, "the ship left the loaded world at " + ship.position());
			helper.assertTrue(ship.isStacked() && ship.isFlying(), "still on the booster, counting down");
			ship.discard();
			booster.discard();
			helper.succeed();
		});
	}

	/** An uncrewed stack flies the test hop: staging, the booster's return, the transfer to Mars and touchdown. */
	@GameTest(maxTicks = 900)
	public void stackFliesToMarsAndLands(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerLevel mars = level.getServer().getLevel(RPDimensions.MARS);
		helper.assertTrue(mars != null, "Mars level");
		FlightProfile profile = RPRegistries.profile(level.registryAccess(), TEST_HOP).orElseThrow();
		BlockPos ground = helper.absolutePos(new BlockPos(4, 0, 4));
		SuperHeavyEntity booster = VehicleItem.place(level, ground, RPStarship.SUPER_HEAVY);
		StarshipEntity ship = RPStarship.STARSHIP.create(level, EntitySpawnReason.COMMAND);
		ship.stackOn(booster);
		level.addFreshEntity(ship);
		Vec3 pad = booster.position();
		UUID id = ship.getUUID();
		Vec3 site = new Vec3(ship.getX() + 10.0, 0.0, ship.getZ() - 6.0);
		helper.assertTrue(ship.launch(level, TEST_HOP, Pacing.STANDARD, site), "launch");
		helper.assertTrue(ship.isFlying() && booster.isFlying(), "both stages fly");
		int total = profile.totalTicks(Pacing.STANDARD);
		boolean[] staged = {false};
		boolean[] climbed = {false};
		StarshipEntity[] landed = {null};
		int[] tick = {0};
		helper.onEachTick(() -> {
			tick[0]++;
			if (!ship.isRemoved() && ship.isFlying() && ship.segment().orElse(null) == FlightSegment.ASCENT) {
				climbed[0] |= ship.getY() > pad.y + StarshipGeometry.BOOSTER_HEIGHT + 20.0;
				staged[0] |= !ship.isStacked();
			}
			if (mars.getEntity(id) instanceof StarshipEntity s && !s.isFlying() && landed[0] == null) {
				landed[0] = s;
			}
			if (landed[0] != null && tick[0] % 10 == 0) {
				// Nobody is on Mars to keep the landing site loaded: hold it for the checks.
				mars.getChunkSource().addTicketWithRadius(RPRegistries.FLIGHT_TICKET, landed[0].chunkPosition(), 1);
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(landed[0] != null, "the ship has not landed on Mars yet");
			StarshipEntity s = landed[0];
			helper.assertTrue(climbed[0], "the stack never climbed");
			helper.assertTrue(staged[0], "the ship never left the booster");
			helper.assertTrue(Math.abs(s.getX() - (Math.floor(site.x) + 0.5)) < 26.0 && Math.abs(s.getZ() - (Math.floor(site.z) + 0.5)) < 26.0,
				"landed at " + s.position() + ", far from the chosen site " + site);
			int surface = mars.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(s.getX()),
				(int) Math.floor(s.getZ()));
			helper.assertTrue(Math.abs(s.getY() - (surface + StarshipGeometry.LANDED_SKIRT_HEIGHT)) < 0.6,
				"standing on its legs: y " + s.getY() + ", surface " + surface);
			helper.assertTrue(s.restingLegs() > 0.99F, "legs deployed");
			helper.assertFalse(booster.isFlying(), "the booster is still flying");
			helper.assertTrue(booster.position().distanceTo(pad) < 0.6, "the booster came home to the pad: " + booster.position() + " vs " + pad);
			s.discard();
			booster.discard();
		});
		helper.assertTrue(total < 800, "the test hop should take well under 40 s, takes " + total + " ticks");
	}

	/** A crewed hop: the passenger rides through the transfer and is still aboard on Mars, unhurt and breathing. */
	@GameTest(maxTicks = 900)
	public void crewRidesToMars(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ServerLevel mars = level.getServer().getLevel(RPDimensions.MARS);
		BlockPos ground = helper.absolutePos(new BlockPos(4, 0, 4));
		SuperHeavyEntity booster = VehicleItem.place(level, ground, RPStarship.SUPER_HEAVY);
		StarshipEntity ship = RPStarship.STARSHIP.create(level, EntitySpawnReason.COMMAND);
		ship.stackOn(booster);
		level.addFreshEntity(ship);
		ServerPlayer crew = TestPlayers.survival(helper, "test-crew");
		helper.assertTrue(crew.startRiding(ship), "boarding from the booster's top");
		UUID id = ship.getUUID();
		helper.assertTrue(ship.launch(level, TEST_HOP, Pacing.STANDARD, new Vec3(ship.getX() - 6.0, 0.0, ship.getZ() + 6.0)), "launch");
		helper.assertTrue(ship.isDismountLocked(), "buckled in for flight");
		helper.succeedWhen(() -> {
			Entity onMars = mars.getEntity(id);
			helper.assertTrue(onMars instanceof StarshipEntity s && !s.isFlying(), "not landed on Mars yet");
			helper.assertTrue(crew.level() == mars, "the crew isn't on Mars: " + crew.level().dimension().identifier());
			helper.assertTrue(crew.getVehicle() == onMars, "the crew should still be aboard after landing");
			helper.assertTrue(crew.getHealth() >= crew.getMaxHealth() - 0.01F, "the crew was hurt: " + crew.getHealth());
			helper.assertTrue(crew.getAirSupply() >= crew.getMaxAirSupply() - 1, "the crew ran short of air: " + crew.getAirSupply());
			crew.stopRiding();
			onMars.discard();
			booster.discard();
			crew.discard();
		});
	}

	private static String describe(Entity e) {
		if (e == null) {
			return "absent";
		}
		if (e instanceof StarshipEntity s) {
			return String.format("%s phase %d flying %b removed %b at %s", s.level().dimension().identifier(), s.phase(), s.isFlying(), s.isRemoved(),
				s.blockPosition());
		}
		return e.toString();
	}
}
