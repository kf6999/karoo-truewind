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
import io.github.kf6999.truewind.core.math.haversineKm
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/** One Synoptic station with the extras that do not fit [Observation]. */
data class SynopticStation(
    val observation: Observation,
    /** Synoptic network id: 1 ASOS/AWOS, 2 RAWS, 65 CWOP (citizen), 63 DOT road weather, ... */
    val mnetId: Int? = null,
    val name: String? = null,
    /** Great-circle distance from the request centre, km (computed; Synoptic's `DISTANCE` is in miles). */
    val distanceKm: Double? = null,
    /** True when the API supplied the anemometer height (`sensorvars=1`). */
    val sensorHeightReported: Boolean = false,
    /** Speed 0.0 with direction 0.0: calm, or a stuck/idle sensor. Never treated as a north wind. */
    val calmOrIdle: Boolean = false,
    /** Synoptic's own QC flagged at least one value. */
    val qcFlagged: Boolean = false,
)

/**
 * Synoptic Data `stations/latest` (SPEC 4.3, 9.3): dense mesonets, token required (constructor).
 *
 * Live findings (2026-11-15):
 * - `radius=lat,lon,R` takes R in **miles**, and each station's `DISTANCE` is in miles, even with
 *   `units=metric` (a 5-mile query returned stations up to `DISTANCE` 4.84 = 7.8 km). This class
 *   converts the caller's km.
 * - `ELEVATION` is a string in **feet** (`UNITS.elevation = "ft"`); `LATITUDE`/`LONGITUDE` are strings.
 * - Values live under `OBSERVATIONS.<var>_value_<n>` with their own `date_time`; stations that
 *   only report temperature are returned too, because `vars` matches any of the listed variables.
 * - `sensorvars=1` adds `SENSOR_VARIABLES.<var>.<key>.position`, the sensor height in metres
 *   (`UNITS.position`): ASOS stations report 10.0, CWOP and DOT stations report null. It makes
 *   the response ~50% larger (SF: 7.8 KB → 11.7 KB for 8 stations).
 * - With `Accept-Encoding: gzip` a 7.1 KB body was 1.25 KB on the wire.
 * - Near downtown SF 7 of 8 nearest stations were CWOP (`MNET_ID` 65), most at 0.0 m/s; several
 *   report 0.0 speed with 0.0 direction. Aspen: CDOT road sensors (63), the ASOS and HADS.
 * - Invalid token: HTTP 401 with `SUMMARY.RESPONSE_CODE 2`. No stations: HTTP 200 with
 *   `RESPONSE_CODE 2`, `NUMBER_OF_OBJECTS 0` and no `STATION` key.
 */
