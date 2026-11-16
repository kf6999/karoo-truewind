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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.timklge.karooheadwind.pipeline.Diagnostics
import kotlinx.coroutines.delay
import java.util.Locale

/** Diagnostics page (SPEC 5): engine weights, stations, last cycle and request budget. */
@Composable
fun DiagnosticsScreen(onFinish: () -> Unit) {
    var lines by remember { mutableStateOf(DiagnosticsText.sections(System.currentTimeMillis())) }
    LaunchedEffect(Unit) {
        while (true) {
            lines = DiagnosticsText.sections(System.currentTimeMillis())
            delay(1000)
        }
    }
    BackHandler { onFinish() }
    Column(
        modifier = Modifier.padding(5.dp).verticalScroll(rememberScrollState()).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        lines.forEach { (title, body) ->
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            body.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(modifier = Modifier.padding(30.dp))
    }
}

/** Plain-text rendering of [Diagnostics] (kept separate from Compose so it can be unit tested). */
object DiagnosticsText {
    fun sections(now: Long, d: Diagnostics = Diagnostics): List<Pair<String, List<String>>> = buildList {
        val c = d.lastCycle
        add("Last cycle" to if (c == null) listOf("No cycle yet") else buildList {
            add("${ago(now, c.startedAtMs)}, ${if (c.success) "ok" else "failed"}, ${c.durationMs} ms, reasons ${c.reasons.joinToString()}")
            add("${c.requests} requests, ${kb(c.bytes)}, ${c.forecastPoints} forecast points")
            add("Observations: ${c.observationsAccepted} used, ${c.observationsRejected} rejected")
            c.providers.forEach { p ->
                add("  ${p.id}: ${if (p.success) "ok" else "failed"}, ${p.requests} req, ${kb(p.bytes)}" +
                    (p.httpStatus?.let { ", HTTP $it" } ?: "") + (p.error?.let { ", $it" } ?: ""))
            }
        })
        d.offlineText?.let { t -> add("Connectivity" to listOfNotNull(t, d.offlineHint)) }
        d.temperatures?.let { t ->
            add("Temperature" to listOf(
                "Model (grid): ${temp(t.modelC)}",
                "Corrected to rider elevation: ${temp(t.correctedC)}",
                "Karoo sensor: ${temp(t.sensorC)}",
            ))
        }
        add("Schedule" to listOf(
            "Last success: ${d.lastSuccessfulCycleMs?.let { ago(now, it) } ?: "never"}",
            "Next check: ${d.nextCheckAtMs?.let { inMin(now, it) } ?: "on change"}",
            "Cycles: ${d.cycles.get()} (${d.cyclesSucceeded.get()} ok)",
            "Running: ${d.tickRunning}, position: ${d.positionSource}",
        ))
        add("Model weights" to d.memberWeights.ifEmpty { null }?.map { (id, w) -> "${id.substringAfter(':')}: ${pct(w)}" }.orEmpty().ifEmpty { listOf("None yet") })
        add("Stations" to d.stationsInUse.map { s ->
            "${s.id} (${s.source}) ${f1(s.distanceKm)} km, ${f1(s.ageMin)} min old" + (s.speedMs?.let { ", ${f1(it)} m/s" } ?: "")
        }.ifEmpty { listOf("None in use") })
        add("Requests" to buildList {
            add("Today: ${d.httpRequestsToday()}, since start: ${d.httpRequests.get()} (${kb(d.httpBytes.get())})")
            add("Errors: ${d.httpErrors.get()}, timeouts: ${d.httpTimeouts.get()}")
            d.httpRequestsByHost.toSortedMap().forEach { (h, n) -> add("  $h: ${n.get()} req, ${kb(d.httpBytesByHost[h]?.get() ?: 0)}") }
        })
        val v = d.virtualSensor
        add("Sensors" to listOfNotNull(
            "Barometer: ${when (d.barometerAvailable) { null -> "not checked"; false -> "none"; true -> "${d.barometerEvents.get()} events" }}" +
                (d.lastBarometerHpa?.let { ", ${f1(it)} hPa" } ?: ""),
            v?.let { "Virtual sensor: ${if (it.active) "active" else "inactive"}, heading ${it.headingSource}" +
                (it.bias?.let { b -> ", bias ${String.format(Locale.US, "%.2f", b)} m/s${if (it.biasFrozen) " (frozen)" else ""}" } ?: "") },
            "Ticks: ${d.ticks.get()}, mean ${if (d.ticks.get() > 0) d.tickNanosTotal.get() / d.ticks.get() / 1000 else 0} µs",
        ))
    }

    private fun temp(v: Double?) = v?.let { String.format(Locale.US, "%.1f °C", it) } ?: "--"
    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun pct(v: Double) = String.format(Locale.US, "%.0f%%", v * 100)
    private fun kb(b: Long) = if (b < 1024) "$b B" else String.format(Locale.US, "%.1f KB", b / 1024.0)
    private fun ago(now: Long, t: Long): String {
        val s = (now - t) / 1000
        return if (s < 60) "${s.coerceAtLeast(0)} s ago" else "${s / 60} min ago"
    }
    private fun inMin(now: Long, t: Long): String {
        val s = (t - now) / 1000
        return if (s <= 0) "due" else if (s < 60) "in $s s" else "in ${s / 60} min"
    }
}
