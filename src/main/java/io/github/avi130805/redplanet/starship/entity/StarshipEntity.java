package io.github.avi130805.redplanet.starship.entity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.environment.Breathing;
import io.github.avi130805.redplanet.mars.geo.MarsProjection;
import io.github.avi130805.redplanet.registry.RPDimensions;
import io.github.avi130805.redplanet.registry.RPRegistries;
import io.github.avi130805.redplanet.registry.RPStarship;
import io.github.avi130805.redplanet.starship.flight.FlightKinematics;
import io.github.avi130805.redplanet.starship.flight.FlightProfile;
import io.github.avi130805.redplanet.starship.flight.FlightSegment;
import io.github.avi130805.redplanet.starship.flight.Pacing;
import io.github.avi130805.redplanet.starship.flight.TelemetryTrack;
import io.github.avi130805.redplanet.starship.geometry.StarshipGeometry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The ship: the upper stage, which carries the crew. Up to eight passengers ride in the crew cabin in the nose.
 *
 * <p>On the ground it stands on its legs or on top of a Super Heavy (stacked). A flight from Earth needs the stack; from
 * Mars the ship flies alone. Before hot staging the ship rides on the booster; after it, it follows its own telemetry.
 * When the flight reaches the transfer segment the ship leaves the world (the crew sees the interlude); at the first
 * descent phase it moves with its crew to the destination dimension, high above the landing site, and flies the
 * descent down to touchdown.
 */
public class StarshipEntity extends VehicleEntity implements Breathing.PressurizedVehicle {
	private static final EntityDataAccessor<Float> DATA_LEGS = SynchedEntityData.defineId(StarshipEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Boolean> DATA_STACKED = SynchedEntityData.defineId(StarshipEntity.class, EntityDataSerializers.BOOLEAN);
	/** Mission seconds over which the ship eases off the booster after hot staging (the stacked and free positions differ). */
	private static final double STAGING_BLEND_SECONDS = 20.0;

	private @Nullable UUID boosterId;
	/** Landing site of the current flight in the destination dimension (x, z); y is found on arrival. */
	private double siteX;
	private double siteZ;
	private boolean hasSite;
	private boolean siteRefined;
	/** The pad this ship last launched from on Earth, where it comes home to. */
	private @Nullable GlobalPos homePad;
	/** Hits taken on the ground: a few knock it back into an item (like a minecart). */
	private float damage;
	/** Set while the ship changes dimension: passengers are lifted out and put back, and must stay at their couches. */
	private boolean transferring;
	/** Crew who asked to skip the current phase; it is skipped once every player aboard has asked. */
	private final java.util.Set<UUID> skipVotes = new java.util.HashSet<>();
	private int skipVotePhase = -1;

	public StarshipEntity(EntityType<? extends StarshipEntity> type, Level level) {
		super(type, level);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_LEGS, 1.0F);
		builder.define(DATA_STACKED, false);
	}

	// ------------------------------------------------------------------------------------------------- state

	/** Resting leg extension: 1 standing on its legs, 0 stowed (stacked on a booster). */
	public float restingLegs() {
		return this.entityData.get(DATA_LEGS);
	}

	public boolean isStacked() {
		return this.entityData.get(DATA_STACKED);
	}

	public Optional<UUID> boosterId() {
		return Optional.ofNullable(this.boosterId);
	}

	/** Puts this ship on top of a booster (on the ground). */
	public void stackOn(SuperHeavyEntity booster) {
		this.boosterId = booster.getUUID();
		this.entityData.set(DATA_STACKED, true);
		this.entityData.set(DATA_LEGS, 0.0F);
		this.setYRot(booster.getYRot());
		this.setAzimuth(booster.azimuth());
		this.setPos(booster.position().add(0.0, StarshipGeometry.BOOSTER_HEIGHT, 0.0));
		booster.linkShip(this);
	}

	/** Stands the ship on its own legs (after landing, or when its booster is gone). */
	public void standOnLegs() {
		this.boosterId = null;
		this.entityData.set(DATA_STACKED, false);
		this.entityData.set(DATA_LEGS, 1.0F);
	}

	public Optional<GlobalPos> homePad() {
		return Optional.ofNullable(this.homePad);
	}

	@Override
	protected TelemetryTrack track(FlightProfile profile) {
		return profile.ship();
	}

