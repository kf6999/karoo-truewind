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
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import de.timklge.karooheadwind.HeadingResponse
import de.timklge.karooheadwind.KarooHeadwindExtension
import de.timklge.karooheadwind.MainActivity
import de.timklge.karooheadwind.R
import de.timklge.karooheadwind.TemperatureUnit
import de.timklge.karooheadwind.WindUnit
import de.timklge.karooheadwind.pipeline.Diagnostics
import de.timklge.karooheadwind.pipeline.WindExtras
import de.timklge.karooheadwind.pipeline.WindRepository
import de.timklge.karooheadwind.pipeline.WindSnapshot
import de.timklge.karooheadwind.throttle
import de.timklge.karooheadwind.util.celciusInUserUnit
import de.timklge.karooheadwind.util.msInWindUnit
import io.github.kf6999.truewind.core.wind.RelativeWind
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "Ride weather": one glance for wind (arrow coloured head/tail + `12 head`), temperature
 * (with feels-like when it differs), and when rain starts.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class RideWeatherDataType(private val windRepository: WindRepository) : DataTypeImpl(KarooHeadwindExtension.EXTENSION_ID, TYPE_ID) {
    private val glance = GlanceRemoteViews()

    data class Model(
        /** Arrow bearing (karoo-headwind convention, 0 = tailwind). */
        val bearing: Int,
        val headwindMs: Double,
        val windText: String,
        val tempText: String?,
        val feelsText: String?,
        val rainText: String?,
        val badge: String?,
    )

    companion object {
        const val TYPE_ID = "rideWeather"

        fun model(s: WindSnapshot, windUnit: WindUnit, imperialTemp: Boolean): Model? {
            val bearing = (s.relativeHeadingResponse() as? HeadingResponse.Value)?.diff ?: return null
            val speed = s.headline?.speed ?: return null
            val head = RelativeWind.headwind(bearing, speed)
            val windText = when {
                speed < 0.5 -> "calm"
                abs(head) < 0.35 * speed -> "${msInWindUnit(speed, windUnit).roundToInt()} cross"
                head > 0 -> "${msInWindUnit(head, windUnit).roundToInt()} head"
                else -> "${msInWindUnit(-head, windUnit).roundToInt()} tail"
            }
            val unit = if (imperialTemp) TemperatureUnit.FAHRENHEIT else TemperatureUnit.CELSIUS
            val tC = s.state.weather?.temperatureC
            val temp = tC?.let { "${celciusInUserUnit(it, imperialTemp).roundToInt()}${unit.unitDisplay}" }
            val feels = s.extras.feelsLikeC?.takeIf { tC != null && abs(it - tC) >= 2.0 }
                ?.let { "feels ${celciusInUserUnit(it, imperialTemp).roundToInt()}°" }
            return Model(bearing.roundToInt(), head, windText, temp, feels, rainText(s.extras.rainInMin), FieldStatus.badge(s))
        }

        /** `Rain now`, `Rain in 25 min`, `Dry 2 h`, or null when unknown. */
        fun rainText(rainInMin: Int?): String? = when {
            rainInMin == null -> "Dry 2 h"
            rainInMin == WindExtras.RAIN_UNKNOWN -> null
            rainInMin <= 0 -> "Rain now"
            else -> "Rain in $rainInMin min"
        }
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            windRepository.readySnapshot.map { it.state.confidence.toDouble() }.distinctUntilChanged().collect {
                Diagnostics.countEmit(dataTypeId)
                emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to it))))
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val arrow = BitmapFactory.decodeResource(context.resources, R.drawable.arrow_0)
        val configJob = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            awaitCancellation()
        }
        val viewJob = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(ShowCustomStreamState("", null))
            val models = if (config.preview) {
                flowOf(Model(20, 3.0, "11 head", "64°F", "feels 60°", "Rain in 40 min", null) to true)
            } else {
                combine(windRepository.readySnapshot, windRepository.settings, windRepository.sensorHub.userProfile, windRepository.sensorHub.isVisible(dataTypeId)) { s, settings, profile, visible ->
                    val imperial = profile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
                    val imperialTemp = profile.preferredUnit.temperature == UserProfile.PreferredUnit.UnitType.IMPERIAL
                    model(s, settings.getWindUnit(imperial), imperialTemp) to visible
                }
            }
            val arrowSize = arrowSizeDp(config, context.resources.displayMetrics.density, wide = false)
            models.filter { it.second }.distinctUntilChanged().throttle(1_000L).collect { (m, _) ->
                if (m == null) {
                    emitter.updateViewCounted(dataTypeId, getErrorWidget(glance, context, HeadwindDirectionDataType.ERROR_NO_WEATHER_DATA).remoteViews)
                    return@collect
                }
                val textColor = ColorProvider(Color.Black, Color.White)
                val big = (config.textSize * 0.6f).coerceAtLeast(18f).sp
                val small = (config.textSize * 0.4f).coerceAtLeast(13f).sp
                val result = glance.compose(context, DpSize.Unspecified) {
                    val base = GlanceModifier.fillMaxSize().padding(4.dp)
                    Box(modifier = if (config.preview) base else base.clickable(actionStartActivity<MainActivity>())) {
                        Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    provider = ImageProvider(getArrowBitmapByBearing(arrow, m.bearing)),
                                    contentDescription = "Relative wind direction",
                                    contentScale = ContentScale.Fit,
                                    colorFilter = ColorFilter.tint(headwindArrowColor(m.headwindMs, context)),
                                    modifier = GlanceModifier.size(((arrowSize ?: 48) * 0.75f).toInt().coerceAtLeast(28).dp),
                                )
                                Text(m.windText, maxLines = 1, style = TextStyle(color = textColor, fontSize = small, fontWeight = FontWeight.Bold))
                            }
                            Column(modifier = GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                                m.tempText?.let { Text(it, maxLines = 1, style = TextStyle(color = textColor, fontSize = big, fontWeight = FontWeight.Bold)) }
                                m.feelsText?.let { Text(it, maxLines = 1, style = TextStyle(color = textColor, fontSize = small, fontWeight = FontWeight.Medium)) }
                                m.rainText?.let {
                                    Text(it, maxLines = 1, style = TextStyle(color = ColorProvider(Color(0xFF1565C0), Color(0xFF64B5F6)), fontSize = small, fontWeight = FontWeight.Bold))
                                }
                            }
                        }
                        StatusBadge(m.badge)
                    }
                }
                emitter.updateViewCounted(dataTypeId, result.remoteViews)
            }
        }
        emitter.setCancellable {
            configJob.cancel()
            viewJob.cancel()
        }
    }
}
