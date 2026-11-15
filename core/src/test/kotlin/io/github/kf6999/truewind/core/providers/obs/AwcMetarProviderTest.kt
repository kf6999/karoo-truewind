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

class AwcMetarProviderTest {
    private val dir = "providers/obs/awc"
    private fun parse(name: String) = requireNotNull(AwcMetarProvider.parse(fixture("$dir/$name")))
    private fun List<AwcMetar>.station(id: String) = single { it.observation.stationId == id }

    @Test fun boundingBox_isSymmetricAndWiderInLongitude() {
        val b = AwcMetarProvider.boundingBox(GeoPoint(37.77, -122.42), 39.0)
        assertEquals(37.77 - 0.3503, b[0], 1e-3)
        assertEquals(37.77 + 0.3503, b[2], 1e-3)
        val dLon = b[3] - (-122.42)
        assertEquals(-122.42 - dLon, b[1], 1e-9)
        assertTrue("longitude span grows with latitude", dLon > 0.43 && dLon < 0.45)
    }

    @Test fun boundingBox_clampsAtPole() {
        val b = AwcMetarProvider.boundingBox(GeoPoint(89.9, 0.0), 30.0)
        assertEquals(90.0, b[2], 0.0)
        assertTrue(b[1] >= -180.0 && b[3] <= 180.0)
    }

    @Test fun buildUrl_matchesApiShape() {
        val url = AwcMetarProvider(FakeHttpClient()).buildUrl(GeoPoint(48.137, 11.575), 30.0)
        assertEquals("https://aviationweather.gov/api/data/metar?bbox=47.87,11.17,48.41,11.98&format=json&hours=1", url)
    }

    @Test fun sf_calmKnotsAndElevation() {
        val metars = parse("metar_sf_coastal.json")
        val ksfo = metars.station("KSFO").observation
        assertEquals(0.0, ksfo.speed!!, 0.0)
        assertEquals(WindVector.ZERO, ksfo.wind)
        assertFalse(ksfo.variableDirection)
        assertEquals(2.0, ksfo.elevationM!!, 0.0)
        assertEquals(1794736560_000L, ksfo.timeMs)
        assertEquals(13.3, ksfo.temperatureC!!, 1e-9)
        assertEquals("awc", ksfo.source)
        assertEquals(10.0, ksfo.sensorHeightM, 0.0)
        // one entry per station
        assertEquals(metars.map { it.observation.stationId }.distinct().size, metars.size)
    }

    @Test fun denver_keepsNewestReportPerStation() {
        val metars = parse("metar_denver_dupes.json")
        assertEquals(listOf("KAPA", "KBJC", "KDEN"), metars.map { it.observation.stationId }.sorted())
        val kden = metars.station("KDEN").observation
        // 310 deg at 3 kt: wind from the north-west, so it blows to the south-east (u > 0, v < 0).
        assertEquals(3 * MS_PER_KNOT, kden.speed!!, 1e-9)
        assertEquals(310.0, kden.wind!!.directionFromDeg, 1e-6)
        assertNull(kden.gust)
        // KBJC appears three times in the fixture; the 1794736500 report is the newest.
        assertEquals(1794736500_000L, metars.station("KBJC").observation.timeMs)
    }

    @Test fun munich_variableWindHasSpeedButNoVector() {
        val metars = parse("metar_munich.json")
        val eddm = metars.station("EDDM").observation
        assertTrue(eddm.variableDirection)
        assertNull(eddm.wind)
        assertEquals(2 * MS_PER_KNOT, eddm.speed!!, 1e-9)
        // EDMO newest report is VRB 1 kt; the older 190/4 report must be discarded.
        val edmo = metars.station("EDMO").observation
        assertTrue(edmo.variableDirection)
        assertEquals(1 * MS_PER_KNOT, edmo.speed!!, 1e-9)
    }

    @Test fun mountainValley_elevationInMetres() {
        val metars = parse("metar_aspen_valley.json")
        assertEquals(2384.0, metars.station("KASE").observation.elevationM!!, 0.0)
        val kege = metars.station("KEGE").observation
        assertEquals(100.0, kege.wind!!.directionFromDeg, 1e-6)
        assertEquals(6 * MS_PER_KNOT, kege.speed!!, 1e-9)
    }

    @Test fun gustsMissingWindAndThunder() {
        val metars = parse("metar_ts_gust_missing.json")
        val konp = metars.station("KONP")
        assertEquals(26 * MS_PER_KNOT, konp.observation.gust!!, 1e-9)
        assertEquals(ThunderIndicator.LIGHTNING_DISTANT, konp.thunder)
        assertTrue(konp.hasThunderstorm)
        assertEquals(ThunderIndicator.LIGHTNING_DISTANT, metars.station("KBWD").thunder)
        assertEquals(22 * MS_PER_KNOT, metars.station("CZPC").observation.speed!!, 1e-9)

        // A station that reported no wind at all is kept in the detailed list but has no speed...
        val kslo = metars.station("KSLO").observation
        assertNull(kslo.speed)
        assertNull(kslo.wind)
        assertFalse(kslo.variableDirection)
        assertEquals(ThunderIndicator.NONE, metars.station("CYSC").thunder)
        assertTrue(metars.station("CYSC").observation.variableDirection)
    }

