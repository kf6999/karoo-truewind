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

import io.github.kf6999.truewind.core.math.KM_PER_DEGREE
import io.github.kf6999.truewind.core.math.haversineKm
import io.github.kf6999.truewind.core.math.normalizeSignedDegrees
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class RequestKind { FORECAST, OBSERVATION, HOURLY }

/** A provider the planner schedules. */
data class ProviderSpec(
    val id: String,
    val kind: RequestKind,
    /** Optional (keyed) providers are dropped first on 429 or budget pressure. */
    val optional: Boolean = false,
    /** Own cadence: never attempted more often than this (Tomorrow.io 15 min, Synoptic 10 min). */
    val minIntervalMs: Long = 0L,
)

data class FetchPlannerConfig(
    /** User setting, SPEC 4.1: one of [REFRESH_OPTIONS_MIN] minutes. */
    val refreshIntervalMs: Long = 10 * 60_000L,
    val moveTriggerKm: Double = 4.0,
    /** Adaptive speed-up: residual change thresholds and the resulting interval and duration. */
    val speedUpResidualDeltaMs: Double = 2.0,
    val speedUpResidualAngleDeg: Double = 40.0,
    /** The angle test only applies when both residuals are at least this long, m/s. */
    val speedUpAngleMinSpeed: Double = 1.0,
    val speedUpIntervalMs: Long = 5 * 60_000L,
    val speedUpDurationMs: Long = 30 * 60_000L,
    /** Adaptive back-off: model spread and residual under these for [backOffCycles] cycles → interval × [backOffFactor] (compounding per further [backOffCycles]), capped. */
    val backOffSpreadMax: Double = 1.0,
    val backOffResidualMax: Double = 1.0,
    val backOffCycles: Int = 3,
    val backOffFactor: Double = 1.5,
    val backOffCapMs: Long = 20 * 60_000L,
    /** Failure backoff 1, 2, 4, 8 min, capped at 10 min. */
    val failureBackoffMs: List<Long> = listOf(1, 2, 4, 8).map { it * 60_000L },
    val failureBackoffCapMs: Long = 10 * 60_000L,
    /** On any 429, optional providers are shed for this long (longer if their Retry-After says so). */
    val optionalShedMs: Long = 30 * 60_000L,
    /** Hourly 12 h forecast for the widgets, at most this often. */
    val hourlyIntervalMs: Long = 60 * 60_000L,
    /** TESTING 5.1 budgets: optional providers and the hourly request are trimmed to stay inside. */
    val maxRequestsPerCycle: Int = 8,
    val maxBytesPerHour: Long = 400_000L,
    /**
     * Upstream Headwind emulation (replay baseline): hourly refresh, refetch when the 3 km grid
     * cell changes, fixed 2 min retry, no observations, optional providers or adaptivity.
     */
    val upstreamEmulation: Boolean = false,
    val upstreamIntervalMs: Long = 60 * 60_000L,
    val upstreamGridKm: Double = 3.0,
    val upstreamRetryMs: Long = 2 * 60_000L,
) {
    companion object {
        val REFRESH_OPTIONS_MIN = listOf(5, 10, 15, 30)

        /** Config for a refresh setting in minutes; snaps to the nearest allowed option. */
        fun forRefreshMinutes(minutes: Int): FetchPlannerConfig {
            val m = REFRESH_OPTIONS_MIN.minByOrNull { abs(it - minutes) }!!
            return FetchPlannerConfig(refreshIntervalMs = m * 60_000L)
        }
    }
}

/** What the planner needs to know now. */
data class PlannerInput(
    val nowMs: Long,
    /** Rider position (after privacy rounding); null without GPS. */
    val rider: GeoPoint?,
    val recording: Boolean,
    /** A TrueWind field or screen is visible. */
    val visible: Boolean,
    /** Hash of the loaded route; null when none. */
    val routeHash: Long? = null,
    /** User-requested refresh: ignores interval and failure backoff, still honours Retry-After holds. */
    val force: Boolean = false,
)

