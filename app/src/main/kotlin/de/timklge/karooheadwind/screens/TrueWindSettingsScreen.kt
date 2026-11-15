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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.timklge.karooheadwind.CdaPreset
import de.timklge.karooheadwind.CrrPreset
import de.timklge.karooheadwind.ExposureSetting
import de.timklge.karooheadwind.HeadwindSettings
import de.timklge.karooheadwind.REFRESH_INTERVAL_OPTIONS_MIN
import de.timklge.karooheadwind.pipeline.KarooHttp
import de.timklge.karooheadwind.pipeline.KarooSystemHttpTransport
import de.timklge.karooheadwind.pipeline.KeyTester
import de.timklge.karooheadwind.pipeline.TrueWindRuntime
import de.timklge.karooheadwind.streamSettings
import de.timklge.karooheadwind.updateSettings
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.launch
import java.util.Locale

/** TrueWind settings (SPEC 5 "Settings"): accuracy pipeline, physics, layers and optional API keys. */
@Composable
fun TrueWindSettingsScreen(onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val karooSystem = remember { KarooSystemService(ctx) }
    val http = remember { KarooHttp(KarooSystemHttpTransport(karooSystem)) }
    var connected by remember { mutableStateOf(false) }
    var s by remember { mutableStateOf<HeadwindSettings?>(null) }

    LaunchedEffect(Unit) { karooSystem.connect { connected = it } }
    DisposableEffect(Unit) { onDispose { karooSystem.disconnect() } }
    LaunchedEffect(Unit) { ctx.streamSettings(karooSystem).collect { s = it } }

    /** Read-modify-write against the stored settings so other screens' fields survive. */
    fun edit(transform: (HeadwindSettings) -> HeadwindSettings) {
        // Not the composition scope: saves issued from onDispose must still complete.
        settingsWriteScope.launch { updateSettings(ctx.applicationContext, transform) }
    }

    BackHandler { onFinish() }

    val settings = s ?: return
    Column(
        modifier = Modifier.padding(5.dp).verticalScroll(rememberScrollState()).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Toggle("Upstream-compatible mode", settings.upstreamCompatibleMode) { v -> edit { it.copy(upstreamCompatibleMode = v) } }
        if (settings.upstreamCompatibleMode) {
            Text("Uses the original Headwind computation (hourly forecast, 10 m wind). The settings below have no effect.",
                style = MaterialTheme.typography.bodySmall)
        }

        val refreshOptions = REFRESH_INTERVAL_OPTIONS_MIN.map { DropdownOption(it.toString(), "$it min") }
        Dropdown("Forecast refresh", refreshOptions,
            refreshOptions.find { it.id == settings.refreshIntervalMin.toString() } ?: refreshOptions[1]) { o ->
            edit { it.copy(refreshIntervalMin = o.id.toInt()) }
        }

        val exposureOptions = ExposureSetting.entries.map { DropdownOption(it.name, it.label) }
        Dropdown("Terrain exposure", exposureOptions, exposureOptions.first { it.id == settings.exposure.name }) { o ->
            edit { it.copy(exposure = ExposureSetting.valueOf(o.id)) }
        }

        NumberField("Rider height (wind at)", settings.riderHeightM, "m", 0.5..3.0) { v -> edit { it.copy(riderHeightM = v) } }
        Toggle("Show wind at 10 m instead of rider height", settings.showWindAt10m) { v -> edit { it.copy(showWindAt10m = v) } }
        Toggle("Group ride (drafting)", settings.groupRide) { v -> edit { it.copy(groupRide = v) } }

        Section("Bike and rider")
        val cdaOptions = CdaPreset.entries.map { DropdownOption(it.name, "${it.label} (${fmt(it.cda, 2)})") } + DropdownOption("custom", "Custom")
        Dropdown("CdA preset", cdaOptions,
            cdaOptions.find { o -> CdaPreset.entries.any { it.name == o.id && it.cda == settings.cda } } ?: cdaOptions.last()) { o ->
            CdaPreset.entries.find { it.name == o.id }?.let { p -> edit { it.copy(cda = p.cda) } }
        }
        NumberField("CdA", settings.cda, "m²", 0.15..0.8, key = settings.cda) { v -> edit { it.copy(cda = v) } }
        val crrOptions = CrrPreset.entries.map { DropdownOption(it.name, "${it.label} (${fmt(it.crr, 3)})") } + DropdownOption("custom", "Custom")
        Dropdown("Crr preset", crrOptions,
            crrOptions.find { o -> CrrPreset.entries.any { it.name == o.id && it.crr == settings.crr } } ?: crrOptions.last()) { o ->
            CrrPreset.entries.find { it.name == o.id }?.let { p -> edit { it.copy(crr = p.crr) } }
        }
        NumberField("Crr", settings.crr, "", 0.001..0.03, key = settings.crr) { v -> edit { it.copy(crr = v) } }
        NumberField("Bike weight", settings.bikeWeightKg, "kg", 3.0..40.0) { v -> edit { it.copy(bikeWeightKg = v) } }

        Section("Layers")
        Toggle("Forecast ensemble (several models)", settings.layerEnsemble) { v -> edit { it.copy(layerEnsemble = v) } }
        Toggle("Station observations", settings.layerObservations) { v -> edit { it.copy(layerObservations = v) } }
        Toggle("Downscaling to rider height", settings.layerDownscaling) { v -> edit { it.copy(layerDownscaling = v) } }
        Toggle("Heading fusion", settings.layerHeadingFusion) { v -> edit { it.copy(layerHeadingFusion = v) } }
        Toggle("Virtual wind sensor (power based, experimental)", settings.layerVirtualSensor) { v -> edit { it.copy(layerVirtualSensor = v) } }

        Section("Optional data sources")
        KeyField("Synoptic Data token", settings.synopticToken,
            onSave = { v -> edit { it.copy(synopticToken = v) } },
            test = { key -> KeyTester.testSynoptic(http, key) }, connected = connected,
            warning = "Synoptic only accepts the token in the URL, and the Karoo system logs request URLs to its log. Anyone with adb access to this Karoo could read it.")
        KeyField("Tomorrow.io API key", settings.tomorrowApiKey,
            onSave = { v -> edit { it.copy(tomorrowApiKey = v) } },
            test = { key -> KeyTester.testTomorrow(http, key) }, connected = connected)

        FilledTonalButton(modifier = Modifier.fillMaxWidth().height(40.dp), onClick = { TrueWindRuntime.pipeline?.forceCycle() }) {
            Text("Refresh wind now")
        }

        Section("Attribution")
        Text("Weather data by Open-Meteo.com (CC BY 4.0). Radar by RainViewer. Observations from aviationweather.gov " +
            "(NOAA AWC), api.weather.gov (NWS) and, if configured, Synoptic Data and Tomorrow.io.",
            style = MaterialTheme.typography.bodySmall)

        Spacer(modifier = Modifier.padding(30.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange)
        Spacer(modifier = Modifier.width(10.dp))
        Text(label)
    }
}

/** Decimal field that saves every valid, in-range value; [key] resets the text when a preset changes it. */
@Composable
private fun NumberField(label: String, value: Double, suffix: String, range: ClosedFloatingPointRange<Double>, key: Any? = null, onSave: (Double) -> Unit) {
    var text by remember(key) { mutableStateOf(trimNumber(value)) }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val valid = parsed != null && parsed in range
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.replace(',', '.').toDoubleOrNull()?.takeIf { it in range }?.let(onSave)
        },
        label = { Text(label) },
        suffix = if (suffix.isNotEmpty()) ({ Text(suffix) }) else null,
        isError = !valid,
        supportingText = if (!valid) ({ Text("${trimNumber(range.start)} to ${trimNumber(range.endInclusive)}") }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun KeyField(label: String, stored: String, onSave: (String) -> Unit, test: suspend (String) -> KeyTester.Result, connected: Boolean, warning: String? = null) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(stored) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // Saved when the field loses focus, on Test and on leaving the screen, not per keystroke:
    // a key change rebuilds the engine and starts a cycle.
    val latest by androidx.compose.runtime.rememberUpdatedState(text)
    DisposableEffect(Unit) { onDispose { if (latest.trim() != stored.trim()) onSave(latest.trim()) } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { t -> text = t },
            label = { Text(label) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
                if (focused && !f.isFocused) onSave(text.trim())
                focused = f.isFocused
            },
        )
        warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        FilledTonalButton(
            modifier = Modifier.fillMaxWidth().height(40.dp),
            enabled = !testing && connected && text.isNotBlank(),
            onClick = {
                testing = true
                result = "Testing..."
                onSave(text.trim())
                scope.launch {
                    result = try { test(text.trim()).message } catch (e: Exception) { "Test failed: ${e.message ?: e.javaClass.simpleName}" }
                    testing = false
                }
            },
        ) { Text("Test") }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private val settingsWriteScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

private fun fmt(v: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", v)

private fun trimNumber(v: Double): String = fmt(v, 3).trimEnd('0').trimEnd('.')
