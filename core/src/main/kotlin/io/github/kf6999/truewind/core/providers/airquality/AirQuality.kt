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

package io.github.kf6999.truewind.core.providers.airquality

import io.github.kf6999.truewind.core.api.HttpClient
import io.github.kf6999.truewind.core.api.HttpRequest
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.providers.openmeteo.OpenMeteoRequests
import io.github.kf6999.truewind.core.providers.openmeteo.asArray
import io.github.kf6999.truewind.core.providers.openmeteo.asBoolean
import io.github.kf6999.truewind.core.providers.openmeteo.asDouble
import io.github.kf6999.truewind.core.providers.openmeteo.asInt
import io.github.kf6999.truewind.core.providers.openmeteo.asLong
import io.github.kf6999.truewind.core.providers.openmeteo.asObject
import io.github.kf6999.truewind.core.providers.openmeteo.asString
import io.github.kf6999.truewind.core.providers.openmeteo.decodeBody
import io.github.kf6999.truewind.core.providers.openmeteo.formatCoordinate
import io.github.kf6999.truewind.core.providers.openmeteo.parseJsonOrNull
import io.github.kf6999.truewind.core.time.Clock

/*
 * WP15 air quality (SPEC 9.1): Open-Meteo air-quality API, keyless, model based (CAMS).
 * Live check 2026-11-15: `current` is hourly (`interval` 3600), grid about 0.1° (SF answered for
 * 37.8,-122.4); variables `us_aqi` (USAQI), `european_aqi` (EAQI), `pm2_5`, `pm10`, `ozone` (μg/m³),
 * `uv_index`. One point with 6 hourly steps is about 0.8 KB raw (0.37 KB gzip).
 */

data class AirQualityStep(
    val timeMs: Long,
    val usAqi: Int? = null,
    /** μg/m³ */
    val pm25: Double? = null,
    /** μg/m³ */
    val ozone: Double? = null,
    val uvIndex: Double? = null,
)

data class AirQuality(
    /** Grid point the API answered for. */
    val location: GeoPoint?,
    val timeMs: Long,
    val usAqi: Int? = null,
    val europeanAqi: Int? = null,
    /** μg/m³ */
    val pm25: Double? = null,
    /** μg/m³ */
    val pm10: Double? = null,
    /** μg/m³ */
    val ozone: Double? = null,
    val uvIndex: Double? = null,
    val hourly: List<AirQualityStep> = emptyList(),
) {
    val usAqiCategory: UsAqiCategory? get() = usAqi?.let { UsAqiCategory.of(it) }
}

/** US EPA AQI bands, for colouring the AQI field. */
enum class UsAqiCategory(val maxInclusive: Int, val label: String) {
    GOOD(50, "Good"),
    MODERATE(100, "Moderate"),
    UNHEALTHY_FOR_SENSITIVE(150, "Unhealthy for sensitive groups"),
    UNHEALTHY(200, "Unhealthy"),
    VERY_UNHEALTHY(300, "Very unhealthy"),
    HAZARDOUS(Int.MAX_VALUE, "Hazardous");

    companion object {
        fun of(aqi: Int): UsAqiCategory = entries.first { aqi <= it.maxInclusive }
    }
}

sealed class AirQualityResult {
    abstract val bytesReceived: Long

    data class Success(val airQuality: AirQuality, override val bytesReceived: Long) : AirQualityResult()

    data class Failure(val reason: String, val httpStatus: Int? = null, override val bytesReceived: Long = 0) : AirQualityResult()
}

object AirQualityRequests {
    const val URL = "https://air-quality-api.open-meteo.com/v1/air-quality"

    val CURRENT = listOf("us_aqi", "european_aqi", "pm2_5", "pm10", "ozone", "uv_index")
    val HOURLY = listOf("us_aqi", "pm2_5", "ozone", "uv_index")

