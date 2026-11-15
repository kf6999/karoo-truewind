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

package io.github.kf6999.truewind.core.replay

import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.virtualwind.RiderWindConfig
import io.github.kf6999.truewind.core.virtualwind.RiderWindEstimator
import io.github.kf6999.truewind.core.virtualwind.RiderWindInputs

/*
 * ADAPTER SEAMS — where the accuracy layers plug into the harness (TESTING 3.5).
 *
 *  - 10 m wind (SPEC 4.1–4.3), [TenMeterWindLayer]:
 *      PRODUCTION — [WindEngineLayer] runs core.engine.WindEngine (FetchPlanner, ObsQc,
 *      MemberWeights, Fusion) against `env.minutely15` / `env.observations` on `env.clock`.
 *      [ReferenceEnsembleLayer] (the SPEC-text stand-in used before the engine landed) is kept as
 *      a cross-check; assign it to [LayerSeams.ensemble] / [LayerSeams.obs] to compare.
 *  - Rider height, heading, virtual sensor (SPEC 4.4–4.6), [RiderLayer]: PRODUCTION —
 *      [RiderWindEstimatorLayer] wraps core.virtualwind.RiderWindEstimator (LogLaw, AirDensity,
 *      HeadingEstimator, VirtualWindSensor).
 */

/** A 10 m wind estimate at a point (fusion output). */
data class TenMeterEstimate(
    val wind: WindVector,
    val confidence: Int? = null,
    val members: List<String> = emptyList(),
    val gust: Double? = null,
)

/** SPEC 4.1–4.3: decides when to fetch, blends members, applies observation correction. */
interface TenMeterWindLayer {
    /** Called every tick with the current (unrounded) position. */
    fun estimate(point: GeoPoint, nowMs: Long): TenMeterEstimate?
}

/** What the rider-side layers produce for one tick. */
data class RiderResult(val rider: WindVector?, val headingDeg: Double?)

/** SPEC 4.4–4.6: 10 m wind and sensors to rider-height wind and displayed heading. */
interface RiderLayer {
    fun update(sample: RideSample, estimate: TenMeterEstimate?): RiderResult
}

/**
 * Production rider layers through the `RiderWindEstimator` tick façade. Layers not enabled behave
 * as upstream (10 m wind as rider wind, raw GPS course), so each configuration adds exactly one.
 */
class RiderWindEstimatorLayer(
    env: ReplayEnvironment,
    private val height: Boolean,
    private val heading: Boolean,
    virtual: Boolean,
) : RiderLayer {
    private val estimator = RiderWindEstimator(
        RiderWindConfig(
            riderMassKg = env.systemMassKg - RiderWindConfig().bikeMassKg,
            virtualSensorEnabled = virtual,
        )
    )
    private val recent = ArrayDeque<RideSample>()

    override fun update(sample: RideSample, estimate: TenMeterEstimate?): RiderResult {
        recent.addLast(sample)
        while (recent.size > 1 && sample.timeMs - recent.first().timeMs > 5_000) recent.removeFirst()
        val power3s = recent.filter { sample.timeMs - it.timeMs < 3_000 }.mapNotNull { it.powerW }.takeIf { it.isNotEmpty() }?.average()
        val out = estimator.update(
            RiderWindInputs(
                timeMs = sample.timeMs,
                model10m = estimate?.wind,
                gust10m = estimate?.gust,
                confidence = estimate?.confidence ?: 0,
                gpsCourseDeg = sample.gpsBearingDeg,
                speedMps = sample.speedMps ?: 0.0,
                gpsAccuracyM = sample.gpsAccuracyM,
                karooHeadingDeg = sample.headingDeg,
                temperatureC = sample.temperatureC,
                riderElevationM = sample.altitudeM,
                power3sW = power3s,
                powerInstantW = sample.powerW,
                cadenceRpm = sample.cadenceRpm,
                gradePercent = sample.gradePct ?: grade(),
            )
        )
        return RiderResult(
            rider = if (height) out.rider else estimate?.wind,
            headingDeg = if (heading) out.headingDeg else sample.gpsBearingDeg,
        )
    }

    /** Karoo-like ELEVATION_GRADE from the last ~5 s of altitude and distance, percent. */
    private fun grade(): Double? {
        val a = recent.first(); val b = recent.last()
        val za = a.altitudeM ?: return null; val zb = b.altitudeM ?: return null
        val d = Geo.distanceKm(a.point, b.point) * 1000
        return if (d < 10) 0.0 else ((zb - za) / d * 100).coerceIn(-30.0, 30.0)
    }
}

