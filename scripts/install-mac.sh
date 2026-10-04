#!/bin/bash
# Installs "Red Planet: Starship to Mars" into the official Minecraft launcher on macOS (Apple silicon or Intel):
#
#   1. Fabric Loader 0.19.5 for Minecraft 26.3, with the official Fabric installer, run on the Java 25 that the
#      Minecraft launcher already ships (no separate Java install needed);
#   2. Fabric API 0.161.0+26.3 into the mods folder;
#   3. the Red Planet jar from this repository's dist/ folder (or one you pass with --jar).
#
# Safe to re-run: it replaces older Red Planet and Fabric API jars (moving them aside, never deleting) and never
# touches your worlds. Quit the Minecraft launcher before running it.
#
#   scripts/install-mac.sh               install or update everything
#   scripts/install-mac.sh --skip-loader only refresh the mod jars (Fabric Loader already installed)
#   scripts/install-mac.sh --jar FILE    install FILE instead of dist/redplanet-*.jar
#   scripts/install-mac.sh --uninstall   move the Red Planet jar out of the mods folder
#
# Environment overrides: MC_DIR (Minecraft folder), JAVA (a java binary, version 25 or newer).
#
# Bash 3.2 compatible (macOS's /bin/bash).
set -euo pipefail

MC_VERSION="26.3"
LOADER_VERSION="0.19.5"
FABRIC_API_VERSION="0.161.0+26.3"
FABRIC_API_URL="https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${FABRIC_API_VERSION}/fabric-api-${FABRIC_API_VERSION}.jar"
INSTALLER_META="https://meta.fabricmc.net/v2/versions/installer"
PROFILE_NAME="fabric-loader-${MC_VERSION}"

MC_DIR="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
MOD_JAR=""
SKIP_LOADER=0
UNINSTALL=0

say() { printf '\033[1;31m==>\033[0m %s\n' "$*"; }
note() { printf '    %s\n' "$*"; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
	sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
	case "$1" in
		--jar) [ $# -ge 2 ] || die "--jar needs a file"; MOD_JAR="$2"; shift 2 ;;
		--skip-loader) SKIP_LOADER=1; shift ;;
		--uninstall) UNINSTALL=1; shift ;;
		-h|--help) usage; exit 0 ;;
		*) usage; die "unknown option: $1" ;;
	esac
done

[ "$(uname -s)" = "Darwin" ] || die "this installer is for macOS (the mod itself runs anywhere Fabric does)"
[ -d "$MC_DIR" ] || die "no Minecraft folder at '$MC_DIR'. Install the official Minecraft launcher, log in, play $MC_VERSION once, then re-run."

MODS_DIR="$MC_DIR/mods"
STAMP="$(date +%Y%m%d-%H%M%S)"
ASIDE_DIR="$MODS_DIR/.replaced-by-redplanet-installer/$STAMP"

# Moves files matching a glob out of the mods folder (kept, so nothing is ever lost).
move_aside() {
	local f moved=0
	for f in "$MODS_DIR"/$1; do
		[ -e "$f" ] || continue
		if [ -n "${2:-}" ] && [ "$(basename "$f")" = "$2" ]; then
			continue
		fi
		mkdir -p "$ASIDE_DIR"
		mv "$f" "$ASIDE_DIR/"
		note "moved $(basename "$f") to mods/.replaced-by-redplanet-installer/$STAMP/"
		moved=1
	done
	return 0
}

if [ "$UNINSTALL" -eq 1 ]; then
	say "Removing Red Planet from $MODS_DIR"
	move_aside 'redplanet-*.jar'
	note "Fabric Loader and Fabric API stay installed; your worlds are untouched."
	exit 0
fi

# ------------------------------------------------------------------------------------------------- Java 25
java_major() {
	"$1" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2}' | cut -d. -f1
}

find_java() {
	local candidate major
	if [ -n "${JAVA:-}" ]; then
		echo "$JAVA"
		return 0
	fi
	# The launcher's bundled runtimes: java-runtime-epsilon is the Java 25 that Minecraft 26.x uses.
	for candidate in \
		"$MC_DIR/runtime/java-runtime-epsilon/mac-os-arm64/java-runtime-epsilon/jre.bundle/Contents/Home/bin/java" \
		"$MC_DIR/runtime/java-runtime-epsilon/mac-os/java-runtime-epsilon/jre.bundle/Contents/Home/bin/java" \
		"$MC_DIR"/runtime/*/mac-os*/*/jre.bundle/Contents/Home/bin/java; do
		[ -x "$candidate" ] || continue
		major="$(java_major "$candidate" || true)"
		if [ -n "$major" ] && [ "$major" -ge 25 ] 2>/dev/null; then
			echo "$candidate"
			return 0
		fi
	done
	if command -v java >/dev/null 2>&1; then
		major="$(java_major java || true)"
		if [ -n "$major" ] && [ "$major" -ge 25 ] 2>/dev/null; then
			command -v java
			return 0
		fi
	fi
	return 1
}

download() {
	# download URL FILE
	curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 --progress-bar -o "$2" "$1" || die "download failed: $1"
}

# --------------------------------------------------------------------------------------------- the mod jar
if [ -z "$MOD_JAR" ]; then
	# The newest jar in dist/ (normally exactly one).
	MOD_JAR="$(ls -t "$REPO_DIR"/dist/redplanet-*.jar 2>/dev/null | grep -v -- '-sources\.jar$' | awk 'NR == 1' || true)"
	[ -n "$MOD_JAR" ] || die "no mod jar in $REPO_DIR/dist/. Pull the repository again, or pass one with --jar."
