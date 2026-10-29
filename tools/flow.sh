#!/usr/bin/env bash
# Full test flow: start both instances, host starts "School Base", client joins.
cd "$(dirname "$0")/.."
D="python -I tools/dbg.py"
get() { timeout 20 $D "$1" "get $2" 2>/dev/null | tail -1; }
click() { timeout 30 $D "$1" "press $2 $3" "sleep 0.3" "release $2 $3" "sleep 1.2" >/dev/null; }
yes_if_dialog() { [ "$(get "$1" confirmviewed)" = "true" ] && click "$1" 327 349; }

tools/testrun.sh both
sleep 9
for p in 7801 7802; do
  timeout 30 $D $p "key SPACE tap" "sleep 2" >/dev/null
  yes_if_dialog $p
done
# host: Coop hosten -> Hosten -> New Game -> Story -> School Base
click 7801 396 479
click 7801 409 356
click 7801 396 375
yes_if_dialog 7801
[ "$(get 7801 page)" = "2" ] || { yes_if_dialog 7801; click 7801 396 375; }
click 7801 396 354
click 7801 396 375
sleep 8
# client: join directly (the dialog itself is tested separately)
timeout 20 $D 7802 "join 127.0.0.1 7777" >/dev/null
sleep 3
timeout 30 $D 7801 "status" | grep page
timeout 30 $D 7802 "status" | grep page
