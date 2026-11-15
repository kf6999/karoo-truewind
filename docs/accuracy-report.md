# TrueWind accuracy report (real corpus)

Date: 2026-11-15. Code: branch `truewind/accuracy`. Every number below comes from the production
`WindEngine` / `RiderWindEstimator` run by the replay harness (`./gradlew :core:replay`), on the final
code. Machine-readable results: `docs/accuracy-baseline.json` (`configs` = fixture corpus, used by the
CI gate; `heldout` / `tuning` = the real corpus).

**Bottom line.** On held-out real rides TrueWind's wind estimate is **not measurably more accurate than
upstream at stations** (Metric A vector RMSE 1.92 vs 1.92 m/s). The tuning work recovered a large
regression of the SPEC-as-written fusion (2.05 -> 1.92 m/s for `+obs`), but the 20% target is
below what the station data can show: a station's own report from 10 minutes earlier predicts its
next report with 1.69 m/s vector RMSE. The rider-side layers do help where they are measured
(Metric B, Metric C). Four of the seven TESTING 3.6 targets are **not met** on the held-out set.

## 1. Corpus

| | tuning | held-out |
|---|---|---|
| rides (all with GPS + power) | 13 | 13 |
| ride hours | 41.7 | 42.2 |
| ASOS stations in the archive (within 60 km of a ride) | 170 | 160 |
| Metric A ride-stations (station within 20 km of the track, with reports) | 65 | 50 |
| Metric A station-hours | 197.9 | 139.1 |
| Metric A pairs (5-min-matched station reports) | 1443 | 1001 |

- Rides: every outdoor ride on the rider's Karoo (read-only `adb pull` of `/sdcard/FitFiles`, nothing on the
  device was changed). 224 FIT files, 46 with positions, 26 outdoor rides with power and >= 30 min.
  **The target of >= 30 rides is not met: only 26 qualifying rides exist on the device.** Total 84.0 h.
- Split (fixed before any replay, `docs/accuracy-split.md`): ISO week even -> tuning, odd -> held-out.
- Observations: Iowa Environmental Mesonet ASOS/AWOS archive (5-minute HFMETAR plus METAR), stations
  within 60 km of each ride, ride -1 h .. +1 h. Replay serves each report 10 min after its timestamp
  (`--obs-lag-min 10`, a realistic feed latency); lag 0 is shown in section 3.4.
- Forecasts: Open-Meteo historical-forecast archive (`best_match`, `ncep_hrrr_conus`, `ncep_nbm_conus`,
  `ecmwf_ifs`, `gfs_global`; hourly + 15-minute) at track points every 4 km and at stations, cached in
  `corpus/private/` (throttled through a request ledger in `corpus/cache/`). Builder:
  `tools/download/build_ride_corpus.py`.
- Synoptic: the token's account only returns history for roughly the last 7 days (403 earlier), so it
  could not add mesonet stations to rides from April - October; not used. No token value is stored.
- Ride files and everything derived from their coordinates stay in git-ignored `corpus/private/`.

## 2. Held-out results (final code)

Obs lag 10 min. Metric B uses the TESTING 3.3 procedure (CdA/Crr fitted on the calmest 20 min with
zero wind); see section 4 for why that fit is unreliable and the alternative whole-ride fit.

| config | A vec RMSE m/s | A speed MAE | A dir MAE ° | A shift-window dir MAE ° | B VE RMSE m | B fwd headwind RMSE m/s | C stopped jitter ° | C turn lag s |
|---|---|---|---|---|---|---|---|---|
| upstream | 1.92 | 0.99 | 15 | 25 | 16.61 | 3.02 | 11.8 | 2.2 |
| nowind (reference: zero wind) | – | – | – | – | 12.57 | 2.79 | – | – |
| +ensemble | 1.94 (+1%) | 1.01 | 15 | 25 | 16.59 | 2.95 | 11.8 | 2.2 |
| +obs | 1.92 (+0%) | 0.99 | 15 | 26 | 16.70 | 2.99 | 11.8 | 2.2 |
| +height | 1.92 | 0.99 | 15 | 26 | 12.81 (-23%) | 2.54 | 11.8 | 2.2 |
| +heading | 1.92 | 0.99 | 15 | 26 | 12.86 | 2.54 | 5.2 | 1.5 |
| +virtual (sensor on) | 1.92 | 0.99 | 15 | 26 | 13.78 | 2.53 | 5.2 | 1.5 |

