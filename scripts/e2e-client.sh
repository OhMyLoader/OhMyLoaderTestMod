#!/usr/bin/env bash
# Client end-to-end gate (M1 / AC-1 in docs/开发计划.md).
#
# Starts the client (Gradle -> OMLBootstrap -> the game) with the E2E driver enabled. Nothing outside
# the process can press the close button, so the verification mod quits the game itself once it has
# seen [READY] — which it only prints after the expected probes have reported. A hard timeout wraps
# the whole run so a hung client fails the job instead of holding it.
#
# Needs a display: wrap with `xvfb-run -a` on a headless machine (CI does).
# Env: OML_E2E_TICKS (default 200), OML_E2E_TIMEOUT (420s), OML_E2E_LOG (build/e2e-client.log),
#      OML_E2E_GRACE_TICKS, OML_E2E_EXPECT_EXTRA (gate self-test).
set -uo pipefail
cd "$(dirname "$0")/.."

TICKS="${OML_E2E_TICKS:-200}"
TIMEOUT="${OML_E2E_TIMEOUT:-420}"
LOG="${OML_E2E_LOG:-build/e2e-client.log}"
# The in-game watchdog fires before the outer one, so a stuck session reports why instead of being killed.
WATCHDOG="${OML_E2E_WATCHDOG:-$((TIMEOUT - 30))}"
E2E_ARGS=(-Poml.e2e.ticks="$TICKS" -Poml.e2e.timeoutSeconds="$WATCHDOG")
[ -n "${OML_E2E_GRACE_TICKS:-}" ] && E2E_ARGS+=(-Poml.e2e.graceTicks="$OML_E2E_GRACE_TICKS")
[ -n "${OML_E2E_EXPECT_EXTRA:-}" ] && E2E_ARGS+=(-Poml.e2e.expectExtra="$OML_E2E_EXPECT_EXTRA")
mkdir -p "$(dirname "$LOG")"

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
