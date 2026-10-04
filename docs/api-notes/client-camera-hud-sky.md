# Client API notes: camera, HUD, screens, sky, fog, sound, input

Minecraft Java 26.3 (unobfuscated), Fabric API 0.161.0+26.3. Researched 2026-10-04 by reading the decompiled
sources. **Nothing here has been compiled yet.** Items marked **UNVERIFIED** were inferred rather than read in the
source. Everything else cites the file and approximate line (±3).

Path prefixes used below:

| Prefix | Location |
|---|---|
| `C/` | `/home/user/mcsrc/client/`: client-only classes. They were decompiled with Fabric's transitive access wideners applied; a comment "Access widened by fabric-transitive-access-wideners-v1" marks a widened member. |
| `M/` | `/home/user/mcsrc/common/` |
| `F/` | `/home/user/mcsrc/fabric-api/src/` |
| `A/` | `/home/user/mcsrc/jar-client/assets/minecraft/` |
| `D/` | `/home/user/mcsrc/jar-client/data/minecraft/` |

Section 11 holds the concrete recommendations and code skeletons for redplanet.

---

## 0. One client frame in 26.3

`Minecraft.runTick` runs the client ticks (20 Hz), then `soundManager.updateSource(gameRenderer.mainCamera())`
(`C/net/minecraft/client/Minecraft.java` ~1249), then `renderFrame(advanceGameTime)` (~1292). Inside it:

1. `this.gui.update()`
2. `this.gameRenderer.update(this.deltaTracker)` → `mainCamera.update(deltaTracker)` (`C/.../renderer/GameRenderer.java` ~420-423)
3. `this.pick(worldPartialTicks)`
4. `this.gameRenderer.extract(this.deltaTracker, advanceGameTime)` (~436-462). This does `extractWindow()`,
   `extractOptions()`, `extractCamera(...)` (camera render state plus fog), `minecraft.levelExtractor.extract(...)`
   (entities, sky, weather and so on), then `minecraft.gui.extractRenderState(...)` (HUD and screen into the GUI
   render state).
5. `this.gameRenderer.render()` (~463-519). This does `renderLevel()` (projection, fog UBO, then
   `levelRenderer.render(...)`, a frame graph of clear → sky → main), then `render3dHud` (hand, screen effects),
   then `guiRenderer.render()`.

All of it runs on one thread. `RenderSystem.initRenderThread()` is called from `C/.../main/Main.java` ~262, and
`LevelExtractor.applyFrustum` throws unless `Minecraft.getInstance().isSameThread()`
(`C/.../renderer/extract/LevelExtractor.java` ~472-475).

**Rule:** read and mutate game objects during *update* and *extract*; consume only render states during *render*.
Fabric lets mods attach data to vanilla render states with `FabricRenderState#setData/getData(RenderStateDataKey<T>)`
(`F/net/fabricmc/fabric/api/client/rendering/v1/FabricRenderState.java` ~115-145, `RenderStateDataKey.java`).
`SkyRenderState`'s extra data is cleared every frame. `LevelExtractor.extract` calls `levelRenderState.reset()`
(~123), and Fabric's `SkyRenderStateMixin` injects `reset` TAIL → `clearExtraData()`
(`F/.../mixin/client/rendering/renderstate/SkyRenderStateMixin.java` ~29-32).

---

## 1. Camera

### 1.1 Class structure

`C/net/minecraft/client/Camera.java`, 535 lines:

```java
public class Camera implements net.minecraft.world.waypoints.TrackedWaypoint.Camera {      // ~42
	public static final float PROJECTION_Z_NEAR = 0.05F;
	public static final float BASE_HUD_FOV = 70.0F;
	private boolean initialized;
	private @Nullable Level level;
	private @Nullable Entity entity;
	private Vec3 position = Vec3.ZERO;
	private final MutableBlockPos blockPosition = new MutableBlockPos();
	private final Vector3f forwards = new Vector3f(FORWARDS);   // FORWARDS (0,0,-1), UP (0,1,0), LEFT (-1,0,0) are static
	private final Vector3f panoramicForwards, up, left;
	private float xRot;
	private float yRot;
	private final Quaternionf rotation = new Quaternionf();
	private boolean detached;
	private float eyeHeight, eyeHeightOld;
	private final Projection projection = new Projection();
	private Frustum cullFrustum; private @Nullable Frustum capturedFrustum; private boolean captureFrustum;
	private final Matrix4f cachedViewRotMatrix, cachedViewRotProjMatrix;
	private int matrixPropertiesDirty = -1;     // bit 1 = view-rotation matrix, bit 2 = view-rotation-projection matrix
	private float fovModifier, oldFovModifier, fov, hudFov, depthFar;
	private boolean isPanoramicMode;
	private final EnvironmentAttributeProbe attributeProbe = new EnvironmentAttributeProbe();
```

These are lines ~43-81. The only instance is `GameRenderer.mainCamera` (`private final Camera mainCamera = new Camera();`,
`GameRenderer.java` ~132; getter `public Camera mainCamera()` ~791).

### 1.2 Per-tick and per-frame methods

- `public void tick()` (~83-91) smooths eye height, calls `attributeProbe.tick(level, position)` and `tickFov()`.
  Environment attributes are therefore sampled at the **camera** position. It is called from `GameRenderer.tick()` (~297).
- `public void update(final DeltaTracker deltaTracker)` (~93-112) is the per-frame setup. It replaces 1.21's `setup(...)`:

```java
float renderDistance = this.minecraft.options.getEffectiveRenderDistance() * 16;
this.depthFar = Math.max(renderDistance * 4.0F, this.minecraft.options.cloudRange().get() * 16);
LocalPlayer player = this.minecraft.player;
if (player != null && this.level != null) {
	if (this.entity == null) { this.setEntity(player); }
	float partialTicks = this.getCameraEntityPartialTicks(deltaTracker);
	this.alignWithEntity(partialTicks);
	this.fov = this.calculateFov(partialTicks);
	this.hudFov = this.calculateHudFov(partialTicks);
	this.prepareCullFrustum(this.getViewRotationMatrix(this.cachedViewRotMatrix), this.createProjectionMatrixForCulling(), this.position);
	...
	this.setupPerspective(0.05F, this.depthFar, this.fov, windowWidth, windowHeight);
	this.initialized = true;
}
```

- `public void extractRenderState(final CameraRenderState cameraState, final DeltaTracker deltaTracker)` (~118-163)
  copies `pos`, `xRot`, `yRot`, `blockPos`, `orientation.set(rotation())`, `cullFrustum`, `depthFar`, the projection
  matrix, the view-rotation matrix, `hudFov` and some entity flags (living, sleeping, hurt, bob) into
  `CameraRenderState` (`C/.../renderer/state/level/CameraRenderState.java`). It is called from
  `GameRenderer.extractCamera` (~767-775), which also computes `cameraState.fogData = fogRenderer.setupFog(mainCamera, …)`.

### 1.3 Setting position and rotation

```java
protected void move(final float forwards, final float up, final float right)              // ~336
protected void setRotation(final float yRot, final float xRot) {                         // ~341-349
	this.xRot = xRot;
	this.yRot = yRot;
	this.rotation.rotationYXZ((float) Math.PI - yRot * (float) (Math.PI / 180.0), -xRot * (float) (Math.PI / 180.0), 0.0F);
	FORWARDS.rotate(this.rotation, this.forwards);
	UP.rotate(this.rotation, this.up);
	LEFT.rotate(this.rotation, this.left);
	this.matrixPropertiesDirty |= 3;
}
protected void setPosition(final double x, final double y, final double z)                // ~351
protected void setPosition(final Vec3 position)                                           // ~355 (also updates blockPosition)
public Matrix4f getViewRotationMatrix(final Matrix4f dest)                                 // ~384: conjugate of `rotation`, cached while dirty bit 1 is clear
public Quaternionf rotation(); public Vector3fc forwardVector(); upVector(); leftVector(); // ~380, ~468-482
```

The angle conventions follow from that math. Yaw 0 looks toward +Z (south) and yaw 90 toward −X (west); a positive
xRot looks down. Look-at angles are therefore `yaw = atan2(-dx, dz)` and `pitch = -atan2(dy, hypot(dx, dz))`, in degrees.

**Camera roll is not supported by any setter**: `setRotation` always passes 0 as the Z angle. The quaternion
`rotation` is still the single source of truth for the view matrix (`getViewRotationMatrix`), for
`CameraRenderState.orientation` (used by billboards) and for the culling frustum (`prepareCullFrustum`). A mixin can
therefore rebuild it with a Z angle and refresh the three direction vectors and the dirty bits (§11.2). Vanilla's only
roll is damage tilt, which `GameRenderer.bobHurt` applies to the **projection** matrix
(`poseStack.rotateDegrees(Axis.ZP, …)`, ~339-359; multiplied in `renderLevel` ~636-642). That path doesn't affect
culling, so don't use it for big roll angles.

### 1.4 `alignWithEntity`, riding and detached third person

`private void alignWithEntity(final float partialTicks)` (~251-297):

- Minecarts using `NewMinecartBehavior` get a special case that lerps the cart (~252-261). Every other entity,
  including passengers of any other vehicle, goes through:

```java
this.setRotation(this.entity.getViewYRot(partialTicks), this.entity.getViewXRot(partialTicks));
this.setPosition(
	Mth.lerp(partialTicks, this.entity.xo, this.entity.getX()),
	Mth.lerp(partialTicks, this.entity.yo, this.entity.getY()) + Mth.lerp(partialTicks, this.eyeHeightOld, this.eyeHeight),
	Mth.lerp(partialTicks, this.entity.zo, this.entity.getZ()));
```

- `this.detached = !this.minecraft.options.getCameraType().isFirstPerson();` (~271). When detached, a mirrored camera
  type flips the yaw. The distance is `Attributes.CAMERA_DISTANCE` times the scale of the camera entity, or of the
  mount if the mount is a `LivingEntity` (~279-289). Then `move(-getMaxZoom(dist), 0, 0)` pulls the camera back, with
  an 8-ray `level.clip` collision test (`getMaxZoom` ~299-318).
- Our Starship will be a plain (non-living) `Entity`, so the camera simply follows the passenger's interpolated eye
  position. There is no vehicle-specific camera logic.

### 1.5 FOV, which moved from `GameRenderer` into `Camera`

- `private void tickFov()` (~165-179). The target is `player.getFieldOfViewModifier(firstPerson, fovEffectScale)`
  (`C/.../player/AbstractClientPlayer.java` ~88-110: flying ×1.1, speed, bow, spyglass). It is smoothed by 0.5 per
  tick and clamped to 0.1–1.5.
- `private float calculateFov(final float partialTicks)` (~223-230) returns
  `options.fov() * lerp(oldFovModifier, fovModifier)`, then applies `modifyFovBasedOnDeathOrFluid` (death zoom,
  water/lava ×0.857, ~236-249). It returns 90 in panoramic mode.
- `private float calculateHudFov(final float partialTicks)` (~232-234) uses a 70° base for the hand.
  `public float getFov()` is at ~324.
- Culling uses `Math.max(this.fov, options.fov)` (`createProjectionMatrixForCulling` ~181-191), so zooming in never
  culls too much.
- `GameRenderer` no longer has an FOV method. It takes `cameraState.projectionMatrix` (`renderLevel` ~635) and
  `cameraState.hudFov` (`render3dHud` ~676-679).
- **There is no Fabric camera or FOV event.** Grepping `F/.../api` finds none; `LevelExtractionContext` only exposes
  `Camera camera()`.

### 1.6 Does the world render around the camera?

Yes. Everything keys off `CameraRenderState`:

- `LevelRenderer.render(GraphicsResourceAllocator, boolean renderOutline, CameraRenderState cameraState, GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepthRequired)`
  (`C/.../renderer/LevelRenderer.java` ~185) calls `repositionCamera(cameraState)` (~353-361), which does
  `viewArea.repositionCamera(SectionPos.of(camera.pos))` and `sectionRenderDispatcher.setCameraPosition(cameraPos)`.
  The render-section grid is re-centred on the camera.
- The model-view stack gets `cameraState.viewRotationMatrix` (~199-201). Terrain and entities are drawn relative to
  the camera.
- `compileSections(camera)` prioritises sections by distance to `camera.blockPos` (~937-964).
- The occlusion search starts at the camera's section. `sectionOcclusionGraph.update(cameraState, fov, …)` (~313-314)
  → `scheduleFullUpdate` → `initializeQueueForFullUpdate(camera.blockPos, …)` (`C/.../renderer/SectionOcclusionGraph.java`
  ~148-180). A full rebuild starts when the camera crosses an 8-block cell or the FOV changes (`invalidateIfNeeded`
  ~97-111). The rebuild runs async (`CompletableFuture.runAsync`), so a hard cut can show a frame or two of missing
  sections.
- The frustum is built by `Camera.prepareCullFrustum` as `new Frustum(viewRot, projForCulling).prepare(camPos)`
  (~197-209). `LevelExtractor.extract` re-applies it only when `floor(xRot/2)` or `floor(yRot/2)` changes, or when the
  occlusion graph asks (`consumeFrustumUpdate()`) (~140-149). **A pure roll change does not re-apply the frustum.**
  Call `levelRenderer.sectionOcclusionGraph().invalidate()` (public, ~93-95) after large roll changes.
- Entities: `extractVisibleEntities(camera, cullFrustum, …)` uses the camera position for distance and the frustum
  (~252-279). `isEntityVisible` also needs the entity's section to be compiled and visible **or** the entity to be
  outside build height (~281-293):
  `return this.level.isOutsideBuildHeight(blockPos.getY()) || this.levelRenderer.isSectionCompiledAndVisible(blockPos, chunkFadeDuration);`.
  An entity that carries the local player skips the frustum and distance test
  (`entity.hasIndirectPassenger(this.minecraft.player)`). `EntityRenderer.shouldRender` uses
  `entity.shouldRender(camX, camY, camZ)` (view distance ∝ bounding-box size × 64 × `Entity.viewScale`) and
  `getBoundingBoxForCulling` (`C/.../renderer/entity/EntityRenderer.java` ~62-74). Give the Starship a large culling
  box.
- **Limit:** the client only holds the chunks the server sends around the **player**. A camera 150 blocks (~9.4
  chunks) away shows terrain only where the player's loaded radius overlaps the camera-centred view area. Keep
  cinematic cameras within about (render distance − 2) chunks of the player. **UNVERIFIED** exact margin.

### 1.7 Does the local player's own model render when the camera is detached?

