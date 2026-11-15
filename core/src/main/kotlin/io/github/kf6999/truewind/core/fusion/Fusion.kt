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

package io.github.kf6999.truewind.core.fusion

import io.github.kf6999.truewind.core.api.ForecastSeries
import io.github.kf6999.truewind.core.api.Observation
import io.github.kf6999.truewind.core.math.haversineKm
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.SourceSummary
import io.github.kf6999.truewind.core.model.Status
import io.github.kf6999.truewind.core.model.WindVector
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Weather scalars at the rider, blended over members with the fusion weights. */
data class WeatherScalars(
    val temperatureC: Double? = null,
    val relativeHumidityPct: Double? = null,
    val precipitationMm: Double? = null,
    val cloudCoverPct: Double? = null,
    val surfacePressureHpa: Double? = null,
    val sealevelPressureHpa: Double? = null,
    /** WMO code from the highest-weighted member that reports one. */
    val weatherCode: Int? = null,
    /** Model grid elevation of the forecast point, m (for the pressure correction, SPEC 4.4). */
    val gridElevationM: Double? = null,
)

enum class ConfidenceBand {
    HIGH, MEDIUM, LOW;

    companion object {
        fun of(confidence: Int, config: ConfidenceConfig = ConfidenceConfig()): ConfidenceBand = when {
            confidence >= config.highBand -> HIGH
            confidence >= config.mediumBand -> MEDIUM
            else -> LOW
        }
    }
}

/** Points deducted from 100, per SPEC 4.3 term. */
data class ConfidenceBreakdown(
    val spread: Double = 0.0,
    val residual: Double = 0.0,
    val noObservation: Double = 0.0,
    val age: Double = 0.0,
)

/**
 * The fused 10 m picture at the rider for one instant: what the app feeds into
 * `RiderWindEstimator` (`model10m`, `gust10m`, `confidence`, weather scalars).
 */
data class FusedSample(
    val timeMs: Long,
    /** OK, STALE or NO_DATA (the app adds NO_GPS / NOT_SET_UP). */
    val status: Status,
    /** Blend + residual correction, 10 m. Null with NO_DATA. */
    val model10m: WindVector?,
    /** Blended 10 m gust, m/s. */
    val gust10m: Double?,
    val confidence: Int,
    val band: ConfidenceBand,
    /** False under [ConfidenceConfig.directionMinSpeed]: show speed, grey out direction. */
    val directionReliable: Boolean,
    val weather: WeatherScalars?,
    val sources: SourceSummary,
    /** Since the fetch that produced the cache, ms; null if there never was one. */
    val ageMs: Long?,
    /** Uncorrected weighted blend at the rider. */
    val blend: WindVector? = null,
    /** Applied residual correction (already scaled by `min(1, Σk)`). */
    val correction: WindVector? = null,
    /** Weighted inter-model spread, m/s RMS. */
    val spread: Double? = null,
    val confidenceBreakdown: ConfidenceBreakdown = ConfidenceBreakdown(),
    /** True when [timeMs] is past the last cached step and values are held. */
    val beyondHorizon: Boolean = false,
) {
    companion object {
        fun noData(timeMs: Long) = FusedSample(
            timeMs = timeMs, status = Status.NO_DATA, model10m = null, gust10m = null, confidence = 0,
            band = ConfidenceBand.LOW, directionReliable = false, weather = null, sources = SourceSummary(), ageMs = null,
        )
    }
}

/** Parameters of [FusionSnapshot.sample] that come from the engine rather than the data. */
data class SampleParams(
    /** Current refresh interval; data older than this costs confidence. */
    val intervalMs: Long = 10 * 60_000L,
    /** Status turns STALE once the cache is older than this (SPEC 4.1 "four hours without a phone"). */
    val cacheWindowMs: Long = 4 * 60 * 60_000L,
    /** After this, values are withdrawn (NO_DATA). */
    val maxHoldMs: Long = 6 * 60 * 60_000L,
)

/**
 * Immutable result of one fusion run: the blended forecast on a regular 15-min grid at every
 * forecast point, plus the residual stations. [sample] is cheap (no list allocation besides the
 * result) and safe to call from the tick thread while a new snapshot is being built.
 */
