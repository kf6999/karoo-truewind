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

import io.github.kf6999.truewind.core.api.HttpClient
import io.github.kf6999.truewind.core.api.HttpRequest
import io.github.kf6999.truewind.core.api.HttpResponse
import io.github.kf6999.truewind.core.providers.obs.arr
import io.github.kf6999.truewind.core.providers.obs.field
import io.github.kf6999.truewind.core.providers.obs.lng
import io.github.kf6999.truewind.core.providers.obs.obj
import io.github.kf6999.truewind.core.providers.obs.parseJsonOrNull
import io.github.kf6999.truewind.core.providers.obs.str

/** One radar frame (a past composite, 10 minutes apart). */
data class RadarFrame(
    /** Valid time, unix ms. */
    val timeMs: Long,
    /** e.g. `/v2/radar/41fb7dcea963`; the unique key of the frame. */
    val path: String,
)

/** Parsed `weather-maps.json`. */
data class RadarIndex(
    val generatedMs: Long,
    val host: String,
    /** Oldest first. */
    val past: List<RadarFrame>,
    /** Always empty on the free API since 2026-01-01; kept in case it returns. */
    val nowcast: List<RadarFrame> = emptyList(),
) {
    val latest: RadarFrame? get() = past.lastOrNull()
}

/**
 * RainViewer public Weather Maps API (keyless), WP13.
 *
 * Live findings, 2026-11-15:
 * - `GET https://api.rainviewer.com/public/weather-maps.json` (818 bytes): `host`
 *   `https://tilecache.rainviewer.com`, 13 past frames 10 min apart (~2 h), `nowcast` and
 *   `satellite.infrared` empty.
 * - Tiles: `{host}{path}/{256|512}/{z}/{x}/{y}/{color}/{smooth}_{snow}.png`, RGBA PNG.
 *   **The colour parameter is ignored**: colour 0, 2 and 4 return byte-identical Universal Blue
 *   tiles (RainViewer's transition FAQ: only Universal Blue since 2026-01-01). We request `2`.
 *   `smooth` and `snow` are honoured. With `snow=1` snow pixels use a separate colour ramp.
 * - **Max zoom 7.** Zoom 8, 9, 10 answer HTTP 200 with a 1,370-byte grey "Zoom Level Not
 *   Supported" image; never request them (see [TilePlanner]).
 * - Sizes: empty 256 px tile 334 B; a z6 tile over the US Midwest 8.9 KB; a z7 tile with
 *   thunderstorms over Dallas 39.8 KB (1_1) / 44.5 KB (0_0). Four tiles stay under 100 KB except
 *   in widespread convection.
 * - Response headers: `x-ratelimit-limit: 500`, `x-ratelimit-window: 60`,
 *   `x-ratelimit-burst-limit: 300` (per IP); the transition FAQ states 100 requests/IP/minute.
 *   Tiles carry `cache-control: max-age=172800`; the index is `no-cache`.
 * - Terms: free API for personal and educational use; show "RainViewer" attribution with a link
 *   (rainviewer.com/api.html, transition FAQ). Not for commercial use without an agreement.
 */
object RainViewer {
    const val INDEX_URL = "https://api.rainviewer.com/public/weather-maps.json"
    const val DEFAULT_HOST = "https://tilecache.rainviewer.com"
    const val MAX_ZOOM = 7

    /** Universal Blue, the only scheme served since 2026-01-01. */
    const val COLOR_SCHEME = 2
    const val ATTRIBUTION = "Radar: RainViewer"
    const val ATTRIBUTION_URL = "https://www.rainviewer.com"

    /** Frame cadence. */
    const val FRAME_INTERVAL_MS = 10 * 60_000L

    fun tileUrl(
        host: String,
        frame: RadarFrame,
        tile: TileId,
        size: Int = TileMath.DEFAULT_TILE_SIZE,
        smooth: Boolean = true,
        snow: Boolean = true,
    ): String {
        require(tile.z in 0..MAX_ZOOM) { "RainViewer serves zoom 0..$MAX_ZOOM, got ${tile.z}" }
        require(size == 256 || size == 512) { "tile size must be 256 or 512" }
        val s = if (smooth) 1 else 0
        val sn = if (snow) 1 else 0
        return "${host.trimEnd('/')}${frame.path}/$size/${tile.z}/${tile.x}/${tile.y}/$COLOR_SCHEME/${s}_$sn.png"
    }

