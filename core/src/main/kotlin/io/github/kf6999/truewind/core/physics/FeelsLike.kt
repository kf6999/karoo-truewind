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

package io.github.kf6999.truewind.core.physics

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * "Feels like" temperature (SPEC section 5): wind chill below 10 C using the rider's air speed,
 * heat index above 27 C, otherwise the air temperature.
 */
object FeelsLike {
    const val WIND_CHILL_MAX_C = 10.0
    const val HEAT_INDEX_MIN_C = 27.0

    /** Wind chill formula is defined from 4.8 km/h upwards. */
    private const val WIND_CHILL_MIN_KMH = 4.8

    /**
     * @param airSpeedMps rider air speed (ground speed + headwind), m/s; negative values use magnitude
     * @param relativeHumidityPct needed for heat index; null skips the heat-index branch
     */
    fun feelsLikeC(tempC: Double, airSpeedMps: Double, relativeHumidityPct: Double?): Double = when {
        tempC < WIND_CHILL_MAX_C -> windChillC(tempC, abs(airSpeedMps) * 3.6)
        tempC > HEAT_INDEX_MIN_C && relativeHumidityPct != null -> heatIndexC(tempC, relativeHumidityPct)
        else -> tempC
    }

    /** North American / UK wind chill index (2001), speed in km/h. Returns [tempC] below 4.8 km/h. */
    fun windChillC(tempC: Double, speedKmh: Double): Double {
        if (speedKmh < WIND_CHILL_MIN_KMH) return tempC
        val v = speedKmh.pow(0.16)
        return (13.12 + 0.6215 * tempC - 11.37 * v + 0.3965 * tempC * v).coerceAtMost(tempC)
    }

    /** NWS heat index (Rothfusz regression with the NWS adjustments), in C. */
    fun heatIndexC(tempC: Double, relativeHumidityPct: Double): Double {
        val t = tempC * 9.0 / 5.0 + 32.0
        val rh = relativeHumidityPct.coerceIn(0.0, 100.0)
        val simple = 0.5 * (t + 61.0 + (t - 68.0) * 1.2 + rh * 0.094)
        val hiF = if ((simple + t) / 2.0 < 80.0) simple else {
            var hi = -42.379 + 2.04901523 * t + 10.14333127 * rh - 0.22475541 * t * rh -
                0.00683783 * t * t - 0.05481717 * rh * rh + 0.00122874 * t * t * rh +
                0.00085282 * t * rh * rh - 0.00000199 * t * t * rh * rh
            if (rh < 13.0 && t in 80.0..112.0) {
                hi -= ((13.0 - rh) / 4.0) * sqrt((17.0 - abs(t - 95.0)) / 17.0)
            } else if (rh > 85.0 && t in 80.0..87.0) {
                hi += ((rh - 85.0) / 10.0) * ((87.0 - t) / 5.0)
            }
            hi
        }
        return (hiF - 32.0) * 5.0 / 9.0
    }
}