Only then. `LevelExtractor.extractVisibleEntities` (~263-265):

```java
if (this.isEntityVisible(entity, frustum, camX, camY, camZ, entityPartialTicks, chunkFadeDuration)
	&& (entity != camera.entity() || camera.isDetached() || camera.entity() instanceof LivingEntity && ((LivingEntity)camera.entity()).isSleeping())
	&& (!(entity instanceof LocalPlayer) || camera.entity() == entity)) {
```

`Camera.isDetached()` (~419) has no other reader. A cinematic override chooses with the `detached` field: `true` shows
the player model, `false` hides it, which suits a passenger who is "inside" the ship.

### 1.8 Hiding the hand, crosshair and HUD

- **`Options.hideGui` no longer exists** (not in `C/.../Options.java`). F1 toggles `Hud.isHidden` through
  `public void toggle()` and `public boolean isHidden()` (`C/.../gui/Hud.java` ~206-212). The call site is
  `Gui.handleKeybinds`: `while (options.keyToggleGui.consumeClick()) this.hud.toggle();` (`C/.../gui/Gui.java` ~340-342).
- `Hud.extractRenderState` (~220-249) copies `isHidden` into `gameRenderState().guiRenderState.isHudHidden`. It skips
  everything while a `LevelLoadingScreen` is open, and that includes Fabric HUD elements (§2.1):

```java
if (!(this.minecraft.gui.screen() instanceof LevelLoadingScreen)) {
	if (!this.isHidden) { this.extractCameraOverlays(…); this.extractCrosshair(…); graphics.nextStratum(); this.extractHotbarAndDecorations(…); … }
	this.extractSleepOverlay(…);
	if (!this.isHidden) { demo, scoreboard, overlay message, title, chat, tab list, subtitles }
```

- **Hand.** `private void renderItemInHand(final CameraRenderState cameraState, final PlayerRenderState playerState, final GpuTextureView depthTextureView)`
  (`GameRenderer.java` ~375-413) draws when
  `playerState.hasPlayer && optionsRenderState.cameraType.isFirstPerson() && !isSleeping && !guiRenderState.isHudHidden && !spectator`.
  It ignores `camera.isDetached()`, so a cinematic camera keeps drawing the hand while the camera type is first
  person. Hide it with a HEAD-cancel mixin.
- **Crosshair.** `Hud.extractCrosshair` (~441-470) draws when `options.getCameraType().isFirstPerson()`. Wrap it
  through Fabric's `VanillaHudElements.CROSSHAIR`.
- **Block outline.** `GameRenderer.shouldRenderBlockOutline()` (~602-625) depends on `!hud.isHidden()` and
  `minecraft.hitResult`, which is picked from the *player* (`Minecraft.pick` ~3088-3097). Cancel it with Fabric's
  `LevelRenderEvents.BEFORE_BLOCK_OUTLINE` by returning false.
- **Free helmet visor.** `Hud.extractCameraOverlays` draws the `Equippable.cameraOverlay()` texture full-screen in
  first person (~279-291; `M/net/minecraft/world/item/equipment/Equippable.java` ~38, field `"camera_overlay"` ~52).
  Use it for the spacesuit helmet.
- Simple text callouts are available as `Hud.setTitle(Component)`, `setSubtitle(Component)`,
  `setOverlayMessage(Component, boolean)` and `setTimes(int, int, int)` (~1207-1235).

### 1.9 Other things that follow the camera

- Environment attributes such as sky colour, fog and sun angle are sampled at `camera.position` (§5, §6).
- **The sound listener is the camera.** `SoundEngine.updateSource(Camera)` uses `camera.position()`,
  `forwardVector()` and `upVector()` (`C/.../sounds/SoundEngine.java` ~510-515). In a wide shot 150 blocks out, the
  rocket is heard with linear attenuation over `max(volume, 1) × attenuation_distance`
  (`Sound.getAttenuationDistance(float)`, `C/.../resources/sounds/Sound.java` ~90-92). Give Raptor sounds a large
  `attenuation_distance` in `sounds.json`, or play them non-attenuated during cinematics.
- **Level-loading readiness checks the camera's section.** `LevelLoadTracker.WaitingForPlayerChunk.isReady()` uses
  `Minecraft.getInstance().gameRenderer.mainCamera().blockPosition()` (`C/.../multiplayer/LevelLoadTracker.java` ~124).
  Don't leave a far-away override active while a dimension loads.
- Smart (cave) culling is disabled only for a spectator whose camera block is solid (`Camera.extractRenderState`
  ~125-127). A cinematic camera inside opaque blocks will look broken, so clip-test camera positions against the
  world.

---

## 2. HUD

### 2.1 Fabric HUD API (fabric-rendering-v1)

`F/net/fabricmc/fabric/api/client/rendering/v1/hud/`:

```java
public interface HudElement {                                                          // HudElement.java ~28-36
	void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker);
}
public interface HudElementRegistry {                                                  // HudElementRegistry.java ~84-170
	static void addFirst(Identifier id, HudElement element);
	static void addLast(Identifier id, HudElement element);
	static void attachElementBefore(Identifier beforeThis, Identifier identifier, HudElement element);
	static void attachElementAfter(Identifier afterThis, Identifier identifier, HudElement element);
	static void removeElement(Identifier identifier);
	static void replaceElement(Identifier identifier, Function<HudElement, HudElement> replacer);
}
```

`VanillaHudElements` IDs: `misc_overlays`, `crosshair`, `spectator_menu`, `hotbar`, `armor_bar`, `health_bar`,
`food_bar`, `air_bar`, `mount_health`, `info_bar`, `experience_level`, `held_item_tooltip`, `spectator_tooltip`,
`mob_effects`, `boss_bar`, `sleep`, `demo_timer`, `scoreboard`, `overlay_message`, `title_and_subtitle`, `chat`,
`player_list`, `subtitles`. There is also `HudStatusBarHeightRegistry` / `StatusBarHeightProvider`. The 1.21-era
`HudLayerRegistrationCallback` and `HudRenderCallback` are **not** in this tree.

How it is wired: `HudMixin` wraps each `Hud.extract*` call in an `@WrapOperation`
(`F/.../mixin/client/rendering/HudMixin.java`). `addFirst` puts the element in the `MISC_OVERLAYS` root and `addLast`
in the `SUBTITLES` root (`F/.../impl/client/rendering/hud/HudElementRegistryImpl.java` ~79-90). The `SUBTITLES` root
is driven by `SubtitleOverlayMixin`, an `@WrapMethod` on `SubtitleOverlay.extractRenderState`.

What follows from that code: vanilla only calls `extractCameraOverlays` and the subtitle overlay while `!isHidden`
(Hud ~223-238). So **every** Fabric HUD element, addFirst and addLast included, disappears with F1 and while any
`LevelLoadingScreen` is open. The javadoc's claim that addFirst/addLast "will not inherit any render condition"
doesn't hold in practice.

### 2.2 `GuiGraphicsExtractor` (formerly `GuiGraphics`)

`C/net/minecraft/client/gui/GuiGraphicsExtractor.java` (class at ~91). A new one is created every frame in
`Gui.extractRenderState` (`Gui.java` ~150). Its methods only **record** render states; the GPU work happens later in
`GuiRenderer.render()`.

| Signature | Line | Notes |
|---|---|---|
| `public int guiWidth()`, `public int guiHeight()` | ~137-143 | Scaled size. |
| `public Matrix3x2fStack pose()` | ~145 | A **2D JOML stack**: `pushMatrix()`, `popMatrix()`, `translate(x, y)`, `scale(sx, sy)`, `rotate(rad)`. Vanilla example: Hud ~341-387. |
| `public void nextStratum()` | ~149 | Starts a new top-level layer. |
| `public void blurBeforeThisStratum()` | ~153 | Once per frame only, else `IllegalStateException("Can only blur once per frame")` (GuiRenderState ~43-49). |
| `public void enableScissor(int x0, int y0, int x1, int y1)`, `disableScissor()` | ~157-164 | A stack. The rectangle is transformed by the pose. |
| `public void horizontalLine(int x0, int x1, int y, int col)`, `verticalLine(int x, int y0, int y1, int col)` | ~170-188 | 1-px fills. These are the only "lines". |
| `public void fill(int x0, int y0, int x1, int y1, int col)` | ~190 | Uses `RenderPipelines.GUI`. |
| `public void fill(RenderPipeline pipeline, int x0, int y0, int x1, int y1, int col)` | ~194 | For a custom blend. |
| `public void fillGradient(int x0, int y0, int x1, int y1, int col1, int col2)` | ~210 | **Vertical only**: col1 at the top, col2 at the bottom. |
| `public void fill(RenderPipeline, TextureSetup, int x0, int y0, int x1, int y1)` | ~214 | For example `END_PORTAL`. |
| `public void outline(int x, int y, int width, int height, int color)` | ~218 | |
| `public void text(Font, String / FormattedCharSequence / Component, int x, int y, int color[, boolean dropShadow])` | ~251-277 | Shadow defaults to true. **Colour is ARGB, and alpha 0 draws nothing** (`if (ARGB.alpha(color) != 0)` ~266), so use `0xFFFFFFFF`, not `0xFFFFFF`. |
| `public void centeredText(Font, String / Component / FormattedCharSequence, int x, int y, int color)` | ~279-290 | |
| `public int textWithWordWrap(Font, FormattedText, int x, int y, int width, int col[, boolean])` | ~292-303 | |
| `public void blit(RenderPipeline, Identifier texture, int x, int y, float u, float v, int w, int h, int texW, int texH[, int color])`, plus overloads with srcW/srcH | ~315-391 | `TextureManager.getTexture(id)` auto-loads a `SimpleTexture` (`C/.../renderer/texture/TextureManager.java` ~90-99). |
| `public void blit(GpuTextureView, GpuSampler, int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1)` | ~399 | For a `DynamicTexture` or render target. |
| `public void blitSprite(RenderPipeline, Identifier sprite, int x, int y, int w, int h[, float alpha | int color])` | ~414-441 | From the GUI atlas. Honours stretch, tile and nine-slice metadata. |
| `public void blitSprite(RenderPipeline, TextureAtlasSprite, int x, int y, int w, int h[, int color])` | ~482-492 | |
| `item(ItemStack, int, int)`, `itemDecorations(...)` | ~876-926 | |
| `public void entity(EntityRenderState, float scale, Vector3fc translation, Quaternionfc rotation, @Nullable Quaternionfc overrideCameraAngle, int x0, int y0, int x1, int y1)` | ~1008 | Picture-in-picture (§4). `skin`, `book`, `bannerPattern`, `profilerChart` at ~1026-1066 are PIP too. |
| `setTooltipForNextFrame(...)` | ~1068+ | |
| `public final GuiRenderState guiRenderState`, `public final ScissorStack scissorStack` | ~98-104 | Public through Fabric's transitive access widener. |

GUI pipelines in `C/.../renderer/RenderPipelines.java`:

- `GUI`: POSITION_COLOR, QUADS, `BlendFunction.TRANSLUCENT` (snippet ~414-422, pipeline ~1200).
- `GUI_TEXTURED`: POSITION_TEX_COLOR (snippet ~426-435, pipeline ~1207).
- Also `GUI_TEXTURED_PREMULTIPLIED_ALPHA`, `GUI_TEXT_HIGHLIGHT` (ADDITIVE), `GUI_INVERT`,
  `GUI_OPAQUE_TEXTURED_BACKGROUND`, `GUI_NAUSEA_OVERLAY` (ADDITIVE, textured), `VIGNETTE`, `CROSSHAIR` and
  `MOJANG_LOGO` (SRC_ALPHA, ONE).

`GUI_SNIPPET` and `GUI_TEXTURED_SNIPPET` are public (widened), so a mod can derive an additive or no-cull GUI pipeline
from them.

### 2.3 How the deferred GUI render state works

From `C/.../renderer/state/gui/GuiRenderState.java`:

- The state is a list of **strata**. Each stratum is a chain of nodes linked by `up`. `findAppropriateNode`
  (~108-122) puts a new element into a node above anything it intersects, so overlapping draws keep submission order.
  **An element whose `bounds()` is null is silently dropped** (~110-112).
- Within one node, elements are sorted by scissor, then pipeline, then texture (`ELEMENT_SORT_COMPARATOR`,
  `C/.../gui/render/GuiRenderer.java` ~72-74). A node's text glyphs are drawn after its elements.
- `GuiRenderer.addElementToMesh` (~219-235) starts a new draw whenever the pipeline, texture or scissor changes. It
  uses **the pipeline's own vertex format and topology**:
  `this.vertexBuffer.appendDraw(pipeline.getVertexFormatBinding(0), pipeline.getPrimitiveTopology())`. Fabric's
  `GuiRendererMixin.uploadPrimitivesIndividually` forces a separate draw for connected topologies (strips and fans)
  (`F/.../mixin/client/rendering/GuiRendererMixin.java` ~101-104).
- Drawing uses a GUI ortho projection (near 1000, far 11000, ~177-180) and runs in two passes, before and after the
  blur.

### 2.4 Custom GUI geometry: circles, arcs, polylines, textured spheres

`C/.../renderer/state/gui/GuiElementRenderState.java`:

```java
public interface GuiElementRenderState extends ScreenArea {
	void buildVertices(final VertexConsumer vertexConsumer);
	RenderPipeline pipeline();
	TextureSetup textureSetup();
	@Nullable ScreenRectangle scissorArea();
}
public interface ScreenArea { @Nullable ScreenRectangle bounds(); }
```

- Submit an element with `graphics.guiRenderState.addGuiElement(state)` (GuiRenderState ~81-86).
- Vanilla templates are `ColoredRectangleRenderState` and `BlitRenderState`. Both are records holding a
  `Matrix3x2fc pose`; they compute bounds with `new ScreenRectangle(x0, y0, x1 - x0, y1 - y0).transformMaxBounds(pose)`
  and intersect that with the scissor.
- `VertexConsumer.addVertexWith2DPose(Matrix3x2fc pose, float x, float y)` takes **floats**
  (`C/com/mojang/blaze3d/vertex/VertexConsumer.java` ~136-139), so arcs can be positioned at sub-pixel precision.
- **Winding.** Pipelines cull back faces by default (`this.cull.orElse(true)` in `RenderPipeline.Builder.build()`,
  `C/com/mojang/renderpearl/api/pipeline/RenderPipeline.java` ~359-420). Every vanilla GUI quad has a negative
  shoelace area in GUI (y-down) coordinates, for example TL → BL → BR → TR in `BlitRenderState` ~123-128. Emit custom
  quads with the same orientation, or build the pipeline with `.withCull(false)`.
