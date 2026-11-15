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

package de.timklge.karooheadwind.pipeline

import io.github.kf6999.truewind.core.api.ForecastRequest
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.api.HttpClient
import io.github.kf6999.truewind.core.providers.obs.SynopticProvider
import io.github.kf6999.truewind.core.providers.tomorrow.TomorrowForecastProvider
import io.github.kf6999.truewind.core.time.Clock

/**
 * "Test" buttons for the optional API keys (settings screen). One request each against a fixed
 * point (not the rider's position). Messages never contain the key.
 */
object KeyTester {
    /** Fixed test point with dense station coverage (Boulder, CO). */
    val TEST_POINT = GeoPoint(39.99, -105.27)

    data class Result(val ok: Boolean, val message: String)

    suspend fun testSynoptic(http: HttpClient, token: String, at: GeoPoint = TEST_POINT): Result {
        if (token.isBlank()) return Result(false, "No token entered")
        val r = SynopticProvider(http, token.trim()).fetchStations(at, 25.0)
        return when {
            r.error != null -> Result(false, "Synoptic error${r.httpStatus?.let { " (HTTP $it)" } ?: ""}: ${r.error}")
            r.items.isEmpty() -> Result(true, "Token accepted, but no stations near the test point")
            else -> Result(true, "Token OK: ${r.items.size} stations, ${r.bytesReceived} bytes")
        }
    }

    suspend fun testTomorrow(http: HttpClient, key: String, clock: Clock = Clock.SYSTEM, at: GeoPoint = TEST_POINT): Result {
        if (key.isBlank()) return Result(false, "No key entered")
        val p = TomorrowForecastProvider(http, key.trim(), clock)
        val r = p.fetch(ForecastRequest(points = listOf(at), nowMs = clock.nowMs()))
        val err = p.lastError
        return when {
            err != null -> Result(false, "Tomorrow.io error${err.httpStatus?.let { " (HTTP $it)" } ?: ""}: ${err.message}")
            r.series.isEmpty() -> Result(false, "Key accepted, but no wind data returned")
            else -> Result(true, "Key OK: ${r.series.sumOf { it.steps.size }} forecast steps, ${r.bytesReceived} bytes")
        }
    }
}
