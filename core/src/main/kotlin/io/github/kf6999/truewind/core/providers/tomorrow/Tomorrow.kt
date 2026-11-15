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

package io.github.kf6999.truewind.core.providers.tomorrow

import io.github.kf6999.truewind.core.api.ForecastProvider
import io.github.kf6999.truewind.core.api.ForecastRequest
import io.github.kf6999.truewind.core.api.ForecastResult
import io.github.kf6999.truewind.core.api.ForecastSeries
import io.github.kf6999.truewind.core.api.ForecastStep
import io.github.kf6999.truewind.core.api.HttpClient
import io.github.kf6999.truewind.core.api.HttpRequest
import io.github.kf6999.truewind.core.api.HttpResponse
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.providers.openmeteo.OpenMeteoRequests
import io.github.kf6999.truewind.core.providers.openmeteo.asArray
import io.github.kf6999.truewind.core.providers.openmeteo.asDouble
import io.github.kf6999.truewind.core.providers.openmeteo.asInt
import io.github.kf6999.truewind.core.providers.openmeteo.asObject
import io.github.kf6999.truewind.core.providers.openmeteo.asString
import io.github.kf6999.truewind.core.providers.openmeteo.decodeBody
import io.github.kf6999.truewind.core.providers.openmeteo.formatCoordinate
import io.github.kf6999.truewind.core.providers.openmeteo.parseJsonOrNull
import io.github.kf6999.truewind.core.providers.openmeteo.urlEncode
import io.github.kf6999.truewind.core.time.Clock
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.format.DateTimeParseException

/*
 * Tomorrow.io (optional keyed member, SPEC 2.4 / 4.2 / 9.1). Live checks 2026-11-15, free plan:
 * - `GET /v4/weather/realtime?location=lat,lon&units=metric` → `{"data":{"time","values":{...}},"location"}`, 0.5 KB.
 * - `GET /v4/timelines?location=..&timesteps=1m&startTime=now&endTime=nowPlus60m&fields=..&units=metric`
 *   works on the free plan. 1-minute steps run as far as `endTime` (6 h gave 361 steps, 104 KB raw),
 *   so the horizon must be kept short: 60 min × 8 fields ≈ 13 KB raw, ≈ 2 KB gzip.
 * - `GET /v4/weather/forecast?timesteps=1m` returns 60 minutely steps with every field (29 KB raw); not used.
 * - Units with `units=metric`: windSpeed/windGust m/s, windDirection ° FROM, temperature °C,
 *   humidity %, precipitationIntensity mm/h, pressure* hPa, visibility km (not m), cloudBase km.
 * - Errors: `{"code":401001,"type":"Invalid Key","message":...}`; 429 when over 25/hour or 500/day.
 * - `weatherCode` uses Tomorrow.io's own codes (1000 clear … 8000 thunderstorm), mapped by [toWmoCode].
 * The key travels in the `apikey` **request header**, not the URL: the Karoo system logs full request
 * URLs to logcat, so a query parameter would leak it. Verified live 2026-11-15 (`apikey:` header ->
 * 200, no key -> 401). [TomorrowRequests.redact] still strips a legacy `apikey=` parameter.
 */

/** One Tomorrow.io interval, metric units as returned. */
data class TomorrowValues(
    val timeMs: Long,
    val windSpeed: Double? = null,
    val windDirectionDeg: Double? = null,
    val windGust: Double? = null,
    val temperatureC: Double? = null,
    val humidityPct: Double? = null,
    /** mm/h */
    val precipitationIntensity: Double? = null,
    val precipitationProbabilityPct: Double? = null,
    val surfacePressureHpa: Double? = null,
    val sealevelPressureHpa: Double? = null,
    val cloudCoverPct: Double? = null,
    /** Tomorrow.io weather code (not WMO). */
    val weatherCode: Int? = null,
    val dewPointC: Double? = null,
    val apparentTemperatureC: Double? = null,
    /** km */
    val visibilityKm: Double? = null,
    val uvIndex: Double? = null,
) {
    val wind10m: WindVector?
        get() = when {
            windSpeed == null || windSpeed < 0 -> null
            windSpeed == 0.0 -> WindVector.ZERO
            windDirectionDeg == null -> null
            else -> WindVector.fromSpeedDirection(windSpeed, windDirectionDeg)
        }

    /** Converts to a 10 m [ForecastStep]; precipitation is intensity × [stepMs] (mm in the step). */
    fun toForecastStep(stepMs: Long): ForecastStep = ForecastStep(
        timeMs = timeMs,
        wind10m = wind10m,
        gust10m = windGust,
        temperatureC = temperatureC,
        relativeHumidityPct = humidityPct,
        precipitationMm = precipitationIntensity?.let { it * stepMs / 3_600_000.0 },
        surfacePressureHpa = surfacePressureHpa,
        sealevelPressureHpa = sealevelPressureHpa,
        cloudCoverPct = cloudCoverPct,
        weatherCode = weatherCode?.let { toWmoCode(it) },
    )
}

