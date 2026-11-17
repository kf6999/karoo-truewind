/*
 * Copyright 2024-2026 karoo-headwind contributors
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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.background
import androidx.glance.appwidget.cornerRadius
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import de.timklge.karooheadwind.MainActivity
import kotlin.math.roundToInt

/** TrueWind confidence styling of the arrow (SPEC 5): solid / dashed / dotted for high / medium / low. */
enum class ArrowStyle { SOLID, DASHED, DOTTED }

/**
 * Cuts the arrow into dashes (radial gaps) or dots (a hole grid) so its outline reads as dashed /
 * dotted. Works on any opaque arrow bitmap.
 */
private fun applyArrowStyle(bitmap: Bitmap, style: ArrowStyle) {
    if (style == ArrowStyle.SOLID) return
    val canvas = Canvas(bitmap)
    val clear = Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        isAntiAlias = false
    }
    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()
    when (style) {
        ArrowStyle.DASHED -> {
            // Horizontal gaps every 16 px (4 px wide): a dashed look in any rotation.
            var y = 0f
            while (y < h) { canvas.drawRect(0f, y + 12f, w, y + 16f, clear); y += 16f }
        }
        ArrowStyle.DOTTED -> {
            // Keep 6x6 px dots on a 10 px grid.
            var y = 0f
            while (y < h) { canvas.drawRect(0f, y + 6f, w, y + 10f, clear); y += 10f }
            var x = 0f
            while (x < w) { canvas.drawRect(x + 6f, 0f, x + 10f, h, clear); x += 10f }
        }
        ArrowStyle.SOLID -> Unit
    }
}

fun getArrowBitmapByBearing(baseBitmap: Bitmap, bearing: Int, style: ArrowStyle): Bitmap =
    getArrowBitmapByBearing(baseBitmap, bearing).also { applyArrowStyle(it, style) }

fun getArrowBitmapByBearing(baseBitmap: Bitmap, bearing: Int): Bitmap {
    val bearingRounded = (((bearing + 360) / 10.0).roundToInt() * 10) % 360

    val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint().apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    canvas.save()
    canvas.scale((bitmap.width / baseBitmap.width.toFloat()), (bitmap.height / baseBitmap.height.toFloat()), (bitmap.width / 2).toFloat(), (bitmap.height / 2).toFloat())
    canvas.rotate(bearingRounded.toFloat(), (bitmap.width / 2).toFloat(), (bitmap.height / 2).toFloat())
    canvas.drawBitmap(baseBitmap, ((bitmap.width - baseBitmap.width) / 2).toFloat(), ((bitmap.height - baseBitmap.height) / 2).toFloat(), paint)
    canvas.restore()

    return bitmap
}

/**
 * Arrow size for the split layouts, in dp, from the field's real size: the arrow grows with the
 * field instead of staying 40-50 dp. Null when the view size is unknown (preview).
 */
fun arrowSizeDp(config: io.hammerhead.karooext.models.ViewConfig, density: Float, wide: Boolean): Int? {
    val (w, h) = config.viewSize
    if (w <= 0 || h <= 0 || density <= 0f) return null
    val px = if (wide) minOf(w * 0.5f, h * 0.62f) else minOf(w * 0.42f, h * 0.9f)
    return (px / density - 10f).toInt().coerceIn(32, 160)
}

/**
 * Head/tail colour for the arrow: green tailwind, neutral cross/light, orange to red headwind
 * (same scale as the tailwind field). [headwindMs] is + into the rider.
 */
fun headwindArrowColor(headwindMs: Double, context: android.content.Context): androidx.glance.unit.ColorProvider =
    ColorProvider(interpolateWindColor(headwindMs * 3.6, false, context), interpolateWindColor(headwindMs * 3.6, true, context))

