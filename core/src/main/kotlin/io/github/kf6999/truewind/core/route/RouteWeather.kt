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

package io.github.kf6999.truewind.core.route

import io.github.kf6999.truewind.core.api.ForecastStep
import io.github.kf6999.truewind.core.math.haversineKm
import io.github.kf6999.truewind.core.model.GeoPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/** A route as a polyline with cumulative distances. Needs at least one point. */
class RoutePolyline(val points: List<GeoPoint>) {
    /** Cumulative distance at each point, km. */
    val cumulativeKm: DoubleArray

    init {
        require(points.isNotEmpty()) { "route needs at least one point" }
        cumulativeKm = DoubleArray(points.size)
        for (i in 1 until points.size) cumulativeKm[i] = cumulativeKm[i - 1] + haversineKm(points[i - 1], points[i])
    }

    val lengthKm: Double get() = cumulativeKm.last()

    private fun segmentIndex(km: Double): Int {
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cumulativeKm[mid] <= km) lo = mid else hi = mid
        }
        return lo
    }

    /** Point at [km] along the route (clamped to the ends). */
    fun pointAt(km: Double): GeoPoint {
        if (points.size == 1) return points[0]
        val d = km.coerceIn(0.0, lengthKm)
        val i = segmentIndex(d)
        val len = cumulativeKm[i + 1] - cumulativeKm[i]
        return if (len <= 0.0) points[i] else GeoMath.interpolate(points[i], points[i + 1], (d - cumulativeKm[i]) / len)
    }

    /**
     * Local direction of travel at [km], degrees: bearing from the point [windowKm] behind to the
     * point [windowKm] ahead (smooths GPS-track zigzags). Null for a zero-length route.
     */
    fun bearingAt(km: Double, windowKm: Double = 0.25): Double? {
        if (lengthKm <= 0.0) return null
        var a = (km - windowKm).coerceAtLeast(0.0)
        var b = (km + windowKm).coerceAtMost(lengthKm)
        if (b - a < 1e-6) { a = 0.0; b = lengthKm }
        val pa = pointAt(a)
        val pb = pointAt(b)
        if (pa == pb) return null
        return GeoMath.bearingDeg(pa, pb)
    }

    /** Distance along the route of the vertex/segment point closest to [p], km (for remaining-distance estimates). */
    fun project(p: GeoPoint): Double {
        if (points.size == 1) return 0.0
        val proj = GeoMath.LocalProjection(p)
        var best = Double.MAX_VALUE
        var bestKm = 0.0
        for (i in 0 until points.size - 1) {
            val d = proj.distanceToSegmentKm(points[i], points[i + 1])
            if (d < best) {
                best = d
                val c = proj.closestOnSegment(points[i], points[i + 1])
                bestKm = cumulativeKm[i] + haversineKm(points[i], c)
            }
        }
        return bestKm
    }
}

/** Rider speed along the route, km/h (> 0). */
fun interface SpeedModel {
    fun speedKmh(distanceKm: Double): Double

    companion object {
        fun constant(kmh: Double): SpeedModel {
            require(kmh > 0) { "speed must be positive" }
            return SpeedModel { kmh }
        }
    }
}

/** Time to ride from [fromKm] to [toKm] with [speed], ms (integrated in 0.5 km steps). */
fun travelTimeMs(fromKm: Double, toKm: Double, speed: SpeedModel, stepKm: Double = 0.5): Long {
    if (toKm <= fromKm) return 0
    var t = 0.0
    var d = fromKm
    while (d < toKm - 1e-9) {
        val step = minOf(stepKm, toKm - d)
        val v = speed.speedKmh(d + step / 2).coerceAtLeast(1.0)
        t += step / v
        d += step
    }
    return (t * 3_600_000).toLong()
}

/** Weather at one route sample. Wind fields are 10 m forecast values. */
data class RouteSample(
    val index: Int,
    val distanceKm: Double,
    val point: GeoPoint,
    val etaMs: Long,
    /** Direction of travel at the sample, degrees. */
    val routeBearingDeg: Double?,
    val windSpeedMs: Double?,
    val windFromDeg: Double?,
    /** + into the rider. */
    val headwindMs: Double?,
    /** + from the right. */
    val crosswindMs: Double?,
    val gustMs: Double?,
    val temperatureC: Double?,
    /** Precipitation in the forecast step, mm. */
    val precipitationMm: Double?,
    val precipitationMmPerHour: Double?,
    /** 0..100, see [RideScore]; null without wind and temperature. */
    val score: Int?,
)

data class RouteWeatherPlan(
    val startMs: Long,
    val finishMs: Long,
    val samples: List<RouteSample>,
    /** Mean of the sample scores, 0..100. */
    val rideScore: Int?,
    /** Mean headwind over the samples, m/s (+ head, - tail). */
    val meanHeadwindMs: Double?,
)

data class BetterStart(val startMs: Long, val score: Int, val nowScore: Int?, val improvement: Int)