	@Override
	protected Vec3 flightPosition(FlightProfile profile, double missionTime, Quaternionf attitudeOut) {
		double staging = profile.stagingTime();
		if (missionTime < staging) {
			return this.stackedPosition(profile, missionTime, attitudeOut);
		}
		Vec3 own = super.flightPosition(profile, missionTime, attitudeOut);
		double since = missionTime - staging;
		if (since < STAGING_BLEND_SECONDS && this.segment().orElse(FlightSegment.ASCENT) == FlightSegment.ASCENT) {
			// Ease from the position on top of the booster to the ship's own track (hot staging pulls the ship clear).
			Quaternionf stackedAttitude = new Quaternionf();
			Quaternionf ownAttitude = new Quaternionf(attitudeOut);
			Vec3 stackedAtStaging = this.stackedPosition(profile, staging, stackedAttitude);
			Vec3 ownAtStaging = super.flightPosition(profile, staging, new Quaternionf());
			double w = 1.0 - smooth(since / STAGING_BLEND_SECONDS);
			stackedAttitude.slerp(ownAttitude, (float) (1.0 - w), attitudeOut);
			return own.add(stackedAtStaging.subtract(ownAtStaging).scale(w));
		}
		return own;
	}

	/** On top of the booster: the booster's track, raised along its axis by the booster's height. */
	private Vec3 stackedPosition(FlightProfile profile, double missionTime, Quaternionf attitudeOut) {
		TelemetryTrack.Sample sample = profile.booster().sample(missionTime);
		attitudeOut.set(FlightKinematics.attitude(sample.pitchDeg(), this.azimuth()));
		Vector3d offset = FlightKinematics.offset(profile, sample, this.azimuth());
		Vector3f top = attitudeOut.transform(new Vector3f(0.0F, (float) StarshipGeometry.BOOSTER_HEIGHT, 0.0F));
		Vec3 ref = this.reference();
		return new Vec3(ref.x + offset.x + top.x, ref.y + offset.y + top.y, ref.z + offset.z + top.z);
	}

	private static double smooth(double x) {
		double t = Mth.clamp(x, 0.0, 1.0);
		return t * t * (3.0 - 2.0 * t);
	}

	// ------------------------------------------------------------------------------------------------- launch

	/** Why a launch can't go ahead, or empty if it can. */
	public Optional<Component> launchProblem(ServerLevel level, FlightProfile profile) {
		if (this.isFlying()) {
			return Optional.of(Component.translatable("starship.redplanet.launch.flying"));
		}
		if (profile.destination().equals(level.dimension())) {
			return Optional.of(Component.translatable("starship.redplanet.launch.same_world"));
		}
		if (level.getServer().getLevel(profile.destination()) == null) {
			return Optional.of(Component.translatable("starship.redplanet.launch.no_destination"));
		}
		if (profile.vehicle() == FlightProfile.Vehicle.STACK && this.booster(level).isEmpty()) {
			return Optional.of(Component.translatable("starship.redplanet.launch.needs_booster"));
		}
		if (profile.vehicle() == FlightProfile.Vehicle.SHIP && this.isStacked()) {
			return Optional.of(Component.translatable("starship.redplanet.launch.ship_only"));
		}
		return Optional.empty();
	}

	/**
	 * Launches the ship (and its booster) on a profile toward a landing site (x, z in the destination dimension; null
	 * for the default site). Returns false if the launch isn't possible.
	 */
	public boolean launch(ServerLevel level, Identifier profileId, Pacing pacing, @Nullable Vec3 site) {
		Optional<FlightProfile> found = RPRegistries.profile(level.registryAccess(), profileId);
		if (found.isEmpty() || this.launchProblem(level, found.get()).isPresent()) {
			return false;
		}
		FlightProfile profile = found.get();
		Vec3 target = site != null ? site : this.defaultSite(level, profile);
		this.siteX = target.x;
		this.siteZ = target.z;
		this.hasSite = true;
		this.siteRefined = false;
		double azimuth = profile.launchAzimuthDeg();
		if (profile.vehicle() == FlightProfile.Vehicle.STACK) {
			SuperHeavyEntity booster = this.booster(level).orElseThrow();
			Vec3 pad = booster.position();
			if (level.dimension() == Level.OVERWORLD) {
				this.homePad = GlobalPos.of(level.dimension(), BlockPos.containing(pad));
			}
			this.startFlight(profileId, pacing, pad, azimuth);
			booster.startFlight(profileId, pacing, pad, azimuth);
			booster.linkShip(this);
		} else {
			this.startFlight(profileId, pacing, this.position(), azimuth);
		}
		this.entityData.set(DATA_LEGS, 0.0F);
		RedPlanet.LOGGER.info("Starship {} launching on {} ({}) toward {} ({}, {})", this.getUUID(), profileId, pacing.getSerializedName(),
			profile.destination().identifier(), Math.round(target.x), Math.round(target.z));
		return true;
	}

