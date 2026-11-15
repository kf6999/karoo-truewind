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

import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.physics.RiderParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class VirtualWindSensorTest {
    private val assumed = RiderParams(massKg = 84.0, cda = 0.32, crr = 0.005)
    private val trueWind = WindVector.fromSpeedDirection(4.0, 240.0)

    /** Prior = truth + 2.1 m/s error; confidence 50 -> sigma 2.5 m/s. */
    private val prior = trueWind + WindVector(1.5, -1.5)
    private val confidence = 50

    private class RunResult(val errors: DoubleArray, val sensor: VirtualWindSensor, val countAt: IntArray)

    private fun run(
        ticks: List<RideTick>,
        sensor: VirtualWindSensor = VirtualWindSensor(assumed),
        priorWind: WindVector = prior,
        rho: Double = 1.2,
    ): RunResult {
        val errors = DoubleArray(ticks.size)
        val counts = IntArray(ticks.size)
        val sigma = sensor.priorSigma(confidence)
        ticks.forEachIndexed { i, t ->
            sensor.update(t.timeMs, t.power3sW, t.powerInstantW, t.cadenceRpm, t.speedMps, t.gradeFraction,
                t.headingDeg, rho, priorWind.u, priorWind.v, sigma)
            errors[i] = hypot(sensor.windU - trueWind.u, sensor.windV - trueWind.v)
            counts[i] = sensor.measurementCount
        }
        return RunResult(errors, sensor, counts)
    }

    private fun maxFrom(errors: DoubleArray, fromS: Int) = (fromS until errors.size).maxOf { errors[it] }

    @Test fun loop20min_correctParams_windErrorUnder1AfterTenMinutes() {
        val ticks = SyntheticRide(trueWind, assumed, seed = 11).generate(SyntheticRide.squareLoop(start = 20.0, legS = 300))
        val r = run(ticks)
        val after10 = maxFrom(r.errors, 600)
        println("loop20 correct params: initial err ${"%.2f".format(r.errors[0])}, max err after 10 min ${"%.2f".format(after10)}, final ${"%.2f".format(r.errors.last())}, bias ${"%.2f".format(r.sensor.bias)}, meas ${r.sensor.measurementCount}")
        assertTrue("max err after 10 min $after10", after10 < 1.0)
    }

    @Test fun loop_cdaError15pct_biasAbsorbsIt() {
        val trueParams = assumed.copy(cda = assumed.cda * 1.15)
        val ticks = SyntheticRide(trueWind, trueParams, seed = 12).generate(SyntheticRide.squareLoop(start = 20.0, legS = 300, loops = 2))
        val r = run(ticks)
        val after10 = maxFrom(r.errors, 600)
        println("loop CdA+15%: max err after 10 min ${"%.2f".format(after10)}, final ${"%.2f".format(r.errors.last())}, bias ${"%.2f".format(r.sensor.bias)}, innovMean ${"%.2f".format(r.sensor.innovationMean)}, frozen ${r.sensor.biasFrozen}")
        assertTrue("max err $after10", after10 < 1.5)
        assertFalse(r.sensor.biasFrozen)
        // More drag than assumed looks like extra headwind on every heading: positive bias.
        assertTrue("bias ${r.sensor.bias}", r.sensor.bias > 0.3)
        assertTrue(r.sensor.filter.isCovariancePositiveDefinite())
    }

    @Test fun loop_cdaErrorMinus15pct_biasNegative() {
        val trueParams = assumed.copy(cda = assumed.cda * 0.85)
        val ticks = SyntheticRide(trueWind, trueParams, seed = 13).generate(SyntheticRide.squareLoop(start = 20.0, legS = 300, loops = 2))
        val r = run(ticks)
        val after10 = maxFrom(r.errors, 600)
        println("loop CdA-15%: max err after 10 min ${"%.2f".format(after10)}, bias ${"%.2f".format(r.sensor.bias)}")
        assertTrue("max err $after10", after10 < 1.5)
        assertTrue("bias ${r.sensor.bias}", r.sensor.bias < -0.3)
    }

    @Test fun straightOutLeg_biasStaysFrozen_outputWithinPriorUncertainty() {
        val trueParams = assumed.copy(cda = assumed.cda * 1.15)
        val ticks = SyntheticRide(trueWind, trueParams, seed = 14).generate(listOf(Leg(15.0, 1500)))
        val sensor = VirtualWindSensor(assumed, VirtualWindConfig(initialBias = 0.2, initialBiasVariance = 0.25))
        val r = run(ticks, sensor)
        val sigma = sensor.priorSigma(confidence)
        val worst = r.errors.max()
        println("straight out-leg: max err ${"%.2f".format(worst)} (prior sigma $sigma), spread ${sensor.headingSpreadDeg}, bias ${sensor.bias}")
        assertTrue(sensor.biasFrozen)
        assertEquals(0.2, sensor.bias, 0.0)
        assertEquals(0.25, sensor.biasVariance, 1e-12)
        assertTrue("max err $worst", worst < sigma)
        assertTrue(sensor.headingSpreadDeg <= 20.0)
    }

    @Test fun outAndBack_unfreezesBias() {
        val ticks = SyntheticRide(trueWind, assumed, seed = 15).generate(listOf(Leg(0.0, 400), Leg(180.0, 400)))
        val r = run(ticks)
        assertTrue("spread ${r.sensor.headingSpreadDeg}", r.sensor.headingSpreadDeg >= 170.0)
        assertFalse(r.sensor.biasFrozen)
        // Along-track (v, north-south) wind is observed; cross-track (u) is constrained only by the prior.
        val ev = kotlin.math.abs(r.sensor.windV - trueWind.v)
        val eu = kotlin.math.abs(r.sensor.windU - trueWind.u)
        println("out-and-back: along-track err ${"%.2f".format(ev)}, cross-track err ${"%.2f".format(eu)}, bias ${"%.2f".format(r.sensor.bias)}")
        assertTrue("along $ev", ev < 0.5)
        assertTrue("cross $eu", eu < r.sensor.priorSigma(confidence))
    }

    @Test fun invalidSamples_areIgnored() {
        val legs = listOf(
            Leg(0.0, 240),
            Leg(0.0, 60, powerW = 0.0),               // coasting
            Leg(0.0, 30, brakeMps2 = 1.0),            // braking
            Leg(0.0, 120, gradeFraction = 0.10),      // steep climb
            Leg(0.0, 60, powerW = 50.0),              // soft-pedalling below 80 W
        )
        val ticks = SyntheticRide(trueWind, assumed, seed = 16).generate(legs)
        val sensor = VirtualWindSensor(assumed)
        val r = run(ticks, sensor)
        val atStart = r.countAt[239]
        // Allow the 5 s window that was open at t=239 to close.
        val afterFirstWindow = r.countAt[245]
        assertTrue(atStart > 30)
        assertEquals(afterFirstWindow, r.countAt.last())
        // Wind estimate barely moves while nothing valid arrives (only the weak prior acts).
        val drift = hypot(r.errors[245] - r.errors.last(), 0.0)
        assertTrue("drift $drift", drift < 0.3)
    }

    @Test fun afterZeroPower_holdoff() {
        val gate = SampleGate()
        var t = 0L
        repeat(5) { gate.update(t, 200.0, 200.0, 90.0, 8.0, 0.0, 0.0); t += 1000 }
        assertEquals(GateResult.VALID, gate.lastResult)
        gate.update(t, 150.0, 0.0, 90.0, 8.0, 0.0, 0.0); t += 1000
        repeat(4) {
            assertEquals(GateResult.AFTER_ZERO_POWER, gate.update(t, 200.0, 200.0, 90.0, 8.0, 0.0, 0.0)); t += 1000
        }
        assertEquals(GateResult.VALID, gate.update(t, 200.0, 200.0, 90.0, 8.0, 0.0, 0.0))
        assertEquals(GateResult.NO_POWER, gate.update(t + 1000, Double.NaN, Double.NaN, 90.0, 8.0, 0.0, 0.0))
        assertEquals(GateResult.TOO_SLOW, gate.update(t + 2000, 200.0, 200.0, 90.0, 3.0, 0.0, 0.0))
        assertEquals(GateResult.NOT_PEDALLING, gate.update(t + 3000, 200.0, 200.0, 0.0, 8.0, 0.0, 0.0))
    }

    @Test fun noPowerMeter_tracksPriorExactlyAcrossForecastChange() {
        val sensor = VirtualWindSensor(assumed)
        val sigma = sensor.priorSigma(80)
        var p = WindVector(2.0, -1.0)
        for (t in 0 until 600) {
            if (t == 300) p = WindVector(-3.0, 0.5) // new fetch
            sensor.update(t * 1000L, Double.NaN, Double.NaN, Double.NaN, 8.0, 0.0, 0.0, 1.2, p.u, p.v, sigma)
        }
        assertEquals(p.u, sensor.windU, 1e-9)
        assertEquals(p.v, sensor.windV, 1e-9)
        assertEquals(0, sensor.measurementCount)
    }

    @Test fun priorOnly_steadyStateUncertaintyMatchesPrior() {
        val sensor = VirtualWindSensor(assumed)
        for (conf in listOf(100, 0)) {
            val s = VirtualWindSensor(assumed)
            val sigma = s.priorSigma(conf)
            for (t in 0 until 4 * 3600) s.update(t * 1000L, Double.NaN, Double.NaN, Double.NaN, 0.0, 0.0, 0.0, 1.2, 1.0, 1.0, sigma)
            println("prior-only conf $conf: sigma ${s.windStdDev} (prior $sigma)")
            assertEquals(sigma, s.windStdDev, 0.25 * sigma)
        }
        assertEquals(1.0, sensor.priorSigma(100), 1e-12)
        assertEquals(4.0, sensor.priorSigma(0), 1e-12)
    }

    @Test fun updateTiming_wellUnderBudget() {
        val ticks = SyntheticRide(trueWind, assumed, seed = 17).generate(SyntheticRide.squareLoop(legS = 300, loops = 2))
        val sensor = VirtualWindSensor(assumed)
        val sigma = sensor.priorSigma(50)
        fun pass(): Long {
            val start = System.nanoTime()
            for (k in 0 until 25) {
                for (t in ticks) {
                    sensor.update(t.timeMs + k * 3_600_000L, t.power3sW, t.powerInstantW, t.cadenceRpm, t.speedMps,
                        t.gradeFraction, t.headingDeg, 1.2, prior.u, prior.v, sigma)
                }
            }
            return System.nanoTime() - start
        }
        repeat(3) { pass() } // warm-up (JIT)
        val n = 25 * ticks.size
        val perUpdateMs = pass() / 1e6 / n
        println("VirtualWindSensor.update: ${"%.5f".format(perUpdateMs)} ms per update over $n updates")
        assertTrue("per update $perUpdateMs ms", perUpdateMs < 0.2)
        // Generous bound against regressions while staying robust on a loaded CI machine.
        assertTrue("per update $perUpdateMs ms", perUpdateMs < 0.02)
    }
}

