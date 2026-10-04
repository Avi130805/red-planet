package io.github.avi130805.redplanet.suit;

import java.util.Locale;
import java.util.function.Consumer;

import io.github.avi130805.redplanet.registry.RPSounds;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * An item that holds oxygen: the suit's life-support torso, or a spare canister. Its bar shows how full it is, its
 * tooltip the kilograms left and how long that lasts. A canister used in the hand tops up the worn suit.
 */
public class OxygenItem extends Item {
	private static final int BAR_COLOR = 0x6EC6FF;
	private static final int LOW_BAR_COLOR = 0xFF5A3C;
	private final boolean canister;

	public OxygenItem(Properties properties, boolean canister) {
		super(properties);
		this.canister = canister;
	}

	@Override
	public boolean isBarVisible(ItemStack stack) {
		Oxygen o = SpaceSuit.oxygen(stack);
		return o != null && !o.isFull() || stack.isDamaged();
	}

	@Override
	public int getBarWidth(ItemStack stack) {
		Oxygen o = SpaceSuit.oxygen(stack);
		return o == null ? super.getBarWidth(stack) : Mth.clamp(Math.round(13.0F * o.fraction()), 0, 13);
	}

	@Override
	public int getBarColor(ItemStack stack) {
		Oxygen o = SpaceSuit.oxygen(stack);
		return o == null ? super.getBarColor(stack) : o.fraction() < SpaceSuit.LOW_FRACTION ? LOW_BAR_COLOR : BAR_COLOR;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		Oxygen o = SpaceSuit.oxygen(stack);
		if (o == null) {
			return;
		}
		builder.accept(Component.translatable("item.redplanet.oxygen.amount", String.format(Locale.ROOT, "%.2f", o.kg()),
			String.format(Locale.ROOT, "%.2f", o.capacityKg())).withStyle(o.fraction() < SpaceSuit.LOW_FRACTION ? ChatFormatting.RED : ChatFormatting.AQUA));
		builder.accept(Component.translatable("item.redplanet.oxygen.duration", minutesOfPlay(o.kg())).withStyle(ChatFormatting.GRAY));
		if (this.canister) {
			builder.accept(Component.translatable("item.redplanet.oxygen_canister.hint").withStyle(ChatFormatting.DARK_GRAY));
		}
	}

	/** Minutes of play an amount of oxygen lasts one person, rounded down. */
	public static int minutesOfPlay(float kg) {
		return (int) Math.floor(kg / SpaceSuit.USE_KG_PER_TICK / 1200.0F);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!this.canister) {
			return super.use(level, player, hand);
		}
		ItemStack canister = player.getItemInHand(hand);
		Oxygen in = SpaceSuit.oxygen(canister);
		Oxygen suit = SpaceSuit.oxygen(SpaceSuit.torso(player));
		if (in == null || in.isEmpty() || suit == null || suit.isFull()) {
			if (!level.isClientSide()) {
				player.sendOverlayMessage(Component.translatable(suit == null ? "item.redplanet.oxygen_canister.no_suit"
					: in == null || in.isEmpty() ? "item.redplanet.oxygen_canister.empty" : "item.redplanet.oxygen_canister.suit_full"));
			}
			return InteractionResult.FAIL;
		}
		if (!level.isClientSide()) {
			float moved = SpaceSuit.fill(SpaceSuit.torso(player), Math.min(in.kg(), suit.room()));
			SpaceSuit.drain(canister, moved);
			level.playSound(null, player.getX(), player.getY(), player.getZ(), RPSounds.SUIT_HELMET_SEAL, SoundSource.PLAYERS, 0.8F, 1.3F);
			player.sendOverlayMessage(Component.translatable("item.redplanet.oxygen_canister.transferred",
				String.format(Locale.ROOT, "%.2f", moved)));
		}
		return InteractionResult.SUCCESS;
	}
}
