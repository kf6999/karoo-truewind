# TrueWind — testing strategy

Companion to [SPEC.md](SPEC.md). Two things are being proven: the wind numbers are more accurate than upstream Headwind's, and the extension costs no more CPU, network or battery. Both are gated by numbers.

Principle: **do the expensive thinking off the device.** Accuracy is decided on a laptop by replaying recorded rides against archived forecasts and observations. The Karoo is used only for what cannot be simulated: real sensor behaviour, IPC cost and battery drain.

---

## 1. Test pyramid

| Tier | Where | Runs | Wall time | Proves |
|---|---|---|---|---|
| T0 Unit | JVM, `core` | every commit | < 30 s | Math, parsers, filters, scheduler logic |
| T1 Replay | JVM, `core/replay` | every PR (fixture corpus); nightly (full corpus) | < 2 min / < 20 min | Accuracy vs. the upstream baseline |
| T2 Integration | JVM with fake Karoo streams and fake HTTP | every PR | < 1 min | Pipeline wiring, emit-on-change, fetch policy |
| T3 Device functional | Karoo over adb | before each release candidate | 15 min | Real streams, views, HTTP over the phone link |
| T4 Device performance and battery | Karoo over Wi-Fi adb, unplugged | before each release candidate and after any change to tick/view/fetch code | 3–7 h, unattended | Budgets in §5 |
| T5 Field | Real rides | weekly during development | – | Ground truth, usability |

---

## 2. T0 — unit tests (JVM)

All in `core/src/test`. No Android, no network, no sleeps (inject a clock).

- **Angles and vectors:** u/v ↔ speed/direction round trips; circular mean and smoothing across the 359°/0° wrap; signed head/cross decomposition for all quadrants.
- **Log law:** table values in SPEC §4.4 to 2 decimals; monotonic in z and z0; clamp behaviour.
- **Air density:** 1.225 kg/m³ at 15 °C, 1013.25 hPa, dry; known values at altitude and at 35 °C / 80% RH; hypsometric correction against a worked example.
- **Parsers:** recorded fixtures per provider — Open-Meteo multi-model (CONUS, Europe, region without 15-min data, a member returning nulls, an error body), AWC (VRB wind, calm, missing gust, duplicate stations, empty list), NWS (null wind fields, unit codes), Synoptic (sensor height present/absent). Parsers must never throw on unknown keys.
- **Observation QC:** each rejection rule in SPEC §4.3, plus the neighbour-outlier rule.
- **Fusion:** with identical members the blend equals the member; a member with a known injected bias loses weight within 3 cycles; residual correction moves the rider value toward a co-located fresh observation and by less than 20% for a 30 km / 30 min old one; confidence deductions.
- **Heading estimator:** synthetic tracks — straight line, stop at a junction for 60 s then turn 90°, 5 km/h switchback climb, GPS outlier. Arrow must not swing more than 10° while stopped.
- **Virtual wind filter:** synthetic rides generated from the same power model with known wind, CdA error and sensor noise. Requirements: on a 20-min loop, wind vector error under 1 m/s after 10 min; with a 15% CdA error and heading diversity, bias state absorbs it and wind error stays under 1.5 m/s; on a straight out-leg only, the bias stays frozen and the output stays within the model prior's uncertainty; invalid samples (coasting, braking, steep grade) are ignored.
- **Fetch scheduler:** fake clock and fake position. Asserts exact fetch times for: steady ride, 4 km move, route change, adaptive speed-up and back-off, backoff sequence on failure, `Retry-After`, no fetches when neither recording nor visible.
- **Property tests** (kotest-property or equivalent) for angle math and the filter's covariance staying positive-definite.

---

## 3. T1 — replay harness: the accuracy gate

`./gradlew :core:replay --args="--corpus corpus/ --out build/replay/metrics.json"`

### 3.1 Inputs

