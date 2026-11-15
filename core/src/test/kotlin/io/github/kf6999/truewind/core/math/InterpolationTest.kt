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

package io.github.kf6999.truewind.core.math

import io.github.kf6999.truewind.core.model.WindVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterpolationTest {
    @Test fun lerpDouble() {
        assertEquals(5.0, lerp(0.0, 10.0, 0.5), 0.0)
        assertEquals(0.0, lerp(0.0, 10.0, 0.0), 0.0)
        assertEquals(10.0, lerp(0.0, 10.0, 1.0), 0.0)
        assertEquals(-5.0, lerp(0.0, -10.0, 0.5), 0.0)
    }

    @Test fun lerpInt_truncates() {
        assertEquals(5, lerp(0, 10, 0.59))
        assertEquals(-5, lerp(0, -10, 0.59)) // toInt truncates towards zero
        assertEquals(60, lerp(50, 70, 0.5))
    }

    @Test fun lerpNullable_cases() {
        assertNull(lerpNullable(null, null, 0.5))
        assertEquals(3.0, lerpNullable(null, 3.0, 0.2)!!, 0.0)
        assertEquals(2.0, lerpNullable(2.0, null, 0.8)!!, 0.0)
        assertEquals(2.5, lerpNullable(2.0, 3.0, 0.5)!!, 0.0)
    }

    @Test fun lerpVector_isComponentWise() {
        // From north at 5 m/s to from south at 5 m/s: halfway is calm, not "from east/west".
        val a = WindVector.fromSpeedDirection(5.0, 0.0)
        val b = WindVector.fromSpeedDirection(5.0, 180.0)
        assertEquals(0.0, lerp(a, b, 0.5).speed, 1e-12)
        assertEquals(WindVector(1.0, 2.0), lerp(WindVector(0.0, 0.0), WindVector(2.0, 4.0), 0.5))
    }

    @Test fun timeFactor_cases() {
        assertEquals(0.5, timeFactor(150, 100, 200), 0.0)
        assertEquals(0.0, timeFactor(50, 100, 200), 0.0)
        assertEquals(1.0, timeFactor(250, 100, 200), 0.0)
        assertEquals(0.0, timeFactor(100, 100, 100), 0.0)
    }
}