- `TextureSetup` (`C/.../gui/render/TextureSetup.java`) has `noTexture()`, `singleTexture(GpuTextureView, GpuSampler)`,
  `singleTextureWithLightmap(...)` and `doubleTexture(...)`. Samplers come from
  `RenderSystem.getSamplerCache().getClampToEdge(FilterMode)` or `getRepeat(FilterMode)`
  (`C/com/mojang/blaze3d/systems/SamplerCache.java` ~40-55).

### 2.5 Text

- `Font` (`C/.../gui/Font.java`) has `public final int lineHeight = 9`, `width(String | FormattedText | FormattedCharSequence)`,
  `split(FormattedText, int)` and `plainSubstrByWidth(...)`. Scale text with `graphics.pose().scale(2f, 2f)`.
- Custom fonts: `Style#withFont(@Nullable FontDescription)` (`M/net/minecraft/network/chat/Style.java` ~369) with
  `new FontDescription.Resource(Identifier)`, backed by a font JSON at `assets/redplanet/font/<name>.json`.
  `FontDescription.AtlasSprite(atlasId, spriteId)` puts inline icons in text
  (`M/net/minecraft/network/chat/FontDescription.java` ~18-25).

### 2.6 GUI sprites

- `A/atlases/gui.json` has two sources: `{"type":"minecraft:directory","source":"gui/sprites","prefix":""}` and
  `mob_effect/`.
- The directory source lists matching files **in every namespace**:
  `new FileToIdConverter("textures/" + this.sourcePath, ".png").listMatchingResources(resourceManager)`
  (`C/.../renderer/texture/atlas/sources/DirectoryLister.java` ~12-17). So
  `assets/redplanet/textures/gui/sprites/hud/gauge_ring.png` becomes sprite `redplanet:hud/gauge_ring`, usable as
  `graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("redplanet", "hud/gauge_ring"), x, y, w, h)`.
  No registration is needed.
- Scaling metadata goes in `<png>.mcmeta`:
  `{"gui":{"scaling":{"type":"nine_slice","width":W,"height":H,"border":B,"stretch_inner":false}}}`. The other types
  are `"stretch"` and `"tile"` (`C/.../resources/metadata/gui/GuiSpriteScaling.java` ~18-97).
- The same trick works for the celestials atlas (§5.3). For a whole new atlas, Fabric has
  `AtlasRegistry.register(AtlasManager.AtlasConfig)`.

---

## 3. Screens and the level-loading flow

### 3.1 Screen API

`C/.../gui/screens/Screen.java`:

```java
public abstract class Screen extends AbstractContainerEventHandler implements Renderable              // ~57
protected Screen(final Component title)                                                               // ~89
public final void extractRenderStateWithTooltipAndSubtitles(GuiGraphicsExtractor, int, int, float)     // ~112: nextStratum, extractBackground, nextStratum, extractRenderState, deferred tooltips
public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a)  // ~121 (was render)
public boolean keyPressed(final KeyEvent event)                                                      // ~128
public boolean shouldCloseOnEsc()                                                                    // ~204
public void onClose() { this.minecraft.gui.setScreen(null); }                                        // ~208
public final void init(final int width, final int height)  /  protected void init()                   // ~374 / ~413
public void tick();  public void removed();  public void added();                                    // ~416-424
public void extractBackground(final GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a)   // ~425 (was renderBackground)
public boolean isPauseScreen()                                                                       // ~477, default true
```

- **Keys.** `public record KeyEvent(int key, int keycode, int modifiers)`. `key` is an **SDL scancode**
  (`InputConstants.KEY_K = 14`, `KEY_ESCAPE = 41`, `C/com/mojang/blaze3d/platform/InputConstants.java` ~27-131).
  `keycode` is the SDL keycode: `Screen.keyPressed` switches on `event.shortcutKey()` with values 9 and 1073741903…
  (~138-145).
- **Mouse.** `mouseClicked(MouseButtonEvent, boolean doubleClick)`, `mouseScrolled(double, double, double, double)`
  (`C/.../gui/components/events/GuiEventListener.java` ~18-46).
- **Widgets** implement `protected abstract void extractWidgetRenderState(GuiGraphicsExtractor, int, int, float)`
  (`AbstractWidget` ~90).

### 3.2 Opening screens

- **Screens now live in `Gui`.** Use `minecraft.gui.setScreen(@Nullable Screen)` (`Gui.java` ~234-293);
  `Minecraft.setScreen` no longer exists. `minecraft.gui.screen()` returns the current screen (~230).
  `minecraft.setScreenAndShow(Screen)` is setScreen plus an immediate `renderFrame(false)` (`Minecraft.java` ~2301-2320).
- `Gui.setScreen` releases the mouse, calls `KeyMapping.releaseAll()` and calls `screen.init(w, h)` (~277-280).
- `Gui` also has an `Overlay` slot (`setOverlay(@Nullable Overlay)`, ~300-302). When set, the overlay is drawn
  *instead of* the screen (`Gui.extractRenderState` ~157-188), and it blocks key and mouse routing to screens
  (`KeyboardHandler` ~590, `MouseHandler` ~79-176). `Overlay.isPausing()` defaults to true
  (`C/.../gui/screens/Overlay.java`).
- `Gui.extractRenderState(DeltaTracker, boolean shouldRenderLevel, boolean resourcesLoaded)` (~144-227) runs in this
  order: HUD (only if `shouldRenderLevel`) → overlay or screen → saving indicator → toasts → debug overlay.
- **A mod can render while the level is null.** Screens need only `resourcesLoaded`;
  `shouldRenderLevel = resourcesLoaded && advanceGameTime && minecraft.level != null` (`GameRenderer.extract`
  ~437-438). `setScreenAndShow` renders with `advanceGameTime = false`, so that forced frame is GUI-only.

### 3.3 What happens on a dimension change, step by step

1. **Server.** `ServerPlayer` sends `new ClientboundRespawnPacket(this.createCommonSpawnInfo(newLevel), (byte)3)`
   (`M/net/minecraft/server/level/ServerPlayer.java` ~1149). Later, `PlayerList.sendLevelInfo` sends
   `new ClientboundGameEventPacket(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0F)`
   (`M/net/minecraft/server/players/PlayerList.java` ~658).
2. **Client `handleRespawn`** (`C/.../multiplayer/ClientPacketListener.java` ~1222-1314):
   - It picks a reason with `determineLevelLoadingReason(playerDied, newKey, oldKey)` (~1316-1329): NETHER_PORTAL if
     either side is the Nether, END_PORTAL if either side is the End, otherwise **OTHER**. Mars ↔ Overworld is OTHER.
   - If the dimension changed, it builds a new `ClientLevel` and calls `minecraft.setLevel(level)` (~1251). That runs
     `updateLevelInEngines(level, true)`, which **stops all sounds** (`this.soundManager.stop()`, `Minecraft.java`
     ~2328-2331), clears the camera entity and fires Fabric's `ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE`
     (`F/.../mixin/event/lifecycle/client/MinecraftMixin.java` ~55-61).
   - It creates the new `LocalPlayer`, then calls `startWaitingForNewLevel(newPlayer, level, reason)` (~1272).
3. **`startWaitingForNewLevel`** (~1598-1610):

```java
if (this.levelLoadTracker == null) { this.levelLoadTracker = new LevelLoadTracker(); }
this.levelLoadTracker.startClientLoad(player, level);
if (this.minecraft.gui.screen() instanceof LevelLoadingScreen loadingScreen) {
	loadingScreen.update(this.levelLoadTracker, reason);
} else {
	this.minecraft.gui.hud.getChat().preserveCurrentChatScreen();
	this.minecraft.setScreenAndShow(new LevelLoadingScreen(this.levelLoadTracker, reason));
}
```

4. **`LevelLoadTracker` state machine** (`C/.../multiplayer/LevelLoadTracker.java`):
   - `WaitingForServer` lasts until `LEVEL_CHUNKS_LOAD_START` arrives (ClientPacketListener ~1560-1562), with a 30 s
     timeout.
   - `WaitingForPlayerChunk` lasts until the camera's section is compiled and visible. That callback comes from
     `LevelRenderer.render` (~316-321). It also ends if the player is a spectator, dead or outside build height, or on
     timeout.
   - Then `ClientLevelReady(now)`. `isLevelReady()` is true once `now ≥ readyAt + closeDelayMs`. `closeDelayMs` is 0
     for this tracker; the 500 ms delay applies only to a new singleplayer world (`Minecraft.java` ~2123).
5. **Closing.** `LevelLoadingScreen.tick()` runs `if (this.loadTracker.isLevelReady()) this.onClose();`
   (`C/.../gui/screens/LevelLoadingScreen.java` ~80-86). Separately, `ClientPacketListener.tick()` (~2671-2676) calls
   `notifyPlayerLoaded()`, which sends `ServerboundPlayerLoadedPacket`, and then nulls the tracker.
6. **What it draws.** The background depends on the reason (`extractBackground` ~143-163): NETHER_PORTAL → the portal
   sprite; END_PORTAL → the `END_PORTAL` pipeline; OTHER → panorama, blur and menu background. The foreground (~88-113)
   is the "Downloading terrain" text, an optional chunk map (singleplayer status view) and a progress bar.
7. **While it is open:**
   - `Hud.extractRenderState` draws nothing (Hud ~222).
   - `MusicManager` won't start music (`C/.../sounds/MusicManager.java` ~59).
   - `LocalPlayer.aiStep` skips portal effects (~751).
   - Fabric's client-gametest helpers treat it as still loading (`F/.../impl/client/gametest/util/ClientGameTestImpl.java` ~73-75).

   All four checks are `instanceof LevelLoadingScreen`, so they apply to a **subclass** too.

How long it stays depends on chunk availability: a fraction of a second to a few seconds in singleplayer
(**UNVERIFIED**). Pre-generate the landing site.

### 3.4 (a) Drawing on top of it without a mixin

`F/.../api/client/screen/v1/ScreenEvents.java`:

```java
Event<BeforeInit> BEFORE_INIT;  Event<AfterInit> AFTER_INIT;   // (Minecraft client, Screen screen, int scaledWidth, int scaledHeight)
static Event<Remove> remove(Screen);  static Event<BeforeExtract> beforeExtract(Screen);
static Event<AfterBackground> afterBackground(Screen);  static Event<AfterForeground> afterForeground(Screen);
static Event<AfterExtract> afterExtract(Screen);               // (Screen, GuiGraphicsExtractor, int mouseX, int mouseY, float tickProgress)
static Event<BeforeTick> beforeTick(Screen);  static Event<AfterTick> afterTick(Screen);
```

- `GuiMixin.onExtractGui` wraps `Screen.extractRenderStateWithTooltipAndSubtitles`
  (`F/.../mixin/screen/GuiMixin.java` ~89-94).
- Keyboard: `ScreenKeyboardEvents.allowKeyPress(screen)` takes `boolean allowKeyPress(Screen, KeyEvent)`, and there
  are before/after variants.
- The `LevelLoadingScreen` from `setScreenAndShow` goes through `Gui.setScreen` → `init` → `AFTER_INIT`, so this works:

```java
ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
	if (screen instanceof LevelLoadingScreen && Interlude.isActive()) {
		ScreenEvents.afterExtract(screen).register((s, g, mx, my, tp) -> { g.nextStratum(); Interlude.draw(g, tp); });
	}
});
```

Its limit: it can't hold the screen open after the level is ready.

### 3.5 (b) Replacing it

- **Subclass, no mixin needed for reuse.** `LevelLoadingScreen` has a public constructor
  `(LevelLoadTracker, LevelLoadingScreen.Reason)` and `public void update(LevelLoadTracker, Reason)` (~51-60), and
  `new LevelLoadTracker()` is public.
  - If an `InterludeScreen extends LevelLoadingScreen` is already open when the respawn packet arrives, vanilla calls
    `update(...)` on it instead of making a new screen (step 3).
  - Override `tick()` so the screen closes only when the tracker is ready **and** the animation has ended, or skip
    was pressed.
  - Override `extractBackground` and `extractRenderState` to draw the interlude.
  - `shouldCloseOnEsc()` already returns false and `isPauseScreen()` returns false.
- **Fallback mixin**, for when the screen wasn't opened in time: an `@ModifyArg` on the
  `Minecraft.setScreenAndShow(Screen)` call inside `ClientPacketListener.startWaitingForNewLevel` (§11.4). A MixinExtras
  `@WrapOperation` on the `NEW LevelLoadingScreen` there would also work.
- **Overlay, not recommended.** It hides the vanilla screen completely, but it blocks key events, pauses singleplayer
  unless `isPausing()` is overridden, and lets the HUD draw underneath it.

---

## 4. 3D inside the GUI

### 4.1 Picture-in-picture renderers

- **How vanilla does it.** `PictureInPictureRenderer<T extends PictureInPictureRenderState>`
  (`C/.../gui/render/pip/PictureInPictureRenderer.java` ~28-148):
  - It renders into its own RGBA8 colour texture and D32 depth texture, sized `(x1-x0)·guiScale × (y1-y0)·guiScale`.
  - It uses an **orthographic** projection: `this.projection.setupOrtho(-1000.0F, 1000.0F, width, height, true)` (~110).
  - It calls `renderToTexture(T state, PoseStack poseStack, SubmitNodeCollector collector)`. The pose is already
    translated to the centre and scaled by `guiScale * state.scale()` with z flipped (~45-51).
  - It renders the collected submits in a "Picture in picture" pass, then blits with
    `GUI_TEXTURED_PREMULTIPLIED_ALPHA` (~68-86).
  - Abstract methods are `getRenderStateClass()`, `renderToTexture(...)` and `getTextureLabel()`. Override
    `textureIsReadyToBlit(T)` to cache a texture between frames.
- **State interface** (`C/.../renderer/state/gui/pip/PictureInPictureRenderState.java`):
  `PictureInPictureRenderState extends ScreenArea { int x0(); int x1(); int y0(); int y1(); float scale(); default Matrix3x2fc pose(); @Nullable ScreenRectangle scissorArea(); }`.
- **Registration.** Vanilla's renderers are a fixed list built in the `GameRenderer` constructor (~166-175). Fabric
  adds `PictureInPictureRendererRegistry.register(Factory)`, where the factory is
  `PictureInPictureRenderer<?> createRenderer(Context ctx)`
  (`F/.../api/client/rendering/v1/PictureInPictureRendererRegistry.java`).
  - Register before the GuiRenderer exists, that is in `onInitializeClient`. Registering later throws
    `"Too late to register, GuiRenderer has already been initialized."` (`PictureInPictureRendererRegistryImpl` ~39-45).
  - Fabric's pool creates extra renderer instances when several states of one class are drawn in a frame
    (`PictureInPictureRendererPool` ~32-50), so the Earth and Mars globes can share one class.
