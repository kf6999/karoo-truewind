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
import io.github.kf6999.truewind.core.api.Observation
import io.github.kf6999.truewind.core.math.KM_PER_DEGREE
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector

object FusionFixtures {
    const val MIN = 60_000L
    const val STEP = 15 * MIN

    /** A fixed reference time on a 15-min boundary (2026-11-15T12:00Z). */
    const val T0 = 1_794_744_000_000L

    val HOME = GeoPoint(37.0, -122.0)

    /** [p] moved [eastKm] east and [northKm] north. */
    fun offset(p: GeoPoint, eastKm: Double, northKm: Double = 0.0) = GeoPoint(
        p.lat + northKm / KM_PER_DEGREE,
        p.lon + eastKm / (KM_PER_DEGREE * Math.cos(Math.toRadians(p.lat))),
    )

    /** 15-min series from [from] to [to] (inclusive) with wind given by [wind]. */
    fun series(
        member: String,
        location: GeoPoint,
        from: Long = T0 - 30 * MIN,
        to: Long = T0 + 4 * 60 * MIN,
        step: Long = STEP,
        gust: ((Long) -> Double?)? = null,
        temperature: ((Long) -> Double?)? = null,
        pressure: ((Long) -> Double?)? = null,
        wind: (Long) -> WindVector?,
    ): ForecastSeries {
        val steps = ArrayList<ForecastStep>()
        var t = from
        while (t <= to) {
            steps += ForecastStep(
                timeMs = t, wind10m = wind(t), gust10m = gust?.invoke(t), temperatureC = temperature?.invoke(t),
                surfacePressureHpa = pressure?.invoke(t),
            )
            t += step
        }
        return ForecastSeries(member, location, null, steps)
    }

    fun obs(
        id: String,
        location: GeoPoint,
        timeMs: Long,
        wind: WindVector?,
        speed: Double? = wind?.speed,
        reliability: Double = 1.0,
    ) = Observation(
        stationId = id, source = "awc", location = location, elevationM = null, timeMs = timeMs,
        wind = wind, speed = speed, reliability = reliability, variableDirection = wind == null,
    )

    fun assertClose(expected: Double, actual: Double, tol: Double = 1e-6, msg: String = "") {
        kotlin.test.assertTrue(kotlin.math.abs(expected - actual) <= tol, "$msg expected $expected ± $tol, got $actual")
    }
}
