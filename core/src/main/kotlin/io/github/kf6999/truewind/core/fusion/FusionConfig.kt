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

package io.github.kf6999.truewind.core.fusion

/**
 * Prior weight of a member before any observation has scored it (SPEC 4.3 "registry priors").
 * Relative values; 1.0 is neutral.
 */
fun interface MemberPriors {
    fun prior(memberId: String): Double

    companion object {
        /** Models with native 15-minute output (no hourly interpolation inside Open-Meteo). */
        private val NATIVE_15_MIN = listOf("hrrr", "rrfs", "icon_d2", "arome")

        /**
         * Default: native 15-min convection-allowing models 1.2 (they resolve short-lead changes),
         * the keyed proprietary nowcast 0.9 (scored at the rider only, so it can never earn weight
         * against stations), everything else 1.0.
         */
        val DEFAULT = MemberPriors { id ->
            val lower = id.lowercase()
            when {
                NATIVE_15_MIN.any { lower.contains(it) } -> 1.2
                lower.startsWith("tomorrow:") -> 0.9
                else -> 1.0
            }
        }

        val EQUAL = MemberPriors { 1.0 }
    }
}

/** Confidence deductions, SPEC 4.3. */
data class ConfidenceConfig(
    val spreadPointsPerMs: Double = 10.0,
    val spreadCap: Double = 40.0,
    val residualPointsPerMs: Double = 10.0,
    val residualCap: Double = 30.0,
    val noObservationPoints: Double = 15.0,
    val agePointsPerMinute: Double = 1.0,
    val ageCap: Double = 30.0,
    /** Below this speed the direction is flagged unreliable (no deduction). */
    val directionMinSpeed: Double = 1.5,
    val highBand: Int = 70,
    val mediumBand: Int = 40,
)

/** Tuning for [MemberWeights] and [Fusion]. Defaults are SPEC 4.3 unless noted. */
data class FusionConfig(
    /** EWMA half-life of the per-member mean-square error. */
    val halfLifeMs: Long = 60 * 60_000L,
    /** ε in `w ∝ 1 / (ε + mse)`, (m/s)². */
    val epsilon: Double = 0.5,
    /** Minimum normalised weight per member. */
    val weightFloor: Double = 0.05,
    /**
     * Prior pseudo-observation: every member starts with this much evidence at
     * `priorMse / prior` (so with no observations the weights equal the priors exactly), and
     * observations take over as they accumulate. Not in SPEC; avoids a single report flipping weights.
     */
    val priorPseudoWeight: Double = 1.0,
    /** Baseline mean-square vector error assumed for a neutral member, (m/s)². */
    val priorMse: Double = 2.0,
    /** Persisted evidence is capped to this weight when loaded, so yesterday informs but does not dominate. */
    val persistedWeightCap: Double = 2.0,
    /** Persisted weights older than this are ignored. */
    val persistedMaxAgeMs: Long = 14L * 24 * 60 * 60_000L,
    /** Speed-only (variable direction) observations score with this fraction of their reliability. */
    val speedOnlyScoreFactor: Double = 0.5,
    /** Residual kernel: `exp(-d / distanceScaleKm) * exp(-age / ageScaleMs)`. */
    val residualDistanceScaleKm: Double = 15.0,
    val residualAgeScaleMs: Long = 30 * 60_000L,
    /** A forecast series counts as "at" a point when within this distance. */
    val seriesMatchKm: Double = 3.0,
    /** A member is used this far before its first / after its last step (held constant). */
    val memberHoldMs: Long = 30 * 60_000L,
    /** Blend grid resolution. */
    val stepMs: Long = 15 * 60_000L,
    /** "No observation within 30 km / 30 min" for confidence. */
    val obsNearKm: Double = 30.0,
    val obsFreshMs: Long = 30 * 60_000L,
    /** Members with a weight below this are left out of [io.github.kf6999.truewind.core.model.SourceSummary.models]. */
    val summaryMinWeight: Double = 0.1,
    val priors: MemberPriors = MemberPriors.DEFAULT,
    val confidence: ConfidenceConfig = ConfidenceConfig(),
)
