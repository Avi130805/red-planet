#!/usr/bin/env bash
# Runs the client gametests (screenshots of the Mars sky, the flight, the landing) under a virtual display with GLX,
# using Mesa's software OpenGL (llvmpipe). Screenshots land in build/gametest-screenshots/.
#
#   JAVA_HOME=/path/to/jdk-25 scripts/run-client-gametests.sh
set -euo pipefail
cd "$(dirname "$0")/.."
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
export SDL_VIDEO_DRIVER=x11
exec xvfb-run -a -s "-screen 0 1920x1080x24 +extension GLX +render -noreset" ./gradlew runClientGameTest "$@"
