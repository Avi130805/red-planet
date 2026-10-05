package io.github.avi130805.redplanet.gametest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.MouseHandler;

/**
 * Gametests only: lets the trailer glide the cursor over a screen. Under Xvfb the real cursor never moves, and the GUI
 * reads its mouse position from these fields every frame.
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
	@Accessor("xpos")
	void redplanetTrailer$setXpos(double xpos);

	@Accessor("ypos")
	void redplanetTrailer$setYpos(double ypos);
}
