package io.github.avi130805.redplanet.client.starship;

import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import io.github.avi130805.redplanet.starship.entity.SuperHeavyEntity;
import io.github.avi130805.redplanet.starship.geometry.StarshipGeometry;
import io.github.avi130805.redplanet.starship.geometry.VehicleMesh;
import io.github.avi130805.redplanet.starship.geometry.VehiclePart;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Draws Super Heavy: hull, hot-staging ring, grid fins, 33 Raptors, frost and plumes. */
public class SuperHeavyRenderer extends VehicleRenderer<SuperHeavyEntity> {
	public SuperHeavyRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 4.5F;
	}

	@Override
	protected void submitVehicle(VehicleRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
		VehicleMesh mesh = VehicleMeshes.booster(state.lod);
		VehicleVisuals v = state.visuals;
		int light = state.lightCoords;
		collector.submitCustomGeometry(poseStack, StarshipRenderTypes.hull(StarshipRenderTypes.BOOSTER_TEXTURE), (pose, buffer) -> {
			Matrix4f m = pose.pose();
			Matrix3f n = pose.normal();
			for (Map.Entry<VehiclePart, VehicleMesh.PartMesh> entry : mesh.parts().entrySet()) {
				VehicleMesh.PartMesh part = entry.getValue();
				switch (entry.getKey()) {
					case BOOSTER_GRID_FIN_0, BOOSTER_GRID_FIN_1, BOOSTER_GRID_FIN_2 -> emitJoint(part, v.fins, m, n, buffer, light);
					case BOOSTER_ENGINES_CENTER -> emitJoint(part, v.gimbal[0], m, n, buffer, light);
					default -> emit(part.quads(), m, n, buffer, 0xFFFFFFFF, light, 0.0F);
				}
			}
		});
		if (v.frost > 0.01F) {
			float[] hull = mesh.part(VehiclePart.BOOSTER_HULL).quads();
			int color = alpha(v.frost);
			collector.submitCustomGeometry(poseStack, StarshipRenderTypes.frost(StarshipRenderTypes.BOOSTER_FROST_TEXTURE),
				(pose, buffer) -> emit(hull, pose.pose(), pose.normal(), buffer, color, light, 0.03F));
		}
		if (v.stagingFlash > 0.01F) {
			// Hot staging: the ship lights its engines while still on the booster, and the flame bursts out of the vented ring.
			collector.submitCustomGeometry(poseStack, StarshipRenderTypes.GLOW, (pose, buffer) ->
				PlumeEmitter.stagingFlash(pose.pose(), buffer, (float) StarshipGeometry.HULL_RADIUS, (float) StarshipGeometry.BOOSTER_BARREL_HEIGHT,
					(float) StarshipGeometry.BOOSTER_RING_HEIGHT, v.stagingFlash, state.time));
		}
		if (v.engines > 0) {
			collector.submitCustomGeometry(poseStack, StarshipRenderTypes.GLOW, (pose, buffer) -> {
				Matrix4f m = pose.pose();
				double[][] engines = StarshipGeometry.boosterEnginePositions();
				int lit = Math.min(engines.length, v.engines);
				float y = (float) StarshipGeometry.BOOSTER_ENGINE_EXIT_Y;
				for (int i = 0; i < lit; i++) {
					PlumeEmitter.engine(m, buffer, (float) engines[i][0], y, (float) engines[i][1], (float) engines[i][2], 0.8F, v.thinAir, state.time, i);
				}
				if (lit >= 13) {
					PlumeEmitter.cluster(m, buffer, y, lit >= 33 ? 4.2F : 2.8F, 1.0F, v.thinAir);
				}
			});
		}
	}

	private static void emitJoint(VehicleMesh.PartMesh part, float angle, Matrix4f m, Matrix3f n, VertexConsumer buffer, int light) {
		emit(part.quads(), jointPose(m, part.joint(), angle), jointNormal(n, part.joint(), angle), buffer, 0xFFFFFFFF, light, 0.0F);
	}
}
