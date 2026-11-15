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

package io.github.kf6999.truewind.core.virtualwind

import io.github.kf6999.truewind.core.heading.HeadingConfig
import io.github.kf6999.truewind.core.heading.HeadingEstimator
import io.github.kf6999.truewind.core.heading.HeadingSample
import io.github.kf6999.truewind.core.heading.HeadingSource
import io.github.kf6999.truewind.core.math.normalizeDegrees
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.physics.AirDensity
import io.github.kf6999.truewind.core.physics.Exposure
import io.github.kf6999.truewind.core.physics.FeelsLike
import io.github.kf6999.truewind.core.physics.LogLaw
import io.github.kf6999.truewind.core.physics.RiderParams

/** Settings that change rarely (SPEC 5 settings screen). */
data class RiderWindConfig(
    val exposure: Exposure = Exposure.DEFAULT,
    val riderHeightM: Double = LogLaw.DEFAULT_RIDER_HEIGHT_M,
    /** Rider weight from `UserProfile`. */
    val riderMassKg: Double = 75.0,
    val bikeMassKg: Double = RiderParams.DEFAULT_BIKE_MASS_KG,
    val cda: Double = RiderParams.DEFAULT_CDA,
    val crr: Double = RiderParams.DEFAULT_CRR,
    val drivetrainEfficiency: Double = RiderParams.DEFAULT_DRIVETRAIN_EFFICIENCY,
    /**
     * Off by default: on the real-corpus tuning set the virtual sensor's forward headwind
     * prediction was worse than height scaling alone with the default CdA (1.40 vs 1.32 m/s RMSE)
     * and only about equal with a CdA calibrated on other rides (1.29). See docs/accuracy-report.md.
     */
    val virtualSensorEnabled: Boolean = false,
    /** Drafting cannot be detected; this toggle disables the virtual sensor. */
    val groupRide: Boolean = false,
    /** The virtual sensor drives the output only while it has measured within this window. */
    val virtualSensorStaleMs: Long = 10 * 60_000L,
    val virtualWind: VirtualWindConfig = VirtualWindConfig(),
    val heading: HeadingConfig = HeadingConfig(),
) {
    val riderParams: RiderParams
        get() = RiderParams(
            massKg = riderMassKg + bikeMassKg,
            cda = cda,
            crr = crr,
            drivetrainEfficiency = drivetrainEfficiency,
        )
}

/**
 * Everything the estimator needs for one tick, as plain values so the app and the replay harness
 * can both drive it. Units: m/s, degrees clockwise from north, C, %, hPa, m, W, rpm.
 */
data class RiderWindInputs(
    val timeMs: Long,
    /** Fused, bias-corrected 10 m wind (WP4). */
    val model10m: WindVector? = null,
    val gust10m: Double? = null,
    /** Fusion confidence 0..100. */
    val confidence: Int = 0,
    val gpsCourseDeg: Double? = null,
    val speedMps: Double = 0.0,
    val gpsAccuracyM: Double? = null,
    val karooHeadingDeg: Double? = null,
    /** Route segment bearing; only while navigating and on route. */
    val routeBearingDeg: Double? = null,
    val temperatureC: Double? = null,
    val relativeHumidityPct: Double? = null,
    val modelSurfacePressureHpa: Double? = null,
    val modelElevationM: Double? = null,
    val riderElevationM: Double? = null,
    /** Device barometer, if `SensorManager.TYPE_PRESSURE` works. */
    val sensorPressureHpa: Double? = null,
    /** 3 s average power. */
    val power3sW: Double? = null,
    val powerInstantW: Double? = null,
    val cadenceRpm: Double? = null,
    /** Karoo `ELEVATION_GRADE`, percent. */
    val gradePercent: Double? = null,
)

/** Result of one tick. Speeds m/s; directions degrees. */
data class RiderWindOutput(
    val timeMs: Long,
    /** Wind at rider height: virtual-sensor estimate when active, otherwise ratio * model10m. */
    val rider: WindVector?,
    /** ratio_preset * model10m. */
    val riderPrior: WindVector?,
    val gustRider: Double?,
    /** + into the rider. */
    val headwind: Double?,
    /** + from the right. */
    val crosswind: Double?,
    /** Wind FROM direction relative to heading, 0 = dead ahead, clockwise, [0, 360). */
    val relativeDirectionDeg: Double?,
    val headingDeg: Double?,
    val headingSource: HeadingSource,
    val airDensity: Double,
    val presetRatio: Double,
    /** |rider| / |model10m| when the virtual sensor is active. Diagnostic. */
    val effectiveRatio: Double?,
    val virtualSensorActive: Boolean,
    /** Raw power-balance headwind this tick ("Measured headwind" field); null when invalid. */
    val measuredHeadwind: Double?,
    val gateResult: GateResult?,
    /** Last filter innovation, m/s ("debug" field); persistent non-zero => CdA/Crr settings off. */
    val innovation: Double?,
    val innovationMean: Double?,
    val bias: Double?,
    val biasFrozen: Boolean,
    val headingSpreadDeg: Double?,
    /** Rider air speed = ground speed + headwind. */
    val airSpeedMps: Double?,
    val feelsLikeC: Double?,
)

