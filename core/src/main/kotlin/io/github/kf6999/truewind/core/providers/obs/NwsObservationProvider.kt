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
import io.github.kf6999.truewind.core.api.HttpResponse
import io.github.kf6999.truewind.core.api.Observation
import io.github.kf6999.truewind.core.api.ObservationProvider
import io.github.kf6999.truewind.core.math.KM_PER_DEGREE
import io.github.kf6999.truewind.core.math.haversineKm
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.time.Clock
import kotlinx.serialization.json.JsonElement
import kotlin.math.cos
import kotlin.math.floor

/** User-Agent the NWS API requires (SPEC 2.4). Shared with the alerts client. */
const val NWS_USER_AGENT = "TrueWind (github.com/kf6999/karoo-truewind)"

internal val NWS_HEADERS = mapOf("User-Agent" to NWS_USER_AGENT, "Accept" to "application/geo+json")

/** One entry of a `gridpoints/{wfo}/{x},{y}/stations` list. */
data class NwsStation(
    val id: String,
    val name: String? = null,
    val location: GeoPoint,
    val elevationM: Double? = null,
    /** NWS `provider`: `ASOS-HFM`, `RAWS`, `APRSWXNET` (CWOP), `MesoWest`, `HADS`, ... */
    val provider: String? = null,
    val subProvider: String? = null,
) {
    /** Airport stations that the AWC METAR feed already covers. */
    val isMetar: Boolean
        get() {
            val p = provider.orEmpty().uppercase()
            return p.startsWith("ASOS") || p.startsWith("AWOS") || p.endsWith("-MTR") || ICAO.matches(id)
        }

    /** Relative trust 0..1 by network (see [Observation.reliability]). */
    val reliability: Double get() = nwsReliability(provider)

    private companion object {
        val ICAO = Regex("^[KP][A-Z]{3}$")
    }
}

/** Reliability weight by NWS station provider. */
fun nwsReliability(provider: String?): Double {
    val p = provider.orEmpty().uppercase()
    return when {
        p.startsWith("ASOS") || p.startsWith("AWOS") || p.endsWith("-MTR") -> 1.0
        p == "RAWS" -> 1.0
        p == "APRSWXNET" || p == "CWOP" -> 0.4
        else -> 0.7
    }
}

/**
 * NWS latest station observations (SPEC 4.3), US only, keyless (User-Agent required).
 *
 * `points/{lat},{lon}` → `properties.observationStations` (a `gridpoints/.../stations` URL) →
 * station list (sorted by distance from the grid point) → `stations/{id}/observations/latest` for
 * the nearest non-METAR stations. The station list is cached per ~[cellKm] cell for
 * [stationListTtlMs]; a 404 from `points` (outside the US) is cached for [negativeTtlMs] so a
 * European ride does not keep asking.
 *
 * Live findings (2026-11-15): values come as `{unitCode, value, qualityControl}` with
 * `wmoUnit:km_h-1` for wind; wind is often `null` with QC `Z` (KSFO's latest had no wind at all);
 * CWOP (`APRSWXNET`) positions are rounded to 0.01°; `points` 3.9 KB, station list ~16 KB for
 * `limit=10`, each latest observation ~4 KB. Within 25 km of downtown SF the list had HADS,
 * CA-Hydro, CWOP, ASOS, RAWS and MesoWest stations; Aspen had CDOT road sensors and CWOP.
 */
