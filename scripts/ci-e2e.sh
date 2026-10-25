#!/usr/bin/env bash
# Release-gate entry point. Expects the loader and plugin as sibling checkouts, exactly as the CI
# layout in .github/workflows/ci.yml produces them, and runs the real server and client against the
# artifacts under test. By default it publishes the sibling loader; OML_E2E_USE_STAGED_LOADER=1
# makes it consume the build job's staging directory instead, so the gate cannot test a different
# rebuild. OML_GATE_VERSION (for example v0.1.0) overrides the SNAPSHOT coordinate; OML_GAME_VERSION
# selects the game line.
set -euo pipefail
cd "$(dirname "$0")/.." || exit

GAME_VERSION="${OML_GAME_VERSION:-26.3}"
GATE_VERSION="${OML_GATE_VERSION:-}"
GATE_VERSION="${GATE_VERSION#v}"
export OML_GAME_VERSION="$GAME_VERSION"
[ -n "$GATE_VERSION" ] && export OML_E2E_OML_VERSION="$GATE_VERSION"

GRADLE_ARGS=(-Poml.gameVersion="$GAME_VERSION")
[ -n "$GATE_VERSION" ] && GRADLE_ARGS+=(-Poml_version="$GATE_VERSION")

echo "ci-e2e: game=$GAME_VERSION version=${GATE_VERSION:-from gradle.properties}"
./scripts/check-oml-version.sh

# The tag gate downloads the build job's staging directory; rebuilding the loader here would test a
# different set of bytes. The release job compares its own rebuild against this same staging manifest.
if [ "${OML_E2E_USE_STAGED_LOADER:-}" = "1" ]; then
  staged="$HOME/.m2/repository/org/ohmyloader/oml-core/${GATE_VERSION:?staged loader needs a gate version}"
  if [ ! -d "$staged" ]; then
    echo "ci-e2e: staged loader $GATE_VERSION is not in mavenLocal: $staged" >&2
    exit 1
  fi
  echo "ci-e2e: using staged loader artifacts at $GATE_VERSION"
else
  ./gradlew -p OhMyLoader "${GRADLE_ARGS[@]}" publishToMavenLocal
fi
./gradlew -p OhMyLoaderGradle "${GRADLE_ARGS[@]}" publishToMavenLocal
./gradlew "${GRADLE_ARGS[@]}" build

./scripts/e2e-server.sh

# The server gate already paid for a generated world; give it to the client instead of making the
# client script run a second full server boot. The client's own world-generation path remains the
# fallback when this directory is absent.
CLIENT_WORLD="${OML_E2E_QUICKPLAY:-oml-e2e-world}"
if [ -d run/server/world ] && [ ! -d "run/client/saves/$CLIENT_WORLD" ]; then
  mkdir -p run/client/saves
  cp -r run/server/world "run/client/saves/$CLIENT_WORLD"
  rm -f "run/client/saves/$CLIENT_WORLD/session.lock"
  echo "ci-e2e: reused the server e2e world for the client"
fi

LIBGL_ALWAYS_SOFTWARE=1 MESA_LOADER_DRIVER_OVERRIDE=llvmpipe \
  xvfb-run -a -s "-screen 0 1280x720x24" ./scripts/e2e-client.sh

# The client script covers the integrated server; this one makes the client join a real dedicated
# server, which is the path a player uses and the singleplayer probes cannot exercise.
LIBGL_ALWAYS_SOFTWARE=1 MESA_LOADER_DRIVER_OVERRIDE=llvmpipe \
  ./scripts/e2e-multiplayer.sh
