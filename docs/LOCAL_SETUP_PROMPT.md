# Prompt for a local AI assistant: install Red Planet on this Mac

Paste everything inside the box into a local AI assistant that can run terminal commands on the Mac. It downloads
the mod from GitHub, installs Fabric and the mod, and tells you how to start playing. Run it again whenever you want
the newest build.

````text
You are setting up a Minecraft mod on this Mac. Work step by step in the Terminal, show me each command before you
run it, and stop and ask me whenever a step needs my input (a password, a browser login, a click) or something
fails in a way these instructions don't cover.

## What we're installing
"Red Planet: Starship to Mars", a Fabric mod for Minecraft Java 26.3 that adds a scientifically grounded Mars
dimension. Source: the PRIVATE GitHub repository Avi130805/Minecraft_Mods, branch claude/busy-carson-9dpa6w.
The repository has a ready-built mod jar in dist/ and an installer script, scripts/install-mac.sh, which:
- installs Fabric Loader 0.19.5 for Minecraft 26.3 with the official Fabric installer, running it on the Java 25
  that the Minecraft launcher already ships;
- downloads Fabric API 0.161.0+26.3 into the mods folder;
- copies the mod jar into the mods folder.
It moves older copies aside instead of deleting them, and never touches saved worlds.

## This Mac
- macOS on Apple silicon (M2), 8 GB RAM. The official Minecraft launcher is installed and has Minecraft 26.3.
- There is NO system Java, and you must not install one: the launcher's Java 25 lives at
  "$HOME/Library/Application Support/minecraft/runtime/java-runtime-epsilon/mac-os-arm64/java-runtime-epsilon/jre.bundle/Contents/Home/bin/java".
  The installer script finds it by itself.
- The Minecraft folder is "$HOME/Library/Application Support/minecraft" (note the space; always quote paths).

## Rules
- Never use sudo. Never delete anything inside the Minecraft folder, especially "saves". The script moves old mod
  jars into mods/.replaced-by-redplanet-installer/.
- Never print, echo, log or store a GitHub token in a file, a URL, a git remote or the shell history. Read secrets
  with `read -rs` or let the git/gh credential prompt handle them.
- Don't install Homebrew, Java or other software without asking me first.

## Step 1: Check the prerequisites
1. Check that this exists:
   ls "$HOME/Library/Application Support/minecraft/versions/26.3"
   If it doesn't, tell me to open the Minecraft launcher, play version 26.3 once until the title screen, quit the
   game, and tell you when that's done.
2. Check that the launcher's Java exists:
   ls "$HOME/Library/Application Support/minecraft/runtime/java-runtime-epsilon"
   If it doesn't, ask me to play 26.3 once as in 1.
3. Ask me to QUIT the Minecraft launcher completely (Cmd+Q) before continuing. It rewrites its profile list when
   it closes.

## Step 2: Get the repository (it's private, so you need my GitHub login)
Put it in ~/RedPlanet. If ~/RedPlanet already exists and is a git checkout, just update it:
   git -C ~/RedPlanet pull --ff-only
Otherwise, use the first of these options that works:

A. GitHub CLI, if `gh --version` works:
   gh auth status || gh auth login --hostname github.com --git-protocol https --web
   (I'll finish the login in the browser.) Then:
   gh repo clone Avi130805/Minecraft_Mods ~/RedPlanet -- --branch claude/busy-carson-9dpa6w --single-branch

B. Plain git, if `git --version` works. On a fresh Mac this first command may open a dialog offering to install
   the Command Line Tools; if it does, ask me to click Install and wait until it's done.
   Ask me to create a fine-grained personal access token at https://github.com/settings/personal-access-tokens/new
   with access to only the repository Avi130805/Minecraft_Mods and the permission "Contents: Read-only". Then run:
   git clone --branch claude/busy-carson-9dpa6w --single-branch https://github.com/Avi130805/Minecraft_Mods.git ~/RedPlanet
   When git asks, the username is my GitHub username and the password is the token. I type it myself; macOS
   Keychain remembers it, so later pulls won't ask again.

C. No git at all: download a snapshot with the token from B instead (later updates mean repeating this):
   read -rs GH_TOKEN; export GH_TOKEN
   curl -fL -H "Authorization: Bearer $GH_TOKEN" -o /tmp/redplanet.zip \
     "https://api.github.com/repos/Avi130805/Minecraft_Mods/zipball/claude/busy-carson-9dpa6w"
   unset GH_TOKEN
   mkdir -p ~/RedPlanet && cd /tmp && rm -rf redplanet-unzip && mkdir redplanet-unzip && cd redplanet-unzip \
     && unzip -q /tmp/redplanet.zip && rsync -a */ ~/RedPlanet/

Check the result: ~/RedPlanet/dist/ must contain a file named redplanet-<version>.jar (several MB), and
~/RedPlanet/scripts/install-mac.sh must exist.

## Step 3: Install
With the Minecraft launcher closed, run:
   bash ~/RedPlanet/scripts/install-mac.sh
Read its output. It must end with "Done" and list fabric-api-0.161.0+26.3.jar and redplanet-<version>.jar in the
mods folder. If it stops with an error, the message says what to do (most often: quit the launcher, or play 26.3
once first). Fix that and run it again. It's safe to re-run.

## Step 4: Give the game more memory (recommended on 8 GB)
Ask me to open the launcher and go to Installations > "fabric-loader-26.3" > Edit > More options, and change
"-Xmx2G" in JVM arguments to "-Xmx4G". Then Save.

## Step 5: Tell me how to play
Explain this to me:
- In the launcher, pick the "fabric-loader-26.3" installation and press Play. On the title screen, "Mods" should
  list Red Planet and Fabric API.
- Create a NEW world: Game Mode Creative, and "Allow Commands" ON. (Survival works too, but there's no spacesuit
  yet, so the thin Mars air knocks you out in about two minutes. To explore in survival anyway, set
  "hypoxiaDamage": false in "$HOME/Library/Application Support/minecraft/config/redplanet-server.json"; that file
  appears after the first launch. Then restart the game.)
- Commands to try in the world:
  /redplanet tp mars                  land at Curiosity's site in Gale crater
  /redplanet tp mars 18.65 226.2      Olympus Mons (any latitude N and longitude E works)
  /redplanet locate valles_marineris  find a landmark (Tab completes the names), then click the answer to fly there
  /redplanet info                     position, local time, season, temperature, air, gravity
  /redplanet weather dust global      a planet-wide dust storm (clear ends it)
  /time set 12330                     sunset on Mars (run it while on Mars): watch the blue sunset in the west
  /redplanet tp earth                 back home
- The Starship flight, suits, habitats, creatures and structures aren't built yet. Each update adds more.

## Step 6: If the game doesn't start
Look at "$HOME/Library/Application Support/minecraft/logs/latest.log" and any file in
"$HOME/Library/Application Support/minecraft/crash-reports/". Show me the lines that mention "redplanet",
"fabric", "Exception" or "Caused by", and explain them. Common causes:
- "requires fabric-api" or a missing mod: re-run the installer.
- Out of memory: do Step 4.
- Another mod in the mods folder conflicts: ask me before moving it into mods/disabled/.

## Updating later
Pull the repository (Step 2's update command, or option C again), quit the launcher, and run
bash ~/RedPlanet/scripts/install-mac.sh again.

## Uninstalling
bash ~/RedPlanet/scripts/install-mac.sh --uninstall removes the mod jar. Fabric stays, and plain 26.3 is unaffected.
````