class FusionSnapshot internal constructor(
    /** When the data were fetched (age reference). */
    val fetchedAtMs: Long,
    /** Members and their weights, highest first. */
    val weights: List<Pair<String, Double>>,
    val points: List<GeoPoint>,
    private val gridStartMs: Long,
    private val stepMs: Long,
    private val nSteps: Int,
    // [point][step] flattened: index = p * nSteps + i. NaN = no member valid.
    private val u: DoubleArray,
    private val v: DoubleArray,
    private val gust: DoubleArray,
    private val spread: DoubleArray,
    private val temp: DoubleArray,
    private val rh: DoubleArray,
    private val precip: DoubleArray,
    private val cloud: DoubleArray,
    private val sfcP: DoubleArray,
    private val mslP: DoubleArray,
    private val code: IntArray,
    private val gridElevation: DoubleArray,
    /** Accepted observations (nearest first at build time) and their residuals. */
    val stations: List<Observation>,
    private val residualU: DoubleArray,
    private val residualV: DoubleArray,
    private val hasResidual: BooleanArray,
    private val config: FusionConfig,
) {
    /** Models for the source summary: weight ≥ [FusionConfig.summaryMinWeight], highest first. */
    val summaryModels: List<String> = weights.filter { it.second >= config.summaryMinWeight }.map { it.first }
        .ifEmpty { weights.take(1).map { it.first } }

    val lastStepMs: Long get() = gridStartMs + (nSteps - 1) * stepMs

    /** Residual (obs − blend at the station) of [stations] entry [i]; null for speed-only reports without a blend. */
    fun residual(i: Int): WindVector? = if (hasResidual[i]) WindVector(residualU[i], residualV[i]) else null

    /** A copy whose station residuals are replaced by [residualOf] (null = no residual for that station). */
    fun withResiduals(residualOf: (Int) -> WindVector?): FusionSnapshot {
        val ru = DoubleArray(stations.size)
        val rv = DoubleArray(stations.size)
        val has = BooleanArray(stations.size)
        for (i in stations.indices) {
            val r = residualOf(i) ?: continue
            ru[i] = r.u
            rv[i] = r.v
            has[i] = true
        }
        return FusionSnapshot(
            fetchedAtMs, weights, points, gridStartMs, stepMs, nSteps, u, v, gust, spread, temp, rh, precip, cloud,
            sfcP, mslP, code, gridElevation, stations, ru, rv, has, config,
        )
    }

    /** Blend at forecast point [pointIndex], time [t] (no correction). */
    fun blendAt(pointIndex: Int, t: Long): WindVector? {
        val (iu, iv) = interp(u, pointIndex, t) to interp(v, pointIndex, t)
        return if (iu.isNaN() || iv.isNaN()) null else WindVector(iu, iv)
    }

    fun nearestPoint(p: GeoPoint): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in points.indices) {
            val d = haversineKm(points[i], p)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /** Interpolates a grid array at [t] (clamped to the grid); NaN where no value. */
    private fun interp(a: DoubleArray, pointIndex: Int, t: Long): Double {
        val base = pointIndex * nSteps
        if (nSteps == 1 || t <= gridStartMs) return firstFinite(a, base, 0, +1)
        val pos = (t - gridStartMs).toDouble() / stepMs
        if (pos >= nSteps - 1) return firstFinite(a, base, nSteps - 1, -1)
        val i0 = floor(pos).toInt()
        val f = pos - i0
        val a0 = a[base + i0]
        val a1 = a[base + i0 + 1]
        return when {
            a0.isNaN() -> a1
            a1.isNaN() -> a0
            else -> a0 + (a1 - a0) * f
        }
    }

    private fun firstFinite(a: DoubleArray, base: Int, from: Int, dir: Int): Double {
        var i = from
        while (i in 0 until nSteps) {
            val x = a[base + i]
            if (!x.isNaN()) return x
            i += dir
        }
        return Double.NaN
    }

    private fun nanToNull(x: Double): Double? = if (x.isNaN()) null else x

    /** The fused sample at the rider for [nowMs]. */
    fun sample(nowMs: Long, rider: GeoPoint, params: SampleParams = SampleParams()): FusedSample {
        val ageMs = max(0L, nowMs - fetchedAtMs)
        if (ageMs > params.maxHoldMs) return FusedSample.noData(nowMs).copy(ageMs = ageMs)

        val p = nearestPoint(rider)
        val bu = interp(u, p, nowMs)
        val bv = interp(v, p, nowMs)
        if (bu.isNaN() || bv.isNaN()) return FusedSample.noData(nowMs).copy(ageMs = ageMs)
        val blend = WindVector(bu, bv)

        // Residual correction and nearest station (SPEC 4.3).
        var sumK = 0.0
        var sumU = 0.0
        var sumV = 0.0
        var nearestIdx = -1
        var nearestD = Double.MAX_VALUE
        var anyNearFresh = false
        for (i in stations.indices) {
            val s = stations[i]
            val d = haversineKm(rider, s.location)
            val age = max(0L, nowMs - s.timeMs)
            if (d < nearestD) {
                nearestD = d
                nearestIdx = i
            }
            if (d <= config.obsNearKm && age <= config.obsFreshMs) anyNearFresh = true
            if (!hasResidual[i]) continue
            val k = s.reliability.coerceIn(0.0, 1.0) *
                exp(-d / config.residualDistanceScaleKm) * exp(-age.toDouble() / config.residualAgeScaleMs)
            sumK += k
            sumU += k * residualU[i]
            sumV += k * residualV[i]
        }
        val correction = if (sumK > 1e-12) {
            val scale = min(1.0, sumK) / sumK
            WindVector(sumU * scale, sumV * scale)
        } else {
            WindVector.ZERO
        }
        val model10m = blend + correction

        val sp = interp(spread, p, nowMs).let { if (it.isNaN()) 0.0 else it }
        val cc = config.confidence
        val intervalMin = params.intervalMs / 60_000.0
        val breakdown = ConfidenceBreakdown(
            spread = min(cc.spreadCap, cc.spreadPointsPerMs * sp),
            residual = min(cc.residualCap, cc.residualPointsPerMs * correction.speed),
            noObservation = if (anyNearFresh) 0.0 else cc.noObservationPoints,
            age = min(cc.ageCap, max(0.0, ageMs / 60_000.0 - intervalMin) * cc.agePointsPerMinute),
        )
        val confidence = (100.0 - breakdown.spread - breakdown.residual - breakdown.noObservation - breakdown.age)
            .roundToInt().coerceIn(0, 100)

        val beyond = nowMs > lastStepMs + stepMs
        val status = if (ageMs > params.cacheWindowMs || beyond) Status.STALE else Status.OK
        val nearest = stations.getOrNull(nearestIdx)

        return FusedSample(
            timeMs = nowMs,
            status = status,
            model10m = model10m,
            gust10m = nanToNull(interp(gust, p, nowMs))?.let { max(it, model10m.speed) },
            confidence = confidence,
            band = ConfidenceBand.of(confidence, cc),
            directionReliable = model10m.speed >= cc.directionMinSpeed,
            weather = WeatherScalars(
                temperatureC = nanToNull(interp(temp, p, nowMs)),
                relativeHumidityPct = nanToNull(interp(rh, p, nowMs)),
                precipitationMm = nanToNull(interp(precip, p, nowMs)),
                cloudCoverPct = nanToNull(interp(cloud, p, nowMs)),
                surfacePressureHpa = nanToNull(interp(sfcP, p, nowMs)),
                sealevelPressureHpa = nanToNull(interp(mslP, p, nowMs)),
                weatherCode = codeAt(p, nowMs),
                gridElevationM = nanToNull(gridElevation[p]),
            ),
            sources = SourceSummary(
                models = summaryModels,
                nearestStationId = nearest?.stationId,
                nearestStationDistanceKm = if (nearest != null) nearestD else null,
                observationAgeMin = nearest?.let { max(0L, nowMs - it.timeMs) / 60_000.0 },
            ),
            ageMs = ageMs,
            blend = blend,
            correction = correction,
            spread = sp,
            confidenceBreakdown = breakdown,
            beyondHorizon = beyond,
        )
    }

    private fun codeAt(p: Int, t: Long): Int? {
        val i = ((t - gridStartMs).toDouble() / stepMs).roundToInt().coerceIn(0, nSteps - 1)
        // Nearest step with a code, searching outward.
        for (off in 0 until nSteps) {
            val a = i - off
            val b = i + off
            if (a >= 0 && code[p * nSteps + a] >= 0) return code[p * nSteps + a]
            if (b < nSteps && code[p * nSteps + b] >= 0) return code[p * nSteps + b]
        }
        return null
    }
}

