package io.github.avi130805.redplanet.client.starship;

import java.io.IOException;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;

import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;

/**
 * Like vanilla's {@code MipmappedTexture}, but averaging alpha plainly ({@link MipmapStrategy#MEAN}). The frost overlays
 * are soft translucent layers; the default strategy treats any texture with fully transparent pixels as a cutout and
 * rescales its alpha at the smaller levels, which left a faint frost tint over the whole hull at a distance.
 */
public class MeanMipmappedTexture extends ReloadableTexture {
	private final int maxMipLevel;

	public MeanMipmappedTexture(Identifier location, int maxMipLevel) {
		super(location);
		this.maxMipLevel = maxMipLevel;
	}

	@Override
	public TextureContents loadContents(ResourceManager resourceManager) throws IOException {
		return TextureContents.load(resourceManager, this.resourceId());
	}

	@Override
	protected void setSampler(TextureContents contents) {
		AddressMode address = contents.clamp() ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
		this.sampler = RenderSystem.getSamplerCache().getSampler(address, address, FilterMode.LINEAR, contents.blur() ? FilterMode.LINEAR : FilterMode.NEAREST,
			true);
	}

	@Override
	protected void doLoad(NativeImage image) {
		GpuDevice device = RenderSystem.getDevice();
		this.close();
		int lowest = Math.min(Integer.lowestOneBit(image.getWidth()), Integer.lowestOneBit(image.getHeight()));
		int levels = Math.min(this.maxMipLevel, Mth.log2(Math.min(Math.min(image.getWidth(), image.getHeight()), lowest)));
		NativeImage[] mips = MipmapGenerator.generateMipLevels(this.resourceId(), new NativeImage[]{image}, levels, MipmapStrategy.MEAN, 0.0F,
			image.computeTransparency());
		this.texture = device.createTexture(this.resourceId()::toString, 5, GpuFormat.RGBA8_UNORM, image.getWidth(), image.getHeight(), 1, mips.length);
		this.textureView = device.createTextureView(this.texture);
		for (int level = 0; level < mips.length; level++) {
			device.createCommandEncoder().writeToTexture(this.texture, mips[level], level, 0, 0, 0);
		}
		for (int level = 1; level < mips.length; level++) {
			mips[level].close();
		}
	}
}
