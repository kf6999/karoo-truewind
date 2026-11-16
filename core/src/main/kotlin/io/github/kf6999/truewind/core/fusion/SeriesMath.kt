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
import io.github.kf6999.truewind.core.math.lerpKeepingSpeed
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector

/** Time interpolation helpers over one member's [ForecastSeries]. Wind is interpolated with [lerpKeepingSpeed] (speed linear, direction from u/v). */
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
            p != null && n != null -> lerpKeepingSpeed(p.wind10m!!, n.wind10m!!, (t - p.timeMs).toDouble() / (n.timeMs - p.timeMs))
            p != null -> if (t - p.timeMs <= holdMs) p.wind10m else null
            n != null -> if (n.timeMs - t <= holdMs) n.wind10m else null
            else -> null
        }
    }

    /** A member's wind at [t] as fusion uses it (hold, optional anchor grid). */
    fun memberWindAt(series: ForecastSeries, t: Long, config: FusionConfig): WindVector? =
        if (config.memberAnchorStepMs > 0L) anchoredWindAt(series, t, config.memberHoldMs, config.memberAnchorStepMs)
        else windAt(series, t, config.memberHoldMs)

    /**
     * Interpolates only between steps on the [anchorMs] grid (e.g. the hour), ignoring the
     * sub-hourly steps in between. When no anchor step precedes [t] in the series (the response
     * starts 30 min back), the earliest step at or before [t] is used as the left end instead;
     * with no anchor after [t], plain [windAt].
     */
    fun anchoredWindAt(series: ForecastSeries, t: Long, holdMs: Long, anchorMs: Long): WindVector? {
        var prev: ForecastStep? = null
        var firstBefore: ForecastStep? = null
        var next: ForecastStep? = null
        for (s in series.steps) {
            if (s.wind10m == null) continue
            if (s.timeMs <= t) {
                if (firstBefore == null) firstBefore = s
                if (s.timeMs % anchorMs == 0L) prev = s
            } else if (s.timeMs % anchorMs == 0L) {
                next = s
                break
            }
        }
        val left = prev ?: firstBefore
        val n = next
        if (left == null || n == null) return windAt(series, t, holdMs)
        if (left.timeMs == t) return left.wind10m
        return lerpKeepingSpeed(left.wind10m!!, n.wind10m!!, (t - left.timeMs).toDouble() / (n.timeMs - left.timeMs))
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