Metric B with the whole-ride CdA fit (`--cda-fit ride`, section 4):

| config | B VE RMSE m | B fwd RMSE m/s |
|---|---|---|
| upstream | 12.64 | 2.33 |
| nowind | 6.36 | 1.98 |
| +ensemble | 12.40 | 2.21 |
| +obs | 12.64 | 2.26 |
| +height | 6.82 (-46%) | 1.62 |
| +heading | 6.91 | 1.62 |
| +virtual (sensor on) | 8.50 | 1.66 |
| +virtual, rider CdA 0.5 | 5.80 | 1.57 (-3% vs +height) |

### 2.1 Per regime (Metric A vector RMSE m/s, n pairs)

| config | calm (26) | moderate (937) | strong (38) | shift (122) |
|---|---|---|---|---|
| upstream | 3.05 | 1.85 | 2.39 | 2.27 |
| +ensemble | 3.09 (+1%) | 1.88 (+2%) | 2.42 (+1%) | 2.31 (+2%) |
| +obs | 2.94 (-4%) | 1.86 (+1%) | 2.40 (+0%) | 2.34 (+3%) |

### 2.2 Per distance to the nearest remaining station (Metric A vector RMSE m/s, n pairs)

| config | 0-10 km (388) | 10-20 km (424) | 20-30 km (89) | >= 30 km (100) |
|---|---|---|---|---|
| upstream | 2.02 | 1.76 | 1.54 | 2.36 |
| +ensemble | 2.08 | 1.82 | 1.57 | 2.20 |
| +obs | 2.07 | 1.76 | 1.57 | 2.19 (-7%) |

Observations do not help when another station is close (0-10 km); the only gain is far from other
stations, where the ensemble alone also helps (forecast blend, not residuals).

### 2.3 Single members (held-out, same engine, one member only)

| member | vec RMSE | speed MAE | dir MAE ° |
|---|---|---|---|
| HRRR (hour-anchored) | 1.92 | 1.03 | 15 |
| NBM | 2.32 | 1.22 | 17 |
| GFS | 2.63 | 1.17 | 23 |
| ECMWF IFS | 2.66 | 1.73 | 20 |

Upstream (Open-Meteo `best_match` hourly) is HRRR in CONUS, so upstream already uses the best
available member. None of the others adds independent skill at these sites.

## 3. Tuning vs held-out

### 3.1 Same table on the tuning set

| config | A vec RMSE | A shift dir MAE ° | B VE (calm20) | B fwd (calm20) | B VE (ride fit) | B fwd (ride fit) | C jitter ° | C lag s |
|---|---|---|---|---|---|---|---|---|
| upstream | 1.88 | 30 | 15.11 | 2.56 | 11.51 | 1.98 | 9.8 | 2.6 |
| nowind | – | – | 11.68 | 2.40 | 7.07 | 1.69 | – | – |
| +ensemble | 1.92 (+2%) | 31 | 14.71 | 2.53 | 11.06 | 1.92 | 9.8 | 2.6 |
| +obs | 1.92 (+2%) | 31 | 14.88 | 2.54 | 11.14 | 1.93 | 9.8 | 2.6 |
| +height | 1.92 | 31 | 11.66 | 2.14 | 6.96 | 1.32 | 9.8 | 2.6 |
| +heading | 1.92 | 31 | 11.72 | 2.14 | 7.06 | 1.32 | 4.7 | 1.7 |
| +virtual | 1.92 | 31 | 11.56 | 2.18 | 7.08 | 1.40 | 4.7 | 1.7 |

Regimes, tuning `+obs` vs upstream: calm 1.60 vs 1.65, moderate 1.92 vs 1.88 (+2%), strong 4.18 vs
4.05 (+3%, n = 6), shift 2.35 vs 2.33. Held-out results track tuning closely (no sign of over-fitting,
and also no hidden gain).

