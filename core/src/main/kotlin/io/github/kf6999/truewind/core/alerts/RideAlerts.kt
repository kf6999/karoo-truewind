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

import io.github.kf6999.truewind.core.alerts.nws.AlertSeverity
import io.github.kf6999.truewind.core.alerts.nws.NwsAlert
import io.github.kf6999.truewind.core.alerts.storm.StormEstimate
import io.github.kf6999.truewind.core.api.ForecastStep
import io.github.kf6999.truewind.core.math.lerp
import io.github.kf6999.truewind.core.math.normalizeSignedDegrees
import io.github.kf6999.truewind.core.math.timeFactor
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.providers.wildfire.FireProximity
import io.github.kf6999.truewind.core.time.Clock
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class AlertPriority { LOW, NORMAL, HIGH, CRITICAL }

enum class AlertRule { RAIN_START, RAIN_END, GUST, WIND_SHIFT, NWS, STORM, WILDFIRE, AQI, RIDE_SUMMARY }

/** An in-ride alert, mapped by the app to a Karoo `InRideAlert`. Each [id] is emitted once. */
data class RideAlert(
    val id: String,
    val title: String,
    val detail: String,
    val priority: AlertPriority,
    val rule: AlertRule,
)

/** Display units for alert text. */
data class AlertUnits(val metricSpeed: Boolean = true, val metricDistance: Boolean = true, val metricTemperature: Boolean = true) {
    fun speed(ms: Double): String =
        if (metricSpeed) "${(ms * 3.6).roundToInt()} km/h" else "${(ms * 2.236936).roundToInt()} mph"

    fun distance(km: Double): String {
        val v = if (metricDistance) km else km / 1.609344
        val u = if (metricDistance) "km" else "mi"
        return if (v < 10) String.format(Locale.US, "%.1f %s", v, u) else "${v.roundToInt()} $u"
    }
}

/**
 * Precipitation over one interval. 15-minute steps come from Open-Meteo `minutely_15`; 1-minute
 * steps from a keyed nowcast (Tomorrow.io) when available.
 */
data class PrecipStep(val startMs: Long, val durationMin: Int, val mm: Double) {
    val endMs: Long get() = startMs + durationMin * 60_000L
    val mmPerHour: Double get() = if (durationMin <= 0) 0.0 else mm * 60.0 / durationMin

    companion object {
        /** From forecast steps (each step's precipitation is the amount over the preceding/following interval of [stepMin]). */
        fun fromForecast(steps: List<ForecastStep>, stepMin: Int = 15): List<PrecipStep> =
            steps.mapNotNull { s -> s.precipitationMm?.let { PrecipStep(s.timeMs, stepMin, it) } }
    }
}

/**
 * Merges 1-minute steps (preferred where they exist) with 15-minute steps, ordered by time and
 * without overlap: a 15-min step is kept only where no 1-min data covers its start.
 */
fun mergePrecip(steps15: List<PrecipStep>, steps1: List<PrecipStep>): List<PrecipStep> {
    if (steps1.isEmpty()) return steps15.sortedBy { it.startMs }
    val s1 = steps1.sortedBy { it.startMs }
    val coveredUntil = s1.last().endMs
    val coveredFrom = s1.first().startMs
    return (s1 + steps15.filter { it.endMs <= coveredFrom || it.startMs >= coveredUntil }).sortedBy { it.startMs }
}

