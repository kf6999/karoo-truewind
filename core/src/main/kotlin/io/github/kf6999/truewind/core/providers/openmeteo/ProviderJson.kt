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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

/*
 * Small, forgiving JSON helpers shared by the keyless and keyed provider parsers (Open-Meteo,
 * air quality, Tomorrow.io). Parsers walk a JsonElement tree instead of binding to @Serializable
 * classes so that unknown, renamed or per-model suffixed keys can never make them throw.
 */

/**
 * Decodes a response body to text. Transparently gunzips when the body starts with the gzip magic
 * bytes, so builders may send `Accept-Encoding: gzip` regardless of whether the app's HTTP layer
 * decompresses (SPEC 2.3: keep payloads small over the Bluetooth link).
 */
fun decodeBody(body: ByteArray?): String? {
    if (body == null || body.isEmpty()) return null
    val gz = body.size >= 2 && body[0] == 0x1f.toByte() && body[1] == 0x8b.toByte()
    return try {
        if (gz) GZIPInputStream(ByteArrayInputStream(body)).use { it.readBytes() }.toString(Charsets.UTF_8)
        else body.toString(Charsets.UTF_8)
    } catch (e: java.io.IOException) {
        null
    }
}

internal fun parseJsonOrNull(text: String?): JsonElement? {
    if (text.isNullOrBlank()) return null
    return try {
        Json.parseToJsonElement(text)
    } catch (e: IllegalArgumentException) { // SerializationException extends IllegalArgumentException
        null
    }
}

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

internal fun JsonElement?.asDouble(): Double? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.doubleOrNull?.takeIf { it.isFinite() }
}

internal fun JsonElement?.asLong(): Long? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.longOrNull ?: p.doubleOrNull?.takeIf { it.isFinite() }?.toLong()
}

internal fun JsonElement?.asInt(): Int? = asLong()?.toInt()

internal fun JsonElement?.asString(): String? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull || !p.isString) return null
    return p.content
}

internal fun JsonElement?.asBoolean(): Boolean? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.booleanOrNull ?: p.longOrNull?.let { it != 0L }
}

/** Formats a coordinate for a query string: locale-independent, at most 5 decimals (~1 m), no trailing zeros. */
fun formatCoordinate(value: Double): String {
    val s = String.format(Locale.ROOT, "%.5f", value).trimEnd('0').trimEnd('.')
    return if (s == "-0") "0" else s
}

internal fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
