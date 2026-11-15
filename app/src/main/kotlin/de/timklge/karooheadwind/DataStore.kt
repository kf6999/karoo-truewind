/*
 * Copyright 2024-2026 karoo-headwind contributors
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

package de.timklge.karooheadwind

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mapbox.geojson.LineString
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import de.timklge.karooheadwind.datatypes.GpsCoordinates
import de.timklge.karooheadwind.pipeline.currentWeatherAt
import de.timklge.karooheadwind.weatherprovider.WeatherData
import de.timklge.karooheadwind.weatherprovider.WeatherDataResponse
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import io.github.kf6999.truewind.core.math.lerp
import io.github.kf6999.truewind.core.math.lerpAngle
import io.github.kf6999.truewind.core.math.lerpNullable
import kotlinx.serialization.json.Json
import kotlin.math.absoluteValue
import kotlin.time.Duration.Companion.minutes

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings", corruptionHandler = ReplaceFileCorruptionHandler {
    Log.w(KarooHeadwindExtension.TAG, "Error reading settings, using default values")
    emptyPreferences()
})

val jsonWithUnknownKeys = Json { ignoreUnknownKeys = true }

val settingsKey = stringPreferencesKey("settings")
val widgetSettingsKey = stringPreferencesKey("widgetSettings")
val currentDataKey = stringPreferencesKey("currentForecastsUnified")
val statsKey = stringPreferencesKey("stats")
val lastKnownPositionKey = stringPreferencesKey("lastKnownPosition")

suspend fun saveSettings(context: Context, settings: HeadwindSettings) {
    context.dataStore.edit { t ->
        t[settingsKey] = Json.encodeToString(settings)
    }
}

/** Atomic read-modify-write of the settings, so screens editing different fields never clobber each other. */
suspend fun updateSettings(context: Context, transform: (HeadwindSettings) -> HeadwindSettings) {
    context.dataStore.edit { t ->
        val current = try {
            jsonWithUnknownKeys.decodeFromString<HeadwindSettings>(t[settingsKey] ?: HeadwindSettings.defaultSettings)
        } catch (e: Throwable) {
            Log.e(KarooHeadwindExtension.TAG, "Failed to read preferences", e)
            jsonWithUnknownKeys.decodeFromString<HeadwindSettings>(HeadwindSettings.defaultSettings)
        }
        val next = transform(current)
        if (next != current) t[settingsKey] = Json.encodeToString(next)
    }
}

suspend fun saveWidgetSettings(context: Context, settings: HeadwindWidgetSettings) {
    context.dataStore.edit { t ->
        t[widgetSettingsKey] = Json.encodeToString(settings)
    }
}

suspend fun saveStats(context: Context, stats: HeadwindStats) {
    context.dataStore.edit { t ->
        t[statsKey] = Json.encodeToString(stats)
    }
}

suspend fun saveForecastRecord(context: Context, response: WeatherDataResponse, stats: HeadwindStats) {
    context.dataStore.edit { t ->
        t[currentDataKey] = Json.encodeToString(response)
        t[statsKey] = Json.encodeToString(stats)
    }
}

suspend fun saveLastKnownPosition(context: Context, gpsCoordinates: GpsCoordinates) {
    if (BuildConfig.DEBUG) Log.d(KarooHeadwindExtension.TAG, "Saving last known position: $gpsCoordinates")

    try {
        context.dataStore.edit { t ->
            t[lastKnownPositionKey] = Json.encodeToString(gpsCoordinates)
        }
    } catch(e: Throwable){
        Log.e(KarooHeadwindExtension.TAG, "Failed to save last known position", e)
    }
}

fun Context.streamWidgetSettings(): Flow<HeadwindWidgetSettings> {
    return dataStore.data.map { settingsJson ->
        try {
            if (settingsJson.contains(widgetSettingsKey)){
                jsonWithUnknownKeys.decodeFromString<HeadwindWidgetSettings>(settingsJson[widgetSettingsKey]!!)
            } else {
                jsonWithUnknownKeys.decodeFromString<HeadwindWidgetSettings>(HeadwindWidgetSettings.defaultWidgetSettings)
            }
        } catch(e: Throwable){
            Log.e(KarooHeadwindExtension.TAG, "Failed to read widget preferences", e)
            jsonWithUnknownKeys.decodeFromString<HeadwindWidgetSettings>(HeadwindWidgetSettings.defaultWidgetSettings)
        }
    }.distinctUntilChanged()
}

