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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class LogLawTest {
    @Test fun specTableValues_twoDecimals() {
        assertEquals(0.64, LogLaw.ratio(Exposure.OPEN, 1.2), 0.005)
        assertEquals(0.54, LogLaw.ratio(Exposure.MIXED, 1.2), 0.005)
        assertEquals(0.40, LogLaw.ratio(Exposure.SHELTERED, 1.2), 0.006) // 0.395 rounds to 0.40
        assertEquals(0.30, LogLaw.ratio(Exposure.URBAN_FOREST, 1.2), 1e-12)
        assertEquals(Exposure.MIXED, Exposure.DEFAULT)
    }

    @Test fun monotonicInHeightAndRoughness() {
        for (e in listOf(Exposure.OPEN, Exposure.MIXED, Exposure.SHELTERED)) {
            var prev = 0.0
            var z = 0.9
            while (z <= 1.5 + 1e-9) {
                val r = LogLaw.ratio(e, z)
                assertTrue("$e z=$z", r >= prev)
                prev = r
                z += 0.05
            }
        }
        for (z in listOf(0.9, 1.2, 1.5)) {
            assertTrue(LogLaw.ratio(Exposure.OPEN, z) > LogLaw.ratio(Exposure.MIXED, z))
            assertTrue(LogLaw.ratio(Exposure.MIXED, z) > LogLaw.ratio(Exposure.SHELTERED, z))
            assertTrue(LogLaw.ratio(Exposure.SHELTERED, z) >= LogLaw.ratio(Exposure.URBAN_FOREST, z))
        }
    }

    @Test fun clamping() {
        // Height is clamped to the settings range.
        assertEquals(LogLaw.ratio(Exposure.OPEN, 1.5), LogLaw.ratio(Exposure.OPEN, 5.0), 1e-12)
        assertEquals(LogLaw.ratio(Exposure.OPEN, 0.9), LogLaw.ratio(Exposure.OPEN, 0.1), 1e-12)
        // Sheltered at 0.9 m is 0.31 by the log law, still above the canopy clamp; never below 0.30.
        assertTrue(LogLaw.ratio(Exposure.SHELTERED, 0.9) >= LogLaw.CANOPY_RATIO)
        assertEquals(1.0, LogLaw.gustRatio(0.95), 1e-12)
        assertEquals(0.64, LogLaw.gustRatio(0.54), 1e-12)
    }

    @Test fun downscaleKeepsDirection() {
        val w = WindVector.fromSpeedDirection(8.0, 250.0)
        val r = LogLaw.toRiderHeight(w, 0.54)
        assertEquals(8.0 * 0.54, r.speed, 1e-9)
        assertEquals(250.0, r.directionFromDeg, 1e-9)
    }

    @Test fun toTenMetres_inverseOfProfile() {
        val at2m = 4.0
        val at10 = LogLaw.toTenMetres(at2m, 2.0, 0.03)
        assertEquals(at2m, at10 * LogLaw.profileRatio(2.0, 0.03), 1e-12)
        assertEquals(5.0, LogLaw.toTenMetres(5.0, 10.0, 0.03), 1e-12)
    }
}

class AirDensityTest {
    @Test fun standardSeaLevel() {
        assertEquals(1.225, AirDensity.density(1013.25, 15.0, 0.0), 0.0005)
        assertEquals(1.225, AirDensity.density(1013.25, 15.0), 0.0005)
    }

    @Test fun altitudeStandardAtmosphere() {
        // ISA at 1500 m: 845.6 hPa, 5.25 C, 1.0581 kg/m3. At 3000 m: 701.1 hPa, -4.5 C, 0.9093.
        assertEquals(1.0581, AirDensity.density(845.6, 5.25), 0.001)
        assertEquals(0.9093, AirDensity.density(701.1, -4.5), 0.001)
    }

    @Test fun hotHumid() {
        // 35 C, 80 % RH, 1013.25 hPa: about 1.126 kg/m3 (moist air is lighter than dry at 1.146).
        val moist = AirDensity.density(1013.25, 35.0, 80.0)
        assertEquals(1.126, moist, 0.002)
        assertTrue(moist < AirDensity.density(1013.25, 35.0, 0.0))
        assertEquals(56.2, AirDensity.saturationVapourPressureHpa(35.0), 0.2)
    }

    @Test fun hypsometricWorkedExample() {
        // ISA: 1013.25 hPa at 0 m, 8.5 C at 1000 m -> 898.76 hPa at 1000 m.
        assertEquals(898.76, AirDensity.correctPressureHpa(1013.25, 0.0, 1000.0, 8.5), 0.1)
        // And back down.
        assertEquals(1013.25, AirDensity.correctPressureHpa(898.76, 1000.0, 0.0, 15.0), 0.1)
        // ~12 Pa per metre near sea level.
        assertEquals(1013.25 - 1.2, AirDensity.correctPressureHpa(1013.25, 0.0, 10.0, 15.0), 0.02)
    }

