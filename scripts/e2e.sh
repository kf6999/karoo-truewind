#!/usr/bin/env bash
# Copyright 2026 TrueWind contributors
# SPDX-License-Identifier: Apache-2.0
#
# End-to-end check (SPEC 9.4), in order, stopping at the first failure:
#   1. ./gradlew build                      (compile, unit tests, lint, debug + release APKs)
#   2. replay gate on corpus/fixtures       (metrics.json + summary; --baseline gate if present)
#   3. live-smoke                           (one live request per provider through the core classes;
#                                            Synoptic / Tomorrow.io only if their keys are in the env)
#   4. device_smoke.sh with all fields      (install, replay mode, every field has a value, fetch OK)
#   5. FIT developer-field check            (only with --fit-check; needs a ride recorded by hand)
#   6. crash check over the whole session   (logcat FATAL/ANR for the app since step 4)
#
#   scripts/e2e.sh [--skip-build] [--skip-live] [--skip-device] [--minutes 3] [--fit-check latest|PATH]
#                  [--keys ~/.config/truewind/keys.env] [--point LAT,LON] [--out DIR]
#
# Keys: --keys FILE is sourced into the environment of the live-smoke step only; values are
# never echoed. GRADLE_ARGS is passed to every gradle call (e.g. an --init-script).

source "$(dirname "$0")/lib.sh"

SKIP_BUILD=0; SKIP_LIVE=0; SKIP_DEVICE=0; MINUTES=3; FIT_CHECK=""; KEYS=""; POINT="37.62,-122.37"
OUT="$REPO_ROOT/build/e2e-$(date +%Y%m%d-%H%M%S)"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1 ;;
    --skip-live) SKIP_LIVE=1 ;;
    --skip-device) SKIP_DEVICE=1 ;;
    --minutes) MINUTES="$2"; shift ;;
    --fit-check) FIT_CHECK="$2"; shift ;;
    --keys) KEYS="$2"; shift ;;
    --point) POINT="$2"; shift ;;
    --out) OUT="$2"; shift ;;
    -h|--help) sed -n '4,18p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
  shift
done
mkdir -p "$OUT"
cd "$REPO_ROOT"
gradle() { ./gradlew ${GRADLE_ARGS:-} "$@"; }
step() { log "=== $*"; }

if [[ $SKIP_BUILD -eq 0 ]]; then
  step "1/6 build"
  gradle build > "$OUT/build.log" 2>&1 || { tail -40 "$OUT/build.log"; die "build failed (log $OUT/build.log)"; }
  grep -E "BUILD SUCCESSFUL" "$OUT/build.log" >&2 || true
fi

step "2/6 replay gate"
BASELINE=()
[[ -f docs/accuracy-baseline.json ]] && BASELINE=(--baseline docs/accuracy-baseline.json)
gradle -q :core:replay --args="run --corpus corpus/fixtures --out $OUT/metrics.json --summary $OUT/summary.md ${BASELINE[*]:-}" \
  > "$OUT/replay.log" 2>&1 || { tail -30 "$OUT/replay.log" >&2; die "replay gate failed"; }
cat "$OUT/summary.md" >&2

if [[ $SKIP_LIVE -eq 0 ]]; then
  step "3/6 live provider smoke"
  (
    if [[ -n "$KEYS" ]]; then set -a; # shellcheck disable=SC1090
      . "$KEYS"; set +a; fi
    gradle -q :core:replay --args="live-smoke --point $POINT"
  ) > "$OUT/live-smoke.json" 2> "$OUT/live-smoke.log" || { cat "$OUT/live-smoke.log" >&2; die "a keyless provider failed"; }
  grep '^live-smoke:' "$OUT/live-smoke.log" >&2 || true
fi

if [[ $SKIP_DEVICE -eq 0 ]]; then
  pick_device
  step "4/6 device smoke (all fields)"
  adb logcat -c || true
  SMOKE_ARGS=(--minutes "$MINUTES" --out "$OUT/device")
  [[ $SKIP_BUILD -eq 1 ]] || SMOKE_ARGS+=(--no-build)
  step "5/6 FIT developer fields ${FIT_CHECK:-(skipped: no --fit-check)}"
  if [[ -n "$FIT_CHECK" ]]; then SMOKE_ARGS+=(--fit-check "$FIT_CHECK"); fi
  "$(dirname "$0")/device_smoke.sh" "${SMOKE_ARGS[@]}" || die "device smoke failed"

  step "6/6 crash check"
  adb logcat -d > "$OUT/logcat-final.txt" || true
  crashes=$(grep -E "FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat-final.txt" | grep -c . || true)
  [[ $crashes -eq 0 && -n "$(app_pid)" ]] || die "crash/ANR lines=$crashes or app not running"
  log "no crash/ANR, app pid $(app_pid)"
fi

log "E2E PASSED; artefacts in $OUT"