data class AlertConfig(
    /** A step counts as rain at or above this rate: 0.8 mm/h = 0.2 mm per 15 min (SPEC 9.1). */
    val rainRateMmH: Double = 0.8,
    /** "Rain in N min" fires when rain is expected within this many minutes. */
    val rainStartLookaheadMin: Int = 45,
    /** "Rain ends in N min" fires when the end is expected within this many minutes. */
    val rainEndLookaheadMin: Int = 60,
    /** Rain counts as ended only if it then stays dry for this long. */
    val dryConfirmMin: Int = 30,
    /** A rain spell / dry spell must last this long before the opposite alert re-arms. */
    val rainRearmMin: Int = 15,
    val gustThresholdMs: Double = 12.0,
    val gustHysteresisMs: Double = 2.0,
    val gustRearmMin: Int = 15,
    val windShiftDeg: Double = 60.0,
    val windShiftWindowMin: Int = 30,
    /** Below this speed (m/s) direction is meaningless and shifts are ignored. */
    val windShiftMinSpeedMs: Double = 3.0,
    val windShiftCooldownMin: Int = 60,
    val stormKm: Double = 20.0,
    val stormHysteresisKm: Double = 10.0,
    val stormRearmMin: Int = 20,
    val fireKm: Double = 15.0,
    val fireHysteresisKm: Double = 5.0,
    /** Lowest US AQI category index that alerts: 2 = Unhealthy for Sensitive Groups (101+). */
    val aqiMinCategory: Int = 2,
    /** AQI must fall this far below a category's lower bound before that category can alert again. */
    val aqiHysteresis: Int = 10,
    /** Same-event NWS updates within this window are not re-announced. */
    val nwsUpdateSuppressMin: Int = 60,
)

/** Everything the rules look at in one evaluation. Missing inputs disable their rule for this call. */
data class AlertInputs(
    val precip: List<PrecipStep> = emptyList(),
    /** Current gust, m/s (the app chooses rider-height or 10 m). */
    val currentGustMs: Double? = null,
    /** Fused 10 m forecast series at the rider, used for the wind-shift rule. */
    val windSeries: List<ForecastStep> = emptyList(),
    val nwsAlerts: List<NwsAlert> = emptyList(),
    val storm: StormEstimate? = null,
    val fires: List<FireProximity> = emptyList(),
    val usAqi: Int? = null,
)

/**
 * Pure, clock-injected in-ride alert rules (WP14). Call [evaluate] whenever inputs change or on
 * a slow tick (once a minute is enough); it returns only alerts that should be shown now. Every
 * rule fires once and re-arms only after its condition has clearly cleared (hysteresis), so
 * repeated calls with the same data never repeat an alert.
 *
 * Create one engine per ride (or call [reset] at ride start). Not thread-safe.
 */
