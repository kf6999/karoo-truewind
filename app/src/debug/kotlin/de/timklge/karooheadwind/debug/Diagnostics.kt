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
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import de.timklge.karooheadwind.KarooHeadwindExtension
import de.timklge.karooheadwind.pipeline.DebugCounters
import de.timklge.karooheadwind.pipeline.DebugHooks
import de.timklge.karooheadwind.pipeline.SensorHub
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug diagnostics (TESTING 4–5): a JSON snapshot in `files/diagnostics.json` (refreshed every
 * [PERIOD_MS] and on demand) and an optional once-a-minute CSV battery log in
 * `files/battery_log.csv`. Pull with `adb exec-out run-as io.github.kf6999.truewind cat files/...`.
 */
object Diagnostics {
    private const val TAG = "truewind-diag"
    private const val PERIOD_MS = 10_000L

    /** Numeric TrueWind fields observed by [observeFields] (extension_info.xml, graphical="false"). */
    val NUMERIC_FIELDS = listOf(
        "headwindSpeed", "windSpeed", "windGusts", "temperature", "relativeHumidity", "cloudCover",
        "surfacePressure", "sealevelPressure", "precipitation", "uvi",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context
    private val startElapsed = SystemClock.elapsedRealtime()
    @Volatile private var sensorHubs = 0
    private var writerJob: Job? = null
    private var batteryJob: Job? = null

    // Field observer (counts emits as the Karoo sees them, and whether each field has data).
    private var karoo: KarooSystemService? = null
    private val consumerIds = mutableListOf<String>()
    private val fieldState = ConcurrentHashMap<String, String>()
    private val fieldValue = ConcurrentHashMap<String, Double>()
    private val fieldEmits = ConcurrentHashMap<String, AtomicLong>()
    private val firstValueElapsed = ConcurrentHashMap<String, Long>()
    private var observeStartElapsed = 0L

    private val baseRx = AtomicLong(TrafficStats.getUidRxBytes(Process.myUid()))
    private val baseTx = AtomicLong(TrafficStats.getUidTxBytes(Process.myUid()))
    private val baseCpuMs = AtomicLong(Process.getElapsedCpuTime())

    fun install(context: Context) {
        appContext = context.applicationContext
        DebugHooks.onSensorHub = { _: SensorHub -> sensorHubs++ }
        writerJob = scope.launch {
            while (isActive) {
                runCatching { write() }.onFailure { Log.w(TAG, "write failed", it) }
                delay(PERIOD_MS)
            }
        }
    }

    fun reset() {
        DebugHooks.counters.reset()
        fieldEmits.clear(); fieldState.clear(); fieldValue.clear(); firstValueElapsed.clear()
        observeStartElapsed = SystemClock.elapsedRealtime()
        baseRx.set(TrafficStats.getUidRxBytes(Process.myUid()))
        baseTx.set(TrafficStats.getUidTxBytes(Process.myUid()))
        baseCpuMs.set(Process.getElapsedCpuTime())
    }

    /**
     * Subscribes to our own fields through the Karoo (`TYPE_EXT::truewind::<id>`), which starts
     * their streams exactly as a ride page would. Lets scripts verify values without UI setup.
     */
    fun observeFields(ids: List<String> = NUMERIC_FIELDS) {
        stopObserving()
        val k = KarooSystemService(appContext)
        karoo = k
        observeStartElapsed = SystemClock.elapsedRealtime()
        k.connect { connected ->
            if (!connected) return@connect
            synchronized(consumerIds) {
                if (consumerIds.isNotEmpty()) return@connect
                for (id in ids) {
                    val typeId = "TYPE_EXT::${KarooHeadwindExtension.EXTENSION_ID}::$id"
                    consumerIds += k.addConsumer(OnStreamState.StartStreaming(typeId)) { event: OnStreamState ->
                        val st = event.state
                        fieldEmits.getOrPut(id) { AtomicLong() }.incrementAndGet()
                        DebugHooks.counters.emit(id)
                        fieldState[id] = st::class.simpleName ?: "?"
                        if (st is StreamState.Streaming) {
                            st.dataPoint.singleValue?.let { fieldValue[id] = it }
                            firstValueElapsed.putIfAbsent(id, SystemClock.elapsedRealtime() - observeStartElapsed)
                        }
                    }
                }
            }
            Log.i(TAG, "observing ${ids.size} fields")
        }
    }

    fun stopObserving() {
        val k = karoo ?: return
        synchronized(consumerIds) { consumerIds.forEach { k.removeConsumer(it) }; consumerIds.clear() }
        k.disconnect()
        karoo = null
    }

    fun startBatteryLog(intervalMs: Long = 60_000L) {
        batteryJob?.cancel()
        val file = File(appContext.filesDir, "battery_log.csv")
        if (!file.exists()) file.writeText("elapsed_s,wall_ms,battery_pct,charge_counter_uah,current_now_ua,cpu_ms,rx_bytes,tx_bytes,http_requests,http_bytes,emits,view_updates,pss_kb,replay_index\n")
        batteryJob = scope.launch {
            while (isActive) {
                runCatching { file.appendText(batteryRow() + "\n") }
                delay(intervalMs)
            }
        }
    }

    fun stopBatteryLog() { batteryJob?.cancel(); batteryJob = null }

    private fun batteryRow(): String {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val c = DebugHooks.counters
        return listOf(
            (SystemClock.elapsedRealtime() - startElapsed) / 1000, System.currentTimeMillis(),
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            Process.getElapsedCpuTime(),
            TrafficStats.getUidRxBytes(Process.myUid()), TrafficStats.getUidTxBytes(Process.myUid()),
            c.httpRequests.get(), c.httpBytes.get(), c.emits.values.sumOf { it.get() }, c.viewUpdates.values.sumOf { it.get() },
            Debug.getPss(), ReplayController.status.value.index,
        ).joinToString(",")
    }

    private fun counterMap(m: Map<String, AtomicLong>) = JSONObject().also { o -> m.toSortedMap().forEach { (k, v) -> o.put(k, v.get()) } }

    fun snapshot(): JSONObject {
        val c: DebugCounters = DebugHooks.counters
        val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val hist = JSONObject()
        DebugCounters.TICK_BUCKETS_US.forEachIndexed { i, b -> hist.put("le_${b}us", c.tickHistogram.get(i)) }
        hist.put("gt_${DebugCounters.TICK_BUCKETS_US.last()}us", c.tickHistogram.get(DebugCounters.TICK_BUCKETS_US.size))
        val r = ReplayController.status.value
        return JSONObject().apply {
            put("schema", 1)
            put("wall_ms", System.currentTimeMillis())
            put("uptime_s", (SystemClock.elapsedRealtime() - startElapsed) / 1000)
            put("pid", Process.myPid())
            put("sensor_hubs", sensorHubs)
            put("replay", JSONObject().put("active", r.active).put("track", r.track).put("speed", r.speed).put("index", r.index).put("samples", r.samples).put("loops", r.loops))
            put("http", JSONObject()
                .put("requests", c.httpRequests.get()).put("bytes", c.httpBytes.get()).put("failures", c.httpFailures.get())
                .put("recent_request_times_ms", JSONArray(c.lastHttpTimesMs.toList()))
                .put("note", "requests/bytes need the KarooHttp seam (WP8); uid_* are kernel counters"))
            put("uid_rx_bytes", TrafficStats.getUidRxBytes(Process.myUid()) - baseRx.get())
            put("uid_tx_bytes", TrafficStats.getUidTxBytes(Process.myUid()) - baseTx.get())
            put("cpu_ms", Process.getElapsedCpuTime() - baseCpuMs.get())
            put("ticks", JSONObject().put("count", c.ticks.get()).put("total_ns", c.tickNanos.get()).put("histogram", hist))
            put("emits", counterMap(c.emits))
            put("view_updates", counterMap(c.viewUpdates))
            put("fields", JSONObject().also { o ->
                (fieldState.keys + fieldEmits.keys).toSortedSet().forEach { id ->
                    o.put(id, JSONObject().put("state", fieldState[id]).put("value", fieldValue[id]).put("emits", fieldEmits[id]?.get() ?: 0)
                        .put("first_value_ms", firstValueElapsed[id]))
                }
            })
            put("memory", JSONObject().put("total_pss_kb", mem.totalPss).put("java_heap_kb", mem.getMemoryStat("summary.java-heap")).put("native_heap_kb", mem.getMemoryStat("summary.native-heap")))
            put("threads", Thread.activeCount())
        }
    }

    fun write() {
        val f = File(appContext.filesDir, "diagnostics.json")
        val tmp = File(appContext.filesDir, "diagnostics.json.tmp")
        tmp.writeText(snapshot().toString(2))
        tmp.renameTo(f)
    }
}
