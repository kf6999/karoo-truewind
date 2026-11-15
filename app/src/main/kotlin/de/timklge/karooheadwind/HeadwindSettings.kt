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

import de.timklge.karooheadwind.datatypes.GpsCoordinates
import io.github.kf6999.truewind.core.physics.Exposure
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class WindUnit(val id: String, val label: String, val unitDisplay: String){
    KILOMETERS_PER_HOUR("kmh", "Kilometers (km/h)", "km/h"),
    METERS_PER_SECOND("ms", "Meters (m/s)", "m/s"),
    MILES_PER_HOUR("mph", "Miles (mph)", "mph"),
    KNOTS("kn", "Knots (kn)", "kn")
}

fun defaultWindUnit(isImperial: Boolean): WindUnit {
    return if (isImperial) WindUnit.MILES_PER_HOUR else WindUnit.KILOMETERS_PER_HOUR
}

enum class PrecipitationUnit(val id: String, val label: String, val unitDisplay: String){
    MILLIMETERS("mm", "Millimeters (mm)", "mm"),
    INCH("inch", "Inch", "in")
}

enum class TemperatureUnit(val id: String, val label: String, val unitDisplay: String){
    CELSIUS("celsius", "Celsius (°C)", "°C"),
    FAHRENHEIT("fahrenheit", "Fahrenheit (°F)", "°F")
}

enum class RoundLocationSetting(val id: String, val label: String, val km: Int){
    // TrueWind: "Exact" added; default changed to 1 km (SPEC 4.1 location privacy).
    EXACT("exact", "Exact", 0),
    KM_1("1 km", "1 km", 1),
    KM_2("2 km", "2 km", 2),
    KM_3("3 km", "3 km", 3),
    KM_5("5 km", "5 km", 5)
}

@Serializable
data class HeadwindWidgetSettings(
    val currentForecastHourOffset: Int = 0
){
    companion object {
        val defaultWidgetSettings = Json.encodeToString(HeadwindWidgetSettings())
    }
}

@Serializable
data class HeadwindStats(
    val lastSuccessfulWeatherRequest: Long? = null,
    val lastSuccessfulWeatherPosition: GpsCoordinates? = null,
    val failedWeatherRequest: Long? = null,
    val lastWeatherError: String? = null,
    val lastSuccessfulWeatherProvider: WeatherDataProvider? = null
){
    companion object {
        val defaultStats = Json.encodeToString(HeadwindStats())
    }
}


enum class RefreshRate(val id: String, val k2Ms: Long, val k3Ms: Long) {
    FAST("fast", 1_000L, 500L),
    STANDARD("medium", 2_000L, 1_000L),
    SLOW("slow", 5_000L, 3_000L),
    MINIMUM("minimum", 10_000L, 10_000L);

    fun getDescription(isOnK2: Boolean): String {
        return if (isOnK2) {
            when (this) {
                FAST -> "Fast (1s)"
                STANDARD -> "Standard (2s)"
                SLOW -> "Slow (5s)"
                MINIMUM -> "Minimum (10s)"
            }
        } else {
            when (this) {
                FAST -> "Fastest"
                STANDARD -> "Standard (1s)"
                SLOW -> "Slow (3s)"
                MINIMUM -> "Minimum (10s)"
            }
        }
    }
}