/**
 * Fusion engine, SPEC 4.3: member scoring against observations, weighted u/v blend on a 15-min
 * grid, residual correction, confidence. Pure functions; state (the EWMA) lives in [MemberWeights].
 */
object Fusion {
    /**
     * Vector errors `model_m(s, t_obs) − obs(s)` for every member with a series at each station
     * (within [FusionConfig.seriesMatchKm]). Observation [Observation.reliability] is the score
     * weight; speed-only reports score `(|model| − speed)²` at [FusionConfig.speedOnlyScoreFactor].
     * [lastScored] (station key → newest scored obs time) is updated so an hourly METAR seen by six
     * 10-min cycles is scored once.
     */
    fun scoreMembers(
        series: List<ForecastSeries>,
        observations: List<Observation>,
        lastScored: MutableMap<String, Long>,
        config: FusionConfig = FusionConfig(),
    ): List<MemberError> {
        val members = series.map { it.memberId }.distinct()
        val out = ArrayList<MemberError>()
        for (o in observations) {
            val key = stationKey(o)
            val prev = lastScored[key]
            if (prev != null && o.timeMs <= prev) continue
            val speed = o.speed ?: continue
            var scored = false
            for (m in members) {
                val s = SeriesMath.seriesNear(series, m, o.location, config.seriesMatchKm) ?: continue
                val model = SeriesMath.memberWindAt(s, o.timeMs, config) ?: continue
                val w = o.wind
                val (e2, weight) = if (w != null) {
                    val du = model.u - w.u
                    val dv = model.v - w.v
                    (du * du + dv * dv) to o.reliability
                } else {
                    val d = model.speed - speed
                    d * d to o.reliability * config.speedOnlyScoreFactor
                }
                if (weight > 0.0) {
                    out += MemberError(m, e2, weight)
                    scored = true
                }
            }
            if (scored) lastScored[key] = o.timeMs
        }
        return out
    }

