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

package io.github.kf6999.truewind.core.wind

import io.github.kf6999.truewind.core.model.WindState
import io.github.kf6999.truewind.core.time.ManualClock
import io.github.kf6999.truewind.core.time.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelativeWindTest {
    @Test fun relativeBearing_convention() {
        // Rider north, wind from north: wind blows TO south, relative bearing +-180 (headwind).
        assertEquals(180.0, Math.abs(RelativeWind.relativeBearing(0.0, 0.0)), 1e-12)
        // Rider north, wind from south: blows TO north, relative bearing 0 (tailwind).
        assertEquals(0.0, RelativeWind.relativeBearing(0.0, 180.0), 1e-12)
        // Rider north, wind from east: blows TO west, -90.
        assertEquals(-90.0, RelativeWind.relativeBearing(0.0, 90.0), 1e-12)
    }

    @Test fun components() {
        assertEquals(5.0, RelativeWind.headwind(RelativeWind.relativeBearing(0.0, 0.0), 5.0), 1e-12)
        assertEquals(-5.0, RelativeWind.headwind(RelativeWind.relativeBearing(0.0, 180.0), 5.0), 1e-12)
        assertEquals(5.0, RelativeWind.crosswind(RelativeWind.relativeBearing(0.0, 90.0), 5.0), 1e-12)
        assertEquals(-5.0, RelativeWind.crosswind(RelativeWind.relativeBearing(0.0, 270.0), 5.0), 1e-12)
    }

    @Test fun relativeDirectionFrom_zeroIsDeadAhead() {
        assertEquals(0.0, RelativeWind.relativeDirectionFrom(RelativeWind.relativeBearing(30.0, 30.0)), 1e-12)
        assertEquals(90.0, RelativeWind.relativeDirectionFrom(RelativeWind.relativeBearing(0.0, 90.0)), 1e-12)
        assertEquals(270.0, RelativeWind.relativeDirectionFrom(RelativeWind.relativeBearing(0.0, 270.0)), 1e-12)
        assertEquals(180.0, RelativeWind.relativeDirectionFrom(RelativeWind.relativeBearing(10.0, 190.0)), 1e-12)
    }
}

class ClockAndStateTest {
    @Test fun manualClock() {
        val c = ManualClock(1_000)
        assertEquals(1_000, c.nowMs())
        c.advanceBy(500)
        assertEquals(1_500, c.nowMs())
        c.set(42)
        assertEquals(42, c.nowMs())
    }

    @Test fun systemClockIsWallTime() {
        val before = System.currentTimeMillis()
        val now = Clock.SYSTEM.nowMs()
        assertTrue(now >= before)
    }

    @Test fun placeholder() {
        assertTrue(WindState.PLACEHOLDER.isPlaceholder)
        assertFalse(WindState.PLACEHOLDER.copy(timeMs = 0).isPlaceholder)
    }
}
