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

package io.github.kf6999.truewind.core.providers.openmeteo

import io.github.kf6999.truewind.core.api.HttpRequest
import io.github.kf6999.truewind.core.model.GeoPoint

/**
 * Open-Meteo variable names, verified live on 2026-11-15 (all units as returned with
 * `wind_speed_unit=ms`): `dew_point_2m` °C, `apparent_temperature` °C, `visibility` m,
 * `shortwave_radiation` W/m², `cape` J/kg, `uv_index` (dimensionless; null at night for some
 * models in the multi-model `current` block), `is_day` 0/1, top-level `elevation` m (grid cell).
 */
object OpenMeteoVars {
    const val WIND_SPEED = "wind_speed_10m"
    const val WIND_DIRECTION = "wind_direction_10m"
    const val WIND_GUSTS = "wind_gusts_10m"
    const val TEMPERATURE = "temperature_2m"
    const val RELATIVE_HUMIDITY = "relative_humidity_2m"
    const val PRECIPITATION = "precipitation"
    const val PRECIPITATION_PROBABILITY = "precipitation_probability"
    const val SURFACE_PRESSURE = "surface_pressure"
    const val PRESSURE_MSL = "pressure_msl"
    const val CLOUD_COVER = "cloud_cover"
    const val WEATHER_CODE = "weather_code"
    const val IS_DAY = "is_day"
    const val UV_INDEX = "uv_index"
    const val DEW_POINT = "dew_point_2m"
    const val APPARENT_TEMPERATURE = "apparent_temperature"
    const val VISIBILITY = "visibility"
    const val SHORTWAVE_RADIATION = "shortwave_radiation"
    const val CAPE = "cape"

    /** SPEC 4.2 nowcast `minutely_15` set, per member. */
    val NOWCAST_MINUTELY_15 = listOf(WIND_SPEED, WIND_DIRECTION, WIND_GUSTS, TEMPERATURE, RELATIVE_HUMIDITY, PRECIPITATION)

    /** SPEC 4.2 nowcast `current` set. In multi-model responses `current` keys come back unsuffixed. */
    val NOWCAST_CURRENT = listOf(SURFACE_PRESSURE, PRESSURE_MSL, CLOUD_COVER, WEATHER_CODE, IS_DAY, UV_INDEX)

    /** Hourly set for the forecast widgets (12 h). */
    val HOURLY = listOf(
        TEMPERATURE, RELATIVE_HUMIDITY, PRECIPITATION, PRECIPITATION_PROBABILITY, CLOUD_COVER, WEATHER_CODE,
        WIND_SPEED, WIND_DIRECTION, WIND_GUSTS, SURFACE_PRESSURE, PRESSURE_MSL, UV_INDEX, IS_DAY,
    )

    /** `current` set of the hourly request: everything the WP15 extra fields need. */
    val HOURLY_CURRENT = listOf(
        TEMPERATURE, RELATIVE_HUMIDITY, DEW_POINT, APPARENT_TEMPERATURE, VISIBILITY, SHORTWAVE_RADIATION, CAPE,
        UV_INDEX, IS_DAY, SURFACE_PRESSURE, PRESSURE_MSL, CLOUD_COVER, WEATHER_CODE, PRECIPITATION,
        WIND_SPEED, WIND_DIRECTION, WIND_GUSTS,
    )
}

/**
 * Builds Open-Meteo forecast URLs (SPEC 4.1, 4.2). Pure; no I/O.
 *
 * Size guide from live probes (2026-11-15): nowcast with 3 points × 5 models × 6 variables × 18
 * steps is 16 KB raw / 2.6 KB gzip; 10 points is 53 KB raw / 6.9 KB gzip; 2 points × 4 models
 * 9.4 KB raw / 1.9 KB gzip. Requests therefore ask for gzip ([ACCEPT_GZIP]); [decodeBody] gunzips.
 */
object OpenMeteoRequests {
    const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"

    /** Open-Meteo accepts many coordinates; SPEC 4.2 caps a request at 10. */
    const val MAX_POINTS = 10

    val ACCEPT_GZIP: Map<String, String> = mapOf("Accept-Encoding" to "gzip")

