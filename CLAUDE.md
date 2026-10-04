# Red Planet: Starship to Mars — project notes

A Fabric mod for Minecraft Java 26.3. It adds a scientifically accurate Mars dimension, reached by riding a SpaceX
Starship instead of a portal; the return trip is the same flight mirrored. The full brief, including the user's
original request, is in [docs/HANDOFF.md](docs/HANDOFF.md).

## Toolchain (verified 2026-10-04)
- Minecraft 26.3, the latest release. It is unobfuscated, so it uses Mojang names and needs no mappings.
- Java 25. Gradle wrapper 9.7.1.
- Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, Loom plugin `net.fabricmc.fabric-loom` 1.18.x.
- Dependencies use `implementation`, not `modImplementation`.
- Template: FabricMC/fabric-example-mod, branch `26.3`.

## Rules
- Don't write 26.x code from memory. Read the decompiled sources (`./gradlew genSources`) and the vanilla data first,
  and keep `docs/API-NOTES-26.3.md` current.
- Prefer Fabric API hooks over mixins. Keep mixins small and commented.
- Every scientific number lives in `docs/SCIENCE.md` with a source and its gameplay mapping. Document deliberate
  compromises.
- Verify with `./gradlew build`, server gametests, and client gametests that capture screenshots under Xvfb. Look at
  the screenshots.
- The user prefers thorough, full-featured work: don't keep things lean.

## Target machine
The user plays on an Apple M2 Mac with 8 GB RAM and macOS 27, using the official launcher with 26.3. Fabric is not
installed there yet, and there is no system JDK. The launcher's bundled Java 25 is at
`~/Library/Application Support/minecraft/runtime/java-runtime-epsilon/mac-os-arm64/java-runtime-epsilon/jre.bundle/Contents/Home`.