enum class FetchReason { NO_CACHE, INTERVAL, MOVED, ROUTE_CHANGED, FORCED }

enum class BlockReason {
    /** Neither recording nor visible. */
    INACTIVE,
    NO_POSITION,
    /** Nothing triggers a cycle yet. */
    NOT_DUE,
    /** Exponential failure backoff. */
    BACKOFF,
    /** Every core forecast provider is held by a 429 Retry-After. */
    RETRY_AFTER,
}

data class PlannedRequest(val providerId: String, val kind: RequestKind, val optional: Boolean)

data class FetchPlan(
    val nowMs: Long,
    val due: Boolean,
    val reasons: Set<FetchReason> = emptySet(),
    val requests: List<PlannedRequest> = emptyList(),
    val blockedBy: BlockReason? = null,
    /** Earliest time a time-based trigger can fire (moves and route changes can fire any time). */
    val nextCheckAtMs: Long? = null,
    /** Refresh interval in force. */
    val intervalMs: Long,
) {
    fun includes(providerId: String): Boolean = requests.any { it.providerId == providerId }
}

/** Result of one provider call in a cycle. */
data class ProviderOutcome(
    val providerId: String,
    val success: Boolean,
    val requests: Int = 1,
    val bytes: Long = 0,
    val httpStatus: Int? = null,
    val retryAfterSec: Long? = null,
    val error: String? = null,
)

/** Result of a cycle, fed back with [FetchPlanner.onCycleFinished]. */
data class CycleOutcome(
    /** Cycle start time (the plan's nowMs). */
    val nowMs: Long,
    val rider: GeoPoint?,
    val routeHash: Long?,
    /** A usable forecast at the rider was obtained. */
    val success: Boolean,
    val providers: List<ProviderOutcome>,
    /** Fusion diagnostics: residual correction at the rider (null without observations). */
    val residual: WindVector? = null,
    /** Weighted inter-model spread at the rider, m/s. */
    val spread: Double? = null,
)

/** Planner state for diagnostics. */
data class PlannerState(
    val lastSuccessMs: Long?,
    val lastFetchPoint: GeoPoint?,
    val consecutiveFailures: Int,
    val nextRetryAtMs: Long,
    val speedUpUntilMs: Long,
    val calmCycles: Int,
    val intervalMs: Long,
    val heldProviders: Map<String, Long>,
)

/**
 * Fetch policy, SPEC 4.1, as a pure, clock-injected state machine. [plan] never mutates state;
 * [onCycleFinished] records the outcome. Not thread-safe: call both from the fetch coroutine.
 */
