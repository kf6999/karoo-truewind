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

package de.timklge.karooheadwind.pipeline

import de.timklge.karooheadwind.HeadingResponse
import de.timklge.karooheadwind.weatherprovider.WeatherData
import io.github.kf6999.truewind.core.fusion.ConfidenceBand
import io.github.kf6999.truewind.core.model.Status
import io.github.kf6999.truewind.core.model.WindState
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.wind.RelativeWind

/**
 * App-side values that accompany a [WindState] but are not part of the core model: display
 * choices, the virtual-sensor raw value and the time-dependent helper fields of SPEC 5.
 */
data class WindExtras(
    /** True when produced by the upstream-compatible computation ([WindStateCalculator]). */
    val upstreamMode: Boolean = true,
    /** Headline fields show the 10 m wind (setting) instead of rider-height wind. */
    val showWindAt10m: Boolean = false,
    val band: ConfidenceBand? = null,
    /** False under ~1.5 m/s: direction is unreliable (grey out the arrow). */
    val directionReliable: Boolean = true,
    /** Fused 10 m gust, m/s. */
    val gust10m: Double? = null,
    /** Raw power-balance headwind (m/s, + into the rider); null when the sample was invalid. */
    val measuredHeadwind: Double? = null,
    val feelsLikeC: Double? = null,
    /** Minutes until the first 15-min step with >= 0.1 mm in the next 2 h; null = none, -1 = unknown. */
    val rainInMin: Int? = RAIN_UNKNOWN,
    /** Time of the last successful fetch cycle. */
    val lastUpdateMs: Long? = null,
    /** Next time the fetch scheduler will check (time trigger). */
    val nextUpdateMs: Long? = null,
    /** Highest headline wind speed seen while recording this ride, m/s. */
    val maxWindThisRide: Double? = null,
    val headingSource: String? = null,
    val innovationMean: Double? = null,
    val effectiveRatio: Double? = null,
    /** Short text for the "Wind source" field, e.g. `HRRR+NBM · KSQL 6 km · 4 min`. */
    val sourceText: String = "",
    // WP15 extra fields
    /** Wind chill at the rider's air speed, °C (temperature above 10 °C). */
    val windChillC: Double? = null,
    val dewPointC: Double? = null,
    /** m */
    val visibilityM: Double? = null,
    /** Shortwave radiation, W/m². */
    val solarRadiationWm2: Double? = null,
    /** Set while the last fetch cycle failed and the values come from cache. */
    val offline: OfflineInfo? = null,
) {
    companion object {
        const val RAIN_UNKNOWN = -1
    }
}

/**
 * The fetch cycles are failing and the values come from the cache.
 * [ageMs] is the age of the cached data; [noInternet] is set when the transport error is a DNS or
 * connect failure (no network at all), as opposed to an HTTP error from a reachable server.
 */
data class OfflineInfo(val ageMs: Long, val noInternet: Boolean)

/** What the data types consume: the core state plus [extras]. */
data class WindSnapshot(val state: WindState, val extras: WindExtras = WindExtras()) {
    val isPlaceholder: Boolean get() = state.isPlaceholder

    /** The wind the headline fields show: rider height by default, 10 m on request or in upstream mode. */
    val headline: WindVector?
        get() = if (extras.upstreamMode || extras.showWindAt10m) state.model10m else state.rider

    val headlineGust: Double?
        get() = if (extras.upstreamMode || extras.showWindAt10m) state.weather?.windGusts else state.gustRider

    /**
     * Relative wind bearing for the existing fields (karoo-headwind convention: direction the wind
     * blows TO relative to the rider, 0 = tailwind). Upstream mode reproduces the legacy expression
     * exactly (golden test).
     */
    fun relativeHeadingResponse(): HeadingResponse {
        if (extras.upstreamMode) return state.relativeHeadingResponse()
        val heading = state.riderHeadingDeg ?: return HeadingResponse.NoGps
        val wind = headline ?: return HeadingResponse.NoWeatherData
        return HeadingResponse.Value(RelativeWind.relativeBearing(heading, wind.directionFromDeg))
    }

    /** 10 m weather (Wind 10 m fields keep their meaning). */
    fun weatherData(): WeatherData? = state.weatherData()

    /** Weather whose wind speed/direction/gust are the headline (rider-height) values. */
    fun headlineWeatherData(): WeatherData? {
        val w = state.weatherData() ?: return null
        if (extras.upstreamMode || extras.showWindAt10m) return w
        val r = state.rider ?: return w
        return w.copy(windSpeed = r.speed, windDirection = r.directionFromDeg, windGusts = state.gustRider ?: w.windGusts)
    }

    companion object {
        val PLACEHOLDER = WindSnapshot(WindState.PLACEHOLDER)

        /** Wraps a legacy-computed state. */
        fun upstream(state: WindState) = WindSnapshot(state, WindExtras(upstreamMode = true))
    }
}

/** Status helpers for the views. */
val WindSnapshot.isStale: Boolean get() = state.status == Status.STALE
