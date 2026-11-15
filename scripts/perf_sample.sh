#!/usr/bin/env bash
# Copyright 2026 TrueWind contributors
# SPDX-License-Identifier: Apache-2.0
#
# Samples the extension's CPU, memory and network on the device (TESTING.md 5.1) into a CSV.
#
#   scripts/perf_sample.sh [--interval 60] [--duration 600] [--pkg io.github.kf6999.truewind] [--out FILE]
#
# Columns: wall time, elapsed s, pid, utime+stime ticks (from /proc/<pid>/stat), CPU % of one core
# over the interval, PSS KB (dumpsys meminfo), rx/tx bytes for the app uid and for the whole device
# (dumpsys netstats detail; cumulative, bucketed by the system). The Karoo performs
# MakeHttpRequest in its own service, so the app uid normally shows ~0: compare A/B/C runs on the
# device totals and on the in-app HTTP counters. Last column: lines in dumpsys power naming the
# package (wakelocks; must stay 0).
#
# Works for any package (use --pkg de.timklge.karooheadwind for the upstream B run). Over Wi-Fi adb
# the sampling itself costs a little radio time; keep --interval at 60 s for battery runs.

source "$(dirname "$0")/lib.sh"

INTERVAL=60; DURATION=600; OUT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --interval) INTERVAL="$2"; shift ;;
    --duration) DURATION="$2"; shift ;;
    --pkg) PKG="$2"; shift ;;
    --out) OUT="$2"; shift ;;
    -h|--help) sed -n '4,17p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
  shift
done
pick_device
OUT="${OUT:-$REPO_ROOT/build/device/perf-$(date +%Y%m%d-%H%M%S).csv}"
mkdir -p "$(dirname "$OUT")"

CLK_TCK=$(adb shell getconf CLK_TCK 2>/dev/null | tr -d '\r'); CLK_TCK=${CLK_TCK:-100}
APP_UID=$(adb shell dumpsys package "$PKG" | awk -F'[= ]' '/userId=/{for(i=1;i<=NF;i++) if($i=="userId") {print $(i+1); exit}}' | tr -d '\r')

cpu_ticks() { # pid -> utime+stime
  adb shell cat "/proc/$1/stat" 2>/dev/null | tr -d '\r' | awk '{ sub(/^.*\) /, ""); print $12 + $13 }'
}
pss_kb() { adb shell dumpsys meminfo "$PKG" 2>/dev/null | awk '/TOTAL PSS:/ {print $3; exit} /^ *TOTAL / {print $2; exit}'; }
uid_bytes() { # uid -> "rx tx" summed over all interfaces from dumpsys netstats --uid
  adb shell dumpsys netstats detail 2>/dev/null | tr -d '\r' | awk -v uid="$1" '
    /uid=/ { match($0, /uid=[-0-9]+/); u = substr($0, RSTART+4, RLENGTH-4); cur = (uid == "all" || u == uid) }
    cur && /rb=/ { for (i=1;i<=NF;i++) { if ($i ~ /^rb=/) rx += substr($i,4); if ($i ~ /^tb=/) tx += substr($i,4) } }
    END { printf "%d %d", rx, tx }'
}
wakelocks() { adb shell dumpsys power 2>/dev/null | grep -c "$PKG" || true; }

echo "wall_iso,elapsed_s,pid,cpu_ticks,cpu_pct,pss_kb,app_rx,app_tx,device_rx,device_tx,wakelock_lines" > "$OUT"
log "sampling $PKG (uid $APP_UID) every ${INTERVAL}s for ${DURATION}s -> $OUT"
start=$(date +%s); prev_ticks=""; prev_t=""
while :; do
  now=$(date +%s); el=$((now - start))
  pid=$(app_pid)
  ticks=""; pct=""
  if [[ -n "$pid" ]]; then
    ticks=$(cpu_ticks "$pid")
    if [[ -n "$prev_ticks" && -n "$ticks" && $now -gt $prev_t ]]; then
      pct=$(python3 -I -c "print(round(100.0*($ticks-$prev_ticks)/$CLK_TCK/($now-$prev_t), 3))")
    fi
    prev_ticks=$ticks; prev_t=$now
  fi
  read -r arx atx <<<"$(uid_bytes "$APP_UID")"
  read -r srx stx <<<"$(uid_bytes all)"
  echo "$(date -u +%FT%TZ),$el,${pid:-},${ticks:-},${pct:-},$(pss_kb),$arx,$atx,$srx,$stx,$(wakelocks)" >> "$OUT"
  tail -1 "$OUT" >&2
  [[ $el -ge $DURATION ]] && break
  sleep "$INTERVAL"
done
log "done: $OUT"
