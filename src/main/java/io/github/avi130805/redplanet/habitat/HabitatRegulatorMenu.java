package io.github.avi130805.redplanet.habitat;

import io.github.avi130805.redplanet.registry.RPHabitat;
import io.github.avi130805.redplanet.suit.SpaceSuit;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** The regulator's screen: the habitat's state, and two slots to empty vessels into the stores or fill them from it. */
public class HabitatRegulatorMenu extends AbstractContainerMenu {
	public static final int DATA_COUNT = 5;
	private final Container container;
	private final ContainerData data;

	public HabitatRegulatorMenu(int containerId, Inventory inventory) {
		this(containerId, inventory, new SimpleContainer(2), new SimpleContainerData(DATA_COUNT));
	}

	public HabitatRegulatorMenu(int containerId, Inventory inventory, Container container, ContainerData data) {
		super(RPHabitat.HABITAT_REGULATOR_MENU, containerId);
		checkContainerSize(container, 2);
		checkContainerDataCount(data, DATA_COUNT);
		this.container = container;
		this.data = data;
		container.startOpen(inventory.player);
		this.addSlot(new VesselSlot(container, HabitatRegulatorBlockEntity.SLOT_DRAIN, 134, 20));
		this.addSlot(new VesselSlot(container, HabitatRegulatorBlockEntity.SLOT_FILL, 134, 50));
		this.addStandardInventorySlots(inventory, 8, 84);
		this.addDataSlots(data);
	}

	public int volume() {
		return this.data.get(0);
	}

	public boolean sealed() {
		return this.data.get(1) != 0;
	}

	public float pressure() {
		return this.data.get(2) / 1000.0F;
	}

	public float storesKg() {
		return this.data.get(3) / 10.0F;
	}

	public int crew() {
		return this.data.get(4);
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
		if (index < 2) {
			if (!this.moveItemStackTo(stack, 2, this.slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else {
			var o = SpaceSuit.oxygen(stack);
			if (o == null) {
				return ItemStack.EMPTY;
			}
			int target = o.isEmpty() ? HabitatRegulatorBlockEntity.SLOT_FILL : HabitatRegulatorBlockEntity.SLOT_DRAIN;
			if (!this.moveItemStackTo(stack, target, target + 1, false)) {
				return ItemStack.EMPTY;
			}
		}
		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		return stack.getCount() == original.getCount() ? ItemStack.EMPTY : original;
	}

	private static final class VesselSlot extends Slot {
		VesselSlot(Container container, int slot, int x, int y) {
			super(container, slot, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return SpaceSuit.oxygen(stack) != null;
		}

		@Override
		public int getMaxStackSize() {
			return 1;
		}
	}
}
