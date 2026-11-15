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
import io.github.kf6999.truewind.core.model.WindVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenMeteoParserTest {
    private val conusModels = listOf("ncep_hrrr_conus", "ncep_rrfs_conus", "ncep_nbm_conus", "ecmwf_ifs", "gfs_global")
    private val sf = GeoPoint(37.77, -122.42)
    private val sfo = GeoPoint(37.62, -122.37)
    private val oak = GeoPoint(37.72, -122.22)
    private val paris = GeoPoint(48.85, 2.35)

    @Test
    fun conusThreeLocationsFiveMembers() {
        val r = OpenMeteoParser.parseNowcast(fixture("openmeteo/conus_nowcast_3loc.json"), conusModels, listOf(sf, sfo, oak))
        assertNull(r.error)
        assertEquals(emptyList(), r.failedModels)
        assertEquals(15, r.series.size)
        assertEquals(3, r.locations.size)
        assertEquals(listOf(0, 1, 2), r.locations.map { it.index })
        assertEquals(sfo, r.locations[1].requested)
        assertEquals(1.0, r.locations[1].elevationM)

        val hrrr = r.series.first { it.memberId == "open-meteo:ncep_hrrr_conus" && it.location == sf }
        assertEquals(14.0, hrrr.gridElevationM)
        assertEquals(6, hrrr.steps.size)
        assertEquals(1794735000_000L, hrrr.steps[0].timeMs)
        assertEquals(15 * 60_000L, hrrr.steps[1].timeMs - hrrr.steps[0].timeMs)
        val w = assertNotNull(hrrr.steps[0].wind10m)
        assertEquals(2.21, w.speed, 1e-9)
        assertEquals(159.0, w.directionFromDeg, 1e-9)
        // Same convention as core/model: wind FROM 159° (SSE) blows towards NNW, so u < 0, v > 0.
        assertEquals(WindVector.fromSpeedDirection(2.21, 159.0), w)
        assertTrue(w.u < 0 && w.v > 0)
        assertNotNull(hrrr.steps[0].gust10m)
        assertNotNull(hrrr.steps[0].temperatureC)
        assertNotNull(hrrr.steps[0].relativeHumidityPct)
        assertNotNull(hrrr.steps[0].precipitationMm)

        // Member order per location follows the request priority.
        assertEquals(conusModels.map { openMeteoMemberId(it) }, r.series.filter { it.location == sf }.map { it.memberId })
    }

    @Test
    fun currentValuesAreLocationLevelAndCopiedOntoTheMatchingStep() {
        val r = OpenMeteoParser.parseNowcast(fixture("openmeteo/conus_nowcast_3loc.json"), conusModels, listOf(sf, sfo, oak))
        val cur = assertNotNull(r.locations[0].current)
        assertEquals(1794736800_000L, cur.timeMs)
        assertEquals(1014.5, cur.surfacePressureHpa)
        assertEquals(1016.2, cur.sealevelPressureHpa)
        assertEquals(100.0, cur.cloudCoverPct)
        assertEquals(3, cur.weatherCode)
        assertEquals(false, cur.isDay)
        assertNull(cur.uvIndex) // null for HRRR in the live response

        val ecmwf = r.series.first { it.memberId == "open-meteo:ecmwf_ifs" && it.location == sf }
        val atCurrent = ecmwf.steps.first { it.timeMs == cur.timeMs }
        assertEquals(1014.5, atCurrent.surfacePressureHpa)
        assertEquals(3, atCurrent.weatherCode)
        assertNull(ecmwf.steps[0].surfacePressureHpa)
    }

    @Test
    fun europeTwoLocations() {
        val models = listOf("meteofrance_arome_france_hd", "dwd_icon_d2_ruc_native", "ecmwf_ifs", "gfs_global")
        val r = OpenMeteoParser.parseNowcast(fixture("openmeteo/europe_nowcast_2loc.json"), models, listOf(paris, GeoPoint(49.0, 2.55)))
        assertEquals(8, r.series.size)
        val arome = r.series.first { it.memberId == "open-meteo:meteofrance_arome_france_hd" && it.location == paris }
        assertEquals(4.22, arome.steps[0].wind10m!!.speed, 1e-9)
        assertEquals(175.0, arome.steps[0].wind10m!!.directionFromDeg, 1e-9)
        // Request was led by AROME HD, which has no pressure in `current`: nulls, not a crash.
        assertNull(r.locations[0].current!!.surfacePressureHpa)
        assertEquals(true, r.locations[0].current!!.isDay)
    }

    @Test
    fun allNullMemberIsReportedFailedAndOmitted() {
        val r = OpenMeteoParser.parseNowcast(fixture("openmeteo/nam_all_null_member.json"), listOf("ncep_nam_conus", "ecmwf_ifs"), listOf(sf))
        assertEquals(listOf("ncep_nam_conus"), r.failedModels)
        assertEquals(listOf("open-meteo:ecmwf_ifs"), r.series.map { it.memberId })
    }

    @Test
    fun unsuffixedKeysAttributedToTheOnlyCoveringMember() {
        // hrrr + meteoswiss requested at SF: Open-Meteo drops the out-of-domain member AND the suffix.
        val registry = ModelRegistry.DEFAULT
        val r = OpenMeteoParser.parseNowcast(
            fixture("openmeteo/single_model_left_unsuffixed.json"),
            listOf("ncep_hrrr_conus", "meteoswiss_icon_ch1"), listOf(sf), registry::covers,
        )
        assertEquals(listOf("open-meteo:ncep_hrrr_conus"), r.series.map { it.memberId })
        assertEquals(listOf("meteoswiss_icon_ch1"), r.failedModels)
        assertEquals(0, r.unattributedBlocks)
    }

    @Test
    fun unsuffixedKeysAreSkippedWhenAmbiguous() {
        val r = OpenMeteoParser.parseNowcast(
            fixture("openmeteo/single_model_left_unsuffixed.json"),
            listOf("ncep_hrrr_conus", "meteoswiss_icon_ch1"), listOf(sf), // default covers = always true
        )
        assertEquals(emptyList(), r.series)
        assertEquals(1, r.unattributedBlocks)
    }

    @Test
    fun multiLocationWhereSecondLocationLostTheSuffix() {
        val r = OpenMeteoParser.parseNowcast(
            fixture("openmeteo/multi_location_second_unsuffixed.json"),
            listOf("ncep_hrrr_conus", "ecmwf_ifs"), listOf(sf, paris), ModelRegistry.DEFAULT::covers,
        )
        assertEquals(1, r.locations[1].index)
        val atParis = r.series.filter { it.location == paris }
        assertEquals(listOf("open-meteo:ecmwf_ifs"), atParis.map { it.memberId })
        assertEquals(4.94, atParis[0].steps[0].wind10m!!.speed, 1e-9)
        assertEquals(3, r.series.size) // hrrr@sf, ecmwf@sf, ecmwf@paris
        assertEquals(emptyList(), r.failedModels)
    }

    @Test
    fun noModelsRequestedMeansBestMatch() {
        val text = """{"latitude":1.0,"longitude":2.0,"elevation":5,"minutely_15":{"time":[1000,1900],
            "wind_speed_10m":[0.0,3.0],"wind_direction_10m":[null,90]}}"""
        val r = OpenMeteoParser.parseNowcast(text, emptyList(), listOf(GeoPoint(1.0, 2.0)))
        val s = r.series.single()
        assertEquals("open-meteo:best_match", s.memberId)
        assertEquals(WindVector.ZERO, s.steps[0].wind10m) // calm without a direction is still valid
        assertEquals(-3.0, s.steps[1].wind10m!!.u, 1e-9) // from the east = blowing west
    }

    @Test
    fun partialNullsKeepTheStepsWithNullWind() {
        val text = """{"latitude":1,"longitude":2,"minutely_15":{"time":[0,900,1800],
            "wind_speed_10m_ecmwf_ifs":[2.0,null,2.0],"wind_direction_10m_ecmwf_ifs":[180,180,null]}}"""
        val s = OpenMeteoParser.parseNowcast(text, listOf("ecmwf_ifs"), listOf(GeoPoint(1.0, 2.0))).series.single()
        assertEquals(3, s.steps.size)
        assertNotNull(s.steps[0].wind10m)
        assertNull(s.steps[1].wind10m)
        assertNull(s.steps[2].wind10m)
    }

    @Test
    fun missingMinutelyBlockFailsEveryMember() {
        val text = """{"latitude":1,"longitude":2,"current":{"time":0,"surface_pressure":1000}}"""
        val r = OpenMeteoParser.parseNowcast(text, listOf("ecmwf_ifs", "gfs_global"), listOf(GeoPoint(1.0, 2.0)))
        assertEquals(emptyList(), r.series)
        assertEquals(listOf("ecmwf_ifs", "gfs_global"), r.failedModels)
        assertEquals(1000.0, r.locations[0].current!!.surfacePressureHpa)
    }

    @Test
    fun unknownKeysAndForeignSuffixesAreIgnored() {
        val text = """{"latitude":1,"longitude":2,"brand_new_field":{"x":[1,2]},"minutely_15_units":{"a":"b"},
            "minutely_15":{"time":[0],"wind_speed_10m_ecmwf_ifs":[1.0],"wind_direction_10m_ecmwf_ifs":[0],
            "wind_speed_10m_some_other_model":[9.0],"lightning_potential_ecmwf_ifs":[5],"precipitation_probability":[3]}}"""
        val r = OpenMeteoParser.parseNowcast(text, listOf("ecmwf_ifs"), listOf(GeoPoint(1.0, 2.0)))
        assertEquals(listOf("open-meteo:ecmwf_ifs"), r.series.map { it.memberId })
        assertEquals(-1.0, r.series[0].steps[0].wind10m!!.v, 1e-9)
        assertNull(r.series[0].steps[0].precipitationMm)
    }

    @Test
    fun isoTimesAreParsedWithTheUtcOffset() {
        val text = """{"latitude":1,"longitude":2,"utc_offset_seconds":3600,"minutely_15":{"time":["2026-11-15T11:00","bogus"],
            "wind_speed_10m":[1.0,2.0],"wind_direction_10m":[0,0]}}"""
        val s = OpenMeteoParser.parseNowcast(text, emptyList(), listOf(GeoPoint(1.0, 2.0))).series.single()
        assertEquals(1, s.steps.size)
        assertEquals(java.time.Instant.parse("2026-11-15T10:00:00Z").toEpochMilli(), s.steps[0].timeMs)
        assertEquals(1.0, s.steps[0].wind10m!!.speed, 1e-9)
    }

    @Test
    fun errorBodies() {
        val invalid = OpenMeteoParser.parseNowcast(fixture("openmeteo/error_invalid_model.json"), listOf("ecmwf_ifs"), listOf(sf))
        assertEquals("not_a_model_xyz", invalid.error!!.invalidModel)
        val noData = OpenMeteoParser.parseError(fixture("openmeteo/error_no_data_for_location.json"), 400)!!
        assertTrue(noData.noDataForLocation)
        assertEquals(400, noData.httpStatus)
        assertNull(noData.invalidModel)
        assertNotNull(OpenMeteoParser.parseNowcast("<html>502</html>", listOf("x"), listOf(sf)).error)
        assertNotNull(OpenMeteoParser.parseNowcast("", listOf("x"), listOf(sf)).error)
        assertNotNull(OpenMeteoParser.parseNowcast("42", listOf("x"), listOf(sf)).error)
    }

    @Test
    fun duplicateMembersAreDropped() {
        val r = OpenMeteoParser.parseNowcast(
            fixture("openmeteo/sydney_nowcast_dup_best_match.json"),
            listOf("best_match", "ecmwf_ifs", "gfs_global"), listOf(GeoPoint(-33.87, 151.21)),
        )
        assertEquals(3, r.series.size)
        assertEquals(listOf("open-meteo:best_match", "open-meteo:gfs_global"), dropDuplicateMembers(r.series).map { it.memberId })
    }

    @Test
    fun hourlyWithExtraCurrentVariables() {
        val h = OpenMeteoParser.parseHourly(fixture("openmeteo/hourly_12h_with_extras.json"), listOf(sf))
        assertNull(h.error)
        val loc = h.locations.single()
        val cur = loc.location.current!!
        assertEquals(10.0, cur.dewPointC)
        assertEquals(11.2, cur.apparentTemperatureC)
        assertEquals(14400.0, cur.visibilityM)
        assertEquals(0.0, cur.shortwaveRadiation)
        assertEquals(0.0, cur.capeJPerKg)
        assertEquals(0.0, cur.uvIndex)
        assertEquals(85.0, cur.relativeHumidityPct)
        assertEquals(2.42, cur.wind10m!!.speed, 1e-9)
        assertEquals(5.3, cur.gust10m)
        val steps = loc.members.getValue("best_match")
        assertEquals(12, steps.size)
        assertEquals(3600_000L, steps[1].step.timeMs - steps[0].step.timeMs)
        assertEquals(1.0, steps[0].precipitationProbabilityPct)
        assertEquals(false, steps[0].isDay)
        assertEquals(1014.5, steps[0].step.surfacePressureHpa)
        assertEquals(3, steps[0].step.weatherCode)
    }

    @Test
    fun gzipBodiesAreDecoded() {
        val raw = fixture("openmeteo/nam_all_null_member.json")
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(raw.toByteArray()) }
        assertEquals(raw, decodeBody(bos.toByteArray()))
        assertEquals("abc", decodeBody("abc".toByteArray()))
        assertNull(decodeBody(byteArrayOf()))
        assertNull(decodeBody(byteArrayOf(0x1f, 0x8b.toByte(), 1, 2))) // truncated gzip
    }
}
