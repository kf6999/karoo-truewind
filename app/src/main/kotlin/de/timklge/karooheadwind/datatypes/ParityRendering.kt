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

package de.timklge.karooheadwind.datatypes

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import io.github.kf6999.truewind.core.alerts.RainBarModel
import io.github.kf6999.truewind.core.alerts.RainIntensity
import io.github.kf6999.truewind.core.providers.radar.ArgbImage
import io.github.kf6999.truewind.core.providers.radar.PngDecoder
import io.github.kf6999.truewind.core.providers.radar.RainViewer
import io.github.kf6999.truewind.core.providers.radar.TileId
import io.github.kf6999.truewind.core.providers.radar.TilePlan
import io.github.kf6999.truewind.core.providers.wildfire.FirePerimeter
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** `PngDecoder` on `BitmapFactory`: non-premultiplied pixels, so radar colours match the table exactly. */
object BitmapPngDecoder : PngDecoder {
    override fun decode(bytes: ByteArray): ArgbImage? {
        val opts = BitmapFactory.Options().apply { inPremultiplied = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return try {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            ArgbImage(bmp.width, bmp.height, px)
        } finally {
            bmp.recycle()
        }
    }
}

fun Context.isNightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** Bitmap size for a field of [viewW] x [viewH] pixels, longest side at most [maxPx] (memory / IPC size). */
fun bitmapSizeFor(viewW: Int, viewH: Int, maxPx: Int = 240): Pair<Int, Int> {
    val w = viewW.coerceAtLeast(1)
    val h = viewH.coerceAtLeast(1)
    val scale = minOf(1.0, maxPx.toDouble() / maxOf(w, h))
    return (w * scale).roundToInt().coerceAtLeast(16) to (h * scale).roundToInt().coerceAtLeast(16)
}

/** Overlay inputs drawn on top of a radar frame. */
data class RadarOverlay(
    val headingDeg: Double?,
    /** Direction the wind blows FROM, degrees; null without wind. */
    val windFromDeg: Double?,
    val windText: String?,
    val label: String,
    val fires: List<FirePerimeter> = emptyList(),
)

/** Draws the radar field (WP13): composited tiles, fire outlines, rider dot, heading tick, wind arrow, attribution. */
object RadarRenderer {
    /** One RGB_565 base image per frame: background plus the frame's tiles drawn at the plan's offsets. */
    fun composeBase(plan: TilePlan, tiles: Map<TileId, ByteArray>, dark: Boolean): Bitmap {
        val out = Bitmap.createBitmap(plan.viewWidth, plan.viewHeight, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        c.drawColor(if (dark) Color.rgb(24, 26, 30) else Color.rgb(232, 234, 228))
        // Faint 1-degree-ish grid so an empty radar still shows motion and scale.
        val grid = Paint().apply { color = if (dark) Color.rgb(44, 47, 52) else Color.rgb(212, 214, 208); strokeWidth = 1f }
        val step = (plan.viewWidth / 4f).coerceAtLeast(20f)
        var x = (plan.riderViewX % step).toFloat()
        while (x < plan.viewWidth) { c.drawLine(x, 0f, x, plan.viewHeight.toFloat(), grid); x += step }
        var y = (plan.riderViewY % step).toFloat()
        while (y < plan.viewHeight) { c.drawLine(0f, y, plan.viewWidth.toFloat(), y, grid); y += step }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (pt in plan.tiles) {
            val png = tiles[pt.tile] ?: continue
            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: continue
            try {
                val dst = RectF(pt.viewX.toFloat(), pt.viewY.toFloat(), (pt.viewX + pt.viewSize).toFloat(), (pt.viewY + pt.viewSize).toFloat())
                c.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), dst, paint)
            } finally {
                bmp.recycle()
            }
        }
        return out
    }