class WindBiasKalmanFilterTest {
    @Test fun headwindMeasurement_movesStateAlongHeadingOnly() {
        val f = WindBiasKalmanFilter(initialWindVariance = 4.0, initialBiasVariance = 1e-6)
        f.biasFrozen = true
        // Rider heading north (0 deg), measured headwind 3 m/s -> wind blowing TO south: wv negative.
        f.updateHeadwind(3.0, 0.0, 1.0)
        assertTrue(f.wv < -2.0)
        assertEquals(0.0, f.wu, 1e-12)
        assertEquals(0.0, f.bias, 0.0)
        assertEquals(3.0, f.predictedHeadwind(0.0), 0.7)
        assertEquals(WindVector(f.wu, f.wv).headwind(0.0), f.predictedHeadwind(0.0) - f.bias, 1e-12)
    }

    @Test fun frozenBias_keepsValueAndVariance_butCrossCovarianceIsConsistent() {
        val f = WindBiasKalmanFilter(initialBias = 0.5, initialBiasVariance = 0.3)
        f.biasFrozen = true
        repeat(50) { f.updateHeadwind(2.0, 90.0, 2.0); f.predict(0.0, 0.0, 0.01, 0.01) }
        assertEquals(0.5, f.bias, 0.0)
        assertEquals(0.3, f.covariance(2, 2), 1e-12)
        assertTrue(f.isCovariancePositiveDefinite())
    }

