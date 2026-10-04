package io.github.avi130805.redplanet.command;

import java.util.Locale;
import java.util.Optional;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import io.github.avi130805.redplanet.mars.geo.MarsLandmarks;
import io.github.avi130805.redplanet.mars.geo.MarsProjection;
import io.github.avi130805.redplanet.registry.RPDimensions;
import io.github.avi130805.redplanet.registry.RPRegistries;
import io.github.avi130805.redplanet.registry.RPStarship;
import io.github.avi130805.redplanet.starship.entity.StarshipEntity;
import io.github.avi130805.redplanet.starship.entity.SuperHeavyEntity;
import io.github.avi130805.redplanet.starship.flight.FlightProfile;
import io.github.avi130805.redplanet.starship.flight.Pacing;
import io.github.avi130805.redplanet.starship.flight.PhaseDef;
import io.github.avi130805.redplanet.starship.item.VehicleItem;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /redplanet starship ...}: flying without the mission-control screen.
 * <ul>
 * <li>{@code launch [<pacing>] [<landmark> | at <lat> <lon>]}: launch the ship you ride, to Mars from Earth or home from
 * Mars, landing at a Mars landmark or coordinates (default Gale crater) or at the pad you launched from.</li>
 * <li>{@code launch profile <id> [<pacing>]}: any flight profile (operators).</li>
 * <li>{@code skip}: skip the rest of the current phase.</li>
 * <li>{@code status}: where the flight is.</li>
 * <li>{@code spawn ship|stack}: place a ship, or a ship on a booster, where you stand (operators).</li>
 * </ul>
 */
final class StarshipCommands {
	private StarshipCommands() {
	}

