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

import android.content.Context
import de.timklge.karooheadwind.HeadingResponse
import de.timklge.karooheadwind.HeadwindSettings
import de.timklge.karooheadwind.KarooHeadwindExtension
import de.timklge.karooheadwind.pipeline.WindRepository
import de.timklge.karooheadwind.throttle
import de.timklge.karooheadwind.util.msInWindUnit
import de.timklge.karooheadwind.weatherprovider.WeatherData
import io.github.kf6999.truewind.core.wind.RelativeWind
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class HeadwindSpeedDataType(
    private val windRepository: WindRepository,
) : DataTypeImpl(KarooHeadwindExtension.EXTENSION_ID, "headwindSpeed"){

    data class StreamData(val headingResponse: HeadingResponse, val weatherData: WeatherData?, val settings: HeadwindSettings, val isImperial: Boolean)

    companion object {
        /** Headwind speed in the user's wind unit, or null when not available. */
        fun computeValue(streamData: StreamData): Double? {
            val heading = (streamData.headingResponse as? HeadingResponse.Value)?.diff
            val windSpeed = streamData.weatherData?.windSpeed
            val errorCode = HeadwindDirectionDataType.getErrorCode(streamData.headingResponse, streamData.settings)

            if (errorCode != null || heading == null || windSpeed == null) return null

            val headwindSpeed = RelativeWind.headwind(heading, windSpeed)
            return msInWindUnit(headwindSpeed, streamData.settings.getWindUnit(streamData.isImperial))
        }
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            val refreshRate = windRepository.refreshRateMs()
            val gate = EmitGate(EmitGate.SPEED_THRESHOLD)

            combine(windRepository.relativeHeadingAndWeather, windRepository.settings, windRepository.sensorHub.userProfile) { (value, data), settings, userProfile ->
                StreamData(value, data, settings, userProfile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL)
            }.throttle(refreshRate)
                .collect { streamData ->
                    val value = computeValue(streamData)
                    // Emit when the displayed value moves by >= 0.5 unit (SPEC 3 rule 4)
                    if (gate.shouldEmit(value)) {
                        de.timklge.karooheadwind.pipeline.Diagnostics.countEmit(dataTypeId)
                        emitter.onNext(value?.let { StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to it))) }
                            ?: StreamState.NotAvailable)
                    }
                }
        }

        emitter.setCancellable {
            job.cancel()
        }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(formatDataTypeId = DataType.Type.INTENSITY_FACTOR))
    }
}
