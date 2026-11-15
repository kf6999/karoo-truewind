/*
 * Copyright 2026 TrueWind contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.kf6999.truewind.core.model

/** WGS84 position in decimal degrees. */
data class GeoPoint(val lat: Double, val lon: Double)

/** Overall state of the wind pipeline, in precedence order NOT_SET_UP > NO_GPS > NO_DATA > STALE > OK. */
enum class Status { OK, NO_GPS, NO_DATA, STALE, NOT_SET_UP }

/** Where the current numbers come from (shown by the "Wind source" field, WP9). */
data class SourceSummary(
    /** Forecast members that contributed, e.g. `["open-meteo:best_match"]`. */
    val models: List<String> = emptyList(),
    val nearestStationId: String? = null,
    val nearestStationDistanceKm: Double? = null,
    val observationAgeMin: Double? = null,
    val virtualSensorActive: Boolean = false,
)

/**
 * Current weather at the rider, interpolated in space and time. Field set mirrors karoo-headwind's
 * `WeatherData` so existing fields can be rendered from it without loss.
 */
data class WeatherNow(
    /** Valid time of the interpolated value, unix seconds (karoo-headwind convention). */
    val timeSec: Long,
    val temperatureC: Double,
    val relativeHumidityPct: Int,
    val precipitationMm: Double,
    val precipitationProbabilityPct: Double? = null,
    val cloudCoverPct: Double,
    val sealevelPressureHpa: Double,
    val surfacePressureHpa: Double,
    /** 10 m wind speed, m/s. */
    val windSpeed: Double,
    /** 10 m wind direction the wind comes FROM, degrees. */
    val windDirectionDeg: Double,
    /** 10 m gust speed, m/s. */
    val windGusts: Double,
    val weatherCode: Int,
    val isForecast: Boolean,
    val isNight: Boolean,
    val uvi: Double,
)

/**
 * The single value every wind/weather field is rendered from (SPEC section 3.1). Produced by the
 * app's `WindRepository` as one `StateFlow<WindState>`.
 *
 * Fields beyond SPEC 3.1: [riderHeadingDeg] (heading used for the relative quantities, also drives
 * the compass field) and [position] (rider position used for interpolation, after privacy rounding).
 */
data class WindState(
    val timeMs: Long,
    /** Fused, bias-corrected 10 m wind. WP1: the single provider's interpolated wind. */
    val model10m: WindVector?,
    /** Wind at rider height; what headline fields show. WP1: equal to [model10m] (no downscaling yet). */
    val rider: WindVector?,
    /** Gust at rider height, m/s. WP1: 10 m gust. */
    val gustRider: Double?,
    /** m/s, + into the rider, - tailwind. */
    val headwind: Double?,
    /** m/s, + from the right. */
    val crosswind: Double?,
    /** Direction the wind comes from relative to the rider's heading: 0 = dead ahead, clockwise, [0, 360). */
    val relativeDirectionDeg: Double?,
    /** 0..100. WP1 has no confidence model and reports [CONFIDENCE_UNKNOWN]. */
    val confidence: Int,
    val sources: SourceSummary,
    /** Time since the last successful fetch, ms; null if there never was one. */
    val ageMs: Long?,
    /** kg/m3. WP1: the fixed sea-level value upstream uses. */
    val airDensity: Double,
    val weather: WeatherNow?,
    val status: Status,
    val riderHeadingDeg: Double? = null,
    val position: GeoPoint? = null,
) {
    /** True for the placeholder a StateFlow starts with before any input has been seen. */
    val isPlaceholder: Boolean get() = timeMs == PLACEHOLDER_TIME

    companion object {
        const val CONFIDENCE_UNKNOWN = 0
        const val STANDARD_AIR_DENSITY = 1.225
        private const val PLACEHOLDER_TIME = Long.MIN_VALUE

        /** Initial value for `stateIn`; filtered out by consumers via [isPlaceholder]. */
        val PLACEHOLDER = WindState(
            timeMs = PLACEHOLDER_TIME,
            model10m = null,
            rider = null,
            gustRider = null,
            headwind = null,
            crosswind = null,
            relativeDirectionDeg = null,
            confidence = CONFIDENCE_UNKNOWN,
            sources = SourceSummary(),
            ageMs = null,
            airDensity = STANDARD_AIR_DENSITY,
            weather = null,
            status = Status.NO_DATA,
        )
    }
}