    fun drawOverlay(base: Bitmap, plan: TilePlan, o: RadarOverlay, dark: Boolean): Bitmap {
        val out = base.copy(Bitmap.Config.RGB_565, true)
        val c = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val fg = if (dark) Color.WHITE else Color.BLACK
        val s = minOf(w, h)

        // Fire perimeters (US, NIFC).
        if (o.fires.isNotEmpty()) {
            val fire = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 40, 20); style = Paint.Style.STROKE; strokeWidth = 2f }
            var budget = 4000
            for (f in o.fires) for (poly in f.polygons) for (ring in poly.rings) {
                if (budget <= 0) break
                val path = Path()
                ring.forEachIndexed { i, p ->
                    val v = plan.geoToView(p)
                    if (i == 0) path.moveTo(v.x.toFloat(), v.y.toFloat()) else path.lineTo(v.x.toFloat(), v.y.toFloat())
                }
                path.close()
                budget -= ring.size
                c.drawPath(path, fire)
            }
        }

        // Rider dot and heading tick.
        val cx = plan.riderViewX.toFloat()
        val cy = plan.riderViewY.toFloat()
        val r = (s * 0.035f).coerceAtLeast(3f)
        o.headingDeg?.let { hd ->
            val a = Math.toRadians(hd)
            val len = r * 4f
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; strokeWidth = (r * 0.6f).coerceAtLeast(2f); strokeCap = Paint.Cap.ROUND }
            c.drawLine(cx, cy, cx + (sin(a) * len).toFloat(), cy - (cos(a) * len).toFloat(), tick)
        }
        c.drawCircle(cx, cy, r + 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.BLACK else Color.WHITE })
        c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 120, 255) })

        // Wind arrow (north-up map: points where the wind blows to), top right.
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; textSize = (s * 0.075f).coerceIn(9f, 16f); isFakeBoldText = true }
        o.windFromDeg?.let { from ->
            val ax = w - s * 0.13f
            val ay = s * 0.15f
            val len = s * 0.1f
            val a = Math.toRadians(from + 180.0)
            val dx = (sin(a) * len).toFloat()
            val dy = (-cos(a) * len).toFloat()
            val ap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 140, 0); strokeWidth = (s * 0.02f).coerceAtLeast(2f); strokeCap = Paint.Cap.ROUND }
            c.drawLine(ax - dx, ay - dy, ax + dx, ay + dy, ap)
            val head = Path().apply {
                val hx = ax + dx; val hy = ay + dy
                val back = Math.toRadians(from + 180.0 + 180.0)
                val l = len * 0.6f
                moveTo(hx, hy)
                lineTo(hx + (sin(back + 0.5) * l).toFloat(), hy - (cos(back + 0.5) * l).toFloat())
                lineTo(hx + (sin(back - 0.5) * l).toFloat(), hy - (cos(back - 0.5) * l).toFloat())
                close()
            }
            c.drawPath(head, ap.apply { style = Paint.Style.FILL })
            o.windText?.let { t -> c.drawText(t, ax - text.measureText(t) / 2, ay + len + text.textSize + 2, text) }
        }

        // Labels: zoom/frame age (top left), attribution (bottom left, required by RainViewer).
        c.drawText(o.label, 3f, text.textSize + 1f, text)
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; textSize = (s * 0.055f).coerceIn(8f, 12f) }
        c.drawText(RainViewer.ATTRIBUTION, 3f, h - 3f, small)
        return out
    }

    /** Message-only image (no position yet, no data). */
    fun message(w: Int, h: Int, msg: String, dark: Boolean): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        c.drawColor(if (dark) Color.rgb(24, 26, 30) else Color.rgb(232, 234, 228))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.WHITE else Color.BLACK; textSize = (minOf(w, h) * 0.08f).coerceIn(9f, 18f) }
        c.drawText(msg, (w - p.measureText(msg)) / 2, h / 2f, p)
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.color; textSize = (minOf(w, h) * 0.055f).coerceIn(8f, 12f) }
        c.drawText(RainViewer.ATTRIBUTION, 3f, h - 3f, small)
        return out
    }
}

/** Draws the 2x1 rain bar field (WP14): bars for the next 2 h, resolution label, "rain in" text. */
object RainBarRenderer {
    fun draw(model: RainBarModel?, w: Int, h: Int, dark: Boolean, imperialPrecip: Boolean = false): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        val bg = if (dark) Color.BLACK else Color.WHITE
        val fg = if (dark) Color.WHITE else Color.BLACK
        c.drawColor(bg)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; textSize = (h * 0.16f).coerceIn(9f, 16f) }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = (h * 0.12f).coerceIn(8f, 13f) }
        if (model == null) {
            c.drawText("Rain: no data", 4f, h / 2f, text)
            return out
        }
        val top = text.textSize + 4f
        val bottom = h - small.textSize - 3f
        val chartH = (bottom - top).coerceAtLeast(4f)
        val axis = Paint().apply { color = Color.GRAY; strokeWidth = 1f }
        c.drawLine(0f, bottom, w.toFloat(), bottom, axis)
        val horizon = model.horizonMin.toFloat()
        // Scale: heavy rain (7.6 mm/h) fills the chart; light rain still shows.
        val full = maxOf(7.6, model.maxMmPerHour)
        for (b in model.bars) {
            val x0 = (b.startOffsetMin.coerceAtLeast(0) / horizon * w)
            val x1 = ((b.startOffsetMin + b.durationMin).coerceAtMost(model.horizonMin) / horizon * w)
            if (x1 <= x0 || b.mmPerHour <= 0.0) continue
            val frac = (Math.sqrt(b.mmPerHour / full)).coerceIn(0.06, 1.0).toFloat()
            val color = when (b.intensity) {
                RainIntensity.NONE -> Color.rgb(150, 190, 230)
                RainIntensity.LIGHT -> Color.rgb(60, 150, 230)
                RainIntensity.MODERATE -> Color.rgb(20, 90, 200)
                RainIntensity.HEAVY -> Color.rgb(140, 30, 160)
            }
            c.drawRect(x0, bottom - chartH * frac, maxOf(x1 - 1f, x0 + 1f), bottom, Paint().apply { this.color = color })
        }
        // Hour ticks.
        for (m in listOf(30, 60, 90)) {
            val x = m / horizon * w
            c.drawLine(x, bottom, x, bottom + 3f, axis)
            c.drawText("${m}'", x - small.measureText("${m}'") / 2, h - 2f, small)
        }
        val headline = when (val r = model.firstRainInMin) {
            null -> "Dry next ${model.horizonMin / 60} h"
            0 -> "Raining now"
            else -> "Rain in $r min"
        }
        c.drawText(headline, 4f, text.textSize, text)
        val res = model.resolutionLabel
        c.drawText(res, w - small.measureText(res) - 3f, small.textSize, small)
        val peak = if (imperialPrecip) String.format(java.util.Locale.US, "%.2f in/h", model.maxMmPerHour / 25.4) else String.format(java.util.Locale.US, "%.1f mm/h", model.maxMmPerHour)
        if (model.maxMmPerHour > 0) c.drawText(peak, w - small.measureText(peak) - 3f, small.textSize * 2 + 2, small)
        return out
    }
}
