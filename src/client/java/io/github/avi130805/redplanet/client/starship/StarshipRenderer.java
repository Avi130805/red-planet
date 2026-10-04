package io.github.avi130805.redplanet.client.starship;

import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;

import io.github.avi130805.redplanet.starship.entity.StarshipEntity;
import io.github.avi130805.redplanet.starship.geometry.StarshipGeometry;
import io.github.avi130805.redplanet.starship.geometry.VehicleMesh;
import io.github.avi130805.redplanet.starship.geometry.VehiclePart;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Draws the ship: hull, crew cabin, flaps, legs and engines, frost, cabin lights, plumes and entry plasma. */
public class StarshipRenderer extends VehicleRenderer<StarshipEntity> {
	private static final int FULL_BRIGHT = 15728880;

	public StarshipRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 4.0F;
	}

	@Override
	protected void submitVehicle(VehicleRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
		VehicleMesh mesh = VehicleMeshes.ship(state.lod);
		VehicleVisuals v = state.visuals;
		int light = state.lightCoords;
		boolean interior = state.lod == StarshipGeometry.Lod.HIGH;
		collector.submitCustomGeometry(poseStack, StarshipRenderTypes.hull(StarshipRenderTypes.SHIP_TEXTURE), (pose, buffer) -> {
			Matrix4f m = pose.pose();
			Matrix3f n = pose.normal();
			for (Map.Entry<VehiclePart, VehicleMesh.PartMesh> entry : mesh.parts().entrySet()) {
				VehicleMesh.PartMesh part = entry.getValue();
				VehiclePart id = entry.getKey();
				switch (id) {
					case SHIP_CABIN -> {
						if (interior) {
							emit(part.quads(), m, n, buffer, 0xFFFFFFFF, CABIN_LIGHT, 0.0F);
						}
					}
					case SHIP_FORE_FLAP_LEFT, SHIP_FORE_FLAP_RIGHT -> emitJoint(part, v.flapsFore, m, n, buffer, light);
					case SHIP_AFT_FLAP_LEFT, SHIP_AFT_FLAP_RIGHT -> emitJoint(part, v.flapsAft, m, n, buffer, light);
					case SHIP_ENGINE_SL_0 -> emitJoint(part, v.gimbal[0], m, n, buffer, light);
					case SHIP_ENGINE_SL_1 -> emitJoint(part, v.gimbal[1], m, n, buffer, light);
					case SHIP_ENGINE_SL_2 -> emitJoint(part, v.gimbal[2], m, n, buffer, light);
					case SHIP_LEG_0, SHIP_LEG_1, SHIP_LEG_2, SHIP_LEG_3, SHIP_LEG_4, SHIP_LEG_5 -> emitLeg(part, v.legs, m, n, buffer, light);
					default -> emit(part.quads(), m, n, buffer, 0xFFFFFFFF, light, 0.0F);
				}
			}
		});
		float[] hull = mesh.part(VehiclePart.SHIP_HULL).quads();
		if (v.frost > 0.01F) {
			int color = alpha(v.frost);
			collector.submitCustomGeometry(poseStack, StarshipRenderTypes.frost(StarshipRenderTypes.SHIP_FROST_TEXTURE),
				(pose, buffer) -> emit(hull, pose.pose(), pose.normal(), buffer, color, light, 0.03F));
		}
		if (state.lod != StarshipGeometry.Lod.LOW) {
			collector.submitCustomGeometry(poseStack, StarshipRenderTypes.lights(),
				(pose, buffer) -> emit(hull, pose.pose(), pose.normal(), buffer, 0xFFFFFFFF, FULL_BRIGHT, 0.02F));
		}
		if (v.engines > 0) {
			collector.submitCustomGeometry(poseStack, StarshipRenderTypes.GLOW, (pose, buffer) -> {
				Matrix4f m = pose.pose();
				double[][] engines = StarshipGeometry.shipEnginePositions();
				int seaLevel = Math.min(3, v.engines);
				boolean vacuum = v.engines >= 6 || v.engines > 3;
				for (int i = 0; i < seaLevel; i++) {
					VehicleMesh.PartMesh part = mesh.part(VehiclePart.shipSeaLevelEngine(i));
					Matrix4f gm = jointPose(m, part.joint(), v.gimbal[i]);
					PlumeEmitter.engine(gm, buffer, (float) engines[i][0], (float) StarshipGeometry.SL_EXIT_Y, (float) engines[i][1],
						(float) StarshipGeometry.SL_EXIT_RADIUS, 1.0F, v.thinAir, state.time, i);
				}
				if (vacuum) {
					for (int i = 3; i < 6; i++) {
						PlumeEmitter.engine(m, buffer, (float) engines[i][0], (float) StarshipGeometry.VAC_EXIT_Y, (float) engines[i][1],
							(float) StarshipGeometry.VAC_EXIT_RADIUS, 1.0F, v.thinAir, state.time, i);
					}
				}
			});
		}
		if (v.plasma > 0.01F) {
			collector.submitCustomGeometry(poseStack, StarshipRenderTypes.GLOW, (pose, buffer) -> PlumeEmitter.plasma(pose.pose(), buffer,
				(float) StarshipGeometry.HULL_RADIUS, (float) StarshipGeometry.SHIP_HEIGHT, v.plasma, state.time));
		}
	}

	private static void emitJoint(VehicleMesh.PartMesh part, float angle, Matrix4f m, Matrix3f n, com.mojang.blaze3d.vertex.VertexConsumer buffer,
			int light) {
		emit(part.quads(), jointPose(m, part.joint(), angle), jointNormal(n, part.joint(), angle), buffer, 0xFFFFFFFF, light, 0.0F);
	}

	/** Telescoping legs: the first half of the deployment slides them down, the second half splays them out. */
	private static void emitLeg(VehicleMesh.PartMesh part, float deploy, Matrix4f m, Matrix3f n, com.mojang.blaze3d.vertex.VertexConsumer buffer,
			int light) {
		float slide = (float) StarshipGeometry.LEG_EXTENSION * Math.clamp(deploy * 2.0F, 0.0F, 1.0F);
		float splay = (float) Math.toRadians(StarshipGeometry.LEG_DEPLOY_DEG) * Math.clamp(deploy * 2.0F - 1.0F, 0.0F, 1.0F);
		VehicleMesh.Joint j = part.joint();
		float py = j.py() - slide;
		Matrix4f lm = new Matrix4f(m).translate(j.px(), py, j.pz()).rotate(splay, j.ax(), j.ay(), j.az()).translate(-j.px(), -py, -j.pz())
			.translate(0.0F, -slide, 0.0F);
		emit(part.quads(), lm, jointNormal(n, j, splay), buffer, 0xFFFFFFFF, light, 0.0F);
	}
}