/** Factories for each seam. Reassign to plug in production code; [implementations] feeds the report. */
object LayerSeams {
    var ensemble: (ReplayEnvironment) -> TenMeterWindLayer = { WindEngineLayer(it, useObservations = false) }
    var obs: (ReplayEnvironment) -> TenMeterWindLayer = { WindEngineLayer(it, useObservations = true) }
    var rider: (ReplayEnvironment, Boolean, Boolean, Boolean) -> RiderLayer = { env, h, hd, v -> RiderWindEstimatorLayer(env, h, hd, v) }

    val implementations: MutableMap<String, String> = defaults()

    private fun defaults() = mutableMapOf(
        "ensemble" to "production WindEngine (no observations)",
        "obs" to "production WindEngine",
        "height" to "production RiderWindEstimator",
        "heading" to "production RiderWindEstimator",
        "virtual" to "production RiderWindEstimator",
    )

    /** Restores the defaults (tests). */
    fun reset() {
        ensemble = { WindEngineLayer(it, useObservations = false) }
        obs = { WindEngineLayer(it, useObservations = true) }
        rider = { env, h, hd, v -> RiderWindEstimatorLayer(env, h, hd, v) }
        implementations.clear(); implementations.putAll(defaults())
    }
}

/**
 * Cumulative configuration: each one adds a layer to the previous (TESTING 3.5). Layers not
 * enabled behave as upstream: 10 m wind as rider wind, raw GPS course.
 */
class LayeredConfig(
    override val name: String,
    private val obs: Boolean,
    private val height: Boolean,
    private val heading: Boolean,
    private val virtual: Boolean,
) : ReplayConfig {
    override val implementation: Map<String, String>
        get() = buildMap {
            val k = if (obs) "obs" else "ensemble"
            put(k, LayerSeams.implementations[k]!!)
            if (height) put("height", LayerSeams.implementations["height"]!!)
            if (heading) put("heading", LayerSeams.implementations["heading"]!!)
            if (virtual) put("virtual", LayerSeams.implementations["virtual"]!!)
        }

    override fun create(env: ReplayEnvironment): ReplayPipeline {
        val windLayer = if (obs) LayerSeams.obs(env) else LayerSeams.ensemble(env)
        val riderLayer = if (height || heading || virtual) LayerSeams.rider(env, height, heading, virtual) else null
        return LayeredPipeline(env, windLayer, riderLayer)
    }
}

class LayeredPipeline(
    private val env: ReplayEnvironment,
    private val wind: TenMeterWindLayer,
    private val riderLayer: RiderLayer?,
) : ReplayPipeline {
    override fun tick(sample: RideSample): ReplayOutput {
        val now = env.clock.nowMs()
        val est = wind.estimate(sample.point, now)
        val r = riderLayer?.update(sample, est) ?: RiderResult(est?.wind, sample.gpsBearingDeg)
        return ReplayOutput(
            timeMs = now,
            model10m = est?.wind,
            rider = r.rider,
            headingDeg = r.headingDeg,
            confidence = est?.confidence,
            members = est?.members.orEmpty(),
        )
    }
}

object ReplayConfigs {
    val all: List<ReplayConfig> = listOf(
        UpstreamConfig,
        LayeredConfig("+ensemble", obs = false, height = false, heading = false, virtual = false),
        LayeredConfig("+obs", obs = true, height = false, heading = false, virtual = false),
        LayeredConfig("+height", obs = true, height = true, heading = false, virtual = false),
        LayeredConfig("+heading", obs = true, height = true, heading = true, virtual = false),
        LayeredConfig("+virtual", obs = true, height = true, heading = true, virtual = true),
    )

    fun byName(names: List<String>): List<ReplayConfig> = names.map { n ->
        all.firstOrNull { it.name == n } ?: throw IllegalArgumentException("unknown config '$n'; known: ${all.joinToString { it.name }}")
    }
}
