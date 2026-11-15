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

package io.github.kf6999.truewind.core.alerts.nws

import io.github.kf6999.truewind.core.api.HttpClient
import io.github.kf6999.truewind.core.api.HttpRequest
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.providers.obs.NWS_USER_AGENT
import io.github.kf6999.truewind.core.providers.obs.ObsFetchResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime

/** CAP severity, most severe first. */
enum class AlertSeverity {
    EXTREME, SEVERE, MODERATE, MINOR, UNKNOWN;

    /** True for the levels that raise an in-ride alert (SPEC 9.1: new Severe/Extreme events). */
    val isSevereOrWorse: Boolean get() = this == EXTREME || this == SEVERE

    companion object {
        fun parse(s: String?): AlertSeverity = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** CAP urgency. */
enum class AlertUrgency {
    IMMEDIATE, EXPECTED, FUTURE, PAST, UNKNOWN;

    companion object {
        fun parse(s: String?): AlertUrgency = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** One active NWS alert, reduced to what the in-ride alert rules (WP14) need. */
data class NwsAlert(
    /** CAP identifier (`urn:oid:...`); stable across fetches, use it for "fire once". */
    val id: String,
    /** e.g. `Severe Thunderstorm Warning`, `High Surf Advisory`. */
    val event: String,
    val severity: AlertSeverity,
    val urgency: AlertUrgency,
    /** `Observed`, `Likely`, `Possible`, `Unlikely`, `Unknown`. */
    val certainty: String? = null,
    val headline: String? = null,
    val effectiveMs: Long? = null,
    val onsetMs: Long? = null,
    /** End of the hazard; null for open-ended alerts. */
    val endsMs: Long? = null,
    /** When this message expires (an update may follow). */
    val expiresMs: Long? = null,
    /** `Alert`, `Update`, `Cancel`. */
    val messageType: String? = null,
    /** `Actual`, `Exercise`, `System`, `Test`, `Draft`. */
    val status: String? = null,
    /** Alert carries its own polygon (otherwise it is zone-based). */
    val hasGeometry: Boolean = false,
) {
    /** In effect at [nowMs]: started (onset or effective) and not ended or expired. */
    fun isActiveAt(nowMs: Long): Boolean {
        val start = onsetMs ?: effectiveMs
        val end = endsMs ?: expiresMs
        return (start == null || start <= nowMs) && (end == null || nowMs < end)
    }
}

/**
 * NWS active alerts for a point: `GET https://api.weather.gov/alerts/active?point=lat,lon`
 * (User-Agent required). US only.
 *
 * Live findings (2026-11-15): a GeoJSON FeatureCollection; zone-based alerts have
 * `geometry: null`; `onset`/`ends`/`expires` are ISO-8601 with local offset; `ends` can be null.
 * SF had 2 alerts (13 KB, mostly description text); a point with none answered 234 bytes.
 * `limit` is not an accepted parameter on `alerts/active`.
 */
object NwsAlerts {
    const val DEFAULT_BASE_URL = "https://api.weather.gov"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun buildUrl(point: GeoPoint, baseUrl: String = DEFAULT_BASE_URL): String =
        "$baseUrl/alerts/active?point=${fmt4(point.lat)},${fmt4(point.lon)}"

    /** Fetches and parses; never throws. Non-actual (test/exercise) and cancelled messages are dropped. */
    suspend fun fetch(http: HttpClient, point: GeoPoint, baseUrl: String = DEFAULT_BASE_URL): ObsFetchResult<NwsAlert> {
        val response = try {
            http.request(
                HttpRequest(
                    method = "GET",
                    url = buildUrl(point, baseUrl),
                    headers = mapOf("User-Agent" to NWS_USER_AGENT, "Accept" to "application/geo+json", "Accept-Encoding" to "gzip"),
                ),
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ObsFetchResult(emptyList(), requests = 1, error = e.message ?: e.javaClass.simpleName)
        }
        val bytes = response.body?.size?.toLong() ?: 0L
        if (!response.isSuccessful) {
            return ObsFetchResult(
                emptyList(), requests = 1, bytesReceived = bytes, error = response.error ?: "HTTP ${response.statusCode}",
                httpStatus = response.statusCode.takeIf { it > 0 },
                retryAfterSec = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.trim()?.toLongOrNull(),
            )
        }
        val alerts = parse(response.body)
            ?: return ObsFetchResult(emptyList(), requests = 1, bytesReceived = bytes, error = "unparseable NWS alerts")
        return ObsFetchResult(
            alerts.filter { it.status.equals("Actual", true) && !it.messageType.equals("Cancel", true) },
            requests = 1, bytesReceived = bytes,
        )
    }

    /** All features of the collection, unfiltered; null when the body is not a FeatureCollection. */
    fun parse(body: ByteArray?): List<NwsAlert>? {
        if (body == null || body.isEmpty()) return null
        val root = try {
            json.parseToJsonElement(io.github.kf6999.truewind.core.providers.openmeteo.decodeBody(body) ?: return null)
        } catch (e: Exception) {
            return null
        } as? JsonObject ?: return null
        val features = root["features"] as? JsonArray ?: return null
        return features.mapNotNull { f ->
            val feature = f as? JsonObject ?: return@mapNotNull null
            val p = feature["properties"] as? JsonObject ?: return@mapNotNull null
            val id = p.s("id") ?: feature.s("id") ?: return@mapNotNull null
            val event = p.s("event") ?: return@mapNotNull null
            NwsAlert(
                id = id,
                event = event,
                severity = AlertSeverity.parse(p.s("severity")),
                urgency = AlertUrgency.parse(p.s("urgency")),
                certainty = p.s("certainty"),
                headline = p.s("headline"),
                effectiveMs = iso(p.s("effective")),
                onsetMs = iso(p.s("onset")),
                endsMs = iso(p.s("ends")),
                expiresMs = iso(p.s("expires")),
                messageType = p.s("messageType"),
                status = p.s("status"),
                hasGeometry = feature["geometry"].let { it != null && it !is JsonNull },
            )
        }
    }

    private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.takeIf { it.isNotBlank() }

    private fun iso(s: String?): Long? = try {
        s?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
    } catch (e: Exception) {
        null
    }

    private fun fmt4(v: Double): String = BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
