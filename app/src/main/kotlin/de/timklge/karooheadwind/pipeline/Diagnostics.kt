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

import de.timklge.karooheadwind.BuildConfig
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide counters for the diagnostics page, the diagnostics JSON and the TESTING 5.1
 * budgets. HTTP, emit, view-update and tick counts are also forwarded to
 * [DebugHooks.counters] in debug builds (the debug diagnostics JSON reads those). A plain singleton with atomic counters: cheap to bump from any thread, never blocks.
 *
 * Counters are cumulative since process start unless named otherwise; [httpRequestsToday] resets
 * at local midnight.
 */
object Diagnostics {
    val processStartMs: Long = System.currentTimeMillis()

    // --- HTTP (KarooHttp) ---
    val httpRequests = AtomicLong()
    val httpBytes = AtomicLong()
    val httpErrors = AtomicLong()
    val httpTimeouts = AtomicLong()
    /** Responses whose body still started with the gzip magic bytes (the Karoo path did not decompress). */
    val httpGzipBodies = AtomicLong()
    /** Responses whose request asked for gzip but whose body arrived as plain text. */
    val httpGzipDecodedByTransport = AtomicLong()
    val httpRequestsByHost = ConcurrentHashMap<String, AtomicLong>()
    val httpBytesByHost = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var todayEpochDay: Long = LocalDate.now().toEpochDay()
    private val requestsToday = AtomicLong()

    // --- Data types ---
    /** Stream emits per data type id. */
    val emits = ConcurrentHashMap<String, AtomicLong>()
    /** View updates (`updateView` IPCs) per data type id. */
    val viewUpdates = ConcurrentHashMap<String, AtomicLong>()

    // --- Tick ---
    val ticks = AtomicLong()
    val tickNanosTotal = AtomicLong()
    val tickNanosMax = AtomicLong()
    val stateEmits = AtomicLong()
    @Volatile var tickRunning: Boolean = false

    // --- Engine ---
    val cycles = AtomicLong()
    val cyclesSucceeded = AtomicLong()
    @Volatile var lastCycle: CycleSummary? = null
    @Volatile var lastSuccessfulCycleMs: Long? = null
    @Volatile var nextCheckAtMs: Long? = null
    @Volatile var memberWeights: List<Pair<String, Double>> = emptyList()
    @Volatile var stationsInUse: List<StationSummary> = emptyList()
    @Volatile var positionSource: String = "none"

    // --- Sensors ---
    val barometerEvents = AtomicLong()
    @Volatile var barometerAvailable: Boolean? = null
    @Volatile var lastBarometerHpa: Double? = null
    val headingEvents = AtomicLong()

    // --- Virtual sensor ---
    @Volatile var virtualSensor: VirtualSensorSummary? = null

    data class ProviderSummary(
        val id: String,
        val success: Boolean,
        val requests: Int,
        val bytes: Long,
        val httpStatus: Int?,
        val error: String?,
    )

    data class CycleSummary(
        val startedAtMs: Long,
        val durationMs: Long,
        val success: Boolean,
        val reasons: List<String>,
        val providers: List<ProviderSummary>,
        val observationsAccepted: Int,
        val observationsRejected: Int,
        val forecastPoints: Int,
        val requests: Int,
        val bytes: Long,
    )

    data class StationSummary(val id: String, val source: String, val distanceKm: Double, val ageMin: Double, val speedMs: Double?)

    data class VirtualSensorSummary(
        val active: Boolean,
        val measuredHeadwind: Double?,
        val innovationMean: Double?,
        val bias: Double?,
        val biasFrozen: Boolean,
        val effectiveRatio: Double?,
        val headingSource: String,
    )