fun Context.streamSettings(karooSystemService: KarooSystemService): Flow<HeadwindSettings> {
    return dataStore.data.map { settingsJson ->
        try {
            if (settingsJson.contains(settingsKey)){
                jsonWithUnknownKeys.decodeFromString<HeadwindSettings>(settingsJson[settingsKey]!!)
            } else {
                val defaultSettings = jsonWithUnknownKeys.decodeFromString<HeadwindSettings>(
                    HeadwindSettings.defaultSettings)

                defaultSettings.copy()
            }
        } catch(e: Throwable){
            Log.e(KarooHeadwindExtension.TAG, "Failed to read preferences", e)
            jsonWithUnknownKeys.decodeFromString<HeadwindSettings>(HeadwindSettings.defaultSettings)
        }
    }.distinctUntilChanged()
}

data class UpcomingRoute(val distanceAlongRoute: Double, val routePolyline: LineString, val routeLength: Double)

fun KarooSystemService.streamUpcomingRoute(): Flow<UpcomingRoute?> =
    upcomingRouteFlow(streamNavigationState(), streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION))

/** Builds the upcoming-route flow from a navigation state flow and the DISTANCE_TO_DESTINATION stream. */
fun upcomingRouteFlow(navigationState: Flow<OnNavigationState>, distanceToDestination: Flow<StreamState>): Flow<UpcomingRoute?> {
    val distanceToDestinationStream = distanceToDestination
        .map { (it as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.DISTANCE_TO_DESTINATION) }
        .distinctUntilChanged()

    var lastKnownDistanceAlongRoute = 0.0
    var lastKnownRoutePolyline: LineString? = null

    val navigationStateStream = navigationState
        .map { it.state as? OnNavigationState.NavigationState.NavigatingRoute }
        .map { navigationState ->
            navigationState?.let { LineString.fromPolyline(it.routePolyline, 5) }
        }
        .distinctUntilChanged()
        .combine(distanceToDestinationStream) { routePolyline, distanceToDestination ->
            if (BuildConfig.DEBUG) Log.d(KarooHeadwindExtension.TAG, "Route polyline size: ${routePolyline?.coordinates()?.size}, distance to destination: $distanceToDestination")
            if (routePolyline != null){
                val length = TurfMeasurement.length(routePolyline, TurfConstants.UNIT_METERS)
                if (routePolyline != lastKnownRoutePolyline){
                    lastKnownDistanceAlongRoute = 0.0
                }
                val distanceAlongRoute = distanceToDestination?.let { toDest -> length - toDest } ?: lastKnownDistanceAlongRoute
                lastKnownDistanceAlongRoute = distanceAlongRoute
                lastKnownRoutePolyline = routePolyline

                UpcomingRoute(distanceAlongRoute, routePolyline, length)
            } else {
                null
            }
        }

    return navigationStateStream
}

private fun decodeStats(prefs: Preferences): HeadwindStats {
    return try {
        jsonWithUnknownKeys.decodeFromString<HeadwindStats>(
            prefs[statsKey] ?: HeadwindStats.defaultStats
        )
    } catch(e: Throwable){
        Log.e(KarooHeadwindExtension.TAG, "Failed to read stats", e)
        jsonWithUnknownKeys.decodeFromString<HeadwindStats>(HeadwindStats.defaultStats)
    }
}

private fun decodeCurrentForecast(prefs: Preferences): WeatherDataResponse? {
    return try {
        prefs[currentDataKey]?.let { d -> jsonWithUnknownKeys.decodeFromString<WeatherDataResponse>(d) }
    } catch (e: Throwable) {
        Log.e(KarooHeadwindExtension.TAG, "Failed to read weather data", e)
        null
    }
}

fun Context.streamStats(): Flow<HeadwindStats> {
    return dataStore.data.map { prefs -> decodeStats(prefs) }.distinctUntilChanged()
}

fun Context.streamForecastRecord(): Flow<ForecastRecord> {
    return dataStore.data.map { prefs ->
        ForecastRecord(stats = decodeStats(prefs), response = decodeCurrentForecast(prefs))
    }.distinctUntilChanged()
}