### 3.2 SPEC as written vs tuned (`--set fusion.spec=1`)

| | tuning +ensemble | tuning +obs | held-out +ensemble | held-out +obs |
|---|---|---|---|---|
| SPEC 4.3 as written | 2.18 | 2.24 | 2.13 | 2.05 |
| final defaults | 1.92 | 1.92 | 1.94 | 1.92 |

### 3.3 Observation noise floor

On the stations used by Metric A, predicting each 5-minute report by the same station's latest report
10 minutes earlier gives vector RMSE **1.69 m/s** (tuning and held-out); the mean of its last 60 min,
1.65 (tuning) / 1.68 (held-out). That is the error of a perfect "persistence at the station itself",
with no spatial or model error. A 20% gain over upstream on held-out would need 1.54 m/s, below that
floor. The remaining error is mostly 2-minute gust/turbulence noise that no 15-min forecast or
neighbouring station can predict.

### 3.4 Observation latency

Held-out `+obs` at lag 0 (reports usable the moment they are timestamped): 1.91 m/s (tuning 1.93).
The 10-min lag is not what limits the observation layer.

## 4. Metric B fit and leakage check

- The TESTING 3.3 procedure fits CdA/Crr on the calmest 20 minutes **assuming zero wind**. On 10 of 13
  tuning rides and 8 of 13 held-out rides the fit lands on the edge of the search grid: 20 minutes of
  real road data do not constrain both parameters, and the zero-wind assumption biases the fit towards
  whatever makes the no-wind model look right. Consequence: the `nowind` reference scores 12.57 m VE on
  held-out, better than every wind config, including `+height` (12.81).
- Leakage: the fitted parameters are the same for every config on a ride (fitted once, without any
  wind estimate), so there is no config-specific leakage; but the fit is in-sample (same ride) and
  biased in favour of small wind. Metric B therefore cannot be used to rank wind sources finely.
- Alternative added for diagnosis, `--cda-fit ride`: CdA on a 0.15 - 0.60 grid over the whole ride with
  Crr 0.005, still zero wind. Grid-edge fits drop to 2 tuning / 0 held-out. `nowind` still beats every
  wind config on VE (6.36 vs 6.82 for `+height`), so the remaining bias is still in favour of zero wind;
  forward headwind RMSE (which measures prediction of the power-implied headwind 1-5 min ahead) ranks
  `+height` best (1.62 vs nowind 1.98, upstream 2.33).
- Rider mass is unknown (84 kg default for all rides); FIT files from this Karoo carry no heading/GPS
  bearing record, so course is derived from positions.

## 5. Changes and their measured effect

All decisions were made on the tuning set; held-out was run once at the end.

