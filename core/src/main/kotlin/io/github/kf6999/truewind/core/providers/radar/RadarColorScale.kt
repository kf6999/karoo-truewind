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

/** Decoded pixels, row-major, `0xAARRGGBB` (Android `Bitmap.getPixels` order, non-premultiplied). */
class ArgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "pixel array does not match ${width}x$height" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
}

enum class PrecipKind { RAIN, SNOW }

/** One decoded radar pixel. */
data class RadarEcho(val dbz: Int, val kind: PrecipKind)

/**
 * Pixel colour -> reflectivity (dBZ) for RainViewer's **Universal Blue** scheme (scheme 2, the only
 * one served since 2026-01-01).
 *
 * Source: RainViewer's published colour table
 * `https://www.rainviewer.com/files/rainviewer_api_colors_table.csv` (downloaded 2026-11-15; a copy
 * is a test fixture and a unit test checks these arrays against it). The CSV has one row per dBZ from
 * -32 to 95, first the rain ramp then the snow ramp, colours as `#RRGGBBAA`.
 *
 * Every non-transparent pixel of live tiles (smooth 0 and 1, snow 0 and 1) matched a table entry
 * exactly, so decoding is an exact lookup; the nearest-colour fallback only guards against
 * resampling by the image pipeline. Where several dBZ share a colour (>= 65 dBZ), the lowest is
 * reported. Values below [MIN_MEANINGFUL_DBZ] are semi-transparent clutter/drizzle shading.
 */
object RadarColorScale {
    const val MIN_DBZ = -32
    const val MIN_MEANINGFUL_DBZ = 5

    /** Universal Blue rain ramp, index = dBZ + 32, `#RRGGBBAA` as in the CSV. */
    internal val UNIVERSAL_BLUE_RAIN_RGBA: List<String> = (
        "00000000 ".repeat(22) +
            "63615914 66635a19 69665c1e 6c685d24 6f6b5f29 726e612e 75706234 78736439 7c75653e 7f786744 " +
            "827b6949 857d6a4e 88806c54 8b826d59 8e856f5e 92887164 9e93756e aa9e7978 b6a97e82 c2b4828c " +
            "cec08796 d2c48ba0 d6c88faa dacc93b4 ded097be 88ddeeff 6cd1ebff 51c5e8ff 36bae5ff 1baee2ff " +
            "00a3e0ff 009ad5ff 0091caff 0088bfff 007fb4ff 0077aaff 0070a3ff 00699cff 006295ff 005b8eff " +
            "005588ff 005180ff 004e78ff 004a70ff 004768ff ffee00ff ffe000ff ffd200ff ffc500ff ffb700ff " +
            "ffaa00ff ff9f00ff ff9500ff ff8b00ff ff8100ff ff4400ff f23600ff e62800ff d91b00ff cd0d00ff " +
            "c10000ff a80000ff 8f0000ff 760000ff 5d0000ff ffaaffff ff9fffff ff95ffff ff8bffff ff81ffff " +
            "ff77ffff ff6cffff ff62ffff ff58ffff ff4effff " + "ffffffff ".repeat(10) + "00ff00ff ".repeat(21)
        ).trim().split(' ')

    /** Universal Blue snow ramp, index = dBZ + 32. */
    internal val UNIVERSAL_BLUE_SNOW_RGBA: List<String> = (
        "00000000 ".repeat(22) +
            "cfffff00 ceffff0c cdffff19 ccffff26 cbffff33 cbffff3f caffff4c c9ffff59 c8ffff66 c7ffff72 " +
            "c7ffff7f c6ffff8c c5ffff99 c4ffffa5 c3ffffb2 c3ffffbf c2ffffcc c1ffffd8 c0ffffe5 bffffff2 " +
            "bfffffff b8f8ffff b2f2ffff abebffff a5e5ffff 9fdfffff 98d8ffff 92d2ffff 8bcbffff 85c5ffff " +
            "7fbfffff 78b8ffff 72b2ffff 6babffff 65a5ffff 5f9fffff 5b9bffff 5898ffff 5595ffff 5292ffff " +
            "4f8fffff 4b8bffff 4888ffff 4585ffff 4282ffff 3f7fffff 3b7bffff 3878ffff 3575ffff 3272ffff " +
            "2f6fffff 2b6bffff 2868ffff 2565ffff 2262ffff 1f5fffff 1b5bffff 1858ffff 1555ffff 1252ffff " +
            "0f4fffff 0c4bffff 0948ffff 0645ffff 0242ffff 003fffff 003bffff 0038ffff 0035ffff 0032ffff " +
            "002fffff 002bffff 0028ffff 0025ffff 0022ffff 001fffff 001bffff 0018ffff 0015ffff 0012ffff " +
            "000fffff 000cffff 0009ffff 0006ffff 0002ffff " + "0000ffff ".repeat(21)
        ).trim().split(' ')

    private fun rgbaToArgb(hex: String): Int {
        val v = hex.toLong(16)
        return (((v and 0xff) shl 24) or (v ushr 8)).toInt()
    }

    private class Entry(val argb: Int, val echo: RadarEcho)

    private val entries: List<Entry> = buildList {
        UNIVERSAL_BLUE_RAIN_RGBA.forEachIndexed { i, c -> add(Entry(rgbaToArgb(c), RadarEcho(i + MIN_DBZ, PrecipKind.RAIN))) }
        UNIVERSAL_BLUE_SNOW_RGBA.forEachIndexed { i, c -> add(Entry(rgbaToArgb(c), RadarEcho(i + MIN_DBZ, PrecipKind.SNOW))) }
    }.filter { (it.argb ushr 24) != 0 }

