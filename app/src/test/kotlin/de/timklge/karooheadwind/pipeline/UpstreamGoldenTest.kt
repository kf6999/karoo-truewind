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
import de.timklge.karooheadwind.datatypes.HeadwindDirectionDataType
import de.timklge.karooheadwind.datatypes.HeadwindSpeedDataType
import de.timklge.karooheadwind.datatypes.TemperatureDataType
import de.timklge.karooheadwind.datatypes.WindDirectionDataType
import de.timklge.karooheadwind.datatypes.WindGustsDataType
import de.timklge.karooheadwind.datatypes.WindSpeedDataType
import de.timklge.karooheadwind.fit.HeadwindFitFileWriter
import de.timklge.karooheadwind.pipeline.PipelineFixtures.toStreamState
import de.timklge.karooheadwind.util.msInWindUnit
import io.github.kf6999.truewind.core.model.Status
import io.github.kf6999.truewind.core.time.Clock
import io.hammerhead.karooext.models.DataType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos

/**
 * WP1 golden / characterisation test (TESTING.md section 4, T2): a fixed ride (positions,
 * bearings, a stop, a bad fix, an hourly step boundary) and a fixed route forecast are fed through
 *  (a) a frozen copy of karoo-headwind's computation ([UpstreamReference]) and
 *  (b) the new single pipeline (SensorHub -> WindRepository -> data type mappers),
 * and every field value must be identical (exact double equality, not a tolerance).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpstreamGoldenTest {
    private val baseMs = (PipelineFixtures.T0_SEC) * 1000

    private class Oracle(val settings: HeadwindSettings) {
        var location: GpsCoordinates? = null
        var lastChangeMs: Long? = null

        fun accept(fix: PipelineFixtures.Fix) {
            if (fix.accuracy >= 500) return // upstream mapNotNull drops it
            val rounded = UpstreamReference.round(GpsCoordinates(fix.lat, fix.lon, fix.bearing), settings.roundLocationTo.km.toDouble())
            if (rounded != location) {
                location = rounded
                lastChangeMs = fix.tSec * 1000
            }
        }

        /** Upstream recomputes on each input change and then once per minute. */
        fun computeTimeAt(nowMs: Long): Long {
            val changed = lastChangeMs ?: return nowMs
            return changed + ((nowMs - changed) / 60_000) * 60_000
        }
    }

    private fun TestScope.runRide(settings: HeadwindSettings, check: (fix: PipelineFixtures.Fix?, nowMs: Long, state: io.github.kf6999.truewind.core.model.WindState, oracle: Oracle) -> Unit) {
        val source = FakeKarooStreamSource()
        val clock = Clock { baseMs + testScheduler.currentTime }
        val repo = PipelineFixtures.repository(backgroundScope, source, clock, settings = settings)
        backgroundScope.launch { repo.state.collect { } }
        runCurrent()

        val oracle = Oracle(settings)
        for (fix in PipelineFixtures.fixes) {
            advanceTimeBy(fix.tSec * 1000 - baseMs - testScheduler.currentTime)
            runCurrent()
            source.data(DataType.Type.LOCATION).tryEmit(fix.toStreamState())
            runCurrent()
            oracle.accept(fix)
            check(fix, fix.tSec * 1000, repo.state.value, oracle)
        }

        // Minute loop keeps time interpolation going without new fixes.
        repeat(5) {
            advanceTimeBy(60_000)
            runCurrent()
            check(null, baseMs + testScheduler.currentTime, repo.state.value, oracle)
        }
    }

    @Test
    fun pipelineMatchesUpstream_metric() = runTest {
        val settings = PipelineFixtures.settings
        val profile = PipelineFixtures.metricProfile
        val repoForTypes = PipelineFixtures.repository(backgroundScope, FakeKarooStreamSource(), Clock.SYSTEM)
        val windSpeedType = WindSpeedDataType(repoForTypes)
        val gustType = WindGustsDataType(repoForTypes)
        val windDirectionType = WindDirectionDataType(repoForTypes)
        val temperatureType = TemperatureDataType(repoForTypes)

        var checked = 0
        runRide(settings) { fix, nowMs, state, oracle ->
            val expectedTime = oracle.computeTimeAt(nowMs)
            assertEquals("compute time at $fix", expectedTime, state.timeMs)

            val expectedWeather = UpstreamReference.currentWeather(PipelineFixtures.response, oracle.location, expectedTime)
            val weather = state.weatherData()
            assertEquals("weather at $fix", expectedWeather, weather)

            // headwindSpeed (numeric field)
            val headwindSpeed = HeadwindSpeedDataType.computeValue(
                HeadwindSpeedDataType.StreamData(state.relativeHeadingResponse(), weather, settings, isImperial = false)
            )
            assertEquals("headwindSpeed at $fix", UpstreamReference.headwindSpeedValue(oracle.location, expectedWeather, settings, false), headwindSpeed)

            // headwind (graphical arrow) stream value
            val direction = HeadwindDirectionDataType.computeValue(
                HeadwindDirectionDataType.StreamData(state.relativeHeadingResponse(), weather?.windDirection, weather?.windSpeed, settings)
            )
            assertEquals("headwind direction at $fix", UpstreamReference.headwindDirectionValue(oracle.location, expectedWeather, settings), direction, 0.0)

            // simple numeric fields
            assertEquals(expectedWeather?.let { msInWindUnit(it.windSpeed, settings.getWindUnit(false)) }, windSpeedType.computeValue(weather, profile, settings))
            assertEquals(expectedWeather?.let { msInWindUnit(it.windGusts, settings.getWindUnit(false)) }, gustType.computeValue(weather, profile, settings))
            assertEquals(expectedWeather?.windDirection, windDirectionType.computeValue(weather, profile, settings))
            assertEquals(expectedWeather?.temperature, temperatureType.computeValue(weather, profile, settings))

            // FIT record values (headwind speed / direction developer fields)
            val relative = UpstreamReference.relativeHeading(oracle.location, expectedWeather)
            assertEquals(
                HeadwindFitFileWriter.buildRecordValues(relative, expectedWeather?.windSpeed, expectedWeather?.windDirection, expectedWeather?.windGusts),
                HeadwindFitFileWriter.buildRecordValues(
                    (state.relativeHeadingResponse() as? de.timklge.karooheadwind.HeadingResponse.Value)?.diff,
                    weather?.windSpeed, weather?.windDirection, weather?.windGusts,
                ),
            )

            // WindState's own headline numbers use the same expression
            if (relative != null && expectedWeather != null) {
                assertEquals(cos((relative + 180) * Math.PI / 180.0) * expectedWeather.windSpeed, state.headwind!!, 0.0)
                assertEquals(Status.OK, state.status)
            } else {
                assertNull(state.headwind)
            }
            assertEquals(oracle.location?.bearing, state.riderHeadingDeg)
            checked++
        }
        assertEquals(PipelineFixtures.fixes.size + 5, checked)
    }

    @Test
    fun pipelineMatchesUpstream_notSetUpAndImperialUnits() = runTest {
        val settings = PipelineFixtures.settings.copy(welcomeDialogAccepted = false, windUnit = de.timklge.karooheadwind.WindUnit.KNOTS)
        runRide(settings) { fix, nowMs, state, oracle ->
            val expectedWeather = UpstreamReference.currentWeather(PipelineFixtures.response, oracle.location, oracle.computeTimeAt(nowMs))
            val weather = state.weatherData()
            assertEquals(expectedWeather, weather)
            assertEquals(Status.NOT_SET_UP, state.status)
            assertEquals(
                UpstreamReference.headwindSpeedValue(oracle.location, expectedWeather, settings, true),
                HeadwindSpeedDataType.computeValue(HeadwindSpeedDataType.StreamData(state.relativeHeadingResponse(), weather, settings, true)),
            )
            assertEquals(
                UpstreamReference.headwindDirectionValue(oracle.location, expectedWeather, settings),
                HeadwindDirectionDataType.computeValue(HeadwindDirectionDataType.StreamData(state.relativeHeadingResponse(), weather?.windDirection, weather?.windSpeed, settings)),
                0.0,
            )
        }
    }
}
