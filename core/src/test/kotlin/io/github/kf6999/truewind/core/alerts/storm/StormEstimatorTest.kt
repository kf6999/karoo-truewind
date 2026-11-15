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

package io.github.kf6999.truewind.core.alerts.storm

import io.github.kf6999.truewind.core.alerts.nws.AlertSeverity
import io.github.kf6999.truewind.core.alerts.nws.AlertUrgency
import io.github.kf6999.truewind.core.alerts.nws.NwsAlert
import io.github.kf6999.truewind.core.math.haversineKm
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.providers.obs.ThunderIndicator
import io.github.kf6999.truewind.core.providers.radar.Block
import io.github.kf6999.truewind.core.providers.radar.DALLAS_FRAME_MS
import io.github.kf6999.truewind.core.providers.radar.DALLAS_TILE
import io.github.kf6999.truewind.core.providers.radar.DbzGrid
import io.github.kf6999.truewind.core.providers.radar.PixelPoint
import io.github.kf6999.truewind.core.providers.radar.TileId
import io.github.kf6999.truewind.core.providers.radar.TileMath
import io.github.kf6999.truewind.core.providers.radar.dallasTile
import io.github.kf6999.truewind.core.providers.radar.syntheticTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StormEstimatorTest {
    private val tile = TileId(7, 29, 51)
    private fun geo(px: Double, py: Double) = TileMath.fromPixel(PixelPoint(29 * 256 + px, 51 * 256 + py), 7)
    private val now = 1_800_000_000_000L

    @Test
    fun findsNearestCoreAndRejectsSpeckle() {
        // Rider at pixel (100,128). A single 50 dBZ pixel (speckle) 10 px east, a real 8x8 cell 30 px west.
        val img = syntheticTile(Block(110, 128, 1, 1, 55), Block(62, 124, 8, 8, 48), Block(64, 126, 2, 2, 52), Block(150, 150, 10, 10, 40))
        val grid = assertNotNull(DbzGrid.fromTiles(mapOf(tile to img), now))
        val rider = geo(100.0, 128.0)
        val cores = RadarCoreFinder().findCores(grid, rider)
        assertEquals(1, cores.size, "speckle and sub-threshold cell are ignored")
        val c = cores.single()
        assertEquals(64, c.pixelCount)
        assertEquals(52, c.maxDbz)
        val mpp = TileMath.metresPerPixel(rider.lat, 7) / 1000
        assertEquals(30 * mpp, c.distanceKm, 0.3)
        assertEquals(270.0, c.bearingDeg!!, 2.0)

        // With speckle filtering off, the single pixel is nearest.
        val loose = RadarCoreFinder(minClusterAreaKm2 = 0.0, minClusterPixels = 1).nearestCore(grid, rider)!!
        assertEquals(10 * mpp, loose.distanceKm, 0.3)
        assertEquals(90.0, loose.bearingDeg!!, 2.0)
    }

    @Test
    fun riderInsideCoreIsZeroDistance() {
        val img = syntheticTile(Block(90, 120, 20, 20, 47))
        val grid = DbzGrid.fromTiles(mapOf(tile to img), now)!!
        val c = RadarCoreFinder().nearestCore(grid, geo(100.0, 130.0))!!
        assertEquals(0.0, c.distanceKm)
        assertNull(c.bearingDeg)
    }

    @Test
    fun realDallasTileHasCoreWestOfCentre() {
        val grid = DbzGrid.fromTiles(mapOf(DALLAS_TILE to dallasTile()), DALLAS_FRAME_MS)!!
        val rider = geo(128.0, 128.0)
        val cores = RadarCoreFinder().findCores(grid, rider)
        assertTrue(cores.size >= 3, "several convective cells in the frame")
        val nearest = cores.first()
        // Independent check (python decoder): nearest >= 45 dBZ pixel to the tile centre is (87,123), ~40.7 px.
        val mpp = TileMath.metresPerPixel(rider.lat, 7) / 1000
        assertTrue(nearest.distanceKm in 38 * mpp..41 * mpp, "distance ${nearest.distanceKm}")
        assertTrue(nearest.bearingDeg!! in 255.0..285.0, "bearing ${nearest.bearingDeg}")
        assertTrue(nearest.maxDbz >= 45)
        assertTrue(cores.maxOf { it.maxDbz } == 53)
    }

    @Test
    fun metarOnlyEstimateUsesNearEdgeOfBand() {
        val rider = GeoPoint(32.9, -97.0)
        val station = GeoPoint(32.9, -96.7) // ~28 km east
        val d = haversineKm(rider, station)
        val est = StormEstimator().estimate(rider, now, stations = listOf(StationThunder("KDFW", station, now - 10 * 60_000, ThunderIndicator.VICINITY)))!!
        assertEquals(d - 16, est.distanceKm!!, 1e-6)
        assertEquals(90.0, est.bearingDeg!!, 1.0)
        assertEquals(setOf(StormBasis.METAR_THUNDER), est.basis)
        assertEquals(StormConfidence.LOW, est.confidence)
        assertTrue(est.isEstimate)
        assertTrue(est.label().endsWith("km est."))

        val at = StormEstimator().estimate(rider, now, stations = listOf(StationThunder("KDFW", station, now, ThunderIndicator.AT_STATION)))!!
        assertEquals(StormConfidence.MEDIUM, at.confidence)
        // Stale METAR is ignored.
        assertNull(StormEstimator().estimate(rider, now, stations = listOf(StationThunder("KDFW", station, now - 2 * 3600_000, ThunderIndicator.AT_STATION))))
    }

    @Test
    fun radarCorroboratedByMetarIsHighConfidence() {
        val rider = GeoPoint(32.9, -97.0)
        val corePoint = GeoPoint(32.9, -96.8)
        val core = RadarCore(haversineKm(rider, corePoint), 90.0, corePoint, 49, 40, 40.0, now - 5 * 60_000)
        val radarOnly = StormEstimator().estimate(rider, now, radarCore = core)!!
        assertEquals(StormConfidence.LOW, radarOnly.confidence)
        assertEquals("radar", radarOnly.nearestSource)
        assertEquals(49, radarOnly.maxDbz)

        val both = StormEstimator().estimate(
            rider, now, radarCore = core,
            stations = listOf(StationThunder("KDAL", GeoPoint(32.85, -96.85), now, ThunderIndicator.VICINITY)),
        )!!
        assertEquals(StormConfidence.HIGH, both.confidence)
        assertEquals(setOf(StormBasis.RADAR_CORE, StormBasis.METAR_THUNDER), both.basis)
        // Old radar frame is ignored.
        assertNull(StormEstimator().estimate(rider, now, radarCore = core.copy(frameTimeMs = now - 3600_000)))
    }

    @Test
    fun nwsWarningWithoutDistanceAndStrikesAreNotEstimates() {
        val rider = GeoPoint(32.9, -97.0)
        val warn = NwsAlert("urn:1", "Severe Thunderstorm Warning", AlertSeverity.SEVERE, AlertUrgency.IMMEDIATE, onsetMs = now - 60_000, endsMs = now + 3600_000)
        val watch = warn.copy(id = "urn:2", event = "Severe Thunderstorm Watch")
        assertNull(StormEstimator().estimate(rider, now, nwsAlerts = listOf(watch)))
        val w = StormEstimator().estimate(rider, now, nwsAlerts = listOf(warn, watch))!!
        assertNull(w.distanceKm)
        assertEquals(StormConfidence.MEDIUM, w.confidence)
        assertEquals("warn", w.label())

        val strike = LightningStrike(GeoPoint(32.9, -96.9), now - 60_000)
        val s = StormEstimator().estimate(rider, now, nwsAlerts = listOf(warn), strikes = listOf(strike))!!
        assertFalse(s.isEstimate)
        assertEquals(setOf(StormBasis.LIGHTNING_STRIKES), s.basis)
        assertFalse(s.label().contains("est."))
    }

    @Test
    fun stationThunderFromMetarsKeepsNewestPerStation() {
        assertTrue(StationThunder.fromMetars(emptyList()).isEmpty())
    }
}
