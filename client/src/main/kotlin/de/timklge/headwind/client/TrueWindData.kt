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

package de.timklge.headwind.client

import kotlinx.serialization.Serializable

/**
 * Live TrueWind wind state (snapshot schema version 2). SI units: m/s, kg/m³, degrees.
 *
 * Direction conventions: [windDirection10m] and [riderWindDirection] are where the wind blows
 * FROM, degrees true. [relativeDirection] is relative to the rider's heading with 0 = wind from
 * dead ahead and 90 = from the right.
 */
@Serializable
data class TrueWindData(
    val timeEpochSeconds: Long,
    /** OK, NO_GPS, NO_DATA, STALE or NOT_SET_UP. */
    val status: String,
    /** Fused 10 m wind. */
    val windSpeed10m: Double? = null,
    val windDirection10m: Double? = null,
    val gust10m: Double? = null,
    /** Wind at rider height (downscaled and, when available, corrected by the virtual sensor). */
    val riderWindSpeed: Double? = null,
    val riderWindDirection: Double? = null,
    val riderGust: Double? = null,
    /** Headwind component, + into the rider. */
    val headwind: Double? = null,
    /** Crosswind component, + from the right. */
    val crosswind: Double? = null,
    val relativeDirection: Double? = null,
    /** 0-100. */
    val confidence: Int = 0,
    /** HIGH, MEDIUM or LOW. */
    val confidenceBand: String? = null,
    /** Age of the underlying data. */
    val ageSeconds: Long? = null,
    val airDensity: Double = 1.225,
    val riderHeading: Double? = null,
    /** Contributing forecast members, e.g. `open-meteo:ncep_hrrr_conus`. */
    val models: List<String> = emptyList(),
    val nearestStationId: String? = null,
    val nearestStationDistanceKm: Double? = null,
    val observationAgeMinutes: Double? = null,
    val virtualSensorActive: Boolean = false,
    /** Raw power-balance headwind (m/s, + into the rider); null when the sample was invalid. */
    val measuredHeadwind: Double? = null,
    val lastUpdateEpochSeconds: Long? = null,
    val nextUpdateEpochSeconds: Long? = null,
)
