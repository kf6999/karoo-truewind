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

package io.github.kf6999.truewind.core.providers.airquality

import io.github.kf6999.truewind.core.api.HttpResponse
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.providers.openmeteo.FakeHttpClient
import io.github.kf6999.truewind.core.providers.openmeteo.fixture
import io.github.kf6999.truewind.core.providers.openmeteo.ok
import io.github.kf6999.truewind.core.providers.openmeteo.runSuspend
import io.github.kf6999.truewind.core.providers.openmeteo.status
import io.github.kf6999.truewind.core.time.ManualClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AirQualityTest {
    private val sf = GeoPoint(37.77, -122.42)

    @Test
    fun requestUrl() {
        val url = AirQualityRequests.url(sf)
        assertEquals(
            "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=37.77&longitude=-122.42" +
                "&current=us_aqi,european_aqi,pm2_5,pm10,ozone,uv_index&hourly=us_aqi,pm2_5,ozone,uv_index&forecast_hours=6&timeformat=unixtime",
            url,
        )
        assertTrue(!AirQualityRequests.url(sf, hours = 0).contains("hourly"))
    }

    @Test
    fun parsesSanFrancisco() {
        val r = assertIs<AirQualityResult.Success>(AirQualityParser.parse(fixture("airquality/sf_current_hourly.json")))
        val aq = r.airQuality
        assertEquals(1794736800_000L, aq.timeMs)
        assertEquals(55, aq.usAqi)
        assertEquals(38, aq.europeanAqi)
        assertEquals(13.9, aq.pm25)
        assertEquals(20.9, aq.pm10)
        assertEquals(65.0, aq.ozone)
        assertEquals(0.0, aq.uvIndex)
        assertEquals(UsAqiCategory.MODERATE, aq.usAqiCategory)
        assertEquals(6, aq.hourly.size)
        assertEquals(3_600_000L, aq.hourly[1].timeMs - aq.hourly[0].timeMs)
        assertEquals(0.05, aq.hourly[5].uvIndex)
        assertEquals(GeoPoint(37.800003, -122.399994), aq.location)
    }

    @Test
    fun parsesParis() {
        val aq = assertIs<AirQualityResult.Success>(AirQualityParser.parse(fixture("airquality/paris_current_hourly.json"))).airQuality
        assertEquals(57, aq.usAqi)
        assertEquals(4.0, aq.pm25)
        assertEquals(0.3, aq.uvIndex)
    }

    @Test
    fun errorsAndGarbage() {
        val e = assertIs<AirQualityResult.Failure>(AirQualityParser.parse(fixture("airquality/error_latitude.json")))
        assertTrue(e.reason.startsWith("Latitude must be in range"))
        assertIs<AirQualityResult.Failure>(AirQualityParser.parse("nope"))
        assertIs<AirQualityResult.Failure>(AirQualityParser.parse("""{"latitude":1}"""))
        val sparse = assertIs<AirQualityResult.Success>(
            AirQualityParser.parse("""{"current":{"time":10,"us_aqi":null,"new_thing":3},"hourly":{"time":[10,"x"],"us_aqi":[1]}}"""),
        ).airQuality
        assertNull(sparse.usAqi)
        assertEquals(1, sparse.hourly.size)
    }

    @Test
    fun categories() {
        assertEquals(UsAqiCategory.GOOD, UsAqiCategory.of(0))
        assertEquals(UsAqiCategory.GOOD, UsAqiCategory.of(50))
        assertEquals(UsAqiCategory.UNHEALTHY_FOR_SENSITIVE, UsAqiCategory.of(101))
        assertEquals(UsAqiCategory.HAZARDOUS, UsAqiCategory.of(501))
    }

    @Test
    fun providerCachesForThirtyMinutes() {
        val clock = ManualClock(0)
        val http = FakeHttpClient.sequence(ok(fixture("airquality/sf_current_hourly.json")))
        val p = AirQualityProvider(http, clock)
        val first = runSuspend { p.fetch(sf) }
        assertTrue(first.bytesReceived > 0)
        clock.advanceBy(29 * 60_000L)
        val second = runSuspend { p.fetch(sf) }
        assertIs<AirQualityResult.Success>(second)
        assertEquals(0, second.bytesReceived)
        assertEquals(1, http.requests.size)
        clock.advanceBy(60_000L)
        runSuspend { p.fetch(sf) }
        assertEquals(2, http.requests.size)
        runSuspend { p.fetch(sf, force = true) }
        assertEquals(3, http.requests.size)
    }

    @Test
    fun providerFailures() {
        val clock = ManualClock(0)
        val bad = runSuspend { AirQualityProvider(FakeHttpClient.sequence(status(400, fixture("airquality/error_latitude.json"))), clock).fetch(sf) }
        assertEquals(400, assertIs<AirQualityResult.Failure>(bad).httpStatus)
        val down = runSuspend { AirQualityProvider(FakeHttpClient.sequence(HttpResponse(0, error = "offline")), clock).fetch(sf) }
        assertEquals("offline", assertIs<AirQualityResult.Failure>(down).reason)
    }
}
