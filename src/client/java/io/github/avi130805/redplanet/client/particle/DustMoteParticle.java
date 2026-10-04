package io.github.avi130805.redplanet.client.particle;

import io.github.avi130805.redplanet.mars.weather.MarsWeather;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;

/**
 * A mote of airborne Mars dust (ochre, iron-oxide stained; the hue never shifts, only the brightness). Motes drift
 * downwind faster the dustier the air, and settle slowly: Martian dust is a few micrometres across and stays aloft
 * for weeks. A spawner may pass a velocity in the auxiliary arguments (dust devils spin theirs); otherwise the mote
 * takes the local wind.
 */
public class DustMoteParticle extends SingleQuadParticle {
	/** Mars dust colour (sRGB), from the storm fog tone. */
	private static final float R = 0.64F;
	private static final float G = 0.45F;
	private static final float B = 0.30F;

	private final float maxAlpha;

	protected DustMoteParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, SpriteSet sprites,
			boolean puff) {
		super(level, x, y, z, sprites.get(level.getRandom()));
		this.xd = xd;
		this.yd = yd;
		this.zd = zd;
		float shade = 0.8F + this.random.nextFloat() * 0.35F;
		this.rCol = R * shade;
		this.gCol = G * shade;
		this.bCol = B * shade;
		this.alpha = 0.0F;
		if (puff) {
			// A dust devil's or storm front's puff: big, soft, short-lived, slowing as it spins out.
			this.friction = 0.93F;
			this.gravity = -0.002F;
			this.hasPhysics = false;
			this.maxAlpha = 0.35F + this.random.nextFloat() * 0.35F;
			this.quadSize = 0.8F + this.random.nextFloat() * 1.6F;
			this.lifetime = 20 + this.random.nextInt(22);
		} else {
			this.friction = 0.985F;
			this.gravity = 0.003F;
			this.hasPhysics = true;
			this.maxAlpha = 0.55F + this.random.nextFloat() * 0.35F;
			this.quadSize = 0.025F + this.random.nextFloat() * 0.05F;
			this.lifetime = 60 + this.random.nextInt(100);
		}
	}

	@Override
	public void tick() {
		super.tick();
		// Fade in quickly and out over the last third of its life.
		float in = Math.min(1.0F, this.age / 6.0F);
		float out = Math.min(1.0F, (this.lifetime - this.age) / (this.lifetime / 3.0F));
		this.alpha = this.maxAlpha * Math.max(0.0F, Math.min(in, out));
	}

	@Override
	protected SingleQuadParticle.Layer getLayer() {
		return SingleQuadParticle.Layer.TRANSLUCENT;
	}

	public static class Provider implements ParticleProvider<SimpleParticleType> {
		private final SpriteSet sprites;

		public Provider(SpriteSet sprites) {
			this.sprites = sprites;
		}

		@Override
		public Particle createParticle(SimpleParticleType options, ClientLevel level, double x, double y, double z, double xAux, double yAux,
				double zAux, RandomSource random) {
			if (xAux != 0.0 || yAux != 0.0 || zAux != 0.0) {
				return new DustMoteParticle(level, x, y, z, xAux, yAux, zAux, this.sprites, false);
			}
			// The prevailing wind, stronger in dusty air: ~0.03 blocks/tick in clear weather, ~0.15 in a global storm.
			double tau = MarsWeather.tauAt(level, x, z);
			double speed = 0.02 + 0.015 * Math.min(tau, 9.0);
			double heading = 0.35 + 0.5 * (random.nextDouble() - 0.5);
			return new DustMoteParticle(level, x, y, z, Math.cos(heading) * speed, (random.nextDouble() - 0.45) * 0.01,
				Math.sin(heading) * speed, this.sprites, false);
		}
	}

	/** {@code redplanet:dust_puff}: the spawner always passes the velocity. */
	public static class PuffProvider implements ParticleProvider<SimpleParticleType> {
		private final SpriteSet sprites;

		public PuffProvider(SpriteSet sprites) {
			this.sprites = sprites;
		}

		@Override
		public Particle createParticle(SimpleParticleType options, ClientLevel level, double x, double y, double z, double xAux, double yAux,
				double zAux, RandomSource random) {
			return new DustMoteParticle(level, x, y, z, xAux, yAux, zAux, this.sprites, true);
		}
	}
}
