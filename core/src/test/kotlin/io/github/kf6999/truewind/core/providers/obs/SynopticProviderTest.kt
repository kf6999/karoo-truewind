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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SynopticProviderTest {
    private val dir = "providers/obs/synoptic"
    private val sf = GeoPoint(37.7749, -122.4194)
    private val aspen = GeoPoint(39.1911, -106.8175)
    private val nyc = GeoPoint(40.78, -73.97)
    private val fakeToken = "test-token-123"

    private fun parse(name: String, center: GeoPoint) = requireNotNull(SynopticProvider.parse(fixture("$dir/$name"), center))
    private fun List<SynopticStation>.station(id: String) = single { it.observation.stationId == id }

    @Test fun buildUrl_radiusInMilesAndTokenLast() {
        val url = SynopticProvider(FakeHttpClient(), fakeToken).buildUrl(sf, 25.0)
        assertEquals(
            "https://api.synopticdata.com/v2/stations/latest?radius=37.7749,-122.4194,15.53&limit=8" +
                "&vars=wind_speed,wind_direction,wind_gust,air_temp&within=30&units=metric&sensorvars=1&token=test-token-123",
            url,
        )
        assertFalse(SynopticProvider(FakeHttpClient(), fakeToken, sensorVars = false).buildUrl(sf, 25.0).contains("sensorvars"))
    }

    @Test fun redact_removesToken() {
        val url = SynopticProvider(FakeHttpClient(), fakeToken).buildUrl(sf, 25.0)
        val red = SynopticProvider.redact(url)
        assertFalse(red.contains(fakeToken))
        assertTrue(red.endsWith("&token=REDACTED"))
        assertEquals("https://x/?token=REDACTED&a=1", SynopticProvider.redact("https://x/?token=abc&a=1"))
    }

    @Test fun elevationFeetToMetres_andNetworks() {
        val stations = parse("latest_sf_sensorvars.json", sf)
        val c5988 = stations.station("C5988")
        assertEquals(177.26 * 0.3048, c5988.observation.elevationM!!, 1e-9)
        assertEquals(65, c5988.mnetId)
        assertEquals("CWOP", c5988.observation.network)
        assertEquals(0.4, c5988.observation.reliability, 0.0)
        assertEquals(0.895, c5988.observation.speed!!, 1e-9)
        assertEquals(222.0, c5988.observation.wind!!.directionFromDeg, 1e-6)
        assertEquals(1.79, c5988.observation.gust!!, 1e-9)
        assertEquals(java.time.Instant.parse("2026-11-15T10:09:00Z").toEpochMilli(), c5988.observation.timeMs)
        // Computed distance in km; Synoptic's own DISTANCE (1.25) is in miles.
        assertEquals(1.25 * SynopticProvider.KM_PER_MILE, c5988.distanceKm!!, 0.05)
    }

    @Test fun calmOrIdleIsNotNorthWind() {
        val stations = parse("latest_sf_sensorvars.json", sf)
        val e9227 = stations.station("E9227") // speed 0.0, direction 0.0, CWOP
        assertTrue(e9227.calmOrIdle)
        assertEquals(WindVector.ZERO, e9227.observation.wind)
        assertEquals(0.0, e9227.observation.speed!!, 0.0)
        assertFalse(e9227.observation.variableDirection)
        assertEquals("idle CWOP sensor halves again", 0.2, e9227.observation.reliability, 1e-9)

        val f6803 = stations.station("F6803") // speed 0.0, direction 251: calm but sensor alive
        assertFalse(f6803.calmOrIdle)
        assertEquals(WindVector.ZERO, f6803.observation.wind)
        assertEquals(0.4, f6803.observation.reliability, 0.0)
        assertNull("gust 0.0 is no gust", f6803.observation.gust)
    }

    @Test fun temperatureOnlyStationsHaveNoWind() {
        val stations = parse("latest_sf_sensorvars.json", sf)
        val sfoc1 = stations.station("SFOC1")
        assertNull(sfoc1.observation.speed)
        assertEquals(13.883, sfoc1.observation.temperatureC!!, 1e-9)
        assertEquals(0.7, sfoc1.observation.reliability, 0.0)
    }

    @Test fun sensorHeight_presentAndAbsent() {
        val aspenStations = parse("latest_aspen_sensorvars.json", aspen)
        val kase = aspenStations.station("KASE")
        assertTrue(kase.sensorHeightReported)
        assertEquals(10.0, kase.observation.sensorHeightM, 0.0)
        assertEquals(1, kase.mnetId)
        assertEquals(1.0, kase.observation.reliability, 0.0)
        assertTrue("ASOS 0/0 is calm", kase.calmOrIdle)
        assertEquals(7673.0 * 0.3048, kase.observation.elevationM!!, 1e-9)

        val co100 = aspenStations.station("CO100")
        assertFalse(co100.sensorHeightReported)
        assertEquals(10.0, co100.observation.sensorHeightM, 0.0)
        assertEquals("MNET_63", co100.observation.network)

        // Without sensorvars=1 nothing is reported.
        assertTrue(parse("latest_sf_plain.json", sf).none { it.sensorHeightReported })

        val klga = parse("latest_nyc_sensorvars.json", nyc).station("KLGA")
        assertTrue(klga.sensorHeightReported)
        assertEquals(6.688, klga.observation.speed!!, 1e-9)
        assertEquals(310.0, klga.observation.wind!!.directionFromDeg, 1e-6)
    }

    @Test fun nonStandardSensorHeightIsParsed() {
        val body = """{"STATION":[{"STID":"T1","LATITUDE":"40","LONGITUDE":"-100","ELEVATION":"100","MNET_ID":"2",
            "UNITS":{"position":"m","elevation":"ft"},
            "OBSERVATIONS":{"wind_speed_value_2":{"value":9,"date_time":"2026-11-15T10:00:00Z"},
                            "wind_speed_value_1":{"value":3,"date_time":"2026-11-15T10:00:00Z"},
                            "wind_direction_value_1d":{"value":"N","date_time":"2026-11-15T10:00:00Z"},
                            "wind_direction_value_1":{"value":90,"date_time":"2026-11-15T10:00:00Z"}},
            "SENSOR_VARIABLES":{"wind_speed":{"wind_speed_value_1":{"position":"6.1"}}},
            "QC_FLAGGED":true,"EXTRA":{"x":[1,2]}}],"SUMMARY":{"RESPONSE_CODE":1}}""".toByteArray()
        val s = requireNotNull(SynopticProvider.parse(body)).single()
        assertEquals(6.1, s.observation.sensorHeightM, 1e-9)
        assertEquals("primary sensor wins", 3.0, s.observation.speed!!, 0.0)
        assertEquals(90.0, s.observation.wind!!.directionFromDeg, 1e-6)
        assertTrue(s.qcFlagged)
        assertEquals("RAWS 1.0 halved by QC flag", 0.5, s.observation.reliability, 0.0)
        assertEquals("RAWS", s.observation.network)
    }

    @Test fun fetch_filtersWindlessAndCountsBytes() = runBlocking {
        val http = FakeHttpClient().onFixture("stations/latest", "$dir/latest_sf_sensorvars.json")
        val provider = SynopticProvider(http, fakeToken)
        val obs = provider.fetch(sf, 25.0)
        assertEquals(7, obs.size)
        assertTrue(obs.all { it.source == "synoptic" && it.speed != null })
        assertEquals(1, http.requests.size)
    }

    @Test fun fetch_noStationsIsEmptyNotError() = runBlocking {
        val http = FakeHttpClient().onFixture("stations/latest", "$dir/latest_no_stations.json")
        val r = SynopticProvider(http, fakeToken).fetchStations(GeoPoint(-40.0, -140.0), 25.0)
        assertTrue(r.items.isEmpty())
        assertNull(r.error)
    }

    @Test fun fetch_invalidTokenIsAnErrorWithoutTheToken() = runBlocking {
        val http = FakeHttpClient().onFixture("stations/latest", "$dir/latest_invalid_token_401.json", status = 401)
        val r = SynopticProvider(http, fakeToken).fetchStations(sf, 25.0)
        assertEquals(401, r.httpStatus)
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("Invalid token"))
        assertFalse(r.error!!.contains(fakeToken))
    }

    @Test fun fetch_blankTokenMakesNoRequest() = runBlocking {
        val http = FakeHttpClient()
        val r = SynopticProvider(http, "  ").fetchStations(sf, 25.0)
        assertNotNull(r.error)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun parse_neverThrowsOnJunk() {
        assertNull(SynopticProvider.parse("[1,2]".toByteArray()))
        assertNull(SynopticProvider.parse(null))
        assertEquals(emptyList<SynopticStation>(), SynopticProvider.parse("""{"STATION":[null,1,{"STID":"x"},{"STID":"y","LATITUDE":"1","LONGITUDE":"1","OBSERVATIONS":{}}]}""".toByteArray()))
        val transport = runBlocking {
            SynopticProvider(FakeHttpClient().on("") { HttpResponse(0, error = "timeout") }, fakeToken).fetchStations(sf, 25.0)
        }
        assertEquals("timeout", transport.error)
    }
}
