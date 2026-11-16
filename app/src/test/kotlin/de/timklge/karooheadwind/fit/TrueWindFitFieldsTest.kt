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

package de.timklge.karooheadwind.fit

import de.timklge.karooheadwind.aidl.model.HeadwindSnapshot
import de.timklge.karooheadwind.aidl.model.buildTrueWindData
import de.timklge.karooheadwind.aidl.model.headwindSnapshotJson
import de.timklge.karooheadwind.pipeline.Diagnostics
import de.timklge.karooheadwind.pipeline.WindExtras
import de.timklge.karooheadwind.pipeline.WindSnapshot
import io.github.kf6999.truewind.core.fusion.ConfidenceBand
import io.github.kf6999.truewind.core.model.SourceSummary
import io.github.kf6999.truewind.core.model.Status
import io.github.kf6999.truewind.core.model.WindState
import io.github.kf6999.truewind.core.model.WindVector
import io.hammerhead.karooext.models.DeveloperField
import io.hammerhead.karooext.models.FieldValue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WP10: FIT developer fields and the AIDL v2 block. */
class TrueWindFitFieldsTest {
    private fun List<FieldValue>.valueOf(field: DeveloperField): Double? = find { it.fieldNum == field.fieldDefinitionNumber.toInt() }?.value

    private fun snapshot(upstream: Boolean = false, measured: Double? = 1.5, virtual: Boolean = true) = WindSnapshot(
        WindState(
            timeMs = 1_794_657_600_000L,
            model10m = WindVector.fromSpeedDirection(6.0, 270.0),
            rider = WindVector.fromSpeedDirection(4.0, 265.0),
            gustRider = 6.5, headwind = 1.0, crosswind = -3.8, relativeDirectionDeg = 345.0, confidence = 81,
            sources = SourceSummary(models = listOf("open-meteo:ncep_hrrr_conus", "tomorrow:nowcast"), nearestStationId = "KSQL",
                nearestStationDistanceKm = 6.0, virtualSensorActive = virtual),
            ageMs = 120_000L, airDensity = 1.21, weather = null, status = Status.OK, riderHeadingDeg = 280.0,
        ),
        WindExtras(upstreamMode = upstream, band = ConfidenceBand.HIGH, measuredHeadwind = measured, lastUpdateMs = 1_794_657_480_000L),
    )

    @Test
    fun fieldNumbersAreSequentialAndUnique() {
        val all = listOf(
            HeadwindFitFileWriter.WIND_MAX_SPEED, HeadwindFitFileWriter.RIDER_WIND_SPEED, HeadwindFitFileWriter.RIDER_WIND_DIRECTION,
            HeadwindFitFileWriter.WIND_CONFIDENCE, HeadwindFitFileWriter.MEASURED_HEADWIND, HeadwindFitFileWriter.AIR_DENSITY,
            HeadwindFitFileWriter.WIND_SOURCES,
        ).map { it.fieldDefinitionNumber.toInt() }
        assertEquals((21..27).toList(), all)
        assertEquals(listOf("rider_wind_speed", "rider_wind_direction", "wind_confidence", "measured_headwind", "air_density", "wind_sources"),
            listOf(HeadwindFitFileWriter.RIDER_WIND_SPEED, HeadwindFitFileWriter.RIDER_WIND_DIRECTION, HeadwindFitFileWriter.WIND_CONFIDENCE,
                HeadwindFitFileWriter.MEASURED_HEADWIND, HeadwindFitFileWriter.AIR_DENSITY, HeadwindFitFileWriter.WIND_SOURCES).map { it.fieldName })
    }

    @Test
    fun recordValues_trueWindMode() {
        val v = HeadwindFitFileWriter.buildTrueWindRecordValues(snapshot())
        assertEquals(4.0, v.valueOf(HeadwindFitFileWriter.RIDER_WIND_SPEED)!!, 1e-9)
        assertEquals(265.0, v.valueOf(HeadwindFitFileWriter.RIDER_WIND_DIRECTION)!!, 1e-9)
        assertEquals(81.0, v.valueOf(HeadwindFitFileWriter.WIND_CONFIDENCE))
        assertEquals(1.5, v.valueOf(HeadwindFitFileWriter.MEASURED_HEADWIND))
        assertEquals(1.21, v.valueOf(HeadwindFitFileWriter.AIR_DENSITY))
    }

