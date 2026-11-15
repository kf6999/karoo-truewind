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
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rider + bike parameters for the power balance (SPEC 4.6).
 *
 * @param massKg rider + bike mass
 * @param cda drag area, m2 (default road hoods)
 * @param crr rolling resistance coefficient
 * @param drivetrainEfficiency fraction of crank power reaching the wheel
 * @param wheelInertiaKg extra effective mass for rotating wheels
 */
data class RiderParams(
    val massKg: Double,
    val cda: Double = DEFAULT_CDA,
    val crr: Double = DEFAULT_CRR,
    val drivetrainEfficiency: Double = DEFAULT_DRIVETRAIN_EFFICIENCY,
    val wheelInertiaKg: Double = DEFAULT_WHEEL_INERTIA_KG,
) {
    val effectiveMassKg: Double get() = massKg + wheelInertiaKg

    companion object {
        const val DEFAULT_CDA = 0.32
        const val DEFAULT_CRR = 0.005
        const val DEFAULT_DRIVETRAIN_EFFICIENCY = 0.975
        const val DEFAULT_WHEEL_INERTIA_KG = 0.15
        const val DEFAULT_BIKE_MASS_KG = 9.0
    }
}

/** Resistive forces along the direction of travel, N. Positive opposes motion. */
data class Forces(
    val aero: Double,
    val gravity: Double,
    val rolling: Double,
    val inertia: Double,
) {
    val total: Double get() = aero + gravity + rolling + inertia
}

/**
 * Cyclist power balance. Grades are fractions (0.05 = 5 %); speeds m/s; air density kg/m3.
 *
 * Air speed is the speed of the air relative to the rider along the direction of travel:
 * `airSpeed = groundSpeed + headwind` (headwind + into the rider).
 */
class PowerModel(val params: RiderParams) {

    /** Forces at a given state. [airSpeed] may be negative (strong tailwind pushing the rider). */
    fun forces(groundSpeed: Double, airSpeed: Double, grade: Double, accel: Double, airDensity: Double): Forces {
        val theta = atan(grade)
        val mg = params.massKg * G
        return Forces(
            aero = 0.5 * airDensity * params.cda * airSpeed * abs(airSpeed),
            gravity = mg * sin(theta),
            rolling = if (groundSpeed > 0.0) mg * params.crr * cos(theta) else 0.0,
            inertia = params.effectiveMassKg * accel,
        )
    }

    /** Crank power needed, W (negative when the rider would have to brake). */
    fun powerFor(groundSpeed: Double, headwind: Double, grade: Double, accel: Double, airDensity: Double): Double =
        forces(groundSpeed, groundSpeed + headwind, grade, accel, airDensity).total * groundSpeed /
            params.drivetrainEfficiency

    /** Aerodynamic force implied by measured crank power, N. Null when [groundSpeed] is not positive. */
    fun aeroForceFromPower(power: Double, groundSpeed: Double, grade: Double, accel: Double): Double? {
        if (groundSpeed <= 0.0) return null
        val theta = atan(grade)
        val mg = params.massKg * G
        return power * params.drivetrainEfficiency / groundSpeed -
            mg * (sin(theta) + params.crr * cos(theta)) - params.effectiveMassKg * accel
    }

    /**
     * Inverse model: air speed along the direction of travel from power (SPEC 4.6):
     * `v_air = sign(F_aero) * sqrt(|F_aero| / (0.5 rho CdA))`.
     */
    fun airSpeedFromPower(power: Double, groundSpeed: Double, grade: Double, accel: Double, airDensity: Double): Double? {
        val f = aeroForceFromPower(power, groundSpeed, grade, accel) ?: return null
        val k = 0.5 * airDensity * params.cda
        if (k <= 0.0) return null
        return sign(f) * sqrt(abs(f) / k)
    }

    /** Measured headwind, m/s (+ into the rider): `v_air - v`. */
    fun headwindFromPower(power: Double, groundSpeed: Double, grade: Double, accel: Double, airDensity: Double): Double? =
        airSpeedFromPower(power, groundSpeed, grade, accel, airDensity)?.minus(groundSpeed)

    /**
     * Steady-state ground speed for a given power by bisection (used by simulations and the
     * "relative grade" style fields). Returns 0 when the power cannot overcome the resistances.
     */
    fun steadySpeedFor(power: Double, headwind: Double, grade: Double, airDensity: Double): Double {
        var lo = 0.0
        var hi = 40.0
        if (powerFor(1e-3, headwind, grade, 0.0, airDensity) > power) return 0.0
        repeat(60) {
            val mid = 0.5 * (lo + hi)
            if (powerFor(mid, headwind, grade, 0.0, airDensity) > power) hi = mid else lo = mid
        }
        return 0.5 * (lo + hi)
    }

    companion object {
        const val G = 9.80665
    }
}
