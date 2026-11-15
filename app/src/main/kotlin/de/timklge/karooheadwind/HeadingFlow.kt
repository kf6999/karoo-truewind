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
import de.timklge.karooheadwind.datatypes.GpsCoordinates
import io.github.kf6999.truewind.core.wind.RelativeWind
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

sealed class HeadingResponse {
    data object NoGps: HeadingResponse()
    data object NoWeatherData: HeadingResponse()
    data class Value(val diff: Double): HeadingResponse()
}

fun KarooSystemService.getRelativeHeadingFlow(context: Context): Flow<HeadingResponse> {
    val currentWeatherData = context.streamCurrentWeatherData(this)

    return getHeadingFlow(this, context)
        .combine(currentWeatherData) { bearing, data -> bearing to data }
        .map { (bearing, data) ->
            when {
                bearing is HeadingResponse.Value && data != null -> {
                    HeadingResponse.Value(RelativeWind.relativeBearing(bearing.diff, data.windDirection))
                }
                bearing is HeadingResponse.NoGps -> HeadingResponse.NoGps
                bearing is HeadingResponse.NoWeatherData || data == null -> HeadingResponse.NoWeatherData
                else -> bearing
            }
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
fun KarooSystemService.getHeadingFlow(karooSystemService: KarooSystemService, context: Context): Flow<HeadingResponse> {
    return getGpsCoordinateFlow(context).map {  gps ->
        if (gps != null) {
            val headingValue = gps.bearing?.let { HeadingResponse.Value(it) }

            headingValue ?: HeadingResponse.NoGps
        } else {
            HeadingResponse.NoGps
        }
    }
}

fun <T> concatenate(vararg flows: Flow<T>) = flow {
    for (flow in flows) {
        emitAll(flow)
    }
}

fun<T> Flow<T>.dropNullsIfNullEncountered(): Flow<T?> = flow {
    var hadValue = false

    collect { value ->
        if (!hadValue) {
            emit(value)
            if (value != null) hadValue = true
        } else {
            if (value != null) emit(value)
        }
    }
}

suspend fun updateLastKnownGps(context: Context, gpsCoordinates: Flow<GpsCoordinates?>) {
    while (true) {
        gpsCoordinates
            .filterNotNull()
            .throttle(60 * 1_000) // Only update last known gps position once every minute
            .collect { gps ->
                saveLastKnownPosition(context, gps)
            }
        delay(1_000)
    }
}

fun KarooSystemService.getGpsCoordinateFlow(context: Context): Flow<GpsCoordinates?> =
    gpsCoordinateFlow(
        lastKnownPosition = { context.getLastKnownPosition() },
        locationStream = streamDataFlow(DataType.Type.LOCATION),
        settings = context.streamSettings(this),
    )

/**
 * Rider position, rounded to the configured privacy grid: the last known position (or the first
 * LOCATION fix) followed by every usable LOCATION fix. Once a position was seen, nulls are dropped.
 */
fun gpsCoordinateFlow(
    lastKnownPosition: suspend () -> GpsCoordinates?,
    locationStream: Flow<StreamState>,
    settings: Flow<HeadwindSettings>,
): Flow<GpsCoordinates?> {
    val initialFlow = flow {
        val lastKnownPosition = lastKnownPosition()

        if (lastKnownPosition == null) {
            val initialState = locationStream.firstOrNull()?.let { it as? StreamState.Streaming }

            initialState?.dataPoint?.let { dataPoint ->
                val lat = dataPoint.values[DataType.Field.LOC_LATITUDE]
                val lng = dataPoint.values[DataType.Field.LOC_LONGITUDE]
                val orientation = dataPoint.values[DataType.Field.LOC_BEARING]
                val accuracy = dataPoint.values[DataType.Field.LOC_ACCURACY]

                if (lat != null && lng != null && accuracy != null && accuracy < 500) {
                    emit(GpsCoordinates(lat, lng, orientation))

                    Log.i(KarooHeadwindExtension.TAG, "No last known position found, fetched initial GPS position")
                } else {
                    emit(null)

                    Log.w(KarooHeadwindExtension.TAG, "No last known position found, initial GPS position is unavailable")
                }
            } ?: run {
                emit(null)

                Log.w(KarooHeadwindExtension.TAG, "No last known position found, initial GPS position is unavailable")
            }
        } else {
            emit(lastKnownPosition)

            if (BuildConfig.DEBUG) Log.d(KarooHeadwindExtension.TAG, "Using last known position: $lastKnownPosition")
        }
    }

    val gpsFlow = locationStream.mapNotNull { it as? StreamState.Streaming }
        .mapNotNull { dataPoint ->
            val lat = dataPoint.dataPoint.values[DataType.Field.LOC_LATITUDE]
            val lng = dataPoint.dataPoint.values[DataType.Field.LOC_LONGITUDE]
            val orientation = dataPoint.dataPoint.values[DataType.Field.LOC_BEARING]
            val accuracy = dataPoint.dataPoint.values[DataType.Field.LOC_ACCURACY]

            // Per-fix logging only in debug builds (SPEC 3, rule 6)
            if (BuildConfig.DEBUG) Log.d(KarooHeadwindExtension.TAG, "Received GPS update: lat=$lat, lng=$lng, accuracy=$accuracy, orientation=$orientation")

            if (lat != null && lng != null && accuracy != null && accuracy < 500) {
                GpsCoordinates(lat, lng, orientation)
            } else {
                null
            }
        }

    val concatenatedFlow = concatenate(initialFlow, gpsFlow)

    return concatenatedFlow
        .combine(settings) { gps, settings -> gps to settings }
        .map { (gps, settings) ->
            // TrueWind: "Exact" (0 km) sends the position unrounded.
            if (settings.roundLocationTo.km <= 0) gps else gps?.round(settings.roundLocationTo.km.toDouble())
        }
        .dropNullsIfNullEncountered()
}