fi
[ -f "$MOD_JAR" ] || die "mod jar not found: $MOD_JAR"
unzip -tq "$MOD_JAR" >/dev/null 2>&1 || die "$MOD_JAR is not a valid jar (an incomplete download or a Git LFS pointer?)"

say "Red Planet installer"
note "Minecraft folder: $MC_DIR"
note "Mod jar:          $MOD_JAR"

# ------------------------------------------------------------------------------------------ Fabric Loader
if [ "$SKIP_LOADER" -eq 0 ]; then
	[ -f "$MC_DIR/launcher_profiles.json" ] || die "no launcher_profiles.json in '$MC_DIR'. Open the Minecraft launcher once, log in, then quit it and re-run."
	[ -d "$MC_DIR/versions/$MC_VERSION" ] || die "Minecraft $MC_VERSION has not been downloaded yet. In the launcher, play $MC_VERSION once (it downloads the game and its Java), quit, then re-run."
	if pgrep -f "Minecraft.app/Contents/MacOS" >/dev/null 2>&1; then
		die "the Minecraft launcher is running. Quit it (Cmd+Q), then re-run: it rewrites its profile list on exit and would drop the Fabric profile."
	fi

	JAVA_BIN="$(find_java)" || die "no Java 25 found. Play Minecraft $MC_VERSION once from the launcher (it downloads its own Java 25), or set JAVA=/path/to/java."
	note "Java:             $JAVA_BIN ($(java_major "$JAVA_BIN"))"

	TMP_DIR="$(mktemp -d)"
	trap 'rm -rf "$TMP_DIR"' EXIT
	say "Finding the latest Fabric installer"
	# (No "head" in these pipes: with pipefail, closing a pipe early kills the script with SIGPIPE.)
	INSTALLER_JSON="$(curl -fsSL --retry 3 "$INSTALLER_META")" || die "could not read $INSTALLER_META (are you online?)"
	INSTALLER_URL="$(printf '%s\n' "$INSTALLER_JSON" | grep -o '"url"[[:space:]]*:[[:space:]]*"[^"]*"' | awk 'NR == 1' \
		| sed 's/.*"\(https[^"]*\)"$/\1/')"
	[ -n "$INSTALLER_URL" ] || die "no installer listed at $INSTALLER_META"
	note "$INSTALLER_URL"
	download "$INSTALLER_URL" "$TMP_DIR/fabric-installer.jar"

	say "Installing Fabric Loader $LOADER_VERSION for Minecraft $MC_VERSION"
	"$JAVA_BIN" -jar "$TMP_DIR/fabric-installer.jar" client -dir "$MC_DIR" -mcversion "$MC_VERSION" -loader "$LOADER_VERSION" \
		|| die "the Fabric installer failed (see its output above)"
	[ -d "$MC_DIR/versions/fabric-loader-$LOADER_VERSION-$MC_VERSION" ] \
		|| die "Fabric Loader did not appear in '$MC_DIR/versions'"
fi

# ------------------------------------------------------------------------------------------------- mods
mkdir -p "$MODS_DIR"

say "Installing Fabric API $FABRIC_API_VERSION"
FABRIC_API_FILE="fabric-api-$FABRIC_API_VERSION.jar"
move_aside 'fabric-api-*.jar' "$FABRIC_API_FILE"
if [ -f "$MODS_DIR/$FABRIC_API_FILE" ] && unzip -tq "$MODS_DIR/$FABRIC_API_FILE" >/dev/null 2>&1; then
	note "already present"
else
	download "$FABRIC_API_URL" "$MODS_DIR/$FABRIC_API_FILE.part"
	unzip -tq "$MODS_DIR/$FABRIC_API_FILE.part" >/dev/null 2>&1 || die "the Fabric API download is corrupt; re-run"
	mv "$MODS_DIR/$FABRIC_API_FILE.part" "$MODS_DIR/$FABRIC_API_FILE"
fi

say "Installing Red Planet"
move_aside 'redplanet-*.jar'
cp "$MOD_JAR" "$MODS_DIR/"
note "copied $(basename "$MOD_JAR")"

say "Done. Mods folder now holds:"
ls -1 "$MODS_DIR" | grep -i '\.jar$' | sed 's/^/    /'
cat <<EOF

Next steps
  1. Open the Minecraft launcher. In "Installations" pick "$PROFILE_NAME" (Fabric Loader $LOADER_VERSION, $MC_VERSION).
     Optional, recommended on 8 GB Macs: Edit > More options > JVM arguments, change -Xmx2G to -Xmx4G.
  2. Play, then create a new world. Creative with cheats on is the easiest way to explore this build.
  3. In the world:  /redplanet tp mars          lands you at Curiosity's site in Gale crater
                    /redplanet locate olympus_mons, then click the result to fly there
                    /redplanet info             position, local time, season, temperature, air, gravity
                    /redplanet tp earth         back home
  Survival on Mars: there is no spacesuit yet, so unprotected you black out and die in about two minutes.
  Set "hypoxiaDamage": false in "$MC_DIR/config/redplanet-server.json" (created on first launch) to explore anyway.

Update later: pull the repository again and re-run this script.
EOF
