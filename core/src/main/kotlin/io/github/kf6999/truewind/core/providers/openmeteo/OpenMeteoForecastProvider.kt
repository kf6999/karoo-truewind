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

package io.github.kf6999.truewind.core.providers.openmeteo

import io.github.kf6999.truewind.core.api.ForecastProvider
import io.github.kf6999.truewind.core.api.ForecastRequest
import io.github.kf6999.truewind.core.api.ForecastResult
import io.github.kf6999.truewind.core.api.HttpClient
import io.github.kf6999.truewind.core.api.HttpResponse
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.time.Clock

/**
 * The keyless Open-Meteo ensemble member set (SPEC 4.2): one multi-model, multi-point `minutely_15`
 * request per cycle, with the 24 h drop-and-retry fallback.
 *
 * Retry policy inside one [fetch] (at most [maxAttempts] HTTP requests, back-to-back so the
 * Bluetooth link wakes once):
 * - HTTP 400 naming an invalid model → drop that model, retry without it;
 * - HTTP 400 "No data is available for this location" with one model → drop it, retry with `best_match`;
 * - a 200 response where some members returned only nulls (or nothing) → those are dropped for 24 h
 *   and reported in [ForecastResult.failedMembers]; when *no* member had data, retry once with the
 *   remaining members, or `best_match` alone;
 * - transport errors, 429 and 5xx are not retried here (the scheduler owns backoff, SPEC 4.1).
 *
 * Member ids in [ForecastRequest.members] may be raw (`ecmwf_ifs`) or prefixed (`open-meteo:ecmwf_ifs`);
 * output ids are always prefixed. Identical twin members are removed with [dropDuplicateMembers].
 */
class OpenMeteoForecastProvider(
    private val http: HttpClient,
    clock: Clock,
    private val registry: ModelRegistry = ModelRegistry.DEFAULT,
    val availability: MemberAvailability = MemberAvailability(clock),
    private val baseUrl: String = OpenMeteoRequests.FORECAST_URL,
    private val pastSteps: Int = 2,
    private val forecastSteps: Int = 16,
    private val maxAttempts: Int = 3,
) : ForecastProvider {
    override val id: String = "open-meteo"

    /** The last full parse (locations and `current` values), for the weather fields and diagnostics. */
    @Volatile
    var lastForecast: OpenMeteoForecast? = null
        private set

    /** The last error seen by [fetch] / [fetchDetailed], null after a clean success. */
    @Volatile
    var lastError: OpenMeteoError? = null
        private set

    override suspend fun fetch(request: ForecastRequest): ForecastResult {
        val detailed = fetchDetailed(request)
        return ForecastResult(
            series = detailed.forecast.series,
            failedMembers = detailed.droppedModels.map { openMeteoMemberId(it) },
            bytesReceived = detailed.bytesReceived,
        )
    }

    data class Detailed(
        val forecast: OpenMeteoForecast,
        /** Every model dropped during this fetch (invalid id, no data, all nulls). */
        val droppedModels: List<String>,
        val bytesReceived: Long,
        val attempts: Int,
        /** The model list of the request that produced [forecast]. */
        val modelsUsed: List<String>,
    )

    suspend fun fetchDetailed(request: ForecastRequest): Detailed {
        val points = request.points.take(OpenMeteoRequests.MAX_POINTS)
        if (points.isEmpty()) return Detailed(OpenMeteoForecast(error = OpenMeteoError("no points")), emptyList(), 0, 0, emptyList())

        val wanted = request.members.map { openMeteoModelOf(it) }.ifEmpty { registry.membersFor(points) }
        var models = availability.filter(wanted)
        val dropped = LinkedHashSet<String>()
        var bytes = 0L
        var attempts = 0
        var result: OpenMeteoForecast

        while (true) {
            attempts++
            val url = OpenMeteoRequests.nowcastUrl(points, models, pastSteps, forecastSteps, baseUrl = baseUrl)
            val response = http.request(OpenMeteoRequests.get(url))
            bytes += response.body?.size ?: 0
            result = interpret(response, models, points)

            val err = result.error
            val retryModels: List<String>? = when {
                err != null -> {
                    val bad = err.invalidModel?.takeIf { it in models }
                        ?: models.singleOrNull()?.takeIf { err.noDataForLocation && it != ModelRegistry.BEST_MATCH }
                    if (bad != null) {
                        dropped += bad
                        availability.markFailed(bad)
                        availability.filter(models - bad).takeIf { it != models }
                    } else null
                }
                result.failedModels.isNotEmpty() -> {
                    dropped += result.failedModels
                    availability.markFailed(result.failedModels.filter { it != ModelRegistry.BEST_MATCH })
                    if (result.series.isEmpty()) {
                        val remaining = (models - result.failedModels.toSet())
                        val next = remaining.ifEmpty { listOf(ModelRegistry.BEST_MATCH) }
                        next.takeIf { it != models }
                    } else null
                }
                else -> null
            }
            if (retryModels == null || attempts >= maxAttempts) break
            models = retryModels
        }

        val cleaned = result.copy(series = dropDuplicateMembers(result.series))
        lastForecast = cleaned
        lastError = cleaned.error
        return Detailed(cleaned, dropped.toList(), bytes, attempts, models)
    }

    /** One hourly request for the forecast widgets (call at most every 60 min, SPEC 4.1). */
    suspend fun fetchHourly(points: List<GeoPoint>, hours: Int = 12, models: List<String> = emptyList()): Pair<OpenMeteoHourly, Long> {
        if (points.isEmpty()) return OpenMeteoHourly(error = OpenMeteoError("no points")) to 0L
        val url = OpenMeteoRequests.hourlyUrl(points, hours, models, baseUrl = baseUrl)
        val response = http.request(OpenMeteoRequests.get(url))
        val size = (response.body?.size ?: 0).toLong()
        val text = decodeBody(response.body)
        if (!response.isSuccessful) {
            return OpenMeteoHourly(error = OpenMeteoParser.parseError(text, response.statusCode) ?: httpError(response)) to size
        }
        return OpenMeteoParser.parseHourly(text, points, models, registry::covers) to size
    }

    private fun interpret(response: HttpResponse, models: List<String>, points: List<GeoPoint>): OpenMeteoForecast {
        val text = decodeBody(response.body)
        if (!response.isSuccessful) {
            return OpenMeteoForecast(error = OpenMeteoParser.parseError(text, response.statusCode) ?: httpError(response))
        }
        return OpenMeteoParser.parseNowcast(text, models, points, registry::covers)
    }

    private fun httpError(response: HttpResponse) =
        OpenMeteoError(response.error ?: "HTTP ${response.statusCode}", response.statusCode.takeIf { response.error == null })
}
