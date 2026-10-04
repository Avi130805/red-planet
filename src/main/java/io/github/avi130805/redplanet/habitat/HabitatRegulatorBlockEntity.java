package io.github.avi130805.redplanet.habitat;

import java.util.ArrayList;
import java.util.List;

import io.github.avi130805.redplanet.config.RedPlanetConfig;
import io.github.avi130805.redplanet.registry.RPHabitat;
import io.github.avi130805.redplanet.registry.RPSounds;
import io.github.avi130805.redplanet.registry.RPTags;
import io.github.avi130805.redplanet.suit.SpaceSuit;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

import org.jspecify.annotations.Nullable;

/**
 * The habitat's life support. Every two seconds it flood-fills the air in front of its outlet; if that air is sealed
 * in, it pressurizes it to Earth's 101.3 kPa with 21 % oxygen (0.276 kg of oxygen per cubic metre, docs/SCIENCE.md
 * section 5; the nitrogen buffer gas is pumped out of the Martian air, which is 2.8 % nitrogen and 2 % argon). Once at
 * least 60 % of the nominal oxygen is in, the air is breathable: the regulator registers it with the
 * {@link HabitatManager}, and fire, water, beds and plants work inside.
 *
 * <p>The crew breathes 0.84 kg a day each, drawn from the habitat's air and made up from the stores: the regulator's own
 * 5 kg buffer, then the oxygen tanks touching it (and touching those). Suits worn inside top up from the stores. A
 * breach (an open outer door, a broken wall) vents the air: its oxygen is lost, and pressurizing again costs as much,
 * which is what airlocks are for. Slot 0 empties canisters and suits into the stores; slot 1 fills them from it.
 */
public class HabitatRegulatorBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {
	public static final int SLOT_DRAIN = 0;
	public static final int SLOT_FILL = 1;
	public static final float BUFFER_KG = 5.0F;
	/** Oxygen in a cubic metre of Earth air at 101.3 kPa and 20 C: 21 % of 1.204 kg/m3, by mass 23.1 %. */
	public static final float O2_KG_PER_BLOCK = 0.276F;
	public static final float BREATHABLE_FRACTION = 0.6F;
	public static final int SCAN_INTERVAL = 40;
	/** Pressurizing takes about ten seconds at most: a twentieth of the nominal oxygen a second. */
	private static final float FILL_SHARE_PER_TICK = 1.0F / 200.0F;
	/** Moving oxygen between the stores and vessels in the slots, kg per tick. */
	private static final float SLOT_KG_PER_TICK = 0.05F;
	private static final int MAX_TANKS = 64;

	private NonNullList<ItemStack> items = NonNullList.withSize(2, ItemStack.EMPTY);
	private LongOpenHashSet air = new LongOpenHashSet();
	private @Nullable AABB airBounds;
	private boolean sealed;
	private boolean breathable;
	/** Oxygen in the habitat's air, kg. */
	private float airKg;
	private float bufferKg;
	private int scanIn;
	private int crew;
	/** The tanks' total, cached at each scan (for the screen). */
	private float tankKg;

	private final ContainerData data = new ContainerData() {
		@Override
		public int get(int id) {
			HabitatRegulatorBlockEntity be = HabitatRegulatorBlockEntity.this;
			return switch (id) {
				case 0 -> Math.min(Short.MAX_VALUE, be.air.size());
				case 1 -> be.sealed ? 1 : 0;
				case 2 -> Math.round(1000.0F * be.pressureFraction());
				case 3 -> Math.min(Short.MAX_VALUE, Math.round(10.0F * (be.bufferKg + be.tankKg)));
				case 4 -> be.crew;
				default -> 0;
			};
		}

		@Override
		public void set(int id, int value) {
		}

		@Override
		public int getCount() {
			return HabitatRegulatorMenu.DATA_COUNT;
		}
	};

	public HabitatRegulatorBlockEntity(BlockPos pos, BlockState state) {
		super(RPHabitat.HABITAT_REGULATOR_ENTITY, pos, state);
	}

	public boolean isSealed() {
		return this.sealed;
	}

	public boolean isBreathable() {
		return this.breathable;
	}

	public int volume() {
		return this.air.size();
	}

	public float airKg() {
		return this.airKg;
	}

	public float pressureFraction() {
		float nominal = this.nominalKg();
		return nominal <= 0.0F ? 0.0F : Math.min(1.0F, this.airKg / nominal);
	}

	private float nominalKg() {
		return this.sealed ? this.air.size() * O2_KG_PER_BLOCK : 0.0F;
	}

