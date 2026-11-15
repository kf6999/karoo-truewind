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

import io.github.kf6999.truewind.core.api.ForecastRequest
import io.github.kf6999.truewind.core.api.HttpResponse
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.time.ManualClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenMeteoForecastProviderTest {
    private val sf = GeoPoint(37.77, -122.42)
    private val clock = ManualClock(1_794_736_800_000L)

    private fun models(url: String) = url.substringAfter("models=").substringBefore('&').split(',').toSet()

    @Test
    fun conusHappyPathUsesRegistryMembers() {
        val body = fixture("openmeteo/conus_nowcast_3loc.json")
        val http = FakeHttpClient.sequence(ok(body))
        val p = OpenMeteoForecastProvider(http, clock)
        val r = runSuspend { p.fetch(ForecastRequest(listOf(sf, GeoPoint(37.62, -122.37), GeoPoint(37.72, -122.22)), clock.nowMs())) }
        assertEquals(1, http.requests.size)
        assertEquals(ModelRegistry.DEFAULT.membersFor(sf).toSet(), models(http.requests[0].url))
        assertEquals(15, r.series.size)
        assertEquals(emptyList(), r.failedMembers)
        assertEquals(body.toByteArray().size.toLong(), r.bytesReceived)
        assertTrue(r.series.all { it.memberId.startsWith("open-meteo:") })
        assertNotNull(p.lastForecast)
        assertNull(p.lastError)
    }

    @Test
    fun allNullMemberIsDroppedForTwentyFourHours() {
        val http = FakeHttpClient.sequence(ok(fixture("openmeteo/nam_all_null_member.json")))
        val p = OpenMeteoForecastProvider(http, clock)
        val req = ForecastRequest(listOf(sf), clock.nowMs(), members = listOf("open-meteo:ncep_nam_conus", "ecmwf_ifs"))
        val r = runSuspend { p.fetch(req) }
        assertEquals(listOf("open-meteo:ncep_nam_conus"), r.failedMembers)
        assertEquals(listOf("open-meteo:ecmwf_ifs"), r.series.map { it.memberId }.distinct())
        assertEquals(1, http.requests.size) // usable data came back: no retry

        runSuspend { p.fetch(req) }
        assertEquals(setOf("ecmwf_ifs"), models(http.requests[1].url))

        clock.advanceBy(24 * 3_600_000L)
        runSuspend { p.fetch(req) }
        assertEquals(setOf("ncep_nam_conus", "ecmwf_ifs"), models(http.requests[2].url))
    }

    @Test
    fun invalidModelErrorRetriesWithoutIt() {
        val http = FakeHttpClient.sequence(
            status(400, fixture("openmeteo/error_invalid_model.json")),
            ok(fixture("openmeteo/nam_all_null_member.json").replace("ncep_nam_conus", "gfs_global").replace("[null,null]", "[1.0,2.0]")),
        )
        val p = OpenMeteoForecastProvider(http, clock)
        val d = runSuspend {
            p.fetchDetailed(ForecastRequest(listOf(sf), clock.nowMs(), members = listOf("gfs_global", "not_a_model_xyz", "ecmwf_ifs")))
        }
        assertEquals(2, d.attempts)
        assertEquals(listOf("not_a_model_xyz"), d.droppedModels)
        assertFalse("not_a_model_xyz" in models(http.requests[1].url))
        assertTrue(p.availability.isDropped("not_a_model_xyz"))
        assertNull(d.forecast.error)
        assertEquals(2, d.forecast.series.size)
        assertTrue(d.bytesReceived > 0)
    }

    @Test
    fun singleOutOfDomainModelFallsBackToBestMatch() {
        val bestMatch = """{"latitude":37.76,"longitude":-122.41,"elevation":14,"minutely_15":{"time":[1794736800],
            "wind_speed_10m":[2.4],"wind_direction_10m":[157]}}"""
        val http = FakeHttpClient.sequence(status(400, fixture("openmeteo/error_no_data_for_location.json")), ok(bestMatch))
        val p = OpenMeteoForecastProvider(http, clock)
        val r = runSuspend { p.fetch(ForecastRequest(listOf(sf), clock.nowMs(), members = listOf("meteoswiss_icon_ch1"))) }
        assertEquals(listOf("open-meteo:meteoswiss_icon_ch1"), r.failedMembers)
        assertEquals(listOf("open-meteo:best_match"), r.series.map { it.memberId })
        assertEquals(setOf("best_match"), models(http.requests[1].url))
    }

    @Test
    fun noMemberWithDataRetriesWithBestMatch() {
        val allNull = fixture("openmeteo/nam_all_null_member.json").replace("[3.38,3.54]", "[null,null]")
        val bestMatch = """{"latitude":37.76,"longitude":-122.41,"minutely_15":{"time":[1794736800],
            "wind_speed_10m_best_match":[2.4],"wind_direction_10m_best_match":[157]}}"""
        val http = FakeHttpClient.sequence(ok(allNull), ok(bestMatch))
        val p = OpenMeteoForecastProvider(http, clock)
        val d = runSuspend { p.fetchDetailed(ForecastRequest(listOf(sf), clock.nowMs(), members = listOf("ncep_nam_conus", "ecmwf_ifs"))) }
        assertEquals(2, d.attempts)
        assertEquals(listOf("best_match"), d.modelsUsed)
        assertEquals(setOf("ncep_nam_conus", "ecmwf_ifs"), d.droppedModels.toSet())
        assertEquals(listOf("open-meteo:best_match"), d.forecast.series.map { it.memberId })
    }

    @Test
    fun boundedRetries() {
        val http = FakeHttpClient.sequence(status(400, """{"error":true,"reason":"Invalid value: Cannot initialize MultiDomains from invalid String value a"}"""),
            status(400, """{"error":true,"reason":"Invalid value: Cannot initialize MultiDomains from invalid String value b"}"""),
            status(400, """{"error":true,"reason":"Invalid value: Cannot initialize MultiDomains from invalid String value c"}"""),
            status(400, """{"error":true,"reason":"Invalid value: Cannot initialize MultiDomains from invalid String value d"}"""))
        val p = OpenMeteoForecastProvider(http, clock, maxAttempts = 3)
        val d = runSuspend { p.fetchDetailed(ForecastRequest(listOf(sf), clock.nowMs(), members = listOf("a", "b", "c", "d"))) }
        assertEquals(3, http.requests.size)
        assertNotNull(d.forecast.error)
    }

    @Test
    fun transportErrorIsNotRetried() {
        val http = FakeHttpClient.sequence(HttpResponse(0, error = "timeout"))
        val p = OpenMeteoForecastProvider(http, clock)
        val r = runSuspend { p.fetch(ForecastRequest(listOf(sf), clock.nowMs())) }
        assertEquals(1, http.requests.size)
        assertEquals(emptyList(), r.series)
        assertEquals(emptyList(), r.failedMembers)
        assertEquals("timeout", p.lastError!!.reason)
    }

    @Test
    fun rateLimitIsNotRetried() {
        val http = FakeHttpClient.sequence(status(429, """{"error":true,"reason":"Too many concurrent requests"}"""))
        val p = OpenMeteoForecastProvider(http, clock)
        runSuspend { p.fetch(ForecastRequest(listOf(sf), clock.nowMs())) }
        assertEquals(1, http.requests.size)
        assertEquals(429, p.lastError!!.httpStatus)
    }

    @Test
    fun duplicateBestMatchIsRemoved() {
        val http = FakeHttpClient.sequence(ok(fixture("openmeteo/sydney_nowcast_dup_best_match.json")))
        val p = OpenMeteoForecastProvider(http, clock)
        val r = runSuspend { p.fetch(ForecastRequest(listOf(GeoPoint(-33.87, 151.21)), clock.nowMs())) }
        assertEquals(listOf("open-meteo:best_match", "open-meteo:gfs_global"), r.series.map { it.memberId })
    }

    @Test
    fun gzipResponse() {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(fixture("openmeteo/conus_nowcast_3loc.json").toByteArray()) }
        val http = FakeHttpClient.sequence(HttpResponse(200, body = bos.toByteArray()))
        val r = runSuspend { OpenMeteoForecastProvider(http, clock).fetch(ForecastRequest(listOf(sf), clock.nowMs())) }
        assertEquals(15, r.series.size)
        assertEquals(bos.size().toLong(), r.bytesReceived)
    }

    @Test
    fun hourly() {
        val http = FakeHttpClient.sequence(ok(fixture("openmeteo/hourly_12h_with_extras.json")))
        val (h, bytes) = runSuspend { OpenMeteoForecastProvider(http, clock).fetchHourly(listOf(sf)) }
        assertTrue(http.requests[0].url.contains("forecast_hours=12"))
        assertEquals(12, h.locations.single().members.getValue("best_match").size)
        assertTrue(bytes > 0)

        val failing = FakeHttpClient.sequence(status(503))
        val (e, _) = runSuspend { OpenMeteoForecastProvider(failing, clock).fetchHourly(listOf(sf)) }
        assertEquals(503, e.error!!.httpStatus)
    }
}
