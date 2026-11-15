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

import io.github.kf6999.truewind.core.providers.obs.fixture
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** JVM stand-in for the app's `BitmapFactory`-based decoder. */
val ImageIoPngDecoder = PngDecoder { bytes ->
    ImageIO.read(ByteArrayInputStream(bytes))?.let { img ->
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        ArgbImage(img.width, img.height, px)
    }
}

/** Real RainViewer tile 7/29/51 (Dallas–Fort Worth), frame 1794738000, `2/1_1`, 2026-11-15. */
const val DALLAS_TILE_PATH = "providers/radar/rainviewer_tile_7_29_51_c2_s1_1.png"
val DALLAS_TILE = TileId(7, 29, 51)
const val DALLAS_FRAME_MS = 1794738000_000L

fun dallasTile(): ArgbImage = requireNotNull(ImageIoPngDecoder.decode(fixture(DALLAS_TILE_PATH)))

/** Synthetic tile: all transparent except the given squares painted with the rain colour of a dBZ. */
fun syntheticTile(vararg blocks: Block, size: Int = 256): ArgbImage {
    val px = IntArray(size * size)
    for (b in blocks) {
        val c = RadarColorScale.colorFor(b.dbz)
        for (y in b.y until b.y + b.h) for (x in b.x until b.x + b.w) px[y * size + x] = c
    }
    return ArgbImage(size, size, px)
}

data class Block(val x: Int, val y: Int, val w: Int, val h: Int, val dbz: Int)
