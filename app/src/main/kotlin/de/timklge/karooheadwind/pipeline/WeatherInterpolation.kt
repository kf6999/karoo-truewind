/*
 * Copyright 2024-2026 karoo-headwind contributors
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
 *
 * Modified by TrueWind: the body of karoo-headwind's streamCurrentWeatherData loop, extracted
 * unchanged into pure functions so it can be shared and tested.
 */

package de.timklge.karooheadwind.pipeline

import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import de.timklge.karooheadwind.datatypes.GpsCoordinates
import de.timklge.karooheadwind.lerpWeather
import de.timklge.karooheadwind.lerpWeatherTime
import de.timklge.karooheadwind.weatherprovider.WeatherData
import de.timklge.karooheadwind.weatherprovider.WeatherDataForLocation
import de.timklge.karooheadwind.weatherprovider.WeatherDataResponse

/**
 * Weather for [location]: the only forecast location, the first one when the position is
 * unknown, or a distance-weighted blend of the two nearest forecast locations (route forecasts).
 * Returns null when the response has no data.
 */
fun weatherForLocation(weatherData: WeatherDataResponse?, location: GpsCoordinates?): WeatherDataForLocation? {
    if (weatherData == null || weatherData.data.isEmpty()) return null

    return if (location == null || weatherData.data.size == 1) weatherData.data.first() else {
        val weatherDatas = weatherData.data.sortedBy { data ->
            TurfMeasurement.distance(
                Point.fromLngLat(location.lon, location.lat),
                Point.fromLngLat(data.coords.lon, data.coords.lat),
                TurfConstants.UNIT_METERS
            )
        }.take(2)

        val location1 = weatherDatas[0]
        val location2 = weatherDatas[1]
        val distanceToLocation1 = TurfMeasurement.distance(
            Point.fromLngLat(location.lon, location.lat),
            Point.fromLngLat(location1.coords.lon, location1.coords.lat),
            TurfConstants.UNIT_METERS
        )
        val distanceToLocation2 = TurfMeasurement.distance(
            Point.fromLngLat(location.lon, location.lat),
            Point.fromLngLat(location2.coords.lon, location2.coords.lat),
            TurfConstants.UNIT_METERS
        )
        val lerpFactor = (distanceToLocation1 / (distanceToLocation1 + distanceToLocation2)).coerceIn(0.0, 1.0)

        val interpolatedWeatherData = lerpWeather(
            start = location1.current,
            end = location2.current,
            factor = lerpFactor
        )

        val interpolatedForecasts = location1.forecasts?.mapIndexed { index, forecast ->
            val forecast2 = location2.forecasts?.get(index) ?: error("Mismatched forecast lengths")
            lerpWeather(
                start = forecast,
                end = forecast2,
                factor = lerpFactor
            )
        }

        WeatherDataForLocation(
            coords = location,
            current = interpolatedWeatherData,
            forecasts = interpolatedForecasts
        )
    }
}

/** Current weather at [location] and time [nowMs], interpolated in space and time; null without data. */
fun currentWeatherAt(weatherData: WeatherDataResponse?, location: GpsCoordinates?, nowMs: Long): WeatherData? {
    val forLocation = weatherForLocation(weatherData, location) ?: return null
    return lerpWeatherTime(forLocation.forecasts, forLocation.current, nowMs)
}
