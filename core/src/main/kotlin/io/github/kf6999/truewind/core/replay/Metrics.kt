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

import io.github.kf6999.truewind.core.math.normalizeSignedDegrees
import io.github.kf6999.truewind.core.model.WindVector
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/* ---------------------------------------------------------------- Metric A (TESTING 3.2) */

/** One (estimate, observation) pair from a held-out station. */
data class WindPair(
    val stationId: String,
    val timeMs: Long,
    val estimate: WindVector,
    val observed: WindVector,
    /** Observation flagged as inside a "shift" window (direction changed > 45 deg within the past hour). */
    val shift: Boolean,
    /** Distance from the held-out station to the nearest remaining station, km (null = none). */
    val nearestRemainingKm: Double?,
)

data class WindErrorStats(
    val n: Int,
    val vectorRmse: Double?,
    val speedMae: Double?,
    val speedBias: Double?,
    /** Circular mean absolute direction error, degrees, only where observed speed >= [DIRECTION_MIN_SPEED]. */
    val directionMae: Double?,
    val nDirection: Int,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "n" to n, "vector_rmse_ms" to vectorRmse, "speed_mae_ms" to speedMae, "speed_bias_ms" to speedBias,
        "direction_mae_deg" to directionMae, "n_direction" to nDirection,
    )

    companion object {
        const val DIRECTION_MIN_SPEED = 2.0

        fun of(pairs: List<WindPair>): WindErrorStats {
            if (pairs.isEmpty()) return WindErrorStats(0, null, null, null, null, 0)
            val vec = sqrt(pairs.sumOf { (it.estimate - it.observed).let { d -> d.u * d.u + d.v * d.v } } / pairs.size)
            val speedErr = pairs.map { it.estimate.speed - it.observed.speed }
            val dirPairs = pairs.filter { it.observed.speed >= DIRECTION_MIN_SPEED && it.estimate.speed > 0.0 }
            val dirMae = dirPairs.takeIf { it.isNotEmpty() }?.map { abs(normalizeSignedDegrees(it.estimate.directionFromDeg - it.observed.directionFromDeg)) }?.average()
            return WindErrorStats(pairs.size, vec, speedErr.map { abs(it) }.average(), speedErr.average(), dirMae, dirPairs.size)
        }
    }
}

object MetricA {
    const val CALM_MAX = 2.0
    const val STRONG_MIN = 8.0
    const val SHIFT_DEG = 45.0
    const val SHIFT_WINDOW_MS = 3_600_000L

    fun regime(p: WindPair): String = when {
        p.observed.speed < CALM_MAX -> "calm"
        p.observed.speed > STRONG_MIN -> "strong"
        else -> "moderate"
    }

    fun distanceBucket(km: Double?): String = when {
        km == null -> "none"
        km < 10 -> "0-10km"
        km < 20 -> "10-20km"
        km < 30 -> "20-30km"
        else -> ">=30km"
    }

    /**
     * Marks each observation of a station whose direction differs by more than 45 degrees from any
     * observation in the preceding hour (both with speed >= 2 m/s, so calm noise does not count).
     */
    fun shiftFlags(times: List<Long>, winds: List<WindVector?>): List<Boolean> = times.indices.map { i ->
        val w = winds[i] ?: return@map false
        if (w.speed < WindErrorStats.DIRECTION_MIN_SPEED) return@map false
        var j = i - 1
        var shift = false
        while (j >= 0 && times[i] - times[j] <= SHIFT_WINDOW_MS) {
            val o = winds[j]
            if (o != null && o.speed >= WindErrorStats.DIRECTION_MIN_SPEED &&
                abs(normalizeSignedDegrees(w.directionFromDeg - o.directionFromDeg)) > SHIFT_DEG) { shift = true; break }
            j--
        }
        shift
    }

