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

import io.github.kf6999.truewind.core.api.ForecastStep
import io.github.kf6999.truewind.core.math.circularMeanDegrees
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.route.RoutePolyline
import io.github.kf6999.truewind.core.route.SpeedModel
import io.github.kf6999.truewind.core.route.travelTimeMs
import java.util.Locale
import kotlin.math.roundToInt

enum class WindTrend { RISING, STEADY, EASING }

enum class RouteWindCharacter { MOSTLY_HEADWIND, MOSTLY_TAILWIND, MOSTLY_CROSSWIND, MIXED }

/** Structured start-of-ride summary; [text] is what the pop-up shows. */
data class RideSummary(
    val windFromDeg: Double?,
    val windStartMs: Double?,
    val windEndMs: Double?,
    val windMaxMs: Double?,
    val gustMaxMs: Double?,
    val trend: WindTrend?,
    /** Only with a route. */
    val routeWind: RouteWindCharacter?,
    val meanHeadwindMs: Double?,
    val tempMinC: Double?,
    val tempMaxC: Double?,
    /** Max precipitation probability in the window, %, when the provider gives one. */
    val rainChancePct: Double?,
    val rainTotalMm: Double,
    /** First step with rain (>= 0.1 mm), or null. */
    val rainFromMs: Long?,
    val title: String,
    val text: String,
)

/**
 * Start-of-ride 6-hour forecast summary (WP14), shown once on `RideState.Recording` after Idle.
 *
 * Without a route, [series] (forecast at the start point; hourly or 15-min steps) is summarised.
 * With a route, a speed model and a [forecast] lookup, the weather is sampled where the rider is
 * expected to be every [routeSampleMin] minutes (holding the finish point after arrival), and wind is
 * classified relative to the local route bearing. The lookup's `precipitationMm` should be the
 * amount per [routeSampleMin] (15-min steps), since the rain total sums the samples.
 */
