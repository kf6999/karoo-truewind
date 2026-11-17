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

import de.timklge.karooheadwind.BuildConfig
import de.timklge.karooheadwind.pipeline.WindRepository
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import de.timklge.karooheadwind.HeadingResponse
import de.timklge.karooheadwind.HeadwindSettings
import de.timklge.karooheadwind.KarooHeadwindExtension
import de.timklge.karooheadwind.R
import de.timklge.karooheadwind.throttle
import de.timklge.karooheadwind.util.msInWindUnit
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.roundToInt

class WindDirectionAndSpeedDataType(
    private val windRepository: WindRepository,
    private val applicationContext: Context
) : DataTypeImpl(KarooHeadwindExtension.EXTENSION_ID, "windDirectionAndSpeed") {
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private val glance = GlanceRemoteViews()

    data class StreamData(val headingResponse: HeadingResponse,
                          val absoluteWindDirection: Double?,
                          val windSpeed: Double?,
                          val settings: HeadwindSettings,
                          val gustSpeed: Double?,
                          val isImperial: Boolean,
                          val isVisible: Boolean)

    private fun previewFlow(profileFlow: Flow<UserProfile>): Flow<StreamData> {
        return flow {
            val profile = profileFlow.first()

            while (true) {
                val bearing = (0..360).random().toDouble()
                val windSpeed = (0..10).random()
                val gustSpeed = windSpeed * ((10..20).random().toDouble() / 10)
                val isImperial = profile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL

                emit(StreamData(HeadingResponse.Value(bearing), bearing, windSpeed.toDouble(), HeadwindSettings(), gustSpeed = gustSpeed, isImperial = isImperial, isVisible = true))

                delay(2_000)
            }
        }
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        if (BuildConfig.DEBUG) Log.d(KarooHeadwindExtension.TAG, "Starting wind direction and speed view with $emitter")

        val baseBitmap = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.arrow_0
        )

        val configJob = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            awaitCancellation()
        }

        val flow = if (config.preview) {
            previewFlow(windRepository.sensorHub.userProfile)
        } else {
            combine(windRepository.relativeHeading,
                windRepository.headlineWeather,
                windRepository.settings,
                windRepository.sensorHub.userProfile,
                windRepository.sensorHub.isVisible(dataTypeId)
            ) { headingResponse, weatherData, settings, userProfile, isVisible ->
                val isImperial = userProfile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
                val absoluteWindDirection = weatherData?.windDirection
                val windSpeed = weatherData?.windSpeed
                val gustSpeed = weatherData?.windGusts

                StreamData(headingResponse, absoluteWindDirection, windSpeed, settings, isImperial = isImperial, gustSpeed = gustSpeed, isVisible = isVisible)
            }
        }

        val viewJob = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(ShowCustomStreamState("", null))

            val refreshRate = windRepository.refreshRateMs()
            flow.filter { it.isVisible }.throttle(refreshRate).combine(if (config.preview) kotlinx.coroutines.flow.flowOf(null) else windRepository.badgeFlow()) { sd, b -> sd to b }.collect { (streamData, badge) ->
                if (BuildConfig.DEBUG) Log.d(KarooHeadwindExtension.TAG, "Updating wind speed and direction view")

                val value = (streamData.headingResponse as? HeadingResponse.Value)?.diff
                if (value == null || streamData.absoluteWindDirection == null || streamData.windSpeed == null){
                    var headingResponse = streamData.headingResponse

                    if (headingResponse is HeadingResponse.Value && (streamData.absoluteWindDirection == null || streamData.windSpeed == null)){
                        headingResponse = HeadingResponse.NoWeatherData
                    }

                    emitter.updateViewCounted(dataTypeId, getErrorWidget(glance, context, streamData.settings, headingResponse).remoteViews)

                    return@collect
                }

                val windSpeed = streamData.windSpeed
                val windDirection = streamData.headingResponse.diff
                val windUnit = streamData.settings.getWindUnit(streamData.isImperial)

                val windSpeedUserUnit = msInWindUnit(windSpeed, windUnit)
                val gustSpeedUserUnit = msInWindUnit(streamData.gustSpeed ?: 0.0, windUnit)

                val mainText = let {
                    "${windSpeedUserUnit.roundToInt().absoluteValue}"
                }


                val subtext = "Max ${gustSpeedUserUnit.roundToInt()}"

                val headwindSpeed = cos( (windDirection + 180) * Math.PI / 180.0) * windSpeed
                val windSpeedInKmh = headwindSpeed * 3.6

                val result = glance.compose(context, DpSize.Unspecified) {
                    HeadwindDirection(
                        baseBitmap,
                        windDirection.roundToInt(),
                        config.textSize,
                        mainText,
                        subtext,
                        interpolateWindColor(windSpeedInKmh, false, context),
                        interpolateWindColor(windSpeedInKmh, true, context),
                        wideMode = config.gridSize.first == 60,
                        preview = config.preview,
                        badge = badge,
                        arrowSize = arrowSizeDp(config, context.resources.displayMetrics.density, config.gridSize.first == 60),
                    )
                }

                emitter.updateViewCounted(dataTypeId, result.remoteViews)
            }
        }
        emitter.setCancellable {
            if (BuildConfig.DEBUG) Log.d(KarooHeadwindExtension.TAG, "Stopping wind speed and direction view with $emitter")
            configJob.cancel()
            viewJob.cancel()
        }
    }
}