- **Submit** with `graphics.guiRenderState.addPicturesInPictureState(state)` (GuiRenderState ~74-79).
- **Geometry** goes through `submitNodeCollector.submitCustomGeometry(PoseStack, RenderType, SubmitNodeCollector.CustomGeometryRenderer)`,
  whose callback is `void render(PoseStack.Pose pose, VertexConsumer buffer)`
  (`C/.../renderer/OrderedSubmitNodeCollector.java` ~193, `SubmitNodeCollector.java`).
  - `RenderType.create(String, RenderSetup)` and
    `RenderSetup.builder(RenderPipeline).withTexture("Sampler0", id)….createRenderSetup()` are public (widened)
    (`C/.../renderer/rendertype/RenderType.java` ~43, `RenderSetup.java` ~81-202).
  - `RenderTypes.text(Identifier)` uses pipeline `TEXT`: POSITION_TEX_LIGHTMAP_COLOR, translucent, depth-tested
    (RenderPipelines ~252-269 and ~938-940, RenderTypes ~398-403).
- **Perspective inside a PIP.** `RenderSystem.setProjectionMatrix(GpuBufferSlice, ProjectionType)`
  (`C/com/mojang/blaze3d/systems/RenderSystem.java` ~267-271) is read by `bindDefaultUniforms` (~370-390) when the PIP
  render pass opens, which happens *after* `renderToTexture` returns. So `renderToTexture` can replace the ortho
  projection with its own `ProjectionMatrixBuffer.getBuffer(Projection)`, built with
  `Projection.setupPerspective(zNear, zFar, fovDeg, w, h)` (`C/.../renderer/Projection.java` ~19-34). `GuiRenderer.draw`
  sets its own projection before drawing (~177-180), so there is nothing to restore.
- **Depth is reverse-Z.** `DepthStencilState.DEFAULT = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true)`
  (`C/com/mojang/renderpearl/api/pipeline/DepthStencilState.java`), depth is cleared to 0.0 everywhere, and
  `Projection.getMatrix` swaps near and far (`float near = this.zFar; float far = this.zNear;` ~60-70). Custom
  pipelines and projections must follow this.

### 4.2 Without PIP: a CPU-projected sphere

For a globe seen from far away, an orthographic projection is physically accurate. A custom `GuiElementRenderState`
can draw it with `RenderPipelines.GUI_TEXTURED` and
`TextureSetup.singleTexture(tex.getTextureView(), RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR))`:

- emit one quad per latitude/longitude cell whose rotated centre faces the viewer;
- take UVs from an equirectangular map, such as the repo's
  `src/client/resources/assets/redplanet/textures/environment/mars_globe.png` (1024×512);
- set a per-vertex colour for the day/night terminator.

It needs no extra render targets or depth buffer, scales with GUI scale, and works over the loading screen. This is
the recommended approach (skeleton in §11.5).

---

## 5. Sky

### 5.1 Where the sky is drawn

- `LevelRenderer.render` adds the sky right after the clear pass:
  `if (shouldRenderSky) this.addSkyPass(frame, cameraState, terrainFog);` (~260-262). `shouldRenderSky` is
  `!bossOverlay.shouldCreateWorldFog()` (GameRenderer ~664-667).
- `addSkyPass` (~363-381):
  - skips the sky for POWDER_SNOW or LAVA fog, and under blindness or darkness;
  - lazily (re)creates `new SkyRenderer(textureManager, atlasManager, gameRenderer.mainRenderTarget())` when it is
    null or `levelRenderState.shouldResetSkyRenderer` is set. That flag is set after every resource reload
    (LevelExtractor ~484-486);
  - adds a frame pass only if `state.skybox != Skybox.NONE`; the pass executes `this.skyRenderer.render(skyFog, state)`.
- During the sky pass the model-view stack holds `cameraState.viewRotationMatrix` (pushed ~199-201, popped after
  `frame.execute` ~294). `RenderSystem.getModelViewMatrixCopy()` is therefore the camera rotation without
  translation, roll included.
- Extraction happens in `LevelExtractor.extract`:
  `skyRenderer.extractRenderState(this.level, worldPartialTicks, camera, this.levelRenderState.skyRenderState)`
  (~192-196). It runs only once the SkyRenderer exists, so there is no sky state on the very first frame.

### 5.2 `SkyRenderState` and the attributes that feed it

`C/.../renderer/state/level/SkyRenderState.java` holds `skybox`, `shouldRenderDarkDisc`, `sunAngle`, `moonAngle`,
`starAngle`, `rainBrightness`, `starBrightness`, `Vector4fc sunriseAndSunsetColor`, `MoonPhase moonPhase`,
`Vector3fc skyColor` and `endFlashIntensity/XAngle/YAngle`.

`SkyRenderer.extractRenderState(ClientLevel, float partialTicks, Camera, SkyRenderState)` (`C/.../renderer/SkyRenderer.java` ~104-127):

```java
state.skybox = level.dimensionType().skybox();
...
state.sunAngle = (Float)attributeProbe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTicks) * (float) (Math.PI / 180.0);
state.moonAngle = … MOON_ANGLE …;  state.starAngle = … STAR_ANGLE …;
state.rainBrightness = 1.0F - level.getRainLevel(partialTicks);
state.starBrightness = (Float)attributeProbe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, partialTicks);
state.sunriseAndSunsetColor = (Vector4fc)camera.attributeProbe().getValue(EnvironmentAttributes.SUNRISE_SUNSET_COLOR, partialTicks);
state.moonPhase = (MoonPhase)attributeProbe.getValue(EnvironmentAttributes.MOON_PHASE, partialTicks);
state.skyColor = (Vector3fc)attributeProbe.getValue(EnvironmentAttributes.SKY_COLOR, partialTicks);
state.shouldRenderDarkDisc = this.shouldRenderDarkDisc(partialTicks, level);   // player eye below horizon height and not under water
```

Attribute definitions (`M/net/minecraft/world/attribute/EnvironmentAttributes.java` ~19-76):

| Attribute | Type / default |
|---|---|
| `visual/fog_color` | RGB |
| `visual/fog_start_distance` | float |
| `visual/fog_end_distance` | float, default 1024 |
| `visual/sky_fog_end_distance` | float, default 512 |
| `visual/cloud_fog_end_distance` | float, default 2048 |
| water fog colour, start and end | |
| `visual/sky_color` | RGB |
| `visual/sunrise_sunset_color` | ARGB |
| `visual/cloud_color` | ARGB; alpha 0 means no clouds |
| `visual/cloud_height` | float, default 192.33 |
| `visual/sun_angle`, `visual/moon_angle`, `visual/star_angle` | ANGLE_DEGREES |
| `visual/moon_phase` | moon phase |
| `visual/star_brightness` | 0–1 |
| `visual/sky_light_color`, `visual/sky_light_factor`, `visual/ambient_light_color`, `visual/ambient_particles` | |
| `audio/*`, `gameplay/*` | `gameplay/water_evaporates` exists |

Values come from `EnvironmentAttributeSystem` layers, applied in this order
(`M/net/minecraft/world/attribute/EnvironmentAttributeSystem.java` ~61-74):

1. the dimension type's `attributes` (constant);
2. biome `attributes` (positional, spatially interpolated);
3. the dimension's `timelines` (time-based);
4. weather (`WeatherAttributes.RAIN/THUNDER`) when `level.canHaveWeather()`;
5. on the client only, the lightning sky-flash layers (`C/.../multiplayer/ClientLevel.java` ~273-281).

Vanilla examples:

- The overworld dimension type sets `sky_color #78a7ff`, `fog_color #c0d8ff` and `cloud_color #ccffffff`
  (`D/dimension_type/overworld.json`).
- The `minecraft:day` timeline (`D/timeline/day.json`, period 24000) keyframes `sun_angle`, `moon_angle` and
  `star_angle` (bezier-eased, 360 → 0 around tick 6000), multiplies `sky_color` and `fog_color` toward black at night,
  sets `star_brightness` up to 0.5, and sets `sunrise_sunset_color` keys such as `#5fefa333`.
