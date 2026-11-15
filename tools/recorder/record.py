#!/usr/bin/env python3
# Copyright 2026 TrueWind contributors
# SPDX-License-Identifier: Apache-2.0
"""Records live, keyless provider responses for the replay corpus (TESTING.md 3.2), and stitches
the snapshots into the corpus format the replay harness reads.

  record.py record --name sfbay --bbox 37.4,-122.5,37.8,-122.1 [--step 0.1] [--every 15] [--once]
                   [--hours 6] [--models ncep_hrrr_conus,ncep_nbm_conus,ecmwf_ifs,gfs_seamless,best_match]
      Every --every minutes writes corpus/cache/<name>/<UTC yyyymmddTHHMMZ>/:
        openmeteo.json   Open-Meteo forecast API, all --models in one request, hourly + minutely_15
                         wind 10 m (m/s, unixtime), for the grid points (as the Karoo would request)
        awc_metar.json   aviationweather.gov METARs in the bbox, last 2 h
        meta.json        request URLs, status, bytes, wall time
      corpus/cache/ is git-ignored. Run it under `caffeinate -i` / tmux for a day.

  record.py stitch --name sfbay [--out corpus/private/sfbay-live]
      Builds <out>/forecasts/<model>.json and <out>/observations/metar.csv from all snapshots. For
      each valid time the value from the latest snapshot issued at or before that time is kept, so
      the stitched series is what a live client would have seen ("freshest forecast"). Add rides
      to <out>/rides/ (FIT or CSV) and run `./gradlew :core:replay --args="--corpus <out>"`.

No keys are used. Snapshots contain no personal data (grid points only).
"""
import argparse
import csv
import datetime as dt
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
UA = {"User-Agent": "truewind-replay-recorder/1.0"}
OM = "https://api.open-meteo.com/v1/forecast"
AWC = "https://aviationweather.gov/api/data/metar"
VARS = "wind_speed_10m,wind_direction_10m,wind_gusts_10m"
KT = 0.514444


def get(url, retries=3):
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            if e.code == 429 or e.code >= 500:
                time.sleep(20 * (attempt + 1))
                continue
            return e.code, e.read()
        except Exception as e:  # noqa: BLE001
            if attempt == retries - 1:
                return 0, str(e).encode()
            time.sleep(5)
    return 0, b"giving up"


def grid(bbox, step):
    lat0, lon0, lat1, lon1 = bbox
    pts, i = [], 0
    while lat0 + i * step <= lat1 + 1e-9:
        j = 0
        while lon0 + j * step <= lon1 + 1e-9:
            pts.append((round(lat0 + i * step, 4), round(lon0 + j * step, 4)))
            j += 1
        i += 1
    return pts


def snapshot(a, bbox):
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%MZ")
    d = os.path.join(ROOT, "corpus", "cache", a.name, stamp)
    os.makedirs(d, exist_ok=True)
    pts = grid(bbox, a.step)
    meta = {"issued_unix": int(time.time()), "bbox": bbox, "points": len(pts), "requests": []}
    q = {
        "latitude": ",".join(str(p[0]) for p in pts), "longitude": ",".join(str(p[1]) for p in pts),
        "models": a.models, "hourly": VARS, "minutely_15": VARS, "current": VARS,
        "wind_speed_unit": "ms", "timeformat": "unixtime", "forecast_hours": a.hours, "past_hours": 1,
        "forecast_minutely_15": a.hours * 4, "past_minutely_15": 4,
    }
    for fname, url in (
        ("openmeteo.json", f"{OM}?{urllib.parse.urlencode(q)}"),
        ("awc_metar.json", f"{AWC}?{urllib.parse.urlencode({'bbox': f'{bbox[0]},{bbox[1]},{bbox[2]},{bbox[3]}', 'format': 'json', 'hours': 2})}"),
    ):
        t0 = time.time()
        status, body = get(url)
        with open(os.path.join(d, fname), "wb") as f:
            f.write(body)
        meta["requests"].append({"file": fname, "url": url, "status": status, "bytes": len(body), "ms": int((time.time() - t0) * 1000)})
        print(f"{stamp} {fname}: HTTP {status}, {len(body)} bytes", file=sys.stderr)
    with open(os.path.join(d, "meta.json"), "w") as f:
        json.dump(meta, f, indent=1)
    return d


def record(a):
    bbox = [float(x) for x in a.bbox.split(",")]
    while True:
        start = time.time()
        snapshot(a, bbox)
        if a.once:
            return
        time.sleep(max(0, a.every * 60 - (time.time() - start)))


