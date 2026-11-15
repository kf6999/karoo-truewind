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
import de.timklge.karooheadwind.HeadwindWidgetSettings
import de.timklge.karooheadwind.RoundLocationSetting
import de.timklge.karooheadwind.WeatherDataProvider
import de.timklge.karooheadwind.datatypes.GpsCoordinates
import de.timklge.karooheadwind.weatherprovider.WeatherData
import de.timklge.karooheadwind.weatherprovider.WeatherDataForLocation
import de.timklge.karooheadwind.weatherprovider.WeatherDataResponse
import io.github.kf6999.truewind.core.time.Clock
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Fake Karoo: every stream is a hot flow the test pushes into; counts upstream subscriptions. */
class FakeKarooStreamSource : KarooStreamSource {
    val dataStreams = ConcurrentHashMap<String, MutableSharedFlow<StreamState>>()
    val subscriptions = ConcurrentHashMap<String, AtomicInteger>()
    val activeSubscriptions = ConcurrentHashMap<String, AtomicInteger>()
    val userProfileFlow = MutableSharedFlow<UserProfile>(replay = 1)

    fun data(dataTypeId: String) = dataStreams.getOrPut(dataTypeId) { MutableSharedFlow(replay = 1) }

    private fun <T> counted(key: String, source: Flow<T>): Flow<T> = flow {
        subscriptions.getOrPut(key) { AtomicInteger() }.incrementAndGet()
        val active = activeSubscriptions.getOrPut(key) { AtomicInteger() }
        active.incrementAndGet()
        try {
            emitAll(source)
        } finally {
            active.decrementAndGet()
        }
    }

    override fun dataStream(dataTypeId: String): Flow<StreamState> = counted(dataTypeId, data(dataTypeId))
    override fun userProfile(): Flow<UserProfile> = counted("userProfile", userProfileFlow)
    override fun rideState(): Flow<RideState> = counted("rideState", emptyFlow())
    override fun navigationState(): Flow<OnNavigationState> = counted("navigationState", emptyFlow())
    override fun activeRidePage(): Flow<ActiveRidePage> = counted("activeRidePage", emptyFlow())
}

object PipelineFixtures {
    /** 2026-11-14 12:00:00 UTC */
    const val T0_SEC = 1_794_657_600L

    val settings = HeadwindSettings(welcomeDialogAccepted = true, roundLocationTo = RoundLocationSetting.KM_2, upstreamCompatibleMode = true)

    val metricProfile = UserProfile(
        weight = 75f,
        preferredUnit = UserProfile.PreferredUnit(
            UserProfile.PreferredUnit.UnitType.METRIC,
            UserProfile.PreferredUnit.UnitType.METRIC,
            UserProfile.PreferredUnit.UnitType.METRIC,
            UserProfile.PreferredUnit.UnitType.METRIC,
        ),
        maxHr = 190, restingHr = 50, heartRateZones = emptyList(), ftp = 250, powerZones = emptyList(),
    )

    private fun weather(timeSec: Long, speed: Double, dir: Double, gust: Double, temp: Double, forecast: Boolean, code: Int = 3) = WeatherData(
        time = timeSec,
        temperature = temp,
        relativeHumidity = 70,
        precipitation = 0.2,
        precipitationProbability = 30.0,
        cloudCover = 55.0,
        sealevelPressure = 1015.0,
        surfacePressure = 1003.0,
        windSpeed = speed,
        windDirection = dir,
        windGusts = gust,
        weatherCode = code,
        isForecast = forecast,
        isNight = false,
        uvi = 1.5,
    )