@Serializable
data class HeadwindSettings(
    val welcomeDialogAccepted: Boolean = false,
    val roundLocationTo: RoundLocationSetting = RoundLocationSetting.KM_1,
    val forecastedKmPerHour: Int = 20,
    val forecastedMilesPerHour: Int = 12,
    val lastUpdateRequested: Long? = null,
    val showDistanceInForecast: Boolean = true,
    val weatherProvider: WeatherDataProvider = WeatherDataProvider.OPEN_METEO,
    val openWeatherMapApiKey: String = "",
    val refreshRate: RefreshRate = RefreshRate.STANDARD,
    val windUnit: WindUnit? = null,
    val enableUpdateNotifications: Boolean = true,
    // --- TrueWind additions (SPEC 4, 5). All have defaults so upstream settings JSON decodes unchanged. ---
    /** Keeps karoo-headwind's computation: one provider, hourly steps, 10 m wind as rider wind. */
    val upstreamCompatibleMode: Boolean = false,
    /** Fetch refresh interval, minutes: 5/10/15/30. */
    val refreshIntervalMin: Int = 10,
    val exposure: ExposureSetting = ExposureSetting.MIXED,
    /** Rider torso height, m (0.9..1.5). */
    val riderHeightM: Double = 1.2,
    val cda: Double = 0.32,
    val crr: Double = 0.005,
    val bikeWeightKg: Double = 9.0,
    /** Drafting cannot be detected; disables the virtual wind sensor. */
    val groupRide: Boolean = false,
    /** Headline fields show the 10 m wind instead of rider-height wind. */
    val showWindAt10m: Boolean = false,
    val layerEnsemble: Boolean = true,
    val layerObservations: Boolean = true,
    val layerDownscaling: Boolean = true,
    val layerHeadingFusion: Boolean = true,
    val layerVirtualSensor: Boolean = true,
    /** Optional Synoptic Data token (non-commercial Open Access). Never logged. */
    val synopticToken: String = "",
    /** Optional Tomorrow.io API key. Never logged. */
    val tomorrowApiKey: String = "",
){

    companion object {
        val defaultSettings = Json.encodeToString(HeadwindSettings())
    }

    fun getForecastMetersPerHour(isImperial: Boolean): Int {
        return if (isImperial) forecastedMilesPerHour * 1609 else forecastedKmPerHour * 1000
    }

    fun getWindUnit(isImperial: Boolean): WindUnit {
        return windUnit ?: defaultWindUnit(isImperial)
    }

    /** Never prints API keys (settings objects end up in debug logs). */
    override fun toString(): String =
        "HeadwindSettings(welcome=$welcomeDialogAccepted, upstream=$upstreamCompatibleMode, refresh=$refreshIntervalMin, " +
            "round=$roundLocationTo, exposure=$exposure, height=$riderHeightM, cda=$cda, crr=$crr, bike=$bikeWeightKg, " +
            "group=$groupRide, show10m=$showWindAt10m, layers=${listOf(layerEnsemble, layerObservations, layerDownscaling, layerHeadingFusion, layerVirtualSensor)}, " +
            "provider=$weatherProvider, owm=${openWeatherMapApiKey.isNotBlank()}, synoptic=${synopticToken.isNotBlank()}, tomorrow=${tomorrowApiKey.isNotBlank()})"
}

/** Exposure presets for rider-height downscaling (SPEC 4.4), mapped to `core.physics.Exposure`. */
enum class ExposureSetting(val label: String, val exposure: Exposure) {
    OPEN("Open (coast, plains)", Exposure.OPEN),
    MIXED("Mixed (farmland, hedges)", Exposure.MIXED),
    SHELTERED("Sheltered (suburb, trees)", Exposure.SHELTERED),
    URBAN_FOREST("Urban / forest", Exposure.URBAN_FOREST),
}

/** CdA presets (SPEC 4.6), m². */
enum class CdaPreset(val label: String, val cda: Double) {
    HOODS("Road, hoods", 0.32),
    DROPS("Road, drops", 0.28),
    TT("TT / aero bars", 0.23),
    GRAVEL("Gravel", 0.36),
    MTB("MTB", 0.45),
}

/** Crr presets by surface (SPEC 4.6). */
enum class CrrPreset(val label: String, val crr: Double) {
    SMOOTH("Smooth asphalt", 0.004),
    ROAD("Average road", 0.005),
    ROUGH("Rough road", 0.007),
    GRAVEL("Gravel", 0.010),
    OFFROAD("Off-road / MTB", 0.015),
}

/** Refresh interval options (SPEC 4.1). */
val REFRESH_INTERVAL_OPTIONS_MIN = listOf(5, 10, 15, 30)

