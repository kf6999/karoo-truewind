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
import android.util.Log
import androidx.compose.ui.unit.DpSize
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import de.timklge.karooheadwind.HeadingResponse
import de.timklge.karooheadwind.KarooHeadwindExtension
import de.timklge.karooheadwind.datatypes.RelativeGradeDataType.Companion.DEFAULT_BIKE_WEIGHT
import de.timklge.karooheadwind.screens.BarChartBuilder
import de.timklge.karooheadwind.throttle
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ResistanceForcesDataType(private val windRepository: WindRepository) : DataTypeImpl(KarooHeadwindExtension.EXTENSION_ID, "forces"){
    private fun previewFlow(): Flow<RelativeGradeDataType.ResistanceForces> {
        return flow {
            while (true) {
                val withoutWind = (0..300).random().toDouble()
                emit(RelativeGradeDataType.ResistanceForces(
                    withoutWind,
                    withoutWind + (0..200).random().toDouble(),
                    (0..50).random().toDouble(),
                    (-100..500).random().toDouble()
                ))
                delay(1_000)
            }
        }
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val configJob = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            awaitCancellation()
        }

        val viewJob = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(ShowCustomStreamState("", null))

            val flow = if (config.preview) {
                previewFlow()
            } else {
                val relativeWindDirectionFlow = windRepository.relativeHeading.filterIsInstance<HeadingResponse.Value>().map { it.diff + 180 }
                val speedFlow = windRepository.sensorHub.stream(DataType.Type.SMOOTHED_3S_AVERAGE_SPEED).filterIsInstance<StreamState.Streaming>().map { it.dataPoint.singleValue ?: 0.0 }
                val actualGradeFlow = windRepository.sensorHub.stream(DataType.Type.ELEVATION_GRADE).filterIsInstance<StreamState.Streaming>().map { it.dataPoint.singleValue }.filterNotNull().map { it / 100.0 } // Convert to decimal grade
                val totalMassFlow = windRepository.sensorHub.userProfile.map {
                    if (it.weight in 30.0f..300.0f){
                        it.weight
                    } else {
                        Log.w(KarooHeadwindExtension.TAG, "Invalid rider weight ${it.weight} kg, defaulting to 70 kg")
                        70.0f // Default to 70 kg if weight is invalid
                    }.toDouble()
                }.combine(windRepository.physicsParams) { riderMass, params -> riderMass to params }

                val refreshRate = windRepository.refreshRateMs()

                val windSpeedFlow = windRepository.headlineWeather.filterNotNull().map { weatherData ->
                    weatherData.windSpeed
                }

                data class StreamValues(
                    val relativeWindDirection: Double,
                    val speed: Double,
                    val windSpeed: Double,
                    val actualGrade: Double,
                    val totalMass: Pair<Double, de.timklge.karooheadwind.pipeline.PhysicsParams>
                )

                combine(relativeWindDirectionFlow, speedFlow, windSpeedFlow, actualGradeFlow, totalMassFlow) { windDirection, speed, windSpeed, actualGrade, totalMass ->
                    StreamValues(windDirection, speed, windSpeed, actualGrade, totalMass)
                }.distinctUntilChanged().throttle(refreshRate).map { (windDirection, speed, windSpeed, actualGrade, totalMass) ->
                    // TrueWind: live air density and configured CdA/Crr/bike weight (upstream constants in upstream mode)
                    val resistanceForces = de.timklge.karooheadwind.pipeline.Physics.forces(
                        actualGrade, speed, windSpeed, windDirection, totalMass.first, totalMass.second,
                    )

                    if (BuildConfig.DEBUG) Log.d(KarooHeadwindExtension.TAG, "Resistance Forces: $resistanceForces")

                    resistanceForces
                }
            }

            val refreshRate = windRepository.refreshRateMs()

            flow.throttle(refreshRate).collect { resistanceForces ->
                if (resistanceForces != null) {
                    // Create bar chart data
                    val bars = listOf(
                        BarChartBuilder.BarData(
                            value = resistanceForces.airResistanceWithoutWind,
                            label = "Air",
                            smallLabel = "Air",
                            color = 0xFF4CAF50.toInt() // Green
                        ),
                        BarChartBuilder.BarData(
                            value = resistanceForces.airResistanceWithWind - resistanceForces.airResistanceWithoutWind,
                            label = "Wind",
                            smallLabel = "Wind",
                            color = 0xFF2196F3.toInt() // Blue
                        ),
                        BarChartBuilder.BarData(
                            value = resistanceForces.rollingResistance,
                            label = "Roll",
                            smallLabel = "R",
                            color = 0xFFFF9800.toInt() // Orange
                        ),
                        BarChartBuilder.BarData(
                            value = resistanceForces.gravitationalForce,
                            label = "Gravity",
                            smallLabel = "G",
                            color = 0xFFF44336.toInt() // Red
                        )
                    )

                    // Draw bar chart
                    val bitmap = BarChartBuilder(context).drawBarChart(
                        width = config.viewSize.first,
                        height = config.viewSize.second,
                        bars = bars,
                        small = config.gridSize.first <= 30
                    )

                    // Use the correct ViewEmitter pattern with glance.compose
                    val glance = GlanceRemoteViews()
                    val result = glance.compose(context, DpSize.Unspecified) {
                        Box(modifier = GlanceModifier.fillMaxSize()) {
                            Image(
                                ImageProvider(bitmap),
                                "Resistance Forces Bar Chart",
                                modifier = GlanceModifier.fillMaxSize()
                            )
                        }
                    }
                    emitter.updateViewCounted(dataTypeId, result.remoteViews)
                } else {
                    // Display error message when no resistance forces data
                    emitter.onNext(ShowCustomStreamState("No resistance data", null))
                }
            }
        }

        emitter.setCancellable {
            configJob.cancel()
            viewJob.cancel()
        }
    }
}