    /** Global model with complete `current` pressure/cloud/weather-code values (see [nowcastUrl]). */
    const val DEFAULT_CURRENT_SOURCE = "ecmwf_ifs"

    /**
     * The per-cycle nowcast: `minutely_15` from −[pastSteps]×15 min to +[forecastSteps]×15 min
     * (default −30 min … +4 h) for each member in [models] at each of [points] (rider first), plus
     * location-level `current` values. Empty [models] means the provider default (`best_match`,
     * unsuffixed keys).
     *
     * Live finding (2026-11-15): with several models the unsuffixed `current` block is taken from
     * the **first** model in `models=` only. AROME France HD, for example, has no surface pressure,
     * cloud cover or weather code there, so a request led by it returns nulls for them.
     * [currentFrom] (if present in [models]) is therefore moved to the front of the `models=` list;
     * member priority is unaffected because the parser orders members itself. `uv_index` is null in
     * `current` for ECMWF and HRRR; take UV from [hourlyUrl] (`best_match`).
     */
    fun nowcastUrl(
        points: List<GeoPoint>,
        models: List<String>,
        pastSteps: Int = 2,
        forecastSteps: Int = 16,
        minutely15: List<String> = OpenMeteoVars.NOWCAST_MINUTELY_15,
        current: List<String> = OpenMeteoVars.NOWCAST_CURRENT,
        currentFrom: String? = DEFAULT_CURRENT_SOURCE,
        baseUrl: String = FORECAST_URL,
    ): String {
        require(points.isNotEmpty()) { "at least one point" }
        val q = LinkedHashMap<String, String>()
        putCoordinates(q, points)
        q["minutely_15"] = minutely15.joinToString(",")
        if (current.isNotEmpty()) q["current"] = current.joinToString(",")
        if (models.isNotEmpty()) {
            val ordered = if (currentFrom != null && currentFrom in models) listOf(currentFrom) + (models - currentFrom) else models
            q["models"] = ordered.joinToString(",")
        }
        q["past_minutely_15"] = pastSteps.coerceAtLeast(0).toString()
        q["forecast_minutely_15"] = forecastSteps.coerceAtLeast(1).toString()
        q["wind_speed_unit"] = "ms"
        q["timeformat"] = "unixtime"
        return build(baseUrl, q)
    }

    /**
     * Hourly forecast for the widgets (at most every 60 min, SPEC 4.1): [hours] hourly steps from the
     * current hour, plus the extra `current` variables (dew point, visibility, radiation, CAPE, ...).
     * Defaults to the provider's `best_match` (no `models=`), which keeps keys unsuffixed and the
     * response at about 2.4 KB for one point.
     */
    fun hourlyUrl(
        points: List<GeoPoint>,
        hours: Int = 12,
        models: List<String> = emptyList(),
        hourly: List<String> = OpenMeteoVars.HOURLY,
        current: List<String> = OpenMeteoVars.HOURLY_CURRENT,
        baseUrl: String = FORECAST_URL,
    ): String {
        require(points.isNotEmpty()) { "at least one point" }
        val q = LinkedHashMap<String, String>()
        putCoordinates(q, points)
        if (current.isNotEmpty()) q["current"] = current.joinToString(",")
        q["hourly"] = hourly.joinToString(",")
        if (models.isNotEmpty()) q["models"] = models.joinToString(",")
        q["forecast_hours"] = hours.coerceIn(1, 384).toString()
        q["wind_speed_unit"] = "ms"
        q["timeformat"] = "unixtime"
        return build(baseUrl, q)
    }

    fun get(url: String): HttpRequest = HttpRequest(method = "GET", url = url, headers = ACCEPT_GZIP)

    private fun putCoordinates(q: MutableMap<String, String>, points: List<GeoPoint>) {
        val capped = points.take(MAX_POINTS)
        q["latitude"] = capped.joinToString(",") { formatCoordinate(it.lat) }
        q["longitude"] = capped.joinToString(",") { formatCoordinate(it.lon) }
    }

    // Commas are kept literal (Open-Meteo's documented form, and easier to read in diagnostics).
    private fun build(base: String, q: Map<String, String>): String =
        base + "?" + q.entries.joinToString("&") { (k, v) -> k + "=" + urlEncode(v).replace("%2C", ",") }
}
