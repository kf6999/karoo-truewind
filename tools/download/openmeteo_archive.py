#!/usr/bin/env python3
# Copyright 2026 TrueWind contributors
# SPDX-License-Identifier: Apache-2.0
"""Download archived Open-Meteo forecasts ("what the models said at the time") for a replay corpus.

One file per model, <out-dir>/<model>.json, holding the raw API responses for a grid of points
covering --bbox (plus optional extra points such as station locations). The replay harness parses
these directly (core/replay/cli/CorpusLoader.parseOpenMeteo).

APIs (keyless free tier, non-commercial, CC BY 4.0; verified live 2026-11-15):
  historical-forecast  https://historical-forecast-api.open-meteo.com/v1/forecast
      Continuous archive stitched from the first hours of each model run. Served per model with
      `models=`; hourly AND minutely_15 work (HRRR has native 15-min; ECMWF/NBM 15-min values are
      interpolated from hourly by the API).
  previous-runs        https://previous-runs-api.open-meteo.com/v1/forecast
      Same, but adds `<var>_previous_dayN` series (forecast issued N days earlier), hourly only.
      Use --api previous-runs --previous-day N to replay a longer forecast lead time.

Example:
  tools/download/openmeteo_archive.py --bbox 37.4,-122.5,37.9,-122.0 --step 0.05 \
      --extra-points corpus/x/observations/iem.csv --start-date 2026-11-10 --end-date 2026-11-10 \
      --models best_match,ncep_hrrr_conus,ncep_nbm_conus,ecmwf_ifs,gfs_seamless --out-dir corpus/x/forecasts
"""
import argparse
import csv
import datetime as dt
import gzip
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

APIS = {
    "historical-forecast": "https://historical-forecast-api.open-meteo.com/v1/forecast",
    "previous-runs": "https://previous-runs-api.open-meteo.com/v1/forecast",
}
UA = {"User-Agent": "truewind-replay-downloader/1.0"}
VARS = "wind_speed_10m,wind_direction_10m,wind_gusts_10m"


def fetch(url, retries=4):
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            body = e.read()[:300]
            if e.code == 429 or e.code >= 500:
                wait = 30 * (attempt + 1)
                print(f"HTTP {e.code}, waiting {wait}s: {body!r}", file=sys.stderr)
                time.sleep(wait)
                continue
            raise SystemExit(f"HTTP {e.code} for {url}\n{body!r}")
        except Exception as e:  # noqa: BLE001
            if attempt == retries - 1:
                raise
            print(f"retry {attempt + 1}: {e}", file=sys.stderr)
            time.sleep(5)
    raise SystemExit("giving up")


def iso_s(s):
    t = dt.datetime.fromisoformat(s.replace("Z", "+00:00"))
    if t.tzinfo is None:
        t = t.replace(tzinfo=dt.timezone.utc)
    return int(t.timestamp())


def trim(o, lo, hi):
    """Drops time steps outside [lo, hi] (unix seconds) in every block of one response object."""
    if lo is None and hi is None:
        return
    for block in ("hourly", "minutely_15"):
        b = o.get(block)
        if not b or "time" not in b:
            continue
        keep = [i for i, t in enumerate(b["time"]) if (lo is None or t >= lo) and (hi is None or t <= hi)]
        for k in list(b):
            b[k] = [b[k][i] for i in keep]


def grid(bbox, step):
    lat0, lon0, lat1, lon1 = bbox
    pts = []
    i = 0
    while lat0 + i * step <= lat1 + 1e-9:
        j = 0
        while lon0 + j * step <= lon1 + 1e-9:
            pts.append((round(lat0 + i * step, 4), round(lon0 + j * step, 4)))
            j += 1
        i += 1
    return pts


def extra_points(path):
    pts = set()
    with open(path) as f:
        for r in csv.DictReader(f):
            pts.add((round(float(r["lat"]), 4), round(float(r["lon"]), 4)))
    return sorted(pts)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bbox", required=True, help="lat_min,lon_min,lat_max,lon_max")
    ap.add_argument("--step", type=float, default=0.05, help="grid spacing, degrees (default 0.05 ~ 5 km)")
    ap.add_argument("--extra-points", action="append", default=[], help="CSV with lat,lon columns (e.g. observations)")
    ap.add_argument("--start-date", required=True)
    ap.add_argument("--end-date", required=True)
    ap.add_argument("--models", default="best_match,ncep_hrrr_conus,ncep_nbm_conus,ecmwf_ifs,gfs_seamless")
    ap.add_argument("--api", choices=sorted(APIS), default="historical-forecast")
    ap.add_argument("--previous-day", type=int, default=0, help="previous-runs only: lead in days (1..7)")
    ap.add_argument("--no-15min", action="store_true", help="hourly only")
    ap.add_argument("--batch", type=int, default=40, help="coordinates per request")
    ap.add_argument("--trim-start", help="ISO UTC time; drop steps before it (keeps fixtures small)")
    ap.add_argument("--trim-end", help="ISO UTC time; drop steps after it")
    ap.add_argument("--gzip", action="store_true", help="write <model>.json.gz")
    ap.add_argument("--out-dir", required=True)
    a = ap.parse_args()
    t_lo = iso_s(a.trim_start) if a.trim_start else None
    t_hi = iso_s(a.trim_end) if a.trim_end else None

    pts = grid([float(x) for x in a.bbox.split(",")], a.step)
    for p in a.extra_points:
        pts += extra_points(p)
    pts = sorted(set(pts))
    os.makedirs(a.out_dir, exist_ok=True)
    print(f"{len(pts)} points x {len(a.models.split(','))} models", file=sys.stderr)

    for model in a.models.split(","):
        model = model.strip()
        out = []
        for k in range(0, len(pts), a.batch):
            chunk = pts[k:k + a.batch]
            q = {
                "latitude": ",".join(str(p[0]) for p in chunk),
                "longitude": ",".join(str(p[1]) for p in chunk),
                "start_date": a.start_date, "end_date": a.end_date,
                "models": model, "wind_speed_unit": "ms", "timeformat": "unixtime",
            }
            if a.api == "previous-runs" and a.previous_day > 0:
                d = a.previous_day
                q["hourly"] = f"wind_speed_10m_previous_day{d},wind_direction_10m_previous_day{d},wind_gusts_10m_previous_day{d}"
            else:
                q["hourly"] = VARS
                if not a.no_15min and a.api == "historical-forecast":
                    q["minutely_15"] = VARS
            data = fetch(f"{APIS[a.api]}?{urllib.parse.urlencode(q)}")
            data = data if isinstance(data, list) else [data]
            if a.api == "previous-runs" and a.previous_day > 0:
                # Rename *_previous_dayN to the plain names so the harness reads them as this model.
                for o in data:
                    h = o.get("hourly", {})
                    for key in list(h):
                        if key.endswith(f"_previous_day{a.previous_day}"):
                            h[key.replace(f"_previous_day{a.previous_day}", "")] = h.pop(key)
            for o in data:
                trim(o, t_lo, t_hi)
            out += data
            time.sleep(0.5)
        nulls = sum(1 for o in out for v in o.get("hourly", {}).get("wind_speed_10m", []) if v is None)
        path = os.path.join(a.out_dir, f"{model}.json" + (".gz" if a.gzip else ""))
        opener = gzip.open if a.gzip else open
        with opener(path, "wt") as f:
            json.dump(out, f, separators=(",", ":"))
        print(f"{model}: {len(out)} locations, {os.path.getsize(path)} bytes, {nulls} null hourly values -> {path}", file=sys.stderr)


if __name__ == "__main__":
    main()
