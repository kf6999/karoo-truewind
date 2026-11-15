# core/replay

Package `io.github.kf6999.truewind.core.replay`. **WP11** replay harness (TESTING.md section 3).

Library (this directory, `core` main): ride/archive model, archive-backed `ForecastProvider` /
`ObservationProvider`, the `upstream` Headwind emulation, layered configs, Metric A/B/C and the
runner. CLI and file loaders live in the `replay` source set (`core/src/replay/kotlin/.../cli`),
which also pulls in the Garmin FIT SDK; nothing there ships in the APK.

## CLI

```
./gradlew :core:replay --args="run --corpus corpus/fixtures --out build/replay/metrics.json --summary build/replay/summary.md"
    [--configs upstream,+ensemble,+obs,+height,+heading,+virtual] [--baseline FILE --max-regression 0.02]
./gradlew :core:replay --args="describe --corpus DIR"            # rides, forecast members, stations
./gradlew :core:replay --args="synthesize DIR"                   # regenerate corpus/fixtures/synthetic-front
./gradlew :core:replay --args="synthesize-ride DIR --center LAT,LON --start ISO --minutes N --id ID"
./gradlew :core:replay --args="fit2csv IN.fit OUT.csv"
./gradlew :core:replay --args="fit-devfields IN.fit"             # developer field names (FIT check)
./gradlew :core:replay --args="live-smoke [--point LAT,LON]"     # one live call per provider
```

`--corpus` is repeatable; a directory containing `rides/` is one corpus, otherwise every
sub-directory with `rides/` is loaded and merged. Corpus layout and tools: `corpus/README.md`.

## Configs and metrics

| config | 10 m layer | rider layer |
|---|---|---|
| `upstream` | Headwind emulation: best_match hourly, 3 km rounding, refetch on cell change / 1 h, 10 m wind = rider wind, raw GPS course | - |
| `+ensemble` | `LayerSeams.ensemble` (15-min members, skill weights) | 10 m, GPS course |
| `+obs` | `LayerSeams.obs` (ensemble + ObsQc + residual correction) | 10 m, GPS course |
| `+height` | as +obs | `RiderWindEstimator` height scaling |
| `+heading` | as +obs | + fused heading |
| `+virtual` | as +obs | + virtual wind sensor |

Metric A: leave-one-station-out, pipeline ticked at the held-out station (vector RMSE, speed
MAE/bias, circular direction MAE when obs >= 2 m/s, regimes calm/moderate/strong/shift, distance
buckets). Metric B: Chung virtual-elevation RMSE (10-min windows, CdA/Crr fitted on the calmest
20 min) and forward headwind prediction RMSE (t+60..300 s). Metric C: stopped heading jitter and
turn lag. `metrics.json` also carries per-ride numbers, fetch counts, `implementation` per layer
and `comparison_vs_upstream` with the TESTING 3.6 targets.

## Seams for other layers (`LayerSeams.kt`)

* **WP4/WP8 fusion engine** - when `core/engine/WindEngine` lands, add
  `WindEngineLayer(env): TenMeterWindLayer` that feeds `env.minutely15` / `env.hourly` /
  `env.observations` (archive providers counting requests) and is driven by `env.clock`
  (`ManualClock`), then set `LayerSeams.ensemble` / `LayerSeams.obs` to it. Until then both use
  `ReferenceEnsembleLayer` and report `"reference (fusion engine seam open)"`.
* **Rider layer** - `LayerSeams.rider` defaults to the production `RiderWindEstimator`.
* `ReplayEnvironment.heldOutStation` must be excluded from every observation request (the archive
  provider already does it).
