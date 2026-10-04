package io.github.avi130805.redplanet.command;

import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import io.github.avi130805.redplanet.environment.PlanetEnvironment;
import io.github.avi130805.redplanet.mars.MarsConditions;
import io.github.avi130805.redplanet.mars.astro.MarsCalendar;
import io.github.avi130805.redplanet.mars.astro.MarsClimate;
import io.github.avi130805.redplanet.mars.geo.MarsLandmarks;
import io.github.avi130805.redplanet.mars.geo.MarsLandmarks.Landmark;
import io.github.avi130805.redplanet.mars.geo.MarsProjection;
import io.github.avi130805.redplanet.mars.weather.DustStorm;
import io.github.avi130805.redplanet.mars.weather.MarsWeather;
import io.github.avi130805.redplanet.registry.RPDimensions;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /redplanet}: debugging and exploration commands.
 *
 * <ul>
 * <li>{@code tp mars [<lat> <lon>]}, {@code tp earth}: jump between worlds without the rocket (operators).</li>
 * <li>{@code info}: where you are on Mars and what the environment is doing there.</li>
 * <li>{@code locate <landmark>}: map coordinates of a landform or landing site (click to go there).</li>
 * <li>{@code weather dust regional|global|clear}: start or stop a dust storm on Mars (operators).</li>
 * </ul>
 */
public final class RPCommands {
	/** Curiosity's landing site in Gale crater: the default arrival point. */
	private static final double DEFAULT_LAT = -4.59;
	private static final double DEFAULT_LON = 137.44;

