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

import de.timklge.karooheadwind.HeadwindSettings
import de.timklge.karooheadwind.weatherprovider.WeatherData
import io.github.kf6999.truewind.core.fusion.FusedSample
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.SourceSummary
import io.github.kf6999.truewind.core.model.Status
import io.github.kf6999.truewind.core.model.WeatherNow
import io.github.kf6999.truewind.core.model.WindState
import io.github.kf6999.truewind.core.math.normalizeDegrees
import io.github.kf6999.truewind.core.virtualwind.RiderWindConfig
import io.github.kf6999.truewind.core.virtualwind.RiderWindInputs
import io.github.kf6999.truewind.core.virtualwind.RiderWindOutput
import io.github.kf6999.truewind.core.virtualwind.VirtualWindConfig
import kotlin.math.roundToInt

/** Latest Karoo sensor values for one tick (null = not available). Units: m/s, degrees, m, W, rpm, %, hPa. */
data class TickInputs(
    val nowMs: Long,
    /** Exact rider position (not rounded; never sent anywhere). */
    val position: GeoPoint? = null,
    val gpsCourseDeg: Double? = null,
    val gpsAccuracyM: Double? = null,
    val speedMps: Double? = null,
    val karooHeadingDeg: Double? = null,
    val routeBearingDeg: Double? = null,
    val elevationM: Double? = null,
    val sensorPressureHpa: Double? = null,
    val powerW: Double? = null,
    val power3sW: Double? = null,
    val cadenceRpm: Double? = null,
    val gradePercent: Double? = null,
    val riderMassKg: Double? = null,
)

/**
 * Pure mapping between the core pipeline and the app (SPEC 3.1): [FusedSample] + Karoo sensors →
 * [RiderWindInputs] → [RiderWindOutput] → [WindSnapshot]. No Android, no clocks, unit-testable.
 */
object TrueWindMapper {

    /** Estimator configuration from the settings and the per-bike persisted bias. */
    fun riderWindConfig(settings: HeadwindSettings, riderMassKg: Double?, bias: Double?, biasVariance: Double?): RiderWindConfig {
        val base = VirtualWindConfig()
        return RiderWindConfig(
            exposure = settings.exposure.exposure,
            riderHeightM = settings.riderHeightM,
            riderMassKg = riderMassKg?.takeIf { it in 30.0..200.0 } ?: 75.0,
            bikeMassKg = settings.bikeWeightKg,
            cda = settings.cda,
            crr = settings.crr,
            virtualSensorEnabled = settings.layerVirtualSensor,
            groupRide = settings.groupRide,
            virtualWind = base.copy(
                initialBias = bias ?: base.initialBias,
                initialBiasVariance = biasVariance ?: base.initialBiasVariance,
            ),
        )
    }

    /** Same mapping as `WindEngineTest`: the sample feeds the per-tick estimator unchanged. */
    fun riderInputs(sample: FusedSample, t: TickInputs, settings: HeadwindSettings): RiderWindInputs {
        val headingFusion = settings.layerHeadingFusion
        return RiderWindInputs(
            timeMs = t.nowMs,
            model10m = sample.model10m,
            gust10m = sample.gust10m,
            confidence = sample.confidence,
            gpsCourseDeg = t.gpsCourseDeg,
            speedMps = t.speedMps ?: 0.0,
            gpsAccuracyM = t.gpsAccuracyM,
            karooHeadingDeg = if (headingFusion) t.karooHeadingDeg else null,
            routeBearingDeg = if (headingFusion) t.routeBearingDeg else null,
            temperatureC = sample.weather?.temperatureC,
            relativeHumidityPct = sample.weather?.relativeHumidityPct,
            modelSurfacePressureHpa = sample.weather?.surfacePressureHpa,
            modelElevationM = sample.weather?.gridElevationM,
            riderElevationM = t.elevationM,
            sensorPressureHpa = t.sensorPressureHpa,
            power3sW = t.power3sW,
            powerInstantW = t.powerW,
            cadenceRpm = t.cadenceRpm,
            gradePercent = t.gradePercent,
        )
    }

