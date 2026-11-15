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
import io.github.kf6999.truewind.core.math.circularMeanDegrees
import io.github.kf6999.truewind.core.math.normalizeSignedDegrees
import io.github.kf6999.truewind.core.math.smoothAngle
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * REFERENCE STAND-INS for the accuracy layers, written straight from SPEC 4.1–4.5 so the harness
 * has something to measure before WP2/4/5/6/8 land. They are intentionally plain and are NOT the
 * product: replace them through [LayerSeams] and delete them once production layers are wired.
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
        val obs: List<Observation> = if (useObservations) {
            runSync { env.observations.fetch(point, 30.0) }.filter { o ->
                o.wind != null && (o.speed ?: 0.0) <= 40.0 && nowMs - o.timeMs <= 30 * 60_000L && Geo.distanceKm(point, o.location) <= 30.0
            }
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

/** SPEC 4.4 log law with the "Mixed" default preset: z = 1.2 m, z0 = 0.10 m (ratio 0.54). */
class ReferenceHeightLayer(zM: Double = 1.2, z0M: Double = 0.10) : HeightLayer {
    val ratio: Double = (ln(zM / z0M) / ln(10.0 / z0M)).coerceIn(0.30, 1.0)
    override fun toRider(model10m: WindVector, sample: RideSample): WindVector = model10m * ratio
}

/** SPEC 4.5 heading fusion, simplified (no route-bearing branch). */
class ReferenceHeadingLayer : HeadingLayer {
    private var heading: Double? = null
    private var lastTimeMs: Long? = null
    /** Exponentially weighted mean |HEADING - course| while moving, degrees. */
    private var headingAgreement: Double? = null

    override fun update(sample: RideSample): Double? {
        val dtS = lastTimeMs?.let { ((sample.timeMs - it) / 1000.0).coerceIn(0.0, 30.0) } ?: 1.0
        lastTimeMs = sample.timeMs
        val alpha = 1 - exp(-dtS / 1.5)
        val v = sample.speedMps ?: 0.0
        val course = sample.gpsBearingDeg
        val compass = sample.headingDeg
        val prev = heading
        when {
            v >= 3.0 && course != null -> {
                val jump = prev?.let { abs(normalizeSignedDegrees(course - it)) } ?: 0.0
                if (!(jump > 60.0 && (sample.gpsAccuracyM ?: 0.0) > 20.0)) {
                    heading = if (prev == null) course else smoothAngle(prev, course, alpha)
                }
                if (compass != null) {
                    val d = abs(normalizeSignedDegrees(compass - course))
                    val a = 1 - exp(-dtS / 120.0)
                    headingAgreement = headingAgreement?.let { it + a * (d - it) } ?: d
                }
            }
            v >= 1.0 && course != null -> {
                val target = if (compass != null) {
                    val wCompass = ((3.0 - v) / 2.0).coerceIn(0.0, 1.0)
                    circularMeanDegrees(listOf(course, compass), listOf(1 - wCompass, wCompass)) ?: course
                } else course
                heading = if (prev == null) target else smoothAngle(prev, target, alpha)
            }
            else -> {
                if (compass != null && (headingAgreement ?: 180.0) < 20.0) {
                    heading = if (prev == null) compass else smoothAngle(prev, compass, alpha)
                }
                // otherwise hold the last moving heading
            }
        }
        return heading
    }
}
