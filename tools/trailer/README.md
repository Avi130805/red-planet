# The launch trailer

A 1080p, 30 fps trailer filmed inside the game and cut together by script, so it can be re-rendered whenever the mod
changes. Nothing in it is sampled or downloaded except two open-licence fonts for the titles (OFL, Google Fonts),
which are fetched into `build/trailer/fonts` on first use.

## Pipeline

1. **Film the shots.** `TrailerClientGameTest` (in `src/gametest`) builds each scene in a client gametest world and
   films it with `trailer/Recorder`:

   ```sh
   JAVA_HOME=/path/to/jdk-25 scripts/run-client-gametests.sh -PclientTests=trailer            # every shot
   JAVA_HOME=/path/to/jdk-25 scripts/run-client-gametests.sh -PclientTests=trailer -PtrailerShots=hook_catch,mars_sunset
   ```

   Each shot becomes `build/trailer/shots/<name>.mp4` (H.264, CRF 12, an intermediate for the edit). The world is
   frozen (`/tick freeze`) and stepped one tick at a time; each frame is rendered at its exact moment inside the tick
   (the partial tick), at 1920x1080, and piped straight to ffmpeg. The cinematic flight camera and the transfer screen,
   which animate by the wall clock in the game, run on `TrailerClock` (game time) while filming. Free camera moves use
   `TrailerCamera` and `CameraPath` (keyframed position and look-at splines, roll and field of view). Under software
   rendering a frame takes a few seconds, so the whole set takes hours; it never runs with the ordinary client tests.

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

The first three seconds are the hook: the Raptors lighting at night, the stack clearing the tower, and the booster
caught by the chopsticks under "THIS IS MINECRAFT."
