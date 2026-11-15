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

package io.github.kf6999.truewind.core.alerts

import kotlin.math.ceil

enum class RainResolution(val minutes: Int) { MINUTE_1(1), MINUTE_15(15) }

/** Intensity classes (mm/h) used for bar colours: light < 2.5 <= moderate < 7.6 <= heavy (AMS). */
enum class RainIntensity { NONE, LIGHT, MODERATE, HEAVY;

    companion object {
        fun of(mmPerHour: Double, wetThreshold: Double = RainBars.WET_MM_PER_HOUR): RainIntensity = when {
            mmPerHour < wetThreshold -> NONE
            mmPerHour < 2.5 -> LIGHT
            mmPerHour < 7.6 -> MODERATE
            else -> HEAVY
        }
    }
}

/** One bar of the rain chart, relative to "now". The first bar may start before now (offset < 0). */
data class RainBar(
    val startOffsetMin: Int,
    val durationMin: Int,
    val mmPerHour: Double,
    val intensity: RainIntensity,
    val resolution: RainResolution,
)

/** Data for the minute-by-minute rain bar field (2x1 graphical, WP14). */
data class RainBarModel(
    val nowMs: Long,
    val horizonMin: Int,
    /** Ordered, non-overlapping, covering at most [horizonMin] from now. */
    val bars: List<RainBar>,
    /** True when at least part of the chart is 1-minute nowcast data. */
    val hasMinuteData: Boolean,
    /** Minutes until the first wet bar (0 = raining now); null if none within the horizon. */
    val firstRainInMin: Int?,
    val maxMmPerHour: Double,
) {
    /** Resolution label for the field corner: "1 min" or "15 min" (worst resolution in the chart). */
    val resolutionLabel: String get() = if (bars.isNotEmpty() && bars.all { it.resolution == RainResolution.MINUTE_1 }) "1 min" else "15 min"
}

object RainBars {
    /** A bar counts as wet at 0.4 mm/h = 0.1 mm per 15 min (the "Rain in" field threshold, SPEC 5). */
    const val WET_MM_PER_HOUR = 0.4

    /**
     * Builds bars for the next [horizonMin] minutes (60–120): 1-minute steps where [steps1] has
     * data (Tomorrow.io, keyed), 15-minute Open-Meteo steps elsewhere. Steps that end before now
     * or start after the horizon are dropped.
     */
    fun build(nowMs: Long, steps15: List<PrecipStep>, steps1: List<PrecipStep> = emptyList(), horizonMin: Int = 120): RainBarModel {
        require(horizonMin in 1..24 * 60)
        val endMs = nowMs + horizonMin * 60_000L
        val merged = mergePrecip(steps15.filter { it.durationMin > 0 }, steps1.filter { it.durationMin > 0 })
            .filter { it.endMs > nowMs && it.startMs < endMs }
        val bars = merged.map { s ->
            RainBar(
                startOffsetMin = Math.floorDiv(s.startMs - nowMs, 60_000L).toInt(),
                durationMin = s.durationMin,
                mmPerHour = s.mmPerHour,
                intensity = RainIntensity.of(s.mmPerHour),
                resolution = if (s.durationMin <= 1) RainResolution.MINUTE_1 else RainResolution.MINUTE_15,
            )
        }
        val first = merged.firstOrNull { it.mmPerHour >= WET_MM_PER_HOUR }
        return RainBarModel(
            nowMs = nowMs,
            horizonMin = horizonMin,
            bars = bars,
            hasMinuteData = bars.any { it.resolution == RainResolution.MINUTE_1 },
            firstRainInMin = first?.let { ceil((it.startMs - nowMs).coerceAtLeast(0) / 60_000.0).toInt() },
            maxMmPerHour = bars.maxOfOrNull { it.mmPerHour } ?: 0.0,
        )
    }
}
