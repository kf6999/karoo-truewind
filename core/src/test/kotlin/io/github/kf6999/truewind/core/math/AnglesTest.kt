/*
 * Copyright 2024-2026 karoo-headwind contributors
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
 *
 * Modified by TrueWind: lerpAngle cases moved from karoo-headwind's DataStoreTest; added tests
 * for the new helpers and seeded property tests.
 */

package io.github.kf6999.truewind.core.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AnglesTest {
    // --- lerpAngle (cases from upstream DataStoreTest) ---

    @Test fun lerpAngle_simple() = assertEquals(135.0, lerpAngle(90.0, 180.0, 0.5), 0.001)
    @Test fun lerpAngle_crossesZeroForward() = assertEquals(0.0, lerpAngle(350.0, 10.0, 0.5), 0.001)
    @Test fun lerpAngle_crossesZeroBackward() = assertEquals(0.0, lerpAngle(10.0, 350.0, 0.5), 0.001)
    @Test fun lerpAngle_shortestPath() = assertEquals(0.0, lerpAngle(45.0, 315.0, 0.5), 0.001)

    @Test fun lerpAngle_opposite() {
        assertEquals(90.0, lerpAngle(0.0, 180.0, 0.5), 0.001)
        assertEquals(90.0, lerpAngle(180.0, 0.0, 0.5), 0.001)
    }

    @Test fun lerpAngle_endpoints() {
        assertEquals(350.0, lerpAngle(350.0, 10.0, 0.0), 0.001)
        assertEquals(0.0, lerpAngle(350.0, 10.0, 0.5), 0.001)
        assertEquals(10.0, lerpAngle(350.0, 10.0, 1.0), 0.001)
    }

    @Test fun lerpAngle_negativeInput() = assertEquals(0.0, lerpAngle(-10.0, 10.0, 0.5), 0.001)
    @Test fun lerpAngle_above360() = assertEquals(15.0, lerpAngle(370.0, 380.0, 0.5), 0.001)

    // --- signedAngleDifference (upstream semantics: angle2 - angle1 wrapped) ---

    @Test fun signedAngleDifference_basic() {
        assertEquals(10.0, signedAngleDifference(0.0, 10.0), 1e-12)
        assertEquals(-10.0, signedAngleDifference(10.0, 0.0), 1e-12)
        assertEquals(20.0, signedAngleDifference(350.0, 10.0), 1e-12)
        assertEquals(-20.0, signedAngleDifference(10.0, 350.0), 1e-12)
        assertEquals(0.0, signedAngleDifference(123.0, 123.0), 1e-12)
    }

    @Test fun signedAngleDifference_exactly180() {
        assertEquals(180.0, signedAngleDifference(0.0, 180.0), 1e-12)
        assertEquals(-180.0, signedAngleDifference(180.0, 0.0), 1e-12)
    }

    @Test fun signedAngleDifference_inputsAbove360() {
        // windFrom + 180 can exceed 360 (karoo-headwind passes it unreduced)
        assertEquals(0.0, signedAngleDifference(90.0, 450.0), 1e-12)
        assertEquals(-90.0, signedAngleDifference(90.0, 360.0), 1e-12)
        assertEquals(170.0, signedAngleDifference(10.0, 180.0 + 360.0), 1e-12)
    }

    @Test fun signedAngleDifference_property_matchesNormalisedDifference() {
        val rnd = Random(42)
        repeat(10_000) {
            val a = rnd.nextDouble(0.0, 360.0)
            val b = rnd.nextDouble(0.0, 540.0) // up to windFrom(360) + 180
            val d = signedAngleDifference(a, b)
            assertTrue("range $d", d >= -180.0 && d <= 180.0)
            // a + d == b (mod 360)
            val residual = normalizeSignedDegrees(a + d - b)
            assertEquals(0.0, residual, 1e-9)
        }
    }

    // --- normalisation ---

    @Test fun normalizeDegrees_values() {
        assertEquals(0.0, normalizeDegrees(0.0), 0.0)
        assertEquals(0.0, normalizeDegrees(360.0), 0.0)
        assertEquals(350.0, normalizeDegrees(-10.0), 1e-12)
        assertEquals(10.0, normalizeDegrees(730.0), 1e-12)
        assertEquals(0.0, normalizeDegrees(-1e-17), 0.0)
        assertTrue(normalizeDegrees(Double.NaN).isNaN())
    }

    @Test fun normalizeSignedDegrees_values() {
        assertEquals(180.0, normalizeSignedDegrees(180.0), 0.0)
        assertEquals(180.0, normalizeSignedDegrees(-180.0), 0.0)
        assertEquals(-170.0, normalizeSignedDegrees(190.0), 1e-12)
        assertEquals(10.0, normalizeSignedDegrees(370.0), 1e-12)
    }

    @Test fun normalize_property_rangeAndIdempotent() {
        val rnd = Random(7)
        repeat(10_000) {
            val x = rnd.nextDouble(-5000.0, 5000.0)
            val n = normalizeDegrees(x)
            assertTrue(n >= 0.0 && n < 360.0)
            assertEquals(n, normalizeDegrees(n), 1e-9)
            val s = normalizeSignedDegrees(x)
            assertTrue(s > -180.0 && s <= 180.0)
        }
    }

    // --- circular mean and smoothing across the wrap ---

    @Test fun circularMean_acrossWrap() {
        assertEquals(0.0, normalizeSignedDegrees(circularMeanDegrees(listOf(350.0, 10.0))!!), 1e-9)
        assertEquals(355.0, circularMeanDegrees(listOf(350.0, 0.0))!!, 1e-9)
        assertEquals(90.0, circularMeanDegrees(listOf(80.0, 100.0))!!, 1e-9)
    }

    @Test fun circularMean_weighted() {
        // weight 3:1 between 0 and 90 -> atan2(1, 3)
        val expected = Math.toDegrees(kotlin.math.atan2(1.0, 3.0))
        assertEquals(expected, circularMeanDegrees(listOf(0.0, 90.0), listOf(3.0, 1.0))!!, 1e-9)
    }

    @Test fun circularMean_degenerate() {
        assertNull(circularMeanDegrees(emptyList()))
        assertNull(circularMeanDegrees(listOf(0.0, 180.0)))
    }

    @Test fun smoothAngle_takesShortArc() {
        assertEquals(0.0, smoothAngle(350.0, 10.0, 0.5), 1e-9)
        assertEquals(355.0, smoothAngle(350.0, 10.0, 0.25), 1e-9)
        assertEquals(10.0, smoothAngle(350.0, 10.0, 1.0), 1e-9)
        assertEquals(350.0, smoothAngle(350.0, 10.0, 0.0), 1e-9)
    }

    @Test fun smoothAngle_property_convergesWithoutSwinging() {
        val rnd = Random(3)
        repeat(500) {
            var h = rnd.nextDouble(0.0, 360.0)
            val target = rnd.nextDouble(0.0, 360.0)
            var lastDistance = kotlin.math.abs(normalizeSignedDegrees(target - h))
            repeat(50) {
                h = smoothAngle(h, target, 0.3)
                val dist = kotlin.math.abs(normalizeSignedDegrees(target - h))
                assertTrue(dist <= lastDistance + 1e-9)
                lastDistance = dist
            }
            assertTrue(lastDistance < 1e-5)
        }
    }
}
