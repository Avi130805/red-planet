package io.github.avi130805.redplanet.machine;

import io.github.avi130805.redplanet.environment.PlanetEnvironment;
import io.github.avi130805.redplanet.habitat.HabitatSeal;
import io.github.avi130805.redplanet.registry.RPSuit;
import io.github.avi130805.redplanet.suit.Oxygen;
import io.github.avi130805.redplanet.suit.SpaceSuit;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

/**
 * The concentrator's workings: slot 0 takes the vessel to fill (a suit torso or a canister), slot 1 furnace fuel, slot 2
 * receives full vessels. It fills at 0.4 kg of oxygen per game hour, the output of a 5 L/min home concentrator at 93 %
 * purity (docs/SCIENCE.md, section 5): an empty suit in about 70 s of play, a canister in two and a half minutes.
 */
public class OxygenConcentratorBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {
	public static final int SLOT_VESSEL = 0;
	public static final int SLOT_FUEL = 1;
	public static final int SLOT_OUTPUT = 2;
	/** 0.4 kg per game hour (1,000 ticks). */
	public static final float FILL_KG_PER_TICK = 0.4F / 1000.0F;
	private static final int[] SLOTS_UP = {SLOT_VESSEL};
	private static final int[] SLOTS_SIDES = {SLOT_FUEL};
	private static final int[] SLOTS_DOWN = {SLOT_OUTPUT, SLOT_FUEL};

	private NonNullList<ItemStack> items = NonNullList.withSize(3, ItemStack.EMPTY);
	private int litTime;
	private int litTotal;
	/** Whether the air here holds oxygen (synced so the screen can say why nothing happens). */
	private boolean airOk = true;
	private boolean airChecked;

	private final ContainerData data = new ContainerData() {
		@Override
		public int get(int id) {
			return switch (id) {
				case 0 -> OxygenConcentratorBlockEntity.this.litTime;
				case 1 -> OxygenConcentratorBlockEntity.this.litTotal;
				case 2 -> {
					Oxygen o = SpaceSuit.oxygen(OxygenConcentratorBlockEntity.this.items.get(SLOT_VESSEL));
					yield o == null ? 0 : Math.round(o.fraction() * 1000.0F);
				}
				case 3 -> OxygenConcentratorBlockEntity.this.airOk ? 1 : 0;
				default -> 0;
			};
		}

		@Override
		public void set(int id, int value) {
			switch (id) {
				case 0 -> OxygenConcentratorBlockEntity.this.litTime = value;
				case 1 -> OxygenConcentratorBlockEntity.this.litTotal = value;
				case 3 -> OxygenConcentratorBlockEntity.this.airOk = value != 0;
				default -> {
				}
			}
		}

		@Override
		public int getCount() {
			return OxygenConcentratorMenu.DATA_COUNT;
		}
	};

	public OxygenConcentratorBlockEntity(BlockPos pos, BlockState state) {
		super(RPSuit.OXYGEN_CONCENTRATOR_ENTITY, pos, state);
	}

	/**
	 * The intake draws from any open side. A machine in a habitat stands in the habitat's air, but its own (solid) block
	 * is never part of that air, so look at the neighbours, not the block.
	 */
	private static boolean intakeAir(ServerLevel level, BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockPos side = pos.relative(d);
			if (HabitatSeal.isOpen(level, side) && PlanetEnvironment.breathable(level, Vec3.atCenterOf(side))
				&& PlanetEnvironment.combustion(level, side)) {
				return true;
			}
		}
		return false;
	}

	public static void serverTick(ServerLevel level, BlockPos pos, BlockState state, OxygenConcentratorBlockEntity be) {
		boolean wasLit = be.litTime > 0;
		if (be.litTime > 0) {
			be.litTime--;
		}
		if (!be.airChecked || level.getGameTime() % 20 == 0) {
			be.airOk = intakeAir(level, pos);
			be.airChecked = true;
		}
		ItemStack vessel = be.items.get(SLOT_VESSEL);
		Oxygen o = SpaceSuit.oxygen(vessel);
		boolean wantsOxygen = be.airOk && o != null && !o.isFull();
		if (wantsOxygen && be.litTime <= 0) {
			ItemStack fuel = be.items.get(SLOT_FUEL);
			int burn = ResolvableInt.getFromItem(fuel, DataComponents.COOKING_FUEL, CookingFuel::burnTime, be.getLootContext(level), 0);
			if (burn > 0) {
				be.litTime = burn;
				be.litTotal = burn;
				consumeFuel(level, pos, be.items, fuel);
			}
		}
		if (be.litTime > 0 && wantsOxygen) {
			SpaceSuit.fill(vessel, FILL_KG_PER_TICK);
			o = SpaceSuit.oxygen(vessel);
		}
		if (o != null && o.isFull() && be.items.get(SLOT_OUTPUT).isEmpty()) {
			be.items.set(SLOT_OUTPUT, vessel);
			be.items.set(SLOT_VESSEL, ItemStack.EMPTY);
		}
		boolean lit = be.litTime > 0;
		if (lit != wasLit) {
			level.setBlock(pos, state.setValue(OxygenConcentratorBlock.LIT, lit), 3);
		}
		be.setChanged();
	}

	private static void consumeFuel(ServerLevel level, BlockPos pos, NonNullList<ItemStack> items, ItemStack fuel) {
		ItemStackTemplate remainder = fuel.getItem().getCraftingRemainder();
		ItemStack newFuel = fuel;
		fuel.shrink(1);
		if (remainder != null) {
			if (fuel.isEmpty()) {
				newFuel = remainder.create();
			} else {
				Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), remainder.create());
			}
		}
		items.set(SLOT_FUEL, newFuel);
	}

	// ------------------------------------------------------------------------------------------------ container

	@Override
	protected Component getDefaultName() {
		return Component.translatable("container.redplanet.oxygen_concentrator");
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
		return new OxygenConcentratorMenu(containerId, inventory, this, this.data);
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return switch (slot) {
			case SLOT_VESSEL -> SpaceSuit.oxygen(stack) != null;
			case SLOT_FUEL -> stack.has(DataComponents.COOKING_FUEL);
			default -> false;
		};
	}

	@Override
	public int[] getSlotsForFace(Direction direction) {
		return direction == Direction.DOWN ? SLOTS_DOWN : direction == Direction.UP ? SLOTS_UP : SLOTS_SIDES;
	}

	@Override
	public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction direction) {
		return this.canPlaceItem(slot, stack);
	}

	@Override
	public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
		// From below: full vessels, and empty buckets left by burnt lava.
		return slot == SLOT_OUTPUT || slot == SLOT_FUEL && !stack.has(DataComponents.COOKING_FUEL);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
		ContainerHelper.loadAllItems(input, this.items);
		this.litTime = input.getIntOr("lit_time", 0);
		this.litTotal = input.getIntOr("lit_total", 0);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		ContainerHelper.saveAllItems(output, this.items);
		output.putInt("lit_time", this.litTime);
		output.putInt("lit_total", this.litTotal);
	}
}
