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

package de.timklge.karooheadwind.fit

import android.content.Context
import de.timklge.karooheadwind.HeadingResponse
import de.timklge.karooheadwind.datatypes.WindAggregator
import de.timklge.karooheadwind.datatypes.WindCategoryStats
import de.timklge.karooheadwind.datatypes.WindSessionStats
import de.timklge.karooheadwind.pipeline.Diagnostics
import de.timklge.karooheadwind.pipeline.WindRepository
import de.timklge.karooheadwind.pipeline.WindSnapshot
import io.github.kf6999.truewind.core.model.Status
import de.timklge.karooheadwind.throttle
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DeveloperField
import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.WriteToRecordMesg
import io.hammerhead.karooext.models.WriteToSessionMesg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.cos

class HeadwindFitFileWriter(
    private val windRepository: WindRepository,
    private val windAggregator: WindAggregator,
) {
    companion object {
        // FIT base types
        private const val FIT_BASE_TYPE_UINT8: Short = 0x02
        private const val FIT_BASE_TYPE_UINT16: Short = 0x84
        private const val FIT_BASE_TYPE_UINT32: Short = 0x86
        internal const val FIT_BASE_TYPE_FLOAT32: Short = 0x88

        const val SESSION_UPDATE_INTERVAL_MS = 60_000L

        val WIND_SPEED = DeveloperField(0, FIT_BASE_TYPE_FLOAT32, "wind_speed", "m/s")
        val WIND_DIRECTION = DeveloperField(1, FIT_BASE_TYPE_UINT16, "wind_direction", "degrees")
        val HEADWIND_SPEED = DeveloperField(2, FIT_BASE_TYPE_FLOAT32, "headwind_speed", "m/s")
        val HEADWIND_DIRECTION = DeveloperField(3, FIT_BASE_TYPE_UINT16, "headwind_direction", "degrees")
        val WIND_GUST = DeveloperField(4, FIT_BASE_TYPE_FLOAT32, "wind_gust", "m/s")

        class CategoryFields(
            val timePercent: DeveloperField,
            val avgRideSpeed: DeveloperField,
            val maxRideSpeed: DeveloperField,
            val avgWindSpeed: DeveloperField,
            val maxWindSpeed: DeveloperField,
        )

        val HEADWIND_FIELDS = CategoryFields(
            timePercent = DeveloperField(5, FIT_BASE_TYPE_UINT16, "headwind_time_percent", "%"),
            avgRideSpeed = DeveloperField(6, FIT_BASE_TYPE_FLOAT32, "headwind_avg_ride_speed", "m/s"),
            maxRideSpeed = DeveloperField(7, FIT_BASE_TYPE_FLOAT32, "headwind_max_ride_speed", "m/s"),
            avgWindSpeed = DeveloperField(8, FIT_BASE_TYPE_FLOAT32, "headwind_avg_wind_speed", "m/s"),
            maxWindSpeed = DeveloperField(9, FIT_BASE_TYPE_FLOAT32, "headwind_max_wind_speed", "m/s"),
        )
        val TAILWIND_FIELDS = CategoryFields(
            timePercent = DeveloperField(10, FIT_BASE_TYPE_UINT16, "tailwind_time_percent", "%"),
            avgRideSpeed = DeveloperField(11, FIT_BASE_TYPE_FLOAT32, "tailwind_avg_ride_speed", "m/s"),
            maxRideSpeed = DeveloperField(12, FIT_BASE_TYPE_FLOAT32, "tailwind_max_ride_speed", "m/s"),
            avgWindSpeed = DeveloperField(13, FIT_BASE_TYPE_FLOAT32, "tailwind_avg_wind_speed", "m/s"),
            maxWindSpeed = DeveloperField(14, FIT_BASE_TYPE_FLOAT32, "tailwind_max_wind_speed", "m/s"),
        )
        val CROSSWIND_FIELDS = CategoryFields(
            timePercent = DeveloperField(15, FIT_BASE_TYPE_UINT16, "crosswind_time_percent", "%"),
            avgRideSpeed = DeveloperField(16, FIT_BASE_TYPE_FLOAT32, "crosswind_avg_ride_speed", "m/s"),
            maxRideSpeed = DeveloperField(17, FIT_BASE_TYPE_FLOAT32, "crosswind_max_ride_speed", "m/s"),
            avgWindSpeed = DeveloperField(18, FIT_BASE_TYPE_FLOAT32, "crosswind_avg_wind_speed", "m/s"),
            maxWindSpeed = DeveloperField(19, FIT_BASE_TYPE_FLOAT32, "crosswind_max_wind_speed", "m/s"),
        )
        val WIND_AVG_SPEED = DeveloperField(20, FIT_BASE_TYPE_FLOAT32, "wind_avg_speed", "m/s")
        val WIND_MAX_SPEED = DeveloperField(21, FIT_BASE_TYPE_FLOAT32, "wind_max_speed", "m/s")

        // TrueWind (SPEC 5, WP10). Record fields at <= 1 Hz, TrueWind mode only.
        val RIDER_WIND_SPEED = DeveloperField(22, FIT_BASE_TYPE_FLOAT32, "rider_wind_speed", "m/s")
        /** Direction the rider-height wind blows FROM, degrees true. */
        val RIDER_WIND_DIRECTION = DeveloperField(23, FIT_BASE_TYPE_UINT16, "rider_wind_direction", "degrees")
        val WIND_CONFIDENCE = DeveloperField(24, FIT_BASE_TYPE_UINT8, "wind_confidence", "%")
        /** Raw virtual-sensor headwind, + into the rider; omitted when the sample was invalid. */
        val MEASURED_HEADWIND = DeveloperField(25, FIT_BASE_TYPE_FLOAT32, "measured_headwind", "m/s")
        val AIR_DENSITY = DeveloperField(26, FIT_BASE_TYPE_FLOAT32, "air_density", "kg/m3")
        /**
         * Session: sources that contributed during the ride. karoo-ext [FieldValue] carries only
         * numbers, so this is a bitmask (see [WindSources]) rather than the string SPEC 5 names.
         */
        val WIND_SOURCES = DeveloperField(27, FIT_BASE_TYPE_UINT32, "wind_sources", "bitmask")

        /** TrueWind record values for one snapshot; empty in upstream mode or without data. */
        fun buildTrueWindRecordValues(snapshot: WindSnapshot): List<FieldValue> {
            if (snapshot.isPlaceholder || snapshot.extras.upstreamMode) return emptyList()
            val s = snapshot.state
            return buildList {
                s.rider?.let {
                    add(FieldValue(RIDER_WIND_SPEED, it.speed))
                    add(FieldValue(RIDER_WIND_DIRECTION, it.directionFromDeg))
                }
                if (s.status != Status.NOT_SET_UP) add(FieldValue(WIND_CONFIDENCE, s.confidence.coerceIn(0, 100).toDouble()))
                snapshot.extras.measuredHeadwind?.let { add(FieldValue(MEASURED_HEADWIND, it)) }
                add(FieldValue(AIR_DENSITY, s.airDensity))
            }
        }

        private fun MutableList<FieldValue>.addCategoryValues(fields: CategoryFields, stats: WindCategoryStats) {
            add(FieldValue(fields.timePercent, stats.timePercent))
            stats.avgRideSpeed?.let { add(FieldValue(fields.avgRideSpeed, it)) }
            stats.maxRideSpeed?.let { add(FieldValue(fields.maxRideSpeed, it)) }
            stats.avgWindSpeed?.let { add(FieldValue(fields.avgWindSpeed, it)) }
            stats.maxWindSpeed?.let { add(FieldValue(fields.maxWindSpeed, it)) }
        }

        fun buildSessionValues(stats: WindSessionStats): List<FieldValue> {
            return buildList {
                addCategoryValues(HEADWIND_FIELDS, stats.headwind)
                addCategoryValues(TAILWIND_FIELDS, stats.tailwind)
                addCategoryValues(CROSSWIND_FIELDS, stats.crosswind)
                stats.avgWindSpeed?.let { add(FieldValue(WIND_AVG_SPEED, it)) }
                stats.maxWindSpeed?.let { add(FieldValue(WIND_MAX_SPEED, it)) }
            }
        }

        /**
         * Standard record fields for [snapshot]: `wind_speed`/`wind_direction`/`wind_gust` are the fused
         * 10 m values (the rider-height values go to `rider_wind_*`); `headwind_speed` follows the
         * rider-height wind the rider actually feels.
         */
        fun buildRecordValuesFor(relativeAngle: Double?, snapshot: WindSnapshot): List<FieldValue> {
            val w10 = snapshot.weatherData()
            return buildRecordValues(
                relativeAngle = relativeAngle,
                windSpeed = w10?.windSpeed,
                windDirection = w10?.windDirection,
                windGust = w10?.windGusts,
                headwindBasisSpeed = snapshot.headlineWeatherData()?.windSpeed,
            )
        }

        fun buildRecordValues(
            relativeAngle: Double?,
            windSpeed: Double?,
            windDirection: Double?,
            windGust: Double?,
            /** Speed the headwind component is taken from (rider-height wind); defaults to [windSpeed]. */
            headwindBasisSpeed: Double? = windSpeed,
        ): List<FieldValue> {
            return buildList {
                if (windSpeed != null) add(FieldValue(WIND_SPEED, windSpeed))
                if (windDirection != null) add(FieldValue(WIND_DIRECTION, windDirection))
                if (windGust != null) add(FieldValue(WIND_GUST, windGust))

                if (relativeAngle != null && headwindBasisSpeed != null) {
                    val headwindSpeed = cos(Math.toRadians(relativeAngle + 180)) * headwindBasisSpeed
                    add(FieldValue(HEADWIND_SPEED, headwindSpeed))

                    val headwindDirection = if (relativeAngle < 0) relativeAngle + 360 else relativeAngle
                    add(FieldValue(HEADWIND_DIRECTION, headwindDirection))
                }
            }
        }
    }

    fun start(emitter: Emitter<FitEffect>): Job {
        val sources = WindSources()
        return CoroutineScope(Dispatchers.IO).launch {
            launch {
                combine(windRepository.relativeHeadingAndWeather, windRepository.snapshot) { (heading, _), snapshot ->
                    sources.add(snapshot)
                    buildRecordValuesFor((heading as? HeadingResponse.Value)?.diff, snapshot) + buildTrueWindRecordValues(snapshot)
                }
                    .throttle(1_000L)
                    .collect { values ->
                        if (values.isNotEmpty()) {
                            emitter.onNext(WriteToRecordMesg(values))
                        }
                    }
            }

            while (isActive) {
                delay(SESSION_UPDATE_INTERVAL_MS)
                val values = buildSessionValues(windAggregator.getSessionStats()) +
                    listOfNotNull(sources.mask.takeIf { it != 0L }?.let { FieldValue(WIND_SOURCES, it.toDouble()) })
                emitter.onNext(WriteToSessionMesg(values))
            }
        }
    }
}

