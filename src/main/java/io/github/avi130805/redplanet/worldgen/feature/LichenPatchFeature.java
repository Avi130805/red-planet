package io.github.avi130805.redplanet.worldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.codec.RegistryCodecs;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * {@code redplanet:lichen_patch}: coats the rock faces around a point in a cave with a multiface crust (areolichen),
 * densest at the centre and thinning out irregularly. Vanilla's {@code multiface_growth} only reaches faces right
 * beside its origin, so in sparse Martian caves it rarely lands at all; this makes the lichen hollows' glowing walls.
 *
 * @param block the multiface block to grow
 * @param radius patch radius in blocks
 * @param coverage chance a face at the centre is covered (falls off toward the rim)
 * @param floorChance relative chance for floor faces (moss owns the floors)
 * @param canBePlacedOn rock it grows on
 */
public record LichenPatchFeature(Block block, int radius, float coverage, float floorChance, HolderSet<Block> canBePlacedOn) implements Feature {
	public static final MapCodec<LichenPatchFeature> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		BuiltInRegistries.BLOCK.byNameCodec().fieldOf("block").forGetter(LichenPatchFeature::block),
		Codec.intRange(1, 8).fieldOf("radius").forGetter(LichenPatchFeature::radius),
		Codec.floatRange(0.0F, 1.0F).fieldOf("coverage").forGetter(LichenPatchFeature::coverage),
		Codec.floatRange(0.0F, 1.0F).optionalFieldOf("floor_chance", 0.2F).forGetter(LichenPatchFeature::floorChance),
		RegistryCodecs.holderSet(Registries.BLOCK).fieldOf("can_be_placed_on").forGetter(LichenPatchFeature::canBePlacedOn)
	).apply(i, LichenPatchFeature::new));

	@Override
	public MapCodec<LichenPatchFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		if (!(this.block instanceof MultifaceBlock) || !level.getBlockState(origin).isAir()) {
			return false;
		}
		int r = this.radius;
		// A lumpy patch: per-patch random stretch, so patches aren't spheres.
		double sx = 0.7 + random.nextDouble() * 0.6;
		double sy = 0.6 + random.nextDouble() * 0.5;
		double sz = 0.7 + random.nextDouble() * 0.6;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
		int placed = 0;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					double d = Math.sqrt((dx * dx) / (sx * sx) + (dy * dy) / (sy * sy) + (dz * dz) / (sz * sz)) / r;
					if (d > 1.0) {
						continue;
					}
					pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					BlockState here = level.getBlockState(pos);
					if (!here.isAir() && !here.is(this.block)) {
						continue;
					}
					double chance = this.coverage * (1.0 - d * d);
					BlockState state = here.is(this.block) ? here : this.block.defaultBlockState();
					boolean any = false;
					for (Direction face : Direction.values()) {
						double c = face == Direction.DOWN ? chance * this.floorChance : chance;
						if (random.nextDouble() >= c || MultifaceBlock.hasFace(state, face)) {
							continue;
						}
						if (level.getBlockState(neighbour.setWithOffset(pos, face)).is(this.canBePlacedOn)) {
							state = state.setValue(MultifaceBlock.getFaceProperty(face), true);
							any = true;
						}
					}
					if (any) {
						level.setBlock(pos, state, 2);
						placed++;
					}
				}
			}
		}
		return placed > 0;
	}
}
