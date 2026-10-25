#!/usr/bin/env bash
# Dedicated server + remote client gate: the client joins a real dedicated server, exchanges the
# payload with it, then quits; the harness stops the server and requires both verdicts.
#
# Env: OML_GAME_VERSION (26.3), OML_E2E_OML_VERSION (release version override),
#      OML_E2E_TICKS (200), OML_E2E_TIMEOUT (900), OML_E2E_WATCHDOG (840),
#      OML_E2E_SERVER_WATCHDOG (1200), OML_E2E_GRACE_SECONDS (720),
#      OML_E2E_READY_TIMEOUT (240), OML_E2E_STOP_TIMEOUT (60),
#      OML_E2E_MULTIPLAYER_PORT (first free port from 25565), OML_E2E_SEED (oml-e2e),
#      OML_E2E_USERNAME (OMLTest).
set -uo pipefail
cd "$(dirname "$0")/.." || exit

GAME_VERSION="${OML_E2E_GAME_VERSION:-${OML_GAME_VERSION:-26.3}}"
TICKS="${OML_E2E_TICKS:-200}"
TIMEOUT="${OML_E2E_TIMEOUT:-900}"
WATCHDOG="${OML_E2E_WATCHDOG:-840}"
SERVER_WATCHDOG="${OML_E2E_SERVER_WATCHDOG:-1200}"
GRACE="${OML_E2E_GRACE_SECONDS:-720}"
READY_TIMEOUT="${OML_E2E_READY_TIMEOUT:-240}"
STOP_TIMEOUT="${OML_E2E_STOP_TIMEOUT:-60}"
USERNAME="${OML_E2E_USERNAME:-OMLTest}"
SEED="${OML_E2E_SEED:-oml-e2e}"
SERVER_LOG="${OML_E2E_MULTIPLAYER_SERVER_LOG:-build/e2e-multiplayer-server.log}"
CLIENT_LOG="${OML_E2E_MULTIPLAYER_CLIENT_LOG:-build/e2e-multiplayer-client.log}"
SERVER_CONTEXT="${OML_E2E_MULTIPLAYER_SERVER_CONTEXT:-build/e2e-context-multiplayer-server.json}"
SERVER_RESULT="${OML_E2E_MULTIPLAYER_SERVER_RESULT:-build/e2e-result-multiplayer-server.json}"
CLIENT_CONTEXT="${OML_E2E_MULTIPLAYER_CLIENT_CONTEXT:-build/e2e-context-multiplayer-client.json}"
CLIENT_RESULT="${OML_E2E_MULTIPLAYER_CLIENT_RESULT:-build/e2e-result-multiplayer-client.json}"
SERVER_LOG_FILE="logs/oml-console.log"

export OML_GAME_VERSION="$GAME_VERSION"
export OML_E2E_MULTIPLAYER=1

mkdir -p build run/server run/client

PORT="${OML_E2E_MULTIPLAYER_PORT:-}"
if [ -z "$PORT" ]; then
  for candidate in $(seq 25565 25600); do
    if ! (echo >"/dev/tcp/127.0.0.1/$candidate") >/dev/null 2>&1; then
      PORT="$candidate"
      break
    fi
  done
fi
if [ -z "$PORT" ]; then
  echo "e2e-multiplayer: no free port in 25565..25600"
  exit 1
fi
export OML_E2E_MULTIPLAYER_ADDRESS="127.0.0.1:$PORT"

SERVER_PROPERTIES="run/server/server.properties"
set_property() {
  local key="$1" value="$2"
  touch "$SERVER_PROPERTIES"
  grep -v "^${key}=" "$SERVER_PROPERTIES" > "${SERVER_PROPERTIES}.tmp" || true
  mv "${SERVER_PROPERTIES}.tmp" "$SERVER_PROPERTIES"
  printf '%s=%s\n' "$key" "$value" >> "$SERVER_PROPERTIES"
}
set_property server-port "$PORT"
set_property online-mode false
set_property enforce-secure-profile false
if ! grep -q '^level-seed=' "$SERVER_PROPERTIES" 2>/dev/null; then
  set_property level-seed "$SEED"
fi

evidence_start() {
  local side="$1" log="$2" context="$3" result="$4"
  OML_E2E_CONTEXT="$context" OML_E2E_RESULT="$result" \
    bash -c '. scripts/e2e-evidence.sh; e2e_evidence_start "$1" "$2"' _ "$side" "$log"
}

evidence_finish() {
  local side="$1" log="$2" context="$3" result="$4" rc="$5"
  OML_E2E_CONTEXT="$context" OML_E2E_RESULT="$result" OML_E2E_SIDE="$side" OML_E2E_LOG_PATH="$log" \
    bash -c '. scripts/e2e-evidence.sh; e2e_evidence_finish "$1"' _ "$rc"
}

