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

package io.github.kf6999.truewind.core.alerts

import io.github.kf6999.truewind.core.api.ForecastStep
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.route.RoutePolyline
import io.github.kf6999.truewind.core.route.SpeedModel
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RainBarsAndSummaryTest {
    private val t0 = 1_800_000_000_000L - (1_800_000_000_000L % 3_600_000L) // on the hour
    private val min = 60_000L

    @BeforeTest
    fun utc() { AlertText.zone = ZoneOffset.UTC }

    @Test
    fun fifteenMinuteBars() {
        val steps = (-1 until 12).map { PrecipStep(t0 + it * 15 * min, 15, if (it in 3..4) 0.8 else 0.0) }
        val m = RainBars.build(t0 + 5 * min, steps, horizonMin = 120)
        assertEquals(RainResolution.MINUTE_15, m.bars.first().resolution)
        assertEquals(-5, m.bars.first().startOffsetMin, "current step started 5 min ago")
        assertEquals(9, m.bars.size, "steps overlapping [now, now+120)")
        assertEquals(40, m.firstRainInMin)
        assertEquals(3.2, m.maxMmPerHour, 1e-9)
        assertEquals(RainIntensity.MODERATE, m.bars.first { it.mmPerHour > 0 }.intensity)
        assertFalse(m.hasMinuteData)
        assertEquals("15 min", m.resolutionLabel)
    }

    @Test
    fun minuteDataPreferredWhereAvailable() {
        val s15 = (0 until 8).map { PrecipStep(t0 + it * 15 * min, 15, 0.2) }
        val s1 = (0 until 60).map { PrecipStep(t0 + it * min, 1, if (it >= 20) 0.01 else 0.0) }
        val m = RainBars.build(t0, s15, s1, horizonMin = 120)
        assertTrue(m.hasMinuteData)
        assertEquals(60 + 4, m.bars.size, "60 one-minute bars then the 15-min steps after the nowcast")
        assertEquals(RainResolution.MINUTE_1, m.bars[0].resolution)
        assertEquals(RainResolution.MINUTE_15, m.bars.last().resolution)
        assertEquals(60, m.bars[60].startOffsetMin)
        assertEquals(20, m.firstRainInMin)
        assertEquals("15 min", m.resolutionLabel)
        assertEquals("1 min", RainBars.build(t0, emptyList(), s1, horizonMin = 60).resolutionLabel)
        assertNull(RainBars.build(t0, emptyList()).firstRainInMin)
    }

    private fun hourly(dir: Double, speeds: List<Double>, temps: List<Double>, rain: List<Double>) =
        speeds.indices.map { i -> ForecastStep(t0 + i * 3_600_000L, WindVector.fromSpeedDirection(speeds[i], dir), gust10m = speeds[i] + 2, temperatureC = temps[i], precipitationMm = rain[i]) }

    @Test
    fun summaryWithoutRoute() {
        val s = RideSummaryBuilder.build(
            t0,
            hourly(270.0, listOf(3.0, 4.0, 5.0, 6.0, 7.0, 7.0, 7.0), listOf(8.0, 10.0, 12.0, 14.0, 13.0, 11.0, 9.0), listOf(0.0, 0.0, 0.0, 0.0, 0.5, 1.0, 0.0)),
            precipProbabilityPct = mapOf(t0 + 4 * 3_600_000L to 60.0),
        )
        assertEquals(WindTrend.RISING, s.trend)
        assertEquals(270.0, s.windFromDeg!!, 1e-6)
        assertNull(s.routeWind)
        assertEquals(8.0, s.tempMinC)
        assertEquals(14.0, s.tempMaxC)
        assertEquals(t0 + 4 * 3_600_000L, s.rainFromMs)
        assertEquals("Next 6 h", s.title)
        assertEquals("Wind W 11→25 km/h, rising. Temp 8–14°C. Rain 60% from 04:00 (1.5 mm).", s.text.replace(Regex("from \\d\\d:00"), "from 04:00"))
        val alert = RideSummaryBuilder.toAlert(s, t0)
        assertEquals(AlertRule.RIDE_SUMMARY, alert.rule)
    }

    @Test
    fun summaryRelativeToRoute() {
        // Straight route due west, 60 km, 30 km/h: 2 h of riding, then the finish is held.
        val route = RoutePolyline(listOf(GeoPoint(40.0, -105.0), GeoPoint(40.0, -105.0 - 60 / (111.32 * Math.cos(Math.toRadians(40.0))))))
        val westerly = { _: GeoPoint, t: Long -> ForecastStep(t, WindVector.fromSpeedDirection(5.0, 270.0), temperatureC = 15.0, precipitationMm = 0.0) }
        val s = RideSummaryBuilder.build(t0, emptyList(), route = route, speed = SpeedModel.constant(30.0), forecast = westerly, units = AlertUnits(metricSpeed = false, metricTemperature = false))
        assertEquals(RouteWindCharacter.MOSTLY_HEADWIND, s.routeWind)
        assertEquals(5.0, s.meanHeadwindMs!!, 0.05)
        assertEquals("Next 6 h on route", s.title)
        assertEquals("Wind W 11 mph, steady; mostly headwind on route. Temp 59–59°F. No rain expected.", s.text)

        val easterly = { _: GeoPoint, t: Long -> ForecastStep(t, WindVector.fromSpeedDirection(5.0, 90.0)) }
        assertEquals(RouteWindCharacter.MOSTLY_TAILWIND, RideSummaryBuilder.build(t0, emptyList(), route = route, speed = SpeedModel.constant(30.0), forecast = easterly).routeWind)
        val northerly = { _: GeoPoint, t: Long -> ForecastStep(t, WindVector.fromSpeedDirection(5.0, 0.0)) }
        assertEquals(RouteWindCharacter.MOSTLY_CROSSWIND, RideSummaryBuilder.build(t0, emptyList(), route = route, speed = SpeedModel.constant(30.0), forecast = northerly).routeWind)
    }
}
