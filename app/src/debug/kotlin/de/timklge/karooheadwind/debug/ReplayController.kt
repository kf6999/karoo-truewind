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

import android.content.Context
import android.util.Log
import de.timklge.karooheadwind.pipeline.KarooStreamSource
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug-only replay mode (TESTING 4, T3): feeds a recorded ride (corpus CSV format, see
 * `corpus/README.md`) into SensorHub in place of the live Karoo streams, at [speed] x real time.
 * Started/stopped by [DebugReceiver] so scripts can drive it over adb.
 */
object ReplayController {
    private const val TAG = "truewind-replay"

    /** Streams the replay substitutes. Everything else stays live. */
    val REPLAYED = setOf(
        DataType.Type.LOCATION, DataType.Type.SPEED, DataType.Type.POWER, DataType.Type.SMOOTHED_3S_AVERAGE_POWER,
        DataType.Type.CADENCE, DataType.Type.ELEVATION_GRADE, DataType.Type.HEADING, DataType.Type.TEMPERATURE,
    )

    data class Status(val active: Boolean = false, val track: String? = null, val speed: Double = 1.0, val index: Int = 0, val samples: Int = 0, val loops: Int = 0)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val flows = REPLAYED.associateWith { MutableSharedFlow<StreamState>(replay = 1, extraBufferCapacity = 8) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    data class Row(val tMs: Long, val lat: Double, val lon: Double, val speed: Double?, val power: Double?, val cadence: Double?, val bearing: Double?, val accuracy: Double?, val heading: Double?, val temp: Double?, val gradePct: Double?, val alt: Double?)

    fun parse(text: String): List<Row> {
        val lines = text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toList()
        val col = lines.first().split(',').withIndex().associate { it.value.trim() to it.index }
        fun d(r: List<String>, n: String) = col[n]?.let { r.getOrNull(it) }?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        return lines.drop(1).mapNotNull { l ->
            val r = l.split(',')
            Row(d(r, "time_ms")?.toLong() ?: return@mapNotNull null, d(r, "lat") ?: return@mapNotNull null, d(r, "lon") ?: return@mapNotNull null,
                d(r, "speed_mps"), d(r, "power_w"), d(r, "cadence_rpm"), d(r, "bearing_deg"), d(r, "accuracy_m"), d(r, "heading_deg"), d(r, "temp_c"), d(r, "grade_pct"), d(r, "alt_m"))
        }
    }

    /** [track] is `assets:<path>` or an absolute file path readable by the app. */
    fun start(context: Context, track: String, speed: Double, loop: Boolean) {
        stop()
        val text = if (track.startsWith("assets:")) context.assets.open(track.removePrefix("assets:")).bufferedReader().use { it.readText() } else File(track).readText()
        val rows = parse(text)
        require(rows.size >= 2) { "track $track has no samples" }
        Log.i(TAG, "replay start: $track, ${rows.size} samples at ${speed}x, loop=$loop")
        _status.value = Status(true, track, speed, 0, rows.size)
        job = scope.launch {
            var loops = 0
            do {
                val t0 = rows.first().tMs
                val wall0 = System.currentTimeMillis()
                rows.forEachIndexed { i, r ->
                    val due = wall0 + ((r.tMs - t0) / speed).toLong()
                    val wait = due - System.currentTimeMillis()
                    if (wait > 0) delay(wait)
                    publish(r)
                    if (i % 10 == 0) _status.value = _status.value.copy(index = i, loops = loops)
                }
                loops++
            } while (loop)
            Log.i(TAG, "replay finished")
            _status.value = _status.value.copy(active = false, index = rows.size, loops = loops)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (_status.value.active) Log.i(TAG, "replay stop")
        _status.value = _status.value.copy(active = false)
        flows.values.forEach { it.resetReplayCache() }
    }

    private suspend fun publish(r: Row) {
        fun single(type: String, v: Double?) = v?.let { StreamState.Streaming(DataPoint(type, mapOf(DataType.Field.SINGLE to it))) }
        flows.getValue(DataType.Type.LOCATION).emit(StreamState.Streaming(DataPoint(DataType.Type.LOCATION, buildMap {
            put(DataType.Field.LOC_LATITUDE, r.lat)
            put(DataType.Field.LOC_LONGITUDE, r.lon)
            put(DataType.Field.LOC_ACCURACY, r.accuracy ?: 5.0)
            r.bearing?.let { put(DataType.Field.LOC_BEARING, it) }
        })))
        single(DataType.Type.SPEED, r.speed)?.let { flows.getValue(DataType.Type.SPEED).emit(it) }
        single(DataType.Type.POWER, r.power)?.let { flows.getValue(DataType.Type.POWER).emit(it) }
        single(DataType.Type.SMOOTHED_3S_AVERAGE_POWER, r.power)?.let { flows.getValue(DataType.Type.SMOOTHED_3S_AVERAGE_POWER).emit(it) }
        single(DataType.Type.CADENCE, r.cadence)?.let { flows.getValue(DataType.Type.CADENCE).emit(it) }
        single(DataType.Type.ELEVATION_GRADE, r.gradePct)?.let { flows.getValue(DataType.Type.ELEVATION_GRADE).emit(it) }
        single(DataType.Type.HEADING, r.heading)?.let { flows.getValue(DataType.Type.HEADING).emit(it) }
        single(DataType.Type.TEMPERATURE, r.temp)?.let { flows.getValue(DataType.Type.TEMPERATURE).emit(it) }
    }

    /** Wraps the live source: replayed types switch between live and replay as replay starts/stops. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun wrap(live: KarooStreamSource): KarooStreamSource = object : KarooStreamSource {
        override fun dataStream(dataTypeId: String): Flow<StreamState> {
            val replay = flows[dataTypeId] ?: return live.dataStream(dataTypeId)
            return _status.map { it.active }.distinctUntilChanged().flatMapLatest { if (it) replay else live.dataStream(dataTypeId) }
        }
        override fun userProfile(): Flow<UserProfile> = live.userProfile()
        override fun rideState(): Flow<RideState> = live.rideState()
        override fun navigationState(): Flow<OnNavigationState> = live.navigationState()
        override fun activeRidePage(): Flow<ActiveRidePage> = live.activeRidePage()
    }
}
