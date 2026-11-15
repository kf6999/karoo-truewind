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

package io.github.kf6999.truewind.core.providers.obs

import io.github.kf6999.truewind.core.api.HttpClient
import io.github.kf6999.truewind.core.api.Observation
import io.github.kf6999.truewind.core.api.ObservationProvider
import io.github.kf6999.truewind.core.math.KM_PER_DEGREE
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import kotlinx.serialization.json.JsonObject
import kotlin.math.cos
import kotlin.math.max

/**
 * Thunderstorm evidence in a METAR, ordered from weakest to strongest. Used later for the
 * "storm distance" estimate (SPEC 9.1, WP14); it is not strike data.
 */
enum class ThunderIndicator {
    NONE,

    /** `LTG DSNT ...` remark: lightning more than 10 SM from the station. */
    LIGHTNING_DISTANT,

    /** `LTG...` remark without `DSNT` (overhead / vicinity), or a `TSB`/`TSE`/`TS` remark. */
    LIGHTNING,

    /** `VCTS`: thunderstorm within 5–10 SM. */
    VICINITY,

    /** `TS`, `-TSRA`, `+TSRA`, ... in present weather: thunderstorm at the station. */
    AT_STATION,
}

/** One decoded METAR with the provider-specific extras that do not fit [Observation]. */
data class AwcMetar(
    val observation: Observation,
    val rawText: String? = null,
    /** Present-weather string as AWC decodes it, e.g. `-RA BR`. */
    val wxString: String? = null,
    val thunder: ThunderIndicator = ThunderIndicator.NONE,
    /** `METAR` or `SPECI`. */
    val metarType: String? = null,
    /** `VFR`, `MVFR`, `IFR`, `LIFR`. */
    val flightCategory: String? = null,
) {
    val hasThunderstorm: Boolean get() = thunder != ThunderIndicator.NONE
}

/**
 * Aviation Weather Center METAR observations (SPEC 4.3), keyless and worldwide.
 *
 * `GET https://aviationweather.gov/api/data/metar?bbox=minLat,minLon,maxLat,maxLon&format=json&hours=1`
 *
 * Live findings (2026-11-15): wind is in knots; `wdir` is an int, the string `"VRB"`, or absent
 * together with `wspd` when the sensor reports nothing; calm is `wdir 0, wspd 0`; `wgst` is absent
 * without gusts; `elev` is metres; `obsTime` is unix seconds; the response is a bare JSON array and
 * an empty box answers **204 with no body**. `hours=1` returns the routine report plus any SPECIs,
 * so the same station appears several times. A 0.7°x0.8° box around SF returned 13 reports (5.7 KB),
 * Munich 4 (1.6 KB), Aspen 3 (1.4 KB). Results are capped at 400 reports per request.
 */
