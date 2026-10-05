# The launch trailer

A 1080p, 30 fps trailer filmed inside the game and cut together by script, so it can be re-rendered whenever the mod
changes. Nothing in it is sampled or downloaded except two open-licence fonts for the titles (OFL, Google Fonts),
which are fetched into `build/trailer/fonts` on first use.

## Pipeline

1. **Film the shots.** `TrailerClientGameTest` (in `src/gametest`) builds each scene in a client gametest world and
   films it with `trailer/Recorder`:

   ```sh
   JAVA_HOME=/path/to/jdk-25 scripts/run-client-gametests.sh -PclientTests=trailer            # every shot
   JAVA_HOME=/path/to/jdk-25 scripts/run-client-gametests.sh -PclientTests=trailer -PtrailerShots=homeward,mars_sunset
   ```

   Each shot becomes `build/trailer/shots/<name>.mp4` (H.264, CRF 12, an intermediate for the edit). The world is
   frozen (`/tick freeze`) and stepped one tick at a time; each frame is rendered at its exact moment inside the tick
   (the partial tick), at 1920x1080, and piped straight to ffmpeg. The cinematic flight camera and the transfer screen,
   which animate by the wall clock in the game, run on `TrailerClock` (game time) while filming. Free camera moves use
   `TrailerCamera` and `CameraPath` (keyframed position and look-at splines, roll and field of view). Under software
   rendering a frame takes a second or more, so the whole set takes about an hour and a half; it never runs with the
   ordinary client tests.

   The shots named `voyage` are one continuous flight (they can only be filmed together): the stack at dusk, mission
   control, ignition, liftoff, the climb, hot staging, the transfer screens, entry, the belly flop and landing on Mars,
   a suit, a habitat and a lava tube. Its big moments
   are filmed as several takes at once (`Recorder.Take`): each frame of the frozen world is rendered once per camera,
   for example the liftoff from far off on the land side, from low beside the pad and from the east against the sunset,
   and the flight's own cameras (with the telemetry overlay) next to the trailer's chase and side cameras.

   The way home is its own shot, `homeward`: a ship standing at InSight's landing site at sunset lifts off against
   Mars' blue sunset glow, enters over Earth and lands at dawn beside the beach pad, so it can be filmed without the
   whole voyage first.

   The GUI keeps one layout while filming (480x270 GUI pixels: GUI scale 4 at 1080p), so screens and the HUD look as
   they do for a player at 1080p.

2. **Score.** `python3 tools/trailer/score.py` synthesizes the music (`build/trailer/score.wav`) from the cue sheet in
   `edit.py`: sub drops, braams and hits on the cuts, a 100 BPM ostinato, a quiet space section, a Mars theme and a
   climax. Re-run it after changing the edit.

3. **Assemble.** `python3 tools/trailer/assemble.py` reads `edit.py`, the shots, the score and the mod's own sound
   effects, composites every frame (camera shake, flashes, fades, captions, a 2.39:1 letterbox, a light grade, grain),
   mixes the audio (the music ducks under the rockets) and writes `build/trailer/red-planet-trailer-1080p.mp4`
   (H.264 High, CRF 16, AAC 320 kb/s). `--preview` makes a quick 540p version; missing shots show a labelled
   placeholder, so the edit can be reviewed before everything is filmed.

## Structure

- `edit.py`: the timeline. Shots (clip, in-point, duration, shake, flash, fades), on-screen text, sound effects and
  music cues, all in seconds.
- `titles.py`: the title card, the logo card, the end card (with the required notices: not an official Minecraft
  product, not approved by or associated with Mojang or Microsoft, not affiliated with SpaceX) and the text overlays.
- `score.py`, `assemble.py`: as above.

## The cut (78 s)

- **Hook (0-3 s):** the stack silhouetted against the sunset, the Raptors lighting and the stack clearing the smoke,
  the ship burning in space above the atmosphere, the ship standing on Mars, under "THIS IS MINECRAFT."
- **Title**, then **Earth:** the stack at dusk, mission control (the cursor picks a landing site), ignition, liftoff
  from three angles, the climb and hot staging with the webcast-style telemetry, the booster tumbling past as the
  ship flies on.
- **To Mars:** refuelling in orbit, Mars growing in the window six months later, entry plasma, the landing.
- **Mars:** NASA-elevation terrain (Gale, Olympus Mons, Valles Marineris), a dust devil and a storm, the suit's
  oxygen readout, a pressurized habitat, a lit lava tube, a Starship at sunset.
- **Home:** liftoff from Mars, entry over Earth, touchdown at dawn; the logo card and the end card with the notices.

Per-shot tools in `edit.py`: `clip_in` and `speed` (slow motion blends neighbouring frames), `zoom` and `anchor` (a
digital push-in), `frame_y` (moves a picture inside the letterbox, for shots whose subject or readout would sit
under the bars), `lift` (opens up a dark shot), `shake` and `flash`. Captions come in three placements: `caption`
(low), `band` (low on a dark band, for the game's screens) and `top`.