    /**
     * Builds the snapshot the fields show. [legacy] is the hourly widget forecast at the rider (for
     * UV index, day/night and precipitation probability, which the nowcast does not carry).
     */
    fun toSnapshot(
        sample: FusedSample,
        out: RiderWindOutput?,
        t: TickInputs,
        settings: HeadwindSettings,
        legacy: WeatherData?,
        lastUpdateMs: Long?,
        nextUpdateMs: Long?,
        maxWindThisRide: Double?,
        rainInMin: Int?,
    ): WindSnapshot {
        val model10m = sample.model10m
        val downscale = settings.layerDownscaling
        // Without downscaling the rider wind is the 10 m wind unless the virtual sensor measured it.
        val rider = when {
            out == null -> null
            !downscale && !out.virtualSensorActive -> model10m
            else -> out.rider
        }
        val heading = out?.headingDeg
        val gustRider = if (!downscale) sample.gust10m else out?.gustRider
        val headwind = if (rider != null && heading != null) rider.headwind(heading) else null
        val crosswind = if (rider != null && heading != null) rider.crosswind(heading) else null
        val relDir = if (rider != null && heading != null) normalizeDegrees(rider.directionFromDeg - heading) else null

        val status = when {
            !settings.welcomeDialogAccepted -> Status.NOT_SET_UP
            t.position == null -> Status.NO_GPS
            else -> sample.status
        }
        val sources = sample.sources.copy(virtualSensorActive = out?.virtualSensorActive == true)
        val weather = weatherNow(sample, legacy, t.nowMs)
        val state = WindState(
            timeMs = t.nowMs,
            model10m = model10m,
            rider = rider,
            gustRider = gustRider,
            headwind = headwind,
            crosswind = crosswind,
            relativeDirectionDeg = relDir,
            confidence = sample.confidence,
            sources = sources,
            ageMs = sample.ageMs,
            airDensity = out?.airDensity ?: WindState.STANDARD_AIR_DENSITY,
            weather = weather,
            status = status,
            riderHeadingDeg = heading,
            position = t.position,
        )
        val extras = WindExtras(
            upstreamMode = false,
            showWindAt10m = settings.showWindAt10m,
            band = if (model10m != null) sample.band else null,
            directionReliable = sample.directionReliable,
            gust10m = sample.gust10m,
            measuredHeadwind = out?.measuredHeadwind,
            feelsLikeC = out?.feelsLikeC ?: weather?.temperatureC,
            rainInMin = rainInMin,
            lastUpdateMs = lastUpdateMs,
            nextUpdateMs = nextUpdateMs,
            maxWindThisRide = maxWindThisRide,
            headingSource = out?.headingSource?.name,
            innovationMean = out?.innovationMean,
            effectiveRatio = out?.effectiveRatio,
            sourceText = sourceText(sources, sample.ageMs),
            windChillC = weather?.temperatureC?.let { tc -> windChill(tc, out?.airSpeedMps) },
            dewPointC = legacy?.dewPoint,
            visibilityM = legacy?.visibility,
            solarRadiationWm2 = legacy?.solarRadiation,
        )
        return WindSnapshot(state, extras)
    }

    /** Wind chill (WP15) at the rider's air speed; the plain temperature above 10 °C, where the index is undefined. */
    fun windChill(tempC: Double, airSpeedMps: Double?): Double =
        if (tempC > 10.0) tempC else io.github.kf6999.truewind.core.physics.FeelsLike.windChillC(tempC, kotlin.math.abs(airSpeedMps ?: 0.0) * 3.6)