def stitch(a):
    src = os.path.join(ROOT, "corpus", "cache", a.name)
    out = a.out or os.path.join(ROOT, "corpus", "private", f"{a.name}-live")
    snaps = sorted(s for s in os.listdir(src) if os.path.isdir(os.path.join(src, s)))
    if not snaps:
        raise SystemExit(f"no snapshots in {src}")
    # series[model][(lat,lon)][block][t] = (issued, speed, dir, gust)
    series = {}
    metars = {}
    for s in snaps:
        d = os.path.join(src, s)
        try:
            issued = json.load(open(os.path.join(d, "meta.json")))["issued_unix"]
            om = json.load(open(os.path.join(d, "openmeteo.json")))
        except (OSError, ValueError, KeyError) as e:
            print(f"skip {s}: {e}", file=sys.stderr)
            continue
        for loc in om if isinstance(om, list) else [om]:
            key = (round(loc["latitude"], 4), round(loc["longitude"], 4))
            for block in ("hourly", "minutely_15"):
                b = loc.get(block) or {}
                for k in b:
                    if not k.startswith("wind_speed_10m"):
                        continue
                    model = k[len("wind_speed_10m"):].lstrip("_") or a.models.split(",")[0]
                    suffix = k[len("wind_speed_10m"):]
                    dirs, gusts = b.get("wind_direction_10m" + suffix, []), b.get("wind_gusts_10m" + suffix, [])
                    slot = series.setdefault(model, {}).setdefault(key, {}).setdefault(block, {})
                    for i, t in enumerate(b["time"]):
                        if b[k][i] is None or (t < issued - 3600):
                            continue
                        prev = slot.get(t)
                        # freshest forecast issued at or before the valid time wins; else the earliest issue after it
                        better = prev is None or (issued <= t and (prev[0] > t or issued > prev[0])) or (prev[0] > t and issued < prev[0])
                        if better:
                            slot[t] = (issued, b[k][i], dirs[i] if i < len(dirs) else None, gusts[i] if i < len(gusts) else None)
        try:
            for m in json.load(open(os.path.join(d, "awc_metar.json"))):
                wdir = m.get("wdir")
                if m.get("wspd") is None or m.get("obsTime") is None:
                    continue
                metars[(m["icaoId"], m["obsTime"])] = [
                    m["icaoId"], "awc_metar", m["lat"], m["lon"], m.get("elev", ""), int(m["obsTime"]) * 1000,
                    round(m["wspd"] * KT, 2), wdir if isinstance(wdir, (int, float)) else "",
                    round(m["wgst"] * KT, 2) if m.get("wgst") else "", 10,
                ]
        except (OSError, ValueError) as e:
            print(f"metar {s}: {e}", file=sys.stderr)

    os.makedirs(os.path.join(out, "forecasts"), exist_ok=True)
    os.makedirs(os.path.join(out, "observations"), exist_ok=True)
    os.makedirs(os.path.join(out, "rides"), exist_ok=True)
    for model, locs in series.items():
        objs = []
        for (lat, lon), blocks in sorted(locs.items()):
            o = {"latitude": lat, "longitude": lon}
            for block, ts in blocks.items():
                times = sorted(ts)
                o[block] = {
                    "time": times,
                    "wind_speed_10m": [ts[t][1] for t in times],
                    "wind_direction_10m": [ts[t][2] for t in times],
                    "wind_gusts_10m": [ts[t][3] for t in times],
                }
            objs.append(o)
        with open(os.path.join(out, "forecasts", f"{model}.json"), "w") as f:
            json.dump(objs, f, separators=(",", ":"))
        print(f"{model}: {len(objs)} points", file=sys.stderr)
    with open(os.path.join(out, "observations", "metar.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["station", "source", "lat", "lon", "elev_m", "time_ms", "speed_mps", "dir_deg", "gust_mps", "sensor_height_m"])
        for k in sorted(metars, key=lambda k: (k[1], k[0])):
            w.writerow(metars[k])
    print(f"{len(snaps)} snapshots, {len(metars)} METARs -> {out}", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("record")
    r.add_argument("--name", required=True)
    r.add_argument("--bbox", required=True, help="lat_min,lon_min,lat_max,lon_max")
    r.add_argument("--step", type=float, default=0.1)
    r.add_argument("--every", type=float, default=15, help="minutes between snapshots")
    r.add_argument("--hours", type=int, default=6, help="forecast horizon per snapshot")
    r.add_argument("--models", default="best_match,ncep_hrrr_conus,ncep_nbm_conus,ecmwf_ifs,gfs_seamless")
    r.add_argument("--once", action="store_true")
    s = sub.add_parser("stitch")
    s.add_argument("--name", required=True)
    s.add_argument("--out")
    s.add_argument("--models", default="best_match", help="model name for un-suffixed keys (single-model snapshots)")
    a = ap.parse_args()
    record(a) if a.cmd == "record" else stitch(a)


if __name__ == "__main__":
    main()