    /** Exact lookup; first (lowest dBZ, rain before snow) wins for duplicated colours. */
    private val exact: Map<Int, RadarEcho> = HashMap<Int, RadarEcho>().also { m -> entries.forEach { m.putIfAbsent(it.argb, it.echo) } }

    /** ARGB colour used for [dbz] (rain ramp), for legends and synthetic test images. */
    fun colorFor(dbz: Int, kind: PrecipKind = PrecipKind.RAIN): Int {
        val table = if (kind == PrecipKind.RAIN) UNIVERSAL_BLUE_RAIN_RGBA else UNIVERSAL_BLUE_SNOW_RGBA
        return rgbaToArgb(table[(dbz - MIN_DBZ).coerceIn(0, table.size - 1)])
    }

    /**
     * Decodes one pixel. Transparent pixels (alpha 0) are "no echo" (null). Unknown colours are
     * matched to the nearest table colour if within [maxDistance] (sum of squared ARGB channel
     * differences), else null.
     */
    fun decode(argb: Int, maxDistance: Int = 3 * 12 * 12): RadarEcho? {
        if ((argb ushr 24) == 0) return null
        exact[argb]?.let { return it }
        var best: Entry? = null
        var bestD = Int.MAX_VALUE
        for (e in entries) {
            val d = dist(argb, e.argb)
            if (d < bestD) { bestD = d; best = e }
        }
        return if (bestD <= maxDistance) best?.echo else null
    }

    /** dBZ or null; convenience for [decode]. */
    fun dbz(argb: Int): Int? = decode(argb)?.dbz

    private fun dist(a: Int, b: Int): Int {
        var s = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val d = ((a ushr shift) and 0xff) - ((b ushr shift) and 0xff)
            s += d * d
        }
        return s
    }

    /**
     * Rain rate from reflectivity, Marshall–Palmer Z = 200 R^1.6, mm/h. Indicative only (convective
     * rain fits Z = 300 R^1.4 better). 20 dBZ ~ 0.6 mm/h, 35 dBZ ~ 5.6 mm/h, 45 dBZ ~ 24 mm/h.
     */
    fun rainRateMmH(dbz: Double): Double = Math.pow(Math.pow(10.0, dbz / 10.0) / 200.0, 1.0 / 1.6)
}

/**
 * A reflectivity grid in source pixels of one zoom level, assembled from decoded tiles.
 * Cells are NaN where there is no echo or no tile. [originPx] is the global pixel of cell (0, 0).
 */
class DbzGrid(
    val zoom: Int,
    val tileSize: Int,
    val originPx: PixelPoint,
    val width: Int,
    val height: Int,
    val dbz: FloatArray,
    /** Frame valid time, unix ms (age checks in storm estimates). */
    val frameTimeMs: Long,
) {
    init { require(dbz.size == width * height) }

    operator fun get(x: Int, y: Int): Float = dbz[y * width + x]

    /** Geographic centre of cell (x, y). */
    fun cellCenter(x: Int, y: Int) = TileMath.fromPixel(PixelPoint(originPx.x + x + 0.5, originPx.y + y + 0.5), zoom, tileSize)

    companion object {
        /**
         * Mosaics decoded tiles into one grid covering their union. Missing tiles leave NaN.
         * Tiles must share a zoom level; at most a 2 x 2 block (one [TilePlan]) is expected.
         */
        fun fromTiles(tiles: Map<TileId, ArgbImage>, frameTimeMs: Long, tileSize: Int = TileMath.DEFAULT_TILE_SIZE): DbzGrid? {
            if (tiles.isEmpty()) return null
            val z = tiles.keys.first().z
            require(tiles.keys.all { it.z == z }) { "tiles must share a zoom" }
            val n = 1 shl z
            // Unwrap x across the antimeridian: tiles x=0 and x=n-1 together are adjacent.
            val xs = tiles.keys.map { it.x }.distinct().sorted()
            val wraps = xs.first() == 0 && xs.last() == n - 1 && n > 2
            fun ux(x: Int) = if (wraps && x < n / 2) x + n else x
            val minX = tiles.keys.minOf { ux(it.x) }
            val maxX = tiles.keys.maxOf { ux(it.x) }
            val minY = tiles.keys.minOf { it.y }
            val maxY = tiles.keys.maxOf { it.y }
            val w = (maxX - minX + 1) * tileSize
            val h = (maxY - minY + 1) * tileSize
            val grid = FloatArray(w * h) { Float.NaN }
            for ((t, img) in tiles) {
                require(img.width == tileSize && img.height == tileSize) { "tile $t is ${img.width}x${img.height}, expected $tileSize" }
                val ox = (ux(t.x) - minX) * tileSize
                val oy = (t.y - minY) * tileSize
                for (y in 0 until tileSize) {
                    val row = (oy + y) * w + ox
                    for (x in 0 until tileSize) {
                        val e = RadarColorScale.decode(img.pixels[y * tileSize + x])
                        if (e != null) grid[row + x] = e.dbz.toFloat()
                    }
                }
            }
            return DbzGrid(z, tileSize, PixelPoint(minX.toDouble() * tileSize, minY.toDouble() * tileSize), w, h, grid, frameTimeMs)
        }
    }
}
