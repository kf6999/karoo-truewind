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

import de.timklge.karooheadwind.HeadwindSettings
import de.timklge.karooheadwind.datatypes.GpsCoordinates
import io.github.kf6999.truewind.core.api.ForecastProvider
import io.github.kf6999.truewind.core.api.ForecastRequest
import io.github.kf6999.truewind.core.api.ForecastResult
import io.github.kf6999.truewind.core.api.ForecastSeries
import io.github.kf6999.truewind.core.api.ForecastStep
import io.github.kf6999.truewind.core.engine.ForecastSource
import io.github.kf6999.truewind.core.engine.WindEngine
import io.github.kf6999.truewind.core.fusion.InMemoryKeyValueStore
import io.github.kf6999.truewind.core.model.Status
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.time.Clock
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tick start/stop (SPEC 3 rule 3, 4.1) and the fetch loop wiring, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrueWindPipelineTest {
    private val t0 = PipelineFixtures.T0_SEC * 1000

    /** Constant 5 m/s from the north for 6 h at 15-min steps. */
    private class ConstantProvider(private val t0: Long) : ForecastProvider {
        val calls = AtomicInteger()
        override val id = "fake"
        override suspend fun fetch(request: ForecastRequest): ForecastResult {
            calls.incrementAndGet()
            val steps = (-4..24).map { i ->
                ForecastStep(t0 + i * 15 * 60_000L, WindVector.fromSpeedDirection(5.0, 0.0), gust10m = 8.0, temperatureC = 12.0,
                    relativeHumidityPct = 60.0, surfacePressureHpa = 1013.0, sealevelPressureHpa = 1013.0, precipitationMm = 0.0)
            }
            return ForecastResult(request.points.map { p -> ForecastSeries("fake:model", p, steps = steps) })
        }
    }

    private class Source : KarooStreamSource {
        val ride = MutableStateFlow<RideState>(RideState.Idle)
        val page = MutableSharedFlow<ActiveRidePage>(replay = 1)
        override fun dataStream(dataTypeId: String): Flow<StreamState> = emptyFlow()
        override fun userProfile(): Flow<UserProfile> = flowOf(PipelineFixtures.metricProfile)
        override fun rideState(): Flow<RideState> = ride
        override fun navigationState(): Flow<OnNavigationState> = emptyFlow()
        override fun activeRidePage(): Flow<ActiveRidePage> = page
    }

    private class Harness(scope: TestScope, t0: Long, initial: HeadwindSettings) {
        val settings = MutableStateFlow(initial)
        val screen = MutableStateFlow(false)
        val streams = MutableStateFlow(0)
        val source = Source()
        val provider = ConstantProvider(t0)
        val pipeline = TrueWindPipeline(
            scope = scope.backgroundScope,
            hub = SensorHub(source, scope.backgroundScope),
            settingsFlow = settings,
            forecastRecordFlow = flowOf(null),
            lastKnownPosition = { GpsCoordinates(37.6, -122.4) },
            engineFactory = { WindEngine(listOf(ForecastSource(provider)), store = InMemoryKeyValueStore()) },
            store = InMemoryKeyValueStore(),
            clock = Clock { t0 + scope.testScheduler.currentTime },
            screenOpen = screen,
            activeStreams = streams,
        )
    }

    @Test
    fun fieldStream_beforeAnyRidePage_startsTick() = runTest {
        val h = Harness(this, t0, trueWind)
        h.pipeline.start()
        h.streams.value = 1
        advanceTimeBy(2_500); runCurrent()
        assertTrue(h.pipeline.running.value)
        h.streams.value = 0
        runCurrent()
        assertFalse(h.pipeline.running.value)
    }

    private val trueWind = HeadwindSettings(welcomeDialogAccepted = true, upstreamCompatibleMode = false)

    @Before
    fun reset() = Diagnostics.resetForTest()

    @Test
    fun idle_whenNothingVisibleAndNotRecording() = runTest {
        val h = Harness(this, t0, trueWind)
        h.pipeline.start()
        advanceTimeBy(5_000); runCurrent()
        assertFalse(h.pipeline.running.value)
        assertNull(h.pipeline.snapshots.value)
        assertEquals(0, h.provider.calls.get())
    }

    @Test
    fun screenOpen_startsTickAndCycle_closingStopsTick() = runTest {
        val h = Harness(this, t0, trueWind)
        h.pipeline.start()
        runCurrent()
        h.screen.value = true
        advanceTimeBy(3_500); runCurrent()
        assertTrue(h.pipeline.running.value)
        assertEquals(1, h.provider.calls.get(), "one cycle on start")
        val snap = assertNotNull(h.pipeline.snapshots.value)
        assertEquals(Status.OK, snap.state.status)
        assertEquals(5.0, snap.state.model10m!!.speed, 0.01)
        assertFalse(snap.extras.upstreamMode)
        val ticksWhileOpen = Diagnostics.ticks.get()
        assertTrue(ticksWhileOpen in 3..5, "about 1 Hz, got $ticksWhileOpen")

        h.screen.value = false
        runCurrent()
        assertFalse(h.pipeline.running.value)
        val frozen = h.pipeline.snapshots.value
        advanceTimeBy(10_000); runCurrent()
        assertEquals(ticksWhileOpen, Diagnostics.ticks.get(), "no ticks while hidden")
        assertTrue(frozen === h.pipeline.snapshots.value)
    }

    @Test
    fun recording_keepsTickRunning() = runTest {
        val h = Harness(this, t0, trueWind)
        h.pipeline.start()
        h.source.ride.value = RideState.Recording
        advanceTimeBy(2_500); runCurrent()
        assertTrue(h.pipeline.running.value)
        assertNotNull(h.pipeline.snapshots.value)
    }

    @Test
    fun upstreamMode_neverRuns() = runTest {
        val h = Harness(this, t0, trueWind.copy(upstreamCompatibleMode = true))
        h.pipeline.start()
        h.screen.value = true
        h.source.ride.value = RideState.Recording
        advanceTimeBy(5_000); runCurrent()
        assertFalse(h.pipeline.running.value)
        assertEquals(0, h.provider.calls.get())
        assertEquals(0L, Diagnostics.ticks.get())
    }

    @Test
    fun notSetUp_neverRuns() = runTest {
        val h = Harness(this, t0, trueWind.copy(welcomeDialogAccepted = false))
        h.pipeline.start()
        h.screen.value = true
        advanceTimeBy(5_000); runCurrent()
        assertFalse(h.pipeline.running.value)
    }

    @Test
    fun noRepeatCycleWithinInterval_forceCycleRunsOne() = runTest {
        val h = Harness(this, t0, trueWind)
        h.pipeline.start()
        h.screen.value = true
        advanceTimeBy(5 * 60_000L); runCurrent()
        assertEquals(1, h.provider.calls.get(), "10-min interval: no second fetch after 5 min")
        h.pipeline.forceCycle()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, h.provider.calls.get())
        assertNotNull(Diagnostics.nextCheckAtMs)
    }
}
