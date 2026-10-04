package io.github.avi130805.redplanet.gametest.mixin;

import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.renderpearl.backend.opengl.GlBackend;

/**
 * Test environment only (the gametest mod never ships). Minecraft asks SDL for an sRGB-capable framebuffer, but
 * Xvfb's GLX offers no sRGB visuals, so window creation fails with "Couldn't find matching GLX visual". With the
 * {@code redplanet.gametest.noSrgbFramebuffer} system property set, this drops that one request just before the
 * window is created. Screenshots read the game's own render target, so they are unaffected.
 */
@Mixin(GlBackend.class)
abstract class GlBackendSrgbMixin {
	@Inject(method = "createWindow", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/sdl/SDLVideo;SDL_CreateWindow(Ljava/lang/CharSequence;IIJ)J"))
	private void redplanet$noSrgbFramebuffer(String title, int width, int height, long flags, CallbackInfoReturnable<Long> cir) {
		if (Boolean.getBoolean("redplanet.gametest.noSrgbFramebuffer")) {
			SDLVideo.SDL_GL_SetAttribute(22, 0); // SDL_GL_FRAMEBUFFER_SRGB_CAPABLE
		}
	}
}
