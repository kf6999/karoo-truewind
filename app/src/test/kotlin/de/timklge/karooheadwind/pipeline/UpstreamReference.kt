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
 *
 * Modified by TrueWind: frozen test-only copy of karoo-headwind's computation at commit c9be073
 * (util/AngleDifference.kt, DataStore.kt lerp* and streamCurrentWeatherData body, GpsCoordinates.round,
 * HeadwindSpeedDataType / HeadwindDirectionDataType value code). The only change is that "now" is a
 * parameter instead of System.currentTimeMillis(). Do not edit: it is the oracle for the WP1 golden test.
 */

package de.timklge.karooheadwind.pipeline

import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import de.timklge.karooheadwind.HeadwindSettings
import de.timklge.karooheadwind.datatypes.GpsCoordinates
import de.timklge.karooheadwind.util.msInWindUnit
import de.timklge.karooheadwind.weatherprovider.WeatherData
import de.timklge.karooheadwind.weatherprovider.WeatherDataForLocation
import de.timklge.karooheadwind.weatherprovider.WeatherDataResponse
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.roundToInt

object UpstreamReference {
    fun signedAngleDifference(angle1: Double, angle2: Double): Double {
        val a1 = angle1 % 360
        val a2 = angle2 % 360
        var diff = abs(a1 - a2)

        val sign = if (a1 < a2) {
            if (diff > 180.0) -1 else 1
        } else {
            if (diff > 180.0) 1 else -1
        }

        if (diff > 180.0) {
            diff = 360.0 - diff
        }

        return sign * diff
    }

    fun lerp(start: Double, end: Double, factor: Double): Double = start + (end - start) * factor

    fun lerp(start: Int, end: Int, factor: Double): Int = (start + (end - start) * factor).toInt()

    fun lerpNullable(start: Double?, end: Double?, factor: Double): Double? {
        if (start == null && end == null) return null
        if (start == null) return end
        if (end == null) return start

        return start + (end - start) * factor
    }

    fun lerpAngle(start: Double, end: Double, factor: Double): Double {
        val normalizedStart = start % 360.0
        val normalizedEnd = end % 360.0

        var diff = normalizedEnd - normalizedStart
        if (diff > 180.0) {
            diff -= 360.0
        } else if (diff < -180.0) {
            diff += 360.0
        }

        var result = normalizedStart + diff * factor

        if (result < 0) {
            result += 360.0
        } else if (result >= 360.0) {
            result -= 360.0
        }

        return result
    }

    fun lerpWeather(start: WeatherData, end: WeatherData, factor: Double): WeatherData {
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

    fun lerpWeatherTime(weatherData: List<WeatherData>?, currentWeatherData: WeatherData, now: Long): WeatherData {
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

    private fun roundDegrees(degrees: Double, km: Double): Double {
        val nkm = degrees * 111
        val rounded = (nkm / km).roundToInt() * km

        return rounded / 111
    }

    fun round(gps: GpsCoordinates, km: Double): GpsCoordinates =
        gps.copy(lat = roundDegrees(gps.lat, km), lon = roundDegrees(gps.lon, km))

    /** Body of one iteration of streamCurrentWeatherData's loop. */
    fun currentWeather(weatherData: WeatherDataResponse?, location: GpsCoordinates?, now: Long): WeatherData? {
        if (weatherData?.data.isNullOrEmpty()) return null

        val weatherDataForCurrentPosition = if (location == null || weatherData?.data?.size == 1) weatherData?.data?.first() else {
            val weatherDatas = weatherData?.data?.sortedBy { data ->
                TurfMeasurement.distance(
                    Point.fromLngLat(location.lon, location.lat),
                    Point.fromLngLat(data.coords.lon, data.coords.lat),
                    TurfConstants.UNIT_METERS
                )
            }!!.take(2)

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

        return weatherDataForCurrentPosition?.let { lerpWeatherTime(it.forecasts, it.current, now) }
    }

    /** getRelativeHeadingFlow value for a rounded fix: null = NoGps / NoWeatherData. */
    fun relativeHeading(location: GpsCoordinates?, weather: WeatherData?): Double? {
        val bearing = location?.bearing ?: return null
        if (weather == null) return null
        val windBearing = weather.windDirection + 180
        return signedAngleDifference(bearing, windBearing)
    }

    /** HeadwindSpeedDataType stream value (null = NotAvailable). */
    fun headwindSpeedValue(location: GpsCoordinates?, weather: WeatherData?, settings: HeadwindSettings, isImperial: Boolean): Double? {
        if (!settings.welcomeDialogAccepted) return null
        val heading = relativeHeading(location, weather) ?: return null
        val windSpeed = weather?.windSpeed ?: return null
        val headwindSpeed = cos((heading + 180) * Math.PI / 180.0) * windSpeed
        return msInWindUnit(headwindSpeed, settings.getWindUnit(isImperial))
    }

    /** HeadwindDirectionDataType stream value (bearing in [0, 360) or negative error code). */
    fun headwindDirectionValue(location: GpsCoordinates?, weather: WeatherData?, settings: HeadwindSettings): Double {
        if (!settings.welcomeDialogAccepted) return -3.0
        if (location?.bearing == null) return -1.0
        val value = relativeHeading(location, weather) ?: return -2.0
        var windDirection = value
        if (windDirection < 0) windDirection += 360
        return windDirection
    }
}
