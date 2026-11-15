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

import io.github.kf6999.truewind.core.api.Observation
import io.github.kf6999.truewind.core.math.haversineKm
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/** Thresholds for [ObsQc]. Defaults are SPEC 4.3. */
data class ObsQcConfig(
    val maxAgeMs: Long = 30 * 60_000L,
    /** Reports time-stamped further in the future than this (clock skew) are rejected. */
    val maxFutureMs: Long = 5 * 60_000L,
    val maxDistanceKm: Double = 30.0,
    val maxElevationDiffM: Double = 250.0,
    /** m/s, after height normalisation. */
    val maxSpeed: Double = 40.0,
    /** Neighbour-median outlier rule: reject beyond this many robust standard deviations. */
    val outlierSigma: Double = 3.0,
    /** The outlier rule needs at least this many other stations. */
    val minNeighbours: Int = 3,
    /**
     * Floor for the robust spread, m/s. Without it a set of calm neighbours (spread 0) would reject
     * any station reporting a breeze; with 1.5 m/s and 3 sigma the threshold is at least 4.5 m/s.
     */
    val minSpread: Double = 1.5,
    /** Two providers' stations closer than this (and not otherwise matched by id) are the same station. */
    val duplicateDistanceKm: Double = 0.2,
    /** Reports of one station closer in time than this are compared on quality, not age, when de-duplicating. */
    val duplicateTimeToleranceMs: Long = 10 * 60_000L,
    /** Normalise reported sensor heights to 10 m with the log law. */
    val normaliseHeight: Boolean = true,
    /** Roughness length for height normalisation, m (SPEC 4.4 "Mixed" preset). */
    val roughnessM: Double = 0.1,
)

enum class ObsRejectReason {
    /** No wind speed at all. */
    MISSING_WIND,
    STALE,
    FROM_FUTURE,
    TOO_FAR,
    ELEVATION,
    SPEED_IMPLAUSIBLE,

    /** Same station already supplied by another provider (or a newer report). */
    DUPLICATE,

    /** Disagrees with the median of its neighbours by more than [ObsQcConfig.outlierSigma]. */
    OUTLIER,
}

data class RejectedObservation(val observation: Observation, val reason: ObsRejectReason)

data class ObsQcResult(
    /** Surviving observations, normalised to 10 m, nearest to the rider first. */
    val accepted: List<Observation>,
    val rejected: List<RejectedObservation> = emptyList(),
)

/**
 * Observation quality control, SPEC 4.3. Pure function of its inputs; the clock is passed as
 * [nowMs]. Order: per-report checks (wind present, age, distance, elevation, speed) →
 * cross-provider de-duplication → log-law normalisation to 10 m → neighbour-median outlier test
 * (single pass against the pre-outlier set, so one bad station cannot cascade).
 */
object ObsQc {
    /** Source preference when the same station arrives from several providers with similar age. */
    private val SOURCE_PRIORITY = listOf("awc", "synoptic", "nws")

    fun apply(
        observations: List<Observation>,
        rider: GeoPoint,
        riderElevationM: Double?,
        nowMs: Long,
        config: ObsQcConfig = ObsQcConfig(),
    ): ObsQcResult {
        val rejected = mutableListOf<RejectedObservation>()
        fun reject(o: Observation, r: ObsRejectReason) { rejected += RejectedObservation(o, r) }

        val distances = HashMap<Observation, Double>()
        val passed = observations.filter { o ->
            val reason = when {
                o.speed == null || !o.speed.isFinite() -> ObsRejectReason.MISSING_WIND
                o.timeMs > nowMs + config.maxFutureMs -> ObsRejectReason.FROM_FUTURE
                nowMs - o.timeMs > config.maxAgeMs -> ObsRejectReason.STALE
                haversineKm(rider, o.location).also { distances[o] = it } > config.maxDistanceKm -> ObsRejectReason.TOO_FAR
                riderElevationM != null && o.elevationM != null &&
                    abs(o.elevationM - riderElevationM) > config.maxElevationDiffM -> ObsRejectReason.ELEVATION
                else -> null
            }
            if (reason != null) reject(o, reason)
            reason == null
        }

        val (unique, duplicates) = deduplicate(passed, config)
        duplicates.forEach { reject(it, ObsRejectReason.DUPLICATE) }

        val normalised = unique.mapNotNull { o ->
            val n = if (config.normaliseHeight) normaliseTo10m(o, config.roughnessM) else o
            if (n.speed!! > config.maxSpeed) {
                reject(o, ObsRejectReason.SPEED_IMPLAUSIBLE)
                null
            } else {
                n
            }
        }

        val outliers = findOutliers(normalised, config)
        outliers.forEach { reject(it, ObsRejectReason.OUTLIER) }

        val accepted = normalised.filter { it !in outliers }
            .sortedBy { distances[it] ?: haversineKm(rider, it.location) }
        return ObsQcResult(accepted, rejected)
    }

