/*
 * Copyright 2024-2026 karoo-headwind contributors
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

package de.timklge.karooheadwind.aidl.model

import de.timklge.karooheadwind.HeadwindStats
import de.timklge.karooheadwind.WeatherDataProvider
import de.timklge.karooheadwind.WindUnit
import de.timklge.karooheadwind.pipeline.WindSnapshot
import de.timklge.karooheadwind.weatherprovider.WeatherDataResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val headwindSnapshotJson = Json { encodeDefaults = true }

fun buildHeadwindSnapshot(stats: HeadwindStats, response: WeatherDataResponse?, windUnit: WindUnit, trueWind: TrueWindData? = null): HeadwindSnapshot {
    val forecast = response?.data?.takeIf { it.isNotEmpty() }?.map { location ->
        HeadwindForecastPoint(
            lat = location.coords.lat,
            lon = location.coords.lon,
            distanceAlongRoute = location.coords.distanceAlongRoute,
            current = location.current,
            hourly = location.forecasts ?: emptyList(),
        )
    }

    return HeadwindSnapshot(
        error = stats.lastWeatherError,
        lastSuccessfulFetchEpochSeconds = stats.lastSuccessfulWeatherRequest?.let { it / 1000 },
        lastFailedFetchEpochSeconds = stats.failedWeatherRequest?.let { it / 1000 },
        provider = stats.lastSuccessfulWeatherProvider,
        windUnit = windUnit,
        forecast = forecast,
        trueWind = trueWind,
    )
}

/**
 * Current weather as streamed over [de.timklge.karooheadwind.aidl.HeadwindService].
 *
 * All values are SI: wind in m/s, directions in degrees, temperature in °C, timestamps in epoch seconds.
 * [windUnit] is the wind unit the user chose for display in karoo-headwind. Values are not converted to it.
 * Fetch timestamps are the last successful and last failed weather download. Clients choose their own staleness threshold.
 * [error] is the message of the most recent failed weather download, or null if the last download succeeded.
 * [forecast] holds the cached forecast for each requested location along the route, or null when nothing is cached.
 */
@Serializable
data class HeadwindSnapshot(
    val version: Int = VERSION,
    val error: String? = null,
    val lastSuccessfulFetchEpochSeconds: Long? = null,
    val lastFailedFetchEpochSeconds: Long? = null,
    val provider: WeatherDataProvider? = null,
    val windUnit: WindUnit? = null,
    val forecast: List<HeadwindForecastPoint>? = null,
    /** Schema v2: the live TrueWind wind state; null in upstream-compatible mode or before the first tick. */
    val trueWind: TrueWindData? = null,
) {
    companion object {
        /** v2 added [trueWind]; every v1 field is still populated. */
        const val VERSION = 2
    }
}

/**
 * Live TrueWind state (schema v2). SI units; directions in degrees.
 *
 * Direction conventions: [windDirection10m] / [riderWindDirection] are where the wind blows FROM,
 * degrees true. [relativeDirection] is relative to the rider's heading with 0 = wind from dead
 * ahead, 90 = from the right (not the arrow convention of the graphical fields).
 */
@Serializable
data class TrueWindData(
    val timeEpochSeconds: Long,
    /** OK, NO_GPS, NO_DATA, STALE or NOT_SET_UP. */
    val status: String,
    val windSpeed10m: Double? = null,
    val windDirection10m: Double? = null,
    val gust10m: Double? = null,
    val riderWindSpeed: Double? = null,
    val riderWindDirection: Double? = null,
    val riderGust: Double? = null,
    /** + into the rider. */
    val headwind: Double? = null,
    /** + from the right. */
    val crosswind: Double? = null,
    val relativeDirection: Double? = null,
    /** 0-100. */
    val confidence: Int = 0,
    /** HIGH, MEDIUM or LOW. */
    val confidenceBand: String? = null,
    val ageSeconds: Long? = null,
    /** kg/m³. */
    val airDensity: Double,
    val riderHeading: Double? = null,
    val models: List<String> = emptyList(),
    val nearestStationId: String? = null,
    val nearestStationDistanceKm: Double? = null,
    val observationAgeMinutes: Double? = null,
    val virtualSensorActive: Boolean = false,
    /** Raw virtual-sensor headwind (m/s, + into the rider); null when invalid. */
    val measuredHeadwind: Double? = null,
    val lastUpdateEpochSeconds: Long? = null,
    val nextUpdateEpochSeconds: Long? = null,
)

/** Maps a TrueWind-mode snapshot to the v2 block; null for placeholders and upstream mode. */
fun buildTrueWindData(snapshot: WindSnapshot?): TrueWindData? {
    if (snapshot == null || snapshot.isPlaceholder || snapshot.extras.upstreamMode) return null
    val s = snapshot.state
    val e = snapshot.extras
    return TrueWindData(
        timeEpochSeconds = s.timeMs / 1000,
        status = s.status.name,
        windSpeed10m = s.model10m?.speed,
        windDirection10m = s.model10m?.directionFromDeg,
        gust10m = e.gust10m,
        riderWindSpeed = s.rider?.speed,
        riderWindDirection = s.rider?.directionFromDeg,
        riderGust = s.gustRider,
        headwind = s.headwind,
        crosswind = s.crosswind,
        relativeDirection = s.relativeDirectionDeg,
        confidence = s.confidence,
        confidenceBand = e.band?.name,
        ageSeconds = s.ageMs?.let { it / 1000 },
        airDensity = s.airDensity,
        riderHeading = s.riderHeadingDeg,
        models = s.sources.models,
        nearestStationId = s.sources.nearestStationId,
        nearestStationDistanceKm = s.sources.nearestStationDistanceKm,
        observationAgeMinutes = s.sources.observationAgeMin,
        virtualSensorActive = s.sources.virtualSensorActive,
        measuredHeadwind = e.measuredHeadwind,
        lastUpdateEpochSeconds = e.lastUpdateMs?.let { it / 1000 },
        nextUpdateEpochSeconds = e.nextUpdateMs?.let { it / 1000 },
    )
}