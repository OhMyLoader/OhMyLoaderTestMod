# OhMyLoader Test Mod

This repository is an end-to-end verification mod. It applies `org.ohmyloader.gradle` as an external mod
project and exercises the full loader consumption path. If this project builds and runs, an external mod
project can too; release-surface defects appear here instead of on a user's release day.

## Build

```bash
# In the OhMyLoader repository:
./gradlew publishToMavenLocal
# If the plugin changed, in the OhMyLoaderGradle repository:
./gradlew publishToMavenLocal
# Back in this repository:
./gradlew build
```

## Run

```bash
./gradlew runClient
./gradlew runServer
```

## E2E gates and play sessions

The jar contains three mods:

- `oml_testmod`: the end-to-end probe set. It runs only in E2E mode (`-Doml.e2e=1`, enabled by
  `-Poml.e2e.ticks=N`). It kills players, moves blocks, and changes game rules, so a normal run must stay
  inert; otherwise the observed world is the probe's world, not the mod's world.
- `techmod`: the API sample mod. It builds a copper/tin material chain and a press in the style of a real mod
  to expose gaps in `oml-api`; see [README-TECHMOD.md](README-TECHMOD.md).
- `oml_playmod` (Playground Kit): a playable mod with `/kit`, a gallery dimension, launch pads, waystones,
  recall pearls, and a key panel. It declares no assertions; its purpose is to use the API the way a mod
  author would.

```bash
./scripts/e2e-server.sh          # dedicated-server gate: RESULT PASS/FAIL + exit code
DISPLAY=:0 ./scripts/e2e-client.sh   # client gate; needs a display, CI uses xvfb-run
./scripts/e2e-multiplayer.sh     # dedicated server + remote client; uses xvfb-run internally
PLAY_SESSION_SECONDS=120 ./scripts/play-session.sh playtest   # real play session
```

Each gate writes `build/e2e-context-<side>.json` and `build/e2e-result-<side>.json`. The context records repo
SHAs, effective versions, artifact SHA-256 values, seed, environment, and CI run identity; the result records
the parsed verdict and exit code.

`play-session.sh` is not a gate. It copies the save produced by the gate, then uses xdotool to type and move
like a player (chat, walking, the mod's keys, F2 screenshots). Its output is the transcript in
`build/play-client.log` and the screenshots in `run/client/screenshots/`. The script never presses Escape as a
safety measure: in singleplayer that pauses the game, and a paused world is the one that swallows input. It
handles the death screen by clicking Respawn once in the center of the window.

## Snapshot tracking

```kotlin
oml {
    minecraftVersion.set("snapshot")   // resolved to latest.snapshot at runtime; no version bump needed
}
```

## API stability

The mod-author compatibility contract for `oml-api` (stability layers, version policy, deprecation) is in
[oml-api/README.md](https://github.com/OhMyLoader/OhMyLoader/blob/main/oml-api/README.md) in the loader
repository.