/**
 * Per-tick façade (SPEC 4.4-4.6): 10 m fused wind -> rider-height prior -> optional virtual wind
 * sensor -> rider wind, head/cross components, gust, air density, feels-like. Not thread-safe; call
 * from the single tick loop.
 */
class RiderWindEstimator(config: RiderWindConfig = RiderWindConfig()) {
    var config: RiderWindConfig = config
        private set

    private var presetRatio = LogLaw.ratio(config.exposure, config.riderHeightM)
    private val headingEstimator = HeadingEstimator(config.heading)
    private var sensor = VirtualWindSensor(config.riderParams, config.virtualWind)
    private var wasGroupRide = config.groupRide

    /** Exposes the sensor for diagnostics and to persist the learned bias per bike. */
    val virtualSensor: VirtualWindSensor get() = sensor

    fun updateConfig(newConfig: RiderWindConfig) {
        val old = config
        config = newConfig
        presetRatio = LogLaw.ratio(newConfig.exposure, newConfig.riderHeightM)
        if (newConfig.virtualWind != old.virtualWind) {
            sensor = VirtualWindSensor(newConfig.riderParams, newConfig.virtualWind)
        } else if (newConfig.riderParams != old.riderParams) {
            sensor.setParams(newConfig.riderParams)
        }
    }

    fun update(input: RiderWindInputs): RiderWindOutput {
        val cfg = config
        val heading = headingEstimator.update(
            HeadingSample(input.timeMs, input.gpsCourseDeg, input.speedMps, input.gpsAccuracyM, input.karooHeadingDeg, input.routeBearingDeg)
        )
        val headingDeg = heading.headingDeg

        val rho = AirDensity.atRider(
            input.temperatureC, input.relativeHumidityPct, input.modelSurfacePressureHpa,
            input.modelElevationM, input.riderElevationM, input.sensorPressureHpa,
        )

        val prior = input.model10m?.let { LogLaw.toRiderHeight(it, presetRatio) }

        val sensorEnabled = cfg.virtualSensorEnabled && !cfg.groupRide
        if (cfg.groupRide && !wasGroupRide) sensor.resetWind()
        wasGroupRide = cfg.groupRide

        var active = false
        if (sensorEnabled) {
            val movingHeading = headingDeg != null && heading.source != HeadingSource.HELD &&
                heading.source != HeadingSource.KAROO_HEADING
            sensor.update(
                timeMs = input.timeMs,
                power3sW = input.power3sW ?: Double.NaN,
                powerInstantW = input.powerInstantW ?: Double.NaN,
                cadenceRpm = input.cadenceRpm ?: Double.NaN,
                speedMps = input.speedMps,
                gradeFraction = input.gradePercent?.div(100.0) ?: Double.NaN,
                headingDeg = if (movingHeading) headingDeg!! else Double.NaN,
                airDensity = rho,
                priorU = prior?.u ?: Double.NaN,
                priorV = prior?.v ?: Double.NaN,
                priorSigma = sensor.priorSigma(input.confidence),
            )
            active = prior != null && sensor.measurementCount > 0 &&
                input.timeMs - sensor.lastMeasurementTimeMs <= cfg.virtualSensorStaleMs
        }

        val rider = if (active) WindVector(sensor.windU, sensor.windV) else prior
        val model10mSpeed = input.model10m?.speed
        val effectiveRatio = if (active && model10mSpeed != null && model10mSpeed > 0.5) rider!!.speed / model10mSpeed else null
        val gust = input.gust10m?.let { it * LogLaw.gustRatio(presetRatio) }

        val headwind = if (rider != null && headingDeg != null) rider.headwind(headingDeg) else null
        val crosswind = if (rider != null && headingDeg != null) rider.crosswind(headingDeg) else null
        val relDir = if (rider != null && headingDeg != null) normalizeDegrees(rider.directionFromDeg - headingDeg) else null
        val airSpeed = input.speedMps + (headwind ?: 0.0)
        val feelsLike = input.temperatureC?.let { FeelsLike.feelsLikeC(it, airSpeed, input.relativeHumidityPct) }

        return RiderWindOutput(
            timeMs = input.timeMs,
            rider = rider,
            riderPrior = prior,
            gustRider = gust,
            headwind = headwind,
            crosswind = crosswind,
            relativeDirectionDeg = relDir,
            headingDeg = headingDeg,
            headingSource = heading.source,
            airDensity = rho,
            presetRatio = presetRatio,
            effectiveRatio = effectiveRatio,
            virtualSensorActive = active,
            measuredHeadwind = if (sensorEnabled) sensor.measuredHeadwind.takeIf { !it.isNaN() } else null,
            gateResult = if (sensorEnabled) sensor.lastGateResult else null,
            innovation = if (sensorEnabled) sensor.lastInnovation.takeIf { !it.isNaN() } else null,
            innovationMean = if (sensorEnabled && sensor.measurementCount > 0) sensor.innovationMean else null,
            bias = if (sensorEnabled) sensor.bias else null,
            biasFrozen = sensor.biasFrozen,
            headingSpreadDeg = if (sensorEnabled) sensor.headingSpreadDeg else null,
            airSpeedMps = if (headwind != null) airSpeed else null,
            feelsLikeC = feelsLike,
        )
    }
}
