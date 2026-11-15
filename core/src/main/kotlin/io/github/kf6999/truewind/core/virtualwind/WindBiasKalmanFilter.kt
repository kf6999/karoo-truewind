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

import kotlin.math.cos
import kotlin.math.sin

/**
 * 3-state Kalman filter, state `x = [wu, wv, b]` (SPEC 4.6): rider-height wind (m/s, u east / v north,
 * direction blowing TO, same convention as `WindVector`) and a heading-independent headwind bias `b`
 * (m/s) that absorbs CdA, Crr, mass and drivetrain errors.
 *
 * All updates are scalar, use the Joseph form and re-symmetrise, so the covariance stays symmetric
 * positive-definite. When the bias is frozen the update is a Schmidt ("consider") update: `b` keeps
 * its value and variance, but its uncertainty still enters the gain and the cross-covariances.
 *
 * Allocation-free: all scratch space is preallocated.
 */
class WindBiasKalmanFilter(
    initialWu: Double = 0.0,
    initialWv: Double = 0.0,
    initialWindVariance: Double = 16.0,
    initialBias: Double = 0.0,
    initialBiasVariance: Double = 1.0,
) {
    /** State [wu, wv, b]. Read-only view through the getters. */
    private val x = DoubleArray(3)

    /** Row-major 3x3 covariance. */
    private val p = DoubleArray(9)

    private val ph = DoubleArray(3)
    private val k = DoubleArray(3)
    private val a = DoubleArray(9)
    private val tmp = DoubleArray(9)

    /** When true, the bias state is not updated and gets no process noise. */
    var biasFrozen: Boolean = true

    /** Last scalar innovation (z - Hx) of a headwind measurement, m/s. */
    var lastInnovation: Double = 0.0
        private set

    /** Last innovation variance S of a headwind measurement, (m/s)^2. */
    var lastInnovationVariance: Double = 0.0
        private set

    init {
        reset(initialWu, initialWv, initialWindVariance, initialBias, initialBiasVariance)
    }

    val wu: Double get() = x[0]
    val wv: Double get() = x[1]
    val bias: Double get() = x[2]

    /** Covariance element (row, col), 0..2. */
    fun covariance(row: Int, col: Int): Double = p[row * 3 + col]

    fun reset(wu: Double, wv: Double, windVariance: Double, bias: Double, biasVariance: Double) {
        x[0] = wu; x[1] = wv; x[2] = bias
        p.fill(0.0)
        p[0] = windVariance; p[4] = windVariance; p[8] = biasVariance
        lastInnovation = 0.0
        lastInnovationVariance = 0.0
    }

    /** Re-initialises the wind part only (keeps the bias and its variance). */
    fun resetWind(wu: Double, wv: Double, windVariance: Double) {
        x[0] = wu; x[1] = wv
        p[0] = windVariance; p[4] = windVariance
        p[1] = 0.0; p[3] = 0.0
        p[2] = 0.0; p[6] = 0.0; p[5] = 0.0; p[7] = 0.0
    }

    /**
     * Prediction: random walk plus a known input. [du]/[dv] shift the wind (used to carry changes of
     * the forecast prior straight through to the state); process noise is added on the diagonal.
     */
    fun predict(du: Double, dv: Double, windProcessVariance: Double, biasProcessVariance: Double) {
        x[0] += du
        x[1] += dv
        p[0] += windProcessVariance
        p[4] += windProcessVariance
        if (!biasFrozen) p[8] += biasProcessVariance
    }

    /**
     * Headwind measurement `z = -(wu*hu + wv*hv) + b` along heading unit vector (hu = sin, hv = cos of
     * the heading). Returns the innovation.
     */
    fun updateHeadwind(z: Double, headingDeg: Double, variance: Double): Double {
        val h = Math.toRadians(headingDeg)
        return updateHeadwindProjected(z, -sin(h), -cos(h), variance)
    }

    /**
     * Same as [updateHeadwind] with an explicit measurement row `[h0, h1, 1]` (lets callers average
     * the row over a window of samples).
     */
    fun updateHeadwindProjected(z: Double, h0: Double, h1: Double, variance: Double): Double {
        val y = scalarUpdate(h0, h1, 1.0, z, variance)
        lastInnovation = y
        return y
    }

    /** Pseudo-measurement of one wind component: [component] 0 = u, 1 = v. */
    fun updateWindComponent(component: Int, z: Double, variance: Double) {
        if (component == 0) scalarUpdate(1.0, 0.0, 0.0, z, variance) else scalarUpdate(0.0, 1.0, 0.0, z, variance)
    }

    /** Predicted headwind for a heading, m/s (+ into the rider), including the bias. */
    fun predictedHeadwind(headingDeg: Double): Double {
        val h = Math.toRadians(headingDeg)
        return -(x[0] * sin(h) + x[1] * cos(h)) + x[2]
    }

    /** True when the covariance is symmetric and positive-definite (Sylvester's criterion). */
    fun isCovariancePositiveDefinite(tolerance: Double = 1e-9): Boolean {
        for (i in 0 until 3) for (j in 0 until 3) {
            val d = p[i * 3 + j] - p[j * 3 + i]
            if (kotlin.math.abs(d) > tolerance * (1.0 + kotlin.math.abs(p[i * 3 + j]))) return false
        }
        val m1 = p[0]
        val m2 = p[0] * p[4] - p[1] * p[3]
        val m3 = p[0] * (p[4] * p[8] - p[5] * p[7]) - p[1] * (p[3] * p[8] - p[5] * p[6]) + p[2] * (p[3] * p[7] - p[4] * p[6])
        return m1 > 0.0 && m2 > 0.0 && m3 > 0.0
    }

    private fun scalarUpdate(h0: Double, h1: Double, h2: Double, z: Double, r: Double): Double {
        // ph = P h
        for (i in 0 until 3) ph[i] = p[i * 3] * h0 + p[i * 3 + 1] * h1 + p[i * 3 + 2] * h2
        val s = h0 * ph[0] + h1 * ph[1] + h2 * ph[2] + r
        if (!(s > 0.0)) return 0.0
        k[0] = ph[0] / s
        k[1] = ph[1] / s
        k[2] = if (biasFrozen) 0.0 else ph[2] / s
        val y = z - (h0 * x[0] + h1 * x[1] + h2 * x[2])
        x[0] += k[0] * y
        x[1] += k[1] * y
        x[2] += k[2] * y
        if (h2 != 0.0) lastInnovationVariance = s

        // Joseph form: P = A P A^T + K r K^T, A = I - K h^T. Valid for any gain (incl. the consider gain).
        for (i in 0 until 3) {
            a[i * 3] = (if (i == 0) 1.0 else 0.0) - k[i] * h0
            a[i * 3 + 1] = (if (i == 1) 1.0 else 0.0) - k[i] * h1
            a[i * 3 + 2] = (if (i == 2) 1.0 else 0.0) - k[i] * h2
        }
        for (i in 0 until 3) for (j in 0 until 3) {
            tmp[i * 3 + j] = a[i * 3] * p[j] + a[i * 3 + 1] * p[3 + j] + a[i * 3 + 2] * p[6 + j]
        }
        for (i in 0 until 3) for (j in 0 until 3) {
            p[i * 3 + j] = tmp[i * 3] * a[j * 3] + tmp[i * 3 + 1] * a[j * 3 + 1] + tmp[i * 3 + 2] * a[j * 3 + 2] +
                k[i] * r * k[j]
        }
        symmetrise()
        return y
    }

    private fun symmetrise() {
        val p01 = 0.5 * (p[1] + p[3]); p[1] = p01; p[3] = p01
        val p02 = 0.5 * (p[2] + p[6]); p[2] = p02; p[6] = p02
        val p12 = 0.5 * (p[5] + p[7]); p[5] = p12; p[7] = p12
        // Guard against numerical collapse of a variance.
        if (p[0] < MIN_VARIANCE) p[0] = MIN_VARIANCE
        if (p[4] < MIN_VARIANCE) p[4] = MIN_VARIANCE
        if (p[8] < MIN_VARIANCE) p[8] = MIN_VARIANCE
    }

    companion object {
        private const val MIN_VARIANCE = 1e-6
    }
}
