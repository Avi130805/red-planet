package io.github.avi130805.redplanet.starship.geometry;

/**
 * Texture atlas layout for the vehicle textures, at 16 pixels per metre (the same texel density as a
 * vanilla block). The texture generator ({@code tools/textures}) paints exactly these rectangles; the
 * layout is exported to {@code tools/textures/vehicle_uv_layout.json} by a unit test.
 */
public final class UvLayout {
	public static final int PIXELS_PER_METRE = 16;

	public enum Texture {
		SHIP(1024, 1024),
		BOOSTER(1024, 2048);

		public final int width;
		public final int height;

		Texture(int width, int height) {
			this.width = width;
			this.height = height;
		}
	}

	/** A pixel rectangle in a texture. (s, t) in [0,1] map from (x0, y0) to (x1, y1). */
	public enum Region {
		// --- Starship texture ---
		/** Nose cone unwrapped: s = angle around the axis (0 at the windward centre line, increasing toward +X), t = 0 at the tip. */
		SHIP_NOSE(Texture.SHIP, 0, 0, 452, 312),
		/** Barrel unwrapped: s as above, t = 0 at the top of the barrel, 1 at the skirt bottom. */
		SHIP_BARREL(Texture.SHIP, 0, 312, 452, 864),
		SHIP_AFT_FLAP_WINDWARD(Texture.SHIP, 456, 0, 528, 192),
		SHIP_AFT_FLAP_LEEWARD(Texture.SHIP, 532, 0, 604, 192),
		SHIP_AFT_FLAP_EDGE(Texture.SHIP, 608, 0, 616, 192),
		SHIP_FORE_FLAP_WINDWARD(Texture.SHIP, 620, 0, 668, 128),
		SHIP_FORE_FLAP_LEEWARD(Texture.SHIP, 672, 0, 720, 128),
		SHIP_FORE_FLAP_EDGE(Texture.SHIP, 724, 0, 732, 128),
		/** Aft bulkhead / skirt bottom, planar: s = x, t = z over the hull diameter. */
		SHIP_AFT_DISC(Texture.SHIP, 456, 200, 600, 344),
		SHIP_ENGINE_SL_OUTER(Texture.SHIP, 608, 200, 672, 232),
		SHIP_ENGINE_SL_INNER(Texture.SHIP, 608, 236, 672, 268),
		SHIP_ENGINE_VAC_OUTER(Texture.SHIP, 680, 200, 808, 264),
		SHIP_ENGINE_VAC_INNER(Texture.SHIP, 680, 268, 808, 332),
		SHIP_LEG(Texture.SHIP, 816, 200, 848, 296),
		SHIP_LEG_FOOT(Texture.SHIP, 852, 200, 884, 232),

		// --- Super Heavy texture ---
		BOOSTER_BARREL(Texture.BOOSTER, 0, 0, 452, 1136),
		BOOSTER_HOT_STAGE_RING(Texture.BOOSTER, 0, 1140, 452, 1172),
		BOOSTER_HOT_STAGE_RING_TOP(Texture.BOOSTER, 456, 300, 600, 444),
		BOOSTER_AFT_DISC(Texture.BOOSTER, 456, 0, 600, 144),
		/** Grid fin lattice, alpha-tested. */
		BOOSTER_GRID_FIN(Texture.BOOSTER, 456, 148, 536, 196),
		BOOSTER_GRID_FIN_EDGE(Texture.BOOSTER, 540, 148, 548, 196),
		BOOSTER_ENGINE_OUTER(Texture.BOOSTER, 608, 0, 672, 32),
		BOOSTER_ENGINE_INNER(Texture.BOOSTER, 608, 36, 672, 68);

		public final Texture texture;
		public final int x0;
		public final int y0;
		public final int x1;
		public final int y1;

		Region(Texture texture, int x0, int y0, int x1, int y1) {
			this.texture = texture;
			this.x0 = x0;
			this.y0 = y0;
			this.x1 = x1;
			this.y1 = y1;
		}

		public float u(double s) {
			return (float) ((this.x0 + (this.x1 - this.x0) * s) / this.texture.width);
		}

		public float v(double t) {
			return (float) ((this.y0 + (this.y1 - this.y0) * t) / this.texture.height);
		}

		public int widthPx() {
			return this.x1 - this.x0;
		}

		public int heightPx() {
			return this.y1 - this.y0;
		}
	}

	private UvLayout() {
	}
}
