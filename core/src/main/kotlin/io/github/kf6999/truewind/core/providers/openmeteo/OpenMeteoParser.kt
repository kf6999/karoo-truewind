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

import io.github.kf6999.truewind.core.api.ForecastSeries
import io.github.kf6999.truewind.core.api.ForecastStep
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.providers.openmeteo.OpenMeteoVars as V
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** Prefix of every Open-Meteo member id in [ForecastSeries.memberId], e.g. `open-meteo:ecmwf_ifs`. */
const val OPEN_METEO_PREFIX = "open-meteo:"

fun openMeteoMemberId(model: String): String = OPEN_METEO_PREFIX + model

/** Strips [OPEN_METEO_PREFIX] if present, so callers may pass either form. */
fun openMeteoModelOf(memberId: String): String = memberId.removePrefix(OPEN_METEO_PREFIX)

/** Location-level values from Open-Meteo's `current` block (not member specific; unsuffixed even with `models=`). */
data class OpenMeteoCurrent(
    val timeMs: Long,
    val temperatureC: Double? = null,
    val relativeHumidityPct: Double? = null,
    val dewPointC: Double? = null,
    val apparentTemperatureC: Double? = null,
    val visibilityM: Double? = null,
    /** W/m² */
    val shortwaveRadiation: Double? = null,
    /** J/kg */
    val capeJPerKg: Double? = null,
    val uvIndex: Double? = null,
    val isDay: Boolean? = null,
    val surfacePressureHpa: Double? = null,
    val sealevelPressureHpa: Double? = null,
    val cloudCoverPct: Double? = null,
    val weatherCode: Int? = null,
    val precipitationMm: Double? = null,
    val wind10m: WindVector? = null,
    val gust10m: Double? = null,
)

data class OpenMeteoLocation(
    /** Index into the request's point list (Open-Meteo `location_id`). */
    val index: Int,
    /** The coordinate we asked for, when known. */
    val requested: GeoPoint?,
    /** Centre of the grid cell Open-Meteo answered with. */
    val grid: GeoPoint?,
    /** Model grid elevation, m (top-level `elevation`). */
    val elevationM: Double?,
    val current: OpenMeteoCurrent?,
)

/** An Open-Meteo error body `{"error":true,"reason":"..."}`, or a transport/HTTP failure. */
data class OpenMeteoError(val reason: String, val httpStatus: Int? = null) {
    /** Model id named by a `Cannot initialize MultiDomains from invalid String value <id>` error. */
    val invalidModel: String? get() = INVALID_MODEL.find(reason)?.groupValues?.get(1)

    /** `No data is available for this location`: a single out-of-domain model. */
    val noDataForLocation: Boolean get() = reason.contains("No data is available", ignoreCase = true)

    private companion object {
        val INVALID_MODEL = Regex("""invalid String value\s+([A-Za-z0-9_\-]+)""")
    }
}

/** Parsed nowcast (multi-model `minutely_15` + `current`). */
data class OpenMeteoForecast(
    val locations: List<OpenMeteoLocation> = emptyList(),
    /** One series per member per location that has at least one non-null wind step. */
    val series: List<ForecastSeries> = emptyList(),
    /** Requested model ids (unprefixed) with no non-null wind at any location. */
    val failedModels: List<String> = emptyList(),
    /** Unsuffixed blocks that could not be attributed to a single member and were skipped. */
    val unattributedBlocks: Int = 0,
    val error: OpenMeteoError? = null,
)

data class HourlyStep(
    val step: ForecastStep,
    val precipitationProbabilityPct: Double? = null,
    val uvIndex: Double? = null,
    val isDay: Boolean? = null,
)

data class HourlyLocation(
    val location: OpenMeteoLocation,
    /** Model id (unprefixed; `best_match` when no `models=` was sent) to hourly steps. */
    val members: Map<String, List<HourlyStep>>,
)

/** Parsed hourly forecast for the widgets plus the extra `current` variables. */
data class OpenMeteoHourly(
    val locations: List<HourlyLocation> = emptyList(),
    val error: OpenMeteoError? = null,
)

/**
 * Parsers for Open-Meteo forecast responses. Never throw: malformed input yields an [OpenMeteoError]
 * or empty results; unknown keys are ignored.
 *
 * Shapes handled (all seen live, see test fixtures): a single object for one coordinate, an array
 * for several (first element without `location_id`, later ones with it); per-model key suffixes;
 * members missing from a location (out of domain); the suffix dropped when only one member is left
 * at a location; all-null members; `unixtime` or ISO-8601 `time` arrays.
 */
