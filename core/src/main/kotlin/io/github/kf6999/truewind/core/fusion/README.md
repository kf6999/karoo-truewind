# core/fusion

Package `io.github.kf6999.truewind.core.fusion`.

Owned by **WP4** (fusion engine): model weighting, u/v blend, observation residual correction, confidence (SPEC 4.3). `ObsQc.kt` (observation quality control) also lives here; WP3 may contribute QC rules in coordination with WP4.

Inputs: `ForecastSeries` and `Observation` from `core/api`. Output: `model10m`, `confidence` and `SourceSummary` for `core/model/WindState`.
