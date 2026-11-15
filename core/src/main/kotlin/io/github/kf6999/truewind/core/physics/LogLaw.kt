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

import io.github.kf6999.truewind.core.model.WindVector
import kotlin.math.ln

/**
 * Terrain exposure presets for rider-height downscaling (SPEC 4.4).
 *
 * [z0] is the aerodynamic roughness length in metres; null means the rider is inside the canopy
 * (urban street canyon, forest) where the log law is not valid and the ratio is clamped instead.
 */
enum class Exposure(val z0: Double?) {
    /** Coast, plains, airfield. */
    OPEN(0.03),

    /** Farmland, hedges. Default. */
    MIXED(0.10),

    /** Suburb, tree-lined road. */
    SHELTERED(0.30),

    /** Urban / forest: inside the canopy, fixed ratio [LogLaw.CANOPY_RATIO]. */
    URBAN_FOREST(null);

    companion object {
        val DEFAULT = MIXED
    }
}

/** Log-law (neutral surface layer) wind profile helpers. All speeds in m/s, heights in metres. */
object LogLaw {
    const val REFERENCE_HEIGHT_M = 10.0
    const val DEFAULT_RIDER_HEIGHT_M = 1.2
    const val MIN_RIDER_HEIGHT_M = 0.9
    const val MAX_RIDER_HEIGHT_M = 1.5

    /** Lower clamp on any ratio, and the fixed ratio inside the canopy (SPEC 4.4 table). */
    const val CANOPY_RATIO = 0.30

    /** Gusts penetrate further towards the ground: gust ratio = ratio + this, capped at 1. */
    const val GUST_RATIO_BONUS = 0.10

    /**
     * Raw log-law ratio `ln(z / z0) / ln(zRef / z0)` without clamping. Requires z > z0 and zRef > z0.
     */
    fun profileRatio(z: Double, z0: Double, zRef: Double = REFERENCE_HEIGHT_M): Double {
        require(z0 > 0.0) { "z0 must be positive" }
        require(z > z0 && zRef > z0) { "heights must be above the roughness length" }
        return ln(z / z0) / ln(zRef / z0)
    }

    /**
     * Ratio of wind at rider height [riderHeightM] to the 10 m wind for [exposure], clamped to
     * [[CANOPY_RATIO], 1]. Rider height is clamped to the settings range 0.9..1.5 m.
     */
    fun ratio(exposure: Exposure, riderHeightM: Double = DEFAULT_RIDER_HEIGHT_M): Double {
        val z0 = exposure.z0 ?: return CANOPY_RATIO
        val z = riderHeightM.coerceIn(MIN_RIDER_HEIGHT_M, MAX_RIDER_HEIGHT_M)
        if (z <= z0) return CANOPY_RATIO
        return profileRatio(z, z0).coerceIn(CANOPY_RATIO, 1.0)
    }

    /** Gust ratio: [ratio] plus 0.1, capped at 1.0 (SPEC 4.4). */
    fun gustRatio(ratio: Double): Double = (ratio + GUST_RATIO_BONUS).coerceAtMost(1.0)

    /** Downscales a 10 m wind vector to rider height; direction is unchanged. */
    fun toRiderHeight(wind10m: WindVector, ratio: Double): WindVector = wind10m * ratio

    /**
     * Normalises a speed measured at [sensorHeightM] to 10 m over roughness [z0] (for observation
     * stations with a non-standard anemometer height). Heights at or below z0 return the input.
     */
    fun toTenMetres(speed: Double, sensorHeightM: Double, z0: Double = Exposure.OPEN.z0!!): Double {
        if (sensorHeightM <= z0) return speed
        return speed / profileRatio(sensorHeightM, z0)
    }
}