    @Test
    fun standardWindFields_are10m_riderFieldsAreDownscaled() {
        val tw = WindSnapshot(
            WindState(
                timeMs = 1_794_657_600_000L, model10m = WindVector.fromSpeedDirection(5.0, 270.0),
                rider = WindVector.fromSpeedDirection(2.7, 270.0), gustRider = 3.0, headwind = null, crosswind = null,
                relativeDirectionDeg = null, confidence = 80, sources = SourceSummary(), ageMs = 1000L, airDensity = 1.2,
                weather = io.github.kf6999.truewind.core.model.WeatherNow(
                    timeSec = 1, temperatureC = 20.0, relativeHumidityPct = 40, precipitationMm = 0.0,
                    precipitationProbabilityPct = null, cloudCoverPct = 0.0, sealevelPressureHpa = 1013.0, surfacePressureHpa = 1000.0,
                    windSpeed = 5.0, windDirectionDeg = 270.0, windGusts = 7.0, weatherCode = 0, isForecast = true, isNight = false, uvi = 0.0,
                ),
                status = Status.OK, riderHeadingDeg = 0.0,
            ),
            WindExtras(upstreamMode = false),
        )
        val v = HeadwindFitFileWriter.buildRecordValuesFor(90.0, tw) + HeadwindFitFileWriter.buildTrueWindRecordValues(tw)
        assertEquals(5.0, v.valueOf(HeadwindFitFileWriter.WIND_SPEED)!!, 1e-9)
        assertEquals(7.0, v.valueOf(HeadwindFitFileWriter.WIND_GUST)!!, 1e-9)
        assertEquals(2.7, v.valueOf(HeadwindFitFileWriter.RIDER_WIND_SPEED)!!, 1e-9)
    }

    @Test
    fun defaultSettings_downscaleToRiderHeight_ratio054() {
        val st = de.timklge.karooheadwind.HeadwindSettings()
        assertTrue(st.layerDownscaling)
        val ratio = io.github.kf6999.truewind.core.physics.LogLaw.ratio(st.exposure.exposure, st.riderHeightM)
        assertEquals(0.54, ratio, 0.005)
    }

    @Test
    fun recordValues_invalidMeasurementOmitted_upstreamModeEmpty() {
        assertNull(HeadwindFitFileWriter.buildTrueWindRecordValues(snapshot(measured = null)).valueOf(HeadwindFitFileWriter.MEASURED_HEADWIND))
        assertTrue(HeadwindFitFileWriter.buildTrueWindRecordValues(snapshot(upstream = true)).isEmpty())
        assertTrue(HeadwindFitFileWriter.buildTrueWindRecordValues(WindSnapshot.PLACEHOLDER).isEmpty())
    }

    @Test
    fun windSources_bitmask() {
        val stations = listOf(Diagnostics.StationSummary("KSQL", "awc", 6.0, 4.0, 3.0), Diagnostics.StationSummary("X1", "synoptic", 9.0, 2.0, 2.0))
        val s = WindSources { stations }
        s.add(snapshot())
        assertEquals("open-meteo+tomorrow+awc+synoptic+virtual", WindSources.describe(s.mask))
        val before = s.mask
        s.add(snapshot(upstream = true))
        assertEquals(before, s.mask, "upstream-mode snapshots add nothing")
    }

    @Test
    fun aidlV2_keepsV1Fields_andAddsTrueWind() {
        val tw = assertNotNull(buildTrueWindData(snapshot()))
        assertEquals(4.0, tw.riderWindSpeed!!, 1e-9)
        assertEquals(270.0, tw.windDirection10m!!, 1e-9)
        assertEquals(345.0, tw.relativeDirection)
        assertEquals("HIGH", tw.confidenceBand)
        assertEquals(120L, tw.ageSeconds)
        assertNull(buildTrueWindData(snapshot(upstream = true)))

        val snap = HeadwindSnapshot(error = null, lastSuccessfulFetchEpochSeconds = 100, trueWind = tw)
        assertEquals(2, snap.version)
        val json = headwindSnapshotJson.encodeToString(snap)
        assertTrue("\"lastSuccessfulFetchEpochSeconds\":100" in json)
        assertTrue("\"trueWind\":{" in json)
        // A v1 client (ignoreUnknownKeys) still reads it.
        val v1 = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<V1Snapshot>(json)
        assertEquals(100L, v1.lastSuccessfulFetchEpochSeconds)
    }

    @kotlinx.serialization.Serializable
    private data class V1Snapshot(val version: Int, val lastSuccessfulFetchEpochSeconds: Long? = null)
}
