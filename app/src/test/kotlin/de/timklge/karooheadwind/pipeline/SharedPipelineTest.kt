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

package de.timklge.karooheadwind.pipeline

import de.timklge.karooheadwind.ForecastRecord
import de.timklge.karooheadwind.HeadingResponse
import de.timklge.karooheadwind.HeadwindStats
import de.timklge.karooheadwind.datatypes.GpsCoordinates
import de.timklge.karooheadwind.pipeline.PipelineFixtures.toStreamState
import io.github.kf6999.truewind.core.model.Status
import io.github.kf6999.truewind.core.model.WindState
import io.github.kf6999.truewind.core.time.Clock
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedPipelineTest {
    private val baseMs = PipelineFixtures.T0_SEC * 1000

    @Test
    fun sensorHub_oneUpstreamSubscriptionPerStream_releasedAfterTimeout() = runTest {
        val source = FakeKarooStreamSource()
        val hub = SensorHub(source, backgroundScope)

        val collectors = (1..6).map { backgroundScope.launch { hub.stream(DataType.Type.SPEED).collect { } } }
        val profileCollectors = (1..4).map { backgroundScope.launch { hub.userProfile.collect { } } }
        runCurrent()
        assertEquals(1, source.subscriptions[DataType.Type.SPEED]!!.get())
        assertEquals(1, source.subscriptions["userProfile"]!!.get())

        source.data(DataType.Type.SPEED).emit(StreamState.Streaming(DataPoint(DataType.Type.SPEED, mapOf(DataType.Field.SINGLE to 7.0))))
        runCurrent()

        (collectors + profileCollectors).forEach { it.cancel() }
        runCurrent()
        assertEquals(1, source.activeSubscriptions[DataType.Type.SPEED]!!.get())
        advanceTimeBy(SensorHub.STOP_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(0, source.activeSubscriptions[DataType.Type.SPEED]!!.get())

        // A later subscriber resubscribes once; the replay cache was cleared on release.
        source.data(DataType.Type.SPEED).resetReplayCache()
        val seen = mutableListOf<StreamState>()
        backgroundScope.launch { hub.stream(DataType.Type.SPEED).collect { seen.add(it) } }
        runCurrent()
        assertEquals(2, source.subscriptions[DataType.Type.SPEED]!!.get())
        assertTrue("stale value replayed: $seen", seen.isEmpty())
    }

    @Test
    fun manyConsumers_shareOneLocationSubscriptionAndOneState() = runTest {
        val source = FakeKarooStreamSource()
        val clock = Clock { baseMs + testScheduler.currentTime }
        val repo = PipelineFixtures.repository(backgroundScope, source, clock)

        // What six fields plus the FIT writer and wind aggregator subscribe to.
        repeat(3) { backgroundScope.launch { repo.relativeHeadingAndWeather.collect { } } }
        repeat(2) { backgroundScope.launch { repo.currentWeather.collect { } } }
        backgroundScope.launch { repo.heading.collect { } }
        backgroundScope.launch { repo.relativeHeading.collect { } }
        backgroundScope.launch { combine(repo.currentWeather, repo.sensorHub.userProfile, repo.settings) { a, _, _ -> a }.collect { } }
        backgroundScope.launch { repo.location.collect { } } // fetch loop
        runCurrent()

        source.data(DataType.Type.LOCATION).emit(PipelineFixtures.fixes[0].toStreamState())
        runCurrent()

        assertEquals(1, source.subscriptions[DataType.Type.LOCATION]!!.get())
        assertEquals(1, source.subscriptions["userProfile"]!!.get())
        assertEquals(Status.OK, repo.state.value.status)
    }

    @Test
    fun state_emitsOnInputChangeAndEveryMinute_only() = runTest {
        val source = FakeKarooStreamSource()
        val clock = Clock { baseMs + testScheduler.currentTime }
        val repo = PipelineFixtures.repository(backgroundScope, source, clock)
        val states = mutableListOf<WindState>()
        backgroundScope.launch { repo.ready.collect { states.add(it) } }
        runCurrent()
        assertEquals(1, states.size) // forecast + "no position yet"
        assertEquals(Status.NO_GPS, states.last().status)

        val fix = PipelineFixtures.fixes[0]
        source.data(DataType.Type.LOCATION).emit(fix.toStreamState())
        runCurrent()
        assertEquals(2, states.size)
        assertEquals(Status.OK, states.last().status)

        // The same fix again (Karoo re-sends the location each second while stationary): no recompute.
        repeat(10) {
            advanceTimeBy(1_000)
            source.data(DataType.Type.LOCATION).emit(fix.toStreamState())
            runCurrent()
        }
        assertEquals(2, states.size)

        // A move inside the same 2 km privacy cell with the same bearing: still no recompute.
        source.data(DataType.Type.LOCATION).emit(fix.copy(lat = fix.lat + 0.0005).toStreamState())
        runCurrent()
        assertEquals(2, states.size)

        // 1-minute loop
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, states.size)
    }

    @Test
    fun derivedFlows_skipPlaceholderAndDuplicates() = runTest {
        val source = FakeKarooStreamSource()
        val clock = Clock { baseMs + testScheduler.currentTime }
        val repo = PipelineFixtures.repository(
            backgroundScope, source, clock,
            record = ForecastRecord(HeadwindStats(), null),
            lastKnown = GpsCoordinates(37.7, -122.45, 90.0),
        )
        val headings = mutableListOf<HeadingResponse>()
        backgroundScope.launch { repo.relativeHeading.collect { headings.add(it) } }
        runCurrent()
        assertEquals(listOf<HeadingResponse>(HeadingResponse.NoGps, HeadingResponse.NoWeatherData), headings)
        assertFalse(repo.state.value.isPlaceholder)
        assertEquals(Status.NO_DATA, repo.state.value.status)

        assertEquals(HeadingResponse.Value(90.0), repo.heading.first())
    }
}
