# Replay corpus

Input for the replay harness (`core/replay`, TESTING.md section 3).

```
corpus/
  fixtures/              committed, runs in CI (< 2 min; currently ~1 s)
    synthetic-front/     known truth: cold front, 5 biased members, 7 noisy stations, 2 rides with power
    recorded-sfbay-front/ real IEM obs + real Open-Meteo historical forecasts, 2026-11-13 15-23Z,
                         San Francisco Bay, with one synthetic ride whose truth is the observations
  private/               git-ignored: real FIT rides and their archives. Never commit these.
  cache/                 git-ignored: raw snapshots from tools/recorder
```

## Corpus directory format

```
<corpus>/
  corpus.json                    optional {name, description}
  rides/<id>.fit                 Karoo/Garmin FIT (positions, speed, power, altitude, temperature)
  rides/<id>.csv                 time_ms,lat,lon,alt_m,speed_mps,power_w,cadence_rpm,bearing_deg,
                                 accuracy_m,heading_deg,temp_c[,truth_u,truth_v]
  rides/<id>.json                optional sidecar {system_mass_kg, truth_cda, truth_crr}
  forecasts/<model>.json[.gz]    Open-Meteo responses (one object or a list of locations; hourly
                                 and/or minutely_15; wind_speed_10m / wind_direction_10m /
                                 wind_gusts_10m, optionally model-suffixed; m/s or km/h)
  observations/*.csv[.gz]        station,source,lat,lon,elev_m,time_ms,speed_mps,dir_deg,gust_mps,
                                 sensor_height_m
```

## Building a corpus for a real ride

```
# 1. rides: pull FIT files from the Karoo (readable over adb) into a private corpus
adb shell ls -t /sdcard/FitFiles | head
adb pull /sdcard/FitFiles/<file>.fit corpus/private/<name>/rides/
./gradlew :core:replay --args="describe --corpus corpus/private/<name>"     # bbox and times

# 2. observations (IEM ASOS 1-min, 5-min HFMETAR or routine METAR; the 1-min archive lags ~1 day)
tools/download/iem_asos.py --bbox LAT0,LON0,LAT1,LON1 --start 2026-10-17T15:00Z --end 2026-10-17T23:00Z \
    --mode 1min --out corpus/private/<name>/observations/iem_1min.csv

# 3. forecasts (Open-Meteo historical-forecast API: per-model hourly + 15-min, free)
tools/download/openmeteo_archive.py --bbox LAT0,LON0,LAT1,LON1 --step 0.05 \
    --extra-points corpus/private/<name>/observations/iem_1min.csv \
    --start-date 2026-10-17 --end-date 2026-10-17 --gzip --out-dir corpus/private/<name>/forecasts
#    --api previous-runs --previous-day 1   gives day-ahead forecasts instead of the freshest run

./gradlew :core:replay --args="run --corpus corpus/private/<name> --out build/replay/<name>.json"
```

## The real accuracy corpus (many rides)

`tools/download/build_ride_corpus.py` builds one corpus per ride under
`corpus/private/real/{tuning,heldout}/<ride id>/` (split in `docs/accuracy-split.md`): IEM ASOS stations
within 60 km, Open-Meteo historical forecasts along the track and at stations, throttled through
`corpus/cache/openmeteo_ledger.json`. A directory of such corpora replays in one run, each ride with its
own archives:

```
./gradlew :core:replay --args="run --corpus corpus/private/real/heldout --obs-lag-min 10 \
    --out build/replay/heldout.json --summary build/replay/heldout.md"
#   --configs upstream,+obs,member:ncep_hrrr_conus,nowind   --cda-fit ride   --set fusion.spec=1
```

Results and method: `docs/accuracy-report.md`.

Live recording (what a client actually saw, including the 15-min data and METARs):
`tools/recorder/record.py record --name X --bbox ... --every 15`, later
`tools/recorder/record.py stitch --name X` writes `corpus/private/X-live/`.

Synthetic data: `./gradlew :core:replay --args="synthesize corpus/fixtures/synthetic-front"` and
`synthesize-ride DIR --center LAT,LON --start ISO --minutes N --id ID` (truth = IDW of the
directory's observations).
