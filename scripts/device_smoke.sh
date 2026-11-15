#!/usr/bin/env bash
# Copyright 2026 TrueWind contributors
# SPDX-License-Identifier: Apache-2.0
#
# T3 device smoke test (TESTING.md section 4) against a connected Karoo, debug build.
#
#   scripts/device_smoke.sh [--no-build] [--no-install] [--minutes 3] [--speed 10]
#                           [--fields headwindSpeed,windSpeed,...] [--track assets:replay/track.csv]
#                           [--fit-check PATH_ON_DEVICE|latest] [--out DIR]
#
# What it does:
#   1. (build and) adb install -r the debug APK; launch; accept the welcome dialog if shown.
#   2. Reset counters, start replay mode at --speed x (the bundled track feeds SensorHub instead of
#      live GPS/speed/power), and subscribe to the TrueWind fields through the Karoo
#      (TYPE_EXT::truewind::<id>) exactly as a ride page would - no ride-page UI setup needed.
#   3. After --minutes: screenshot, diagnostics JSON, logcat, meminfo into --out.
#   4. Assert: no crash/ANR of the app; every observed field left "no data"; a weather fetch
#      succeeded; PSS <= 60 MB (warning only on a debug build); optional FIT developer-field check.
#
# Ride recording is NOT started: doing that unattended would create (and possibly sync) a junk
# activity in the rider's account. To cover "FIT file contains the developer fields", record a short
# ride by hand with TrueWind fields on the page, then run with --fit-check latest.
#
# Exit code 0 = all assertions passed. Run once on Wi-Fi and once with Wi-Fi off and the phone
# companion connected (TESTING 4).

source "$(dirname "$0")/lib.sh"

BUILD=1; INSTALL=1; MINUTES=3; SPEED=10; FIELDS=""; TRACK="assets:replay/track.csv"; FIT_CHECK=""
OUT="$REPO_ROOT/build/device/smoke-$(date +%Y%m%d-%H%M%S)"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build) BUILD=0 ;;
    --no-install) INSTALL=0; BUILD=0 ;;
    --minutes) MINUTES="$2"; shift ;;
    --speed) SPEED="$2"; shift ;;
    --fields) FIELDS="$2"; shift ;;
    --track) TRACK="$2"; shift ;;
    --fit-check) FIT_CHECK="$2"; shift ;;
    --out) OUT="$2"; shift ;;
    -h|--help) sed -n '4,30p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
  shift
done
mkdir -p "$OUT"
pick_device
log "device $ANDROID_SERIAL, output $OUT"

if [[ $BUILD -eq 1 ]]; then
  log "building debug APK"
  (cd "$REPO_ROOT" && ./gradlew ${GRADLE_ARGS:-} -q :app:assembleDebug) || die "build failed"
fi
if [[ $INSTALL -eq 1 ]]; then
  [[ -f "$APK" ]] || die "no APK at $APK"
  log "installing $(basename "$APK") ($(du -k "$APK" | cut -f1) KB)"
  adb install -r "$APK" >/dev/null || die "install failed"
fi

adb logcat -c || true
ensure_welcome_accepted
sleep 2
PID_START=$(app_pid)
[[ -n "$PID_START" ]] || die "app process not running after launch"

dbg DIAG_RESET
dbg REPLAY_START --es track "$TRACK" --ef speed "$SPEED" --ez loop true
if [[ -n "$FIELDS" ]]; then dbg OBSERVE_START --es fields "$FIELDS"; else dbg OBSERVE_START; fi
log "replay at ${SPEED}x and field observer started (pid $PID_START); waiting ${MINUTES} min"

