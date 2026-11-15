#!/usr/bin/env python3
# Copyright 2026 TrueWind contributors
# SPDX-License-Identifier: Apache-2.0
"""Fills one real-ride corpus directory with archived observations and forecasts.

Input: <dir>/work/track.csv (from `./gradlew :core:replay --args="fit2csv rides/X.fit work/track.csv"`).
Output (all git-ignored under corpus/private/):
  <dir>/observations/iem.csv        IEM ASOS/AWOS 5-minute HFMETAR + routine/special METAR for every
                                    station within --obs-km of the track, ride window -1 h .. +1 h
  <dir>/forecasts/<model>.json.gz   Open-Meteo historical-forecast archive (hourly + minutely_15) at
                                    track points every --track-km plus every station within
                                    --fc-station-km of the track, trimmed to the ride window
  <dir>/work/meta.json              counts (no coordinates are printed)

Throttling: Open-Meteo counts each coordinate of a multi-location request as one call (free tier
600/min, 5000/h, 10000/day). A ledger in corpus/cache/openmeteo_ledger.json keeps the per-minute,
per-hour and per-day totals across runs and sleeps before a request would exceed --max-per-min /
--max-per-hour / --max-per-day. Existing outputs are reused (delete them to refetch).

    tools/download/build_ride_corpus.py corpus/private/real/heldout/<id> [...more dirs]
"""
import argparse
import csv
import datetime as dt
import gzip
import json
import math
import os
import sys
import time
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import iem_asos  # noqa: E402
import openmeteo_archive  # noqa: E402

MODELS = "best_match,ncep_hrrr_conus,ncep_nbm_conus,ecmwf_ifs,gfs_global"
NETWORKS = ["CA_ASOS"]
KT = 0.514444


def km(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371 * math.asin(min(1.0, math.sqrt(h)))


def read_track(path):
    pts = []
    with open(path) as f:
        for r in csv.DictReader(f):
            pts.append((int(r["time_ms"]), float(r["lat"]), float(r["lon"])))
    return pts


def thin(track, every_km):
    out = [track[0][1:]]
    for _, lat, lon in track:
        if km(out[-1], (lat, lon)) >= every_km:
            out.append((lat, lon))
    if km(out[-1], track[-1][1:]) > 0.5:
        out.append(track[-1][1:])
    return out


class Ledger:
    def __init__(self, path, per_min, per_hour, per_day):
        self.path, self.limits = path, ((60, per_min), (3600, per_hour), (86400, per_day))
        try:
            self.events = json.load(open(path))
        except Exception:  # noqa: BLE001
            self.events = []

    def wait_for(self, n):
        while True:
            now = time.time()
            self.events = [e for e in self.events if now - e[0] < 86400]
            waits = []
            for span, lim in self.limits:
                recent = [e for e in self.events if now - e[0] < span]
                if sum(e[1] for e in recent) + n > lim:
                    # wait until enough old events expire
                    acc = sum(e[1] for e in recent) + n - lim
                    for t, c in sorted(recent):
                        acc -= c
                        if acc <= 0:
                            waits.append(t + span - now + 1)
                            break
            if not waits:
                return
            w = max(waits)
            print(f"  rate limit: sleeping {w:.0f}s", file=sys.stderr)
            time.sleep(min(w, 600))

    def add(self, n):
        self.events.append((time.time(), n))
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        json.dump(self.events, open(self.path, "w"))


def stations_meta(cache_dir):
    path = os.path.join(cache_dir, "iem_networks.json")
    if os.path.exists(path):
        return {k: tuple(v) for k, v in json.load(open(path)).items()}
    meta = iem_asos.network_stations(NETWORKS)
    os.makedirs(cache_dir, exist_ok=True)
    json.dump(meta, open(path, "w"))
    return meta


def obs(d, track, meta, a):
    out = os.path.join(d, "observations", "iem.csv")
    t0 = dt.datetime.fromtimestamp(track[0][0] / 1000 - 3600, dt.timezone.utc)
    t1 = dt.datetime.fromtimestamp(track[-1][0] / 1000 + 3600, dt.timezone.utc)
    pts = thin(track, 2.0)
    near = {sid: m for sid, m in meta.items() if min(km((m[0], m[1]), p) for p in pts) <= a.obs_km}
    if os.path.exists(out):
        return near, sum(1 for _ in open(out)) - 1
    rows = []
    sids = sorted(near)
    for k in range(0, len(sids), 20):
        for rt, tag in ((["1"], "iem-5min"), (["3", "4"], "iem-metar")):
            for r in iem_asos.rows_metar(sids[k:k + 20], t0, t1, rt):
                rows.append((tag,) + tuple(r))
            time.sleep(1)
    # Keep 5-minute reports; add a routine/special METAR only where no 5-minute report lies within 3 min.
    five = {}
    for r in rows:
        if r[0] == "iem-5min":
            five.setdefault(r[1], []).append(r[5].timestamp())
    kept = []
    for r in rows:
        tag, sid, lat, lon, elev, t, sknt, drct, gust = r
        if lat is None or lon is None or sknt is None:
            continue
        if tag == "iem-metar" and any(abs(t.timestamp() - x) <= 180 for x in five.get(sid, [])):
            continue
        kept.append(r)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["station", "source", "lat", "lon", "elev_m", "time_ms", "speed_mps", "dir_deg", "gust_mps", "sensor_height_m"])
        for tag, sid, lat, lon, elev, t, sknt, drct, gust in sorted(kept, key=lambda r: (r[1], r[5])):
            d_ = "" if drct is None or (sknt > 0 and drct == 0) else f"{drct:.0f}"
            w.writerow([sid, tag, f"{lat:.4f}", f"{lon:.4f}", "" if elev is None else f"{elev:.1f}", int(t.timestamp() * 1000),
                        f"{sknt * KT:.2f}", d_, "" if gust is None else f"{gust * KT:.2f}", "10"])
    return near, len(kept)


