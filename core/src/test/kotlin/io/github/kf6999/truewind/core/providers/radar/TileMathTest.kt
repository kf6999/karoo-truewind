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

package io.github.kf6999.truewind.core.providers.radar

import io.github.kf6999.truewind.core.math.haversineKm
import io.github.kf6999.truewind.core.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TileMathTest {
    @Test
    fun tileForKnownPoints() {
        assertEquals(TileId(7, 29, 51), TileMath.tileFor(GeoPoint(32.78, -96.80), 7)) // Dallas
        assertEquals(TileId(0, 0, 0), TileMath.tileFor(GeoPoint(10.0, 10.0), 0))
        assertEquals(TileId(1, 1, 0), TileMath.tileFor(GeoPoint(10.0, 10.0), 1))
        assertEquals(TileId(6, 10, 24), TileMath.tileFor(GeoPoint(38.0, -122.0), 6)) // SF Bay
        // Longitude 180 wraps to x = 0, latitude beyond Mercator range clamps.
        assertEquals(0, TileMath.tileFor(GeoPoint(0.0, 180.0), 3).x)
        assertEquals(0, TileMath.tileFor(GeoPoint(89.9, 0.0), 3).y)
    }

    @Test
    fun pixelRoundTrip() {
        for (p in listOf(GeoPoint(32.78, -96.8), GeoPoint(-33.9, 151.2), GeoPoint(64.1, -21.9), GeoPoint(0.0, 0.0))) {
            for (z in listOf(0, 5, 7, 12)) {
                val back = TileMath.fromPixel(TileMath.toPixel(p, z), z)
                assertEquals(p.lat, back.lat, 1e-9)
                assertEquals(p.lon, back.lon, 1e-9)
            }
        }
    }

    @Test
    fun metresPerPixelMatchesReference() {
        assertEquals(156543.034, TileMath.metresPerPixel(0.0, 0), 0.01)
        assertEquals(1222.99, TileMath.metresPerPixel(0.0, 7), 0.01)
        assertEquals(1222.99 * kotlin.math.cos(Math.toRadians(60.0)), TileMath.metresPerPixel(60.0, 7), 0.01)
        // Consistent with great-circle distance over 100 px east at 45 N, zoom 7.
        val p = GeoPoint(45.0, 7.0)
        val px = TileMath.toPixel(p, 7)
        val q = TileMath.fromPixel(PixelPoint(px.x + 100, px.y), 7)
        // Web Mercator uses the equatorial radius, haversine the mean radius: 0.1% apart.
        assertEquals(TileMath.metresPerPixel(45.0, 7) * 100 / 1000, haversineKm(p, q), 0.2)
    }

    @Test
    fun pixelInTile() {
        val p = TileMath.pixelInTile(GeoPoint(32.78, -96.80), 7)
        assertTrue(p.x in 0.0..256.0 && p.y in 0.0..256.0)
        assertEquals((29.5813 - 29) * 256, p.x, 0.5)
    }

    @Test
    fun planCentredInTileUsesOneToFourTiles() {
        val centre = TileMath.fromPixel(PixelPoint(29.5 * 256, 51.5 * 256), 7)
        val small = TilePlanner.plan(centre, 7, 200, 200)
        assertEquals(listOf(TileId(7, 29, 51)), small.tiles.map { it.tile })
        assertEquals(100.0, small.riderViewX)
        assertEquals(-28.0, small.tiles[0].viewX, 1e-6)

        val corner = TileMath.fromPixel(PixelPoint(30.0 * 256, 52.0 * 256), 7)
        val four = TilePlanner.plan(corner, 7, 256, 256)
        assertEquals(setOf(TileId(7, 29, 51), TileId(7, 30, 51), TileId(7, 29, 52), TileId(7, 30, 52)), four.tiles.map { it.tile }.toSet())
    }

    @Test
    fun overZoomUpscalesSourceZoomSeven() {
        val p = GeoPoint(32.78, -96.80)
        val plan = TilePlanner.plan(p, 8, 400, 400)
        assertEquals(7, plan.sourceZoom)
        assertEquals(2.0, plan.scale)
        assertTrue(plan.tiles.size in 1..4)
        assertEquals(TileMath.metresPerPixel(p.lat, 7) / 2, plan.metresPerViewPixel, 1e-9)
        assertTrue(plan.tiles.all { it.viewSize == 512.0 })
        // The rider maps back to the view centre.
        val v = plan.geoToView(p)
        assertEquals(200.0, v.x, 1e-6)
        assertEquals(200.0, v.y, 1e-6)
        val g = plan.viewToGeo(200.0, 200.0)
        assertEquals(p.lat, g.lat, 1e-9)
        assertEquals(p.lon, g.lon, 1e-9)
    }

    @Test
    fun rejectsViewNeedingMoreThanFourTiles() {
        assertFailsWith<IllegalArgumentException> { TilePlanner.plan(GeoPoint(0.0, 0.0), 6, 300, 200) }
    }

    @Test
    fun wrapsAcrossAntimeridian() {
        val plan = TilePlanner.plan(GeoPoint(-17.0, 179.99), 6, 200, 200)
        val xs = plan.tiles.map { it.tile.x }.toSet()
        assertEquals(setOf(0, 63), xs)
        // Fiji east of the antimeridian is drawn right of centre.
        assertTrue(plan.geoToView(GeoPoint(-17.0, -179.9)).x > 100)
    }

    @Test
    fun tileUrlFormat() {
        val f = RadarFrame(1794738000_000L, "/v2/radar/41fb7dcea963")
        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/41fb7dcea963/256/7/29/51/2/1_1.png",
            RainViewer.tileUrl("https://tilecache.rainviewer.com", f, TileId(7, 29, 51)),
        )
        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/41fb7dcea963/256/6/10/24/2/0_0.png",
            RainViewer.tileUrl("https://tilecache.rainviewer.com/", f, TileId(6, 10, 24), smooth = false, snow = false),
        )
        assertFailsWith<IllegalArgumentException> { RainViewer.tileUrl(RainViewer.DEFAULT_HOST, f, TileId(8, 1, 1)) }
    }
}