/**
 * Ride score, 0..100 (higher is better), from one sample's forecast:
 *
 * ```
 * score = 100 - wind - rain - temperature - gust, clamped to 0..100
 *   wind        = 6 x headwind+ (m/s) + 1.5 x |crosswind| - 2 x min(tailwind, 5)   (tailwind earns up to 10)
 *   rain        = 0 below 0.1 mm/h, else min(50, 15 + 10 x mm/h)
 *   temperature = 2 per degree C below 12 or above 26, at most 30
 *   gust        = 2 per m/s of gust above 10 m/s, at most 15
 * ```
 *
 * Missing inputs contribute 0. A 5 m/s (18 km/h) headwind at 18 °C, dry, scores 70; the same
 * wind as a tailwind scores 100; steady 2 mm/h rain costs 35.
 */
object RideScore {
    fun score(headwindMs: Double?, crosswindMs: Double?, gustMs: Double?, temperatureC: Double?, precipMmPerHour: Double?): Int? {
        if (headwindMs == null && temperatureC == null && precipMmPerHour == null) return null
        val h = headwindMs ?: 0.0
        val wind = 6 * maxOf(0.0, h) + 1.5 * abs(crosswindMs ?: 0.0) - 2 * minOf(maxOf(0.0, -h), 5.0)
        val p = precipMmPerHour ?: 0.0
        val rain = if (p < 0.1) 0.0 else minOf(50.0, 15 + 10 * p)
        val t = temperatureC
        val temp = when {
            t == null -> 0.0
            t < 12 -> minOf(30.0, 2 * (12 - t))
            t > 26 -> minOf(30.0, 2 * (t - 26))
            else -> 0.0
        }
        val gust = minOf(15.0, 2 * maxOf(0.0, (gustMs ?: 0.0) - 10))
        return (100 - wind - rain - temp - gust).roundToInt().coerceIn(0, 100)
    }
}

/**
 * Route weather (WP16): forecast at sample points along a route at the time the rider is expected
 * there, and a "better start time" scan. [forecast] returns the fused forecast step at a place
 * and time (the app backs it with the cached multi-point forecast, interpolated); null where it has
 * no data. [precipStepMin] is the interval [ForecastStep.precipitationMm] covers (15 or 60).
 */
class RouteWeatherPlanner(
    private val forecast: (GeoPoint, Long) -> ForecastStep?,
    private val precipStepMin: Int = 15,
) {
    fun plan(route: RoutePolyline, startMs: Long, speed: SpeedModel, sampleCount: Int = 8): RouteWeatherPlan {
        require(sampleCount >= 2) { "need at least 2 samples" }
        val total = route.lengthKm
        val samples = (0 until sampleCount).map { i ->
            val km = total * i / (sampleCount - 1)
            sampleAt(route, i, km, startMs + travelTimeMs(0.0, km, speed))
        }
        val scores = samples.mapNotNull { it.score }
        val heads = samples.mapNotNull { it.headwindMs }
        return RouteWeatherPlan(
            startMs = startMs,
            finishMs = startMs + travelTimeMs(0.0, total, speed),
            samples = samples,
            rideScore = scores.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            meanHeadwindMs = heads.takeIf { it.isNotEmpty() }?.average(),
        )
    }

    fun sampleAt(route: RoutePolyline, index: Int, km: Double, etaMs: Long): RouteSample {
        val p = route.pointAt(km)
        val bearing = route.bearingAt(km)
        val f = forecast(p, etaMs)
        val w = f?.wind10m
        val head = if (w != null && bearing != null) w.headwind(bearing) else null
        val cross = if (w != null && bearing != null) w.crosswind(bearing) else null
        val mmH = f?.precipitationMm?.let { it * 60.0 / precipStepMin }
        return RouteSample(
            index = index,
            distanceKm = km,
            point = p,
            etaMs = etaMs,
            routeBearingDeg = bearing,
            windSpeedMs = w?.speed,
            windFromDeg = w?.directionFromDeg,
            headwindMs = head,
            crosswindMs = cross,
            gustMs = f?.gust10m,
            temperatureC = f?.temperatureC,
            precipitationMm = f?.precipitationMm,
            precipitationMmPerHour = mmH,
            score = RideScore.score(head, cross, f?.gust10m, f?.temperatureC, mmH),
        )
    }

    /** Ride score for each candidate start from [nowMs] to [nowMs] + [horizonH] in [stepMin] steps. */
    fun scan(route: RoutePolyline, nowMs: Long, speed: SpeedModel, horizonH: Int = 12, stepMin: Int = 60, sampleCount: Int = 8): List<RouteWeatherPlan> =
        (0..horizonH * 60 / stepMin).map { plan(route, nowMs + it * stepMin * 60_000L, speed, sampleCount) }

    /**
     * Best start within the next [horizonH] hours if it beats starting now by at least
     * [minImprovement] score points; null otherwise (or without forecast data). Ties go to the
     * earliest start.
     */
    fun betterStart(route: RoutePolyline, nowMs: Long, speed: SpeedModel, horizonH: Int = 12, stepMin: Int = 60, minImprovement: Int = 10): BetterStart? {
        val plans = scan(route, nowMs, speed, horizonH, stepMin)
        val nowScore = plans.first().rideScore
        val best = plans.filter { it.rideScore != null }.maxWithOrNull(compareBy<RouteWeatherPlan> { it.rideScore!! }.thenByDescending { it.startMs }) ?: return null
        val improvement = best.rideScore!! - (nowScore ?: 0)
        if (best.startMs == nowMs || improvement < minImprovement) return null
        return BetterStart(best.startMs, best.rideScore, nowScore, improvement)
    }
}
