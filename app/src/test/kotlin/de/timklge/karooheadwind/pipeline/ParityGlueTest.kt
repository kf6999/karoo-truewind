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

package de.timklge.karooheadwind.pipeline

import de.timklge.karooheadwind.HeadwindSettings
import de.timklge.karooheadwind.datatypes.ParityFields
import de.timklge.karooheadwind.datatypes.RadarZoomRegistry
import io.github.kf6999.truewind.core.alerts.AlertPriority
import io.github.kf6999.truewind.core.alerts.AlertRule
import io.github.kf6999.truewind.core.alerts.RideAlert
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.providers.radar.TilePlanner
import io.hammerhead.karooext.models.InRideAlert
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParityGlueTest {
    private fun alert(id: String, rule: AlertRule = AlertRule.GUST) = RideAlert(id, "t $id", "d", AlertPriority.HIGH, rule)

    @Test
    fun alertDispatchedOncePerIdUntilReset() {
        val sent = mutableListOf<InRideAlert>()
        val d = AlertDispatcher { sent += it }
        val s = HeadwindSettings()
        d.dispatch(listOf(alert("a"), alert("b")), s)
        d.dispatch(listOf(alert("a"), alert("b")), s)
        assertEquals(2, sent.size)
        assertTrue(sent.all { it.id.startsWith("truewind-") })
        d.reset()
        d.dispatch(listOf(alert("a")), s)
        assertEquals(3, sent.size)
    }

    @Test
    fun disabledRuleIsNotDispatched() {
        val sent = mutableListOf<InRideAlert>()
        val d = AlertDispatcher { sent += it }
        d.dispatch(listOf(alert("g", AlertRule.GUST)), HeadwindSettings(alertGust = false))
        assertTrue(sent.isEmpty())
        assertFalse(AlertDispatcher.enabled(AlertRule.AQI, HeadwindSettings(alertAqi = false)))
    }

    @Test
    fun airQualityFetchedOnlyWhenNeeded() {
        val s = HeadwindSettings(alertAqi = false)
        assertFalse(ParityNeeds.of(s, emptySet(), recording = true, us = true).airQuality)
        assertTrue(ParityNeeds.of(s, setOf(ParityFields.PM25), recording = false, us = true).airQuality)
        assertTrue(ParityNeeds.of(HeadwindSettings(alertAqi = true), emptySet(), recording = true, us = true).airQuality)
        assertFalse(ParityNeeds.of(HeadwindSettings(alertAqi = true), emptySet(), recording = false, us = true).airQuality)
        // NWS alerts never outside the US.
        assertFalse(ParityNeeds.of(HeadwindSettings(), setOf(ParityFields.STORM), recording = true, us = false).nwsAlerts)
        assertTrue(ParityNeeds.of(HeadwindSettings(), setOf(ParityFields.STORM), recording = true, us = null).nwsAlerts)
    }

    @Test
    fun sideFetchWaitsForEngineCycle() {
        val p = SideFetchPolicy(10 * 60_000L)
        assertEquals(SideFetchPolicy.Decision.NOT_NEEDED, p.decide(0, false, null))
        assertEquals(SideFetchPolicy.Decision.FETCH, p.decide(0, true, null))
        p.onResult(0, true)
        assertEquals(SideFetchPolicy.Decision.NOT_DUE, p.decide(5 * 60_000L, true, null))
        // Due, no engine cycle: wait, then fetch once overdue by maxWait.
        assertEquals(SideFetchPolicy.Decision.WAIT, p.decide(10 * 60_000L + 1, true, 1_000L))
        assertEquals(SideFetchPolicy.Decision.FETCH, p.decide(13 * 60_000L, true, 1_000L))
        // Engine cycle just ended, inside the early window: fetch with it.
        val cycleEnd = 8 * 60_000L
        assertEquals(SideFetchPolicy.Decision.FETCH, p.decide(cycleEnd + 5_000L, true, cycleEnd))
        p.onResult(0, false)
        assertEquals(SideFetchPolicy.Decision.NOT_DUE, p.decide(60_000L, true, null))
        assertEquals(SideFetchPolicy.Decision.FETCH, p.decide(2 * 60_000L, true, null))
    }

    @Test
    fun radarBudgetCapsTiles() {
        val plan = TilePlanner.plan(GeoPoint(37.77, -122.42), 7, 240, 240)
        val needed = plan.tiles.map { it.tile }
        assertEquals(needed.size, RadarBudget.select(plan, needed, null).size)
        assertEquals(needed.size, RadarBudget.select(plan, needed, 20_000L).size)
        val few = RadarBudget.select(plan, needed, 45_000L)
        assertTrue(few.size in 1..2, "tiles: ${few.size}")
        assertTrue(RadarBudget.select(plan, needed, 500_000L).size >= 1, "always at least the rider's tile")
    }

    @Test
    fun zoomCycles() {
        assertEquals(8, RadarZoomRegistry.nextZoom(7))
        assertEquals(6, RadarZoomRegistry.nextZoom(8))
        assertEquals(7, RadarZoomRegistry.nextZoom(6))
    }
}
