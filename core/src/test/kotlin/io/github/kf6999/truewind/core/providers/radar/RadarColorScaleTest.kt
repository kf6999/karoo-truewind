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

import io.github.kf6999.truewind.core.providers.obs.fixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadarColorScaleTest {
    @Test
    fun embeddedTablesMatchPublishedCsv() {
        val rows = String(fixture("providers/radar/rainviewer_api_colors_table.csv")).trim().lines()
        val header = rows.first().split(',')
        val col = header.indexOf("Universal Blue")
        val data = rows.drop(1).map { it.split(',') }
        assertEquals(256, data.size)
        val rain = data.take(128)
        val snow = data.drop(128)
        rain.forEachIndexed { i, r ->
            assertEquals(i - 32, r[0].toInt())
            assertEquals(r[col].removePrefix("#"), RadarColorScale.UNIVERSAL_BLUE_RAIN_RGBA[i], "rain dBZ ${i - 32}")
        }
        snow.forEachIndexed { i, r -> assertEquals(r[col].removePrefix("#"), RadarColorScale.UNIVERSAL_BLUE_SNOW_RGBA[i], "snow dBZ ${i - 32}") }
    }

    @Test
    fun decodesTableColours() {
        assertNull(RadarColorScale.decode(0))
        assertEquals(RadarEcho(45, PrecipKind.RAIN), RadarColorScale.decode(0xffff4400.toInt()))
        assertEquals(RadarEcho(50, PrecipKind.RAIN), RadarColorScale.decode(0xffc10000.toInt()))
        assertEquals(RadarEcho(15, PrecipKind.RAIN), RadarColorScale.decode(0xff88ddee.toInt()))
        assertEquals(RadarEcho(0, PrecipKind.RAIN), RadarColorScale.decode(0x49827b69))
        assertEquals(RadarEcho(30, PrecipKind.SNOW), RadarColorScale.decode(0xff4f8fff.toInt()))
        // Duplicated colours report the lowest dBZ.
        assertEquals(65, RadarColorScale.dbz(0xffffffff.toInt()))
        // Near miss (resampled) still decodes; far colours do not.
        assertEquals(45, RadarColorScale.dbz(0xfffd4502.toInt()))
        assertNull(RadarColorScale.decode(0xff808080.toInt()))
        for (d in -10..95) assertEquals(d.coerceAtMost(if (d >= 75) 75 else if (d >= 65) 65 else d), RadarColorScale.dbz(RadarColorScale.colorFor(d)))
    }

    @Test
    fun realTileDecodesExactlyAndSampledPixelsMatch() {
        val img = dallasTile()
        assertEquals(256, img.width)
        var echoes = 0
        var unknown = 0
        var strong = 0
        var max = -99
        for (p in img.pixels) {
            if ((p ushr 24) == 0) continue
            val e = RadarColorScale.decode(p, maxDistance = 0)
            if (e == null) unknown++ else {
                echoes++
                if (e.dbz >= 45) strong++
                max = maxOf(max, e.dbz)
            }
        }
        assertEquals(0, unknown, "every opaque pixel must be an exact table colour")
        assertEquals(56372, echoes)
        assertEquals(1943, strong)
        assertEquals(53, max)
        // Pixels sampled with an independent decoder (python zlib) from the same PNG.
        assertEquals(27, RadarColorScale.dbz(img[0, 0]))
        assertEquals(30, RadarColorScale.dbz(img[3, 0]))
        assertEquals(43, RadarColorScale.dbz(img[60, 0]))
        assertEquals(16, RadarColorScale.dbz(img[198, 0]))
        assertEquals(3, RadarColorScale.dbz(img[201, 0]))
        assertEquals(-8, RadarColorScale.dbz(img[204, 0]))
        assertEquals(50, RadarColorScale.dbz(img[141, 57]))
        assertEquals(0xffc10000.toInt(), img[141, 57])
    }

    @Test
    fun mosaicOfTiles() {
        val a = syntheticTile(Block(250, 0, 6, 6, 50))
        val b = syntheticTile(Block(0, 0, 6, 6, 40))
        val g = assertNotNull(DbzGrid.fromTiles(mapOf(TileId(7, 10, 20) to a, TileId(7, 11, 20) to b), 0L))
        assertEquals(512, g.width)
        assertEquals(256, g.height)
        assertEquals(50f, g[255, 0])
        assertEquals(40f, g[256, 0])
        assertTrue(g[300, 100].isNaN())
        assertEquals(PixelPoint(2560.0, 5120.0), g.originPx)

        // Antimeridian: x = 63 and x = 0 at z6 are adjacent, 63 on the left.
        val w = assertNotNull(DbzGrid.fromTiles(mapOf(TileId(6, 0, 30) to b, TileId(6, 63, 30) to a), 0L))
        assertEquals(50f, w[255, 0])
        assertEquals(40f, w[256, 0])
    }

    @Test
    fun marshallPalmer() {
        assertEquals(0.65, RadarColorScale.rainRateMmH(20.0), 0.05)
        assertEquals(23.7, RadarColorScale.rainRateMmH(45.0), 0.5)
    }
}