    fun summarize(pairs: List<WindPair>): Map<String, Any?> = linkedMapOf(
        "overall" to WindErrorStats.of(pairs).toMap(),
        "regimes" to linkedMapOf(
            "calm" to WindErrorStats.of(pairs.filter { regime(it) == "calm" }).toMap(),
            "moderate" to WindErrorStats.of(pairs.filter { regime(it) == "moderate" }).toMap(),
            "strong" to WindErrorStats.of(pairs.filter { regime(it) == "strong" }).toMap(),
            "shift" to WindErrorStats.of(pairs.filter { it.shift }).toMap(),
        ),
        "by_nearest_remaining_station" to pairs.groupBy { distanceBucket(it.nearestRemainingKm) }.toSortedMap()
            .mapValues { WindErrorStats.of(it.value).toMap() },
        "stations" to pairs.map { it.stationId }.distinct().sorted(),
        "by_station" to pairs.groupBy { it.stationId }.toSortedMap().mapValues { WindErrorStats.of(it.value).toMap() },
    )
}

/* ---------------------------------------------------------------- Metric B (TESTING 3.3) */

/** Rider/bike physical parameters for the power balance. */
data class PowerModel(
    val massKg: Double,
    val cdA: Double,
    val crr: Double,
    val drivetrainEfficiency: Double = 0.975,
)

object VirtualElevation {
    const val G = 9.80665
    private const val MAX_SLOPE = 0.3

    /** Air density from altitude (ISA pressure) and temperature, dry air. */
    fun airDensity(altitudeM: Double?, temperatureC: Double?): Double {
        val p = 101_325.0 * (1 - 2.25577e-5 * (altitudeM ?: 0.0)).pow(5.25588)
        val t = (temperatureC ?: 15.0) + 273.15
        return p / (287.05 * t)
    }

    /** Ground speed derivative over +-1 sample (central difference), m/s^2. */
    fun accelerations(samples: List<RideSample>): DoubleArray = DoubleArray(samples.size) { i ->
        val a = samples[maxOf(0, i - 1)]; val b = samples[minOf(samples.size - 1, i + 1)]
        val dt = (b.timeMs - a.timeMs) / 1000.0
        if (dt <= 0) 0.0 else ((b.speedMps ?: 0.0) - (a.speedMps ?: 0.0)) / dt
    }

    /**
     * Chung virtual-elevation increments: dh_i = s_i * v_i * dt_i with
     * s = P*eta/(m g v) - Crr - a/g - rho CdA va|va| / (2 m g), va = v + headwind.
     */
    fun increments(samples: List<RideSample>, headwind: DoubleArray, model: PowerModel, rho: DoubleArray, accel: DoubleArray): DoubleArray =
        DoubleArray(samples.size) { i ->
            if (i == 0) return@DoubleArray 0.0
            val s = samples[i]
            val v = s.speedMps ?: 0.0
            val dt = ((s.timeMs - samples[i - 1].timeMs) / 1000.0).coerceIn(0.0, 10.0)
            if (v < 1.0 || dt == 0.0) return@DoubleArray 0.0
            val p = s.powerW ?: 0.0
            val va = v + headwind[i]
            val slope = p * model.drivetrainEfficiency / (model.massKg * G * v) - model.crr - accel[i] / G -
                rho[i] * model.cdA * va * abs(va) / (2 * model.massKg * G)
            slope.coerceIn(-MAX_SLOPE, MAX_SLOPE) * v * dt
        }

