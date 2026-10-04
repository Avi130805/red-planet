package io.github.avi130805.redplanet.client.sky;

import org.joml.Matrix3f;
import org.joml.Vector3f;

import io.github.avi130805.redplanet.mars.astro.MarsSkyModel;

import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;

/**
 * One frame of the Mars sky, computed during extraction and attached to vanilla's sky render state. Directions are
 * unit vectors in world axes (x east, y up, z south).
 */
public record MarsSkyData(
	Vector3f sun, float sunDiameterDeg,
	Vector3f phobos, float phobosDiameterDeg, float phobosIllumination, boolean phobosEclipsed,
	Vector3f deimos, float deimosIllumination, boolean deimosEclipsed,
	Vector3f earth, float earthMagnitude, Vector3f moon,
	Matrix3f starRotation,
	MarsSkyModel.Look look, float bodyScale
) {
	public static final RenderStateDataKey<MarsSkyData> KEY = RenderStateDataKey.create(() -> "redplanet:mars_sky");
}
