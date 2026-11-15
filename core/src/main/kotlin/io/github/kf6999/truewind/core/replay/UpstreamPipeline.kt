/*
 * Copyright 2024-2026 karoo-headwind contributors
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
 *
 * Modified by TrueWind: an emulation of karoo-headwind's fetch policy and interpolation
 * (HeadingFlow.kt rounding, KarooHeadwindExtension.kt hourly refetch, DataStore.kt lerp) written
 * against the replay archives, for use as the accuracy baseline.
 */

package io.github.kf6999.truewind.core.replay

import io.github.kf6999.truewind.core.api.ForecastRequest
import io.github.kf6999.truewind.core.api.ForecastSeries
import io.github.kf6999.truewind.core.math.lerp
import io.github.kf6999.truewind.core.math.lerpAngle
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import kotlin.math.roundToInt

/**
 * The `upstream` baseline (TESTING 3.5): emulates Headwind 2026-10 (`c9be073`).
 *
 * - Open-Meteo `best_match` only, hourly steps.
 * - Position rounded to a 3 km grid (`GpsCoordinates.round`, default `RoundLocationSetting.KM_3`).
 * - Refetch when the rounded position changes, or one hour after the last fetch
 *   (`transformLatest { while (true) { emit; delay(1.hours) } }`).
 * - Between hourly steps, speed is lerped linearly and direction with `lerpAngle` (upstream
 *   interpolates speed and angle separately, not u/v).
 * - The 10 m wind is shown as the rider's wind; heading is the raw GPS course (`LOC_BEARING`),
 *   absent when the receiver reports no bearing.
 * - Fixes with accuracy >= 500 m are ignored (`gpsCoordinateFlow`).
 */
class UpstreamPipeline(
    private val env: ReplayEnvironment,
    private val roundKm: Double = 3.0,
    private val refetchMs: Long = 3_600_000L,
    private val member: String = BEST_MATCH,
) : ReplayPipeline {
    private var lastFetchMs: Long? = null
    private var lastCell: GeoPoint? = null
    private var series: ForecastSeries? = null
    private var position: GeoPoint? = null

    override fun tick(sample: RideSample): ReplayOutput {
        val now = env.clock.nowMs()
        if (sample.gpsAccuracyM == null || sample.gpsAccuracyM < 500.0) {
            position = GeoPoint(sample.lat, sample.lon)
        }
        val cell = position?.let { round(it) }
        if (cell != null && (cell != lastCell || lastFetchMs == null || now - lastFetchMs!! >= refetchMs)) {
            val result = runSync { env.hourly.fetch(ForecastRequest(points = listOf(cell), nowMs = now, members = listOf(member))) }
            result.series.firstOrNull()?.let { series = it }
            lastCell = cell
            lastFetchMs = now
        }
        val wind = series?.let { upstreamWindAt(it, now) }
        return ReplayOutput(
            timeMs = now,
            model10m = wind,
            rider = wind,
            headingDeg = sample.gpsBearingDeg,
            members = listOf(member),
        )
    }

    private fun round(p: GeoPoint) = GeoPoint(roundDegrees(p.lat, roundKm), roundDegrees(p.lon, roundKm))

    companion object {
        const val BEST_MATCH = "open-meteo:best_match"

        /** Verbatim `GpsCoordinates.roundDegrees`. */
        fun roundDegrees(degrees: Double, km: Double): Double {
            val nkm = degrees * 111
            val rounded = (nkm / km).roundToInt() * km
            return rounded / 111
        }

        /** Speed lerp + `lerpAngle` between the bracketing hourly steps; holds the last step after the end. */
        fun upstreamWindAt(series: ForecastSeries, timeMs: Long): WindVector? {
            val steps = series.steps.filter { it.wind10m != null }
            if (steps.isEmpty()) return null
            val after = steps.indexOfFirst { it.timeMs > timeMs }
            if (after == 0) return steps.first().wind10m
            if (after < 0) return steps.last().wind10m
            val a = steps[after - 1]; val b = steps[after]
            val f = ((timeMs - a.timeMs).toDouble() / (b.timeMs - a.timeMs)).coerceIn(0.0, 1.0)
            val wa = a.wind10m!!; val wb = b.wind10m!!
            val speed = lerp(wa.speed, wb.speed, f)
            val dir = lerpAngle(wa.directionFromDeg, wb.directionFromDeg, f)
            return WindVector.fromSpeedDirection(speed, dir)
        }
    }
}

object UpstreamConfig : ReplayConfig {
    override val name = "upstream"
    override val implementation = mapOf("all" to "upstream-emulation")
    override fun create(env: ReplayEnvironment): ReplayPipeline = UpstreamPipeline(env)
}
