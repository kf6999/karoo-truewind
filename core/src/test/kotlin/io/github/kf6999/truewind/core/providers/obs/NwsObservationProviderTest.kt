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

import io.github.kf6999.truewind.core.api.HttpResponse
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.time.ManualClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NwsObservationProviderTest {
    private val dir = "providers/obs/nws"
    private val sf = GeoPoint(37.7749, -122.4194)
    private val aspen = GeoPoint(39.1911, -106.8175)
    private val hour = 3_600_000L

    private fun sfHttp() = FakeHttpClient()
        .onFixture("/points/", "$dir/points_sf.json")
        .onFixture("/gridpoints/MTR/85,105/stations", "$dir/stations_sf.json")
        .onFixture("/stations/SFOC1/observations/latest", "$dir/latest_SFOC1.json")
        .onFixture("/stations/D3169/observations/latest", "$dir/latest_D3169.json")
        .onFixture("/stations/KSFO/observations/latest", "$dir/latest_KSFO.json")

    @Test fun parsePoints_givesStationListUrl() {
        assertEquals(
            "https://api.weather.gov/gridpoints/MTR/85,105/stations",
            NwsObservationProvider.parsePointsStationsUrl(fixture("$dir/points_sf.json")),
        )
        assertNull(NwsObservationProvider.parsePointsStationsUrl(fixture("$dir/points_outside_us_404.json")))
        assertNull(NwsObservationProvider.parsePointsStationsUrl("garbage".toByteArray()))
    }

    @Test fun parseStations_providersAndMetarDetection() {
        val stations = requireNotNull(NwsObservationProvider.parseStations(fixture("$dir/stations_sf.json")))
        assertEquals(listOf("SFOC1", "OAMC1", "D3169", "KSFO", "D6436", "KOAK"), stations.map { it.id })
        val byId = stations.associateBy { it.id }
        assertTrue(byId.getValue("KSFO").isMetar)
        assertFalse(byId.getValue("SFOC1").isMetar)
        assertEquals(0.4, byId.getValue("D3169").reliability, 0.0)
        assertEquals(1.0, byId.getValue("KOAK").reliability, 0.0)
        assertEquals(45.72, byId.getValue("SFOC1").elevationM!!, 1e-9)
        assertEquals(37.77056, byId.getValue("SFOC1").location.lat, 1e-9)

        val aspenStations = requireNotNull(NwsObservationProvider.parseStations(fixture("$dir/stations_aspen.json")))
        val co100 = aspenStations.single { it.id == "CO100" }
        assertEquals("MesoWest", co100.provider)
        assertEquals("CDOT", co100.subProvider)
        assertEquals(1.0, nwsReliability("RAWS"), 0.0)
    }

    @Test fun parseLatest_unitsCalmAndNulls() {
        // CO100: 2.16 km/h from 135 deg, gust 3.24 km/h, 2.4 degC, 2418.9 m.
        val co100 = requireNotNull(NwsObservationProvider.parseLatest(fixture("$dir/latest_CO100.json")))
        assertEquals("CO100", co100.stationId)
        assertEquals(0.6, co100.speed!!, 1e-9)
        assertEquals(0.9, co100.gust!!, 1e-9)
        assertEquals(135.0, co100.wind!!.directionFromDeg, 1e-6)
        assertEquals(2.4, co100.temperatureC!!, 1e-9)
        assertEquals(2418.9, co100.elevationM!!, 1e-9)
        assertEquals(java.time.Instant.parse("2026-11-15T09:33:00Z").toEpochMilli(), co100.timeMs)
        assertEquals("nws", co100.source)

        // D3169 (CWOP): 0 km/h with a direction is calm, not a 168 deg wind; gust 0 means none.
        val d3169 = requireNotNull(NwsObservationProvider.parseLatest(fixture("$dir/latest_D3169.json")))
        assertEquals(0.0, d3169.speed!!, 0.0)
        assertEquals(WindVector.ZERO, d3169.wind)
        assertNull(d3169.gust)
        assertFalse(d3169.variableDirection)

        // SFOC1 and KSFO: wind values null (QC "Z"), temperature still present.
        for (id in listOf("SFOC1", "KSFO")) {
            val o = requireNotNull(NwsObservationProvider.parseLatest(fixture("$dir/latest_$id.json")))
            assertNull(o.speed)
            assertNull(o.wind)
            assertNotNull(o.temperatureC)
        }

        // KASE: calm 0/0 at an ASOS.
        val kase = requireNotNull(NwsObservationProvider.parseLatest(fixture("$dir/latest_KASE.json")))
        assertEquals(WindVector.ZERO, kase.wind)
    }

    @Test fun parseLatest_qcFlagsAndUnitCodes() {
        fun body(speed: String, dir: String) = """{"geometry":{"coordinates":[-100,40]},"properties":{
            "stationId":"TEST1","timestamp":"2026-11-15T09:00:00+00:00","unknown":{"x":1},
            "windSpeed":$speed,"windDirection":$dir,"windGust":{"unitCode":"wmoUnit:km_h-1","value":null,"qualityControl":"Z"}}}""".toByteArray()
        val dirOk = """{"unitCode":"wmoUnit:degree_(angle)","value":270,"qualityControl":"V"}"""

        val rejected = NwsObservationProvider.parseLatest(body("""{"unitCode":"wmoUnit:km_h-1","value":36,"qualityControl":"X"}""", dirOk))!!
        assertNull("QC X is rejected", rejected.speed)

        val doubtful = NwsObservationProvider.parseLatest(body("""{"unitCode":"wmoUnit:km_h-1","value":36,"qualityControl":"Q"}""", dirOk))!!
        assertEquals(10.0, doubtful.speed!!, 1e-9)
        assertEquals(0.35, doubtful.reliability, 1e-9)

        val ms = NwsObservationProvider.parseLatest(body("""{"unitCode":"wmoUnit:m_s-1","value":5,"qualityControl":"V"}""", dirOk))!!
        assertEquals(5.0, ms.speed!!, 1e-9)
        assertEquals(WindVector(5.0, 0.0).u, ms.wind!!.u, 1e-9)

        val kn = NwsObservationProvider.parseLatest(body("""{"unitCode":"wmoUnit:kn","value":10}""", dirOk))!!
        assertEquals(10 * MS_PER_KNOT, kn.speed!!, 1e-9)

        val unknownUnit = NwsObservationProvider.parseLatest(body("""{"unitCode":"wmoUnit:furlong_fortnight-1","value":10}""", dirOk))!!
        assertNull(unknownUnit.speed)

        val variable = NwsObservationProvider.parseLatest(body("""{"unitCode":"wmoUnit:km_h-1","value":18}""", "null"))!!
        assertTrue(variable.variableDirection)
        assertNull(variable.wind)
        assertEquals(5.0, variable.speed!!, 1e-9)

        assertNull(NwsObservationProvider.parseLatest("""{"properties":{}}""".toByteArray()))
        assertNull(NwsObservationProvider.parseLatest(null))
        assertEquals(-40.0, NwsObservationProvider.temperatureToC(-40.0, "wmoUnit:degF")!!, 1e-9)
        assertEquals(304.8, NwsObservationProvider.lengthToM(1000.0, "wmoUnit:ft")!!, 1e-9)
    }

    @Test fun fetch_sf_skipsMetarAndWindlessStations() = runBlocking {
        val http = sfHttp()
        val provider = NwsObservationProvider(http, ManualClock(0L))
        val result = provider.fetchDetailed(sf, 30.0)
        // Candidates by distance: SFOC1 (no wind), OAMC1 (404 here), D3169 (calm). KSFO/KOAK are METAR.
        assertEquals(listOf("D3169"), result.items.map { it.stationId })
        assertEquals(5, result.requests)
        assertNull(result.error)
        assertFalse(http.requests.any { it.url.contains("/KSFO/") })
        val d = result.items.single()
        assertEquals(0.4, d.reliability, 0.0)
        assertEquals("APRSWXNET", d.network)
        assertTrue(http.requests.all { it.headers["User-Agent"] == NWS_USER_AGENT })
        assertEquals("https://api.weather.gov/points/37.7749,-122.4194", http.requests[0].url)
        assertTrue(http.requests[1].url.endsWith("/stations?limit=15"))
        assertTrue(result.bytesReceived > 0)
    }

    @Test fun fetch_canIncludeMetarStations() = runBlocking {
        val http = FakeHttpClient()
            .onFixture("/points/", "$dir/points_aspen.json")
            .onFixture("/stations?", "$dir/stations_aspen.json")
            .onFixture("/stations/CO100/", "$dir/latest_CO100.json")
            .onFixture("/stations/KASE/", "$dir/latest_KASE.json")
        val obs = NwsObservationProvider(http, ManualClock(0L), excludeMetar = false).fetch(aspen, 30.0)
        assertEquals(listOf("CO100", "KASE"), obs.map { it.stationId })
        assertEquals("MesoWest/CDOT", obs[0].network)
    }

    @Test fun stationListIsCachedPerCellWithClock() = runBlocking {
        val http = sfHttp()
        val clock = ManualClock(1_000_000L)
        val provider = NwsObservationProvider(http, clock)
        provider.fetch(sf, 30.0)
        fun pointsCalls() = http.requests.count { it.url.contains("/points/") }
        assertEquals(1, pointsCalls())

        clock.advanceBy(hour)
        provider.fetch(GeoPoint(sf.lat + 0.001, sf.lon + 0.001), 30.0) // same cell
        assertEquals(1, pointsCalls())

        clock.advanceBy(24 * hour)
        provider.fetch(sf, 30.0)
        assertEquals("expired after 24 h", 2, pointsCalls())

        provider.fetch(GeoPoint(sf.lat + 0.5, sf.lon), 30.0) // ~55 km north: other cell
        assertEquals(3, pointsCalls())
    }

    @Test fun cellKey_about25km() {
        val a = NwsObservationProvider.cellKey(GeoPoint(37.70, -122.40))
        assertEquals(a, NwsObservationProvider.cellKey(GeoPoint(37.701, -122.401)))
        assertNotEquals(a, NwsObservationProvider.cellKey(GeoPoint(37.70 + 0.25, -122.40)))
        assertNotEquals(a, NwsObservationProvider.cellKey(GeoPoint(37.70, -122.40 + 0.32)))
    }

    @Test fun outsideUs_404IsNegativelyCached() = runBlocking {
        val http = FakeHttpClient().on("/points/") { HttpResponse(404, body = fixture("$dir/points_outside_us_404.json")) }
        val clock = ManualClock(0L)
        val provider = NwsObservationProvider(http, clock)
        val munich = GeoPoint(48.1, 11.5)
        val first = provider.fetchDetailed(munich, 30.0)
        assertTrue(first.items.isEmpty())
        assertNull("outside the US is not an error", first.error)
        clock.advanceBy(hour)
        provider.fetchDetailed(munich, 30.0)
        assertEquals(1, http.requests.size)
        clock.advanceBy(6 * hour)
        provider.fetchDetailed(munich, 30.0)
        assertEquals(2, http.requests.size)
    }

    @Test fun serverErrorsAreReportedAndNotCached() = runBlocking {
        var calls = 0
        val http = FakeHttpClient().on("/points/") { calls++; HttpResponse(503, headers = mapOf("Retry-After" to "30")) }
        val provider = NwsObservationProvider(http, ManualClock(0L))
        val r = provider.fetchDetailed(sf, 30.0)
        assertEquals(503, r.httpStatus)
        assertEquals(30L, r.retryAfterSec)
        provider.fetchDetailed(sf, 30.0)
        assertEquals(2, calls)
    }

    @Test fun rateLimitOnObservationStopsTheCycle() = runBlocking {
        val http = FakeHttpClient()
            .onFixture("/points/", "$dir/points_sf.json")
            .onFixture("/gridpoints/MTR/85,105/stations", "$dir/stations_sf.json")
            .on("/observations/latest") { HttpResponse(429) }
        val r = NwsObservationProvider(http, ManualClock(0L)).fetchDetailed(sf, 30.0)
        assertEquals(429, r.httpStatus)
        assertEquals(3, r.requests)
    }
}