object OpenMeteoParser {
    private val MINUTELY_VARS = V.NOWCAST_MINUTELY_15
    private val HOURLY_VARS = V.HOURLY

    fun parseError(text: String?, httpStatus: Int? = null): OpenMeteoError? {
        val o = parseJsonOrNull(text).asObject() ?: return null
        if (o["error"].asBoolean() != true) return null
        return OpenMeteoError(o["reason"].asString() ?: "unknown error", httpStatus)
    }

    /**
     * Parses a nowcast response.
     *
     * @param requestedModels the `models=` list sent (unprefixed); empty when none was sent.
     * @param points the coordinates sent, rider first.
     * @param covers whether a model is expected to have data at a point (see [ModelRegistry.covers]);
     *   used only to attribute unsuffixed keys when more than one model was requested.
     */
    fun parseNowcast(
        text: String?,
        requestedModels: List<String>,
        points: List<GeoPoint>,
        covers: (String, GeoPoint) -> Boolean = { _, _ -> true },
    ): OpenMeteoForecast {
        val root = parseJsonOrNull(text) ?: return OpenMeteoForecast(error = OpenMeteoError("unparseable response"))
        parseError(text)?.let { return OpenMeteoForecast(error = it) }
        val objects = locationObjects(root) ?: return OpenMeteoForecast(error = OpenMeteoError("unexpected JSON shape"))

        val locations = ArrayList<OpenMeteoLocation>()
        val series = ArrayList<ForecastSeries>()
        var unattributed = 0
        objects.forEachIndexed { i, obj ->
            val loc = location(obj, i, points)
            locations += loc
            val point = loc.requested ?: loc.grid ?: return@forEachIndexed
            val block = parseBlock(obj, "minutely_15", MINUTELY_VARS, requestedModels, point, covers)
            unattributed += block.unattributed
            for ((model, values) in block.members) {
                val steps = block.times.indices.map { t -> stepAt(block.times[t], values, t) }
                    .let { fillCurrent(it, loc.current) }
                if (steps.any { it.wind10m != null }) {
                    series += ForecastSeries(openMeteoMemberId(model), point, loc.elevationM, steps)
                }
            }
        }
        val withData = series.map { openMeteoModelOf(it.memberId) }.toSet()
        val expected = requestedModels.ifEmpty { listOf(ModelRegistry.BEST_MATCH) }
        val failed = expected.filter { it !in withData }
        return OpenMeteoForecast(locations, series, failed, unattributed)
    }

    /** Parses the hourly widget response (see [OpenMeteoRequests.hourlyUrl]). */
    fun parseHourly(
        text: String?,
        points: List<GeoPoint>,
        requestedModels: List<String> = emptyList(),
        covers: (String, GeoPoint) -> Boolean = { _, _ -> true },
    ): OpenMeteoHourly {
        val root = parseJsonOrNull(text) ?: return OpenMeteoHourly(error = OpenMeteoError("unparseable response"))
        parseError(text)?.let { return OpenMeteoHourly(error = it) }
        val objects = locationObjects(root) ?: return OpenMeteoHourly(error = OpenMeteoError("unexpected JSON shape"))
        val result = objects.mapIndexed { i, obj ->
            val loc = location(obj, i, points)
            val point = loc.requested ?: loc.grid ?: GeoPoint(0.0, 0.0)
            val block = parseBlock(obj, "hourly", HOURLY_VARS, requestedModels, point, covers)
            val members = block.members.mapValues { (_, values) ->
                block.times.indices.map { t ->
                    HourlyStep(
                        step = stepAt(block.times[t], values, t),
                        precipitationProbabilityPct = values[V.PRECIPITATION_PROBABILITY]?.getOrNull(t).asDouble(),
                        uvIndex = values[V.UV_INDEX]?.getOrNull(t).asDouble(),
                        isDay = values[V.IS_DAY]?.getOrNull(t).asBoolean(),
                    )
                }
            }
            HourlyLocation(loc, members)
        }
        return OpenMeteoHourly(result)
    }

    // ---- internals ----

