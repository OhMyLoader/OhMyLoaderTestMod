#!/usr/bin/env bash
# Client end-to-end gate (M1 / AC-1 of the loader workspace's development plan).
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
  # What this boot owes this script is a save directory: the server gate's verdict and exit code belong
  # to the server job, so a red code here must not read as "no world was generated".
  OML_E2E_LOG="${LOG%.log}-worldgen.log" ./scripts/e2e-server.sh ||
    echo "e2e-client: the world-generation boot exited non-zero (the server job reports its verdict)"
  mkdir -p "$(dirname "$WORLD_DIR")"
  cp -r run/server/world "$WORLD_DIR"
  # The script runs without `set -e`: a failed copy must not turn into the silent shape of "the
  # client boots, quickPlay finds no save, lands on the title screen, world_load never reports".
  if [ ! -f "$WORLD_DIR/level.dat" ]; then
    echo "e2e-client: world copy incomplete — no level.dat in $WORLD_DIR"
    tail -40 "${LOG%.log}-worldgen.log" 2>/dev/null
    exit 1
  fi
fi

# Two options stand between an unattended run and a verdict:
# - `onboardAccessibility`: with no options.txt the game shows the accessibility onboarding screen instead
#   of the title screen and quickPlay never fires (observed on CI: 12 minutes of that screen, world=none).
# - `pauseOnLostFocus`: `Minecraft.pauseIfInactive()` pauses after 500 ms unfocused and opens the pause
#   screen, which stops the integrated server's tick — in singleplayer every server-side probe then stalls
#   on a game nobody paused on purpose (observed: `screen=PauseScreen`, five checks "never reported").
# Both are set rather than seeded-if-missing: the game writes `pauseOnLostFocus:true` itself, and `run/` is
# this task's own generated directory (never committed), so the gate owns these two keys outright.
OPTIONS="run/client/options.txt"
mkdir -p run/client
touch "$OPTIONS"
for key in onboardAccessibility pauseOnLostFocus; do
  grep -v "^${key}:" "$OPTIONS" > "${OPTIONS}.tmp" || true
  mv "${OPTIONS}.tmp" "$OPTIONS"
done
echo 'onboardAccessibility:false' >> "$OPTIONS"
echo 'pauseOnLostFocus:false' >> "$OPTIONS"

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
  echo "e2e-client: FAIL (gradle exit=$GRADLE_RC — early crash or watchdog halt)"
  exit 1
fi
if ! grep -q '\[OML-E2E\] RESULT PASS' "$LOG"; then
  echo "e2e-client: FAIL (no PASS verdict in $LOG)"
  exit 1
fi
echo "e2e-client: PASS"
