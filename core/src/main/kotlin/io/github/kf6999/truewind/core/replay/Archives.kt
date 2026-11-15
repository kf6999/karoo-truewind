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

import io.github.kf6999.truewind.core.api.ForecastProvider
import io.github.kf6999.truewind.core.api.ForecastRequest
import io.github.kf6999.truewind.core.api.ForecastResult
import io.github.kf6999.truewind.core.api.ForecastSeries
import io.github.kf6999.truewind.core.api.ForecastStep
import io.github.kf6999.truewind.core.api.Observation
import io.github.kf6999.truewind.core.api.ObservationProvider
import io.github.kf6999.truewind.core.math.lerp
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.time.Clock
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Time resolution of archived forecast steps. */
enum class Resolution(val stepMs: Long) { HOURLY(3_600_000L), MINUTELY_15(900_000L) }

/**
 * Archived forecasts ("what the models said at the time"), per member and resolution, on a set of
 * grid points. Member ids are provider-qualified (`open-meteo:best_match`).
 *
 * Sampling at an arbitrary point uses inverse-distance weighting in u/v of the nearest archived
 * points within [MAX_SAMPLE_KM]; the downloader fetches a grid dense enough (about 3 km) that this
 * is close to what the API would have returned for the exact coordinate.
 */
class ForecastArchive(series: Map<Pair<String, Resolution>, List<ForecastSeries>>) {
    private val byKey = series.mapValues { (_, list) -> list.map { s -> s.copy(steps = s.steps.sortedBy { it.timeMs }) } }

    val members: List<String> get() = byKey.keys.map { it.first }.distinct().sorted()

    fun has(member: String, resolution: Resolution) = byKey[member to resolution]?.isNotEmpty() == true

    fun series(member: String, resolution: Resolution): List<ForecastSeries> = byKey[member to resolution].orEmpty()

    /** Series of [member] interpolated to [point] (IDW over the nearest archive points); null when none is near. */
    fun seriesAt(member: String, resolution: Resolution, point: GeoPoint): ForecastSeries? {
        val all = byKey[member to resolution] ?: return null
        val near = all.map { it to Geo.distanceKm(it.location, point) }
            .filter { it.second <= MAX_SAMPLE_KM }
            .sortedBy { it.second }
            .take(4)
        if (near.isEmpty()) return null
        if (near.first().second < 0.05 || near.size == 1) return near.first().first.copy(location = point)
        val weights = near.map { 1.0 / (it.second * it.second) }
        val wSum = weights.sum()
        val base = near.first().first
        val steps = base.steps.mapIndexedNotNull { i, step ->
            var u = 0.0; var v = 0.0; var g = 0.0; var gw = 0.0; var ok = 0.0
            near.forEachIndexed { k, (s, _) ->
                val other = s.steps.getOrNull(i)?.takeIf { it.timeMs == step.timeMs } ?: return@forEachIndexed
                val w = other.wind10m ?: return@forEachIndexed
                u += weights[k] * w.u; v += weights[k] * w.v; ok += weights[k]
                other.gust10m?.let { g += weights[k] * it; gw += weights[k] }
            }
            if (ok == 0.0) step.copy(wind10m = null) else step.copy(
                wind10m = WindVector(u / ok, v / ok),
                gust10m = if (gw > 0) g / gw else step.gust10m,
            )
        }
        check(wSum > 0)
        return base.copy(location = point, steps = steps)
    }

    companion object {
        const val MAX_SAMPLE_KM = 15.0
    }
}

/** Value of a series at [timeMs], linear in u/v between steps; null outside the series or across gaps. */
fun ForecastSeries.windAt(timeMs: Long): WindVector? {
    if (steps.isEmpty()) return null
    val idx = steps.binarySearchBy(timeMs) { it.timeMs }
    if (idx >= 0) return steps[idx].wind10m
    val ins = -idx - 1
    if (ins == 0 || ins >= steps.size) return null
    val a = steps[ins - 1]; val b = steps[ins]
    val wa = a.wind10m ?: return null
    val wb = b.wind10m ?: return null
    return lerp(wa, wb, (timeMs - a.timeMs).toDouble() / (b.timeMs - a.timeMs))
}