    private fun locationObjects(root: JsonElement): List<JsonObject>? = when (root) {
        is JsonObject -> listOf(root)
        is JsonArray -> {
            val objs = root.mapNotNull { it.asObject() }
            // Order by location_id when present (first element omits it), else keep array order.
            objs.withIndex().sortedBy { (i, o) -> o["location_id"].asInt() ?: i }.map { it.value }
        }
        else -> null
    }

    private fun location(obj: JsonObject, arrayIndex: Int, points: List<GeoPoint>): OpenMeteoLocation {
        val index = obj["location_id"].asInt() ?: arrayIndex
        val lat = obj["latitude"].asDouble()
        val lon = obj["longitude"].asDouble()
        val offsetSec = obj["utc_offset_seconds"].asLong() ?: 0L
        return OpenMeteoLocation(
            index = index,
            requested = points.getOrNull(index),
            grid = if (lat != null && lon != null) GeoPoint(lat, lon) else null,
            elevationM = obj["elevation"].asDouble(),
            current = parseCurrent(obj["current"].asObject(), offsetSec),
        )
    }

    private fun parseCurrent(c: JsonObject?, offsetSec: Long): OpenMeteoCurrent? {
        if (c == null) return null
        val time = timeMs(c["time"], offsetSec) ?: return null
        // Unsuffixed in every response seen; fall back to the first suffixed variant just in case.
        fun d(name: String): Double? = c[name].asDouble()
            ?: c.entries.firstOrNull { it.key.startsWith(name + "_") && it.key.length > name.length + 1 }?.value.asDouble()
        val speed = d(V.WIND_SPEED)
        val dir = d(V.WIND_DIRECTION)
        return OpenMeteoCurrent(
            timeMs = time,
            temperatureC = d(V.TEMPERATURE),
            relativeHumidityPct = d(V.RELATIVE_HUMIDITY),
            dewPointC = d(V.DEW_POINT),
            apparentTemperatureC = d(V.APPARENT_TEMPERATURE),
            visibilityM = d(V.VISIBILITY),
            shortwaveRadiation = d(V.SHORTWAVE_RADIATION),
            capeJPerKg = d(V.CAPE),
            uvIndex = d(V.UV_INDEX),
            isDay = d(V.IS_DAY)?.let { it != 0.0 },
            surfacePressureHpa = d(V.SURFACE_PRESSURE),
            sealevelPressureHpa = d(V.PRESSURE_MSL),
            cloudCoverPct = d(V.CLOUD_COVER),
            weatherCode = d(V.WEATHER_CODE)?.toInt(),
            precipitationMm = d(V.PRECIPITATION),
            wind10m = wind(speed, dir),
            gust10m = d(V.WIND_GUSTS),
        )
    }

    private class Block(
        val times: List<Long>,
        /** model -> variable -> values */
        val members: Map<String, Map<String, JsonArray>>,
        val unattributed: Int,
    )

    private fun parseBlock(
        obj: JsonObject,
        blockName: String,
        vars: List<String>,
        requestedModels: List<String>,
        point: GeoPoint,
        covers: (String, GeoPoint) -> Boolean,
    ): Block {
        val block = obj[blockName].asObject() ?: return Block(emptyList(), emptyMap(), 0)
        val offsetSec = obj["utc_offset_seconds"].asLong() ?: 0L
        val timeArr = block["time"].asArray() ?: return Block(emptyList(), emptyMap(), 0)
        val times = timeArr.map { timeMs(it, offsetSec) }
        // Steps with an unparseable time are skipped, keeping value indices aligned.
        val validIdx = times.indices.filter { times[it] != null }

        val requested = requestedModels.toSet()
        val byLength = vars.sortedByDescending { it.length }
        val suffixed = LinkedHashMap<String, MutableMap<String, JsonArray>>()
        val unsuffixed = LinkedHashMap<String, JsonArray>()
        for ((key, value) in block) {
            if (key == "time") continue
            val arr = value.asArray() ?: continue
            for (v in byLength) {
                if (key == v) {
                    unsuffixed[v] = arr; break
                }
                if (key.startsWith(v + "_")) {
                    val model = key.substring(v.length + 1)
                    if (model in requested) {
                        suffixed.getOrPut(model) { LinkedHashMap() }[v] = arr; break
                    }
                }
            }
        }
        var unattributed = 0
        if (unsuffixed.isNotEmpty()) {
            val owner = when {
                requestedModels.isEmpty() -> ModelRegistry.BEST_MATCH
                requestedModels.size == 1 -> requestedModels[0]
                else -> requestedModels.filter { it !in suffixed && covers(it, point) }.singleOrNull()
            }
            if (owner != null && owner !in suffixed) suffixed[owner] = unsuffixed else unattributed = 1
        }
        // Order members by request priority so downstream de-duplication keeps the preferred one.
        val order = requestedModels.withIndex().associate { it.value to it.index }
        val members = suffixed.entries.sortedBy { order[it.key] ?: Int.MAX_VALUE }.associate { (model, values) ->
            model to values.mapValues { (_, arr) -> JsonArray(validIdx.map { arr.getOrNull(it) ?: kotlinx.serialization.json.JsonNull }) }
        }
        return Block(validIdx.map { times[it]!! }, members, unattributed)
    }