class SynopticProvider(
    private val http: HttpClient,
    private val token: String,
    private val limit: Int = 8,
    private val withinMinutes: Int = 30,
    private val sensorVars: Boolean = true,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : ObservationProvider {
    override val id: String = ID

    override suspend fun fetch(center: GeoPoint, radiusKm: Double): List<Observation> =
        fetchStations(center, radiusKm).items.map { it.observation }.filter { it.speed != null }

    suspend fun fetchStations(center: GeoPoint, radiusKm: Double): ObsFetchResult<SynopticStation> {
        if (token.isBlank()) return ObsFetchResult(emptyList(), error = "Synoptic token not set")
        // Compression is the HttpClient implementation's business: it must decompress what it asks for.
        val response = http.safeGet(buildUrl(center, radiusKm))
        val bytes = response.body?.size?.toLong() ?: 0L
        val root = parseJsonOrNull(response.body).obj()
        if (!response.isSuccessful) {
            val message = root.field("SUMMARY").obj().field("RESPONSE_MESSAGE").str()
            return ObsFetchResult(
                emptyList(), requests = 1, bytesReceived = bytes,
                error = listOfNotNull(response.describeError(), message).joinToString(": "),
                httpStatus = response.statusCode.takeIf { it > 0 }, retryAfterSec = response.retryAfterSec(),
            )
        }
        root ?: return ObsFetchResult(emptyList(), requests = 1, bytesReceived = bytes, error = "unparseable Synoptic response")
        return ObsFetchResult(parse(root, center), requests = 1, bytesReceived = bytes)
    }

    /** Request URL including the token. Never log it; use [redact]. */
    fun buildUrl(center: GeoPoint, radiusKm: Double): String {
        val miles = (radiusKm / KM_PER_MILE).coerceAtLeast(0.1)
        val sv = if (sensorVars) "&sensorvars=1" else ""
        return "$baseUrl?radius=${fmt(center.lat, 4)},${fmt(center.lon, 4)},${fmt(miles, 2)}" +
            "&limit=$limit&vars=wind_speed,wind_direction,wind_gust,air_temp&within=$withinMinutes&units=metric$sv" +
            "&token=${URLEncoder.encode(token, "UTF-8")}"
    }

    companion object {
        const val ID = "synoptic"
        const val DEFAULT_BASE_URL = "https://api.synopticdata.com/v2/stations/latest"
        const val KM_PER_MILE = 1.609344

        /** Synoptic network ids with a known meaning. */
        const val MNET_ASOS_AWOS = 1
        const val MNET_RAWS = 2
        const val MNET_CWOP = 65

        private val TOKEN_PARAM = Regex("([?&]token=)[^&]*")

        /** Replaces the token value in [url] so it can be logged or stored. */
        fun redact(url: String): String = TOKEN_PARAM.replace(url, "$1REDACTED")

        /** Reliability weight by network: ASOS/AWOS and RAWS 1.0, CWOP 0.4, anything else 0.7. */
        fun reliabilityFor(mnetId: Int?): Double = when (mnetId) {
            MNET_ASOS_AWOS, MNET_RAWS -> 1.0
            MNET_CWOP -> 0.4
            else -> 0.7
        }

        fun networkName(mnetId: Int?): String? = when (mnetId) {
            null -> null
            MNET_ASOS_AWOS -> "ASOS/AWOS"
            MNET_RAWS -> "RAWS"
            MNET_CWOP -> "CWOP"
            else -> "MNET_$mnetId"
        }

        /** Parses a body; null when it is not a JSON object. Never throws. */
        fun parse(body: ByteArray?, center: GeoPoint? = null): List<SynopticStation>? =
            parseJsonOrNull(body).obj()?.let { parse(it, center) }

        internal fun parse(root: JsonObject, center: GeoPoint?): List<SynopticStation> =
            root.field("STATION").arr()?.mapNotNull { parseStation(it.obj() ?: return@mapNotNull null, center) }.orEmpty()

        private fun parseStation(s: JsonObject, center: GeoPoint?): SynopticStation? {
            val stid = s.field("STID").str()?.takeIf { it.isNotBlank() } ?: return null
            val lat = s.field("LATITUDE").dbl() ?: return null
            val lon = s.field("LONGITUDE").dbl() ?: return null
            val units = s.field("UNITS").obj()
            val obs = s.field("OBSERVATIONS").obj() ?: return null

            val speedV = valueOf(obs, "wind_speed")
            val dirV = valueOf(obs, "wind_direction")
            val gustV = valueOf(obs, "wind_gust")
            val tempV = valueOf(obs, "air_temp")
            val timeMs = speedV?.timeMs ?: listOfNotNull(dirV, gustV, tempV).mapNotNull { it.timeMs }.maxOrNull() ?: return null

            val speed = speedV?.value?.takeIf { it >= 0.0 }
            val dir = dirV?.value?.takeIf { it in 0.0..360.0 }
            val calmOrIdle = speed == 0.0 && (dir == null || dir == 0.0)
            val wind = when {
                speed == null -> null
                speed == 0.0 -> WindVector.ZERO
                dir == null -> null
                else -> WindVector.fromSpeedDirection(speed, dir)
            }

            val elevRaw = s.field("ELEVATION").dbl()
            val elevationM = elevRaw?.let { if (units.field("elevation").str() == "m") it else it * M_PER_FOOT }

            val heightRaw = sensorHeight(s, "wind_speed")
            val heightM = heightRaw?.let { if (units.field("position").str() == "ft") it * M_PER_FOOT else it }
                ?.takeIf { it in 0.5..100.0 }

            val mnet = s.field("MNET_ID").str()?.trim()?.toIntOrNull()
            val qcFlagged = s.field("QC_FLAGGED").bool() ?: false
            var reliability = reliabilityFor(mnet)
            if (calmOrIdle && mnet == MNET_CWOP) reliability *= 0.5
            if (qcFlagged) reliability *= 0.5

            val location = GeoPoint(lat, lon)
            val observation = Observation(
                stationId = stid,
                source = ID,
                location = location,
                elevationM = elevationM,
                timeMs = timeMs,
                wind = wind,
                speed = speed,
                gust = gustV?.value?.takeIf { it > 0.0 },
                variableDirection = speed != null && speed > 0.0 && dir == null,
                sensorHeightM = heightM ?: 10.0,
                temperatureC = tempV?.value,
                reliability = reliability,
                network = networkName(mnet),
            )
            if (!observation.isWellFormed()) return null
            return SynopticStation(
                observation = observation,
                mnetId = mnet,
                name = s.field("NAME").str(),
                distanceKm = center?.let { haversineKm(it, location) },
                sensorHeightReported = heightM != null,
                calmOrIdle = calmOrIdle,
                qcFlagged = qcFlagged,
            )
        }

        private data class Value(val value: Double?, val timeMs: Long?)

        /** Picks `<variable>_value_<n>` with the lowest n (primary sensor); skips derived `..._value_1d`. */
        private fun valueKey(obs: JsonObject, variable: String): String? =
            obs.keys.filter { it.startsWith("${variable}_value_") && it.removePrefix("${variable}_value_").all(Char::isDigit) }
                .minByOrNull { it.removePrefix("${variable}_value_").toIntOrNull() ?: Int.MAX_VALUE }

        private fun valueOf(obs: JsonObject, variable: String): Value? {
            val key = valueKey(obs, variable) ?: return null
            val o = obs.field(key).obj() ?: return null
            return Value(o.field("value").dbl(), parseIsoMs(o.field("date_time").str()))
        }

        private fun sensorHeight(s: JsonObject, variable: String): Double? {
            val sensors = s.field("SENSOR_VARIABLES").obj().field(variable).obj() ?: return null
            val key = sensors.keys.filter { it.removePrefix("${variable}_value_").all(Char::isDigit) }
                .minByOrNull { it.removePrefix("${variable}_value_").toIntOrNull() ?: Int.MAX_VALUE }
                ?: return null
            return sensors.field(key).obj().field("position").dbl()
        }
    }
}
