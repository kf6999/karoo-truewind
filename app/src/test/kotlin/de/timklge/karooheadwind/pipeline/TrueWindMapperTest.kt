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
import de.timklge.karooheadwind.datatypes.FieldInputs
import de.timklge.karooheadwind.datatypes.TrueWindFields
import io.github.kf6999.truewind.core.fusion.ConfidenceBand
import io.github.kf6999.truewind.core.fusion.FusedSample
import io.github.kf6999.truewind.core.fusion.WeatherScalars
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.SourceSummary
import io.github.kf6999.truewind.core.model.Status
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.virtualwind.RiderWindEstimator
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WindState / WindSnapshot mapping (SPEC 3.1, 5) and the direction conventions. */
class TrueWindMapperTest {
    private val now = PipelineFixtures.T0_SEC * 1000
    private val settings = HeadwindSettings(welcomeDialogAccepted = true, upstreamCompatibleMode = false)
    private val pos = GeoPoint(37.6, -122.4)

    /** 6 m/s from the north. */
    private fun sample(status: Status = Status.OK) = FusedSample(
        timeMs = now, status = status, model10m = WindVector.fromSpeedDirection(6.0, 0.0), gust10m = 9.0, confidence = 72,
        band = ConfidenceBand.MEDIUM, directionReliable = true,
        weather = WeatherScalars(temperatureC = 15.0, relativeHumidityPct = 50.0, surfacePressureHpa = 1010.0, sealevelPressureHpa = 1012.0),
        sources = SourceSummary(models = listOf("open-meteo:ncep_hrrr_conus", "open-meteo:ncep_nbm_conus"), nearestStationId = "KSQL",
            nearestStationDistanceKm = 6.2, observationAgeMin = 4.0),
        ageMs = 4 * 60_000L,
    )

    /** Riding north at 8 m/s with a GPS course. */
    private fun inputs(position: GeoPoint? = pos) = TickInputs(nowMs = now, position = position, gpsCourseDeg = 0.0, gpsAccuracyM = 5.0,
        speedMps = 8.0, karooHeadingDeg = 10.0, elevationM = 20.0)

    private fun map(s: HeadwindSettings = settings, sample: FusedSample = sample(), t: TickInputs = inputs()): WindSnapshot {
        val est = RiderWindEstimator(TrueWindMapper.riderWindConfig(s, 75.0, null, null))
        val out = est.update(TrueWindMapper.riderInputs(sample, t, s))
        return TrueWindMapper.toSnapshot(sample, out, t, s, legacy = null, lastUpdateMs = now - 60_000, nextUpdateMs = now + 9 * 60_000,
            maxWindThisRide = null, rainInMin = null)
    }

    @Test
    fun riderWindIsDownscaledAndHeadline() {
        val snap = map()
        assertEquals(Status.OK, snap.state.status)
        val rider = assertNotNull(snap.state.rider)
        assertTrue(rider.speed < 6.0 && rider.speed > 1.0, "rider height wind below the 10 m wind, got ${rider.speed}")
        assertEquals(rider, snap.headline)
        assertEquals(6.0, snap.weatherData()!!.windSpeed, 1e-6, "Wind 10 m fields keep the 10 m value")
        assertEquals(rider.speed, snap.headlineWeatherData()!!.windSpeed, 1e-9)
        assertEquals(ConfidenceBand.MEDIUM, snap.extras.band)
        assertEquals(72, snap.state.confidence)
        assertTrue(snap.state.headwind!! > 0, "riding into a north wind is a headwind")
    }

    @Test
    fun directionConventionsAreNotMixed() {
        val snap = map()
        // SPEC relativeDirectionDeg: 0 = wind from dead ahead.
        val rel = snap.state.relativeDirectionDeg!!
        assertTrue(rel < 15 || rel > 345, "wind from ahead, got $rel")
        // Upstream arrow value: direction the wind blows TO relative to the rider, 0 = tailwind.
        val arrow = (snap.relativeHeadingResponse() as HeadingResponse.Value).diff
        assertTrue(abs(abs(arrow) - 180) < 15, "headwind arrow points back (180), got $arrow")
    }

    @Test
    fun show10m_switchesHeadline() {
        val snap = map(settings.copy(showWindAt10m = true))
        assertEquals(snap.state.model10m, snap.headline)
        assertEquals(9.0, snap.headlineGust)
    }

    @Test
    fun downscalingOff_riderEqualsModel() {
        val snap = map(settings.copy(layerDownscaling = false))
        assertEquals(snap.state.model10m, snap.state.rider)
    }

    @Test
    fun headingFusionOff_dropsKarooHeadingAndRoute() {
        val t = inputs().copy(routeBearingDeg = 45.0)
        val r = TrueWindMapper.riderInputs(sample(), t, settings.copy(layerHeadingFusion = false))
        assertNull(r.karooHeadingDeg)
        assertNull(r.routeBearingDeg)
        val on = TrueWindMapper.riderInputs(sample(), t, settings)
        assertEquals(10.0, on.karooHeadingDeg)
        assertEquals(45.0, on.routeBearingDeg)
    }