    @Test fun fetch_dropsStationsWithoutWind() = runBlocking {
        val http = FakeHttpClient().onFixture("bbox=", "$dir/metar_ts_gust_missing.json")
        val provider = AwcMetarProvider(http)
        val obs = provider.fetch(GeoPoint(40.0, -100.0), 30.0)
        assertFalse(obs.any { it.stationId == "KSLO" || it.stationId == "KMLP" })
        assertTrue(obs.any { it.stationId == "KONP" })
        assertEquals(1, http.requests.size)
        assertEquals("GET", http.requests[0].method)
    }

    @Test fun fetch_204IsEmptyNotError() = runBlocking {
        val http = FakeHttpClient().on("bbox=") { HttpResponse(204, body = ByteArray(0)) }
        val result = AwcMetarProvider(http).fetchMetars(GeoPoint(-39.7, -139.7), 30.0)
        assertTrue(result.items.isEmpty())
        assertNull(result.error)
        assertEquals(1, result.requests)
    }

    @Test fun fetch_errorsAreReportedNotThrown() = runBlocking {
        val p429 = AwcMetarProvider(FakeHttpClient().on("bbox=") { HttpResponse(429, headers = mapOf("retry-after" to "120")) })
            .fetchMetars(GeoPoint(0.0, 0.0), 30.0)
        assertEquals(429, p429.httpStatus)
        assertEquals(120L, p429.retryAfterSec)
        assertNotNull(p429.error)

        val transport = AwcMetarProvider(FakeHttpClient().on("bbox=") { throw java.io.IOException("offline") })
            .fetchMetars(GeoPoint(0.0, 0.0), 30.0)
        assertEquals("offline", transport.error)

        val garbage = AwcMetarProvider(FakeHttpClient().on("bbox=") { HttpResponse(200, body = "<html>".toByteArray()) })
            .fetchMetars(GeoPoint(0.0, 0.0), 30.0)
        assertNotNull(garbage.error)
        assertTrue(garbage.items.isEmpty())
    }

    @Test fun parse_toleratesUnknownKeysNullsAndJunk() {
        val body = """[{"icaoId":"XXXX","lat":1,"lon":2,"obsTime":100,"wdir":null,"wspd":null,"newKey":{"a":[1]}},
            {"icaoId":null},{"lat":"x"},42,"str",
            {"icaoId":"YYYY","lat":"1.5","lon":"2.5","obsTime":"200","wdir":"270","wspd":"10","wgst":0}]""".toByteArray()
        val metars = requireNotNull(AwcMetarProvider.parse(body))
        assertEquals(listOf("XXXX", "YYYY"), metars.map { it.observation.stationId })
        val y = metars[1].observation
        assertEquals(270.0, y.wind!!.directionFromDeg, 1e-6)
        assertNull("gust 0 means no gust", y.gust)
        assertNull(AwcMetarProvider.parse("{\"not\":\"array\"}".toByteArray()))
        assertEquals(emptyList<AwcMetar>(), AwcMetarProvider.parse(ByteArray(0)))
    }

    @Test fun thunderIndicator_rules() {
        fun t(raw: String?, wx: String? = null) = AwcMetarProvider.thunderIndicator(raw, wx)
        assertEquals(ThunderIndicator.AT_STATION, t(null, "+TSRA BR"))
        assertEquals(ThunderIndicator.AT_STATION, t("METAR KXXX 151200Z 18010KT 3SM TS BKN020CB 20/18 A2990"))
        assertEquals(ThunderIndicator.VICINITY, t(null, "VCTS"))
        assertEquals(ThunderIndicator.VICINITY, t("METAR KXXX 151200Z 18010KT 10SM VCTS SCT050CB 20/18 A2990 RMK AO2"))
        assertEquals(ThunderIndicator.LIGHTNING, t("METAR KXXX 151200Z 18010KT 10SM SCT050 20/18 A2990 RMK AO2 OCNL LTGICCG OHD"))
        assertEquals(ThunderIndicator.LIGHTNING, t("METAR KXXX 151200Z 18010KT 10SM SCT050 20/18 A2990 RMK AO2 TSE05"))
        assertEquals(ThunderIndicator.LIGHTNING_DISTANT, t("METAR KXXX 151200Z 18010KT 10SM 20/18 A2990 RMK AO2 LTG DSNT W"))
        // Station ids and unrelated tokens never match.
        assertEquals(ThunderIndicator.NONE, t("METAR KTSX 151200Z 18010KT 10SM FEW050 20/18 A2990 RMK AO2 TSNO"))
        assertEquals(ThunderIndicator.NONE, t(null, "-RA BR"))
        assertEquals(ThunderIndicator.NONE, t(null, null))
        // Strongest wins.
        assertEquals(ThunderIndicator.AT_STATION, t("METAR KXXX 151200Z 18010KT 3SM -TSRA 20/18 A2990 RMK LTG DSNT E"))
    }
}
