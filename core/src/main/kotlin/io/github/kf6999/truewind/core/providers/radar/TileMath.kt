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

import io.github.kf6999.truewind.core.model.GeoPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** A slippy-map (Web Mercator, XYZ / "Google") tile. */
data class TileId(val z: Int, val x: Int, val y: Int) {
    override fun toString(): String = "$z/$x/$y"
}

/** A position in global pixel space at some zoom: (0,0) is the top-left of tile 0/0/0, x grows east, y south. */
data class PixelPoint(val x: Double, val y: Double)

/**
 * Web Mercator tile maths (EPSG:3857 as used by every XYZ tile server, RainViewer included).
 * Latitudes are clamped to +-85.05112878 degrees.
 */
object TileMath {
    const val MAX_LATITUDE = 85.05112878
    const val DEFAULT_TILE_SIZE = 256

    /** WGS84 equatorial radius used by Web Mercator, m. */
    private const val EARTH_RADIUS_M = 6378137.0

    /** Global pixel position of [p] at zoom [z]. x is not wrapped (longitude 180 maps to the right edge). */
    fun toPixel(p: GeoPoint, z: Int, tileSize: Int = DEFAULT_TILE_SIZE): PixelPoint {
        val worldPx = tileSize.toDouble() * (1 shl z)
        val lat = Math.toRadians(p.lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))
        val x = (p.lon + 180.0) / 360.0 * worldPx
        val y = (1.0 - ln(tan(lat) + 1.0 / cos(lat)) / PI) / 2.0 * worldPx
        return PixelPoint(x, y)
    }

    /** Inverse of [toPixel]. x outside the world is wrapped into [-180, 180). */
    fun fromPixel(px: PixelPoint, z: Int, tileSize: Int = DEFAULT_TILE_SIZE): GeoPoint {
        val worldPx = tileSize.toDouble() * (1 shl z)
        var lon = px.x / worldPx * 360.0 - 180.0
        lon = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val n = PI * (1.0 - 2.0 * px.y / worldPx)
        val lat = Math.toDegrees(atan(sinh(n)))
        return GeoPoint(lat, lon)
    }

    /** The tile containing [p] at zoom [z]. */
    fun tileFor(p: GeoPoint, z: Int, tileSize: Int = DEFAULT_TILE_SIZE): TileId {
        val px = toPixel(p, z, tileSize)
        val n = 1 shl z
        val x = floor(px.x / tileSize).toInt().let { ((it % n) + n) % n }
        val y = floor(px.y / tileSize).toInt().coerceIn(0, n - 1)
        return TileId(z, x, y)
    }

    /** Pixel of [p] inside its own tile, in [0, tileSize). */
    fun pixelInTile(p: GeoPoint, z: Int, tileSize: Int = DEFAULT_TILE_SIZE): PixelPoint {
        val px = toPixel(p, z, tileSize)
        return PixelPoint(px.x - floor(px.x / tileSize) * tileSize, px.y - floor(px.y / tileSize) * tileSize)
    }

    /** Ground resolution at latitude [lat] and zoom [z], metres per (source) pixel. */
    fun metresPerPixel(lat: Double, z: Int, tileSize: Int = DEFAULT_TILE_SIZE): Double =
        cos(Math.toRadians(lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))) * 2.0 * PI * EARTH_RADIUS_M / (tileSize.toDouble() * (1 shl z))

    /** Geographic bounds of a tile: (north-west corner, south-east corner). */
    fun tileBounds(t: TileId, tileSize: Int = DEFAULT_TILE_SIZE): Pair<GeoPoint, GeoPoint> {
        val nw = fromPixel(PixelPoint(t.x.toDouble() * tileSize, t.y.toDouble() * tileSize), t.z, tileSize)
        val se = fromPixel(PixelPoint((t.x + 1.0) * tileSize - 1e-9, (t.y + 1.0) * tileSize), t.z, tileSize)
        return nw to se
    }

    internal fun tilesSpanned(minPx: Double, maxPx: Double, tileSize: Int): IntRange =
        floor(minPx / tileSize).toInt()..(ceil(maxPx / tileSize).toInt() - 1)
}

/** One tile to download and where to draw it in the view. */
data class PlannedTile(
    val tile: TileId,
    /** Top-left corner of the tile in view pixels (may be negative: the tile is clipped). */
    val viewX: Double,
    val viewY: Double,
    /** Drawn edge length in view pixels (= tileSize x [TilePlan.scale]). */
    val viewSize: Double,
)

