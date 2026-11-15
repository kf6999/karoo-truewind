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

package io.github.kf6999.truewind.core.virtualwind

import io.github.kf6999.truewind.core.math.normalizeDegrees
import io.github.kf6999.truewind.core.math.normalizeSignedDegrees
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.physics.PowerModel
import io.github.kf6999.truewind.core.physics.RiderParams
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/** One measured sample at 1 Hz plus the hidden truth. */
data class RideTick(
    val timeMs: Long,
    val power3sW: Double,
    val powerInstantW: Double,
    val cadenceRpm: Double,
    val speedMps: Double,
    val gradeFraction: Double,
    val headingDeg: Double,
    val trueHeadingDeg: Double,
    val trueSpeedMps: Double,
)

/** A leg of constant heading; [powerW] overrides the ride's target power (0 = coasting). */
data class Leg(
    val headingDeg: Double,
    val durationS: Int,
    val powerW: Double? = null,
    val gradeFraction: Double? = null,
    /** Forced deceleration (braking), m/s2; overrides the power balance. */
    val brakeMps2: Double? = null,
)

/**
 * Synthetic ride generator using the same [PowerModel] as the estimator, with a known rider-height
 * wind, the TRUE rider parameters (which may differ from what the estimator assumes) and sensor noise.
 */
class SyntheticRide(
    private val trueWind: WindVector,
    private val trueParams: RiderParams,
    private val targetPowerW: Double = 200.0,
    private val airDensity: Double = 1.2,
    private val seed: Long = 1,
    /** Relative power noise per second. */
    private val powerNoise: Double = 0.03,
    private val speedNoise: Double = 0.05,
    private val gradeNoise: Double = 0.003,
    private val headingNoiseDeg: Double = 2.0,
    /** Rolling terrain amplitude (fraction) and period, applied when a leg sets no grade. */
    private val rollingGrade: Double = 0.015,
    private val rollingPeriodS: Double = 240.0,
    private val turnRateDegPerS: Double = 15.0,
) {
    fun generate(legs: List<Leg>): List<RideTick> {
        val rnd = Random(seed)
        val model = PowerModel(trueParams)
        val out = ArrayList<RideTick>()
        var heading = legs.first().headingDeg
        var v = model.steadySpeedFor(targetPowerW, trueWind.headwind(heading), 0.0, airDensity)
        var t = 0
        val pw = DoubleArray(3)
        for (leg in legs) {
            repeat(leg.durationS) {
                // Turn towards the leg heading at a finite rate.
                val d = normalizeSignedDegrees(leg.headingDeg - heading)
                heading = normalizeDegrees(heading + sign(d) * min(abs(d), turnRateDegPerS))
                val grade = leg.gradeFraction ?: (rollingGrade * sin(2 * PI * t / rollingPeriodS))
                val pTarget = leg.powerW ?: (targetPowerW * (1.0 + 0.1 * sin(2 * PI * t / 97.0)))
                val p = max(0.0, pTarget * (1.0 + powerNoise * rnd.nextGaussian()))
                val v0 = v
                // Integrate at 0.1 s.
                repeat(10) {
                    val a = if (leg.brakeMps2 != null) -leg.brakeMps2 else {
                        val hw = trueWind.headwind(heading)
                        val f = model.forces(v, v + hw, grade, 0.0, airDensity)
                        (p * trueParams.drivetrainEfficiency / max(v, 0.5) - f.total) / trueParams.effectiveMassKg
                    }
                    v = max(0.5, v + a * 0.1)
                }
                pw[t % 3] = p
                val p3 = if (t >= 2) pw.average() else p
                out.add(
                    RideTick(
                        timeMs = t * 1000L,
                        power3sW = p3,
                        powerInstantW = p,
                        cadenceRpm = if (p > 0.0) 88.0 else 0.0,
                        speedMps = v + speedNoise * rnd.nextGaussian(),
                        gradeFraction = grade + gradeNoise * rnd.nextGaussian(),
                        headingDeg = normalizeDegrees(heading + headingNoiseDeg * rnd.nextGaussian()),
                        trueHeadingDeg = heading,
                        trueSpeedMps = 0.5 * (v0 + v),
                    )
                )
                t++
            }
        }
        return out
    }

    companion object {
        /** Square loop: 4 legs of [legS] seconds, headings start, +90, +180, +270. */
        fun squareLoop(start: Double = 0.0, legS: Int = 300, loops: Int = 1): List<Leg> =
            (0 until loops).flatMap { (0 until 4).map { k -> Leg(normalizeDegrees(start + 90.0 * k), legS) } }
    }
}
