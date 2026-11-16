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
import de.timklge.karooheadwind.HeadwindSettings
import de.timklge.karooheadwind.HeadwindStats
import de.timklge.karooheadwind.WeatherDataProvider
import de.timklge.karooheadwind.datatypes.FieldInputs
import de.timklge.karooheadwind.datatypes.GpsCoordinates
import de.timklge.karooheadwind.datatypes.TrueWindFields
import de.timklge.karooheadwind.weatherprovider.WeatherData
import de.timklge.karooheadwind.weatherprovider.WeatherDataForLocation
import de.timklge.karooheadwind.weatherprovider.WeatherDataResponse
import io.github.kf6999.truewind.core.api.ForecastProvider
import io.github.kf6999.truewind.core.api.ForecastRequest
import io.github.kf6999.truewind.core.api.ForecastResult
import io.github.kf6999.truewind.core.api.ForecastSeries
import io.github.kf6999.truewind.core.api.ForecastStep
import io.github.kf6999.truewind.core.api.HttpRequest
import io.github.kf6999.truewind.core.api.HttpResponse
import io.github.kf6999.truewind.core.engine.ForecastSource
import io.github.kf6999.truewind.core.engine.SourceStatus
import io.github.kf6999.truewind.core.engine.WindEngine
import io.github.kf6999.truewind.core.fusion.InMemoryKeyValueStore
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.Status
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.providers.radar.PngDecoder
import io.github.kf6999.truewind.core.time.Clock
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Field report 2026-11-15: no internet on the road (DNS failures), "the weather disappeared".
 * One successful cycle, then hours of transport errors while the rider moves 15 km and climbs
 * 200 m: every data type that had a value must keep one (STALE / low confidence is fine).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineRideTest {
    private val t0 = PipelineFixtures.T0_SEC * 1000
    private val start = GeoPoint(37.70, -122.45)
    private val trueWind = HeadwindSettings(welcomeDialogAccepted = true, upstreamCompatibleMode = false)
    private val dns = "Unable to resolve host \"api.open-meteo.com\": No address associated with hostname"

    @Before
    fun reset() = Diagnostics.resetForTest()

    /** 4 h of 15-min steps from fetch time (like the Open-Meteo nowcast), reached over [http]. */
    private class NetProvider(val http: FakeHttpClient) : ForecastProvider {
        override val id = "fake"
        @Volatile var lastError: String? = null
        override suspend fun fetch(request: ForecastRequest): ForecastResult {
            val r = http.request(HttpRequest("GET", "https://api.open-meteo.com/v1/forecast"))
            lastError = r.error
            if (!r.isSuccessful) return ForecastResult(emptyList())
            val now = request.nowMs
            val steps = (-2..16).map { i ->
                val t = now + i * 15 * 60_000L
                ForecastStep(
                    t, WindVector.fromSpeedDirection(4.0, 270.0), gust10m = 7.0, temperatureC = 20.0, relativeHumidityPct = 40.0,
                    precipitationMm = if (t - now in 60 * 60_000L..90 * 60_000L) 0.4 else 0.0,
                    surfacePressureHpa = 960.0, sealevelPressureHpa = 1013.0, cloudCoverPct = 35.0, weatherCode = 3,
                )
            }
            return ForecastResult(request.points.map { p -> ForecastSeries("fake:model", p, gridElevationM = 460.0, steps = steps) })
        }
    }

    private class Rig(val pipeline: TrueWindPipeline, val source: FakeKarooStreamSource, val hub: ParityHub?)

    private fun rig(scope: TestScope, http: FakeHttpClient, engineStore: InMemoryKeyValueStore, withHub: Boolean = false): Rig {
        val source = FakeKarooStreamSource()
        val provider = NetProvider(http)
        val fc = ForecastSource(provider) { req ->
            val r = provider.fetch(req)
            r to SourceStatus(error = provider.lastError ?: if (r.series.isEmpty()) "no series" else null)
        }
        val sensorHub = SensorHub(source, scope.backgroundScope)
        val clock = Clock { t0 + scope.testScheduler.currentTime }
        val pipeline = TrueWindPipeline(
            scope = scope.backgroundScope,
            hub = sensorHub,
            settingsFlow = flowOf(trueWind),
            forecastRecordFlow = flowOf(PipelineFixtures.record),
            lastKnownPosition = { GpsCoordinates(start.lat, start.lon) },
            engineFactory = { WindEngine(listOf(fc), store = engineStore) },
            store = InMemoryKeyValueStore(),
            clock = clock,
            screenOpen = MutableStateFlow(true),
        )
        val hub = if (withHub) ParityHub(
            scope = scope.backgroundScope, sensorHub = sensorHub, settingsFlow = flowOf(trueWind), pipeline = pipeline, http = http,
            forecastRecord = flowOf(PipelineFixtures.record), dispatchAlert = { }, decoder = PngDecoder { null }, clock = clock,
            activeIds = MutableStateFlow(setOf("aqi")), forceVisible = MutableStateFlow(false),
        ) else null
        return Rig(pipeline, source, hub)
    }

    private fun fixes(source: FakeKarooStreamSource, minute: Int) {
        val frac = (minute / 180.0).coerceAtMost(1.0)
        source.data(DataType.Type.LOCATION).tryEmit(
            StreamState.Streaming(
                DataPoint(
                    DataType.Type.LOCATION,
                    mapOf(
                        DataType.Field.LOC_LATITUDE to start.lat + 0.135 * frac, DataType.Field.LOC_LONGITUDE to start.lon,
                        DataType.Field.LOC_BEARING to 0.0, DataType.Field.LOC_ACCURACY to 5.0,
                    ),
                ),
            ),
        )
        source.data(DataType.Type.PRESSURE_ELEVATION_CORRECTION).tryEmit(
            StreamState.Streaming(DataPoint(DataType.Type.PRESSURE_ELEVATION_CORRECTION, mapOf(DataType.Field.SINGLE to 460.0 + 200.0 * frac))),
        )
        source.data(DataType.Type.SPEED).tryEmit(StreamState.Streaming(DataPoint(DataType.Type.SPEED, mapOf(DataType.Field.SINGLE to 6.0))))
    }

    private fun snapshotProblems(pipeline: TrueWindPipeline, nowMs: Long): List<String> {
        val snap = pipeline.snapshots.value ?: return listOf("no snapshot")
        val st = snap.state
        val problems = mutableListOf<String>()
        fun need(name: String, ok: Boolean) { if (!ok) problems += name }
        need("status", st.status == Status.OK || st.status == Status.STALE)
        need("model10m", st.model10m != null && st.model10m!!.speed > 1.0)
        need("rider wind", st.rider != null)
        need("gust", snap.extras.gust10m != null)
        need("headwind", st.headwind != null)
        val w = st.weather
        need("weather", w != null)
        if (w != null) {
            need("temperature", w.temperatureC in 5.0..30.0)
            need("humidity", w.relativeHumidityPct > 0)
            need("pressure", w.surfacePressureHpa > 800 && w.sealevelPressureHpa > 900)
            need("cloud", w.cloudCoverPct > 0)
            need("uv (hourly widget forecast)", w.uvi > 0.0)
            need("weather code", w.weatherCode == 3)
            need("precipitation", w.precipitationMm >= 0.0)
        }
        need("feels-like", snap.extras.feelsLikeC != null)
        need("air density", st.airDensity in 0.9..1.4)
        need("rain-in known", snap.extras.rainInMin != WindExtras.RAIN_UNKNOWN)
        need("source text", snap.extras.sourceText.isNotBlank() && snap.extras.sourceText != "--")
        val inputs = FieldInputs(snap, trueWind, PipelineFixtures.metricProfile)
        for (id in listOf(TrueWindFields.CROSSWIND, TrueWindFields.CONFIDENCE, TrueWindFields.AIR_DENSITY, TrueWindFields.FEELS_LIKE, TrueWindFields.LAST_UPDATE)) {
            need("field $id", TrueWindFields.value(id, inputs, nowMs) != null)
        }
        return problems
    }

    @Test
    fun threeHoursWithoutInternet_noFieldGoesBlank() = runTest {
        var down = false
        val http = FakeHttpClient()
            .route("v1/forecast") { if (down) HttpResponse(0, error = dns) else HttpResponse(200, body = "{}".toByteArray()) }
            .route("air-quality") { if (down) HttpResponse(0, error = dns) else HttpResponse(200, body = AQ_JSON.toByteArray()) }
        val rig = rig(this, http, InMemoryKeyValueStore(), withHub = true)
        val hub = rig.hub!!
        rig.pipeline.start()
        hub.start()
        fixes(rig.source, 0)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, http.count("v1/forecast"), "one successful cycle")
        assertEquals(42, hub.airQuality.value?.usAqi, "AQI fetched while online")

        fun check(label: String) {
            val now = t0 + testScheduler.currentTime
            val problems = snapshotProblems(rig.pipeline, now).toMutableList()
            if (hub.buildRainBars(now, rig.pipeline.riderPosition()!!).bars.isEmpty()) problems += "rain bar"
            if (hub.airQuality.value?.usAqi == null) problems += "aqi"
            assertTrue(problems.isEmpty(), "$label: blank/invalid: $problems")
        }

        check("online")
        down = true
        // Failing cycles while moving 15 km and climbing 200 m, up to the 4 h cache window.
        for (minute in 1..235) {
            fixes(rig.source, minute)
            advanceTimeBy(60_000); runCurrent()
            if (minute % 30 == 0 || minute == 235) check("offline +$minute min")
        }
        assertTrue(http.count("v1/forecast") > 5, "cycles kept failing, not stopped")

        // The offline state is explicit, with the hint for a DNS failure, and nothing is blanked.
        val extras = rig.pipeline.snapshots.value!!.extras
        assertTrue(extras.sourceText.startsWith("OFFLINE 3h 5") && extras.sourceText.endsWith("m · cached"), extras.sourceText)
        assertEquals(TrueWindMapper.NO_INTERNET_HINT, Diagnostics.offlineHint)
        assertTrue(Diagnostics.offlineText!!.startsWith("OFFLINE"))
        val page = de.timklge.karooheadwind.screens.DiagnosticsText.sections(t0 + testScheduler.currentTime).toMap()
        assertTrue(page["Connectivity"]!!.any { it.startsWith("OFFLINE") } && page["Connectivity"]!!.contains(TrueWindMapper.NO_INTERNET_HINT), "${page["Connectivity"]}")
        assertTrue(page["Temperature"]!!.size == 3 && page["Temperature"]!![1].startsWith("Corrected"), "${page["Temperature"]}")
        val temps = Diagnostics.temperatures!!
        assertNotNull(temps.modelC)
        assertTrue(temps.correctedC!! < temps.modelC!!, "climbed 200 m: corrected temperature is lower than the grid value")
    }

    @Test
    fun processRestartWithoutNetwork_showsCachedValuesFromStorage() = runTest {
        val store = InMemoryKeyValueStore()
        val http1 = FakeHttpClient().json("v1/forecast", "{}")
        val rig1 = rig(this, http1, store)
        rig1.pipeline.start()
        fixes(rig1.source, 0)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, http1.count("v1/forecast"))
        assertTrue(store.entries().containsKey("truewind.lastCycle"), "last successful cycle persisted")
        http1.offline = true // the first service instance loses the link too, so the cache stays at minute 0

        // The service restarts 90 minutes later with no internet: a new pipeline and engine, the same storage.
        advanceTimeBy(90 * 60_000L); runCurrent()
        val http2 = FakeHttpClient().also { it.offline = true }
        val rig2 = rig(this, http2, store)
        rig2.pipeline.start()
        fixes(rig2.source, 90)
        advanceTimeBy(5_000); runCurrent()
        val problems = snapshotProblems(rig2.pipeline, t0 + testScheduler.currentTime)
        assertTrue(problems.isEmpty(), "after restart: $problems")
        assertTrue(rig2.pipeline.snapshots.value!!.extras.sourceText.startsWith("OFFLINE 1h 3"), rig2.pipeline.snapshots.value!!.extras.sourceText)
    }

    private fun hourlyResponse(): WeatherDataResponse {
        fun w(sec: Long, forecast: Boolean) = WeatherData(
            time = sec, temperature = 20.0, relativeHumidity = 40, precipitation = 0.0, precipitationProbability = 10.0, cloudCover = 30.0,
            sealevelPressure = 1013.0, surfacePressure = 960.0, windSpeed = 4.0, windDirection = 270.0, windGusts = 7.0, weatherCode = 3,
            isForecast = forecast, isNight = false, uvi = 2.0,
        )
        val t = PipelineFixtures.T0_SEC
        return WeatherDataResponse(
            provider = WeatherDataProvider.OPEN_METEO,
            data = listOf(WeatherDataForLocation(w(t, false), GpsCoordinates(start.lat, start.lon), forecasts = (0..11).map { w(t + it * 3600L, true) })),
        )
    }

    @Test
    fun forecastWidgets_keepShowingTheHoursAhead_afterThreeHoursOffline() = runTest {
        val response = hourlyResponse()
        for (hoursLater in listOf(0, 1, 3, 4)) {
            val now = t0 + hoursLater * 3_600_000L + 20 * 60_000L
            val loc = assertNotNull(rollForecastForward(response, now)).data.first()
            // The widgets show the first entries and skip any older than 1 h: none may be skipped, and some must remain.
            assertTrue(loc.forecasts!!.size >= 3, "+${hoursLater}h: ${loc.forecasts!!.size} forecast hours left")
            assertTrue(loc.forecasts!!.all { it.time * 1000 >= now - 3_600_000L }, "+${hoursLater}h: stale entries remain")
            assertTrue(loc.current.time * 1000 >= now - 3_600_000L, "+${hoursLater}h: current is stale")
        }
        // A route response (one location per hour of riding) is left alone.
        val route = response.copy(data = response.data + response.data)
        assertTrue(rollForecastForward(route, t0 + 5 * 3_600_000L) === route)

        // And through the repository flow, which the widgets collect.
        val clockNow = t0 + 3 * 3_600_000L
        val repo = PipelineFixtures.repository(backgroundScope, FakeKarooStreamSource(), Clock { clockNow }, record = ForecastRecord(HeadwindStats(), response))
        val seen = repo.forecastResponse.first()!!.data.first()
        assertTrue(seen.forecasts!!.all { it.time * 1000 >= clockNow - 3_600_000L })
    }

    private companion object {
        val AQ_JSON = """{"latitude":37.7,"longitude":-122.45,"current":{"time":1794657600,"us_aqi":42,"pm2_5":9.0,"ozone":60.0,"uv_index":3.0}}"""
    }
}
