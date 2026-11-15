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

import io.github.kf6999.truewind.core.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenMeteoRequestsTest {
    private fun query(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @Test
    fun nowcastMatchesSpec() {
        val url = OpenMeteoRequests.nowcastUrl(
            listOf(GeoPoint(37.77, -122.42), GeoPoint(37.6213, -122.379)),
            listOf("ncep_hrrr_conus", "ncep_rrfs_conus", "ecmwf_ifs"),
        )
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?"))
        val q = query(url)
        assertEquals("37.77,37.6213", q["latitude"])
        assertEquals("-122.42,-122.379", q["longitude"])
        assertEquals("wind_speed_10m,wind_direction_10m,wind_gusts_10m,temperature_2m,relative_humidity_2m,precipitation", q["minutely_15"])
        assertEquals("surface_pressure,pressure_msl,cloud_cover,weather_code,is_day,uv_index", q["current"])
        // ecmwf_ifs moved to the front so `current` has pressure; the rest keep their order.
        assertEquals("ecmwf_ifs,ncep_hrrr_conus,ncep_rrfs_conus", q["models"])
        assertEquals("2", q["past_minutely_15"])
        assertEquals("16", q["forecast_minutely_15"])
        assertEquals("ms", q["wind_speed_unit"])
        assertEquals("unixtime", q["timeformat"])
        assertTrue(url.contains("models=ecmwf_ifs,ncep_hrrr_conus")) // literal commas
    }

    @Test
    fun nowcastWithoutModelsOrCurrentSource() {
        val q = query(OpenMeteoRequests.nowcastUrl(listOf(GeoPoint(1.0, 2.0)), emptyList()))
        assertEquals(null, q["models"])
        val q2 = query(OpenMeteoRequests.nowcastUrl(listOf(GeoPoint(1.0, 2.0)), listOf("a", "b"), currentFrom = null))
        assertEquals("a,b", q2["models"])
    }

    @Test
    fun capsAtTenPoints() {
        val pts = (0 until 14).map { GeoPoint(it.toDouble(), 0.0) }
        val q = query(OpenMeteoRequests.nowcastUrl(pts, listOf("ecmwf_ifs")))
        assertEquals(10, q.getValue("latitude").split(',').size)
        assertFailsWith<IllegalArgumentException> { OpenMeteoRequests.nowcastUrl(emptyList(), emptyList()) }
    }

    @Test
    fun hourly() {
        val q = query(OpenMeteoRequests.hourlyUrl(listOf(GeoPoint(37.77, -122.42))))
        assertEquals("12", q["forecast_hours"])
        assertTrue(q.getValue("current").split(',').containsAll(listOf("dew_point_2m", "visibility", "apparent_temperature", "shortwave_radiation", "cape", "uv_index", "is_day")))
        assertTrue(q.getValue("hourly").contains("precipitation_probability"))
        assertEquals(null, q["models"])
    }

    @Test
    fun coordinateFormatting() {
        assertEquals("0", formatCoordinate(-0.0000001))
        assertEquals("1.5", formatCoordinate(1.5))
        assertEquals("-122.41286", formatCoordinate(-122.412864))
        assertEquals("10", formatCoordinate(10.0))
    }

    @Test
    fun requestsAskForGzip() {
        assertEquals("gzip", OpenMeteoRequests.get("u").headers["Accept-Encoding"])
        assertEquals("GET", OpenMeteoRequests.get("u").method)
    }
}
