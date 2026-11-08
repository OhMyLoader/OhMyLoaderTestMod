# Contributing to OhMyLoaderTestMod

This repository is the end-to-end verification mod for OhMyLoader. It applies `org.ohmyloader.gradle` as
an external mod project and contains three mods in one jar: `oml_testmod` (E2E probes), `techmod` (API
sample), and `oml_playmod` (playable sample). The repository is a test consumer, not a distributable mod
release.

## Before you start

- Open an issue before changing the E2E verdict protocol, the probe set, or the CI gate behavior.
- Keep the three repositories independent. Consume the loader and plugin through Maven coordinates; do not
  add relative source paths, shared catalogs, `includeBuild`, or source mounts.
- Read the loader README for the runtime contract and the API stability policy before adding a probe.

## Prerequisites

- JDK 27. CI uses the Zulu distribution.
- The Gradle wrapper in this repository (Gradle 9.9.0).
- Zig 0.17.0 when publishing the loader from a sibling checkout.
- A sibling `OhMyLoader` and `OhMyLoaderGradle` checkout.
- Linux with Xvfb, Mesa, and OpenAL for the CI-shaped E2E gates. A desktop display is enough for
  `runClient`; the scripts use `xvfb-run` for the client gates.

## Build and run

```bash
(cd ../OhMyLoader && ./gradlew publishToMavenLocal)
(cd ../OhMyLoaderGradle && ./gradlew publishToMavenLocal)
./gradlew build
./gradlew runClient
./gradlew runServer
```

`./gradlew build` runs the test-mod unit tests, including `E2EVerdictTest`, which proves the verdict
protocol without a game. The E2E probes run only when `-Poml.e2e.ticks=N` is set; a normal game run must
remain inert and must not modify the player's world.

## E2E gates

```bash
./scripts/e2e-server.sh
DISPLAY=:0 ./scripts/e2e-client.sh
./scripts/e2e-multiplayer.sh
PLAY_SESSION_SECONDS=120 ./scripts/play-session.sh playtest
```

The server, client, and multiplayer scripts are gates: they exit nonzero without a `RESULT PASS` verdict.
The multiplayer script starts a dedicated server with `online-mode=false`, joins it from a remote client,
and requires both verdicts. `play-session.sh` is not a gate; it copies the gate save and uses xdotool to
type and move like a player, producing a transcript and screenshots.

Every gate writes `build/e2e-context-<side>.json` and `build/e2e-result-<side>.json`. The context records
repository SHAs, effective versions, artifact hashes, seed, environment, and CI identity; the result records
the parsed verdict. Attach both files and the relevant logs when reporting a failure. CI runs the gates on
Linux; do not treat a Windows client launch as a substitute for the CI gate.

## Tests and probes

- Add a probe with both an observation and an expectation. A check that is only printed is not a gate.
- Keep the client and server expectation sets separate; do not expect an integrated-server-only check on a
  remote client.
- Keep the gate's self-tests working: a planted expectation that is never reported must produce a FAIL.
- Do not weaken timeouts, grace windows, the verdict exit code, or the requirement that a real run leaves
  a verdict file just to make CI green.
- E2E probes may mutate the world and player. Guard them behind E2E mode and clean up owned state.
- Add a unit case for verdict or protocol logic that can be tested without the game.

## Version pins

`gradle.properties` pins `oml_version` and the default `oml.gameVersion` (`26.3`). Keep the version in
step with the sibling loader and plugin publications. The CI workflow accepts `loader_ref`, `plugin_ref`,
`testmod_ref`, and `game_version` inputs for a dispatched run; use them when validating a specific tree.
This repository has no release tag and publishes no Maven artifact.

## Commit and pull request

- Branch from `main`; do not commit to the upstream `main` directly.
- Use Conventional Commits, for example `test(e2e): ...` or `fix(testmod): ...`. One concern per commit.
  The body states the constraint and the evidence, not the investigation.
- Run `./gradlew build` and the relevant E2E gate; include the commands and verdicts in the pull request.
- Do not commit `build/`, `run/`, screenshots, local paths, or generated evidence files.
- Push your branch to your fork and open a pull request. Do not push tags or release branches.

## Security

Do not report a vulnerability in a public issue. Use GitHub's private vulnerability reporting for this
repository; include the affected version or commit, reproduction steps, and impact. If private reporting
is unavailable, contact the maintainer through GitHub before public disclosure.

## License

The project is licensed under AGPL-3.0. By submitting a contribution, you confirm that you have the right
to license it under AGPL-3.0. No CLA or `Signed-off-by` is required. Do not submit code with an
incompatible license.
