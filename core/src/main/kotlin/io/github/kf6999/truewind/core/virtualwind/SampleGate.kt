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

import kotlin.math.abs

/** Why the last sample was (in)valid. */
enum class GateResult {
    VALID,
    NO_POWER,
    LOW_POWER,
    NOT_PEDALLING,
    TOO_SLOW,
    ACCELERATING,
    NO_ACCELERATION_YET,
    STEEP_GRADE,
    NO_GRADE,
    AFTER_ZERO_POWER,
    NO_HEADING,
}

/**
 * Sample validity gate for the virtual wind sensor (SPEC 4.6) and the 3 s speed derivative.
 * Allocation-free; missing values are passed as NaN.
 *
 * Valid only when: P >= 80 W and cadence > 0; v >= 4 m/s; |a| < 0.5 m/s2; |grade| < 8 %; not within
 * 5 s of a zero-power sample (coasting/braking).
 */
class SampleGate(private val config: VirtualWindConfig = VirtualWindConfig()) {
    private val n = 16
    private val times = LongArray(n)
    private val speeds = DoubleArray(n)
    private var count = 0
    private var head = 0
    private var lastZeroPowerMs = Long.MIN_VALUE

    /** Acceleration from the last [update], m/s2, NaN when unknown. */
    var acceleration: Double = Double.NaN
        private set

    var lastResult: GateResult = GateResult.NO_POWER
        private set

    fun reset() {
        count = 0
        head = 0
        lastZeroPowerMs = Long.MIN_VALUE
        acceleration = Double.NaN
    }

    /**
     * @param power3sW 3 s average power (NaN = no power meter)
     * @param powerInstantW instantaneous power used to detect coasting (NaN = use [power3sW])
     * @param cadenceRpm NaN = unknown (treated as pedalling when power is present)
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
    ): GateResult {
        // Acceleration over ~3 s.
        times[head] = timeMs
        speeds[head] = speedMps
        head = (head + 1) % n
        if (count < n) count++
        acceleration = Double.NaN
        var bestAge = Long.MAX_VALUE
        for (k in 1 until count) {
            val i = (head - 1 - k + n * 2) % n
            val age = timeMs - times[i]
            if (age <= 0) continue
            val diff = abs(age - config.accelWindowMs)
            if (diff < bestAge && age >= config.accelWindowMs / 2 && age <= config.accelWindowMs * 2) {
                bestAge = diff
                acceleration = (speedMps - speeds[i]) * 1000.0 / age
            }
        }

        val instant = if (powerInstantW.isNaN()) power3sW else powerInstantW
        val zeroPower = (!instant.isNaN() && instant < 1.0) || (!cadenceRpm.isNaN() && cadenceRpm <= 0.0)
        if (zeroPower) lastZeroPowerMs = timeMs

        lastResult = when {
            power3sW.isNaN() -> GateResult.NO_POWER
            !cadenceRpm.isNaN() && cadenceRpm <= 0.0 -> GateResult.NOT_PEDALLING
            power3sW < config.minPowerW -> GateResult.LOW_POWER
            lastZeroPowerMs != Long.MIN_VALUE && timeMs - lastZeroPowerMs < config.zeroPowerHoldoffMs -> GateResult.AFTER_ZERO_POWER
            !(speedMps >= config.minSpeedMps) -> GateResult.TOO_SLOW
            acceleration.isNaN() -> GateResult.NO_ACCELERATION_YET
            abs(acceleration) >= config.maxAccelMps2 -> GateResult.ACCELERATING
            gradeFraction.isNaN() -> GateResult.NO_GRADE
            abs(gradeFraction) >= config.maxGradeFraction -> GateResult.STEEP_GRADE
            headingDeg.isNaN() -> GateResult.NO_HEADING
            else -> GateResult.VALID
        }
        return lastResult
    }
}
