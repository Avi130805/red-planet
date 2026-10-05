package io.github.avi130805.redplanet.gametest.client.trailer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;

import io.github.avi130805.redplanet.RedPlanet;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;

import org.jspecify.annotations.Nullable;

/**
 * Films trailer shots at 1920x1080 and 30 frames a second, deterministically. The world is frozen ({@code /tick
 * freeze}) and stepped one tick at a time; between steps each frame is rendered at its exact moment within the tick
 * (the partial tick), so motion is smooth although the game runs at 20 ticks a second. Frames go straight into ffmpeg
 * (an H.264 intermediate per shot, near-lossless), never through PNG files.
 *
 * <p>Rendering happens off the window size: the window renders small between captures, and each capture renders at
 * full size into the main render target and reads it back. The GUI keeps one layout through both sizes (480 x 270 GUI
 * pixels: scale 1 small, scale 4 at 1920 x 1080, the automatic GUI scale for 1080p), so open screens and the HUD fill
 * the frame as a player would see them.
 */
public final class Recorder {
	public static final int WIDTH = 1920;
	public static final int HEIGHT = 1080;
	public static final int FPS = 30;
	/** The window size between captures: small, since the client renders a frame on every wait. */
	private static final int IDLE_WIDTH = 480;
	private static final int IDLE_HEIGHT = 270;
	/** The GUI's width in GUI pixels at both window sizes. */
	private static final int GUI_WIDTH = 480;

	private final ClientGameTestContext context;
	private final TestSingleplayerContext sp;
	private final Path out;
	private boolean frozen;

	/** The camera for a frame, {@code t} seconds into the shot; null shows the game's own camera. */
	@FunctionalInterface
	public interface Director {
		TrailerCamera.@Nullable Pose frame(Minecraft mc, double t, float partialTick);
	}

	/**
	 * One take of a moment: its file name, its camera, and whether the HUD shows (null leaves it as it is). Several
	 * takes film the same moment from different cameras, each frame rendered once per take from the same frozen world.
	 */
	public record Take(String name, Director director, @Nullable Boolean hud) {
		public static Take of(String name, Director director) {
			return new Take(name, director, null);
		}

		public static Take withHud(String name, Director director) {
			return new Take(name, director, true);
		}

		public static Take withoutHud(String name, Director director) {
			return new Take(name, director, false);
		}
	}

	public Recorder(ClientGameTestContext context, TestSingleplayerContext sp, Path out) {
		this.context = context;
		this.sp = sp;
		this.out = out;
		try {
			Files.createDirectories(out);
		} catch (IOException e) {
			throw new IllegalStateException("Could not create " + out, e);
		}
	}

	public Path out() {
		return this.out;
	}

	public void freeze() {
		if (!this.frozen) {
			this.sp.getServer().runCommand("tick freeze");
			this.context.waitFor(mc -> mc.level != null && mc.level.tickRateManager().isFrozen(), 200);
			this.frozen = true;
		}
	}

	public void unfreeze() {
		if (this.frozen) {
			this.sp.getServer().runCommand("tick unfreeze");
			this.context.waitFor(mc -> mc.level != null && !mc.level.tickRateManager().isFrozen(), 200);
			this.frozen = false;
		}
	}

	/** Advances the frozen world by one tick, on the server and then on the client. */
	public void step() {
		long before = this.context.computeOnClient(mc -> mc.level == null ? 0L : mc.level.getGameTime());
		this.sp.getServer().runCommand("tick step 1");
		this.context.waitFor(mc -> mc.level != null && mc.level.getGameTime() > before && !mc.level.tickRateManager().isSteppingForward(), 400);
		// One more client loop so the entity updates from that server tick are in.
		this.context.waitTick();
	}

	/**
	 * Films {@code seconds} of a shot into {@code <name>.mp4}, stepping the frozen world as the frames require.
	 * Returns the file.
	 */
	public Path record(String name, double seconds, Director director) {
		return this.record(seconds, Take.of(name, director)).getFirst();
	}

