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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Debug-only seams (TESTING 4–5). Release builds never set these: the debug source set installs
 * them from a ContentProvider at process start (`app/src/debug`), and every call site is guarded by
 * `BuildConfig.DEBUG` so R8 removes it from release.
 */
object DebugHooks {
    /** Wraps the Karoo stream source (replay mode feeds a recorded ride in place of live streams). */
    @Volatile
    var wrapStreamSource: ((KarooStreamSource) -> KarooStreamSource)? = null

    /** Called once per [SensorHub] created. */
    @Volatile
    var onSensorHub: ((SensorHub) -> Unit)? = null

    val counters = DebugCounters()
}

/**
 * Process-wide counters written to the diagnostics JSON. Cheap enough to call from hot paths.
 *
 * SEAMS still to be called by other work packages (they currently stay 0):
 *  - [httpRequest]: `KarooHttp` (WP8) after every MakeHttpRequest; bytes = response body size.
 *  - [viewUpdate]: data types (WP9) after every `emitter.updateView`.
 *  - [tick]: the WindRepository / tick loop (WP1/WP8) around each WindState computation.
 * [emit] is also counted from outside by the diagnostics observer, see `app/src/debug`.
 */
class DebugCounters {
    val httpRequests = AtomicLong()
    val httpBytes = AtomicLong()
    val httpFailures = AtomicLong()
    val viewUpdates = ConcurrentHashMap<String, AtomicLong>()
    val emits = ConcurrentHashMap<String, AtomicLong>()
    val ticks = AtomicLong()
    val tickNanos = AtomicLong()
    /** Tick time histogram, upper bounds in microseconds: [TICK_BUCKETS_US], last bucket = overflow. */
    val tickHistogram = AtomicLongArray(TICK_BUCKETS_US.size + 1)
    val lastHttpTimesMs = java.util.concurrent.ConcurrentLinkedDeque<Long>()

    fun httpRequest(bytes: Long, success: Boolean, nowMs: Long = System.currentTimeMillis()) {
        httpRequests.incrementAndGet()
        httpBytes.addAndGet(bytes)
        if (!success) httpFailures.incrementAndGet()
        lastHttpTimesMs.addLast(nowMs)
        while (lastHttpTimesMs.size > 50) lastHttpTimesMs.pollFirst()
    }

    fun viewUpdate(typeId: String) { viewUpdates.getOrPut(typeId) { AtomicLong() }.incrementAndGet() }

    fun emit(typeId: String) { emits.getOrPut(typeId) { AtomicLong() }.incrementAndGet() }

    fun tick(nanos: Long) {
        ticks.incrementAndGet()
        tickNanos.addAndGet(nanos)
        val us = nanos / 1000
        val idx = TICK_BUCKETS_US.indexOfFirst { us <= it }.let { if (it < 0) TICK_BUCKETS_US.size else it }
        tickHistogram.incrementAndGet(idx)
    }

    fun reset() {
        httpRequests.set(0); httpBytes.set(0); httpFailures.set(0); ticks.set(0); tickNanos.set(0)
        viewUpdates.clear(); emits.clear(); lastHttpTimesMs.clear()
        for (i in 0 until tickHistogram.length()) tickHistogram.set(i, 0)
    }

    companion object {
        val TICK_BUCKETS_US = longArrayOf(50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 50_000)
    }
}
