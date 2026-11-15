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

import io.github.kf6999.truewind.core.heading.HeadingSource
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.physics.AirDensity
import io.github.kf6999.truewind.core.physics.Exposure
import io.github.kf6999.truewind.core.physics.LogLaw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class RiderWindEstimatorTest {
    private val wind10 = WindVector.fromSpeedDirection(8.0, 0.0) // from the north

    @Test fun noPowerMeter_riderIsPresetRatioTimesModel() {
        val est = RiderWindEstimator(RiderWindConfig(exposure = Exposure.MIXED))
        var out: RiderWindOutput? = null
        for (t in 0 until 10) {
            out = est.update(RiderWindInputs(t * 1000L, model10m = wind10, gust10m = 12.0, confidence = 80,
                gpsCourseDeg = 0.0, speedMps = 8.0, gpsAccuracyM = 5.0,
                temperatureC = 15.0, relativeHumidityPct = 0.0, modelSurfacePressureHpa = 1013.25,
                modelElevationM = 0.0, riderElevationM = 0.0))
        }
        val o = out!!
        val ratio = LogLaw.ratio(Exposure.MIXED)
        assertFalse(o.virtualSensorActive)
        assertEquals(8.0 * ratio, o.rider!!.speed, 1e-9)
        assertEquals(0.0, o.rider!!.directionFromDeg, 1e-9)
        assertEquals(12.0 * (ratio + 0.1), o.gustRider!!, 1e-9)
        // Riding north into a north wind: full headwind, no crosswind, wind dead ahead.
        assertEquals(8.0 * ratio, o.headwind!!, 1e-9)
        assertEquals(0.0, o.crosswind!!, 1e-9)
        assertEquals(0.0, o.relativeDirectionDeg!!, 1e-9)
        assertEquals(AirDensity.STANDARD, o.airDensity, 0.001)
        assertEquals(HeadingSource.GPS_COURSE, o.headingSource)
        assertEquals(8.0 + 8.0 * ratio, o.airSpeedMps!!, 1e-9)
        assertNull(o.effectiveRatio)
    }

    @Test fun crosswindFromRight_andMissingInputs() {
        val est = RiderWindEstimator()
        val o = est.update(RiderWindInputs(0, model10m = WindVector.fromSpeedDirection(5.0, 90.0), gpsCourseDeg = 0.0, speedMps = 8.0))
        assertTrue(o.crosswind!! > 0.0)
        assertEquals(90.0, o.relativeDirectionDeg!!, 1e-9)
        val empty = RiderWindEstimator().update(RiderWindInputs(0))
        assertNull(empty.rider)
        assertNull(empty.headwind)
        assertNull(empty.feelsLikeC)
        assertEquals(AirDensity.STANDARD, empty.airDensity, 0.0)
    }

    /** End to end: synthetic loop with power, prior wrong by 2 m/s, through the façade. */
    @Test fun syntheticLoop_throughFacade_converges() {
        val riderMass = 75.0
        val cfg = RiderWindConfig(exposure = Exposure.OPEN, riderMassKg = riderMass, bikeMassKg = 9.0, virtualSensorEnabled = true)
        val ratio = LogLaw.ratio(Exposure.OPEN)
        val trueRider = WindVector.fromSpeedDirection(4.0, 240.0)
        // The forecast is 2 m/s off at rider height.
        val model10m = (trueRider + WindVector(1.5, -1.5)) / ratio
        val ticks = SyntheticRide(trueRider, cfg.riderParams, seed = 21, headingNoiseDeg = 0.0, airDensity = 1.225)
            .generate(SyntheticRide.squareLoop(start = 20.0, legS = 300, loops = 2))
        val est = RiderWindEstimator(cfg)
        var worstAfter10 = 0.0
        var last: RiderWindOutput? = null
        ticks.forEachIndexed { i, t ->
            // Course from GPS: truth with a little noise.
            last = est.update(RiderWindInputs(t.timeMs, model10m = model10m, gust10m = 10.0, confidence = 50,
                gpsCourseDeg = t.trueHeadingDeg + 2.0 * Math.sin(i * 0.7), speedMps = t.speedMps, gpsAccuracyM = 4.0,
                temperatureC = 15.0, relativeHumidityPct = 0.0, modelSurfacePressureHpa = 1013.25,
                modelElevationM = 0.0, riderElevationM = 0.0,
                power3sW = t.power3sW, powerInstantW = t.powerInstantW, cadenceRpm = t.cadenceRpm,
                gradePercent = t.gradeFraction * 100.0))
            if (i >= 600) worstAfter10 = maxOf(worstAfter10, hypot(last!!.rider!!.u - trueRider.u, last!!.rider!!.v - trueRider.v))
        }
        println("facade loop: worst rider-wind error after 10 min ${"%.2f".format(worstAfter10)}, effective ratio ${last!!.effectiveRatio}, innovMean ${last!!.innovationMean}")
        assertTrue(last!!.virtualSensorActive)
        assertTrue("worst $worstAfter10", worstAfter10 < 1.0)
        // Effective ratio |rider| / |model10m| reflects the learned rider wind.
        assertEquals(trueRider.speed / model10m.speed, last!!.effectiveRatio!!, 0.05)
        assertTrue(last!!.measuredHeadwind != null || last!!.gateResult != GateResult.VALID)
    }

    @Test fun groupRide_disablesSensor() {
        val est = RiderWindEstimator(RiderWindConfig(groupRide = true))
        val o = est.update(RiderWindInputs(0, model10m = wind10, gpsCourseDeg = 0.0, speedMps = 9.0, power3sW = 250.0,
            cadenceRpm = 90.0, gradePercent = 0.0))
        assertFalse(o.virtualSensorActive)
        assertNull(o.measuredHeadwind)
        assertNull(o.gateResult)
    }

    @Test fun feelsLike_usesRiderAirSpeed() {
        val est = RiderWindEstimator()
        val o = est.update(RiderWindInputs(0, model10m = wind10, gpsCourseDeg = 0.0, speedMps = 8.0, temperatureC = 2.0,
            relativeHumidityPct = 70.0))
        assertTrue(o.feelsLikeC!! < 2.0 - 3.0)
    }
}