/** Archived observations per station, time ordered. */
class ObservationArchive(observations: List<Observation>, stations: List<StationInfo> = emptyList()) {
    val byStation: Map<String, List<Observation>> = observations.groupBy { it.stationId }.mapValues { (_, l) -> l.sortedBy { it.timeMs } }

    val stations: List<StationInfo> = stations.ifEmpty {
        byStation.values.map { l -> l.first().let { StationInfo(it.stationId, it.source, it.location, it.elevationM) } }
    }.sortedBy { it.id }

    fun stationsNear(points: List<GeoPoint>, radiusKm: Double): List<StationInfo> =
        stations.filter { s -> points.any { Geo.distanceKm(it, s.location) <= radiusKm } && byStation[s.id].orEmpty().isNotEmpty() }

    /** Newest report per station at or before [nowMs] and no older than [maxAgeMs]. */
    fun latest(nowMs: Long, center: GeoPoint, radiusKm: Double, maxAgeMs: Long, exclude: Set<String> = emptySet()): List<Observation> =
        byStation.mapNotNull { (id, list) ->
            if (id in exclude) return@mapNotNull null
            val idx = list.binarySearchBy(nowMs) { it.timeMs }.let { if (it >= 0) it else -it - 2 }
            val o = list.getOrNull(idx) ?: return@mapNotNull null
            if (nowMs - o.timeMs > maxAgeMs) return@mapNotNull null
            if (Geo.distanceKm(center, o.location) > radiusKm) return@mapNotNull null
            o
        }
}

/**
 * Serves archived forecasts through the production [ForecastProvider] interface, so fusion /
 * scheduler code from WP2/WP4/WP8 runs unchanged against the corpus. "Network" use is counted.
 */
class ArchiveForecastProvider(
    private val archive: ForecastArchive,
    private val resolution: Resolution,
    override val id: String = "archive-${resolution.name.lowercase()}",
    /** Members served when the request names none (`best_match` emulation for upstream). */
    private val defaultMembers: List<String> = listOf("open-meteo:best_match"),
) : ForecastProvider {
    var requests = 0
        private set

    override suspend fun fetch(request: ForecastRequest): ForecastResult {
        requests++
        val members = request.members.ifEmpty { defaultMembers }
        val failed = mutableListOf<String>()
        val series = members.flatMap { m ->
            val perPoint = request.points.mapNotNull { archive.seriesAt(m, resolution, it) }
            if (perPoint.isEmpty() || perPoint.all { s -> s.steps.all { it.wind10m == null } }) failed += m
            perPoint
        }
        return ForecastResult(series = series, failedMembers = failed)
    }
}

/** Serves archived observations through [ObservationProvider], excluding held-out stations (Metric A). */
class ArchiveObservationProvider(
    private val archive: ObservationArchive,
    private val clock: Clock,
    private val exclude: Set<String> = emptySet(),
    private val maxAgeMs: Long = 30 * 60_000L,
    override val id: String = "archive-obs",
) : ObservationProvider {
    var requests = 0
        private set

    override suspend fun fetch(center: GeoPoint, radiusKm: Double): List<Observation> {
        requests++
        return archive.latest(clock.nowMs(), center, radiusKm, maxAgeMs, exclude)
    }
}

/**
 * Runs a suspend block that never actually suspends (archive providers, fakes) without a
 * coroutines dependency in core. Throws if the block does suspend.
 */
fun <T> runSync(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return checkNotNull(result) { "runSync block suspended; replay providers must complete synchronously" }.getOrThrow()
}

/** Convenience for building steps in tests and the synthesiser. */
fun step(timeMs: Long, wind: WindVector?, gust: Double? = null) = ForecastStep(timeMs = timeMs, wind10m = wind, gust10m = gust)
