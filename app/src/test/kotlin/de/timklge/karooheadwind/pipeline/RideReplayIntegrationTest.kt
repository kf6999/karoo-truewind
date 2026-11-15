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

import de.timklge.karooheadwind.HeadingResponse
import io.github.kf6999.truewind.core.alerts.nws.NwsAlerts
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindState
import io.github.kf6999.truewind.core.time.Clock
import io.hammerhead.karooext.models.DataType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T2: a recorded-format corpus ride through SensorHub + WindRepository with many consumers. */
@OptIn(ExperimentalCoroutinesApi::class)
class RideReplayIntegrationTest {
    private val baseMs = PipelineFixtures.T0_SEC * 1000
    private val replayed = listOf(
        DataType.Type.LOCATION, DataType.Type.SPEED, DataType.Type.POWER, DataType.Type.CADENCE,
        DataType.Type.HEADING, DataType.Type.TEMPERATURE,
    )

    @Test
    fun corpusRide_oneUpstreamSubscriptionPerStream_andStateEmitsOnlyOnChange() = runTest {
        // 20 min of the sfbay fixture ride, shifted onto the PipelineFixtures forecast time.
        val ride = RideFeeder.fromCsv(RideFeeder.SFBAY_RIDE, maxRows = 1200).startingAt(baseMs)
        val source = FakeKarooStreamSource()
        val clock = Clock { baseMs + testScheduler.currentTime }
        val repo = PipelineFixtures.repository(backgroundScope, source, clock)

        // Several data fields per stream, as on a busy ride page, plus several wind consumers.
        replayed.forEach { type -> repeat(4) { backgroundScope.launch { repo.sensorHub.stream(type).collect { } } } }
        val states = mutableListOf<WindState>()
        val headings = mutableListOf<HeadingResponse>()
        backgroundScope.launch { repo.state.collect { states.add(it) } }
        backgroundScope.launch { repo.relativeHeading.collect { headings.add(it) } }
        repeat(3) { backgroundScope.launch { repo.ready.collect { } } }
        repeat(3) { backgroundScope.launch { repo.currentWeather.collect { } } }
        runCurrent()

        ride.rows.indices.forEach { i ->
            ride.emit(source, i)
            runCurrent()
            advanceTimeBy(1_000)
        }
        runCurrent()

        replayed.forEach { type -> assertEquals("upstream subscriptions for $type", 1, source.subscriptions[type]?.get()) }
        assertEquals(1, source.subscriptions["userProfile"]?.get() ?: 1)

        // Emit-on-change: GPS bearing changes nearly every second while riding, so the state may
        // recompute per fix, but never more than once per input change plus the 1-minute loop,
        // and never twice in a row with the same value.
        val minutes = ride.rows.size / 60
        assertTrue("states=${states.size}", states.size in minutes..(ride.rows.size + minutes + 2))

        // Re-sending an unchanged fix (Karoo does this every second while stopped) emits nothing.
        val before = states.size
        repeat(30) { ride.emit(source, ride.rows.lastIndex); runCurrent() }
        assertEquals(before, states.size)
        states.zipWithNext().forEach { (a, b) -> assertNotEquals("duplicate consecutive state", a, b) }
        headings.zipWithNext().forEach { (a, b) -> assertNotEquals("duplicate consecutive heading", a, b) }
        assertTrue("no heading values: $headings", headings.any { it is HeadingResponse.Value })
    }

    @Test
    fun fakeHttpClient_routesRecordsAndGoesOffline() = runTest {
        val http = FakeHttpClient().json("api.weather.gov/alerts", """{"type":"FeatureCollection","features":[]}""")
        val ok = NwsAlerts.fetch(http, GeoPoint(37.62, -122.37))
        assertEquals(null, ok.error)
        assertEquals(1, http.count("/alerts/active"))

        http.offline = true
        val off = NwsAlerts.fetch(http, GeoPoint(37.62, -122.37))
        assertTrue(off.error!!.contains("offline"))
        assertEquals(2, http.requests.size)
    }
}