    fun stationKey(o: Observation): String = o.stationId.uppercase()

    /**
     * Builds the snapshot. [weights] maps member id → normalised weight (members missing from it
     * get the floor weight). [observations] are QC'd and normalised to 10 m. Returns null when no
     * series has any wind.
     */
    fun build(
        fetchedAtMs: Long,
        series: List<ForecastSeries>,
        weights: Map<String, Double>,
        observations: List<Observation>,
        config: FusionConfig = FusionConfig(),
    ): FusionSnapshot? {
        val usable = series.filter { s -> s.steps.any { it.wind10m != null } }
        if (usable.isEmpty()) return null

        // Distinct forecast points (series at the same requested coordinate share a point).
        val points = ArrayList<GeoPoint>()
        for (s in usable) if (points.none { haversineKm(it, s.location) < 0.01 }) points += s.location
        val byPoint = points.map { p -> usable.filter { haversineKm(it.location, p) < 0.01 } }

        val step = config.stepMs
        val minT = usable.minOf { s -> s.steps.first().timeMs }
        val maxT = usable.maxOf { s -> s.steps.last().timeMs }
        val start = floor(minT.toDouble() / step).toLong() * step
        val end = max(start, ceil(maxT.toDouble() / step).toLong() * step)
        val n = ((end - start) / step).toInt() + 1
        val size = points.size * n

        fun nanArray() = DoubleArray(size) { Double.NaN }
        val u = nanArray(); val v = nanArray(); val gust = nanArray(); val spread = nanArray()
        val temp = nanArray(); val rh = nanArray(); val precip = nanArray(); val cloud = nanArray()
        val sfcP = nanArray(); val mslP = nanArray()
        val code = IntArray(size) { -1 }
        val gridElev = DoubleArray(points.size) { Double.NaN }

        val floorW = config.weightFloor
        fun weightOf(m: String) = weights[m] ?: floorW

        for (pi in points.indices) {
            val group = byPoint[pi].sortedByDescending { weightOf(it.memberId) }
            gridElev[pi] = group.firstNotNullOfOrNull { it.gridElevationM } ?: Double.NaN
            val ws = DoubleArray(group.size)
            val mu = DoubleArray(group.size)
            val mv = DoubleArray(group.size)
            for (i in 0 until n) {
                val t = start + i * step
                val idx = pi * n + i
                var sw = 0.0
                for ((k, s) in group.withIndex()) {
                    val w = SeriesMath.memberWindAt(s, t, config)
                    if (w == null) {
                        ws[k] = 0.0
                    } else {
                        ws[k] = weightOf(s.memberId)
                        mu[k] = w.u
                        mv[k] = w.v
                        sw += ws[k]
                    }
                }
                if (sw <= 0.0) continue
                var bu = 0.0
                var bv = 0.0
                for (k in group.indices) if (ws[k] > 0.0) {
                    bu += ws[k] / sw * mu[k]
                    bv += ws[k] / sw * mv[k]
                }
                var var2 = 0.0
                for (k in group.indices) if (ws[k] > 0.0) {
                    val du = mu[k] - bu
                    val dv = mv[k] - bv
                    var2 += ws[k] / sw * (du * du + dv * dv)
                }
                spread[idx] = sqrt(var2)
                u[idx] = bu
                v[idx] = bv

                fun blendScalar(select: (io.github.kf6999.truewind.core.api.ForecastStep) -> Double?): Double {
                    var acc = 0.0
                    var accW = 0.0
                    for ((k, s) in group.withIndex()) {
                        if (ws[k] <= 0.0) continue
                        val x = SeriesMath.scalarAt(s, t, select) ?: continue
                        acc += ws[k] * x
                        accW += ws[k]
                    }
                    return if (accW > 0.0) acc / accW else Double.NaN
                }
                gust[idx] = blendScalar { it.gust10m }
                temp[idx] = blendScalar { it.temperatureC }
                rh[idx] = blendScalar { it.relativeHumidityPct }
                precip[idx] = blendScalar { it.precipitationMm }
                cloud[idx] = blendScalar { it.cloudCoverPct }
                sfcP[idx] = blendScalar { it.surfacePressureHpa }
                mslP[idx] = blendScalar { it.sealevelPressureHpa }
                code[idx] = group.withIndex().firstNotNullOfOrNull { (k, s) ->
                    if (ws[k] > 0.0) SeriesMath.weatherCodeAt(s, t) else null
                } ?: -1
            }
        }

        val sortedWeights = weights.entries.filter { e -> usable.any { it.memberId == e.key } }
            .sortedByDescending { it.value }.map { it.key to it.value }

        val snapshotNoResiduals = FusionSnapshot(
            fetchedAtMs, sortedWeights, points, start, step, n, u, v, gust, spread, temp, rh, precip, cloud,
            sfcP, mslP, code, gridElev, emptyList(), DoubleArray(0), DoubleArray(0), BooleanArray(0), config,
        )

        // Residuals at stations: r_s = obs_s − blend(s, t_obs), blend at the nearest forecast point.
        val ru = DoubleArray(observations.size)
        val rv = DoubleArray(observations.size)
        val has = BooleanArray(observations.size)
        for ((i, o) in observations.withIndex()) {
            val pi = snapshotNoResiduals.nearestPoint(o.location)
            if (haversineKm(points[pi], o.location) > config.obsNearKm) continue
            val b = snapshotNoResiduals.blendAt(pi, o.timeMs) ?: continue
            val w = o.wind
            if (w != null) {
                ru[i] = w.u - b.u
                rv[i] = w.v - b.v
                has[i] = true
            } else if (o.speed != null && b.speed > 0.5) {
                // Speed-only (VRB): correct the magnitude along the blend direction.
                val k = (o.speed - b.speed) / b.speed
                ru[i] = b.u * k
                rv[i] = b.v * k
                has[i] = true
            }
        }

        return FusionSnapshot(
            fetchedAtMs, sortedWeights, points, start, step, n, u, v, gust, spread, temp, rh, precip, cloud,
            sfcP, mslP, code, gridElev, observations, ru, rv, has, config,
        )
    }

    /** Magnitude helper for diagnostics. */
    internal fun magnitude(u: Double, v: Double) = hypot(u, v)
}