object RideSummaryBuilder {
    fun build(
        nowMs: Long,
        series: List<ForecastStep>,
        units: AlertUnits = AlertUnits(),
        windowH: Int = 6,
        route: RoutePolyline? = null,
        speed: SpeedModel? = null,
        forecast: ((GeoPoint, Long) -> ForecastStep?)? = null,
        precipProbabilityPct: Map<Long, Double> = emptyMap(),
        routeSampleMin: Int = 15,
    ): RideSummary {
        val endMs = nowMs + windowH * 3_600_000L
        data class Sample(val step: ForecastStep, val bearing: Double?)

        val samples: List<Sample> = if (route != null && speed != null && forecast != null && route.lengthKm > 0) {
            // Rider position over time: invert travel time by stepping along the route.
            val out = mutableListOf<Sample>()
            var km = 0.0
            var t = nowMs
            while (t <= endMs) {
                val p = route.pointAt(km)
                forecast(p, t)?.let { out += Sample(it, if (km < route.lengthKm) route.bearingAt(km) else null) }
                val next = t + routeSampleMin * 60_000L
                // advance km by speed for routeSampleMin minutes
                var dt = 0L
                while (km < route.lengthKm && dt < routeSampleMin * 60_000L) {
                    val step = minOf(0.25, route.lengthKm - km)
                    dt += travelTimeMs(km, km + step, speed)
                    km += step
                }
                t = next
            }
            out
        } else {
            series.filter { it.timeMs in nowMs..endMs }.map { Sample(it, null) }
        }

        val winds = samples.mapNotNull { it.step.wind10m }
        val windFrom = if (winds.isEmpty()) null else circularMeanDegrees(winds.map { it.directionFromDeg }, winds.map { it.speed })
        val startW = winds.firstOrNull()?.speed
        val endW = winds.lastOrNull()?.speed
        val trend = if (startW == null || endW == null) null else when {
            endW - startW >= 1.5 -> WindTrend.RISING
            startW - endW >= 1.5 -> WindTrend.EASING
            else -> WindTrend.STEADY
        }
        val heads = samples.mapNotNull { s -> s.bearing?.let { b -> s.step.wind10m?.headwind(b) } }
        val crosses = samples.mapNotNull { s -> s.bearing?.let { b -> s.step.wind10m?.crosswind(b) } }
        val meanHead = heads.takeIf { it.isNotEmpty() }?.average()
        val routeWind = if (heads.isEmpty()) null else {
            val meanAbsCross = crosses.map { kotlin.math.abs(it) }.average()
            val meanAbsHead = heads.map { kotlin.math.abs(it) }.average()
            when {
                meanHead!! >= 1.0 -> RouteWindCharacter.MOSTLY_HEADWIND
                meanHead <= -1.0 -> RouteWindCharacter.MOSTLY_TAILWIND
                meanAbsCross > meanAbsHead -> RouteWindCharacter.MOSTLY_CROSSWIND
                else -> RouteWindCharacter.MIXED
            }
        }
        val temps = samples.mapNotNull { it.step.temperatureC }
        val precip = samples.mapNotNull { s -> s.step.precipitationMm?.let { s.step.timeMs to it } }
        val rainFrom = precip.firstOrNull { it.second >= 0.1 }?.first
        // With route sampling each sample stands for routeSampleMin; otherwise steps are summed as given.
        val rainTotal = precip.sumOf { it.second }
        val chance = precipProbabilityPct.filterKeys { it in nowMs..endMs }.values.maxOrNull()
        val gustMax = samples.mapNotNull { it.step.gust10m }.maxOrNull()

        val parts = mutableListOf<String>()
        if (windFrom != null && startW != null && endW != null) {
            val trendTxt = when (trend) {
                WindTrend.RISING -> "rising"
                WindTrend.EASING -> "easing"
                else -> "steady"
            }
            var s = "Wind ${AlertText.compass(windFrom)} ${speedRange(units, startW, endW)}, $trendTxt"
            if (gustMax != null && gustMax >= (winds.maxOf { it.speed } + 3)) s += ", gusts ${units.speed(gustMax)}"
            routeWind?.let {
                s += when (it) {
                    RouteWindCharacter.MOSTLY_HEADWIND -> "; mostly headwind on route"
                    RouteWindCharacter.MOSTLY_TAILWIND -> "; mostly tailwind on route"
                    RouteWindCharacter.MOSTLY_CROSSWIND -> "; mostly crosswind on route"
                    RouteWindCharacter.MIXED -> "; mixed head/tail on route"
                }
            }
            parts += s
        }
        if (temps.isNotEmpty()) {
            val lo = temps.min()
            val hi = temps.max()
            parts += "Temp ${temp(units, lo)}–${temp(units, hi)}"
        }
        parts += when {
            rainFrom != null -> {
                val c = chance?.let { "${it.roundToInt()}% " } ?: ""
                "Rain ${c}from ${AlertText.hhmm(rainFrom)} (${String.format(Locale.US, "%.1f", rainTotal)} mm)"
            }
            chance != null && chance >= 20 -> "Rain chance ${chance.roundToInt()}%"
            else -> "No rain expected"
        }
        return RideSummary(
            windFromDeg = windFrom,
            windStartMs = startW,
            windEndMs = endW,
            windMaxMs = winds.maxOfOrNull { it.speed },
            gustMaxMs = gustMax,
            trend = trend,
            routeWind = routeWind,
            meanHeadwindMs = meanHead,
            tempMinC = temps.minOrNull(),
            tempMaxC = temps.maxOrNull(),
            rainChancePct = chance,
            rainTotalMm = rainTotal,
            rainFromMs = rainFrom,
            title = "Next ${windowH} h" + if (route != null && routeWind != null) " on route" else "",
            text = parts.joinToString(". ") + ".",
        )
    }

    /** The summary as a one-off alert. */
    fun toAlert(summary: RideSummary, nowMs: Long): RideAlert =
        RideAlert("ride-summary-$nowMs", summary.title, summary.text, AlertPriority.LOW, AlertRule.RIDE_SUMMARY)

    private fun speedRange(units: AlertUnits, a: Double, b: Double): String {
        val sa = units.speed(a)
        val sb = units.speed(b)
        return if (sa == sb) sa else "${sa.substringBefore(' ')}→$sb"
    }

    private fun temp(units: AlertUnits, c: Double): String =
        if (units.metricTemperature) "${c.roundToInt()}°C" else "${(c * 9 / 5 + 32).roundToInt()}°F"
}
