package io.github.avi130805.redplanet.client.sky;

import io.github.avi130805.redplanet.mars.geo.MarsProjection;
import io.github.avi130805.redplanet.registry.RPDimensions;
import io.github.avi130805.redplanet.starship.flight.AltitudeMapping;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * The sky seen on the way up. Above the atmosphere's lower layers the daytime sky darkens toward black and the stars
 * come out, while the world below (which is far beyond the render distance up there) fades into the glow of the planet
 * under you: blue-white haze on Earth, butterscotch on Mars.
 *
 * <p>Camera height is turned back into real altitude with the flight's altitude mapping, then the sky's brightness falls
 * with the air above: exp(-h / H), with scale heights H of about 7 km for Earth's sky brightness and 11 km for Mars'
 * (docs/SCIENCE.md, section 3). The stars wait for a sky that is truly dark.
 */
public final class AltitudeSky {
	private static final Vector3fc SPACE = new Vector3f(0.0F, 0.0F, 0.012F);
	private static final Vector3fc EARTH_HAZE = new Vector3f(0.42F, 0.58F, 0.82F);
	private static final Vector3fc MARS_HAZE = new Vector3f(0.62F, 0.47F, 0.36F);

	private AltitudeSky() {
	}

	/** Called from ClientLevelMixin, after vanilla's and the Mars sky's layers. */
	public static void addLayers(EnvironmentAttributeSystem.Builder builder, ClientLevel level) {
		boolean mars = RPDimensions.isMars(level);
		double ground = mars ? MarsProjection.MIN_Y + MarsProjection.HEIGHT : level.getSeaLevel();
		double scaleKm = mars ? 11.0 : 7.0;
		Vector3fc haze = mars ? MARS_HAZE : EARTH_HAZE;
		builder.addPositionalLayer(EnvironmentAttributes.SKY_COLOR, (base, pos, biomes) -> mix(base, SPACE, space(pos, ground, scaleKm)));
		builder.addPositionalLayer(EnvironmentAttributes.FOG_COLOR, (base, pos, biomes) -> mix(base, haze, space(pos, ground, scaleKm)));
		builder.addPositionalLayer(EnvironmentAttributes.STAR_BRIGHTNESS, (base, pos, biomes) ->
			Math.max(base, 0.85F * stars(pos, ground, scaleKm)));
		builder.addPositionalLayer(EnvironmentAttributes.SUNRISE_SUNSET_COLOR, (base, pos, biomes) -> {
			float s = space(pos, ground, scaleKm);
			return s <= 0.0F ? base : new Vector4f(base.x(), base.y(), base.z(), base.w() * (1.0F - s));
		});
	}

	/** 0 near the ground, toward 1 above the sky's scale heights. */
	static float space(Vec3 pos, double ground, double scaleKm) {
		double blocks = pos.y - ground;
		if (blocks <= AltitudeMapping.DEFAULT.trueScaleMetres()) {
			return 0.0F;
		}
		double km = AltitudeMapping.DEFAULT.toMetres(blocks) / 1000.0;
		return (float) Mth.clamp(1.0 - Math.exp(-km / scaleKm), 0.0, 1.0);
	}

	/**
	 * Stars need a properly dark sky, not just a darker one: they fade in between 3.5 and 6.5 scale heights (about 25 to
	 * 45 km over Earth, 40 to 70 km over Mars), where the sky's brightness has fallen to a few percent and then to a
	 * fraction of a percent.
	 */
	static float stars(Vec3 pos, double ground, double scaleKm) {
		double blocks = pos.y - ground;
		if (blocks <= AltitudeMapping.DEFAULT.trueScaleMetres()) {
			return 0.0F;
		}
		double heights = AltitudeMapping.DEFAULT.toMetres(blocks) / 1000.0 / scaleKm;
		double k = Mth.clamp((heights - 3.5) / 3.0, 0.0, 1.0);
		return (float) (k * k * (3.0 - 2.0 * k));
	}

	private static Vector3fc mix(Vector3fc a, Vector3fc b, float t) {
		if (t <= 0.0F) {
			return a;
		}
		return new Vector3f(Mth.lerp(t, a.x(), b.x()), Mth.lerp(t, a.y(), b.y()), Mth.lerp(t, a.z(), b.z()));
	}
}