    /**
     * RMSE of virtual vs. recorded elevation over rolling windows ([windowS], stride [strideS]),
     * re-anchored at each window start and with the per-window mean error removed. Returns the
     * root of the mean squared per-window RMSE, or null when no window qualifies.
     */
    fun windowRmse(samples: List<RideSample>, dh: DoubleArray, windowS: Int = 600, strideS: Int = 60): Double? {
        if (samples.size < 2) return null
        val cum = DoubleArray(samples.size).also { for (i in 1 until it.size) it[i] = it[i - 1] + dh[i] }
        val windows = mutableListOf<Double>()
        var start = 0
        val t0 = samples.first().timeMs
        var nextStartMs = t0
        while (start < samples.size) {
            while (start < samples.size && samples[start].timeMs < nextStartMs) start++
            if (start >= samples.size) break
            val endMs = samples[start].timeMs + windowS * 1000L
            var end = start
            while (end + 1 < samples.size && samples[end + 1].timeMs <= endMs) end++
            if (samples[end].timeMs - samples[start].timeMs >= windowS * 800L) {
                val alt0 = samples[start].altitudeM
                if (alt0 != null) {
                    val errs = (start..end).mapNotNull { i -> samples[i].altitudeM?.let { (cum[i] - cum[start]) - (it - alt0) } }
                    if (errs.size > 10) {
                        val mean = errs.average()
                        windows += errs.sumOf { (it - mean) * (it - mean) } / errs.size
                    }
                }
            }
            nextStartMs = samples[start].timeMs + strideS * 1000L
        }
        return if (windows.isEmpty()) null else sqrt(windows.average())
    }

    /**
     * Fits CdA and Crr on the [windowS]-second stretch starting at [startIdx], assuming calm air,
     * by grid search minimising the drift of virtual from recorded elevation. Shared by every
     * configuration of a ride so methods are compared with identical physics (TESTING 3.3).
     */
    fun fitCdaCrr(samples: List<RideSample>, startIdx: Int, windowS: Int, massKg: Double, rho: DoubleArray, accel: DoubleArray): Pair<Double, Double> {
        val endMs = samples[startIdx].timeMs + windowS * 1000L
        var end = startIdx
        while (end + 1 < samples.size && samples[end + 1].timeMs <= endMs) end++
        val sub = samples.subList(startIdx, end + 1)
        val subRho = rho.copyOfRange(startIdx, end + 1)
        val subAcc = accel.copyOfRange(startIdx, end + 1)
        val zero = DoubleArray(sub.size)
        var best = Triple(0.32, 0.005, Double.MAX_VALUE)
        var cda = 0.15
        while (cda <= 0.60 + 1e-9) {
            var crr = 0.002
            while (crr <= 0.012 + 1e-9) {
                val dh = increments(sub, zero, PowerModel(massKg, cda, crr), subRho, subAcc)
                var cum = 0.0; var se = 0.0; var n = 0
                val alt0 = sub.first().altitudeM ?: 0.0
                for (i in sub.indices) {
                    cum += dh[i]
                    val alt = sub[i].altitudeM ?: continue
                    val e = cum - (alt - alt0); se += e * e; n++
                }
                val score = if (n == 0) Double.MAX_VALUE else se / n
                if (score < best.third) best = Triple(cda, crr, score)
                crr += 0.0005
            }
            cda += 0.005
        }
        return best.first to best.second
    }

    /**
     * Alternative to [fitCdaCrr] (diagnostic): Crr fixed at [crr], CdA fitted over the whole ride
     * on the same windowed virtual-elevation objective as Metric B, assuming still air. Over a
     * whole ride head- and tailwind stretches largely cancel, where a 20-minute "calm" window often
     * does not; with Crr fixed the fit is no longer ill-conditioned (CdA and Crr trade off unless
     * the speed varies a lot). Still identical for every configuration.
     */
    fun fitCdaWholeRide(samples: List<RideSample>, massKg: Double, rho: DoubleArray, accel: DoubleArray, crr: Double = 0.005): Pair<Double, Double> {
        val zero = DoubleArray(samples.size)
        var best = 0.32 to Double.MAX_VALUE
        var cda = 0.15
        while (cda <= 0.60 + 1e-9) {
            val r = windowRmse(samples, increments(samples, zero, PowerModel(massKg, cda, crr), rho, accel)) ?: Double.MAX_VALUE
            if (r < best.second) best = cda to r
            cda += 0.005
        }
        return best.first to crr
    }

