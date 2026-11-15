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

package io.github.kf6999.truewind.core.providers.radar

import io.github.kf6999.truewind.core.time.Clock

/** A frame whose tiles for the current plan are held in memory. */
data class CachedFrame<T>(val frame: RadarFrame, val tiles: Map<TileId, T>)

/**
 * Pure, clock-injected refresh and cache policy for the radar field (WP13).
 *
 * - Refresh every [refreshIntervalMs] (10 min, matching RainViewer's frame cadence), only while the
 *   field is visible: [shouldRefresh] is false when hidden, so nothing is fetched in the background.
 * - Each refresh downloads only the newest frame's tiles (<= 4, [TilePlanner.MAX_TILES]); the last
 *   [keepFrames] (3) frames build up over successive refreshes and are what the field animates.
 * - A different tile set (zoom tap, rider moved off the planned tiles) invalidates the cache and
 *   allows an immediate refresh, rate limited by [minIntervalMs].
 * - After a failure the next attempt waits [retryAfterFailureMs].
 *
 * `T` is whatever the app keeps per tile (a `Bitmap`, decoded [ArgbImage], or PNG bytes).
 * Not thread-safe: call from one coroutine.
 */
class RadarFramePolicy<T>(
    private val clock: Clock,
    val refreshIntervalMs: Long = 10 * 60_000L,
    val keepFrames: Int = 3,
    val minIntervalMs: Long = 30_000L,
    val retryAfterFailureMs: Long = 2 * 60_000L,
) {
    private var frames = ArrayList<CachedFrame<T>>()
    private var tileSet: Set<TileId> = emptySet()
    private var lastAttemptMs: Long? = null
    private var lastSuccessMs: Long? = null
    private var lastFailed = false

    /** Frames to animate, oldest first (1..[keepFrames]). */
    val cachedFrames: List<CachedFrame<T>> get() = frames.toList()
    val latestFrame: CachedFrame<T>? get() = frames.lastOrNull()
    val lastSuccessTimeMs: Long? get() = lastSuccessMs

    /** Informs the policy which tiles the current [TilePlan] needs. Returns true if the cache was invalidated. */
    fun setTiles(tiles: Collection<TileId>): Boolean {
        val s = tiles.toSet()
        if (s == tileSet) return false
        tileSet = s
        frames.clear()
        lastSuccessMs = null
        return true
    }

    val currentTiles: Set<TileId> get() = tileSet

    /** Whether to run a fetch now. Never while [visible] is false. */
    fun shouldRefresh(visible: Boolean): Boolean {
        if (!visible || tileSet.isEmpty()) return false
        val now = clock.nowMs()
        lastAttemptMs?.let { if (now - it < minIntervalMs) return false }
        if (lastFailed) return now - (lastAttemptMs ?: Long.MIN_VALUE / 2) >= retryAfterFailureMs
        val ok = lastSuccessMs ?: return true
        return now - ok >= refreshIntervalMs
    }

    /** Milliseconds until [shouldRefresh] could become true while visible (0 = now); for scheduling a delay. */
    fun msUntilNextRefresh(): Long {
        val now = clock.nowMs()
        val due = when {
            lastFailed -> (lastAttemptMs ?: now) + retryAfterFailureMs
            lastSuccessMs == null -> (lastAttemptMs?.plus(minIntervalMs)) ?: now
            else -> lastSuccessMs!! + refreshIntervalMs
        }
        return (due - now).coerceAtLeast(0)
    }

    /** Tiles still missing for [frame] (all of them for a new frame); empty when already cached. */
    fun tilesToFetch(frame: RadarFrame): List<TileId> {
        val cached = frames.firstOrNull { it.frame.path == frame.path } ?: return tileSet.sortedWith(TILE_ORDER)
        return tileSet.filter { it !in cached.tiles }.sortedWith(TILE_ORDER)
    }

    fun onAttempt() { lastAttemptMs = clock.nowMs() }

    /**
     * Records a successful refresh. Tiles for an already cached frame are merged. Frames older than
     * the newest [keepFrames] are evicted; tiles outside the current tile set are dropped.
     */
    fun onSuccess(frame: RadarFrame, tiles: Map<TileId, T>) {
        val now = clock.nowMs()
        lastAttemptMs = lastAttemptMs ?: now
        lastSuccessMs = now
        lastFailed = false
        val kept = tiles.filterKeys { it in tileSet }
        val i = frames.indexOfFirst { it.frame.path == frame.path }
        if (i >= 0) frames[i] = CachedFrame(frame, frames[i].tiles + kept) else frames.add(CachedFrame(frame, kept))
        frames.sortBy { it.frame.timeMs }
        while (frames.size > keepFrames) frames.removeAt(0)
    }

    fun onFailure() {
        lastAttemptMs = clock.nowMs()
        lastFailed = true
    }

    /** Age of the newest cached frame relative to now, ms; null if none. */
    fun latestFrameAgeMs(): Long? = latestFrame?.let { clock.nowMs() - it.frame.timeMs }

    private companion object {
        val TILE_ORDER = compareBy<TileId>({ it.y }, { it.x })
    }
}
