# core/providers

Package `io.github.kf6999.truewind.core.providers`.

Owned by **WP2** (`providers/openmeteo/`, `ModelRegistry.kt`: Open-Meteo multi-model 15-minute request builder and parser, SPEC 4.2) and **WP3** (`providers/obs/`: AWC METAR, NWS, Synoptic observation parsers, SPEC 4.3).

Implement the interfaces in `core/api/Providers.kt` (`ForecastProvider`, `ObservationProvider`, `HttpClient`). Request builders and parsers only; no Android, no karoo-ext, no network I/O except through `HttpClient`. Fixtures go in `core/src/test/resources/providers/...`.
