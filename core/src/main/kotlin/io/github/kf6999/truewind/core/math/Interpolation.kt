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
 * Modified by TrueWind: lerp helpers moved here from karoo-headwind's DataStore.kt; added
 * time-factor and vector helpers.
 */

package io.github.kf6999.truewind.core.math

import io.github.kf6999.truewind.core.model.WindVector
import kotlin.math.absoluteValue

fun lerp(
    start: Double,
    end: Double,
    factor: Double
): Double {
    return start + (end - start) * factor
}

/** Integer lerp, truncating towards zero (karoo-headwind behaviour). */
fun lerp(
    start: Int,
    end: Int,
    factor: Double
): Int {
    return (start + (end - start) * factor).toInt()
}

/** Lerp that tolerates missing ends: returns the other end, or null when both are missing. */
fun lerpNullable(
    start: Double?,
    end: Double?,
    factor: Double
): Double? {
    if (start == null && end == null) return null
    if (start == null) return end
    if (end == null) return start

    return start + (end - start) * factor
}

/** Component-wise lerp in u/v. This is the only correct way to interpolate wind in time or space. */
fun lerp(start: WindVector, end: WindVector, factor: Double): WindVector =
    WindVector(lerp(start.u, end.u, factor), lerp(start.v, end.v, factor))

/**
 * Interpolation factor of [t] between [t0] and [t1], clamped to [0, 1]. Returns 0 when
 * `t0 == t1` (karoo-headwind's `lerpWeatherTime` divides by zero there; the app keeps that
 * expression verbatim for WP1 parity, new code should use this function).
 */
fun timeFactor(t: Long, t0: Long, t1: Long): Double {
    val span = (t1 - t0).absoluteValue
    if (span == 0L) return 0.0
    return ((t - t0).toDouble() / span).coerceIn(0.0, 1.0)
}
