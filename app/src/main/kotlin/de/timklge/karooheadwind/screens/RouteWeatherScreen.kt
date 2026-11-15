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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.timklge.karooheadwind.pipeline.ParityHub
import de.timklge.karooheadwind.streamForecastRecord
import de.timklge.karooheadwind.streamSettings
import de.timklge.karooheadwind.streamUpcomingRoute
import de.timklge.karooheadwind.streamUserProfile
import io.github.kf6999.truewind.core.route.RouteWeatherPlanner
import io.github.kf6999.truewind.core.route.SpeedModel
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.delay

/** Route weather (SPEC 9.1 "ride planner"): 8 points along the loaded route for a chosen start offset. */
@Composable
fun RouteWeatherScreen(onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val karooSystem = remember { KarooSystemService(ctx) }
    LaunchedEffect(Unit) { karooSystem.connect { } }
    DisposableEffect(Unit) { onDispose { karooSystem.disconnect() } }
    BackHandler { onFinish() }

    val route by remember { karooSystem.streamUpcomingRoute() }.collectAsStateWithLifecycle(null)
    val record by remember { ctx.streamForecastRecord() }.collectAsStateWithLifecycle(null)
    val settings by remember { ctx.streamSettings(karooSystem) }.collectAsStateWithLifecycle(null)
    val profile by remember { karooSystem.streamUserProfile() }.collectAsStateWithLifecycle(null)
    var offsetH by remember { mutableIntStateOf(0) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000L); nowMs = System.currentTimeMillis() } }

    Column(
        modifier = Modifier.padding(5.dp).verticalScroll(rememberScrollState()).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val r = route
        val s = settings
        val poly = r?.let { ParityHub.routePolyline(it.routePolyline) }
        if (r == null || poly == null || s == null) {
            Text("No route loaded. Load a route on the Karoo to see weather along it.")
            return@Column
        }
        val imperialDist = profile?.preferredUnit?.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
        val units = RouteWeatherUnits(
            s.getWindUnit(imperialDist),
            profile?.preferredUnit?.temperature == UserProfile.PreferredUnit.UnitType.IMPERIAL,
            imperialDist,
        )
        val kmh = (s.getForecastMetersPerHour(imperialDist) / 1000.0).coerceAtLeast(5.0)
        val response = record?.response
        val planner = RouteWeatherPlanner(forecast = { p, t -> ParityHub.forecastAt(response, p, t) })
        val speed = SpeedModel.constant(kmh)
        val startMs = nowMs + offsetH * 3_600_000L
        val plan = remember(poly, response, startMs, kmh) { planner.plan(poly, startMs, speed) }
        val better = remember(poly, response, nowMs, kmh) { planner.betterStart(poly, nowMs, speed) }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(enabled = offsetH > 0, onClick = { offsetH-- }) { Text("-") }
            Text("Start: ${RouteWeatherModel.offsetLabel(offsetH)}", fontWeight = FontWeight.Bold)
            FilledTonalButton(enabled = offsetH < RouteWeatherModel.OFFSETS_H.last(), onClick = { offsetH++ }) { Text("+") }
        }
        Text(RouteWeatherModel.overall(plan), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(RouteWeatherModel.betterStartText(better), style = MaterialTheme.typography.bodySmall)
        RouteWeatherModel.rows(plan, units).forEach { row ->
            Column {
                Text("${row.eta}  ${row.distance}   score ${row.score}", fontWeight = FontWeight.Bold)
                Text("${row.wind}   ${row.temperature}   ${row.rain}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Hourly forecast along the route at your forecast speed.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.padding(30.dp))
    }
}
