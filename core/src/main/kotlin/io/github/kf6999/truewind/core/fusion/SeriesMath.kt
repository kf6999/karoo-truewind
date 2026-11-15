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
import io.github.kf6999.truewind.core.api.ForecastStep
import io.github.kf6999.truewind.core.math.haversineKm
import io.github.kf6999.truewind.core.math.lerp
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector

/** Time interpolation helpers over one member's [ForecastSeries]. All linear in u/v. */
internal object SeriesMath {
    /**
     * Wind at [t], linearly interpolated between the neighbouring steps that have wind; held
     * constant up to [holdMs] before the first / after the last such step; null outside that.
     */
    fun windAt(series: ForecastSeries, t: Long, holdMs: Long): WindVector? {
        var prev: ForecastStep? = null
        var next: ForecastStep? = null
        for (s in series.steps) {
            if (s.wind10m == null) continue
            if (s.timeMs <= t) prev = s else { next = s; break }
        }
        val p = prev
        val n = next
        return when {
            p != null && n != null -> lerp(p.wind10m!!, n.wind10m!!, (t - p.timeMs).toDouble() / (n.timeMs - p.timeMs))
            p != null -> if (t - p.timeMs <= holdMs) p.wind10m else null
            n != null -> if (n.timeMs - t <= holdMs) n.wind10m else null
            else -> null
        }
    }

    /**
     * A scalar at [t] from the steps where [select] is non-null: interpolated between neighbours,
     * otherwise the nearest value (scalars such as pressure arrive only on the `current` step).
     */
    inline fun scalarAt(series: ForecastSeries, t: Long, select: (ForecastStep) -> Double?): Double? {
        var prevT = 0L
        var prevV: Double? = null
        for (s in series.steps) {
            val v = select(s) ?: continue
            if (!v.isFinite()) continue
            if (s.timeMs <= t) {
                prevT = s.timeMs
                prevV = v
            } else {
                val pv = prevV ?: return v
                return lerp(pv, v, (t - prevT).toDouble() / (s.timeMs - prevT))
            }
        }
        return prevV
    }

    /** Weather code of the step nearest to [t] that has one. */
    fun weatherCodeAt(series: ForecastSeries, t: Long): Int? {
        var best: Int? = null
        var bestDt = Long.MAX_VALUE
        for (s in series.steps) {
            val c = s.weatherCode ?: continue
            val dt = kotlin.math.abs(s.timeMs - t)
            if (dt < bestDt) {
                bestDt = dt
                best = c
            }
        }
        return best
    }

    /** The series of [memberId] nearest to [p] within [maxKm], or null. */
    fun seriesNear(series: List<ForecastSeries>, memberId: String, p: GeoPoint, maxKm: Double): ForecastSeries? {
        var best: ForecastSeries? = null
        var bestD = Double.MAX_VALUE
        for (s in series) {
            if (s.memberId != memberId) continue
            val d = haversineKm(s.location, p)
            if (d < bestD) {
                bestD = d
                best = s
            }
        }
        return if (bestD <= maxKm) best else null
    }
}
