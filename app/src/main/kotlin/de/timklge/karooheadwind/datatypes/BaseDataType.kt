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
import android.util.Log
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import de.timklge.karooheadwind.BuildConfig
import de.timklge.karooheadwind.HeadwindSettings
import de.timklge.karooheadwind.KarooHeadwindExtension
import de.timklge.karooheadwind.pipeline.WindRepository
import de.timklge.karooheadwind.throttle
import de.timklge.karooheadwind.weatherprovider.WeatherData
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
import kotlinx.coroutines.launch

/**
 * Numeric weather field. Maps the shared [WindRepository] state (TrueWind WP1: no per-field GPS,
 * settings or DataStore chain) and emits only when the value changes.
 */
abstract class BaseDataType(
    private val windRepository: WindRepository,
    dataTypeId: String
) : DataTypeImpl(KarooHeadwindExtension.EXTENSION_ID, dataTypeId) {
    abstract fun getValue(data: WeatherData, userProfile: UserProfile, settings: HeadwindSettings): Double?

    open fun getFormatDataType(): String? = null

    /** The value this field streams for the given inputs; null means "not available". */
    fun computeValue(data: WeatherData?, userProfile: UserProfile, settings: HeadwindSettings): Double? =
        data?.let { getValue(it, userProfile, settings) }

    override fun startStream(emitter: Emitter<StreamState>) {
        if (BuildConfig.DEBUG) Log.d(KarooHeadwindExtension.TAG, "start $dataTypeId stream")
        val job = CoroutineScope(Dispatchers.IO).launch {
            data class StreamData(val weatherData: WeatherData?, val userProfile: UserProfile, val settings: HeadwindSettings)

            val currentWeatherData = combine(windRepository.currentWeather, windRepository.sensorHub.userProfile, windRepository.settings) { weatherData, userProfile, settings ->
                StreamData(weatherData, userProfile, settings)
            }

            val refreshRate = windRepository.refreshRateMs()
            var lastEmitted: StreamState? = null

            currentWeatherData
                .throttle(refreshRate)
                .collect { (data, userProfile, settings) ->
                    val value = computeValue(data, userProfile, settings)

                    val state = if (value != null) {
                        StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to value)))
                    } else {
                        StreamState.NotAvailable
                    }

                    // Emit on change only (SPEC section 3, rule 4)
                    if (state != lastEmitted) {
                        lastEmitted = state
                        emitter.onNext(state)
                    }
            }
        }
        emitter.setCancellable {
            if (BuildConfig.DEBUG) Log.d(KarooHeadwindExtension.TAG, "stop $dataTypeId stream")
            job.cancel()
        }
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        if (BuildConfig.DEBUG) Log.d(KarooHeadwindExtension.TAG, "Starting $dataTypeId view with $emitter")

        if (getFormatDataType() != null){
            emitter.onNext(UpdateGraphicConfig(formatDataTypeId = getFormatDataType()))
        }
    }
}