    private fun stepAt(time: Long, values: Map<String, JsonArray>, i: Int): ForecastStep {
        fun d(name: String): Double? = values[name]?.getOrNull(i).asDouble()
        return ForecastStep(
            timeMs = time,
            wind10m = wind(d(V.WIND_SPEED), d(V.WIND_DIRECTION)),
            gust10m = d(V.WIND_GUSTS),
            temperatureC = d(V.TEMPERATURE),
            relativeHumidityPct = d(V.RELATIVE_HUMIDITY),
            precipitationMm = d(V.PRECIPITATION),
            surfacePressureHpa = d(V.SURFACE_PRESSURE),
            sealevelPressureHpa = d(V.PRESSURE_MSL),
            cloudCoverPct = d(V.CLOUD_COVER),
            weatherCode = d(V.WEATHER_CODE)?.toInt(),
        )
    }

    /**
     * Puts the location-level `current` values that `minutely_15` lacks (pressure, cloud, weather
     * code) on the step at `current.time`, without overwriting member values.
     */
    private fun fillCurrent(steps: List<ForecastStep>, current: OpenMeteoCurrent?): List<ForecastStep> {
        if (current == null) return steps
        return steps.map { s ->
            if (s.timeMs != current.timeMs) s else s.copy(
                surfacePressureHpa = s.surfacePressureHpa ?: current.surfacePressureHpa,
                sealevelPressureHpa = s.sealevelPressureHpa ?: current.sealevelPressureHpa,
                cloudCoverPct = s.cloudCoverPct ?: current.cloudCoverPct,
                weatherCode = s.weatherCode ?: current.weatherCode,
            )
        }
    }

    /** Speed (m/s) and FROM-direction (deg) to u/v; calm (speed 0) needs no direction. */
    internal fun wind(speed: Double?, directionFromDeg: Double?): WindVector? = when {
        speed == null || speed < 0.0 -> null
        speed == 0.0 -> WindVector.ZERO
        directionFromDeg == null -> null
        else -> WindVector.fromSpeedDirection(speed, directionFromDeg)
    }

    private fun timeMs(e: JsonElement?, utcOffsetSec: Long): Long? {
        e.asLong()?.let { return it * 1000L }
        val s = e.asString() ?: return null
        return try {
            LocalDateTime.parse(s).toEpochSecond(ZoneOffset.UTC).minus(utcOffsetSec) * 1000L
        } catch (ex: DateTimeParseException) {
            null
        }
    }
}

/**
 * Removes members whose wind series is identical to a member listed earlier at the same location
 * (e.g. `best_match` duplicating HRRR in CONUS or ECMWF near Sydney), so the fusion does not
 * double-count one model. Input order is the priority order; the first of a set of twins is kept.
 */
fun dropDuplicateMembers(series: List<ForecastSeries>, tolerance: Double = 1e-6): List<ForecastSeries> {
    val kept = ArrayList<ForecastSeries>()
    for (s in series) {
        val twin = kept.any { k ->
            k.location == s.location && k.steps.size == s.steps.size && k.steps.isNotEmpty() &&
                k.steps.indices.all { i ->
                    val a = k.steps[i]
                    val b = s.steps[i]
                    a.timeMs == b.timeMs && sameWind(a.wind10m, b.wind10m, tolerance)
                }
        }
        if (!twin) kept += s
    }
    return kept
}

private fun sameWind(a: WindVector?, b: WindVector?, tol: Double): Boolean =
    if (a == null || b == null) a == b else kotlin.math.abs(a.u - b.u) <= tol && kotlin.math.abs(a.v - b.v) <= tol