| change | where | tuning effect | held-out effect |
|---|---|---|---|
| Hour-anchored member interpolation (`memberAnchorStepMs` 60 min): native 15-min HRRR steps skipped | `fusion/SeriesMath.kt`, `FusionConfig` | HRRR-only 2.03 -> 1.88 | HRRR-only 1.92 |
| Member priors from data (native CAMs 1.2, NBM 0.3, global 0.1; was equal) | `FusionConfig.MemberPriors.DEFAULT` | +ensemble 2.18 -> 1.92 together with the above | +ensemble 2.13 -> 1.94 |
| Online skill weighting off (`skillWeighting=false`; members still scored) | `FusionConfig`, `WindEngine` | 1.96 -> 1.92 (was worse at every pseudo-weight tried; 2.04 at SPEC's) | included in 2.05 -> 1.92 for +obs |
| Residual kernel 8 km (was 15) and per-station 60-min residual mean (was newest report) | `FusionConfig`, `WindEngine.averageResiduals` | +obs 1.95 -> 1.92 | +obs 1.92 (= upstream) |
| Virtual wind sensor **default off**, measurement sigma 3 m/s (was 1.5) | `virtualwind/RiderWindEstimator.kt`, `VirtualWindSensor.kt` | fwd 1.32 (+height) vs 1.51 (SPEC) / 1.40 (sigma 3) with sensor | sensor on: fwd 1.66 vs 1.62, VE 8.50 vs 6.82 -> off |
| Heading tuning (earlier branch work, kept) | `virtualwind` | jitter 4.7°, lag 1.7 s (upstream 9.8°, 2.6 s) | 5.2°, 1.5 s (upstream 11.8°, 2.2 s) |

Tried and rejected (removed from code): output rounding (no effect), temporal smoothing of members,
speed-preserving blend, noise-aware skill weighting (obs error variance subtracted), exposure OPEN
(fwd 1.39) and SHELTERED (1.30) vs MIXED (1.32; kept MIXED, difference within noise).

`FusionConfig.SPEC_4_3` keeps the SPEC-as-written behaviour (used by the end-to-end unit test and by
`--set fusion.spec=1`).

Replay tooling added: multi-corpus runs, observation latency, `member:*` / `member-h:*` / `nowind`
configs, `--set key=value` overrides (unknown keys fail), `--cda-fit ride`, per-regime and
per-distance tables in the summary, per-station Metric A, corpus statistics (station-hours, grid-edge
fits) in the report.

## 6. TESTING 3.6 on the held-out set

| target | held-out | met? |
|---|---|---|
| `+obs` Metric A vector RMSE >= 20% lower than upstream | 1.92 vs 1.92 (0%) | **not met** |
| `+obs` not > 5% worse in any regime | worst: shift +3% | met |
| shift-window direction MAE >= 30% lower | 26° vs 25° (+4%) | **not met** |
| `+height` VE RMSE >= 25% lower than upstream | -23% (TESTING calm-20 fit); -46% with whole-ride fit | **not met** as specified (and `nowind` beats `+height` under both fits) |
| `+virtual` forward error >= 15% lower than `+height` | +0% / -3% with CdA 0.5 (sensor therefore default off) | **not met** |
| stopped jitter < 5° | 5.2° (tuning 4.7°) | **not met** on held-out |
| turn lag <= upstream + 1 s | 1.5 s vs 2.2 s | met |
| each layer neutral or better, else off | `+ensemble` +1% (within noise, on); virtual sensor off | met by defaulting off |

What would be needed to meet them honestly:

- **Metric A:** a denser and lower-noise truth than 2-minute ASOS winds. The current evaluation cannot
  resolve a 20% gain (noise floor 1.69 vs upstream 1.92). Options: Synoptic/MesoWest mesonet history
  (needs a plan with archive access beyond 7 days), 10-min mean winds as truth instead of 2-min
  reports, and stations that were not themselves assimilated into HRRR. Data from a rider-mounted
  anemometer would be the real test.
- **Forecast side:** RRFS (native 15-min, convection-allowing) has no Open-Meteo archive; a second
  independent CAM would be the only member that could add skill over HRRR.
- **Metric B:** a calibration ride per rider (known CdA/Crr/mass from a calm out-and-back) instead of
  an in-sample zero-wind fit; then VE and forward error would rank wind sources without bias.
- **Virtual sensor:** per-rider CdA calibration (with CdA 0.5 it already gains 3% forward / 15% VE on
  held-out).
- **Corpus:** >= 30 rides (only 26 on the device; intervals.icu history could add more) and more
  strong-wind and front-passage hours (held-out has 38 strong and 122 shift pairs).

## 7. Fixture corpus (CI gate)

`docs/accuracy-baseline.json` `configs` holds the fixture-corpus metrics of the final code (obs lag 0,
default configs, `+virtual` with the sensor forced on). CI replays the fixtures and fails if a gated
metric is more than 2% worse. Final fixture numbers: Metric A upstream 3.90, +ensemble 3.31, +obs 3.18
(was 3.24 before tuning); B VE +height 4.74, +virtual 2.51; fwd +height 1.61, +virtual 1.22. On the
fixtures `+obs` is 7% worse than upstream in the calm regime (0.94 vs 0.88 m/s, n = 112) - noted, not
hidden; the real held-out calm regime is 4% better.

## 8. Not verified

- Results are from one rider, one region, April - October; generalisation is untested.
- Rider mass is a default (84 kg) for every ride.
- The Open-Meteo historical-forecast archive is short-lead (recent runs), close to but not the same as
  what the app would have fetched live.
- `app/` was not touched; on-device behaviour of the new defaults is not tested here.
