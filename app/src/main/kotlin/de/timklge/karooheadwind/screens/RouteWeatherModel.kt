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

package de.timklge.karooheadwind.screens

import de.timklge.karooheadwind.WindUnit
import de.timklge.karooheadwind.util.celciusInUserUnit
import de.timklge.karooheadwind.util.millimetersInUserUnit
import de.timklge.karooheadwind.util.msInWindUnit
import io.github.kf6999.truewind.core.route.BetterStart
import io.github.kf6999.truewind.core.route.RouteWeatherPlan
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** One display row of the route weather screen (pure strings; unit-tested). */
data class RouteWeatherRow(
    val distance: String,
    val eta: String,
    val wind: String,
    val temperature: String,
    val rain: String,
    val score: String,
)

data class RouteWeatherUnits(val wind: WindUnit, val imperialTemp: Boolean, val imperialDistance: Boolean)

object RouteWeatherModel {
    /** Start offsets offered in the stepper: Now, +1 h ... +12 h. */
    val OFFSETS_H = (0..12).toList()

    fun offsetLabel(h: Int) = if (h == 0) "Now" else "+$h h"

    private fun clock(ms: Long, zone: ZoneId) = io.github.kf6999.truewind.core.alerts.AlertText.clock(ms, zone)

    fun rows(plan: RouteWeatherPlan, u: RouteWeatherUnits, zone: ZoneId = ZoneId.systemDefault()): List<RouteWeatherRow> =
        plan.samples.map { s ->
            val km = if (u.imperialDistance) s.distanceKm / 1.609344 else s.distanceKm
            val head = s.headwindMs
            val cross = s.crosswindMs
            val wind = if (head == null || cross == null) "-" else {
                val h = msInWindUnit(head, u.wind)
                val c = msInWindUnit(abs(cross), u.wind)
                String.format(Locale.US, "%s%.0f / x%.0f %s", if (h >= 0) "head " else "tail ", abs(h), c, u.wind.unitDisplay)
            }
            val temp = s.temperatureC?.let { String.format(Locale.US, "%.0f%s", celciusInUserUnit(it, u.imperialTemp), if (u.imperialTemp) "F" else "C") } ?: "-"
            val rain = s.precipitationMmPerHour?.let {
                if (it < 0.1) "dry" else String.format(Locale.US, "%.1f %s/h", millimetersInUserUnit(it, u.imperialDistance), if (u.imperialDistance) "in" else "mm")
            } ?: "-"
            RouteWeatherRow(
                distance = String.format(Locale.US, "%.0f %s", km, if (u.imperialDistance) "mi" else "km"),
                eta = clock(s.etaMs, zone),
                wind = wind,
                temperature = temp,
                rain = rain,
                score = s.score?.toString() ?: "-",
            )
        }

    fun overall(plan: RouteWeatherPlan): String = plan.rideScore?.let { "Ride score $it/100" } ?: "Ride score: no forecast"

    fun betterStartText(b: BetterStart?, zone: ZoneId = ZoneId.systemDefault()): String =
        if (b == null) "No clearly better start in the next 12 h."
        else "Better start: ${clock(b.startMs, zone)} (score ${b.score}, +${b.improvement} vs now)"
}
