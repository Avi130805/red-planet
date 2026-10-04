package io.github.avi130805.redplanet.client.habitat;

import java.util.Locale;

import io.github.avi130805.redplanet.habitat.HabitatRegulatorBlockEntity;
import io.github.avi130805.redplanet.habitat.HabitatRegulatorMenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

/**
 * The regulator's status panel: whether the habitat is sealed, its volume, pressure, the crew breathing inside and the
 * oxygen in the stores, plus the two vessel slots (empty a canister into the stores, or fill one from them).
 */
public class HabitatRegulatorScreen extends AbstractContainerScreen<HabitatRegulatorMenu> {
	private static final int PANEL = 0xFF1E2A30;
	private static final int TEAL = 0xFF5FE0D0;
	private static final int DIM = 0xFF8FA3AD;
	private static final int RED = 0xFFFF6A50;

	public HabitatRegulatorScreen(HabitatRegulatorMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractBackground(g, mouseX, mouseY, a);
		int x = this.leftPos;
		int y = this.topPos;
		// A vanilla-style container panel, drawn rather than borrowed.
		g.fill(x, y, x + this.imageWidth, y + this.imageHeight, 0xFF000000);
		g.fill(x + 1, y + 1, x + this.imageWidth - 1, y + this.imageHeight - 1, 0xFFFFFFFF);
		g.fill(x + 3, y + 3, x + this.imageWidth - 1, y + this.imageHeight - 1, 0xFF555555);
		g.fill(x + 3, y + 3, x + this.imageWidth - 3, y + this.imageHeight - 3, 0xFFC6C6C6);
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				this.slotFrame(g, x + 7 + col * 18, y + 83 + row * 18);
			}
		}
		for (int col = 0; col < 9; col++) {
			this.slotFrame(g, x + 7 + col * 18, y + 141);
		}
		// The LCD readout.
		g.fill(x + 7, y + 15, x + 126, y + 72, 0xFF373737);
		g.fill(x + 8, y + 16, x + 125, y + 71, PANEL);
		this.slotFrame(g, x + 133, y + 19);
		this.slotFrame(g, x + 133, y + 49);
		// Pressure bar.
		int barX = x + 12;
		int barY = y + 62;
		g.fill(barX, barY, barX + 108, barY + 4, 0x40FFFFFF);
		g.fill(barX, barY, barX + Math.round(108 * Mth.clamp(this.menu.pressure(), 0.0F, 1.0F)), barY + 4,
			this.menu.pressure() >= HabitatRegulatorBlockEntity.BREATHABLE_FRACTION ? TEAL : RED);
	}

	private void slotFrame(GuiGraphicsExtractor g, int x, int y) {
		g.fill(x, y, x + 18, y + 18, 0xFF373737);
		g.fill(x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF);
		g.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int xm, int ym) {
		super.extractLabels(g, xm, ym);
		int x = 12;
		int y = 20;
		boolean sealed = this.menu.sealed();
		Component state = sealed ? Component.translatable(this.menu.pressure() >= HabitatRegulatorBlockEntity.BREATHABLE_FRACTION
			? "container.redplanet.habitat_regulator.breathable" : "container.redplanet.habitat_regulator.pressurizing")
			: Component.translatable("container.redplanet.habitat_regulator.leak");
		g.text(this.font, state, x, y, sealed ? TEAL : RED, false);
		g.text(this.font, Component.translatable("container.redplanet.habitat_regulator.volume", this.menu.volume()), x, y + 10, DIM, false);
		g.text(this.font, Component.translatable("container.redplanet.habitat_regulator.pressure",
			String.format(Locale.ROOT, "%.0f", 101.3F * this.menu.pressure())), x, y + 20, DIM, false);
		g.text(this.font, Component.translatable("container.redplanet.habitat_regulator.stores",
			String.format(Locale.ROOT, "%.1f", this.menu.storesKg()), this.menu.crew()), x, y + 30, DIM, false);
		g.text(this.font, Component.translatable("container.redplanet.habitat_regulator.drain"), 152, 24, 0xFF404040, false);
		g.text(this.font, Component.translatable("container.redplanet.habitat_regulator.fill"), 152, 54, 0xFF404040, false);
	}
}
