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

package io.github.kf6999.truewind.core.fusion

import io.github.kf6999.truewind.core.math.KM_PER_DEGREE
import io.github.kf6999.truewind.core.model.GeoPoint
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.floor

/**
 * Small string key-value persistence. The app backs it with DataStore Preferences; tests use
 * [InMemoryKeyValueStore]. Called only from the fetch cycle (never from the tick path), at most
 * one read and one write per cycle.
 */
interface KeyValueStore {
    suspend fun get(key: String): String?

    suspend fun put(key: String, value: String)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val map = ConcurrentHashMap<String, String>()

    override suspend fun get(key: String): String? = map[key]

    override suspend fun put(key: String, value: String) {
        map[key] = value
    }

    /** Snapshot for assertions. */
    fun entries(): Map<String, String> = HashMap(map)
}

/** Persists [MemberWeights] per ~40 km cell (SPEC 4.3) under keys `truewind.weights.<cell>`. */
class WeightStore(private val store: KeyValueStore, private val cellKm: Double = 40.0) {
    fun cellKey(p: GeoPoint): String {
        val deg = cellKm / KM_PER_DEGREE
        val latIdx = floor(p.lat / deg).toInt()
        // Longitude cells widen with latitude; use the latitude band's centre so a cell keeps its key.
        val bandLat = (latIdx + 0.5) * deg
        val lonDeg = deg / cos(Math.toRadians(bandLat)).coerceAtLeast(0.05)
        val lonIdx = floor(p.lon / lonDeg).toInt()
        return "truewind.weights.${latIdx}_$lonIdx"
    }

    suspend fun load(p: GeoPoint): List<MemberStat> =
        store.get(cellKey(p))?.let { MemberWeights.decode(it) } ?: emptyList()

    suspend fun save(p: GeoPoint, stats: List<MemberStat>) {
        if (stats.isNotEmpty()) store.put(cellKey(p), MemberWeights.encode(stats))
    }
}
