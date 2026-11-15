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

package de.timklge.karooheadwind.datatypes

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Emit-on-change thresholds (SPEC 3 rule 4): 0.5 display unit, 3 degrees, band change. */
class EmitGateTest {
    @Test
    fun speed_halfUnitThreshold() {
        val g = EmitGate(EmitGate.SPEED_THRESHOLD)
        assertTrue(g.shouldEmit(10.0))
        assertFalse(g.shouldEmit(10.4))
        assertFalse(g.shouldEmit(9.6))
        assertTrue(g.shouldEmit(10.5))
        // Measured from the last emitted value, so slow drift still emits eventually.
        assertFalse(g.shouldEmit(10.8))
        assertTrue(g.shouldEmit(11.0))
    }

    @Test
    fun angle_threeDegreesAcrossNorth() {
        val g = EmitGate(EmitGate.ANGLE_THRESHOLD, angle = true)
        assertTrue(g.shouldEmit(359.0))
        assertFalse(g.shouldEmit(1.0))
        assertTrue(g.shouldEmit(2.0))
        assertEquals(4.0, EmitGate.circularDiff(358.0, 2.0), 1e-9)
    }

    @Test
    fun bandChangeAndAppearance() {
        val g = EmitGate(EmitGate.SPEED_THRESHOLD)
        assertTrue(g.shouldEmit(5.0, "HIGH"))
        assertTrue(g.shouldEmit(5.1, "MEDIUM"))
        assertFalse(g.shouldEmit(5.2, "MEDIUM"))
        assertTrue(g.shouldEmit(null, "MEDIUM"))
        assertFalse(g.shouldEmit(null, "MEDIUM"))
        assertTrue(g.shouldEmit(5.2, "MEDIUM"))
    }

    @Test
    fun zeroThreshold_anyChange() {
        val g = EmitGate()
        assertTrue(g.shouldEmit(1.0))
        assertFalse(g.shouldEmit(1.0))
        assertTrue(g.shouldEmit(1.001))
    }

    @Test
    fun viewRateLimiter_onePerSecond() {
        val l = ViewRateLimiter(1_000)
        assertTrue(l.tryAcquire(0))
        assertFalse(l.tryAcquire(999))
        assertEquals(1L, l.waitMs(999))
        assertTrue(l.tryAcquire(1_000))
    }
}
