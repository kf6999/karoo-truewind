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

package io.github.kf6999.truewind.core.engine

import io.github.kf6999.truewind.core.api.ForecastProvider
import io.github.kf6999.truewind.core.api.ForecastRequest
import io.github.kf6999.truewind.core.api.ForecastResult
import io.github.kf6999.truewind.core.api.Observation
import io.github.kf6999.truewind.core.api.ObservationProvider
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.providers.obs.AwcMetarProvider
import io.github.kf6999.truewind.core.providers.obs.NwsObservationProvider
import io.github.kf6999.truewind.core.providers.obs.ObsFetchResult
import io.github.kf6999.truewind.core.providers.obs.SynopticProvider
import io.github.kf6999.truewind.core.providers.openmeteo.OpenMeteoForecastProvider
import io.github.kf6999.truewind.core.providers.openmeteo.OpenMeteoHourly
import io.github.kf6999.truewind.core.providers.tomorrow.TomorrowForecastProvider
import io.github.kf6999.truewind.core.schedule.ProviderOutcome
import kotlin.coroutines.cancellation.CancellationException

/** Status of one source call, independent of its payload. */
data class SourceStatus(
    val requests: Int = 1,
    val bytes: Long = 0,
    val httpStatus: Int? = null,
    val retryAfterSec: Long? = null,
    val error: String? = null,
)

/**
 * A forecast provider plus its scheduling traits and a way to read its HTTP status (the
 * [ForecastProvider] contract has none). Use the factory functions for the known providers.
 */
class ForecastSource(
    val provider: ForecastProvider,
    /** Optional (keyed) member, shed first on 429 / budget pressure. */
    val optional: Boolean = false,
    /** Own cadence, e.g. Tomorrow.io 15 min. */
    val minIntervalMs: Long = 0L,
    /** Sample only the rider point (keyed members, SPEC 4.2). */
    val riderOnly: Boolean = optional,
    private val call: suspend (ForecastRequest) -> Pair<ForecastResult, SourceStatus> = { req ->
        val r = provider.fetch(req)
        r to SourceStatus(bytes = r.bytesReceived, error = if (r.series.isEmpty()) "no series" else null)
    },
) {
    val id: String get() = provider.id

    suspend fun fetch(request: ForecastRequest): Pair<ForecastResult, SourceStatus> = try {
        call(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ForecastResult(emptyList()) to SourceStatus(error = e.message ?: e.javaClass.simpleName)
    }

    companion object {
        /** Open-Meteo ensemble: uses `fetchDetailed` for attempts, bytes and the HTTP status. */
        fun openMeteo(provider: OpenMeteoForecastProvider) = ForecastSource(provider, optional = false) { req ->
            val d = provider.fetchDetailed(req)
            val err = d.forecast.error
            ForecastResult(
                series = d.forecast.series,
                failedMembers = d.droppedModels.map { "open-meteo:$it" },
                bytesReceived = d.bytesReceived,
            ) to SourceStatus(
                requests = d.attempts,
                bytes = d.bytesReceived,
                httpStatus = err?.httpStatus,
                error = err?.reason ?: if (d.forecast.series.isEmpty()) "no series" else null,
            )
        }

        /** Tomorrow.io: optional, rider only, own 15-min cadence (the provider also self-limits). */
        fun tomorrow(provider: TomorrowForecastProvider) =
            ForecastSource(provider, optional = true, minIntervalMs = provider.minIntervalMs, riderOnly = true) { req ->
                val r = provider.fetch(req)
                val err = provider.lastError
                r to SourceStatus(
                    bytes = r.bytesReceived,
                    httpStatus = err?.httpStatus,
                    retryAfterSec = err?.retryAfterSec,
                    error = err?.message ?: if (r.series.isEmpty()) "no series" else null,
                )
            }
    }
}

/** An observation provider plus scheduling traits and detailed status. */
class ObservationSource(
    val id: String,
    val optional: Boolean = false,
    val minIntervalMs: Long = 0L,
    private val call: suspend (GeoPoint, Double) -> ObsFetchResult<Observation>,
) {
    suspend fun fetch(center: GeoPoint, radiusKm: Double): ObsFetchResult<Observation> = try {
        call(center, radiusKm)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ObsFetchResult(emptyList(), requests = 1, error = e.message ?: e.javaClass.simpleName)
    }

    companion object {
        fun awc(p: AwcMetarProvider) = ObservationSource(p.id) { c, r ->
            val res = p.fetchMetars(c, r)
            ObsFetchResult(
                res.items.map { it.observation }.filter { it.speed != null },
                res.requests, res.bytesReceived, res.error, res.httpStatus, res.retryAfterSec,
            )
        }

        fun nws(p: NwsObservationProvider) = ObservationSource(p.id) { c, r -> p.fetchDetailed(c, r) }

        /** Synoptic: optional (keyed), default cadence 10 min (SPEC 8.5 until the quota is known). */
        fun synoptic(p: SynopticProvider, minIntervalMs: Long = 10 * 60_000L) =
            ObservationSource(p.id, optional = true, minIntervalMs = minIntervalMs) { c, r ->
                val res = p.fetchStations(c, r)
                ObsFetchResult(
                    res.items.map { it.observation }.filter { it.speed != null },
                    res.requests, res.bytesReceived, res.error, res.httpStatus, res.retryAfterSec,
                )
            }

        /** Any [ObservationProvider] without detailed status (an exception is the only failure signal). */
        fun generic(p: ObservationProvider, optional: Boolean = false, minIntervalMs: Long = 0L) =
            ObservationSource(p.id, optional, minIntervalMs) { c, r -> ObsFetchResult(p.fetch(c, r), requests = 1) }
    }
}

/**
 * The hourly 12 h forecast for the widgets (SPEC 4.1, at most every 60 min). The engine only
 * schedules and counts it; the result goes to [onResult] for the app to keep.
 */
class HourlySource(
    val id: String = "hourly",
    private val call: suspend (List<GeoPoint>) -> SourceStatus,
) {
    suspend fun fetch(points: List<GeoPoint>): SourceStatus = try {
        call(points)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SourceStatus(error = e.message ?: e.javaClass.simpleName)
    }

    companion object {
        fun openMeteo(provider: OpenMeteoForecastProvider, hours: Int = 12, onResult: (OpenMeteoHourly) -> Unit) =
            HourlySource("open-meteo-hourly") { points ->
                val (h, bytes) = provider.fetchHourly(points, hours)
                if (h.error == null) onResult(h)
                SourceStatus(bytes = bytes, httpStatus = h.error?.httpStatus, error = h.error?.reason)
            }
    }
}

internal fun SourceStatus.toOutcome(id: String, success: Boolean) =
    ProviderOutcome(id, success, requests, bytes, httpStatus, retryAfterSec, error)
