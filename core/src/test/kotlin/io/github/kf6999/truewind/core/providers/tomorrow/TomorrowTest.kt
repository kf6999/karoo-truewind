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

import io.github.kf6999.truewind.core.api.ForecastRequest
import io.github.kf6999.truewind.core.api.HttpResponse
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.providers.openmeteo.FakeHttpClient
import io.github.kf6999.truewind.core.providers.openmeteo.fixture
import io.github.kf6999.truewind.core.providers.openmeteo.ok
import io.github.kf6999.truewind.core.providers.openmeteo.runSuspend
import io.github.kf6999.truewind.core.providers.openmeteo.status
import io.github.kf6999.truewind.core.time.ManualClock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TomorrowTest {
    private val sf = GeoPoint(37.77, -122.42)
    private val key = "test-key-123"
    private val t0 = Instant.parse("2026-11-15T10:14:00Z").toEpochMilli()

    @Test
    fun keyTravelsInHeaderNotUrl() {
        val url = TomorrowRequests.timelineUrl(sf)
        assertTrue(url.startsWith("https://api.tomorrow.io/v4/timelines?location=37.77,-122.42&timesteps=1m&startTime=now&endTime=nowPlus60m"))
        assertTrue(url.contains("fields=windSpeed,windDirection,windGust,temperature,humidity,precipitationIntensity,precipitationProbability,pressureSurfaceLevel"))
        assertTrue(url.contains("units=metric"))
        val req = TomorrowRequests.get(url, key)
        assertFalse(req.url.contains(key))
        assertFalse(req.url.contains("apikey"))
        assertEquals(key, req.headers["apikey"])
        assertEquals("gzip", req.headers["Accept-Encoding"])
        val rt = TomorrowRequests.get(TomorrowRequests.realtimeUrl(sf), "a&b")
        assertFalse(rt.url.contains("a&b"))
        assertEquals("a&b", rt.headers["apikey"])
        assertEquals("https://x/?location=1,2&apikey=REDACTED&units=metric", TomorrowRequests.redact("https://x/?location=1,2&apikey=a%26b&units=metric"))
    }

    @Test
    fun parsesRealtime() {
        val v = assertNotNull(TomorrowParser.parseRealtime(fixture("tomorrow/realtime_sf.json")))
        assertEquals(t0, v.timeMs)
        assertEquals(1.8, v.windSpeed)
        assertEquals(163.0, v.windDirectionDeg)
        assertEquals(4.9, v.windGust)
        assertEquals(13.0, v.temperatureC)
        assertEquals(93.0, v.humidityPct)
        assertEquals(11.9, v.dewPointC)
        assertEquals(13.2, v.visibilityKm)
        assertEquals(1010.41, v.surfacePressureHpa)
        assertEquals(0.0, v.precipitationIntensity) // summed from rain/snow/sleet/freezing rain
        assertEquals(1102, v.weatherCode)
        assertEquals(WindVector.fromSpeedDirection(1.8, 163.0), v.wind10m)
        assertEquals(3, v.toForecastStep(60_000).weatherCode)
    }

    @Test
    fun parsesTimelines() {
        val tl = TomorrowParser.parseTimelines(fixture("tomorrow/timelines_1m_1h_sf.json"))
        assertEquals(setOf("1m", "1h"), tl.keys)
        val m = tl.getValue("1m")
        assertEquals(8, m.size)
        assertEquals(t0, m[0].timeMs)
        assertEquals(60_000L, m[1].timeMs - m[0].timeMs)
        assertEquals(1.8, m[0].windSpeed)
        assertEquals(0.0, m[0].precipitationIntensity)
        assertEquals(3, tl.getValue("1h").size)
        assertEquals(2.8, tl.getValue("1h")[0].windSpeed)
    }

    @Test
    fun parsesForecastEndpointShapeAndJunk() {
        val text = """{"timelines":{"minutely":[{"time":"2026-11-15T10:15:00Z","values":{"windSpeed":0,"precipitationIntensity":2.4}},
            {"time":"garbage","values":{}}],"weekly":[]},"location":{}}"""
        val tl = TomorrowParser.parseTimelines(text)
        val m = tl.getValue("1m").single()
        assertEquals(WindVector.ZERO, m.wind10m)
        assertEquals(0.04, m.toForecastStep(60_000).precipitationMm!!, 1e-12) // 2.4 mm/h for one minute
        assertEquals(emptyMap(), TomorrowParser.parseTimelines("[]"))
        assertEquals(emptyMap(), TomorrowParser.parseTimelines(null))
        assertNull(TomorrowParser.parseRealtime("{}"))
    }

    @Test
    fun errorBody() {
        val e = assertNotNull(TomorrowParser.parseError("""{"code":401001,"type":"Invalid Key","message":"The method requires authentication."}""", 401))
        assertEquals(401001, e.code)
        assertTrue(e.message.startsWith("Invalid Key"))
        assertNull(TomorrowParser.parseError("""{"data":{}}"""))
    }

    @Test
    fun weatherCodeMapping() {
        assertEquals(0, toWmoCode(1000))
        assertEquals(63, toWmoCode(4001))
        assertEquals(95, toWmoCode(8000))
        assertNull(toWmoCode(1234))
    }

    @Test
    fun providerFetchesOnceAndCachesForFifteenMinutes() {
        val clock = ManualClock(t0)
        val http = FakeHttpClient.sequence(ok(fixture("tomorrow/timelines_1m_1h_sf.json")))
        val p = TomorrowForecastProvider(http, key, clock)
        val req = ForecastRequest(listOf(sf, GeoPoint(0.0, 0.0)), clock.nowMs())
        val r = runSuspend { p.fetch(req) }
        assertEquals(1, http.requests.size)
        assertTrue(http.requests[0].url.contains("location=37.77,-122.42")) // rider only
        val s = r.series.single()
        assertEquals("tomorrow:nowcast", s.memberId)
        assertEquals(sf, s.location)
        assertEquals(8, s.steps.size)
        assertEquals(WindVector.fromSpeedDirection(1.8, 163.0), s.steps[0].wind10m)
        assertTrue(r.bytesReceived > 0)
        assertEquals(8, p.lastNowcast!!.precipitation.size)

        clock.advanceBy(14 * 60_000L)
        val cached = runSuspend { p.fetch(req) }
        assertEquals(1, http.requests.size)
        assertEquals(0, cached.bytesReceived)
        assertEquals(s, cached.series.single())

        clock.advanceBy(60_000L)
        runSuspend { p.fetch(req) }
        assertEquals(2, http.requests.size)
    }

    @Test
    fun rateLimitedProviderBacksOff() {
        val clock = ManualClock(t0)
        val http = FakeHttpClient.sequence(
            HttpResponse(429, headers = mapOf("retry-after" to "120"), body = """{"code":429001,"type":"Too Many Calls","message":"slow down"}""".toByteArray()),
            ok(fixture("tomorrow/timelines_1m_1h_sf.json")),
        )
        val p = TomorrowForecastProvider(http, key, clock)
        val req = ForecastRequest(listOf(sf), clock.nowMs())
        val r = runSuspend { p.fetch(req) }
        assertEquals(listOf("tomorrow:nowcast"), r.failedMembers)
        assertEquals(120L, p.lastError!!.retryAfterSec)
        assertEquals(429001, p.lastError!!.code)

        clock.advanceBy(119_000L)
        runSuspend { p.fetch(req) }
        assertEquals(1, http.requests.size)
        clock.advanceBy(1_000L)
        val ok = runSuspend { p.fetch(req) }
        assertEquals(2, http.requests.size)
        assertEquals(1, ok.series.size)
        assertNull(p.lastError)
    }

    @Test
    fun failuresAreReported() {
        val clock = ManualClock(t0)
        val unauthorized = TomorrowForecastProvider(
            FakeHttpClient.sequence(status(401, """{"code":401001,"type":"Invalid Key","message":"x"}""")), key, clock,
        )
        assertEquals(listOf("tomorrow:nowcast"), runSuspend { unauthorized.fetch(ForecastRequest(listOf(sf), t0)) }.failedMembers)
        assertEquals(401, unauthorized.lastError!!.httpStatus)

        val empty = TomorrowForecastProvider(FakeHttpClient.sequence(ok("""{"data":{"timelines":[]}}""")), key, clock)
        assertEquals(listOf("tomorrow:nowcast"), runSuspend { empty.fetch(ForecastRequest(listOf(sf), t0)) }.failedMembers)

        val offline = TomorrowForecastProvider(FakeHttpClient.sequence(HttpResponse(0, error = "no link")), key, clock)
        runSuspend { offline.fetch(ForecastRequest(listOf(sf), t0)) }
        assertEquals("no link", offline.lastError!!.message)
        assertEquals(0, runSuspend { offline.fetch(ForecastRequest(emptyList(), t0)) }.series.size)
    }

    @Test
    fun realtimeCheck() {
        val clock = ManualClock(t0)
        val (v, e) = runSuspend {
            TomorrowForecastProvider(FakeHttpClient.sequence(ok(fixture("tomorrow/realtime_sf.json"))), key, clock).fetchRealtime(sf)
        }
        assertNull(e)
        assertEquals(1.8, v!!.windSpeed)
        val (v2, e2) = runSuspend {
            TomorrowForecastProvider(FakeHttpClient.sequence(status(401, """{"code":401001,"type":"Invalid Key","message":"x"}""")), key, clock).fetchRealtime(sf)
        }
        assertNull(v2)
        assertEquals(401001, e2!!.code)
    }
}
