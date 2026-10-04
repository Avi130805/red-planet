package io.github.avi130805.redplanet.starship.entity;

import java.util.Optional;

import io.github.avi130805.redplanet.registry.RPRegistries;
import io.github.avi130805.redplanet.starship.flight.FlightKinematics;
import io.github.avi130805.redplanet.starship.flight.FlightProfile;
import io.github.avi130805.redplanet.starship.flight.FlightSegment;
import io.github.avi130805.redplanet.starship.flight.Pacing;
import io.github.avi130805.redplanet.starship.flight.PhaseDef;
import io.github.avi130805.redplanet.starship.flight.TelemetryTrack;

import net.minecraft.core.BlockPos;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * A Starship or Super Heavy: kinematic, non-solid and server-authoritative.
 *
 * <p>In flight a vehicle follows its {@link FlightProfile}: the server advances the phases on game time and places the
 * vehicle from the telemetry, and every client runs the same function each tick from the synced flight state (profile,
 * pacing, phase, phase start, reference point, azimuth). Clients therefore see smooth motion at any speed, and position
 * packets are ignored while the flight runs.
 */
public abstract class VehicleEntity extends Entity {
	private static final EntityDataAccessor<String> DATA_PROFILE = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Byte> DATA_PACING = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.BYTE);
	private static final EntityDataAccessor<Integer> DATA_PHASE = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Long> DATA_PHASE_START = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.LONG);
	private static final EntityDataAccessor<BlockPos> DATA_REFERENCE = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.BLOCK_POS);
	private static final EntityDataAccessor<Float> DATA_REFERENCE_DY = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_AZIMUTH = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.FLOAT);

	/** Attitude (body frame to world) at the end of the last tick, and the one before, for interpolation. */
	protected final Quaternionf attitude = new Quaternionf();
	protected final Quaternionf attitudeOld = new Quaternionf();
	/** Mission time handled by the last server tick (events fire once as it passes them). */
	private double lastMissionTime = Double.NaN;
	/** The same on the client, for sounds and effects. */
	private double lastClientMissionTime = Double.NaN;
	private @Nullable ChunkPos ticketChunk;
	private int ticketAge;

	protected VehicleEntity(EntityType<? extends VehicleEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.blocksBuilding = true;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_PROFILE, "");
		builder.define(DATA_PACING, (byte) Pacing.STANDARD.ordinal());
		builder.define(DATA_PHASE, 0);
		builder.define(DATA_PHASE_START, 0L);
		builder.define(DATA_REFERENCE, BlockPos.ZERO);
		builder.define(DATA_REFERENCE_DY, 0.0F);
		builder.define(DATA_AZIMUTH, 90.0F);
	}

	// ---------------------------------------------------------------------------------------------- flight state

	public boolean isFlying() {
		return !this.entityData.get(DATA_PROFILE).isEmpty();
	}

	public Optional<Identifier> profileId() {
		String id = this.entityData.get(DATA_PROFILE);
		return id.isEmpty() ? Optional.empty() : Optional.ofNullable(Identifier.tryParse(id));
	}

	/** The flight profile being flown, if any (and if this world has it). */
	public Optional<FlightProfile> profile() {
		return this.profileId().flatMap(id -> RPRegistries.profile(this.registryAccess(), id));
	}

	public Pacing pacing() {
		Pacing[] values = Pacing.values();
		return values[Math.floorMod(this.entityData.get(DATA_PACING), values.length)];
	}

	public int phase() {
		return this.entityData.get(DATA_PHASE);
	}

	public long phaseStart() {
		return this.entityData.get(DATA_PHASE_START);
	}

	public double azimuth() {
		return this.entityData.get(DATA_AZIMUTH);
	}

	/** The reference point of the current segment: the pad on the way up, the landing site on the way down. */
	public Vec3 reference() {
		BlockPos pos = this.entityData.get(DATA_REFERENCE);
		return new Vec3(pos.getX() + 0.5, pos.getY() + this.entityData.get(DATA_REFERENCE_DY), pos.getZ() + 0.5);
	}

	protected void setAzimuth(double azimuthDeg) {
		this.entityData.set(DATA_AZIMUTH, (float) azimuthDeg);
	}

	protected void setReference(Vec3 point) {
		BlockPos block = BlockPos.containing(point.x, Math.floor(point.y), point.z);
		this.entityData.set(DATA_REFERENCE, block);
		this.entityData.set(DATA_REFERENCE_DY, (float) (point.y - block.getY()));
	}

	/** Starts (or restarts) a flight from phase 0 now. */
	public void startFlight(Identifier profile, Pacing pacing, Vec3 reference, double azimuthDeg) {
		this.entityData.set(DATA_PROFILE, profile.toString());
		this.entityData.set(DATA_PACING, (byte) pacing.ordinal());
		this.entityData.set(DATA_PHASE, 0);
		this.entityData.set(DATA_PHASE_START, this.level().getGameTime());
		this.entityData.set(DATA_AZIMUTH, (float) azimuthDeg);
		this.setReference(reference);
		this.lastMissionTime = Double.NaN;
	}

	/** Copies another vehicle's timeline (the ship keeps its booster in step, including skips). */
	public void copyTimeline(VehicleEntity other) {
		this.entityData.set(DATA_PROFILE, other.entityData.get(DATA_PROFILE));
		this.entityData.set(DATA_PACING, other.entityData.get(DATA_PACING));
		this.entityData.set(DATA_PHASE, other.phase());
		this.entityData.set(DATA_PHASE_START, other.phaseStart());
		this.entityData.set(DATA_AZIMUTH, other.entityData.get(DATA_AZIMUTH));
	}

	/** Moves to a phase now (skips, segment changes). */
	protected void setPhase(int phase, long start) {
		this.entityData.set(DATA_PHASE, phase);
		this.entityData.set(DATA_PHASE_START, start);
	}

	/** Ends the current phase now: the next tick moves on, firing the events of the skipped part. */
	public void skipPhase() {
		this.profile().ifPresent(profile -> {
			int phase = Math.min(this.phase(), profile.phases().size() - 1);
			this.setPhase(phase, this.level().getGameTime() - profile.phaseTicks(phase, this.pacing()));
		});
	}

	/** Ends the flight where the vehicle is. */
	protected void endFlight() {
		this.entityData.set(DATA_PROFILE, "");
		this.entityData.set(DATA_PHASE, 0);
		this.lastMissionTime = Double.NaN;
	}

	/** Gameplay ticks into the current phase, with a partial tick for smooth clients. */
	public double phaseTick(float partialTick) {
		return Math.max(0.0, this.level().getGameTime() - this.phaseStart() + partialTick);
	}

	/** Mission time now (s), or NaN on the ground. */
	public double missionTime(float partialTick) {
		Optional<FlightProfile> profile = this.profile();
		if (profile.isEmpty()) {
			return Double.NaN;
		}
		FlightProfile p = profile.get();
		int phase = Math.min(this.phase(), p.phases().size() - 1);
		return p.missionTime(phase, Math.min(this.phaseTick(partialTick), p.phaseTicks(phase, this.pacing())), this.pacing());
	}

	public Optional<PhaseDef> currentPhase() {
		return this.profile().map(p -> p.phases().get(Math.min(this.phase(), p.phases().size() - 1)));
	}

	public Optional<FlightSegment> segment() {
		return this.currentPhase().map(PhaseDef::segment);
	}

	/** This vehicle's telemetry track in a profile. */
	protected abstract TelemetryTrack track(FlightProfile profile);

	/** Whether this vehicle has finished its part of the flight (a booster after the catch). */
	protected boolean trackFinished(FlightProfile profile, double missionTime) {
		return false;
	}

	/**
	 * Where the vehicle is and which way it points at a mission time: the telemetry, mapped into the world around the
	 * reference point. Subclasses adjust it (the stacked ship rides on the booster).
	 */
	protected Vec3 flightPosition(FlightProfile profile, double missionTime, Quaternionf attitudeOut) {
		TelemetryTrack.Sample sample = this.track(profile).sample(missionTime);
		attitudeOut.set(FlightKinematics.attitude(sample.pitchDeg(), this.azimuth()));
		Vector3d offset = FlightKinematics.offset(profile, sample, this.azimuth());
		Vec3 ref = this.reference();
		return new Vec3(ref.x + offset.x, ref.y + offset.y, ref.z + offset.z);
	}

	/** Attitude interpolated between the last two ticks. */
	public Quaternionf attitude(float partialTick, Quaternionf out) {
		return this.attitudeOld.slerp(this.attitude, partialTick, out);
	}

	/** Attitude on the ground: upright, turned to the vehicle's yaw. */
	protected void restingAttitude(Quaternionf out) {
		out.set(FlightKinematics.attitude(90.0, this.azimuth()));
	}

	// ------------------------------------------------------------------------------------------------- ticking

	@Override
	public void tick() {
		this.attitudeOld.set(this.attitude);
		super.tick();
		if (this.level() instanceof ServerLevel level) {
			this.serverTick(level);
		} else {
			this.clientTick();
		}
	}

	private void serverTick(ServerLevel level) {
		if (!this.isFlying()) {
			this.restingAttitude(this.attitude);
			this.groundTick(level);
			return;
		}
		Optional<FlightProfile> found = this.profile();
		if (found.isEmpty()) {
			// The profile is gone (removed datapack): stop where we are rather than crash.
			this.endFlight();
			return;
		}
		FlightProfile profile = found.get();
		Pacing pacing = this.pacing();
		long now = level.getGameTime();
		int phase = Math.min(this.phase(), profile.phases().size() - 1);
		long start = this.phaseStart();
		// Advance through every phase that has ended (several at once after lag or a long unload).
		while (now - start >= profile.phaseTicks(phase, pacing)) {
			start += profile.phaseTicks(phase, pacing);
			if (phase + 1 >= profile.phases().size()) {
				this.fireEvents(level, profile, this.lastMissionTime, profile.phases().getLast().missionEnd());
				this.onFlightComplete(level, profile);
				return;
			}
			phase++;
			this.setPhase(phase, start);
			if (!this.onPhaseStart(level, profile, phase) || this.isRemoved()) {
				return; // the vehicle changed dimension or was removed
			}
		}
		double missionTime = profile.missionTime(phase, now - start, pacing);
		this.fireEvents(level, profile, this.lastMissionTime, missionTime);
		if (this.isRemoved()) {
			return;
		}
		this.lastMissionTime = missionTime;
		if (this.trackFinished(profile, missionTime)) {
			this.onTrackFinished(level, profile);
			return;
		}
		if (this.movesInWorld(profile, phase)) {
			Vec3 pos = this.flightPosition(profile, missionTime, this.attitude);
			this.ensureLoaded(level, pos);
			this.setPos(pos);
		}
		// Keep ticking even with nobody near (an uncrewed booster flying home, a ship parked during the transfer).
		this.keepChunksLoaded(level, profile, phase, now - start);
		this.flightTick(level, profile, missionTime);
	}

	private void fireEvents(ServerLevel level, FlightProfile profile, double from, double to) {
		if (Double.isNaN(from)) {
			return; // first tick after a start or a load: don't replay past events
		}
		for (PhaseDef phase : profile.phases()) {
			if (phase.missionEnd() < from || phase.missionStart() > to) {
				continue;
			}
			for (PhaseDef.Event event : phase.events()) {
				double t = phase.missionTimeAt(event.at());
				if (t > from && t <= to) {
					this.onEvent(level, profile, event.type(), t);
				}
			}
		}
	}

	private void clientTick() {
		if (!this.isFlying()) {
			this.restingAttitude(this.attitude);
			this.lastClientMissionTime = Double.NaN;
			return;
		}
		Optional<FlightProfile> found = this.profile();
		if (found.isEmpty()) {
			return;
		}
		FlightProfile profile = found.get();
		int phase = Math.min(this.phase(), profile.phases().size() - 1);
		double missionTime = this.missionTime(0.0F);
		if (this.movesInWorld(profile, phase) && !this.trackFinished(profile, missionTime)) {
			Vec3 pos = this.flightPosition(profile, missionTime, this.attitude);
			this.setPos(pos);
			FlightEffects.tick(this, profile, missionTime, this.lastClientMissionTime);
		}
		this.clientFlightTick(profile, missionTime);
		this.lastClientMissionTime = missionTime;
	}

	/**
	 * Whether the vehicle is placed from telemetry in this phase: everywhere but the transfer, when it is between worlds
	 * (on the pad and after touchdown the telemetry holds it at the reference point).
	 */
	protected boolean movesInWorld(FlightProfile profile, int phase) {
		return profile.phases().get(phase).segment() != FlightSegment.TRANSFER;
	}

	/**
	 * Before moving into another chunk, makes sure it is loaded (normally the look-ahead tickets have done it already;
	 * after a skip the vehicle can jump further). An entity that enters an unloaded chunk stops ticking until it loads.
	 */
	private void ensureLoaded(ServerLevel level, Vec3 pos) {
		ChunkPos target = ChunkPos.containing(BlockPos.containing(pos));
		if (!target.equals(this.chunkPosition())) {
			level.getChunkSource().addTicketWithRadius(RPRegistries.FLIGHT_TICKET, target, 2);
			if (!level.hasChunk(target.x(), target.z())) {
				level.getChunk(target.x(), target.z());
			}
		}
	}

	/**
	 * Loads the chunks the vehicle is in and the ones it will fly through over the next five seconds. An entity that
	 * moves into an unloaded chunk stops ticking until that chunk loads, so loading only where it is lets a fast vehicle
	 * stall and jump; the flight path is known in advance, so load ahead of it.
	 */
	private void keepChunksLoaded(ServerLevel level, FlightProfile profile, int phase, long phaseTick) {
		ChunkPos chunk = this.chunkPosition();
		if (chunk.equals(this.ticketChunk) && ++this.ticketAge < 10) {
			return;
		}
		this.ticketChunk = chunk;
		this.ticketAge = 0;
		level.getChunkSource().addTicketWithRadius(RPRegistries.FLIGHT_TICKET, chunk, 2);
		Pacing pacing = this.pacing();
		Quaternionf scratch = new Quaternionf();
		for (int ahead = 20; ahead <= 100; ahead += 20) {
			int p = phase;
			long tick = phaseTick + ahead;
			while (p + 1 < profile.phases().size() && tick >= profile.phaseTicks(p, pacing)) {
				tick -= profile.phaseTicks(p, pacing);
				p++;
			}
			if (!this.movesInWorld(profile, p)) {
				break; // the transfer: nothing further ahead in this world
			}
			double t = profile.missionTime(p, Math.min(tick, profile.phaseTicks(p, pacing)), pacing);
			if (this.trackFinished(profile, t)) {
				break;
			}
			Vec3 future = this.flightPosition(profile, t, scratch);
			level.getChunkSource().addTicketWithRadius(RPRegistries.FLIGHT_TICKET, ChunkPos.containing(BlockPos.containing(future)), 2);
		}
	}

	/** Server tick while not flying. */
	protected void groundTick(ServerLevel level) {
	}

	/** Server tick in flight, after moving. */
	protected void flightTick(ServerLevel level, FlightProfile profile, double missionTime) {
	}

	/** Client tick in flight, after moving (particles, sounds). */
	protected void clientFlightTick(FlightProfile profile, double missionTime) {
	}

	/**
	 * A new phase began. Return false if the vehicle must stop ticking this tick (it changed dimension or was removed).
	 */
	protected boolean onPhaseStart(ServerLevel level, FlightProfile profile, int phase) {
		return true;
	}

	/** A timeline event passed. */
	protected void onEvent(ServerLevel level, FlightProfile profile, String type, double missionTime) {
	}

	/** The last phase ended. */
	protected void onFlightComplete(ServerLevel level, FlightProfile profile) {
		this.endFlight();
	}

	/** This vehicle's track ended before the flight did (the booster after the catch). */
	protected void onTrackFinished(ServerLevel level, FlightProfile profile) {
		this.endFlight();
	}

	// ----------------------------------------------------------------------------------------------- interpolation

	@Override
	protected InterpolationHandler createInterpolationHandler() {
		return new FlightInterpolation(this);
	}

	/**
	 * Ignores position packets while a flight runs (the client computes the same motion every tick) and snaps to them on
	 * the ground.
	 */
	private record FlightInterpolation(VehicleEntity vehicle) implements InterpolationHandler {
		@Override
		public @Nullable PositionAndRotation target() {
			return null;
		}

		@Override
		public boolean interpolateTo(@Nullable PositionPath position, float yRot, float xRot, boolean hasRotation) {
			return this.vehicle.isFlying();
		}

		@Override
		public void interpolate() {
		}

		@Override
		public void applyPredictedMovement(Vec3 delta) {
		}

		@Override
		public boolean hasActiveInterpolation() {
			return false;
		}

		@Override
		public void cancel() {
		}
	}

	// ----------------------------------------------------------------------------------------------- persistence

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		this.entityData.set(DATA_PROFILE, input.getStringOr("flight_profile", ""));
		this.entityData.set(DATA_PACING, (byte) input.getIntOr("pacing", Pacing.STANDARD.ordinal()));
		this.entityData.set(DATA_PHASE, input.getIntOr("phase", 0));
		this.entityData.set(DATA_PHASE_START, input.getLongOr("phase_start", 0L));
		this.entityData.set(DATA_AZIMUTH, input.getFloatOr("azimuth", 90.0F));
		this.setReference(new Vec3(input.getDoubleOr("reference_x", this.getX()), input.getDoubleOr("reference_y", this.getY()),
			input.getDoubleOr("reference_z", this.getZ())));
		this.lastMissionTime = Double.NaN;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putString("flight_profile", this.entityData.get(DATA_PROFILE));
		output.putInt("pacing", this.entityData.get(DATA_PACING));
		output.putInt("phase", this.phase());
		output.putLong("phase_start", this.phaseStart());
		output.putFloat("azimuth", this.entityData.get(DATA_AZIMUTH));
		Vec3 ref = this.reference();
		output.putDouble("reference_x", ref.x);
		output.putDouble("reference_y", ref.y);
		output.putDouble("reference_z", ref.z);
	}

	// ------------------------------------------------------------------------------------------- physical behaviour

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return !this.isRemoved();
	}

	@Override
	public boolean canBeCollidedWith(@Nullable Entity other) {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean isPushedByFluid() {
		return false;
	}

	@Override
	public boolean ignoreExplosion(Explosion explosion) {
		return true;
	}

	@Override
	public boolean canUsePortal(boolean ignorePassenger) {
		return false;
	}

	@Override
	public boolean showVehicleHealth() {
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
		return distance < 4096.0 * 4096.0;
	}

	@Override
	public boolean fireImmune() {
		return true;
	}
}
