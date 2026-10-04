package io.github.avi130805.redplanet.client.starship;

import io.github.avi130805.redplanet.starship.geometry.StarshipGeometry;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

import org.joml.Quaternionf;

/** Everything a vehicle renderer needs for one frame (filled on the main thread, read when the frame is drawn). */
public class VehicleRenderState extends EntityRenderState {
	public final Quaternionf attitude = new Quaternionf();
	public final VehicleVisuals visuals = new VehicleVisuals();
	public StarshipGeometry.Lod lod = StarshipGeometry.Lod.HIGH;
	/** Seconds, for flicker. */
	public float time;
	/** The vehicle is a ship on Mars: its plumes are drawn for thin air. */
	public boolean onMars;
}
