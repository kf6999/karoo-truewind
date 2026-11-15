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

package io.github.kf6999.truewind.core.engine

import io.github.kf6999.truewind.core.api.ForecastRequest
import io.github.kf6999.truewind.core.api.ForecastSeries
import io.github.kf6999.truewind.core.api.Observation
import io.github.kf6999.truewind.core.fusion.Fusion
import io.github.kf6999.truewind.core.fusion.FusedSample
import io.github.kf6999.truewind.core.fusion.FusionConfig
import io.github.kf6999.truewind.core.fusion.FusionSnapshot
import io.github.kf6999.truewind.core.fusion.InMemoryKeyValueStore
import io.github.kf6999.truewind.core.fusion.KeyValueStore
import io.github.kf6999.truewind.core.fusion.MemberWeights
import io.github.kf6999.truewind.core.fusion.ObsQc
import io.github.kf6999.truewind.core.fusion.ObsQcConfig
import io.github.kf6999.truewind.core.fusion.RejectedObservation
import io.github.kf6999.truewind.core.fusion.SampleParams
import io.github.kf6999.truewind.core.fusion.WeightStore
import io.github.kf6999.truewind.core.math.haversineKm
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.schedule.BudgetSnapshot
import io.github.kf6999.truewind.core.schedule.CycleOutcome
import io.github.kf6999.truewind.core.schedule.FetchPlan
import io.github.kf6999.truewind.core.schedule.FetchPlanner
import io.github.kf6999.truewind.core.schedule.FetchPlannerConfig
import io.github.kf6999.truewind.core.schedule.PlannerInput
import io.github.kf6999.truewind.core.schedule.ProviderOutcome
import io.github.kf6999.truewind.core.schedule.ProviderSpec
import io.github.kf6999.truewind.core.schedule.RequestKind

data class WindEngineConfig(
    val fusion: FusionConfig = FusionConfig(),
    val planner: FetchPlannerConfig = FetchPlannerConfig(),
    val qc: ObsQcConfig = ObsQcConfig(),
    /** Observation search radius, km (QC rejects beyond 30 km anyway). */
    val observationRadiusKm: Double = 30.0,
    /** Nearest accepted stations added to the forecast request so members are scored where truth exists. */
    val stationsInForecast: Int = 3,
    /** Total forecast coordinates per request (rider + stations + route points), SPEC 4.2. */
    val maxForecastPoints: Int = 10,
    val sample: SampleParams = SampleParams(),
    /** Per-layer switch (SPEC 4): use observations for weighting and residual correction. */
    val useObservations: Boolean = true,
    /**
     * Upstream Headwind emulation for the replay baseline: one forecast source, member
     * [upstreamMemberId] only, hourly steps linearly interpolated, no observations, planner in
     * upstream mode (also set [FetchPlannerConfig.upstreamEmulation]; [WindEngine] does it for you).
     */
    val upstreamEmulation: Boolean = false,
    val upstreamMemberId: String = "open-meteo:best_match",
)

/** Inputs of one [WindEngine.runCycleIfDue] call. */
data class CycleContext(
    val nowMs: Long,
    /** Rider position after privacy rounding; null without GPS. */
    val rider: GeoPoint?,
    val riderElevationM: Double? = null,
    val recording: Boolean,
    val visible: Boolean,
    val routeHash: Long? = null,
    /** Sample points along the loaded route (added to the forecast request after stations). */
    val routePoints: List<GeoPoint> = emptyList(),
    val force: Boolean = false,
)

/** What a cycle did, for diagnostics and tests. */
data class CycleReport(
    val plan: FetchPlan,
    /** False when the plan was not due (nothing fetched). */
    val ran: Boolean,
    val success: Boolean = false,
    val providers: List<ProviderOutcome> = emptyList(),
    val observationsAccepted: Int = 0,
    val observationsRejected: List<RejectedObservation> = emptyList(),
    /** Member weights after this cycle, highest first. */
    val weights: List<Pair<String, Double>> = emptyList(),
    /** Members newly scored this cycle → number of comparisons. */
    val scoredComparisons: Int = 0,
    val forecastPoints: List<GeoPoint> = emptyList(),
    val sampleAtCycle: FusedSample? = null,
    val budget: BudgetSnapshot = BudgetSnapshot(),
)

