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
import io.github.kf6999.truewind.core.time.ManualClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelRegistryTest {
    private val r = ModelRegistry.DEFAULT

    @Test
    fun conus() {
        assertEquals(
            listOf("ncep_hrrr_conus", "ncep_rrfs_conus", "ncep_nbm_conus", "ecmwf_ifs", "gfs_global"),
            r.membersFor(GeoPoint(37.77, -122.42)),
        )
    }

    @Test
    fun europeNationalModelsLeadAndGlobalsFollow() {
        assertEquals(
            listOf("meteofrance_arome_france_hd", "ecmwf_ifs", "gfs_global", "dwd_icon_eu_native"),
            r.membersFor(GeoPoint(43.6, 1.44)), // Toulouse: France only
        )
        assertEquals(
            listOf("meteofrance_arome_france_hd", "dwd_icon_d2_ruc_native", "ecmwf_ifs", "gfs_global", "dwd_icon_eu_native"),
            r.membersFor(GeoPoint(48.85, 2.35)), // Paris: inside both AROME and ICON-D2 boxes
        )
        assertEquals("ukmo_uk_deterministic_2km", r.membersFor(GeoPoint(51.5, -0.12)).first())
        assertEquals("meteoswiss_icon_ch1", r.membersFor(GeoPoint(47.37, 8.54)).first())
        assertEquals("metno_nordic", r.membersFor(GeoPoint(59.9, 10.75)).first())
        assertTrue(r.membersFor(GeoPoint(47.37, 8.54)).size <= 5)
    }

    @Test
    fun elsewhereFallsBack() {
        assertEquals(listOf("best_match", "ecmwf_ifs", "gfs_global"), r.membersFor(GeoPoint(-33.87, 151.21)))
        assertEquals(listOf("best_match", "ecmwf_ifs", "gfs_global"), r.membersFor(emptyList()))
    }

    @Test
    fun multiPointUsesTheRider() {
        assertEquals(r.membersFor(GeoPoint(37.77, -122.42)), r.membersFor(listOf(GeoPoint(37.77, -122.42), GeoPoint(48.85, 2.35))))
    }

    @Test
    fun covers() {
        assertTrue(r.covers("ecmwf_ifs", GeoPoint(-33.0, 151.0)))
        assertTrue(r.covers("ncep_hrrr_conus", GeoPoint(37.77, -122.42)))
        assertFalse(r.covers("ncep_hrrr_conus", GeoPoint(48.85, 2.35)))
        assertFalse(r.covers("meteoswiss_icon_ch1", GeoPoint(37.77, -122.42)))
    }

    @Test
    fun fromJson() {
        val reg = assertNotNull(
            ModelRegistry.fromJson(
                """{"maxMembers":2,"fallback":["gfs_global"],"regions":[
                  {"id":"x","box":[0,10,0,10],"members":["a","b","c"]},{"id":"bad","box":[1,2],"members":["z"]}],"extra":1}""",
            ),
        )
        assertEquals(1, reg.regions.size)
        assertEquals(listOf("a", "b"), reg.membersFor(GeoPoint(5.0, 5.0)))
        assertEquals(listOf("gfs_global"), reg.membersFor(GeoPoint(50.0, 50.0)))
        assertNull(ModelRegistry.fromJson("not json"))
        assertNull(ModelRegistry.fromJson("""{"fallback":[]}"""))
    }
}

class MemberAvailabilityTest {
    private val hour = 3_600_000L

    @Test
    fun dropForTwentyFourHoursThenRestore() {
        val clock = ManualClock(1_000L)
        val a = MemberAvailability(clock)
        a.markFailed("ncep_hrrr_conus")
        assertEquals(listOf("ncep_rrfs_conus", "ecmwf_ifs"), a.filter(listOf("ncep_hrrr_conus", "ncep_rrfs_conus", "ecmwf_ifs")))
        clock.advanceBy(24 * hour - 1)
        assertTrue(a.isDropped("ncep_hrrr_conus"))
        clock.advanceBy(1)
        assertFalse(a.isDropped("ncep_hrrr_conus"))
        assertEquals(listOf("ncep_hrrr_conus", "ecmwf_ifs"), a.filter(listOf("ncep_hrrr_conus", "ecmwf_ifs")))
    }

    @Test
    fun emptySetFallsBackToBestMatch() {
        val a = MemberAvailability(ManualClock(0))
        a.markFailed(listOf("x", "y"))
        assertEquals(listOf("best_match"), a.filter(listOf("x", "y")))
        assertEquals(listOf("best_match"), a.filter(emptyList()))
    }

    @Test
    fun failingAgainExtendsTheDrop() {
        val clock = ManualClock(0)
        val a = MemberAvailability(clock)
        a.markFailed("x")
        clock.advanceBy(20 * hour)
        a.markFailed("x")
        clock.advanceBy(10 * hour)
        assertTrue(a.isDropped("x"))
    }

    @Test
    fun snapshotAndRestore() {
        val clock = ManualClock(0)
        val a = MemberAvailability(clock)
        a.markFailed("x")
        val saved = a.snapshot()
        assertEquals(mapOf("x" to 24 * hour), saved)

        val b = MemberAvailability(clock)
        b.restore(saved + ("expired" to 0L))
        clock.advanceBy(1)
        assertTrue(b.isDropped("x"))
        assertEquals(setOf("x"), b.snapshot().keys)
    }
}