    @Test fun atRiderPrefersSensorThenModel() {
        val sensor = AirDensity.atRider(15.0, 50.0, 1013.25, 0.0, 500.0, sensorPressureHpa = 900.0)
        assertEquals(AirDensity.density(900.0, 15.0, 50.0), sensor, 1e-12)
        val model = AirDensity.atRider(15.0, 50.0, 1013.25, 0.0, 500.0)
        assertTrue(model < AirDensity.density(1013.25, 15.0, 50.0))
        assertEquals(AirDensity.STANDARD, AirDensity.atRider(null, null, null, null, null), 0.0)
        assertEquals(AirDensity.STANDARD, AirDensity.densityOrStandard(10.0, 15.0, null), 0.0)
    }
}

class FeelsLikeTest {
    @Test fun windChill() {
        // Environment Canada table: -10 C at 20 km/h -> -17.9; 5 C at 30 km/h -> 0 (rounded).
        assertEquals(-17.9, FeelsLike.windChillC(-10.0, 20.0), 0.1)
        assertEquals(0.0, FeelsLike.windChillC(5.0, 30.0), 0.5)
        // Uses rider air speed: 8 m/s ground + 2 m/s headwind = 36 km/h.
        assertEquals(FeelsLike.windChillC(5.0, 36.0), FeelsLike.feelsLikeC(5.0, 10.0, 60.0), 1e-9)
        assertEquals(5.0, FeelsLike.feelsLikeC(5.0, 1.0, 60.0), 0.0) // below 4.8 km/h
    }

    @Test fun heatIndex() {
        // NWS table: 90 F (32.2 C) at 70 % RH -> 106 F (41.1 C); 86 F at 40 % -> 85 F.
        assertEquals(41.1, FeelsLike.heatIndexC((90.0 - 32) * 5 / 9, 70.0), 0.6)
        assertEquals((85.0 - 32) * 5 / 9, FeelsLike.heatIndexC((86.0 - 32) * 5 / 9, 40.0), 0.6)
        assertEquals(FeelsLike.heatIndexC(32.0, 70.0), FeelsLike.feelsLikeC(32.0, 8.0, 70.0), 1e-9)
    }

    @Test fun neutralBand() {
        assertEquals(20.0, FeelsLike.feelsLikeC(20.0, 12.0, 90.0), 0.0)
        assertEquals(30.0, FeelsLike.feelsLikeC(30.0, 12.0, null), 0.0)
    }
}

class PowerModelTest {
    private val model = PowerModel(RiderParams(massKg = 80.0))

    @Test fun knownFlatPower() {
        // 10 m/s, no wind, flat, rho 1.225, CdA 0.32, Crr 0.005, 80 kg:
        // aero 0.5*1.225*0.32*100 = 19.6 N; rolling 80*9.80665*0.005 = 3.923 N; P = 23.523*10/0.975.
        val p = model.powerFor(10.0, 0.0, 0.0, 0.0, 1.225)
        assertEquals(23.5227 * 10.0 / 0.975, p, 0.01)
        val f = model.forces(10.0, 10.0, 0.0, 0.0, 1.225)
        assertEquals(19.6, f.aero, 1e-9)
        assertEquals(3.9227, f.rolling, 1e-3)
    }

    @Test fun inverseRoundTrip_property() {
        val rnd = Random(42)
        repeat(2000) {
            val v = 2.0 + rnd.nextDouble() * 15.0
            val hw = -8.0 + rnd.nextDouble() * 16.0
            val grade = -0.08 + rnd.nextDouble() * 0.16
            val a = -0.5 + rnd.nextDouble()
            val rho = 0.9 + rnd.nextDouble() * 0.4
            val p = model.powerFor(v, hw, grade, a, rho)
            val back = model.headwindFromPower(p, v, grade, a, rho)!!
            assertEquals("v=$v hw=$hw", hw, back, 1e-6)
        }
    }

    @Test fun tailwindFasterThanRider_negativeAirSpeed() {
        // Tailwind stronger than ground speed: air pushes the rider, F_aero < 0.
        val p = model.powerFor(5.0, -7.0, 0.0, 0.0, 1.2)
        assertEquals(-2.0, model.airSpeedFromPower(p, 5.0, 0.0, 0.0, 1.2)!!, 1e-6)
    }

    @Test fun steadySpeed() {
        val v = model.steadySpeedFor(200.0, 0.0, 0.0, 1.225)
        assertEquals(200.0, model.powerFor(v, 0.0, 0.0, 0.0, 1.225), 0.01)
        assertTrue(model.steadySpeedFor(200.0, 3.0, 0.0, 1.225) < v)
        assertNull(model.headwindFromPower(200.0, 0.0, 0.0, 0.0, 1.225))
    }
}