	static LiteralArgumentBuilder<CommandSourceStack> build() {
		return Commands.literal("starship")
			.then(Commands.literal("launch")
				.executes(ctx -> launch(ctx, Pacing.STANDARD, null))
				.then(Commands.literal("profile")
					.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
					.then(Commands.argument("id", IdentifierArgument.id())
						.executes(ctx -> launchProfile(ctx, IdentifierArgument.getId(ctx, "id"), Pacing.STANDARD))
						.then(Commands.argument("pacing", StringArgumentType.word())
							.suggests((c, b) -> SharedSuggestionProvider.suggest(pacingNames(), b))
							.executes(ctx -> launchProfile(ctx, IdentifierArgument.getId(ctx, "id"), pacing(ctx))))))
				.then(Commands.argument("pacing", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(pacingNames(), b))
					.executes(ctx -> launch(ctx, pacing(ctx), null))
					.then(Commands.literal("at")
						.then(Commands.argument("lat", DoubleArgumentType.doubleArg(-90.0, 90.0))
							.then(Commands.argument("lon", DoubleArgumentType.doubleArg(-360.0, 360.0))
								.executes(ctx -> launchAt(ctx, pacing(ctx), DoubleArgumentType.getDouble(ctx, "lat"),
									DoubleArgumentType.getDouble(ctx, "lon"))))))
					.then(Commands.argument("landmark", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(MarsLandmarks.ALL.stream().map(MarsLandmarks.Landmark::id), b))
						.executes(ctx -> launch(ctx, pacing(ctx), StringArgumentType.getString(ctx, "landmark"))))))
			.then(Commands.literal("skip").executes(StarshipCommands::skip))
			.then(Commands.literal("status").executes(StarshipCommands::status))
			.then(Commands.literal("spawn")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("ship").executes(ctx -> spawn(ctx, false)))
				.then(Commands.literal("stack").executes(ctx -> spawn(ctx, true))));
	}

	private static String[] pacingNames() {
		return new String[]{"short", "standard", "long"};
	}

	private static Pacing pacing(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "pacing").toLowerCase(Locale.ROOT);
		for (Pacing p : Pacing.values()) {
			if (p.getSerializedName().equals(name)) {
				return p;
			}
		}
		return Pacing.STANDARD;
	}

	private static Optional<StarshipEntity> riddenShip(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (player.getVehicle() instanceof StarshipEntity ship) {
			return Optional.of(ship);
		}
		source.sendFailure(Component.translatable("commands.redplanet.starship.not_aboard"));
		return Optional.empty();
	}

	private static int launch(CommandContext<CommandSourceStack> ctx, Pacing pacing, String landmark) throws CommandSyntaxException {
		Optional<StarshipEntity> ship = riddenShip(ctx.getSource());
		if (ship.isEmpty()) {
			return 0;
		}
		ServerLevel level = (ServerLevel) ship.get().level();
		Identifier profile = RPDimensions.MARS.equals(level.dimension()) ? RPRegistries.MARS_TO_EARTH : RPRegistries.EARTH_TO_MARS;
		Vec3 site = null;
		if (landmark != null && profile.equals(RPRegistries.EARTH_TO_MARS)) {
			Optional<MarsLandmarks.Landmark> found = MarsLandmarks.byId(landmark);
			if (found.isEmpty()) {
				ctx.getSource().sendFailure(Component.translatable("commands.redplanet.locate.unknown", landmark));
				return 0;
			}
			site = new Vec3(found.get().x(), 0.0, found.get().z());
		}
		return doLaunch(ctx.getSource(), ship.get(), level, profile, pacing, site);
	}

	private static int launchAt(CommandContext<CommandSourceStack> ctx, Pacing pacing, double lat, double lon) throws CommandSyntaxException {
		Optional<StarshipEntity> ship = riddenShip(ctx.getSource());
		if (ship.isEmpty()) {
			return 0;
		}
		ServerLevel level = (ServerLevel) ship.get().level();
		if (RPDimensions.MARS.equals(level.dimension())) {
			return doLaunch(ctx.getSource(), ship.get(), level, RPRegistries.MARS_TO_EARTH, pacing, null);
		}
		Vec3 site = new Vec3(MarsProjection.xOf(lon), 0.0, MarsProjection.zOf(lat));
		return doLaunch(ctx.getSource(), ship.get(), level, RPRegistries.EARTH_TO_MARS, pacing, site);
	}

	private static int launchProfile(CommandContext<CommandSourceStack> ctx, Identifier profile, Pacing pacing) throws CommandSyntaxException {
		Optional<StarshipEntity> ship = riddenShip(ctx.getSource());
		if (ship.isEmpty()) {
			return 0;
		}
		return doLaunch(ctx.getSource(), ship.get(), (ServerLevel) ship.get().level(), profile, pacing, null);
	}

	private static int doLaunch(CommandSourceStack source, StarshipEntity ship, ServerLevel level, Identifier profileId, Pacing pacing, Vec3 site) {
		Optional<FlightProfile> profile = RPRegistries.profile(level.registryAccess(), profileId);
		if (profile.isEmpty()) {
			source.sendFailure(Component.translatable("commands.redplanet.starship.no_profile", profileId.toString()));
			return 0;
		}
		Optional<Component> problem = ship.launchProblem(level, profile.get());
		if (problem.isPresent()) {
			source.sendFailure(Component.translatable("commands.redplanet.starship.launch_failed", problem.get()));
			return 0;
		}
		if (!ship.launch(level, profileId, pacing, site)) {
			return 0;
		}
		Component destination = destinationName(profile.get());
		// To the crew only: a launch isn't news for every operator's chat.
		source.sendSuccess(() -> Component.translatable("commands.redplanet.starship.launch", destination,
			Component.translatable("pacing.redplanet." + pacing.getSerializedName())), false);
		return 1;
	}

	private static Component destinationName(FlightProfile profile) {
		return profile.destination().equals(Level.OVERWORLD) ? Component.translatable("destination.redplanet.overworld")
			: profile.destination().equals(RPDimensions.MARS) ? Component.translatable("destination.redplanet.mars")
			: Component.literal(profile.destination().identifier().toString());
	}

	private static int skip(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Optional<StarshipEntity> ship = riddenShip(ctx.getSource());
		if (ship.isEmpty()) {
			return 0;
		}
		Optional<FlightProfile> profile = ship.get().profile();
		if (!ship.get().isFlying() || profile.isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("commands.redplanet.starship.not_flying"));
			return 0;
		}
		int next = Math.min(ship.get().phase() + 1, profile.get().phases().size() - 1);
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		int missing = ship.get().voteSkip(player);
		if (missing > 0) {
			Component vote = Component.translatable("commands.redplanet.starship.skip_vote", player.getDisplayName(), missing);
			for (net.minecraft.world.entity.Entity passenger : ship.get().getPassengers()) {
				if (passenger instanceof ServerPlayer crew) {
					crew.sendOverlayMessage(vote);
				}
			}
			return 1;
		}
		String nextId = profile.get().phases().get(next).id();
		ctx.getSource().sendSuccess(() -> Component.translatable("commands.redplanet.starship.skip", nextId), false);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Optional<StarshipEntity> ship = riddenShip(ctx.getSource());
		if (ship.isEmpty()) {
			return 0;
		}
		StarshipEntity s = ship.get();
		Optional<FlightProfile> profile = s.profile();
		if (!s.isFlying() || profile.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.translatable("commands.redplanet.starship.status.ground", Math.round(s.getX()),
				Math.round(s.getY()), Math.round(s.getZ())), false);
			return 1;
		}
		FlightProfile p = profile.get();
		int phase = Math.min(s.phase(), p.phases().size() - 1);
		PhaseDef def = p.phases().get(phase);
		double t = s.missionTime(0.0F);
		ctx.getSource().sendSuccess(() -> Component.translatable("commands.redplanet.starship.status", s.profileId().map(Identifier::toString).orElse("?"),
			phase + 1, p.phases().size(), def.id(), formatMissionTime(t)), false);
		return 1;
	}

	/** T+/- formatted as d:hh:mm:ss or hh:mm:ss. */
	static String formatMissionTime(double t) {
		String sign = t < 0 ? "-" : "+";
		long s = (long) Math.floor(Math.abs(t));
		long days = s / 86400;
		long h = s / 3600 % 24;
		long m = s / 60 % 60;
		long sec = s % 60;
		return days > 0 ? String.format(Locale.ROOT, "%s%dd %02d:%02d:%02d", sign, days, h, m, sec)
			: String.format(Locale.ROOT, "%s%02d:%02d:%02d", sign, h, m, sec);
	}

	private static int spawn(CommandContext<CommandSourceStack> ctx, boolean stack) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		ServerLevel level = ctx.getSource().getLevel();
		BlockPos ground = player.blockPosition().below();
		if (stack) {
			if (!VehicleItem.hasRoom(level, ground, RPStarship.SUPER_HEAVY)
				|| !VehicleItem.hasRoom(level, ground.above((int) Math.ceil(VehicleItem.height(RPStarship.SUPER_HEAVY))), RPStarship.STARSHIP)) {
				ctx.getSource().sendFailure(Component.translatable("commands.redplanet.starship.no_room", Component.translatable("entity.redplanet.super_heavy")));
				return 0;
			}
			SuperHeavyEntity booster = VehicleItem.place(level, ground, RPStarship.SUPER_HEAVY);
			StarshipEntity ship = RPStarship.STARSHIP.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			if (booster == null || ship == null) {
				return 0;
			}
			ship.stackOn(booster);
			level.addFreshEntity(ship);
			ctx.getSource().sendSuccess(() -> Component.translatable("commands.redplanet.starship.spawned", Component.translatable("entity.redplanet.super_heavy"),
				ground.getX(), ground.getY() + 1, ground.getZ()), true);
			return 1;
		}
		if (!VehicleItem.hasRoom(level, ground, RPStarship.STARSHIP)) {
			ctx.getSource().sendFailure(Component.translatable("commands.redplanet.starship.no_room", Component.translatable("entity.redplanet.starship")));
			return 0;
		}
		VehicleItem.place(level, ground, RPStarship.STARSHIP);
		ctx.getSource().sendSuccess(() -> Component.translatable("commands.redplanet.starship.spawned", Component.translatable("entity.redplanet.starship"),
			ground.getX(), ground.getY() + 1, ground.getZ()), true);
		return 1;
	}
}
