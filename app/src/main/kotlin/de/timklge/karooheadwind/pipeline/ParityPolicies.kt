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

package de.timklge.karooheadwind.pipeline

import de.timklge.karooheadwind.HeadwindSettings
import de.timklge.karooheadwind.R
import io.github.kf6999.truewind.core.alerts.AlertConfig
import io.github.kf6999.truewind.core.alerts.AlertPriority
import io.github.kf6999.truewind.core.alerts.AlertRule
import io.github.kf6999.truewind.core.alerts.AlertUnits
import io.github.kf6999.truewind.core.alerts.RideAlert
import io.github.kf6999.truewind.core.providers.radar.TileId
import io.github.kf6999.truewind.core.providers.radar.TilePlan
import io.github.kf6999.truewind.core.route.FinishAlertConfig
import io.github.kf6999.truewind.core.route.FinishAlertFormat
import io.hammerhead.karooext.models.InRideAlert

/**
 * When to run one of the WP13-16 side fetches (air quality, NWS alerts, fire perimeters, storm
 * radar). Pure and clock-free (times are passed in) so it is unit-tested.
 *
 * The goal is one radio wake per engine cycle (SPEC 4.1, TESTING 5.1 "one burst per cycle"):
 * a side fetch that is due, or due within [earlyMs], runs right after an engine fetch cycle
 * ([windowMs] after its end). Only if no engine cycle comes within [maxWaitMs] of the due time does
 * it run on its own. Nothing runs while it is not needed.
 */
class SideFetchPolicy(
    val intervalMs: Long,
    val retryMs: Long = 2 * 60_000L,
    val earlyMs: Long = minOf(intervalMs / 4, 3 * 60_000L),
    val maxWaitMs: Long = minOf(intervalMs / 2, 3 * 60_000L),
    val windowMs: Long = 20_000L,
) {
    enum class Decision { NOT_NEEDED, NOT_DUE, WAIT, FETCH }

    var lastAttemptMs: Long? = null
        private set
    var lastSuccessMs: Long? = null
        private set
    private var failed = false

    fun decide(nowMs: Long, needed: Boolean, lastCycleEndMs: Long?): Decision {
        if (!needed) return Decision.NOT_NEEDED
        val last = lastAttemptMs ?: return Decision.FETCH
        val elapsed = nowMs - last
        if (failed) return if (elapsed >= retryMs) Decision.FETCH else Decision.NOT_DUE
        val inWindow = lastCycleEndMs != null && lastCycleEndMs > last && nowMs - lastCycleEndMs in 0..windowMs
        return when {
            inWindow && elapsed >= intervalMs - earlyMs -> Decision.FETCH
            elapsed >= intervalMs + maxWaitMs -> Decision.FETCH
            elapsed >= intervalMs -> Decision.WAIT
            else -> Decision.NOT_DUE
        }
    }

    fun onResult(nowMs: Long, success: Boolean) {
        lastAttemptMs = nowMs
        failed = !success
        if (success) lastSuccessMs = nowMs
    }

    /** Forget history (e.g. the rider moved far, or a new ride). */
    fun reset() { lastAttemptMs = null; lastSuccessMs = null; failed = false }
}

/**
 * Maps core [RideAlert]s to Karoo [InRideAlert]s and dispatches each alert id exactly once
 * (WP14). Per-rule enable switches come from the settings.
 */
class AlertDispatcher(private val dispatch: (InRideAlert) -> Unit) {
    private val sent = LinkedHashSet<String>()
    var dispatchedCount = 0
        private set
    @Volatile var lastAlert: RideAlert? = null
        private set

    fun reset() = sent.clear()

    /** Dispatches the enabled, not yet sent alerts; returns them. */
    fun dispatch(alerts: List<RideAlert>, settings: HeadwindSettings): List<RideAlert> {
        val out = alerts.filter { enabled(it.rule, settings) && sent.add(it.id) }
        while (sent.size > MAX_REMEMBERED) sent.remove(sent.first())
        for (a in out) {
            dispatch(toInRideAlert(a))
            dispatchedCount++
            lastAlert = a
        }
        return out
    }

    companion object {
        private const val MAX_REMEMBERED = 500

        fun enabled(rule: AlertRule, s: HeadwindSettings): Boolean = when (rule) {
            AlertRule.RAIN_START, AlertRule.RAIN_END -> s.alertRain
            AlertRule.GUST -> s.alertGust
            AlertRule.WIND_SHIFT -> s.alertWindShift
            AlertRule.NWS -> s.alertNws
            AlertRule.STORM -> s.alertStorm
            AlertRule.WILDFIRE -> s.alertWildfire
            AlertRule.AQI -> s.alertAqi
            AlertRule.RIDE_SUMMARY -> s.alertRideSummary
        }

        fun toInRideAlert(a: RideAlert): InRideAlert = InRideAlert(
            id = "truewind-${a.id}".take(120),
            icon = R.drawable.wind,
            title = a.title,
            detail = a.detail,
            autoDismissMs = when (a.priority) {
                AlertPriority.LOW -> 10_000L
                AlertPriority.NORMAL -> 15_000L
                AlertPriority.HIGH -> 20_000L
                AlertPriority.CRITICAL -> 30_000L
            },
            backgroundColor = when (a.priority) {
                AlertPriority.LOW, AlertPriority.NORMAL -> R.color.alert_info
                AlertPriority.HIGH -> R.color.alert_warning
                AlertPriority.CRITICAL -> R.color.alert_critical
            },
            textColor = R.color.white,
        )

        fun alertConfig(s: HeadwindSettings) = AlertConfig(
            gustThresholdMs = s.alertGustKmh / 3.6,
            stormKm = s.alertStormKm.toDouble(),
            fireKm = s.alertWildfireKm.toDouble(),
            aqiMinCategory = s.alertAqiCategory.coerceIn(1, 5),
        )

        fun units(imperial: Boolean) = AlertUnits(metricSpeed = !imperial, metricDistance = !imperial, metricTemperature = !imperial)

        fun finishConfig(s: HeadwindSettings) = FinishAlertConfig(
            enabled = s.finishAlertEnabled,
            url = s.finishAlertUrl.trim(),
            format = if (s.finishAlertJson) FinishAlertFormat.JSON else FinishAlertFormat.NTFY,
            thresholdMin = s.finishAlertMinutes,
            message = s.finishAlertMessage,
            includeLocation = s.finishAlertIncludeLocation,
        )
    }
}

/**
 * Radar byte budget (WP13): a refresh should stay under [budgetBytes] (~100 KB, SPEC 9.2). Tile
 * sizes vary from 334 B (empty) to ~45 KB (convection), so the previous refresh's mean tile size
 * predicts this one; when the plan would exceed the budget, only the tiles nearest the rider are
 * fetched (the rider's own tile first).
 */
object RadarBudget {
    const val DEFAULT_BUDGET_BYTES = 100_000L

    fun select(plan: TilePlan, needed: Collection<TileId>, lastMeanTileBytes: Long?, budgetBytes: Long = DEFAULT_BUDGET_BYTES): List<TileId> {
        val ordered = plan.tiles.filter { it.tile in needed }.sortedBy { t ->
            val cx = t.viewX + t.viewSize / 2 - plan.riderViewX
            val cy = t.viewY + t.viewSize / 2 - plan.riderViewY
            cx * cx + cy * cy
        }.map { it.tile }
        val mean = lastMeanTileBytes ?: return ordered
        if (mean <= 0 || mean * ordered.size <= budgetBytes) return ordered
        val n = (budgetBytes / mean).toInt().coerceAtLeast(1)
        return ordered.take(n)
    }
}
