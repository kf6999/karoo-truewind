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
import io.github.kf6999.truewind.core.physics.PowerModel
import io.github.kf6999.truewind.core.physics.RiderParams
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Virtual wind sensor tuning. Spec values (SPEC 4.6) unless marked.
 */
data class VirtualWindConfig(
    val minPowerW: Double = 80.0,
    val minSpeedMps: Double = 4.0,
    val maxAccelMps2: Double = 0.5,
    val maxGradeFraction: Double = 0.08,
    val zeroPowerHoldoffMs: Long = 5_000,
    val accelWindowMs: Long = 3_000,
    /** A headwind measurement is made from the valid samples of each 5 s window. */
    val measurementIntervalMs: Long = 5_000,
    /** Not in SPEC: minimum valid samples in a window (at 1 Hz, 3 of 5). */
    val minSamplesPerWindow: Int = 3,
    /** Headwind measurement variance, (m/s)^2. */
    val measurementVariance: Double = 1.5 * 1.5,
    val windProcessVariancePerMin: Double = 0.05,
    val biasProcessVariancePerMin: Double = 0.002,
    val diversityWindowMs: Long = 15 * 60_000L,
    val diversityThresholdDeg: Double = 60.0,
    /** Prior pseudo-measurement standard deviation at confidence 100 and 0, m/s (variance (1)^2..(4)^2). */
    val priorSigmaAtFullConfidence: Double = 1.0,
    val priorSigmaAtZeroConfidence: Double = 4.0,
    /** Not in SPEC: innovation gate in standard deviations (drafting bursts, sensor glitches). */
    val innovationGateSigma: Double = 5.0,
    /** Not in SPEC: physically implausible measured headwinds are discarded. */
    val maxAbsHeadwindMps: Double = 25.0,
    /** Persisted bias (per bike) and its variance used at start. */
    val initialBias: Double = 0.0,
    val initialBiasVariance: Double = 1.0,
    /** EWMA weight of the innovation mean diagnostic, per measurement. */
    val innovationEwmaAlpha: Double = 0.05,
)

/**
 * Power-based virtual wind sensor (SPEC 4.6): sample gate, 5 s headwind measurements from the power
 * balance, the 3-state [WindBiasKalmanFilter], the model prior pseudo-measurement and the heading
 * diversity gate for the bias.
 *
 * [update] is allocation-free; missing inputs are NaN. Read results from the properties afterwards.
 */
