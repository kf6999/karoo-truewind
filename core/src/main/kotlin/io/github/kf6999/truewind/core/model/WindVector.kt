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

package io.github.kf6999.truewind.core.model

import io.github.kf6999.truewind.core.math.normalizeDegrees
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Wind velocity in m/s, meteorological components: [u] towards east, [v] towards north. The
 * vector points where the air is going (direction blowing TO). Weather APIs report the direction
 * the wind comes FROM; convert with [fromSpeedDirection] / [directionFromDeg].
 *
 * Compute in u/v everywhere and convert to speed/direction only at the display edge (SPEC 3.1).
 */
data class WindVector(val u: Double, val v: Double) {
    /** Speed in m/s. */
    val speed: Double get() = hypot(u, v)

    /** Direction the wind blows FROM, degrees clockwise from north, [0, 360). 0 for calm. */
    val directionFromDeg: Double get() = if (u == 0.0 && v == 0.0) 0.0 else normalizeDegrees(Math.toDegrees(atan2(-u, -v)))

    /** Direction the wind blows TO, degrees clockwise from north, [0, 360). 0 for calm. */
    val directionToDeg: Double get() = if (u == 0.0 && v == 0.0) 0.0 else normalizeDegrees(Math.toDegrees(atan2(u, v)))

    operator fun plus(other: WindVector) = WindVector(u + other.u, v + other.v)
    operator fun minus(other: WindVector) = WindVector(u - other.u, v - other.v)
    operator fun times(factor: Double) = WindVector(u * factor, v * factor)
    operator fun div(divisor: Double) = WindVector(u / divisor, v / divisor)
    operator fun unaryMinus() = WindVector(-u, -v)

    fun dot(other: WindVector): Double = u * other.u + v * other.v

    /**
     * Component of this wind blowing against a rider travelling on [headingDeg] (degrees clockwise
     * from north). Positive = headwind (into the rider), negative = tailwind.
     */
    fun headwind(headingDeg: Double): Double {
        val h = Math.toRadians(headingDeg)
        return -(u * sin(h) + v * cos(h))
    }

    /**
     * Cross component relative to a rider travelling on [headingDeg]. Positive = wind coming from
     * the rider's right, negative = from the left.
     */
    fun crosswind(headingDeg: Double): Double {
        val h = Math.toRadians(headingDeg)
        return -(u * cos(h) - v * sin(h))
    }

    companion object {
        val ZERO = WindVector(0.0, 0.0)

        /**
         * Builds a vector from a speed (m/s) and the meteorological direction the wind comes FROM
         * (degrees clockwise from north).
         */
        fun fromSpeedDirection(speed: Double, directionFromDeg: Double): WindVector {
            val d = Math.toRadians(directionFromDeg)
            return WindVector(-speed * sin(d), -speed * cos(d))
        }
    }
}
