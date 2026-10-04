package io.github.avi130805.redplanet.mars.weather;

import io.github.avi130805.redplanet.config.RedPlanetConfig;
import io.github.avi130805.redplanet.mars.MarsConditions;
import io.github.avi130805.redplanet.mars.astro.MarsCalendar;
import io.github.avi130805.redplanet.registry.RPDimensions;
import io.github.avi130805.redplanet.registry.RPEntities;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Raises dust devils near players on the Martian surface, when and where the real ones form (docs/SCIENCE.md
 * section 11): in the dusty season (Ls 173-340, peaking near 250) and from mid-morning to late afternoon (09:30-16:30
 * local time, peaking at 13:00), when the ground is hottest and the air above it most unstable. Thick dust shades the
 * ground and stifles them, so none form in a storm. The rate is tuned for play ({@code dustDevilScale} in the server
 * config).
 */
public final class DustDevils {
	private static final int CHECK_INTERVAL = 100;
	private static final int MAX_NEAR_PLAYER = 2;
	private static final double BASE_CHANCE = 0.15;

	private DustDevils() {
	}

	public static void init() {
		ServerTickEvents.END_LEVEL_TICK.register(DustDevils::tick);
	}

	/** 0..1: how favourable this moment is for dust devils (season x time of day). */
	public static double activity(ServerLevel level) {
		return activity(MarsConditions.solarLongitude(level),
			MarsCalendar.lmstFraction(MarsConditions.clockTicks(level)) * MarsCalendar.HOURS_PER_SOL);
	}

	/** 0..1 for a solar longitude (degrees) and local mean solar time (Mars hours). */
	public static double activity(double ls, double hours) {
		double season = ls >= 173.0 && ls <= 340.0 ? Math.exp(-0.5 * Math.pow((ls - 250.0) / 45.0, 2.0)) : 0.0;
		double day = hours >= 9.5 && hours <= 16.5 ? Math.exp(-0.5 * Math.pow((hours - 13.0) / 1.6, 2.0)) : 0.0;
		return season * day;
	}

	private static void tick(ServerLevel level) {
		if (!RPDimensions.isMars(level) || level.getGameTime() % CHECK_INTERVAL != 0) {
			return;
		}
		double activity = activity(level) * RedPlanetConfig.server().dustDevilScale;
		if (activity <= 0.0) {
			return;
		}
		RandomSource random = level.getRandom();
		for (ServerPlayer player : level.players()) {
			if (player.isSpectator()) {
				continue;
			}
			int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, player.getBlockX(), player.getBlockZ());
			if (player.getY() < surface - 12 || MarsWeather.tauAt(level, player.getX(), player.getZ()) > 2.0) {
				continue;
			}
			int near = level.getEntitiesOfClass(DustDevil.class, player.getBoundingBox().inflate(160.0)).size();
			if (near < MAX_NEAR_PLAYER && random.nextDouble() < BASE_CHANCE * activity) {
				spawnNear(level, player.getX(), player.getZ(), 40.0, 110.0, random);
			}
		}
	}

	/** Raises one dust devil 'min'-'max' blocks from a point (used by the spawner and the weather command). */
	public static DustDevil spawnNear(ServerLevel level, double x, double z, double min, double max, RandomSource random) {
		double angle = random.nextDouble() * Math.PI * 2.0;
		double distance = min + random.nextDouble() * (max - min);
		double px = x + Math.cos(angle) * distance;
		double pz = z + Math.sin(angle) * distance;
		int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(px), Mth.floor(pz));
		DustDevil devil = RPEntities.DUST_DEVIL.create(level, EntitySpawnReason.EVENT);
		if (devil == null) {
			return null;
		}
		// Mostly small (Spirit's were typically 10-20 m across), occasionally large; tall and thin.
		float radius = (float) (3.0 + 17.0 * Math.pow(random.nextDouble(), 2.2));
		float height = Mth.clamp(radius * (3.5F + random.nextFloat() * 4.5F) + 10.0F, 20.0F, 120.0F);
		double speed = 0.05 + 0.25 * random.nextDouble();
		double heading = random.nextDouble() * Math.PI * 2.0;
		devil.configure(radius, height, Math.cos(heading) * speed, Math.sin(heading) * speed, 600 + random.nextInt(1800));
		devil.setPos(px, ground, pz);
		level.addFreshEntity(devil);
		return devil;
	}
}