	/**
	 * Films {@code seconds} of one moment as several takes, {@code <take>.mp4} each, stepping the frozen world as the
	 * frames require. Returns the files, in the takes' order.
	 */
	public List<Path> record(double seconds, Take... takes) {
		this.freeze();
		int frames = (int) Math.round(seconds * FPS);
		long start = System.nanoTime();
		List<Process> encoders = new ArrayList<>();
		List<Path> files = new ArrayList<>();
		for (Take take : takes) {
			Path file = this.out.resolve(take.name() + ".mp4");
			files.add(file);
			try {
				encoders.add(new ProcessBuilder(List.of("ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24",
					"-s", WIDTH + "x" + HEIGHT, "-r", Integer.toString(FPS), "-i", "-", "-c:v", "libx264", "-preset", "medium", "-crf", "12",
					"-pix_fmt", "yuv420p", "-movflags", "+faststart", file.toString()))
					.redirectErrorStream(true)
					.redirectOutput(this.out.resolve(take.name() + ".ffmpeg.log").toFile())
					.start());
			} catch (IOException e) {
				encoders.forEach(Process::destroy);
				throw new IllegalStateException("Could not start ffmpeg", e);
			}
		}
		String names = String.join("+", Arrays.stream(takes).map(Take::name).toList());
		Boolean hudBefore = this.context.computeOnClient(mc -> !mc.gui.hud.isHidden());
		int stepped = 0;
		try {
			for (int f = 0; f < frames; f++) {
				double t = f / (double) FPS;
				double ticks = t * 20.0;
				int tick = (int) Math.floor(ticks + 1.0E-9);
				while (stepped < tick) {
					this.step();
					stepped++;
				}
				float partial = (float) Math.max(0.0, Math.min(1.0, ticks - tick));
				for (int k = 0; k < takes.length; k++) {
					Take take = takes[k];
					encoders.get(k).getOutputStream().write(this.capture(take.director(), take.hud() == null ? hudBefore : take.hud(), t, partial));
				}
				if (f % 30 == 0) {
					double elapsed = (System.nanoTime() - start) / 1.0E9;
					RedPlanet.LOGGER.info("Trailer shot {}: frame {}/{} ({} s elapsed)", names, f, frames,
						String.format(Locale.ROOT, "%.0f", elapsed));
				}
			}
			for (Process encoder : encoders) {
				encoder.getOutputStream().close();
			}
		} catch (IOException e) {
			encoders.forEach(Process::destroy);
			throw new IllegalStateException("Writing frames to ffmpeg failed for " + names, e);
		} finally {
			TrailerCamera.set(null);
			this.context.runOnClient(mc -> setHud(mc, hudBefore));
		}
		for (int k = 0; k < takes.length; k++) {
			try {
				int code = encoders.get(k).waitFor();
				if (code != 0) {
					throw new IllegalStateException("ffmpeg exited with " + code + " for " + takes[k].name() + "; see " + takes[k].name() + ".ffmpeg.log");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
			RedPlanet.LOGGER.info("Trailer shot {}: {} frames in {} s -> {}", takes[k].name(), frames,
				String.format(Locale.ROOT, "%.0f", (System.nanoTime() - start) / 1.0E9), files.get(k));
		}
		return files;
	}

	/** Renders one frame at full size at the given partial tick and returns it as packed RGB bytes, top row first. */
	private byte[] capture(Director director, boolean hud, double t, float partial) {
		CompletableFuture<byte[]> result = new CompletableFuture<>();
		this.context.runOnClient(mc -> {
			setHud(mc, hud);
			TrailerClock.partial(partial);
			TrailerCamera.set(director.frame(mc, t, partial));
			resize(mc, WIDTH, HEIGHT);
			DeltaTracker delta = new FixedDelta(partial);
			mc.gameRenderer.update(delta);
			mc.gameRenderer.extract(delta, true);
			mc.gameRenderer.render();
			RenderSystem.getDevice().createCommandEncoder().submit();
			Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
				try {
					result.complete(rgb(image));
				} catch (Throwable e) {
					result.completeExceptionally(e);
				} finally {
					image.close();
				}
			});
			resize(mc, IDLE_WIDTH, IDLE_HEIGHT);
			TrailerClock.partial(0.0F);
		});
		// The read-back completes on a later frame. The world is frozen, so waiting changes nothing in it.
		while (!result.isDone()) {
			this.context.waitTick();
		}
		return result.join();
	}

	/**
	 * Makes the window render small between captures (call once at the start): under software rendering every wait
	 * redraws the whole view, and a large window makes waiting for terrain take many times longer.
	 */
	public void idleSmall() {
		this.context.runOnClient(mc -> resize(mc, IDLE_WIDTH, IDLE_HEIGHT));
	}

	private static void setHud(Minecraft mc, boolean visible) {
		if (mc.gui.hud.isHidden() == visible) {
			mc.gui.hud.toggle(); // F1
		}
	}

	private static void resize(Minecraft mc, int width, int height) {
		Window window = mc.getWindow();
		if (window.getWidth() != width || window.getHeight() != height) {
			window.setWidth(width);
			window.setHeight(height);
			mc.gameRenderer.resize(width, height);
		}
		// The GUI scale is set here rather than through Minecraft.resizeGui, which would pick it from the options and
		// re-sync the mouse to the real window.
		window.setGuiScale(Math.max(1, width / GUI_WIDTH));
		Screen screen = mc.gui.screen();
		if (screen != null && (screen.width != window.getGuiScaledWidth() || screen.height != window.getGuiScaledHeight())) {
			screen.resize(window.getGuiScaledWidth(), window.getGuiScaledHeight());
		}
	}

	private static byte[] rgb(NativeImage image) {
		int[] abgr = image.getPixelsABGR();
		byte[] rgb = new byte[abgr.length * 3];
		for (int i = 0, j = 0; i < abgr.length; i++) {
			int p = abgr[i];
			rgb[j++] = (byte) p; // red is the low byte of ABGR
			rgb[j++] = (byte) (p >>> 8);
			rgb[j++] = (byte) (p >>> 16);
		}
		return rgb;
	}

	/** A delta tracker frozen at one partial tick, as the frame's moment within the current tick. */
	private record FixedDelta(float partial) implements DeltaTracker {
		@Override
		public float getGameTimeDeltaTicks() {
			return 0.0F;
		}

		@Override
		public float getGameTimeDeltaPartialTick(boolean ignoreFrozenGame) {
			return this.partial;
		}

		@Override
		public float getRealtimeDeltaTicks() {
			return 0.0F;
		}
	}
}