class NwsObservationProvider(
    private val http: HttpClient,
    private val clock: Clock = Clock.SYSTEM,
    /** Latest observations to return per fetch (SPEC: two nearest non-METAR stations). */
    private val maxStations: Int = 2,
    /** Station requests per fetch at most; stations whose latest report lacks wind are skipped. */
    private val maxAttempts: Int = 3,
    private val excludeMetar: Boolean = true,
    private val cellKm: Double = 25.0,
    private val stationListTtlMs: Long = 24 * 3_600_000L,
    private val negativeTtlMs: Long = 6 * 3_600_000L,
    private val stationListLimit: Int = 15,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : ObservationProvider {
    override val id: String = ID

    private data class CacheEntry(val stations: List<NwsStation>, val fetchedAtMs: Long, val negative: Boolean)

    private val cache = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean = size > MAX_CACHE_CELLS
    }

    override suspend fun fetch(center: GeoPoint, radiusKm: Double): List<Observation> =
        fetchDetailed(center, radiusKm).items

    suspend fun fetchDetailed(center: GeoPoint, radiusKm: Double): ObsFetchResult<Observation> {
        val list = stationsFor(center)
        var requests = list.requests
        var bytes = list.bytesReceived
        if (list.error != null) {
            return ObsFetchResult(
                emptyList(), requests = requests, bytesReceived = bytes, error = list.error,
                httpStatus = list.httpStatus, retryAfterSec = list.retryAfterSec,
            )
        }

        val candidates = list.items
            .asSequence()
            .filter { !excludeMetar || !it.isMetar }
            .map { it to haversineKm(center, it.location) }
            .filter { it.second <= radiusKm }
            .sortedBy { it.second }
            .map { it.first }
            .take(maxAttempts)
            .toList()

        val out = mutableListOf<Observation>()
        var lastError: ObsFetchResult<Observation>? = null
        for (station in candidates) {
            if (out.size >= maxStations) break
            val response = http.safeGet("$baseUrl/stations/${station.id}/observations/latest", NWS_HEADERS)
            requests++
            bytes += response.body?.size ?: 0
            if (!response.isSuccessful) {
                lastError = ObsFetchResult(
                    emptyList(), error = response.describeError(), httpStatus = response.statusCode.takeIf { it > 0 },
                    retryAfterSec = response.retryAfterSec(),
                )
                if (response.statusCode == 429) break
                continue
            }
            val obs = parseLatest(response.body, station) ?: continue
            if (obs.speed != null) out += obs
        }
        val error = if (out.isEmpty() && lastError != null) lastError else null
        return ObsFetchResult(
            out, requests = requests, bytesReceived = bytes, error = error?.error,
            httpStatus = error?.httpStatus, retryAfterSec = error?.retryAfterSec,
        )
    }

    /** Station list for the cell containing [center], from cache when fresh. */
    suspend fun stationsFor(center: GeoPoint): ObsFetchResult<NwsStation> {
        val key = cellKey(center, cellKm)
        val now = clock.nowMs()
        synchronized(cache) { cache[key] }?.let { entry ->
            val ttl = if (entry.negative) negativeTtlMs else stationListTtlMs
            if (now - entry.fetchedAtMs in 0 until ttl) return ObsFetchResult(entry.stations)
        }

        val points = http.safeGet(pointsUrl(center), NWS_HEADERS)
        var bytes = (points.body?.size ?: 0).toLong()
        if (points.statusCode == 404 && points.error == null) {
            synchronized(cache) { cache[key] = CacheEntry(emptyList(), now, negative = true) }
            return ObsFetchResult(emptyList(), requests = 1, bytesReceived = bytes)
        }
        if (!points.isSuccessful) return failure(points, 1, bytes)
        val stationsUrl = parsePointsStationsUrl(points.body)
            ?: return ObsFetchResult(emptyList(), requests = 1, bytesReceived = bytes, error = "NWS points without observationStations")

        val sep = if (stationsUrl.contains('?')) '&' else '?'
        val list = http.safeGet("$stationsUrl${sep}limit=$stationListLimit", NWS_HEADERS)
        bytes += list.body?.size ?: 0
        if (!list.isSuccessful) return failure(list, 2, bytes)
        val stations = parseStations(list.body)
            ?: return ObsFetchResult(emptyList(), requests = 2, bytesReceived = bytes, error = "unparseable NWS station list")
        synchronized(cache) { cache[key] = CacheEntry(stations, now, negative = false) }
        return ObsFetchResult(stations, requests = 2, bytesReceived = bytes)
    }

    fun pointsUrl(center: GeoPoint): String = "$baseUrl/points/${fmt(center.lat, 4)},${fmt(center.lon, 4)}"

    private fun <T> failure(response: HttpResponse, requests: Int, bytes: Long) =
        ObsFetchResult<T>(
            emptyList(), requests = requests, bytesReceived = bytes, error = response.describeError(),
            httpStatus = response.statusCode.takeIf { it > 0 }, retryAfterSec = response.retryAfterSec(),
        )

    companion object {
        const val ID = "nws"
        const val DEFAULT_BASE_URL = "https://api.weather.gov"
        private const val MAX_CACHE_CELLS = 32

        /** QC codes (MADIS) that mean the value must not be used. */
        private val REJECT_QC = setOf("X", "B")

        /** QC codes that halve the reliability. */
        private val DOUBTFUL_QC = setOf("Q")

        /** Cache key of the ~[cellKm] square cell containing [p]. */
        fun cellKey(p: GeoPoint, cellKm: Double = 25.0): String {
            val dLat = cellKm / KM_PER_DEGREE
            val row = floor(p.lat / dLat).toLong()
            val rowLat = (row + 0.5) * dLat
            val dLon = cellKm / (KM_PER_DEGREE * cos(Math.toRadians(rowLat)).coerceAtLeast(0.01))
            val col = floor(p.lon / dLon).toLong()
            return "$row:$col"
        }

        /** `properties.observationStations` of a `points` response. */
        fun parsePointsStationsUrl(body: ByteArray?): String? =
            parseJsonOrNull(body).obj().field("properties").obj().field("observationStations").str()?.takeIf { it.startsWith("http") }

        /** Station list (GeoJSON FeatureCollection); null when the body is not one. */
        fun parseStations(body: ByteArray?): List<NwsStation>? {
            val features = parseJsonOrNull(body).obj().field("features").arr() ?: return null
            return features.mapNotNull { f ->
                val o = f.obj() ?: return@mapNotNull null
                val p = o.field("properties").obj()
                val id = p.field("stationIdentifier").str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val loc = pointOf(o.field("geometry")) ?: return@mapNotNull null
                NwsStation(
                    id = id,
                    name = p.field("name").str(),
                    location = loc,
                    elevationM = quantity(p.field("elevation"))?.let { lengthToM(it.value, it.unit) },
                    provider = p.field("provider").str()?.takeIf { it.isNotBlank() },
                    subProvider = p.field("subProvider").str()?.takeIf { it.isNotBlank() },
                )
            }
        }

        /**
         * Parses `observations/latest`. Returns null when the body is unusable (no time or
         * position); returns an observation with `speed == null` when wind is missing or rejected
         * by QC. [station] supplies network and reliability; position comes from the observation.
         */
        fun parseLatest(body: ByteArray?, station: NwsStation? = null): Observation? {
            val o = parseJsonOrNull(body).obj() ?: return null
            val p = o.field("properties").obj() ?: return null
            val timeMs = parseIsoMs(p.field("timestamp").str()) ?: return null
            val loc = pointOf(o.field("geometry")) ?: station?.location ?: return null
            val stationId = p.field("stationId").str()
                ?: p.field("station").str()?.substringAfterLast('/')
                ?: station?.id
                ?: return null

            var doubtful = false
            fun usable(q: Quantity?): Quantity? {
                if (q == null) return null
                if (q.qc in REJECT_QC) return null
                if (q.qc in DOUBTFUL_QC) doubtful = true
                return q
            }

            val speedQ = usable(quantity(p.field("windSpeed")))
            val speed = speedQ?.let { speedToMs(it.value, it.unit) }?.takeIf { it >= 0.0 }
            val dir = usable(quantity(p.field("windDirection")))?.value?.takeIf { it in 0.0..360.0 }
            val gust = usable(quantity(p.field("windGust")))?.let { speedToMs(it.value, it.unit) }?.takeIf { it > 0.0 }
            val temp = usable(quantity(p.field("temperature")))?.let { temperatureToC(it.value, it.unit) }
            val wind = when {
                speed == null -> null
                speed == 0.0 -> WindVector.ZERO
                dir == null -> null
                else -> WindVector.fromSpeedDirection(speed, dir)
            }
            val base = station?.reliability ?: 0.7
            val network = station?.provider?.let { prov -> station.subProvider?.let { "$prov/$it" } ?: prov }
            return Observation(
                stationId = stationId,
                source = ID,
                location = loc,
                elevationM = quantity(p.field("elevation"))?.let { lengthToM(it.value, it.unit) } ?: station?.elevationM,
                timeMs = timeMs,
                wind = wind,
                speed = speed,
                gust = gust,
                variableDirection = speed != null && speed > 0.0 && dir == null,
                sensorHeightM = 10.0,
                temperatureC = temp,
                reliability = if (doubtful) base * 0.5 else base,
                network = network,
            ).takeIf { it.isWellFormed() }
        }

        internal data class Quantity(val value: Double, val unit: String, val qc: String?)

        /** `{unitCode, value, qualityControl}`; null when value is null or not numeric. */
        internal fun quantity(e: JsonElement?): Quantity? {
            val o = e.obj() ?: return null
            val v = o.field("value").dbl() ?: return null
            return Quantity(v, o.field("unitCode").str().orEmpty(), o.field("qualityControl").str())
        }

        private fun unitName(unitCode: String) = unitCode.substringAfter(':')

        /** Speed in m/s from an NWS/WMO unit code; null for an unknown unit. */
        fun speedToMs(value: Double, unitCode: String): Double? = when (unitName(unitCode)) {
            "km_h-1" -> value * MS_PER_KMH
            "m_s-1" -> value
            "kn", "kt", "knot" -> value * MS_PER_KNOT
            "mi_h-1" -> value * 0.44704
            else -> null
        }

        fun temperatureToC(value: Double, unitCode: String): Double? = when (unitName(unitCode)) {
            "degC" -> value
            "degF" -> (value - 32.0) * 5.0 / 9.0
            "K" -> value - 273.15
            else -> null
        }

        fun lengthToM(value: Double, unitCode: String): Double? = when (unitName(unitCode)) {
            "m" -> value
            "ft" -> value * M_PER_FOOT
            "km" -> value * 1000.0
            else -> null
        }

        private fun pointOf(geometry: JsonElement?): GeoPoint? {
            val coords = geometry.obj().field("coordinates").arr() ?: return null
            val lon = coords.getOrNull(0).dbl() ?: return null
            val lat = coords.getOrNull(1).dbl() ?: return null
            return GeoPoint(lat, lon)
        }
    }
}
