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
 * Modified by TrueWind: moved here from karoo-headwind's util/AngleDifference.kt and
 * DataStore.kt (lerpAngle); added normalisation and circular mean helpers.
 */

package io.github.kf6999.truewind.core.math

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Normalises an angle in degrees to [0, 360). NaN and infinities are returned unchanged. */
fun normalizeDegrees(degrees: Double): Double {
    if (!degrees.isFinite()) return degrees
    val r = degrees % 360.0
    val n = if (r < 0.0) r + 360.0 else r
    // -1e-17 % 360 + 360 rounds to exactly 360.0
    return if (n >= 360.0) 0.0 else n
}

/** Normalises an angle in degrees to (-180, 180]. */
fun normalizeSignedDegrees(degrees: Double): Double {
    val n = normalizeDegrees(degrees)
    return if (n > 180.0) n - 360.0 else n
}

/**
 * Signed difference `angle2 - angle1`, wrapped into [-180, 180].
 *
 * Exact behaviour of karoo-headwind's `signedAngleDifference` (kept verbatim so that WP1 is a pure
 * refactor): inputs are reduced with `%`, and a difference of exactly 180 degrees is reported as
 * +180 when `angle1 < angle2` (after reduction) and -180 otherwise.
 */
fun signedAngleDifference(angle1: Double, angle2: Double): Double {
    val a1 = angle1 % 360
    val a2 = angle2 % 360
    var diff = abs(a1 - a2)

    val sign = if (a1 < a2) {
        if (diff > 180.0) -1 else 1
    } else {
        if (diff > 180.0) 1 else -1
    }

    if (diff > 180.0) {
        diff = 360.0 - diff
    }

    return sign * diff
}

/**
 * Linearly interpolates between two angles in degrees along the shorter arc.
 *
 * Verbatim karoo-headwind behaviour.
 *
 * @param start Starting angle in degrees [0-360]
 * @param end Ending angle in degrees [0-360]
 * @param factor Interpolation factor [0-1]
 * @return Interpolated angle in degrees [0-360)
 */
fun lerpAngle(
    start: Double,
    end: Double,
    factor: Double
): Double {
    val normalizedStart = start % 360.0
    val normalizedEnd = end % 360.0

    var diff = normalizedEnd - normalizedStart
    if (diff > 180.0) {
        diff -= 360.0
    } else if (diff < -180.0) {
        diff += 360.0
    }

    var result = normalizedStart + diff * factor

    if (result < 0) {
        result += 360.0
    } else if (result >= 360.0) {
        result -= 360.0
    }

    return result
}

/**
 * Circular (vector) mean of angles in degrees, optionally weighted. Returns null when the input is
 * empty or the resultant vector has (near) zero length, e.g. for 0 and 180 degrees.
 * Never average directions as plain numbers (SPEC section 3.1).
 */
fun circularMeanDegrees(degrees: List<Double>, weights: List<Double>? = null): Double? {
    require(weights == null || weights.size == degrees.size) { "weights must match degrees" }
    if (degrees.isEmpty()) return null
    var s = 0.0
    var c = 0.0
    degrees.forEachIndexed { i, d ->
        val w = weights?.get(i) ?: 1.0
        val r = Math.toRadians(d)
        s += w * sin(r)
        c += w * cos(r)
    }
    if (hypot(s, c) < 1e-9) return null
    return normalizeDegrees(Math.toDegrees(atan2(s, c)))
}

/**
 * One step of circular exponential smoothing: moves [previous] towards [target] by [alpha] (0..1)
 * along the shorter arc. Result in [0, 360).
 */
fun smoothAngle(previous: Double, target: Double, alpha: Double): Double {
    val delta = normalizeSignedDegrees(target - previous)
    return normalizeDegrees(previous + alpha.coerceIn(0.0, 1.0) * delta)
}
