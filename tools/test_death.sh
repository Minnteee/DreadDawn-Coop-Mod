#!/usr/bin/env bash
# Death / revive / respawn / save-for-host test with two running test instances (host 7801, client 7802).
cd "$(dirname "$0")/.."
if [ -z "${GAME:-}" ] && [ -f local.env ]; then . ./local.env; fi
GAME="${GAME:?set GAME to the Dread Dawn folder (environment or local.env)}"
D="python -I tools/dbg.py"
st() { timeout 10 $D "$1" status 2>/dev/null | tail -1; }
field() { st "$1" | grep -o "$2=[^ ]*" | head -1; }
waitplay() { for i in $(seq 1 30); do case "$(st 7801)" in *clientPlaying=true*) return 0;; esac; sleep 2; done; return 1; }
heal() { st "$1" | grep -o ' heal=[0-9.]*' | head -1; }

echo "== setup: campus, no god mode"
timeout 20 $D 7801 "sim traveling school 41062 14700 0" >/dev/null; waitplay; sleep 2
timeout 10 $D 7801 "god 0" >/dev/null; timeout 10 $D 7802 "god 0" >/dev/null
timeout 10 $D 7801 "sim heal" >/dev/null; timeout 10 $D 7802 "sim heal" >/dev/null

echo "== 1. guest down, host revives in time"
timeout 10 $D 7801 "sim tp 41060 14700" >/dev/null; timeout 10 $D 7802 "sim tp 41110 14700" >/dev/null; sleep 1
timeout 10 $D 7802 kill >/dev/null; sleep 1.5
echo "   guest after kill: $(field 7802 deathed) $(heal 7802)"
timeout 10 $D 7801 "key E tap" >/dev/null; sleep 2
echo "   guest after E:    $(field 7802 deathed) $(heal 7802)"

echo "== 2. guest down, nobody helps -> dead -> map change brings him back"
timeout 10 $D 7802 kill >/dev/null; sleep 17
echo "   guest after 17 s: $(field 7802 deathed) $(heal 7802)"
timeout 20 $D 7801 "sim traveling school 41062 14700 0" >/dev/null; waitplay; sleep 3
echo "   guest after trip: $(field 7802 deathed) $(heal 7802)"

echo "== 3. host down, guest revives"
timeout 10 $D 7801 "sim heal" >/dev/null; timeout 10 $D 7802 "sim heal" >/dev/null
timeout 10 $D 7801 "sim tp 41060 14700" >/dev/null; timeout 10 $D 7802 "sim tp 41110 14700" >/dev/null; sleep 1
timeout 10 $D 7801 kill >/dev/null; sleep 1.5
echo "   host after kill: $(field 7801 deathed) $(heal 7801)"
timeout 10 $D 7802 "key E tap" >/dev/null; sleep 2
echo "   host after E:    $(field 7801 deathed) $(heal 7801)"

echo "== 4. host dead, the guest walks into an exit -> host stands up before the trip, bag kept"
timeout 10 $D 7801 "packinfo" | tail -1 | cut -c1-110
timeout 10 $D 7801 kill >/dev/null; sleep 17
echo "   host after 17 s: $(field 7801 deathed) $(heal 7801)"
timeout 20 $D 7802 "sim traveling school 41062 14700 0" >/dev/null; sleep 1; waitplay; sleep 3
echo "   host after trip: $(field 7801 deathed) $(heal 7801)"
timeout 10 $D 7801 "packinfo" | tail -1 | cut -c1-110

echo "== 5. guest saves for the host (F5)"
timeout 10 $D 7802 "key F5 tap" >/dev/null; sleep 3
grep -E "saved for the guest|save slot" "$GAME/coop/logs/host.log" | tail -2
grep -E "message: .*(gespeichert|saved)" "$GAME/coop/logs/client.log" | tail -2

echo "== logs"
grep -E "down|revive|dead|back in|death screen|standing" "$GAME/coop/logs/client.log" | tail -8
grep -E "down|revive|dead|back in|death screen|standing" "$GAME/coop/logs/host.log" | tail -8