/** One minute of the rain bar (WP14). */
data class PrecipitationMinute(val timeMs: Long, val intensityMmPerHour: Double, val probabilityPct: Double?)

data class TomorrowError(val message: String, val httpStatus: Int? = null, val code: Int? = null, val retryAfterSec: Long? = null)

object TomorrowRequests {
    const val BASE = "https://api.tomorrow.io"

    /** Fields for the 1-minute timeline; kept to 8 so 60 steps stay under the 15 KB budget raw. */
    val TIMELINE_FIELDS = listOf(
        "windSpeed", "windDirection", "windGust", "temperature", "humidity",
        "precipitationIntensity", "precipitationProbability", "pressureSurfaceLevel",
    )

    const val KEY_HEADER = "apikey"

    /** Realtime URL; carries no key (see [get]). */
    fun realtimeUrl(point: GeoPoint, base: String = BASE): String =
        "$base/v4/weather/realtime?location=${loc(point)}&units=metric"

    /** Timeline from now to now + [horizonMin] minutes in [timestep] steps (`1m`, `5m`, `15m`, `1h`). */
    fun timelineUrl(
        point: GeoPoint,
        horizonMin: Int = 60,
        timestep: String = "1m",
        fields: List<String> = TIMELINE_FIELDS,
        base: String = BASE,
    ): String =
        "$base/v4/timelines?location=${loc(point)}&timesteps=$timestep&startTime=now&endTime=nowPlus${horizonMin.coerceIn(1, 360)}m" +
            "&fields=${fields.joinToString(",")}&units=metric"

    /** GET with gzip and the key in the `apikey` header (never in the URL). */
    fun get(url: String, apiKey: String): HttpRequest =
        HttpRequest("GET", url, OpenMeteoRequests.ACCEPT_GZIP + (KEY_HEADER to apiKey))

    /** Replaces the `apikey` parameter value, for logs and diagnostics. */
    fun redact(url: String): String = url.replace(Regex("""([?&]apikey=)[^&]*"""), "$1REDACTED")

    private fun loc(p: GeoPoint) = "${formatCoordinate(p.lat)},${formatCoordinate(p.lon)}"
}

object TomorrowParser {
    fun parseError(text: String?, httpStatus: Int? = null, retryAfterSec: Long? = null): TomorrowError? {
        val o = parseJsonOrNull(text).asObject() ?: return null
        val code = o["code"].asInt() ?: return null
        val msg = listOfNotNull(o["type"].asString(), o["message"].asString()).joinToString(": ").ifEmpty { "error $code" }
        return TomorrowError(msg, httpStatus, code, retryAfterSec)
    }

    /** `/v4/weather/realtime` → values, or null. */
    fun parseRealtime(text: String?): TomorrowValues? {
        val data = parseJsonOrNull(text).asObject()?.get("data").asObject() ?: return null
        return values(data["time"].asString(), data["values"].asObject())
    }

