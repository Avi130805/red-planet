package io.github.avi130805.redplanet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/** Access to the furnace's remaining burn time, so a furnace can be put out where there is no oxygen. */
@Mixin(AbstractFurnaceBlockEntity.class)
public interface AbstractFurnaceAccessor {
	@Accessor("litTimeRemaining")
	int redplanet$getLitTimeRemaining();

	@Accessor("litTimeRemaining")
	void redplanet$setLitTimeRemaining(int ticks);
}