    /**
     * A route forecast with two locations ~20 km apart and hourly steps whose wind direction
     * crosses north (350 -> 20 degrees), so spatial and temporal interpolation and the angle wrap
     * are all exercised.
     */
    val response = WeatherDataResponse(
        provider = WeatherDataProvider.OPEN_METEO,
        data = listOf(
            WeatherDataForLocation(
                current = weather(T0_SEC, 4.0, 350.0, 7.0, 11.0, false),
                coords = GpsCoordinates(37.70, -122.45, distanceAlongRoute = 0.0),
                forecasts = listOf(
                    weather(T0_SEC + 1800, 5.0, 355.0, 8.0, 11.5, true),
                    weather(T0_SEC + 5400, 7.0, 20.0, 11.0, 12.5, true, code = 61),
                    weather(T0_SEC + 9000, 6.0, 40.0, 9.0, 13.0, true),
                ),
            ),
            WeatherDataForLocation(
                current = weather(T0_SEC, 6.0, 300.0, 9.0, 9.0, false),
                coords = GpsCoordinates(37.88, -122.40, distanceAlongRoute = 20_000.0),
                forecasts = listOf(
                    weather(T0_SEC + 1800, 6.5, 310.0, 9.5, 9.5, true),
                    weather(T0_SEC + 5400, 8.0, 330.0, 12.0, 10.0, true, code = 61),
                    weather(T0_SEC + 9000, 9.0, 345.0, 13.0, 10.5, true),
                ),
            ),
        ),
    )

    val record = ForecastRecord(HeadwindStats(lastSuccessfulWeatherRequest = T0_SEC * 1000), response)

    data class Fix(val tSec: Long, val lat: Double, val lon: Double, val bearing: Double?, val accuracy: Double = 5.0)

    /**
     * A recorded-ride-like sequence: riding north between the forecast points, bearings wrapping
     * through 0, a stop with no bearing, a poor-accuracy fix (ignored) and time passing an hourly step.
     */
    val fixes = listOf(
        Fix(T0_SEC + 60, 37.700, -122.450, 5.0),
        Fix(T0_SEC + 120, 37.712, -122.448, 355.0),
        Fix(T0_SEC + 600, 37.740, -122.441, 0.0),
        Fix(T0_SEC + 1200, 37.760, -122.437, 10.0),
        Fix(T0_SEC + 1500, 37.775, -122.433, 179.9),
        Fix(T0_SEC + 1700, 37.780, -122.431, 180.0),
        Fix(T0_SEC + 1800, 37.790, -122.429, 270.0),
        Fix(T0_SEC + 2400, 37.800, -122.425, 359.99),
        Fix(T0_SEC + 3000, 37.810, -122.421, null),
        Fix(T0_SEC + 3100, 37.815, -122.420, 90.0, accuracy = 800.0),
        Fix(T0_SEC + 3600, 37.830, -122.416, 45.0),
        Fix(T0_SEC + 5400, 37.850, -122.410, 135.0),
        Fix(T0_SEC + 7000, 37.870, -122.404, 225.0),
        Fix(T0_SEC + 9500, 37.880, -122.400, 300.0),
    )

    fun Fix.toStreamState() = StreamState.Streaming(
        DataPoint(
            DataType.Type.LOCATION,
            buildMap {
                put(DataType.Field.LOC_LATITUDE, lat)
                put(DataType.Field.LOC_LONGITUDE, lon)
                bearing?.let { put(DataType.Field.LOC_BEARING, it) }
                put(DataType.Field.LOC_ACCURACY, accuracy)
            },
        )
    )

    fun repository(
        scope: CoroutineScope,
        source: FakeKarooStreamSource,
        clock: Clock,
        settings: HeadwindSettings = PipelineFixtures.settings,
        record: ForecastRecord = PipelineFixtures.record,
        lastKnown: GpsCoordinates? = null,
    ): WindRepository {
        val hub = SensorHub(source, scope)
        return WindRepository(
            scope = scope,
            sensorHub = hub,
            settingsSource = flowOf(settings),
            widgetSettingsSource = flowOf(HeadwindWidgetSettings()),
            forecastRecordSource = flowOf(record),
            lastKnownPosition = { lastKnown },
            isK2 = { false },
            clock = clock,
        )
    }
}