class FetchPlanner(
    providers: List<ProviderSpec>,
    config: FetchPlannerConfig = FetchPlannerConfig(),
) {
    var config: FetchPlannerConfig = config
        private set

    private val specs = providers.toList()

    private class PState {
        var lastAttemptMs: Long? = null
        var lastSuccessMs: Long? = null
        var holdUntilMs: Long = Long.MIN_VALUE
    }

    private val pstate = specs.associate { it.id to PState() }

    private var lastSuccessMs: Long? = null
    private var lastFetchPoint: GeoPoint? = null
    private var lastRouteHash: Long? = null
    private var consecutiveFailures = 0
    private var nextRetryAtMs = Long.MIN_VALUE
    private var speedUpUntilMs = Long.MIN_VALUE
    private var calmCycles = 0
    private var prevResidual: WindVector? = null
    private var lastHourlySuccessMs: Long? = null

    val budget = FetchBudget()

    fun updateConfig(newConfig: FetchPlannerConfig) {
        config = newConfig
    }

    /** Phone link / Wi-Fi came back: forget the failure backoff (SPEC 4.1). */
    fun onConnectivityChanged() {
        consecutiveFailures = 0
        nextRetryAtMs = Long.MIN_VALUE
    }

    /** Refresh interval in force at [nowMs], including adaptive speed-up / back-off. */
    fun intervalMs(nowMs: Long): Long {
        val c = config
        if (c.upstreamEmulation) return c.upstreamIntervalMs
        val base = c.refreshIntervalMs
        if (nowMs < speedUpUntilMs) return min(base, c.speedUpIntervalMs)
        if (calmCycles >= c.backOffCycles) {
            val steps = calmCycles / c.backOffCycles
            val stretched = (base * c.backOffFactor.pow(steps)).toLong()
            return min(stretched, max(base, c.backOffCapMs))
        }
        return base
    }

    fun plan(input: PlannerInput): FetchPlan {
        val now = input.nowMs
        val c = config
        val interval = intervalMs(now)
        if (!input.recording && !input.visible) return FetchPlan(now, false, blockedBy = BlockReason.INACTIVE, intervalMs = interval)
        val rider = input.rider ?: return FetchPlan(now, false, blockedBy = BlockReason.NO_POSITION, intervalMs = interval)

        val reasons = LinkedHashSet<FetchReason>()
        val last = lastSuccessMs
        if (last == null) reasons += FetchReason.NO_CACHE else if (now - last >= interval) reasons += FetchReason.INTERVAL
        val lp = lastFetchPoint
        if (last != null && lp != null && moved(lp, rider)) reasons += FetchReason.MOVED
        if (last != null && input.routeHash != lastRouteHash) reasons += FetchReason.ROUTE_CHANGED
        if (input.force) reasons += FetchReason.FORCED

        if (reasons.isEmpty()) {
            return FetchPlan(now, false, blockedBy = BlockReason.NOT_DUE, nextCheckAtMs = last!! + interval, intervalMs = interval)
        }
        if (!input.force && now < nextRetryAtMs) {
            return FetchPlan(now, false, reasons, blockedBy = BlockReason.BACKOFF, nextCheckAtMs = nextRetryAtMs, intervalMs = interval)
        }

        val shedOptional = budget.lastHourBytes(now) >= c.maxBytesPerHour
        val requests = ArrayList<PlannedRequest>()
        for (spec in specs) {
            val st = pstate.getValue(spec.id)
            if (now < st.holdUntilMs) continue
            if (c.upstreamEmulation && (spec.optional || spec.kind == RequestKind.OBSERVATION)) continue
            if (spec.optional) {
                if (shedOptional) continue
                val la = st.lastAttemptMs
                if (la != null && now - la < spec.minIntervalMs) continue
            }
            if (spec.kind == RequestKind.HOURLY) {
                if (shedOptional) continue
                val lh = lastHourlySuccessMs
                if (lh != null && now - lh < c.hourlyIntervalMs) continue
            }
            requests += PlannedRequest(spec.id, spec.kind, spec.optional)
        }
        if (c.upstreamEmulation) {
            // Upstream used exactly one forecast source.
            val first = requests.firstOrNull { it.kind == RequestKind.FORECAST }
            requests.removeAll { it.kind == RequestKind.FORECAST && it !== first }
        }
        // Request cap: shed optional, then hourly, then core observations.
        while (requests.size > c.maxRequestsPerCycle) {
            val victim = requests.lastOrNull { it.optional }
                ?: requests.lastOrNull { it.kind == RequestKind.HOURLY }
                ?: requests.lastOrNull { it.kind == RequestKind.OBSERVATION }
                ?: break
            requests.remove(victim)
        }

        val coreForecasts = specs.filter { it.kind == RequestKind.FORECAST && !it.optional }
        val anyForecast = requests.any { it.kind == RequestKind.FORECAST }
        if (!anyForecast && coreForecasts.isNotEmpty()) {
            val until = coreForecasts.minOf { pstate.getValue(it.id).holdUntilMs }
            return FetchPlan(now, false, reasons, blockedBy = BlockReason.RETRY_AFTER, nextCheckAtMs = until, intervalMs = interval)
        }
        return FetchPlan(now, true, reasons, requests, intervalMs = interval)
    }

    private fun moved(from: GeoPoint, to: GeoPoint): Boolean {
        val c = config
        if (c.upstreamEmulation) {
            val deg = c.upstreamGridKm / KM_PER_DEGREE
            fun cell(p: GeoPoint) = floor(p.lat / deg).toLong() to floor(p.lon / deg).toLong()
            return cell(from) != cell(to)
        }
        return haversineKm(from, to) > c.moveTriggerKm
    }

    fun onCycleFinished(outcome: CycleOutcome) {
        val now = outcome.nowMs
        val c = config
        budget.recordCycle(now, outcome.providers.map { Triple(it.providerId, it.requests, it.bytes) })

        var any429 = false
        var coreForecastRetryAfterMs: Long? = null
        for (o in outcome.providers) {
            val st = pstate[o.providerId] ?: continue
            val spec = specs.first { it.id == o.providerId }
            st.lastAttemptMs = now
            if (o.success) {
                st.lastSuccessMs = now
                if (spec.kind == RequestKind.HOURLY) lastHourlySuccessMs = now
            }
            if (o.httpStatus == 429) {
                any429 = true
                val retryMs = o.retryAfterSec?.let { it * 1000L } ?: failureDelay(consecutiveFailures + 1)
                st.holdUntilMs = max(st.holdUntilMs, now + retryMs)
                if (spec.optional) st.holdUntilMs = max(st.holdUntilMs, now + c.optionalShedMs)
                if (spec.kind == RequestKind.FORECAST && !spec.optional) {
                    coreForecastRetryAfterMs = max(coreForecastRetryAfterMs ?: 0L, retryMs)
                }
            }
        }
        if (any429) {
            for (spec in specs) if (spec.optional) {
                val st = pstate.getValue(spec.id)
                st.holdUntilMs = max(st.holdUntilMs, now + c.optionalShedMs)
            }
        }

        if (outcome.success) {
            lastSuccessMs = now
            lastFetchPoint = outcome.rider
            lastRouteHash = outcome.routeHash
            consecutiveFailures = 0
            nextRetryAtMs = Long.MIN_VALUE
            adapt(now, outcome)
        } else {
            consecutiveFailures++
            var delay = if (c.upstreamEmulation) c.upstreamRetryMs else failureDelay(consecutiveFailures)
            coreForecastRetryAfterMs?.let { delay = max(delay, it) }
            nextRetryAtMs = now + delay
        }
    }

    private fun failureDelay(failures: Int): Long {
        val c = config
        val i = (failures - 1).coerceAtLeast(0)
        return min(c.failureBackoffMs.getOrElse(i) { c.failureBackoffCapMs }, c.failureBackoffCapMs)
    }

    private fun adapt(now: Long, outcome: CycleOutcome) {
        val c = config
        if (c.upstreamEmulation) return
        val r = outcome.residual
        val prev = prevResidual
        var speedUp = false
        if (r != null && prev != null) {
            if ((r - prev).speed > c.speedUpResidualDeltaMs) speedUp = true
            if (min(r.speed, prev.speed) >= c.speedUpAngleMinSpeed &&
                abs(normalizeSignedDegrees(r.directionFromDeg - prev.directionFromDeg)) > c.speedUpResidualAngleDeg
            ) speedUp = true
        }
        if (r != null) prevResidual = r
        if (speedUp) {
            speedUpUntilMs = now + c.speedUpDurationMs
            calmCycles = 0
            return
        }
        val spread = outcome.spread
        val calm = spread != null && spread < c.backOffSpreadMax && r != null && r.speed < c.backOffResidualMax
        calmCycles = if (calm) calmCycles + 1 else 0
    }

    fun state(nowMs: Long): PlannerState = PlannerState(
        lastSuccessMs = lastSuccessMs,
        lastFetchPoint = lastFetchPoint,
        consecutiveFailures = consecutiveFailures,
        nextRetryAtMs = nextRetryAtMs,
        speedUpUntilMs = speedUpUntilMs,
        calmCycles = calmCycles,
        intervalMs = intervalMs(nowMs),
        heldProviders = pstate.filterValues { it.holdUntilMs > nowMs }.mapValues { it.value.holdUntilMs },
    )

    fun budgetSnapshot(nowMs: Long): BudgetSnapshot = budget.snapshot(nowMs)
}
