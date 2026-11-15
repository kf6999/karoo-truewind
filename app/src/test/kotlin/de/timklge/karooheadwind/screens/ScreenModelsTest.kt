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

package de.timklge.karooheadwind.screens

import de.timklge.karooheadwind.HeadwindSettings
import de.timklge.karooheadwind.WindUnit
import de.timklge.karooheadwind.datatypes.RadarAnimation
import de.timklge.karooheadwind.pipeline.AlertDispatcher
import io.github.kf6999.truewind.core.api.ForecastStep
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.route.FinishAlertFormat
import io.github.kf6999.truewind.core.route.RoutePolyline
import io.github.kf6999.truewind.core.route.RouteWeatherPlanner
import io.github.kf6999.truewind.core.route.SpeedModel
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenModelsTest {
    private val units = RouteWeatherUnits(WindUnit.KILOMETERS_PER_HOUR, imperialTemp = false, imperialDistance = false)

    @Test
    fun routeScreenShowsEightRows() {
        val route = RoutePolyline(listOf(GeoPoint(47.0, 8.0), GeoPoint(47.0, 8.5))) // due east
        // Wind from the east at 5 m/s: a headwind for an eastbound rider.
        val planner = RouteWeatherPlanner(forecast = { _, t -> ForecastStep(t, WindVector.fromSpeedDirection(5.0, 90.0), 7.0, 15.0, null, 0.0) })
        val plan = planner.plan(route, 0L, SpeedModel.constant(20.0))
        val rows = RouteWeatherModel.rows(plan, units, ZoneOffset.UTC)
        assertEquals(8, rows.size)
        assertEquals("00:00", rows.first().eta)
        assertTrue(rows[3].wind.startsWith("head 18"), rows[3].wind)
        assertEquals("15C", rows[3].temperature)
        assertEquals("dry", rows[3].rain)
        assertNotNull(plan.rideScore)
        assertTrue(RouteWeatherModel.overall(plan).startsWith("Ride score"))
        assertEquals("No clearly better start in the next 12 h.", RouteWeatherModel.betterStartText(null))
        assertEquals(listOf("Now", "+1 h", "+12 h"), listOf(0, 1, 12).map(RouteWeatherModel::offsetLabel))
    }

    @Test
    fun routeScreenWithoutForecastShowsDashes() {
        val route = RoutePolyline(listOf(GeoPoint(47.0, 8.0), GeoPoint(47.0, 8.5)))
        val plan = RouteWeatherPlanner(forecast = { _, _ -> null }).plan(route, 0L, SpeedModel.constant(20.0))
        val rows = RouteWeatherModel.rows(plan, units, ZoneOffset.UTC)
        assertTrue(rows.all { it.wind == "-" && it.score == "-" })
        assertNull(plan.rideScore)
    }

    @Test
    fun finishUrlMustBeHttps() {
        assertNull(FinishAlertSettingsModel.urlError(""))
        assertNull(FinishAlertSettingsModel.urlError("https://ntfy.sh/my-ride"))
        assertNotNull(FinishAlertSettingsModel.urlError("http://ntfy.sh/my-ride"))
        assertNotNull(FinishAlertSettingsModel.urlError("ntfy.sh/topic"))
        assertNotNull(FinishAlertSettingsModel.urlError("https://a b"))
        assertTrue(!FinishAlertSettingsModel.canTest(""))
        assertTrue(!FinishAlertSettingsModel.canTest("http://x.example/t"))
        assertTrue(FinishAlertSettingsModel.canTest(" https://ntfy.sh/my-ride "))
    }

    @Test
    fun finishSettingsMapToConfig() {
        val s = HeadwindSettings(
            finishAlertEnabled = true, finishAlertUrl = " https://ntfy.sh/t ", finishAlertJson = true,
            finishAlertMinutes = 15, finishAlertMessage = "Almost home", finishAlertIncludeLocation = true,
        )
        val c = AlertDispatcher.finishConfig(s)
        assertTrue(c.enabled)
        assertEquals("https://ntfy.sh/t", c.url)
        assertEquals(FinishAlertFormat.JSON, c.format)
        assertEquals(15, c.thresholdMin)
        assertTrue(c.includeLocation)
        assertTrue(!AlertDispatcher.finishConfig(HeadwindSettings()).enabled)
        val body = String(FinishAlertSettingsModel.testRequest(s, 0L).body!!)
        assertTrue(body.contains("Almost home"))
        assertTrue(!body.contains("\"location\""), "test request never carries a location")
    }

    @Test
    fun radarAnimationCyclesThreeFramesThenHoldsFiveTicks() {
        var st = 2 to 0 // just refreshed: showing the newest
        val seen = ArrayList<Int>()
        repeat(10) { st = RadarAnimation.next(st.first, st.second, 3); seen += st.first }
        assertEquals(listOf(2, 2, 2, 2, 0, 1, 2, 2, 2, 2), seen)
        assertEquals(3, RadarAnimation.FRAMES)
    }
}