    /**
     * Headwind measured from the power balance (SPEC 4.6), m/s, null for invalid samples
     * (P < 80 W or no cadence, v < 4 m/s, |a| >= 0.5 m/s^2, |grade| >= 8%).
     */
    fun measuredHeadwind(samples: List<RideSample>, model: PowerModel, rho: DoubleArray, accel: DoubleArray): Array<Double?> {
        val grade = grades(samples)
        return Array(samples.size) { i ->
            val s = samples[i]
            val v = s.speedMps ?: return@Array null
            val p = s.powerW ?: return@Array null
            if (p < 80.0 || (s.cadenceRpm != null && s.cadenceRpm <= 0.0) || v < 4.0 || abs(accel[i]) >= 0.5 || abs(grade[i]) >= 0.08) return@Array null
            val theta = kotlin.math.atan(grade[i])
            val fAero = p * model.drivetrainEfficiency / v - model.massKg * G * (sin(theta) + model.crr * cos(theta)) - model.massKg * accel[i]
            val va = sign(fAero) * sqrt(abs(fAero) / (0.5 * rho[i] * model.cdA))
            va - v
        }
    }

    /** Grade from recorded altitude over +-5 samples, as a fraction. */
    fun grades(samples: List<RideSample>): DoubleArray = DoubleArray(samples.size) { i ->
        samples[i].gradePct?.let { return@DoubleArray it / 100.0 }
        val a = samples[maxOf(0, i - 5)]; val b = samples[minOf(samples.size - 1, i + 5)]
        val d = Geo.distanceKm(a.point, b.point) * 1000
        val dz = (b.altitudeM ?: return@DoubleArray 0.0) - (a.altitudeM ?: return@DoubleArray 0.0)
        if (d < 10) 0.0 else (dz / d).coerceIn(-0.3, 0.3)
    }

    /**
     * Forward prediction: the rider-height wind displayed at t, projected on the rider's future
     * course, against the mean measured headwind over (t+60 s, t+300 s]. RMSE in m/s, evaluated
     * every [strideS] seconds where at least 30 valid measurements exist.
     */
    fun forwardPredictionRmse(samples: List<RideSample>, outputs: List<ReplayOutput>, measured: Array<Double?>, course: Array<Double?>, strideS: Int = 30): Pair<Double?, Int> {
        val errs = mutableListOf<Double>()
        var nextMs = samples.first().timeMs
        var j0 = 0
        for (i in samples.indices) {
            if (samples[i].timeMs < nextMs) continue
            nextMs = samples[i].timeMs + strideS * 1000L
            val rider = outputs[i].rider ?: continue
            val from = samples[i].timeMs + 60_000L; val to = samples[i].timeMs + 300_000L
            if (j0 < i) j0 = i
            while (j0 < samples.size && samples[j0].timeMs <= from) j0++
            var j = j0
            var sumP = 0.0; var sumM = 0.0; var n = 0
            while (j < samples.size && samples[j].timeMs <= to) {
                val m = measured[j]; val c = course[j]
                if (m != null && c != null) { sumM += m; sumP += rider.headwind(c); n++ }
                j++
            }
            if (n >= 30) errs += (sumP / n - sumM / n).let { it * it }
        }
        return (if (errs.isEmpty()) null else sqrt(errs.average())) to errs.size
    }

    /** Ground-truth course from positions +-[halfS] samples apart, only while v >= 3 m/s. */
    fun trueCourse(samples: List<RideSample>, halfS: Int = 3): Array<Double?> = Array(samples.size) { i ->
        if ((samples[i].speedMps ?: 0.0) < 3.0) return@Array null
        val a = samples[maxOf(0, i - halfS)]; val b = samples[minOf(samples.size - 1, i + halfS)]
        if (Geo.distanceKm(a.point, b.point) < 0.005) null else Geo.bearingDeg(a.point, b.point)
    }
}

/* ---------------------------------------------------------------- Metric C (TESTING 3.4) */