	private RPCommands() {
	}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("redplanet")
			.then(Commands.literal("tp")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("mars")
					.executes(ctx -> toMars(ctx, DEFAULT_LAT, DEFAULT_LON))
					.then(Commands.argument("lat", DoubleArgumentType.doubleArg(-90.0, 90.0))
						.then(Commands.argument("lon", DoubleArgumentType.doubleArg(-360.0, 360.0))
							.executes(ctx -> toMars(ctx, DoubleArgumentType.getDouble(ctx, "lat"), DoubleArgumentType.getDouble(ctx, "lon"))))))
				.then(Commands.literal("earth").executes(RPCommands::toEarth)))
			.then(Commands.literal("info").executes(RPCommands::info))
			.then(Commands.literal("weather")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("dust")
					.then(Commands.literal("regional").executes(ctx -> dust(ctx, "regional")))
					.then(Commands.literal("global").executes(ctx -> dust(ctx, "global")))
					.then(Commands.literal("clear").executes(ctx -> dust(ctx, "clear")))))
			.then(Commands.literal("locate")
				.then(Commands.argument("landmark", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(MarsLandmarks.ALL.stream().map(Landmark::id), builder))
					.executes(RPCommands::locate))));
	}

	private static int toMars(CommandContext<CommandSourceStack> ctx, double lat, double lon) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		ServerLevel mars = ctx.getSource().getServer().getLevel(RPDimensions.MARS);
		if (mars == null) {
			ctx.getSource().sendFailure(Component.translatable("commands.redplanet.no_mars"));
			return 0;
		}
		double fromX = player.level() == mars ? player.getX() : 0.0;
		double x = nearestX(lon, fromX);
		double z = MarsProjection.zOf(lat);
		int bx = (int) Math.floor(x);
		int bz = (int) Math.floor(z);
		mars.getChunk(bx >> 4, bz >> 4); // generate the arrival chunk (blocking: an operator command)
		int y = mars.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
		player.teleport(new TeleportTransition(mars, new Vec3(bx + 0.5, y + 0.1, bz + 0.5), Vec3.ZERO, player.getYRot(), player.getXRot(),
			TeleportTransition.DO_NOTHING));
		ctx.getSource().sendSuccess(() -> Component.translatable("commands.redplanet.tp.mars", fmt(lat, 2), fmt(lon, 2), bx, y, bz), true);
		return 1;
	}

	private static int toEarth(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		MinecraftServer server = ctx.getSource().getServer();
		ServerLevel overworld = server.overworld();
		BlockPos spawn = server.getRespawnData().pos();
		overworld.getChunk(spawn.getX() >> 4, spawn.getZ() >> 4);
		int y = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ());
		player.teleport(new TeleportTransition(overworld, new Vec3(spawn.getX() + 0.5, y + 0.1, spawn.getZ() + 0.5), Vec3.ZERO,
			player.getYRot(), player.getXRot(), TeleportTransition.DO_NOTHING));
		ctx.getSource().sendSuccess(() -> Component.translatable("commands.redplanet.tp.earth"), true);
		return 1;
	}

	private static int info(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		Level level = source.getLevel();
		Vec3 pos = source.getPosition();
		double gravity = PlanetEnvironment.gravity(level);
		double pressure = PlanetEnvironment.pressure(level, pos);
		double density = PlanetEnvironment.airDensity(level, pos) * 1.225;
		double dose = PlanetEnvironment.radiation(level, pos);

		if (MarsConditions.applies(level)) {
			double lat = MarsConditions.latitude(pos);
			double lon = MarsConditions.longitude(pos);
			double ls = MarsConditions.solarLongitude(level);
			long ticks = MarsConditions.clockTicks(level);
			double tK = MarsConditions.temperatureK(level, pos);
			String northSeason = MarsCalendar.seasonKey(ls, true);
			String southSeason = MarsCalendar.seasonKey(ls, false);
			line(source, Component.translatable("commands.redplanet.info.position", fmt(lat, 2), fmt(lon, 2),
				fmt(MarsConditions.elevation(pos), 0)));
			line(source, Component.translatable("commands.redplanet.info.time", MarsConditions.sol(level), MarsCalendar.formatLmst(ticks),
				fmt(ls, 1), Component.translatable("season.redplanet." + northSeason), Component.translatable("season.redplanet." + southSeason)));
			line(source, Component.translatable("commands.redplanet.info.weather", fmt(tK, 0), fmt(MarsClimate.kelvinToCelsius(tK), 0),
				fmt(MarsConditions.dustTau(level, pos), 2)));
		}
		line(source, Component.translatable("commands.redplanet.info.air", fmt(pressure, pressure < 10000 ? 0 : 1), fmt(density, 4),
			Component.translatable(PlanetEnvironment.breathable(level, pos) ? "commands.redplanet.info.breathable" : "commands.redplanet.info.unbreathable")));
		line(source, Component.translatable("commands.redplanet.info.physics", fmt(gravity, 4), fmt(gravity * 9.80665, 2), fmt(dose, 4)));
		level.getBiome(BlockPos.containing(pos)).unwrapKey().ifPresent(key ->
			line(source, Component.translatable("commands.redplanet.info.biome", key.identifier().toString())));
		return 1;
	}

	private static int dust(CommandContext<CommandSourceStack> ctx, String kind) {
		ServerLevel mars = ctx.getSource().getServer().getLevel(RPDimensions.MARS);
		if (mars == null) {
			ctx.getSource().sendFailure(Component.translatable("commands.redplanet.no_mars"));
			return 0;
		}
		MarsWeather weather = MarsWeather.get(mars);
		long now = mars.getGameTime();
		// Commanded storms grow in 10 s instead of hours, so the effect shows right away.
		long ramp = 200;
		DustStorm storm = switch (kind) {
			case "global" -> {
				DustStorm s = MarsWeather.global(now, mars.getRandom());
				yield new DustStorm(true, 0, 0, 0, s.peakTau(), now, ramp, now + ramp + (s.holdUntil() - s.startTime() - s.rampTicks()),
					s.decayTicks());
			}
			case "regional" -> {
				DustStorm s = MarsWeather.regional(mars, now, mars.getRandom());
				// Centre it on the caller when they are on Mars.
				Vec3 at = ctx.getSource().getLevel() == mars ? ctx.getSource().getPosition() : new Vec3(s.centerX(), 0, s.centerZ());
				yield new DustStorm(false, at.x, at.z, s.radius(), s.peakTau(), now, ramp,
					now + ramp + (s.holdUntil() - s.startTime() - s.rampTicks()), s.decayTicks());
			}
			default -> null;
		};
		weather.setStorm(mars, storm);
		ctx.getSource().sendSuccess(() -> storm == null ? Component.translatable("commands.redplanet.weather.clear")
			: Component.translatable("commands.redplanet.weather.storm", Component.translatable("commands.redplanet.weather." + kind),
				fmt(storm.peakTau(), 1), fmt((storm.endTime() - now) / (double) MarsCalendar.TICKS_PER_SOL, 1)), true);
		return 1;
	}

	private static int locate(CommandContext<CommandSourceStack> ctx) {
		String id = StringArgumentType.getString(ctx, "landmark");
		Landmark landmark = MarsLandmarks.byId(id).orElse(null);
		if (landmark == null) {
			ctx.getSource().sendFailure(Component.translatable("commands.redplanet.locate.unknown", id));
			return 0;
		}
		CommandSourceStack source = ctx.getSource();
		boolean onMars = MarsConditions.applies(source.getLevel());
		double x = nearestX(landmark.lon(), onMars ? source.getPosition().x : 0.0);
		double z = landmark.z();
		String tp = String.format(Locale.ROOT, "/redplanet tp mars %.2f %.2f", landmark.lat(), landmark.lon());
		MutableComponent coords = Component.literal("[" + (int) Math.floor(x) + ", ~, " + (int) Math.floor(z) + "]")
			.withStyle(style -> style.withColor(ChatFormatting.GREEN)
				.withClickEvent(new ClickEvent.SuggestCommand(tp))
				.withHoverEvent(new HoverEvent.ShowText(Component.translatable("commands.redplanet.locate.click"))));
		if (onMars) {
			double distance = Math.hypot(x - source.getPosition().x, z - source.getPosition().z);
			source.sendSuccess(() -> Component.translatable("commands.redplanet.locate.found_distance", landmark.name(), fmt(landmark.lat(), 2),
				fmt(landmark.lon(), 2), coords, (int) Math.round(distance)), false);
		} else {
			source.sendSuccess(() -> Component.translatable("commands.redplanet.locate.found", landmark.name(), fmt(landmark.lat(), 2),
				fmt(landmark.lon(), 2), coords), false);
		}
		return 1;
	}

	/** The block x of a longitude nearest to {@code fromX}, since the map repeats every circumference. */
	static double nearestX(double lonDeg, double fromX) {
		double x = MarsProjection.xOf(lonDeg);
		double c = MarsProjection.CIRCUMFERENCE_BLOCKS;
		return x + c * Math.round((fromX - x) / c);
	}

	private static void line(CommandSourceStack source, Component message) {
		source.sendSuccess(() -> message, false);
	}

	private static String fmt(double value, int decimals) {
		return String.format(Locale.ROOT, "%." + decimals + "f", value);
	}
}
