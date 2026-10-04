package io.github.avi130805.redplanet.client.starship;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import io.github.avi130805.redplanet.registry.RPDimensions;
import io.github.avi130805.redplanet.starship.entity.VehicleEntity;
import io.github.avi130805.redplanet.starship.geometry.VehicleMesh;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.AABB;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Shared vehicle drawing: the procedural meshes with their animated joints, emitted as custom geometry every frame
 * (the full stack is about 14,000 quads up close and 4,000 far away).
 *
 * <p>A rocket thousands of blocks up would be clipped by the far plane and swallowed by the render-distance fog, so a
 * vehicle beyond the fog start is drawn as a <i>far impostor</i>: pulled toward the camera and shrunk by the same
 * factor, which keeps its size and direction on screen exactly (docs/api-notes/entity-rendering.md, section 2.9).
 */
public abstract class VehicleRenderer<T extends VehicleEntity> extends EntityRenderer<T, VehicleRenderState> {
	/** Light for the cabin interior: its own lamps. */
	protected static final int CABIN_LIGHT = 15728880;

	protected VehicleRenderer(EntityRendererProvider.Context context) {
		super(context);
		StarshipRenderTypes.registerTextures();
	}

	@Override
	public VehicleRenderState createRenderState() {
		return new VehicleRenderState();
	}

	@Override
	public void extractRenderState(T entity, VehicleRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		entity.attitude(partialTick, state.attitude);
		state.visuals.compute(entity, partialTick);
		state.lod = VehicleMeshes.lodFor(state.distanceToCameraSq);
		state.time = (entity.tickCount + partialTick) / 20.0F;
		state.onMars = RPDimensions.MARS.equals(entity.level().dimension());
	}

	@Override
	public boolean shouldRender(T entity, Frustum culler, double camX, double camY, double camZ, float partialTicks) {
		// In flight the attitude changes and the far impostor moves the drawing, so the vanilla box test would be wrong.
		return entity.isFlying() || super.shouldRender(entity, culler, camX, camY, camZ, partialTicks);
	}

	@Override
	protected AABB getBoundingBoxForCulling(T entity, float partialTicks) {
		return entity.getInterpolatedBoundingBox(partialTicks).inflate(6.0, 2.0, 6.0); // flaps, legs and fins stick out
	}

	@Override
	public void submit(VehicleRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.visuals.hidden) {
			return;
		}
		poseStack.pushPose();
		applyFarImpostor(state, poseStack, camera);
		poseStack.rotate(state.attitude);
		this.submitVehicle(state, poseStack, collector);
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	protected abstract void submitVehicle(VehicleRenderState state, PoseStack poseStack, SubmitNodeCollector collector);

	/** Pulls a distant vehicle in to just inside the fog and shrinks it by the same factor (same angular size). */
	private static void applyFarImpostor(VehicleRenderState state, PoseStack poseStack, CameraRenderState camera) {
		double rx = state.x - camera.pos.x;
		double ry = state.y - camera.pos.y;
		double rz = state.z - camera.pos.z;
		double distance = Math.sqrt(rx * rx + ry * ry + rz * rz);
		double limit = 0.9 * Math.min(camera.depthFar, camera.fogData.renderDistanceStart);
		if (limit > 16.0 && distance > limit) {
			double k = limit / distance;
			poseStack.translate(-rx * (1.0 - k), -ry * (1.0 - k), -rz * (1.0 - k));
			poseStack.scale((float) k, (float) k, (float) k);
		}
	}

	// --------------------------------------------------------------------------------------------- emission

	/** Pose of an animated part: a rotation by {@code angle} about its joint. */
	protected static Matrix4f jointPose(Matrix4f base, VehicleMesh.Joint joint, float angle) {
		if (angle == 0.0F) {
			return base;
		}
		return new Matrix4f(base).translate(joint.px(), joint.py(), joint.pz()).rotate(angle, joint.ax(), joint.ay(), joint.az())
			.translate(-joint.px(), -joint.py(), -joint.pz());
	}

	protected static Matrix3f jointNormal(Matrix3f base, VehicleMesh.Joint joint, float angle) {
		return angle == 0.0F ? base : new Matrix3f(base).rotate(angle, joint.ax(), joint.ay(), joint.az());
	}

	/**
	 * Emits a part's quads through the bulk vertex path. {@code inflate} pushes every vertex out along its normal (for
	 * overlays drawn on the hull).
	 */
	protected static void emit(float[] q, Matrix4f m, Matrix3f n, VertexConsumer buffer, int argb, int light, float inflate) {
		for (int i = 0; i < q.length; i += VehicleMesh.FLOATS_PER_VERTEX) {
			float nx0 = q[i + 3];
			float ny0 = q[i + 4];
			float nz0 = q[i + 5];
			float x = q[i] + nx0 * inflate;
			float y = q[i + 1] + ny0 * inflate;
			float z = q[i + 2] + nz0 * inflate;
			float px = Math.fma(m.m00(), x, Math.fma(m.m10(), y, Math.fma(m.m20(), z, m.m30())));
			float py = Math.fma(m.m01(), x, Math.fma(m.m11(), y, Math.fma(m.m21(), z, m.m31())));
			float pz = Math.fma(m.m02(), x, Math.fma(m.m12(), y, Math.fma(m.m22(), z, m.m32())));
			float nx = n.m00() * nx0 + n.m10() * ny0 + n.m20() * nz0;
			float ny = n.m01() * nx0 + n.m11() * ny0 + n.m21() * nz0;
			float nz = n.m02() * nx0 + n.m12() * ny0 + n.m22() * nz0;
			buffer.addVertex(px, py, pz, argb, q[i + 6], q[i + 7], OverlayTexture.NO_OVERLAY, light, nx, ny, nz);
		}
	}

	protected static int alpha(float a) {
		int value = Math.round(Math.max(0.0F, Math.min(1.0F, a)) * 255.0F);
		return value << 24 | 0xFFFFFF;
	}
}
