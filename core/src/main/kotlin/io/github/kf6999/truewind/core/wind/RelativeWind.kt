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
 * Modified by TrueWind: the head/cross decomposition karoo-headwind repeats in several data
 * types, collected in one place.
 */

package io.github.kf6999.truewind.core.wind

import io.github.kf6999.truewind.core.math.normalizeDegrees
import io.github.kf6999.truewind.core.math.signedAngleDifference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Relative wind in the convention karoo-headwind's data types use.
 *
 * The "relative bearing" is `signedAngleDifference(heading, windFrom + 180)`: the direction the wind
 * blows TO relative to the rider, in [-180, 180]. 0 = pure tailwind, +-180 = pure headwind. The
 * headwind arrow, FIT fields and the 1x1 fields are all derived from it.
 */
object RelativeWind {
    fun relativeBearing(headingDeg: Double, windFromDeg: Double): Double =
        signedAngleDifference(headingDeg, windFromDeg + 180)

    /** Headwind component, m/s, + into the rider. Same expression as karoo-headwind. */
    fun headwind(relativeBearing: Double, windSpeed: Double): Double =
        cos((relativeBearing + 180) * PI / 180.0) * windSpeed

    /** Crosswind component, m/s, + from the rider's right. */
    fun crosswind(relativeBearing: Double, windSpeed: Double): Double =
        sin((relativeBearing + 180) * PI / 180.0) * windSpeed

    /** Direction the wind comes from relative to the heading, 0 = dead ahead, clockwise, [0, 360). */
    fun relativeDirectionFrom(relativeBearing: Double): Double = normalizeDegrees(relativeBearing + 180)
}
