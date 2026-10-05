#!/usr/bin/env bash
# Dedicated-server end-to-end gate (M1 / AC-2 in docs/开发计划.md).
#
# Starts the server exactly the way a player would (Gradle -> OMLBootstrap -> the game), waits for the
# verification mod's "[OML-E2E] READY" line, stops it gracefully by sending /stop on stdin, and decides
# on the verdict. Exit code 0 only when the run passed — that is the gate; the RESULT line is why.
#
# Env: OML_E2E_TICKS (default 200), OML_E2E_READY_TIMEOUT (240s), OML_E2E_STOP_TIMEOUT (60s),
#      OML_E2E_LOG (build/e2e-server.log), OML_E2E_GRACE_SECONDS, OML_E2E_EXPECT_EXTRA (gate self-test:
#      an expectation nothing reports, so a working gate must fail).
set -uo pipefail
cd "$(dirname "$0")/.."

TICKS="${OML_E2E_TICKS:-200}"
READY_TIMEOUT="${OML_E2E_READY_TIMEOUT:-240}"
STOP_TIMEOUT="${OML_E2E_STOP_TIMEOUT:-60}"
LOG="${OML_E2E_LOG:-build/e2e-server.log}"
E2E_ARGS=(-Poml.e2e.ticks="$TICKS" -Poml.e2e.timeoutSeconds="${OML_E2E_WATCHDOG:-300}")
[ -n "${OML_E2E_GRACE_SECONDS:-}" ] && E2E_ARGS+=(-Poml.e2e.graceSeconds="$OML_E2E_GRACE_SECONDS")
[ -n "${OML_E2E_EXPECT_EXTRA:-}" ] && E2E_ARGS+=(-Poml.e2e.expectExtra="$OML_E2E_EXPECT_EXTRA")
[ -n "${OML_LOG_FILE:-}" ] && E2E_ARGS+=(-Poml.log.file="$OML_LOG_FILE")
mkdir -p "$(dirname "$LOG")"

FIFO="$(mktemp -u)"
mkfifo "$FIFO"
cleanup() { exec 3>&- 2>/dev/null; rm -f "$FIFO"; }
trap cleanup EXIT

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

printf '/stop\n' >&3
stop_deadline=$((SECONDS + STOP_TIMEOUT))
while kill -0 "$GRADLE_PID" 2>/dev/null && [ "$SECONDS" -lt "$stop_deadline" ]; do sleep 1; done
if kill -0 "$GRADLE_PID" 2>/dev/null; then
  echo "e2e-server: the server did not stop within ${STOP_TIMEOUT}s of /stop"
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
