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

package io.github.kf6999.truewind.core.schedule

/** Network usage for diagnostics and the TESTING 5.1 budgets (≤ 8 req / 60 KB per cycle, ≤ 400 KB per hour). */
data class BudgetSnapshot(
    val cycles: Long = 0,
    val lastCycleRequests: Int = 0,
    val lastCycleBytes: Long = 0,
    val lastHourRequests: Int = 0,
    val lastHourBytes: Long = 0,
    val totalRequests: Long = 0,
    val totalBytes: Long = 0,
    /** Requests per provider id since start. */
    val requestsByProvider: Map<String, Long> = emptyMap(),
    val bytesByProvider: Map<String, Long> = emptyMap(),
)

/** Rolling request / byte counters. Not thread-safe; owned by the planner. */
class FetchBudget(private val windowMs: Long = 60 * 60_000L) {
    private class Entry(val timeMs: Long, val requests: Int, val bytes: Long)

    private val window = ArrayDeque<Entry>()
    private var cycles = 0L
    private var lastCycleRequests = 0
    private var lastCycleBytes = 0L
    private var totalRequests = 0L
    private var totalBytes = 0L
    private val byProviderRequests = LinkedHashMap<String, Long>()
    private val byProviderBytes = LinkedHashMap<String, Long>()

    fun recordCycle(nowMs: Long, perProvider: List<Triple<String, Int, Long>>) {
        val req = perProvider.sumOf { it.second }
        val bytes = perProvider.sumOf { it.third }
        cycles++
        lastCycleRequests = req
        lastCycleBytes = bytes
        totalRequests += req
        totalBytes += bytes
        for ((id, r, b) in perProvider) {
            byProviderRequests[id] = (byProviderRequests[id] ?: 0L) + r
            byProviderBytes[id] = (byProviderBytes[id] ?: 0L) + b
        }
        window.addLast(Entry(nowMs, req, bytes))
        prune(nowMs)
    }

    private fun prune(nowMs: Long) {
        while (window.isNotEmpty() && nowMs - window.first().timeMs >= windowMs) window.removeFirst()
    }

    fun lastHourBytes(nowMs: Long): Long = window.filter { nowMs - it.timeMs < windowMs }.sumOf { it.bytes }

    fun lastHourRequests(nowMs: Long): Int = window.filter { nowMs - it.timeMs < windowMs }.sumOf { it.requests }

    fun snapshot(nowMs: Long): BudgetSnapshot = BudgetSnapshot(
        cycles = cycles,
        lastCycleRequests = lastCycleRequests,
        lastCycleBytes = lastCycleBytes,
        lastHourRequests = lastHourRequests(nowMs),
        lastHourBytes = lastHourBytes(nowMs),
        totalRequests = totalRequests,
        totalBytes = totalBytes,
        requestsByProvider = LinkedHashMap(byProviderRequests),
        bytesByProvider = LinkedHashMap(byProviderBytes),
    )
}