class VirtualWindSensor(
    var riderParams: RiderParams,
    private val config: VirtualWindConfig = VirtualWindConfig(),
) {
    val filter = WindBiasKalmanFilter(
        initialBias = config.initialBias,
        initialBiasVariance = config.initialBiasVariance,
    )
    private val gate = SampleGate(config)
    private var model = PowerModel(riderParams)

    private var lastTimeMs = Long.MIN_VALUE
    private var hasPrior = false
    private var priorU = 0.0
    private var priorV = 0.0

    // 5 s measurement window accumulators.
    private var windowStartMs = Long.MIN_VALUE
    private var windowCount = 0
    private var windowZ = 0.0
    private var windowH0 = 0.0
    private var windowH1 = 0.0

    // Heading diversity: last time each 10 degree bin was seen in a valid sample.
    private val binLastSeen = LongArray(DIVERSITY_BINS) { Long.MIN_VALUE }

    /** Raw measured headwind of the last tick, m/s (+ into the rider); NaN when the sample was invalid. */
    var measuredHeadwind: Double = Double.NaN
        private set

    var lastGateResult: GateResult = GateResult.NO_POWER
        private set

    /** Number of headwind measurements applied to the filter. */
    var measurementCount: Int = 0
        private set

    /** Time of the last applied measurement, or Long.MIN_VALUE. */
    var lastMeasurementTimeMs: Long = Long.MIN_VALUE
        private set

    /** Innovation of the last applied measurement, m/s. */
    var lastInnovation: Double = Double.NaN
        private set

    /** Innovation divided by its predicted standard deviation. */
    var lastNormalizedInnovation: Double = Double.NaN
        private set

    /** Exponentially weighted mean innovation; persistently non-zero means CdA/Crr settings are off. */
    var innovationMean: Double = 0.0
        private set

    /** Measurements rejected by the innovation gate. */
    var rejectedCount: Int = 0
        private set

    /** Heading spread over the diversity window, degrees (10 degree resolution). */
    var headingSpreadDeg: Double = 0.0
        private set

    val windU: Double get() = filter.wu
    val windV: Double get() = filter.wv
    val bias: Double get() = filter.bias
    val biasVariance: Double get() = filter.covariance(2, 2)
    val biasFrozen: Boolean get() = filter.biasFrozen
    val windStdDev: Double get() = sqrt(0.5 * (filter.covariance(0, 0) + filter.covariance(1, 1)))

    fun setParams(params: RiderParams) {
        riderParams = params
        model = PowerModel(params)
    }

    /** Forget the wind (e.g. group-ride toggled), keep the learned bias. */
    fun resetWind() {
        hasPrior = false
        windowStartMs = Long.MIN_VALUE
        windowCount = 0
        gate.reset()
    }

    fun priorSigma(confidence: Int): Double {
        val c = confidence.coerceIn(0, 100) / 100.0
        return config.priorSigmaAtZeroConfidence + c * (config.priorSigmaAtFullConfidence - config.priorSigmaAtZeroConfidence)
    }

    /**
     * One tick.
     *
     * @param priorU prior rider-height wind (ratio * model10m), u component, NaN if no forecast
     * @param priorSigma prior standard deviation, m/s (see [priorSigma])
     * @param gradeFraction 0.05 = 5 %
     */
    fun update(
        timeMs: Long,
        power3sW: Double,
        powerInstantW: Double,
        cadenceRpm: Double,
        speedMps: Double,
        gradeFraction: Double,
        headingDeg: Double,
        airDensity: Double,
        priorU: Double,
        priorV: Double,
        priorSigma: Double,
    ) {
        val dtS = if (lastTimeMs == Long.MIN_VALUE) 0.0 else ((timeMs - lastTimeMs).coerceIn(0L, MAX_DT_MS)) / 1000.0
        lastTimeMs = timeMs

        // 1. Predict, carrying forecast changes straight into the state.
        val priorOk = !priorU.isNaN() && !priorV.isNaN() && priorSigma > 0.0
        if (priorOk && !hasPrior) {
            filter.resetWind(priorU, priorV, priorSigma * priorSigma)
            this.priorU = priorU
            this.priorV = priorV
            hasPrior = true
        }
        var du = 0.0
        var dv = 0.0
        if (priorOk) {
            du = priorU - this.priorU
            dv = priorV - this.priorV
            this.priorU = priorU
            this.priorV = priorV
        }
        updateDiversity(timeMs)
        filter.biasFrozen = headingSpreadDeg <= config.diversityThresholdDeg
        val dtMin = dtS / 60.0
        filter.predict(du, dv, config.windProcessVariancePerMin * dtMin, config.biasProcessVariancePerMin * dtMin)

        // 2. Prior pseudo-measurement every tick.
        if (priorOk && dtS > 0.0) {
            // The same forecast value arrives every tick, so it must not count as independent evidence
            // each second. The per-tick variance R = sigma^4 / (q * dt) makes the prior-only steady state
            // (P^2 ~= q R) equal to the prior's stated variance sigma^2, and its pull time constant
            // sigma^2 / q (20 min at confidence 100). Not in SPEC; see report.
            val qPerS = config.windProcessVariancePerMin / 60.0
            val s2 = priorSigma * priorSigma
            val r = (s2 * s2 / (qPerS * dtS)).coerceAtLeast(s2)
            filter.updateWindComponent(0, priorU, r)
            filter.updateWindComponent(1, priorV, r)
        }

        // 3. Gate and power-balance measurement.
        lastGateResult = gate.update(timeMs, power3sW, powerInstantW, cadenceRpm, speedMps, gradeFraction, headingDeg)
        measuredHeadwind = Double.NaN
        if (lastGateResult == GateResult.VALID && airDensity > 0.0) {
            val hw = model.headwindFromPower(power3sW, speedMps, gradeFraction, gate.acceleration, airDensity)
            if (hw != null && abs(hw) <= config.maxAbsHeadwindMps) {
                measuredHeadwind = hw
                val h = Math.toRadians(headingDeg)
                if (windowStartMs == Long.MIN_VALUE) windowStartMs = timeMs
                windowCount++
                windowZ += hw
                windowH0 += -sin(h)
                windowH1 += -cos(h)
                markHeading(timeMs, headingDeg)
            }
        }
        if (windowStartMs != Long.MIN_VALUE) {
            val tickMs = (dtS * 1000.0).toLong().coerceAtLeast(1L)
            if (timeMs - windowStartMs + tickMs >= config.measurementIntervalMs) closeWindow(timeMs)
        }
    }

    private fun closeWindow(timeMs: Long) {
        if (windowCount >= config.minSamplesPerWindow) {
            val n = windowCount.toDouble()
            val z = windowZ / n
            val h0 = windowH0 / n
            val h1 = windowH1 / n
            // Innovation gate using the predicted innovation variance.
            val predicted = h0 * filter.wu + h1 * filter.wv + filter.bias
            val s = h0 * h0 * filter.covariance(0, 0) + 2 * h0 * h1 * filter.covariance(0, 1) +
                h1 * h1 * filter.covariance(1, 1) + 2 * h0 * filter.covariance(0, 2) + 2 * h1 * filter.covariance(1, 2) +
                filter.covariance(2, 2) + config.measurementVariance
            val y = z - predicted
            if (abs(y) <= config.innovationGateSigma * sqrt(s)) {
                filter.updateHeadwindProjected(z, h0, h1, config.measurementVariance)
                lastInnovation = y
                lastNormalizedInnovation = y / sqrt(s)
                innovationMean += config.innovationEwmaAlpha * (y - innovationMean)
                measurementCount++
                lastMeasurementTimeMs = timeMs
            } else {
                rejectedCount++
            }
        }
        windowStartMs = Long.MIN_VALUE
        windowCount = 0
        windowZ = 0.0
        windowH0 = 0.0
        windowH1 = 0.0
    }

    private fun markHeading(timeMs: Long, headingDeg: Double) {
        val bin = (normalizeDegrees(headingDeg) / BIN_DEG).toInt().coerceIn(0, DIVERSITY_BINS - 1)
        binLastSeen[bin] = timeMs
    }

    /** Spread = arc covered by recently seen bins = 360 - largest empty circular run, minus one bin. */
    private fun updateDiversity(timeMs: Long) {
        val since = timeMs - config.diversityWindowMs
        var occupied = 0
        var maxRun = 0
        var run = 0
        // Walk twice around to handle runs across the 350/0 wrap.
        for (k in 0 until DIVERSITY_BINS * 2) {
            val seen = binLastSeen[k % DIVERSITY_BINS]
            val active = seen != Long.MIN_VALUE && seen >= since
            if (k < DIVERSITY_BINS && active) occupied++
            if (active) run = 0 else { run++; if (run > maxRun) maxRun = run }
        }
        headingSpreadDeg = if (occupied == 0) 0.0 else {
            val emptyRun = maxRun.coerceAtMost(DIVERSITY_BINS - 1)
            (DIVERSITY_BINS - emptyRun - 1) * BIN_DEG
        }
    }

    companion object {
        private const val DIVERSITY_BINS = 36
        private const val BIN_DEG = 10.0
        private const val MAX_DT_MS = 60_000L
    }
}