/**
 * Orchestrates providers, the fetch planner, observation QC, fusion and the cache of the latest
 * cycle (SPEC 3 "fetch and tick are separate").
 *
 * - [runCycleIfDue] does the network work; call it from one coroutine (e.g. every few seconds and
 *   on GPS/route/visibility changes). Requests run back-to-back so the radio wakes once.
 * - [sample] is the tick path: no network, no locking, reads the latest immutable snapshot.
 *
 * [runCycleIfDue] must not be called concurrently with itself; [sample] may run on any thread.
 */
class WindEngine(
    private val forecastSources: List<ForecastSource>,
    private val observationSources: List<ObservationSource> = emptyList(),
    private val hourlySource: HourlySource? = null,
    store: KeyValueStore = InMemoryKeyValueStore(),
    config: WindEngineConfig = WindEngineConfig(),
) {
    val config: WindEngineConfig = if (config.upstreamEmulation) {
        config.copy(planner = config.planner.copy(upstreamEmulation = true))
    } else config

    val planner = FetchPlanner(
        forecastSources.map { ProviderSpec(it.id, RequestKind.FORECAST, it.optional, it.minIntervalMs) } +
            observationSources.map { ProviderSpec(it.id, RequestKind.OBSERVATION, it.optional, it.minIntervalMs) } +
            listOfNotNull(hourlySource?.let { ProviderSpec(it.id, RequestKind.HOURLY) }),
        this.config.planner,
    )

    private val weights = MemberWeights(this.config.fusion)
    private val weightStore = WeightStore(store)
    private var weightsLoaded = false
    private val lastScored = HashMap<String, Long>()
    private var lastAccepted: List<Observation> = emptyList()

    @Volatile
    private var snapshot: FusionSnapshot? = null

    @Volatile
    private var sampleParams: SampleParams = this.config.sample.copy(intervalMs = this.config.planner.refreshIntervalMs)

    /** The cached fusion result, for diagnostics. */
    val latestSnapshot: FusionSnapshot? get() = snapshot

    fun onConnectivityChanged() = planner.onConnectivityChanged()

    suspend fun runCycleIfDue(context: CycleContext): CycleReport {
        val now = context.nowMs
        val plan = planner.plan(
            PlannerInput(now, context.rider, context.recording, context.visible, context.routeHash, context.force)
        )
        setInterval(plan.intervalMs)
        if (!plan.due) return CycleReport(plan, ran = false, budget = planner.budgetSnapshot(now))
        val rider = context.rider!!
        val cfg = config
        val outcomes = ArrayList<ProviderOutcome>()

        // 1. Observations first, so this cycle's stations can join the forecast request.
        val fresh = ArrayList<Observation>()
        val failedObsSources = HashSet<String>()
        if (cfg.useObservations && !cfg.upstreamEmulation) {
            for (src in observationSources) {
                if (!plan.includes(src.id)) continue
                val r = src.fetch(rider, cfg.observationRadiusKm)
                val ok = r.error == null
                if (!ok) failedObsSources += src.id
                fresh += r.items
                outcomes += ProviderOutcome(src.id, ok, r.requests, r.bytesReceived, r.httpStatus, r.retryAfterSec, r.error)
            }
        }
        // Carry over still-fresh reports (QC drops them by age; de-dup keeps the newest per station).
        val qc = ObsQc.apply(fresh + lastAccepted, rider, context.riderElevationM, now, cfg.qc)
        val accepted = if (cfg.useObservations && !cfg.upstreamEmulation) qc.accepted else emptyList()

        // 2. Forecasts: rider, nearest stations, route points.
        val points = ArrayList<GeoPoint>()
        points += rider
        for (o in accepted) {
            if (points.size >= 1 + cfg.stationsInForecast) break
            if (points.none { haversineKm(it, o.location) < 0.5 }) points += o.location
        }
        for (p in context.routePoints) {
            if (points.size >= cfg.maxForecastPoints) break
            if (points.none { haversineKm(it, p) < 0.5 }) points += p
        }
        val series = ArrayList<ForecastSeries>()
        for (src in forecastSources) {
            if (!plan.includes(src.id)) continue
            val req = ForecastRequest(
                points = if (src.riderOnly) listOf(rider) else points.toList(),
                nowMs = now,
                members = if (cfg.upstreamEmulation) listOf(cfg.upstreamMemberId) else emptyList(),
            )
            val (result, status) = src.fetch(req)
            val got = result.series.filter { s -> s.steps.any { it.wind10m != null } }
            series += got
            outcomes += status.toOutcome(src.id, got.isNotEmpty())
        }

        // 3. Hourly widgets forecast (rider only).
        if (hourlySource != null && plan.includes(hourlySource.id)) {
            val st = hourlySource.fetch(listOf(rider))
            outcomes += st.toOutcome(hourlySource.id, st.error == null)
        }

        val usable = if (cfg.upstreamEmulation) upstreamSeries(series, rider) else series
        val hasRider = usable.any { haversineKm(it.location, rider) <= cfg.fusion.seriesMatchKm }

        var weightList: List<Pair<String, Double>> = snapshot?.weights ?: emptyList()
        var scored = 0
        var newSnapshot: FusionSnapshot? = null
        if (hasRider) {
            val members = usable.map { it.memberId }.distinct()
            val weightMap: Map<String, Double>
            if (cfg.upstreamEmulation) {
                weightMap = members.associateWith { 1.0 / members.size }
            } else {
                if (!weightsLoaded) {
                    weights.import(weightStore.load(rider), now)
                    weightsLoaded = true
                }
                val errors = Fusion.scoreMembers(usable, accepted, lastScored, cfg.fusion)
                scored = errors.size
                if (errors.isNotEmpty()) {
                    weights.update(errors, now)
                    weightStore.save(rider, weights.export())
                }
                weightMap = weights.weightMap(members, now)
            }
            newSnapshot = Fusion.build(now, usable, weightMap, accepted, cfg.fusion)
        }
        val success = newSnapshot != null
        if (newSnapshot != null) {
            snapshot = newSnapshot
            weightList = newSnapshot.weights
            lastAccepted = accepted
        }

        val atCycle = snapshot?.sample(now, rider, cfg.sample.copy(intervalMs = plan.intervalMs))
        planner.onCycleFinished(
            CycleOutcome(
                nowMs = now,
                rider = rider,
                routeHash = context.routeHash,
                success = success,
                providers = outcomes,
                residual = if (success && accepted.any { haversineKm(it.location, rider) <= cfg.fusion.obsNearKm }) atCycle?.correction else null,
                spread = if (success) atCycle?.spread else null,
            )
        )
        setInterval(planner.intervalMs(now))

        return CycleReport(
            plan = plan,
            ran = true,
            success = success,
            providers = outcomes,
            observationsAccepted = accepted.size,
            observationsRejected = qc.rejected,
            weights = weightList,
            scoredComparisons = scored,
            forecastPoints = points,
            sampleAtCycle = atCycle,
            budget = planner.budgetSnapshot(now),
        )
    }

    /** Upstream emulation: the configured member (or the first one) at hourly steps only. */
    private fun upstreamSeries(series: List<ForecastSeries>, rider: GeoPoint): List<ForecastSeries> {
        val member = series.firstOrNull { it.memberId == config.upstreamMemberId }?.memberId
            ?: series.firstOrNull()?.memberId ?: return emptyList()
        return series.filter { it.memberId == member }.map { s ->
            s.copy(steps = s.steps.filter { it.timeMs % HOUR_MS == 0L })
        }.filter { s -> s.steps.any { it.wind10m != null } }
    }

    /**
     * Tick path: the fused 10 m wind, gust, confidence, weather scalars and source summary at
     * [rider] for [nowMs], interpolated from the cache. No network; safe from any thread.
     */
    fun sample(nowMs: Long, rider: GeoPoint): FusedSample {
        val s = snapshot ?: return FusedSample.noData(nowMs)
        return s.sample(nowMs, rider, sampleParams)
    }

    private fun setInterval(ms: Long) {
        if (sampleParams.intervalMs != ms) sampleParams = config.sample.copy(intervalMs = ms)
    }

    /** Current member weights (highest first) for the diagnostics page. */
    fun memberWeights(): List<Pair<String, Double>> = snapshot?.weights ?: emptyList()

    private companion object {
        const val HOUR_MS = 60 * 60_000L
    }
}

/** Convenience for diagnostics: correction magnitude. */
val FusedSample.correctionSpeed: Double get() = correction?.speed ?: 0.0