suspend fun Context.getLastKnownPosition(): GpsCoordinates? {
    val settingsJson = dataStore.data.first()

    try {
        val lastKnownPositionString = settingsJson[lastKnownPositionKey] ?: return null
        val lastKnownPosition = jsonWithUnknownKeys.decodeFromString<GpsCoordinates>(
            lastKnownPositionString
        )

        return lastKnownPosition
    } catch(e: Throwable){
        Log.e(KarooHeadwindExtension.TAG, "Failed to read last known position", e)
        return null
    }
}

fun KarooSystemService.streamUserProfile(): Flow<UserProfile> {
    return callbackFlow {
        val listenerId = addConsumer { userProfile: UserProfile ->
            trySendBlocking(userProfile)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }
}

data class ForecastRecord(val stats: HeadwindStats, val response: WeatherDataResponse?)

fun Context.streamCurrentForecastWeatherData(): Flow<WeatherDataResponse?> {
    return dataStore.data.map { prefs -> decodeCurrentForecast(prefs) }.distinctUntilChanged()
}

fun lerpWeather(
    start: WeatherData,
    end: WeatherData,
    factor: Double
): WeatherData {
    val closestWeatherData = if (factor < 0.5) start else end

    return WeatherData(
        time = (start.time + (end.time - start.time) * factor).toLong(),
        temperature = start.temperature + (end.temperature - start.temperature) * factor,
        relativeHumidity = lerp(start.relativeHumidity, end.relativeHumidity, factor),
        precipitation = start.precipitation + (end.precipitation - start.precipitation) * factor,
        precipitationProbability = lerpNullable(start.precipitationProbability, end.precipitationProbability, factor),
        cloudCover = lerp(start.cloudCover, end.cloudCover, factor),
        surfacePressure = lerp(start.surfacePressure, end.surfacePressure, factor),
        sealevelPressure = lerp(start.sealevelPressure, end.sealevelPressure, factor),
        windSpeed = start.windSpeed + (end.windSpeed - start.windSpeed) * factor,
        windDirection = lerpAngle(start.windDirection, end.windDirection, factor),
        windGusts = start.windGusts + (end.windGusts - start.windGusts) * factor,
        weatherCode = closestWeatherData.weatherCode,
        isForecast = closestWeatherData.isForecast,
        isNight = closestWeatherData.isNight,
        uvi = start.uvi + (end.uvi - start.uvi) * factor
    )
}
fun lerpWeatherTime(
    weatherData: List<WeatherData>?,
    currentWeatherData: WeatherData,
    now: Long = System.currentTimeMillis(),
): WeatherData {
    val nextWeatherForecastData = weatherData?.find { forecast -> forecast.time * 1000 >= now }
    val previousWeatherForecastData = weatherData?.findLast { forecast -> forecast.time * 1000 < now }

    val interpolateStartWeatherData = previousWeatherForecastData ?: currentWeatherData
    val interpolateEndWeatherData = nextWeatherForecastData ?: interpolateStartWeatherData

    val lerpFactor = ((now - (interpolateStartWeatherData.time * 1000)).toDouble() / (interpolateEndWeatherData.time * 1000 - (interpolateStartWeatherData.time * 1000)).absoluteValue).coerceIn(0.0, 1.0)

    return lerpWeather(
        start = interpolateStartWeatherData,
        end = interpolateEndWeatherData,
        factor = lerpFactor
    )
}

/**
 * Current weather at the rider, recomputed every minute.
 *
 * TrueWind: data types and the FIT writer use [de.timklge.karooheadwind.pipeline.WindRepository]
 * instead, which shares one instance of this chain; this standalone version remains for the
 * settings/weather screens of the app UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Context.streamCurrentWeatherData(karooSystemService: KarooSystemService): Flow<WeatherData?> {
    val locationFlow = flow {
        emit(null)
        emitAll(karooSystemService.getGpsCoordinateFlow(this@streamCurrentWeatherData))
    }

    return streamCurrentForecastWeatherData().combine(locationFlow) {
        weatherData, location -> weatherData to location
    }.distinctUntilChanged()
    .flatMapLatest { (weatherData, location) ->
        flow {
            if (!weatherData?.data.isNullOrEmpty()) {
                while(true){
                    emit(currentWeatherAt(weatherData, location, System.currentTimeMillis()))
                    delay(1.minutes)
                }
            } else {
                emit(null)
            }
        }
    }
}