    /**
     * Timelines by timestep (`1m`, `1h`, ...), each ordered by time. Accepts the `/v4/timelines`
     * shape (`data.timelines[].intervals[].startTime`) and the `/v4/weather/forecast` shape
     * (`timelines.minutely[].time`). Never throws.
     */
    fun parseTimelines(text: String?): Map<String, List<TomorrowValues>> {
        val root = parseJsonOrNull(text).asObject() ?: return emptyMap()
        val out = LinkedHashMap<String, List<TomorrowValues>>()
        root["data"].asObject()?.get("timelines").asArray()?.forEach { tl ->
            val o = tl.asObject() ?: return@forEach
            val step = o["timestep"].asString() ?: return@forEach
            out[step] = o["intervals"].asArray().orEmpty().mapNotNull { iv ->
                val io = iv.asObject() ?: return@mapNotNull null
                values(io["startTime"].asString(), io["values"].asObject())
            }.sortedBy { it.timeMs }
        }
        root["timelines"].asObject()?.forEach { (name, arr) ->
            val step = FORECAST_NAMES[name] ?: return@forEach
            out[step] = arr.asArray().orEmpty().mapNotNull { iv ->
                val io = iv.asObject() ?: return@mapNotNull null
                values(io["time"].asString(), io["values"].asObject())
            }.sortedBy { it.timeMs }
        }
        return out
    }

    private val FORECAST_NAMES = mapOf("minutely" to "1m", "hourly" to "1h", "daily" to "1d")

    private fun values(time: String?, v: JsonObject?): TomorrowValues? {
        if (time == null || v == null) return null
        val t = try {
            Instant.parse(time).toEpochMilli()
        } catch (e: DateTimeParseException) {
            return null
        }
        return TomorrowValues(
            timeMs = t,
            windSpeed = v["windSpeed"].asDouble(),
            windDirectionDeg = v["windDirection"].asDouble(),
            windGust = v["windGust"].asDouble(),
            temperatureC = v["temperature"].asDouble(),
            humidityPct = v["humidity"].asDouble(),
            // realtime has rainIntensity etc. but no precipitationIntensity
            precipitationIntensity = v["precipitationIntensity"].asDouble()
                ?: listOf("rainIntensity", "snowIntensity", "sleetIntensity", "freezingRainIntensity")
                    .mapNotNull { v[it].asDouble() }.takeIf { it.isNotEmpty() }?.sum(),
            precipitationProbabilityPct = v["precipitationProbability"].asDouble(),
            surfacePressureHpa = v["pressureSurfaceLevel"].asDouble(),
            sealevelPressureHpa = v["pressureSeaLevel"].asDouble(),
            cloudCoverPct = v["cloudCover"].asDouble(),
            weatherCode = v["weatherCode"].asInt(),
            dewPointC = v["dewPoint"].asDouble(),
            apparentTemperatureC = v["temperatureApparent"].asDouble(),
            visibilityKm = v["visibility"].asDouble(),
            uvIndex = v["uvIndex"].asDouble(),
        )
    }
}

/** Maps a Tomorrow.io weather code to the nearest WMO code (the convention of [ForecastStep.weatherCode]). */
fun toWmoCode(tomorrowCode: Int): Int? = when (tomorrowCode) {
    1000 -> 0 // clear
    1100 -> 1 // mostly clear
    1101 -> 2 // partly cloudy
    1102, 1001 -> 3 // mostly cloudy, cloudy
    2000, 2100 -> 45 // fog, light fog
    4000 -> 51 // drizzle
    4200 -> 61 // light rain
    4001 -> 63 // rain
    4201 -> 65 // heavy rain
    5001, 5100 -> 71 // flurries, light snow
    5000 -> 73 // snow
    5101 -> 75 // heavy snow
    6000 -> 56 // freezing drizzle
    6200, 6001 -> 66 // light freezing rain, freezing rain
    6201 -> 67 // heavy freezing rain
    7000, 7101, 7102 -> 77 // ice pellets
    8000 -> 95 // thunderstorm
    else -> null
}

/** Result of one Tomorrow.io nowcast fetch. */
data class TomorrowNowcast(
    val location: GeoPoint,
    val fetchedAtMs: Long,
    /** 1-minute steps from now to the horizon. */
    val minutely: List<TomorrowValues>,
    val bytesReceived: Long,
) {
    /** Minute-by-minute precipitation for the rain bar (WP14). */
    val precipitation: List<PrecipitationMinute>
        get() = minutely.mapNotNull { v ->
            v.precipitationIntensity?.let { PrecipitationMinute(v.timeMs, it, v.precipitationProbabilityPct) }
        }

    fun toSeries(memberId: String): ForecastSeries =
        ForecastSeries(memberId, location, null, minutely.map { it.toForecastStep(60_000L) })
}

