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

package io.github.kf6999.truewind.core.schedule

import io.github.kf6999.truewind.core.fusion.FusionFixtures.HOME
import io.github.kf6999.truewind.core.fusion.FusionFixtures.offset
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FetchPlannerTest {
    private val MIN = 60_000L
    private val T = 1_000_000_000_000L

    private val om = ProviderSpec("open-meteo", RequestKind.FORECAST)
    private val awc = ProviderSpec("awc", RequestKind.OBSERVATION)
    private val synoptic = ProviderSpec("synoptic", RequestKind.OBSERVATION, optional = true, minIntervalMs = 10 * MIN)
    private val tomorrow = ProviderSpec("tomorrow", RequestKind.FORECAST, optional = true, minIntervalMs = 15 * MIN)
    private val hourly = ProviderSpec("hourly", RequestKind.HOURLY)
    private val all = listOf(om, awc, synoptic, tomorrow, hourly)

    private class Step(val plan: FetchPlan, val minute: Long)

    /**
     * Drives [planner] once per simulated minute from 0 to [minutes] and returns the minutes at
     * which a cycle ran. [position], [route], [active] and [outcome] are functions of the minute.
     */
    private fun simulate(
        planner: FetchPlanner,
        minutes: Long,
        position: (Long) -> GeoPoint = { HOME },
        route: (Long) -> Long? = { null },
        active: (Long) -> Boolean = { true },
        outcome: (Long, FetchPlan) -> CycleOutcomeSpec = { _, _ -> CycleOutcomeSpec() },
        startMinute: Long = 0,
    ): List<Step> {
        val ran = ArrayList<Step>()
        for (m in startMinute..minutes) {
            val now = T + m * MIN
            val plan = planner.plan(PlannerInput(now, position(m), recording = active(m), visible = false, routeHash = route(m)))
            if (!plan.due) continue
            ran += Step(plan, m)
            val o = outcome(m, plan)
            planner.onCycleFinished(
                CycleOutcome(
                    now, position(m), route(m), o.success,
                    plan.requests.map { r ->
                        val status = o.status[r.providerId]
                        ProviderOutcome(
                            r.providerId, success = o.success && status == null, requests = 1, bytes = 5_000,
                            httpStatus = status, retryAfterSec = o.retryAfter[r.providerId],
                        )
                    },
                    o.residual, o.spread,
                )
            )
        }
        return ran
    }

    private data class CycleOutcomeSpec(
        val success: Boolean = true,
        val status: Map<String, Int> = emptyMap(),
        val retryAfter: Map<String, Long> = emptyMap(),
        val residual: WindVector? = null,
        val spread: Double? = null,
    )

    private fun List<Step>.minutes() = map { it.minute }

    @Test
    fun steadyRide_fetchesEveryInterval() {
        val runs = simulate(FetchPlanner(listOf(om, awc)), 60)
        assertEquals(listOf(0L, 10, 20, 30, 40, 50, 60), runs.minutes())
        assertEquals(setOf(FetchReason.NO_CACHE), runs.first().plan.reasons)
        assertEquals(setOf(FetchReason.INTERVAL), runs[1].plan.reasons)
    }

    @Test
    fun refreshOptions() {
        assertEquals(listOf(0L, 5, 10, 15), simulate(FetchPlanner(listOf(om), FetchPlannerConfig.forRefreshMinutes(5)), 15).minutes())
        assertEquals(listOf(0L, 30, 60), simulate(FetchPlanner(listOf(om), FetchPlannerConfig.forRefreshMinutes(30)), 60).minutes())
        assertEquals(15 * MIN, FetchPlannerConfig.forRefreshMinutes(14).refreshIntervalMs)
    }

    @Test
    fun nothingWhenNeitherRecordingNorVisible() {
        val p = FetchPlanner(listOf(om))
        assertTrue(simulate(p, 60, active = { false }).isEmpty())
        assertEquals(BlockReason.INACTIVE, p.plan(PlannerInput(T, HOME, recording = false, visible = false)).blockedBy)
        assertTrue(p.plan(PlannerInput(T, HOME, recording = false, visible = true)).due)
        assertEquals(BlockReason.NO_POSITION, p.plan(PlannerInput(T, null, recording = true, visible = true)).blockedBy)
        // Pausing mid-ride: fetches stop and resume on the interval.
        val q = FetchPlanner(listOf(om))
        assertEquals(listOf(0L, 10, 31, 41), simulate(q, 45, active = { it !in 11..30 }).minutes())
    }

    @Test
    fun moveOver4km_triggersImmediately() {
        // 0.5 km per minute east: 4 km is exceeded at minute 9 (4.5 km).
        val runs = simulate(FetchPlanner(listOf(om)), 25, position = { m -> offset(HOME, 0.5 * m) })
        assertEquals(listOf(0L, 9, 18), runs.minutes())
        assertTrue(FetchReason.MOVED in runs[1].plan.reasons)
    }

    @Test
    fun routeChange_triggersImmediately() {
        val runs = simulate(FetchPlanner(listOf(om)), 20, route = { m -> if (m < 4) 1L else 2L })
        assertEquals(listOf(0L, 4, 14), runs.minutes())
        assertEquals(setOf(FetchReason.ROUTE_CHANGED), runs[1].plan.reasons)
    }

    @Test
    fun optionalProviders_capAtOwnCadence_andHourlyAtMostHourly() {
        val runs = simulate(FetchPlanner(all), 70)
        assertEquals(listOf(0L, 10, 20, 30, 40, 50, 60, 70), runs.minutes())
        assertEquals(listOf(0L, 20, 40, 60), runs.filter { it.plan.includes("tomorrow") }.minutes())
        assertEquals(runs.minutes(), runs.filter { it.plan.includes("synoptic") }.minutes())
        assertEquals(listOf(0L, 60), runs.filter { it.plan.includes("hourly") }.minutes())
        assertTrue(runs.all { it.plan.includes("open-meteo") && it.plan.includes("awc") })
    }

    @Test
    fun failureBackoff_1_2_4_8_cappedAt10() {
        val runs = simulate(FetchPlanner(listOf(om)), 60, outcome = { _, _ -> CycleOutcomeSpec(success = false) })
        assertEquals(listOf(0L, 1, 3, 7, 15, 25, 35, 45, 55), runs.minutes())
    }

    @Test
    fun backoffResetsOnSuccess() {
        val runs = simulate(FetchPlanner(listOf(om)), 40, outcome = { m, _ -> CycleOutcomeSpec(success = m >= 7) })
        assertEquals(listOf(0L, 1, 3, 7, 17, 27, 37), runs.minutes())
    }

    @Test
    fun connectivityChange_resetsBackoff() {
        val p = FetchPlanner(listOf(om))
        simulate(p, 3, outcome = { _, _ -> CycleOutcomeSpec(success = false) }) // fails at 0, 1, 3; next at 7
        assertFalse(p.plan(PlannerInput(T + 4 * MIN, HOME, true, false)).due)
        assertEquals(BlockReason.BACKOFF, p.plan(PlannerInput(T + 4 * MIN, HOME, true, false)).blockedBy)
        p.onConnectivityChanged()
        assertTrue(p.plan(PlannerInput(T + 4 * MIN, HOME, true, false)).due)
    }

    @Test
    fun retryAfter_onCoreForecast_isHonoured_andOptionalsAreShed() {
        val p = FetchPlanner(all)
        val runs = simulate(p, 70, outcome = { m, _ ->
            if (m == 10L) CycleOutcomeSpec(success = false, status = mapOf("open-meteo" to 429), retryAfter = mapOf("open-meteo" to 420))
            else CycleOutcomeSpec()
        })
        // Backoff would allow minute 11; Retry-After 7 min pushes the next attempt to 17.
        assertEquals(listOf(0L, 10, 17, 27, 37, 47, 57, 67), runs.minutes())
        // Optional providers are shed for 30 min after the 429.
        val optionalMinutes = runs.filter { it.plan.includes("tomorrow") || it.plan.includes("synoptic") }.minutes()
        assertEquals(listOf(0L, 10, 47, 57, 67), optionalMinutes)
    }

    @Test
    fun retryAfterWithoutBackoffNeeded_blocksWhileCoreForecastHeld() {
        val p = FetchPlanner(listOf(om, awc))
        // The forecast 429s but the cycle still counts as a success (e.g. served from another core source):
        simulate(p, 0, outcome = { _, _ -> CycleOutcomeSpec(success = true, status = mapOf("open-meteo" to 429), retryAfter = mapOf("open-meteo" to 900)) })
        val plan = p.plan(PlannerInput(T + 10 * MIN, HOME, true, false))
        assertFalse(plan.due)
        assertEquals(BlockReason.RETRY_AFTER, plan.blockedBy)
        assertEquals(T + 15 * MIN, plan.nextCheckAtMs)
        assertTrue(p.plan(PlannerInput(T + 15 * MIN, HOME, true, false)).due)
    }

    @Test
    fun optional429_dropsOnlyThatTier() {
        val runs = simulate(FetchPlanner(all), 60, outcome = { m, _ ->
            if (m == 0L) CycleOutcomeSpec(status = mapOf("tomorrow" to 429), retryAfter = mapOf("tomorrow" to 3600)) else CycleOutcomeSpec()
        })
        assertEquals(listOf(0L, 10, 20, 30, 40, 50, 60), runs.minutes())
        // Held for its Retry-After (60 min), not just the 30-min shed.
        assertEquals(listOf(0L, 60), runs.filter { it.plan.includes("tomorrow") }.minutes())
        assertEquals(listOf(0L, 30, 40, 50, 60), runs.filter { it.plan.includes("synoptic") }.minutes())
    }

    @Test
    fun adaptiveSpeedUp_after_residualChange() {
        val runs = simulate(FetchPlanner(listOf(om, awc)), 80, outcome = { m, _ ->
            // Residual flips from (1, 0) to (−2, 0) at minute 20: change 3 m/s > 2.
            CycleOutcomeSpec(residual = if (m < 20) WindVector(1.0, 0.0) else WindVector(-2.0, 0.0), spread = 2.0)
        })
        // Speed-up from minute 20 until 50: 5-min interval, then back to 10.
        assertEquals(listOf(0L, 10, 20, 25, 30, 35, 40, 45, 55, 65, 75), runs.minutes())
    }

    @Test
    fun adaptiveSpeedUp_on40DegreeTurnOfResidual() {
        val runs = simulate(FetchPlanner(listOf(om)), 30, outcome = { m, _ ->
            CycleOutcomeSpec(residual = WindVector.fromSpeedDirection(1.5, if (m < 10) 0.0 else 50.0), spread = 2.0)
        })
        assertEquals(listOf(0L, 10, 15, 20, 25, 30), runs.minutes())
    }

    @Test
    fun adaptiveBackOff_afterThreeCalmCycles_cappedAt20() {
        val p = FetchPlanner(listOf(om))
        val runs = simulate(p, 130, outcome = { _, _ -> CycleOutcomeSpec(residual = WindVector(0.4, 0.0), spread = 0.5) })
        // 3 calm cycles (0, 10, 20) → 15 min; 6 calm cycles → 22.5 capped at 20.
        assertEquals(listOf(0L, 10, 20, 35, 50, 65, 85, 105, 125), runs.minutes())
        assertEquals(20 * MIN, p.intervalMs(T + 130 * MIN))
    }

    @Test
    fun budget_isAccountedPerCycleAndHour() {
        val p = FetchPlanner(all)
        simulate(p, 70)
        val b = p.budgetSnapshot(T + 70 * MIN)
        assertEquals(8, b.cycles)
        assertEquals(3, b.lastCycleRequests) // om, awc, synoptic at minute 70
        assertEquals(15_000, b.lastCycleBytes)
        // Last hour (minutes 11..70): cycles at 20..70 → 6 cycles.
        val expectedHour = listOf(20L, 30, 40, 50, 60, 70).sumOf { m ->
            3 + (if (m % 20 == 0L) 1 else 0) + (if (m == 60L) 1 else 0)
        }
        assertEquals(expectedHour, b.lastHourRequests)
        assertEquals(expectedHour * 5_000L, b.lastHourBytes)
        assertEquals(8L, b.requestsByProvider["open-meteo"])
    }

    @Test
    fun requestCap_shedsOptionalFirst() {
        val p = FetchPlanner(all, FetchPlannerConfig(maxRequestsPerCycle = 3))
        val plan = p.plan(PlannerInput(T, HOME, true, false))
        assertEquals(listOf("open-meteo", "awc", "hourly"), plan.requests.map { it.providerId })
    }

    @Test
    fun hourlyByteBudget_shedsOptionalAndHourly() {
        val p = FetchPlanner(all, FetchPlannerConfig(maxBytesPerHour = 20_000))
        simulate(p, 0) // 5 requests × 5 KB = 25 KB
        val plan = p.plan(PlannerInput(T + 10 * MIN, HOME, true, false))
        assertEquals(listOf("open-meteo", "awc"), plan.requests.map { it.providerId })
    }

    @Test
    fun upstreamEmulation_hourlyGridCellAndFixedRetry() {
        val cfg = FetchPlannerConfig(upstreamEmulation = true)
        val p = FetchPlanner(listOf(om, ProviderSpec("om2", RequestKind.FORECAST), awc, tomorrow), cfg)
        val runs = simulate(p, 130)
        assertEquals(listOf(0L, 60, 120), runs.minutes())
        assertEquals(listOf("open-meteo"), runs.first().plan.requests.map { it.providerId })

        // 3 km cells: 0.1 km/min east crosses a cell boundary somewhere within 30 minutes.
        val q = FetchPlanner(listOf(om), cfg)
        val moving = simulate(q, 59, position = { m -> offset(HOME, 0.1 * m) })
        assertTrue(moving.size >= 2 && FetchReason.MOVED in moving[1].plan.reasons, moving.minutes().toString())

        val r = FetchPlanner(listOf(om), cfg)
        assertEquals(listOf(0L, 2, 4, 6), simulate(r, 7, outcome = { _, _ -> CycleOutcomeSpec(success = false) }).minutes())
    }
}
