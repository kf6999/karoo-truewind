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

package io.github.kf6999.truewind.core.route

import io.github.kf6999.truewind.core.api.ForecastStep
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteWeatherTest {
    private val t0 = 1_800_000_000_000L
    private val kmPerDegLon40 = 111.32 * Math.cos(Math.toRadians(40.0))

    /** Out-and-back fixture route: 20 km east, then 20 km back west, as a dense polyline. */
    private val outAndBack = RoutePolyline(
        (0..20).map { GeoPoint(40.0, -105.0 + it / kmPerDegLon40) } + (19 downTo 0).map { GeoPoint(40.0, -105.0 + it / kmPerDegLon40) },
    )

    @Test
    fun geometry() {
        assertEquals(40.0, outAndBack.lengthKm, 0.05)
        assertEquals(90.0, outAndBack.bearingAt(5.0)!!, 0.5)
        assertEquals(270.0, outAndBack.bearingAt(30.0)!!, 0.5)
        val mid = outAndBack.pointAt(10.0)
        assertEquals(-105.0 + 10 / kmPerDegLon40, mid.lon, 5e-4)
        assertEquals(outAndBack.points.last(), outAndBack.pointAt(99.0))
        assertEquals(12.3, RoutePolyline(outAndBack.points.take(21)).project(GeoPoint(40.01, -105.0 + 12.3 / kmPerDegLon40)), 0.05)
        assertEquals(0.0, GeoMath.bearingDeg(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)), 1e-9)
        assertNull(RoutePolyline(listOf(GeoPoint(1.0, 1.0))).bearingAt(0.0))
        assertEquals(3_600_000L, travelTimeMs(0.0, 25.0, SpeedModel.constant(25.0)))
    }

    @Test
    fun eightSamplesWithEtaAndRelativeWind() {
        val westWind = { _: GeoPoint, t: Long -> ForecastStep(t, WindVector.fromSpeedDirection(5.0, 270.0), gust10m = 8.0, temperatureC = 18.0, precipitationMm = 0.0) }
        val plan = RouteWeatherPlanner(westWind).plan(outAndBack, t0, SpeedModel.constant(20.0))
        assertEquals(8, plan.samples.size)
        assertEquals(2 * 3_600_000.0, (plan.finishMs - t0).toDouble(), 30_000.0)
        val s = plan.samples
        assertEquals(0.0, s.first().distanceKm)
        assertEquals(40.0, s.last().distanceKm, 0.05)
        assertEquals((outAndBack.lengthKm / 7 * 3 / 20 * 3_600_000), (s[3].etaMs - t0).toDouble(), 1000.0)
        // Out leg (east) with a westerly: tailwind -5; back leg (west): headwind +5.
        assertEquals(-5.0, s[1].headwindMs!!, 0.05)
        assertEquals(5.0, s[6].headwindMs!!, 0.05)
        assertEquals(0.0, s[1].crosswindMs!!, 0.05)
        assertEquals(100, s[1].score)
        assertEquals(70, s[6].score)
        assertEquals(0.0, plan.meanHeadwindMs!!, 1.5)
    }

    @Test
    fun rideScoreFormula() {
        assertEquals(70, RideScore.score(5.0, 0.0, null, 18.0, 0.0))
        assertEquals(100, RideScore.score(-5.0, 0.0, null, 18.0, 0.0))
        assertEquals(65, RideScore.score(0.0, 0.0, null, 18.0, 2.0))
        assertEquals(80, RideScore.score(0.0, 0.0, null, 2.0, 0.0))
        assertEquals(85, RideScore.score(0.0, 0.0, 18.0, 20.0, 0.0))
        assertEquals(0, RideScore.score(15.0, 10.0, 25.0, -5.0, 10.0))
        assertNull(RideScore.score(null, null, 20.0, null, null))
    }

    @Test
    fun betterStartFindsTheDryWindow() {
        // Rain until t0+3h, dry after; wind calm.
        val f = { _: GeoPoint, t: Long -> ForecastStep(t, WindVector.ZERO, temperatureC = 18.0, precipitationMm = if (t < t0 + 3 * 3_600_000L) 0.5 else 0.0) }
        val planner = RouteWeatherPlanner(f)
        val best = assertNotNull(planner.betterStart(outAndBack, t0, SpeedModel.constant(20.0)))
        assertEquals(t0 + 3 * 3_600_000L, best.startMs)
        assertEquals(100, best.score)
        assertEquals(65, best.nowScore)
        assertEquals(35, best.improvement)
        assertEquals(13, planner.scan(outAndBack, t0, SpeedModel.constant(20.0)).size)
        // Already the best: no hint.
        val dry = RouteWeatherPlanner({ _, t -> ForecastStep(t, WindVector.ZERO, temperatureC = 18.0, precipitationMm = 0.0) })
        assertNull(dry.betterStart(outAndBack, t0, SpeedModel.constant(20.0)))
        // No data at all.
        assertNull(RouteWeatherPlanner({ _, _ -> null }).betterStart(outAndBack, t0, SpeedModel.constant(20.0)))
    }
}