class AlertEngine(
    private val clock: Clock,
    val config: AlertConfig = AlertConfig(),
    val units: AlertUnits = AlertUnits(),
) {
    private var rainStartArmed = true
    private var rainEndArmed = true
    private var wetSince: Long? = null
    private var drySince: Long? = null
    private var noRainForecastSince: Long? = null

    private var gustArmed = true
    private var gustLowSince: Long? = null

    private var shiftArmed = true
    private var noShiftSince: Long? = null
    private var lastShiftMs: Long? = null

    private val nwsFired = HashSet<String>()
    private val nwsEventLastMs = HashMap<String, Long>()

    private var stormArmed = true
    private var stormClearSince: Long? = null

    private val fireArmed = HashMap<String, Boolean>()
    private var fireAlertCount = 0

    private var aqiAlertedCategory: Int = -1

    fun reset() {
        rainStartArmed = true; rainEndArmed = true; wetSince = null; drySince = null; noRainForecastSince = null
        gustArmed = true; gustLowSince = null
        shiftArmed = true; noShiftSince = null; lastShiftMs = null
        nwsFired.clear(); nwsEventLastMs.clear()
        stormArmed = true; stormClearSince = null
        fireArmed.clear()
        aqiAlertedCategory = -1
    }

    fun evaluate(inputs: AlertInputs): List<RideAlert> {
        val now = clock.nowMs()
        val out = mutableListOf<RideAlert>()
        rain(now, inputs.precip, out)
        gust(now, inputs.currentGustMs, out)
        windShift(now, inputs.windSeries, out)
        nws(now, inputs.nwsAlerts, out)
        storm(now, inputs.storm, out)
        fires(inputs.fires, out)
        aqi(inputs.usAqi, out)
        return out
    }

    // --- rain -------------------------------------------------------------------------------

    private fun isWet(s: PrecipStep) = s.mmPerHour >= config.rainRateMmH

    private fun rain(now: Long, series: List<PrecipStep>, out: MutableList<RideAlert>) {
        if (series.isEmpty()) return
        val steps = series.sortedBy { it.startMs }
        val current = steps.lastOrNull { it.startMs <= now && now < it.endMs } ?: return
        val rainingNow = isWet(current)

        if (rainingNow) {
            drySince = null
            if (wetSince == null) wetSince = now
            // A confirmed wet spell re-arms "rain in N min" for the next dry spell.
            if (now - wetSince!! >= config.rainRearmMin * 60_000L) rainStartArmed = true
        } else {
            wetSince = null
            if (drySince == null) drySince = now
            if (now - drySince!! >= config.rainRearmMin * 60_000L) rainEndArmed = true
        }

        if (!rainingNow) {
            val next = steps.firstOrNull { it.startMs >= current.endMs && isWet(it) }
            val inMin = next?.let { minutesUntil(now, it.startMs) }
            if (inMin == null || inMin > config.rainStartLookaheadMin + 15) {
                if (noRainForecastSince == null) noRainForecastSince = now
                if (now - noRainForecastSince!! >= 30 * 60_000L) rainStartArmed = true
            } else {
                noRainForecastSince = null
            }
            if (rainStartArmed && inMin != null && inMin <= config.rainStartLookaheadMin) {
                rainStartArmed = false
                val peak = steps.filter { it.startMs >= next.startMs && it.startMs < next.startMs + 3600_000L }.maxOf { it.mmPerHour }
                out += RideAlert(
                    id = "rain-start-${next.startMs}",
                    title = "Rain in $inMin min",
                    detail = "${intensityWord(peak)} rain expected around ${hhmm(next.startMs)} (${fmt1(peak)} mm/h)",
                    priority = AlertPriority.NORMAL,
                    rule = AlertRule.RAIN_START,
                )
            }
        } else {
            val endStep = findRainEnd(steps, current)
            if (rainEndArmed && endStep != null) {
                val inMin = minutesUntil(now, endStep.startMs)
                if (inMin <= config.rainEndLookaheadMin) {
                    rainEndArmed = false
                    out += RideAlert(
                        id = "rain-end-${endStep.startMs}",
                        title = "Rain ends in $inMin min",
                        detail = "Dry from about ${hhmm(endStep.startMs)}",
                        priority = AlertPriority.LOW,
                        rule = AlertRule.RAIN_END,
                    )
                }
            }
        }
    }

    /** First step after [current] that starts a dry stretch lasting [AlertConfig.dryConfirmMin]. */
    private fun findRainEnd(steps: List<PrecipStep>, current: PrecipStep): PrecipStep? {
        val after = steps.filter { it.startMs >= current.endMs }
        for ((i, s) in after.withIndex()) {
            if (isWet(s)) continue
            var dryUntil = s.startMs
            var j = i
            while (j < after.size && !isWet(after[j]) && after[j].startMs <= dryUntil) {
                dryUntil = after[j].endMs
                j++
            }
            if (dryUntil - s.startMs >= config.dryConfirmMin * 60_000L) return s
        }
        return null
    }

    // --- gust -------------------------------------------------------------------------------

    private fun gust(now: Long, gust: Double?, out: MutableList<RideAlert>) {
        gust ?: return
        if (gust < config.gustThresholdMs - config.gustHysteresisMs) {
            if (gustLowSince == null) gustLowSince = now
            if (now - gustLowSince!! >= config.gustRearmMin * 60_000L) gustArmed = true
        } else {
            gustLowSince = null
        }
        if (gustArmed && gust >= config.gustThresholdMs) {
            gustArmed = false
            out += RideAlert("gust-$now", "Gusts ${units.speed(gust)}", "Gusts at or above ${units.speed(config.gustThresholdMs)}", AlertPriority.HIGH, AlertRule.GUST)
        }
    }

    // --- wind shift -------------------------------------------------------------------------

    private fun windAt(series: List<ForecastStep>, t: Long): WindVector? {
        val s = series.filter { it.wind10m != null }.sortedBy { it.timeMs }
        if (s.isEmpty() || t < s.first().timeMs || t > s.last().timeMs) return null
        val i = s.indexOfLast { it.timeMs <= t }
        if (i == s.size - 1) return s[i].wind10m
        return lerp(s[i].wind10m!!, s[i + 1].wind10m!!, timeFactor(t, s[i].timeMs, s[i + 1].timeMs))
    }

    private fun windShift(now: Long, series: List<ForecastStep>, out: MutableList<RideAlert>) {
        if (series.isEmpty()) return
        val cur = windAt(series, now) ?: return
        val end = now + config.windShiftWindowMin * 60_000L
        val shift = if (cur.speed < config.windShiftMinSpeedMs) null else series
            .filter { it.timeMs in (now + 1)..end && it.wind10m != null }
            .sortedBy { it.timeMs }
            .firstOrNull { s ->
                val w = s.wind10m!!
                w.speed >= config.windShiftMinSpeedMs && abs(normalizeSignedDegrees(w.directionFromDeg - cur.directionFromDeg)) >= config.windShiftDeg
            }
        if (shift == null) {
            if (noShiftSince == null) noShiftSince = now
            if (now - noShiftSince!! >= 30 * 60_000L) shiftArmed = true
            return
        }
        noShiftSince = null
        val cooled = lastShiftMs?.let { now - it >= config.windShiftCooldownMin * 60_000L } ?: true
        if (shiftArmed && cooled) {
            shiftArmed = false
            lastShiftMs = now
            val w = shift.wind10m!!
            val inMin = minutesUntil(now, shift.timeMs)
            out += RideAlert(
                id = "wind-shift-${shift.timeMs}",
                title = "Wind shift in $inMin min",
                detail = "${compass(cur.directionFromDeg)} ${units.speed(cur.speed)} → ${compass(w.directionFromDeg)} ${units.speed(w.speed)}",
                priority = AlertPriority.NORMAL,
                rule = AlertRule.WIND_SHIFT,
            )
        }
    }

    // --- NWS --------------------------------------------------------------------------------

    private fun nws(now: Long, alerts: List<NwsAlert>, out: MutableList<RideAlert>) {
        for (a in alerts.sortedByDescending { it.severity == AlertSeverity.EXTREME }) {
            if (!a.severity.isSevereOrWorse) continue
            if (a.status != null && !a.status.equals("Actual", true)) continue
            if (a.messageType.equals("Cancel", true)) continue
            val end = a.endsMs ?: a.expiresMs
            if (end != null && end <= now) continue
            if (!nwsFired.add(a.id)) continue
            val last = nwsEventLastMs[a.event]
            nwsEventLastMs[a.event] = now
            if (a.messageType.equals("Update", true) && last != null && now - last < config.nwsUpdateSuppressMin * 60_000L) continue
            out += RideAlert(
                id = "nws-${a.id}",
                title = a.event,
                detail = a.headline ?: (end?.let { "Until ${hhmm(it)}" } ?: "In effect"),
                priority = if (a.severity == AlertSeverity.EXTREME) AlertPriority.CRITICAL else AlertPriority.HIGH,
                rule = AlertRule.NWS,
            )
        }
    }

    // --- storm estimate -----------------------------------------------------------------------

    private fun storm(now: Long, est: StormEstimate?, out: MutableList<RideAlert>) {
        val d = est?.distanceKm
        if (d == null || d > config.stormKm + config.stormHysteresisKm) {
            if (stormClearSince == null) stormClearSince = now
            if (now - stormClearSince!! >= config.stormRearmMin * 60_000L) stormArmed = true
        } else {
            stormClearSince = null
        }
        if (est != null && d != null && d <= config.stormKm && stormArmed) {
            stormArmed = false
            val dir = est.bearingDeg?.let { " ${compass(it)}" } ?: ""
            out += RideAlert(
                id = "storm-$now",
                title = "Storm ~${units.distance(d)}$dir",
                detail = (if (est.isEstimate) "Estimate from ${est.basis.joinToString { it.name.lowercase().replace('_', ' ') }}, not lightning data" else "Lightning detected") +
                    " (confidence ${est.confidence.name.lowercase()})",
                priority = AlertPriority.HIGH,
                rule = AlertRule.STORM,
            )
        }
    }

    // --- wildfire -----------------------------------------------------------------------------

    private fun fires(fires: List<FireProximity>, out: MutableList<RideAlert>) {
        for (f in fires) {
            val id = f.perimeter.id
            val armed = fireArmed[id] ?: true
            if (f.distanceKm > config.fireKm + config.fireHysteresisKm) { fireArmed[id] = true; continue }
            if (armed && f.distanceKm <= config.fireKm) {
                fireArmed[id] = false
                val where = if (f.inside) "You are inside the perimeter" else "${units.distance(f.distanceKm)}${f.bearingDeg?.let { " " + compass(it) } ?: ""}"
                val contained = f.perimeter.percentContained?.let { ", ${it.roundToInt()}% contained" } ?: ""
                out += RideAlert(
                    id = "fire-$id-${++fireAlertCount}",
                    title = "Wildfire ${f.perimeter.name}",
                    detail = "$where$contained",
                    priority = if (f.inside || f.distanceKm < 5) AlertPriority.CRITICAL else AlertPriority.HIGH,
                    rule = AlertRule.WILDFIRE,
                )
            }
        }
    }

    // --- AQI ----------------------------------------------------------------------------------

    private fun aqi(aqi: Int?, out: MutableList<RideAlert>) {
        aqi ?: return
        val cat = UsAqi.category(aqi)
        if (aqiAlertedCategory >= 0 && aqi < UsAqi.lowerBound(aqiAlertedCategory) - config.aqiHysteresis) aqiAlertedCategory = cat
        if (cat >= config.aqiMinCategory && cat > aqiAlertedCategory) {
            aqiAlertedCategory = cat
            out += RideAlert(
                id = "aqi-$cat-$aqi-${clock.nowMs()}",
                title = "Air quality: ${UsAqi.NAMES[cat]}",
                detail = "US AQI $aqi",
                priority = if (cat >= 4) AlertPriority.HIGH else AlertPriority.NORMAL,
                rule = AlertRule.AQI,
            )
        }
    }

    private fun minutesUntil(now: Long, t: Long): Int = ceil((t - now).coerceAtLeast(0) / 60_000.0).toInt()
    private fun hhmm(t: Long) = AlertText.hhmm(t)
    private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun intensityWord(mmH: Double) = when {
        mmH >= 7.6 -> "Heavy"
        mmH >= 2.5 -> "Moderate"
        else -> "Light"
    }

    private fun compass(deg: Double) = AlertText.compass(deg)
}