@Composable
fun HeadwindDirection(
    baseBitmap: Bitmap, bearing: Int, fontSize: Int,
    overlayText: String, overlaySubText: String? = null,
    nightColor: Color = Color.Black, dayColor: Color = Color.White, preview: Boolean = false,
    wideMode: Boolean,
    arrowStyle: ArrowStyle = ArrowStyle.SOLID,
    /** STALE data: arrow drawn grey (SPEC 5). */
    grey: Boolean = false,
    /** Arrow colour (e.g. [headwindArrowColor]); null = plain black/white. */
    arrowColor: androidx.glance.unit.ColorProvider? = null,
    /** Corner status badge ([FieldStatus.badge]). */
    badge: String? = null,
    /** Arrow size in the split layouts ([arrowSizeDp]); null = the old fixed size. */
    arrowSize: Int? = null,
) {
    val baseModifier = GlanceModifier.fillMaxSize().padding(5.dp).background(dayColor, nightColor).cornerRadius(10.dp)
    val arrowTint = when {
        grey -> ColorProvider(Color.Gray, Color.Gray)
        arrowColor != null -> arrowColor
        else -> ColorProvider(Color.Black, Color.White)
    }
    val textColor = ColorProvider(Color.Black, Color.White)
    val subSize = maxOf(0.4 * fontSize, 13.0)

    Box(
        modifier = if (!preview) baseModifier.clickable(actionStartActivity<MainActivity>()) else baseModifier,
        contentAlignment = Alignment(
            vertical = Alignment.Vertical.CenterVertically,
            horizontal = Alignment.Horizontal.CenterHorizontally,
        ),
    ) {
        if (overlayText.isNotEmpty()){
            if (overlaySubText == null){
                Image(
                    modifier = GlanceModifier.fillMaxSize(),
                    provider = ImageProvider(getArrowBitmapByBearing(baseBitmap, bearing, arrowStyle)),
                    contentDescription = "Relative wind direction indicator",
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(arrowTint)
                )

                Text(
                    overlayText,
                    maxLines = 1,
                    style = TextStyle(color = textColor, fontSize = (0.6 * fontSize).sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.padding(1.dp)
                )
            } else {
                if (wideMode){
                    Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(modifier = GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = GlanceModifier.size((arrowSize ?: 50).dp)) {
                                Image(
                                    provider = ImageProvider(getArrowBitmapByBearing(baseBitmap, bearing)),
                                    contentDescription = "Relative wind direction indicator",
                                    contentScale = ContentScale.Fit,
                                    colorFilter = ColorFilter.tint(arrowTint)
                                )
                            }

                            Text(
                                overlaySubText,
                                maxLines = 1,
                                style = TextStyle(color = textColor, fontSize = subSize.sp, fontWeight = FontWeight.Medium),
                                modifier = GlanceModifier.padding(1.dp)
                            )
                        }

                        Column(modifier = GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                overlayText,
                                maxLines = 1,
                                modifier = GlanceModifier.padding(5.dp),
                                style = TextStyle(color = textColor, fontSize = (0.6 * fontSize).sp, fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                } else {
                    Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = GlanceModifier.size((arrowSize ?: 40).dp)) {
                                Image(
                                    provider = ImageProvider(getArrowBitmapByBearing(baseBitmap, bearing)),
                                    contentDescription = "Relative wind direction indicator",
                                    contentScale = ContentScale.Fit,
                                    colorFilter = ColorFilter.tint(arrowTint)
                                )
                            }
                        }

                        Column(modifier = GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
                            Text(
                                overlayText,
                                maxLines = 1,
                                style = TextStyle(color = textColor, fontSize = (0.65 * fontSize).sp, fontWeight = FontWeight.Bold),
                                modifier = GlanceModifier.padding(1.dp)
                            )

                            Row(){
                                Text(
                                    overlaySubText,
                                    maxLines = 1,
                                    style = TextStyle(color = textColor, fontSize = subSize.sp, fontWeight = FontWeight.Medium),
                                    modifier = GlanceModifier.padding(1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        StatusBadge(badge)
    }
}