    @Test
    fun statusRules() {
        assertEquals(Status.NOT_SET_UP, map(settings.copy(welcomeDialogAccepted = false)).state.status)
        assertEquals(Status.NO_GPS, map(t = inputs(position = null)).state.status)
        assertEquals(Status.STALE, map(sample = sample(Status.STALE)).state.status)
    }

    @Test
    fun noData_hasNoWindAndPlaceholderFields() {
        val snap = map(sample = FusedSample.noData(now))
        assertNull(snap.state.model10m)
        assertNull(snap.state.rider)
        assertNull(snap.extras.band)
        assertEquals(Status.NO_DATA, snap.state.status)
    }

    @Test
    fun sourceText_formats() {
        assertEquals("HRRR+NBM · KSQL 6 km · 4 min", map().extras.sourceText)
        assertEquals("--", TrueWindMapper.sourceText(SourceSummary(), null))
        assertEquals("ECMWF · PWR", TrueWindMapper.sourceText(SourceSummary(models = listOf("open-meteo:ecmwf_ifs"), virtualSensorActive = true), null))
    }

    @Test
    fun rainIn_firstStepAboveThreshold() {
        assertEquals(30, TrueWindMapper.rainInMinutes(now, { t -> if (t >= now + 30 * 60_000L) 0.4 else 0.0 }))
        assertNull(TrueWindMapper.rainInMinutes(now, { 0.0 }))
        assertEquals(WindExtras.RAIN_UNKNOWN, TrueWindMapper.rainInMinutes(now, { null }))
    }

    @Test
    fun fields_values() {
        val snap = map()
        val i = FieldInputs(snap, settings.copy(windUnit = de.timklge.karooheadwind.WindUnit.METERS_PER_SECOND), PipelineFixtures.metricProfile)
        assertEquals(72.0, TrueWindFields.value(TrueWindFields.CONFIDENCE, i))
        assertEquals(6.2, TrueWindFields.value(TrueWindFields.STATION_DISTANCE, i)!!, 1e-9)
        assertEquals(1.0, TrueWindFields.value(TrueWindFields.LAST_UPDATE, i))
        assertEquals(9.0, TrueWindFields.value(TrueWindFields.NEXT_UPDATE, i))
        assertNull(TrueWindFields.value(TrueWindFields.RAIN_IN, i), "no rain in the next 2 h")
        assertTrue(TrueWindFields.value(TrueWindFields.AIR_DENSITY, i)!! in 1.1..1.3)
        val cross = TrueWindFields.value(TrueWindFields.CROSSWIND, i)!!
        assertTrue(abs(cross) < 1.0, "wind from ahead has little crosswind, got $cross")
        // Upstream mode: no confidence.
        val up = FieldInputs(WindSnapshot.upstream(snap.state), i.settings, i.profile)
        assertNull(TrueWindFields.value(TrueWindFields.CONFIDENCE, up))
    }

    @Test
    fun tickPolicy() {
        assertTrue(TickPolicy.shouldRun(settings, fieldVisible = true, screenOpen = false, recording = false))
        assertTrue(TickPolicy.shouldRun(settings, fieldVisible = false, screenOpen = true, recording = false))
        assertTrue(TickPolicy.shouldRun(settings, fieldVisible = false, screenOpen = false, recording = true))
        assertTrue(!TickPolicy.shouldRun(settings, fieldVisible = false, screenOpen = false, recording = false))
        assertTrue(!TickPolicy.shouldRun(settings.copy(upstreamCompatibleMode = true), true, true, true))
        assertTrue(!TickPolicy.shouldRun(null, true, true, true))
        assertTrue(TickPolicy.anyFieldVisible(listOf("TYPE_EXT::other::x", "TYPE_EXT::truewind::headwind")))
        assertTrue(!TickPolicy.anyFieldVisible(listOf("TYPE_SPEED_ID", "TYPE_EXT::other::x")))
        assertTrue(TickPolicy.fieldVisible(null, activeStreams = 1), "no ride page seen yet: a running stream counts")
        assertTrue(!TickPolicy.fieldVisible(null, activeStreams = 0))
        assertTrue(!TickPolicy.fieldVisible(listOf("TYPE_SPEED_ID"), activeStreams = 3), "a known page without our fields wins")
    }

    @Test
    fun roundForFetch_exactAndGrid() {
        val p = GeoPoint(37.61234, -122.38765)
        assertEquals(p, TickPolicy.roundForFetch(p, settings.copy(roundLocationTo = de.timklge.karooheadwind.RoundLocationSetting.EXACT)))
        val r = TickPolicy.roundForFetch(p, settings.copy(roundLocationTo = de.timklge.karooheadwind.RoundLocationSetting.KM_1))
        assertTrue(r != p && abs(r.lat - p.lat) < 0.01 && abs(r.lon - p.lon) < 0.02)
    }
}