def forecasts(d, track, near, ledger, a):
    fdir = os.path.join(d, "forecasts")
    os.makedirs(fdir, exist_ok=True)
    trk = thin(track, a.track_km)
    pts = list(trk)
    for sid, m in near.items():
        if min(km((m[0], m[1]), p) for p in trk) <= a.fc_station_km:
            pts.append((m[0], m[1]))
    pts = sorted({(round(p[0], 4), round(p[1], 4)) for p in pts})
    lo = track[0][0] // 1000 - 3 * 3600
    hi = track[-1][0] // 1000 + 5 * 3600
    sd = dt.datetime.fromtimestamp(lo, dt.timezone.utc).date().isoformat()
    ed = dt.datetime.fromtimestamp(hi, dt.timezone.utc).date().isoformat()
    for model in a.models.split(","):
        path = os.path.join(fdir, f"{model}.json.gz")
        if os.path.exists(path):
            continue
        out = []
        for k in range(0, len(pts), a.batch):
            chunk = pts[k:k + a.batch]
            q = {
                "latitude": ",".join(str(p[0]) for p in chunk), "longitude": ",".join(str(p[1]) for p in chunk),
                "start_date": sd, "end_date": ed, "models": model, "wind_speed_unit": "ms", "timeformat": "unixtime",
                "hourly": openmeteo_archive.VARS, "minutely_15": openmeteo_archive.VARS,
            }
            ledger.wait_for(len(chunk))
            data = openmeteo_archive.fetch(f"{openmeteo_archive.APIS['historical-forecast']}?{urllib.parse.urlencode(q)}")
            ledger.add(len(chunk))
            data = data if isinstance(data, list) else [data]
            for o in data:
                openmeteo_archive.trim(o, lo, hi)
            out += data
        with gzip.open(path + ".tmp", "wt") as f:
            json.dump(out, f, separators=(",", ":"))
        os.replace(path + ".tmp", path)
        print(f"  {model}: {len(out)} locations", file=sys.stderr)
    return len(pts), len(trk)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dirs", nargs="+")
    ap.add_argument("--obs-km", type=float, default=60.0, help="stations within this distance of the track feed the archive")
    ap.add_argument("--fc-station-km", type=float, default=40.0, help="stations within this distance get an exact forecast point")
    ap.add_argument("--track-km", type=float, default=4.0, help="forecast point spacing along the track")
    ap.add_argument("--models", default=MODELS)
    ap.add_argument("--batch", type=int, default=50)
    ap.add_argument("--max-per-min", type=int, default=400)
    ap.add_argument("--max-per-hour", type=int, default=4000)
    ap.add_argument("--max-per-day", type=int, default=9000)
    ap.add_argument("--cache", default="corpus/cache")
    ap.add_argument("--skip-forecasts", action="store_true")
    a = ap.parse_args()
    meta = stations_meta(a.cache)
    ledger = Ledger(os.path.join(a.cache, "openmeteo_ledger.json"), a.max_per_min, a.max_per_hour, a.max_per_day)
    for d in a.dirs:
        name = os.path.basename(os.path.normpath(d))
        track = read_track(os.path.join(d, "work", "track.csv"))
        near, n_obs = obs(d, track, meta, a)
        info = {"stations_in_radius": len(near), "observations": n_obs}
        if not a.skip_forecasts:
            n_pts, n_trk = forecasts(d, track, near, ledger, a)
            info.update(forecast_points=n_pts, track_points=n_trk)
        json.dump(info, open(os.path.join(d, "work", "meta.json"), "w"))
        print(f"{name[:8]}: {info}", file=sys.stderr)


if __name__ == "__main__":
    main()
