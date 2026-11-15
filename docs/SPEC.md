# TrueWind — a higher-accuracy fork of karoo-headwind

Implementation spec for coding agents. Working name "TrueWind"; rename freely, but do not use the WindField name, branding or assets.

- **Upstream:** https://github.com/timklge/karoo-headwind (Apache-2.0), reviewed at commit `c9be073` (2026-10-17).
- **Target devices:** Hammerhead Karoo 2 and Karoo 3, via `io.hammerhead:karoo-ext` 1.1.9.
- **Companion document:** [TESTING.md](TESTING.md) — accuracy, performance and battery gates. A work package is not done until its gates there pass.

---

## 1. Goal and non-goals

**Goal.** Live wind and weather data fields whose headline number is the wind the rider actually feels (speed and direction at rider height, at the rider's position, now), with a displayed confidence, while costing no more CPU, network or battery than upstream Headwind.

**Success is measured, not asserted.** The fork must beat an emulation of upstream's behaviour on the replay corpus by the margins in TESTING.md §3, and stay inside the device budgets in TESTING.md §5.

**Non-goals for v1:** radar imagery, lightning, wildfire overlays, air quality, finish-alert emails, Strava/RWGPS integration, accounts or subscriptions, any backend server. Everything runs on the device against public APIs.

---

## 2. What the research found

### 2.1 WindField (the commercial reference)

Source: windfield.app pricing, features and manual pages, November 2026.

| Capability | Free | PRO ($12/yr) | Ultimate ($30/yr) |
|---|---|---|---|
| Weather refresh | 60 min | 10 min | 5 min |
| Weather sources | 1 | 4, "Recommended" auto-picks best for the area and re-checks during the ride | adds averaging across all 4 |
| Radar | – | 30 min | 10 min, zoom |
| Other | | AQI (PurpleAir), Race Mode (4 h of wind/temp stored for phone-less riding) | Lightning distance (Weatherbit), rain-timing and NWS alerts, minute-by-minute rain bar, finish alerts, web ride planner |

Fields worth matching: Wind Factor (pure head/tail component), effective wind direction, gusts, feels-like, wind chill, air density, distance from weather station, last/next update.

What WindField's accuracy story amounts to: **pick or average among several forecast sources, refresh often, and surface station distance.** It does not publish a height correction or any on-bike measurement. Its own battery test (Edge 1050, 5-min polling with radar, indoors, uncontrolled) showed 44 h 29 min against 47 h 49 min, roughly 7% — a useful ceiling for our budget.

TrueWind gets the Ultimate-tier accuracy behaviours (multi-source, auto-weighting, 5–10 min refresh, phone-less carry-over) for free, then adds three things WindField does not describe: station-observation correction, rider-height downscaling, and a power-based on-bike wind estimate.

### 2.2 Upstream Headwind — accuracy and efficiency gaps

All file paths are under `app/src/main/kotlin/de/timklge/karooheadwind/`.

| # | Gap | Where | Effect |
|---|---|---|---|
| G1 | 10 m model wind is shown as the rider's wind. No height or roughness reduction. | `datatypes/HeadwindSpeedDataType.kt` (`cos(...) * windSpeed`) | Systematic overstatement. Log-law ratio from 10 m to 1.2 m is about 0.64 over open grass and 0.54 over crops/hedges. |
| G2 | Position is rounded to a 3 km grid by default before fetching. Refetch happens only when the rounded position changes or after 1 hour. | `HeadingFlow.kt` `getGpsCoordinateFlow`, `KarooHeadwindExtension.kt` (`delay(1.hours)`) | Stale data during frontal passages and sea-breeze onset; wrong grid cell in complex terrain. |
| G3 | One forecast model (Open-Meteo `best_match`, or OpenWeatherMap). | `weatherprovider/openmeteo/OpenMeteoWeatherProvider.kt` | No way to detect or correct a model that is wrong today. A live probe for this spec (San Francisco, 2026-11-14) had HRRR at 211°, NBM at 180°, ECMWF at 159° and RRFS at 199° for the same minute. |
| G4 | Hourly forecast steps, linearly interpolated. Open-Meteo's 15-minute data is unused. | `DataStore.kt` `lerpWeatherTime` | Smears gust fronts and wind shifts. |
| G5 | No observations. | – | Nothing anchors the forecast to reality. |
| G6 | Heading is GPS course only (`LOC_BEARING`). | `HeadingFlow.kt` `getHeadingFlow` | Noisy below about 8 km/h and meaningless when stopped; the arrow swings at junctions and on steep climbs. |
| G7 | Relative grade and resistance fields use fixed air density 1.225, CdA 0.4, Crr 0.005. | `datatypes/RelativeGradeDataType.kt` | Up to about 15% error in drag at altitude or in heat. |
| G8 | Every data type builds its own flow chain: its own GPS subscription, DataStore read and 1-minute loop; `Log.d`/`Log.i` on every GPS fix and every emit; Glance recomposition on every tick. | `datatypes/BaseDataType.kt`, `DataStore.kt` `streamCurrentWeatherData`, `HeadingFlow.kt` | Duplicated work that scales with the number of fields on the page. |
| G9 | Failure retry is a fixed 2 minutes forever, with no backoff. | `KarooHeadwindExtension.kt` `.retry` | Wasted radio time when the phone is out of coverage. |

Things upstream does well and the fork must keep: all HTTP goes through `OnHttpResponse.MakeHttpRequest` (works over Wi-Fi or the phone's Bluetooth link), route-aware forecasts, FIT developer fields, the AIDL service and `client` module, visible-only view updates (`streamDatatypeIsVisible`).

### 2.3 Karoo SDK facts that constrain the design

- `MakeHttpRequest` documentation: keep payloads under 100 KB, because requests may travel over Bluetooth to the companion app. Budget in this spec: under 15 KB per response, gzip-encoded where the API offers it.
- Available streams used here: `LOCATION` (lat, lon, bearing, accuracy), `HEADING`, `SPEED`, `POWER`, `CADENCE`, `ELEVATION_GRADE`, `PRESSURE_ELEVATION_CORRECTION`, `TEMPERATURE`, `SMOOTHED_3S_AVERAGE_POWER`. Events: `RideState`, `UserProfile` (weight, units), `OnNavigationState`, `ActiveRidePage`, `Bikes`.
- There is no SDK stream for raw barometric pressure. Android `SensorManager` (`TYPE_PRESSURE`, `TYPE_ROTATION_VECTOR`) may work on Karoo hardware; **probe on device before relying on it** (WP6, WP5).
- Views are Glance `RemoteViews` pushed to the Karoo ride app. Each `updateView` is an IPC with a serialized view tree; this is the main per-tick cost.

### 2.4 Data sources evaluated

Verified live on 2026-11-14 unless marked otherwise.

| Source | Key | What it adds | Limits / caveats | Role |
|---|---|---|---|---|
| **Open-Meteo forecast** | none | Many models in one call via `models=`; 15-minute steps (`minutely_15`) in North America and Central Europe; multiple coordinates per call | Free tier is non-commercial, 10,000 calls/day; CC BY 4.0 attribution required; multi-model and many-variable requests count as fractional extra calls | **Primary forecast ensemble** |
| **Aviation Weather Center METAR** `aviationweather.gov/api/data/metar?bbox=…&format=json` | none | Worldwide airport observations; wind in **knots**, direction, gust, temp, lat/lon/elev. A 1°×1° box around SF returned 18 reports in 8.7 KB | "Keep requests limited in scope and frequency"; most stations report hourly plus specials, some every 5–20 min | **Primary observations** |
| **NWS** `api.weather.gov` | none (User-Agent header required) | US station list per grid point, latest observations, alerts | US only; rate limit undisclosed but generous | US observation supplement; alerts later |
| **Synoptic Data** Open Access | free token, non-commercial | Dense mesonets (RAWS, DOT road weather, CWOP) — far more stations than METAR, radius queries | Account + token; third-party pages cite about 5,000 requests/month on the free tier — **verify current limit at signup** | Optional observations, biggest gain away from airports |
| **Tomorrow.io** | free key | Proprietary blended nowcast | 500 requests/day, 25/hour, 3/s on the free plan | Optional extra ensemble member |
| **Pirate Weather** | free key | HRRR-based, Dark Sky-compatible, minutely | Free quota reported inconsistently (10,000/month in its own older post) — verify | Optional extra member |
| **OpenWeatherMap One Call 3.0** | key + payment card | Already supported upstream | Card required even for the free 1,000/day | Keep existing provider as optional member |

**No API key is required for the core design.** Keys are optional accuracy add-ons entered in settings.

**Model availability is a moving target.** Open-Meteo's docs page currently lists `NCEP HRRR U.S. CONUS` as "Discontinued 2026-11-30" (RRFS replaces it), with NAM, ICON Global/EU/D2 and several others also marked discontinued. Never hardcode a model list without a fallback; see §4.2.

---

## 3. Architecture

```
┌────────────── app (Android) ───────────────┐
│ KarooTrueWindExtension (service)           │
│  ├─ SensorHub      one subscription per    │
│  │                 Karoo stream, shared    │
│  ├─ FetchScheduler decides when to fetch   │
│  ├─ KarooHttp      MakeHttpRequest wrapper │
│  ├─ WindRepository StateFlow<WindState>    │
│  ├─ DataTypes      thin mappers + views    │
│  ├─ FitWriter      developer fields        │
│  └─ Settings / cache (DataStore)           │
└───────────────┬────────────────────────────┘
                │ pure Kotlin interfaces
┌───────────────┴──── core (JVM library) ────┐
│ providers/   request builders + parsers    │
│ fusion/      model weighting, obs residual │
│ physics/     log law, air density, power   │
│ heading/     course/compass fusion         │
│ virtualwind/ 3-state Kalman filter         │
│ replay/      FIT + archive backtest CLI    │
└────────────────────────────────────────────┘
```

Rules:

1. **`core` has no Android or karoo-ext dependency.** Everything numerical lives there so it runs in JVM unit tests and in the replay harness on a laptop. `app` adapts Karoo streams to `core` inputs.
2. **One hot pipeline.** `WindRepository` exposes a single `StateFlow<WindState>` built with `stateIn(scope, SharingStarted.WhileSubscribed(5_000), …)`. Data types map from it; none opens its own GPS, settings or DataStore flow. This removes G8.
3. **Fetch and tick are separate.** Network fetches fill a time-indexed cache (minutes apart). A local tick (1 Hz while a wind field is visible or FIT recording is on; otherwise stopped) reads sensors and the cache and recomputes `WindState`. No network on the tick path.
4. **Emit on change.** Streams emit only when the displayed value would change (speed by ≥ 0.5 display unit, direction by ≥ 3°, confidence band change). Views update at most once per second per field and only while visible.
5. **No wakelocks, no AlarmManager, no foreground service of our own.** Work exists only while the Karoo system has the extension bound.
6. **Logging:** no per-tick or per-fix logs in release builds. Guard debug logs behind `BuildConfig.DEBUG`.

### 3.1 Core data model

```kotlin
data class WindVector(val u: Double, val v: Double)   // m/s, meteorological: u east, v north, direction blowing TO

data class WindState(
    val timeMs: Long,
    val model10m: WindVector?,        // fused, bias-corrected, 10 m
    val rider: WindVector?,           // at rider height, what the headline fields show
    val gustRider: Double?,           // m/s
    val headwind: Double?,            // m/s, + into the rider, − tailwind
    val crosswind: Double?,           // m/s, + from the right
    val relativeDirectionDeg: Double?,// 0 = dead ahead, clockwise
    val confidence: Int,              // 0..100
    val sources: SourceSummary,       // models used, nearest station id, distance km, obs age min, virtual-sensor active
    val ageMs: Long,                  // since last successful fetch
    val airDensity: Double,           // kg/m³
    val weather: WeatherNow?,         // temp, humidity, precip, cloud, pressure, uvi, weatherCode
    val status: Status                // OK, NO_GPS, NO_DATA, STALE, NOT_SET_UP
)
```

Compute in u/v components everywhere; convert to speed/direction only at the display edge. Never average directions as angles.

---

## 4. Accuracy pipeline

Five layers. Each is independently switchable in settings and in the replay harness so its contribution can be measured.

### 4.1 Fetch policy (fixes G2, G9)

A fetch cycle runs when **any** of these holds, and only while a ride is recording or a TrueWind field/screen is visible:

- no cache, or cache older than the refresh interval (default 10 min; options 5/10/15/30);
- rider is more than 4 km from the last fetch point;
- the loaded route changed;
- adaptive speed-up: observation residual (§4.3) changed by more than 2 m/s or 40° since the previous cycle → next interval is 5 min for the following 30 min;
- adaptive back-off: model spread under 1 m/s and residual under 1 m/s for three cycles → interval ×1.5, capped at 20 min.

Each cycle sends its requests back-to-back so the Bluetooth link wakes once. Each forecast response carries 15-minute steps from −30 min to +4 h, so the device keeps producing interpolated values for four hours without a phone (the Race Mode equivalent). Hourly data out to 12 h is fetched at most every 60 min for the forecast widgets.

Failure handling: exponential backoff 1, 2, 4, 8 min, capped at 10 min, reset on success or on a connectivity change. HTTP 429 → honour `Retry-After`, drop optional providers first.

Location privacy: keep the rounding setting, add "Exact", and change the default to 1 km. Bearing is never sent anywhere.

### 4.2 Forecast ensemble (fixes G3, G4)

One Open-Meteo request per cycle:

```
https://api.open-meteo.com/v1/forecast
  ?latitude=<rider>,<stn1>,<stn2>,<stn3>&longitude=…
  &minutely_15=wind_speed_10m,wind_direction_10m,wind_gusts_10m,temperature_2m,relative_humidity_2m,precipitation
  &current=surface_pressure,pressure_msl,cloud_cover,weather_code,is_day,uv_index
  &models=<region set>
  &past_minutely_15=2&forecast_minutely_15=16
  &wind_speed_unit=ms&timeformat=unixtime
```

- Coordinates: the rider plus up to three nearest observation stations (known from the previous cycle), so each model can be scored where truth exists. With a route loaded, add route sample points as upstream does (max 10 coordinates total).
- Response keys are suffixed per model (`wind_speed_10m_ncep_hrrr_conus`, …). A probe with 6 models, 4 variables and 10 steps for one location was 3.5 KB.
- **Model registry** (`core/providers/ModelRegistry.kt`, data-driven, overridable from a JSON asset):

  | Region (by lat/lon box) | Members, in priority order |
  |---|---|
  | CONUS | `ncep_hrrr_conus` (until removed), `ncep_rrfs_conus`, `ncep_nbm_conus`, `ecmwf_ifs`, `gfs_seamless` |
  | Europe | highest-resolution national model for the box, plus `ecmwf_ifs`, `gfs_seamless` — **look up current IDs on the Open-Meteo docs page at implementation time; several ICON and AROME IDs are being renamed** |
  | Elsewhere | `best_match`, `ecmwf_ifs`, `gfs_seamless` |

  The six CONUS/global IDs above were verified valid on 2026-11-14. At runtime, a member that returns all-null or an API error is dropped for 24 h and the request retried without it; if the set would be empty, fall back to `best_match` alone. `best_match` duplicated HRRR exactly in the probe, so do not include both.
- Optional keyed members (Tomorrow.io, Pirate Weather, OpenWeatherMap) implement the same `ForecastProvider` interface and are sampled at the rider location only, at most every 15 min each.

### 4.3 Observation anchoring (fixes G5)

Providers implement `ObservationProvider.fetch(center, radiusKm): List<Observation>`.

- **AWC METAR** (default): bbox of ±0.35° around the rider, `hours=1`, keep the newest report per station. Convert knots → m/s. Variable wind (`wdir = "VRB"`) contributes speed only. Calm (0 kt) is a valid observation.
- **NWS** (US): `points/{lat},{lon}` once per ~25 km of travel (cache the grid and station list), then `stations/{id}/observations/latest` for the two nearest non-METAR stations.
- **Synoptic** (if token set): `stations/latest` within 25 km, wind variables only, limit 8.

Quality control, in `core/fusion/ObsQc.kt`: reject age > 30 min; distance > 30 km; elevation difference from the rider > 250 m; speed > 40 m/s; a station that disagrees with the median of its neighbours by more than 3 σ. Assume a 10 m anemometer for METAR; use the reported sensor height when Synoptic supplies one and normalise to 10 m with the log law.

**Model weighting.** For each member *m* and each station *s*, vector error `e = model_m(s, t_obs) − obs(s)`. Keep an exponentially weighted mean-square error per member with a 60-min half-life: `w_m ∝ 1 / (ε + mse_m)`, ε = 0.5 (m/s)², normalised, floor 0.05. With no observations the weights are the registry priors. Persist the last weights per ~40 km cell so the next ride starts informed. This is WindField's "Recommended"/"Average" behaviour made continuous.

**Blend.** `blend(x, t) = Σ w_m · model_m(x, t)` in u/v, with 15-minute steps interpolated linearly in u/v.

**Residual correction.** At each station, `r_s = obs_s − blend(s, t_obs)`. Correction at the rider is the weighted mean of `r_s` with weights `exp(−d/15 km) · exp(−age/30 min)`, and the correction itself is scaled by `min(1, Σweights)` so a single distant, old station nudges rather than overrides. `model10m = blend(rider, now) + correction`.

**Confidence (0–100).** Start at 100 and subtract: weighted inter-model spread (10 points per m/s, cap 40); residual magnitude (10 per m/s, cap 30); no observation within 30 km/30 min (15); data age beyond one interval (1 per min, cap 30); speed under 1.5 m/s makes direction unreliable (direction-only flag, not a deduction). Bands: ≥ 70 high, 40–69 medium, < 40 low.

### 4.4 Rider-height downscaling and air density (fixes G1, G7)

`U(z) = U10 · ln(z / z0) / ln(10 / z0)`, with z = rider torso height (default 1.2 m, setting 0.9–1.5).

| Exposure preset | z0 (m) | Ratio to 10 m at z = 1.2 m |
|---|---|---|
| Open (coast, plains, airfield) | 0.03 | 0.64 |
| Mixed (farmland, hedges) — default | 0.10 | 0.54 |
| Sheltered (suburb, tree-lined) | 0.30 | 0.40 |
| Urban / forest | – | clamp at 0.30; the log law is not valid inside the canopy |

Gusts use the same ratio plus 0.1 (gusts penetrate further), capped at 1.0. Direction is unchanged. The preset is the prior; when the virtual wind sensor (§4.6) is active it learns the effective ratio for the current stretch of road and the preset matters less.

Keep an "absolute wind (10 m)" field so the forecast number is still available and comparable with other apps.

Air density: `ρ = (p_d / (287.05·T)) + (p_v / (461.5·T))`, with T and humidity from the fused weather, and pressure from model `surface_pressure` corrected from model grid elevation to rider elevation by the hypsometric equation (rider elevation from the Karoo). If `SensorManager.TYPE_PRESSURE` works on the device, prefer it. Use ρ in relative grade, resistance forces and the virtual wind sensor.

### 4.5 Heading fusion (fixes G6)

`core/heading/HeadingEstimator.kt`, inputs: GPS course, GPS speed, Karoo `HEADING` stream, wheel speed.

- Speed ≥ 3 m/s: GPS course, circular exponential smoothing with τ = 1.5 s.
- 1–3 m/s: complementary blend of GPS course and `HEADING`, weight shifting linearly to `HEADING` as speed falls.
- Under 1 m/s or stopped: hold the last moving heading; if `HEADING` is available and has tracked GPS course within 20° over the previous 2 minutes of riding, follow it instead.
- Reject course jumps over 60° in one fix when GPS accuracy is worse than 20 m.
- When navigating and on-route with GPS accuracy worse than 25 m (tunnels, canyons), use the route segment bearing.

WP6 must first log `HEADING` against GPS course on the device for one ride and report whether `HEADING` is magnetometer-based, course-based or absent on Karoo 2 vs 3; the low-speed branch depends on the answer.

### 4.6 Virtual wind sensor (new; needs a power meter)

The only source of truth available on the bike itself. From the power balance, air speed along the direction of travel is

```
F_aero = (P·η)/v − m·g·(sinθ + Crr·cosθ) − m_eff·a
v_air  = sign(F_aero) · sqrt(|F_aero| / (½·ρ·CdA))
headwind_meas = v_air − v
```

η = 0.975 drivetrain, m = rider + bike (rider weight from `UserProfile`, bike weight setting, default 9 kg), m_eff = m + 0.15 kg wheel inertia allowance, θ from `ELEVATION_GRADE`, a from differentiated speed over 3 s, P = 3 s power.

Samples are valid only when: P ≥ 80 W and cadence > 0; v ≥ 4 m/s; |a| < 0.5 m/s²; |grade| < 8%; not within 5 s of a zero-power sample (coasting/braking). Drafting cannot be detected; a "riding in a group" toggle disables the sensor.

**Filter.** 3-state Kalman filter, state `x = [wu, wv, b]`: rider-height wind vector and a heading-independent bias `b` (absorbs CdA, Crr, mass and drivetrain errors, which look like a constant head/tail offset).

- Measurement every 5 s when valid: `headwind_meas = −(wu·hu + wv·hv) + b`, where `h` is the heading unit vector. Variance from power and grade noise, default (1.5 m/s)².
- Pseudo-measurement every tick: `[wu, wv] = ratio · model10m`, variance from confidence — (1 m/s)² at 100, (4 m/s)² at 0.
- Process noise: 0.05 (m/s)²/min on wind, 0.002 on bias.
- `b` is observable only with heading diversity. Track the spread of headings over the last 15 min; until it exceeds 60°, freeze `b` at its persisted value (per bike, from `Bikes`).

Output: the filtered `[wu, wv]` becomes `WindState.rider`; the learned effective ratio `|w| / |model10m|` is exposed for diagnostics. With no power meter, or the sensor disabled, `rider = ratio_preset · model10m`.

Settings: CdA (default 0.32 road hoods, presets for drops/TT/gravel/MTB), Crr (default 0.005, presets by surface), bike weight. Expose the filter innovation as a debug field; a persistent non-zero innovation means the CdA setting is wrong.

---

## 5. Data fields

New extension id (for example `truewind`) and application id, so it installs alongside upstream. Keep upstream's `typeId` strings where a field is carried over, so users rebuilding pages find familiar names.

| Field | Type | Notes |
|---|---|---|
| Headwind (graphical, existing `headwind`) | graphical 1×1 | Now rider-height. Arrow outline solid / dashed / dotted for high / medium / low confidence. Grey when `STALE`. |
| Tailwind and ride speed, wind direction and speed (existing) | graphical | Rider-height by default; setting to show 10 m. |
| Headwind speed (existing `headwindSpeed`) | numeric | Rider-height. This is WindField's "Wind Factor". |
| **Crosswind** | numeric | Signed; + from the right. |
| **Wind 10 m** speed / gust / direction (existing `windSpeed`, `windGusts`, `windDirection`) | numeric | Unchanged meaning: fused 10 m values. |
| **Wind confidence** | numeric 0–100 | |
| **Wind source** | graphical 1×1 text | For example `HRRR+NBM · KSQL 6 km · 4 min`. |
| **Measured headwind** | numeric | Raw virtual-sensor value; `--` when invalid. |
| **Air density** | numeric | kg/m³. |
| **Feels like** | numeric | Wind chill below 10 °C using rider air speed; heat index above 27 °C. |
| **Rain in** | numeric minutes | First 15-min step with precipitation ≥ 0.1 mm in the next 2 h; `--` if none. |
| Forecast widgets, relative grade, relative elevation gain, resistance forces, headwind time, time distribution, compass, temperature, humidity, cloud cover, precipitation, pressure, UV (all existing) | as upstream | Fed from `WindState`; physics fields use live ρ and the configured CdA/Crr. |

FIT developer fields: keep upstream's; add `rider_wind_speed`, `rider_wind_direction`, `wind_confidence`, `measured_headwind`, `air_density` (record, ≤ 1 Hz) and `wind_sources` (session string). These make every ride a validation sample for TESTING.md §3.

Settings screen additions: refresh interval, exposure preset, rider height, CdA/Crr/bike weight, group-ride toggle, per-layer enable switches, optional API keys with a "Test" button each, a diagnostics page (member weights, stations in use, last fetch sizes and durations, request count today).

AIDL service and `client` module: bump the snapshot schema to version 2, add the `WindState` fields, keep version-1 fields populated.

---

## 6. Work packages

Each package lists the files it owns. Two agents must not edit the same files concurrently. "Done" always includes: unit tests in the same PR, `./gradlew build` green, and the named TESTING.md gate.

| WP | Title | Depends on | Owns | Done when |
|---|---|---|---|---|
| 0 | Fork and bootstrap | – | root Gradle files, `AndroidManifest.xml`, `extension_info.xml`, CI workflow, `NOTICE` | Fork of `timklge/karoo-headwind` builds a debug APK under the new application id and extension id; manifest URL and `generateManifest` point at the fork's releases; `NOTICE` states the fork and changes; upstream copyright headers retained; installs on the Karoo next to upstream. |
| 1 | `core` module and single pipeline | 0 | new `core/`, `WindRepository`, `SensorHub`, `BaseDataType.kt`, `DataStore.kt` | All existing fields render from one `StateFlow<WindState>` with behaviour identical to upstream (golden test on a recorded ride); per-tick logging removed. Gate: TESTING §5 budgets measured and no worse than upstream. |
| 2 | Open-Meteo ensemble provider | 1 | `core/providers/openmeteo/`, `ModelRegistry` | Multi-model 15-min request builder and parser with fixtures for CONUS, Europe and a no-15-min region; null-member fallback tested. |
| 3 | Observation providers | 1 | `core/providers/obs/` | AWC, NWS and Synoptic parsers with fixtures (including VRB, calm, missing gust, stale); QC rules tested. |
| 4 | Fusion engine | 2, 3 | `core/fusion/` | Weighting, blend, residual correction and confidence as §4.3. Gate: TESTING §3 accuracy margin for 10 m wind. |
| 5 | Downscaling and air density | 1 | `core/physics/` | §4.4 implemented; physics fields use live ρ; device probe of `TYPE_PRESSURE` reported. |
| 6 | Heading fusion | 1 | `core/heading/` | §4.5 implemented after the on-device `HEADING` investigation; gate: heading metric in TESTING §3.4. |
| 7 | Virtual wind sensor | 4, 5, 6 | `core/virtualwind/` | §4.6 filter; converges on synthetic rides; gate: virtual-elevation metric in TESTING §3.3. |
| 8 | Fetch scheduler | 2, 3 | `FetchScheduler`, `KarooHttp` | §4.1 policy with a fake clock test suite; backoff and 429 handling; request/byte counters exposed. |
| 9 | Fields, views, settings | 4, 5, 6 (7 optional) | `datatypes/`, `screens/`, `res/` | §5 fields and settings; emit-on-change and ≤ 1 Hz view updates verified by counter test. |
| 10 | FIT and AIDL v2 | 9 | `fit/`, `aidl/`, `client/` | New developer fields round-trip in tests; client example updated. |
| 11 | Replay harness | 1 | `core/replay/`, `tools/` | TESTING §3 CLI runs on the fixture corpus in CI and writes the metrics JSON. Start this right after WP1; WP4–7 are judged with it. |
| 12 | Device perf tooling | 1 | `scripts/` | TESTING §5 scripts run end-to-end against the connected Karoo and produce the A/B report. |

Suggested order: 0 → 1 → {2, 3, 5, 6, 11, 12 in parallel} → {4, 8} → 7 → 9 → 10.

---

## 7. Environment and prerequisites

State of this Mac on 2026-11-14:

- **JDK:** OpenJDK 25 is installed via Homebrew but is not the default (`java` on PATH is still 17). Build with `export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`. Upstream CI builds with JDK 25 (AGP 9.4.1, Kotlin 2.4.20, Gradle 9.8.0, compileSdk 37, minSdk 26).
- **Android SDK:** installed at `/opt/homebrew/share/android-commandlinetools` (platforms 34–36 and 37.0, build-tools up to 37.0.0, licenses accepted). `ANDROID_HOME` is not set globally; export it or write `sdk.dir` into `local.properties`. `adb` is at `/opt/homebrew/bin/adb`.
- **GitHub Packages credentials:** karoo-ext is served from `maven.pkg.github.com/hammerheadnav/karoo-ext`, which requires authentication even though it is public. Upstream reads `GPR_USER`/`GPR_KEY` env vars or `gpr.user`/`gpr.key` in `local.properties`. The current `gh` token for account `kf6999` has scopes `gist, read:org, repo, workflow, write:gpg_key` — **no `read:packages`**. Run `gh auth refresh -s read:packages` (interactive) and use `gh auth token` as `GPR_KEY`. Never commit it.
- **Karoo:** Karoo 3 (`k24`, Android 12), connected and authorized over USB as `00442GA241750051`. Upstream Headwind is not installed on it; `de.timklge.karooroutegraph` is.
- **Signing:** release builds read `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Generate a new keystore for the fork; debug builds need none.
- **Optional API keys:** not created (the browser automation bridge failed during research). Likely signup pages, not opened or verified: `customer.synopticdata.com` (Open Access), `app.tomorrow.io/signup`, `pirate-weather.apiable.io`. Keys are entered in the app's settings on the device, never built into the APK or committed.

Licensing: upstream is Apache-2.0 — keep `LICENSE`, keep existing copyright headers, add a `NOTICE` describing the fork, mark modified files. Open-Meteo data is CC BY 4.0 and its free endpoint is non-commercial: show attribution on the settings screen and do not publish the fork commercially without their paid plan. Synoptic Open Access is non-commercial.

---

## 8. Open questions to resolve during implementation

1. What does the Karoo `HEADING` stream measure on K2 and K3? (WP6)
2. Does `SensorManager.TYPE_PRESSURE` deliver data to a third-party app on Karoo? (WP5)
3. Current Open-Meteo model IDs for Europe, and whether `minutely_15` honours `models=` for every member or silently interpolates hourly data. (WP2 — record the answer in `ModelRegistry` comments.)
4. How Open-Meteo counts a 5-model × 4-location × 6-variable request against the daily quota. Measure with the diagnostics counter; target under 1,500 weighted calls per 8-hour ride.
5. Synoptic free-tier monthly quota today; set the default Synoptic cadence from it.

---

## 9. WindField Ultimate parity (phase 2)

Added after the owner asked for full Ultimate parity. These packages start once WP1 is merged and may run in parallel with WP2–12. Everything stays on-device and keyless unless noted. Endpoints marked ✔ were probed live on 2026-11-14.

### 9.1 Parity matrix

| WindField Ultimate feature | TrueWind approach | Honest gap |
|---|---|---|
| 5-min updates, 4 sources, auto-select, averaging | WP2–4, WP8 (5/10/15/30 min setting, ensemble weighting) | none; exceeds |
| Race Mode (4 h without phone) | 4 h of 15-min steps cached every fetch (§4.1) | none |
| Radar images every 10 min, zoom | **WP13.** RainViewer public API ✔ (`api.rainviewer.com/public/weather-maps.json`, then 256 px PNG tiles from `tilecache.rainviewer.com{path}/256/{z}/{x}/{y}/2/1_1.png`; an empty tile was 334 bytes). Graphical 2×2 field: 1–4 tiles around the rider, rider dot, heading tick, wind arrow; tap cycles zoom 6/7/8; refresh 10 min; animate last 3 frames only when the field is visible | Coverage and attribution per RainViewer terms (non-commercial, show "RainViewer") |
| Active wildfire overlay | **WP13.** US: NIFC public ArcGIS perimeter service (keyless; find the current FeatureServer URL at implementation time), query by bbox, draw outline on the radar field; alert if a perimeter is within 15 km | Non-US fires need a NASA FIRMS key (free, not created) |
| Real-time lightning distance | **WP14.** No free keyless strike feed exists. Approximation: distance to the nearest radar core at or above ~45 dBZ (from the RainViewer tile colour scale) combined with METAR thunderstorm codes (`TS`, `VCTS`, `LTG` remarks) and NWS severe-thunderstorm polygons. Field "Storm distance" with a "est." marker. Provider interface left open for a real strike feed (Tomorrow.io premium, Weatherbit, Xweather) | **Not true strike data.** State this in the field description |
| Ride insight alerts: rain timing, wildfire, NWS severe weather | **WP14.** `InRideAlert` effects. Rain: first 15-min step ≥ 0.2 mm within 45 min → "Rain in N min"; when raining, "Rain ends in N min". Sharpen with Tomorrow.io 1-minute timeline when a key is set. NWS: `api.weather.gov/alerts/active?point=lat,lon` ✔ every 10 min in the US, alert on new Severe/Extreme events. Wind: gust ≥ configurable threshold; wind shift ≥ 60° expected within 30 min | NWS alerts are US only, as in WindField |
| Minute-by-minute rain bar | **WP14.** Graphical 2×1 bar chart of the next 60–120 min: 15-min steps from Open-Meteo, 1-min from Tomorrow.io when keyed | 15-min resolution without a key |
| Start-of-ride 6-hour forecast pop-up | **WP14.** On `RideState.Recording` after Idle: one `InRideAlert` summarising wind trend, temperature range, rain chance for the next 6 h (along the route if loaded) | none |
| Air quality (AQI, PM2.5, ozone, UV, solar) | **WP15.** Open-Meteo air-quality API ✔ (`air-quality-api.open-meteo.com/v1/air-quality?current=us_aqi,pm2_5,ozone,uv_index`), every 30 min. Fields: AQI (coloured), PM2.5, ozone | Model-based, not PurpleAir sensors; a PurpleAir key could be added later |
| Extra optional fields | **WP15.** Dew point, visibility, solar radiation, wind chill, feels-like, max wind this ride, last update (min ago), next update (countdown), weather-station distance, effective wind direction, sunrise/sunset are native Karoo fields already. Verify exact Open-Meteo variable names before use | none |
| Finish alert to a contact | **WP16.** With a route loaded, when time-to-destination first drops under 60/30/15 min (setting), POST once to a user-configured HTTPS URL (ntfy topic or any webhook) with rider-chosen text and ETA. No location in the payload unless the user opts in | Not email; needs the contact to subscribe to the ntfy topic or the user to wire the webhook. No backend |
| Web ride planner with departure time | **WP16.** In-app "Route weather" screen: 8 points along the loaded route for a chosen start time (now, +1 h … +12 h), wind relative to route bearing, temperature, rain, a ride score; "better start" hint by scanning the next 12 h | On the device, not a website; only for the loaded route |
| Daily pre-flight briefing email, Strava/RWGPS reposting and awards, trophy case, team kit, bonus Garmin apps | **Not built.** These are WindField's web/account services and need a server and third-party OAuth apps | Out of scope for an on-device extension; FIT developer fields carry the wind data into any platform that reads them |

### 9.2 Additional work packages

| WP | Title | Depends on | Owns | Done when |
|---|---|---|---|---|
| 13 | Radar and wildfire field | 1, 8 | `core/providers/radar/`, `datatypes/Radar*`, `screens/Radar*` | Tile maths unit-tested; field renders on the Karoo from live tiles; ≤ 4 tiles (≤ 100 KB) per refresh; no fetch when hidden |
| 14 | Alerts, rain bar, storm distance, start-of-ride summary | 2, 3, 13 | `core/alerts/`, `datatypes/Rain*`, `datatypes/Storm*` | Alert rules unit-tested with a fake clock (each fires once, with hysteresis); e2e replay shows an alert on a rainy fixture |
| 15 | Air quality and extra fields | 1 | `core/providers/airquality/`, `datatypes/` additions | Parsers with fixtures; fields listed in `extension_info.xml` |
| 16 | Finish alert and route weather screen | 2, 9 | `core/route/`, `screens/RouteWeather*`, `FinishAlert*` | Fires exactly once per ride in a fake-clock test; screen shows 8 points for a fixture route |

### 9.3 Keys now available

Stored outside every repo in `~/.config/truewind/keys.env` (mode 600) as `SYNOPTIC_TOKEN` and `TOMORROW_API_KEY`. Agents may `source` that file for live smoke tests and to enter keys into the app on the device over adb. **Never copy the values into the repo, fixtures, logs, commit messages or reports.** Fixtures recorded from keyed APIs must have the key stripped from URLs.

- Synoptic ✔: `https://api.synopticdata.com/v2/stations/latest?radius=<lat>,<lon>,<km>&limit=8&vars=wind_speed,wind_direction,wind_gust&within=30&units=metric&token=…` returned 5 stations within 1.6 km of the test point, observations 1–2 min old, 5.2 KB. Caveats found: `ELEVATION` is in feet; network `MNET_ID` 65 is citizen (CWOP) stations, often roof-mounted and sheltered — weight them below ASOS/AWOS (`MNET_ID` 1) and RAWS (2), and treat a 0.0 speed with 0.0 direction as "calm or sensor idle", not as a north wind.
- Tomorrow.io ✔: `https://api.tomorrow.io/v4/weather/realtime?location=<lat>,<lon>&units=metric&apikey=…` works; free plan 500/day, 25/hour.
- Pirate Weather: not created (email-code signup needs the owner). Not required.

### 9.4 End-to-end definition of done

`scripts/e2e.sh` on the connected Karoo 3 passes: build → install → replay-mode ride with every TrueWind field on a page → live fetch from each keyless provider plus Synoptic and Tomorrow.io → assertions from TESTING §4 (T3) → FIT file contains the developer fields → no crash/ANR. Plus `./gradlew build` and the replay accuracy gate (TESTING §3) green on the fixture corpus.