1. **Rides:** FIT files with GPS, speed, power, cadence, altitude. Sources: the Karoo itself (`adb pull` of the ride files once authorised), intervals.icu or Strava exports. Target corpus: at least 30 rides, 60+ hours, covering calm days, sea-breeze afternoons, frontal passages, mountain valleys and flat open country. Keep a 5-ride **fixture corpus** in the repo (strip to the needed fields, offset coordinates if privacy matters) for CI.
2. **What the forecasts said at the time:** Open-Meteo Historical Forecast / Previous Runs APIs, queried per model for each ride's track and time. Using archived *forecasts* rather than reanalysis is essential — reanalysis has already seen the observations and would flatter every method. Confirm free-tier access for these endpoints when building the downloader; if a model's archive is unavailable, record live responses with the `tools/recorder` script (one cron-style fetch every 10 min for a set of test locations) and build the corpus forward in time.
3. **What actually happened:** archived station observations. Iowa Environmental Mesonet ASOS/AWOS archives (keyless, 1-minute and 5-minute data for US stations) and, with a token, Synoptic time series.

The downloader caches everything under `corpus/cache/` so replays are offline and deterministic.

### 3.2 Metric A — 10 m wind against held-out stations

For every station within 30 km of a ride track, leave that station out of the observation feed, run the pipeline as if the rider were at the station, and compare the fused 10 m wind with what the station reported.

- Vector RMSE (m/s) — primary.
- Speed MAE and bias (m/s).
- Direction circular MAE (degrees), only where observed speed ≥ 2 m/s.

Report by regime (calm < 2 m/s, moderate, strong > 8 m/s, and "shift" windows where the observed direction changed more than 45° in an hour) and by distance to the nearest *remaining* station.

### 3.3 Metric B — rider-height wind via virtual elevation

There is no anemometer on the bike, so use the ride's own power data as truth. For each ride with power, integrate the power balance using the pipeline's rider-height wind to reconstruct elevation ("virtual elevation", the Chung method), and compare with barometric elevation.

- Virtual-elevation RMSE over rolling 10-minute windows, after removing the per-window mean — lower means the wind estimate explains the rider's power better.
- Run with the virtual wind sensor **disabled** to score layers 4.2–4.5 honestly (otherwise the filter is graded on data it consumed). Score the filter separately by forward prediction: its estimate at time *t* predicts headwind over *t*+1…5 min.
- CdA and Crr are fitted once per ride on the calmest 20 minutes and then frozen, identically for every method compared.

### 3.4 Metric C — heading

On the same rides: relative-wind-direction jitter (standard deviation of the displayed arrow angle over 10 s windows) while speed < 2 m/s, and lag after turns greater than 60° (time to settle within 15°).

### 3.5 Baselines and ablations

The harness runs these configurations over the same corpus:

| Config | Behaviour |
|---|---|
| `upstream` | Emulates Headwind: `best_match` only, hourly steps with linear interpolation, 3 km rounding, refetch on rounded-cell change or hourly, 10 m wind used as rider wind, raw GPS course |
| `+ensemble` | Multi-model, 15-min steps, prior weights, 10-min refresh |
| `+obs` | Adds weighting and residual correction |
| `+height` | Adds rider-height downscaling |
| `+heading` | Adds heading fusion |
| `+virtual` | Adds the virtual wind sensor |

### 3.6 Acceptance thresholds

On the full corpus:

- Metric A vector RMSE: `+obs` at least **20% lower** than `upstream` overall, and not more than 5% worse in any regime.
- Metric A direction MAE in "shift" windows: at least **30% lower**.
- Metric B: `+height` at least **25% lower** virtual-elevation RMSE than `upstream`; `+virtual` forward-prediction error at least 15% lower than `+height`.
- Metric C: stopped-jitter under 5° and no worse turn lag than `upstream` + 1 s.
- Every layer must be neutral or better on its own; a layer that does not earn its place is removed or defaulted off.

