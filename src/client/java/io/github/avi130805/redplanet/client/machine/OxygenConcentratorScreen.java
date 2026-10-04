package io.github.avi130805.redplanet.client.machine;

import io.github.avi130805.redplanet.machine.OxygenConcentratorMenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

/**
 * The concentrator on vanilla's furnace layout (vanilla's own GUI texture and progress sprites, referenced, not copied):
 * the flame shows the fuel burning, the arrow how full the vessel is, and a line says when the air has no oxygen to
 * give.
 */
public class OxygenConcentratorScreen extends AbstractContainerScreen<OxygenConcentratorMenu> {
	private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/container/furnace.png");
	private static final Identifier LIT_SPRITE = Identifier.withDefaultNamespace("container/furnace/lit_progress");
	private static final Identifier ARROW_SPRITE = Identifier.withDefaultNamespace("container/furnace/burn_progress");

	public OxygenConcentratorScreen(OxygenConcentratorMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
	}

	@Override
	protected void init() {
		super.init();
		this.titleLabelX = (this.imageWidth - this.font.width(this.title)) / 2;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractBackground(g, mouseX, mouseY, a);
		int x = this.leftPos;
		int y = this.topPos;
		g.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, x, y, 0.0F, 0.0F, this.imageWidth, this.imageHeight, 256, 256);
		if (this.menu.isLit()) {
			int h = Mth.ceil(this.menu.litProgress() * 13.0F) + 1;
			g.blitSprite(RenderPipelines.GUI_TEXTURED, LIT_SPRITE, 14, 14, 0, 14 - h, x + 56, y + 36 + 14 - h, 14, h);
		}
		int w = Mth.ceil(this.menu.fillProgress() * 24.0F);
		g.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW_SPRITE, 24, 16, 0, 0, x + 79, y + 34, w, 16);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int xm, int ym) {
		super.extractLabels(g, xm, ym);
		Component status = !this.menu.airHasOxygen() ? Component.translatable("container.redplanet.oxygen_concentrator.no_air")
			: Component.translatable("container.redplanet.oxygen_concentrator.fill", Math.round(this.menu.fillProgress() * 100.0F));
		int color = this.menu.airHasOxygen() ? 0xFF404040 : 0xFFB02020;
		g.text(this.font, status, this.imageWidth - 8 - this.font.width(status), this.inventoryLabelY, color, false);
	}
}
