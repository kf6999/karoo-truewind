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
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentWidth
import androidx.glance.preview.ExperimentalGlancePreviewApi
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import de.timklge.karooheadwind.R
import de.timklge.karooheadwind.TemperatureUnit
import de.timklge.karooheadwind.weatherprovider.WeatherInterpretation
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.ceil

fun getShortDateFormatter(): DateTimeFormatter =
    DateTimeFormatter.ofPattern(de.timklge.karooheadwind.util.DisplayPrefs.shortDatePattern()).withZone(ZoneId.systemDefault())

fun getWeatherIcon(interpretation: WeatherInterpretation, isNight: Boolean): Int {
    return when (interpretation){
        WeatherInterpretation.CLEAR -> if (isNight) R.drawable.crescent_moon else R.drawable.sun
        WeatherInterpretation.PARTLY_CLOUDY -> if (isNight) R.drawable.partly_cloudy_night else R.drawable.partly_cloudy_day
        WeatherInterpretation.CLOUDY -> R.drawable.cloud
        WeatherInterpretation.FOG -> R.drawable.fog
        WeatherInterpretation.RAINY -> R.drawable.cloud_with_rain
        WeatherInterpretation.SNOWY -> R.drawable.cloud_with_snow
        WeatherInterpretation.DRIZZLE -> R.drawable.cloud_with_light_rain
        WeatherInterpretation.THUNDERSTORM -> R.drawable.cloud_with_lightning_and_rain
        WeatherInterpretation.UNKNOWN -> R.drawable.question_mark_regular_240
    }
}

/**
 * Rain text for a forecast column: probability when it is worth noting (>= 20 %), else the amount
 * when there is any, else null. [precipitation] is already in the user's unit (mm or in).
 */
fun rainLabel(precipitation: Double, precipitationProbability: Int?, isImperial: Boolean?): String? = when {
    precipitationProbability != null && precipitationProbability >= 20 -> "Rain ${precipitationProbability}%"
    precipitationProbability == null && precipitation >= (if (isImperial == true) 0.01 else 0.1) ->
        if (isImperial == true) String.format(Locale.US, "%.2f in", precipitation) else String.format(Locale.US, "%.1f mm", precipitation)
    else -> null
}

/** `12` or `12-18` when the gust is higher. */
fun windLabel(windSpeed: Int, windGusts: Int): String = if (windGusts > windSpeed) "$windSpeed-$windGusts" else "$windSpeed"

@OptIn(ExperimentalGlancePreviewApi::class)
@Composable
fun Weather(
    arrowBitmap: Bitmap,
    current: WeatherInterpretation,
    windBearing: Int,
    windSpeed: Int,
    windGusts: Int,
    precipitation: Double,
    precipitationProbability: Int?,
    temperature: Int,
    temperatureUnit: TemperatureUnit,
    distance: Double? = null,
    timeLabel: String? = null,
    rowAlignment: Alignment.Horizontal = Alignment.Horizontal.CenterHorizontally,
    dateLabel: String? = null,
    singleDisplay: Boolean = false,
    isImperial: Boolean?,
    isNight: Boolean,
    /** Column width in the forecast strip; null = 86 dp. */
    columnWidthDp: Int? = null,
) {

    val fontSize = if (singleDisplay) 19f else 16f
    val textColor = ColorProvider(Color.Black, Color.White)
    val rainColor = ColorProvider(Color(0xFF1565C0), Color(0xFF64B5F6))
    val size = TextUnit(fontSize, TextUnitType.Sp)
    val smallSize = TextUnit(fontSize - 2f, TextUnitType.Sp)

    Column(modifier = if (singleDisplay) GlanceModifier.fillMaxSize().padding(1.dp) else GlanceModifier.fillMaxHeight().padding(1.dp).width((columnWidthDp ?: 86).dp), horizontalAlignment = rowAlignment) {
        Row(modifier = GlanceModifier.defaultWeight().wrapContentWidth(), horizontalAlignment = rowAlignment, verticalAlignment = Alignment.CenterVertically) {
            Image(
                modifier = GlanceModifier.defaultWeight().wrapContentWidth().padding(1.dp),
                provider = ImageProvider(getWeatherIcon(current, isNight)),
                contentDescription = "Current weather information",
                contentScale = ContentScale.Fit,
            )
        }

        if (dateLabel != null && !singleDisplay){
            Text(text = dateLabel, style = TextStyle(color = textColor, fontWeight = FontWeight.Medium, fontSize = smallSize))
        }

        if (distance != null && !singleDisplay && isImperial != null){
            val distanceInUserUnit = (distance / (if(!isImperial) 1000.0 else 1609.34)).toInt()
            val label = "${distanceInUserUnit.absoluteValue} ${if(!isImperial) "km" else "mi"}"
            val text = if(distanceInUserUnit > 0) "In $label" else "$label ago"

            if (distanceInUserUnit != 0){
                Text(text = text, style = TextStyle(color = textColor, fontWeight = FontWeight.Medium, fontSize = smallSize))
            }
        }

        // Time and temperature: the two things read first.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalAlignment = rowAlignment) {
            if (dateLabel != null && singleDisplay){
                Text(text = dateLabel, style = TextStyle(color = textColor, fontWeight = FontWeight.Medium, fontSize = size))
                Spacer(modifier = GlanceModifier.width(5.dp))
            }
            if (timeLabel != null){
                Text(text = timeLabel, style = TextStyle(color = textColor, fontWeight = FontWeight.Medium, fontSize = size))
                Spacer(modifier = GlanceModifier.width(5.dp))
            }
            Text(
                text = "${temperature}${temperatureUnit.unitDisplay}",
                style = TextStyle(color = textColor, fontWeight = FontWeight.Bold, fontSize = size, textAlign = TextAlign.Center)
            )
        }

        // Wind: arrow (direction the wind blows to) and speed-gust.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalAlignment = rowAlignment) {
            Image(
                modifier = if (singleDisplay) GlanceModifier.height(20.dp).width(18.dp) else GlanceModifier.height(18.dp).width(16.dp).padding(1.dp),
                provider = ImageProvider(getArrowBitmapByBearing(arrowBitmap, windBearing + 180)),
                contentDescription = "Current wind direction",
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(textColor)
            )
            Text(text = windLabel(windSpeed, windGusts), style = TextStyle(color = textColor, fontWeight = FontWeight.Bold, fontSize = size))
        }

        // Rain only when there is some; a blank line keeps the columns aligned.
        val rain = rainLabel(precipitation, precipitationProbability, isImperial)
        if (rain != null || !singleDisplay) {
            Text(text = rain ?: " ", maxLines = 1, style = TextStyle(color = rainColor, fontWeight = FontWeight.Bold, fontSize = smallSize))
        }
    }
}