- World clocks are data too (`D/world_clock/overworld.json`, and the dimension type's `default_clock`).

So a butterscotch day sky, a Mars fog colour, a per-sol sun angle, star brightness and a **blue**
`sunrise_sunset_color` all need only data: a Mars dimension type plus a Mars sol timeline. Code is needed only for
extra bodies, sun size, a sun-centred aureole and latitude tilt.

### 5.3 What vanilla draws, and how big

`SkyRenderer.render(final GpuBufferSlice skyFog, final SkyRenderState state)` (~129-158) calls
`RenderSystem.setShaderFog(skyFog)` and opens one RenderPass, "Sky", on the main colour and depth target. For END it
calls `renderEndSky` (plus the end flash) and returns. Otherwise it draws four things:

1. **Sky disc**, `renderSkyDisc(renderPass, state.skyColor)` (~160-168). Pipeline `SKY` (POSITION, TRIANGLE_FAN, with
   fog). A 10-vertex disc at y = +16 with radius 512 (`buildSkyDisc` ~432-439), coloured through `ColorModulator`. The
   horizon gradient is nothing but fog: `sky.fsh` does
   `apply_fog(ColorModulator, …, 0.0, FogSkyEnd, FogSkyEnd, FogSkyEnd, FogColor)` (`A/shaders/core/sky.fsh`).
2. **Sunrise/sunset fan**, `renderSunriseAndSunset(renderPass, poseStack, sunAngle, color)` (~285-307), drawn when
   alpha > 0.001. It rotates X by 90°, then Z by (sin(sunAngle) < 0 ? 180 : 0) + 90, then scales z by alpha. Pipeline
   `SUNRISE_SUNSET` (POSITION_COLOR, TRIANGLE_FAN, TRANSLUCENT). The 18-vertex fan has its centre at (0, 100, 0),
   white at alpha 1, and a ring at (sin·120, cos·120, −cos·40) at alpha 0, in 16 steps (`buildSunriseFan` ~333-354).
   **It always sits on the east or west horizon, not around the sun.**
3. **Sun, moon and stars**, `renderSunMoonAndStars(...)` (~185-213). First `poseStack.rotateDegrees(Axis.YP, -90.0F)`,
   then for each body `rotate(Axis.XP, angle)`:
   - the sun via `applyCelestialBodyTransform(poseStack, 100.0F, 30.0F)`, which translates y + 100 and scales a ±1 XZ
     quad by (30, 1, 30) (~215-246);
   - the moon at (100, 20), using base vertex `moonPhase.index() * 4` to pick the phase;
   - the stars, if `starBrightness > 0`.

   Sun and moon use pipeline `CELESTIAL` (POSITION_TEX, QUADS, `BlendFunction.OVERLAY`, which is SRC_ALPHA,ONE for
   colour and ONE,ZERO for alpha), with the celestials atlas texture and `ColorModulator = (1, 1, 1, rainBrightness)`.
   Stars use pipeline `STARS` (POSITION, OVERLAY) with colour = starBrightness.
4. **Dark disc**, `renderDarkDisc` (~170-183), drawn when the player is below horizon height. SKY pipeline; a black
   disc at y −16, translated +12.

After the `YP −90` rotation the rotation axis is world Z, so vanilla's celestial pole lies on the horizon: it models an
observer on the equator. The sun moves east (+X) → zenith → west (−X).

Sizes, computed from the code and the textures:

| Body | Quad half-width at distance 100 | Whole quad | Visible disc |
|---|---|---|---|
| Sun (`SUN_SIZE = 30`) | 30 | 33.4° | `A/textures/environment/celestial/sun.png` is 32×32 and its bright core is pixels 12–19, a quarter of the quad, so 2·atan(7.5/100) = **8.58°**. The real Sun is 0.533°, so vanilla exaggerates it about 16×. A faint halo reaches about 19°. |
| Moon (`MOON_SIZE = 20`) | 20 | 22.6° | `full_moon.png` core is 8/32 of the quad, so **5.72°**. |
| Stars | 0.15–0.25 | 0.17–0.29° | 1500 random quads at radius 100, seed 10842 (`buildStars` ~401-430). |
| End flash | 60 | | |

- **Static GPU buffers**, built once per SkyRenderer: star, top sky, bottom sky, end sky, sun, moon (8 phases),
  sunrise and end flash (`private final GpuBuffer …` ~64-71). Quads are indexed through
  `RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)` (~72).
- **Atlas.** `A/atlases/celestials.json` is a directory source over `environment/celestial`, giving sprites `sun`,
  `end_flash` and `moon/<phase>` (`SUN_SPRITE = Identifier.withDefaultNamespace("sun")`). Because the directory source
  scans every namespace, `assets/redplanet/textures/environment/celestial/phobos.png` automatically becomes sprite
  `redplanet:phobos` in that same atlas (from `DirectoryLister`, §2.6).
- **Shader conventions** (`A/shaders/core/*.vsh` and `*.fsh`): `#version 330`,
  `#extension GL_ARB_separate_shader_objects : require`, explicit `layout(location = N)` on ins and outs, and std140
  blocks `DynamicTransforms {ModelViewMat; TextureMat; ColorModulator; ModelOffset}`, `Projection`, and
  `Fog {FogColor; FogEnvironmentalStart/End; FogRenderDistanceStart/End; FogSkyEnd; FogCloudsEnd}`
  (`A/shaders/include/dynamictransforms.glsl`, `fog.glsl`). Custom shaders must keep this layout so the Vulkan backend
  can use them (**UNVERIFIED** Vulkan translation details). The recommendations below avoid custom shaders.

### 5.4 `DimensionType.skybox`

`enum Skybox { NONE("none"), OVERWORLD("overworld"), END("end") }`. The codec field is `"skybox"` with default
OVERWORLD (`M/net/minecraft/world/level/dimension/DimensionType.java` ~96, ~139-152). `hasEndFlashes()` is true for
END.

How the renderer branches:

- **NONE**: extraction stops after setting `state.skybox`, and LevelRenderer adds no sky pass.
- **END**: the end sky box plus flashes.
- **OVERWORLD**: steps 1–4 above.

Mars uses `"overworld"`.

### 5.5 Is there a Fabric sky hook?

No. fabric-rendering-v1 in this build has no `DimensionRenderingRegistry`, no sky-renderer registry and no mixin on
`SkyRenderer`; `F/fabric-rendering-v1.mixins.json` lists only `renderstate.SkyRenderStateMixin`. `LevelRenderEvents`
start at the main pass, which runs after the sky (§7). A small mixin on `SkyRenderer` is required for extra bodies.

The best mixin points are all private, stable and single-purpose:

- `SkyRenderer.render(GpuBufferSlice, SkyRenderState)` at HEAD, to read our data key, or to cancel and replace
  everything.
- `SkyRenderer.renderSunMoonAndStars(RenderPass, PoseStack, float sunAngle, float moonAngle, float starAngle, MoonPhase, float rainBrightness, float starBrightness)`
  at HEAD, cancellable. This replaces only the celestial bodies, drawing into the **already open** "Sky" RenderPass
  passed in as the first argument. The sky disc, sunrise fan and dark disc stay vanilla.
- `SkyRenderer.renderSunriseAndSunset(RenderPass, PoseStack, float, Vector4fc)` at HEAD, if we want a sun-centred
  aureole instead of the horizon fan.
- `SkyRenderer.<init>` TAIL and `close()` TAIL, to create and free our own GPU buffers in step with vanilla's
  reset-on-reload.

### 5.6 Extra bodies at any direction, and latitude tilt

Copy the pattern of vanilla's `drawCelestialBody` (~248-265), with a model-view matrix that turns the +Y quad toward a
world-space direction `d` (x east, y up, z south):

```java
Matrix4f mv = RenderSystem.getModelViewMatrixCopy()
	.rotate(new Quaternionf().rotationTo(0f, 1f, 0f, d.x(), d.y(), d.z()))
	.translate(0f, 100f, 0f)
	.scale(halfWidth, 1f, halfWidth);     // halfWidth = 100·tan(angularDiameter/2) / (fraction of the sprite the disc fills)
GpuBufferSlice dt = RenderSystem.getDynamicUniforms().writeTransform(mv, new Vector4f(r, g, b, a));  // DynamicGpuData.writeTransform(Matrix4f, Vector4f) ~63
renderPass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
RenderSystem.bindDefaultUniforms(renderPass);
renderPass.setUniform("DynamicTransforms", dt);
renderPass.setUniform("Sampler0", atlas.getTextureView(), atlas.getSampler());
renderPass.setVertexBuffer(0, quadBuffer.slice());
renderPass.setIndexBuffer(quadIndices.getBuffer(6), quadIndices.type());
renderPass.drawIndexed(6, 1, 0, baseVertex, 0);
```

- **Additive bodies** (sun, Earth, Deimos, aureole) use vanilla `RenderPipelines.CELESTIAL`.
- **Occluding or dark bodies** (Phobos against a bright sky) need a copy of CELESTIAL with
  `new ColorTargetState(BlendFunction.TRANSLUCENT)`. The builder pieces are all public: `RenderPipelines.GLOBALS_SNIPPET`,
  `BindGroupLayouts.PROJECTION / DYNAMIC_TRANSFORMS / SAMPLER0`, `DefaultVertexFormat.POSITION_TEX` and
  `PrimitiveTopology.QUADS`.
- **Per-star brightness and colour**, for the repo's `assets/redplanet/sky/stars.bin`, need POSITION_COLOR quads with
  the vanilla `core/position_color` shaders and OVERLAY blending. Vanilla `STARS` is POSITION-only, with one colour for
  every star.
- **Latitude tilt.** The repo's `MarsAstronomy.compute(...)` (`src/main/java/.../mars/astro/MarsAstronomy.java`)
  already returns world-space vectors (x east, y up, z south) for the sun, Phobos, Deimos, Earth and the Moon, plus a
  row-major `starRotation` 3×3 that includes latitude. Driving everything from it is recommended. The alternative, for
  vanilla-style angles, is to pre-rotate the pose about world X (the east–west axis) by the latitude before
  `rotateDegrees(Axis.YP, -90)`. That sign is **UNVERIFIED**: check that the pole sits φ above the −Z horizon.
- JOML's `Matrix3f(m00, m01, m02, m10, …)` constructor takes **columns**, so transpose the row-major array.
- Don't put frame-varying data in the vertex buffers. Vary only `DynamicTransforms` per draw, as vanilla does.

---

## 6. Fog

### 6.1 Classes

From `C/.../renderer/fog/`:

- `FogData implements FabricRenderState` holds `float environmentalStart, renderDistanceStart, environmentalEnd,
  renderDistanceEnd, skyEnd, cloudEnd` and `Vector4f color`.
- `FogRenderer` holds the environment list:
  `private static final List<FogEnvironment> FOG_ENVIRONMENTS = Lists.newArrayList(new LavaFogEnvironment(), new PowderedSnowFogEnvironment(), new BlindnessFogEnvironment(), new DarknessFogEnvironment(), new WaterFogEnvironment(), new AtmosphericFogEnvironment());`
  (~36-43). It is a mutable ArrayList but private and not widened.
  - `public FogData setupFog(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level)`
    (~157-178). The colour comes from the first applicable environment that `providesColor()`; `computeFogColor`
    (~82-151) adds void darkness, boss darkening and night or water vision. The distances come from the first
    applicable environment's `setupFog`. Then render-distance fog is set as
    `renderDistanceStart = rd − clamp(rd/10, 4, 64)` and `renderDistanceEnd = rd`.
  - It also has `updateBuffer(FogData)` (writes a std140 UBO), `getBuffer(FogMode.NONE | WORLD)` and `static toggleFog()`.
- `FogEnvironment` is abstract, with `setupFog(FogData, Camera, ClientLevel, float renderDistance, DeltaTracker)`,
  `providesColor()`, `getBaseColor(ClientLevel, Camera, int renderDistance, float partialTicks)`,
  `modifiesDarkness()`, `getModifiedDarkness(...)` and `isApplicable(@Nullable FogType, Entity)`.
- `AtmosphericFogEnvironment` applies to ATMOSPHERIC fog only.
  - Colour: start from `FOG_COLOR`; tint toward `SUNRISE_SUNSET_COLOR` when looking toward the sun's horizon; then
    blend toward `SKY_COLOR` (darkened by rain and thunder) by a factor derived from `SKY_FOG_END_DISTANCE`.
  - Distances: `environmentalStart = FOG_START_DISTANCE − 160·rain`;
    `environmentalEnd = max(min(96, FOG_END), FOG_END − 256·rain)`; `skyEnd = min(rd, SKY_FOG_END_DISTANCE)`;
    `cloudEnd = min(cloudRange·16, CLOUD_FOG_END_DISTANCE)`. Boss fog clamps them to 10 and 96.
  - `rain` is a smoothed `rainLevel × sky-light factor × (biome has precipitation ? 1 : 0.5)`.
- Flow: `GameRenderer.extractCamera` stores `cameraState.fogData` (~771-774). `renderLevel` uploads it and passes
  `terrainFog` to the level and sky passes (~660-667).

### 6.2 Hooks for a dust storm

There is no Fabric fog API. The options, best first:

1. **Client environment-attribute layers.** One tiny mixin, and no fog code touched. `ClientLevel` builds its
   attribute system in `private Builder addEnvironmentAttributeLayers(Builder)` (~273-281), which already appends
   client-only time-based layers. Inject at RETURN and append `addTimeBasedLayer(...)` or `addPositionalLayer(...)` for
   `FOG_START_DISTANCE`, `FOG_END_DISTANCE`, `SKY_FOG_END_DISTANCE`, `FOG_COLOR`, `SKY_COLOR`, `SUNRISE_SUNSET_COLOR`,
   `SKY_LIGHT_FACTOR` and `STAR_BRIGHTNESS`.
   - Layers appended last override the earlier ones.
   - The camera probe samples once per tick and lerps between ticks (`EnvironmentAttributeProbe.ValueProbe.get` →
     `partialTickLerp`).
   - Layer types (`M/net/minecraft/world/attribute/EnvironmentAttributeLayer.java`):
     `TimeBased<V> { V applyTimeBased(V base, int cacheTickId); }` and
     `Positional<V> { V applyPositional(V base, Vec3 pos, @Nullable SpatialAttributeInterpolator biomes); }`.
   - The same mechanism handles the darkening during ascent (§11.6).
2. Insert a custom `FogEnvironment` before `AtmosphericFogEnvironment` through a static `@Accessor("FOG_ENVIRONMENTS")`.
3. A MixinExtras `@ModifyReturnValue` on `FogRenderer.setupFog` that edits the returned `FogData`.

**Storm intensity can come free from vanilla weather.**

- Mars biomes with `has_precipitation: false` never render rain: `WeatherEffectRenderer` draws only where
  `level.getPrecipitationAt(...) != NONE` (`C/.../renderer/WeatherEffectRenderer.java` ~84-92).
- `level.getRainLevel()` is still synced, and it drives `WeatherAttributes` whenever `canHaveWeather()` is true
  (`hasSkyLight && !hasCeiling && != END`, `M/.../world/level/Level.java` ~937-939).
- Override the vanilla rain visuals with our own last layer.

---

## 7. Fabric level-render events

Files: `F/net/fabricmc/fabric/api/client/rendering/v1/level/`. Firing points come from `LevelRendererMixin` and
`LevelExtractorMixin`.

| Event | Callback | When it fires | Inside an open RenderPass? |
|---|---|---|---|
| `LevelExtractionEvents.AFTER_BLOCK_OUTLINE_EXTRACTION` | `void afterBlockOutlineExtraction(LevelExtractionContext, @Nullable HitResult)` | RETURN of `LevelExtractor.extractBlockOutline` | n/a (extract phase) |
| `LevelExtractionEvents.END_EXTRACTION` | `void endExtraction(LevelExtractionContext)` | RETURN of `LevelExtractor.extract`, after sky extraction | n/a |
| `LevelRenderEvents.START_MAIN` | `void startMain(LevelTerrainRenderContext)` | wraps the opaque `renderGroup` in `executeSolid`: after the sky, before opaque terrain | **yes** ("Solid" or "Main") |
| `AFTER_OPAQUE_TERRAIN` | `void afterOpaqueTerrain(LevelTerrainRenderContext)` | after opaque terrain | yes |
| `COLLECT_SUBMITS` | `void collectSubmits(LevelRenderContext)` | RETURN of `submitFeatures`. In code this runs *before* the frame graph executes (LevelRenderer ~205 vs ~283), despite what the javadoc implies. | no. Add submits here. |
| `AFTER_SOLID_FEATURES` | `void afterSolidFeatures(LevelRenderContext)` | after `featureFrame.executeSolid` | yes |
| `AFTER_TRANSLUCENT_FEATURES` | `void afterTranslucentFeatures(LevelRenderContext)` | classic path: after `executeTranslucent`, inside the pass. OIT path: RETURN of `executeOit`. | classic yes; OIT no |
| `BEFORE_BLOCK_OUTLINE` | `boolean beforeBlockOutline(LevelRenderContext, BlockOutlineRenderState)`; false cancels | inside `submitBlockOutline` | no |
| `BEFORE_GIZMOS` | `void beforeGizmos(LevelRenderContext)` | before `finalizeGizmoCollection` in `submitFeatures` | no |
| `BEFORE_TRANSLUCENT_TERRAIN`, `AFTER_TRANSLUCENT_TERRAIN` | `(LevelRenderContext)` | wrap the translucent `renderGroup` in `executeClassicTransparency` | yes; classic path only |
| `END_MAIN` | `void endMain(LevelRenderContext)` | RETURN of the main-pass lambda (`lambda$addMainPass$0`) | **no**, so it is safe to open your own pass |

- **`END_MAIN` fires after clouds, weather and the world border.** In the classic path they are drawn inside
  `executeClassicTransparency` (~682-689); in the OIT path inside `executeOit`. The javadoc's "before clouds, weather"
  is stale.
- **Contexts:**
  - `AbstractLevelRenderContext { GameRenderer gameRenderer(); LevelRenderer levelRenderer(); LevelRenderState levelState(); }`
  - `LevelTerrainRenderContext { @Nullable ChunkSectionsToRender sectionsToRender(); }`
  - `LevelRenderContext { SubmitNodeCollector submitNodeCollector(); PoseStack poseStack(); }`. The pose stack is the
    one created in `submitFeatures`, and is null outside it.
  - `LevelExtractionContext { ClientLevel level(); Camera camera(); DeltaTracker deltaTracker(); }`
- **Camera and projection** come from `levelState().cameraRenderState`: `pos`, `orientation`, `projectionMatrix`,
  `viewRotationMatrix`, `cullFrustum` and `fogData`.
- **There is no buffer source any more.** Use `SubmitNodeCollector.submitCustomGeometry(...)` with positions relative
  to `cameraRenderState.pos`, as vanilla does with `state.x - camX` (LevelRenderer ~975-981).
- **No raw drawing inside an open pass.** Opening a second RenderPass while one is open throws
  `IllegalStateException("Close the existing render pass before creating a new one!")`
  (`C/com/mojang/renderpearl/frontend/FrontendCommandEncoder.java` ~100-103), and the open pass isn't exposed in the
  context. So raw GPU drawing from START_MAIN through AFTER_TRANSLUCENT_TERRAIN isn't possible. Use `COLLECT_SUBMITS`
  or `END_MAIN`.

---

## 8. Sound

- **`SoundManager`** (`C/.../sounds/SoundManager.java`): `play(SoundInstance)`, `queueTickingSound`, `playDelayed`,
  `stop(...)`, `updateSource(Camera)`, `refreshCategoryVolume`, `pauseAllExcept`, `resume`, and
  `public void updateCategoryVolume(final SoundSource source, final float gain)` (~226-228).
- **`SoundEngine.play(SoundInstance)`** (`C/.../sounds/SoundEngine.java` ~365-468):
  - resolves the Sound and sets `attenuationDistance = sound.getAttenuationDistance(instanceVolume)` and
    `volume = calculateVolume(instanceVolume, source)`;
  - gets a channel with `channelAccess.createHandle(STREAMING | STATIC).join()`;
  - configures it in `handle.execute(channel -> { setPitch; setVolume; linearAttenuation(dist) or disableAttenuation(); setLooping; setSelfPosition; setRelative; })` (~436-448);
  - attaches the buffer or stream and calls `channel.play()` in later executor tasks.
- **Volume** is `Mth.clamp(volume, 0, 1) * Mth.clamp(options.getFinalSoundSourceVolume(source), 0, 1) * this.gainBySource.getFloat(source)`
  (~486-488). `gainBySource` defaults to 1 and is set by `updateCategoryVolume` (~155-158). `MusicManager` sets the
  MUSIC gain every tick (`C/.../sounds/MusicManager.java` ~133). Ticking sounds recompute their volume every tick
  (`tickInGameSound` ~232-260).
- **`SoundSource` values:** MASTER, MUSIC, RECORDS, WEATHER, BLOCKS, HOSTILE, NEUTRAL, PLAYERS, AMBIENT, VOICE, UI
  (`M/net/minecraft/sounds/SoundSource.java`).
- **OpenAL** (`C/com/mojang/blaze3d/audio/`):
  - One AL source per playing sound. `Channel.create()` calls `AL10.alGenSources(newId)` (`Channel.java` ~23-27), and
    the source is deleted on release (`alDeleteSources`, ~33-51).
  - `private final int source` (~18). `setVolume` → `alSourcef(source, AL_GAIN)`; `linearAttenuation` sets
    `AL_LINEAR_DISTANCE` and the max distance.
  - All channel work runs on `SoundEngineExecutor`, through `ChannelAccess.ChannelHandle.execute`.
  - `Library.init(@Nullable String, DeviceList, boolean)` (~61-104) opens the device, creates the context and makes it
    current. The context attributes are HRTF if requested and `ALC_OUTPUT_LIMITER_SOFT` (6554) = 1; there is **no**
    `ALC_MAX_AUXILIARY_SENDS`. It requires AL_EXT_source_distance_model and AL_EXT_LINEAR_DISTANCE.
    `Library.cleanup()` destroys the context and closes the device.
  - `Listener.setTransform` calls `alListener3f(AL_POSITION)` and `alListenerfv(AL_ORIENTATION)`. There is no listener
    gain.
- **EFX.** Vanilla doesn't use it, but `org.lwjgl.openal.EXTEfx` ships in lwjgl-openal 3.4.3. I checked this with
  `javap` on `~/.gradle/caches/.../lwjgl-openal-3.4.3.jar`:
  - methods `int alGenFilters()`, `void alDeleteFilters(int)`, `void alFilteri(int, int, int)`, `void alFilterf(int, int, float)`;
  - constants `AL_FILTER_TYPE = 32769`, `AL_FILTER_LOWPASS = 1`, `AL_LOWPASS_GAIN = 1`, `AL_LOWPASS_GAINHF = 2`,
    `AL_DIRECT_FILTER = 131077`, `AL_FILTER_NULL = 0`;
  - `ALCCapabilities.ALC_EXT_EFX`.

  A low-pass filter on the direct path needs no auxiliary sends, so the vanilla context is enough. Whether the macOS
  arm64 OpenAL Soft native exposes ALC_EXT_EFX is **UNVERIFIED** (OpenAL Soft normally does). Check at runtime and fall
  back to volume only.
- **Every dimension change stops all sounds** (`updateLevelInEngines(level, true)` → `soundManager.stop()`). Filters
  therefore only ever need applying to new sounds. Restart any interlude audio after `AFTER_CLIENT_LEVEL_CHANGE`.
- **Fabric sound API.** It has only `FabricSoundInstance#getAudioStream(SoundBufferLibrary, Identifier, boolean)`, for
  custom streams (implemented by `SoundEngineMixin`'s redirect; the placeholder sound is `fabric-sound-api-v1:empty`).
  That could drive a procedural engine rumble or software DSP, but the filter doesn't need it.

---

## 9. Input and timing

- **Registration.** `KeyMappingHelper.registerKeyMapping(KeyMapping)` (`F/.../api/client/keymapping/v1/KeyMappingHelper.java`
  ~45-48) must run before `Options` exists, or it throws `"GameOptions has already been initialised"`
  (`KeyMappingRegistryImpl` ~33-35). Call it from `onInitializeClient`. `getBoundKeyOf(KeyMapping)` is also there.
- **`KeyMapping`:** constructor `(String name, InputConstants.Type type, int value, KeyMapping.Category category)`,
  plus an `int order` overload (`C/.../KeyMapping.java` ~87-95). Methods `isDown()`, `consumeClick()`,
  `matches(KeyEvent)`, `matchesMouse(MouseButtonEvent)` (~105-172).
- **Categories are records now:** `public record Category(Identifier id)`, created with
  `public static KeyMapping.Category register(final Identifier id)`, which throws if registered twice. The label is
  `Component.translatable(id.toLanguageKey("key.category"))` (~200-226), so the lang key is
  `key.category.redplanet.<path>`.
- **Key codes are SDL scancodes:** `InputConstants.KEY_BACKSPACE = 42`, `KEY_V = 25`, `KEY_F6 = 63`. `InputConstants.Type`
  is `KEYBOARD` or `MOUSE`; there is no SCANCODE type. Polling: `InputConstants.isKeyDown(int key)` (~217).
- **Reading presses.** `Minecraft.tick()` calls `handleKeybinds()` only when there is no screen and no overlay
  (~1899-1905). So poll our mappings in `ClientTickEvents.END_CLIENT_TICK`, whose callback is
  `void onEndTick(Minecraft client)` (`F/.../client/event/lifecycle/v1/ClientTickEvents.java`; injected at the HEAD and
  RETURN of `Minecraft.tick`), with `while (KEY.consumeClick())`. Inside a screen, use `keyPressed(KeyEvent)` with
  `KEY.matches(event)`. `Gui.setScreen` calls `KeyMapping.releaseAll()`.
- **`DeltaTracker`** (`C/.../DeltaTracker.java`):
  - `getGameTimeDeltaTicks()`: ticks elapsed this frame.
  - `getGameTimeDeltaPartialTick(boolean ignoreFrozenGame)`: 0–1 between ticks; frozen at the residual while paused.
  - `getRealtimeDeltaTicks()`: UI time, capped at 0.5 after a hitch longer than 7 ticks.
  - `Minecraft.getDeltaTracker()` (~2843). Screens get `deltaTracker.getGameTimeDeltaTicks()` as their `float a`
    (Gui ~161, ~174). HUD elements get the frame's tracker.
- **Other client events:** `ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE (Minecraft, ClientLevel)`,
  `ClientLifecycleEvents.CLIENT_STARTED / CLIENT_STOPPING`, `ClientTickEvents.START/END_LEVEL_TICK (ClientLevel)`.

---

## 10. Config screen with vanilla widgets only

Widgets in `C/.../gui/components/`:

- `Button.builder(Component, Button.OnPress).bounds(x, y, w, h).tooltip(Tooltip).build()` (`Button.java` ~21-106;
  DEFAULT_WIDTH 150, DEFAULT_HEIGHT 20).
- `CycleButton.builder(Function<T, Component>, T default).withValues(...).create(x, y, w, h, Component, OnValueChange<T>)`,
  plus `CycleButton.onOffBuilder(boolean)` and `booleanBuilder(...)` (`CycleButton.java` ~179-305).
- `AbstractSliderButton(x, y, w, h, Component, double)` with abstract `updateMessage()` and `applyValue()` (~19-160).
- `Checkbox`, `EditBox`, `StringWidget`, `MultiLineTextWidget`, `Tooltip`.
- `OptionsList`: `addBig(OptionInstance | AbstractWidget)`, `addSmall(...)`, `addHeader(Component)` (~16-60). It
  extends `ContainerObjectSelectionList`.
- Layouts in `gui/layouts`: `HeaderAndFooterLayout`, `GridLayout`, `LinearLayout` and others.

**Fastest path.** Extend `OptionsSubScreen(Screen lastScreen, Options options, Component title)` and implement
`protected abstract void addOptions()`. It builds a header, an `OptionsList` and a Done button, applies unsaved slider
values in `onClose`, and saves vanilla options in `removed()` (`C/.../gui/screens/options/OptionsSubScreen.java`).

- Mod options are `OptionInstance<T>` objects. Either
  `OptionInstance.createBoolean(String captionId, boolean initial, ValueUpdateListener<? super Boolean>)`, or
  `new OptionInstance<>(captionId, TooltipSupplier, CaptionBasedToString, ValueSet, initial, ValueUpdateListener)` with
  a value set of `OptionInstance.Enum<T>(List<T>, Codec<T>)`, `IntRange(min, max)` or `UnitDouble.INSTANCE`
  (`C/.../OptionInstance.java` ~48-110, ~251-276, ~576).
- Labels: `Options.genericValueLabel(Component caption, Component value)` (`Options.java` ~2006).
- Persist our own values ourselves, for example as JSON under `FabricLoader.getConfigDir()`.

There is no vanilla or Fabric API entry point for a mod config screen. Mod Menu is an external, optional integration
through its `modmenu` entrypoint; we don't use it. Open our screen from a key mapping, or add a button to
`OptionsScreen` with `ScreenEvents.AFTER_INIT` plus `Screens.getWidgets(screen).add(...)`
(`F/.../api/client/screen/v1/Screens.java` ~43). Watch for layout overlap with the button approach.

---

## 11. Recommendations for redplanet

### 11.1 Hook summary

| Need | Hook | Kind |
|---|---|---|
| Cinematic camera pose and roll | `Camera.alignWithEntity` TAIL | mixin |
| Cinematic FOV | `Camera.calculateFov` RETURN | mixin |
| Hide the hand | `GameRenderer.renderItemInHand` HEAD, cancel | mixin |
| Hide vanilla HUD elements one by one | `HudElementRegistry.replaceElement` | Fabric |
| Hide the block outline | `LevelRenderEvents.BEFORE_BLOCK_OUTLINE` returns false | Fabric |
| Freeze mouse look (optional) | `MouseHandler.turnPlayer` HEAD, cancel (~318) | mixin |
| Telemetry and suit HUD | `HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, …)` | Fabric |
| Gauges, arcs, globes | custom `GuiElementRenderState` | vanilla API |
| Interlude | `InterludeScreen extends LevelLoadingScreen`, opened before the transfer; fallback `@ModifyArg` in `ClientPacketListener.startWaitingForNewLevel` | API, plus 1 mixin |
| Mars sky bodies | `SkyRenderer.renderSunMoonAndStars` HEAD, cancel; plus `<init>` and `close` TAIL | mixin |
| Mars sky colours, blue sunset, sol length | dimension type `attributes` plus a timeline JSON | data |
| Ascent darkening, dust-storm fog | `ClientLevel.addEnvironmentAttributeLayers` RETURN | mixin |
| Per-frame sky data | `LevelExtractionEvents.END_EXTRACTION` + `skyRenderState.setData(KEY, …)` | Fabric |
| Quieter sound on Mars | `SoundManager.updateCategoryVolume`, per category | vanilla API |
| Low-pass filter on Mars | `Library.init` TAIL, `Library.cleanup` HEAD, `@WrapOperation` on the first `ChannelHandle.execute` in `SoundEngine.play`, and a `Channel` accessor | mixins |
| Key bindings | `KeyMappingHelper` + `END_CLIENT_TICK` | Fabric |
| Config screen | an `OptionsSubScreen` subclass | vanilla |

Fabric API has no mixins on `Camera`, `SkyRenderer`, `FogRenderer`, `Channel`, `Library` or `LevelLoadingScreen` (none
found by grep in `F/`). It does have mixins on `ClientLevel`, but none on `addEnvironmentAttributeLayers`. The
skeletons below haven't been compiled; package `io.github.avi130805.redplanet.client…`.

### 11.2 Camera override (CameraMixin)

```java
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow @Final private static Vector3fc FORWARDS;
	@Shadow @Final private static Vector3fc UP;
	@Shadow @Final private static Vector3fc LEFT;
	@Shadow @Final private Quaternionf rotation;
	@Shadow @Final private Vector3f forwards;
	@Shadow @Final private Vector3f up;
	@Shadow @Final private Vector3f left;
	@Shadow private int matrixPropertiesDirty;
	@Shadow private boolean detached;
	@Shadow protected abstract void setPosition(Vec3 position);
	@Shadow protected abstract void setRotation(float yRot, float xRot);

	/** Replaces the entity-aligned pose with the cinematic one. update() builds the frustum and projection right after this. */
	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void redplanet$applyCinematicPose(float partialTicks, CallbackInfo ci) {
		CinematicPose pose = CinematicCamera.INSTANCE.poseFor(partialTicks);   // null = vanilla camera
		if (pose == null) return;
		this.setPosition(pose.position());
		this.setRotation(pose.yaw(), pose.pitch());          // sets xRot/yRot, vectors, dirty bits
		if (pose.roll() != 0.0F) {                            // vanilla always passes 0 for Z; rebuild with roll
			this.rotation.rotationYXZ((float) Math.PI - pose.yaw() * Mth.DEG_TO_RAD, -pose.pitch() * Mth.DEG_TO_RAD, pose.roll() * Mth.DEG_TO_RAD);
			FORWARDS.rotate(this.rotation, this.forwards);
			UP.rotate(this.rotation, this.up);
			LEFT.rotate(this.rotation, this.left);
			this.matrixPropertiesDirty |= 3;
		}
		this.detached = pose.showLocalPlayer();               // false = player hidden "inside" the ship
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void redplanet$cinematicFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		float fov = CinematicCamera.INSTANCE.fovFor(partialTicks);   // NaN = keep vanilla
		if (!Float.isNaN(fov)) cir.setReturnValue(fov);
	}
}

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	/** The hand draws whenever the camera type is first person, even with the camera elsewhere. */
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void redplanet$hideHand(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depth, CallbackInfo ci) {
		if (CinematicCamera.INSTANCE.isActive()) ci.cancel();
	}
}
```

`CinematicCamera` evaluates the data-driven keyframes: pad wide shot, tower cam looking up (narrow FOV), orbit,
chase, onboard. It interpolates the ship position
(`Mth.lerp(pt, ship.xo, ship.getX())`), applies Catmull-Rom or smoothstep easing, and handles look-at with
`yaw = (float) (Mth.atan2(-d.x, d.z) * Mth.RAD_TO_DEG)` and `pitch = (float) (-Mth.atan2(d.y, Math.sqrt(d.x*d.x + d.z*d.z)) * Mth.RAD_TO_DEG)`.

- Shake is a small noise offset on position, yaw and pitch, scaled by the config's "reduce shake" option.
- Clip-test every camera position against blocks (§1.9).
- When roll changes by more than about 2°, call `Minecraft.getInstance().levelRenderer.sectionOcclusionGraph().invalidate()` (§1.6).
- Turn the override off, or snap it to the player, while a `LevelLoadingScreen` subclass is open (§1.9).
- The roll sign convention is **UNVERIFIED**; check it visually.

### 11.3 HUD: suppress vanilla elements during cinematics, draw telemetry and the suit HUD

```java
public static void registerHud() {
	for (Identifier id : List.of(VanillaHudElements.CROSSHAIR, VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR,
			VanillaHudElements.ARMOR_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH,
			VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.HELD_ITEM_TOOLTIP,
			VanillaHudElements.MOB_EFFECTS, VanillaHudElements.SCOREBOARD)) {
		HudElementRegistry.replaceElement(id, vanilla -> (g, dt) -> {
			if (!CinematicCamera.INSTANCE.hidesVanillaHud()) vanilla.extractRenderState(g, dt);
		});
	}
	HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, RedPlanet.id("telemetry"), new TelemetryHud());
	HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, RedPlanet.id("suit"), new SuitHud());
	LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register((ctx, outline) -> !CinematicCamera.INSTANCE.isActive());
}

public final class TelemetryHud implements HudElement {
	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker dt) {
		FlightTelemetry t = FlightClientState.telemetry(dt.getGameTimeDeltaPartialTick(false));   // null when not flying
		if (t == null) return;
		Font font = Minecraft.getInstance().font;
		int w = g.guiWidth(), h = g.guiHeight();
		g.fillGradient(0, h - 56, w, h, 0x00000000, 0xB0000000);                          // webcast band
		GuiShapes.ring(g, 48, h - 28, 17, 20, -225f, 45f, 0x40FFFFFF);                    // gauge track
		GuiShapes.ring(g, 48, h - 28, 17, 20, -225f, -225f + 270f * t.speedFraction(), 0xFFFFFFFF);
		g.centeredText(font, String.format(Locale.ROOT, "%,d", t.speedKmh()), 48, h - 32, 0xFFFFFFFF);
		g.centeredText(font, "KM/H", 48, h - 22, 0xFFB0B0B0);
		g.pose().pushMatrix();
		g.pose().translate(w / 2f, h - 40f);
		g.pose().scale(2f, 2f);
		g.centeredText(font, t.clockText(), 0, 0, 0xFFFFFFFF);                            // "T+00:02:41"
		g.pose().popMatrix();
		for (int i = 0; i < t.engineCount(); i++) {                                       // Raptor status dots
			GuiShapes.disc(g, 120 + (i % 11) * 5, h - 40 + (i / 11) * 5, 2f, t.engineOn(i) ? 0xFFFFFFFF : 0x50FFFFFF);
		}
		g.outline(199, h - 41, 62, 6, 0x80FFFFFF);                                         // LOX bar
		g.fill(200, h - 40, 200 + Math.round(60 * t.lox()), h - 36, 0xFFFFFFFF);
		// CH4 bar, event timeline ticks and labels the same way
	}
}

public final class GuiShapes {
	public static void ring(GuiGraphicsExtractor g, float cx, float cy, float rIn, float rOut, float deg0, float deg1, int argb) {
		g.guiRenderState.addGuiElement(new RingRenderState(new Matrix3x2f(g.pose()), cx, cy, rIn, rOut,
			deg0 * Mth.DEG_TO_RAD, deg1 * Mth.DEG_TO_RAD, argb, argb, g.scissorStack.peek()));
	}
	public static void disc(GuiGraphicsExtractor g, float cx, float cy, float r, int argb) { ring(g, cx, cy, 0f, r, 0f, 360f, argb); }
}

/** Annular sector, angles in radians, y down (angle grows clockwise on screen). */
public record RingRenderState(Matrix3x2fc pose, float cx, float cy, float rIn, float rOut, float a0, float a1,
		int innerColor, int outerColor, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements GuiElementRenderState {
	public RingRenderState(Matrix3x2fc pose, float cx, float cy, float rIn, float rOut, float a0, float a1, int innerColor, int outerColor,
			@Nullable ScreenRectangle scissorArea) {
		this(pose, cx, cy, rIn, rOut, a0, a1, innerColor, outerColor, scissorArea, computeBounds(pose, cx, cy, rOut, scissorArea));
	}
	private static @Nullable ScreenRectangle computeBounds(Matrix3x2fc pose, float cx, float cy, float r, @Nullable ScreenRectangle scissor) {
		int size = (int) Math.ceil(2 * r) + 2;
		ScreenRectangle b = new ScreenRectangle(Mth.floor(cx - r) - 1, Mth.floor(cy - r) - 1, size, size).transformMaxBounds(pose);
		return scissor != null ? scissor.intersection(b) : b;   // a null bounds() would drop the element
	}
	@Override public void buildVertices(VertexConsumer vc) {
		int n = Math.max(4, (int) Math.ceil(Math.abs(a1 - a0) * Math.max(rOut, 4f) / 3f));
		for (int i = 0; i < n; i++) {
			float t0 = a0 + (a1 - a0) * i / n, t1 = a0 + (a1 - a0) * (i + 1) / n;
			float c0 = Mth.cos(t0), s0 = Mth.sin(t0), c1 = Mth.cos(t1), s1 = Mth.sin(t1);
			// outer t0, inner t0, inner t1, outer t1: same signed-area orientation as vanilla quads when a1 > a0
			vc.addVertexWith2DPose(pose, cx + c0 * rOut, cy + s0 * rOut).setColor(outerColor);
			vc.addVertexWith2DPose(pose, cx + c0 * rIn, cy + s0 * rIn).setColor(innerColor);
			vc.addVertexWith2DPose(pose, cx + c1 * rIn, cy + s1 * rIn).setColor(innerColor);
			vc.addVertexWith2DPose(pose, cx + c1 * rOut, cy + s1 * rOut).setColor(outerColor);
		}
	}
	@Override public RenderPipeline pipeline() { return RenderPipelines.GUI; }
	@Override public TextureSetup textureSetup() { return TextureSetup.noTexture(); }
}
```

- **Polylines** (the Hohmann ellipse, orbits, trajectory plots): for each segment p0 → p1, with
  `n = perpendicular(p1 - p0) * halfWidth`, emit `p0 - n, p0 + n, p1 + n, p1 - n`. That order gives the vanilla
  orientation. If anything comes out invisible, swap the order or use a pipeline built from
  `RenderPipeline.builder(RenderPipelines.GUI_SNIPPET).withLocation(Identifier.fromNamespaceAndPath("redplanet", "pipeline/gui_nocull")).withCull(false).build()`.
- **Additive glow:** the same builder with `.withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))`.
- **Registering pipelines.** Call `RenderPipelines.register(pipeline)` (public through the access widener, ~1383)
  during client init, before the first resource reload. `ShaderManager` then precompiles it and fails loudly if it is
  broken. Unregistered pipelines still work: `PipelineCache.get` compiles a missing pipeline on first use
  (`C/com/mojang/blaze3d/pipeline/PipelineCache.java` ~27-40), at the cost of a hitch.
- **When the HUD shows.** Fabric HUD elements are invisible with F1 and during loading screens (§2.1). For a
  telemetry overlay that must ignore F1, inject into `Gui.extractRenderState` after `hud.extractRenderState` instead
  (optional mixin).
- **Suit HUD:** same building blocks (O₂ ring, suit and outside pressure, temperature, dose), plus the helmet's
  `camera_overlay` for the visor (§1.8).

### 11.4 Interlude over the world and in place of the loading screen

**Flow.**

1. The server sends a `redplanet:interlude_start` payload, waits a few ticks, then teleports the player.
2. The client opens `InterludeScreen`. The world keeps rendering behind it, the HUD is hidden automatically
   (Hud ~222) and the game isn't paused.
3. The respawn packet arrives. Vanilla calls `update(...)` on our screen (§3.3, step 3) instead of opening its own.
4. The screen closes once the level is ready **and** the animation is done, or skip was pressed.

```java
public final class InterludeScreen extends LevelLoadingScreen {
	private LevelLoadTracker tracker;
	private final InterludeTimeline timeline;      // Earth shrink → refilling/TMI → clock jumps months → Mars grows
	private final long startMs = Util.getMillis();
	private boolean arrivalStarted, skipRequested;

	public InterludeScreen(InterludeTimeline timeline) { this(new LevelLoadTracker(), timeline, false); }
	InterludeScreen(LevelLoadTracker tracker, InterludeTimeline timeline, boolean arrivalStarted) {
		super(tracker, LevelLoadingScreen.Reason.OTHER);
		this.tracker = tracker; this.timeline = timeline; this.arrivalStarted = arrivalStarted;
	}

	@Override public void update(LevelLoadTracker loadTracker, LevelLoadingScreen.Reason reason) {  // from ClientPacketListener.startWaitingForNewLevel
		super.update(loadTracker, reason);
		this.tracker = loadTracker;
		this.arrivalStarted = true;
	}

	@Override public void tick() {          // don't call super: it closes as soon as the level is ready
		long elapsed = Util.getMillis() - this.startMs;
		if (this.arrivalStarted && this.tracker.isLevelReady() && (this.skipRequested || this.timeline.finished(elapsed))) {
			this.onClose();                   // LevelLoadingScreen.onClose narrates "ready", then gui.setScreen(null)
		}
		// also add a timeout or abort path in case the transfer fails
	}

	@Override public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int alpha = this.timeline.backgroundAlpha(Util.getMillis() - this.startMs);   // 0 → 255: fades in over the live world
		g.fill(0, 0, this.width, this.height, ARGB.color(alpha, 0, 0, 0));
	}

	@Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		InterludeRenderer.draw(g, this.width, this.height, this.timeline, Util.getMillis() - this.startMs);  // stars, globes, ellipse, clock
	}

	@Override public boolean keyPressed(KeyEvent event) {
		if (RedPlanetKeys.SKIP_CINEMATIC.matches(event)) { this.skipRequested = true; return true; }
		return super.keyPressed(event);
	}
}

/** Fallback if the respawn packet beats our screen: swap vanilla's loading screen for the interlude. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
	@Shadow private @Nullable LevelLoadTracker levelLoadTracker;

	@ModifyArg(method = "startWaitingForNewLevel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setScreenAndShow(Lnet/minecraft/client/gui/screens/Screen;)V"))
	private Screen redplanet$interludeInsteadOfLoading(Screen vanilla) {
		return InterludeController.pending() ? new InterludeScreen(this.levelLoadTracker, InterludeController.timeline(), true) : vanilla;
	}
}
```

Notes:

- The dimension switch stops all sounds. Restart the interlude ambience from `ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE`.
- If the screen stays up after the level is ready, the server already treats the player as loaded
  (`ServerboundPlayerLoadedPacket`). Keep the ship and its passengers protected until the screen closes.
- Client gametests that wait for "world loaded" also wait for our screen, because they check
  `instanceof LevelLoadingScreen`. Screenshots of the interlude need explicit waits.

### 11.5 Globes as a GUI element (orthographic projection on the CPU)

```java
public record GlobeRenderState(Matrix3x2fc pose, float cx, float cy, float radius, Identifier texture, Quaternionfc spin,
		Vector3fc sunDir, @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements GuiElementRenderState {
	private static final int LAT = 24, LON = 48;
	@Override public void buildVertices(VertexConsumer vc) {
		Vector3f tmp = new Vector3f();
		for (int i = 0; i < LAT; i++) for (int j = 0; j < LON; j++) {
			if (point(i + 0.5f, j + 0.5f, tmp).z < -0.05f) continue;       // +z faces the viewer; skip the far side
			emit(vc, i, j, tmp); emit(vc, i + 1, j, tmp); emit(vc, i + 1, j + 1, tmp); emit(vc, i, j + 1, tmp);   // TL, BL, BR, TR like vanilla
		}
	}
	private Vector3f point(float i, float j, Vector3f out) {
		float lat = Mth.PI * (0.5f - i / LAT), lon = 2f * Mth.PI * j / LON - Mth.PI;
		return spin.transform(out.set(Mth.cos(lat) * Mth.sin(lon), Mth.sin(lat), Mth.cos(lat) * Mth.cos(lon)));
	}
	private void emit(VertexConsumer vc, int i, int j, Vector3f tmp) {
		Vector3f n = point(i, j, tmp);
		float light = Mth.clamp(n.dot(sunDir) * 1.2f + 0.08f, 0.06f, 1f);  // terminator plus a faint night side
		vc.addVertexWith2DPose(pose, cx + n.x * radius, cy - n.y * radius)
			.setUv((float) j / LON, (float) i / LAT)
			.setColor(ARGB.colorFromFloat(1f, light, light, light));
	}
	@Override public RenderPipeline pipeline() { return RenderPipelines.GUI_TEXTURED; }
	@Override public TextureSetup textureSetup() {
		AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(texture);
		return TextureSetup.singleTexture(tex.getTextureView(), RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
	}
}
```

- Add an atmosphere halo with `RingRenderState`: inner colour about `0x704080FF`, outer colour transparent.
- Draw the Hohmann transfer with polylines: Earth's orbit, Mars's orbit, a transfer half-ellipse (a = 1.262 AU), and a
  moving ship marker. The numbers belong in SCIENCE.md.
- For true 3D with depth, port this to a `PictureInPictureRenderer` (§4.1): register it in `onInitializeClient`, and
  in `renderToTexture` set a perspective projection and submit the sphere with
  `submitCustomGeometry(poseStack, RenderTypes.text(texture), …)`.

### 11.6 Mars sky, plus Earth-ascent darkening and dust storms

**Data first.** A Mars dimension type with `"skybox": "overworld"`, butterscotch `visual/sky_color`, a fog colour,
`cloud_color` alpha 0 (no water clouds by default), its own `default_clock` and a Mars sol timeline (period 24660
ticks) that keyframes `sun_angle`, `star_angle`, `star_brightness`, sky and fog multipliers, and a **blue**
`visual/sunrise_sunset_color`. `AtmosphericFogEnvironment` then also tints the horizon fog blue toward the setting sun
(§6.1).

**Per-frame astronomy.** Compute it in `LevelExtractionEvents.END_EXTRACTION` and attach it to the sky state:

```java
public record MarsSkyData(Vector3fc sun, float sunDiameterDeg, Vector3fc phobos, float phobosDiameterDeg, float phobosLit,
		Vector3fc deimos, Vector3fc earth, float earthBrightness, Matrix3fc starRotation) {
	public static final RenderStateDataKey<MarsSkyData> KEY = RenderStateDataKey.create(() -> "redplanet:mars_sky");
}

LevelExtractionEvents.END_EXTRACTION.register(ctx -> {
	if (!MarsDimension.is(ctx.level())) return;      // only Mars gets the data; other dimensions stay vanilla
	MarsAstronomy.Sky sky = MarsAstronomy.compute(marsClockTicks(ctx.level()), ctx.deltaTracker().getGameTimeDeltaPartialTick(false),
		latitudeAt(ctx.camera().position()), START_LS, YEAR_COMPRESSION, EARTH_PHASE0, MOON_SEED);
	ctx.levelState().skyRenderState.setData(MarsSkyData.KEY, MarsSkyData.from(sky));
});
```

**SkyRenderer mixin.** Vanilla keeps drawing the sky disc, the sunrise fan and the dark disc; we replace only the
bodies:

```java
@Mixin(SkyRenderer.class)
abstract class SkyRendererMixin {
	@Unique private @Nullable MarsCelestials redplanet$mars;
	@Unique private @Nullable MarsSkyData redplanet$frame;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void redplanet$init(TextureManager textureManager, AtlasManager atlasManager, RenderTarget renderTarget, CallbackInfo ci) {
		this.redplanet$mars = new MarsCelestials(atlasManager.getAtlasOrThrow(AtlasIds.CELESTIALS));   // quad and star GpuBuffers, rebuilt on reload with vanilla
	}

	@Inject(method = "close", at = @At("TAIL"))
	private void redplanet$close(CallbackInfo ci) { if (this.redplanet$mars != null) this.redplanet$mars.close(); }

	@Inject(method = "render", at = @At("HEAD"))
	private void redplanet$frame(GpuBufferSlice skyFog, SkyRenderState state, CallbackInfo ci) {
		this.redplanet$frame = state.getData(MarsSkyData.KEY);
	}

	/** On Mars, draw our sun, Phobos, Deimos, Earth and catalogue stars inside vanilla's open "Sky" pass. */
	@Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
	private void redplanet$marsBodies(RenderPass renderPass, PoseStack poseStack, float sunAngle, float moonAngle, float starAngle,
			MoonPhase moonPhase, float rainBrightness, float starBrightness, CallbackInfo ci) {
		if (this.redplanet$frame != null && this.redplanet$mars != null) {
			this.redplanet$mars.draw(renderPass, this.redplanet$frame, rainBrightness, starBrightness);   // pattern of §5.6
			ci.cancel();
		}
	}
}
```

`MarsCelestials.draw` works like this:

- **Stars** (from `stars.bin`): model-view × `starRotation`, a POSITION_COLOR pipeline with OVERLAY blending, faded by
  `starBrightness`.
- **Sun:** CELESTIAL pipeline with a `redplanet:sun_mars` sprite placed in `textures/environment/celestial/`, plus a
  blue aureole quad drawn additively around it.
- **Phobos:** a translucent pipeline, a phase sprite chosen from `phobosLit`, hidden below the horizon.
- **Deimos and Earth:** additive points (Earth tinted blue-white) with a soft glow.

Angular size mapping, to record in SCIENCE.md. Vanilla draws the Earth sun's disc at 8.58°, 16× the real 0.533°
(§5.3). Using the same factor k ≈ 16 for every body keeps the true *ratios*:

| Body | Real | ×16 |
|---|---|---|
| Mars sun | 0.35° | ≈ 5.6° |
| Phobos | 0.2° | ≈ 3.2° |
| Deimos | ≈ 0.03° | ≈ 0.5° |
| Earth | point | a vanilla-star-sized point (0.2–0.3°) plus glow |

At true scale the sun would be about 5 px tall at 1080p with a 70° FOV.

**Ascent darkening and dust storms** use one `ClientLevel` mixin:

```java
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Inject(method = "addEnvironmentAttributeLayers", at = @At("RETURN"))
	private void redplanet$addLayers(EnvironmentAttributeSystem.Builder builder, CallbackInfoReturnable<EnvironmentAttributeSystem.Builder> cir) {
		RedPlanetSkyLayers.addTo(cir.getReturnValue(), (ClientLevel) (Object) this);
	}
}

public final class RedPlanetSkyLayers {
	private static final Vector3fc DUST_RGB = ARGB.vector3fFromRGB24(0xB07850);   // placeholder; real value goes in SCIENCE.md
	public static void addTo(EnvironmentAttributeSystem.Builder b, ClientLevel level) {
		// Altitude (camera Y): sky and fog toward black, stars out, horizon glow gone. Earth flight only.
		b.addPositionalLayer(EnvironmentAttributes.SKY_COLOR, (base, pos, biomes) -> ARGB.scaleRGB(base, Ascent.skyFactor(level, pos.y)));
		b.addPositionalLayer(EnvironmentAttributes.FOG_COLOR, (base, pos, biomes) -> ARGB.scaleRGB(base, Ascent.skyFactor(level, pos.y)));
		b.addPositionalLayer(EnvironmentAttributes.STAR_BRIGHTNESS, (base, pos, biomes) -> Math.max(base, Ascent.starBrightness(level, pos.y)));
		b.addPositionalLayer(EnvironmentAttributes.SUNRISE_SUNSET_COLOR, (base, pos, biomes) ->
			new Vector4f(base.x(), base.y(), base.z(), base.w() * Ascent.skyFactor(level, pos.y)));
		// Mars dust storm, intensity 0..1 (e.g. from the synced rain level): short fog, dusty sky
		b.addTimeBasedLayer(EnvironmentAttributes.FOG_START_DISTANCE, (base, tick) -> Mth.lerp(DustStorm.intensity(level), base, 0.0F));
		b.addTimeBasedLayer(EnvironmentAttributes.FOG_END_DISTANCE, (base, tick) -> Mth.lerp(DustStorm.intensity(level), base, 48.0F));
		b.addTimeBasedLayer(EnvironmentAttributes.SKY_FOG_END_DISTANCE, (base, tick) -> Mth.lerp(DustStorm.intensity(level), base, 24.0F));
		b.addTimeBasedLayer(EnvironmentAttributes.FOG_COLOR, (base, tick) -> ARGB.srgbLerp(DustStorm.intensity(level), base, DUST_RGB));
		b.addTimeBasedLayer(EnvironmentAttributes.SKY_COLOR, (base, tick) -> ARGB.srgbLerp(DustStorm.intensity(level), base, DUST_RGB));
	}
}
```

The lambdas run once per attribute per tick and must stay cheap. Positional layers receive the **camera** position,
which for a chase cam is close to the rocket's altitude.

### 11.7 Mars sound: lower volume and a low-pass filter

```java
@Mixin(Channel.class)
public interface ChannelAccessor { @Accessor("source") int redplanet$source(); }

@Mixin(Library.class)
abstract class LibraryMixin {
	@Inject(method = "init", at = @At("TAIL"))            // the context was just made current (~84)
	private void redplanet$efxInit(@Nullable String preferredDevice, DeviceList currentDevices, boolean useHrtf, CallbackInfo ci) { MarsAudio.createFilter(); }
	@Inject(method = "cleanup", at = @At("HEAD"))
	private void redplanet$efxCleanup(CallbackInfo ci) { MarsAudio.deleteFilter(); }
}

@Mixin(SoundEngine.class)
abstract class SoundEngineMixin {
	/** Wrap play()'s first channel set-up task so every new AL source gets, or loses, the Mars low-pass filter. */
	@WrapOperation(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/ChannelAccess$ChannelHandle;execute(Ljava/util/function/Consumer;)V", ordinal = 0))
	private void redplanet$filterNewSource(ChannelAccess.ChannelHandle handle, Consumer<Channel> setup, Operation<Void> original,
			@Local(argsOnly = true) SoundInstance instance) {
		SoundSource category = instance.getSource();
		original.call(handle, (Consumer<Channel>) channel -> {
			setup.accept(channel);
			MarsAudio.apply(((ChannelAccessor) channel).redplanet$source(), category);   // sound-executor thread
		});
	}
}

public final class MarsAudio {
	private static volatile boolean onMars;
	private static volatile int filter;                 // 0 = no EFX
	static void createFilter() {
		long device = ALC10.alcGetContextsDevice(ALC10.alcGetCurrentContext());
		if (!ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) { filter = 0; return; }
		int f = EXTEfx.alGenFilters();
		EXTEfx.alFilteri(f, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
		EXTEfx.alFilterf(f, EXTEfx.AL_LOWPASS_GAIN, 0.8F);      // values go in SCIENCE.md (Perseverance acoustics)
		EXTEfx.alFilterf(f, EXTEfx.AL_LOWPASS_GAINHF, 0.15F);
		filter = f;
	}
	static void deleteFilter() { int f = filter; filter = 0; if (f != 0) EXTEfx.alDeleteFilters(f); }
	static void apply(int alSource, SoundSource category) {
		boolean muffle = onMars && filter != 0 && category != SoundSource.UI && category != SoundSource.MUSIC && category != SoundSource.VOICE;
		AL10.alSourcei(alSource, EXTEfx.AL_DIRECT_FILTER, muffle ? filter : EXTEfx.AL_FILTER_NULL);
	}
	/** Call from ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE (every dimension change stops all sounds anyway). */
	public static void setOnMars(Minecraft mc, boolean mars) {
		onMars = mars;
		for (SoundSource s : SoundSource.values()) {
			if (s != SoundSource.MASTER && s != SoundSource.MUSIC && s != SoundSource.UI) {
				mc.getSoundManager().updateCategoryVolume(s, mars ? 0.45F : 1.0F);   // placeholder gain
			}
		}
	}
}
```

The volume part needs no mixin. An alternative is a MixinExtras `@ModifyReturnValue` on the private
`SoundEngine.calculateVolume(FLnet/minecraft/sounds/SoundSource;)F`. Sounds inside a pressurised habitat could later
skip the filter by checking the player's position in `apply`.

### 11.8 Keys and config

```java
public final class RedPlanetKeys {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("redplanet", "flight"));
	public static final KeyMapping SKIP_CINEMATIC = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.redplanet.skip_cinematic", InputConstants.Type.KEYBOARD, InputConstants.KEY_BACKSPACE, CATEGORY));
	public static final KeyMapping NEXT_CAMERA = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.redplanet.next_camera", InputConstants.Type.KEYBOARD, InputConstants.KEY_V, CATEGORY));
	public static final KeyMapping OPEN_CONFIG = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.redplanet.config", InputConstants.Type.KEYBOARD, InputConstants.KEY_O, CATEGORY));   // check vanilla conflicts
	static void init() {                                // from onInitializeClient, before Options exist
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (SKIP_CINEMATIC.consumeClick()) CinematicCamera.INSTANCE.skip();
			while (NEXT_CAMERA.consumeClick()) CinematicCamera.INSTANCE.nextShot();
			while (OPEN_CONFIG.consumeClick()) client.gui.setScreen(new RedPlanetConfigScreen(client.gui.screen()));
		});
	}
}
// lang: "key.category.redplanet.flight", "key.redplanet.skip_cinematic", …

public final class RedPlanetConfigScreen extends OptionsSubScreen {
	public RedPlanetConfigScreen(@Nullable Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("redplanet.config.title"));
	}
	@Override protected void addOptions() {
		this.list.addHeader(Component.translatable("redplanet.config.flight"));
		this.list.addBig(RedPlanetOptions.CINEMATIC_LENGTH);                          // short / standard / long
		this.list.addSmall(RedPlanetOptions.REDUCE_SHAKE, RedPlanetOptions.PHOTOSENSITIVE_SAFE);
		this.list.addSmall(RedPlanetOptions.TELEMETRY_HUD, RedPlanetOptions.MARS_SOUND_MUFFLING);
	}
	@Override public void removed() { super.removed(); RedPlanetOptions.save(); }   // our JSON; super saves vanilla options.txt
}

// RedPlanetOptions:
public static final OptionInstance<Boolean> REDUCE_SHAKE =
	OptionInstance.createBoolean("redplanet.option.reduce_shake", false, v -> CONFIG.reduceShake = v);
public static final OptionInstance<CinematicLength> CINEMATIC_LENGTH = new OptionInstance<>(
	"redplanet.option.cinematic_length", OptionInstance.noTooltip(),
	(caption, value) -> Options.genericValueLabel(caption, value.label()),
	new OptionInstance.Enum<>(List.of(CinematicLength.values()), CinematicLength.CODEC),
	CinematicLength.STANDARD, v -> CONFIG.cinematicLength = v);
```

### 11.9 Client init order

Do all of this in `onInitializeClient`. That runs before `Options` and before `GameRenderer`/`GuiRenderer`, as the two
"too late" checks above imply:

1. Key mappings.
2. PIP renderers, if used.
3. `RenderPipelines.register(...)` for our pipelines.
4. HUD registration.
5. Screen, extraction, tick and level-change events.

---

## 12. Risks and UNVERIFIED items

1. **Mixin targets are private methods:** `alignWithEntity`, `calculateFov`, `renderItemInHand`,
   `renderSunMoonAndStars`, `addEnvironmentAttributeLayers`, `startWaitingForNewLevel`, `Library.init` and
   `SoundEngine.play`. Names are stable now that the game is unobfuscated, but they can change in 26.4. Keep each
   mixin tiny and commented, with `require = 1`.
2. **Roll sign, latitude-tilt sign, quad winding.** All three were derived from reading code and are **UNVERIFIED**.
   Check them with client-gametest screenshots.
3. **Far cameras can't show terrain beyond the player's loaded chunks** (§1.6). Keep shots within about render
   distance − 2 chunks, or build shots that frame the sky. The async occlusion rebuild can pop for a frame after hard
   cuts.
4. **Camera-dependent systems:** the level-loading readiness check, the sound listener and environment attributes all
   follow the camera (§1.9). Disable the override during dimension loading.
5. **The interlude screen holds past readiness.** The server already considers the player loaded, so protect the
   passengers. The dimension switch stops all audio. Client-gametest "world loaded" waits include our screen.
6. **EFX availability on macOS arm64 is UNVERIFIED**; degrade to volume only. Don't create filters off the main or
   sound threads before `Library.init`.
7. **No custom shaders are proposed.** Every suggested pipeline reuses vanilla shaders (`core/gui`,
   `core/position_tex`, `core/position_tex_color`, `core/position_color`), which avoids GLSL-to-Vulkan risk. If custom
   shaders become necessary, copy the vanilla layout conventions and reverse-Z depth (§4.1, §5.3).
8. **Fabric HUD elements vanish with F1 and during loading screens**, despite the javadoc (§2.1).
9. **Fabric javadoc ordering is stale** for `COLLECT_SUBMITS` (it runs before the frame graph) and `END_MAIN` (it runs
   after clouds and weather) (§7). Rely on the code order.
10. **Reverse-Z and `GREATER_THAN_OR_EQUAL` depth** everywhere. Any copied 1.21-era depth or projection code will be
    wrong.
11. **`docs/API-NOTES-26.3.md`** should link to this file (not edited by this research task).
