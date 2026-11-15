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

package de.timklge.karooheadwind.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.timklge.karooheadwind.saveSettings
import de.timklge.karooheadwind.streamSettings
import de.timklge.karooheadwind.pipeline.DebugOverrides
import de.timklge.karooheadwind.pipeline.TrueWindRuntime
import io.github.kf6999.truewind.core.model.GeoPoint
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import de.timklge.karooheadwind.pipeline.Diagnostics as PipelineDiagnostics

/**
 * Debug-only provisioning over adb (debug manifest; protected by `android.permission.DUMP`, which
 * the adb shell holds). Lets scripts set API keys without typing them on the device:
 *
 *   source ~/.config/truewind/keys.env
 *   adb shell am broadcast -n io.github.kf6999.truewind/de.timklge.karooheadwind.debug.TrueWindProvisionReceiver \
 *     -a io.github.kf6999.truewind.debug.PROVISION --es synoptic_token "$SYNOPTIC_TOKEN" --es tomorrow_key "$TOMORROW_API_KEY"
 *
 * Extras (all optional): `synoptic_token`, `tomorrow_key` (empty string clears), `accept_welcome`
 * (bool), `upstream_mode` (bool), `refresh_min` (int), `lat` + `lon` (strings; debug position
 * override for indoor checks), `clear_position` (bool), `force_cycle` (bool), `dump` (bool: write
 * `files/truewind_diagnostics.json`). Key values are never logged.
 */
class TrueWindProvisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val done = ArrayList<String>()
            try {
                val karoo = KarooSystemService(app)
                var s = app.streamSettings(karoo).first()
                intent.getStringExtra("synoptic_token")?.let { s = s.copy(synopticToken = it.trim()); done += "synoptic_token(${if (it.isBlank()) "cleared" else "set"})" }
                intent.getStringExtra("tomorrow_key")?.let { s = s.copy(tomorrowApiKey = it.trim()); done += "tomorrow_key(${if (it.isBlank()) "cleared" else "set"})" }
                if (intent.hasExtra("accept_welcome")) { s = s.copy(welcomeDialogAccepted = intent.getBooleanExtra("accept_welcome", true)); done += "welcome" }
                if (intent.hasExtra("upstream_mode")) { s = s.copy(upstreamCompatibleMode = intent.getBooleanExtra("upstream_mode", false)); done += "upstream_mode=${s.upstreamCompatibleMode}" }
                if (intent.hasExtra("refresh_min")) { s = s.copy(refreshIntervalMin = intent.getIntExtra("refresh_min", 10)); done += "refresh=${s.refreshIntervalMin}" }
                if (done.isNotEmpty()) saveSettings(app, s)

                val lat = intent.getStringExtra("lat")?.toDoubleOrNull()
                val lon = intent.getStringExtra("lon")?.toDoubleOrNull()
                if (lat != null && lon != null) { DebugOverrides.position = GeoPoint(lat, lon); done += "position override" }
                if (intent.getBooleanExtra("clear_position", false)) { DebugOverrides.position = null; done += "position cleared" }
                if (intent.getBooleanExtra("force_cycle", false)) {
                    TrueWindRuntime.pipeline?.forceCycle()
                    done += if (TrueWindRuntime.pipeline != null) "force_cycle" else "force_cycle(no pipeline: extension not bound)"
                }
                if (intent.getBooleanExtra("dump", false)) {
                    File(app.filesDir, "truewind_diagnostics.json").writeText(toJson(PipelineDiagnostics.snapshot()))
                    done += "dump"
                }
                Log.i(TAG, "provision: ${done.joinToString()}")
                pending.resultCode = 1
                pending.resultData = "ok ${done.joinToString()}"
            } catch (e: Exception) {
                Log.e(TAG, "provision failed: ${e.javaClass.simpleName}")
                pending.resultCode = 2
                pending.resultData = "error ${e.javaClass.simpleName}"
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val TAG = "truewind-debug"

        /** Minimal JSON writer for the diagnostics map (maps, lists, pairs, numbers, strings; else toString). */
        fun toJson(v: Any?): String = when (v) {
            null -> "null"
            is Number, is Boolean -> v.toString().let { if (it == "NaN" || it.contains("Infinity")) "null" else it }
            is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            is Map<*, *> -> v.entries.joinToString(",", "{", "}") { toJson(it.key.toString()) + ":" + toJson(it.value) }
            is Collection<*> -> v.joinToString(",", "[", "]") { toJson(it) }
            is Pair<*, *> -> "[" + toJson(v.first) + "," + toJson(v.second) + "]"
            else -> toJson(v.toString())
        }
    }
}
