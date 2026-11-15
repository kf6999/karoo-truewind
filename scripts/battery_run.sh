#!/usr/bin/env bash
# Copyright 2026 TrueWind contributors
# SPDX-License-Identifier: Apache-2.0
#
# T4 battery run (TESTING.md 5.2), one configuration per invocation, over Wi-Fi adb.
#
#   scripts/battery_run.sh A|B|C [--hours 3] [--no-wait-unplug] [--speed 1] [--out DIR]
#   scripts/battery_run.sh collect --out DIR     # after a run where Wi-Fi dropped: pull logs only
#   scripts/battery_run.sh report DIR [DIR...]   # drain slopes (least squares, %/h) per run
#
#   A = no weather extension fields on the page, B = upstream Headwind (de.timklge.karooheadwind),
#   C = TrueWind (debug build, so the minute logger and replay mode exist).
#
# ONE-TIME SETUP (USB, then unplug):
#   1. adb -s <serial> tcpip 5555
#   2. adb connect <karoo-ip>:5555        (Karoo Settings > Wi-Fi > network details shows the IP;
#                                          `adb shell ip addr show wlan0` over USB also prints it)
#   3. export ANDROID_SERIAL=<karoo-ip>:5555 ; unplug the USB cable.
#   Mac and Karoo on the same Wi-Fi. Wi-Fi adb stays enabled until the Karoo reboots.
#
# PROCEDURE PER RUN (the script prompts for the manual steps):
#   1. Charge to 100 %, unplug, wait 5 min (the script waits until `dumpsys battery` shows unplugged).
#   2. `dumpsys batterystats --reset`.
#   3. Fixed conditions: brightness 50 % with auto-brightness off (set by hand on the Karoo -
#      this script never changes system settings), same ride profile and page, GPS on at a window,
#      same sensors paired, phone companion connected.
#   4. Start the ride recording by hand on the Karoo, then press Enter. For C the script starts
#      replay mode (--speed 1 = real time, so the virtual sensor sees power/speed) and the per-minute
#      battery logger (files/battery_log.csv in the app).
#   5. Every minute the Mac also samples CPU/PSS/net via perf_sample.sh. After --hours the script
#      collects battery_log.csv, `dumpsys batterystats --charged <pkg>` and `dumpsys netstats`.
#   Order A, C, B, C, A across days; report with `battery_run.sh report build/device/battery-*`.
#
# If the Karoo drops Wi-Fi when a ride starts: start the run, unplug, ride it out, replug, then
#   scripts/battery_run.sh collect --out <the run's DIR>
# (the in-app logger keeps writing without adb; the Mac-side perf CSV will have a gap).

source "$(dirname "$0")/lib.sh"

usage() { sed -n '4,38p' "$0"; exit 0; }
[[ $# -ge 1 ]] || usage
MODE="$1"; shift
HOURS=3; WAIT_UNPLUG=1; SPEED=1; OUT=""
DIRS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --hours) HOURS="$2"; shift ;;
    --no-wait-unplug) WAIT_UNPLUG=0 ;;
    --speed) SPEED="$2"; shift ;;
    --out) OUT="$2"; shift ;;
    -h|--help) usage ;;
    *) DIRS+=("$1") ;;
  esac
  shift
done

report() {
  python3 -I - "$@" <<'EOF'
import csv, glob, os, sys
def slope(xs, ys):
    n = len(xs); mx = sum(xs)/n; my = sum(ys)/n
    sxx = sum((x-mx)**2 for x in xs)
    return sum((x-mx)*(y-my) for x, y in zip(xs, ys))/sxx if sxx else float('nan')
for d in sys.argv[1:]:
    cfg = open(os.path.join(d, "config.txt")).read().strip() if os.path.exists(os.path.join(d, "config.txt")) else "?"
    f = os.path.join(d, "battery_log.csv")
    src = "in-app"
    if not os.path.exists(f):
        f = os.path.join(d, "battery_mac.csv"); src = "mac-side"
    rows = list(csv.DictReader(open(f)))
    if len(rows) < 3:
        print(f"{d}: {cfg}: not enough samples"); continue
    t = [float(r["elapsed_s"])/3600 for r in rows]
    pct = [float(r["battery_pct"]) for r in rows]
    line = f"{d}: config {cfg}, {src}, {len(rows)} samples over {t[-1]-t[0]:.2f} h, drain {-slope(t, pct):.2f} %/h"
    if "charge_counter_uah" in rows[0] and all(r["charge_counter_uah"] not in ("", "-1", "0") for r in rows):
        cc = [float(r["charge_counter_uah"])/1000 for r in rows]
        line += f", {-slope(t, cc):.0f} mAh/h (charge counter)"
    print(line)
print("Report a C-A or C-B difference smaller than the A/A or C/C spread as 'not measurable' (TESTING 5.2).")
EOF
}

