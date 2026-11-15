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
import de.timklge.karooheadwind.HeadwindSettings
import de.timklge.karooheadwind.datatypes.GpsCoordinates
import de.timklge.karooheadwind.weatherprovider.WeatherData
import de.timklge.karooheadwind.weatherprovider.WeatherDataResponse
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.SourceSummary
import io.github.kf6999.truewind.core.model.Status
import io.github.kf6999.truewind.core.model.WeatherNow
import io.github.kf6999.truewind.core.model.WindState
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.wind.RelativeWind

/**
 * Builds the [WindState] from the cached forecast, the rider position/heading and the settings.
 * WP1: reproduces karoo-headwind exactly (single provider, GPS course as heading, 10 m wind shown
 * as rider wind). Later work packages replace the pieces behind this function.
 */
object WindStateCalculator {
    fun compute(
        response: WeatherDataResponse?,
        lastSuccessfulFetchMs: Long?,
        location: GpsCoordinates?,
        settings: HeadwindSettings,
        nowMs: Long,
    ): WindState {
        val weather = currentWeatherAt(response, location, nowMs)
        val heading = location?.bearing

        val wind = weather?.let { WindVector.fromSpeedDirection(it.windSpeed, it.windDirection) }
        val relativeBearing = if (heading != null && weather != null) RelativeWind.relativeBearing(heading, weather.windDirection) else null

        val status = when {
            !settings.welcomeDialogAccepted -> Status.NOT_SET_UP
            heading == null -> Status.NO_GPS
            weather == null -> Status.NO_DATA
            else -> Status.OK
        }

        return WindState(
            timeMs = nowMs,
            model10m = wind,
            rider = wind,
            gustRider = weather?.windGusts,
            headwind = relativeBearing?.let { RelativeWind.headwind(it, weather!!.windSpeed) },
            crosswind = relativeBearing?.let { RelativeWind.crosswind(it, weather!!.windSpeed) },
            relativeDirectionDeg = relativeBearing?.let { RelativeWind.relativeDirectionFrom(it) },
            confidence = WindState.CONFIDENCE_UNKNOWN,
            sources = SourceSummary(models = listOfNotNull(response?.provider?.name)),
            ageMs = lastSuccessfulFetchMs?.let { nowMs - it },
            airDensity = WindState.STANDARD_AIR_DENSITY,
            weather = weather?.toWeatherNow(),
            status = status,
            riderHeadingDeg = heading,
            position = location?.let { GeoPoint(it.lat, it.lon) },
        )
    }
}

fun WeatherData.toWeatherNow() = WeatherNow(
    timeSec = time,
    temperatureC = temperature,
    relativeHumidityPct = relativeHumidity,
    precipitationMm = precipitation,
    precipitationProbabilityPct = precipitationProbability,
    cloudCoverPct = cloudCover,
    sealevelPressureHpa = sealevelPressure,
    surfacePressureHpa = surfacePressure,
    windSpeed = windSpeed,
    windDirectionDeg = windDirection,
    windGusts = windGusts,
    weatherCode = weatherCode,
    isForecast = isForecast,
    isNight = isNight,
    uvi = uvi,
)

fun WeatherNow.toWeatherData() = WeatherData(
    time = timeSec,
    temperature = temperatureC,
    relativeHumidity = relativeHumidityPct,
    precipitation = precipitationMm,
    precipitationProbability = precipitationProbabilityPct,
    cloudCover = cloudCoverPct,
    sealevelPressure = sealevelPressureHpa,
    surfacePressure = surfacePressureHpa,
    windSpeed = windSpeed,
    windDirection = windDirectionDeg,
    windGusts = windGusts,
    weatherCode = weatherCode,
    isForecast = isForecast,
    isNight = isNight,
    uvi = uvi,
)

/** Current weather in the legacy app type, for fields that still render from [WeatherData]. */
fun WindState.weatherData(): WeatherData? = weather?.toWeatherData()

/** Rider heading (GPS course in WP1), as karoo-headwind's getHeadingFlow reported it. */
fun WindState.headingResponse(): HeadingResponse =
    riderHeadingDeg?.let { HeadingResponse.Value(it) } ?: HeadingResponse.NoGps

/**
 * Relative wind bearing (direction the wind blows TO relative to the rider; 0 = tailwind) as
 * karoo-headwind's getRelativeHeadingFlow reported it.
 */
fun WindState.relativeHeadingResponse(): HeadingResponse {
    val heading = riderHeadingDeg ?: return HeadingResponse.NoGps
    val weather = weather ?: return HeadingResponse.NoWeatherData
    return HeadingResponse.Value(RelativeWind.relativeBearing(heading, weather.windDirectionDeg))
}
