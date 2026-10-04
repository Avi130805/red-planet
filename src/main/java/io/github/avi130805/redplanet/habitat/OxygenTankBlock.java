package io.github.avi130805.redplanet.habitat;

import java.util.Locale;
import java.util.function.Consumer;

import io.github.avi130805.redplanet.suit.Oxygen;
import io.github.avi130805.redplanet.suit.OxygenItem;
import io.github.avi130805.redplanet.suit.SpaceSuit;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import org.jspecify.annotations.Nullable;

/** The oxygen tank block. Its item shows the oxygen it holds. */
public class OxygenTankBlock extends BaseEntityBlock {
	public OxygenTankBlock(Properties properties) {
		super(properties);
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new OxygenTankBlockEntity(pos, state);
	}

	@Override
	protected boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
		return level.getBlockEntity(pos) instanceof OxygenTankBlockEntity tank ? tank.signal() : 0;
	}

	/** The tank as an item: a fill bar and the kilograms in the tooltip. */
	public static class TankItem extends BlockItem {
		public TankItem(Block block, Properties properties) {
			super(block, properties);
		}

		@Override
		public boolean isBarVisible(ItemStack stack) {
			Oxygen o = SpaceSuit.oxygen(stack);
			return o != null && !o.isFull();
		}

		@Override
		public int getBarWidth(ItemStack stack) {
			Oxygen o = SpaceSuit.oxygen(stack);
			return o == null ? 13 : Mth.clamp(Math.round(13.0F * o.fraction()), 0, 13);
		}

		@Override
		public int getBarColor(ItemStack stack) {
			return 0x6EC6FF;
		}

		@Override
		@SuppressWarnings("deprecation")
		public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
			Oxygen o = SpaceSuit.oxygen(stack);
			if (o != null) {
				builder.accept(Component.translatable("item.redplanet.oxygen.amount", String.format(Locale.ROOT, "%.1f", o.kg()),
					String.format(Locale.ROOT, "%.0f", o.capacityKg())).withStyle(ChatFormatting.AQUA));
				builder.accept(Component.translatable("item.redplanet.oxygen_tank.person_days", Math.round(o.kg() / 0.84F),
					OxygenItem.minutesOfPlay(o.kg()) / 60).withStyle(ChatFormatting.GRAY));
			}
		}
	}
}
