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

import io.github.kf6999.truewind.core.time.ManualClock

/**
 * A wind pipeline under test. The harness sets [ReplayEnvironment.clock] to the sample time, then
 * calls [tick] once per sample in time order. The pipeline may "fetch" at any tick through the
 * environment's providers; nothing else is reachable, so a run is deterministic and offline.
 *
 * For Metric A the same pipeline is driven by a stationary pseudo-ride at a held-out station
 * (samples without speed or bearing), with that station removed from [ReplayEnvironment.observations].
 */
interface ReplayPipeline {
    fun tick(sample: RideSample): ReplayOutput
}

/** Everything a pipeline may use. One environment per (config, ride or station run). */
class ReplayEnvironment(
    val clock: ManualClock,
    val forecastArchive: ForecastArchive,
    val observationArchive: ObservationArchive,
    /** Hourly archived forecasts (what upstream Headwind consumes). */
    val hourly: ArchiveForecastProvider,
    /** 15-minute archived forecasts (ensemble layers). */
    val minutely15: ArchiveForecastProvider,
    /** Observations as they would have been available at [clock] time, minus held-out stations. */
    val observations: ArchiveObservationProvider,
    val systemMassKg: Double,
    /** Station id held out of the observation feed, when this is a Metric A station run. */
    val heldOutStation: String? = null,
) {
    val fetchRequests: Int get() = hourly.requests + minutely15.requests + observations.requests

    companion object {
        fun create(
            forecastArchive: ForecastArchive,
            observationArchive: ObservationArchive,
            systemMassKg: Double = Ride.DEFAULT_SYSTEM_MASS_KG,
            heldOutStation: String? = null,
            startMs: Long = 0L,
        ): ReplayEnvironment {
            val clock = ManualClock(startMs)
            return ReplayEnvironment(
                clock = clock,
                forecastArchive = forecastArchive,
                observationArchive = observationArchive,
                hourly = ArchiveForecastProvider(forecastArchive, Resolution.HOURLY),
                minutely15 = ArchiveForecastProvider(forecastArchive, Resolution.MINUTELY_15),
                observations = ArchiveObservationProvider(observationArchive, clock, exclude = setOfNotNull(heldOutStation)),
                systemMassKg = systemMassKg,
                heldOutStation = heldOutStation,
            )
        }
    }
}

/** A named, buildable configuration (TESTING 3.5). */
interface ReplayConfig {
    val name: String

    /** Which implementation fills each layer, for the report ("reference", "production", "upstream-emulation"). */
    val implementation: Map<String, String>

    /** Null when the configuration cannot run yet (a seam is not filled); [unavailableReason] says why. */
    fun create(env: ReplayEnvironment): ReplayPipeline?

    val unavailableReason: String? get() = null
}
