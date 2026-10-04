package io.github.avi130805.redplanet.starship.item;

import java.util.function.Supplier;

import io.github.avi130805.redplanet.starship.entity.StarshipEntity;
import io.github.avi130805.redplanet.starship.entity.SuperHeavyEntity;
import io.github.avi130805.redplanet.starship.entity.VehicleEntity;
import io.github.avi130805.redplanet.starship.geometry.StarshipGeometry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Places a Starship (standing on its legs) or a Super Heavy (on its engines) on the block clicked. The vehicle is true
 * scale, so it needs a clear 9-block-wide column above the spot: 53 blocks for the ship, 72 for the booster, and another
 * 52 above the booster if a ship will be stacked on it. A Starship used on a standing Super Heavy stacks onto it.
 */
public class VehicleItem extends Item {
	private final Supplier<EntityType<? extends VehicleEntity>> type;

	public VehicleItem(Supplier<EntityType<? extends VehicleEntity>> type, Item.Properties properties) {
		super(properties);
		this.type = type;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (context.getClickedFace() != Direction.UP) {
			return InteractionResult.FAIL;
		}
		Level level = context.getLevel();
		BlockPos ground = context.getClickedPos();
		Player player = context.getPlayer();
		EntityType<? extends VehicleEntity> type = this.type.get();
		if (!hasRoom(level, ground, type)) {
			if (player != null && !level.isClientSide()) {
				player.sendOverlayMessage(Component.translatable("starship.redplanet.no_room", (int) Math.ceil(height(type))));
			}
			return InteractionResult.FAIL;
		}
		if (level instanceof ServerLevel serverLevel) {
			VehicleEntity vehicle = place(serverLevel, ground, type);
			if (vehicle == null) {
				return InteractionResult.FAIL;
			}
			serverLevel.gameEvent(player, GameEvent.ENTITY_PLACE, ground.above());
			if (vehicle instanceof SuperHeavyEntity && player != null) {
				player.sendOverlayMessage(Component.translatable("starship.redplanet.booster_placed"));
			}
		}
		context.getItemInHand().consume(1, player);
		return InteractionResult.SUCCESS;
	}

	private static boolean isShip(EntityType<?> type) {
		return type.getBaseClass() == StarshipEntity.class;
	}

	/** Hull height of a vehicle type, blocks. */
	public static double height(EntityType<?> type) {
		return isShip(type) ? StarshipGeometry.SHIP_HEIGHT : StarshipGeometry.BOOSTER_HEIGHT;
	}

	/** Where the hull bottom sits on the block {@code ground}: on its legs (ship) or on its engine bells (booster). */
	public static Vec3 restingPosition(BlockPos ground, EntityType<?> type) {
		double lift = isShip(type) ? StarshipGeometry.LANDED_SKIRT_HEIGHT : -StarshipGeometry.BOOSTER_ENGINE_EXIT_Y;
		return new Vec3(ground.getX() + 0.5, ground.getY() + 1.0 + lift, ground.getZ() + 0.5);
	}

	/** Whether the 9-block-wide column above {@code ground} is clear of blocks and other vehicles. */
	public static boolean hasRoom(Level level, BlockPos ground, EntityType<?> type) {
		Vec3 base = restingPosition(ground, type);
		double r = StarshipGeometry.HULL_RADIUS - 0.5;
		AABB column = new AABB(base.x - r, base.y, base.z - r, base.x + r, base.y + height(type), base.z + r);
		return level.noBlockCollision(null, column) && level.getEntitiesOfClass(VehicleEntity.class, column.inflate(1.0)).isEmpty();
	}

	/** Places a vehicle standing on {@code ground} (no room check). */
	public static <V extends VehicleEntity> V place(ServerLevel level, BlockPos ground, EntityType<V> type) {
		V vehicle = type.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
		if (vehicle == null) {
			return null;
		}
		vehicle.setPos(restingPosition(ground, type));
		if (vehicle instanceof StarshipEntity starship) {
			starship.standOnLegs();
		}
		level.addFreshEntity(vehicle);
		return vehicle;
	}
}
