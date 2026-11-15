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

import io.github.kf6999.truewind.core.api.HttpResponse
import io.github.kf6999.truewind.core.providers.obs.FakeHttpClient
import io.github.kf6999.truewind.core.providers.obs.fixture
import io.github.kf6999.truewind.core.time.ManualClock
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RainViewerTest {
    @Test
    fun parsesLiveIndex() {
        val idx = assertNotNull(RainViewer.parseIndex(fixture("providers/radar/weather_maps.json")))
        assertEquals("https://tilecache.rainviewer.com", idx.host)
        assertEquals(13, idx.past.size)
        assertEquals(RadarFrame(1794738000_000L, "/v2/radar/41fb7dcea963"), idx.latest)
        assertTrue(idx.nowcast.isEmpty())
        assertTrue(idx.past.zipWithNext().all { (a, b) -> b.timeMs - a.timeMs == RainViewer.FRAME_INTERVAL_MS })
        assertEquals(1794738324_000L, idx.generatedMs)
    }

    @Test
    fun rejectsGarbageIndex() {
        assertNull(RainViewer.parseIndex(null))
        assertNull(RainViewer.parseIndex("<html>".toByteArray()))
        assertNull(RainViewer.parseIndex("""{"radar":{"past":[]}}""".toByteArray()))
        // Unknown keys and a non-https host are tolerated / replaced.
        val idx = assertNotNull(RainViewer.parseIndex("""{"x":1,"host":"http://evil","radar":{"past":[{"time":10,"path":"/a"},{"time":5,"path":"/b"},{"path":"/c"}]}}""".toByteArray()))
        assertEquals(RainViewer.DEFAULT_HOST, idx.host)
        assertEquals(listOf("/b", "/a"), idx.past.map { it.path })
    }

    @Test
    fun clientFetchesIndexAndAtMostFourTiles() = runBlocking {
        val http = FakeHttpClient()
            .onFixture("weather-maps.json", "providers/radar/weather_maps.json")
            .on("/41fb7dcea963/256/7/") { HttpResponse(200, body = ByteArray(334)) }
        val plan = TilePlanner.plan(TileMath.fromPixel(PixelPoint(30.0 * 256, 52.0 * 256), 7), 7, 256, 256)
        val r = RainViewerClient(http).fetchLatest(plan.tiles.map { it.tile })
        assertNull(r.error)
        assertEquals(5, r.requests)
        assertEquals(4, r.tiles.size)
        assertEquals(818L + 4 * 334, r.bytesReceived)
        assertTrue(http.requests.drop(1).all { it.url.endsWith("/2/1_1.png") })
    }

    @Test
    fun clientReportsIndexFailure() = runBlocking {
        val http = FakeHttpClient().on("weather-maps") { HttpResponse(503) }
        val r = RainViewerClient(http).fetchLatest(listOf(TileId(7, 1, 1)))
        assertEquals("HTTP 503", r.error)
        assertTrue(r.tiles.isEmpty())
        assertEquals(1, r.requests)
    }

    @Test
    fun framePolicyRefreshesEveryTenMinutesOnlyWhenVisible() {
        val clock = ManualClock(1_000_000L)
        val p = RadarFramePolicy<String>(clock)
        assertFalse(p.shouldRefresh(true), "no tiles planned yet")
        val tiles = listOf(TileId(7, 29, 51), TileId(7, 30, 51))
        assertTrue(p.setTiles(tiles))
        assertFalse(p.shouldRefresh(visible = false))
        assertTrue(p.shouldRefresh(visible = true))

        fun frame(i: Int) = RadarFrame(i * RainViewer.FRAME_INTERVAL_MS, "/f$i")
        p.onAttempt()
        assertEquals(tiles.sortedBy { it.x }, p.tilesToFetch(frame(1)))
        p.onSuccess(frame(1), tiles.associateWith { "f1" })
        assertFalse(p.shouldRefresh(true))
        assertTrue(p.tilesToFetch(frame(1)).isEmpty())

        clock.advanceBy(9 * 60_000L)
        assertFalse(p.shouldRefresh(true))
        assertEquals(60_000L, p.msUntilNextRefresh())
        clock.advanceBy(60_000L)
        assertTrue(p.shouldRefresh(true))
        assertFalse(p.shouldRefresh(false))

        for (i in 2..5) {
            p.onAttempt()
            p.onSuccess(frame(i), tiles.associateWith { "f$i" })
            clock.advanceBy(10 * 60_000L)
        }
        assertEquals(listOf("/f3", "/f4", "/f5"), p.cachedFrames.map { it.frame.path })
        assertEquals("/f5", p.latestFrame?.frame?.path)
    }

    @Test
    fun framePolicyInvalidatesOnNewTilesAndBacksOffOnFailure() {
        val clock = ManualClock(0L)
        val p = RadarFramePolicy<Int>(clock)
        p.setTiles(listOf(TileId(7, 1, 1)))
        p.onAttempt()
        p.onSuccess(RadarFrame(0, "/a"), mapOf(TileId(7, 1, 1) to 1, TileId(7, 9, 9) to 2))
        assertEquals(setOf(TileId(7, 1, 1)), p.latestFrame!!.tiles.keys, "tiles outside the plan are dropped")
        assertFalse(p.setTiles(listOf(TileId(7, 1, 1))))

        // Zoom tap: new tile set clears the cache; refresh allowed once the 30 s floor has passed.
        clock.advanceBy(10_000)
        assertTrue(p.setTiles(listOf(TileId(8, 2, 2))))
        assertTrue(p.cachedFrames.isEmpty())
        assertFalse(p.shouldRefresh(true))
        clock.advanceBy(20_000)
        assertTrue(p.shouldRefresh(true))

        p.onAttempt()
        p.onFailure()
        clock.advanceBy(60_000)
        assertFalse(p.shouldRefresh(true))
        clock.advanceBy(60_000)
        assertTrue(p.shouldRefresh(true))
    }
}
