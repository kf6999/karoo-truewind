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

package de.timklge.karooheadwind.weatherprovider

enum class WeatherInterpretation {
    CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, RAINY, SNOWY, DRIZZLE, THUNDERSTORM, UNKNOWN;

    companion object {
        // WMO weather interpretation codes (WW)
        fun fromWeatherCode(code: Int?): WeatherInterpretation {
            return when(code){
                0, 1 -> CLEAR               // clear, mainly clear
                2 -> PARTLY_CLOUDY
                3 -> CLOUDY                 // overcast
                45, 48 -> FOG
                51, 53, 55, 56, 57, 61, 80 -> DRIZZLE   // drizzle, slight rain, slight showers (light-rain icon)
                63, 65, 66, 67, 81, 82 -> RAINY
                71, 73, 75, 77, 85, 86 -> SNOWY
                95, 96, 99 -> THUNDERSTORM
                else -> UNKNOWN
            }
        }

        /**
         * The icon for the conditions actually reported. The weather code can come from a single
         * model while cloud cover and precipitation are blended, so for a dry-sky code the measured
         * cloud cover picks clear / partly cloudy / overcast, and measurable precipitation always shows.
         */
        fun of(code: Int?, cloudCoverPct: Double?, precipitationMm: Double?, temperatureC: Double? = null): WeatherInterpretation {
            val base = fromWeatherCode(code)
            if (base != CLEAR && base != PARTLY_CLOUDY && base != CLOUDY) return base
            if (precipitationMm != null && precipitationMm >= 0.1) {
                return when {
                    temperatureC != null && temperatureC <= 0.0 -> SNOWY
                    precipitationMm >= 1.0 -> RAINY
                    else -> DRIZZLE
                }
            }
            // 0 % under an "overcast" code means the cloud cover is missing, not a clear sky.
            if (cloudCoverPct == null || !cloudCoverPct.isFinite() || (cloudCoverPct == 0.0 && code == 3)) return base
            return when {
                cloudCoverPct < 25.0 -> CLEAR
                cloudCoverPct < 70.0 -> PARTLY_CLOUDY
                else -> CLOUDY
            }
        }

        fun of(w: WeatherData?): WeatherInterpretation =
            if (w == null) UNKNOWN else of(w.weatherCode, w.cloudCover, w.precipitation, w.temperature)

        fun getKnownWeatherCodes(): Set<Int> = setOf(0, 1, 2, 3, 45, 48, 61, 63, 65, 66, 67, 80, 81, 82, 71, 73, 75, 77, 85, 86, 51, 53, 55, 56, 57, 95, 96, 99)
    }
}
