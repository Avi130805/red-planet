package io.github.avi130805.redplanet.client.sky;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import io.github.avi130805.redplanet.client.config.RedPlanetClientConfig;
import io.github.avi130805.redplanet.mars.MarsConditions;
import io.github.avi130805.redplanet.mars.PlanetSettings;
import io.github.avi130805.redplanet.mars.astro.MarsAstronomy;
import io.github.avi130805.redplanet.mars.astro.MarsSkyModel;
import io.github.avi130805.redplanet.mars.geo.MarsProjection;
import io.github.avi130805.redplanet.mars.weather.MarsWeather;
import io.github.avi130805.redplanet.registry.RPDimensions;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.attribute.AmbientParticle;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.phys.Vec3;

/**
 * The client side of the Mars sky.
 *
 * <ul>
 * <li>Every frame, computes where the Sun, Phobos, Deimos, Earth, the Moon and the stars are for the camera's
 * latitude ({@link MarsAstronomy}) and attaches it to vanilla's sky render state for {@code SkyRendererMixin}.</li>
 * <li>Adds environment-attribute layers on Mars that set the sky and fog colour, star brightness, sky light and
 * fog distance from the real Sun altitude at the camera and the local dust ({@link MarsSkyModel}), overriding the
 * sol timeline's equatorial day/night (which still drives server-side light levels), and switch off vanilla's
 * sunrise tint (the aureole replaces it).</li>
 * </ul>
 */
public final class MarsSkyClient {
	private MarsSkyClient() {
	}

	public static void init() {
		LevelExtractionEvents.END_EXTRACTION.register(context -> {
			ClientLevel level = context.level();
			if (!RPDimensions.isMars(level)) {
				return;
			}
			float partial = context.deltaTracker().getGameTimeDeltaPartialTick(false);
			Vec3 cam = context.camera().position();
			context.levelState().skyRenderState.setData(MarsSkyData.KEY, compute(level, cam, partial));
		});
	}

	static MarsSkyData compute(ClientLevel level, Vec3 cam, float partial) {
		PlanetSettings s = PlanetSettings.of(level);
		double lat = MarsProjection.latitude(cam.z);
		MarsAstronomy.Sky sky = MarsAstronomy.compute(MarsConditions.clockTicks(level), partial, lat, s.startLs(), s.yearCompression(),
			s.earthPhaseAtStartDeg(), s.moonPhaseSeed());
		double tau = MarsWeather.tauAt(level, cam.x, cam.z);
		MarsSkyModel.Look look = MarsSkyModel.compute(sky.sunAltitudeDeg(), tau);
		double[] r = sky.starRotation();
		// Row-major array -> JOML (constructor arguments are column by column).
		Matrix3f rot = new Matrix3f(
			(float) r[0], (float) r[3], (float) r[6],
			(float) r[1], (float) r[4], (float) r[7],
			(float) r[2], (float) r[5], (float) r[8]);
		return new MarsSkyData(
			vec(sky.sun()), (float) sky.sunAngularDiameterDeg(),
			vec(sky.phobos()), (float) sky.phobosAngularDiameterDeg(), (float) sky.phobosIllumination(), sky.phobosEclipsed(),
			vec(sky.deimos()), (float) sky.deimosIllumination(), sky.deimosEclipsed(),
			vec(sky.earth()), (float) sky.earthMagnitude(), vec(sky.moon()),
			rot, look, (float) RedPlanetClientConfig.get().skyBodyScale());
	}

	/** Called from ClientLevelMixin when a client level builds its attribute system. */
	public static void addLayers(EnvironmentAttributeSystem.Builder builder, ClientLevel level) {
		if (!RPDimensions.isMars(level)) {
			return;
		}
		LookCache cache = new LookCache(level);
		builder.addPositionalLayer(EnvironmentAttributes.SKY_COLOR, (base, pos, biomes) -> color(cache.look(pos).sky()));
		builder.addPositionalLayer(EnvironmentAttributes.FOG_COLOR, (base, pos, biomes) -> color(cache.look(pos).fog()));
		builder.addPositionalLayer(EnvironmentAttributes.STAR_BRIGHTNESS, (base, pos, biomes) -> cache.look(pos).starBrightness());
		builder.addPositionalLayer(EnvironmentAttributes.SKY_LIGHT_FACTOR, (base, pos, biomes) -> cache.look(pos).skyLightFactor());
		builder.addPositionalLayer(EnvironmentAttributes.FOG_END_DISTANCE, (base, pos, biomes) -> Math.min(base, cache.look(pos).fogEnd()));
		builder.addPositionalLayer(EnvironmentAttributes.SKY_FOG_END_DISTANCE, (base, pos, biomes) ->
			Math.min(base, cache.look(pos).fogEnd() * 0.5F));
		// Vanilla tints the fog toward this colour whenever the camera faces the timeline's (equatorial, east-west) Sun,
		// and draws it as a fan on the horizon. Neither follows the real Sun, and the tint showed as blue silhouettes of
		// distant ridges against a grey dusk sky. On Mars the renderer's Sun-centred aureole carries the blue instead.
		builder.addPositionalLayer(EnvironmentAttributes.SUNRISE_SUNSET_COLOR, (base, pos, biomes) -> NO_SUNRISE_TINT);
		// Blowing dust in storms: up to ~4% of nearby air blocks spawn a dust mote per tick at tau 8.
		builder.addPositionalLayer(EnvironmentAttributes.AMBIENT_PARTICLES, (base, pos, biomes) -> {
			double tau = MarsWeather.tauAt(level, pos.x, pos.z);
			if (tau < 1.2) {
				return base;
			}
			List<AmbientParticle> list = new ArrayList<>(base);
			list.add(new AmbientParticle(STORM_DUST, (float) Math.min(0.045, 0.006 * (tau - 1.0))));
			return list;
		});
	}

	private static final DustParticleOptions STORM_DUST = new DustParticleOptions(0x9E6E48, 0.9F);
	private static final Vector4fc NO_SUNRISE_TINT = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);

	private static Vector3fc color(float[] rgb) {
		return new Vector3f(rgb[0], rgb[1], rgb[2]);
	}

	private static Vector3f vec(double[] d) {
		return new Vector3f((float) d[0], (float) d[1], (float) d[2]);
	}

	/**
	 * Evaluates the sky model once per camera block position, clock tick and dust optical depth: the layers above run
	 * every tick for several attributes. The optical depth is part of the key because a storm can change it while the
	 * clock stands still (or is set back to the same tick).
	 */
	private static final class LookCache {
		private final ClientLevel level;
		private long tick = Long.MIN_VALUE;
		private long cell = Long.MIN_VALUE;
		private double tau = Double.NaN;
		private MarsSkyModel.Look look = MarsSkyModel.compute(45.0, 0.5);

		LookCache(ClientLevel level) {
			this.level = level;
		}

		MarsSkyModel.Look look(Vec3 pos) {
			long now = MarsConditions.clockTicks(this.level);
			long key = ((long) Math.floor(pos.x) << 32) ^ (long) Math.floor(pos.z);
			double tauNow = MarsWeather.tauAt(this.level, pos.x, pos.z);
			if (now != this.tick || key != this.cell || tauNow != this.tau) {
				this.tick = now;
				this.cell = key;
				this.tau = tauNow;
				PlanetSettings s = PlanetSettings.of(this.level);
				double alt = MarsAstronomy.sunAltitudeDeg(now, 0.0, MarsProjection.latitude(pos.z), s.startLs(), s.yearCompression());
				this.look = MarsSkyModel.compute(alt, tauNow);
			}
			return this.look;
		}
	}
}
