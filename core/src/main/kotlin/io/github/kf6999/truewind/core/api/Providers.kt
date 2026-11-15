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

package io.github.kf6999.truewind.core.api

import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector

/*
 * Contracts between the app and the accuracy layers. Implementations live in
 * the core providers package (WP2, WP3) and are wired up by the app (WP8). Keep these small; extend
 * with default values rather than breaking changes, since several work packages build on them.
 */

/** Minimal HTTP abstraction. The app implements it on top of Karoo's `OnHttpResponse.MakeHttpRequest`. */
interface HttpClient {
    suspend fun request(request: HttpRequest): HttpResponse
}

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is HttpRequest && method == other.method && url == other.url && headers == other.headers &&
            (body?.contentEquals(other.body) ?: (other.body == null))

    override fun hashCode(): Int = ((method.hashCode() * 31 + url.hashCode()) * 31 + headers.hashCode()) * 31 + (body?.contentHashCode() ?: 0)
}

data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    /** Transport-level error (no connection, timeout); null when a status code was received. */
    val error: String? = null,
) {
    val isSuccessful: Boolean get() = error == null && statusCode in 200..299

    override fun equals(other: Any?): Boolean =
        other is HttpResponse && statusCode == other.statusCode && headers == other.headers && error == other.error &&
            (body?.contentEquals(other.body) ?: (other.body == null))

    override fun hashCode(): Int = ((statusCode * 31 + headers.hashCode()) * 31 + (body?.contentHashCode() ?: 0)) * 31 + (error?.hashCode() ?: 0)
}

/** One forecast time step at one location for one model member. All wind at 10 m. */
data class ForecastStep(
    val timeMs: Long,
    val wind10m: WindVector?,
    /** m/s */
    val gust10m: Double? = null,
    val temperatureC: Double? = null,
    val relativeHumidityPct: Double? = null,
    val precipitationMm: Double? = null,
    val surfacePressureHpa: Double? = null,
    val sealevelPressureHpa: Double? = null,
    val cloudCoverPct: Double? = null,
    val weatherCode: Int? = null,
)

/** Time series from one member (model) at one location. */
data class ForecastSeries(
    /** Provider-qualified member id, e.g. `open-meteo:ncep_hrrr_conus`. */
    val memberId: String,
    val location: GeoPoint,
    /** Model grid elevation, m, when the provider reports it (needed for pressure correction, WP5). */
    val gridElevationM: Double? = null,
    /** Ordered by [ForecastStep.timeMs]. */
    val steps: List<ForecastStep>,
)

data class ForecastRequest(
    /** Rider first, then stations / route points. Providers may cap the count. */
    val points: List<GeoPoint>,
    val nowMs: Long,
    /** Requested member ids; empty = provider default. */
    val members: List<String> = emptyList(),
)

data class ForecastResult(
    val series: List<ForecastSeries>,
    /** Members that failed or returned only nulls (to be dropped for 24 h, SPEC 4.2). */
    val failedMembers: List<String> = emptyList(),
    val bytesReceived: Long = 0,
)

/** A forecast source (Open-Meteo ensemble, Tomorrow.io, Pirate Weather, OpenWeatherMap, ...). */
interface ForecastProvider {
    /** Stable id, e.g. `open-meteo`. */
    val id: String

    suspend fun fetch(request: ForecastRequest): ForecastResult
}

/** A surface observation, normalised to SI units. */
data class Observation(
    val stationId: String,
    /** Provider id, e.g. `awc`, `nws`, `synoptic`. */
    val source: String,
    val location: GeoPoint,
    val elevationM: Double? = null,
    val timeMs: Long,
    /** Wind vector; null when the direction is variable or missing (speed may still be known). */
    val wind: WindVector?,
    /** m/s; 0.0 is a valid calm report. */
    val speed: Double?,
    /** m/s */
    val gust: Double? = null,
    val variableDirection: Boolean = false,
    /** Anemometer height above ground, m. METAR is assumed 10 m. */
    val sensorHeightM: Double = 10.0,
    val temperatureC: Double? = null,
)

/** An observation source (AWC METAR, NWS, Synoptic). */
interface ObservationProvider {
    val id: String

    suspend fun fetch(center: GeoPoint, radiusKm: Double): List<Observation>
}