These thresholds are targets set before any data exists. After the first full-corpus run, record the achieved numbers in `docs/accuracy-baseline.json`; from then on CI fails a PR that regresses any metric by more than 2% on the fixture corpus.

---

## 4. T2 and T3 — integration and on-device functional

**T2 (JVM).** `app` logic behind interfaces (`KarooStreams`, `HttpClient`, `Clock`, `Store`) with fakes:

- Replay a recorded ride through the whole pipeline at 60× and assert: exactly one upstream subscription per Karoo stream regardless of how many fields are active; emissions only on displayed-value change; view updates ≤ 1 per second per field and zero while not visible; HTTP request count and bytes match the scheduler's plan.
- Golden test for WP1: `WindState` from the refactored pipeline in `upstream` mode equals values captured from unmodified Headwind on the same inputs.
- Fault injection: HTTP timeout, 429, 500, truncated JSON, phone link drop for 40 minutes (values must continue from the 4 h cache and status must turn `STALE` only after the cache window), DST change, midnight rollover, GPS loss.

**T3 (device).** A debug-only **replay mode** in the app feeds a bundled FIT track into `SensorHub` in place of live streams, so the device can "ride" on a desk. Script `scripts/device_smoke.sh`:

1. `adb install -r` the debug APK; launch; accept the welcome dialog via `adb shell input`.
2. Start replay mode at 10×; start a ride recording with the test profile (a page holding the six heaviest fields).
3. After 3 minutes: screenshot (`adb exec-out screencap -p`), pull the diagnostics JSON from the app's files dir, stop the ride.
4. Assert: no crash or ANR in `logcat`; all fields left the "no data" state; at least one successful fetch per provider; response sizes under 15 KB; FIT file contains the developer fields.

Run once on Wi-Fi and once with Wi-Fi off and the phone companion connected, since the Bluetooth path is the one used on the road.

---

## 5. T4 — performance and battery

### 5.1 Budgets

Measured on a Karoo 3 during a recorded ride with six TrueWind fields on the visible page, 10-minute refresh, brightness fixed at 50%.

| Metric | Budget | How measured |
|---|---|---|
| Extension process CPU, average | ≤ 1.0% of one core, and ≤ upstream Headwind in the same test | `/proc/<pid>/stat` utime+stime deltas sampled every 60 s |
| Tick compute time | ≤ 2 ms p99 on device; ≤ 0.2 ms on JVM microbenchmark | in-app histogram; JMH-style benchmark in `core` |
| View updates | ≤ 1 per second per visible field; 0 when hidden | in-app counter |
| Wakelocks, alarms, jobs held by the app | 0 | `dumpsys power`, `dumpsys alarm`, `dumpsys jobscheduler` |
| Network | ≤ 8 requests and ≤ 60 KB per fetch cycle; ≤ 400 KB per hour | in-app counters, cross-checked with `dumpsys netstats` per uid |
| Radio wake-ups | one burst per cycle (all requests within 10 s) | request timestamps in diagnostics |
| Memory (PSS) | ≤ 60 MB steady, no growth over 6 h | `dumpsys meminfo <pkg>` every 10 min |
| Battery | ≤ 0.5 percentage points per hour above the no-extension run, and ≤ upstream | §5.2 |
| Cold start to first value | ≤ 3 s with cache, ≤ 15 s without | timestamps in diagnostics |
| APK size | ≤ upstream + 300 KB | CI check |

### 5.2 Battery protocol (A/B/C, unplugged)

USB power invalidates battery measurements, so the device must run unplugged and be controlled over Wi-Fi.

Setup, once: authorise adb over USB; `adb tcpip 5555`; `adb connect <karoo-ip>:5555`; unplug. Karoo and Mac on the same Wi-Fi. (If the Karoo drops Wi-Fi when a ride starts, fall back to: start the run by script, unplug, and collect the log files after replugging.)

Each run, driven by `scripts/battery_run.sh <config>`:

1. Charge to 100%, unplug, wait 5 minutes.
2. `adb shell dumpsys batterystats --reset`.
3. Fixed conditions: brightness 50%, auto-brightness off, same ride profile and page, GPS on with the device at a window (or outdoors in shade), same sensors paired (or none), phone companion connected, replay mode supplying speed/power so the virtual sensor is exercised.
4. Start recording; run **3 hours**. A logger inside the debug build writes one CSV row per minute: battery percent (Karoo `BATTERY_PERCENT` stream and `BatteryManager` charge counter in µAh when the device reports it), process CPU ticks, request and byte counters, view-update counters, PSS.
5. Stop; collect the CSV, `dumpsys batterystats --charged <pkg>`, and `dumpsys netstats`.

Configurations: **A** no weather extension fields on the page; **B** upstream Headwind with the equivalent fields; **C** TrueWind. Order A, C, B, C, A across days so drift in temperature or battery health cancels. Report drain as the least-squares slope of charge against time (percent per hour), not end-minus-start, with the run-to-run spread from the repeated A and C runs. A difference smaller than that spread is reported as "not measurable" rather than as a pass by default; the CPU and network budgets, which are far less noisy, are then the deciding evidence.

### 5.3 CPU and scheduling detail

When a budget is missed: record a 2-minute Perfetto trace (`adb shell perfetto` with sched and process stats) to see wake-up frequency and which threads run, and use `simpleperf` on the extension process for a flat profile. Expected hot spots, in order: Glance composition and `RemoteViews` serialisation, JSON parsing during a fetch, DataStore writes. Mitigations already in the design: emit-on-change, one write per fetch cycle, parsing off the tick path.

### 5.4 Soak

Replay mode at 10× for 6 hours of wall time (60 simulated ride hours) with the phone link toggled off for 10 minutes every hour. Pass: no crash, PSS flat within 5 MB, no growth in coroutine count (debug probe), request count equal to the scheduler's plan ± 2%.

---

## 6. T5 — field validation

- **Station shadow ride:** loops within 2 km of an airport ASOS on open ground. The 10 m field should track the station's 5-minute reports; the rider-height field should sit near the Open-preset ratio. Compare from the FIT developer fields afterwards.
- **Out-and-back:** 5 km each way on a flat, exposed road at steady power. The virtual sensor's headwind on the two legs should be equal and opposite within 1 m/s once the bias state has settled; if not, CdA/Crr settings are off.
- **Optional gold standard:** a bike-mounted anemometer or aero sensor for a handful of rides, logged to the same FIT file, as an independent check on Metric B.
- **Side-by-side:** TrueWind and upstream Headwind fields on the same page (the fork installs alongside). Note disagreements with the felt wind and feed those rides into the corpus.

Every field ride automatically becomes replay corpus, because the FIT file records what the extension showed.

---

## 7. CI

GitHub Actions on the fork:

- **PR:** `./gradlew build` (unit + integration), replay on the fixture corpus, metrics diff against `docs/accuracy-baseline.json` posted as a job summary, APK size check. Target under 6 minutes with Gradle cache.
- **Nightly:** full-corpus replay (corpus cache restored from an Actions cache or a release asset), `tools/recorder` run to extend the forward corpus, a live smoke request to each keyless provider to catch schema or model-ID changes early (this is what will notice an Open-Meteo model being withdrawn).
- **Manual, pre-release:** T3 smoke and T4 scripts against the connected Karoo; attach the battery report to the release notes.

Secrets needed in CI: `GPR_USER`/`GPR_KEY` for karoo-ext (the default `GITHUB_TOKEN` works, as upstream does), signing keystore values for release builds. No weather API keys are needed for any automated test; keyed providers are tested from fixtures only.

---

## 8. Before testing can start

1. Authorise adb on the Karoo — it currently shows `unauthorized`.
2. Install JDK 25 and the Android SDK (platform 37) on the Mac.
3. Add `read:packages` to the GitHub token used for Gradle.
4. Pull existing rides off the Karoo or export them, to seed the corpus.