/**
 * Tomorrow.io as an optional ensemble member (SPEC 4.2): sampled at the rider location only (first
 * point of the request), one `/v4/timelines` call with 1-minute steps to +[horizonMin] min.
 *
 * Calls within [minIntervalMs] (default 15 min, SPEC 4.2; the free plan allows 25/hour) of the last
 * successful fetch return the cached series with `bytesReceived = 0` and no network. After a 429
 * the provider stays silent until `Retry-After` (or 1 h when absent) has passed.
 *
 * Member id: [MEMBER_ID]. Failures are reported in [ForecastResult.failedMembers] and [lastError].
 */
class TomorrowForecastProvider(
    private val http: HttpClient,
    private val apiKey: String,
    private val clock: Clock,
    val minIntervalMs: Long = 15 * 60_000L,
    val horizonMin: Int = 60,
) : ForecastProvider {
    override val id: String = "tomorrow"

    @Volatile
    var lastNowcast: TomorrowNowcast? = null
        private set

    @Volatile
    var lastError: TomorrowError? = null
        private set

    private var blockedUntilMs: Long = Long.MIN_VALUE

    override suspend fun fetch(request: ForecastRequest): ForecastResult {
        val point = request.points.firstOrNull() ?: return ForecastResult(emptyList())
        val now = clock.nowMs()
        lastNowcast?.let { if (now - it.fetchedAtMs < minIntervalMs) return ForecastResult(listOf(it.toSeries(MEMBER_ID))) }
        if (now < blockedUntilMs) return ForecastResult(emptyList(), failedMembers = listOf(MEMBER_ID))

        val response = http.request(TomorrowRequests.get(TomorrowRequests.timelineUrl(point, horizonMin), apiKey))
        val size = (response.body?.size ?: 0).toLong()
        val text = decodeBody(response.body)
        if (!response.isSuccessful) {
            val retryAfter = retryAfterSec(response)
            lastError = TomorrowParser.parseError(text, response.statusCode, retryAfter)
                ?: TomorrowError(response.error ?: "HTTP ${response.statusCode}", response.statusCode.takeIf { response.error == null }, retryAfterSec = retryAfter)
            if (response.statusCode == 429) blockedUntilMs = now + (retryAfter ?: 3600L) * 1000L
            return ForecastResult(emptyList(), failedMembers = listOf(MEMBER_ID), bytesReceived = size)
        }
        val minutely = TomorrowParser.parseTimelines(text)["1m"].orEmpty()
        if (minutely.none { it.wind10m != null }) {
            lastError = TomorrowError("no 1-minute wind data")
            return ForecastResult(emptyList(), failedMembers = listOf(MEMBER_ID), bytesReceived = size)
        }
        val nowcast = TomorrowNowcast(point, now, minutely, size)
        lastNowcast = nowcast
        lastError = null
        return ForecastResult(listOf(nowcast.toSeries(MEMBER_ID)), bytesReceived = size)
    }

    /** One realtime call (for the settings screen "Test" button); does not touch the cache. */
    suspend fun fetchRealtime(point: GeoPoint): Pair<TomorrowValues?, TomorrowError?> {
        val response = http.request(TomorrowRequests.get(TomorrowRequests.realtimeUrl(point), apiKey))
        val text = decodeBody(response.body)
        if (!response.isSuccessful) {
            return null to (TomorrowParser.parseError(text, response.statusCode, retryAfterSec(response))
                ?: TomorrowError(response.error ?: "HTTP ${response.statusCode}", response.statusCode.takeIf { response.error == null }))
        }
        val values = TomorrowParser.parseRealtime(text)
        return values to (if (values == null) TomorrowError("unparseable response") else null)
    }

    private fun retryAfterSec(response: HttpResponse): Long? =
        response.headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.trim()?.toLongOrNull()

    companion object {
        const val MEMBER_ID = "tomorrow:nowcast"
    }
}