    /** Parses `weather-maps.json`; null when the body is not a usable index. Never throws. */
    fun parseIndex(body: ByteArray?): RadarIndex? {
        val root = parseJsonOrNull(body).obj() ?: return null
        val radar = root.field("radar").obj() ?: return null
        fun frames(name: String): List<RadarFrame> =
            radar.field(name).arr().orEmpty().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                val t = o.field("time").lng() ?: return@mapNotNull null
                val p = o.field("path").str()?.takeIf { it.startsWith("/") } ?: return@mapNotNull null
                RadarFrame(t * 1000, p)
            }.sortedBy { it.timeMs }
        val past = frames("past")
        if (past.isEmpty()) return null
        return RadarIndex(
            generatedMs = (root.field("generated").lng() ?: 0L) * 1000,
            host = root.field("host").str()?.takeIf { it.startsWith("https://") } ?: DEFAULT_HOST,
            past = past,
            nowcast = frames("nowcast"),
        )
    }
}

/** Decodes PNG bytes to pixels. Implemented by the app with `BitmapFactory`; tests use `ImageIO`. */
fun interface PngDecoder {
    /** Null if the bytes are not a decodable image. */
    fun decode(bytes: ByteArray): ArgbImage?
}

/** Result of one radar refresh, with counters for diagnostics (TESTING 5.1). */
data class RadarFetchResult(
    val index: RadarIndex?,
    val frame: RadarFrame?,
    /** Raw PNG bytes per tile; the app decodes for drawing and for [DbzGrid]. */
    val tiles: Map<TileId, ByteArray>,
    val requests: Int,
    val bytesReceived: Long,
    val error: String? = null,
)

/** Fetches the index and the newest frame's tiles for a plan. Network only; caching is [RadarFramePolicy]. */
class RainViewerClient(
    private val http: HttpClient,
    private val indexUrl: String = RainViewer.INDEX_URL,
) {
    suspend fun fetchIndex(): Pair<RadarIndex?, HttpResponse?> {
        val r = get(indexUrl) ?: return null to null
        return (if (r.isSuccessful) RainViewer.parseIndex(r.body) else null) to r
    }

    /**
     * Fetches the index (unless [knownIndex] is given) and the tiles in [tiles] (at most
     * [TilePlanner.MAX_TILES]) for the newest frame.
     */
    suspend fun fetchLatest(tiles: List<TileId>, knownIndex: RadarIndex? = null): RadarFetchResult {
        require(tiles.size <= TilePlanner.MAX_TILES) { "at most ${TilePlanner.MAX_TILES} tiles per refresh" }
        var requests = 0
        var bytes = 0L
        val index = knownIndex ?: run {
            requests++
            val (idx, resp) = fetchIndex()
            bytes += resp?.body?.size ?: 0
            idx ?: return RadarFetchResult(null, null, emptyMap(), requests, bytes, resp?.let { it.error ?: "HTTP ${it.statusCode}" } ?: "transport error")
        }
        val frame = index.latest ?: return RadarFetchResult(index, null, emptyMap(), requests, bytes, "no frames")
        val out = LinkedHashMap<TileId, ByteArray>()
        var error: String? = null
        for (t in tiles) {
            requests++
            val r = get(RainViewer.tileUrl(index.host, frame, t))
            bytes += r?.body?.size ?: 0
            if (r != null && r.isSuccessful && r.body != null) out[t] = r.body else error = r?.let { it.error ?: "HTTP ${it.statusCode}" } ?: "transport error"
        }
        return RadarFetchResult(index, frame, out, requests, bytes, error)
    }

    private suspend fun get(url: String): HttpResponse? = try {
        http.request(HttpRequest("GET", url))
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