    fun url(point: GeoPoint, hours: Int = 6, baseUrl: String = URL): String =
        "$baseUrl?latitude=${formatCoordinate(point.lat)}&longitude=${formatCoordinate(point.lon)}" +
            "&current=${CURRENT.joinToString(",")}" +
            (if (hours > 0) "&hourly=${HOURLY.joinToString(",")}&forecast_hours=${hours.coerceAtMost(96)}" else "") +
            "&timeformat=unixtime"

    fun get(point: GeoPoint, hours: Int = 6): HttpRequest =
        HttpRequest("GET", url(point, hours), OpenMeteoRequests.ACCEPT_GZIP)
}

object AirQualityParser {
    /** Parses a response; never throws. Returns a [AirQualityResult.Failure] for error bodies or bad JSON. */
    fun parse(text: String?, bytes: Long = 0): AirQualityResult {
        val root = parseJsonOrNull(text).asObject() ?: return AirQualityResult.Failure("unparseable response", bytesReceived = bytes)
        if (root["error"].asBoolean() == true) {
            return AirQualityResult.Failure(root["reason"].asString() ?: "unknown error", bytesReceived = bytes)
        }
        val cur = root["current"].asObject() ?: return AirQualityResult.Failure("no current block", bytesReceived = bytes)
        val time = cur["time"].asLong()?.times(1000) ?: return AirQualityResult.Failure("no current time", bytesReceived = bytes)
        val lat = root["latitude"].asDouble()
        val lon = root["longitude"].asDouble()

        val hourly = root["hourly"].asObject()?.let { h ->
            val times = h["time"].asArray() ?: return@let emptyList()
            times.mapIndexedNotNull { i, t ->
                val ms = t.asLong()?.times(1000) ?: return@mapIndexedNotNull null
                AirQualityStep(
                    timeMs = ms,
                    usAqi = h["us_aqi"].asArray()?.getOrNull(i).asInt(),
                    pm25 = h["pm2_5"].asArray()?.getOrNull(i).asDouble(),
                    ozone = h["ozone"].asArray()?.getOrNull(i).asDouble(),
                    uvIndex = h["uv_index"].asArray()?.getOrNull(i).asDouble(),
                )
            }
        } ?: emptyList()

        return AirQualityResult.Success(
            AirQuality(
                location = if (lat != null && lon != null) GeoPoint(lat, lon) else null,
                timeMs = time,
                usAqi = cur["us_aqi"].asInt(),
                europeanAqi = cur["european_aqi"].asInt(),
                pm25 = cur["pm2_5"].asDouble(),
                pm10 = cur["pm10"].asDouble(),
                ozone = cur["ozone"].asDouble(),
                uvIndex = cur["uv_index"].asDouble(),
                hourly = hourly,
            ),
            bytes,
        )
    }
}

/**
 * Fetches air quality at the rider, at most once per [minIntervalMs] (SPEC 9.1: every 30 min);
 * calls inside the interval return the cached success without network.
 */
class AirQualityProvider(
    private val http: HttpClient,
    private val clock: Clock,
    val minIntervalMs: Long = 30 * 60_000L,
    private val hours: Int = 6,
) {
    private var cached: AirQualityResult.Success? = null
    private var cachedAtMs: Long = Long.MIN_VALUE

    suspend fun fetch(point: GeoPoint, force: Boolean = false): AirQualityResult {
        val now = clock.nowMs()
        cached?.let { if (!force && now - cachedAtMs < minIntervalMs) return it.copy(bytesReceived = 0) }
        val response = http.request(AirQualityRequests.get(point, hours))
        val size = (response.body?.size ?: 0).toLong()
        val text = decodeBody(response.body)
        if (!response.isSuccessful) {
            val reason = parseJsonOrNull(text).asObject()?.get("reason").asString()
            return AirQualityResult.Failure(reason ?: response.error ?: "HTTP ${response.statusCode}", response.statusCode.takeIf { response.error == null }, size)
        }
        val result = AirQualityParser.parse(text, size)
        if (result is AirQualityResult.Success) {
            cached = result
            cachedAtMs = now
        }
        return result
    }
}