	public static void serverTick(ServerLevel level, BlockPos pos, BlockState state, HabitatRegulatorBlockEntity be) {
		if (--be.scanIn <= 0) {
			be.scanIn = SCAN_INTERVAL;
			be.rescan(level, pos, state);
		}
		be.handleSlots(level, pos);
		if (be.sealed) {
			float nominal = be.nominalKg();
			if (level.getGameTime() % 20 == 0) {
				be.crew = be.countBreathers(level);
			}
			be.airKg = Math.max(0.0F, be.airKg - be.crew * SpaceSuit.USE_KG_PER_TICK);
			if (be.airKg < nominal) {
				be.airKg += be.draw(level, pos, Math.min(nominal - be.airKg, Math.max(0.01F, nominal * FILL_SHARE_PER_TICK)));
			}
			be.airKg = Math.min(be.airKg, nominal);
			if (level.getGameTime() % 10 == 0) {
				be.refillSuits(level, pos);
			}
		} else {
			be.crew = 0;
		}
		boolean breathableNow = be.sealed && be.airKg >= BREATHABLE_FRACTION * be.nominalKg() && be.nominalKg() > 0.0F;
		if (breathableNow != be.breathable) {
			be.breathable = breathableNow;
			HabitatManager.set(level, pos, breathableNow ? be.air : null);
			if (breathableNow) {
				level.playSound(null, pos, RPSounds.MACHINE_HABITAT_PRESSURIZE, SoundSource.BLOCKS, 1.0F, 1.0F);
			}
		}
		if (state.getValue(HabitatRegulatorBlock.LIT) != be.breathable) {
			level.setBlock(pos, state.setValue(HabitatRegulatorBlock.LIT, be.breathable), 3);
		}
		be.setChanged();
	}

	private void rescan(ServerLevel level, BlockPos pos, BlockState state) {
		BlockPos outlet = pos.relative(state.getValue(HabitatRegulatorBlock.FACING));
		HabitatSeal.Result result = HabitatSeal.fill(level, outlet, RedPlanetConfig.server().habitatMaxVolume());
		if (result.incomplete()) {
			this.scanIn = 10; // part of the air is in a chunk still loading: no verdict yet
			return;
		}
		boolean wasSealed = this.sealed;
		this.sealed = result.sealed() && result.size() > 0;
		this.tankKg = 0.0F;
		for (OxygenTankBlockEntity tank : this.tanks(level, pos)) {
			this.tankKg += tank.kg();
		}
		if (this.sealed) {
			if (!result.volume().equals(this.air)) {
				this.air = result.volume();
				this.airBounds = bounds(this.air);
				if (this.breathable) {
					HabitatManager.set(level, pos, this.air);
				}
			}
			this.airKg = Math.min(this.airKg, this.nominalKg());
		} else {
			this.sealed = false;
			if (wasSealed && this.airKg > 0.0F) {
				// Breach: the air rushes out.
				level.playSound(null, pos, RPSounds.MACHINE_HABITAT_LEAK, SoundSource.BLOCKS, 1.2F, 1.0F);
			}
			this.airKg = 0.0F;
			this.air = new LongOpenHashSet();
			this.airBounds = null;
		}
	}