    /** Current weather: fused nowcast scalars, falling back to the hourly widget forecast. */
    fun weatherNow(sample: FusedSample, legacy: WeatherData?, nowMs: Long): WeatherNow? {
        val w = sample.weather
        val wind = sample.model10m
        if (wind == null && legacy == null) return null
        return WeatherNow(
            timeSec = nowMs / 1000,
            temperatureC = w?.temperatureC ?: legacy?.temperature ?: return null,
            relativeHumidityPct = (w?.relativeHumidityPct ?: legacy?.relativeHumidity?.toDouble() ?: 0.0).roundToInt(),
            precipitationMm = w?.precipitationMm ?: legacy?.precipitation ?: 0.0,
            precipitationProbabilityPct = legacy?.precipitationProbability,
            cloudCoverPct = w?.cloudCoverPct ?: legacy?.cloudCover ?: 0.0,
            sealevelPressureHpa = w?.sealevelPressureHpa ?: legacy?.sealevelPressure ?: 0.0,
            surfacePressureHpa = w?.surfacePressureHpa ?: legacy?.surfacePressure ?: 0.0,
            windSpeed = wind?.speed ?: legacy?.windSpeed ?: 0.0,
            windDirectionDeg = wind?.directionFromDeg ?: legacy?.windDirection ?: 0.0,
            windGusts = sample.gust10m ?: legacy?.windGusts ?: (wind?.speed ?: 0.0),
            weatherCode = w?.weatherCode ?: legacy?.weatherCode ?: 0,
            isForecast = true,
            isNight = legacy?.isNight ?: false,
            uvi = legacy?.uvi ?: 0.0,
        )
    }

    private val MODEL_SHORT = listOf(
        "hrrr" to "HRRR", "rrfs" to "RRFS", "nbm" to "NBM", "ecmwf" to "ECMWF", "gfs" to "GFS", "icon" to "ICON",
        "arome" to "AROME", "ukmo" to "UKMO", "gem" to "GEM", "jma" to "JMA", "knmi" to "KNMI", "dmi" to "DMI",
        "metno" to "MetNo", "meteoswiss" to "CH", "best_match" to "OM", "tomorrow" to "TMRW",
    )

    /** Short model name for display: `open-meteo:ncep_hrrr_conus` → `HRRR`. */
    fun shortModel(memberId: String): String {
        val id = memberId.lowercase()
        return MODEL_SHORT.firstOrNull { id.contains(it.first) }?.second ?: memberId.substringAfter(':').take(6).uppercase()
    }

    /** "Wind source" text, e.g. `HRRR+NBM · KSQL 6 km · 4 min`. */
    fun sourceText(sources: SourceSummary, ageMs: Long?): String {
        val parts = ArrayList<String>(3)
        val models = sources.models.map { shortModel(it) }.distinct()
        if (models.isNotEmpty()) parts += models.take(3).joinToString("+")
        val station = sources.nearestStationId
        if (station != null) {
            parts += sources.nearestStationDistanceKm?.let { "$station ${it.roundToInt()} km" } ?: station
        }
        val ageMin = sources.observationAgeMin ?: ageMs?.let { it / 60_000.0 }
        if (ageMin != null && parts.isNotEmpty()) parts += "${ageMin.roundToInt()} min"
        if (sources.virtualSensorActive) parts += "PWR"
        return if (parts.isEmpty()) "--" else parts.joinToString(" · ")
    }

    /**
     * SPEC 5 "Rain in": minutes to the first 15-min step in the next 2 h with precipitation
     * >= 0.1 mm; null when none. [precipAt] returns the precipitation of the step at a time, or null
     * when unknown; [RAIN_UNKNOWN][WindExtras.RAIN_UNKNOWN] when nothing is known at all.
     */
    fun rainInMinutes(nowMs: Long, precipAt: (Long) -> Double?, horizonMin: Int = 120, stepMin: Int = 15, thresholdMm: Double = 0.1): Int? {
        var known = false
        var m = 0
        while (m <= horizonMin) {
            val p = precipAt(nowMs + m * 60_000L)
            if (p != null) {
                known = true
                if (p >= thresholdMm) return m
            }
            m += stepMin
        }
        return if (known) null else WindExtras.RAIN_UNKNOWN
    }
}
