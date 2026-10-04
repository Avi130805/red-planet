package io.github.avi130805.redplanet.starship.entity;

import java.util.Optional;
import java.util.UUID;

import io.github.avi130805.redplanet.registry.RPStarship;
import io.github.avi130805.redplanet.starship.flight.FlightProfile;
import io.github.avi130805.redplanet.starship.flight.TelemetryTrack;
import io.github.avi130805.redplanet.starship.geometry.StarshipGeometry;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

/**
 * Super Heavy, the booster. It lifts the stack off Earth, separates at hot staging and flies back to the pad on its
 * own telemetry (boostback, coast, landing burn) while the ship climbs on. Without a launch tower to catch it, it is
 * set down on the pad at the end of its flight, ready for the next ship.
 */
public class SuperHeavyEntity extends VehicleEntity {
	/** How fast a booster is lowered onto the pad after its flight, blocks per tick. */
	private static final double SETTLE_SPEED = 0.6;

	private @Nullable UUID shipId;
	private float damage;
	/** After the flight: lowering onto the pad. */
	private boolean settling;

	public SuperHeavyEntity(EntityType<? extends SuperHeavyEntity> type, Level level) {
		super(type, level);
	}

	public void linkShip(StarshipEntity ship) {
		this.shipId = ship.getUUID();
	}

	public Optional<StarshipEntity> ship(ServerLevel level) {
		if (this.shipId == null) {
			return Optional.empty();
		}
		return level.getEntity(this.shipId) instanceof StarshipEntity ship && !ship.isRemoved() ? Optional.of(ship) : Optional.empty();
	}

	/** The ship standing on this booster, if any. */
	public Optional<StarshipEntity> stackedShip(ServerLevel level) {
		return this.ship(level).filter(ship -> ship.isStacked() && ship.boosterId().map(this.getUUID()::equals).orElse(false));
	}

	@Override
	protected TelemetryTrack track(FlightProfile profile) {
		return profile.booster();
	}

	@Override
	protected boolean trackFinished(FlightProfile profile, double missionTime) {
		return profile.booster().isEmpty() || missionTime > profile.booster().endTime();
	}

	@Override
	protected void onTrackFinished(ServerLevel level, FlightProfile profile) {
		Vec3 pad = this.reference();
		this.endFlight();
		this.shipId = null;
		this.settling = this.getY() > pad.y + 0.01;
		if (!this.settling) {
			this.setPos(pad);
		}
	}

	@Override
	protected void groundTick(ServerLevel level) {
		if (this.settling) {
			// Lowered onto the pad (in place of a tower catch), straight down over the pad.
			Vec3 pad = this.reference();
			double y = Math.max(pad.y, this.getY() - SETTLE_SPEED);
			this.setPos(pad.x, y, pad.z);
			if (y <= pad.y) {
				this.settling = false;
			}
		}
		if (this.damage > 0.0F) {
			this.damage = Math.max(0.0F, this.damage - 0.1F);
		}
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
		InteractionResult result = super.interact(player, hand, location);
		if (result != InteractionResult.PASS || this.isFlying() || this.settling) {
			return result;
		}
		ItemStack held = player.getItemInHand(hand);
		if (!(this.level() instanceof ServerLevel level)) {
			return held.is(RPStarship.STARSHIP_ITEM) || !player.isSecondaryUseActive() ? InteractionResult.SUCCESS : InteractionResult.PASS;
		}
		Optional<StarshipEntity> stacked = this.stackedShip(level);
		if (held.is(RPStarship.STARSHIP_ITEM)) {
			if (stacked.isPresent()) {
				player.sendOverlayMessage(Component.translatable("starship.redplanet.already_stacked"));
				return InteractionResult.FAIL;
			}
			StarshipEntity ship = RPStarship.STARSHIP.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
			if (ship == null) {
				return InteractionResult.FAIL;
			}
			ship.stackOn(this);
			level.addFreshEntity(ship);
			held.consume(1, player);
			player.sendOverlayMessage(Component.translatable("starship.redplanet.stacked"));
			return InteractionResult.SUCCESS_SERVER;
		}
		// Board the ship on top: its hull is out of reach 72 m up, so the booster takes the crew up.
		if (!player.isSecondaryUseActive() && stacked.isPresent()) {
			return stacked.get().interact(player, hand, location);
		}
		return InteractionResult.PASS;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (this.isFlying() || this.settling || this.isRemoved() || !(source.getEntity() instanceof Player player)) {
			return false;
		}
		if (this.stackedShip(level).isPresent()) {
			player.sendOverlayMessage(Component.translatable("starship.redplanet.unstack_first"));
			return false;
		}
		if (player.getAbilities().instabuild) {
			this.discard();
			return true;
		}
		this.damage += amount * 2.0F;
		if (this.damage > 12.0F) {
			this.spawnAtLocation(level, new ItemStack(RPStarship.SUPER_HEAVY_ITEM));
			this.discard();
		}
		return true;
	}

	@Override
	public @Nullable ItemStack getPickResult() {
		return new ItemStack(RPStarship.SUPER_HEAVY_ITEM);
	}

	@Override
	protected AABB makeBoundingBox(Vec3 position) {
		double r = StarshipGeometry.HULL_RADIUS;
		return new AABB(position.x - r, position.y, position.z - r, position.x + r, position.y + StarshipGeometry.BOOSTER_HEIGHT,
			position.z + r);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		this.shipId = input.read("ship", UUIDUtil.CODEC).orElse(null);
		this.settling = input.getBooleanOr("settling", false);
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.storeNullable("ship", UUIDUtil.CODEC, this.shipId);
		output.putBoolean("settling", this.settling);
	}
}
