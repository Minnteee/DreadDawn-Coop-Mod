#!/usr/bin/env bash
# Starts host + client, host begins a new story game ("School Base"), client joins; waits until both play.
cd "$(dirname "$0")/.."
D="python -I tools/dbg.py"
get() { timeout 20 $D "$1" "get $2" 2>/dev/null | tail -1; }
click() { timeout 30 $D "$1" "press $2 $3" "sleep 0.3" "release $2 $3" "sleep 1.5" >/dev/null; }
tools/testrun.sh both
sleep 9
for p in 7801 7802; do
  timeout 30 $D $p "key SPACE tap" "sleep 2" >/dev/null
  [ "$(get $p confirmviewed)" = "true" ] && click $p 327 349
done
click 7801 396 479   # Coop hosten
click 7801 409 356   # Hosten
sleep 2
click 7801 396 375   # New Game
for i in 1 2 3 4 5 6; do
  pg=$(get 7801 page)
  if [ "$(get 7801 confirmviewed)" = "true" ]; then click 7801 327 349
  elif [ "$pg" = "2" ]; then click 7801 396 354    # Story Mode
  elif [ "$pg" = "10" ]; then click 7801 396 375   # School Base
  elif [ "$pg" = "3" ]; then break
  else sleep 1; click 7801 396 375
  fi
done
sleep 4
timeout 20 $D 7802 "join 127.0.0.1 7777" >/dev/null
for i in $(seq 1 40); do
  s=$(timeout 20 $D 7802 status | tail -1)
  case "$s" in *clientInWorld=true*) break;; esac
  sleep 4
done
timeout 20 $D 7801 status | tail -1
timeout 20 $D 7802 status | tail -1
timeout 10 $D 7801 "god 1" >/dev/null; timeout 10 $D 7802 "god 1" >/dev/null
