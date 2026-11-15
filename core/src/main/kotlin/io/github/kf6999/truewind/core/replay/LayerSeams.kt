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

/*
 * ADAPTER SEAMS — where the accuracy layers built in other work packages plug into the harness.
 *
 * Each layer of TESTING 3.5 is one small interface below. [LayerSeams] holds a factory per layer.
 * Until the production implementation lands, a factory points at a *reference* stand-in in
 * `ReferenceLayers.kt` (deliberately simple, written from the SPEC text) so every configuration
 * produces numbers in CI. The report labels which implementation ran.
 *
 * To plug in a production layer, add an adapter class next to this file and assign the factory,
 * e.g. in `LayerSeams.installProduction()`:
 *
 *   SEAM(WP2+WP4+WP8) ensemble / obs : wrap the Open-Meteo ensemble ForecastProvider usage, the
 *       fusion engine (core.fusion) and the FetchScheduler policy as a [TenMeterWindLayer]. Feed it
 *       env.minutely15 (implements core.api.ForecastProvider) and env.observations (implements
 *       core.api.ObservationProvider) instead of the HTTP-backed providers; drive time with env.clock.
 *   SEAM(WP5) height : core.physics log law + exposure preset as a [HeightLayer].
 *   SEAM(WP6) heading : core.heading.HeadingEstimator as a [HeadingLayer].
 *   SEAM(WP7) virtual : the RiderWindEstimator tick facade (core.virtualwind) as a [VirtualWindLayer].
 *       No reference implementation exists: `+virtual` reports "unavailable" until this is filled.
 */

/** A 10 m wind estimate at a point (fusion output). */
data class TenMeterEstimate(
    val wind: WindVector,
    val confidence: Int? = null,
    val members: List<String> = emptyList(),
)

/** SPEC 4.1–4.3: decides when to fetch, blends members, applies observation correction. */
interface TenMeterWindLayer {
    /** Called every tick with the current (unrounded) position. */
    fun estimate(point: GeoPoint, nowMs: Long): TenMeterEstimate?
}

/** SPEC 4.4: 10 m wind to rider height. */
interface HeightLayer {
    fun toRider(model10m: WindVector, sample: RideSample): WindVector
}

/** SPEC 4.5: displayed heading from GPS course, HEADING, speed. */
interface HeadingLayer {
    fun update(sample: RideSample): Double?
}

/** SPEC 4.6: virtual wind sensor. Returns the filtered rider-height wind. */
interface VirtualWindLayer {
    fun update(sample: RideSample, riderPrior: WindVector, headingDeg: Double?, confidence: Int?): WindVector
}

/** Factories for each seam. Reassign to plug in production code; [implementations] feeds the report. */
object LayerSeams {
    var ensemble: (ReplayEnvironment) -> TenMeterWindLayer = { ReferenceEnsembleLayer(it, useObservations = false) }
    var obs: (ReplayEnvironment) -> TenMeterWindLayer = { ReferenceEnsembleLayer(it, useObservations = true) }
    var height: (ReplayEnvironment) -> HeightLayer = { ReferenceHeightLayer() }
    var heading: (ReplayEnvironment) -> HeadingLayer = { ReferenceHeadingLayer() }
    /** Null until WP7 provides the filter. */
    var virtual: ((ReplayEnvironment) -> VirtualWindLayer)? = null

    val implementations: MutableMap<String, String> = mutableMapOf(
        "ensemble" to "reference",
        "obs" to "reference",
        "height" to "reference",
        "heading" to "reference",
        "virtual" to "missing",
    )

    /** Restores the reference stand-ins (tests). */
    fun reset() {
        ensemble = { ReferenceEnsembleLayer(it, useObservations = false) }
        obs = { ReferenceEnsembleLayer(it, useObservations = true) }
        height = { ReferenceHeightLayer() }
        heading = { ReferenceHeadingLayer() }
        virtual = null
        implementations.putAll(mapOf("ensemble" to "reference", "obs" to "reference", "height" to "reference", "heading" to "reference", "virtual" to "missing"))
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
            put(if (obs) "obs" else "ensemble", LayerSeams.implementations[if (obs) "obs" else "ensemble"]!!)
            if (height) put("height", LayerSeams.implementations["height"]!!)
            if (heading) put("heading", LayerSeams.implementations["heading"]!!)
            if (virtual) put("virtual", LayerSeams.implementations["virtual"]!!)
        }

    override val unavailableReason: String?
        get() = if (virtual && LayerSeams.virtual == null) "virtual wind layer not implemented yet (SEAM WP7)" else null

    override fun create(env: ReplayEnvironment): ReplayPipeline? {
        if (unavailableReason != null) return null
        val windLayer = if (obs) LayerSeams.obs(env) else LayerSeams.ensemble(env)
        val heightLayer = if (height) LayerSeams.height(env) else null
        val headingLayer = if (heading) LayerSeams.heading(env) else null
        val virtualLayer = if (virtual) LayerSeams.virtual!!(env) else null
        return LayeredPipeline(env, windLayer, heightLayer, headingLayer, virtualLayer)
    }
}

class LayeredPipeline(
    private val env: ReplayEnvironment,
    private val wind: TenMeterWindLayer,
    private val height: HeightLayer?,
    private val heading: HeadingLayer?,
    private val virtual: VirtualWindLayer?,
) : ReplayPipeline {
    override fun tick(sample: RideSample): ReplayOutput {
        val now = env.clock.nowMs()
        val est = wind.estimate(sample.point, now)
        val headingDeg = if (heading != null) heading.update(sample) else sample.gpsBearingDeg
        var rider = est?.wind?.let { w -> height?.toRider(w, sample) ?: w }
        if (virtual != null && rider != null) rider = virtual.update(sample, rider, headingDeg, est?.confidence)
        return ReplayOutput(
            timeMs = now,
            model10m = est?.wind,
            rider = rider,
            headingDeg = headingDeg,
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
