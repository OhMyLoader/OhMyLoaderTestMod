#!/usr/bin/env bash
# Client end-to-end gate (M1 / AC-1 in docs/开发计划.md).
#
# Starts the client (Gradle -> OMLBootstrap -> the game) with the E2E driver enabled, joins a world,
# and quits itself once the expectations have reported. A hard timeout wraps the whole run so a hung
# client fails the job instead of holding it.
#
# AC-1 says "main menu, into a world, exit" — and `--quickPlaySingleplayer` does NOT create a missing
# world (it lands on the title screen), so when the save is absent this script generates one with a
# server boot and copies it in: a dedicated server's world directory is the same on-disk layout a
# client save uses. Re-runs reuse it.
#
# The runtime layer must already be in mavenLocal (CI publishes it first).
# Needs a display: wrap with `xvfb-run -a` on a headless machine (CI does).
# Env: OML_E2E_TICKS (default 200), OML_E2E_TIMEOUT (420s), OML_E2E_LOG (build/e2e-client.log),
#      OML_E2E_QUICKPLAY (default oml-e2e-world), OML_E2E_GRACE_SECONDS, OML_E2E_WATCHDOG,
#      OML_E2E_EXPECT_EXTRA (gate self-test), OML_LOG_FILE.
set -uo pipefail
cd "$(dirname "$0")/.."

TICKS="${OML_E2E_TICKS:-200}"
TIMEOUT="${OML_E2E_TIMEOUT:-420}"
LOG="${OML_E2E_LOG:-build/e2e-client.log}"
WORLD="${OML_E2E_QUICKPLAY:-oml-e2e-world}"
WORLD_DIR="run/client/saves/$WORLD"
# The in-game watchdog fires before the outer one, so a stuck session reports why instead of being killed.
WATCHDOG="${OML_E2E_WATCHDOG:-$((TIMEOUT - 30))}"
mkdir -p "$(dirname "$LOG")"

if [ ! -d "$WORLD_DIR" ]; then
  echo "e2e-client: no save at $WORLD_DIR — generating one with a server boot"
  if ! OML_E2E_LOG="${LOG%.log}-worldgen.log" ./scripts/e2e-server.sh; then
    echo "e2e-client: world generation failed"
    exit 1
  fi
  mkdir -p "$(dirname "$WORLD_DIR")"
  cp -r run/server/world "$WORLD_DIR"
fi

E2E_ARGS=(-Poml.e2e.ticks="$TICKS" -Poml.e2e.timeoutSeconds="$WATCHDOG")
E2E_ARGS+=(-Poml.quickPlay="$WORLD" -Poml.e2e.quickPlay=1)
[ -n "${OML_E2E_GRACE_SECONDS:-}" ] && E2E_ARGS+=(-Poml.e2e.graceSeconds="$OML_E2E_GRACE_SECONDS")
[ -n "${OML_E2E_EXPECT_EXTRA:-}" ] && E2E_ARGS+=(-Poml.e2e.expectExtra="$OML_E2E_EXPECT_EXTRA")
[ -n "${OML_LOG_FILE:-}" ] && E2E_ARGS+=(-Poml.log.file="$OML_LOG_FILE")

timeout -s KILL "$TIMEOUT" \
  ./gradlew --no-daemon runClient "${E2E_ARGS[@]}" --console=plain >"$LOG" 2>&1
GRADLE_RC=$?

grep -E '\[OML-E2E\] (FAILED|READY|RESULT)' "$LOG" || true
if [ "$GRADLE_RC" -eq 137 ]; then
  echo "e2e-client: FAIL (no exit within ${TIMEOUT}s)"
  tail -30 "$LOG"
  exit 1
fi
if [ "$GRADLE_RC" -ne 0 ]; then
  echo "e2e-client: FAIL (gradle exit=$GRADLE_RC — the verdict forces a non-zero exit)"
  exit 1
fi
if ! grep -q '\[OML-E2E\] RESULT PASS' "$LOG"; then
  echo "e2e-client: FAIL (no PASS verdict in $LOG)"
  exit 1
fi
echo "e2e-client: PASS"
