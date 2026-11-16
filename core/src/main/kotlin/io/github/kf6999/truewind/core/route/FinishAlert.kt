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

package io.github.kf6999.truewind.core.route

import io.github.kf6999.truewind.core.api.HttpRequest
import io.github.kf6999.truewind.core.model.GeoPoint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

enum class FinishAlertFormat {
    /** ntfy.sh-style: `POST https://ntfy.sh/<topic>` with a plain-text body and `Title`/`Tags` headers. */
    NTFY,

    /** Generic webhook: `POST <url>` with a JSON body. */
    JSON,
}

data class FinishAlertConfig(
    val enabled: Boolean = false,
    /** HTTPS endpoint (ntfy topic URL or webhook). */
    val url: String = "",
    val format: FinishAlertFormat = FinishAlertFormat.NTFY,
    /** Fire when the time to destination first drops below this; 60, 30 or 15 in the settings UI. */
    val thresholdMin: Int = 30,
    /** Rider-chosen text, e.g. "Put the kettle on". */
    val message: String = "",
    val title: String = "Ride finishing soon",
    /** Off by default: the position is only sent when the rider opts in. */
    val includeLocation: Boolean = false,
    /** Fire immediately if the ride starts already inside the threshold. */
    val fireIfStartsInside: Boolean = true,
    /** The time to destination must stay below the threshold this long, so one optimistic ETA blip does not fire. */
    val debounceMs: Long = 60_000L,
    /** Retries after a failed POST, spaced by [retryDelayMs]. */
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 60_000L,
    /** Zone for the ETA clock time in the text. */
    val zone: ZoneId = ZoneId.systemDefault(),
)

/**
 * Finish alert (WP16): a pure state machine that yields one HTTP POST per ride when the
 * time to destination first drops below [FinishAlertConfig.thresholdMin].
 *
 * Feed [onUpdate] with the navigation state (e.g. from Karoo `OnNavigationState` /
 * time-to-destination) about once a minute; when it returns a request, send it and report the
 * outcome with [onSendResult]. A new [rideId] resets the machine. No backend, no email.
 */
class FinishAlertMachine(private val config: FinishAlertConfig) {
    enum class State { IDLE, ARMED, PENDING, SENDING, SENT, FAILED }

    var state: State = State.IDLE
        private set

    private var rideId: String? = null
    private var seenAbove = false
    private var belowSinceMs: Long? = null
    private var attempts = 0
    private var lastAttemptMs = 0L

    /**
     * @param timeToDestinationMs remaining time on the loaded route, null when not navigating.
     * @return the request to POST now, or null.
     */
    fun onUpdate(nowMs: Long, rideId: String, timeToDestinationMs: Long?, position: GeoPoint? = null): HttpRequest? {
        if (rideId != this.rideId) {
            this.rideId = rideId
            state = State.IDLE
            seenAbove = false
            belowSinceMs = null
            attempts = 0
        }
        if (!config.enabled || !isValidUrl(config.url)) return null
        if (state == State.SENT || state == State.SENDING) return null
        if (state == State.FAILED) {
            if (attempts > config.maxRetries || nowMs - lastAttemptMs < config.retryDelayMs) return null
            return send(nowMs, timeToDestinationMs, position)
        }
        val ttd = timeToDestinationMs ?: run { belowSinceMs = null; return null }
        if (state == State.IDLE) state = State.ARMED
        val threshold = config.thresholdMin * 60_000L
        if (ttd >= threshold) {
            seenAbove = true
            belowSinceMs = null
            return null
        }
        if (!seenAbove && !config.fireIfStartsInside) return null
        val since = belowSinceMs ?: nowMs.also { belowSinceMs = it }
        state = State.PENDING
        if (nowMs - since < config.debounceMs) return null
        return send(nowMs, ttd, position)
    }

    fun onSendResult(success: Boolean, nowMs: Long) {
        if (state != State.SENDING) return
        lastAttemptMs = nowMs
        state = if (success) State.SENT else State.FAILED
    }

    private fun send(nowMs: Long, ttd: Long?, position: GeoPoint?): HttpRequest? {
        val remaining = ttd ?: return null
        attempts++
        lastAttemptMs = nowMs
        state = State.SENDING
        return buildRequest(config, nowMs, remaining, position)
    }

    companion object {
        fun isValidUrl(url: String): Boolean = url.startsWith("https://") && url.length > "https://".length + 3 && !url.any { it.isWhitespace() }

        /** Builds the POST. Location is included only when [FinishAlertConfig.includeLocation] is set and known. */
        fun buildRequest(config: FinishAlertConfig, nowMs: Long, timeToDestinationMs: Long, position: GeoPoint?): HttpRequest {
            val etaMs = nowMs + timeToDestinationMs
            val minutes = ceil(timeToDestinationMs / 60_000.0).toInt()
            val etaClock = io.github.kf6999.truewind.core.alerts.AlertText.clock(etaMs, config.zone)
            val loc = position?.takeIf { config.includeLocation }
            val text = buildString {
                append("ETA $etaClock (about $minutes min).")
                if (config.message.isNotBlank()) append(' ').append(config.message.trim())
                if (loc != null) append("\nLocation: ${c(loc.lat)},${c(loc.lon)} https://www.openstreetmap.org/?mlat=${c(loc.lat)}&mlon=${c(loc.lon)}#map=14/${c(loc.lat)}/${c(loc.lon)}")
            }
            return when (config.format) {
                FinishAlertFormat.NTFY -> HttpRequest(
                    method = "POST",
                    url = config.url,
                    headers = mapOf("Content-Type" to "text/plain; charset=utf-8", "Title" to config.title, "Tags" to "bicyclist"),
                    body = text.toByteArray(Charsets.UTF_8),
                )
                FinishAlertFormat.JSON -> {
                    val json: JsonObject = buildJsonObject {
                        put("event", "finish_eta")
                        put("title", config.title)
                        put("message", text)
                        put("etaMs", etaMs)
                        put("etaIso", Instant.ofEpochMilli(etaMs).toString())
                        put("minutesRemaining", minutes)
                        if (loc != null) put("location", buildJsonObject { put("lat", JsonPrimitive(c(loc.lat).toDouble())); put("lon", JsonPrimitive(c(loc.lon).toDouble())) })
                    }
                    HttpRequest("POST", config.url, mapOf("Content-Type" to "application/json"), json.toString().toByteArray(Charsets.UTF_8))
                }
            }
        }

        private fun c(v: Double) = BigDecimal.valueOf(v).setScale(5, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}