class AwcMetarProvider(
    private val http: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : ObservationProvider {
    override val id: String = ID

    override suspend fun fetch(center: GeoPoint, radiusKm: Double): List<Observation> =
        fetchMetars(center, radiusKm).items.map { it.observation }.filter { it.speed != null }

    /** Full result: every newest-per-station METAR (including ones without wind) plus counters. */
    suspend fun fetchMetars(center: GeoPoint, radiusKm: Double): ObsFetchResult<AwcMetar> {
        val response = http.safeGet(buildUrl(center, radiusKm), mapOf("Accept-Encoding" to "gzip"))
        val bytes = response.body?.size?.toLong() ?: 0L
        if (response.statusCode == 204 && response.error == null) {
            return ObsFetchResult(emptyList(), requests = 1, bytesReceived = bytes)
        }
        if (!response.isSuccessful) {
            return ObsFetchResult(
                emptyList(), requests = 1, bytesReceived = bytes, error = response.describeError(),
                httpStatus = response.statusCode.takeIf { it > 0 }, retryAfterSec = response.retryAfterSec(),
            )
        }
        val parsed = parse(response.body)
            ?: return ObsFetchResult(emptyList(), requests = 1, bytesReceived = bytes, error = "unparseable AWC response")
        return ObsFetchResult(parsed, requests = 1, bytesReceived = bytes)
    }

    fun buildUrl(center: GeoPoint, radiusKm: Double): String {
        val b = boundingBox(center, radiusKm)
        return "$baseUrl?bbox=${fmt(b[0], 2)},${fmt(b[1], 2)},${fmt(b[2], 2)},${fmt(b[3], 2)}&format=json&hours=1"
    }

    companion object {
        const val ID = "awc"
        const val DEFAULT_BASE_URL = "https://aviationweather.gov/api/data/metar"

        /**
         * `[minLat, minLon, maxLat, maxLon]` enclosing a circle of [radiusKm] around [center],
         * clamped to valid coordinates. Does not split at the antimeridian.
         */
        fun boundingBox(center: GeoPoint, radiusKm: Double): DoubleArray {
            val r = max(radiusKm, 1.0)
            val dLat = r / KM_PER_DEGREE
            val cosLat = cos(Math.toRadians(center.lat)).coerceAtLeast(0.01)
            val dLon = (r / (KM_PER_DEGREE * cosLat)).coerceAtMost(180.0)
            return doubleArrayOf(
                (center.lat - dLat).coerceIn(-90.0, 90.0),
                (center.lon - dLon).coerceIn(-180.0, 180.0),
                (center.lat + dLat).coerceIn(-90.0, 90.0),
                (center.lon + dLon).coerceIn(-180.0, 180.0),
            )
        }

        /**
         * Parses an AWC JSON body, keeping only the newest report per station. Returns an empty
         * list for an empty body, null for a body that is not a JSON array. Never throws.
         */
        fun parse(body: ByteArray?): List<AwcMetar>? {
            if (body == null || body.isEmpty() || body.decodeToString().isBlank()) return emptyList()
            val array = parseJsonOrNull(body).arr() ?: return null
            val newest = LinkedHashMap<String, AwcMetar>()
            for (element in array) {
                val metar = parseOne(element.obj() ?: continue) ?: continue
                val key = metar.observation.stationId
                val existing = newest[key]
                if (existing == null || metar.observation.timeMs > existing.observation.timeMs) newest[key] = metar
            }
            return newest.values.toList()
        }

        internal fun parseOne(o: JsonObject): AwcMetar? {
            val station = o.field("icaoId").str()?.takeIf { it.isNotBlank() } ?: return null
            val lat = o.field("lat").dbl() ?: return null
            val lon = o.field("lon").dbl() ?: return null
            val obsTimeSec = o.field("obsTime").lng() ?: return null

            val speedKt = o.field("wspd").dbl()?.takeIf { it >= 0.0 }
            val dirRaw = o.field("wdir")
            val dirText = dirRaw.str()?.trim()
            val variable = dirText.equals("VRB", ignoreCase = true)
            val dirDeg = if (variable) null else dirRaw.dbl()?.takeIf { it in 0.0..360.0 }
            val speed = speedKt?.times(MS_PER_KNOT)
            val wind = when {
                speed == null -> null
                speed == 0.0 -> WindVector.ZERO
                dirDeg == null -> null
                else -> WindVector.fromSpeedDirection(speed, dirDeg)
            }
            val gust = o.field("wgst").dbl()?.takeIf { it > 0.0 }?.times(MS_PER_KNOT)

            val raw = o.field("rawOb").str()
            val wx = o.field("wxString").str()
            val observation = Observation(
                stationId = station,
                source = ID,
                location = GeoPoint(lat, lon),
                elevationM = o.field("elev").dbl(),
                timeMs = obsTimeSec * 1000L,
                wind = wind,
                speed = speed,
                gust = gust,
                variableDirection = variable || (speed != null && speed > 0.0 && dirDeg == null),
                sensorHeightM = 10.0,
                temperatureC = o.field("temp").dbl(),
                reliability = 1.0,
                network = "METAR",
            )
            if (!observation.isWellFormed()) return null
            return AwcMetar(
                observation = observation,
                rawText = raw,
                wxString = wx,
                thunder = thunderIndicator(raw, wx),
                metarType = o.field("metarType").str(),
                flightCategory = o.field("fltCat").str(),
            )
        }

        private val TS_WEATHER = Regex("^[+-]?(VC)?TS[A-Z]*$")
        private val TS_REMARK = Regex("^(TS|TS[BE]\\d.*|TSB|TSE|LTG[A-Z]*)$")

        /** Strongest thunderstorm indication found in the decoded weather string or the raw report. */
        fun thunderIndicator(rawText: String?, wxString: String?): ThunderIndicator {
            var best = ThunderIndicator.NONE
            fun raise(t: ThunderIndicator) { if (t > best) best = t }

            fun weatherToken(token: String) {
                if (TS_WEATHER.matches(token)) {
                    raise(if (token.trimStart('+', '-').startsWith("VC")) ThunderIndicator.VICINITY else ThunderIndicator.AT_STATION)
                }
            }
            wxString?.split(' ')?.forEach { weatherToken(it.trim()) }

            if (rawText != null) {
                val tokens = rawText.trim().split(Regex("\\s+"))
                val rmk = tokens.indexOf("RMK").let { if (it < 0) tokens.size else it }
                // Body: skip the type and station id so an id like "KTSX" can never match.
                tokens.subList(0, rmk).drop(2).forEach { weatherToken(it) }
                val remarks = tokens.subList(minOf(rmk + 1, tokens.size), tokens.size)
                remarks.forEachIndexed { i, t ->
                    if (!TS_REMARK.matches(t)) return@forEachIndexed
                    if (t.startsWith("LTG")) {
                        val distant = remarks.drop(i + 1).take(3).any { it == "DSNT" }
                        raise(if (distant) ThunderIndicator.LIGHTNING_DISTANT else ThunderIndicator.LIGHTNING)
                    } else {
                        raise(ThunderIndicator.LIGHTNING)
                    }
                }
            }
            return best
        }
    }
}
