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

package de.timklge.karooheadwind.pipeline

import de.timklge.karooheadwind.datatypes.RelativeGradeDataType
import io.github.kf6999.truewind.core.physics.PowerModel
import io.github.kf6999.truewind.core.physics.RiderParams
import kotlin.math.cos

/** Physics inputs of the relative grade / resistance fields (SPEC 4.4, G7). */
data class PhysicsParams(
    val upstream: Boolean,
    val cda: Double,
    val crr: Double,
    val airDensity: Double,
    val bikeMassKg: Double,
) {
    companion object {
        /** karoo-headwind's fixed constants. */
        val UPSTREAM = PhysicsParams(
            upstream = true,
            cda = RelativeGradeDataType.DEFAULT_CDA,
            crr = RelativeGradeDataType.DEFAULT_CRR,
            airDensity = RelativeGradeDataType.DEFAULT_AIR_DENSITY,
            bikeMassKg = RelativeGradeDataType.DEFAULT_BIKE_WEIGHT,
        )
    }
}

/**
 * Resistance forces and relative grade. Upstream mode calls karoo-headwind's functions unchanged;
 * TrueWind mode uses `core.physics.PowerModel` with live ρ and the configured CdA/Crr/bike weight.
 */
object Physics {
    fun forces(
        actualGrade: Double, riderSpeed: Double, windSpeed: Double, windDirectionDegrees: Double,
        riderMassKg: Double, p: PhysicsParams,
    ): RelativeGradeDataType.ResistanceForces? {
        val totalMass = riderMassKg + p.bikeMassKg
        if (p.upstream) {
            return RelativeGradeDataType.estimateResistanceForces(actualGrade, riderSpeed, windSpeed, windDirectionDegrees, totalMass)
        }
        if (totalMass <= 0.0 || riderSpeed < 0.0 || windSpeed < 0.0) return null
        val model = PowerModel(RiderParams(massKg = totalMass, cda = p.cda, crr = p.crr))
        val headwind = windSpeed * cos(Math.toRadians(windDirectionDegrees))
        val withWind = model.forces(riderSpeed, riderSpeed + headwind, actualGrade, 0.0, p.airDensity)
        val calm = model.forces(riderSpeed, riderSpeed, actualGrade, 0.0, p.airDensity)
        return RelativeGradeDataType.ResistanceForces(
            airResistanceWithoutWind = calm.aero,
            airResistanceWithWind = withWind.aero,
            rollingResistance = model.forces(1.0, 1.0, actualGrade, 0.0, p.airDensity).rolling,
            gravitationalForce = withWind.gravity,
        )
    }

    /** Grade (fraction) with the same total resistance in still air. */
    fun relativeGrade(
        actualGrade: Double, riderSpeed: Double, windSpeed: Double, windDirectionDegrees: Double,
        riderMassKg: Double, p: PhysicsParams,
    ): Double {
        val totalMass = riderMassKg + p.bikeMassKg
        if (p.upstream) {
            return RelativeGradeDataType.estimateRelativeGrade(actualGrade, riderSpeed, windSpeed, windDirectionDegrees, totalMass)
        }
        val f = forces(actualGrade, riderSpeed, windSpeed, windDirectionDegrees, riderMassKg, p) ?: return Double.NaN
        if (riderSpeed == 0.0 && windSpeed == 0.0) return actualGrade
        return actualGrade + (f.airResistanceWithWind - f.airResistanceWithoutWind) / (totalMass * PowerModel.G)
    }
}
