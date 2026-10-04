package io.github.avi130805.redplanet.mars.weather;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.avi130805.redplanet.RedPlanet;
import io.github.avi130805.redplanet.config.RedPlanetConfig;
import io.github.avi130805.redplanet.mars.MarsConditions;
import io.github.avi130805.redplanet.mars.astro.MarsCalendar;
import io.github.avi130805.redplanet.mars.geo.MarsProjection;
import io.github.avi130805.redplanet.network.MarsWeatherPayload;
import io.github.avi130805.redplanet.network.RPNetworking;
import io.github.avi130805.redplanet.registry.RPDimensions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Mars' dust weather, saved with the Mars level ({@code dimensions/redplanet/mars/data/redplanet/weather.dat}).
 *
 * <ul>
 * <li><b>Background haze</b> follows the season: visible optical depth ~0.45 around aphelion, ~0.9 in the dusty
 * season (Ls 180-360).</li>
 * <li><b>Regional storms</b> start only in the dusty season, on average every {@code dustStormIntervalSols}
 * sols (config), half of them near a player on Mars so they get seen, the rest over the classic genesis regions
 * (Hellas, Argyre, Chryse/Acidalia). Peak tau 1.5-4, 2-10 sols.</li>
 * <li><b>Global storms</b>: a 1-in-4 chance per Mars year (one every 3-6 years), peak tau 6-10, ~60-120 sols.</li>
 * </ul>
 * Sources: docs/SCIENCE.md section 11.
 */
public final class MarsWeather extends SavedData {
	public static final Codec<MarsWeather> CODEC = RecordCodecBuilder.create(i -> i.group(
		DustStorm.CODEC.optionalFieldOf("storm").forGetter(w -> Optional.ofNullable(w.storm)),
		Codec.LONG.optionalFieldOf("next_roll", 0L).forGetter(w -> w.nextRoll)
	).apply(i, MarsWeather::new));
	public static final SavedDataType<MarsWeather> TYPE = new SavedDataType<>(RedPlanet.id("weather"), MarsWeather::new, CODEC, null);

	/** Where regional storms like to start (lat, lon): Hellas, Argyre, Chryse/Acidalia. */
	private static final double[][] GENESIS = {{-42.0, 70.0}, {-50.0, 316.0}, {35.0, 325.0}};

	/** The client's copy of the Mars storm, from MarsWeatherPayload. */
	private static volatile DustStorm clientStorm;

	private DustStorm storm;
	private long nextRoll;

	public MarsWeather() {
		this(Optional.empty(), 0L);
	}

	private MarsWeather(Optional<DustStorm> storm, long nextRoll) {
		this.storm = storm.orElse(null);
		this.nextRoll = nextRoll;
	}

	public static MarsWeather get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	public DustStorm storm() {
		return this.storm;
	}

	/** Background (non-storm) optical depth for a season. */
	public static double backgroundTau(double lsDeg) {
		double dusty = Math.max(0.0, Math.sin(Math.toRadians(lsDeg - 180.0)));
		return 0.45 + 0.45 * dusty;
	}

	/** Optical depth at a place, on whichever side owns the level. */
	public static double tauAt(Level level, double x, double z) {
		double background = backgroundTau(MarsConditions.solarLongitude(level));
		DustStorm s = level instanceof ServerLevel serverLevel ? get(serverLevel).storm : clientStorm;
		return s == null ? background : s.tau(x, z, level.getGameTime(), background);
	}

	public static void setClientStorm(DustStorm storm) {
		clientStorm = storm;
	}

	public static DustStorm clientStorm() {
		return clientStorm;
	}

	/** Server tick (Mars only): roll for storms once per sol and clear finished ones. */
	public static void tick(ServerLevel level) {
		if (!RPDimensions.isMars(level) || level.getGameTime() % 20 != 0) {
			return;
		}
		get(level).update(level);
	}

	private void update(ServerLevel level) {
		long now = level.getGameTime();
		if (this.storm != null && this.storm.isOver(now)) {
			setStorm(level, null);
		}
		if (now < this.nextRoll) {
			return;
		}
		this.nextRoll = now + MarsCalendar.TICKS_PER_SOL;
		setDirty();
		if (this.storm != null) {
			return;
		}
		double ls = MarsConditions.solarLongitude(level);
		boolean dustySeason = ls >= 180.0;
		double interval = RedPlanetConfig.server().dustStormIntervalSols;
		if (!dustySeason || interval <= 0.0) {
			return;
		}
		RandomSource random = level.getRandom();
		// The dusty season lasts about 330 sols: a 1-in-4 chance per year spread over it.
		if (random.nextDouble() < 0.25 / 330.0) {
			setStorm(level, global(now, random));
		} else if (random.nextDouble() < 1.0 / interval) {
			setStorm(level, regional(level, now, random));
		}
	}

	public static DustStorm global(long now, RandomSource random) {
		long sol = MarsCalendar.TICKS_PER_SOL;
		long ramp = (long) ((8 + random.nextInt(8)) * sol);
		long hold = (long) ((30 + random.nextInt(50)) * sol);
		long decay = (long) ((20 + random.nextInt(20)) * sol);
		double peak = 6.0 + 4.0 * random.nextDouble();
		return new DustStorm(true, 0, 0, 0, peak, now, ramp, now + ramp + hold, decay);
	}

	public static DustStorm regional(ServerLevel level, long now, RandomSource random) {
		double x;
		double z;
		List<ServerPlayer> players = level.players();
		if (!players.isEmpty() && random.nextBoolean()) {
			ServerPlayer p = players.get(random.nextInt(players.size()));
			x = p.getX() + (random.nextDouble() - 0.5) * 1500.0;
			z = p.getZ() + (random.nextDouble() - 0.5) * 1500.0;
		} else {
			double[] g = GENESIS[random.nextInt(GENESIS.length)];
			x = MarsProjection.xOf(g[1]);
			z = MarsProjection.zOf(g[0]);
		}
		long sol = MarsCalendar.TICKS_PER_SOL;
		long ramp = sol / 3;
		long hold = (long) ((1.5 + 6.0 * random.nextDouble()) * sol);
		long decay = sol;
		double peak = 1.5 + 2.5 * random.nextDouble();
		double radius = 1200.0 + 2000.0 * random.nextDouble();
		return new DustStorm(false, x, z, radius, peak, now, ramp, now + ramp + hold, decay);
	}

	/** Replaces the current storm (null clears it) and tells every player on Mars. */
	public void setStorm(ServerLevel level, DustStorm newStorm) {
		this.storm = newStorm;
		setDirty();
		MarsWeatherPayload payload = new MarsWeatherPayload(Optional.ofNullable(newStorm));
		for (ServerPlayer player : level.players()) {
			RPNetworking.send(player, payload);
		}
	}

	/** Sends the current storm to one player (join, or arrival on Mars). */
	public static void sendTo(ServerPlayer player) {
		if (player.level() instanceof ServerLevel level && RPDimensions.isMars(level)) {
			RPNetworking.send(player, new MarsWeatherPayload(Optional.ofNullable(get(level).storm)));
		} else {
			RPNetworking.send(player, new MarsWeatherPayload(Optional.empty()));
		}
	}
}
