#!/usr/bin/env bash
# Starts two test instances (host + client) side by side on the virtual monitor (x >= 2560).
#   tools/testrun.sh            start both
#   tools/testrun.sh host       only host
#   tools/testrun.sh kill       stop both
HERE="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "${GAME:-}" ] && [ -f "$HERE/local.env" ]; then . "$HERE/local.env"; fi
GAME="${GAME:?set GAME to the Dread Dawn folder (environment or local.env)}"
JAVA="$GAME/Word/Etc/doot/effc/MyPanel/jreing/bin/java.exe"
MOD="$(cygpath -w "$GAME/coop/coop-mod.jar")"
PIDS="${TMPDIR:-/tmp}/ddcoop-test-pids"
mkdir -p "$PIDS"

killall_tests() {
  for f in "$PIDS"/*.pid; do
    [ -f "$f" ] || continue
    pid=$(cat "$f")
    taskkill //F //PID "$pid" >/dev/null 2>&1 && echo "beendet $(basename "$f" .pid) ($pid)"
    rm -f "$f"
  done
}

prep_root() {
  local root="$GAME/Save/cooptest/$1"
  mkdir -p "$root/Stock" "$root/History" "$root/Cloud"
  cp -f "$GAME/Save/option.txt" "$root/option.txt" 2>/dev/null
  cp -f "$GAME/Save/language.txt" "$root/language.txt" 2>/dev/null
}

start() {
  local tag=$1 x=$2 port=$3 name=$4
  prep_root "$tag"
  out=$("$JAVA" -jar "$MOD" -Dcoop.window=800x600 -Dcoop.windowPos=$x,30 -Dcoop.renderSize=1280x720 \
    -Dcoop.title="DD $tag" -Dcoop.logtag=$tag -Dcoop.debugPort=$port -Dcoop.saveRoot="Save\\cooptest\\$tag" \
    -Dcoop.config=coop-test-$tag.properties -Dcoop.name=$name -Dcoop.bind=127.0.0.1 2>&1)
  echo "$out" | tail -2
  pid=$(echo "$out" | grep -o 'PID [0-9]*' | grep -o '[0-9]*')
  [ -n "$pid" ] && echo "$pid" > "$PIDS/$tag.pid"
}

case "${1:-both}" in
  kill) killall_tests ;;
  host) killall_tests; start host 2570 7801 Host ;;
  client) start client 3390 7802 Gast ;;
  both) killall_tests; start host 2570 7801 Host; sleep 2; start client 3390 7802 Gast ;;
esac
