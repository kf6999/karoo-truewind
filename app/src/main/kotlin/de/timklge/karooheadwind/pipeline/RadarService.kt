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

import io.github.kf6999.truewind.core.alerts.storm.RadarCore
import io.github.kf6999.truewind.core.alerts.storm.RadarCoreFinder
import io.github.kf6999.truewind.core.api.HttpClient
import io.github.kf6999.truewind.core.api.HttpRequest
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.providers.radar.ArgbImage
import io.github.kf6999.truewind.core.providers.radar.DbzGrid
import io.github.kf6999.truewind.core.providers.radar.PngDecoder
import io.github.kf6999.truewind.core.providers.radar.RadarFramePolicy
import io.github.kf6999.truewind.core.providers.radar.RadarIndex
import io.github.kf6999.truewind.core.providers.radar.RainViewer
import io.github.kf6999.truewind.core.providers.radar.TileId
import io.github.kf6999.truewind.core.providers.radar.TilePlan
import io.github.kf6999.truewind.core.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Result of one radar refresh. */
data class RadarRefresh(val ok: Boolean, val requests: Int, val bytes: Long, val tilesFetched: Int, val tilesSkippedForBudget: Int, val error: String? = null)

/**
 * RainViewer access for the radar field and the storm estimate (WP13/14). All traffic goes through
 * [HttpClient] (KarooHttp: counted in [Diagnostics]). Tiles are kept as PNG bytes in the caller's
 * [RadarFramePolicy] (a few KB each); bitmaps are only decoded when drawing.
 *
 * The index is shared for [indexTtlMs] between callers so the radar field and the storm estimate
 * do not both fetch it. The newest grid any caller fetched also yields the nearest >= 45 dBZ core
 * ([latestCore]) so the storm estimate can reuse the field's tiles.
 */
class RadarService(
    private val http: HttpClient,
    private val decoder: PngDecoder,
    private val clock: Clock = Clock.SYSTEM,
    private val indexTtlMs: Long = 60_000L,
    val budgetBytes: Long = RadarBudget.DEFAULT_BUDGET_BYTES,
) {
    private val mutex = Mutex()
    private var index: Pair<Long, RadarIndex>? = null
    private val coreFinder = RadarCoreFinder()

    /** Mean bytes per tile of the previous refresh (budget prediction). */
    @Volatile var lastMeanTileBytes: Long? = null
        private set
    @Volatile var latestFrameTimeMs: Long? = null
        private set

    /** (frame time, nearest core within 100 km or null, computed at). */
    val latestCore = MutableStateFlow<Triple<Long, RadarCore?, Long>?>(null)

    val refreshes = AtomicLong()
    val requests = AtomicLong()
    val bytes = AtomicLong()
    val budgetSkips = AtomicLong()
    @Volatile var lastRefresh: RadarRefresh? = null
        private set

    private suspend fun currentIndex(force: Boolean): Pair<RadarIndex?, Pair<Int, Long>> {
        val now = clock.nowMs()
        index?.let { (t, idx) -> if (!force && now - t < indexTtlMs) return idx to (0 to 0L) }
        val r = get(RainViewer.INDEX_URL)
        val idx = if (r != null && r.isSuccessful) RainViewer.parseIndex(r.body) else null
        if (idx != null) index = now to idx
        return idx to (1 to (r?.body?.size ?: 0).toLong())
    }

    /**
     * Refreshes [policy] for [plan]: newest frame, only the missing tiles, at most
     * [budgetBytes] (predicted from the last refresh). [computeCoreAt]: also decode the frame and
     * update [latestCore] (only when the storm estimate needs it).
     */
    suspend fun refresh(plan: TilePlan, policy: RadarFramePolicy<ByteArray>, computeCoreAt: GeoPoint? = null): RadarRefresh = mutex.withLock {
        policy.setTiles(plan.tiles.map { it.tile })
        policy.onAttempt()
        refreshes.incrementAndGet()
        val (idx, cost) = currentIndex(force = false)
        var req = cost.first
        var b = cost.second
        val frame = idx?.latest
        if (frame == null) {
            policy.onFailure()
            return@withLock RadarRefresh(false, req, b, 0, 0, "no radar index").also { record(it) }
        }
        val missing = policy.tilesToFetch(frame)
        val chosen = RadarBudget.select(plan, missing, lastMeanTileBytes, budgetBytes)
        val skipped = missing.size - chosen.size
        if (skipped > 0) budgetSkips.incrementAndGet()
        val got = LinkedHashMap<TileId, ByteArray>()
        var error: String? = null
        for (t in chosen) {
            val r = get(RainViewer.tileUrl(idx.host, frame, t))
            req++
            b += (r?.body?.size ?: 0)
            val body = r?.body
            if (r != null && r.isSuccessful && body != null) got[t] = body else error = r?.let { it.error ?: "HTTP ${it.statusCode}" } ?: "transport error"
        }
        if (got.isNotEmpty()) lastMeanTileBytes = got.values.sumOf { it.size.toLong() } / got.size
        if (got.isEmpty() && chosen.isNotEmpty()) {
            policy.onFailure()
        } else {
            policy.onSuccess(frame, got)
            latestFrameTimeMs = frame.timeMs
        }
        if (computeCoreAt != null) {
            policy.latestFrame?.let { cf -> updateCore(cf.tiles, cf.frame.timeMs, computeCoreAt) }
        }
        RadarRefresh(got.isNotEmpty() || chosen.isEmpty(), req, b, got.size, skipped, error).also { record(it) }
    }

    private fun record(r: RadarRefresh) {
        requests.addAndGet(r.requests.toLong())
        bytes.addAndGet(r.bytes)
        lastRefresh = r
    }

    /** Decodes [tiles] into a reflectivity grid and stores the nearest core to [rider]. */
    fun updateCore(tiles: Map<TileId, ByteArray>, frameTimeMs: Long, rider: GeoPoint) {
        val decoded = LinkedHashMap<TileId, ArgbImage>()
        for ((t, png) in tiles) decoder.decode(png)?.let { if (it.width == 256 && it.height == 256) decoded[t] = it }
        val grid = try { DbzGrid.fromTiles(decoded, frameTimeMs) } catch (e: IllegalArgumentException) { null }
        val core = grid?.let { coreFinder.nearestCore(it, rider, maxDistanceKm = 100.0) }
        latestCore.value = Triple(frameTimeMs, core, clock.nowMs())
    }

    private suspend fun get(url: String) = try {
        http.request(HttpRequest("GET", url))
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
