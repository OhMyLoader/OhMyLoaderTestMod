#!/usr/bin/env bash
# Scripted real-play session for the playground kit (`oml_playmod`).
#
# Boots the real client into a world with this jar in it, drives it the way a player would — chat commands on
# the keyboard, a key-bind press, screenshots on F2 — and leaves the log and the screenshots behind as the
# evidence. It is NOT a gate: nothing here asserts, the point is to sit in the world the API built and look at
# it. The E2E harness stays idle because no `-Doml.e2e=1` is passed.
#
# Usage: ./scripts/play-session.sh [world]     (default: playtest)
# Env:   PLAY_SESSION_SECONDS (default 150) — how long to stay in the world.
set -uo pipefail
cd "$(dirname "$0")/.." || exit

WORLD="${1:-playtest}"
LOG="build/play-client.log"
SHOTS="run/client/screenshots"
SECONDS_PLAY="${PLAY_SESSION_SECONDS:-150}"

# The client's `--quickPlaySingleplayer` does not create a missing world, so the session always starts from a
# fresh copy of a save the gates already built: the copy keeps this session out of the gate's own save, and a
# new copy also drops the session.lock a killed previous run left behind (the game refuses a locked world).
template="run/client/saves/oml-e2e-world"
[ -d "$template" ] || { echo "no save to copy: run ./scripts/e2e-client.sh once first"; exit 1; }
rm -rf "run/client/saves/$WORLD"
cp -r "$template" "run/client/saves/$WORLD"
rm -f "run/client/saves/$WORLD/session.lock"
echo "recreated run/client/saves/$WORLD from the gate's save"
rm -f "$SHOTS"/*.png 2>/dev/null
# A scripted session starts from the kit's defaults: a config left over from an earlier run would silently
# change what this one shows.
rm -f run/client/config/oml_playmod.toml
mkdir -p build

# The desktop is a Wayland session, and SDL picks Wayland when it sees WAYLAND_DISPLAY — a Wayland surface is
# invisible to xdotool, so nothing below could drive it. Clearing it puts the window under XWayland, which is
# also what the `DISPLAY=:0` the gates already use refers to.
# Its own process group, so the trap reaches the game and not only the wrapper: a killed client leaves a locked
# save for the next run.
# `--no-configuration-cache`: the E2E switch is a JVM arg added at configuration time from `-Poml.e2e.ticks`, and
# a cached configuration from a gate run replays it — a play session that quietly runs the probes is not a play
# session, because the probes kill and respawn the player and rewrite game rules.
setsid env -u WAYLAND_DISPLAY -u XDG_SESSION_TYPE SDL_VIDEO_DRIVER=x11 DISPLAY=:0 ./gradlew --no-daemon -q \
  --no-configuration-cache runClient "-Poml.quickPlay=$WORLD" --console=plain >"$LOG" 2>&1 &
GRADLE_PID=$!
trap 'kill -TERM -- "-$GRADLE_PID" 2>/dev/null || kill -TERM "$GRADLE_PID" 2>/dev/null' EXIT

echo "waiting for the kit's own join line in $LOG…"
GREET_TIMEOUT=300
deadline=$((SECONDS + GREET_TIMEOUT))
while ! grep -q 'kit: claim a waystone' "$LOG" 2>/dev/null; do
  if grep -q '\[OML-E2E\]' "$LOG" 2>/dev/null; then
    echo "the E2E probes are running in this session, so the world is not the mod's to play in"; exit 1
  fi
  if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
    echo "the client exited before the kit greeted the player"; tail -40 "$LOG"; exit 1
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "no greeting within ${GREET_TIMEOUT}s"; tail -40 "$LOG"; exit 1
  fi
  sleep 2
done
sleep 8   # let the renderer settle on the spawn column before anything is typed

# The window carries no title worth matching on this desktop (KWin reports the class), so it is found by the
# game's own pid and then by size — the 10x10 utility window GLFW keeps is not the one that takes keys.
GAME_PID="$(pgrep -f 'OMLBootstrap.*quickPlaySingleplayer' | tail -1)"
WIN=""
for candidate in $(DISPLAY=:0 xdotool search --pid "$GAME_PID" 2>/dev/null); do
  area=$(( $(DISPLAY=:0 xdotool getwindowgeometry --shell "$candidate" 2>/dev/null | sed -n 's/^WIDTH=//p') * $(DISPLAY=:0 xdotool getwindowgeometry --shell "$candidate" 2>/dev/null | sed -n 's/^HEIGHT=//p') ))
  if [ "$area" -gt 100000 ] && [ "$area" -gt "${best:-0}" ]; then best=$area; WIN=$candidate; fi
done
[ -n "$WIN" ] || { echo "no window to drive for pid $GAME_PID"; exit 1; }
echo "driving window $WIN ($(DISPLAY=:0 xdotool getwindowname "$WIN" 2>/dev/null)) of pid $GAME_PID"

# `windowfocus` plus XTEST rather than `--window`: SDL takes keystrokes either way, but its chat box reads text
# from IME input events, which a targeted XSendEvent never produces — typing at the window with `--window` lands
# nowhere, so every event below goes through the XTEST path to the focused window.
focus() { DISPLAY=:0 xdotool windowactivate --sync "$WIN"; DISPLAY=:0 xdotool windowfocus --sync "$WIN"; }
# Escape is never pressed on speculation: with no screen open it pauses the singleplayer game, and a paused
# world is what swallows the steps that follow.
say()   { focus; DISPLAY=:0 xdotool key t; sleep 0.7
          DISPLAY=:0 xdotool type --delay 40 "$1"; sleep 0.3
          DISPLAY=:0 xdotool key Return; sleep "${2:-3}"; }
shot()  { focus; DISPLAY=:0 xdotool key F2; sleep 1.5; }
press() { focus; DISPLAY=:0 xdotool key "$1"; sleep "${2:-2}"; }
# Death is part of play, and the death screen takes the mouse: its respawn button sits in the middle of the
# window, a little below the centre. Clicking there in the world is only a punch, so this is safe when alive.
GEOM="$(DISPLAY=:0 xdotool getwindowgeometry --shell "$WIN")"
WIN_W="$(sed -n 's/^WIDTH=//p' <<<"$GEOM")"; WIN_H="$(sed -n 's/^HEIGHT=//p' <<<"$GEOM")"
revive() { focus; DISPLAY=:0 xdotool mousemove --window "$WIN" $((WIN_W / 2)) $((WIN_H / 2 + 12))
           DISPLAY=:0 xdotool click 1; sleep 3; }

echo "driving the session: /kit tree, the creative book, the panel key, and a walk onto the pad"
say '/kit' 4                                       # the root lists what the kit can do
shot
say '/kit give' 4
say '/kit wear' 4
say '/kit probe' 6
say '/kit tabs' 4                             # what the game's creative tab registry actually holds
say '/kit creative' 4
press e 6                                     # the creative book: the screen mod content has to reach
shot
press e 2                                     # close the screen: KeyMapping takes no keys while a screen holds the keyboard
say '/kit anchor here' 4                            # a waystone is not placed yet: the anchor is the test
say '/kit mob cow 2' 5
say '/kit zap cow' 4
say '/kit tag is_lightning' 4
say '/kit tag oml_playmod:lite_kit' 4
say '/kit rule keep_inventory true' 4
say '/kit rule do_mob_spawning false' 4
say '/kit weather rain' 5
shot
say '/kit clock 60' 4
say '/kit gallery build' 8                          # force-loads its own column and places three kit blocks
shot
say '/kit gallery enter' 8
shot
say '/kit pad 3' 4              # the player arrived on the pad: charge it before walking
say '/kit pad' 4
press w 2; press s 4                               # step off the charged pad, step back on: it takes the player up
shot
say '/kit pad' 4
press j 4                                          # the kit's own key bind: ask the server for the panel
shot
revive                                    # a death screen would hold the keyboard through the readout below
say '/kit panel' 3
say '/kit home' 3
say '/kit home' 3
revive                                    # a death would hold the screen and swallow everything below
say '/kit claim' 4                              # the command form of right-clicking the waystone
say '/kit pearl' 8                                  # throw the recall pearl: back to the claimed waystone
shot
say '/kit gallery leave' 8
say '/kit' 4
say '/kit admin' 4        # operator-only on purpose: a non-op player sees the gate refuse
shot

echo "staying in the world for the remaining ${SECONDS_PLAY}s of scripted play…"
# Every second: wander, punch, look at the overlay. The window has keyboard focus, so these are the player's
# own inputs rather than the mod's.
end=$((SECONDS + SECONDS_PLAY))
while [ "$SECONDS" -lt "$end" ]; do
  press w 1; press a 1; press space 1; press 1 1
done

revive                                    # the death screen holds the keyboard; the commands below need the world
# The session's world is a copy of a dedicated server's save, so the player holds no cheats: vanilla's
# /save-all and /stop are not in the command tree at all, and the kit's own /kit admin leaf refusing is the
# evidence of that. The attempt stays for a world that does have them; the exit below is bounded either way,
# because this save is throwaway and an unsaved exit costs nothing.
say '/save-all' 6
say '/stop' 20
for _ in $(seq 1 10); do kill -0 "$GRADLE_PID" 2>/dev/null || break; sleep 3; done
kill -TERM -- "-$GRADLE_PID" 2>/dev/null
wait "$GRADLE_PID" 2>/dev/null
rc=$?
# The session always ends by killing the client (the /stop above is refused in a world without cheats),
# so a non-zero status here is expected. Report it rather than hiding it: an early crash would print the
# same line, and the tail above is what tells the two apart.
[ "$rc" -ne 0 ] && echo "client ended with status $rc (expected: the scripted kill at the end of the session)"

echo "---------------------------------------------------------------"
echo "session log: $LOG"
echo "screenshots: $(ls -1 "$SHOTS" 2>/dev/null | tr '\n' ' ')"
echo "kit lines:"
grep -E '\[oml_playmod\]|\[kit\]|kit:' "$LOG" | tail -30
# The session's own verdict is "every scripted step ran": the failure paths above exit 1, and reaching here
# means they did not. The client's status cannot be that verdict — this script ends it with a kill.
exit 0