/**
 * What to fetch and how to draw it for a radar view of [viewWidth] x [viewHeight] pixels centred
 * on the rider.
 *
 * RainViewer serves zoom 0..7 only (zoom 8+ answers HTTP 200 with a "Zoom Level Not Supported"
 * image, verified 2026-11-15), so a display zoom above [RainViewer.MAX_ZOOM] is drawn by upscaling
 * source tiles: [sourceZoom] < [displayZoom] and [scale] = 2^(display - source).
 */
data class TilePlan(
    val displayZoom: Int,
    val sourceZoom: Int,
    /** View pixels per source pixel. */
    val scale: Double,
    val viewWidth: Int,
    val viewHeight: Int,
    val tileSize: Int,
    /** Global source-pixel position of the view's top-left corner at [sourceZoom]. */
    val originPx: PixelPoint,
    val tiles: List<PlannedTile>,
    /** Rider position in view pixels (the view centre). */
    val riderViewX: Double,
    val riderViewY: Double,
    /** Ground resolution at the rider, metres per view pixel. */
    val metresPerViewPixel: Double,
) {
    /** Geographic position of a view pixel. */
    fun viewToGeo(vx: Double, vy: Double): GeoPoint =
        TileMath.fromPixel(PixelPoint(originPx.x + vx / scale, originPx.y + vy / scale), sourceZoom, tileSize)

    /** View pixel of a geographic position (for drawing fire outlines, station markers). */
    fun geoToView(p: GeoPoint): PixelPoint {
        val g = TileMath.toPixel(p, sourceZoom, tileSize)
        var dx = g.x - originPx.x
        val world = tileSize.toDouble() * (1 shl sourceZoom)
        // Pick the copy of the world nearest to the view (antimeridian).
        if (dx > world / 2) dx -= world else if (dx < -world / 2) dx += world
        return PixelPoint(dx * scale, (g.y - originPx.y) * scale)
    }
}

object TilePlanner {
    /** Hard cap from SPEC 9.2 (WP13): at most 4 tiles (<= 100 KB) per refresh. */
    const val MAX_TILES = 4

    /**
     * Plans the 1–4 tiles covering a [viewWidth] x [viewHeight] view centred on [rider] at
     * [displayZoom]. The view must span at most one tile edge of source pixels in each axis
     * (`view / scale <= tileSize`), which guarantees at most 2 x 2 tiles; larger views are rejected
     * so the caller picks a higher display zoom instead of silently fetching more.
     *
     * Tiles north of 85 degrees / south of -85 degrees are omitted; x wraps across the antimeridian.
     */
    fun plan(
        rider: GeoPoint,
        displayZoom: Int,
        viewWidth: Int,
        viewHeight: Int,
        maxSourceZoom: Int = RainViewer.MAX_ZOOM,
        tileSize: Int = TileMath.DEFAULT_TILE_SIZE,
    ): TilePlan {
        require(displayZoom >= 0) { "zoom must be >= 0" }
        require(viewWidth > 0 && viewHeight > 0) { "view must be non-empty" }
        val sourceZoom = minOf(displayZoom, maxSourceZoom)
        val scale = (1 shl (displayZoom - sourceZoom)).toDouble()
        val spanX = viewWidth / scale
        val spanY = viewHeight / scale
        require(spanX <= tileSize && spanY <= tileSize) {
            "view ${viewWidth}x$viewHeight at zoom $displayZoom needs more than $MAX_TILES tiles"
        }
        val c = TileMath.toPixel(rider, sourceZoom, tileSize)
        val origin = PixelPoint(c.x - spanX / 2, c.y - spanY / 2)
        val n = 1 shl sourceZoom
        val tiles = mutableListOf<PlannedTile>()
        for (ty in TileMath.tilesSpanned(origin.y, origin.y + spanY, tileSize)) {
            if (ty < 0 || ty >= n) continue
            for (tx in TileMath.tilesSpanned(origin.x, origin.x + spanX, tileSize)) {
                val wrapped = ((tx % n) + n) % n
                tiles += PlannedTile(
                    tile = TileId(sourceZoom, wrapped, ty),
                    viewX = (tx * tileSize - origin.x) * scale,
                    viewY = (ty * tileSize - origin.y) * scale,
                    viewSize = tileSize * scale,
                )
            }
        }
        check(tiles.size <= MAX_TILES)
        return TilePlan(
            displayZoom = displayZoom,
            sourceZoom = sourceZoom,
            scale = scale,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            tileSize = tileSize,
            originPx = origin,
            tiles = tiles,
            riderViewX = viewWidth / 2.0,
            riderViewY = viewHeight / 2.0,
            metresPerViewPixel = TileMath.metresPerPixel(rider.lat, sourceZoom, tileSize) / scale,
        )
    }
}
