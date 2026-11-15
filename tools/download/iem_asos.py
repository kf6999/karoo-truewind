#!/usr/bin/env python3
# Copyright 2026 TrueWind contributors
# SPDX-License-Identifier: Apache-2.0
"""Download archived ASOS/AWOS wind observations from the Iowa Environmental Mesonet (keyless).

Writes the replay corpus' normalised observation CSV:
    station,source,lat,lon,elev_m,time_ms,speed_mps,dir_deg,gust_mps,sensor_height_m

Endpoints (verified live 2026-11-15):
  * 1-minute ASOS:  https://mesonet.agron.iastate.edu/cgi-bin/request/asos1min.py
                    (station, valid(UTC), sknt, drct, gust_sknt; no coordinates -> from the network list)
  * 5-minute / routine / specials METAR:
                    https://mesonet.agron.iastate.edu/cgi-bin/request/asos.py  (report_type 1 = HFMETAR
                    5-minute, 3 = routine, 4 = specials)
  * station list:   https://mesonet.agron.iastate.edu/geojson/network/<STATE>_ASOS.geojson

Examples:
  tools/download/iem_asos.py --bbox 37.3,-122.6,37.95,-121.8 --network CA_ASOS \
      --start 2026-11-10T18:00Z --end 2026-11-10T23:00Z --mode 1min --out corpus/x/observations/iem.csv
  tools/download/iem_asos.py --stations SFO,OAK --start ... --end ... --mode 5min --out ...
"""
import argparse
import csv
import datetime as dt
import io
import json
import sys
import time
import urllib.parse
import urllib.request

BASE = "https://mesonet.agron.iastate.edu"
KT = 0.514444
UA = {"User-Agent": "truewind-replay-downloader/1.0"}


def fetch(url, retries=3):
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=120) as r:
                return r.read().decode("utf-8", "replace")
        except Exception as e:  # noqa: BLE001 - network tool, report and retry
            if attempt == retries - 1:
                raise
            print(f"retry {attempt + 1}: {e}", file=sys.stderr)
            time.sleep(5 * (attempt + 1))


def parse_time(s):
    s = s.strip().replace("Z", "+00:00")
    t = dt.datetime.fromisoformat(s)
    if t.tzinfo is None:
        t = t.replace(tzinfo=dt.timezone.utc)
    return t.astimezone(dt.timezone.utc)


def network_stations(networks):
    out = {}
    for net in networks:
        d = json.loads(fetch(f"{BASE}/geojson/network/{net}.geojson"))
        for f in d["features"]:
            p = f["properties"]
            lon, lat = f["geometry"]["coordinates"]
            out[p["sid"]] = (lat, lon, p.get("elevation"))
    return out


def in_bbox(lat, lon, bbox):
    return bbox[0] <= lat <= bbox[2] and bbox[1] <= lon <= bbox[3]


def num(s):
    s = (s or "").strip()
    if s in ("", "M", "T"):
        return None
    try:
        return float(s)
    except ValueError:
        return None


def rows_1min(station, meta, start, end):
    q = [("station", station), ("vars", "sknt"), ("vars", "drct"), ("vars", "gust_sknt"),
         ("sts", start.strftime("%Y-%m-%dT%H:%MZ")), ("ets", end.strftime("%Y-%m-%dT%H:%MZ")),
         ("sample", "1min"), ("what", "download"), ("tz", "UTC")]
    text = fetch(f"{BASE}/cgi-bin/request/asos1min.py?{urllib.parse.urlencode(q)}")
    lat, lon, elev = meta
    for r in csv.DictReader(io.StringIO(text)):
        t = dt.datetime.strptime(r["valid(UTC)"], "%Y-%m-%d %H:%M").replace(tzinfo=dt.timezone.utc)
        yield station, lat, lon, elev, t, num(r.get("sknt")), num(r.get("drct")), num(r.get("gust_sknt"))


def rows_metar(stations, start, end, report_types):
    q = [("station", s) for s in stations] + [("data", "sknt"), ("data", "drct"), ("data", "gust")]
    q += [("year1", start.year), ("month1", start.month), ("day1", start.day), ("hour1", start.hour), ("minute1", start.minute),
          ("year2", end.year), ("month2", end.month), ("day2", end.day), ("hour2", end.hour), ("minute2", end.minute),
          ("tz", "Etc/UTC"), ("format", "onlycomma"), ("latlon", "yes"), ("elev", "yes"), ("missing", "M"), ("trace", "T"), ("direct", "no")]
    q += [("report_type", rt) for rt in report_types]
    text = fetch(f"{BASE}/cgi-bin/request/asos.py?{urllib.parse.urlencode(q)}")
    for r in csv.DictReader(io.StringIO(text)):
        t = dt.datetime.strptime(r["valid"], "%Y-%m-%d %H:%M").replace(tzinfo=dt.timezone.utc)
        yield r["station"], num(r["lat"]), num(r["lon"]), num(r.get("elevation")), t, num(r.get("sknt")), num(r.get("drct")), num(r.get("gust"))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--stations", help="comma-separated IEM station ids (SFO, not KSFO)")
    ap.add_argument("--bbox", help="lat_min,lon_min,lat_max,lon_max (needs --network)")
    ap.add_argument("--network", action="append", default=[], help="IEM network, e.g. CA_ASOS (repeatable)")
    ap.add_argument("--start", required=True, help="ISO time, UTC if no offset")
    ap.add_argument("--end", required=True)
    ap.add_argument("--mode", choices=["1min", "5min", "metar"], default="5min",
                    help="1min = ASOS 1-minute archive; 5min = HFMETAR; metar = routine+specials")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    start, end = parse_time(a.start), parse_time(a.end)
    meta = network_stations(a.network) if a.network else {}
    stations = [s.strip() for s in a.stations.split(",")] if a.stations else []
    if a.bbox:
        bbox = [float(x) for x in a.bbox.split(",")]
        if not meta:
            sys.exit("--bbox needs --network")
        stations += [sid for sid, (lat, lon, _) in meta.items() if in_bbox(lat, lon, bbox)]
    stations = sorted(set(stations))
    if not stations:
        sys.exit("no stations selected")
    print(f"{len(stations)} stations: {' '.join(stations)}", file=sys.stderr)

    rows = []
    if a.mode == "1min":
        for s in stations:
            if s not in meta:
                print(f"skip {s}: no coordinates (add its --network)", file=sys.stderr)
                continue
            rows += list(rows_1min(s, meta[s], start, end))
    else:
        rows += list(rows_metar(stations, start, end, ["1"] if a.mode == "5min" else ["3", "4"]))

    n = 0
    with open(a.out, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["station", "source", "lat", "lon", "elev_m", "time_ms", "speed_mps", "dir_deg", "gust_mps", "sensor_height_m"])
        for sid, lat, lon, elev, t, sknt, drct, gust in sorted(rows, key=lambda r: (r[0], r[4])):
            if lat is None or lon is None or sknt is None:
                continue
            speed = sknt * KT
            # IEM reports variable wind as drct missing; calm as 0 kt / 0 deg.
            d = "" if drct is None or (sknt > 0 and drct == 0) else f"{drct:.0f}"
            w.writerow([sid, f"iem-{a.mode}", f"{lat:.4f}", f"{lon:.4f}", "" if elev is None else f"{elev:.1f}",
                        int(t.timestamp() * 1000), f"{speed:.2f}", d, "" if gust is None else f"{gust * KT:.2f}", "10"])
            n += 1
    print(f"wrote {n} observations to {a.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
