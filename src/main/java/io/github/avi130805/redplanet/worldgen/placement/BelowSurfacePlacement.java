package io.github.avi130805.redplanet.worldgen.placement;

import java.util.function.Consumer;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.IntProviders;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/**
 * {@code redplanet:below_surface}: moves a position to a random depth below the local surface. The cave biomes live
 * in depth windows under terrain that spans 290 blocks of height (Hellas to Olympus Mons), so placing cave
 * features relative to the surface wastes far fewer attempts than an absolute height range. Positions that would
 * fall below the world are dropped.
 *
 * @param depth blocks below the {@code WORLD_SURFACE_WG} height
 */
public record BelowSurfacePlacement(IntProvider depth) implements PlacementModifier {
	public static final MapCodec<BelowSurfacePlacement> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		IntProviders.codec(0, 448).fieldOf("depth").forGetter(BelowSurfacePlacement::depth)
	).apply(i, BelowSurfacePlacement::new));

	@Override
	public void modify(PlacementContext context, RandomSource random, BlockPos origin, Consumer<BlockPos> output) {
		int surface = context.getHeight(Heightmap.Types.WORLD_SURFACE_WG, origin.getX(), origin.getZ());
		int y = surface - this.depth.sample(random);
		if (y > context.getMinY()) {
			output.accept(new BlockPos(origin.getX(), y, origin.getZ()));
		}
	}

	@Override
	public MapCodec<BelowSurfacePlacement> codec() {
		return CODEC;
	}
}