collect() {
  local out="$1"
  mkdir -p "$out"
  pick_device
  log "collecting into $out"
  if adb shell pm list packages | grep -q "package:$PKG\$"; then
    adb exec-out run-as "$PKG" cat files/battery_log.csv > "$out/battery_log.csv" 2>/dev/null || rm -f "$out/battery_log.csv"
    dbg BATTERY_LOG_STOP 2>/dev/null || true
    dbg REPLAY_STOP 2>/dev/null || true
    diag "$out/diagnostics.json" 2>/dev/null || true
  fi
  local p; p=$(cat "$out/pkg.txt" 2>/dev/null || echo "$PKG")
  adb shell dumpsys batterystats --charged "$p" > "$out/batterystats.txt" || true
  adb shell dumpsys netstats > "$out/netstats.txt" || true
  adb shell dumpsys battery > "$out/battery_end.txt" || true
  log "done; run: scripts/battery_run.sh report $out"
}

case "$MODE" in
  report) report "${DIRS[@]}"; exit 0 ;;
  collect) [[ -n "$OUT" ]] || die "collect needs --out DIR"; collect "$OUT"; exit 0 ;;
  A|B|C) ;;
  *) die "mode must be A, B, C, collect or report" ;;
esac

pick_device
[[ "$ANDROID_SERIAL" == *:* ]] || log "WARNING: $ANDROID_SERIAL looks like a USB serial; USB power invalidates battery numbers (see header for Wi-Fi adb)"
OUT="${OUT:-$REPO_ROOT/build/device/battery-$MODE-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
echo "$MODE" > "$OUT/config.txt"
case "$MODE" in
  B) echo "de.timklge.karooheadwind" > "$OUT/pkg.txt" ;;
  *) echo "$PKG" > "$OUT/pkg.txt" ;;
esac
adb shell dumpsys battery > "$OUT/battery_start.txt"

if [[ $WAIT_UNPLUG -eq 1 ]]; then
  log "waiting until the Karoo is unplugged and at 100 % (dumpsys battery)"
  until adb shell dumpsys battery | grep -q "USB powered: false" && adb shell dumpsys battery | grep -q "AC powered: false" && adb shell dumpsys battery | grep -q "level: 100"; do sleep 30; done
  log "unplugged at 100 %; settling 5 min"
  sleep 300
fi
adb shell dumpsys batterystats --reset >/dev/null
log "batterystats reset. Set brightness 50 %, auto-brightness off, open the test ride profile/page for config $MODE,"
log "START THE RIDE RECORDING on the Karoo, then press Enter here."
read -r _

if [[ "$MODE" == "C" ]]; then
  adb exec-out run-as "$PKG" rm -f files/battery_log.csv || true
  dbg DIAG_RESET
  dbg REPLAY_START --ef speed "$SPEED" --ez loop true
  dbg BATTERY_LOG_START --el interval_ms 60000
fi

# Mac-side minute samples (battery % for A/B, which have no in-app logger, plus CPU/PSS/net).
(
  echo "elapsed_s,battery_pct,charge_counter_uah" > "$OUT/battery_mac.csv"
  t0=$(date +%s)
  while [[ $(( $(date +%s) - t0 )) -lt $(( HOURS * 3600 )) ]]; do
    b=$(adb shell dumpsys battery 2>/dev/null | tr -d '\r')
    echo "$(( $(date +%s) - t0 )),$(awk '/level:/ {print $2}' <<<"$b"),$(awk '/Charge counter:/ {print $3}' <<<"$b")" >> "$OUT/battery_mac.csv"
    sleep 60
  done
) &
BAT_PID=$!
"$(dirname "$0")/perf_sample.sh" --pkg "$(cat "$OUT/pkg.txt")" --interval 60 --duration $(( HOURS * 3600 )) --out "$OUT/perf.csv" || true
wait "$BAT_PID" || true
log "run time over: stop the ride recording on the Karoo (do not discard if you want the FIT)."
collect "$OUT"
report "$OUT"