	/** Where a flight lands if no site was chosen: home for a return to Earth, Gale crater (Curiosity) on Mars. */
	private Vec3 defaultSite(ServerLevel level, FlightProfile profile) {
		if (this.homePad != null && this.homePad.dimension().equals(profile.destination())) {
			BlockPos pad = this.homePad.pos();
			// Land beside the pad, not on the booster standing on it.
			return new Vec3(pad.getX() + 0.5 + 24.0, 0.0, pad.getZ() + 0.5);
		}
		if (profile.destination().equals(RPDimensions.MARS)) {
			return new Vec3(MarsProjection.xOf(137.44), 0.0, MarsProjection.zOf(-4.59));
		}
		ServerLevel destination = level.getServer().getLevel(profile.destination());
		BlockPos spawn = destination != null ? destination.getRespawnData().pos() : BlockPos.ZERO;
		return new Vec3(spawn.getX() + 0.5, 0.0, spawn.getZ() + 0.5);
	}

	/**
	 * A crew member asks to skip ahead. Returns how many more votes are needed (0 when the phase was skipped). With one
	 * player aboard the skip is immediate.
	 */
	public int voteSkip(Player player) {
		if (this.skipVotePhase != this.phase()) {
			this.skipVotes.clear();
			this.skipVotePhase = this.phase();
		}
		this.skipVotes.add(player.getUUID());
		long missing = this.getPassengers().stream().filter(p -> p instanceof Player && !this.skipVotes.contains(p.getUUID())).count();
		if (missing == 0) {
			this.skipVotes.clear();
			this.skipPhase();
		}
		return (int) missing;
	}

	/** Skips the rest of the current phase, keeping a booster still in flight in step. */
	@Override
	public void skipPhase() {
		super.skipPhase();
		if (this.level() instanceof ServerLevel level) {
			this.booster(level).filter(VehicleEntity::isFlying).ifPresent(booster -> booster.copyTimeline(this));
		}
	}

	public Optional<SuperHeavyEntity> booster(ServerLevel level) {
		if (this.boosterId == null) {
			return Optional.empty();
		}
		return level.getEntity(this.boosterId) instanceof SuperHeavyEntity booster && !booster.isRemoved() ? Optional.of(booster) : Optional.empty();
	}

	// ------------------------------------------------------------------------------------------------- ticking

