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
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.util.Log
import de.timklge.karooheadwind.pipeline.DebugHooks

/**
 * adb control surface for debug builds (exported, debug manifest only):
 *
 *   adb shell am broadcast -n io.github.kf6999.truewind/de.timklge.karooheadwind.debug.DebugReceiver -a <ACTION> [extras]
 *
 *   ...REPLAY_START   --es track assets:replay/track.csv|/abs/path.csv  --ef speed 10  --ez loop true
 *   ...REPLAY_STOP
 *   ...OBSERVE_START  [--es fields headwindSpeed,windSpeed,...]   (subscribe to our fields via the Karoo)
 *   ...OBSERVE_STOP
 *   ...DIAG_RESET     zero counters
 *   ...DIAG_DUMP      write files/diagnostics.json now
 *   ...BATTERY_LOG_START [--el interval_ms 60000] / BATTERY_LOG_STOP
 *
 * Action names are prefixed with `io.github.kf6999.truewind.debug.`.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.substringAfterLast('.') ?: return
        Log.i(TAG, "debug action $action")
        try {
            when (action) {
                "REPLAY_START" -> ReplayController.start(
                    context,
                    track = intent.getStringExtra("track") ?: "assets:replay/track.csv",
                    speed = intent.getFloatExtra("speed", 10f).toDouble(),
                    loop = intent.getBooleanExtra("loop", true),
                )
                "REPLAY_STOP" -> ReplayController.stop()
                "OBSERVE_START" -> Diagnostics.observeFields(intent.getStringExtra("fields")?.split(',')?.map { it.trim() } ?: Diagnostics.NUMERIC_FIELDS)
                "OBSERVE_STOP" -> Diagnostics.stopObserving()
                "DIAG_RESET" -> Diagnostics.reset()
                "DIAG_DUMP" -> Diagnostics.write()
                "BATTERY_LOG_START" -> Diagnostics.startBatteryLog(intent.getLongExtra("interval_ms", 60_000L))
                "BATTERY_LOG_STOP" -> Diagnostics.stopBatteryLog()
                else -> Log.w(TAG, "unknown action $action")
            }
            resultCode = 1
            resultData = "ok $action"
        } catch (e: Exception) {
            Log.e(TAG, "debug action $action failed", e)
            resultCode = 2
            resultData = "error $action: ${e.message}"
        }
    }

    companion object { const val TAG = "truewind-debug" }
}

/** Runs before the extension service in the app process and installs the debug hooks. */
class DebugInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        DebugHooks.wrapStreamSource = { live -> ReplayController.wrap(live) }
        Diagnostics.install(ctx)
        Log.i(DebugReceiver.TAG, "TrueWind debug hooks installed")
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