    @Test fun covarianceStaysPositiveDefinite_property() {
        val rnd = java.util.Random(99)
        repeat(200) { trial ->
            val f = WindBiasKalmanFilter(
                initialWindVariance = 0.01 + rnd.nextDouble() * 50,
                initialBiasVariance = 1e-4 + rnd.nextDouble() * 4,
            )
            repeat(500) {
                f.biasFrozen = rnd.nextInt(3) == 0
                when (rnd.nextInt(4)) {
                    0 -> f.predict(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextDouble() * 0.01, rnd.nextDouble() * 0.001)
                    1 -> f.updateHeadwind(rnd.nextGaussian() * 10, rnd.nextDouble() * 360, 1e-3 + rnd.nextDouble() * 5)
                    2 -> f.updateWindComponent(rnd.nextInt(2), rnd.nextGaussian() * 10, 1e-3 + rnd.nextDouble() * 1e4)
                    else -> f.updateHeadwindProjected(rnd.nextGaussian(), rnd.nextDouble() * 0.1, rnd.nextDouble() * 0.1, 1e-6 + rnd.nextDouble())
                }
                assertTrue("trial $trial", f.isCovariancePositiveDefinite())
                assertTrue(f.wu.isFinite() && f.wv.isFinite() && f.bias.isFinite())
            }
        }
    }

    @Test fun headingDiversity_identifiesBiasAndWind() {
        // Noise-free: true wind (2, -3), bias 0.8, measured on four headings.
        val trueW = WindVector(2.0, -3.0)
        val f = WindBiasKalmanFilter(initialWindVariance = 25.0, initialBiasVariance = 4.0)
        f.biasFrozen = false
        repeat(40) { k ->
            val h = (k % 4) * 90.0 + 10.0
            f.updateHeadwind(trueW.headwind(h) + 0.8, h, 0.01)
        }
        assertEquals(2.0, f.wu, 0.05)
        assertEquals(-3.0, f.wv, 0.05)
        assertEquals(0.8, f.bias, 0.05)
    }
}
