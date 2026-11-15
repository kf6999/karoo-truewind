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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.timklge.karooheadwind.HeadwindSettings
import de.timklge.karooheadwind.pipeline.AlertDispatcher
import de.timklge.karooheadwind.pipeline.KarooHttp
import de.timklge.karooheadwind.pipeline.KarooSystemHttpTransport
import de.timklge.karooheadwind.streamSettings
import de.timklge.karooheadwind.updateSettings
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.route.FinishAlertMachine
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Pure validation/mapping for the finish alert settings (unit-tested). */
object FinishAlertSettingsModel {
    val THRESHOLDS_MIN = listOf(60, 30, 15)

    /** Null when [url] is acceptable (blank is allowed: nothing configured yet); otherwise the error text. */
    fun urlError(url: String): String? {
        val u = url.trim()
        if (u.isEmpty()) return null
        return if (FinishAlertMachine.isValidUrl(u)) null else "Use a full https:// URL (HTTP is not allowed)"
    }

    fun canTest(url: String): Boolean = url.isNotBlank() && FinishAlertMachine.isValidUrl(url.trim())

    /** Test message payload: 25 minutes to go, no location unless the rider opted in (a fixed sample point is never sent). */
    fun testRequest(s: HeadwindSettings, nowMs: Long) =
        FinishAlertMachine.buildRequest(
            AlertDispatcher.finishConfig(s).copy(title = "TrueWind test"),
            nowMs, 25 * 60_000L, null as GeoPoint?,
        )
}

/** "Finish" tab: finish alert to a contact (SPEC 9.1). Off by default; the URL is never logged. */
@Composable
fun FinishAlertSettingsScreen(onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val karooSystem = remember { KarooSystemService(ctx) }
    val http = remember { KarooHttp(KarooSystemHttpTransport(karooSystem)) }
    var connected by remember { mutableStateOf(false) }
    var s by remember { mutableStateOf<HeadwindSettings?>(null) }
    LaunchedEffect(Unit) { karooSystem.connect { connected = it } }
    DisposableEffect(Unit) { onDispose { karooSystem.disconnect() } }
    LaunchedEffect(Unit) { ctx.streamSettings(karooSystem).collect { s = it } }
    BackHandler { onFinish() }

    fun edit(transform: (HeadwindSettings) -> HeadwindSettings) {
        writeScope.launch { updateSettings(ctx.applicationContext, transform) }
    }

    val settings = s ?: return
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.padding(5.dp).verticalScroll(rememberScrollState()).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = settings.finishAlertEnabled, onCheckedChange = { v -> edit { it.copy(finishAlertEnabled = v) } })
            Spacer(Modifier.width(10.dp))
            Text("Send finish alert")
        }
        Text("When you navigate a route, sends one message when the time to the destination first drops under the threshold. " +
            "Needs an HTTPS ntfy topic URL or a JSON webhook.", style = MaterialTheme.typography.bodySmall)

        SavedTextField("Target URL (https only)", settings.finishAlertUrl, singleLine = true,
            error = FinishAlertSettingsModel::urlError) { v -> edit { it.copy(finishAlertUrl = v) } }
        SavedTextField("Message text", settings.finishAlertMessage, singleLine = false, error = { null }) { v -> edit { it.copy(finishAlertMessage = v) } }

        val thresholds = FinishAlertSettingsModel.THRESHOLDS_MIN.map { DropdownOption(it.toString(), "$it min before arrival") }
        Dropdown("Threshold", thresholds, thresholds.firstOrNull { it.id == settings.finishAlertMinutes.toString() } ?: thresholds[1]) { o ->
            edit { it.copy(finishAlertMinutes = o.id.toInt()) }
        }
        val formats = listOf(DropdownOption("ntfy", "ntfy (plain text)"), DropdownOption("json", "JSON webhook"))
        Dropdown("Format", formats, if (settings.finishAlertJson) formats[1] else formats[0]) { o ->
            edit { it.copy(finishAlertJson = o.id == "json") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = settings.finishAlertIncludeLocation, onCheckedChange = { v -> edit { it.copy(finishAlertIncludeLocation = v) } })
            Spacer(Modifier.width(10.dp))
            Text("Include my location")
        }

        var result by remember { mutableStateOf<String?>(null) }
        var sending by remember { mutableStateOf(false) }
        FilledTonalButton(
            modifier = Modifier.fillMaxWidth().height(40.dp),
            enabled = connected && !sending && FinishAlertSettingsModel.canTest(settings.finishAlertUrl),
            onClick = {
                sending = true
                result = "Sending..."
                scope.launch {
                    result = try {
                        val r = http.request(FinishAlertSettingsModel.testRequest(settings, System.currentTimeMillis()))
                        if (r.isSuccessful) "Test sent (HTTP ${r.statusCode})" else "Test failed (HTTP ${r.statusCode})"
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "Test failed: ${e.javaClass.simpleName}"
                    }
                    sending = false
                }
            },
        ) { Text("Send test") }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.padding(30.dp))
    }
}

/** Text field saved when it loses focus and on leaving the screen (not per keystroke). */
@Composable
private fun SavedTextField(label: String, stored: String, singleLine: Boolean, error: (String) -> String?, onSave: (String) -> Unit) {
    var text by remember(stored) { mutableStateOf(stored) }
    var focused by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(text)
    DisposableEffect(Unit) { onDispose { if (latest.trim() != stored.trim()) onSave(latest.trim()) } }
    val err = error(text)
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = singleLine,
        isError = err != null,
        supportingText = err?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
            if (focused && !f.isFocused && text.trim() != stored.trim()) onSave(text.trim())
            focused = f.isFocused
        },
    )
}

private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