	@Override
	protected void groundTick(ServerLevel level) {
		if (this.isStacked()) {
			Optional<SuperHeavyEntity> booster = this.booster(level);
			if (booster.isPresent()) {
				Vec3 top = booster.get().position().add(0.0, StarshipGeometry.BOOSTER_HEIGHT, 0.0);
				if (this.position().distanceToSqr(top) > 1.0E-4) {
					this.setPos(top);
				}
			} else if (this.tickCount > 40) {
				// The booster is gone: settle onto the ground on the legs.
				this.standOnLegs();
				int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(this.getX()), Mth.floor(this.getZ()));
				this.setPos(this.getX(), ground + StarshipGeometry.LANDED_SKIRT_HEIGHT, this.getZ());
			}
		}
		if (this.damage > 0.0F) {
			this.damage = Math.max(0.0F, this.damage - 0.1F);
		}
	}

	@Override
	protected void flightTick(ServerLevel level, FlightProfile profile, double missionTime) {
		FlightSegment segment = this.segment().orElse(null);
		if (this.tickCount % 20 == 0) {
			// Hold the landing site's chunks while the transfer plays and the descent closes in.
			if (segment == FlightSegment.TRANSFER) {
				this.preloadSite(level, profile);
			} else if (segment == FlightSegment.DESCENT) {
				level.getChunkSource().addTicketWithRadius(RPRegistries.FLIGHT_TICKET, ChunkPos.containing(BlockPos.containing(this.reference())), 2);
			}
		}
		if (segment == FlightSegment.DESCENT && !this.siteRefined) {
			this.refineSite(level);
		}
	}

	@Override
	protected boolean onPhaseStart(ServerLevel level, FlightProfile profile, int phase) {
		RedPlanet.LOGGER.info("Starship {}: phase {} ({}) at {}", this.getUUID(), phase, profile.phases().get(phase).id(), this.blockPosition());
		FlightSegment segment = profile.phases().get(phase).segment();
		FlightSegment previous = profile.phases().get(phase - 1).segment();
		if (segment == FlightSegment.TRANSFER && previous != FlightSegment.TRANSFER) {
			this.preloadSite(level, profile);
		}
		if (segment == FlightSegment.DESCENT && previous != FlightSegment.DESCENT) {
			return this.transferToDestination(level, profile, phase);
		}
		return true;
	}

	@Override
	protected void onEvent(ServerLevel level, FlightProfile profile, String type, double missionTime) {
		if ("hot_staging".equals(type)) {
			this.entityData.set(DATA_STACKED, false);
		}
	}

	@Override
	protected void onFlightComplete(ServerLevel level, FlightProfile profile) {
		// Touchdown happened at the end of the descent: stand exactly on the site.
		Vec3 site = this.reference();
		this.endFlight();
		this.standOnLegs();
		this.hasSite = false;
		this.setPos(site);
		this.restingAttitude(this.attitude);
		this.attitudeOld.set(this.attitude);
		for (Entity passenger : this.getPassengers()) {
			if (passenger instanceof ServerPlayer player) {
				player.sendSystemMessage(Component.translatable("starship.redplanet.landed"));
			}
		}
	}

	// ------------------------------------------------------------------------------------------- the transfer

	/** Starts generating the landing site in the destination dimension while the interlude plays. */
	private void preloadSite(ServerLevel level, FlightProfile profile) {
		ServerLevel destination = level.getServer().getLevel(profile.destination());
		if (destination == null || !this.hasSite) {
			return;
		}
		BlockPos site = BlockPos.containing(this.siteX, 0, this.siteZ);
		destination.getChunkSource().addTicketWithRadius(RPRegistries.FLIGHT_TICKET, ChunkPos.containing(site), 3);
	}

	/**
	 * Moves the ship and its crew to the destination dimension at the start of the descent, high above the landing site.
	 * Returns false: this entity is replaced by the one in the new dimension.
	 */
	private boolean transferToDestination(ServerLevel level, FlightProfile profile, int phase) {
		ServerLevel destination = level.getServer().getLevel(profile.destination());
		if (destination == null) {
			RedPlanet.LOGGER.warn("Starship {}: destination {} is missing; ending the flight", this.getUUID(), profile.destination().identifier());
			this.endFlight();
			return false;
		}
		if (!this.hasSite) {
			Vec3 site = this.defaultSite(level, profile);
			this.siteX = site.x;
			this.siteZ = site.z;
			this.hasSite = true;
		}
		// The terrain height from the generator (no chunk needed); refined from the real heightmap during the descent.
		int bx = Mth.floor(this.siteX);
		int bz = Mth.floor(this.siteZ);
		int surface = destination.getChunkSource().getGenerator().getBaseHeight(bx, bz, Heightmap.Types.MOTION_BLOCKING, destination,
			destination.getChunkSource().randomState());
		this.setReference(new Vec3(bx + 0.5, surface + StarshipGeometry.LANDED_SKIRT_HEIGHT, bz + 0.5));
		this.siteRefined = false;
		this.boosterId = null;
		this.entityData.set(DATA_STACKED, false);
		double missionTime = profile.missionTime(phase, 0.0, this.pacing());
		Quaternionf arrivalAttitude = new Quaternionf();
		Vec3 arrival = super.flightPosition(profile, missionTime, arrivalAttitude);
		this.attitude.set(arrivalAttitude);
		this.attitudeOld.set(arrivalAttitude);
		RedPlanet.LOGGER.info("Starship {} arriving over {} at {}, {} (surface y {})", this.getUUID(), profile.destination().identifier(), bx, bz,
			surface);
		// The descent starts far from the site: load the arrival chunk now (one column, a short pause the interlude covers)
		// and keep it ticking, or an uncrewed ship would arrive in an unloaded chunk and freeze there.
		ChunkPos arrivalChunk = ChunkPos.containing(BlockPos.containing(arrival));
		destination.getChunkSource().addTicketWithRadius(RPRegistries.FLIGHT_TICKET, arrivalChunk, 2);
		destination.getChunk(arrivalChunk.x(), arrivalChunk.z());
		this.transferring = true;
		Entity arrived = this.teleport(new TeleportTransition(destination, arrival, Vec3.ZERO, this.getYRot(), this.getXRot(),
			TeleportTransition.DO_NOTHING));
		this.transferring = false;
		if (arrived == null) {
			RedPlanet.LOGGER.warn("Starship {}: transfer to {} failed", this.getUUID(), profile.destination().identifier());
		}
		return false;
	}

	/** Once the landing site is loaded, aim for the real ground (and a flat spot near the target). */
	private void refineSite(ServerLevel level) {
		Vec3 ref = this.reference();
		int bx = Mth.floor(ref.x);
		int bz = Mth.floor(ref.z);
		if (!level.hasChunk(bx >> 4, bz >> 4)) {
			return;
		}
		BlockPos best = null;
		int bestSpread = Integer.MAX_VALUE;
		int bestTop = 0;
		// Search a few rings around the target for the flattest 11-block-wide pad (the legs span about 11 m).
		for (int r = 0; r <= 24 && bestSpread > 1; r += 6) {
			for (int k = 0; k < Math.max(1, r); k++) {
				double a = 2.0 * Math.PI * k / Math.max(1, r);
				int cx = bx + (int) Math.round(r * Math.cos(a));
				int cz = bz + (int) Math.round(r * Math.sin(a));
				if (!level.hasChunk((cx - 5) >> 4, (cz - 5) >> 4) || !level.hasChunk((cx + 5) >> 4, (cz + 5) >> 4)) {
					continue;
				}
				int min = Integer.MAX_VALUE;
				int max = Integer.MIN_VALUE;
				for (int dx = -5; dx <= 5; dx += 5) {
					for (int dz = -5; dz <= 5; dz += 5) {
						int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx + dx, cz + dz);
						min = Math.min(min, h);
						max = Math.max(max, h);
					}
				}
				int spread = max - min;
				if (spread < bestSpread) {
					bestSpread = spread;
					best = new BlockPos(cx, 0, cz);
					bestTop = max;
				}
			}
		}
		if (best == null) {
			return;
		}
		this.setReference(new Vec3(best.getX() + 0.5, bestTop + StarshipGeometry.LANDED_SKIRT_HEIGHT, best.getZ() + 0.5));
		this.siteRefined = true;
		RedPlanet.LOGGER.info("Starship {}: landing site at {}, {}, {} (unevenness {} blocks)", this.getUUID(), best.getX(), bestTop,
			best.getZ(), bestSpread);
	}

	// ---------------------------------------------------------------------------------------------- passengers

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return this.getPassengers().size() < StarshipGeometry.SEAT_COUNT && !this.isFlying() && passenger instanceof Player;
	}

	@Override
	public @Nullable LivingEntity getControllingPassenger() {
		return null;
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		int index = Math.max(0, this.getPassengers().indexOf(passenger)) % StarshipGeometry.SEAT_COUNT;
		double[] seat = StarshipGeometry.seatPosition(index);
		Vector3f world = this.attitude.transform(new Vector3f((float) seat[0], (float) seat[1], (float) seat[2]));
		return new Vec3(world.x, world.y, world.z);
	}

	/** The crew cabin holds air: passengers breathe in flight and on Mars while seated. */
	@Override
	public boolean isCabinPressurized() {
		return true;
	}

	/** Whether passengers must stay seated (the dismount lock): from launch to the end of the flight. */
	public boolean isDismountLocked() {
		return this.isFlying();
	}

	@Override
	public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
		if (this.transferring) {
			return passenger.position(); // lifted across worlds with the ship, then seated again
		}
		// Step out beside the ship (or the booster it stands on), on the ground, nearest the passenger's couch.
		Vec3 base = this.position();
		if (this.isStacked() && this.level() instanceof ServerLevel level) {
			base = this.booster(level).map(Entity::position).orElse(base);
		}
		int index = Math.max(0, this.getPassengers().indexOf(passenger));
		Vector3f out = this.attitude.transform(new Vector3f((float) Math.sin(StarshipGeometry.seatAngle(index)), 0.0F,
			(float) Math.cos(StarshipGeometry.seatAngle(index))));
		double baseAngle = Math.atan2(out.z, out.x);
		for (double radius : new double[]{7.0, 9.0, 12.0}) {
			for (int k = 0; k < 12; k++) {
				double a = baseAngle + (k % 2 == 0 ? 1 : -1) * Math.PI * ((k + 1) / 2) / 6.0;
				int x = Mth.floor(base.x + Math.cos(a) * radius);
				int z = Mth.floor(base.z + Math.sin(a) * radius);
				int y = this.level().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				Vec3 spot = DismountHelper.findSafeDismountLocation(passenger.getType(), this.level(), new BlockPos(x, y, z), true);
				if (spot != null) {
					return spot;
				}
			}
		}
		int x = Mth.floor(base.x + 7.0);
		int z = Mth.floor(base.z);
		return new Vec3(x + 0.5, this.level().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z + 0.5);
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
		InteractionResult result = super.interact(player, hand, location);
		if (result != InteractionResult.PASS) {
			return result;
		}
		if (player.isSecondaryUseActive() || this.isFlying() || player.getVehicle() == this) {
			return InteractionResult.PASS;
		}
		if (this.getPassengers().size() >= StarshipGeometry.SEAT_COUNT) {
			if (!this.level().isClientSide()) {
				player.sendOverlayMessage(Component.translatable("starship.redplanet.full"));
			}
			return InteractionResult.FAIL;
		}
		if (this.level().isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		return player.startRiding(this) ? InteractionResult.SUCCESS_SERVER : InteractionResult.PASS;
	}

	@Override
	protected void addPassenger(Entity passenger) {
		super.addPassenger(passenger);
		if (passenger instanceof ServerPlayer player) {
			player.sendOverlayMessage(Component.translatable("starship.redplanet.boarded"));
		}
	}

	// ------------------------------------------------------------------------------------------------ breaking

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (this.isFlying() || this.isRemoved() || !(source.getEntity() instanceof Player player)) {
			return false;
		}
		if (player.getAbilities().instabuild) {
			this.ejectPassengers();
			this.discard();
			return true;
		}
		this.damage += amount * 2.0F;
		if (this.damage > 12.0F) {
			this.ejectPassengers();
			this.spawnAtLocation(level, new ItemStack(RPStarship.STARSHIP_ITEM));
			this.discard();
		}
		return true;
	}

	@Override
	public @Nullable ItemStack getPickResult() {
		return new ItemStack(RPStarship.STARSHIP_ITEM);
	}

	@Override
	protected AABB makeBoundingBox(Vec3 position) {
		double r = StarshipGeometry.HULL_RADIUS;
		return new AABB(position.x - r, position.y, position.z - r, position.x + r, position.y + StarshipGeometry.SHIP_HEIGHT, position.z + r);
	}

	// ----------------------------------------------------------------------------------------------- persistence

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		this.entityData.set(DATA_LEGS, input.getFloatOr("legs", 1.0F));
		this.entityData.set(DATA_STACKED, input.getBooleanOr("stacked", false));
		this.boosterId = input.read("booster", UUIDUtil.CODEC).orElse(null);
		this.hasSite = input.getBooleanOr("has_site", false);
		this.siteX = input.getDoubleOr("site_x", 0.0);
		this.siteZ = input.getDoubleOr("site_z", 0.0);
		this.siteRefined = input.getBooleanOr("site_refined", false);
		this.homePad = input.read("home_pad", GlobalPos.CODEC).orElse(null);
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putFloat("legs", this.restingLegs());
		output.putBoolean("stacked", this.isStacked());
		output.storeNullable("booster", UUIDUtil.CODEC, this.boosterId);
		output.putBoolean("has_site", this.hasSite);
		output.putDouble("site_x", this.siteX);
		output.putDouble("site_z", this.siteZ);
		output.putBoolean("site_refined", this.siteRefined);
		output.storeNullable("home_pad", GlobalPos.CODEC, this.homePad);
	}

	/** Lists the ships riding nearby players (for commands). */
	public static List<StarshipEntity> near(ServerLevel level, Vec3 at, double radius) {
		return level.getEntitiesOfClass(StarshipEntity.class, new AABB(at, at).inflate(radius, 256.0, radius));
	}

	public static Optional<ResourceKey<Level>> destinationOf(StarshipEntity ship) {
		return ship.profile().map(FlightProfile::destination);
	}
}
