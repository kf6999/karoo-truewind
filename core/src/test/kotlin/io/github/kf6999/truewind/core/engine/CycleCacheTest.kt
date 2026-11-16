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

package io.github.kf6999.truewind.core.engine

import io.github.kf6999.truewind.core.api.ForecastProvider
import io.github.kf6999.truewind.core.api.ForecastRequest
import io.github.kf6999.truewind.core.api.ForecastResult
import io.github.kf6999.truewind.core.api.ForecastSeries
import io.github.kf6999.truewind.core.api.ForecastStep
import io.github.kf6999.truewind.core.fusion.FusionFixtures.HOME
import io.github.kf6999.truewind.core.fusion.FusionFixtures.MIN
import io.github.kf6999.truewind.core.fusion.FusionFixtures.T0
import io.github.kf6999.truewind.core.fusion.FusionFixtures.offset
import io.github.kf6999.truewind.core.fusion.InMemoryKeyValueStore
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.Status
import io.github.kf6999.truewind.core.model.WindVector
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CycleCacheTest {
    private class Member(override val id: String, val wind: WindVector, val temp: Double) : ForecastProvider {
        var down = false
        override suspend fun fetch(request: ForecastRequest): ForecastResult {
            if (down) return ForecastResult(emptyList())
            val steps = (-2..16).map { i ->
                ForecastStep(request.nowMs / (15 * MIN) * (15 * MIN) + i * 15 * MIN, wind, gust10m = 6.0, temperatureC = temp,
                    relativeHumidityPct = 40.0, precipitationMm = 0.1, surfacePressureHpa = 950.0, sealevelPressureHpa = 1012.0,
                    cloudCoverPct = 20.0, weatherCode = 3)
            }
            return ForecastResult(request.points.map { ForecastSeries("$id:m", it, 460.0, steps) })
        }
    }

    private fun ctx(now: Long, rider: GeoPoint = HOME) = CycleContext(now, rider, recording = true, visible = true)

    @Test
    fun codecRoundTrip() {
        val c = CachedCycle(
            T0, HOME, mapOf("a:m" to 0.7, "b:m" to 0.3),
            listOf(ForecastSeries("a:m", HOME, 12.0, listOf(ForecastStep(T0, WindVector(1.0, -2.0), 5.0, 11.0, 50.0, 0.2, 990.0, 1012.0, 30.0, 61)))),
            emptyList(), mapOf("a:m" to T0),
        )
        assertEquals(c, CycleCacheCodec.decode(CycleCacheCodec.encode(c)))
        assertNull(CycleCacheCodec.decode("garbage"))
        assertNull(CycleCacheCodec.decode(null))
    }

    @Test
    fun restartWithoutNetwork_restoresFromStore_ifYoungerThan6h() = runBlocking {
        val store = InMemoryKeyValueStore()
        val a = Member("a", WindVector(3.0, 0.0), 20.0)
        val e1 = WindEngine(listOf(ForecastSource(a)), store = store)
        assertTrue(e1.runCycleIfDue(ctx(T0)).success)
        assertEquals(1, store.entries().keys.count { it == CycleCacheCodec.KEY }, "written once per successful cycle")

        // "Process restart": new engine, same store, phone gone.
        val e2 = WindEngine(listOf(ForecastSource(a.also { it.down = true })), store = store)
        assertEquals(Status.NO_DATA, e2.sample(T0 + 2 * 60 * MIN, HOME).status)
        assertTrue(e2.restore(T0 + 2 * 60 * MIN, HOME))
        val s = e2.sample(T0 + 2 * 60 * MIN, HOME)
        assertEquals(Status.OK, s.status)
        assertEquals(3.0, s.model10m!!.speed, 1e-6)
        assertEquals(20.0, s.weather!!.temperatureC!!, 1e-9)
        assertEquals(2 * 60 * MIN, s.ageMs, "age is the age of the cached data, not of the restore")
        // A failing cycle after the restore does not rebuild or refresh the snapshot.
        assertFalse(e2.runCycleIfDue(ctx(T0 + 2 * 60 * MIN + 1)).success)
        assertEquals(2 * 60 * MIN + 1, e2.sample(T0 + 2 * 60 * MIN + 1, HOME).ageMs)

        // Too old / too far: not restored.
        assertFalse(WindEngine(listOf(ForecastSource(a)), store = store).restore(T0 + 6 * 60 * MIN + 1, HOME))
        assertFalse(WindEngine(listOf(ForecastSource(a)), store = store).restore(T0 + MIN, offset(HOME, 300.0)))
        assertTrue(WindEngine(listOf(ForecastSource(a)), store = store).restore(T0 + 5 * 60 * MIN, HOME))
    }

    @Test
    fun restore_dropsMembersTheCurrentSettingsCannotProduce() = runBlocking {
        val store = InMemoryKeyValueStore()
        val a = Member("a", WindVector(4.0, 0.0), 20.0)
        val b = Member("b", WindVector(2.0, 0.0), 18.0)
        assertTrue(WindEngine(listOf(ForecastSource(a), ForecastSource(b)), store = store).runCycleIfDue(ctx(T0)).success)

        val onlyA = WindEngine(listOf(ForecastSource(a.also { it.down = true })), store = store,
            config = WindEngineConfig(memberAllowed = { it == "a:m" }))
        assertTrue(onlyA.restore(T0 + MIN, HOME))
        val s = onlyA.sample(T0 + MIN, HOME)
        assertEquals(4.0, s.model10m!!.speed, 1e-6)
        assertEquals(listOf("a:m"), s.sources.models)

        val none = WindEngine(listOf(ForecastSource(a)), store = store, config = WindEngineConfig(memberAllowed = { false }))
        assertFalse(none.restore(T0 + MIN, HOME))
    }

    @Test
    fun failingMemberStaysInBlend_whileAnotherSourceAnswers() = runBlocking {
        val om = Member("open-meteo", WindVector(4.0, 0.0), 20.0)
        val tm = Member("tomorrow", WindVector(2.0, 0.0), 18.0)
        val e = WindEngine(listOf(ForecastSource(om), ForecastSource(tm, optional = true, riderOnly = true)), store = InMemoryKeyValueStore())
        assertTrue(e.runCycleIfDue(ctx(T0)).success)
        assertEquals(setOf("open-meteo:m", "tomorrow:m"), e.memberWeights().map { it.first }.toSet())

        // Open-Meteo goes down; Tomorrow.io keeps answering at its own cadence.
        om.down = true
        val r = e.runCycleIfDue(ctx(T0 + 20 * MIN, offset(HOME, 3.0)))
        assertTrue(r.success, "Tomorrow.io alone still makes a cycle")
        assertEquals(setOf("open-meteo:m", "tomorrow:m"), e.memberWeights().map { it.first }.toSet(), "cached Open-Meteo member kept")

        // Everything down: no new snapshot, the old one ages and keeps its values.
        tm.down = true
        assertFalse(e.runCycleIfDue(ctx(T0 + 60 * MIN)).success)
        val s = e.sample(T0 + 3 * 60 * MIN, HOME)
        assertNotNull(s.model10m)
        assertEquals(Status.OK, s.status)
        assertTrue(s.ageMs!! >= 2 * 60 * MIN + 40 * MIN)
    }
}
