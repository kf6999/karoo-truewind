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

package io.github.kf6999.truewind.core.providers.wildfire

import io.github.kf6999.truewind.core.api.HttpResponse
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.providers.obs.FakeHttpClient
import io.github.kf6999.truewind.core.providers.obs.fixture
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures are real responses recorded 2026-11-15 (Timber fire, Big Sur; Aspen Acres fire, CO; an empty SF box). */
class NifcPerimetersTest {
    private fun load(name: String) = assertNotNull(NifcPerimeters.parse(fixture("providers/wildfire/$name")))

    @Test
    fun buildsBoundedSimplifiedQuery() {
        val url = NifcPerimeters.buildUrl(GeoPoint(36.25, -121.7), radiusKm = 25.0)
        assertTrue(url.startsWith(NifcPerimeters.DEFAULT_URL + "?where=1%3D1"))
        assertTrue("geometry=-121.9785%2C36.0254%2C-121.4215%2C36.4746" in url, url)
        assertTrue("&f=geojson" in url && "geometryPrecision=3" in url && "maxAllowableOffset=0.005" in url && "outSR=4326" in url)
        assertTrue("outFields=poly_IncidentName%2Cpoly_GISAcres%2Cattr_PercentContained" in url)
    }

    @Test
    fun parsesRealResponses() {
        val timber = load("nifc_timber_bigsur.json").single()
        assertEquals("Timber", timber.name)
        assertEquals("Timber", timber.id)
        assertEquals(25349.5, timber.acres!!, 0.1)
        assertEquals(100.0, timber.percentContained)
        assertEquals(8, timber.polygons.size, "two single-point slivers dropped")

        val aspen = load("nifc_aspen_acres_co.json").single()
        assertEquals("Aspen Acres", aspen.name)
        assertEquals(96.0, aspen.percentContained)
        assertTrue(aspen.polygons.size > 100)

        assertTrue(load("nifc_empty_sf.json").isEmpty())
    }

    @Test
    fun parserIsDefensive() {
        assertNull(NifcPerimeters.parse(null))
        assertNull(NifcPerimeters.parse("""{"error":{"code":400,"message":"Invalid query"}}""".toByteArray()))
        val odd = assertNotNull(
            NifcPerimeters.parse(
                """{"type":"FeatureCollection","features":[
                 {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[0,0],[0,1],[1,1],[0,0]]]},"properties":{}},
                 {"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},"properties":{"poly_IncidentName":"P"}},
                 {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[0,0],[0,0],[0,0]]]},"properties":{"poly_IncidentName":"Degenerate"}},
                 {"type":"Feature","geometry":null}]}""".toByteArray(),
            ),
        )
        assertEquals(1, odd.size)
        assertEquals("Wildfire", odd[0].name)
    }

    @Test
    fun distanceInsideAndOutsideRealPerimeters() {
        val timber = load("nifc_timber_bigsur.json")
        val inside = assertNotNull(FireDistance.nearest(GeoPoint(36.25, -121.70), timber))
        assertTrue(inside.inside)
        assertEquals(0.0, inside.distanceKm)
        assertNull(inside.bearingDeg)

        // Monterey: ~36 km to the nearest vertex (python reference); edges can only be closer.
        val monterey = assertNotNull(FireDistance.nearest(GeoPoint(36.6, -121.9), timber))
        assertFalse(monterey.inside)
        assertTrue(monterey.distanceKm in 33.0..36.2, "${monterey.distanceKm}") // nearest reference vertex is a dropped 1-point sliver
        assertTrue(monterey.bearingDeg!! in 120.0..180.0, "fire is SSE of Monterey: ${monterey.bearingDeg}")

        val aspen = load("nifc_aspen_acres_co.json")
        assertTrue(FireDistance.nearest(GeoPoint(38.05, -105.0), aspen)!!.inside)
        val west = FireDistance.nearest(GeoPoint(38.0, -105.3), aspen)!!
        assertTrue(west.distanceKm in 11.0..13.49, "${west.distanceKm}")
        assertTrue(west.bearingDeg!! in 45.0..135.0)
    }

    @Test
    fun handMadeSquareWithHole() {
        // 0.2 deg square around (40, -105) with a hole in the middle.
        val outer = listOf(GeoPoint(39.9, -105.1), GeoPoint(39.9, -104.9), GeoPoint(40.1, -104.9), GeoPoint(40.1, -105.1))
        val hole = listOf(GeoPoint(39.99, -105.01), GeoPoint(39.99, -104.99), GeoPoint(40.01, -104.99), GeoPoint(40.01, -105.01))
        val p = FirePerimeter("sq", "Square", polygons = listOf(FirePolygon(listOf(outer, hole))))
        val inHole = FireDistance.proximity(GeoPoint(40.0, -105.0), p)!!
        assertFalse(inHole.inside)
        assertEquals(0.01 * 111.32 * kotlin.math.cos(Math.toRadians(40.0)), inHole.distanceKm, 0.01)
        assertTrue(FireDistance.proximity(GeoPoint(39.95, -105.0), p)!!.inside)
        val north = FireDistance.proximity(GeoPoint(40.2, -105.0), p)!!
        assertEquals(11.13, north.distanceKm, 0.05)
        assertEquals(180.0, north.bearingDeg!!, 0.5)
    }

    @Test
    fun fetchReportsErrorsAndBytes() = runBlocking {
        val ok = FakeHttpClient().onFixture("WFIGS_Interagency_Perimeters_Current", "providers/wildfire/nifc_timber_bigsur.json")
        val r = NifcPerimeters.fetch(ok, GeoPoint(36.25, -121.7))
        assertNull(r.error)
        assertEquals(1, r.items.size)
        assertEquals(1652L, r.bytesReceived)
        assertEquals("gzip", ok.requests.single().headers["Accept-Encoding"])

        val limited = FakeHttpClient().on("WFIGS") { HttpResponse(429, headers = mapOf("Retry-After" to "60")) }
        val e = NifcPerimeters.fetch(limited, GeoPoint(36.25, -121.7))
        assertEquals(429, e.httpStatus)
        assertEquals(60L, e.retryAfterSec)
    }
}
