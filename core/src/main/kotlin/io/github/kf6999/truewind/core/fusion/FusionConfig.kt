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

        /** Global models, kept as a fallback where a regional model exists. */
        private val GLOBAL = listOf("ecmwf_ifs", "gfs_global", "gfs_seamless")

        /**
         * Default: native 15-min convection-allowing models 1.2, NBM 0.3, global models 0.1, the
         * keyed proprietary nowcast 0.9 (scored at the rider only, so it can never earn weight
         * against stations), everything else (`best_match`, national models) 1.0.
         *
         * NBM and global priors come from the real-corpus tuning set (docs/accuracy-report.md):
         * against held-out ASOS stations HRRR alone scored 1.88 m/s vector RMSE, NBM 2.23, GFS 2.68,
         * ECMWF IFS 2.83 (-1.4 m/s speed bias), and an equal-prior blend 2.14.
         */
        val DEFAULT = MemberPriors { id ->
            val lower = id.lowercase()
            when {
                NATIVE_15_MIN.any { lower.contains(it) } -> 1.2
                lower.contains("nbm") -> 0.3
                GLOBAL.any { lower.contains(it) } -> 0.1
                lower.startsWith("tomorrow:") -> 0.9
                else -> 1.0
            }
        }

        val EQUAL = MemberPriors { 1.0 }

        /** Priors before the real-corpus tuning: native 15-min models 1.2, keyed nowcast 0.9, rest 1.0. */
        val SPEC = MemberPriors { id ->
            val lower = id.lowercase()
            when {
                NATIVE_15_MIN.any { lower.contains(it) } -> 1.2
                lower.startsWith("tomorrow:") -> 0.9
                else -> 1.0
            }
        }
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
    /**
     * Use observation skill scores to re-weight members (SPEC 4.3 "model weighting"). Off by
     * default: on the real-corpus tuning set it was worse at every setting tried (vector RMSE 1.96
     * with it vs 1.92 without; 2.04 with the SPEC pseudo-weight), because a member's error against a
     * few gusty 2-minute station reports is dominated by noise common to all members, which pulls
     * the blend back towards equal weights. Members are still scored (diagnostics) and the
     * registry priors are used. See docs/accuracy-report.md.
     */
    val skillWeighting: Boolean = false,
    /** Baseline mean-square vector error assumed for a neutral member, (m/s)². */
    val priorMse: Double = 2.0,
    /** Persisted evidence is capped to this weight when loaded, so yesterday informs but does not dominate. */
    val persistedWeightCap: Double = 2.0,
    /** Persisted weights older than this are ignored. */
    val persistedMaxAgeMs: Long = 14L * 24 * 60 * 60_000L,
    /** Speed-only (variable direction) observations score with this fraction of their reliability. */
    val speedOnlyScoreFactor: Double = 0.5,
    /**
     * Residual kernel: `exp(-d / distanceScaleKm) * exp(-age / ageScaleMs)`. 8 km (SPEC: 15): station
     * residuals are mostly local (siting, gusts); at 15-30 km they added error on the tuning set.
     */
    val residualDistanceScaleKm: Double = 8.0,
    val residualAgeScaleMs: Long = 30 * 60_000L,
    /** A forecast series counts as "at" a point when within this distance. */
    val seriesMatchKm: Double = 3.0,
    /** A member is used this far before its first / after its last step (held constant). */
    val memberHoldMs: Long = 30 * 60_000L,
    /**
     * Each station's residual is the mean of its residuals over this window (each report against
     * the blend of the cycle that first saw it) instead of the newest 2-minute report alone, which
     * is mostly gust noise. 0 = newest only. Tuned on the real corpus (docs/accuracy-report.md).
     */
    val residualAverageMs: Long = 60 * 60_000L,
    /**
     * Members are interpolated between steps on this grid only, skipping the steps in between
     * (0 = every step). With 60 min the native sub-hourly HRRR steps, which score worse than the
     * hourly ones against station reports (HRRR-only vector RMSE 2.03 -> 1.88 m/s on the tuning
     * set), are not used. Members whose 15-min values are interpolated from hourly are unchanged.
     */
    val memberAnchorStepMs: Long = 60 * 60_000L,
    /**
     * Stations with reliability at or below this (CWOP citizen stations) feed the wind residual only
     * when an ASOS/AWOS/RAWS-class station (reliability >= [referenceReliability]) within
     * [lowReliabilityReferenceKm] reports a speed within [lowReliabilityAgreeMs] of theirs.
     */
    val lowReliabilityMax: Double = 0.4,
    val referenceReliability: Double = 0.95,
    val lowReliabilityReferenceKm: Double = 30.0,
    val lowReliabilityAgreeMs: Double = 1.5,
    /** A 0.0 m/s report from a non-ASOS/AWOS station counts as "no wind data" for the residual. */
    val calmNonAirportIsNoData: Boolean = true,
    /** The residual correction may lower the blended speed by at most this fraction. */
    val maxResidualSpeedReduction: Double = 0.5,
    /** Blend grid resolution. */
    val stepMs: Long = 15 * 60_000L,
    /** "No observation within 30 km / 30 min" for confidence. */
    val obsNearKm: Double = 30.0,
    val obsFreshMs: Long = 30 * 60_000L,
    /** Members with a weight below this are left out of [io.github.kf6999.truewind.core.model.SourceSummary.models]. */
    val summaryMinWeight: Double = 0.1,
    val priors: MemberPriors = MemberPriors.DEFAULT,
    val confidence: ConfidenceConfig = ConfidenceConfig(),
) {
    companion object {
        /**
         * SPEC 4.3 exactly as written (skill weighting, 15 km kernel, newest report only, every
         * forecast step), before the real-corpus tuning. Kept for tests and A/B replays.
         */
        val SPEC_4_3 = FusionConfig(
            skillWeighting = true,
            residualDistanceScaleKm = 15.0,
            residualAverageMs = 0L,
            calmNonAirportIsNoData = false,
            lowReliabilityMax = -1.0,
            maxResidualSpeedReduction = 1.0,
            memberAnchorStepMs = 0L,
            priors = MemberPriors.SPEC,
        )
    }
}
