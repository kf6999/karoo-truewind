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

package io.github.kf6999.truewind.core.replay

import io.github.kf6999.truewind.core.api.ForecastSeries
import io.github.kf6999.truewind.core.api.Observation
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.replay.cli.CorpusLoader
import io.github.kf6999.truewind.core.replay.cli.Synthesizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReplayHarnessTest {
    private val p = GeoPoint(37.0, -122.0)
    private fun w(speed: Double, from: Double) = WindVector.fromSpeedDirection(speed, from)

    @Test
    fun windErrorStats_vectorRmse_speedBias_circularDirection() {
        val pairs = listOf(
            WindPair("A", 0, w(5.0, 355.0), w(5.0, 5.0), shift = false, nearestRemainingKm = 5.0),
            WindPair("A", 1, w(3.0, 90.0), w(2.0, 90.0), shift = false, nearestRemainingKm = 5.0),
            WindPair("A", 2, w(1.0, 0.0), w(1.0, 180.0), shift = false, nearestRemainingKm = 5.0), // calm: no direction
        )
        val s = WindErrorStats.of(pairs)
        assertEquals(3, s.n)
        assertEquals(2, s.nDirection)
        // 355 vs 5 is a 10 degree error across north, not 350.
        assertEquals((10.0 + 0.0) / 2, s.directionMae!!, 1e-9)
        assertEquals(1.0 / 3, s.speedBias!!, 1e-9)
        val e1 = (w(5.0, 355.0) - w(5.0, 5.0)).let { it.u * it.u + it.v * it.v }
        assertEquals(kotlin.math.sqrt((e1 + 1.0 + 4.0) / 3), s.vectorRmse!!, 1e-9)
    }

    @Test
    fun shiftFlags_needDirectionChangeOver45WithinAnHour_andIgnoreCalm() {
        val t = listOf(0L, 1_800_000L, 3_000_000L, 9_000_000L)
        val flags = MetricA.shiftFlags(t, listOf(w(5.0, 180.0), w(5.0, 200.0), w(5.0, 260.0), w(5.0, 200.0)))
        assertEquals(listOf(false, false, true, false), flags)
        assertEquals(listOf(false, false), MetricA.shiftFlags(listOf(0L, 60_000L), listOf(w(1.0, 0.0), w(1.0, 180.0))))
    }

    @Test
    fun upstreamRounding_matchesHeadwind_andInterpolatesSpeedAndAngleSeparately() {
        assertEquals(37.0 * 111 / 3, UpstreamPipeline.roundDegrees(37.0, 3.0) * 111 / 3, 0.5)
        val s = ForecastSeries("m", p, null, listOf(step(0, w(4.0, 350.0)), step(3_600_000, w(8.0, 10.0))))
        val mid = UpstreamPipeline.upstreamWindAt(s, 1_800_000)!!
        assertEquals(6.0, mid.speed, 1e-9) // speed lerp, not u/v
        assertEquals(0.0, io.github.kf6999.truewind.core.math.normalizeSignedDegrees(mid.directionFromDeg), 1e-6)
        assertNull(UpstreamPipeline.upstreamWindAt(s, 10 * 3_600_000L))
    }

    @Test
    fun archiveObservations_hideFutureAndHeldOutStations() {
        val obs = listOf(
            Observation("A", "t", p, timeMs = 1000, wind = w(3.0, 0.0), speed = 3.0),
            Observation("A", "t", p, timeMs = 5000, wind = w(4.0, 0.0), speed = 4.0),
            Observation("B", "t", p, timeMs = 1000, wind = w(5.0, 0.0), speed = 5.0),
        )
        val a = ObservationArchive(obs)
        assertEquals(listOf(3.0, 5.0), a.latest(2000, p, 10.0, 60_000).map { it.speed })
        assertEquals(listOf(4.0), a.latest(6000, p, 10.0, 60_000, exclude = setOf("B")).map { it.speed })
    }

    @Test
    fun virtualElevation_withTruthWindAndParameters_reproducesTerrain() {
        val dir = Files.createTempDirectory("syn").toFile()
        val syn = Synthesizer(seed = 7)
        syn.writeRide(dir.also { File(it, "rides").mkdirs() }, "t", syn.t0Ms, listOf(
            Synthesizer.Waypoint(0.0, 0.0), Synthesizer.Waypoint(4000.0, 0.0), Synthesizer.Waypoint(4000.0, 3000.0), Synthesizer.Waypoint(0.0, 3000.0),
        ), stopsEvery = 1000, minutes = 30, truth = { _, _ -> w(6.0, 250.0) })
        val ride = CorpusLoader.readRideCsv(File(dir, "rides/t.csv"))
        val truth = assertNotNull(ride.truth).let { ride.truth!! }
        val s = ride.samples
        val rho = DoubleArray(s.size) { VirtualElevation.airDensity(s[it].altitudeM, s[it].temperatureC) }
        val acc = VirtualElevation.accelerations(s)
        val course = VirtualElevation.trueCourse(s)
        var c = 0.0
        val hw = DoubleArray(s.size) { i -> c = course[i] ?: c; truth.riderWind[i].headwind(c) }
        val good = VirtualElevation.windowRmse(s, VirtualElevation.increments(s, hw, PowerModel(84.0, truth.cdA, truth.crr), rho, acc))!!
        val noWind = VirtualElevation.windowRmse(s, VirtualElevation.increments(s, DoubleArray(s.size), PowerModel(84.0, truth.cdA, truth.crr), rho, acc))!!
        assertTrue("VE with truth should be small, was $good", good < 2.0)
        assertTrue("ignoring the wind must be worse ($noWind vs $good)", noWind > 2 * good)
    }

    @Test
    fun fixtureCorpus_replaysAllConfigs_inUnderAMinute() {
        val root = listOf(File("../corpus/fixtures"), File("corpus/fixtures")).first { it.isDirectory }
        val started = System.nanoTime()
        val report = ReplayRunner(CorpusLoader.load(root)).run()
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("took $seconds s", seconds < 60)
        @Suppress("UNCHECKED_CAST")
        val configs = report["configs"] as Map<String, Map<String, Any?>>
        assertEquals(ReplayConfigs.all.map { it.name }, configs.keys.toList())
        val flat = ReportJson.flatten(report["configs"])
        for (c in ReplayConfigs.all.map { it.name }) {
            assertTrue("$c metric A", (flat["$c.metric_a.overall.n"] ?: 0.0) > 500)
            assertNotNull("$c metric B", flat["$c.metric_b.ve_rmse_m"])
            assertNotNull("$c metric C", flat["$c.metric_c.stopped_jitter_deg"])
        }
        // The layers the synthetic corpus is built to exercise must move in the right direction.
        assertTrue(flat["+obs.metric_a.overall.vector_rmse_ms"]!! < flat["upstream.metric_a.overall.vector_rmse_ms"]!!)
        assertTrue(flat["+height.metric_b.ve_rmse_m"]!! < flat["upstream.metric_b.ve_rmse_m"]!!)
        assertTrue(flat["+heading.metric_c.stopped_jitter_deg"]!! < flat["upstream.metric_c.stopped_jitter_deg"]!!)
        assertFalse(ReportJson.write(report).contains("NaN"))
    }
}