    fun recordHttp(host: String, bytes: Long, error: Boolean) {
        if (BuildConfig.DEBUG) DebugHooks.counters.httpRequest(bytes, !error)
        httpRequests.incrementAndGet()
        httpBytes.addAndGet(bytes)
        if (error) httpErrors.incrementAndGet()
        httpRequestsByHost.getOrPut(host) { AtomicLong() }.incrementAndGet()
        httpBytesByHost.getOrPut(host) { AtomicLong() }.addAndGet(bytes)
        val day = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        if (day != todayEpochDay) {
            todayEpochDay = day
            requestsToday.set(0)
        }
        requestsToday.incrementAndGet()
    }

    fun httpRequestsToday(): Long {
        val day = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        return if (day != todayEpochDay) 0 else requestsToday.get()
    }

    fun countEmit(dataTypeId: String) {
        if (BuildConfig.DEBUG) DebugHooks.counters.emit(dataTypeId)
        emits.getOrPut(dataTypeId) { AtomicLong() }.incrementAndGet()
    }

    fun countViewUpdate(dataTypeId: String) {
        if (BuildConfig.DEBUG) DebugHooks.counters.viewUpdate(dataTypeId)
        viewUpdates.getOrPut(dataTypeId) { AtomicLong() }.incrementAndGet()
    }

    fun recordTick(nanos: Long) {
        if (BuildConfig.DEBUG) DebugHooks.counters.tick(nanos)
        ticks.incrementAndGet()
        tickNanosTotal.addAndGet(nanos)
        while (true) {
            val max = tickNanosMax.get()
            if (nanos <= max || tickNanosMax.compareAndSet(max, nanos)) break
        }
    }

    /** Plain map for the diagnostics JSON (no secrets: URLs and keys are never stored here). */
    fun snapshot(): Map<String, Any?> = linkedMapOf(
        "processStartMs" to processStartMs,
        "httpRequests" to httpRequests.get(),
        "httpRequestsToday" to httpRequestsToday(),
        "httpBytes" to httpBytes.get(),
        "httpErrors" to httpErrors.get(),
        "httpTimeouts" to httpTimeouts.get(),
        "httpGzipBodies" to httpGzipBodies.get(),
        "httpGzipDecodedByTransport" to httpGzipDecodedByTransport.get(),
        "httpRequestsByHost" to httpRequestsByHost.mapValues { it.value.get() },
        "httpBytesByHost" to httpBytesByHost.mapValues { it.value.get() },
        "emits" to emits.mapValues { it.value.get() },
        "viewUpdates" to viewUpdates.mapValues { it.value.get() },
        "ticks" to ticks.get(),
        "tickMeanUs" to (if (ticks.get() > 0) tickNanosTotal.get() / ticks.get() / 1000 else 0),
        "tickMaxUs" to tickNanosMax.get() / 1000,
        "tickRunning" to tickRunning,
        "stateEmits" to stateEmits.get(),
        "cycles" to cycles.get(),
        "cyclesSucceeded" to cyclesSucceeded.get(),
        "lastCycle" to lastCycle,
        "lastSuccessfulCycleMs" to lastSuccessfulCycleMs,
        "nextCheckAtMs" to nextCheckAtMs,
        "memberWeights" to memberWeights,
        "stationsInUse" to stationsInUse,
        "positionSource" to positionSource,
        "barometerAvailable" to barometerAvailable,
        "barometerEvents" to barometerEvents.get(),
        "lastBarometerHpa" to lastBarometerHpa,
        "headingEvents" to headingEvents.get(),
        "virtualSensor" to virtualSensor,
    )

    /** For tests. */
    fun resetForTest() {
        listOf(httpRequests, httpBytes, httpErrors, httpTimeouts, httpGzipBodies, httpGzipDecodedByTransport, ticks,
            tickNanosTotal, tickNanosMax, stateEmits, cycles, cyclesSucceeded, barometerEvents, headingEvents, requestsToday).forEach { it.set(0) }
        httpRequestsByHost.clear(); httpBytesByHost.clear(); emits.clear(); viewUpdates.clear()
        lastCycle = null; lastSuccessfulCycleMs = null; nextCheckAtMs = null
    }
}