object HeadingMetrics {
    /** Circular standard deviation of angles, degrees. */
    fun circularStdDeg(degrees: List<Double>): Double {
        var s = 0.0; var c = 0.0
        degrees.forEach { s += sin(Math.toRadians(it)); c += cos(Math.toRadians(it)) }
        val r = (hypot(s, c) / degrees.size).coerceIn(1e-12, 1.0)
        return Math.toDegrees(sqrt(-2 * ln(r)))
    }

    /**
     * Jitter of the displayed relative wind angle in 10 s windows while v < 2 m/s. Returns the mean
     * and 95th percentile of the per-window circular standard deviation, and the window count.
     */
    fun stoppedJitter(samples: List<RideSample>, outputs: List<ReplayOutput>, windowS: Int = 10): Triple<Double?, Double?, Int> {
        val stds = mutableListOf<Double>()
        var i = 0
        while (i < samples.size) {
            val endMs = samples[i].timeMs + windowS * 1000L
            var j = i
            val angles = mutableListOf<Double>()
            var allSlow = true
            while (j < samples.size && samples[j].timeMs < endMs) {
                if ((samples[j].speedMps ?: 0.0) >= 2.0) allSlow = false
                outputs[j].relativeDirectionDeg?.let { angles += it }
                j++
            }
            if (allSlow && angles.size >= windowS / 2) stds += circularStdDeg(angles)
            i = j
        }
        if (stds.isEmpty()) return Triple(null, null, 0)
        val sorted = stds.sorted()
        return Triple(stds.average(), sorted[((sorted.size - 1) * 0.95).toInt()], stds.size)
    }

    /**
     * Lag after turns > 60 deg: for each turn in the true course (change over 10 s), the time from
     * the true course settling within 15 deg of its new value to the displayed heading doing the
     * same (and staying 3 s). Returns (mean lag s, p95 lag s, turns).
     */
    fun turnLag(samples: List<RideSample>, outputs: List<ReplayOutput>, course: Array<Double?>): Triple<Double?, Double?, Int> {
        val lags = mutableListOf<Double>()
        var i = 0
        val n = samples.size
        fun idxAt(ms: Long, from: Int): Int { var k = from; while (k < n - 1 && samples[k].timeMs < ms) k++; return k }
        while (i < n) {
            val c0 = course[i]
            val j = idxAt(samples[i].timeMs + 10_000, i)
            val c1 = course[j]
            if (c0 != null && c1 != null && abs(normalizeSignedDegrees(c1 - c0)) > 60.0) {
                val k = idxAt(samples[j].timeMs + 5_000, j)
                val newCourse = course[k] ?: c1
                fun settled(at: Int, value: (Int) -> Double?): Long? {
                    var m = at
                    while (m < n && samples[m].timeMs - samples[at].timeMs < 60_000) {
                        val ok = (m until minOf(n, idxAt(samples[m].timeMs + 3_000, m) + 1)).all { q ->
                            value(q)?.let { abs(normalizeSignedDegrees(it - newCourse)) < 15.0 } ?: false
                        }
                        if (ok) return samples[m].timeMs
                        m++
                    }
                    return null
                }
                val trueSettle = settled(i) { course[it] }
                val shownSettle = settled(i) { outputs[it].headingDeg }
                if (trueSettle != null) lags += ((shownSettle ?: (samples[i].timeMs + 60_000)) - trueSettle).coerceAtLeast(0L) / 1000.0
                i = idxAt(samples[j].timeMs + 20_000, j)
                continue
            }
            i++
        }
        if (lags.isEmpty()) return Triple(null, null, 0)
        val sorted = lags.sorted()
        return Triple(lags.average(), sorted[((sorted.size - 1) * 0.95).toInt()], lags.size)
    }
}

/** Exponential weight helper shared by synthesiser and tests. */
fun halfLifeDecay(dtMs: Long, halfLifeMs: Long): Double = exp(-ln(2.0) * dtMs / halfLifeMs)

/** atan2 in degrees, [0, 360). */
fun bearingOf(dx: Double, dy: Double): Double = io.github.kf6999.truewind.core.math.normalizeDegrees(Math.toDegrees(atan2(dx, dy)))
