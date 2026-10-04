package io.github.avi130805.redplanet.mixin.client;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;

import io.github.avi130805.redplanet.client.starship.interlude.InterludeController;
import io.github.avi130805.redplanet.client.starship.interlude.InterludeScreen;
import io.github.avi130805.redplanet.starship.entity.VehicleEntity;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.client.GameNarrator;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * Two touches on the client's packet handling:
 * <ul>
 * <li>If a flight's world change arrives before the interlude opened, vanilla opens its own loading screen. Swap in the
 * interlude instead, so the arrival still shows the approach rather than "Downloading terrain".</li>
 * <li>Vanilla says "Press Shift to dismount" whenever the player is put on a vehicle, which happens again when the ship
 * arrives in the other world, mid-flight, when nobody can unbuckle. The ship gives its own boarding message.</li>
 * </ul>
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
	@Shadow
	private @Nullable LevelLoadTracker levelLoadTracker;

	@ModifyArg(method = "startWaitingForNewLevel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setScreenAndShow(Lnet/minecraft/client/gui/screens/Screen;)V"))
	private Screen redplanet$interludeInsteadOfLoading(Screen vanilla) {
		if (InterludeController.expectingArrival() && this.levelLoadTracker != null) {
			return new InterludeScreen(this.levelLoadTracker, true);
		}
		return vanilla;
	}

	@WrapWithCondition(method = "handleSetEntityPassengersPacket",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Hud;setOverlayMessage(Lnet/minecraft/network/chat/Component;Z)V"))
	private boolean redplanet$noMountHintForShips(Hud hud, Component message, boolean animate, @Local(ordinal = 0) Entity vehicle) {
		return !(vehicle instanceof VehicleEntity);
	}

	@WrapWithCondition(method = "handleSetEntityPassengersPacket",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/GameNarrator;saySystemNow(Lnet/minecraft/network/chat/Component;)V"))
	private boolean redplanet$noMountNarrationForShips(GameNarrator narrator, Component message, @Local(ordinal = 0) Entity vehicle) {
		return !(vehicle instanceof VehicleEntity);
	}
}