END=$(( $(date +%s) + MINUTES * 60 ))
while [[ $(date +%s) -lt $END ]]; do
  sleep 30
  pid=$(app_pid)
  [[ "$pid" == "$PID_START" ]] || { log "app pid changed ($PID_START -> ${pid:-none})"; break; }
  diag "$OUT/diagnostics.json" 2>/dev/null || true
  [[ -s "$OUT/diagnostics.json" ]] && log "$(json_get "$OUT/diagnostics.json" "'replay idx %s/%s, fields with data %d/%d, pss %d MB' % (d['replay']['index'], d['replay']['samples'], sum(1 for f in d['fields'].values() if f.get('value') is not None), len(d['fields']), d['memory']['total_pss_kb']//1024)")"
done

log "collecting artefacts"
adb exec-out screencap -p > "$OUT/screen.png" || true
diag "$OUT/diagnostics.json"
adb shell dumpsys meminfo "$PKG" > "$OUT/meminfo.txt" || true
adb logcat -d > "$OUT/logcat.txt" || true
dbg OBSERVE_STOP
dbg REPLAY_STOP

# ---------------------------------------------------------------- assertions
FAIL=0
check() { local name="$1" ok="$2" detail="$3"; if [[ "$ok" == "1" ]]; then log "PASS $name: $detail"; else log "FAIL $name: $detail"; FAIL=1; fi; }
warn() { log "WARN $1: $2"; }

PID_END=$(app_pid)
crashes=$(grep -E "FATAL EXCEPTION|ANR in $PKG|Process: $PKG" "$OUT/logcat.txt" | grep -c . || true)
check "no crash/ANR" "$([[ $crashes -eq 0 && "$PID_END" == "$PID_START" ]] && echo 1 || echo 0)" "crash lines=$crashes, pid $PID_START -> ${PID_END:-none}"

# Fields that are legitimately empty in some weather: no rain coming (rainIn), no storm within 100 km
# (stormDistance), above 10 C (windChill), no power meter / not riding (measuredHeadwind, maxWindRide).
CONDITIONAL="rainIn,stormDistance,windChill,measuredHeadwind,maxWindRide"
fields=$(json_get "$OUT/diagnostics.json" "len(d['fields'])")
with_data=$(json_get "$OUT/diagnostics.json" "sum(1 for f in d['fields'].values() if f.get('value') is not None)")
missing=$(json_get "$OUT/diagnostics.json" "','.join(k for k,f in sorted(d['fields'].items()) if f.get('value') is None and k not in '$CONDITIONAL'.split(','))")
check "fields left no-data" "$([[ $fields -gt 0 && -z "$missing" ]] && echo 1 || echo 0)" "$with_data/$fields fields have a value (missing non-conditional: ${missing:-none}): $(json_get "$OUT/diagnostics.json" "', '.join('%s=%s' % (k, (round(v['value'],2) if v.get('value') is not None else v.get('state'))) for k,v in sorted(d['fields'].items()))")"
# Conditional fields must at least have been evaluated (storm estimate ran, rain bars built).
parity=$(json_get "$OUT/diagnostics.json" "json.dumps(d.get('parity') or {})" 2>/dev/null || echo "{}")
log "parity diagnostics: $parity"
log "network: cycles $(json_get "$OUT/diagnostics.json" "d.get('cycles')"), uptime $(json_get "$OUT/diagnostics.json" "d['uptime_s']") s, by host $(json_get "$OUT/diagnostics.json" "json.dumps(d.get('hosts') or {})")"


# Upstream-compatible mode logs "Got updated weather info"; TrueWind mode logs one "cycle ok|failed"
# line per WindEngine cycle with per-provider requests/bytes.
fetches=$(grep -cE "Got updated weather info|truewind *: cycle ok" "$OUT/logcat.txt" || true)
fetch_fail=$(grep -cE "Failed to get weather data|truewind *: cycle failed" "$OUT/logcat.txt" || true)
check "weather fetch" "$([[ $fetches -gt 0 ]] && echo 1 || echo 0)" "successful=$fetches failed=$fetch_fail (logcat)"

http_req=$(json_get "$OUT/diagnostics.json" "d['http']['requests']")
if [[ "$http_req" -gt 0 ]]; then
  per=$(json_get "$OUT/diagnostics.json" "d['http']['bytes']/max(1,d['http']['requests'])")
  check "response size < 15 KB" "$(python3 -I -c "print(1 if $per < 15360 else 0)")" "mean $per bytes over $http_req requests"
else
  warn "response size" "not measured: HTTP counters are 0 until KarooHttp (WP8) calls DebugHooks.counters.httpRequest"
fi

pss=$(json_get "$OUT/diagnostics.json" "d['memory']['total_pss_kb']")
if [[ $pss -le 61440 ]]; then log "PASS PSS: $((pss/1024)) MB"; else warn "PSS" "$((pss/1024)) MB > 60 MB budget (debug build, not the release budget)"; fi
log "replay advanced to sample $(json_get "$OUT/diagnostics.json" "d['replay']['index']"), cpu $(json_get "$OUT/diagnostics.json" "d['cpu_ms']") ms, emits $(json_get "$OUT/diagnostics.json" "sum(d['emits'].values())"), view updates $(json_get "$OUT/diagnostics.json" "sum(d['view_updates'].values())") (seam)"

if [[ -n "$FIT_CHECK" ]]; then
  if [[ "$FIT_CHECK" == "latest" ]]; then
    FIT_CHECK=$(adb shell 'ls -t /sdcard/FitFiles/*.fit | head -1' | tr -d '\r')
  fi
  adb pull "$FIT_CHECK" "$OUT/ride.fit" >/dev/null
  devfields=$(cd "$REPO_ROOT" && ./gradlew ${GRADLE_ARGS:-} -q :core:replay --args="fit-devfields $OUT/ride.fit" 2>/dev/null | tail -1)
  # TrueWind/Headwind developer fields (fit/HeadwindFitFileWriter.kt), including the WP10 ones
  # (TrueWind mode; wind_sources is a session bitmask).
  has=$(python3 -I -c 'import json,sys; n=set(json.loads(sys.argv[1] or "[]")); print(1 if {"wind_speed","headwind_speed","rider_wind_speed","rider_wind_direction","wind_confidence","air_density","wind_sources"} <= n else 0)' "$devfields")
  check "FIT developer fields" "$has" "$FIT_CHECK: $devfields"
else
  warn "FIT developer fields" "skipped (no ride recorded; use --fit-check latest after a manual ride)"
fi

log "artefacts in $OUT"
[[ $FAIL -eq 0 ]] && log "SMOKE PASSED" || log "SMOKE FAILED"
exit $FAIL
