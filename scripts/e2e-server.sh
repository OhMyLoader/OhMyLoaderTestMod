#!/usr/bin/env bash
# Dedicated-server end-to-end gate (M1 / AC-2 of the loader workspace's development plan).
#
# Starts the server exactly the way a player would (Gradle -> OMLBootstrap -> the game), waits for the
# verification mod's "[OML-E2E] READY" line, lets it stop itself gracefully, and decides on the verdict.
# Exit code 0 only when the run passed — that is the gate; the RESULT line is why.
#
# Env: OML_E2E_TICKS (default 200), OML_E2E_READY_TIMEOUT (240s), OML_E2E_STOP_TIMEOUT (60s),
#      OML_E2E_LOG (build/e2e-server.log), OML_E2E_WATCHDOG (300s, the in-game watchdog that turns
#      a session that never finishes into a FAIL instead of a hang), OML_E2E_GRACE_SECONDS,
#      OML_E2E_EXPECT_EXTRA (gate self-test: an expectation nothing reports, so a working gate must fail),
#      OML_E2E_SEED (oml-e2e, the world seed a generated world is created from),
#      OML_E2E_OML_VERSION (overrides the SNAPSHOT coordinate for a release gate),
#      OML_E2E_CONTEXT / OML_E2E_RESULT (evidence paths, default build/e2e-{context,result}-server.json),
#      OML_RUNTIME_JAVA / OML_TEST_JDK_HOME (which JVM the game runs on — see e2e_resolve_runtime_java).
set -uo pipefail
cd "$(dirname "$0")/.." || exit

TICKS="${OML_E2E_TICKS:-200}"
READY_TIMEOUT="${OML_E2E_READY_TIMEOUT:-240}"
STOP_TIMEOUT="${OML_E2E_STOP_TIMEOUT:-60}"
LOG="${OML_E2E_LOG:-build/e2e-server.log}"
# The game version is gradle.properties' oml_game_version and nowhere else.
GAME_VERSION="$(sed -n 's/^oml_game_version=//p' gradle.properties | head -1)"
[ -n "$GAME_VERSION" ] || { echo "e2e-server: oml_game_version missing from gradle.properties" >&2; exit 1; }
export OML_E2E_GAME_VERSION="$GAME_VERSION"
E2E_ARGS=(-Poml.e2e.ticks="$TICKS" -Poml.e2e.timeoutSeconds="${OML_E2E_WATCHDOG:-300}")
[ -n "${OML_E2E_GRACE_SECONDS:-}" ] && E2E_ARGS+=(-Poml.e2e.graceSeconds="$OML_E2E_GRACE_SECONDS")
[ -n "${OML_E2E_EXPECT_EXTRA:-}" ] && E2E_ARGS+=(-Poml.e2e.expectExtra="$OML_E2E_EXPECT_EXTRA")
[ -n "${OML_LOG_FILE:-}" ] && E2E_ARGS+=(-Poml.log.file="$OML_LOG_FILE")
[ -n "${OML_E2E_DIAGNOSTICS:-}" ] && E2E_ARGS+=(-Poml.diagnostics="$OML_E2E_DIAGNOSTICS")
[ -n "${OML_E2E_OML_VERSION:-}" ] && E2E_ARGS+=(-Poml_version="$OML_E2E_OML_VERSION")
mkdir -p "$(dirname "$LOG")"

# Several preconditions read the terrain the game generates (a solid column with a free block above it
# near the player, a run of open air for a launch). A fresh CI job generates a fresh world, so those
# checks would otherwise be a draw from a seed distribution; one fixed seed makes the generated terrain
# the same run to run, which also makes a red reproducible. It applies where a world is *created* — an
# existing run/server/world keeps the seed it was made with, so delete that directory to re-roll.
SERVER_PROPERTIES="run/server/server.properties"
mkdir -p run/server
if grep -q '^level-seed=' "$SERVER_PROPERTIES" 2>/dev/null; then
  echo "e2e-server: keeping the level-seed already in $SERVER_PROPERTIES"
else
  printf 'level-seed=%s\n' "${OML_E2E_SEED:-oml-e2e}" >> "$SERVER_PROPERTIES"
fi

FIFO="$(mktemp -u)"
mkfifo "$FIFO"
cleanup() { exec 3>&- 2>/dev/null; rm -f "$FIFO"; }
. "$(dirname "$0")/e2e-evidence.sh"
# Both sides run the game on the same JVM as the client gate (see e2e_resolve_runtime_java): a red
# server gate cannot be attributed to the code or the runtime if the two halves differ.
e2e_resolve_runtime_java "e2e-server"
[ -n "${OML_RUNTIME_JAVA:-}" ] && E2E_ARGS+=(-Poml.runtimeJava="$OML_RUNTIME_JAVA")
e2e_evidence_start server "$LOG"
trap 'rc=$?; e2e_evidence_finish "$rc"; cleanup' EXIT

# Opened read-write so the shell holds the write end: the server's stdin never reaches EOF before the
# /stop we send, and opening a FIFO this way cannot block.
exec 3<>"$FIFO"

./gradlew --no-daemon runServer "${E2E_ARGS[@]}" --console=plain <"$FIFO" >"$LOG" 2>&1 &
GRADLE_PID=$!

deadline=$((SECONDS + READY_TIMEOUT))
until grep -q '\[OML-E2E\] READY' "$LOG" 2>/dev/null; do
  if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
    echo "e2e-server: the server exited before READY"
    tail -40 "$LOG"
    exit 1
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "e2e-server: no READY within ${READY_TIMEOUT}s"
    tail -40 "$LOG"
    kill -9 "$GRADLE_PID" 2>/dev/null
    exit 1
  fi
  sleep 2
done

# The server stops itself once the mod has printed READY (E2E calls MinecraftServer.halt(false), which
# is what /stop does), so this only waits. Console input is deliberately unused: Gradle's stdin
# forwarding fails on Windows with "invalid handle", and the gate then hung here after READY.
stop_deadline=$((SECONDS + STOP_TIMEOUT))
while kill -0 "$GRADLE_PID" 2>/dev/null && [ "$SECONDS" -lt "$stop_deadline" ]; do sleep 1; done
if kill -0 "$GRADLE_PID" 2>/dev/null; then
  echo "e2e-server: the server did not stop within ${STOP_TIMEOUT}s of READY"
  kill -9 "$GRADLE_PID" 2>/dev/null
  exit 1
fi

wait "$GRADLE_PID"
GRADLE_RC=$?
exec 3>&-

grep -E '\[OML-E2E\] (FAILED|READY|RESULT)' "$LOG" || true
if [ "$GRADLE_RC" -ne 0 ]; then
  echo "e2e-server: FAIL (gradle exit=$GRADLE_RC — early crash or watchdog halt)"
  exit 1
fi
if ! grep -q '\[OML-E2E\] RESULT PASS' "$LOG"; then
  echo "e2e-server: FAIL (no PASS verdict in $LOG)"
  exit 1
fi
echo "e2e-server: PASS"
