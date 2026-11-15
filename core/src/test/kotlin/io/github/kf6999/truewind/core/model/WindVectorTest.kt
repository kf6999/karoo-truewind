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

package io.github.kf6999.truewind.core.model

import io.github.kf6999.truewind.core.math.normalizeSignedDegrees
import io.github.kf6999.truewind.core.wind.RelativeWind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WindVectorTest {
    private val eps = 1e-9

    @Test fun fromNorth_blowsSouth() {
        val w = WindVector.fromSpeedDirection(5.0, 0.0)
        assertEquals(0.0, w.u, eps)
        assertEquals(-5.0, w.v, eps)
        assertEquals(0.0, w.directionFromDeg, eps)
        assertEquals(180.0, w.directionToDeg, eps)
    }

    @Test fun fromWest_blowsEast() {
        val w = WindVector.fromSpeedDirection(3.0, 270.0)
        assertEquals(3.0, w.u, eps)
        assertEquals(0.0, w.v, eps)
        assertEquals(270.0, w.directionFromDeg, eps)
    }

    @Test fun calm() {
        assertEquals(0.0, WindVector.ZERO.speed, 0.0)
        assertEquals(0.0, WindVector.ZERO.directionFromDeg, 0.0)
    }

    @Test fun roundTrip_property() {
        val rnd = Random(11)
        repeat(10_000) {
            val speed = rnd.nextDouble(0.01, 40.0)
            val dir = rnd.nextDouble(0.0, 360.0)
            val w = WindVector.fromSpeedDirection(speed, dir)
            assertEquals(speed, w.speed, 1e-9)
            assertEquals(0.0, normalizeSignedDegrees(w.directionFromDeg - dir), 1e-7)
            assertEquals(0.0, normalizeSignedDegrees(w.directionToDeg - dir - 180.0), 1e-7)
        }
    }

    @Test fun arithmetic() {
        val a = WindVector(1.0, 2.0)
        val b = WindVector(3.0, -1.0)
        assertEquals(WindVector(4.0, 1.0), a + b)
        assertEquals(WindVector(-2.0, 3.0), a - b)
        assertEquals(WindVector(2.0, 4.0), a * 2.0)
        assertEquals(WindVector(0.5, 1.0), a / 2.0)
        assertEquals(WindVector(-1.0, -2.0), -a)
        assertEquals(1.0, a.dot(b), 0.0)
    }

    /** Signed head/cross decomposition for all quadrants: (wind from, heading) -> (headwind, crosswind). */
    @Test fun headAndCross_allQuadrants() {
        data class Case(val from: Double, val heading: Double, val head: Double, val cross: Double)
        val s = 10.0
        val cases = listOf(
            Case(0.0, 0.0, s, 0.0),      // riding north into a north wind
            Case(0.0, 180.0, -s, 0.0),   // riding south, north wind behind
            Case(90.0, 0.0, 0.0, s),     // riding north, wind from east = from the right
            Case(270.0, 0.0, 0.0, -s),   // from west = from the left
            Case(0.0, 90.0, 0.0, -s),    // riding east, north is on the left
            Case(0.0, 270.0, 0.0, s),    // riding west, north is on the right
            Case(45.0, 0.0, s * Math.sqrt(0.5), s * Math.sqrt(0.5)),
            Case(135.0, 0.0, -s * Math.sqrt(0.5), s * Math.sqrt(0.5)),
            Case(225.0, 0.0, -s * Math.sqrt(0.5), -s * Math.sqrt(0.5)),
            Case(315.0, 0.0, s * Math.sqrt(0.5), -s * Math.sqrt(0.5)),
            Case(200.0, 200.0, s, 0.0),
        )
        for (c in cases) {
            val w = WindVector.fromSpeedDirection(s, c.from)
            assertEquals("head $c", c.head, w.headwind(c.heading), 1e-9)
            assertEquals("cross $c", c.cross, w.crosswind(c.heading), 1e-9)
        }
    }

    @Test fun vectorDecomposition_matchesUpstreamFormula_property() {
        val rnd = Random(5)
        repeat(10_000) {
            val speed = rnd.nextDouble(0.0, 30.0)
            val from = rnd.nextDouble(0.0, 360.0)
            val heading = rnd.nextDouble(0.0, 360.0)
            val w = WindVector.fromSpeedDirection(speed, from)
            val rel = RelativeWind.relativeBearing(heading, from)
            assertEquals(RelativeWind.headwind(rel, speed), w.headwind(heading), 1e-9)
            assertEquals(RelativeWind.crosswind(rel, speed), w.crosswind(heading), 1e-9)
            val h2 = w.headwind(heading) * w.headwind(heading) + w.crosswind(heading) * w.crosswind(heading)
            assertTrue(Math.abs(h2 - speed * speed) < 1e-6)
        }
    }
}
