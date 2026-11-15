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

import kotlin.math.exp

/** Moist-air density and pressure helpers (SPEC 4.4). Pressures in hPa, temperatures in degrees C. */
object AirDensity {
    /** Specific gas constant of dry air, J/(kg K). */
    const val R_DRY = 287.05

    /** Specific gas constant of water vapour, J/(kg K). */
    const val R_VAPOUR = 461.5

    const val G = 9.80665
    const val KELVIN = 273.15

    /** Standard atmosphere lapse rate, K/m. */
    const val LAPSE_RATE = 0.0065

    const val STANDARD = 1.225
    const val STANDARD_PRESSURE_HPA = 1013.25

    /** Plausible range; values outside fall back to [STANDARD]. */
    private const val MIN_PLAUSIBLE = 0.5
    private const val MAX_PLAUSIBLE = 1.6

    /** Saturation vapour pressure over water, hPa (Buck 1996). */
    fun saturationVapourPressureHpa(tempC: Double): Double =
        6.1121 * exp((18.678 - tempC / 234.5) * (tempC / (257.14 + tempC)))

    /**
     * Moist-air density `rho = p_d / (R_d T) + p_v / (R_v T)`, kg/m3.
     *
     * @param pressureHpa total (station) pressure at the rider
     * @param relativeHumidityPct 0..100; null is treated as dry air
     */
    fun density(pressureHpa: Double, tempC: Double, relativeHumidityPct: Double? = null): Double {
        val t = tempC + KELVIN
        val rh = (relativeHumidityPct ?: 0.0).coerceIn(0.0, 100.0) / 100.0
        val pv = rh * saturationVapourPressureHpa(tempC) * 100.0
        val pd = pressureHpa * 100.0 - pv
        return pd / (R_DRY * t) + pv / (R_VAPOUR * t)
    }

    /** [density] with null / implausible inputs falling back to [STANDARD]. Never throws. */
    fun densityOrStandard(pressureHpa: Double?, tempC: Double?, relativeHumidityPct: Double?): Double {
        if (pressureHpa == null || tempC == null || !pressureHpa.isFinite() || !tempC.isFinite()) return STANDARD
        val rho = density(pressureHpa, tempC, relativeHumidityPct?.takeIf { it.isFinite() })
        return if (rho in MIN_PLAUSIBLE..MAX_PLAUSIBLE) rho else STANDARD
    }

    /**
     * Hypsometric correction of a pressure from [fromElevationM] (e.g. model grid elevation) to
     * [toElevationM] (rider elevation).
     *
     * [tempAtTargetC] is the air temperature at the target elevation (the fused 2 m temperature at the
     * rider). The layer-mean temperature is reconstructed with the standard lapse rate, and humidity
     * is included through the virtual temperature when known.
     */
    fun correctPressureHpa(
        pressureHpa: Double,
        fromElevationM: Double,
        toElevationM: Double,
        tempAtTargetC: Double,
        relativeHumidityPct: Double? = null,
    ): Double {
        val dz = toElevationM - fromElevationM
        if (dz == 0.0) return pressureHpa
        // Mean of the layer: halfway between target and source, warmer below.
        val meanTempC = tempAtTargetC + LAPSE_RATE * dz / 2.0
        val meanT = meanTempC + KELVIN
        val tv = if (relativeHumidityPct == null) meanT else {
            val e = relativeHumidityPct.coerceIn(0.0, 100.0) / 100.0 * saturationVapourPressureHpa(meanTempC)
            meanT / (1.0 - (e / pressureHpa) * (1.0 - R_DRY / R_VAPOUR))
        }
        return pressureHpa * exp(-G * dz / (R_DRY * tv))
    }

    /**
     * Air density at the rider: prefers a device barometer reading ([sensorPressureHpa]) when present,
     * otherwise corrects the model surface pressure from grid to rider elevation.
     */
    fun atRider(
        tempC: Double?,
        relativeHumidityPct: Double?,
        modelSurfacePressureHpa: Double?,
        modelElevationM: Double?,
        riderElevationM: Double?,
        sensorPressureHpa: Double? = null,
    ): Double {
        if (tempC == null) return STANDARD
        val p = when {
            sensorPressureHpa != null && sensorPressureHpa.isFinite() && sensorPressureHpa > 300.0 -> sensorPressureHpa
            modelSurfacePressureHpa == null -> null
            modelElevationM != null && riderElevationM != null ->
                correctPressureHpa(modelSurfacePressureHpa, modelElevationM, riderElevationM, tempC, relativeHumidityPct)
            else -> modelSurfacePressureHpa
        }
        return densityOrStandard(p, tempC, relativeHumidityPct)
    }
}