    /**
     * Collapses reports of the same station: same id (case-insensitive) from any provider, or
     * reports from different providers within [ObsQcConfig.duplicateDistanceKm]. The newest wins;
     * reports within [ObsQcConfig.duplicateTimeToleranceMs] of each other are ranked by
     * reliability, then source (AWC, Synoptic, NWS), then a reported sensor height.
     * Returns (kept, dropped).
     */
    fun deduplicate(observations: List<Observation>, config: ObsQcConfig = ObsQcConfig()): Pair<List<Observation>, List<Observation>> {
        val kept = mutableListOf<Observation>()
        val dropped = mutableListOf<Observation>()
        for (o in observations.sortedByDescending { it.timeMs }) {
            val i = kept.indexOfFirst { isSameStation(it, o, config) }
            if (i < 0) {
                kept += o
                continue
            }
            val k = kept[i]
            val similarAge = abs(k.timeMs - o.timeMs) <= config.duplicateTimeToleranceMs
            if (similarAge && quality(o) > quality(k)) {
                kept[i] = o
                dropped += k
            } else {
                dropped += o
            }
        }
        return kept to dropped
    }

    private fun isSameStation(a: Observation, b: Observation, config: ObsQcConfig): Boolean =
        a.stationId.equals(b.stationId, ignoreCase = true) ||
            (a.source != b.source && haversineKm(a.location, b.location) <= config.duplicateDistanceKm)

    /** Lexicographic quality: reliability, then source priority, then a non-default sensor height. */
    private fun quality(o: Observation): Double {
        val sourceRank = SOURCE_PRIORITY.indexOf(o.source).let { if (it < 0) SOURCE_PRIORITY.size else it }
        return o.reliability * 100.0 + (SOURCE_PRIORITY.size - sourceRank) + if (o.sensorHeightM != 10.0) 0.5 else 0.0
    }

    /**
     * Log-law speed ratio U(toM) / U(fromM) over roughness [z0]: `ln(to/z0) / ln(from/z0)`.
     * Heights are clamped to at least 2·z0 so the ratio stays finite and positive.
     */
    fun logLawRatio(fromM: Double, toM: Double, z0: Double): Double {
        val zFrom = max(fromM, 2 * z0)
        val zTo = max(toM, 2 * z0)
        return ln(zTo / z0) / ln(zFrom / z0)
    }

    /** Scales wind, speed and gust from the reported sensor height to 10 m. 10 m reports are returned unchanged. */
    fun normaliseTo10m(o: Observation, z0: Double = 0.1): Observation {
        if (o.sensorHeightM == 10.0 || !o.sensorHeightM.isFinite() || o.sensorHeightM <= 0.0) return o
        val k = logLawRatio(o.sensorHeightM, 10.0, z0)
        return o.copy(
            wind = o.wind?.times(k),
            speed = o.speed?.times(k),
            gust = o.gust?.times(k),
            sensorHeightM = 10.0,
        )
    }

    /** Stations failing the neighbour-median rule (identity set). */
    private fun findOutliers(obs: List<Observation>, config: ObsQcConfig): Set<Observation> {
        if (obs.size < config.minNeighbours + 1) return emptySet()
        val out = HashSet<Observation>()
        for (o in obs) {
            val others = obs.filter { it !== o }
            val w = o.wind
            val vectorNeighbours = others.mapNotNull { it.wind }
            val deviation: Double
            val spread: Double
            if (w != null && vectorNeighbours.size >= config.minNeighbours) {
                val m = WindVector(median(vectorNeighbours.map { it.u }), median(vectorNeighbours.map { it.v }))
                spread = 1.4826 * median(vectorNeighbours.map { (it - m).speed })
                deviation = (w - m).speed
            } else {
                val speeds = others.mapNotNull { it.speed }
                if (speeds.size < config.minNeighbours) continue
                val m = median(speeds)
                spread = 1.4826 * median(speeds.map { abs(it - m) })
                deviation = abs(o.speed!! - m)
            }
            if (deviation > config.outlierSigma * max(spread, config.minSpread)) out += o
        }
        return out
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