/**
 * Sources that contributed to the wind during a ride, as the `wind_sources` session bitmask:
 * bit 0 Open-Meteo, 1 Tomorrow.io, 2 AWC (METAR), 3 NWS, 4 Synoptic, 5 virtual sensor,
 * 6 any other provider.
 */
class WindSources(private val stations: () -> List<Diagnostics.StationSummary> = { Diagnostics.stationsInUse }) {
    @Volatile var mask: Long = 0L
        private set

    fun add(snapshot: WindSnapshot) {
        if (snapshot.isPlaceholder || snapshot.extras.upstreamMode) return
        var m = mask
        snapshot.state.sources.models.forEach { m = m or bit(it.substringBefore(':')) }
        if (snapshot.state.sources.nearestStationId != null) stations().forEach { m = m or bit(it.source) }
        if (snapshot.state.sources.virtualSensorActive) m = m or (1L shl VIRTUAL_SENSOR)
        mask = m
    }

    companion object {
        const val OPEN_METEO = 0
        const val TOMORROW = 1
        const val AWC = 2
        const val NWS = 3
        const val SYNOPTIC = 4
        const val VIRTUAL_SENSOR = 5
        const val OTHER = 6

        fun bit(providerId: String): Long = 1L shl when (providerId) {
            "open-meteo" -> OPEN_METEO
            "tomorrow" -> TOMORROW
            "awc" -> AWC
            "nws" -> NWS
            "synoptic" -> SYNOPTIC
            else -> OTHER
        }

        /** Readable form of a mask, e.g. `open-meteo+awc+virtual`. */
        fun describe(mask: Long): String = listOf("open-meteo", "tomorrow", "awc", "nws", "synoptic", "virtual", "other")
            .filterIndexed { i, _ -> mask and (1L shl i) != 0L }.joinToString("+")
    }
}
