package io.github.avi130805.redplanet.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Steam and exhaust clouds around a launch, and boil-off vents. A cloud starts as a dense puff where it was spawned and
 * swells several times over as it drifts and slows, then thins out; the vanilla smoke sprites play through its life.
 */
public class SteamCloudParticle extends SingleQuadParticle {
	private final SpriteSet sprites;
	private final float startSize;
	private final float endSize;
	private final float maxAlpha;

	protected SteamCloudParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, SpriteSet sprites, boolean vent,
			float r, float g, float b) {
		super(level, x, y, z, sprites.first());
		this.sprites = sprites;
		this.xd = xd;
		this.yd = yd;
		this.zd = zd;
		this.hasPhysics = false;
		float shade = 0.86F + this.random.nextFloat() * 0.14F;
		this.rCol = r * shade;
		this.gCol = g * shade;
		this.bCol = b * shade;
		this.roll = this.oRoll = this.random.nextFloat() * Mth.TWO_PI;
		if (vent) {
			this.friction = 0.9F;
			this.gravity = -0.004F;
			this.startSize = 0.6F + this.random.nextFloat() * 0.6F;
			this.endSize = this.startSize * 3.0F;
			this.maxAlpha = 0.55F;
			this.lifetime = 30 + this.random.nextInt(25);
		} else {
			this.friction = 0.955F;
			this.gravity = -0.0015F;
			this.startSize = 2.5F + this.random.nextFloat() * 2.5F;
			this.endSize = this.startSize * (3.0F + this.random.nextFloat() * 2.0F);
			this.maxAlpha = 0.7F + this.random.nextFloat() * 0.25F;
			this.lifetime = 160 + this.random.nextInt(160);
		}
		this.quadSize = this.startSize;
		this.setSpriteFromAge(sprites);
	}

	@Override
	public void tick() {
		super.tick();
		if (!this.removed) {
			this.setSpriteFromAge(this.sprites);
			this.oRoll = this.roll;
			this.roll += 0.003F;
			float t = this.age / (float) this.lifetime;
			float in = Math.min(1.0F, this.age / 4.0F);
			float out = 1.0F - Mth.clamp((t - 0.55F) / 0.45F, 0.0F, 1.0F);
			this.alpha = this.maxAlpha * Math.min(in, out);
		}
	}

	@Override
	public float getQuadSize(float partialTick) {
		float t = Mth.clamp((this.age + partialTick) / this.lifetime, 0.0F, 1.0F);
		return Mth.lerp(1.0F - (1.0F - t) * (1.0F - t), this.startSize, this.endSize);
	}

	@Override
	protected SingleQuadParticle.Layer getLayer() {
		return SingleQuadParticle.Layer.TRANSLUCENT;
	}

	public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
		@Override
		public Particle createParticle(SimpleParticleType options, ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
				RandomSource random) {
			return new SteamCloudParticle(level, x, y, z, xd, yd, zd, this.sprites, false, 1.0F, 1.0F, 1.0F);
		}
	}

	/** Mars dust, the colour of the storm fog. */
	public record DustProvider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
		@Override
		public Particle createParticle(SimpleParticleType options, ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
				RandomSource random) {
			return new SteamCloudParticle(level, x, y, z, xd, yd, zd, this.sprites, false, 0.74F, 0.52F, 0.36F);
		}
	}

	public record VentProvider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
		@Override
		public Particle createParticle(SimpleParticleType options, ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
				RandomSource random) {
			return new SteamCloudParticle(level, x, y, z, xd, yd, zd, this.sprites, true, 1.0F, 1.0F, 1.0F);
		}
	}
}