SERVER_RC=1
CLIENT_RC=1
SERVER_STARTED=""
CLIENT_STARTED=""
FIFO="$(mktemp -u)"
mkfifo "$FIFO"
cleanup() {
  exec 3>&- 2>/dev/null
  if [ -n "${SERVER_PID:-}" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
  fi
  rm -f "$FIFO"
}
finish_all() {
  local rc=$?
  if [ -n "$SERVER_STARTED" ]; then
    evidence_finish server "$SERVER_LOG" "$SERVER_CONTEXT" "$SERVER_RESULT" "${SERVER_RC:-$rc}"
  fi
  if [ -n "$CLIENT_STARTED" ]; then
    evidence_finish client "$CLIENT_LOG" "$CLIENT_CONTEXT" "$CLIENT_RESULT" "${CLIENT_RC:-$rc}"
  fi
  cleanup
  return 0
}
trap finish_all EXIT

echo "e2e-multiplayer: server on 127.0.0.1:$PORT"
SERVER_STARTED=1
export OML_LOG_FILE="$SERVER_LOG_FILE"
evidence_start server "$SERVER_LOG" "$SERVER_CONTEXT" "$SERVER_RESULT"
unset OML_LOG_FILE

SERVER_ARGS=(
  -Poml.gameVersion="$GAME_VERSION"
  -Poml.e2e.ticks="$TICKS"
  -Poml.e2e.timeoutSeconds="$SERVER_WATCHDOG"
  -Poml.e2e.graceSeconds="$GRACE"
  -Poml.e2e.multiplayer=1
  -Poml.log.file="$SERVER_LOG_FILE"
)
[ -n "${OML_E2E_OML_VERSION:-}" ] && SERVER_ARGS+=(-Poml_version="$OML_E2E_OML_VERSION")

exec 3<>"$FIFO"
./gradlew --no-daemon runServer "${SERVER_ARGS[@]}" --console=plain <"$FIFO" >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!

deadline=$((SECONDS + READY_TIMEOUT))
until grep -qE 'Done \(|For help, type' "$SERVER_LOG" 2>/dev/null; do
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "e2e-multiplayer: the server exited before Done"
    tail -60 "$SERVER_LOG"
    SERVER_RC=1
    exit 1
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "e2e-multiplayer: no server Done within ${READY_TIMEOUT}s"
    tail -60 "$SERVER_LOG"
    SERVER_RC=1
    exit 1
  fi
  sleep 2
done

OPTIONS="run/client/options.txt"
mkdir -p run/client
touch "$OPTIONS"
for key in onboardAccessibility pauseOnLostFocus skipMultiplayerWarning; do
  grep -v "^${key}:" "$OPTIONS" > "${OPTIONS}.tmp" || true
  mv "${OPTIONS}.tmp" "$OPTIONS"
done
printf '%s\n' 'onboardAccessibility:false' 'pauseOnLostFocus:false' 'skipMultiplayerWarning:true' >> "$OPTIONS"

CLIENT_STARTED=1
unset OML_LOG_FILE
evidence_start client "$CLIENT_LOG" "$CLIENT_CONTEXT" "$CLIENT_RESULT"

CLIENT_ARGS=(
  -Poml.gameVersion="$GAME_VERSION"
  -Poml.quickPlayServer="127.0.0.1:$PORT"
  -Poml.e2e.ticks="$TICKS"
  -Poml.e2e.timeoutSeconds="$WATCHDOG"
  -Poml.e2e.graceSeconds="$GRACE"
  -Poml.e2e.multiplayer=1
  -Poml.e2e.username="$USERNAME"
)
[ -n "${OML_E2E_OML_VERSION:-}" ] && CLIENT_ARGS+=(-Poml_version="$OML_E2E_OML_VERSION")

LIBGL_ALWAYS_SOFTWARE=1 MESA_LOADER_DRIVER_OVERRIDE=llvmpipe \
  timeout -s KILL "$TIMEOUT" xvfb-run -a -s "-screen 0 1280x720x24" \
  ./gradlew --no-daemon runClient "${CLIENT_ARGS[@]}" --console=plain >"$CLIENT_LOG" 2>&1
CLIENT_RC=$?

if [ "$CLIENT_RC" -eq 137 ]; then
  echo "e2e-multiplayer: the client did not exit within ${TIMEOUT}s"
  tail -40 "$CLIENT_LOG"
elif [ "$CLIENT_RC" -ne 0 ]; then
  echo "e2e-multiplayer: the client exited $CLIENT_RC"
  tail -40 "$CLIENT_LOG"
fi
if ! grep -q '\[OML-E2E\] RESULT PASS' "$CLIENT_LOG"; then
  echo "e2e-multiplayer: the client left no PASS verdict"
  grep -E '\[OML-E2E\] (FAILED|RESULT)' "$CLIENT_LOG" || true
  CLIENT_RC=1
fi

printf '/stop\n' >&3
stop_deadline=$((SECONDS + STOP_TIMEOUT))
while kill -0 "$SERVER_PID" 2>/dev/null && [ "$SECONDS" -lt "$stop_deadline" ]; do sleep 1; done
if kill -0 "$SERVER_PID" 2>/dev/null; then
  echo "e2e-multiplayer: the server did not stop within ${STOP_TIMEOUT}s"
  kill -9 "$SERVER_PID" 2>/dev/null || true
  SERVER_RC=1
else
  wait "$SERVER_PID"
  SERVER_RC=$?
fi
exec 3>&-

if ! grep -q '\[OML-E2E\] RESULT PASS' "$SERVER_LOG"; then
  echo "e2e-multiplayer: the server left no PASS verdict"
  grep -E '\[OML-E2E\] (FAILED|RESULT)' "$SERVER_LOG" || true
  SERVER_RC=1
fi

if [ "$SERVER_RC" -ne 0 ] || [ "$CLIENT_RC" -ne 0 ]; then
  echo "e2e-multiplayer: FAIL (server=$SERVER_RC client=$CLIENT_RC)"
  exit 1
fi
echo "e2e-multiplayer: PASS (server=$SERVER_RC client=$CLIENT_RC)"
