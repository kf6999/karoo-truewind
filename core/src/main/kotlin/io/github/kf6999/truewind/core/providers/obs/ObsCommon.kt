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
import io.github.kf6999.truewind.core.api.HttpRequest
import io.github.kf6999.truewind.core.api.HttpResponse
import io.github.kf6999.truewind.core.api.Observation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime

/** m/s per knot. */
const val MS_PER_KNOT = 0.514444

/** m/s per km/h. */
const val MS_PER_KMH = 1.0 / 3.6

/** Metres per foot. */
const val M_PER_FOOT = 0.3048

/**
 * Outcome of one observation fetch, with the counters the fetch scheduler and diagnostics page
 * need (SPEC 4.1, TESTING 5.1). [error] is non-null when the provider could not produce data
 * (transport error, non-2xx, unparseable body); [observations] may still be partially filled.
 */
data class ObsFetchResult<T>(
    val items: List<T>,
    val requests: Int = 0,
    val bytesReceived: Long = 0,
    val error: String? = null,
    /** HTTP status of the failing request, if any (e.g. 429 for rate limiting). */
    val httpStatus: Int? = null,
    /** `Retry-After` in seconds when the server sent one. */
    val retryAfterSec: Long? = null,
)

internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Parses [body] into a JSON tree; null for empty or malformed input. Never throws. */
internal fun parseJsonOrNull(body: ByteArray?): JsonElement? {
    if (body == null || body.isEmpty()) return null
    return try {
        lenientJson.parseToJsonElement(body.decodeToString())
    } catch (e: Exception) {
        null
    }
}

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonObject?.field(name: String): JsonElement? = this?.get(name)?.takeUnless { it is JsonNull }

/** Content of a primitive as a string; null for JSON null, objects and arrays. */
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

/** Numeric value of a primitive, accepting numbers and numeric strings ("150.0"). */
internal fun JsonElement?.dbl(): Double? = str()?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

internal fun JsonElement?.lng(): Long? = str()?.trim()?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

internal fun JsonElement?.bool(): Boolean? = str()?.toBooleanStrictOrNull()

/** ISO-8601 timestamp with offset ("2026-11-15T09:43:00+00:00", "...Z") to epoch ms; null if unparseable. */
internal fun parseIsoMs(s: String?): Long? {
    if (s.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }
}

/** Fixed-point decimal text with at most [decimals] places, no exponent, locale independent. */
internal fun fmt(value: Double, decimals: Int): String =
    BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/** Case-insensitive header lookup. */
internal fun HttpResponse.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

internal fun HttpResponse.retryAfterSec(): Long? = header("Retry-After")?.trim()?.toLongOrNull()

internal fun HttpResponse.describeError(): String = error ?: "HTTP $statusCode"

/** Performs a GET; transport exceptions become an error response so providers never throw. */
internal suspend fun HttpClient.safeGet(url: String, headers: Map<String, String> = emptyMap()): HttpResponse =
    try {
        request(HttpRequest(method = "GET", url = url, headers = headers))
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        HttpResponse(statusCode = 0, error = e.message ?: e.javaClass.simpleName)
    }

/** Plausible observation: finite coordinates and a non-negative speed when present. */
internal fun Observation.isWellFormed(): Boolean =
    location.lat in -90.0..90.0 && location.lon in -180.0..180.0 && (speed == null || speed >= 0.0)