/** US EPA AQI categories. */
object UsAqi {
    val NAMES = listOf("Good", "Moderate", "Unhealthy for sensitive groups", "Unhealthy", "Very unhealthy", "Hazardous")
    private val LOWER = intArrayOf(0, 51, 101, 151, 201, 301)

    fun category(aqi: Int): Int = LOWER.indexOfLast { aqi >= it }.coerceAtLeast(0)
    fun lowerBound(category: Int): Int = LOWER[category.coerceIn(0, LOWER.size - 1)]
}

/** Formatting shared by alert and summary text. Time zone is the JVM default unless set by the app. */
object AlertText {
    @Volatile
    var zone: java.time.ZoneId = java.time.ZoneId.systemDefault()

    /** 12-hour clock (`3:05 PM`) instead of `15:05`; the app sets it from the device's time setting. */
    @Volatile
    var twelveHour: Boolean = false

    fun hhmm(t: Long): String = clock(t, zone)

    /** Clock time in [zone], in the 12- or 24-hour style of [twelveHour]. */
    fun clock(t: Long, zone: java.time.ZoneId): String =
        java.time.format.DateTimeFormatter.ofPattern(if (twelveHour) "h:mm a" else "HH:mm", Locale.US)
            .withZone(zone).format(java.time.Instant.ofEpochMilli(t))

    private val POINTS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun compass(deg: Double): String = POINTS[(((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
}
