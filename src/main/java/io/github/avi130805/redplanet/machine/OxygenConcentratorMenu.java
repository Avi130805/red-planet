package io.github.avi130805.redplanet.machine;

import io.github.avi130805.redplanet.registry.RPSuit;
import io.github.avi130805.redplanet.suit.SpaceSuit;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** The concentrator's screen: vessel, fuel and output slots laid out like a furnace's. */
public class OxygenConcentratorMenu extends AbstractContainerMenu {
	public static final int DATA_COUNT = 4;
	private final Container container;
	private final ContainerData data;

	public OxygenConcentratorMenu(int containerId, Inventory inventory) {
		this(containerId, inventory, new SimpleContainer(3), new SimpleContainerData(DATA_COUNT));
	}

	public OxygenConcentratorMenu(int containerId, Inventory inventory, Container container, ContainerData data) {
		super(RPSuit.OXYGEN_CONCENTRATOR_MENU, containerId);
		checkContainerSize(container, 3);
		checkContainerDataCount(data, DATA_COUNT);
		this.container = container;
		this.data = data;
		container.startOpen(inventory.player);
		this.addSlot(new Slot(container, OxygenConcentratorBlockEntity.SLOT_VESSEL, 56, 17) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return SpaceSuit.oxygen(stack) != null;
			}
		});
		this.addSlot(new Slot(container, OxygenConcentratorBlockEntity.SLOT_FUEL, 56, 53) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return stack.has(DataComponents.COOKING_FUEL);
			}
		});
		this.addSlot(new Slot(container, OxygenConcentratorBlockEntity.SLOT_OUTPUT, 116, 35) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return false;
			}
		});
		this.addStandardInventorySlots(inventory, 8, 84);
		this.addDataSlots(data);
	}

	public boolean isLit() {
		return this.data.get(0) > 0;
	}

	public float litProgress() {
		int total = this.data.get(1);
		return total <= 0 ? 0.0F : Math.min(1.0F, this.data.get(0) / (float) total);
	}

	/** How full the vessel being filled is, 0..1. */
	public float fillProgress() {
		return this.data.get(2) / 1000.0F;
	}

	public boolean airHasOxygen() {
		return this.data.get(3) != 0;
	}

	@Override
	public boolean stillValid(Player player) {
		return this.container.stillValid(player);
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		this.container.stopOpen(player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = this.slots.get(index);
		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = slot.getItem();
		ItemStack original = stack.copy();
		int machineSlots = 3;
		int inventoryEnd = this.slots.size();
		if (index < machineSlots) {
			if (!this.moveItemStackTo(stack, machineSlots, inventoryEnd, true)) {
				return ItemStack.EMPTY;
			}
		} else if (SpaceSuit.oxygen(stack) != null) {
			if (!this.moveItemStackTo(stack, OxygenConcentratorBlockEntity.SLOT_VESSEL, OxygenConcentratorBlockEntity.SLOT_VESSEL + 1, false)) {
				return ItemStack.EMPTY;
			}
		} else if (stack.has(DataComponents.COOKING_FUEL)) {
			if (!this.moveItemStackTo(stack, OxygenConcentratorBlockEntity.SLOT_FUEL, OxygenConcentratorBlockEntity.SLOT_FUEL + 1, false)) {
				return ItemStack.EMPTY;
			}
		} else {
			return ItemStack.EMPTY;
		}
		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		if (stack.getCount() == original.getCount()) {
			return ItemStack.EMPTY;
		}
		slot.onTake(player, stack);
		return original;
	}
}
