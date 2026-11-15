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
import de.timklge.karooheadwind.HeadwindSettings
import de.timklge.karooheadwind.HeadwindWidgetSettings
import de.timklge.karooheadwind.datatypes.GpsCoordinates
import de.timklge.karooheadwind.gpsCoordinateFlow
import de.timklge.karooheadwind.weatherprovider.WeatherData
import de.timklge.karooheadwind.weatherprovider.WeatherDataResponse
import io.github.kf6999.truewind.core.model.WindState
import io.github.kf6999.truewind.core.time.Clock
import io.hammerhead.karooext.models.DataType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/**
 * The one hot wind pipeline (SPEC section 3, rule 2). Every data type, the FIT writer and the wind
 * aggregator map from [state] (or the derived flows below) instead of building their own GPS,
 * settings and DataStore chains.
 *
 * WP1 keeps karoo-headwind's timing: the state is recomputed on every position/forecast/settings
 * change and every [recomputeIntervalMs] in between (upstream's 1-minute loop).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WindRepository(
    scope: CoroutineScope,
    val sensorHub: SensorHub,
    settingsSource: Flow<HeadwindSettings>,
    widgetSettingsSource: Flow<HeadwindWidgetSettings>,
    forecastRecordSource: Flow<ForecastRecord>,
    lastKnownPosition: suspend () -> GpsCoordinates?,
    private val isK2: () -> Boolean,
    private val clock: Clock = Clock.SYSTEM,
    private val recomputeIntervalMs: Long = 60_000L,
) {
    private val sharing = SharingStarted.WhileSubscribed(SensorHub.STOP_TIMEOUT_MS, 0)

    val settings: SharedFlow<HeadwindSettings> = settingsSource.distinctUntilChanged().shareIn(scope, sharing, replay = 1)
    val widgetSettings: SharedFlow<HeadwindWidgetSettings> = widgetSettingsSource.distinctUntilChanged().shareIn(scope, sharing, replay = 1)
    val forecastRecord: SharedFlow<ForecastRecord> = forecastRecordSource.distinctUntilChanged().shareIn(scope, sharing, replay = 1)
    val forecastResponse: Flow<WeatherDataResponse?> = forecastRecord.map { it.response }.distinctUntilChanged()

    /** Rider position after privacy rounding; null until a first position is known. */
    val location: SharedFlow<GpsCoordinates?> = gpsCoordinateFlow(
        lastKnownPosition = lastKnownPosition,
        locationStream = sensorHub.stream(DataType.Type.LOCATION),
        settings = settings,
    ).shareIn(scope, sharing, replay = 1)

    private data class Inputs(val response: WeatherDataResponse?, val lastFetchMs: Long?, val location: GpsCoordinates?, val settings: HeadwindSettings)

    val state: StateFlow<WindState> = combine(
        forecastRecord,
        flow { emit(null); emitAll(location) },
        settings,
    ) { record, location, settings ->
        Inputs(record.response, record.stats.lastSuccessfulWeatherRequest, location, settings)
    }
        .distinctUntilChanged()
        .flatMapLatest { inputs ->
            flow {
                if (!inputs.response?.data.isNullOrEmpty()) {
                    while (true) {
                        emit(compute(inputs))
                        delay(recomputeIntervalMs)
                    }
                } else {
                    emit(compute(inputs))
                }
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(SensorHub.STOP_TIMEOUT_MS), WindState.PLACEHOLDER)

    private fun compute(inputs: Inputs) = WindStateCalculator.compute(
        response = inputs.response,
        lastSuccessfulFetchMs = inputs.lastFetchMs,
        location = inputs.location,
        settings = inputs.settings,
        nowMs = clock.nowMs(),
    )

    /** [state] without the initial placeholder. */
    val ready: Flow<WindState> = state.filter { !it.isPlaceholder }

    /** Relative wind bearing; replaces karoo-headwind's per-field getRelativeHeadingFlow. */
    val relativeHeading: Flow<HeadingResponse> = ready.map { it.relativeHeadingResponse() }.distinctUntilChanged()

    /** Rider heading; replaces karoo-headwind's per-field getHeadingFlow. */
    val heading: Flow<HeadingResponse> = ready.map { it.headingResponse() }.distinctUntilChanged()

    /** Current weather; replaces karoo-headwind's per-field streamCurrentWeatherData. */
    val currentWeather: Flow<WeatherData?> = ready.map { it.weatherData() }.distinctUntilChanged()

    /** Relative heading and weather from the same state, so combined fields never see a mix of two states. */
    val relativeHeadingAndWeather: Flow<Pair<HeadingResponse, WeatherData?>> =
        ready.map { it.relativeHeadingResponse() to it.weatherData() }.distinctUntilChanged()

    /** View refresh interval from the settings, per hardware type. */
    suspend fun refreshRateMs(): Long {
        val refreshRate = settings.first().refreshRate
        return if (isK2()) refreshRate.k2Ms else refreshRate.k3Ms
    }
}
