package io.github.avi130805.redplanet.mars.weather;

import java.util.List;

import io.github.avi130805.redplanet.registry.RPParticles;
import io.github.avi130805.redplanet.registry.RPSounds;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A Martian dust devil: a convective vortex that lifts dust into a tall, slowly drifting column (Spirit watched
 * hundreds of them cross Gusev crater). Drawn at human scale (docs/SCIENCE.md section 11): 6-40 blocks across, tens
 * of blocks tall, drifting a few blocks per second.
 *
 * <p>It has no body. The client draws it as spiralling dust motes, and the server moves it across the ground and
 * swirls loose items. Mars' air is too thin to knock a player over, so it leaves living things alone. Dust devils
 * aren't saved with the world; {@link DustDevils} raises new ones.
 */
public class DustDevil extends Entity {
	private static final EntityDataAccessor<Float> RADIUS = SynchedEntityData.defineId(DustDevil.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(DustDevil.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> STRENGTH = SynchedEntityData.defineId(DustDevil.class, EntityDataSerializers.FLOAT);
	/** Ticks over which a dust devil spins up and dies down. */
	private static final int FADE_TICKS = 120;

	private int lifetime = 1200;
	private double driftX;
	private double driftZ;
	private float spinPhase;

	public DustDevil(EntityType<? extends DustDevil> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	/** Sets up a new dust devil: radius and height in blocks, drift in blocks per tick, lifetime in ticks. */
	public void configure(float radius, float height, double driftX, double driftZ, int lifetime) {
		this.entityData.set(RADIUS, radius);
		this.entityData.set(HEIGHT, height);
		this.driftX = driftX;
		this.driftZ = driftZ;
		this.lifetime = lifetime;
	}

	public float radius() {
		return this.entityData.get(RADIUS);
	}

	public float columnHeight() {
		return this.entityData.get(HEIGHT);
	}

	/** 0..1: spinning up, at full strength, dying down. */
	public float strength() {
		return this.entityData.get(STRENGTH);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder entityData) {
		entityData.define(RADIUS, 8.0F);
		entityData.define(HEIGHT, 40.0F);
		entityData.define(STRENGTH, 0.0F);
	}

	@Override
	public void tick() {
		super.tick();
		if (this.level() instanceof ServerLevel level) {
			this.serverTick(level);
		} else {
			this.clientTick();
		}
	}

	private void serverTick(ServerLevel level) {
		int age = this.tickCount;
		if (age > this.lifetime) {
			this.discard();
			return;
		}
		float strength = Math.min(1.0F, Math.min(age, this.lifetime - age) / (float) FADE_TICKS);
		this.entityData.set(STRENGTH, Math.max(0.0F, strength));

		// Wander: the drift heading turns slowly; the column hugs the ground it crosses.
		double turn = (this.random.nextDouble() - 0.5) * 0.04;
		double c = Math.cos(turn);
		double s = Math.sin(turn);
		double dx = this.driftX * c - this.driftZ * s;
		double dz = this.driftX * s + this.driftZ * c;
		this.driftX = dx;
		this.driftZ = dz;
		double x = this.getX() + dx;
		double z = this.getZ() + dz;
		int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
		this.setPos(x, ground, z);

		// Swirl loose items: tangential spin, a little lift, and a pull toward the core.
		float r = this.radius();
		AABB box = new AABB(x - r, ground - 1, z - r, x + r, ground + Math.min(this.columnHeight(), 24.0F), z + r);
		List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box);
		for (ItemEntity item : items) {
			double ox = item.getX() - x;
			double oz = item.getZ() - z;
			double d = Math.sqrt(ox * ox + oz * oz);
			if (d > r || d < 1.0E-3) {
				continue;
			}
			double core = 1.0 - d / r;
			Vec3 v = item.getDeltaMovement();
			double spin = 0.08 * strength * core;
			item.setDeltaMovement(v.x + (-oz / d) * spin - ox / d * 0.02 * core, v.y + 0.035 * strength * core,
				v.z + (ox / d) * spin - oz / d * 0.02 * core);
			item.needsSync = true;
		}
	}

	private void clientTick() {
		float strength = this.strength();
		if (strength <= 0.01F) {
			return;
		}
		float r = this.radius();
		float h = this.columnHeight();
		this.spinPhase += 0.35F;
		// The column: puffs on a slowly flaring spiral, dense at the bottom and thinning with height, so it reads as a
		// solid tan funnel from across the plain (always-visible particles ignore vanilla's 32-block cutoff).
		int column = Math.round((18 + r * 2.5F) * strength);
		for (int i = 0; i < column; i++) {
			double frac = Math.pow(this.random.nextDouble(), 1.6);
			double y = this.getY() + frac * h;
			double ringRadius = r * (0.25 + 0.75 * frac) * (0.75 + 0.5 * this.random.nextDouble()) * 0.5;
			this.puff(ringRadius, y, 0.22 + 0.18 * strength, 0.04 + 0.1 * this.random.nextDouble());
		}
		// The debris skirt: a wide, low cloud of dust thrown out around the base.
		int skirt = Math.round((6 + r) * strength);
		for (int i = 0; i < skirt; i++) {
			double y = this.getY() + this.random.nextDouble() * 2.5;
			this.puff(r * (0.5 + 0.6 * this.random.nextDouble()), y, 0.12, 0.01 + 0.03 * this.random.nextDouble());
		}
		if (this.tickCount % 80 == 0) {
			this.level().playLocalSound(this.getX(), this.getY(), this.getZ(), RPSounds.MARS_DUST_DEVIL, SoundSource.WEATHER, 0.7F * strength,
				0.85F + this.random.nextFloat() * 0.3F, false);
		}
	}

	/** One puff on the vortex: tangential (counter-clockwise from above) at the given ring radius, plus an updraft. */
	private void puff(double ringRadius, double y, double spin, double updraft) {
		double angle = this.spinPhase + this.random.nextDouble() * Math.PI * 2.0;
		double px = this.getX() + Math.cos(angle) * ringRadius;
		double pz = this.getZ() + Math.sin(angle) * ringRadius;
		this.level().addAlwaysVisibleParticle(RPParticles.DUST_PUFF, true, px, y, pz, -Math.sin(angle) * spin, updraft, Math.cos(angle) * spin);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		this.entityData.set(RADIUS, input.getFloatOr("radius", 8.0F));
		this.entityData.set(HEIGHT, input.getFloatOr("height", 40.0F));
		this.lifetime = input.getIntOr("lifetime", 1200);
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putFloat("radius", this.radius());
		output.putFloat("height", this.columnHeight());
		output.putInt("lifetime", this.lifetime);
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public PushReaction getPistonPushReaction() {
		return PushReaction.IGNORE_ENTITY;
	}

	@Override
	public boolean isIgnoringBlockTriggers() {
		return true;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 256.0 * 256.0;
	}
}