	private int countBreathers(ServerLevel level) {
		if (this.airBounds == null) {
			return 0;
		}
		int count = 0;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, this.airBounds)) {
			if (e.isAlive() && !e.is(RPTags.DOES_NOT_BREATHE) && this.air.contains(BlockPos.containing(e.getEyePosition()).asLong())) {
				count++;
			}
		}
		return count;
	}

	private void refillSuits(ServerLevel level, BlockPos pos) {
		if (this.airBounds == null || !this.breathable) {
			return;
		}
		for (Player player : level.getEntitiesOfClass(Player.class, this.airBounds)) {
			if (this.air.contains(BlockPos.containing(player.getEyePosition()).asLong())) {
				float budget = Math.min(SpaceSuit.STATION_REFILL_KG_PER_TICK * 10.0F, this.stores(level, pos));
				float given = SpaceSuit.refillFrom(player, budget);
				this.draw(level, pos, given);
			}
		}
	}

	private void handleSlots(ServerLevel level, BlockPos pos) {
		ItemStack drain = this.items.get(SLOT_DRAIN);
		if (!drain.isEmpty()) {
			float out = SpaceSuit.drain(drain, SLOT_KG_PER_TICK);
			float stored = this.store(level, pos, out);
			if (stored < out) {
				SpaceSuit.fill(drain, out - stored); // the stores are full: give it back
			}
		}
		ItemStack fill = this.items.get(SLOT_FILL);
		if (!fill.isEmpty()) {
			float wanted = Math.min(SLOT_KG_PER_TICK, this.stores(level, pos));
			float in = SpaceSuit.fill(fill, wanted);
			this.draw(level, pos, in);
		}
	}

	// ------------------------------------------------------------------------------------------------ stores

	/** The oxygen tanks touching the regulator, and the tanks touching those. */
	private List<OxygenTankBlockEntity> tanks(ServerLevel level, BlockPos pos) {
		List<OxygenTankBlockEntity> found = new ArrayList<>();
		LongOpenHashSet seen = new LongOpenHashSet();
		List<BlockPos> frontier = new ArrayList<>();
		frontier.add(pos);
		seen.add(pos.asLong());
		while (!frontier.isEmpty() && found.size() < MAX_TANKS) {
			BlockPos at = frontier.removeLast();
			for (Direction d : Direction.values()) {
				BlockPos n = at.relative(d);
				if (seen.add(n.asLong()) && level.isLoaded(n) && level.getBlockEntity(n) instanceof OxygenTankBlockEntity tank) {
					found.add(tank);
					frontier.add(n);
				}
			}
		}
		return found;
	}

	public float stores(ServerLevel level, BlockPos pos) {
		float total = this.bufferKg;
		for (OxygenTankBlockEntity tank : this.tanks(level, pos)) {
			total += tank.kg();
		}
		return total;
	}

	/** Takes up to {@code kg} from the stores (buffer first); returns what it got. */
	public float draw(ServerLevel level, BlockPos pos, float kg) {
		if (kg <= 0.0F) {
			return 0.0F;
		}
		float got = Math.min(kg, this.bufferKg);
		this.bufferKg -= got;
		if (got < kg) {
			for (OxygenTankBlockEntity tank : this.tanks(level, pos)) {
				got += tank.take(kg - got);
				if (got >= kg) {
					break;
				}
			}
		}
		return got;
	}

	/** Puts up to {@code kg} into the stores (buffer first); returns what fitted. */
	public float store(ServerLevel level, BlockPos pos, float kg) {
		if (kg <= 0.0F) {
			return 0.0F;
		}
		float put = Math.min(kg, BUFFER_KG - this.bufferKg);
		this.bufferKg += put;
		if (put < kg) {
			for (OxygenTankBlockEntity tank : this.tanks(level, pos)) {
				put += tank.put(kg - put);
				if (put >= kg) {
					break;
				}
			}
		}
		return put;
	}

	/** A regulator that is gone, or whose chunk unloaded, keeps no air (it registers it again when it next runs). */
	@Override
	public void setRemoved() {
		super.setRemoved();
		if (this.level instanceof ServerLevel server && this.breathable) {
			HabitatManager.set(server, this.worldPosition, null);
		}
		this.breathable = false;
	}

	private static @Nullable AABB bounds(LongOpenHashSet air) {
		if (air.isEmpty()) {
			return null;
		}
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		LongIterator it = air.iterator();
		while (it.hasNext()) {
			long p = it.nextLong();
			minX = Math.min(minX, BlockPos.getX(p));
			minY = Math.min(minY, BlockPos.getY(p));
			minZ = Math.min(minZ, BlockPos.getZ(p));
			maxX = Math.max(maxX, BlockPos.getX(p));
			maxY = Math.max(maxY, BlockPos.getY(p));
			maxZ = Math.max(maxZ, BlockPos.getZ(p));
		}
		return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
	}

	// ------------------------------------------------------------------------------------------------ container

	@Override
	protected Component getDefaultName() {
		return Component.translatable("container.redplanet.habitat_regulator");
	}

	@Override
	protected NonNullList<ItemStack> getItems() {
		return this.items;
	}

	@Override
	protected void setItems(NonNullList<ItemStack> items) {
		this.items = items;
	}

	@Override
	public int getContainerSize() {
		return this.items.size();
	}

	@Override
	protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
		return new HabitatRegulatorMenu(containerId, inventory, this, this.data);
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return SpaceSuit.oxygen(stack) != null;
	}

	@Override
	public int[] getSlotsForFace(Direction direction) {
		return direction == Direction.UP ? new int[]{SLOT_DRAIN} : new int[]{SLOT_FILL};
	}

	@Override
	public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction direction) {
		return this.canPlaceItem(slot, stack);
	}

	@Override
	public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
		var o = SpaceSuit.oxygen(stack);
		return o != null && (slot == SLOT_FILL ? o.isFull() : o.isEmpty());
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
		ContainerHelper.loadAllItems(input, this.items);
		this.airKg = input.getFloatOr("air_oxygen_kg", 0.0F);
		this.bufferKg = Math.min(BUFFER_KG, input.getFloatOr("buffer_oxygen_kg", 0.0F));
		// The air set is found again on the first tick; until then the saved oxygen is held.
		this.sealed = input.getBooleanOr("sealed", false);
		this.scanIn = 0;
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		ContainerHelper.saveAllItems(output, this.items);
		output.putFloat("air_oxygen_kg", this.airKg);
		output.putFloat("buffer_oxygen_kg", this.bufferKg);
		output.putBoolean("sealed", this.sealed);
	}
}
