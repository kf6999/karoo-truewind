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

import io.github.kf6999.truewind.core.api.ForecastRequest
import io.github.kf6999.truewind.core.providers.obs.ObsFetchResult
import io.github.kf6999.truewind.core.engine.CycleContext
import io.github.kf6999.truewind.core.engine.ForecastSource
import io.github.kf6999.truewind.core.engine.ObservationSource
import io.github.kf6999.truewind.core.engine.SourceStatus
import io.github.kf6999.truewind.core.engine.WindEngine
import io.github.kf6999.truewind.core.engine.WindEngineConfig
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.Status

/**
 * The production fusion engine ([WindEngine]: FetchPlanner + ObsQc + MemberWeights + Fusion) as a
 * replay 10 m layer. Archive providers stand in for the network (requests are counted in
 * [ReplayEnvironment.fetchRequests]); the engine runs a cycle whenever its planner says one is due
 * on the replay clock, and every tick samples the cached snapshot exactly like the app's tick path.
 */
class WindEngineLayer(
    private val env: ReplayEnvironment,
    useObservations: Boolean,
    config: WindEngineConfig = WindEngineConfig(),
) : TenMeterWindLayer {
    private val resolution =
        if (env.forecastArchive.members.any { env.forecastArchive.has(it, Resolution.MINUTELY_15) }) Resolution.MINUTELY_15 else Resolution.HOURLY
    private val members: List<String> = env.forecastArchive.members.filter { env.forecastArchive.has(it, resolution) }.let { all ->
        // best_match duplicates the regional model (SPEC 4.2); the app drops it when others exist.
        all.filter { it != UpstreamPipeline.BEST_MATCH }.ifEmpty { all }
    }
    private val provider = if (resolution == Resolution.MINUTELY_15) env.minutely15 else env.hourly

    val engine = WindEngine(
        forecastSources = listOf(
            ForecastSource(provider) { req ->
                val r = provider.fetch(ForecastRequest(req.points, req.nowMs, members))
                r to SourceStatus(error = if (r.series.isEmpty()) "no series" else null)
            },
        ),
        observationSources = if (useObservations) listOf(
            ObservationSource(env.observations.id) { c, radius -> ObsFetchResult(env.observations.fetch(c, radius), requests = 1) },
        ) else emptyList(),
        config = config.copy(useObservations = useObservations),
    )

    var cycles = 0
        private set

    override fun estimate(point: GeoPoint, nowMs: Long): TenMeterEstimate? {
        val report = runSync { engine.runCycleIfDue(CycleContext(nowMs = nowMs, rider = point, recording = true, visible = true)) }
        if (report.ran) cycles++
        val s = engine.sample(nowMs, point)
        val wind = s.model10m ?: return null
        if (s.status == Status.NO_DATA) return null
        return TenMeterEstimate(wind = wind, confidence = s.confidence, members = s.sources.models, gust = s.gust10m)
    }
}
