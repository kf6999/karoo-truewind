#!/usr/bin/env bash
# Copyright 2026 TrueWind contributors
# SPDX-License-Identifier: Apache-2.0
#
# Shared helpers for the TrueWind device scripts (TESTING.md sections 4-5). Source, don't run.
#
# Device selection: ANDROID_SERIAL if set (USB serial or <ip>:5555 for Wi-Fi adb), else the only
# attached device.

set -euo pipefail

PKG="${TRUEWIND_PKG:-io.github.kf6999.truewind}"
RECEIVER="$PKG/de.timklge.karooheadwind.debug.DebugReceiver"
ACTION_PREFIX="io.github.kf6999.truewind.debug."
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${TRUEWIND_APK:-$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk}"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

pick_device() {
  if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    local devices
    devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
    [[ -n "$devices" ]] || die "no adb device attached"
    [[ $(wc -l <<<"$devices") -eq 1 ]] || die "several devices attached; set ANDROID_SERIAL: $(tr '\n' ' ' <<<"$devices")"
    ANDROID_SERIAL="$devices"
  fi
  export ANDROID_SERIAL
  adb get-state >/dev/null 2>&1 || die "device $ANDROID_SERIAL not reachable"
}

sh_() { adb shell "$@"; }

app_pid() { adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true; }

# dbg ACTION [am extras...]  -> broadcasts to the debug receiver, fails unless it answers ok.
dbg() {
  local action="$1"; shift
  local out
  out=$(adb shell am broadcast -n "$RECEIVER" -a "${ACTION_PREFIX}${action}" "$@" 2>&1 | tr -d '\r')
  grep -q 'data="ok' <<<"$out" || die "debug action $action failed: $out (is the DEBUG build installed?)"
}

# pull_file <path relative to the app's files dir> <local file>
pull_file() { adb exec-out run-as "$PKG" cat "files/$1" > "$2"; }

diag() { dbg DIAG_DUMP; pull_file diagnostics.json "$1"; }

# json_get FILE python-expression-on-d
json_get() { python3 -I -c "import json,sys; d=json.load(open(sys.argv[1])); print($2)" "$1"; }

ensure_welcome_accepted() {
  # Launch the main screen; if the welcome dialog shows, press its OK button (found via uiautomator).
  adb shell am start -W -n "$PKG/de.timklge.karooheadwind.MainActivity" >/dev/null
  sleep 2
  adb shell uiautomator dump /sdcard/truewind-ui.xml >/dev/null 2>&1 || true
  local xml
  xml=$(adb exec-out cat /sdcard/truewind-ui.xml 2>/dev/null || true)
  adb shell rm -f /sdcard/truewind-ui.xml
  if grep -q 'Welcome to TrueWind' <<<"$xml"; then
    local bounds
    bounds=$(python3 -I -c '
import re,sys
x=sys.stdin.read()
for m in re.finditer(r"<node [^>]*>", x):
    n=m.group(0)
    if re.search(r"text=\"OK\"", n):
        b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n).groups()
        print((int(b[0])+int(b[2]))//2, (int(b[1])+int(b[3]))//2); break
' <<<"$xml")
    [[ -n "$bounds" ]] || die "welcome dialog shown but no OK button found"
    log "accepting welcome dialog at $bounds"
    # shellcheck disable=SC2086
    adb shell input tap $bounds
    sleep 1
  else
    log "welcome dialog already accepted"
  fi
  adb shell input keyevent KEYCODE_BACK || true
}
