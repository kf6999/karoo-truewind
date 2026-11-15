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
import io.github.kf6999.truewind.core.api.ForecastSeries
import io.github.kf6999.truewind.core.api.Observation
import io.github.kf6999.truewind.core.fusion.ObsQc
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * REFERENCE STAND-IN for the 10 m fusion layers (SPEC 4.1–4.3), so `+ensemble` / `+obs` produce
 * numbers before the fusion engine (core.engine.WindEngine, core.fusion, core.schedule) lands. It
 * uses the production ObsQc but is otherwise intentionally plain and is NOT the product: replace it
 * through [LayerSeams.ensemble] / [LayerSeams.obs] and delete it once the engine is wired.
 */

/**
 * SPEC 4.1 fetch policy (10 min, 4 km, 1 km rounding), 4.2 prior-weighted multi-model blend of
 * 15-minute steps in u/v, and — with [useObservations] — 4.3 inverse-MSE member weighting and
 * distance/age-weighted residual correction plus the confidence deductions.
 */
class ReferenceEnsembleLayer(
    private val env: ReplayEnvironment,
    private val useObservations: Boolean,
    private val refreshMs: Long = 10 * 60_000L,
    private val moveKm: Double = 4.0,
    private val roundKm: Double = 1.0,
) : TenMeterWindLayer {
    private val resolution = if (env.forecastArchive.members.any { env.forecastArchive.has(it, Resolution.MINUTELY_15) }) Resolution.MINUTELY_15 else Resolution.HOURLY
    private val members: List<String> = env.forecastArchive.members.filter { env.forecastArchive.has(it, resolution) }.let { all ->
        // best_match duplicates the regional model (SPEC 4.2), keep it only when it is alone.
        val others = all.filter { it != UpstreamPipeline.BEST_MATCH }
        others.ifEmpty { all }
    }
    private val provider = if (resolution == Resolution.MINUTELY_15) env.minutely15 else env.hourly

    private var lastFetchMs: Long? = null
    private var lastFetchPoint: GeoPoint? = null
    private var riderSeries: Map<String, ForecastSeries> = emptyMap()
    private val mse = mutableMapOf<String, Double>()
    private var mseTimeMs: Long? = null
    private var weights: Map<String, Double> = emptyMap()

    private data class Residual(val station: GeoPoint, val timeMs: Long, val r: WindVector)
    private var residuals: List<Residual> = emptyList()
    private var hadUsableObs = false

    override fun estimate(point: GeoPoint, nowMs: Long): TenMeterEstimate? {
        val moved = lastFetchPoint?.let { Geo.distanceKm(it, point) > moveKm } ?: true
        if (lastFetchMs == null || nowMs - lastFetchMs!! >= refreshMs || moved) fetch(point, nowMs)

        val (blend, spread) = blendAt(riderSeries, nowMs) ?: return null
        var wind = blend
        var corrMag = 0.0
        if (useObservations && residuals.isNotEmpty()) {
            var sw = 0.0; var su = 0.0; var sv = 0.0
            for (r in residuals) {
                val d = Geo.distanceKm(point, r.station)
                val ageMin = (nowMs - r.timeMs) / 60_000.0
                val w = exp(-d / 15.0) * exp(-ageMin.coerceAtLeast(0.0) / 30.0)
                sw += w; su += w * r.r.u; sv += w * r.r.v
            }
            if (sw > 0) {
                val corr = WindVector(su / sw, sv / sw) * minOf(1.0, sw)
                wind += corr
                corrMag = corr.speed
            }
        }
        var confidence = 100.0
        confidence -= minOf(40.0, 10 * spread)
        if (useObservations) {
            confidence -= minOf(30.0, 10 * corrMag)
            if (!hadUsableObs) confidence -= 15.0
        } else confidence -= 15.0
        val ageMin = (nowMs - lastFetchMs!!) / 60_000.0
        confidence -= minOf(30.0, (ageMin - refreshMs / 60_000.0).coerceAtLeast(0.0))
        return TenMeterEstimate(wind, confidence.toInt().coerceIn(0, 100), riderSeries.keys.toList())
    }

    private fun weightOf(member: String): Double = weights[member] ?: (1.0 / members.size)

    /** Weighted u/v blend and weighted RMS spread of members at [timeMs]. */
    private fun blendAt(series: Map<String, ForecastSeries>, timeMs: Long): Pair<WindVector, Double>? {
        val values = series.mapNotNull { (m, s) -> s.windAt(timeMs)?.let { m to it } }
        if (values.isEmpty()) return null
        val wSum = values.sumOf { weightOf(it.first) }
        val blend = values.fold(WindVector.ZERO) { acc, (m, w) -> acc + w * (weightOf(m) / wSum) }
        val spread = sqrt(values.sumOf { (m, w) -> weightOf(m) / wSum * (w - blend).let { it.u * it.u + it.v * it.v } })
        return blend to spread
    }

    private fun fetch(point: GeoPoint, nowMs: Long) {
        val fetchPoint = GeoPoint(UpstreamPipeline.roundDegrees(point.lat, roundKm), UpstreamPipeline.roundDegrees(point.lon, roundKm))
        // Production QC (core.fusion.ObsQc, WP3): age, distance, elevation, speed, neighbour outliers, 10 m normalisation.
        val obs: List<Observation> = if (useObservations) {
            ObsQc.apply(runSync { env.observations.fetch(point, 30.0) }, point, null, nowMs).accepted.filter { it.wind != null }
        } else emptyList()
        val stationPoints = obs.sortedBy { Geo.distanceKm(point, it.location) }.take(3)
        val result = runSync { provider.fetch(ForecastRequest(points = listOf(fetchPoint) + stationPoints.map { it.location }, nowMs = nowMs, members = members)) }
        val byMember = result.series.groupBy { it.memberId }
        riderSeries = byMember.mapNotNull { (m, list) -> list.firstOrNull { it.location == fetchPoint }?.let { m to it } }.toMap()
        lastFetchMs = nowMs
        lastFetchPoint = point
        hadUsableObs = obs.isNotEmpty()
        if (!useObservations) return

        // Member weighting: exponentially weighted MSE with a 60 min half-life (SPEC 4.3).
        val decay = mseTimeMs?.let { 0.5.pow((nowMs - it) / 3_600_000.0) } ?: 0.0
        for (m in members) {
            val errs = stationPoints.mapNotNull { o ->
                val s = byMember[m]?.firstOrNull { it.location == o.location } ?: return@mapNotNull null
                val mv = s.windAt(o.timeMs) ?: return@mapNotNull null
                (mv - o.wind!!).let { it.u * it.u + it.v * it.v }
            }
            if (errs.isEmpty()) continue
            val e = errs.average()
            mse[m] = mse[m]?.let { decay * it + (1 - decay) * e } ?: e
        }
        if (stationPoints.isNotEmpty()) mseTimeMs = nowMs
        if (mse.isNotEmpty()) {
            val raw = members.associateWith { 1.0 / (0.5 + (mse[it] ?: mse.values.average())) }
            val norm = raw.values.sum()
            val floored = raw.mapValues { maxOf(0.05, it.value / norm) }
            val n2 = floored.values.sum()
            weights = floored.mapValues { it.value / n2 }
        }

        // Residuals of the (re-weighted) blend at every usable station.
        residuals = obs.mapNotNull { o ->
            val stationSeries = byMember.mapNotNull { (m, list) -> list.firstOrNull { it.location == o.location }?.let { m to it } }.toMap()
                .ifEmpty { members.mapNotNull { m -> env.forecastArchive.seriesAt(m, resolution, o.location)?.let { m to it } }.toMap() }
            val (b, _) = blendAt(stationSeries, o.timeMs) ?: return@mapNotNull null
            Residual(o.location, o.timeMs, o.wind!! - b)
        }
    }
}
